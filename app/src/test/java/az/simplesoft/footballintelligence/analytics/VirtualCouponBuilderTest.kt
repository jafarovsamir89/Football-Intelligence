package az.simplesoft.footballintelligence.analytics

import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualCouponBuilderTest {
    @Test fun createsSeparateModelOnlyCouponsWithoutPricesOrCombinedOdds() {
        val forecast = ForecastedMatch(8, "A", "B", 100L, OutcomeProbabilities(.61, .24, .15))
        val coupons = VirtualCouponBuilder.buildModelOnly(listOf(forecast), listOf("misli" to "Misli.az", "topaz" to "TOPAZ"))

        assertEquals(listOf("misli", "topaz"), coupons.map { it.providerId })
        coupons.forEach {
            assertEquals("H", it.picks.single().outcome)
            assertEquals(.61, it.picks.single().modelProbability, 1e-12)
            assertNull(it.combinedDecimalOdds)
        }
    }

    @Test fun deduplicatesMatchesAndOrdersSelectionsByKickoff() {
        val forecasts = listOf(
            ForecastedMatch(2, "Later", "Opponent", 200L, OutcomeProbabilities(.2, .3, .5)),
            ForecastedMatch(2, "Duplicate", "Ignored", 100L, OutcomeProbabilities(.8, .1, .1)),
            ForecastedMatch(1, "Earlier", "Opponent", 50L, OutcomeProbabilities(.1, .7, .2)),
        )
        val picks = VirtualCouponBuilder.buildModelOnly(forecasts, listOf("misli" to "Misli")).single().picks
        assertEquals(listOf(1, 2), picks.map { it.matchId })
        assertEquals(listOf("D", "A"), picks.map { it.outcome })
    }

    @Test fun usesOnlyEachOperatorsOwnPricesAndCombinesOnlyCompleteRealLines() {
        val forecasts=listOf(
            ForecastedMatch(1,"A","B",100L,OutcomeProbabilities(.7,.2,.1)),
            ForecastedMatch(2,"C","D",200L,OutcomeProbabilities(.1,.2,.7)),
        )
        val quotes=mapOf(
            "misli" to mapOf(1 to ThreeWayDecimalOdds(2.0,3.2,4.0,1_000),2 to ThreeWayDecimalOdds(4.0,3.0,3.0,1_000)),
            "topaz" to mapOf(1 to ThreeWayDecimalOdds(1.8,3.5,4.2,1_000)),
        )
        val coupons=VirtualCouponBuilder.buildModelOnly(forecasts,listOf("misli" to "Misli","topaz" to "TOPAZ"),quotes,nowMillis=2_000)
        val misli=coupons.first { it.providerId=="misli" }
        val topaz=coupons.first { it.providerId=="topaz" }

        assertEquals(2.0,misli.picks[0].bookmakerDecimalOdds!!,0.0)
        assertEquals(3.0,misli.picks[1].bookmakerDecimalOdds!!,0.0)
        assertEquals(6.0,misli.combinedDecimalOdds!!,0.0)
        assertEquals(1.8,topaz.picks[0].bookmakerDecimalOdds!!,0.0)
        assertNull(topaz.picks[1].bookmakerDecimalOdds)
        assertNull(topaz.combinedDecimalOdds)
    }
}
