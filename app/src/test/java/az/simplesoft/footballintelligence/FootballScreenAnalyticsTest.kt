package az.simplesoft.footballintelligence

import az.simplesoft.footballintelligence.analytics.OutcomeProbabilities
import az.simplesoft.footballintelligence.analytics.ProspectiveForecastObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FootballScreenAnalyticsTest {
    @Test fun screenAnalyticsScoresTheCompleteProspectiveLedgerNotTheVisibleHistory() {
        val settled = ProspectiveForecastObservation(
            snapshotId = 1L,
            modelVersion = "elo-1x2-v1",
            matchId = 42,
            competition = "league",
            homeTeam = "Home FC",
            awayTeam = "Away FC",
            forecastedAtMillis = 100L,
            kickoffMillis = 200L,
            historyMatchCount = 12,
            probabilities = OutcomeProbabilities(.6, .3, .1),
            actualOutcome = "H",
            homeGoals = 2,
            awayGoals = 0,
            settledAtMillis = 300L,
            resultAvailableAtMillis = 250L,
            resultSourceName = "OpenLigaDB",
            resultSourceUrl = "https://example.org/result/42",
        )
        val screenSnapshot = FootballScreenStoreSnapshot.empty().copy(
            prospectiveForecastHistory = emptyList(),
            prospectiveForecastEvaluationHistory = listOf(settled),
        )

        val analytics = calculateFootballScreenAnalytics(screenSnapshot)

        assertEquals(1, analytics.prospectiveForecastReport.snapshotCount)
        assertEquals(1, analytics.prospectiveForecastReport.settledCount)
        assertEquals(1, analytics.prospectiveForecastReport.modelReports.single().fixtureCount)
        assertEquals(.26, analytics.prospectiveForecastReport.meanBrierScore!!, 1e-12)
        assertTrue(analytics.internationalConsensus.isEmpty())
        assertEquals(0, analytics.internationalMarketEvaluation.quoteFixtureCount)
    }
}
