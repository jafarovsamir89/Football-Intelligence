package az.simplesoft.footballintelligence.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedModelResearchTest {
    private val elo = OutcomeProbabilities(home = 0.70, draw = 0.15, away = 0.15)
    private val poisson = OutcomeProbabilities(home = 0.10, draw = 0.40, away = 0.50)

    private fun validationRows(): List<PairedModelForecast> = buildList {
        repeat(200) { add(PairedModelForecast(elo, poisson, OutcomeProbabilities(1.0, 0.0, 0.0))) }
        repeat(150) { add(PairedModelForecast(elo, poisson, OutcomeProbabilities(0.0, 1.0, 0.0))) }
        repeat(150) { add(PairedModelForecast(elo, poisson, OutcomeProbabilities(0.0, 0.0, 1.0))) }
    }

    @Test
    fun validationSelectsAWeightedBlendOnlyWhenItBeatsBothBaselinesOnBothScores() {
        val report = CombinedModelResearch.evaluate(
            validation = validationRows(),
            holdout = validationRows(),
        )

        assertEquals(500, report.validationPairedForecastCount)
        assertEquals(500, report.minimumValidationForecasts)
        assertEquals(
            CombinedModelResearch.ELO_WEIGHT_GRID.size * CombinedModelResearch.TEMPERATURE_GRID.size,
            report.validationCandidates.size,
        )
        val candidate = requireNotNull(report.validationCandidate)
        assertTrue(CombinedModelResearch.ELO_WEIGHT_GRID.contains(candidate.eloWeight))
        assertTrue(CombinedModelResearch.TEMPERATURE_GRID.contains(candidate.temperature))
        assertTrue(report.validationGatePassed)
        assertTrue(
            candidate.metrics.meanBrierScore!! <
                report.validationEloMetrics.meanBrierScore!!,
        )
        assertTrue(
            candidate.metrics.meanBrierScore!! <
                report.validationPoissonMetrics.meanBrierScore!!,
        )
        assertEquals(500, report.holdoutPairedForecastCount)
        assertNotNull(report.holdoutMetrics?.meanLogLoss)
        assertTrue(report.holdoutGatePassed)
        assertEquals(500, report.validationCalibration.forecastCount)
        assertEquals(500, report.holdoutCalibration.forecastCount)
        assertTrue(report.holdoutCalibration.blendMeanOneVsRestEce!! in 0.0..1.0)
    }

    @Test
    fun insufficientValidationSampleDoesNotSelectWeightOrScoreHoldout() {
        val report = CombinedModelResearch.evaluate(
            validation = validationRows().take(499),
            holdout = validationRows().take(5),
        )

        assertEquals(499, report.validationPairedForecastCount)
        assertNull(report.validationCandidate)
        assertFalse(report.validationGatePassed)
        assertEquals(0, report.holdoutPairedForecastCount)
        assertNull(report.holdoutMetrics)
    }

    @Test
    fun alreadyCalibratedTiedSimpleModelsFailImprovementGateEvenWithEnoughRows() {
        // The outcome rates in validationRows are 40% / 30% / 30%; temperature
        // scaling should not create a false improvement when both inputs already match them.
        val sameModel = OutcomeProbabilities(0.40, 0.30, 0.30)
        val rows = validationRows().map { it.copy(elo = sameModel, poisson = sameModel) }
        val report = CombinedModelResearch.evaluate(rows, holdout = rows.take(20))

        assertNotNull(report.validationCandidate)
        assertFalse(report.validationGatePassed)
        assertNotNull(report.holdoutMetrics)
    }

    @Test
    fun holdoutMustIndependentlyClearTheSameSampleAndImprovementGates() {
        val report = CombinedModelResearch.evaluate(
            validation = validationRows(),
            holdout = validationRows().take(499),
        )

        assertTrue(report.validationGatePassed)
        assertEquals(499, report.holdoutPairedForecastCount)
        assertFalse(report.holdoutGatePassed)
        assertNotNull(report.holdoutMetrics)
    }

    @Test
    fun validationPassCannotPromoteBlendWhenHoldoutFavorsOneComponent() {
        val allHomeHoldout = List(500) {
            PairedModelForecast(elo, poisson, OutcomeProbabilities(1.0, 0.0, 0.0))
        }
        val report = CombinedModelResearch.evaluate(
            validation = validationRows(),
            holdout = allHomeHoldout,
        )

        assertTrue(report.validationGatePassed)
        assertFalse(report.holdoutGatePassed)
        assertTrue(report.holdoutEloMetrics.meanLogLoss!! < report.holdoutMetrics!!.meanLogLoss!!)
    }

    @Test
    fun probabilityBlendRemainsNormalizedAndRejectsInvalidWeights() {
        val blended = CombinedModelResearch.blend(elo, poisson, eloWeight = 0.25)

        assertEquals(1.0, blended.values().sum(), 1e-12)
        assertEquals(0.25, blended.home, 1e-12)
        assertEquals(0.3375, blended.draw, 1e-12)
        assertEquals(0.4125, blended.away, 1e-12)
        assertFalse(runCatching { CombinedModelResearch.blend(elo, poisson, -0.1) }.isSuccess)

        val softened = CombinedModelResearch.temperatureScale(elo, temperature = 2.0)
        val sharpened = CombinedModelResearch.temperatureScale(elo, temperature = 0.5)
        assertEquals(1.0, softened.values().sum(), 1e-12)
        assertEquals(1.0, sharpened.values().sum(), 1e-12)
        assertTrue(softened.home < elo.home)
        assertTrue(sharpened.home > elo.home)
        assertFalse(runCatching { CombinedModelResearch.temperatureScale(elo, 0.0) }.isSuccess)
    }
}
