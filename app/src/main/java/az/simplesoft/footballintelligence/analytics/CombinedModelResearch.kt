package az.simplesoft.footballintelligence.analytics

internal data class PairedModelForecast(
    val elo: OutcomeProbabilities,
    val poisson: OutcomeProbabilities,
    val observed: OutcomeProbabilities,
)

data class CombinedModelCandidate(
    /** Share assigned to Elo; the remaining weight is assigned to Poisson. */
    val eloWeight: Double,
    val metrics: EloExperimentMetrics,
)

/** Retrospective Elo/Poisson blend study. It never changes production forecasts. */
data class CombinedModelResearchReport(
    val validationPairedForecastCount: Int = 0,
    val minimumValidationForecasts: Int = CombinedModelResearch.DEFAULT_MINIMUM_VALIDATION_FORECASTS,
    val validationEloMetrics: EloExperimentMetrics = emptyModelMetrics(),
    val validationPoissonMetrics: EloExperimentMetrics = emptyModelMetrics(),
    val validationCandidates: List<CombinedModelCandidate> = emptyList(),
    /** Best predeclared blend on validation, present only after the sample gate is met. */
    val validationCandidate: CombinedModelCandidate? = null,
    /** True only when that blend beats both simple models on both proper scores. */
    val validationGatePassed: Boolean = false,
    val minimumHoldoutForecasts: Int = CombinedModelResearch.DEFAULT_MINIMUM_HOLDOUT_FORECASTS,
    val holdoutPairedForecastCount: Int = 0,
    val holdoutEloMetrics: EloExperimentMetrics = emptyModelMetrics(),
    val holdoutPoissonMetrics: EloExperimentMetrics = emptyModelMetrics(),
    /** The validation-selected weight is evaluated here without further tuning. */
    val holdoutMetrics: EloExperimentMetrics? = null,
    /** True only when the blend clears the same two-score improvement gate on holdout. */
    val holdoutGatePassed: Boolean = false,
)

internal object CombinedModelResearch {
    const val DEFAULT_MINIMUM_VALIDATION_FORECASTS = 500
    const val DEFAULT_MINIMUM_HOLDOUT_FORECASTS = 500
    private const val IMPROVEMENT_EPSILON = 1e-9
    val ELO_WEIGHT_GRID: List<Double> = (1..9).map { it / 10.0 }

    fun evaluate(
        validation: List<PairedModelForecast>,
        holdout: List<PairedModelForecast>,
        minimumValidationForecasts: Int = DEFAULT_MINIMUM_VALIDATION_FORECASTS,
        minimumHoldoutForecasts: Int = DEFAULT_MINIMUM_HOLDOUT_FORECASTS,
    ): CombinedModelResearchReport {
        require(minimumValidationForecasts >= 1)
        require(minimumHoldoutForecasts >= 1)
        val validationElo = summarize(validation) { it.elo }
        val validationPoisson = summarize(validation) { it.poisson }
        val holdoutElo = summarize(holdout) { it.elo }
        val holdoutPoisson = summarize(holdout) { it.poisson }
        val candidates = ELO_WEIGHT_GRID.map { weight ->
            CombinedModelCandidate(weight, summarize(validation) { blend(it.elo, it.poisson, weight) })
        }
        val enoughValidation = validation.size >= minimumValidationForecasts
        val order = compareBy<CombinedModelCandidate> { it.metrics.meanLogLoss ?: Double.POSITIVE_INFINITY }
            .thenBy { it.metrics.meanBrierScore ?: Double.POSITIVE_INFINITY }
            .thenBy { kotlin.math.abs(it.eloWeight - 0.5) }
            .thenBy { it.eloWeight }
        fun improvesBoth(candidate: CombinedModelCandidate, eloMetrics: EloExperimentMetrics, poissonMetrics: EloExperimentMetrics): Boolean {
            val blendBrier = candidate.metrics.meanBrierScore
            val blendLogLoss = candidate.metrics.meanLogLoss
            val eloBrier = eloMetrics.meanBrierScore
            val eloLogLoss = eloMetrics.meanLogLoss
            val poissonBrier = poissonMetrics.meanBrierScore
            val poissonLogLoss = poissonMetrics.meanLogLoss
            return blendBrier != null && blendLogLoss != null &&
                eloBrier != null && eloLogLoss != null && poissonBrier != null && poissonLogLoss != null &&
                blendBrier < minOf(eloBrier, poissonBrier) - IMPROVEMENT_EPSILON &&
                blendLogLoss < minOf(eloLogLoss, poissonLogLoss) - IMPROVEMENT_EPSILON
        }
        val bestByLogLoss = candidates.minWithOrNull(order)
        val validationPassingCandidates = candidates.filter { improvesBoth(it, validationElo, validationPoisson) }
        val best = (validationPassingCandidates.minWithOrNull(order) ?: bestByLogLoss)
            .takeIf { enoughValidation }
        val validationGatePassed = best?.let { improvesBoth(it, validationElo, validationPoisson) } ?: false
        val holdoutMetrics = if (best != null) summarize(holdout) {
            blend(it.elo, it.poisson, best.eloWeight)
        } else null
        val holdoutGatePassed = best != null && holdout.size >= minimumHoldoutForecasts &&
            holdoutMetrics != null && improvesBoth(
                CombinedModelCandidate(best.eloWeight, holdoutMetrics),
                holdoutElo,
                holdoutPoisson,
            )

        return CombinedModelResearchReport(
            validationPairedForecastCount = validation.size,
            minimumValidationForecasts = minimumValidationForecasts,
            validationEloMetrics = validationElo,
            validationPoissonMetrics = validationPoisson,
            validationCandidates = candidates,
            validationCandidate = best,
            validationGatePassed = validationGatePassed,
            minimumHoldoutForecasts = minimumHoldoutForecasts,
            holdoutPairedForecastCount = if (best != null) holdout.size else 0,
            holdoutEloMetrics = holdoutElo,
            holdoutPoissonMetrics = holdoutPoisson,
            holdoutMetrics = holdoutMetrics,
            holdoutGatePassed = holdoutGatePassed,
        )
    }

    fun blend(
        elo: OutcomeProbabilities,
        poisson: OutcomeProbabilities,
        eloWeight: Double,
    ): OutcomeProbabilities {
        require(eloWeight.isFinite() && eloWeight in 0.0..1.0)
        val poissonWeight = 1.0 - eloWeight
        return OutcomeProbabilities(
            home = elo.home * eloWeight + poisson.home * poissonWeight,
            draw = elo.draw * eloWeight + poisson.draw * poissonWeight,
            away = elo.away * eloWeight + poisson.away * poissonWeight,
        )
    }

    private fun summarize(
        rows: List<PairedModelForecast>,
        probabilities: (PairedModelForecast) -> OutcomeProbabilities,
    ): EloExperimentMetrics {
        if (rows.isEmpty()) return emptyModelMetrics()
        var brierSum = 0.0
        var logLossSum = 0.0
        rows.forEach { row ->
            val forecast = probabilities(row)
            brierSum += ForecastMetrics.brierScore(forecast, row.observed)
            logLossSum += ForecastMetrics.logLoss(forecast, row.observed)
        }
        return EloExperimentMetrics(
            targetCount = rows.size,
            forecastCount = rows.size,
            meanBrierScore = brierSum / rows.size,
            meanLogLoss = logLossSum / rows.size,
        )
    }
}

private fun emptyModelMetrics() = EloExperimentMetrics(0, 0, null, null)
