package az.simplesoft.footballintelligence.analytics

import java.util.Random
import kotlin.math.ceil
import kotlin.math.floor

data class GoalModelComparisonReport(
    val validationStartMillis: Long?,
    val holdoutStartMillis: Long?,
    val validationTargetCount: Int,
    val validationGoalMetrics: EloExperimentMetrics,
    val validationHomeAwayMetrics: EloExperimentMetrics,
    val selectedRhoValidationMetrics: EloExperimentMetrics?,
    val rhoCandidates: List<GoalRhoGridCandidate>,
    val selectedDixonColesRho: Double?,
    val rhoSelectionAvailable: Boolean,
    val minimumValidationForecastsForRhoSelection: Int,
    val holdoutTargetCount: Int,
    val eloHoldoutForecastCount: Int,
    val goalHoldoutForecastCount: Int,
    val homeAwayHoldoutForecastCount: Int,
    val pairedHoldoutTargetCount: Int,
    val homeAwayPairedHoldoutTargetCount: Int,
    val eloPairedHoldout: EloExperimentMetrics,
    val goalPairedHoldout: EloExperimentMetrics,
    val eloHomeAwayPairedHoldout: EloExperimentMetrics,
    val homeAwayPairedHoldout: EloExperimentMetrics,
    val eloMinusPoissonBrier95: MetricConfidenceInterval95?,
    val eloMinusPoissonLogLoss95: MetricConfidenceInterval95?,
    val eloMinusHomeAwayBrier95: MetricConfidenceInterval95?,
    val eloMinusHomeAwayLogLoss95: MetricConfidenceInterval95?,
    val minimumPairedFixturesForIntervals: Int,
    val selectedEloConfig: EloConfig,
    val usedValidationSelectedEloConfig: Boolean,
    val goalConfig: GoalExpectationConfig,
    val homeAwayConfig: HomeAwayPerformanceConfig,
)

data class GoalRhoGridCandidate(
    val rho: Double,
    val validation: EloExperimentMetrics,
)

data class AutomaticBacktestReports(
    val eloWalkForward: EloWalkForwardReport,
    val eloParameterExperiment: EloChronologicalExperimentReport,
    val goalModelComparison: GoalModelComparisonReport,
    val eloByCompetitionSeason: List<CompetitionSeasonWalkForwardMetrics> = emptyList(),
)

/** Compares goal-based baselines to Elo on identical forecastable chronological holdout rows. */
object GoalModelComparisonEngine {
    /** Conservative values valid across the model's bounded 0.05–5.0 goal rates. */
    val DIXON_COLES_RHO_GRID: List<Double> = listOf(-0.15, -0.10, -0.05, 0.0, 0.02)
    const val DEFAULT_MINIMUM_VALIDATION_FORECASTS_FOR_RHO_SELECTION = 100
    const val MINIMUM_PAIRED_FIXTURES_FOR_INTERVALS = 30
    private const val BOOTSTRAP_RESAMPLES = 2_000

    private data class ModelScore(val brier: Double, val logLoss: Double)
    private data class FixtureKey(val competition: String, val targetId: String)
    private data class PairedDifference(val brier: Double, val logLoss: Double)
    private data class PairedIntervals(
        val brier: MetricConfidenceInterval95,
        val logLoss: MetricConfidenceInterval95,
    )

    fun evaluate(
        datasets: List<EloExperimentDataset>,
        eloExperiment: EloChronologicalExperimentReport,
        goalConfig: GoalExpectationConfig = GoalExpectationConfig(),
        minimumValidationForecastsForRhoSelection: Int = DEFAULT_MINIMUM_VALIDATION_FORECASTS_FOR_RHO_SELECTION,
        homeAwayConfig: HomeAwayPerformanceConfig = HomeAwayPerformanceConfig(),
    ): GoalModelComparisonReport {
        require(minimumValidationForecastsForRhoSelection >= 1)
        val baseGoalConfig = goalConfig.copy(dixonColesRho = null)
        val eloConfig = eloExperiment.selectedConfig ?: EloConfig()
        val validationStart = eloExperiment.validationStartMillis
        val holdoutStart = eloExperiment.testStartMillis
        if (validationStart == null || holdoutStart == null) {
            val empty = EloExperimentMetrics(0, 0, null, null)
            return GoalModelComparisonReport(
                validationStartMillis = validationStart,
                holdoutStartMillis = holdoutStart,
                validationTargetCount = 0,
                validationGoalMetrics = empty,
                validationHomeAwayMetrics = empty,
                selectedRhoValidationMetrics = null,
                rhoCandidates = emptyList(),
                selectedDixonColesRho = null,
                rhoSelectionAvailable = false,
                minimumValidationForecastsForRhoSelection = minimumValidationForecastsForRhoSelection,
                holdoutTargetCount = 0,
                eloHoldoutForecastCount = 0,
                goalHoldoutForecastCount = 0,
                homeAwayHoldoutForecastCount = 0,
                pairedHoldoutTargetCount = 0,
                homeAwayPairedHoldoutTargetCount = 0,
                eloPairedHoldout = empty,
                goalPairedHoldout = empty,
                eloHomeAwayPairedHoldout = empty,
                homeAwayPairedHoldout = empty,
                eloMinusPoissonBrier95 = null,
                eloMinusPoissonLogLoss95 = null,
                eloMinusHomeAwayBrier95 = null,
                eloMinusHomeAwayLogLoss95 = null,
                minimumPairedFixturesForIntervals = MINIMUM_PAIRED_FIXTURES_FOR_INTERVALS,
                selectedEloConfig = eloConfig,
                usedValidationSelectedEloConfig = eloExperiment.selectionAvailable,
                goalConfig = baseGoalConfig,
                homeAwayConfig = homeAwayConfig,
            )
        }

        val validationDatasets = datasets.selectTargets { it >= validationStart && it < holdoutStart }
        val holdoutDatasets = datasets.selectTargets { it >= holdoutStart }
        val validationCandidates = DIXON_COLES_RHO_GRID.map { rho ->
            GoalRhoGridCandidate(
                rho = rho,
                validation = summarizeGoals(validationDatasets, baseGoalConfig.copy(dixonColesRho = rho)),
            )
        }.sortedWith(
            compareBy<GoalRhoGridCandidate> { it.validation.meanLogLoss ?: Double.POSITIVE_INFINITY }
                .thenBy { it.validation.meanBrierScore ?: Double.POSITIVE_INFINITY }
                .thenBy { kotlin.math.abs(it.rho) }
                .thenBy { it.rho },
        )
        val selectedRho = validationCandidates.firstOrNull {
            it.validation.forecastCount >= minimumValidationForecastsForRhoSelection &&
                it.validation.meanLogLoss != null && it.validation.meanBrierScore != null
        }
        val selectedGoalConfig = baseGoalConfig.copy(dixonColesRho = selectedRho?.rho)
        val validationGoalMetrics = summarizeGoals(validationDatasets, baseGoalConfig)
        val validationHomeAwayMetrics = summarizeHomeAway(validationDatasets, homeAwayConfig)

        val eloScores = mutableMapOf<FixtureKey, ModelScore>()
        val goalScores = mutableMapOf<FixtureKey, ModelScore>()
        val homeAwayScores = mutableMapOf<FixtureKey, ModelScore>()
        var eloForecastCount = 0
        var goalForecastCount = 0
        var homeAwayForecastCount = 0
        for (dataset in holdoutDatasets) {
            val eloReport = EloWalkForwardEngine.evaluate(
                history = dataset.history,
                targets = dataset.targets,
                config = eloConfig,
            )
            val goalReport = GoalExpectationWalkForwardEngine.evaluate(
                history = dataset.history,
                targets = dataset.targets,
                config = selectedGoalConfig,
            )
            val homeAwayReport = HomeAwayPerformanceWalkForwardEngine.evaluate(
                history = dataset.history,
                targets = dataset.targets,
                config = homeAwayConfig,
            )
            eloForecastCount += eloReport.forecastCount
            goalForecastCount += goalReport.forecastCount
            homeAwayForecastCount += homeAwayReport.forecastCount
            eloReport.predictions.forEach { prediction ->
                eloScores[FixtureKey(dataset.competition, prediction.targetId)] =
                    ModelScore(prediction.brierScore, prediction.logLoss)
            }
            goalReport.predictions.forEach { prediction ->
                goalScores[FixtureKey(dataset.competition, prediction.targetId)] =
                    ModelScore(prediction.brierScore, prediction.logLoss)
            }
            homeAwayReport.predictions.forEach { prediction ->
                homeAwayScores[FixtureKey(dataset.competition, prediction.targetId)] =
                    ModelScore(prediction.brierScore, prediction.logLoss)
            }
        }
        val commonKeys = eloScores.keys.intersect(goalScores.keys)
        val commonHomeAwayKeys = eloScores.keys.intersect(homeAwayScores.keys)
        val commonElo = commonKeys.mapNotNull(eloScores::get)
        val commonGoal = commonKeys.mapNotNull(goalScores::get)
        val commonHomeAwayElo = commonHomeAwayKeys.mapNotNull(eloScores::get)
        val commonHomeAway = commonHomeAwayKeys.mapNotNull(homeAwayScores::get)
        val eloPaired = pairedMetrics(commonKeys.size, commonElo)
        val goalPaired = pairedMetrics(commonKeys.size, commonGoal)
        val eloHomeAwayPaired = pairedMetrics(commonHomeAwayKeys.size, commonHomeAwayElo)
        val homeAwayPaired = pairedMetrics(commonHomeAwayKeys.size, commonHomeAway)
        val eloMinusPoissonIntervals = pairedBootstrapIntervals("elo-poisson", commonKeys, eloScores, goalScores)
        val eloMinusHomeAwayIntervals = pairedBootstrapIntervals("elo-home-away", commonHomeAwayKeys, eloScores, homeAwayScores)
        return GoalModelComparisonReport(
            validationStartMillis = validationStart,
            holdoutStartMillis = holdoutStart,
            validationTargetCount = validationDatasets.sumOf { it.targets.size },
            validationGoalMetrics = validationGoalMetrics,
            validationHomeAwayMetrics = validationHomeAwayMetrics,
            holdoutTargetCount = holdoutDatasets.sumOf { it.targets.size },
            eloHoldoutForecastCount = eloForecastCount,
            goalHoldoutForecastCount = goalForecastCount,
            homeAwayHoldoutForecastCount = homeAwayForecastCount,
            pairedHoldoutTargetCount = commonKeys.size,
            homeAwayPairedHoldoutTargetCount = commonHomeAwayKeys.size,
            eloPairedHoldout = eloPaired,
            goalPairedHoldout = goalPaired,
            eloHomeAwayPairedHoldout = eloHomeAwayPaired,
            homeAwayPairedHoldout = homeAwayPaired,
            eloMinusPoissonBrier95 = eloMinusPoissonIntervals?.brier,
            eloMinusPoissonLogLoss95 = eloMinusPoissonIntervals?.logLoss,
            eloMinusHomeAwayBrier95 = eloMinusHomeAwayIntervals?.brier,
            eloMinusHomeAwayLogLoss95 = eloMinusHomeAwayIntervals?.logLoss,
            minimumPairedFixturesForIntervals = MINIMUM_PAIRED_FIXTURES_FOR_INTERVALS,
            selectedEloConfig = eloConfig,
            usedValidationSelectedEloConfig = eloExperiment.selectionAvailable,
            selectedRhoValidationMetrics = selectedRho?.validation,
            rhoCandidates = validationCandidates,
            selectedDixonColesRho = selectedRho?.rho,
            rhoSelectionAvailable = selectedRho != null,
            minimumValidationForecastsForRhoSelection = minimumValidationForecastsForRhoSelection,
            goalConfig = selectedGoalConfig,
            homeAwayConfig = homeAwayConfig,
        )
    }

    private fun summarizeGoals(
        datasets: List<EloExperimentDataset>,
        config: GoalExpectationConfig,
    ): EloExperimentMetrics {
        var targetCount = 0
        var forecastCount = 0
        var brierSum = 0.0
        var logLossSum = 0.0
        datasets.forEach { dataset ->
            val report = GoalExpectationWalkForwardEngine.evaluate(dataset.history, dataset.targets, config)
            targetCount += report.targetCount
            forecastCount += report.forecastCount
            brierSum += report.predictions.sumOf { it.brierScore }
            logLossSum += report.predictions.sumOf { it.logLoss }
        }
        return EloExperimentMetrics(
            targetCount = targetCount,
            forecastCount = forecastCount,
            meanBrierScore = (brierSum / forecastCount).takeIf { forecastCount > 0 },
            meanLogLoss = (logLossSum / forecastCount).takeIf { forecastCount > 0 },
        )
    }

    private fun summarizeHomeAway(
        datasets: List<EloExperimentDataset>,
        config: HomeAwayPerformanceConfig,
    ): EloExperimentMetrics {
        var targetCount = 0
        var forecastCount = 0
        var brierSum = 0.0
        var logLossSum = 0.0
        datasets.forEach { dataset ->
            val report = HomeAwayPerformanceWalkForwardEngine.evaluate(dataset.history, dataset.targets, config)
            targetCount += report.targetCount
            forecastCount += report.forecastCount
            brierSum += report.predictions.sumOf { it.brierScore }
            logLossSum += report.predictions.sumOf { it.logLoss }
        }
        return EloExperimentMetrics(
            targetCount = targetCount,
            forecastCount = forecastCount,
            meanBrierScore = (brierSum / forecastCount).takeIf { forecastCount > 0 },
            meanLogLoss = (logLossSum / forecastCount).takeIf { forecastCount > 0 },
        )
    }

    private fun pairedMetrics(targetCount: Int, scores: List<ModelScore>): EloExperimentMetrics =
        EloExperimentMetrics(
            targetCount = targetCount,
            forecastCount = scores.size,
            meanBrierScore = scores.map { it.brier }.average().takeIf { scores.isNotEmpty() },
            meanLogLoss = scores.map { it.logLoss }.average().takeIf { scores.isNotEmpty() },
        )

    /** Bootstrap fixture-wise Elo-minus-baseline score deltas; the holdout is never tuned. */
    private fun pairedBootstrapIntervals(
        label: String,
        fixtureKeys: Set<FixtureKey>,
        eloScores: Map<FixtureKey, ModelScore>,
        baselineScores: Map<FixtureKey, ModelScore>,
    ): PairedIntervals? {
        if (fixtureKeys.size < MINIMUM_PAIRED_FIXTURES_FOR_INTERVALS) return null
        val orderedKeys = fixtureKeys.sortedWith(compareBy<FixtureKey> { it.competition }.thenBy { it.targetId })
        val differences = orderedKeys.map { key ->
            val elo = requireNotNull(eloScores[key])
            val baseline = requireNotNull(baselineScores[key])
            PairedDifference(elo.brier - baseline.brier, elo.logLoss - baseline.logLoss)
        }
        val random = Random(stableSeed(label, orderedKeys))
        val brierSamples = DoubleArray(BOOTSTRAP_RESAMPLES)
        val logLossSamples = DoubleArray(BOOTSTRAP_RESAMPLES)
        repeat(BOOTSTRAP_RESAMPLES) { sampleIndex ->
            var brierSum = 0.0
            var logLossSum = 0.0
            repeat(differences.size) {
                val row = differences[random.nextInt(differences.size)]
                brierSum += row.brier
                logLossSum += row.logLoss
            }
            brierSamples[sampleIndex] = brierSum / differences.size
            logLossSamples[sampleIndex] = logLossSum / differences.size
        }
        return PairedIntervals(
            brier = percentileInterval95(brierSamples),
            logLoss = percentileInterval95(logLossSamples),
        )
    }

    private fun stableSeed(label: String, keys: List<FixtureKey>): Long {
        var seed = -3750763034362895579L
        fun mix(value: String) {
            value.forEach { character ->
                seed = (seed xor character.code.toLong()) * 1099511628211L
            }
            seed = (seed xor 0xffL) * 1099511628211L
        }
        mix(label)
        keys.forEach { key ->
            mix(key.competition)
            mix(key.targetId)
        }
        return seed
    }

    private fun percentileInterval95(samples: DoubleArray): MetricConfidenceInterval95 {
        val sorted = samples.sortedArray()
        fun percentile(fraction: Double): Double {
            val index = (sorted.lastIndex * fraction).coerceIn(0.0, sorted.lastIndex.toDouble())
            val lowerIndex = floor(index).toInt()
            val upperIndex = ceil(index).toInt()
            val weight = index - lowerIndex
            return sorted[lowerIndex] * (1.0 - weight) + sorted[upperIndex] * weight
        }
        return MetricConfidenceInterval95(percentile(0.025), percentile(0.975))
    }

    private fun List<EloExperimentDataset>.selectTargets(
        predicate: (Long) -> Boolean,
    ): List<EloExperimentDataset> = mapNotNull { dataset ->
        val targets = dataset.targets.filter { predicate(it.forecastCutoffMillis) }
        targets.takeIf { it.isNotEmpty() }?.let { dataset.copy(targets = it) }
    }
}
