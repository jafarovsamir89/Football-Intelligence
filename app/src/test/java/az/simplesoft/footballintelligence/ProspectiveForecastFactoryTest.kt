package az.simplesoft.footballintelligence

import az.simplesoft.footballintelligence.analytics.HistoricalMatch
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProspectiveForecastFactoryTest {
    @Test fun createsOnlyFutureExactValidForecastsFromInformationAvailableAtCutoff() {
        val forecastAt = 2_000_000_000_000L
        val history = eligibleHistory(forecastAt)
        val forecasts = buildProspectiveEloForecasts(
            matches = listOf(
                fixture(1, forecastAt + DAY),
                fixture(2, forecastAt - DAY),
                fixture(3, forecastAt + DAY, exactKickoff = false),
                fixture(4, forecastAt + DAY, home = "Same FC", away = "Same FC"),
                fixture(5, forecastAt + 2 * DAY),
                fixture(6, forecastAt + 3 * DAY, finished = true),
            ),
            historicalByLeague = mapOf("league" to history + listOf(
                // The match is old, but its result was not available at forecast time.
                HistoricalMatch(forecastAt - 2 * DAY, "Home FC", "Late FC", 1, 0, forecastAt),
            )),
            forecastedAtMillis = forecastAt,
        )

        assertEquals(listOf(1, 5), forecasts.map { it.matchId })
        assertTrue(forecasts.all { it.historyMatchCount == 10 })
        assertTrue(forecasts.all { it.forecastedAtMillis == forecastAt })
        assertTrue(forecasts.all { it.probabilities.values().sum() in 0.999999..1.000001 })
    }

    @Test fun requiresTheModelHistoryFloorBeforeProducingAProspectiveSnapshot() {
        val forecastAt = 2_000_000_000_000L
        val forecasts = buildProspectiveEloForecasts(
            matches = listOf(fixture(1, forecastAt + DAY)),
            historicalByLeague = mapOf("league" to eligibleHistory(forecastAt).take(9)),
            forecastedAtMillis = forecastAt,
        )

        assertTrue(forecasts.isEmpty())
    }

    private fun eligibleHistory(forecastAt: Long): List<HistoricalMatch> {
        val teams = listOf(
            "Home FC" to "Other FC",
            "Away FC" to "Another FC",
            "Other FC" to "Away FC",
            "Another FC" to "Home FC",
            "Home FC" to "Away FC",
            "Other FC" to "Another FC",
            "Away FC" to "Home FC",
            "Another FC" to "Other FC",
            "Home FC" to "Another FC",
            "Away FC" to "Other FC",
        )
        return teams.mapIndexed { index, (home, away) ->
            val kickoff = forecastAt - (teams.size - index + 3L) * DAY
            HistoricalMatch(kickoff, home, away, index % 3, (index + 1) % 3, kickoff + 60_000L)
        }
    }

    private fun fixture(
        id: Int,
        kickoffMillis: Long,
        exactKickoff: Boolean = true,
        home: String = "Home FC",
        away: String = "Away FC",
        finished: Boolean = false,
    ) = Match(
        id = id,
        league = "league",
        home = home,
        away = away,
        kickoff = Instant.ofEpochMilli(kickoffMillis).toString(),
        finished = finished,
        homeScore = if (finished) 1 else null,
        awayScore = if (finished) 0 else null,
        exactKickoff = exactKickoff,
    )

    private companion object {
        const val DAY = 86_400_000L
    }
}
