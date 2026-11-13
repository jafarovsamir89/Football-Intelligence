package az.simplesoft.footballintelligence.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompetitionSeasonWalkForwardMetricsTest {
    @Test fun groupsEloAndHistoricalFrequencyScoresByExactStoredSeason() {
        val targets = listOf(
            WalkForwardTarget("s1", 10, "A", "B", 1, 0, season = "2024-25"),
            WalkForwardTarget("s2", 20, "C", "D", 0, 0, season = "2024-25"),
            WalkForwardTarget("unknown", 30, "E", "F", 0, 1, season = null),
        )
        val predictions = listOf(
            prediction("s1", baselineBrier = 0.5, baselineLogLoss = 0.9),
            prediction("s2", baselineBrier = 0.6, baselineLogLoss = 1.0),
            prediction("unknown", baselineBrier = 0.7, baselineLogLoss = 1.1),
        )
        val dataset = EloExperimentDataset("League", emptyList(), targets)
        val report = EloWalkForwardReport(3, 3, 0, 0.4, 0.8, predictions)

        val grouped = CompetitionSeasonWalkForwardMetricsBuilder.build(listOf(dataset), listOf(report))
        val known = grouped.single { it.season == "2024-25" }
        val unknown = grouped.single { it.season == null }

        assertEquals(0.55, known.frequencyBaselineMeanBrierScore!!, 1e-12)
        assertEquals(0.95, known.frequencyBaselineMeanLogLoss!!, 1e-12)
        assertEquals(0.7, unknown.frequencyBaselineMeanBrierScore!!, 1e-12)
        assertEquals(1.1, unknown.frequencyBaselineMeanLogLoss!!, 1e-12)
    }

    @Test fun missingBaselineRowsDoNotCreateSeasonScores() {
        val target = WalkForwardTarget("s1", 10, "A", "B", 1, 0, season = "2024-25")
        val report = EloWalkForwardReport(1, 1, 0, 0.4, 0.8, listOf(prediction("s1", null, null)))

        val grouped = CompetitionSeasonWalkForwardMetricsBuilder.build(
            listOf(EloExperimentDataset("League", emptyList(), listOf(target))),
            listOf(report),
        ).single()

        assertNull(grouped.frequencyBaselineMeanBrierScore)
        assertNull(grouped.frequencyBaselineMeanLogLoss)
    }

    private fun prediction(id: String, baselineBrier: Double?, baselineLogLoss: Double?) = WalkForwardPrediction(
        targetId = id,
        forecastCutoffMillis = 1,
        probabilities = OutcomeProbabilities(0.5, 0.25, 0.25),
        observedHomeGoals = 1,
        observedAwayGoals = 0,
        brierScore = 0.4,
        logLoss = 0.8,
        frequencyBaselineProbabilities = OutcomeProbabilities(0.4, 0.3, 0.3).takeIf { baselineBrier != null },
        frequencyBaselineBrierScore = baselineBrier,
        frequencyBaselineLogLoss = baselineLogLoss,
    )
}
