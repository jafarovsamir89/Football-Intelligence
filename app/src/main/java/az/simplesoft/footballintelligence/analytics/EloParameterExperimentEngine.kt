package az.simplesoft.footballintelligence.analytics

data class EloExperimentDataset(
    val competition: String,
    val history: List<HistoricalMatch>,
    val targets: List<WalkForwardTarget>,
)

data class EloExperimentMetrics(
    val targetCount: Int,
    val forecastCount: Int,
    val meanBrierScore: Double?,
    val meanLogLoss: Double?,
)

data class EloGridCandidate(
    val config: EloConfig,
    val validation: EloExperimentMetrics,
)

data class EloChronologicalExperimentReport(
    val totalTargets: Int,
    val trainingStartMillis: Long?,
    val validationStartMillis: Long?,
    val testStartMillis: Long?,
    val testEndMillis: Long?,
    val training: EloExperimentMetrics,
    val candidates: List<EloGridCandidate>,
    val selectedConfig: EloConfig?,
    val selectedValidation: EloExperimentMetrics?,
    val selectedTest: EloExperimentMetrics?,
    val defaultTest: EloExperimentMetrics,
    val minimumValidationForecastsForSelection: Int,
) {
    val selectionAvailable: Boolean get() = selectedConfig != null
}

/**
 * Small, deterministic Elo parameter study. The first 60% of distinct forecast cutoffs
 * is the warm-up/training window, the next 20% selects parameters, and the final 20%
 * is evaluated only after selection. Each competition keeps its own rating state.
 */
object EloParameterExperimentEngine {
    val DEFAULT_GRID: List<EloConfig> = listOf(10.0, 20.0, 30.0).flatMap { k ->
        listOf(0.0, 65.0, 100.0).flatMap { home ->
            listOf(0.22, 0.25, 0.28).map { draw ->
                EloConfig(kFactor = k, homeAdvantage = home, priorDrawProbability = draw)
            }
        }
    }

    private const val TRAIN_SHARE = 0.60
    private const val VALIDATION_SHARE = 0.20

    fun evaluate(
        datasets: List<EloExperimentDataset>,
        parameterGrid: List<EloConfig> = DEFAULT_GRID,
        minimumHistoryMatches: Int = EloEngine.MIN_HISTORY_MATCHES_FOR_FORECAST,
        minimumMatchesPerTeam: Int = EloEngine.MIN_TEAM_MATCHES_FOR_FORECAST,
        minimumValidationForecastsForSelection: Int = 100,
    ): EloChronologicalExperimentReport {
        require(minimumHistoryMatches >= 0)
        require(minimumMatchesPerTeam >= 0)
        require(minimumValidationForecastsForSelection >= 1)
        require(parameterGrid.isNotEmpty()) { "At least one Elo configuration is required." }
        require(parameterGrid.distinct().size == parameterGrid.size) { "Elo configurations must be unique." }
        datasets.forEach { dataset ->
            require(dataset.competition.isNotBlank())
            require(dataset.targets.map { it.id }.distinct().size == dataset.targets.size)
        }

        val cutoffs = datasets.flatMap { it.targets }.map { it.forecastCutoffMillis }.distinct().sorted()
        val defaults = EloConfig()
        if (cutoffs.size < 5) {
            val empty = summarize(emptyList(), defaults, minimumHistoryMatches, minimumMatchesPerTeam)
            return EloChronologicalExperimentReport(
                totalTargets = datasets.sumOf { it.targets.size },
                trainingStartMillis = cutoffs.firstOrNull(),
                validationStartMillis = null,
                testStartMillis = null,
                testEndMillis = cutoffs.lastOrNull(),
                training = empty,
                candidates = emptyList(),
                selectedConfig = null,
                selectedValidation = null,
                selectedTest = null,
                defaultTest = empty,
                minimumValidationForecastsForSelection = minimumValidationForecastsForSelection,
            )
        }

        val validationIndex = (cutoffs.size * TRAIN_SHARE).toInt().coerceIn(1, cutoffs.lastIndex - 1)
        val testIndex = (cutoffs.size * (TRAIN_SHARE + VALIDATION_SHARE)).toInt()
            .coerceIn(validationIndex + 1, cutoffs.lastIndex)
        val validationStart = cutoffs[validationIndex]
        val testStart = cutoffs[testIndex]
        val trainingTargets = targetsIn(datasets) { it < validationStart }
        val validationTargets = targetsIn(datasets) { it >= validationStart && it < testStart }
        val testTargets = targetsIn(datasets) { it >= testStart }

        val training = summarize(trainingTargets, defaults, minimumHistoryMatches, minimumMatchesPerTeam)
        val candidates = parameterGrid.map { config ->
            EloGridCandidate(
                config,
                summarize(validationTargets, config, minimumHistoryMatches, minimumMatchesPerTeam),
            )
        }.sortedWith(
            compareBy<EloGridCandidate> { it.validation.meanLogLoss ?: Double.POSITIVE_INFINITY }
                .thenBy { it.validation.meanBrierScore ?: Double.POSITIVE_INFINITY }
                .thenBy { it.config.kFactor }
                .thenBy { it.config.homeAdvantage }
                .thenBy { it.config.priorDrawProbability },
        )

        val bestValidation = candidates.firstOrNull {
            it.validation.forecastCount >= minimumValidationForecastsForSelection &&
                it.validation.meanLogLoss != null && it.validation.meanBrierScore != null
        }
        val selectedTest = bestValidation?.let {
            summarize(testTargets, it.config, minimumHistoryMatches, minimumMatchesPerTeam)
        }
        val defaultTest = summarize(testTargets, defaults, minimumHistoryMatches, minimumMatchesPerTeam)

        return EloChronologicalExperimentReport(
            totalTargets = datasets.sumOf { it.targets.size },
            trainingStartMillis = cutoffs.firstOrNull(),
            validationStartMillis = validationStart,
            testStartMillis = testStart,
            testEndMillis = cutoffs.lastOrNull(),
            training = training,
            candidates = candidates,
            selectedConfig = bestValidation?.config,
            selectedValidation = bestValidation?.validation,
            selectedTest = selectedTest,
            defaultTest = defaultTest,
            minimumValidationForecastsForSelection = minimumValidationForecastsForSelection,
        )
    }

    private fun targetsIn(
        datasets: List<EloExperimentDataset>,
        predicate: (Long) -> Boolean,
    ): List<EloExperimentDataset> = datasets.mapNotNull { dataset ->
        val selected = dataset.targets.filter { predicate(it.forecastCutoffMillis) }
        selected.takeIf { it.isNotEmpty() }?.let { dataset.copy(targets = it) }
    }

    private fun summarize(
        datasets: List<EloExperimentDataset>,
        config: EloConfig,
        minimumHistoryMatches: Int,
        minimumMatchesPerTeam: Int,
    ): EloExperimentMetrics {
        var targetCount = 0
        var forecastCount = 0
        var brierSum = 0.0
        var logLossSum = 0.0
        for (dataset in datasets) {
            val report = EloWalkForwardEngine.evaluate(
                history = dataset.history,
                targets = dataset.targets,
                config = config,
                minimumHistoryMatches = minimumHistoryMatches,
                minimumMatchesPerTeam = minimumMatchesPerTeam,
            )
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
}
