package az.simplesoft.footballintelligence.analytics

import kotlin.math.exp
import kotlin.math.ln

data class GoalExpectationConfig(
    val smoothingMatches: Double = 8.0,
    val minimumLeagueMatches: Int = 50,
    val minimumMatchesPerTeam: Int = 5,
    val maxGoals: Int = 10,
    val dixonColesRho: Double? = null,
    /** Optional exponential half-life for old results. Null preserves the cumulative research baseline. */
    val timeDecayHalfLifeDays: Double? = null,
) {
    init {
        require(smoothingMatches.isFinite() && smoothingMatches > 0.0)
        require(minimumLeagueMatches >= 0)
        require(minimumMatchesPerTeam >= 0)
        require(maxGoals in 4..20)
        require(dixonColesRho == null || dixonColesRho.isFinite())
        require(timeDecayHalfLifeDays == null || timeDecayHalfLifeDays.isFinite() && timeDecayHalfLifeDays > 0.0)
    }
}

data class GoalExpectationPrediction(
    val probabilities: OutcomeProbabilities,
    val homeExpectedGoals: Double,
    val awayExpectedGoals: Double,
    val historyMatchCount: Int,
    val homeTeamMatches: Int,
    val awayTeamMatches: Int,
)

data class GoalExpectationWalkForwardPrediction(
    val targetId: String,
    val forecastCutoffMillis: Long,
    val probabilities: OutcomeProbabilities,
    val homeExpectedGoals: Double,
    val awayExpectedGoals: Double,
    val historyMatchCount: Int,
    val observedHomeGoals: Int,
    val observedAwayGoals: Int,
    val brierScore: Double,
    val logLoss: Double,
)

data class GoalExpectationWalkForwardReport(
    val targetCount: Int,
    val forecastCount: Int,
    val skippedForInsufficientHistory: Int,
    val meanBrierScore: Double?,
    val meanLogLoss: Double?,
    val predictions: List<GoalExpectationWalkForwardPrediction>,
)

internal data class MutableTeamGoalTotals(
    var weightedMatches: Double = 0.0,
    var goalsFor: Double = 0.0,
    var goalsAgainst: Double = 0.0,
    var actualMatches: Int = 0,
)

/** Mutable sufficient statistics. Decay affects evidence weights, never the sample-coverage counts. */
internal class GoalModelState {
    var matches: Int = 0
        private set
    var weightedMatches: Double = 0.0
        private set
    var homeGoals: Double = 0.0
        private set
    var awayGoals: Double = 0.0
        private set
    val teams = mutableMapOf<String, MutableTeamGoalTotals>()
    private var lastAvailabilityMillis: Long? = null

    fun decayTo(availabilityMillis: Long, halfLifeDays: Double?) {
        val previous = lastAvailabilityMillis
        if (previous != null) {
            require(availabilityMillis >= previous) { "Goal-model state cannot move backward in time." }
            if (halfLifeDays != null) {
                val elapsedDays = (availabilityMillis - previous).toDouble() / MILLIS_PER_DAY
                val factor = exp(-ln(2.0) * elapsedDays / halfLifeDays)
                weightedMatches *= factor
                homeGoals *= factor
                awayGoals *= factor
                teams.values.forEach { totals ->
                    totals.weightedMatches *= factor
                    totals.goalsFor *= factor
                    totals.goalsAgainst *= factor
                }
            }
        }
        lastAvailabilityMillis = availabilityMillis
    }

    fun add(match: HistoricalMatch) {
        GoalExpectationEngine.validate(match)
        val home = teamKey(match.homeTeam)
        val away = teamKey(match.awayTeam)
        matches++
        weightedMatches += 1.0
        homeGoals += match.homeGoals
        awayGoals += match.awayGoals
        teams.getOrPut(home) { MutableTeamGoalTotals() }.apply {
            weightedMatches += 1.0
            actualMatches++
            goalsFor += match.homeGoals
            goalsAgainst += match.awayGoals
        }
        teams.getOrPut(away) { MutableTeamGoalTotals() }.apply {
            weightedMatches += 1.0
            actualMatches++
            goalsFor += match.awayGoals
            goalsAgainst += match.homeGoals
        }
    }
}

private const val MILLIS_PER_DAY = 86_400_000.0

/** Estimates fixture lambdas from prior league goals with a league-average pseudo-count. */
object GoalExpectationEngine {
    const val MIN_EXPECTED_GOALS = 0.05
    const val MAX_EXPECTED_GOALS = 5.0

    fun forecast(
        history: Collection<HistoricalMatch>,
        forecastCutoffMillis: Long,
        homeTeam: String,
        awayTeam: String,
        config: GoalExpectationConfig = GoalExpectationConfig(),
    ): GoalExpectationPrediction? {
        require(homeTeam.isNotBlank() && awayTeam.isNotBlank())
        require(teamKey(homeTeam) != teamKey(awayTeam))
        val eligible = history.filter { match ->
            match.safeAvailabilityMillisOrNull()?.let { it < forecastCutoffMillis } == true
        }
        val state = stateFromAsOf(eligible, forecastCutoffMillis, config.timeDecayHalfLifeDays)
        return predict(state, homeTeam, awayTeam, config)
    }

    internal fun stateFrom(history: Collection<HistoricalMatch>): GoalModelState {
        return GoalModelState().apply { history.forEach(::add) }
    }

    private fun stateFromAsOf(
        history: Collection<HistoricalMatch>,
        forecastCutoffMillis: Long,
        halfLifeDays: Double?,
    ): GoalModelState {
        val eligible = history.mapNotNull { match ->
            match.safeAvailabilityMillisOrNull()?.takeIf { it < forecastCutoffMillis }?.let { it to match }
        }.sortedWith(compareBy<Pair<Long, HistoricalMatch>> { it.first }
            .thenBy { it.second.kickoffMillis }
            .thenBy { teamKey(it.second.homeTeam) }
            .thenBy { teamKey(it.second.awayTeam) })
        return GoalModelState().apply {
            eligible.forEach { (availableAt, match) ->
                decayTo(availableAt, halfLifeDays)
                add(match)
            }
            decayTo(forecastCutoffMillis, halfLifeDays)
        }
    }

    internal fun predict(
        state: GoalModelState,
        homeTeam: String,
        awayTeam: String,
        config: GoalExpectationConfig,
    ): GoalExpectationPrediction? {
        if (state.matches < config.minimumLeagueMatches || state.weightedMatches <= 0.0) return null
        val homeStats = state.teams[teamKey(homeTeam)] ?: return null
        val awayStats = state.teams[teamKey(awayTeam)] ?: return null
        if (homeStats.actualMatches < config.minimumMatchesPerTeam || awayStats.actualMatches < config.minimumMatchesPerTeam) return null

        val leagueHome = state.homeGoals / state.weightedMatches
        val leagueAway = state.awayGoals / state.weightedMatches
        val leagueTeamAverage = (state.homeGoals + state.awayGoals) / (2.0 * state.weightedMatches)
        if (leagueHome <= 0.0 || leagueAway <= 0.0 || leagueTeamAverage <= 0.0) return null

        fun attack(totals: MutableTeamGoalTotals): Double =
            ((totals.goalsFor + leagueTeamAverage * config.smoothingMatches) /
                (totals.weightedMatches + config.smoothingMatches)) / leagueTeamAverage

        fun defense(totals: MutableTeamGoalTotals): Double =
            ((totals.goalsAgainst + leagueTeamAverage * config.smoothingMatches) /
                (totals.weightedMatches + config.smoothingMatches)) / leagueTeamAverage

        val homeLambda = (leagueHome * attack(homeStats) * defense(awayStats))
            .coerceIn(MIN_EXPECTED_GOALS, MAX_EXPECTED_GOALS)
        val awayLambda = (leagueAway * attack(awayStats) * defense(homeStats))
            .coerceIn(MIN_EXPECTED_GOALS, MAX_EXPECTED_GOALS)
        val probabilities = GoalModel.predict1x2(
            homeExpectedGoals = homeLambda,
            awayExpectedGoals = awayLambda,
            maxGoals = config.maxGoals,
            dixonColesRho = config.dixonColesRho,
        ).probabilities
        return GoalExpectationPrediction(
            probabilities,
            homeLambda,
            awayLambda,
            state.matches,
            homeStats.actualMatches,
            awayStats.actualMatches,
        )
    }

    internal fun validate(match: HistoricalMatch) {
        require(match.homeTeam.isNotBlank() && match.awayTeam.isNotBlank())
        require(teamKey(match.homeTeam) != teamKey(match.awayTeam))
        require(match.homeGoals >= 0 && match.awayGoals >= 0)
    }
}

/** Streaming prequential expected-goals evaluation with shared as-of availability batches. */
object GoalExpectationWalkForwardEngine {
    fun evaluate(
        history: Collection<HistoricalMatch>,
        targets: Collection<WalkForwardTarget>,
        config: GoalExpectationConfig = GoalExpectationConfig(),
    ): GoalExpectationWalkForwardReport {
        require(targets.map { it.id }.distinct().size == targets.size) { "Target IDs must be unique." }
        targets.forEach { target ->
            require(target.id.isNotBlank())
            require(target.homeTeam.isNotBlank() && target.awayTeam.isNotBlank())
            require(teamKey(target.homeTeam) != teamKey(target.awayTeam))
            require(target.homeGoals >= 0 && target.awayGoals >= 0)
        }
        val orderedHistory = history.filter { it.safeAvailabilityMillisOrNull() != null }.sortedWith(
            compareBy<HistoricalMatch> { requireNotNull(it.safeAvailabilityMillisOrNull()) }
                .thenBy { it.kickoffMillis }
                .thenBy { teamKey(it.homeTeam) }
                .thenBy { teamKey(it.awayTeam) },
        )
        val targetsByCutoff = targets.groupBy { it.forecastCutoffMillis }.toSortedMap()
        val state = GoalModelState()
        var historyIndex = 0
        val forecasts = mutableListOf<GoalExpectationWalkForwardPrediction>()

        for ((cutoff, sameCutoffTargets) in targetsByCutoff) {
            while (historyIndex < orderedHistory.size && requireNotNull(orderedHistory[historyIndex].safeAvailabilityMillisOrNull()) < cutoff) {
                val availability = requireNotNull(orderedHistory[historyIndex].safeAvailabilityMillisOrNull())
                var batchEnd = historyIndex + 1
                while (batchEnd < orderedHistory.size && orderedHistory[batchEnd].safeAvailabilityMillisOrNull() == availability) batchEnd++
                state.decayTo(availability, config.timeDecayHalfLifeDays)
                for (index in historyIndex until batchEnd) state.add(orderedHistory[index])
                historyIndex = batchEnd
            }
            state.decayTo(cutoff, config.timeDecayHalfLifeDays)
            for (target in sameCutoffTargets.sortedWith(
                compareBy<WalkForwardTarget> { teamKey(it.homeTeam) }.thenBy { teamKey(it.awayTeam) }.thenBy { it.id },
            )) {
                val prediction = GoalExpectationEngine.predict(state, target.homeTeam, target.awayTeam, config) ?: continue
                val observed = observed(target.homeGoals, target.awayGoals)
                forecasts += GoalExpectationWalkForwardPrediction(
                    targetId = target.id,
                    forecastCutoffMillis = cutoff,
                    probabilities = prediction.probabilities,
                    homeExpectedGoals = prediction.homeExpectedGoals,
                    awayExpectedGoals = prediction.awayExpectedGoals,
                    historyMatchCount = prediction.historyMatchCount,
                    observedHomeGoals = target.homeGoals,
                    observedAwayGoals = target.awayGoals,
                    brierScore = ForecastMetrics.brierScore(prediction.probabilities, observed),
                    logLoss = ForecastMetrics.logLoss(prediction.probabilities, observed),
                )
            }
        }
        return GoalExpectationWalkForwardReport(
            targetCount = targets.size,
            forecastCount = forecasts.size,
            skippedForInsufficientHistory = targets.size - forecasts.size,
            meanBrierScore = forecasts.map { it.brierScore }.average().takeIf { forecasts.isNotEmpty() },
            meanLogLoss = forecasts.map { it.logLoss }.average().takeIf { forecasts.isNotEmpty() },
            predictions = forecasts,
        )
    }

    private fun observed(homeGoals: Int, awayGoals: Int): OutcomeProbabilities = when {
        homeGoals > awayGoals -> OutcomeProbabilities(1.0, 0.0, 0.0)
        homeGoals == awayGoals -> OutcomeProbabilities(0.0, 1.0, 0.0)
        else -> OutcomeProbabilities(0.0, 0.0, 1.0)
    }
}
