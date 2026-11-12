package az.simplesoft.footballintelligence.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TheOddsApiProviderTest {
    private val provider = TheOddsApiProvider()
    private val captured = Instant.parse("2026-11-12T08:36:30Z").toEpochMilli()
    private val kickoff = Instant.parse("2026-11-21T12:30:00Z").toEpochMilli()

    @Test fun acceptsFreshEuropeanH2hLinesFromIndependentBookmakersAndExactLinks() {
        val fixture = FixtureIdentity(42, "Manchester City FC", "Fulham FC", kickoff + 60_000L)
        val result = provider.parseOdds(
            response(event(book("unibet_fr", "Unibet (FR)"), book("onexbet", "1xBet"))),
            capturedAtMillis = captured,
            responseDateMillis = captured,
            sportKey = "soccer_epl",
            fixtures = listOf(fixture),
        )

        assertEquals(InternationalOddsStatus.LIVE, result.status)
        assertEquals(InternationalOddsQualityCounts(2, 2, 0, 0, 0, 0), result.qualityCounts)
        assertEquals(setOf("unibet_fr", "onexbet"), result.snapshots.map { it.bookmakerId }.toSet())
        val quote = result.snapshots.first { it.bookmakerId == "unibet_fr" }
        assertEquals(THE_ODDS_API_PROVIDER_ID, quote.providerId)
        assertEquals(1.27, quote.home, 0.0)
        assertEquals(4.5, quote.draw, 0.0)
        assertEquals(5.8, quote.away, 0.0)
        assertEquals(42, quote.matchId)
        assertEquals(captured, quote.capturedAtMillis)
        assertEquals(captured - 10_000L, quote.providerPolledAtMillis)
        assertEquals(quote.providerPolledAtMillis, quote.homePriceChangedAtMillis)
        assertFalse(quote.sourceUrl.contains("apiKey"))
        assertFalse(quote.sourceUrl.contains("secret"))
    }

    @Test fun rejectsStaleIncompleteLiveAndDuplicateMarketsWithExclusiveCounts() {
        val stale = event(book("old", "Old", updatedAt = captured - 16 * 60_000L))
        val incomplete = event(book("partial", "Partial", outcomes = """[{"name":"Manchester City","price":1.4},{"name":"Fulham","price":6.5}]"""))
        val inPlay = event(book("live", "Live"), kickoffAt = Instant.ofEpochMilli(captured - 1_000L).toString())
        val duplicateOutcome = event(book("duplicate", "Duplicate", outcomes = """[
            {"name":"Manchester City","price":1.4},{"name":"Manchester City","price":1.5},
            {"name":"Draw","price":4.0},{"name":"Fulham","price":6.5}]"""))

        val result = provider.parseOdds(
            response(stale, incomplete, inPlay, duplicateOutcome), captured, captured, "soccer_epl",
        )

        assertTrue(result.snapshots.isEmpty())
        assertEquals(InternationalOddsStatus.STALE, result.status)
        assertEquals(InternationalOddsQualityCounts(4, 0, 3, 1, 0, 0), result.qualityCounts)
    }

    @Test fun missingOrSkewedHttpDateDoesNotPromotePricesToFresh() {
        val payload = response(event(book("book", "Book")))
        val missing = provider.parseOdds(payload, captured, null, "soccer_epl")
        val skewed = provider.parseOdds(payload, captured, captured + 6 * 60_000L, "soccer_epl")

        assertEquals(InternationalOddsStatus.UNVERIFIED, missing.status)
        assertEquals(InternationalOddsQualityCounts(1, 0, 0, 0, 0, 1), missing.qualityCounts)
        assertEquals(InternationalOddsStatus.UNVERIFIED, skewed.status)
        assertEquals(InternationalOddsQualityCounts(1, 0, 0, 0, 0, 1), skewed.qualityCounts)
    }

    @Test fun duplicateUnderlyingEventsForSameBookDoNotCreateDoubleLines() {
        val first = event(book("book", "Book"), id = "first")
        val second = event(book("book", "Book"), id = "second", home = "MANCHESTER CITY", kickoffAt = Instant.ofEpochMilli(kickoff + 4 * 60_000L).toString())
        val result = provider.parseOdds(response(first, second), captured, captured, "soccer_epl")

        assertEquals(1, result.snapshots.size)
        assertEquals(InternationalOddsQualityCounts(2, 1, 0, 0, 1, 0), result.qualityCounts)
    }

    @Test fun reviewedFootballClubSuffixAndShortNameVariantsNormalizeExactly() {
        assertEquals("manchester city", TeamNameIdentity.canonical("Manchester City FC"))
        assertEquals("manchester city", TeamNameIdentity.canonical("Man City"))
        assertEquals("bournemouth", TeamNameIdentity.canonical("AFC Bournemouth"))
        assertEquals("fulham", TeamNameIdentity.canonical("Fulham FC"))
        assertEquals("paris fc", TeamNameIdentity.canonical("FC Paris"))
    }

    @Test fun activeCatalogAndRoundRobinStayWithinFreePlanBatch() {
        val catalog = """[
          {"key":"soccer_epl","title":"EPL","active":true},
          {"key":"soccer_test","title":"Test","active":false},
          {"key":"americanfootball_nfl","title":"NFL","active":true},
          {"key":"soccer_laliga","title":"La Liga","active":true}
        ]"""
        val sports = provider.parseActiveSoccerSports(catalog)
        assertEquals(listOf("soccer_epl", "soccer_laliga"), sports.map { it.key })
        assertEquals(listOf("soccer_laliga", "soccer_epl"), provider.nextSports(sports, cursor = 1, limit = 12).map { it.key })
        assertEquals(THE_ODDS_API_DAILY_LEAGUE_LIMIT, provider.nextSports((1..20).map { TheOddsApiSport("soccer_$it", "L$it") }, 0).size)
    }

    @Test fun apiKeyIsSentInPrivateHeaderAndNeverInRequestOrPersistedSourceUrl() {
        assertEquals("$THE_ODDS_API_BASE_URL/sports/", theOddsApiSportsUrl())
        assertEquals("$THE_ODDS_API_BASE_URL/sports/soccer_epl/odds/?regions=eu&markets=h2h&oddsFormat=decimal&dateFormat=iso", theOddsApiOddsUrl("soccer_epl"))
        assertEquals(mapOf("x-api-key" to "private-key"), theOddsApiAuthHeaders("private-key"))
        assertFalse(theOddsApiSportsUrl().contains("apiKey"))
        assertFalse(theOddsApiOddsUrl("soccer_epl").contains("apiKey"))
        assertFalse(theOddsApiOddsUrl("soccer_epl").contains("private-key"))
    }

    private fun response(vararg events: String): String = "[${events.joinToString(",")}]"

    private fun event(
        vararg bookmakers: String,
        id: String = "event-1",
        home: String = "Manchester City",
        away: String = "Fulham",
        kickoffAt: String = Instant.ofEpochMilli(kickoff).toString(),
    ) = """{"id":"$id","sport_key":"soccer_epl","sport_title":"EPL","commence_time":"$kickoffAt","home_team":"$home","away_team":"$away","bookmakers":[${bookmakers.joinToString(",")}] }"""

    private fun book(
        key: String,
        title: String,
        updatedAt: Long = captured - 10_000L,
        outcomes: String = """[
            {"name":"Fulham","price":5.8},
            {"name":"Draw","price":4.5},
            {"name":"Manchester City","price":1.27}
        ]""",
    ) = """{"key":"$key","title":"$title","markets":[{"key":"h2h","last_update":"${Instant.ofEpochMilli(updatedAt)}","outcomes":$outcomes}]}"""
}
