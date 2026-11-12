package az.simplesoft.footballintelligence.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class InternationalOddsProviderTest {
    private val provider=Bet365ViaMoviOddsProvider()
    private val captured=Instant.parse("2026-11-12T00:40:00Z").toEpochMilli()
    private val kickoff=captured+3_600_000L

    @Test fun acceptsFreshCompletePrematchLineAndStoresThreeIndependentTimes() {
        val fixture=FixtureIdentity(701,"Roma","Napoli",kickoff+60_000L)
        val result=provider.parse(response(match(pollAt=iso(captured-1_000L),priceTs=iso(captured-20*60_000L))),captured,listOf(fixture))

        assertEquals(InternationalOddsStatus.LIVE,result.status)
        assertEquals(1,result.snapshots.size)
        val quote=result.snapshots.single()
        assertEquals("bet365",quote.bookmakerId)
        assertEquals(MOVIODDS_PROVIDER_ID,quote.providerId)
        assertEquals(2.15,quote.home,0.0)
        assertEquals(quote.matchId,701)
        assertEquals(captured,quote.capturedAtMillis)
        assertEquals(captured-1_000L,quote.providerPolledAtMillis)
        assertEquals(captured-20*60_000L,quote.drawPriceChangedAtMillis)
    }

    @Test fun stalePollSuspensionIncompleteMarketAndInPlayAreRejected() {
        val stale=match(id="1",pollAt=iso(captured-16*60_000L))
        val suspended=match(id="2",suspendDraw=true)
        val incomplete=match(id="3",awayLabel="Unknown Team")
        val inPlay=match(id="4",inPlay=true)
        val result=provider.parse(response(stale,suspended,incomplete,inPlay),captured)

        assertTrue(result.snapshots.isEmpty())
        assertEquals(InternationalOddsStatus.STALE,result.status)
        assertEquals(1,result.staleMatches)
        assertEquals(3,result.rejectedMatches)
    }

    @Test fun missingPollClockSkewAndFuturePollAreNotReportedAsLive() {
        val noPoll=match(id="1",includeGroupStatus=false)
        val noPollResult=provider.parse(response(noPoll),captured)
        assertEquals(InternationalOddsStatus.UNVERIFIED,noPollResult.status)

        val wrongClock=provider.parse(response(match()),captured+6*60_000L)
        assertEquals(InternationalOddsStatus.UNVERIFIED,wrongClock.status)

        val futurePoll=match(id="5",pollAt=iso(captured+3*60_000L))
        val futurePollResult=provider.parse(response(futurePoll),captured)
        assertEquals(InternationalOddsStatus.UNVERIFIED,futurePollResult.status)

        val staleFlag=provider.parse(response(match(id="6",stale=true)),captured)
        assertEquals(InternationalOddsStatus.STALE,staleFlag.status)
    }

    @Test fun matchLinkUsesUniqueExactOrderAndKickoff() {
        val exact=provider.parse(response(match()),captured,listOf(FixtureIdentity(7,"Roma","Napoli",kickoff)))
        val reversed=provider.parse(response(match()),captured,listOf(FixtureIdentity(8,"Napoli","Roma",kickoff)))
        val ambiguous=provider.parse(response(match()),captured,listOf(
            FixtureIdentity(7,"Roma","Napoli",kickoff),FixtureIdentity(8,"Roma","Napoli",kickoff),
        ))
        val outsideTolerance=provider.parse(response(match()),captured,listOf(FixtureIdentity(9,"Roma","Napoli",kickoff+21*60_000L)))

        assertEquals(7,exact.snapshots.single().matchId)
        assertNull(reversed.snapshots.single().matchId)
        assertNull(ambiguous.snapshots.single().matchId)
        assertNull(outsideTolerance.snapshots.single().matchId)
    }

    @Test fun duplicateUnderlyingEventsAreDiscardedEvenWhenSourceIdsDiffer() {
        val first=match(id="1")
        val second=match(id="2",home="ROMA",away="Napoli",kickoffAt=iso(kickoff+4*60_000L))
        val result=provider.parse(response(first,second),captured)
        val beyondTolerance=provider.parse(response(first,match(id="3",kickoffAt=iso(kickoff+6*60_000L))),captured)

        assertEquals(1,result.snapshots.size)
        assertEquals(1,result.duplicateMatches)
        assertEquals(2,beyondTolerance.snapshots.size)
        assertEquals(0,beyondTolerance.duplicateMatches)
    }

    @Test fun qualityGateRejectsInvalidPricesAndDistinguishesSourcePollFromPriceChange() {
        assertTrue(OddsQualityGate.validPriceAndChangeTime(2.2,captured-60_000,captured-1_000,captured))
        assertFalse(OddsQualityGate.validPriceAndChangeTime(Double.NaN,captured-1_000,captured-1_000,captured))
        assertFalse(OddsQualityGate.validPriceAndChangeTime(1.0,captured-1_000,captured-1_000,captured))
        assertFalse(OddsQualityGate.validPriceAndChangeTime(2.2,captured+3*60_000,captured-1_000,captured))
        assertEquals(InternationalOddsStatus.STALE,OddsQualityGate.sourcePollStatus(captured-16*60_000,captured,false))
        assertEquals(InternationalOddsStatus.UNVERIFIED,OddsQualityGate.sourcePollStatus(captured+3*60_000,captured,false))
        assertNull(OddsQualityGate.sourcePollStatus(captured-1_000,captured,false))
    }

    @Test fun malformedResponseHasNoInventedFallback() {
        assertTrue(runCatching { provider.parse("{}",captured) }.isFailure)
    }

    private fun response(vararg matches:String,serverTime:String=iso(captured)):String =
        """{"server_time":"$serverTime","matches":[${matches.joinToString(",")}] }"""

    private fun match(
        id:String="777",
        home:String="Roma",
        away:String="Napoli",
        awayLabel:String=away,
        pollAt:String=iso(captured-1_000L),
        priceTs:String=iso(captured-20*60_000L),
        stale:Boolean=false,
        suspendDraw:Boolean=false,
        inPlay:Boolean=false,
        includeGroupStatus:Boolean=true,
        kickoffAt:String=iso(kickoff),
    ):String {
        val groupStatus=if(includeGroupStatus) "\"group_status\":{\"40\":{\"polled_at\":\"$pollAt\",\"stale\":$stale}}," else ""
        val drawSuspended=if(suspendDraw) ",\"suspended\":true" else ""
        return """{"id":"$id","home":"$home","away":"$away","league":"Serie A","region":"uk","sport":"soccer","in_play":$inPlay,"kickoff_utc":"$kickoffAt",$groupStatus"quotes":[
            {"group_id":"40","market_id":"40","label":"$home","odds_dec":2.15,"ts":"$priceTs"},
            {"group_id":"40","market_id":"40","label":"Draw","odds_dec":3.2,"ts":"$priceTs"$drawSuspended},
            {"group_id":"40","market_id":"40","label":"$awayLabel","odds_dec":3.4,"ts":"$priceTs"}
        ]}"""
    }

    private fun iso(millis:Long)=Instant.ofEpochMilli(millis).toString()
}
