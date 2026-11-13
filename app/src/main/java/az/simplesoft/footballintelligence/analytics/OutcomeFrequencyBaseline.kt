package az.simplesoft.footballintelligence.analytics

/** Stable identifier for the saved, same-competition historical outcome-frequency reference. */
const val OUTCOME_FREQUENCY_BASELINE_VERSION = "same-competition-frequency-dirichlet-1-v1"

/** Symmetric Dirichlet(1,1,1) baseline shared by retrospective and prospective evaluation. */
object OutcomeFrequencyBaseline {
    const val PSEUDOCOUNT_PER_OUTCOME = 1.0

    fun fromCounts(homeWins: Int, draws: Int, awayWins: Int): OutcomeProbabilities {
        require(homeWins >= 0 && draws >= 0 && awayWins >= 0)
        val denominator = homeWins + draws + awayWins + 3.0 * PSEUDOCOUNT_PER_OUTCOME
        return OutcomeProbabilities(
            (homeWins + PSEUDOCOUNT_PER_OUTCOME) / denominator,
            (draws + PSEUDOCOUNT_PER_OUTCOME) / denominator,
            (awayWins + PSEUDOCOUNT_PER_OUTCOME) / denominator,
        )
    }

    /** The caller supplies only the results known to belong to the baseline's as-of cohort. */
    fun fromHistory(history: Collection<HistoricalMatch>): OutcomeProbabilities {
        var homeWins = 0
        var draws = 0
        var awayWins = 0
        for (match in history) {
            if (!TeamDataQuality.isValidFixture(match.homeTeam, match.awayTeam)) continue
            if (match.homeGoals < 0 || match.awayGoals < 0) continue
            when {
                match.homeGoals > match.awayGoals -> homeWins++
                match.homeGoals == match.awayGoals -> draws++
                else -> awayWins++
            }
        }
        return fromCounts(homeWins, draws, awayWins)
    }
}
