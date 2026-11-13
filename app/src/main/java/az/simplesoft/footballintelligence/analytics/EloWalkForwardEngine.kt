package az.simplesoft.footballintelligence.analytics

import kotlin.math.pow

data class WalkForwardTarget(
    val id: String,
    val forecastCutoffMillis: Long,
    val homeTeam: String,
    val awayTeam: String,
    val homeGoals: Int,
    val awayGoals: Int,
    /** Exact source season label, when supplied. Never infer it from the event date. */
    val season: String? = null,
)

data class WalkForwardPrediction(
    val targetId: String,
    val forecastCutoffMillis: Long,
    val probabilities: OutcomeProbabilities,
    val observedHomeGoals: Int,
    val observedAwayGoals: Int,
    val brierScore: Double,
    val logLoss: Double,
    /** Same-cutoff historical 1X2 frequency reference, smoothed with one pseudo-count per outcome. */
    val frequencyBaselineProbabilities: OutcomeProbabilities? = null,
    val frequencyBaselineBrierScore: Double? = null,
    val frequencyBaselineLogLoss: Double? = null,
)

data class EloWalkForwardReport(
    val targetCount: Int,
    val forecastCount: Int,
    val skippedForInsufficientHistory: Int,
    val meanBrierScore: Double?,
    val meanLogLoss: Double?,
    val predictions: List<WalkForwardPrediction>,
) {
    val meanFrequencyBaselineBrierScore: Double?
        get() = predictions.mapNotNull { it.frequencyBaselineBrierScore }.average().takeIf { it.isFinite() }

    val meanFrequencyBaselineLogLoss: Double?
        get() = predictions.mapNotNull { it.frequencyBaselineLogLoss }.average().takeIf { it.isFinite() }
}

/** Date- or kickoff-ordered retrospective evaluation. History timestamps mean safe availability time. */
object EloWalkForwardEngine {
    /** Compatibility alias; retrospective and prospective reports share this fixed prior. */
    const val FREQUENCY_BASELINE_PSEUDOCOUNT_PER_OUTCOME = OutcomeFrequencyBaseline.PSEUDOCOUNT_PER_OUTCOME

    fun evaluate(
        history: Collection<HistoricalMatch>,
        targets: Collection<WalkForwardTarget>,
        config: EloConfig = EloConfig(),
        minimumHistoryMatches: Int = EloEngine.MIN_HISTORY_MATCHES_FOR_FORECAST,
        minimumMatchesPerTeam: Int = EloEngine.MIN_TEAM_MATCHES_FOR_FORECAST,
    ): EloWalkForwardReport {
        require(minimumHistoryMatches >= 0)
        require(minimumMatchesPerTeam >= 0)
        require(targets.map { it.id }.distinct().size == targets.size) { "Target IDs must be unique." }
        targets.forEach(::validateTarget)

        // Stream availability batches once instead of refitting the full history for every
        // target. This keeps large local histories practical and preserves strict as-of rules.
        val orderedHistory = history.filter { it.safeAvailabilityMillisOrNull() != null }.sortedWith(
            compareBy<HistoricalMatch> { requireNotNull(it.safeAvailabilityMillisOrNull()) }
                .thenBy { it.kickoffMillis }
                .thenBy { teamKey(it.homeTeam) }
                .thenBy { teamKey(it.awayTeam) },
        )
        val forecasts = mutableListOf<WalkForwardPrediction>()
        val targetsByCutoff = targets.groupBy { it.forecastCutoffMillis }.toSortedMap()
        val ratings = mutableMapOf<String, Double>()
        val played = mutableMapOf<String, Int>()
        val resultCounts = IntArray(3)
        var matchesUsed = 0
        var drawCount = 0
        var historyIndex = 0

        for ((cutoff, sameTimeTargets) in targetsByCutoff) {
            while (historyIndex < orderedHistory.size && requireNotNull(orderedHistory[historyIndex].safeAvailabilityMillisOrNull()) < cutoff) {
                val availability = requireNotNull(orderedHistory[historyIndex].safeAvailabilityMillisOrNull())
                var batchEnd = historyIndex + 1
                while (batchEnd < orderedHistory.size && orderedHistory[batchEnd].safeAvailabilityMillisOrNull() == availability) batchEnd++

                // Results becoming available together use one shared pre-batch rating snapshot.
                val ratingDeltas = mutableMapOf<String, Double>()
                for (index in historyIndex until batchEnd) {
                    val match = orderedHistory[index]
                    validateHistoryMatch(match)
                    val home = teamKey(match.homeTeam)
                    val away = teamKey(match.awayTeam)
                    val homeRating = ratings[home] ?: config.initialRating
                    val awayRating = ratings[away] ?: config.initialRating
                    val expectedHome = 1.0 / (1.0 + 10.0.pow((awayRating - homeRating - config.homeAdvantage) / 400.0))
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
                    when {
                        match.homeGoals > match.awayGoals -> resultCounts[0]++
                        match.homeGoals == match.awayGoals -> resultCounts[1]++
                        else -> resultCounts[2]++
                    }
                    if (actualHome == 0.5) drawCount++
                    matchesUsed++
                }
                for ((team, delta) in ratingDeltas) ratings[team] = (ratings[team] ?: config.initialRating) + delta
                historyIndex = batchEnd
            }

            val drawProbability = (drawCount + config.priorDrawProbability * config.priorMatchCount) /
                (matchesUsed + config.priorMatchCount)
            val model = EloRatings(ratings.toMap(), matchesUsed, drawProbability, played.toMap(), config)
            val frequencyBaseline = OutcomeFrequencyBaseline.fromCounts(
                resultCounts[0], resultCounts[1], resultCounts[2],
            )
            val orderedTargets = sameTimeTargets.sortedWith(
                compareBy<WalkForwardTarget> { teamKey(it.homeTeam) }
                    .thenBy { teamKey(it.awayTeam) }
                    .thenBy { it.id }
            )
            for (target in orderedTargets) {
                if (model.matchesUsed < minimumHistoryMatches ||
                    model.gamesFor(target.homeTeam) < minimumMatchesPerTeam ||
                    model.gamesFor(target.awayTeam) < minimumMatchesPerTeam
                ) continue

                val probabilities = EloEngine.predict1x2(model, target.homeTeam, target.awayTeam)
                val observed = when {
                    target.homeGoals > target.awayGoals -> OutcomeProbabilities(1.0, 0.0, 0.0)
                    target.homeGoals == target.awayGoals -> OutcomeProbabilities(0.0, 1.0, 0.0)
                    else -> OutcomeProbabilities(0.0, 0.0, 1.0)
                }
                forecasts.add(
                    WalkForwardPrediction(
                        target.id,
                        cutoff,
                        probabilities,
                        target.homeGoals,
                        target.awayGoals,
                        ForecastMetrics.brierScore(probabilities, observed),
                        ForecastMetrics.logLoss(probabilities, observed),
                        frequencyBaseline,
                        ForecastMetrics.brierScore(frequencyBaseline, observed),
                        ForecastMetrics.logLoss(frequencyBaseline, observed),
                    )
                )
            }
        }
        val brier = forecasts.map { it.brierScore }.average().takeIf { forecasts.isNotEmpty() }
        val logLoss = forecasts.map { it.logLoss }.average().takeIf { forecasts.isNotEmpty() }
        return EloWalkForwardReport(
            targetCount = targets.size,
            forecastCount = forecasts.size,
            skippedForInsufficientHistory = targets.size - forecasts.size,
            meanBrierScore = brier,
            meanLogLoss = logLoss,
            predictions = forecasts,
        )
    }

    private fun validateTarget(target: WalkForwardTarget) {
        require(target.id.isNotBlank()) { "Target ID cannot be blank." }
        require(target.homeTeam.isNotBlank() && target.awayTeam.isNotBlank()) { "Team names cannot be blank." }
        require(teamKey(target.homeTeam) != teamKey(target.awayTeam)) { "A team cannot play itself." }
        require(target.homeGoals >= 0 && target.awayGoals >= 0) { "Goals cannot be negative." }
    }

    private fun validateHistoryMatch(match: HistoricalMatch) {
        require(match.homeTeam.isNotBlank() && match.awayTeam.isNotBlank()) { "Team names cannot be blank." }
        require(teamKey(match.homeTeam) != teamKey(match.awayTeam)) { "A team cannot play itself." }
        require(match.homeGoals >= 0 && match.awayGoals >= 0) { "Goals cannot be negative." }
    }
}
