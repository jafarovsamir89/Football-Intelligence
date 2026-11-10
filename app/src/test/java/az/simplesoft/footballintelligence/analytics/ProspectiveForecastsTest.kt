package az.simplesoft.footballintelligence.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class ProspectiveForecastsTest {
    @Test fun onlyCompletedAndAvailableMatchesBeforeCutoffEnterProspectiveHistory() {
        val cutoff = 10_000L
        val eligible = HistoricalMatch(cutoff - 2_000, "A", "B", 1, 0, cutoff - 1)
        val resultNotAvailableYet = eligible.copy(kickoffMillis = cutoff - 3_000, availableAtMillis = cutoff)
        val kickoffNotPast = eligible.copy(kickoffMillis = cutoff, availableAtMillis = cutoff - 1)
        assertEquals(listOf(eligible), historyAvailableAt(listOf(eligible, resultNotAvailableYet, kickoffNotPast), cutoff))
    }

    @Test fun resultAvailabilityCannotPrecedeItsLocalCapture() {
        assertEquals(500L, conservativeResultAvailability(200, 500))
        assertEquals(700L, conservativeResultAvailability(700, 500))
        assertEquals(500L, conservativeResultAvailability(null, 500))
        assertNull(conservativeResultAvailability(null, null))
    }

    @Test fun snapshotRequiresARealPreKickoffCaptureAndMinimumTrainingHistory() {
        assertTrue(isProspectiveForecastEligible(100, 300, 200, EloEngine.MIN_HISTORY_MATCHES_FOR_FORECAST))
        assertFalse(isProspectiveForecastEligible(100, 200, 200, EloEngine.MIN_HISTORY_MATCHES_FOR_FORECAST))
        assertFalse(isProspectiveForecastEligible(100, 300, 300, EloEngine.MIN_HISTORY_MATCHES_FOR_FORECAST))
        assertFalse(isProspectiveForecastEligible(201, 300, 200, EloEngine.MIN_HISTORY_MATCHES_FOR_FORECAST))
        assertFalse(isProspectiveForecastEligible(100, 300, 200, EloEngine.MIN_HISTORY_MATCHES_FOR_FORECAST - 1))
    }

    @Test fun prospectiveMetricsUseOnlySettledRowsAndReturnProperScores() {
        val settled = observation("H")
        val pending = observation(null, id = 2)

        val report = ProspectiveForecastEvaluator.evaluate(listOf(settled, pending))

        assertEquals(2, report.snapshotCount)
        assertEquals(1, report.settledCount)
        assertEquals(.26, report.meanBrierScore!!, 1e-12)
        assertEquals(-ln(.6), report.meanLogLoss!!, 1e-12)
        assertEquals(1.0, report.topOutcomeAccuracy!!, 0.0)
        assertNull(ProspectiveForecastEvaluator.evaluate(listOf(pending)).meanBrierScore)
    }

    private fun observation(outcome: String?, id: Long = 1) = ProspectiveForecastObservation(
        snapshotId = id,
        modelVersion = "elo-1x2-v1",
        matchId = id.toInt(),
        competition = "league",
        homeTeam = "Home",
        awayTeam = "Away",
        forecastedAtMillis = 100,
        kickoffMillis = 200,
        historyMatchCount = 12,
        probabilities = OutcomeProbabilities(.6, .3, .1),
        actualOutcome = outcome,
        homeGoals = if (outcome == "H") 2 else null,
        awayGoals = if (outcome == "H") 1 else null,
        settledAtMillis = if (outcome == null) null else 300,
        resultAvailableAtMillis = if (outcome == null) null else 250,
        resultSourceName = if (outcome == null) null else "OpenLigaDB",
        resultSourceUrl = null,
    )
}
