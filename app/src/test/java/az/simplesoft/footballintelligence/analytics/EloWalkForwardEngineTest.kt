package az.simplesoft.footballintelligence.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EloWalkForwardEngineTest {
    @Test fun resultsAvailableAtOrAfterCutoffCannotChangeEarlierForecast() {
        val target = WalkForwardTarget("target", 200, "Alpha", "Beta", 1, 0)
        val baseline = EloWalkForwardEngine.evaluate(emptyList(), listOf(target), minimumHistoryMatches = 0, minimumMatchesPerTeam = 0)
        val withUnavailableRows = EloWalkForwardEngine.evaluate(
            listOf(
                HistoricalMatch(200, "Alpha", "Beta", 0, 9),
                HistoricalMatch(500, "Alpha", "Beta", 9, 0),
            ),
            listOf(target),
            minimumHistoryMatches = 0,
            minimumMatchesPerTeam = 0,
        )
        assertEquals(baseline.predictions.single().probabilities, withUnavailableRows.predictions.single().probabilities)
    }

    @Test fun earlierAvailableResultsAffectForecastAndProperScoresAreReported() {
        val target = WalkForwardTarget("target", 500, "Alpha", "Beta", 2, 0)
        val withoutHistory = EloWalkForwardEngine.evaluate(emptyList(), listOf(target), minimumHistoryMatches = 0, minimumMatchesPerTeam = 0)
        val withHistory = EloWalkForwardEngine.evaluate(
            listOf(HistoricalMatch(100, "Alpha", "Beta", 3, 0)),
            listOf(target),
            minimumHistoryMatches = 0,
            minimumMatchesPerTeam = 0,
        )
        val prediction = withHistory.predictions.single()
        assertNotEquals(withoutHistory.predictions.single().probabilities, prediction.probabilities)
        assertNotNull(withHistory.meanBrierScore)
        assertNotNull(withHistory.meanLogLoss)
        assertEquals(prediction.brierScore, withHistory.meanBrierScore!!, 1e-12)
        assertEquals(prediction.logLoss, withHistory.meanLogLoss!!, 1e-12)
        assertTrue(prediction.brierScore >= 0.0)
        assertTrue(prediction.logLoss >= 0.0)
    }

    @Test fun sameCutoffTargetsUseOneSharedModel() {
        val targets = listOf(
            WalkForwardTarget("first", 500, "Alpha", "Beta", 1, 0),
            WalkForwardTarget("second", 500, "Alpha", "Beta", 0, 1),
        )
        val report = EloWalkForwardEngine.evaluate(
            listOf(HistoricalMatch(100, "Alpha", "Gamma", 2, 0)),
            targets,
            minimumHistoryMatches = 0,
            minimumMatchesPerTeam = 0,
        )
        assertEquals(2, report.forecastCount)
        assertEquals(report.predictions[0].probabilities, report.predictions[1].probabilities)
    }

    @Test fun insufficientHistorySkipsTargetsAndLeavesMetricsEmpty() {
        val report = EloWalkForwardEngine.evaluate(
            history = emptyList(),
            targets = listOf(WalkForwardTarget("target", 500, "Alpha", "Beta", 0, 0)),
        )
        assertEquals(1, report.targetCount)
        assertEquals(0, report.forecastCount)
        assertEquals(1, report.skippedForInsufficientHistory)
        assertNull(report.meanBrierScore)
        assertNull(report.meanLogLoss)
    }
}
