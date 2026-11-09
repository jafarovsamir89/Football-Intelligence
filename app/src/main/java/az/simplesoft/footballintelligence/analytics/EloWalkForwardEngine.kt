package az.simplesoft.footballintelligence.analytics

data class WalkForwardTarget(
    val id: String,
    val forecastCutoffMillis: Long,
    val homeTeam: String,
    val awayTeam: String,
    val homeGoals: Int,
    val awayGoals: Int,
)

data class WalkForwardPrediction(
    val targetId: String,
    val forecastCutoffMillis: Long,
    val probabilities: OutcomeProbabilities,
    val observedHomeGoals: Int,
    val observedAwayGoals: Int,
    val brierScore: Double,
    val logLoss: Double,
)

data class EloWalkForwardReport(
    val targetCount: Int,
    val forecastCount: Int,
    val skippedForInsufficientHistory: Int,
    val meanBrierScore: Double?,
    val meanLogLoss: Double?,
    val predictions: List<WalkForwardPrediction>,
)

/** Date- or kickoff-ordered retrospective evaluation. History timestamps mean safe availability time. */
object EloWalkForwardEngine {
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

        val forecasts = mutableListOf<WalkForwardPrediction>()
        val targetsByCutoff = targets.groupBy { it.forecastCutoffMillis }.toSortedMap()
        for ((cutoff, sameTimeTargets) in targetsByCutoff) {
            // One fit per timestamp means all matches at a shared date/kickoff see the same history.
            val model = EloEngine.fit(history, cutoff, config)
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
}
