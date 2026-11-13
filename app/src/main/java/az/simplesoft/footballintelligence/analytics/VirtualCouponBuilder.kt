package az.simplesoft.footballintelligence.analytics

data class ForecastedMatch(
    val matchId: Int,
    val homeTeam: String,
    val awayTeam: String,
    val kickoffMillis: Long,
    val probabilities: OutcomeProbabilities,
    val forecastedAtMillis: Long = 0L,
    val historyMatchCount: Int = 0,
    val modelVersion: String = CURRENT_PRODUCTION_MODEL_VERSION,
    val competition: String = "",
    val frequencyBaselineVersion: String? = null,
    val frequencyBaselineProbabilities: OutcomeProbabilities? = null,
)

data class VirtualCouponPick(
    val matchId: Int,
    val homeTeam: String,
    val awayTeam: String,
    val kickoffMillis: Long,
    val outcome: String,
    val modelProbability: Double,
    val bookmakerDecimalOdds: Double? = null,
    val bookmakerOddsCapturedAtMillis: Long? = null,
    /** De-vigged probability from the same operator's full 1X2 line, for research drafts only. */
    val marketFairProbability: Double? = null,
    /** Signed model probability minus same-book de-vigged probability. Not expected value. */
    val modelMinusMarket: Double? = null,
) {
    /** Raw decimal-price break-even probability (1 / odds); null without an evidenced price. */
    val breakEvenProbability: Double?
        get() = bookmakerDecimalOdds?.takeIf { it.isFinite() && it > 1.0 }?.let { 1.0 / it }

    /** Model probability minus the raw price break-even probability; not a validated edge. */
    val modelMinusBreakEvenProbability: Double?
        get() = breakEvenProbability?.let { modelProbability - it }
}

data class ThreeWayDecimalOdds(
    val home: Double,
    val draw: Double,
    val away: Double,
    val capturedAtMillis: Long? = null,
)

data class JointProbabilityBounds(val lower: Double, val upper: Double)

enum class CouponSelectionBasis {
    MODEL_RANKED,
    MATCHED_FRESH_BOOKMAKER_ODDS,
    MODEL_MARKET_DISAGREEMENT,
    MODEL_PRICE_EDGE;

    val strategyVersion: String
        get() = when (this) {
            MODEL_RANKED -> "model-max-1x2-frechet-v2"
            MATCHED_FRESH_BOOKMAKER_ODDS -> "model-priced-max-1x2-frechet-v2"
            MODEL_MARKET_DISAGREEMENT -> "model-vs-local-devig-positive-delta-frechet-v1"
            MODEL_PRICE_EDGE -> "model-vs-raw-price-breakeven-positive-gap-frechet-v1"
        }
}

data class VirtualCouponCandidate(
    val providerId: String,
    val providerName: String,
    val picks: List<VirtualCouponPick>,
    /** Null unless source odds exist; probability is not a bookmaker price or payout. */
    val combinedDecimalOdds: Double? = null,
    val requestedLegCount: Int = picks.size,
    /** Fréchet bounds from marginal probabilities; no independence assumption is made. */
    val jointProbabilityBounds: JointProbabilityBounds? = null,
    val selectionBasis: CouponSelectionBasis = CouponSelectionBasis.MODEL_RANKED,
)

data class LoggedCouponPick(
    val providerId: String,
    val matchId: Int,
    val homeTeam: String,
    val awayTeam: String,
    val kickoffMillis: Long,
    val outcome: String,
    val modelProbability: Double,
    val capturedAtMillis: Long,
    val finished: Boolean,
    val homeGoals: Int?,
    val awayGoals: Int?,
)

object VirtualCouponBuilder {
    val supportedAccumulatorSizes = listOf(2, 3, 5, 8, 10)
    const val BOOKMAKER_QUOTE_FRESHNESS_MILLIS = 30L * 60L * 1000L

    /** Builds separate model-only drafts. It deliberately does not multiply probabilities or invent odds. */
    fun buildModelOnly(
        forecasts: List<ForecastedMatch>,
        providers: List<Pair<String, String>>,
        oddsByProvider: Map<String, Map<Int, ThreeWayDecimalOdds>> = emptyMap(),
        nowMillis: Long = System.currentTimeMillis(),
    ): List<VirtualCouponCandidate> {
        val ordered = forecasts.distinctBy { it.matchId }
            .filter { TeamDataQuality.isValidFixture(it.homeTeam, it.awayTeam) }
            .sortedBy { it.kickoffMillis }
        return providers.map { (providerId, providerName) ->
            val providerOdds=oddsByProvider[providerId].orEmpty().filterValues { it.isFreshValidThreeWay(nowMillis) }
            val picks = ordered.map { forecast ->
                val (outcome, probability) = listOf(
                    "H" to forecast.probabilities.home,
                    "D" to forecast.probabilities.draw,
                    "A" to forecast.probabilities.away,
                ).maxBy { it.second }
                val prices=providerOdds[forecast.matchId]
                val decimalOdds=when(outcome) { "H"->prices?.home;"D"->prices?.draw;else->prices?.away }
                VirtualCouponPick(
                    forecast.matchId,
                    forecast.homeTeam,
                    forecast.awayTeam,
                    forecast.kickoffMillis,
                    outcome,
                    probability,
                    decimalOdds,
                    prices?.capturedAtMillis,
                )
            }
            val combined=if(picks.isNotEmpty()&&picks.all { it.bookmakerDecimalOdds!=null })
                picks.fold(1.0) { value,pick -> value*(pick.bookmakerDecimalOdds ?: 1.0) }
            else null
            VirtualCouponCandidate(providerId, providerName, picks, combined)
        }
    }

    /**
     * Builds only supported accumulator sizes for which there are enough eligible fixtures.
     * The strongest available model marginals are selected first; no filler legs are added.
     */
    fun buildAccumulators(
        forecasts: List<ForecastedMatch>,
        providers: List<Pair<String, String>>,
        oddsByProvider: Map<String, Map<Int, ThreeWayDecimalOdds>> = emptyMap(),
        nowMillis: Long = System.currentTimeMillis(),
    ): List<VirtualCouponCandidate> {
        val eligible = forecasts.distinctBy { it.matchId }
            .filter { TeamDataQuality.isValidFixture(it.homeTeam, it.awayTeam) }
        return providers.flatMap { (providerId, providerName) ->
            val providerOdds = oddsByProvider[providerId].orEmpty().filterValues { it.isFreshValidThreeWay(nowMillis) }
            val modelPool = eligible.map { forecast -> forecast.toPick(providerOdds[forecast.matchId]) }
            val pricedPool = eligible.filter { forecast -> providerOdds[forecast.matchId] != null }
                .map { forecast -> forecast.toPick(providerOdds[forecast.matchId]) }
            val disagreementPool = eligible.mapNotNull { forecast ->
                forecast.toMarketDisagreementPick(providerOdds[forecast.matchId])
            }
            val priceEdgePool = eligible.mapNotNull { forecast ->
                forecast.toModelPriceEdgePick(providerOdds[forecast.matchId])
            }
            buildSizedDrafts(providerId,providerName,pricedPool,CouponSelectionBasis.MATCHED_FRESH_BOOKMAKER_ODDS) +
                buildSizedDrafts(providerId,providerName,priceEdgePool,CouponSelectionBasis.MODEL_PRICE_EDGE) +
                buildSizedDrafts(providerId,providerName,disagreementPool,CouponSelectionBasis.MODEL_MARKET_DISAGREEMENT) +
                buildSizedDrafts(providerId,providerName,modelPool,CouponSelectionBasis.MODEL_RANKED)
        }
            .sortedBy {
                when (it.selectionBasis) {
                    CouponSelectionBasis.MODEL_PRICE_EDGE -> 0
                    CouponSelectionBasis.MATCHED_FRESH_BOOKMAKER_ODDS -> 1
                    CouponSelectionBasis.MODEL_MARKET_DISAGREEMENT -> 2
                    CouponSelectionBasis.MODEL_RANKED -> 3
                }
            }
    }

    private fun buildSizedDrafts(
        providerId:String,
        providerName:String,
        pool:List<VirtualCouponPick>,
        selectionBasis:CouponSelectionBasis,
    ):List<VirtualCouponCandidate> {
        val ordered=pool.sortedWith(
            compareByDescending<VirtualCouponPick> {
                when (selectionBasis) {
                    CouponSelectionBasis.MODEL_MARKET_DISAGREEMENT -> it.modelMinusMarket ?: Double.NEGATIVE_INFINITY
                    CouponSelectionBasis.MODEL_PRICE_EDGE -> it.modelMinusBreakEvenProbability ?: Double.NEGATIVE_INFINITY
                    else -> it.modelProbability
                }
            }.thenByDescending { it.modelProbability }.thenBy { it.kickoffMillis },
        )
        return supportedAccumulatorSizes.mapNotNull { size ->
            if(ordered.size<size) return@mapNotNull null
            val selected=ordered.take(size).sortedBy { it.kickoffMillis }
            val combinedOdds=if(selected.all { it.bookmakerDecimalOdds?.let { odds -> odds.isFinite()&&odds>1.0 }==true })
                selected.fold(1.0) { total,pick -> total*(pick.bookmakerDecimalOdds ?: 1.0) }.takeIf { it.isFinite() }
            else null
            val probabilities=selected.map { it.modelProbability }
            val bounds=JointProbabilityBounds(
                lower=maxOf(0.0,probabilities.sum()-(selected.size-1)),
                upper=probabilities.min(),
            )
            VirtualCouponCandidate(providerId,providerName,selected,combinedOdds,size,bounds,selectionBasis)
        }
    }

    private fun ForecastedMatch.toPick(prices:ThreeWayDecimalOdds?):VirtualCouponPick {
        val (outcome,probability)=listOf("H" to probabilities.home,"D" to probabilities.draw,"A" to probabilities.away).maxBy { it.second }
        val odds=when(outcome) { "H"->prices?.home;"D"->prices?.draw;else->prices?.away }
        return VirtualCouponPick(matchId,homeTeam,awayTeam,kickoffMillis,outcome,probability,odds,prices?.capturedAtMillis)
    }

    private fun ForecastedMatch.toMarketDisagreementPick(prices: ThreeWayDecimalOdds?): VirtualCouponPick? {
        if (prices == null) return null
        val market = ProbabilityEngine.removeMargin(prices.home, prices.draw, prices.away, MarginMethod.BASIC)
        val choice = listOf(
            Triple("H", probabilities.home, market.home),
            Triple("D", probabilities.draw, market.draw),
            Triple("A", probabilities.away, market.away),
        ).maxByOrNull { it.second - it.third } ?: return null
        val difference = choice.second - choice.third
        if (difference <= ProbabilityEngine.MIN_POSITIVE_MODEL_MARKET_GAP) return null
        val odds = when (choice.first) {
            "H" -> prices.home
            "D" -> prices.draw
            else -> prices.away
        }
        return VirtualCouponPick(
            matchId = matchId,
            homeTeam = homeTeam,
            awayTeam = awayTeam,
            kickoffMillis = kickoffMillis,
            outcome = choice.first,
            modelProbability = choice.second,
            bookmakerDecimalOdds = odds,
            bookmakerOddsCapturedAtMillis = prices.capturedAtMillis,
            marketFairProbability = choice.third,
            modelMinusMarket = difference,
        )
    }

    /** Requires both a fresh complete same-book line and model probability above raw price break-even. */
    private fun ForecastedMatch.toModelPriceEdgePick(prices: ThreeWayDecimalOdds?): VirtualCouponPick? {
        if (prices == null) return null
        val market = ProbabilityEngine.removeMargin(prices.home, prices.draw, prices.away, MarginMethod.BASIC)
        val choices = listOf(
            Triple("H", probabilities.home, prices.home to market.home),
            Triple("D", probabilities.draw, prices.draw to market.draw),
            Triple("A", probabilities.away, prices.away to market.away),
        )
        val choice = choices.maxByOrNull { (_, probability, priceAndFair) ->
            probability - (1.0 / priceAndFair.first)
        } ?: return null
        val (outcome, probability, priceAndFair) = choice
        val odds = priceAndFair.first
        val modelMinusBreakEven = probability - 1.0 / odds
        if (modelMinusBreakEven <= ProbabilityEngine.MIN_POSITIVE_MODEL_MARKET_GAP) return null
        val modelMinusMarket = probability - priceAndFair.second
        return VirtualCouponPick(
            matchId = matchId,
            homeTeam = homeTeam,
            awayTeam = awayTeam,
            kickoffMillis = kickoffMillis,
            outcome = outcome,
            modelProbability = probability,
            bookmakerDecimalOdds = odds,
            bookmakerOddsCapturedAtMillis = prices.capturedAtMillis,
            marketFairProbability = priceAndFair.second,
            modelMinusMarket = modelMinusMarket,
        )
    }

    private fun ThreeWayDecimalOdds.isFreshValidThreeWay(nowMillis:Long):Boolean = capturedAtMillis!=null&&
        isFreshQuote(capturedAtMillis,nowMillis,BOOKMAKER_QUOTE_FRESHNESS_MILLIS)&&
        listOf(home,draw,away).all { it.isFinite()&&it>1.0 }
}
