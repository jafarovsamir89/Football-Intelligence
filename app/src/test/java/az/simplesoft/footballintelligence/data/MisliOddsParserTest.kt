package az.simplesoft.footballintelligence.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MisliOddsParserTest {
    private val url = "https://www.misli.az/idman-novleri/futbol"
    private val now = Instant.parse("2026-11-09T18:00:00Z").toEpochMilli()

    private fun page(text: String, timeZone: String = "America/Los_Angeles") = MisliVisiblePage(
        url=url,title="Futbol oyunları əmsalları | Misli.az",visibleText=text,timeZoneId=timeZone
    )

    @Test fun parsesOnlyCompleteFutureVisibleOneXTwoWithLocalTimezone() {
        val provider=MisliOddsProvider()
        val result=provider.parseVisiblePage(
            page("Sabah\n10.11.2026\n09:45\n2\nHaken (Q)\n-\nÇelsi (Q)\n1\n10.50\nX\n6.26\n2\n1.17"),now
        )

        assertEquals(1,result.snapshots.size)
        val quote=result.snapshots.single()
        assertEquals(10.50,quote.home,0.0)
        assertEquals(6.26,quote.draw,0.0)
        assertEquals(1.17,quote.away,0.0)
        assertEquals("2026-11-10",quote.eventDate)
        assertEquals("America/Los_Angeles",quote.timeZoneId)
        assertEquals(Instant.parse("2026-11-10T17:45:00Z").toEpochMilli(),quote.kickoffMillis)
        assertEquals("hacken",TeamNameIdentity.canonical(quote.homeTeam))
        assertEquals("chelsea",TeamNameIdentity.canonical(quote.awayTeam))
    }

    @Test fun convertsVisibleKickoffInThePageTimezoneAcrossEuropeAndAsia() {
        val text="Sabah\n10.11.2026\n09:45\n2\nHaken (Q)\n-\nÇelsi (Q)\n1\n10.50\nX\n6.26\n2\n1.17"
        val cases=listOf(
            "America/Los_Angeles" to "2026-11-10T17:45:00Z",
            "Europe/London" to "2026-11-10T09:45:00Z",
            "Asia/Baku" to "2026-11-10T05:45:00Z",
            "Asia/Tokyo" to "2026-11-10T00:45:00Z",
        )

        cases.forEach { (zone, expectedKickoff) ->
            val quote=MisliOddsProvider().parseVisiblePage(page(text,zone),now).snapshots.single()
            assertEquals(zone,quote.timeZoneId)
            assertEquals("2026-11-10",quote.eventDate)
            assertEquals(Instant.parse(expectedKickoff).toEpochMilli(),quote.kickoffMillis)
            assertEquals(now,quote.capturedAtMillis)
        }
    }

    @Test fun doesNotLinkWithinToleranceWhenFixtureFallsOnTheNextPageLocalDate() {
        val capture=Instant.parse("2026-11-10T18:00:00Z").toEpochMilli()
        val text="10.11.2026\n23:50\n2\nHaken (Q)\n-\nÇelsi (Q)\n1\n10.50\nX\n6.26\n2\n1.17"
        val fixture=FixtureIdentity(
            78,
            "Häcken",
            "Chelsea",
            Instant.parse("2026-11-10T20:05:00Z").toEpochMilli(),
        )

        val quote=MisliOddsProvider().parseVisiblePage(page(text,"Asia/Baku"),capture,listOf(fixture)).snapshots.single()

        assertEquals(Instant.parse("2026-11-10T19:50:00Z").toEpochMilli(),quote.kickoffMillis)
        assertNull(quote.matchId)
    }

    @Test fun rejectsPageWithUnknownTimezoneInsteadOfGuessingKickoff() {
        val row="10.11.2026\n09:45\n2\nHaken (Q)\n-\nÇelsi (Q)\n1\n10.50\nX\n6.26\n2\n1.17"

        val result=MisliOddsProvider().parseVisiblePage(page(row,"Unknown/Zone"),now)

        assertTrue(result.snapshots.isEmpty())
        assertTrue(result.detail.contains("часовой пояс"))
    }

    @Test fun mapsOnlyUniqueExactFixtureWithHomeAwayAndKickoffMatch() {
        val kickoff=Instant.parse("2026-11-10T17:45:00Z").toEpochMilli()
        val result=MisliOddsProvider().parseVisiblePage(
            page("Sabah\n10.11.2026\n09:45\n2\nHaken (Q)\n-\nÇelsi (Q)\n1\n10.50\nX\n6.26\n2\n1.17"),
            now,
            listOf(FixtureIdentity(77,"Häcken","Chelsea",kickoff))
        )
        assertEquals(77,result.snapshots.single().matchId)
    }

    @Test fun rejectsWrongOrientationAndAmbiguousFixtureMatch() {
        val kickoff=Instant.parse("2026-11-10T17:45:00Z").toEpochMilli()
        val text="Sabah\n10.11.2026\n09:45\n2\nHaken (Q)\n-\nÇelsi (Q)\n1\n10.50\nX\n6.26\n2\n1.17"
        val reversed=MisliOddsProvider().parseVisiblePage(page(text),now,listOf(FixtureIdentity(1,"Chelsea","Häcken",kickoff)))
        assertNull(reversed.snapshots.single().matchId)
        val duplicate=MisliOddsProvider().parseVisiblePage(page(text),now,listOf(
            FixtureIdentity(1,"Häcken","Chelsea",kickoff),FixtureIdentity(2,"Häcken","Chelsea",kickoff)
        ))
        assertNull(duplicate.snapshots.single().matchId)
    }

    @Test fun excludesPastRowsLiveScoreRowsAndIncompletePrices() {
        val text="""Sabah
            |10.11.2026
            |09:45
            |2
            |Haken (Q)
            |-
            |Çelsi (Q)
            |1
            |10.50
            |X
            |6.26
            |2
            |1.17
            |11:00
            |2
            |Live Team
            |-
            |Away Team
            |2H
            |90’
            ||
            |1-2
            |12:00
            |2
            |No Odds
            |-
            |Other
            |1
            |-
            |X
            |-
            |2
            |-
            |""".trimMargin()
        val result=MisliOddsProvider().parseVisiblePage(page(text),Instant.parse("2026-11-10T18:00:00Z").toEpochMilli())
        assertTrue(result.snapshots.isEmpty())
        assertEquals(OddsProviderStatus.UNAVAILABLE,MisliOddsProvider().status().status)
    }

    @Test fun refusesUnapprovedHostAndConflictingDuplicatePrices() {
        val row="Sabah\n10.11.2026\n09:45\n2\nHaken (Q)\n-\nÇelsi (Q)\n1\n10.50\nX\n6.26\n2\n1.17"
        val wrongHost=MisliOddsProvider().parseVisiblePage(page(row).copy(url="https://example.com/idman-novleri/futbol"),now)
        assertTrue(wrongHost.snapshots.isEmpty())

        val duplicate=MisliOddsProvider().parseVisiblePage(page("$row\n09:45\n2\nHaken (Q)\n-\nÇelsi (Q)\n1\n9.50\nX\n6.26\n2\n1.17"),now)
        assertTrue(duplicate.snapshots.isEmpty())
    }
}
