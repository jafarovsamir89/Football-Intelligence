package az.simplesoft.footballintelligence.analytics

data class GoalModelComparisonReport(
    val validationStartMillis: Long?,
    val holdoutStartMillis: Long?,
    val validationTargetCount: Int,
    val validationGoalMetrics: EloExperimentMetrics,
    val holdoutTargetCount: Int,
    val eloHoldoutForecastCount: Int,
    val goalHoldoutForecastCount: Int,
    val pairedHoldoutTargetCount: Int,
    val eloPairedHoldout: EloExperimentMetrics,
    val goalPairedHoldout: EloExperimentMetrics,
    val selectedEloConfig: EloConfig,
    val usedValidationSelectedEloConfig: Boolean,
    val goalConfig: GoalExpectationConfig,
)

data class AutomaticBacktestReports(
    val eloWalkForward: EloWalkForwardReport,
    val eloParameterExperiment: EloChronologicalExperimentReport,
    val goalModelComparison: GoalModelComparisonReport,
)

/** Compares the fixed local Poisson goal model to Elo on the identical forecastable holdout rows. */
object GoalModelComparisonEngine {
    private data class ModelScore(val brier: Double, val logLoss: Double)
    private data class FixtureKey(val competition: String, val targetId: String)

    fun evaluate(
        datasets: List<EloExperimentDataset>,
        eloExperiment: EloChronologicalExperimentReport,
        goalConfig: GoalExpectationConfig = GoalExpectationConfig(),
    ): GoalModelComparisonReport {
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
                holdoutTargetCount = 0,
                eloHoldoutForecastCount = 0,
                goalHoldoutForecastCount = 0,
                pairedHoldoutTargetCount = 0,
                eloPairedHoldout = empty,
                goalPairedHoldout = empty,
                selectedEloConfig = eloConfig,
                usedValidationSelectedEloConfig = eloExperiment.selectionAvailable,
                goalConfig = goalConfig,
            )
        }

        val validationDatasets = datasets.selectTargets { it >= validationStart && it < holdoutStart }
        val holdoutDatasets = datasets.selectTargets { it >= holdoutStart }
        val goalValidationReports = validationDatasets.map { dataset ->
            GoalExpectationWalkForwardEngine.evaluate(dataset.history, dataset.targets, goalConfig)
        }
        val goalValidationPredictions = goalValidationReports.flatMap { it.predictions }
        val validationGoalMetrics = EloExperimentMetrics(
            targetCount = validationDatasets.sumOf { it.targets.size },
            forecastCount = goalValidationPredictions.size,
            meanBrierScore = goalValidationPredictions.map { it.brierScore }.average().takeIf { goalValidationPredictions.isNotEmpty() },
            meanLogLoss = goalValidationPredictions.map { it.logLoss }.average().takeIf { goalValidationPredictions.isNotEmpty() },
        )

        val eloScores = mutableMapOf<FixtureKey, ModelScore>()
        val goalScores = mutableMapOf<FixtureKey, ModelScore>()
        var eloForecastCount = 0
        var goalForecastCount = 0
        for (dataset in holdoutDatasets) {
            val eloReport = EloWalkForwardEngine.evaluate(
                history = dataset.history,
                targets = dataset.targets,
                config = eloConfig,
            )
            val goalReport = GoalExpectationWalkForwardEngine.evaluate(
                history = dataset.history,
                targets = dataset.targets,
                config = goalConfig,
            )
            eloForecastCount += eloReport.forecastCount
            goalForecastCount += goalReport.forecastCount
            eloReport.predictions.forEach { prediction ->
                eloScores[FixtureKey(dataset.competition, prediction.targetId)] =
                    ModelScore(prediction.brierScore, prediction.logLoss)
            }
            goalReport.predictions.forEach { prediction ->
                goalScores[FixtureKey(dataset.competition, prediction.targetId)] =
                    ModelScore(prediction.brierScore, prediction.logLoss)
            }
        }
        val commonKeys = eloScores.keys.intersect(goalScores.keys)
        val commonElo = commonKeys.mapNotNull(eloScores::get)
        val commonGoal = commonKeys.mapNotNull(goalScores::get)
        val eloPaired = EloExperimentMetrics(
            targetCount = commonKeys.size,
            forecastCount = commonKeys.size,
            meanBrierScore = commonElo.map { it.brier }.average().takeIf { commonElo.isNotEmpty() },
            meanLogLoss = commonElo.map { it.logLoss }.average().takeIf { commonElo.isNotEmpty() },
        )
        val goalPaired = EloExperimentMetrics(
            targetCount = commonKeys.size,
            forecastCount = commonKeys.size,
            meanBrierScore = commonGoal.map { it.brier }.average().takeIf { commonGoal.isNotEmpty() },
            meanLogLoss = commonGoal.map { it.logLoss }.average().takeIf { commonGoal.isNotEmpty() },
        )
        return GoalModelComparisonReport(
            validationStartMillis = validationStart,
            holdoutStartMillis = holdoutStart,
            validationTargetCount = validationDatasets.sumOf { it.targets.size },
            validationGoalMetrics = validationGoalMetrics,
            holdoutTargetCount = holdoutDatasets.sumOf { it.targets.size },
            eloHoldoutForecastCount = eloForecastCount,
            goalHoldoutForecastCount = goalForecastCount,
            pairedHoldoutTargetCount = commonKeys.size,
            eloPairedHoldout = eloPaired,
            goalPairedHoldout = goalPaired,
            selectedEloConfig = eloConfig,
            usedValidationSelectedEloConfig = eloExperiment.selectionAvailable,
            goalConfig = goalConfig,
        )
    }

    private fun List<EloExperimentDataset>.selectTargets(
        predicate: (Long) -> Boolean,
    ): List<EloExperimentDataset> = mapNotNull { dataset ->
        val targets = dataset.targets.filter { predicate(it.forecastCutoffMillis) }
        targets.takeIf { it.isNotEmpty() }?.let { dataset.copy(targets = it) }
    }
}
