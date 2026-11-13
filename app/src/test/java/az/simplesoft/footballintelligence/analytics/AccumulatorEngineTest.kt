package az.simplesoft.footballintelligence.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccumulatorEngineTest {
    private fun forecast(id: Int, kickoff: Long, home: Double) = ForecastedMatch(
        id, "Home$id", "Away$id", kickoff,
        OutcomeProbabilities(home, 0.2, 0.8 - home),
    )

    private fun history(id: Long, state: AccumulatorSettlementState, odds: Double?, captured: Long, startMatchId: Int) =
        VirtualAccumulatorHistoryEntry(
            id,"misli",2,captured,odds,JointProbabilityBounds(.1,.4),
            AccumulatorSettlement(state,if(state==AccumulatorSettlementState.WON) 2 else 1,if(state==AccumulatorSettlementState.LOST) 1 else 0,0),
            listOf(
                VirtualCouponPick(startMatchId,"Team A","Team B",100,"H",.7),
                VirtualCouponPick(startMatchId+1,"Team C","Team D",200,"A",.6),
            ),
            CouponSelectionBasis.MODEL_RANKED,
        )

    @Test fun buildsOnlySizesSupportedByAvailablePredictionsAndNeverAddsFillerLegs() {
        val coupons = VirtualCouponBuilder.buildAccumulators(
            listOf(forecast(1, 100, .7), forecast(2, 200, .6), forecast(3, 300, .5)),
            listOf("misli" to "Misli"),
        )

        assertEquals(listOf(2, 3), coupons.map { it.requestedLegCount })
        assertEquals(listOf(2, 3), coupons.map { it.picks.size })
        assertEquals(listOf(1, 2, 3), coupons.last().picks.map { it.matchId })
        assertNull(coupons.first().combinedDecimalOdds)
    }

    @Test fun selectsHighestMarginalsAndComputesFrechetBoundsWithoutAssumingIndependence() {
        val forecasts = listOf(
            forecast(1, 300, .55),
            forecast(2, 100, .70),
            forecast(3, 200, .60),
        )
        val coupon = VirtualCouponBuilder.buildAccumulators(forecasts, listOf("topaz" to "TOPAZ")).first()

        assertEquals(listOf(2, 3), coupon.picks.map { it.matchId })
        val bounds = coupon.jointProbabilityBounds!!
        assertEquals(.3, bounds.lower, 1e-12)
        assertEquals(.6, bounds.upper, 1e-12)
    }

    @Test fun combinedOddsUseOnlyCompletePricesFromTheSelectedProviderAndKeepCaptureTime() {
        val forecasts = listOf(forecast(1, 100, .7), forecast(2, 200, .6))
        val prices = mapOf(
            "misli" to mapOf(
                1 to ThreeWayDecimalOdds(2.0, 3.0, 4.0, 1_000),
                2 to ThreeWayDecimalOdds(2.5, 3.5, 4.5, 2_000),
            ),
            "topaz" to mapOf(1 to ThreeWayDecimalOdds(1.8, 3.2, 4.2, 3_000)),
        )
        val coupons = VirtualCouponBuilder.buildAccumulators(
            forecasts, listOf("misli" to "Misli", "topaz" to "TOPAZ"), prices, nowMillis = 3_000,
        )

        val misli = coupons.single { it.providerId == "misli"&&it.selectionBasis==CouponSelectionBasis.MODEL_RANKED }
        val misliPriced = coupons.single { it.providerId == "misli"&&it.selectionBasis==CouponSelectionBasis.MATCHED_FRESH_BOOKMAKER_ODDS }
        val topaz = coupons.single { it.providerId == "topaz"&&it.selectionBasis==CouponSelectionBasis.MODEL_RANKED }
        assertEquals(5.0, misli.combinedDecimalOdds!!, 0.0)
        assertEquals(5.0, misliPriced.combinedDecimalOdds!!, 0.0)
        assertEquals(listOf(1_000L, 2_000L), misli.picks.map { it.bookmakerOddsCapturedAtMillis })
        assertNull(topaz.combinedDecimalOdds)
        assertNull(topaz.picks.last().bookmakerDecimalOdds)
    }

    @Test fun returnsOnlyRequestedSizesWithEnoughLegsAcrossLargeCouponSet() {
        val forecasts = (1..10).map { forecast(it, it * 100L, .5) }
        val coupons = VirtualCouponBuilder.buildAccumulators(forecasts, listOf("misli" to "Misli"))

        assertEquals(listOf(2, 3, 5, 8, 10), coupons.map { it.requestedLegCount })
    }

    @Test fun createsSeparateSameBookmakerCouponsOnlyFromFixturesWithTimestampedPrices() {
        val forecasts=(1..4).map { forecast(it,it*100L,.65-(it*.02)) }
        val prices=mapOf(
            "topaz" to mapOf(
                1 to ThreeWayDecimalOdds(2.0,3.0,4.0,1_000),
                2 to ThreeWayDecimalOdds(2.1,3.1,4.1,2_000),
                3 to ThreeWayDecimalOdds(2.2,3.2,4.2,3_000),
                // No timestamp means the line is not accepted as an evidenced fresh price.
                4 to ThreeWayDecimalOdds(2.3,3.3,4.3),
            ),
            "misli" to mapOf(1 to ThreeWayDecimalOdds(1.9,3.2,4.5,4_000)),
        )
        val drafts=VirtualCouponBuilder.buildAccumulators(
            forecasts,listOf("misli" to "Misli","topaz" to "TOPAZ"),prices,nowMillis = 4_000,
        )
        val topazPriced=drafts.filter { it.providerId=="topaz"&&it.selectionBasis==CouponSelectionBasis.MATCHED_FRESH_BOOKMAKER_ODDS }
        val misliPriced=drafts.filter { it.providerId=="misli"&&it.selectionBasis==CouponSelectionBasis.MATCHED_FRESH_BOOKMAKER_ODDS }
        val topazModel=drafts.filter { it.providerId=="topaz"&&it.selectionBasis==CouponSelectionBasis.MODEL_RANKED }

        assertEquals(listOf(2,3),topazPriced.map { it.requestedLegCount })
        assertTrue(topazPriced.all { it.picks.all { pick -> pick.bookmakerDecimalOdds!=null&&pick.bookmakerOddsCapturedAtMillis!=null } })
        assertTrue(topazPriced.all { it.combinedDecimalOdds!=null })
        assertTrue(misliPriced.isEmpty())
        assertEquals(listOf(2,3),topazModel.map { it.requestedLegCount })
        assertEquals(CouponSelectionBasis.MODEL_PRICE_EDGE,drafts.first().selectionBasis)
    }

    @Test fun marketDisagreementDraftUsesOnlyFreshSameBookPricesAndRanksPositiveGaps() {
        val now = 10_000L
        val forecasts = listOf(
            ForecastedMatch(1,"Home1","Away1",100,OutcomeProbabilities(.70,.20,.10)),
            ForecastedMatch(2,"Home2","Away2",200,OutcomeProbabilities(.20,.60,.20)),
            ForecastedMatch(3,"Home3","Away3",300,OutcomeProbabilities(.30,.24,.46)),
        )
        val prices = mapOf("misli" to mapOf(
            1 to ThreeWayDecimalOdds(2.0,3.0,4.0,now),
            2 to ThreeWayDecimalOdds(4.0,2.0,4.0,now),
            3 to ThreeWayDecimalOdds(2.0,4.0,3.0,now),
        ))

        val candidate = VirtualCouponBuilder.buildAccumulators(
            forecasts,listOf("misli" to "Misli"),prices,nowMillis=now,
        ).single { it.selectionBasis==CouponSelectionBasis.MODEL_MARKET_DISAGREEMENT&&it.requestedLegCount==2 }

        assertEquals("misli",candidate.providerId)
        assertEquals(listOf(1,3),candidate.picks.map { it.matchId })
        assertEquals(listOf("H","A"),candidate.picks.map { it.outcome })
        assertTrue(candidate.picks.all { (it.modelMinusMarket ?: 0.0)>0.0 })
        assertTrue(candidate.picks.all { it.marketFairProbability!=null&&it.bookmakerOddsCapturedAtMillis==now })
        assertEquals(6.0,candidate.combinedDecimalOdds!!,1e-12)
    }

    @Test fun modelMarketDisagreementIsNotCreatedForZeroEdgeOrStalePrice() {
        val now=60L*60*1000
        val forecasts=listOf(
            ForecastedMatch(1,"Home1","Away1",now+1,OutcomeProbabilities(.7,.2,.1)),
            ForecastedMatch(2,"Home2","Away2",now+2,OutcomeProbabilities(.7,.2,.1)),
        )
        val exactFair=ThreeWayDecimalOdds(1.0/.7,1.0/.2,1.0/.1,now)
        val zeroDifference=VirtualCouponBuilder.buildAccumulators(
            forecasts,listOf("misli" to "Misli"),mapOf("misli" to mapOf(1 to exactFair,2 to exactFair)),nowMillis=now,
        )
        val stale=VirtualCouponBuilder.buildAccumulators(
            forecasts,listOf("misli" to "Misli"),
            mapOf("misli" to mapOf(
                1 to exactFair.copy(capturedAtMillis=now-VirtualCouponBuilder.BOOKMAKER_QUOTE_FRESHNESS_MILLIS-1),
                2 to exactFair.copy(capturedAtMillis=now-VirtualCouponBuilder.BOOKMAKER_QUOTE_FRESHNESS_MILLIS-1),
            )),
            nowMillis=now,
        )

        assertTrue(zeroDifference.none { it.selectionBasis==CouponSelectionBasis.MODEL_MARKET_DISAGREEMENT })
        assertTrue(stale.none { it.selectionBasis==CouponSelectionBasis.MODEL_MARKET_DISAGREEMENT })
    }

    @Test fun marketDisagreementNeverCombinesPricesFromDifferentLocalBooks() {
        val now = 60L * 60 * 1000
        val forecasts = listOf(
            ForecastedMatch(1,"Home1","Away1",now+1,OutcomeProbabilities(.70,.20,.10)),
            ForecastedMatch(2,"Home2","Away2",now+2,OutcomeProbabilities(.20,.60,.20)),
        )
        val splitPrices = mapOf(
            "misli" to mapOf(1 to ThreeWayDecimalOdds(2.0,3.0,4.0,now)),
            "topaz" to mapOf(2 to ThreeWayDecimalOdds(4.0,2.0,4.0,now)),
        )

        val drafts = VirtualCouponBuilder.buildAccumulators(
            forecasts,listOf("misli" to "Misli","topaz" to "TOPAZ"),splitPrices,nowMillis=now,
        )

        assertTrue(drafts.none { it.selectionBasis==CouponSelectionBasis.MODEL_MARKET_DISAGREEMENT })
    }

    @Test fun staleAndFutureDatedPricesCannotCreatePricedAccumulatorLegs() {
        val now=60L*60*1000
        val forecasts=(1..3).map { forecast(it,it*100L,.7-(it*.05)) }
        val prices=mapOf("topaz" to mapOf(
            1 to ThreeWayDecimalOdds(2.0,3.0,4.0,1_000),
            2 to ThreeWayDecimalOdds(2.1,3.1,4.1,now+1),
            3 to ThreeWayDecimalOdds(2.2,3.2,4.2,now-5*60*1000),
        ))

        val drafts=VirtualCouponBuilder.buildAccumulators(
            forecasts,listOf("topaz" to "TOPAZ"),prices,nowMillis=now,
        )
        val priced=drafts.filter { it.selectionBasis==CouponSelectionBasis.MATCHED_FRESH_BOOKMAKER_ODDS }

        assertTrue(priced.isEmpty())
        assertTrue(drafts.filter { it.selectionBasis==CouponSelectionBasis.MODEL_RANKED }
            .flatMap { it.picks }.none { it.matchId in setOf(1,2)&&it.bookmakerDecimalOdds!=null })
        assertTrue(drafts.filter { it.selectionBasis==CouponSelectionBasis.MODEL_RANKED }
            .flatMap { it.picks }.filter { it.bookmakerDecimalOdds!=null }
            .all { it.matchId==3&&it.bookmakerOddsCapturedAtMillis==now-5*60*1000 })
    }

    @Test fun placeholderFixturesNeverBecomeModelOnlyOrAccumulatorPicks() {
        val forecasts=listOf(
            ForecastedMatch(1,"Empty Stands Dummy Team","Altona 93",100,OutcomeProbabilities(.9999,.00005,.00005)),
            forecast(2,200,.7),
            forecast(3,300,.6),
        )
        val provider=listOf("misli" to "Misli")
        val single=VirtualCouponBuilder.buildModelOnly(forecasts,provider).single()
        val accumulator=VirtualCouponBuilder.buildAccumulators(forecasts,provider).single()

        assertEquals(listOf(2,3),single.picks.map { it.matchId })
        assertEquals(listOf(2,3),accumulator.picks.map { it.matchId })
    }

    @Test fun settlementDistinguishesPendingPartialWonLostAndScheduleChanged() {
        val picks = listOf(
            VirtualCouponPick(1, "A", "B", 100_000, "H", .7),
            VirtualCouponPick(2, "C", "D", 200_000, "A", .6),
        )
        val homeWin = AccumulatorMatchResult(1, 100_000, true, 2, 0)
        val awayWin = AccumulatorMatchResult(2, 200_000, true, 0, 1)

        assertEquals(AccumulatorSettlementState.PENDING, AccumulatorEngine.settle(picks, emptyMap()).state)
        assertEquals(AccumulatorSettlementState.IN_PROGRESS, AccumulatorEngine.settle(picks, mapOf(1 to homeWin)).state)
        val lostWhileLegPending = AccumulatorEngine.settle(
            picks,
            mapOf(1 to homeWin.copy(homeGoals = 0, awayGoals = 1)),
        )
        assertEquals(AccumulatorSettlementState.LOST, lostWhileLegPending.state)
        assertEquals(1, lostWhileLegPending.incorrectLegs)
        assertEquals(1, lostWhileLegPending.pendingLegs)
        assertEquals(AccumulatorSettlementState.WON, AccumulatorEngine.settle(picks, mapOf(1 to homeWin, 2 to awayWin)).state)
        assertEquals(
            AccumulatorSettlementState.LOST,
            AccumulatorEngine.settle(picks, mapOf(1 to homeWin, 2 to awayWin.copy(homeGoals = 1, awayGoals = 0))).state,
        )
        assertEquals(
            AccumulatorSettlementState.SCHEDULE_CHANGED,
            AccumulatorEngine.settle(picks, mapOf(1 to homeWin.copy(actualKickoffMillis = 3_000_000))).state,
        )
        assertEquals(
            AccumulatorSettlementState.INVALID,
            AccumulatorEngine.settle(listOf(picks.first().copy(homeTeam="Empty Stands Dummy Team"), picks.last()), emptyMap()).state,
        )
    }

    @Test fun historicalPerformanceUsesOnlyFullySettledCouponsAndFullSameBookPrices() {
        val entries=listOf(
            history(1,AccumulatorSettlementState.WON,3.0,10,1),
            history(2,AccumulatorSettlementState.LOST,4.0,20,3),
            history(3,AccumulatorSettlementState.PENDING,null,30,5),
        )
        val singles=listOf(
            SettledSinglePrediction(1,true),SettledSinglePrediction(2,true),
            SettledSinglePrediction(3,false),SettledSinglePrediction(4,true),
            SettledSinglePrediction(99,false),
        )
        val report=AccumulatorPerformanceEngine.evaluate(entries,"misli",2,CouponSelectionBasis.MODEL_RANKED,singles)

        assertEquals(2,report.settledCoupons)
        assertEquals(1,report.wonCoupons)
        assertEquals(.5,report.couponHitRate!!,1e-12)
        assertEquals(2,report.pricedSettledCoupons)
        assertEquals(0,report.settledCouponsWithoutPrice)
        assertEquals(1.0,report.settledPriceCoverage!!,1e-12)
        assertEquals(.5,report.virtualRoi!!,1e-12)
        assertEquals(2.0,report.profitFactor!!,1e-12)
        assertEquals(.25,report.maximumDrawdown!!,1e-12)
        assertEquals(4,report.matchingSinglePicks)
        assertEquals(.75,report.singlePickHitRate!!,1e-12)
        assertTrue(report.couponHitRate95Low!!<report.couponHitRate!!)
        assertTrue(report.couponHitRate95High!!>report.couponHitRate!!)
    }

    @Test fun marketDisagreementPerformanceNeverReportsReturnMetrics() {
        val entry=history(7,AccumulatorSettlementState.WON,3.0,70,11)
            .copy(selectionBasis=CouponSelectionBasis.MODEL_MARKET_DISAGREEMENT)

        val report=AccumulatorPerformanceEngine.evaluate(
            listOf(entry),"misli",2,CouponSelectionBasis.MODEL_MARKET_DISAGREEMENT,emptyList(),
        )

        assertEquals(1.0,report.settledPriceCoverage!!,0.0)
        assertNull(report.virtualRoi)
        assertNull(report.profitFactor)
        assertNull(report.maximumDrawdown)
    }

    @Test fun priceEdgePerformanceUsesOnlySettledSavedQuotesAndLabelsNoFutureGuarantee() {
        val entries = listOf(
            history(21,AccumulatorSettlementState.WON,3.0,210,21).copy(selectionBasis=CouponSelectionBasis.MODEL_PRICE_EDGE),
            history(22,AccumulatorSettlementState.LOST,4.0,220,23).copy(selectionBasis=CouponSelectionBasis.MODEL_PRICE_EDGE),
            history(23,AccumulatorSettlementState.PENDING,null,230,25).copy(selectionBasis=CouponSelectionBasis.MODEL_PRICE_EDGE),
        )

        val report = AccumulatorPerformanceEngine.evaluate(
            entries,"misli",2,CouponSelectionBasis.MODEL_PRICE_EDGE,emptyList(),
        )

        assertEquals(2,report.settledCoupons)
        assertEquals(1,report.wonCoupons)
        assertEquals(2,report.pricedSettledCoupons)
        assertEquals(.5,report.virtualRoi!!,1e-12)
        assertEquals(.25,report.maximumDrawdown!!,1e-12)
    }

    @Test fun performanceLeavesRoiUnavailableWhenASettledCouponHadNoCompletePrice() {
        val report=AccumulatorPerformanceEngine.evaluate(
            listOf(history(1,AccumulatorSettlementState.WON,null,10,1)),"misli",2,CouponSelectionBasis.MODEL_RANKED,emptyList(),
        )
        assertEquals(1,report.settledCoupons)
        assertEquals(0,report.pricedSettledCoupons)
        assertEquals(1,report.settledCouponsWithoutPrice)
        assertEquals(0.0,report.settledPriceCoverage!!,1e-12)
        assertNull(report.virtualRoi)
        assertNull(report.profitFactor)
        assertNull(report.maximumDrawdown)
    }

    @Test fun partialPriceCoverageIsReportedButCannotProduceBiasedReturnMetrics() {
        val report=AccumulatorPerformanceEngine.evaluate(
            listOf(
                history(1,AccumulatorSettlementState.WON,3.0,10,1),
                history(2,AccumulatorSettlementState.LOST,null,20,3),
            ),"misli",2,CouponSelectionBasis.MODEL_RANKED,emptyList(),
        )

        assertEquals(2,report.settledCoupons)
        assertEquals(1,report.pricedSettledCoupons)
        assertEquals(1,report.settledCouponsWithoutPrice)
        assertEquals(.5,report.settledPriceCoverage!!,1e-12)
        assertNull(report.virtualRoi)
        assertNull(report.profitFactor)
        assertNull(report.maximumDrawdown)
    }
}
