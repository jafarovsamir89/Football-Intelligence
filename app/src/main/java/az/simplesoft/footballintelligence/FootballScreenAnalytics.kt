package az.simplesoft.footballintelligence

import az.simplesoft.footballintelligence.analytics.InternationalMarketConsensus
import az.simplesoft.footballintelligence.analytics.InternationalMarketEngine
import az.simplesoft.footballintelligence.analytics.InternationalMarketEvaluationEngine
import az.simplesoft.footballintelligence.analytics.InternationalMarketEvaluationReport
import az.simplesoft.footballintelligence.analytics.ProspectiveForecastEvaluator
import az.simplesoft.footballintelligence.analytics.ProspectiveForecastReport

/** Analytics derived from one immutable screen snapshot, calculated off the Compose thread. */
internal data class FootballScreenAnalytics(
    val internationalConsensus: List<InternationalMarketConsensus>,
    val internationalMarketEvaluation: InternationalMarketEvaluationReport,
    val prospectiveForecastReport: ProspectiveForecastReport,
) {
    companion object {
        fun empty() = FootballScreenAnalytics(
            internationalConsensus = emptyList(),
            internationalMarketEvaluation = InternationalMarketEvaluationReport(0, 0, 0, 0, emptyList()),
            prospectiveForecastReport = ProspectiveForecastReport(0, 0, null, null, null),
        )
    }
}

internal fun calculateFootballScreenAnalytics(snapshot: FootballScreenStoreSnapshot) =
    FootballScreenAnalytics(
        internationalConsensus = InternationalMarketEngine.consensus(snapshot.internationalOdds),
        internationalMarketEvaluation = InternationalMarketEvaluationEngine.evaluate(
            snapshot.internationalOddsEvaluationHistory,
            snapshot.prospectiveForecastEvaluationHistory,
        ),
        prospectiveForecastReport = ProspectiveForecastEvaluator.evaluate(
            snapshot.prospectiveForecastEvaluationHistory,
        ),
    )
