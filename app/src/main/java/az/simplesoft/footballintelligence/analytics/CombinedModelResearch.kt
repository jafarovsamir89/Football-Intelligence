package az.simplesoft.footballintelligence.analytics

import kotlin.math.exp
import kotlin.math.ln

internal data class PairedModelForecast(
    val elo: OutcomeProbabilities,
    val poisson: OutcomeProbabilities,
    val observed: OutcomeProbabilities,
)

data class CombinedModelCandidate(
    /** Share assigned to Elo; the remaining weight is assigned to Poisson. */
    val eloWeight: Double,
    val metrics: EloExperimentMetrics,
    /** Shared multiclass temperature fitted by validation Log Loss. */
    val temperature: Double = 1.0,
)

data class CombinedModelCalibrationMetrics(
    val forecastCount: Int = 0,
    val eloMeanOneVsRestEce: Double? = null,
    val poissonMeanOneVsRestEce: Double? = null,
    val blendMeanOneVsRestEce: Double? = null,
)

/** Retrospective temperature-scaled Elo/Poisson blend study; never changes production forecasts. */
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
    val validationCalibration: CombinedModelCalibrationMetrics = CombinedModelCalibrationMetrics(),
    val holdoutCalibration: CombinedModelCalibrationMetrics = CombinedModelCalibrationMetrics(),
)

internal object CombinedModelResearch {
    const val DEFAULT_MINIMUM_VALIDATION_FORECASTS = 500
    const val DEFAULT_MINIMUM_HOLDOUT_FORECASTS = 500
    private const val IMPROVEMENT_EPSILON = 1e-9
    val ELO_WEIGHT_GRID: List<Double> = (1..9).map { it / 10.0 }
    val TEMPERATURE_GRID: List<Double> = listOf(0.70, 0.80, 0.90, 1.0, 1.10, 1.25, 1.50, 1.75, 2.0)

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
        val candidates = ELO_WEIGHT_GRID.flatMap { weight ->
            TEMPERATURE_GRID.map { temperature ->
                CombinedModelCandidate(
                    eloWeight = weight,
                    metrics = summarize(validation) { row ->
                        temperatureScale(blend(row.elo, row.poisson, weight), temperature)
                    },
                    temperature = temperature,
                )
            }
        }
        val enoughValidation = validation.size >= minimumValidationForecasts
        val order = compareBy<CombinedModelCandidate> { it.metrics.meanLogLoss ?: Double.POSITIVE_INFINITY }
            .thenBy { it.metrics.meanBrierScore ?: Double.POSITIVE_INFINITY }
            .thenBy { kotlin.math.abs(it.temperature - 1.0) }
            .thenBy { kotlin.math.abs(it.eloWeight - 0.5) }
            .thenBy { it.eloWeight }
            .thenBy { it.temperature }
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
            temperatureScale(blend(it.elo, it.poisson, best.eloWeight), best.temperature)
        } else null
        val holdoutGatePassed = best != null && holdout.size >= minimumHoldoutForecasts &&
            holdoutMetrics != null && improvesBoth(
                CombinedModelCandidate(best.eloWeight, holdoutMetrics, best.temperature),
                holdoutElo,
                holdoutPoisson,
            )
        val validationCalibration = calibrationMetrics(validation, best)
        val holdoutCalibration = calibrationMetrics(holdout, best)

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
            validationCalibration = validationCalibration,
            holdoutCalibration = holdoutCalibration,
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

    /** Multiclass temperature scaling; T>1 softens and T<1 sharpens probabilities. */
    fun temperatureScale(probabilities: OutcomeProbabilities, temperature: Double): OutcomeProbabilities {
        require(temperature.isFinite() && temperature > 0.0)
        val logits = probabilities.values().map { ln(it.coerceAtLeast(1e-15)) / temperature }
        val maxLogit = logits.maxOrNull()!!
        val exponentials = logits.map { exp(it - maxLogit) }
        val denominator = exponentials.sum()
        return OutcomeProbabilities(
            home = exponentials[0] / denominator,
            draw = exponentials[1] / denominator,
            away = exponentials[2] / denominator,
        )
    }

    private fun calibrationMetrics(
        rows: List<PairedModelForecast>,
        candidate: CombinedModelCandidate?,
    ): CombinedModelCalibrationMetrics {
        fun ece(probabilities: (PairedModelForecast) -> OutcomeProbabilities): Double? =
            CalibrationDiagnostics.evaluate(rows.map { row ->
                CalibrationObservation(
                    probabilities = probabilities(row),
                    observedOutcome = when {
                        row.observed.home == 1.0 -> ForecastOutcome.HOME
                        row.observed.draw == 1.0 -> ForecastOutcome.DRAW
                        else -> ForecastOutcome.AWAY
                    },
                )
            }).meanOneVsRestEce

        return CombinedModelCalibrationMetrics(
            forecastCount = rows.size,
            eloMeanOneVsRestEce = ece { it.elo },
            poissonMeanOneVsRestEce = ece { it.poisson },
            blendMeanOneVsRestEce = candidate?.let { selected ->
                ece { row ->
                    temperatureScale(blend(row.elo, row.poisson, selected.eloWeight), selected.temperature)
                }
            },
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
