package az.simplesoft.footballintelligence.analytics

const val CURRENT_ELO_MODEL_VERSION = "elo-1x2-v1"
const val CURRENT_PRODUCTION_MODEL_VERSION = CURRENT_ADVANCED_MODEL_VERSION

data class ProspectiveForecastObservation(
    val snapshotId: Long,
    val modelVersion: String,
    val matchId: Int,
    val competition: String,
    val homeTeam: String,
    val awayTeam: String,
    val forecastedAtMillis: Long,
    val kickoffMillis: Long,
    val historyMatchCount: Int,
    val probabilities: OutcomeProbabilities,
    val actualOutcome: String?,
    val homeGoals: Int?,
    val awayGoals: Int?,
    val settledAtMillis: Long?,
    val resultAvailableAtMillis: Long?,
    val resultSourceName: String?,
    val resultSourceUrl: String?,
    val frequencyBaselineVersion: String? = null,
    val frequencyBaselineProbabilities: OutcomeProbabilities? = null,
)

data class ProspectiveForecastReport(
    val snapshotCount: Int,
    val settledCount: Int,
    val meanBrierScore: Double?,
    val meanLogLoss: Double?,
    val topOutcomeAccuracy: Double?,
    val modelReports: List<ProspectiveModelForecastReport> = emptyList(),
)

data class ProspectiveFrequencyBaselineReport(
    val baselineVersion: String,
    val fixtureCount: Int,
    val settledCount: Int,
    val modelMeanBrierScore: Double?,
    val baselineMeanBrierScore: Double?,
    /** Negative difference means the model scored lower on the same settled fixtures. */
    val modelMinusBaselineBrierScore: Double?,
    val modelMeanLogLoss: Double?,
    val baselineMeanLogLoss: Double?,
    /** Negative difference means the model scored lower on the same settled fixtures. */
    val modelMinusBaselineLogLoss: Double?,
)

data class ProspectiveModelForecastReport(
    val modelVersion: String,
    val snapshotCount: Int,
    /** Number of distinct fixtures after choosing one pre-kickoff snapshot per fixture. */
    val fixtureCount: Int,
    val settledCount: Int,
    val pendingCount: Int,
    val meanBrierScore: Double?,
    val meanLogLoss: Double?,
    val topOutcomeAccuracy: Double?,
    val frequencyBaselineReports: List<ProspectiveFrequencyBaselineReport> = emptyList(),
)

/** Keeps prospective training history strictly before the instant when the forecast was made. */
fun historyAvailableAt(history: Collection<HistoricalMatch>, forecastCutoffMillis: Long): List<HistoricalMatch> =
    history.filter { match ->
        match.kickoffMillis < forecastCutoffMillis &&
            match.safeAvailabilityMillisOrNull()?.let { it < forecastCutoffMillis } == true
    }

/** A fact cannot be known earlier than either its source availability time or its local capture. */
fun conservativeResultAvailability(sourceAvailableAtMillis: Long?, capturedAtMillis: Long?): Long? =
    listOfNotNull(sourceAvailableAtMillis, capturedAtMillis).maxOrNull()

fun isProspectiveForecastEligible(
    forecastedAtMillis: Long,
    kickoffMillis: Long,
    nowMillis: Long,
    historyMatchCount: Int,
): Boolean =
    forecastedAtMillis > 0L &&
        forecastedAtMillis <= nowMillis &&
        forecastedAtMillis < kickoffMillis &&
        nowMillis < kickoffMillis &&
        historyMatchCount >= EloEngine.MIN_HISTORY_MATCHES_FOR_FORECAST

object ProspectiveForecastEvaluator {
    fun evaluate(observations: Collection<ProspectiveForecastObservation>): ProspectiveForecastReport {
        val validForecasts = observations.filter(::isValidProspectiveSnapshot)
        val byModel = validForecasts.groupBy { it.modelVersion }.toSortedMap()
        val selectedByModel = byModel.mapValues { (_, rows) ->
            rows.groupBy { it.matchId to it.kickoffMillis }.values.mapNotNull { fixtureRows ->
                fixtureRows.maxWithOrNull(compareBy<ProspectiveForecastObservation> { it.forecastedAtMillis }.thenBy { it.snapshotId })
            }
        }
        val modelReports = selectedByModel.map { (modelVersion, selected) ->
            val settled = selected.mapNotNull { observation ->
                observation.observedOutcomeOrNull()?.let { observation to it }
            }
            val baselineReports = selected.asSequence()
                .filter { it.frequencyBaselineVersion?.isNotBlank() == true && it.frequencyBaselineProbabilities?.let(::validProbabilities) == true }
                .groupBy { requireNotNull(it.frequencyBaselineVersion) }
                .toSortedMap()
                .map { (baselineVersion, rows) ->
                    val baselineSettled = rows.mapNotNull { observation ->
                        observation.observedOutcomeOrNull()?.let { observation to it }
                    }
                    val modelBrier = meanBrier(baselineSettled)
                    val baselineBrier = baselineSettled.map { (row, observed) ->
                        ForecastMetrics.brierScore(requireNotNull(row.frequencyBaselineProbabilities), observed)
                    }.average().takeIf { baselineSettled.isNotEmpty() }
                    val modelLogLoss = meanLogLoss(baselineSettled)
                    val baselineLogLoss = baselineSettled.map { (row, observed) ->
                        ForecastMetrics.logLoss(requireNotNull(row.frequencyBaselineProbabilities), observed)
                    }.average().takeIf { baselineSettled.isNotEmpty() }
                    ProspectiveFrequencyBaselineReport(
                        baselineVersion = baselineVersion,
                        fixtureCount = rows.size,
                        settledCount = baselineSettled.size,
                        modelMeanBrierScore = modelBrier,
                        baselineMeanBrierScore = baselineBrier,
                        modelMinusBaselineBrierScore = if (modelBrier != null && baselineBrier != null) modelBrier - baselineBrier else null,
                        modelMeanLogLoss = modelLogLoss,
                        baselineMeanLogLoss = baselineLogLoss,
                        modelMinusBaselineLogLoss = if (modelLogLoss != null && baselineLogLoss != null) modelLogLoss - baselineLogLoss else null,
                    )
                }
            ProspectiveModelForecastReport(
                modelVersion = modelVersion,
                snapshotCount = byModel.getValue(modelVersion).size,
                fixtureCount = selected.size,
                settledCount = settled.size,
                pendingCount = selected.size - settled.size,
                meanBrierScore = meanBrier(settled),
                meanLogLoss = meanLogLoss(settled),
                topOutcomeAccuracy = topAccuracy(settled),
                frequencyBaselineReports = baselineReports,
            )
        }
        val allSettled = selectedByModel.values.flatten().mapNotNull { observation ->
            observation.observedOutcomeOrNull()?.let { observation to it }
        }
        return ProspectiveForecastReport(
            snapshotCount = observations.size,
            settledCount = allSettled.size,
            meanBrierScore = meanBrier(allSettled),
            meanLogLoss = meanLogLoss(allSettled),
            topOutcomeAccuracy = topAccuracy(allSettled),
            modelReports = modelReports,
        )
    }

    private fun isValidProspectiveSnapshot(observation: ProspectiveForecastObservation): Boolean =
        observation.modelVersion.isNotBlank() &&
            TeamDataQuality.isValidFixture(observation.homeTeam, observation.awayTeam) &&
            observation.forecastedAtMillis > 0L &&
            observation.forecastedAtMillis < observation.kickoffMillis &&
            observation.historyMatchCount >= EloEngine.MIN_HISTORY_MATCHES_FOR_FORECAST &&
            validProbabilities(observation.probabilities)

    private fun validProbabilities(probabilities: OutcomeProbabilities): Boolean =
        probabilities.values().all { it.isFinite() && it in 0.0..1.0 } &&
            kotlin.math.abs(probabilities.values().sum() - 1.0) < 1e-8

    /** A saved result is scoreable only if its timestamped availability follows both prediction and kickoff. */
    private fun ProspectiveForecastObservation.observedOutcomeOrNull(): OutcomeProbabilities? {
        if (homeGoals == null || awayGoals == null || homeGoals < 0 || awayGoals < 0) return null
        if (resultSourceName.isNullOrBlank()) return null
        val availableAt = resultAvailableAtMillis ?: return null
        val settledAt = settledAtMillis ?: return null
        if (availableAt < kickoffMillis || availableAt <= forecastedAtMillis || settledAt < availableAt) return null
        val scoreOutcome = when {
            homeGoals > awayGoals -> "H"
            homeGoals < awayGoals -> "A"
            else -> "D"
        }
        if (actualOutcome != scoreOutcome) return null
        return when (scoreOutcome) {
            "H" -> OutcomeProbabilities(1.0, 0.0, 0.0)
            "D" -> OutcomeProbabilities(0.0, 1.0, 0.0)
            else -> OutcomeProbabilities(0.0, 0.0, 1.0)
        }
    }

    private fun meanBrier(rows: List<Pair<ProspectiveForecastObservation, OutcomeProbabilities>>): Double? =
        rows.map { (row, observed) -> ForecastMetrics.brierScore(row.probabilities, observed) }
            .average().takeIf { rows.isNotEmpty() }

    private fun meanLogLoss(rows: List<Pair<ProspectiveForecastObservation, OutcomeProbabilities>>): Double? =
        rows.map { (row, observed) -> ForecastMetrics.logLoss(row.probabilities, observed) }
            .average().takeIf { rows.isNotEmpty() }

    private fun topAccuracy(rows: List<Pair<ProspectiveForecastObservation, OutcomeProbabilities>>): Double? =
        rows.count { (row, observed) ->
            row.probabilities.values().indexOf(row.probabilities.values().maxOrNull()) == observed.values().indexOf(1.0)
        }.toDouble().div(rows.size).takeIf { rows.isNotEmpty() }
}
