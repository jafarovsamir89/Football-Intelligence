package az.simplesoft.footballintelligence.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbabilityEngineTest {
    @Test fun basicMarginRemovalNormalizesImpliedProbabilities() {
        val result = ProbabilityEngine.removeMargin(2.0, 3.5, 4.0, MarginMethod.BASIC)
        assertEquals(1.0, result.values().sum(), 1e-12)
        assertTrue(result.home > result.draw && result.draw > result.away)
    }

    @Test fun largestPositiveModelMarketGapNamesTheHighestOutcomeDifference() {
        val model = OutcomeProbabilities(.55, .25, .20)
        val market = ProbabilityEngine.removeMargin(2.0, 3.2, 4.0, MarginMethod.BASIC)

        val gap = ProbabilityEngine.largestPositiveModelMarketGap(model, market)

        assertEquals("H", gap?.outcomeCode)
        assertEquals(model.home - market.home, gap?.deltaProbability ?: 0.0, 1e-12)
    }

    @Test fun largestPositiveModelMarketGapIgnoresEqualInputsAndFloatingPointNoise() {
        val market = OutcomeProbabilities(.5, .3, .2)
        assertEquals(null, ProbabilityEngine.largestPositiveModelMarketGap(market, market))

        val noiseOnly = OutcomeProbabilities(.5 + 5e-13, .3 - 5e-13, .2)
        assertEquals(null, ProbabilityEngine.largestPositiveModelMarketGap(noiseOnly, market))
    }

    @Test fun powerAndShinMethodsReturnFiniteNormalizedProbabilities() {
        for (method in listOf(MarginMethod.POWER, MarginMethod.SHIN)) {
            val result = ProbabilityEngine.removeMargin(1.91, 3.6, 4.2, method)
            assertEquals(1.0, result.values().sum(), 1e-8)
            assertTrue(result.values().all { it.isFinite() && it in 0.0..1.0 })
        }
    }

    @Test fun powerMethodBracketsLargeExponentForExtremeOverround() {
        val result = ProbabilityEngine.removeMargin(1.00001, 1.00002, 1.00003, MarginMethod.POWER)
        assertEquals(1.0, result.values().sum(), 1e-8)
        assertTrue(result.home < 0.9)
    }

    @Test fun rejectsInvalidDecimalOdds() {
        assertFalse(runCatching { ProbabilityEngine.removeMargin(1.0, 3.0, 4.0, MarginMethod.BASIC) }.isSuccess)
        assertFalse(runCatching { ProbabilityEngine.removeMargin(Double.NaN, 3.0, 4.0, MarginMethod.SHIN) }.isSuccess)
        assertFalse(runCatching { ProbabilityEngine.removeMargin(3.0, 4.0, 5.0, MarginMethod.SHIN) }.isSuccess)
    }

    @Test fun poissonAndDixonColesGridsNormalizeAndProduceValidOutcomes() {
        val poisson = GoalModel.predict1x2(1.45, 0.95)
        val dixonColes = GoalModel.predict1x2(1.45, 0.95, dixonColesRho = -0.08)
        assertEquals(1.0, poisson.scoreProbabilities.values.sum(), 1e-10)
        assertEquals(1.0, dixonColes.probabilities.values().sum(), 1e-10)
        assertTrue(poisson.probabilities.home > poisson.probabilities.away)
        assertFalse(poisson.probabilities == dixonColes.probabilities)
    }

    @Test fun rejectsImpossibleDixonColesCorrectionAndInvalidGoalRates() {
        assertFalse(runCatching { GoalModel.predict1x2(0.0, 1.0) }.isSuccess)
        assertFalse(runCatching { GoalModel.predict1x2(1.0, 1.0, dixonColesRho = 2.0) }.isSuccess)
    }

    @Test fun scoringMetricsAndExpectedValueUseObservedOutcome() {
        val prediction = OutcomeProbabilities(0.6, 0.25, 0.15)
        val homeWin = OutcomeProbabilities(1.0, 0.0, 0.0)
        assertEquals(0.245, ForecastMetrics.brierScore(prediction, homeWin), 1e-12)
        assertEquals(-kotlin.math.ln(0.6), ForecastMetrics.logLoss(prediction, homeWin), 1e-12)
        assertEquals(0.2, ForecastMetrics.expectedValuePerUnit(0.6, 2.0), 1e-12)
    }
}
