package az.simplesoft.footballintelligence.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EloEngineTest {
    @Test fun trainingUsesOnlyMatchesStrictlyBeforeKickoff() {
        val history = listOf(
            HistoricalMatch(100, "Alpha", "Beta", 2, 0),
            HistoricalMatch(200, "Beta", "Gamma", 1, 1),
            HistoricalMatch(300, "Alpha", "Gamma", 0, 6),
        )
        val model = EloEngine.fit(history, forecastKickoffMillis = 200)
        assertEquals(1, model.matchesUsed)
        assertEquals(1, model.gamesFor("ALPHA"))
        assertEquals(0, model.gamesFor("Gamma"))
    }

    @Test fun futureMatchesDoNotChangeEarlierRatings() {
        val earlier = listOf(HistoricalMatch(100, "Alpha", "Beta", 1, 0))
        val withFuture = earlier + HistoricalMatch(500, "Alpha", "Beta", 0, 8)
        val first = EloEngine.fit(earlier, 300)
        val second = EloEngine.fit(withFuture, 300)
        assertEquals(first.ratings, second.ratings)
        assertEquals(first.drawProbability, second.drawProbability, 1e-12)
    }

    @Test fun homeAdvantageFavorsTheHomeSideForEitherTeamOrder() {
        val model = EloEngine.fit(emptyList(), 200)
        val home = EloEngine.predict1x2(model, "Alpha", "Beta")
        val reversed = EloEngine.predict1x2(model, "Beta", "Alpha")
        assertEquals(1.0, home.values().sum(), 1e-12)
        assertTrue(home.home > home.away)
        assertTrue(reversed.home > reversed.away)
    }

    @Test fun emptyHistoryUsesExplicitSmoothedDrawPrior() {
        val model = EloEngine.fit(emptyList(), 200, EloConfig(homeAdvantage = 0.0))
        val result = EloEngine.predict1x2(model, "Alpha", "Beta")
        assertEquals(0, model.matchesUsed)
        assertEquals(0.25, result.draw, 1e-12)
        assertEquals(0.375, result.home, 1e-12)
        assertEquals(0.375, result.away, 1e-12)
    }

    @Test fun forecastWithholdsProbabilitiesBelowDocumentedSampleFloors() {
        val history = listOf(
            HistoricalMatch(100, "Alpha", "Gamma", 1, 0),
            HistoricalMatch(200, "Beta", "Gamma", 0, 0),
            HistoricalMatch(300, "Alpha", "Delta", 2, 1),
            HistoricalMatch(400, "Beta", "Delta", 1, 2),
        )
        val forecast = EloEngine.forecast(history, 500, "Alpha", "Beta")
        assertEquals(4, forecast.matchesUsed)
        assertEquals(2, forecast.homeTeamMatches)
        assertEquals(2, forecast.awayTeamMatches)
        assertEquals(null, forecast.probabilities)
    }

    @Test fun forecastAppearsOnlyAfterLeagueAndBothTeamFloorsAreMet() {
        val history = listOf(
            HistoricalMatch(100, "Alpha", "Gamma", 1, 0),
            HistoricalMatch(200, "Beta", "Gamma", 0, 0),
            HistoricalMatch(300, "Alpha", "Delta", 2, 1),
            HistoricalMatch(400, "Beta", "Delta", 1, 2),
            HistoricalMatch(500, "Alpha", "Epsilon", 0, 1),
            HistoricalMatch(600, "Beta", "Epsilon", 2, 1),
            HistoricalMatch(700, "Zeta", "Eta", 1, 1),
            HistoricalMatch(800, "Theta", "Iota", 3, 0),
            HistoricalMatch(900, "Kappa", "Lambda", 0, 1),
            HistoricalMatch(1_000, "Mu", "Nu", 2, 2),
        )
        val forecast = EloEngine.forecast(history, 1_100, "Alpha", "Beta")
        assertEquals(10, forecast.matchesUsed)
        assertEquals(3, forecast.homeTeamMatches)
        assertEquals(3, forecast.awayTeamMatches)
        assertTrue(forecast.probabilities != null)
        assertEquals(1.0, forecast.probabilities!!.values().sum(), 1e-12)
    }

    @Test fun rejectsInvalidTrainingRows() {
        val invalid = HistoricalMatch(100, "Alpha", "alpha", 0, 0)
        assertFalse(runCatching { EloEngine.fit(listOf(invalid), 200) }.isSuccess)
    }
}
