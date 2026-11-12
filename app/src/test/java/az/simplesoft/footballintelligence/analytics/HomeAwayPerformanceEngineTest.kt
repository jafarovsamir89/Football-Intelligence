package az.simplesoft.footballintelligence.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAwayPerformanceEngineTest {
    private fun venueHistory(): List<HistoricalMatch> = buildList {
        repeat(6) { index ->
            val time = index * 20_000L
            add(HistoricalMatch(time, "Alpha", "Visitor$index", 3, 0, availableAtMillis = time + 5_000L))
            add(HistoricalMatch(time + 10_000L, "Host$index", "Beta", 1, 1, availableAtMillis = time + 15_000L))
        }
    }

    @Test fun venueSplitHistoryDrivesBoundedPoissonProbabilities() {
        val prediction = HomeAwayPerformanceEngine.forecast(
            history = venueHistory(),
            forecastCutoffMillis = 1_000_000L,
            homeTeam = "Alpha",
            awayTeam = "Beta",
            config = HomeAwayPerformanceConfig(minimumLeagueMatches = 12, minimumMatchesPerSplit = 6),
        )

        assertNotNull(prediction)
        assertEquals(12, prediction!!.historyMatchCount)
        assertEquals(6, prediction.homeTeamHomeMatches)
        assertEquals(6, prediction.awayTeamAwayMatches)
        assertTrue(prediction.homeExpectedGoals in GoalExpectationEngine.MIN_EXPECTED_GOALS..GoalExpectationEngine.MAX_EXPECTED_GOALS)
        assertTrue(prediction.awayExpectedGoals in GoalExpectationEngine.MIN_EXPECTED_GOALS..GoalExpectationEngine.MAX_EXPECTED_GOALS)
        assertEquals(1.0, prediction.probabilities.values().sum(), 1e-12)
        assertTrue(prediction.probabilities.home > prediction.probabilities.away)
    }

    @Test fun venueSpecificMinimumsAreAppliedIndependently() {
        val rows = venueHistory()
        val config = HomeAwayPerformanceConfig(minimumLeagueMatches = 12, minimumMatchesPerSplit = 7)

        assertNull(HomeAwayPerformanceEngine.forecast(rows, 1_000_000L, "Alpha", "Beta", config))
        assertFalse(runCatching { HomeAwayPerformanceConfig(smoothingMatches = 0.0) }.isSuccess)
        assertFalse(runCatching { HomeAwayPerformanceConfig(maxGoals = 3) }.isSuccess)
    }

    @Test fun sameAvailabilityAndInvalidTimestampsCannotLeakIntoEarlierForecast() {
        val rows = venueHistory()
        val cutoff = 1_000_000L
        val config = HomeAwayPerformanceConfig(minimumLeagueMatches = 12, minimumMatchesPerSplit = 6)
        val boundary = HistoricalMatch(cutoff - 10, "Alpha", "Beta", 10, 0, availableAtMillis = cutoff)
        val invalid = HistoricalMatch(cutoff - 5, "Alpha", "Beta", 0, 10, availableAtMillis = 0)
        val baseline = HomeAwayPerformanceEngine.forecast(rows, cutoff, "Alpha", "Beta", config)
        val withUnavailable = HomeAwayPerformanceEngine.forecast(rows + boundary + invalid, cutoff, "Alpha", "Beta", config)

        assertEquals(baseline, withUnavailable)
        assertNotNull(HomeAwayPerformanceEngine.forecast(rows + boundary, cutoff + 1, "Alpha", "Beta", config))
    }

    @Test fun streamingWalkForwardMatchesIndependentAsOfFitsAndSharesSameCutoffState() {
        val history = venueHistory()
        val targets = listOf(
            WalkForwardTarget("first", 100_000L, "Alpha", "Beta", 2, 0),
            WalkForwardTarget("same-cutoff", 100_000L, "Alpha", "Beta", 0, 1),
            WalkForwardTarget("later", 150_000L, "Alpha", "Beta", 1, 1),
        )
        val config = HomeAwayPerformanceConfig(minimumLeagueMatches = 0, minimumMatchesPerSplit = 0)
        val report = HomeAwayPerformanceWalkForwardEngine.evaluate(history, targets, config)

        assertEquals(3, report.forecastCount)
        assertEquals(report.predictions[0].probabilities, report.predictions[1].probabilities)
        targets.forEach { target ->
            val independent = HomeAwayPerformanceEngine.forecast(
                history, target.forecastCutoffMillis, target.homeTeam, target.awayTeam, config,
            )!!
            val streamed = report.predictions.single { it.targetId == target.id }
            assertEquals(independent.historyMatchCount, streamed.historyMatchCount)
            assertEquals(independent.homeExpectedGoals, streamed.homeExpectedGoals, 1e-12)
            assertEquals(independent.awayExpectedGoals, streamed.awayExpectedGoals, 1e-12)
            assertEquals(independent.probabilities, streamed.probabilities)
        }
        assertNotNull(report.meanBrierScore)
        assertNotNull(report.meanLogLoss)
    }

    @Test fun comparisonReportsHomeAwayMetricsOnItsOwnPairedHoldout() {
        val rows = GoalExpectationEngineTestFixtures.history(100)
        val dataset = EloExperimentDataset("Synthetic League", rows, GoalExpectationEngineTestFixtures.targets(rows))
        val elo = EloParameterExperimentEngine.evaluate(
            datasets = listOf(dataset),
            parameterGrid = listOf(EloConfig()),
            minimumHistoryMatches = 0,
            minimumMatchesPerTeam = 0,
            minimumValidationForecastsForSelection = 1,
        )
        val comparison = GoalModelComparisonEngine.evaluate(
            datasets = listOf(dataset),
            eloExperiment = elo,
            goalConfig = GoalExpectationConfig(minimumLeagueMatches = 0, minimumMatchesPerTeam = 0),
            minimumValidationForecastsForRhoSelection = 1,
            homeAwayConfig = HomeAwayPerformanceConfig(minimumLeagueMatches = 0, minimumMatchesPerSplit = 0),
        )

        assertTrue(comparison.homeAwayHoldoutForecastCount >= comparison.homeAwayPairedHoldoutTargetCount)
        assertEquals(comparison.homeAwayPairedHoldoutTargetCount, comparison.eloHomeAwayPairedHoldout.forecastCount)
        assertEquals(comparison.homeAwayPairedHoldoutTargetCount, comparison.homeAwayPairedHoldout.forecastCount)
        assertEquals(comparison.homeAwayPairedHoldoutTargetCount, comparison.eloHomeAwayPairedHoldout.targetCount)
    }
}

private object GoalExpectationEngineTestFixtures {
    fun history(size: Int): List<HistoricalMatch> = (0 until size).map { index ->
        val kickoff = index * 10_000L
        val home = "Team${index % 4}"
        val away = "Team${(index + 1) % 4}"
        val (homeGoals, awayGoals) = when (index % 4) {
            0 -> 2 to 0
            1 -> 0 to 1
            2 -> 1 to 1
            else -> 3 to 1
        }
        HistoricalMatch(kickoff, home, away, homeGoals, awayGoals, availableAtMillis = kickoff + 5_000L)
    }

    fun targets(rows: List<HistoricalMatch>) = rows.mapIndexed { index, match ->
        WalkForwardTarget(
            "row-$index", match.kickoffMillis + 1_000L, match.homeTeam, match.awayTeam,
            match.homeGoals, match.awayGoals,
        )
    }
}
