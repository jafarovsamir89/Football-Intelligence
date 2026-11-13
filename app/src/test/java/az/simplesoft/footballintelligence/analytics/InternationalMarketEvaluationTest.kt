package az.simplesoft.footballintelligence.analytics

import az.simplesoft.footballintelligence.data.InternationalMatchLinkBasis
import az.simplesoft.footballintelligence.data.InternationalOddsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InternationalMarketEvaluationTest {
    private val kickoff = 1_800_000_000_000L
    private val captured = kickoff - 60_000L

    @Test fun comparesLatestFreshBookmakerQuoteWithOnlyProductionModelKnownByCapture() {
        val oldQuote = quote("moviodds_bet365", "Bet365", captured - 30 * 60_000L)
        val latestQuote = quote("the_odds_api", "bet365", captured - 10_000L, home = 2.0, draw = 3.5, away = 4.0)
        val duplicateIntermediaryQuote = quote("other_feed", "BET365", captured - 5_000L, home = 4.0, draw = 3.5, away = 2.0)
        val preQuoteForecast = forecast(id = 1, at = captured - 10_000L, outcome = "H")
        val laterForecast = preQuoteForecast.copy(
            snapshotId = 2,
            forecastedAtMillis = captured - 4_999L,
            probabilities = OutcomeProbabilities(.01, .01, .98),
        )

        val report = InternationalMarketEvaluationEngine.evaluate(
            listOf(oldQuote, latestQuote, duplicateIntermediaryQuote),
            listOf(preQuoteForecast, laterForecast),
        )

        val bet365 = report.bookmakerReports.single()
        assertEquals(1, report.quoteFixtureCount)
        assertEquals(1, report.pairedFixtureCount)
        assertEquals(1, report.settledFixtureCount)
        assertEquals(1, bet365.quoteFixtureCount)
        assertEquals(listOf("other_feed"), bet365.sourceProviderIds)
        assertEquals(65_000.0 / 3_600_000.0, bet365.medianHoursBeforeKickoff, 1e-12)
        assertEquals(1, bet365.pairedFixtureCount)
        assertEquals(1, bet365.settledFixtureCount)
        assertEquals(.26, bet365.modelBrierScore!!, 1e-12)
        assertTrue(bet365.marketBrierScore!! > bet365.modelBrierScore!!)
        assertEquals(0.0, bet365.marketTopOutcomeAccuracy!!, 0.0)
        assertEquals(1.0, bet365.modelTopOutcomeAccuracy!!, 0.0)
        assertEquals(0, bet365.pendingFixtureCount)
    }

    @Test fun keepsBookmakersSeparateAndDoesNotScorePendingOutcomes() {
        val firstBook = quote("the_odds_api", "pinnacle", captured - 10_000L)
        val secondBook = quote("moviodds_bet365", "bet365", captured - 10_000L, matchId = 84)
        val firstForecast = forecast(id = 1, at = captured - 20_000L, outcome = null)
        val secondForecast = forecast(id = 84, matchId = 84, at = captured - 20_000L, outcome = null)

        val report = InternationalMarketEvaluationEngine.evaluate(
            listOf(firstBook, secondBook),
            listOf(firstForecast, secondForecast),
        )

        assertEquals(2, report.quoteFixtureCount)
        assertEquals(2, report.pairedFixtureCount)
        assertEquals(0, report.settledFixtureCount)
        assertEquals(2, report.pendingFixtureCount)
        assertEquals(setOf("bet365", "pinnacle"), report.bookmakerReports.map { it.bookmakerId }.toSet())
        assertTrue(report.bookmakerReports.all { it.settledFixtureCount == 0 })
        assertTrue(report.bookmakerReports.all { it.marketBrierScore == null && it.modelLogLoss == null })
    }

    @Test fun rejectsQuotesWithoutTrustedFixtureLinkFreshnessOrPreKickoffCapture() {
        val baseline = quote("the_odds_api", "bet365", captured)
        val tooOld = baseline.copy(providerPolledAtMillis = captured - 16 * 60_000L)
        val postKickoff = baseline.copy(capturedAtMillis = kickoff, providerPolledAtMillis = kickoff)
        val unlinked = baseline.copy(matchId = null, matchLinkBasis = null)
        val forecast = forecast(id = 1, at = captured - 1_000L, outcome = "H")

        val report = InternationalMarketEvaluationEngine.evaluate(
            listOf(tooOld, postKickoff, unlinked),
            listOf(forecast),
        )

        assertEquals(0, report.quoteFixtureCount)
        assertEquals(0, report.pairedFixtureCount)
        assertTrue(report.bookmakerReports.isEmpty())
    }

    @Test fun quoteWithoutAnEarlierModelForecastIsNotPaired() {
        val quote = quote("the_odds_api", "bet365", captured)
        val onlyLaterForecast = forecast(id = 1, at = captured + 1L, outcome = "H")

        val report = InternationalMarketEvaluationEngine.evaluate(listOf(quote), listOf(onlyLaterForecast))

        assertEquals(1, report.quoteFixtureCount)
        assertEquals(0, report.pairedFixtureCount)
        assertNull(report.bookmakerReports.single().marketBrierScore)
    }

    @Test fun conflictingLatestResultCorrectionsAreExcludedFromScoring() {
        val quote = quote("the_odds_api", "bet365", captured)
        val first = forecast(id = 1, at = captured - 1L, outcome = "H")
        val conflictingCorrection = first.copy(snapshotId = 2, actualOutcome = "A", homeGoals = 0, awayGoals = 2)

        val report = InternationalMarketEvaluationEngine.evaluate(listOf(quote), listOf(first, conflictingCorrection))

        assertEquals(1, report.pairedFixtureCount)
        assertEquals(0, report.settledFixtureCount)
        assertNull(report.bookmakerReports.single().marketLogLoss)
    }

    @Test fun pairedBootstrapIntervalsAreDeterministicAndUseEloMinusMarketDirection() {
        val fixtures = settledFixtures(60)
        val first = InternationalMarketEvaluationEngine.evaluate(fixtures.first, fixtures.second)
            .bookmakerReports.single()
        val repeated = InternationalMarketEvaluationEngine.evaluate(fixtures.first, fixtures.second)
            .bookmakerReports.single()

        assertEquals(first.eloMinusMarketBrier95, repeated.eloMinusMarketBrier95)
        assertEquals(first.eloMinusMarketLogLoss95, repeated.eloMinusMarketLogLoss95)
        assertEquals(first.eloMinusMarketTopAccuracy95, repeated.eloMinusMarketTopAccuracy95)
        assertTrue(first.eloMinusMarketBrier95!!.upper < 0.0)
        assertTrue(first.eloMinusMarketLogLoss95!!.upper < 0.0)
        assertTrue(first.eloMinusMarketTopAccuracy95!!.lower > 0.0)
        listOf(first.eloMinusMarketBrier95, first.eloMinusMarketLogLoss95, first.eloMinusMarketTopAccuracy95)
            .forEach { interval ->
                assertTrue(interval!!.lower <= interval.upper)
                assertTrue(interval.lower.isFinite() && interval.upper.isFinite())
            }
    }

    @Test fun pairedBootstrapIntervalsStayUnavailableBelowMinimumSettledSample() {
        val fixtures = settledFixtures(InternationalMarketEvaluationEngine.MIN_SETTLED_FIXTURES_FOR_INTERVALS - 1)
        val report = InternationalMarketEvaluationEngine.evaluate(fixtures.first, fixtures.second)
            .bookmakerReports.single()

        assertEquals(29, report.settledFixtureCount)
        assertNull(report.eloMinusMarketBrier95)
        assertNull(report.eloMinusMarketLogLoss95)
        assertNull(report.eloMinusMarketTopAccuracy95)
    }

    private fun settledFixtures(count: Int): Pair<List<InternationalOddsSnapshot>, List<ProspectiveForecastObservation>> {
        val quotes = ArrayList<InternationalOddsSnapshot>(count)
        val forecasts = ArrayList<ProspectiveForecastObservation>(count)
        repeat(count) { index ->
            val matchId = 1_000 + index
            val fixtureKickoff = kickoff + index * 2L * 24 * 60 * 60_000L
            val capturedAt = fixtureKickoff - 60_000L
            val outcome = listOf("H", "D", "A")[index % 3]
            val modelProbabilities = when (outcome) {
                "H" -> OutcomeProbabilities(.8, .1, .1)
                "D" -> OutcomeProbabilities(.1, .8, .1)
                else -> OutcomeProbabilities(.1, .1, .8)
            }
            quotes += quote(
                provider = "the_odds_api",
                bookmaker = "bootstrap-book",
                capturedAt = capturedAt,
                home = 3.0,
                draw = 3.0,
                away = 3.0,
                matchId = matchId,
                kickoffAt = fixtureKickoff,
            )
            forecasts += forecast(
                id = matchId.toLong(),
                matchId = matchId,
                at = capturedAt - 1_000L,
                outcome = outcome,
                kickoffAt = fixtureKickoff,
            ).copy(probabilities = modelProbabilities)
        }
        return quotes to forecasts
    }

    private fun quote(
        provider: String,
        bookmaker: String,
        capturedAt: Long,
        home: Double = 2.15,
        draw: Double = 3.2,
        away: Double = 3.4,
        matchId: Int = 42,
        kickoffAt: Long = kickoff,
    ) = InternationalOddsSnapshot(
        providerId = provider,
        bookmakerId = bookmaker,
        eventId = "event-$matchId",
        sourceUrl = if (provider == "moviodds_bet365") "https://moviodds.com/odds" else "https://api.example.test/odds",
        league = "Serie A",
        region = "eu",
        capturedAtMillis = capturedAt,
        providerPolledAtMillis = capturedAt - 1_000L,
        kickoffMillis = kickoffAt,
        market = "1X2",
        homeTeam = "Roma",
        awayTeam = "Napoli",
        home = home,
        draw = draw,
        away = away,
        homePriceChangedAtMillis = capturedAt - 1_000L,
        drawPriceChangedAtMillis = capturedAt - 1_000L,
        awayPriceChangedAtMillis = capturedAt - 1_000L,
        matchId = matchId,
        matchLinkBasis = InternationalMatchLinkBasis.EXACT_KICKOFF,
    )

    private fun forecast(id: Long, matchId: Int = 42, at: Long, outcome: String?, kickoffAt: Long = kickoff) = ProspectiveForecastObservation(
        snapshotId = id,
        modelVersion = CURRENT_ADVANCED_MODEL_VERSION,
        matchId = matchId,
        competition = "Serie A",
        homeTeam = "Roma",
        awayTeam = "Napoli",
        forecastedAtMillis = at,
        kickoffMillis = kickoffAt,
        historyMatchCount = EloEngine.MIN_HISTORY_MATCHES_FOR_FORECAST,
        probabilities = OutcomeProbabilities(.6, .3, .1),
        actualOutcome = outcome,
        homeGoals = when (outcome) { "H" -> 2; "A" -> 0; "D" -> 1; else -> null },
        awayGoals = when (outcome) { "H" -> 1; "A" -> 2; "D" -> 1; else -> null },
        settledAtMillis = if (outcome == null) null else kickoffAt + 20_000L,
        resultAvailableAtMillis = if (outcome == null) null else kickoffAt + 10_000L,
        resultSourceName = if (outcome == null) null else "OpenLigaDB",
        resultSourceUrl = if (outcome == null) null else "https://www.openligadb.de",
    )
}
