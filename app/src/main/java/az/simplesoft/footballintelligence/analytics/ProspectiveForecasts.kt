package az.simplesoft.footballintelligence.analytics

const val CURRENT_ELO_MODEL_VERSION = "elo-1x2-v1"

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
)

data class ProspectiveForecastReport(
    val snapshotCount: Int,
    val settledCount: Int,
    val meanBrierScore: Double?,
    val meanLogLoss: Double?,
    val topOutcomeAccuracy: Double?,
)

/** Keeps prospective training history strictly before the instant when the forecast was made. */
fun historyAvailableAt(history: Collection<HistoricalMatch>, forecastCutoffMillis: Long): List<HistoricalMatch> =
    history.filter {
        it.kickoffMillis < forecastCutoffMillis && it.availableAtMillis < forecastCutoffMillis
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
        val settled = observations.mapNotNull { observation ->
            val observed = when (observation.actualOutcome) {
                "H" -> OutcomeProbabilities(1.0, 0.0, 0.0)
                "D" -> OutcomeProbabilities(0.0, 1.0, 0.0)
                "A" -> OutcomeProbabilities(0.0, 0.0, 1.0)
                else -> null
            } ?: return@mapNotNull null
            observation to observed
        }
        return ProspectiveForecastReport(
            snapshotCount = observations.size,
            settledCount = settled.size,
            meanBrierScore = settled.map { (row, observed) ->
                ForecastMetrics.brierScore(row.probabilities, observed)
            }.average().takeIf { settled.isNotEmpty() },
            meanLogLoss = settled.map { (row, observed) ->
                ForecastMetrics.logLoss(row.probabilities, observed)
            }.average().takeIf { settled.isNotEmpty() },
            topOutcomeAccuracy = settled.map { (row, observed) ->
                row.probabilities.values().indexOf(row.probabilities.values().maxOrNull()) ==
                    observed.values().indexOf(1.0)
            }.count { it }.toDouble().div(settled.size).takeIf { settled.isNotEmpty() },
        )
    }
}
