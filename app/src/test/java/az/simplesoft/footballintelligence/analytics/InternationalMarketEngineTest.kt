package az.simplesoft.footballintelligence.analytics

import az.simplesoft.footballintelligence.data.InternationalOddsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InternationalMarketEngineTest {
    private val kickoff=1_800_000_000_000L

    @Test fun duplicateBookmakerQuotesFromDifferentIntermediariesCountOnce() {
        val sameBookA=quote("moviodds_bet365","bet365","https://moviodds.com/odds",captured=100_000)
        val sameBookB=quote("another_provider","bet365","https://example.org/odds",captured=101_000,home=2.0,draw=3.5,away=3.8)

        val consensus=InternationalMarketEngine.consensus(listOf(sameBookA,sameBookB)).single()

        assertEquals(1,consensus.distinctBookmakerCount)
        assertEquals(1.0/2.0/(1.0/2.0+1.0/3.5+1.0/3.8),consensus.probabilities.home,1e-9)
    }

    @Test fun consensusAveragesNoVigProbabilitiesAcrossDistinctBookmakers() {
        val first=quote("moviodds_bet365","bet365","https://moviodds.com/odds",captured=100_000)
        val second=quote("other","Pinnacle","https://example.org/odds",captured=100_500,home=2.0,draw=3.5,away=3.8)

        val consensus=InternationalMarketEngine.consensus(listOf(first,second)).single()
        val p1=ProbabilityEngine.removeMargin(first.home,first.draw,first.away,MarginMethod.BASIC)
        val p2=ProbabilityEngine.removeMargin(second.home,second.draw,second.away,MarginMethod.BASIC)

        assertEquals(2,consensus.distinctBookmakerCount)
        assertEquals((p1.home+p2.home)/2,consensus.probabilities.home,1e-9)
        assertEquals((p1.draw+p2.draw)/2,consensus.probabilities.draw,1e-9)
        assertEquals((p1.away+p2.away)/2,consensus.probabilities.away,1e-9)
    }

    @Test fun separateOrientationAndKickoffAreNeverMerged() {
        val baseline=quote("a","bet365","https://moviodds.com/odds",captured=100_000)
        val reversed=quote("b","Pinnacle","https://example.org/odds",captured=101_000,homeTeam="Napoli",awayTeam="Roma")
        val later=quote("c","Pinnacle","https://example.org/odds",captured=102_000,kickoffMillis=kickoff+6*60_000L)

        val consensus=InternationalMarketEngine.consensus(listOf(baseline,reversed,later))

        assertEquals(3,consensus.size)
        assertFalse(consensus.any { it.distinctBookmakerCount>1 })
    }

    @Test fun kickoffToleranceDoesNotTransitivelyMergeDifferentEvents() {
        val atZero=quote("a","bet365","https://moviodds.com/odds",captured=100_000,kickoffMillis=kickoff)
        val atFour=quote("b","Pinnacle","https://example.org/odds",captured=200_000,kickoffMillis=kickoff+4*60_000L)
        val atEight=quote("c","William Hill","https://example.org/odds",captured=300_000,kickoffMillis=kickoff+8*60_000L)

        val groups=InternationalMarketEngine.consensus(listOf(atZero,atFour,atEight))

        assertEquals(3,groups.size)
        assertTrue(groups.all { it.distinctBookmakerCount==1 })
    }

    @Test fun ambiguousMidpointIsNotAssignedToEitherNearbyEvent() {
        val atZero=quote("a","bet365","https://moviodds.com/odds",captured=200_000,kickoffMillis=kickoff)
        val atEight=quote("b","Pinnacle","https://example.org/odds",captured=200_000,kickoffMillis=kickoff+8*60_000L)
        val midpoint=quote("c","William Hill","https://example.org/odds",captured=300_000,kickoffMillis=kickoff+4*60_000L)

        val groups=InternationalMarketEngine.consensus(listOf(atZero,atEight,midpoint))

        assertEquals(3,groups.size)
        assertTrue(groups.all { it.distinctBookmakerCount==1 })
    }

    @Test fun conflictingExactLocalMatchIdsAreNeverMerged() {
        val first=quote("a","bet365","https://moviodds.com/odds",captured=100_000,matchId=42)
        val second=quote("b","Pinnacle","https://example.org/odds",captured=200_000,matchId=84)

        val groups=InternationalMarketEngine.consensus(listOf(first,second))

        assertEquals(2,groups.size)
        assertTrue(groups.all { it.distinctBookmakerCount==1 })
    }

    @Test fun movementUsesPriorSavedSnapshotFromSameProviderEvent() {
        val previous=quote("moviodds_bet365","bet365","https://moviodds.com/odds",captured=100_000,home=2.2)
        val current=quote("moviodds_bet365","bet365","https://moviodds.com/odds",captured=200_000,home=2.0)
        val unrelated=quote("other","bet365","https://example.org/odds",captured=300_000)

        val movement=InternationalMarketEngine.latestMovement(current,listOf(current,previous,unrelated))!!

        assertEquals(-0.2,movement.homeDelta,1e-12)
        assertTrue(InternationalMarketEngine.latestMovement(previous,listOf(previous))==null)
    }

    private fun quote(
        provider:String,
        bookmaker:String,
        url:String,
        captured:Long,
        home:Double=2.15,
        draw:Double=3.2,
        away:Double=3.4,
        homeTeam:String="Roma",
        awayTeam:String="Napoli",
        kickoffMillis:Long=kickoff,
        matchId:Int?=42,
    )=InternationalOddsSnapshot(
        provider,bookmaker,"event-$homeTeam-$awayTeam-$kickoffMillis",url,"Serie A",null,captured,captured,kickoffMillis,"1X2",
        homeTeam,awayTeam,home,draw,away,captured,captured,captured,matchId,
    )
}
