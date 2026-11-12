package az.simplesoft.footballintelligence.analytics

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
)

/** Compares goal-based baselines to Elo on identical forecastable chronological holdout rows. */
object GoalModelComparisonEngine {
    /** Conservative values valid across the model's bounded 0.05–5.0 goal rates. */
    val DIXON_COLES_RHO_GRID: List<Double> = listOf(-0.15, -0.10, -0.05, 0.0, 0.02)
    const val DEFAULT_MINIMUM_VALIDATION_FORECASTS_FOR_RHO_SELECTION = 100

    private data class ModelScore(val brier: Double, val logLoss: Double)
    private data class FixtureKey(val competition: String, val targetId: String)

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

    private fun List<EloExperimentDataset>.selectTargets(
        predicate: (Long) -> Boolean,
    ): List<EloExperimentDataset> = mapNotNull { dataset ->
        val targets = dataset.targets.filter { predicate(it.forecastCutoffMillis) }
        targets.takeIf { it.isNotEmpty() }?.let { dataset.copy(targets = it) }
    }
}
