package az.simplesoft.footballintelligence.analytics

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class HistoricalMatch(
    val kickoffMillis: Long,
    val homeTeam: String,
    val awayTeam: String,
    val homeGoals: Int,
    val awayGoals: Int,
    /** First safe time when this result may be used; callers with unknown timing must supply a bound. */
    val availableAtMillis: Long = kickoffMillis,
)

/** Reject a source timestamp that predates the match; do not guess when its result became available. */
internal fun HistoricalMatch.safeAvailabilityMillisOrNull(): Long? = availableAtMillis.takeIf { it >= kickoffMillis }

data class EloConfig(
    val initialRating: Double = 1500.0,
    val kFactor: Double = 20.0,
    val homeAdvantage: Double = 65.0,
    val priorDrawProbability: Double = 0.25,
    val priorMatchCount: Double = 4.0,
) {
    init {
        require(initialRating.isFinite())
        require(kFactor.isFinite() && kFactor > 0.0)
        require(homeAdvantage.isFinite())
        require(priorDrawProbability.isFinite() && priorDrawProbability in 0.0..1.0)
        require(priorMatchCount.isFinite() && priorMatchCount > 0.0)
    }
}

data class EloRatings(
    val ratings: Map<String, Double>,
    val matchesUsed: Int,
    val drawProbability: Double,
    val playedByTeam: Map<String, Int>,
    val config: EloConfig,
) {
    fun gamesFor(team: String): Int = playedByTeam[teamKey(team)] ?: 0
    fun ratingFor(team: String): Double = ratings[teamKey(team)] ?: config.initialRating
}

data class EloForecast(
    val probabilities: OutcomeProbabilities?,
    val matchesUsed: Int,
    val homeTeamMatches: Int,
    val awayTeamMatches: Int,
)

object EloEngine {
    const val MIN_HISTORY_MATCHES_FOR_FORECAST = 10
    const val MIN_TEAM_MATCHES_FOR_FORECAST = 3

    /** Fits ratings from only results whose safe availability time is strictly before the forecast cutoff. */
    fun fit(
        history: Collection<HistoricalMatch>,
        forecastKickoffMillis: Long,
        config: EloConfig = EloConfig(),
    ): EloRatings {
        val eligible = history.filter { match ->
            match.safeAvailabilityMillisOrNull()?.let { it < forecastKickoffMillis } == true
        }
            .onEach(::validateMatch)
            .sortedWith(compareBy<HistoricalMatch> { requireNotNull(it.safeAvailabilityMillisOrNull()) }.thenBy { it.kickoffMillis }.thenBy { teamKey(it.homeTeam) }.thenBy { teamKey(it.awayTeam) })
        val ratings = mutableMapOf<String, Double>()
        val played = mutableMapOf<String, Int>()
        var drawCount = 0

        for (sameAvailability in eligible.groupBy { requireNotNull(it.safeAvailabilityMillisOrNull()) }.toSortedMap().values) {
            // A date-only source may give many matches the same availability boundary. Use one
            // pre-batch rating snapshot so result order cannot leak into other matches in that batch.
            val ratingDeltas = mutableMapOf<String, Double>()
            for (match in sameAvailability) {
                val home = teamKey(match.homeTeam)
                val away = teamKey(match.awayTeam)
                val homeRating = ratings[home] ?: config.initialRating
                val awayRating = ratings[away] ?: config.initialRating
                val expectedHome = expectedHomeScore(homeRating, awayRating, config.homeAdvantage)
                val actualHome = when {
                    match.homeGoals > match.awayGoals -> 1.0
                    match.homeGoals < match.awayGoals -> 0.0
                    else -> 0.5
                }
                val delta = config.kFactor * (actualHome - expectedHome)
                ratingDeltas[home] = (ratingDeltas[home] ?: 0.0) + delta
                ratingDeltas[away] = (ratingDeltas[away] ?: 0.0) - delta
                played[home] = (played[home] ?: 0) + 1
                played[away] = (played[away] ?: 0) + 1
                if (actualHome == 0.5) drawCount++
            }
            for ((team, delta) in ratingDeltas) ratings[team] = (ratings[team] ?: config.initialRating) + delta
        }

        val smoothedDrawRate = (drawCount + config.priorDrawProbability * config.priorMatchCount) /
            (eligible.size + config.priorMatchCount)
        return EloRatings(ratings.toMap(), eligible.size, smoothedDrawRate, played.toMap(), config)
    }

    /** Converts the Elo expected score to a 1X2 estimate using a smoothed training-period draw rate. */
    fun predict1x2(model: EloRatings, homeTeam: String, awayTeam: String): OutcomeProbabilities {
        require(homeTeam.isNotBlank() && awayTeam.isNotBlank())
        require(teamKey(homeTeam) != teamKey(awayTeam))
        val expectedHome = expectedHomeScore(
            model.ratingFor(homeTeam),
            model.ratingFor(awayTeam),
            model.config.homeAdvantage,
        )
        // Preserve the Elo expected-score identity while keeping all three probabilities non-negative.
        val draw = min(model.drawProbability, 2.0 * min(expectedHome, 1.0 - expectedHome))
        val home = max(0.0, expectedHome - draw / 2.0)
        val away = max(0.0, 1.0 - expectedHome - draw / 2.0)
        return OutcomeProbabilities(home, draw, away)
    }

    /**
     * Produces a forecast only when the pre-kickoff history meets explicit sample-size floors.
     * The fitted model and counts remain available when the sample is too small so callers can
     * explain why no probabilities are shown.
     */
    fun forecast(
        history: Collection<HistoricalMatch>,
        forecastKickoffMillis: Long,
        homeTeam: String,
        awayTeam: String,
        config: EloConfig = EloConfig(),
        minimumHistoryMatches: Int = MIN_HISTORY_MATCHES_FOR_FORECAST,
        minimumMatchesPerTeam: Int = MIN_TEAM_MATCHES_FOR_FORECAST,
    ): EloForecast {
        require(minimumHistoryMatches >= 0)
        require(minimumMatchesPerTeam >= 0)
        val model = fit(history, forecastKickoffMillis, config)
        val homeMatches = model.gamesFor(homeTeam)
        val awayMatches = model.gamesFor(awayTeam)
        val probabilities = if (
            model.matchesUsed >= minimumHistoryMatches &&
            homeMatches >= minimumMatchesPerTeam &&
            awayMatches >= minimumMatchesPerTeam
        ) predict1x2(model, homeTeam, awayTeam) else null
        return EloForecast(probabilities, model.matchesUsed, homeMatches, awayMatches)
    }

    private fun expectedHomeScore(homeRating: Double, awayRating: Double, homeAdvantage: Double): Double =
        1.0 / (1.0 + 10.0.pow((awayRating - homeRating - homeAdvantage) / 400.0))

    private fun validateMatch(match: HistoricalMatch) {
        require(match.homeTeam.isNotBlank() && match.awayTeam.isNotBlank()) { "Team names cannot be blank." }
        require(teamKey(match.homeTeam) != teamKey(match.awayTeam)) { "A team cannot play itself." }
        require(match.homeGoals >= 0 && match.awayGoals >= 0) { "Goals cannot be negative." }
    }
}

fun teamKey(name: String): String = name.trim().lowercase(java.util.Locale.ROOT)
