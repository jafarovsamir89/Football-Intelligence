package az.simplesoft.footballintelligence.analytics

data class AccumulatorMatchResult(
    val matchId: Int,
    val actualKickoffMillis: Long,
    val finished: Boolean,
    val homeGoals: Int?,
    val awayGoals: Int?,
)

enum class AccumulatorSettlementState { PENDING, IN_PROGRESS, WON, LOST, SCHEDULE_CHANGED, INVALID }

data class AccumulatorSettlement(
    val state: AccumulatorSettlementState,
    val correctLegs: Int,
    val incorrectLegs: Int,
    val pendingLegs: Int,
) {
    val settledLegs: Int get() = correctLegs + incorrectLegs
}

data class VirtualAccumulatorHistoryEntry(
    val id: Long,
    val providerId: String,
    val legCount: Int,
    val capturedAtMillis: Long,
    val combinedDecimalOdds: Double?,
    val jointProbabilityBounds: JointProbabilityBounds,
    val settlement: AccumulatorSettlement,
    val picks: List<VirtualCouponPick>,
    val selectionBasis: CouponSelectionBasis,
)

data class SettledSinglePrediction(val matchId: Int, val correct: Boolean)

data class AccumulatorPerformanceReport(
    val settledCoupons: Int,
    val wonCoupons: Int,
    val lostCoupons: Int,
    val couponHitRate: Double?,
    val couponHitRate95Low: Double?,
    val couponHitRate95High: Double?,
    val pricedSettledCoupons: Int,
    val settledCouponsWithoutPrice: Int,
    val settledPriceCoverage: Double?,
    val virtualRoi: Double?,
    val profitFactor: Double?,
    val maximumDrawdown: Double?,
    val matchingSinglePicks: Int,
    val singlePickHitRate: Double?,
)

object AccumulatorEngine {
    const val SCHEDULE_CHANGE_TOLERANCE_MILLIS = 30L * 60L * 1000L

    /** Checks a saved virtual coupon against current local results without treating postponements as losses. */
    fun settle(
        picks: List<VirtualCouponPick>,
        resultsByMatchId: Map<Int, AccumulatorMatchResult>,
    ): AccumulatorSettlement {
        if (picks.isEmpty() || picks.map { it.matchId }.distinct().size != picks.size ||
            picks.any { !TeamDataQuality.isValidFixture(it.homeTeam, it.awayTeam) }
        ) {
            return AccumulatorSettlement(AccumulatorSettlementState.INVALID, 0, 0, picks.size)
        }
        if (picks.any { pick ->
                val result = resultsByMatchId[pick.matchId]
                result != null && kotlin.math.abs(result.actualKickoffMillis - pick.kickoffMillis) > SCHEDULE_CHANGE_TOLERANCE_MILLIS
            }) {
            return AccumulatorSettlement(AccumulatorSettlementState.SCHEDULE_CHANGED, 0, 0, picks.size)
        }

        var correct = 0
        var incorrect = 0
        var pending = 0
        picks.forEach { pick ->
            val result = resultsByMatchId[pick.matchId]
            if (result == null || !result.finished || result.homeGoals == null || result.awayGoals == null) {
                pending++
            } else {
                val actual = when {
                    result.homeGoals > result.awayGoals -> "H"
                    result.homeGoals < result.awayGoals -> "A"
                    else -> "D"
                }
                if (actual == pick.outcome) correct++ else incorrect++
            }
        }
        val state = when {
            pending == picks.size -> AccumulatorSettlementState.PENDING
            incorrect > 0 -> AccumulatorSettlementState.LOST
            pending > 0 -> AccumulatorSettlementState.IN_PROGRESS
            incorrect == 0 -> AccumulatorSettlementState.WON
            else -> AccumulatorSettlementState.LOST
        }
        return AccumulatorSettlement(state, correct, incorrect, pending)
    }
}

object AccumulatorPerformanceEngine {
    private const val WILSON_Z = 1.96

    /** Reports only settled saved coupons; return metrics require complete price coverage for that cohort. */
    fun evaluate(
        entries: List<VirtualAccumulatorHistoryEntry>,
        providerId: String,
        legCount: Int,
        selectionBasis: CouponSelectionBasis,
        settledSingles: List<SettledSinglePrediction>,
    ): AccumulatorPerformanceReport {
        val matching = entries.filter { it.providerId == providerId && it.legCount == legCount && it.selectionBasis==selectionBasis }
        val settled = matching.filter {
            it.settlement.state == AccumulatorSettlementState.WON || it.settlement.state == AccumulatorSettlementState.LOST
        }.sortedBy { it.capturedAtMillis }
        val won = settled.count { it.settlement.state == AccumulatorSettlementState.WON }
        val lost = settled.size - won
        val hitRate = (won.toDouble() / settled.size).takeIf { settled.isNotEmpty() }
        val (low, high) = hitRate?.let { wilsonInterval(won, settled.size) } ?: (null to null)
        val priced = settled.filter { it.combinedDecimalOdds?.let { odds -> odds.isFinite() && odds > 1.0 } == true }
        val missingPriceCount = settled.size - priced.size
        val priceCoverage = priced.size.toDouble().div(settled.size).takeIf { settled.isNotEmpty() }
        val completePriceCoverage = settled.isNotEmpty() && priced.size == settled.size
        val roi = if (!completePriceCoverage) null else {
            (priced.sumOf { if (it.settlement.state == AccumulatorSettlementState.WON) it.combinedDecimalOdds!! else 0.0 } / priced.size) - 1.0
        }
        val grossProfit = priced.filter { it.settlement.state == AccumulatorSettlementState.WON }
            .sumOf { (it.combinedDecimalOdds ?: 1.0) - 1.0 }
        val grossLoss = priced.count { it.settlement.state == AccumulatorSettlementState.LOST }.toDouble()
        val profitFactor = (grossProfit / grossLoss).takeIf { completePriceCoverage && grossLoss > 0.0 && it.isFinite() }
        val drawdown = if (completePriceCoverage) maximumDrawdown(priced) else null
        val relevantMatchIds = settled.flatMap { it.picks }.map { it.matchId }.toSet()
        val singleRows = settledSingles.distinctBy { it.matchId }.filter { it.matchId in relevantMatchIds }
        val singleHitRate = (singleRows.count { it.correct }.toDouble() / singleRows.size).takeIf { singleRows.isNotEmpty() }
        return AccumulatorPerformanceReport(
            settled.size, won, lost, hitRate, low, high, priced.size, missingPriceCount, priceCoverage,
            roi, profitFactor, drawdown,
            singleRows.size, singleHitRate,
        )
    }

    private fun wilsonInterval(successes: Int, total: Int): Pair<Double, Double> {
        val p = successes.toDouble() / total
        val z2 = WILSON_Z * WILSON_Z
        val denominator = 1.0 + z2 / total
        val center = (p + z2 / (2.0 * total)) / denominator
        val margin = WILSON_Z * kotlin.math.sqrt((p * (1.0 - p) / total) + (z2 / (4.0 * total * total))) / denominator
        return (center - margin).coerceAtLeast(0.0) to (center + margin).coerceAtMost(1.0)
    }

    private fun maximumDrawdown(priced: List<VirtualAccumulatorHistoryEntry>): Double {
        var balance = priced.size.toDouble()
        var peak = balance
        var maximum = 0.0
        priced.forEach { entry ->
            balance += if (entry.settlement.state == AccumulatorSettlementState.WON) (entry.combinedDecimalOdds ?: 1.0) - 1.0 else -1.0
            peak = maxOf(peak, balance)
            if (peak > 0.0) maximum = maxOf(maximum, (peak - balance) / peak)
        }
        return maximum
    }
}
