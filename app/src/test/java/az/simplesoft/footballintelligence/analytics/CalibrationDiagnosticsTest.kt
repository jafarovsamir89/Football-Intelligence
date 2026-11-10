package az.simplesoft.footballintelligence.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationDiagnosticsTest {
    @Test fun equalWidthOneVsRestBinsReportMeanProbabilityAndObservedFrequency() {
        val report = CalibrationDiagnostics.evaluate(
            listOf(
                CalibrationObservation(OutcomeProbabilities(.86, .05, .09), ForecastOutcome.HOME),
                CalibrationObservation(OutcomeProbabilities(.84, .1, .06), ForecastOutcome.AWAY),
            ),
            binCount = 10,
        )

        assertEquals(2, report.predictionCount)
        assertEquals(3, report.outcomes.size)
        val home = report.outcomes.single { it.outcome == ForecastOutcome.HOME }
        assertEquals(2, home.sampleCount)
        assertEquals(1, home.occupiedBins.size)
        assertEquals(8, home.occupiedBins.single().index)
        assertEquals(.85, home.occupiedBins.single().meanPredictedProbability, 1e-12)
        assertEquals(.5, home.occupiedBins.single().observedFrequency, 1e-12)
        assertEquals(.35, home.expectedCalibrationError!!, 1e-12)
        assertEquals(.2833333333333333, report.meanOneVsRestEce!!, 1e-12)
    }

    @Test fun probabilityOneIsIncludedInTheFinalBinAndEmptyInputsHaveNoEce() {
        val certain = CalibrationDiagnostics.evaluate(
            listOf(CalibrationObservation(OutcomeProbabilities(1.0, 0.0, 0.0), ForecastOutcome.HOME)),
            binCount = 4,
        )
        assertEquals(3, certain.outcomes.first().occupiedBins.single().index)
        assertEquals(1, certain.outcomes.first().occupiedBins.single().sampleCount)
        assertTrue(certain.outcomes.first().occupiedBins.single().includesUpperBound)
        assertEquals(1.0, certain.outcomes.first().occupiedBins.single().observedFrequency, 0.0)

        val empty = CalibrationDiagnostics.evaluate(emptyList())
        assertEquals(0, empty.predictionCount)
        assertNull(empty.meanOneVsRestEce)
        assertTrue(empty.outcomes.all { it.expectedCalibrationError == null && it.occupiedBins.isEmpty() })
    }

    @Test fun walkForwardObservedScoreIsMappedToTheCorrectOutcome() {
        val prediction = WalkForwardPrediction(
            targetId = "match-1",
            forecastCutoffMillis = 123,
            probabilities = OutcomeProbabilities(.2, .3, .5),
            observedHomeGoals = 0,
            observedAwayGoals = 2,
            brierScore = 0.0,
            logLoss = 0.0,
        )
        val report = CalibrationDiagnostics.evaluateWalkForward(listOf(prediction), binCount = 5)
        val away = report.outcomes.single { it.outcome == ForecastOutcome.AWAY }
        assertEquals(1.0, away.occupiedBins.single().observedFrequency, 0.0)
        assertEquals(.5, away.occupiedBins.single().meanPredictedProbability, 0.0)
    }

    @Test fun binCountMustBeUsefulAndBounded() {
        assertTrue(runCatching { CalibrationDiagnostics.evaluate(emptyList(), 1) }.isFailure)
        assertTrue(runCatching { CalibrationDiagnostics.evaluate(emptyList(), 101) }.isFailure)
    }
}
