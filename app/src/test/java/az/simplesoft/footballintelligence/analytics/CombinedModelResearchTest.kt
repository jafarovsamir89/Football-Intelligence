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
        assertEquals(9, report.validationCandidates.size)
        val candidate = requireNotNull(report.validationCandidate)
        assertEquals(0.5, candidate.eloWeight, 1e-12)
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
    fun tiedSimpleModelsFailImprovementGateEvenWithEnoughRows() {
        val sameModel = OutcomeProbabilities(0.45, 0.30, 0.25)
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
    fun probabilityBlendRemainsNormalizedAndRejectsInvalidWeights() {
        val blended = CombinedModelResearch.blend(elo, poisson, eloWeight = 0.25)

        assertEquals(1.0, blended.values().sum(), 1e-12)
        assertEquals(0.25, blended.home, 1e-12)
        assertEquals(0.3375, blended.draw, 1e-12)
        assertEquals(0.4125, blended.away, 1e-12)
        assertFalse(runCatching { CombinedModelResearch.blend(elo, poisson, -0.1) }.isSuccess)
    }
}
