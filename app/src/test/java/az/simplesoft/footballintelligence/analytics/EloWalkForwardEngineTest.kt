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
                // A bad source timestamp cannot make a post-kickoff result available early.
                HistoricalMatch(300, "Alpha", "Beta", 9, 0, availableAtMillis = 0),
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
        assertEquals(report.predictions[0].frequencyBaselineProbabilities, report.predictions[1].frequencyBaselineProbabilities)
    }

    @Test fun historicalFrequencyReferenceUsesOnlyStrictlyAvailableLeagueResultsAndScoresSameRows() {
        val report = EloWalkForwardEngine.evaluate(
            history = listOf(
                HistoricalMatch(10, "Alpha", "Gamma", 2, 0, availableAtMillis = 50),
                // This away win is unavailable at cutoff 100 and becomes available only afterward.
                HistoricalMatch(20, "Delta", "Beta", 0, 1, availableAtMillis = 100),
            ),
            targets = listOf(
                WalkForwardTarget("at-cutoff", 100, "Alpha", "Beta", 1, 0),
                WalkForwardTarget("after-cutoff", 101, "Alpha", "Beta", 0, 0),
            ),
            minimumHistoryMatches = 0,
            minimumMatchesPerTeam = 0,
        )

        val atCutoff = report.predictions.single { it.targetId == "at-cutoff" }
        val afterCutoff = report.predictions.single { it.targetId == "after-cutoff" }
        assertEquals(OutcomeProbabilities(0.5, 0.25, 0.25), atCutoff.frequencyBaselineProbabilities)
        assertEquals(OutcomeProbabilities(0.4, 0.2, 0.4), afterCutoff.frequencyBaselineProbabilities)
        assertEquals(
            ForecastMetrics.brierScore(requireNotNull(atCutoff.frequencyBaselineProbabilities), OutcomeProbabilities(1.0, 0.0, 0.0)),
            atCutoff.frequencyBaselineBrierScore!!,
            1e-12,
        )
        assertEquals(
            ForecastMetrics.logLoss(requireNotNull(afterCutoff.frequencyBaselineProbabilities), OutcomeProbabilities(0.0, 1.0, 0.0)),
            afterCutoff.frequencyBaselineLogLoss!!,
            1e-12,
        )
        assertEquals(
            listOf(requireNotNull(atCutoff.frequencyBaselineBrierScore), requireNotNull(afterCutoff.frequencyBaselineBrierScore)).average(),
            report.meanFrequencyBaselineBrierScore!!,
            1e-12,
        )
        assertEquals(2, report.forecastCount)
    }

    @Test fun streamingWalkForwardMatchesIndependentAsOfFitsAcrossAvailabilityBatches() {
        val history = listOf(
            HistoricalMatch(10, "Alpha", "Beta", 3, 0, availableAtMillis = 100),
            HistoricalMatch(11, "Gamma", "Delta", 0, 2, availableAtMillis = 100),
            HistoricalMatch(20, "Beta", "Gamma", 1, 1, availableAtMillis = 150),
            HistoricalMatch(30, "Delta", "Alpha", 0, 1, availableAtMillis = 250),
        )
        val targets = listOf(
            WalkForwardTarget("at-availability", 100, "Alpha", "Beta", 0, 0),
            WalkForwardTarget("after-first-batch", 120, "Alpha", "Gamma", 1, 0),
            WalkForwardTarget("same-cutoff-a", 200, "Alpha", "Beta", 1, 0),
            WalkForwardTarget("same-cutoff-b", 200, "Gamma", "Delta", 0, 1),
            WalkForwardTarget("after-second-batch", 300, "Delta", "Alpha", 0, 0),
        )
        val report = EloWalkForwardEngine.evaluate(
            history,
            targets,
            minimumHistoryMatches = 0,
            minimumMatchesPerTeam = 0,
        )

        assertEquals(targets.size, report.forecastCount)
        targets.forEach { target ->
            val expected = EloEngine.predict1x2(
                EloEngine.fit(history, target.forecastCutoffMillis),
                target.homeTeam,
                target.awayTeam,
            )
            assertEquals(expected, report.predictions.single { it.targetId == target.id }.probabilities)
        }
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
