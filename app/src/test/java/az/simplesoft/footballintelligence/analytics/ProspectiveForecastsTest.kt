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
        val impossibleAvailability = eligible.copy(kickoffMillis = cutoff - 3_000, availableAtMillis = cutoff - 4_000)
        val kickoffNotPast = eligible.copy(kickoffMillis = cutoff, availableAtMillis = cutoff - 1)
        assertEquals(listOf(eligible), historyAvailableAt(listOf(eligible, resultNotAvailableYet, impossibleAvailability, kickoffNotPast), cutoff))
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

    @Test fun prospectiveFrequencyBaselineIsComparedOnTheSameSettledFixtures() {
        val settled=observation("H").copy(
            frequencyBaselineVersion=OUTCOME_FREQUENCY_BASELINE_VERSION,
            frequencyBaselineProbabilities=OutcomeProbabilities(.2,.3,.5),
        )
        val pending=observation(null,id=2).copy(
            frequencyBaselineVersion=OUTCOME_FREQUENCY_BASELINE_VERSION,
            frequencyBaselineProbabilities=OutcomeProbabilities(.4,.3,.3),
        )

        val report=ProspectiveForecastEvaluator.evaluate(listOf(settled,pending))
        val comparison=report.modelReports.single().frequencyBaselineReports.single()

        assertEquals(2,comparison.fixtureCount)
        assertEquals(1,comparison.settledCount)
        assertEquals(.26,comparison.modelMeanBrierScore!!,1e-12)
        assertEquals(.98,comparison.baselineMeanBrierScore!!,1e-12)
        assertEquals(-.72,comparison.modelMinusBaselineBrierScore!!,1e-12)
        assertEquals(-kotlin.math.ln(.6),comparison.modelMeanLogLoss!!,1e-12)
        assertEquals(-kotlin.math.ln(.2),comparison.baselineMeanLogLoss!!,1e-12)
        assertEquals(-kotlin.math.ln(.6)+kotlin.math.ln(.2),comparison.modelMinusBaselineLogLoss!!,1e-12)
    }

    @Test fun baselineComparisonDoesNotFallBackToAnOlderSnapshotWhenTheSelectedOneHasNoBaseline() {
        val earlier=observation("H").copy(
            forecastedAtMillis=100,
            frequencyBaselineVersion=OUTCOME_FREQUENCY_BASELINE_VERSION,
            frequencyBaselineProbabilities=OutcomeProbabilities(.2,.3,.5),
        )
        val latest=earlier.copy(snapshotId=2,forecastedAtMillis=150,frequencyBaselineVersion=null,frequencyBaselineProbabilities=null)

        val report=ProspectiveForecastEvaluator.evaluate(listOf(earlier,latest))

        assertEquals(1,report.modelReports.single().fixtureCount)
        assertTrue(report.modelReports.single().frequencyBaselineReports.isEmpty())
    }

    @Test fun prospectiveMetricsUseLatestPreKickoffSnapshotOncePerFixtureAndModelVersion() {
        val earlier = observation("H", id = 1).copy(
            forecastedAtMillis = 100,
            probabilities = OutcomeProbabilities(.6, .3, .1),
        )
        val later = earlier.copy(
            snapshotId = 2,
            forecastedAtMillis = 150,
            probabilities = OutcomeProbabilities(.1, .8, .1),
        )
        val afterKickoff = earlier.copy(
            snapshotId = 3,
            forecastedAtMillis = 201,
            probabilities = OutcomeProbabilities(.99, .005, .005),
        )
        val secondModelVersion = later.copy(snapshotId = 4, modelVersion = "elo-1x2-v2")

        val report = ProspectiveForecastEvaluator.evaluate(listOf(earlier, later, afterKickoff, secondModelVersion))

        assertEquals(4, report.snapshotCount)
        assertEquals(2, report.settledCount)
        assertEquals(2, report.modelReports.size)
        val firstVersion = report.modelReports.single { it.modelVersion == "elo-1x2-v1" }
        assertEquals(2, firstVersion.snapshotCount)
        assertEquals(1, firstVersion.fixtureCount)
        assertEquals(1, firstVersion.settledCount)
        assertEquals(1.46, firstVersion.meanBrierScore!!, 1e-12)
        assertEquals("elo-1x2-v2", report.modelReports.last().modelVersion)
    }

    @Test fun prospectiveMetricsRejectInconsistentScoresAndResultTimesBeforeKickoff() {
        val valid = observation("H")
        val contradictoryScore = valid.copy(homeGoals = 0, awayGoals = 1)
        val earlyAvailability = valid.copy(resultAvailableAtMillis = valid.kickoffMillis - 1)
        val lateSettlement = valid.copy(settledAtMillis = valid.resultAvailableAtMillis!! - 1)
        val missingSource = valid.copy(resultSourceName = null)

        listOf(contradictoryScore, earlyAvailability, lateSettlement, missingSource).forEach { invalid ->
            val report = ProspectiveForecastEvaluator.evaluate(listOf(invalid))
            assertEquals(0, report.settledCount)
            assertNull(report.meanBrierScore)
        }
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
