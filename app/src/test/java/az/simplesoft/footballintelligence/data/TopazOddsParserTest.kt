package az.simplesoft.footballintelligence.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TopazOddsParserTest {
    private val now=Instant.parse("2026-11-09T23:59:00Z").toEpochMilli()
    private val kickoff=Instant.parse("2026-11-12T17:00:00Z").toEpochMilli()
    private val pageText="""İdman
        |Futbol (649)
        |POPULAR 7
        |Türkiye - Belçika
        |12 noy | 09:00
        |1
        |3.30
        |x
        |3.64
        |2
        |1.88
        |+610
        |""".trimMargin()
    private fun page(text:String=pageText,url:String=TopazOddsProvider.PUBLIC_PREMATCH_URL)=MisliVisiblePage(
        url,"Azərbaycanda Rəsmi Onlayn İdman Mərcləri Operatoru",text,"America/Los_Angeles"
    )

    @Test fun parsesVisibleFuturePrematchRowAndUsesLocalTimezone() {
        val provider=TopazOddsProvider()
        val result=provider.parseVisiblePage(page(),now,listOf(FixtureIdentity(42,"Türkiye","Belgium",kickoff)))
        assertEquals(1,result.snapshots.size)
        val quote=result.snapshots.single()
        assertEquals(42,quote.matchId)
        assertEquals(3.30,quote.home,0.0)
        assertEquals(3.64,quote.draw,0.0)
        assertEquals(1.88,quote.away,0.0)
        assertEquals("2026-11-12",quote.eventDate)
        assertEquals(kickoff,quote.kickoffMillis)
        assertTrue(quote.eventYearInferred)
        assertEquals(OddsProviderStatus.LIVE,provider.status().status)
    }

    @Test fun parsesObservedEnglishPrematchCardAndKeepsOnlyFirstThreePrices() {
        val capture=Instant.parse("2026-11-13T09:00:00Z").toEpochMilli()
        val kickoff=Instant.parse("2026-11-13T10:45:00Z").toEpochMilli()
        val observedText="""Pre-match
            |13 Nov
            |14:45
            |Serbia
            |Germany
            |1
            |8.92
            |5.88
            |1.24
            |2.5
            |1.37
            |2.58
            |+679
            |""".trimMargin()

        val result=TopazOddsProvider().parseVisiblePage(
            page(observedText).copy(timeZoneId="Asia/Baku"),
            capture,
            listOf(FixtureIdentity(77,"Serbia","Germany",kickoff)),
        )

        val quote=result.snapshots.single()
        assertEquals(77,quote.matchId)
        assertEquals("Serbia",quote.homeTeam)
        assertEquals("Germany",quote.awayTeam)
        assertEquals("2026-11-13",quote.eventDate)
        assertEquals(kickoff,quote.kickoffMillis)
        assertEquals(8.92,quote.home,0.0)
        assertEquals(5.88,quote.draw,0.0)
        assertEquals(1.24,quote.away,0.0)
        assertTrue(quote.eventYearInferred)
    }

    @Test fun parsesKickoffAndExactFixtureInBakuTimezone() {
        val page=page().copy(timeZoneId="Asia/Baku")
        val localKickoff=Instant.parse("2026-11-12T05:00:00Z").toEpochMilli()
        val result=TopazOddsProvider().parseVisiblePage(
            page,
            now,
            listOf(FixtureIdentity(43,"Türkiye","Belgium",localKickoff)),
        )

        val quote=result.snapshots.single()
        assertEquals(localKickoff,quote.kickoffMillis)
        assertEquals("Asia/Baku",quote.timeZoneId)
        assertEquals("2026-11-12",quote.eventDate)
        assertEquals(43,quote.matchId)
    }

    @Test fun infersTheNextJanuaryEventYearAcrossTimezoneDateBoundaries() {
        val capture=Instant.parse("2026-12-31T20:30:00Z").toEpochMilli()
        val januaryPage=page(
            pageText.replace("12 noy | 09:00","02 yan | 08:00"),
        )
        val cases=listOf(
            "America/Los_Angeles" to "2027-01-02T16:00:00Z",
            "Asia/Baku" to "2027-01-02T04:00:00Z",
        )

        cases.forEach { (zone,expectedKickoff) ->
            val quote=TopazOddsProvider().parseVisiblePage(
                januaryPage.copy(timeZoneId=zone),
                capture,
            ).snapshots.single()
            assertEquals("2027-01-02",quote.eventDate)
            assertEquals(Instant.parse(expectedKickoff).toEpochMilli(),quote.kickoffMillis)
            assertTrue(quote.eventYearInferred)
        }
    }

    @Test fun rejectsPageWithUnknownTimezoneInsteadOfAssumingUtc() {
        val result=TopazOddsProvider().parseVisiblePage(
            page().copy(timeZoneId="Unknown/Zone"),
            now,
        )

        assertTrue(result.snapshots.isEmpty())
        assertTrue(result.detail.contains("часовой пояс"))
    }

    @Test fun exactOrientationRequiredForLocalFixtureLink() {
        val result=TopazOddsProvider().parseVisiblePage(page(),now,listOf(FixtureIdentity(42,"Belgium","Türkiye",kickoff)))
        assertNull(result.snapshots.single().matchId)
    }

    @Test fun linksCapturedAzerbaijaniNamesToTheExactGermanOpenLigaDbFixture() {
        val result=TopazOddsProvider().parseVisiblePage(page(),now,listOf(FixtureIdentity(85220,"Türkei","Belgien",kickoff)))
        assertEquals(85220,result.snapshots.single().matchId)
        assertEquals("turkey",TeamNameIdentity.canonical("Türkiyə"))
        assertEquals("turkey",TeamNameIdentity.canonical("Türkei"))
        assertEquals("belgium",TeamNameIdentity.canonical("Belçika"))
        assertEquals("belgium",TeamNameIdentity.canonical("Belgien"))
    }

    @Test fun rejectsPastSameDayLiveOrMissingPriceRows() {
        val past="""Futbol (3)
            |CD Junior - Cucuta Deportivo
            |09 noy | 14:05
            |1
            |1.63
            |x
            |3.22
            |2
            |3.78
            |Luqueno Sportivo - CS 2 De Mayo
            |2H
            |69’
            ||
            |1-0
            |1
            |1.09
            |x
            |4.08
            |2
            |24.25
            |""".trimMargin()
        assertTrue(TopazOddsProvider().parseVisiblePage(page(past),now).snapshots.isEmpty())
        val noPrice=pageText.replace("3.30","-")
        assertTrue(TopazOddsProvider().parseVisiblePage(page(noPrice),now).snapshots.isEmpty())
    }

    @Test fun rejectsUnapprovedHostAndDatesMoreThanNinetyDaysAway() {
        val provider=TopazOddsProvider()
        assertTrue(provider.parseVisiblePage(page(url="https://notetopaz.example/home"),now).snapshots.isEmpty())
        assertTrue(provider.parseVisiblePage(page(url="https://etopaz.az/eng/home"),now).snapshots.isEmpty())
        val farFuture=page(pageText.replace("12 noy","10 iyn"))
        assertTrue(TopazOddsProvider().parseVisiblePage(farFuture,now).snapshots.isEmpty())
    }
}
