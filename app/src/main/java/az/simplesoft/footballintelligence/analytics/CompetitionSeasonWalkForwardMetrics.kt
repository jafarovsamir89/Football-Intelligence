package az.simplesoft.footballintelligence.analytics

data class CompetitionSeasonWalkForwardMetrics(
    val competition: String,
    /** Null means the stored source row did not carry a season label. */
    val season: String?,
    val targetCount: Int,
    val forecastCount: Int,
    val skippedForInsufficientHistory: Int,
    val meanBrierScore: Double?,
    val meanLogLoss: Double?,
)

/** Groups already computed as-of Elo predictions by the exact competition and season labels. */
object CompetitionSeasonWalkForwardMetricsBuilder {
    fun build(
        datasets: List<EloExperimentDataset>,
        reports: List<EloWalkForwardReport>,
    ): List<CompetitionSeasonWalkForwardMetrics> {
        require(datasets.size == reports.size) { "Each dataset must have one walk-forward report." }
        return datasets.zip(reports).flatMap { (dataset, report) ->
            require(report.predictions.map { it.targetId }.distinct().size == report.predictions.size)
            val predictionsById = report.predictions.associateBy { it.targetId }
            dataset.targets.groupBy { it.season }.map { (season, targets) ->
                val predictions = targets.mapNotNull { predictionsById[it.id] }
                val forecastCount = predictions.size
                CompetitionSeasonWalkForwardMetrics(
                    competition = dataset.competition,
                    season = season,
                    targetCount = targets.size,
                    forecastCount = forecastCount,
                    skippedForInsufficientHistory = targets.size - forecastCount,
                    meanBrierScore = predictions.map { it.brierScore }.average().takeIf { forecastCount > 0 },
                    meanLogLoss = predictions.map { it.logLoss }.average().takeIf { forecastCount > 0 },
                )
            }
        }.sortedWith(compareBy<CompetitionSeasonWalkForwardMetrics> { it.competition }
            .thenBy { it.season ?: "\uffff" })
    }
}
