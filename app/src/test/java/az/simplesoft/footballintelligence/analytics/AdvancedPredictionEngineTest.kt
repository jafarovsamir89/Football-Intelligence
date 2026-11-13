package az.simplesoft.footballintelligence.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedPredictionEngineTest {
    @Test fun productionUsesDecayPoissonWhenLeagueAndTeamsHaveEnoughAsOfHistory() {
        val cutoff = CUTOFF
        val prediction = AdvancedPredictionEngine.forecast(history(60), cutoff, "Team A", "Team B")

        assertEquals(CURRENT_ADVANCED_MODEL_VERSION, prediction.modelVersion)
        assertEquals("Poisson · затухание 365 дней", prediction.modelName)
        assertNotNull(prediction.probabilities)
        assertNotNull(prediction.homeExpectedGoals)
        assertNotNull(prediction.awayExpectedGoals)
        assertEquals(1.0, prediction.probabilities!!.values().sum(), 1e-12)
        assertEquals(60, prediction.historyMatchCount)
    }

    @Test fun resultsNotAvailableByCutoffDoNotChangeThePrediction() {
        val cutoff = CUTOFF
        val known = history(60)
        val unavailable = known.last().copy(availableAtMillis = cutoff)

        val withoutUnavailable = AdvancedPredictionEngine.forecast(known.dropLast(1), cutoff, "Team A", "Team B")
        val withUnavailable = AdvancedPredictionEngine.forecast(known.dropLast(1) + unavailable, cutoff, "Team A", "Team B")

        assertEquals(withoutUnavailable.probabilities, withUnavailable.probabilities)
        assertEquals(withoutUnavailable.historyMatchCount, withUnavailable.historyMatchCount)
    }

    @Test fun eloIsUsedOnlyAsADeclaredCoverageFallbackAndNoForecastIsReturnedBelowItsFloor() {
        val cutoff = CUTOFF
        val fallback = AdvancedPredictionEngine.forecast(history(20), cutoff, "Team A", "Team B")
        val unavailable = AdvancedPredictionEngine.forecast(history(9), cutoff, "Team A", "Team B")

        assertEquals(CURRENT_ELO_MODEL_VERSION, fallback.modelVersion)
        assertEquals("Elo · резервная модель", fallback.modelName)
        assertNotNull(fallback.probabilities)
        assertNotNull(fallback.fallbackReason)
        assertNull(unavailable.modelVersion)
        assertNull(unavailable.probabilities)
        assertNotNull(unavailable.fallbackReason)
    }

    @Test fun exponentialEvidenceWeightHasTheConfiguredHalfLifeWithoutReducingCoverageCounts() {
        val state = GoalModelState()
        val start = 100_000L
        state.decayTo(start, AdvancedPredictionEngine.HALF_LIFE_DAYS)
        state.add(HistoricalMatch(start, "Team A", "Team B", 2, 1, start))

        state.decayTo(start + 365L * 86_400_000L, AdvancedPredictionEngine.HALF_LIFE_DAYS)

        assertEquals(0.5, state.weightedMatches, 1e-12)
        assertEquals(0.5, state.teams.getValue(teamKey("Team A")).weightedMatches, 1e-12)
        assertEquals(1, state.matches)
        assertTrue(state.teams.getValue(teamKey("Team A")).actualMatches == 1)
    }

    private fun history(size: Int): List<HistoricalMatch> = (0 until size).map { index ->
        val team = index % 4
        val kickoff = 1_000_000L + index * DAY
        HistoricalMatch(
            kickoffMillis = kickoff,
            homeTeam = "Team ${('A'.code + team).toChar()}",
            awayTeam = "Team ${('A'.code + (team + 1) % 4).toChar()}",
            homeGoals = index % 4,
            awayGoals = (index + 1) % 3,
            availableAtMillis = kickoff + 60_000L,
        )
    }

    private companion object {
        const val DAY = 86_400_000L
        const val CUTOFF = 1_000_000L + 100L * DAY
    }
}
