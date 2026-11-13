package az.simplesoft.footballintelligence.analytics

import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun priceEdgeRejectsPositiveDevigGapWhenModelIsBelowActualPriceBreakeven() {
        val now = 10_000L
        val forecasts = listOf(
            ForecastedMatch(1,"ValueHome","ValueAway",now+1,OutcomeProbabilities(.70,.10,.20)),
            ForecastedMatch(2,"ThinHome","ThinAway",now+2,OutcomeProbabilities(.515,.28,.205)),
        )
        val prices = mapOf("misli" to mapOf(
            1 to ThreeWayDecimalOdds(2.0,4.0,4.0,now),
            // Model home is above the de-vigged probability (~51.1%), but below 1/1.90 (52.6%).
            2 to ThreeWayDecimalOdds(1.90,3.55,4.50,now),
        ))

        val drafts = VirtualCouponBuilder.buildAccumulators(
            forecasts,listOf("misli" to "Misli"),prices,nowMillis=now,
        )

        assertTrue(drafts.any { it.selectionBasis==CouponSelectionBasis.MODEL_MARKET_DISAGREEMENT&&it.requestedLegCount==2 })
        assertTrue(drafts.none { it.selectionBasis==CouponSelectionBasis.MODEL_PRICE_EDGE&&it.requestedLegCount==2 })
    }

    @Test fun priceEdgeUsesFreshBookSpecificLinesAndExposesBreakEvenGap() {
        val now = 10_000L
        val forecasts = listOf(
            ForecastedMatch(1,"Home1","Away1",now+1,OutcomeProbabilities(.60,.24,.16)),
            ForecastedMatch(2,"Home2","Away2",now+2,OutcomeProbabilities(.58,.25,.17)),
            ForecastedMatch(3,"Home3","Away3",now+3,OutcomeProbabilities(.52,.26,.22)),
            ForecastedMatch(4,"Home4","Away4",now+4,OutcomeProbabilities(.90,.06,.04)),
        )
        val prices = mapOf("topaz" to mapOf(
            1 to ThreeWayDecimalOdds(1.75,4.0,4.2,now),
            2 to ThreeWayDecimalOdds(1.85,3.8,4.5,now),
            3 to ThreeWayDecimalOdds(2.0,3.8,4.0,now),
            4 to ThreeWayDecimalOdds(4.0,5.0,7.0,now-VirtualCouponBuilder.BOOKMAKER_QUOTE_FRESHNESS_MILLIS-1),
        ))

        val candidate = VirtualCouponBuilder.buildAccumulators(
            forecasts,listOf("misli" to "Misli","topaz" to "TOPAZ"),prices,nowMillis=now,
        ).single { it.providerId=="topaz"&&it.selectionBasis==CouponSelectionBasis.MODEL_PRICE_EDGE&&it.requestedLegCount==2 }

        assertEquals(listOf(1,2),candidate.picks.map { it.matchId })
        assertTrue(candidate.picks.all { (it.modelMinusBreakEvenProbability ?: 0.0)>0.0 })
        assertEquals(1.0/1.75,candidate.picks.first().breakEvenProbability!!,1e-12)
        assertEquals(.60-1.0/1.75,candidate.picks.first().modelMinusBreakEvenProbability!!,1e-12)
        assertEquals(1.75*1.85,candidate.combinedDecimalOdds!!,1e-12)
        assertTrue(VirtualCouponBuilder.buildAccumulators(
            forecasts,listOf("misli" to "Misli"),prices,nowMillis=now,
        ).none { it.selectionBasis==CouponSelectionBasis.MODEL_PRICE_EDGE })
    }
}
