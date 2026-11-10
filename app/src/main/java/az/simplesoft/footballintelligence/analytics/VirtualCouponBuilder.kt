package az.simplesoft.footballintelligence.analytics

data class ForecastedMatch(
    val matchId: Int,
    val homeTeam: String,
    val awayTeam: String,
    val kickoffMillis: Long,
    val probabilities: OutcomeProbabilities,
)

data class VirtualCouponPick(
    val matchId: Int,
    val homeTeam: String,
    val awayTeam: String,
    val kickoffMillis: Long,
    val outcome: String,
    val modelProbability: Double,
    val bookmakerDecimalOdds: Double? = null,
)

data class ThreeWayDecimalOdds(
    val home: Double,
    val draw: Double,
    val away: Double,
)

data class VirtualCouponCandidate(
    val providerId: String,
    val providerName: String,
    val picks: List<VirtualCouponPick>,
    /** Null unless source odds exist; probability is not a bookmaker price or payout. */
    val combinedDecimalOdds: Double? = null,
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
    /** Builds separate model-only drafts. It deliberately does not multiply probabilities or invent odds. */
    fun buildModelOnly(
        forecasts: List<ForecastedMatch>,
        providers: List<Pair<String, String>>,
        oddsByProvider: Map<String, Map<Int, ThreeWayDecimalOdds>> = emptyMap(),
    ): List<VirtualCouponCandidate> {
        val ordered = forecasts.distinctBy { it.matchId }.sortedBy { it.kickoffMillis }
        return providers.map { (providerId, providerName) ->
            val providerOdds=oddsByProvider[providerId].orEmpty()
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
                )
            }
            val combined=if(picks.isNotEmpty()&&picks.all { it.bookmakerDecimalOdds!=null })
                picks.fold(1.0) { value,pick -> value*(pick.bookmakerDecimalOdds ?: 1.0) }
            else null
            VirtualCouponCandidate(providerId, providerName, picks, combined)
        }
    }
}
