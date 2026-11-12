package az.simplesoft.footballintelligence.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EloParameterExperimentEngineTest {
    @Test fun defaultGridContainsTwentySevenUniqueConfigsIncludingProductionDefaults() {
        assertEquals(27, EloParameterExperimentEngine.DEFAULT_GRID.size)
        assertEquals(27, EloParameterExperimentEngine.DEFAULT_GRID.distinct().size)
        assertTrue(EloConfig() in EloParameterExperimentEngine.DEFAULT_GRID)
    }

    private fun dataset(size: Int = 30): EloExperimentDataset {
        val rows = (0 until size).map { index ->
            val kickoff = index * 10_000L
            val home = "Team${index % 4}"
            val away = "Team${(index + 1) % 4}"
            val homeGoals = if (index % 3 == 0) 2 else if (index % 3 == 1) 0 else 1
            val awayGoals = if (index % 3 == 0) 0 else if (index % 3 == 1) 1 else 1
            HistoricalMatch(kickoff, home, away, homeGoals, awayGoals, availableAtMillis = kickoff + 3_000L) to
                WalkForwardTarget(
                    id = "match-$index",
                    forecastCutoffMillis = kickoff + 2_000L,
                    homeTeam = home,
                    awayTeam = away,
                    homeGoals = homeGoals,
                    awayGoals = awayGoals,
                )
        }
        return EloExperimentDataset("Synthetic League", rows.map { it.first }, rows.map { it.second })
    }

    @Test fun selectsOnChronologicalValidationAndKeepsFinalHoldoutOutOfSelection() {
        val base = dataset()
        val grid = listOf(
            EloConfig(kFactor = 10.0, homeAdvantage = 0.0, priorDrawProbability = 0.22),
            EloConfig(kFactor = 30.0, homeAdvantage = 100.0, priorDrawProbability = 0.28),
        )
        val report = EloParameterExperimentEngine.evaluate(
            listOf(base), grid, minimumHistoryMatches = 0, minimumMatchesPerTeam = 0,
            minimumValidationForecastsForSelection = 1,
        )
        val changedHoldout = base.copy(targets = base.targets.mapIndexed { index, target ->
            if (index < 24) target else target.copy(homeGoals = target.awayGoals + 3)
        })
        val afterHoldoutChange = EloParameterExperimentEngine.evaluate(
            listOf(changedHoldout), grid, minimumHistoryMatches = 0, minimumMatchesPerTeam = 0,
            minimumValidationForecastsForSelection = 1,
        )

        assertEquals(30, report.totalTargets)
        assertEquals(18, report.training.targetCount)
        assertEquals(6, report.selectedValidation!!.targetCount)
        assertEquals(6, report.selectedTest!!.targetCount)
        assertEquals(2, report.candidates.size)
        assertNotNull(report.selectedConfig)
        assertEquals(report.selectedConfig, afterHoldoutChange.selectedConfig)
        assertEquals(report.selectedValidation, afterHoldoutChange.selectedValidation)
        assertTrue(report.testStartMillis!! > report.validationStartMillis!!)
        assertTrue(report.candidates.zipWithNext().all { (left, right) ->
            left.validation.meanLogLoss!! <= right.validation.meanLogLoss!!
        })
    }

    @Test fun doesNotSelectParametersWithoutEnoughValidationForecasts() {
        val report = EloParameterExperimentEngine.evaluate(
            listOf(dataset(10)),
            parameterGrid = listOf(EloConfig()),
            minimumHistoryMatches = 0,
            minimumMatchesPerTeam = 0,
            minimumValidationForecastsForSelection = 100,
        )

        assertNull(report.selectedConfig)
        assertNull(report.selectedValidation)
        assertNull(report.selectedTest)
        assertEquals(100, report.minimumValidationForecastsForSelection)
    }
}
