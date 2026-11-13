package az.simplesoft.footballintelligence

import az.simplesoft.footballintelligence.analytics.CURRENT_ELO_MODEL_VERSION
import az.simplesoft.footballintelligence.analytics.EloEngine
import az.simplesoft.footballintelligence.analytics.ForecastedMatch
import az.simplesoft.footballintelligence.analytics.HistoricalMatch
import az.simplesoft.footballintelligence.analytics.TeamDataQuality
import az.simplesoft.footballintelligence.analytics.historyAvailableAt
import java.time.Instant

internal const val MAX_AUTOMATIC_PROSPECTIVE_FORECASTS = 80

/** Builds the same leakage-safe live Elo snapshots for the screen and background sync. */
internal fun buildProspectiveEloForecasts(
    matches: Collection<Match>,
    historicalByLeague: Map<String, List<HistoricalMatch>>,
    forecastedAtMillis: Long,
    preferredMatchIds: Set<Int> = emptySet(),
    limit: Int = MAX_AUTOMATIC_PROSPECTIVE_FORECASTS,
): List<ForecastedMatch> {
    require(forecastedAtMillis > 0L)
    require(limit >= 0)
    if (limit == 0) return emptyList()

    val upcoming = matches.asSequence()
        .filter { !it.finished && it.exactKickoff && TeamDataQuality.isValidFixture(it.home, it.away) }
        .mapNotNull { match ->
            val kickoff = runCatching { Instant.parse(match.kickoff).toEpochMilli() }.getOrNull()
                ?: return@mapNotNull null
            match.takeIf { kickoff > forecastedAtMillis }
        }
        .sortedBy { runCatching { Instant.parse(it.kickoff).toEpochMilli() }.getOrDefault(Long.MAX_VALUE) }
        .toList()
    val ordered = (upcoming.filter { it.id in preferredMatchIds } + upcoming.filterNot { it.id in preferredMatchIds })
        .distinctBy { it.id }
        .take(limit)

    return ordered.mapNotNull { match ->
        val history = historyAvailableAt(historicalByLeague[match.league].orEmpty(), forecastedAtMillis)
        val forecast = EloEngine.forecast(history, forecastedAtMillis, match.home, match.away)
        val probabilities = forecast.probabilities ?: return@mapNotNull null
        ForecastedMatch(
            matchId = match.id,
            homeTeam = match.home,
            awayTeam = match.away,
            kickoffMillis = Instant.parse(match.kickoff).toEpochMilli(),
            probabilities = probabilities,
            forecastedAtMillis = forecastedAtMillis,
            historyMatchCount = forecast.matchesUsed,
            modelVersion = CURRENT_ELO_MODEL_VERSION,
            competition = match.league,
        )
    }
}
