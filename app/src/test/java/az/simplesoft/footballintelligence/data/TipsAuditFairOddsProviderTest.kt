package az.simplesoft.footballintelligence.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TipsAuditFairOddsProviderTest {
    private val provider = TipsAuditFairOddsProvider()
    private val capturedAt = Instant.parse("2026-11-11T23:22:04Z").toEpochMilli()

    @Test fun acceptsOnlyFreshLivePrematchFairMarketRowsAndLinksExactTeams() {
        val result = provider.parse(response(row(readAt = "2026-11-11T23:15:00Z")), capturedAt, listOf(
            FixtureIdentity(85220, "Türkei", "Belgien", Instant.parse("2026-11-12T17:00:00Z").toEpochMilli()),
        ))

        assertEquals(1, result.snapshots.size)
        val snapshot = result.snapshots.single()
        assertEquals(TIPSAUDIT_PROVIDER_ID, snapshot.providerId)
        assertEquals("Türkiye", snapshot.homeTeam)
        assertEquals(85220, snapshot.matchId)
        assertEquals("live", snapshot.fairSource)
        assertEquals("2.0", snapshot.methodologyVersion)
        assertEquals(1, result.receivedRows)
    }

    @Test fun rejectsStaleFutureAndLoggedRows() {
        val stale = row(id=1,readAt = "2026-11-11T22:34:39Z")
        val future = row(id=2,readAt = "2026-11-11T23:23:00Z")
        val logged = row(id=3,readAt = "2026-11-11T23:15:00Z", fairSource = "logged")

        val result = provider.parse(response(stale, future, logged), capturedAt)

        assertTrue(result.snapshots.isEmpty())
        assertEquals(2, result.staleRows)
        assertEquals(1, result.rejectedRows)
    }

    @Test fun rejectsInvalidProbabilitiesOddsAndNonTipsAuditUrls() {
        val badProbability = row(id=4,readAt = "2026-11-11T23:15:00Z", homeProbability = 0.6)
        val badOdds = row(id=5,readAt = "2026-11-11T23:15:00Z", homeOdds = 3.0)
        val badUrl = row(id=6,readAt = "2026-11-11T23:15:00Z", url = "https://example.org/event")

        val result = provider.parse(response(badProbability, badOdds, badUrl), capturedAt)

        assertEquals(3, result.rejectedRows)
        assertTrue(result.snapshots.isEmpty())
    }

    @Test fun matchLinkRequiresUniqueExactOrientationAndCloseKickoff() {
        val kickoff = Instant.parse("2026-11-12T17:00:00Z").toEpochMilli()
        val base = FixtureIdentity(1, "Türkei", "Belgien", kickoff)
        val exact = provider.parse(response(row(readAt = "2026-11-11T23:15:00Z")), capturedAt, listOf(base))
        val reversed = provider.parse(response(row(readAt = "2026-11-11T23:15:00Z")), capturedAt, listOf(base.copy(homeTeam = "Belgium", awayTeam = "Türkiye")))
        val ambiguous = provider.parse(response(row(readAt = "2026-11-11T23:15:00Z")), capturedAt, listOf(base, base.copy(matchId = 2)))
        val farKickoff = provider.parse(response(row(readAt = "2026-11-11T23:15:00Z")), capturedAt, listOf(base.copy(kickoffMillis = kickoff + 21 * 60_000L)))

        assertEquals(1, exact.snapshots.single().matchId)
        assertNull(reversed.snapshots.single().matchId)
        assertNull(ambiguous.snapshots.single().matchId)
        assertNull(farKickoff.snapshots.single().matchId)
    }

    @Test fun duplicateExternalEventsAreKeptOnceAndMalformedRootIsRejected() {
        val result = provider.parse(
            response(row(readAt = "2026-11-11T23:15:00Z"), row(readAt = "2026-11-11T23:16:00Z")),
            capturedAt,
        )
        assertEquals(1, result.snapshots.size)
        assertEquals(1, result.duplicateRows)
        assertTrue(runCatching { provider.parse("{}", capturedAt) }.isFailure)
    }

    private fun response(vararg predictionRows: String): String = """
        {
          "methodology_version":"2.0",
          "predictions":[${predictionRows.joinToString(",")}] 
        }
    """.trimIndent()

    private fun row(
        id:Int=16388,
        readAt: String,
        fairSource: String = "live",
        homeProbability: Double = 0.2427,
        homeOdds: Double = 4.12,
        url: String = "https://tipsaudit.com/match/16388/turkiye-vs-belgium-2026-11-12",
    ): String = """
        {
          "id":$id,"home":"Türkiye","away":"Belgium",
          "kickoff_utc":"2026-11-12T17:00:00+00:00",
          "fair_source":"$fairSource","fair_read_at":"$readAt",
          "fair_probabilities":{"home":$homeProbability,"draw":0.2282,"away":0.5291},
          "fair_odds":{"home":$homeOdds,"draw":4.38,"away":1.89},
          "url":"$url"
        }
    """.trimIndent()
}
