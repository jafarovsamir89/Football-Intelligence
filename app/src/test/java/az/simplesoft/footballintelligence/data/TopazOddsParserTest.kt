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
    private fun page(text:String=pageText,url:String="https://etopaz.az/home?sport-id=1%3Asr%3Asport%3A1")=MisliVisiblePage(
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
        val farFuture=page(pageText.replace("12 noy","10 iyn"))
        assertTrue(TopazOddsProvider().parseVisiblePage(farFuture,now).snapshots.isEmpty())
    }
}
