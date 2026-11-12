package az.simplesoft.footballintelligence.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalExpectationEngineTest {
    private fun history(size: Int = 40): List<HistoricalMatch> = (0 until size).map { index ->
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

    private fun targets(rows: List<HistoricalMatch>) = rows.mapIndexed { index, match ->
        WalkForwardTarget(
            "row-$index", match.kickoffMillis + 1_000L, match.homeTeam, match.awayTeam,
            match.homeGoals, match.awayGoals,
        )
    }

    @Test fun resultTimestampBeforeKickoffCannotLeakIntoExpectedGoals() {
        val rows = listOf(
            HistoricalMatch(10, "Alpha", "Beta", 2, 1, availableAtMillis = 5),
            HistoricalMatch(20, "Alpha", "Beta", 1, 1, availableAtMillis = 30),
            HistoricalMatch(30, "Gamma", "Alpha", 0, 2, availableAtMillis = 100),
        )
        val prediction = GoalExpectationEngine.forecast(
            rows, forecastCutoffMillis = 100, homeTeam = "Alpha", awayTeam = "Beta",
            config = GoalExpectationConfig(minimumLeagueMatches = 0, minimumMatchesPerTeam = 0),
        )

        assertNotNull(prediction)
        assertEquals(1, prediction!!.historyMatchCount)
        assertTrue(prediction.homeExpectedGoals in GoalExpectationEngine.MIN_EXPECTED_GOALS..GoalExpectationEngine.MAX_EXPECTED_GOALS)
        assertTrue(prediction.awayExpectedGoals in GoalExpectationEngine.MIN_EXPECTED_GOALS..GoalExpectationEngine.MAX_EXPECTED_GOALS)
        assertEquals(1.0, prediction.probabilities.values().sum(), 1e-12)
    }

    @Test fun streamingPredictionsMatchIndependentStrictCutoffFits() {
        val rows = history(30)
        val targets = listOf(
            WalkForwardTarget("cut-1", 100_000, "Team0", "Team1", 2, 0),
            WalkForwardTarget("cut-2", 180_000, "Team2", "Team3", 0, 1),
            WalkForwardTarget("cut-3", 260_000, "Team1", "Team2", 1, 1),
        )
        val config = GoalExpectationConfig(minimumLeagueMatches = 0, minimumMatchesPerTeam = 0)
        val report = GoalExpectationWalkForwardEngine.evaluate(rows, targets, config)

        assertEquals(3, report.forecastCount)
        targets.forEach { target ->
            val independent = GoalExpectationEngine.forecast(
                rows, target.forecastCutoffMillis, target.homeTeam, target.awayTeam, config,
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

    @Test fun modelRequiresConfiguredLeagueAndTeamHistory() {
        val report = GoalExpectationWalkForwardEngine.evaluate(
            history(20), targets(history(20)), GoalExpectationConfig(minimumLeagueMatches = 50, minimumMatchesPerTeam = 5),
        )

        assertEquals(20, report.targetCount)
        assertEquals(0, report.forecastCount)
        assertEquals(20, report.skippedForInsufficientHistory)
        assertNull(report.meanBrierScore)
        assertNull(report.meanLogLoss)
        assertFalse(runCatching { GoalExpectationConfig(smoothingMatches = 0.0) }.isSuccess)
    }

    @Test fun poissonAndSelectedEloAreComparedOnIdenticalHoldoutFixtures() {
        val rows = history(50)
        val dataset = EloExperimentDataset("Synthetic League", rows, targets(rows))
        val eloReport = EloParameterExperimentEngine.evaluate(
            datasets = listOf(dataset),
            parameterGrid = listOf(EloConfig()),
            minimumHistoryMatches = 0,
            minimumMatchesPerTeam = 0,
            minimumValidationForecastsForSelection = 1,
        )
        val comparison = GoalModelComparisonEngine.evaluate(
            listOf(dataset),
            eloReport,
            GoalExpectationConfig(minimumLeagueMatches = 0, minimumMatchesPerTeam = 0),
        )

        assertTrue(comparison.usedValidationSelectedEloConfig)
        assertTrue(comparison.pairedHoldoutTargetCount > 0)
        assertEquals(comparison.pairedHoldoutTargetCount, comparison.eloPairedHoldout.forecastCount)
        assertEquals(comparison.pairedHoldoutTargetCount, comparison.goalPairedHoldout.forecastCount)
        assertNotNull(comparison.eloPairedHoldout.meanLogLoss)
        assertNotNull(comparison.goalPairedHoldout.meanLogLoss)
        assertTrue(comparison.eloHoldoutForecastCount >= comparison.pairedHoldoutTargetCount)
        assertTrue(comparison.goalHoldoutForecastCount >= comparison.pairedHoldoutTargetCount)
        assertFalse(comparison.rhoSelectionAvailable)
        assertNull(comparison.selectedDixonColesRho)
        assertNull(comparison.goalConfig.dixonColesRho)
    }

    @Test fun dixonColesRhoIsSelectedOnValidationAndUsedForUntouchedHoldout() {
        val rows = history(100)
        val dataset = EloExperimentDataset("Synthetic League", rows, targets(rows))
        val eloReport = EloParameterExperimentEngine.evaluate(
            datasets = listOf(dataset),
            parameterGrid = listOf(EloConfig()),
            minimumHistoryMatches = 0,
            minimumMatchesPerTeam = 0,
            minimumValidationForecastsForSelection = 1,
        )
        val comparison = GoalModelComparisonEngine.evaluate(
            listOf(dataset),
            eloReport,
            GoalExpectationConfig(minimumLeagueMatches = 0, minimumMatchesPerTeam = 0),
            minimumValidationForecastsForRhoSelection = 1,
        )

        assertTrue(comparison.rhoSelectionAvailable)
        assertEquals(GoalModelComparisonEngine.DIXON_COLES_RHO_GRID.size, comparison.rhoCandidates.size)
        assertNotNull(comparison.selectedRhoValidationMetrics?.meanLogLoss)
        assertEquals(comparison.selectedDixonColesRho, comparison.goalConfig.dixonColesRho)
        assertNotNull(comparison.goalPairedHoldout.meanLogLoss)
    }

    @Test fun changingOnlyHoldoutLabelsCannotChangeSelectedDixonColesRho() {
        val rows = history(100)
        val originalTargets = targets(rows)
        val dataset = EloExperimentDataset("Synthetic League", rows, originalTargets)
        val eloReport = EloParameterExperimentEngine.evaluate(
            datasets = listOf(dataset),
            parameterGrid = listOf(EloConfig()),
            minimumHistoryMatches = 0,
            minimumMatchesPerTeam = 0,
            minimumValidationForecastsForSelection = 1,
        )
        val holdoutStart = requireNotNull(eloReport.testStartMillis)
        val changedHoldoutTargets = originalTargets.map { target ->
            if (target.forecastCutoffMillis >= holdoutStart) {
                target.copy(homeGoals = target.awayGoals + 1, awayGoals = target.homeGoals)
            } else {
                target
            }
        }
        val changedDataset = dataset.copy(targets = changedHoldoutTargets)
        val config = GoalExpectationConfig(minimumLeagueMatches = 0, minimumMatchesPerTeam = 0)
        val original = GoalModelComparisonEngine.evaluate(
            listOf(dataset), eloReport, config, minimumValidationForecastsForRhoSelection = 1,
        )
        val changedHoldout = GoalModelComparisonEngine.evaluate(
            listOf(changedDataset), eloReport, config, minimumValidationForecastsForRhoSelection = 1,
        )

        assertEquals(original.selectedDixonColesRho, changedHoldout.selectedDixonColesRho)
    }
}
