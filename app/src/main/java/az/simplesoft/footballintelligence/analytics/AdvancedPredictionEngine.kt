package az.simplesoft.footballintelligence.analytics

/** Version of the model that passed the 2022–2024 validation / 2024–2026 holdout experiment. */
const val CURRENT_ADVANCED_MODEL_VERSION = "poisson-goals-decay365-1x2-v1"
const val CURRENT_ADVANCED_FEATURES_VERSION = "same-league-goals-attack-defense-decay365-v1"

data class AdvancedPrediction(
    val probabilities: OutcomeProbabilities?,
    /** Null when neither the primary model nor the Elo fallback has enough history. */
    val modelVersion: String?,
    val modelName: String,
    val historyMatchCount: Int,
    val homeTeamMatchCount: Int,
    val awayTeamMatchCount: Int,
    val homeExpectedGoals: Double? = null,
    val awayExpectedGoals: Double? = null,
    val fallbackReason: String? = null,
)

/** Uses the validation-selected Poisson model, with the prior production Elo as the coverage fallback. */
object AdvancedPredictionEngine {
    const val HALF_LIFE_DAYS = 365.0
    const val MINIMUM_LEAGUE_MATCHES = 50
    const val MINIMUM_MATCHES_PER_TEAM = 5

    const val HISTORICAL_EVIDENCE_SUMMARY = "Отдельный хронологический holdout: 3 448 матчей пяти лиг; Poisson Brier 0,5906 / Log Loss 0,9909, Elo 0,5954 / 1,0645. Это ретроспективный снимок источника, не гарантия будущей точности или прибыли."

    private val primaryConfig = GoalExpectationConfig(
        minimumLeagueMatches = MINIMUM_LEAGUE_MATCHES,
        minimumMatchesPerTeam = MINIMUM_MATCHES_PER_TEAM,
        timeDecayHalfLifeDays = HALF_LIFE_DAYS,
    )

    fun forecast(
        history: Collection<HistoricalMatch>,
        forecastCutoffMillis: Long,
        homeTeam: String,
        awayTeam: String,
    ): AdvancedPrediction {
        require(homeTeam.isNotBlank() && awayTeam.isNotBlank())
        require(teamKey(homeTeam) != teamKey(awayTeam))

        val asOfHistory = historyAvailableAt(history, forecastCutoffMillis)
            .filter { TeamDataQuality.isValidFixture(it.homeTeam, it.awayTeam) }
        val primary = GoalExpectationEngine.forecast(
            history = asOfHistory,
            forecastCutoffMillis = forecastCutoffMillis,
            homeTeam = homeTeam,
            awayTeam = awayTeam,
            config = primaryConfig,
        )
        if (primary != null) {
            return AdvancedPrediction(
                probabilities = primary.probabilities,
                modelVersion = CURRENT_ADVANCED_MODEL_VERSION,
                modelName = "Poisson · затухание 365 дней",
                historyMatchCount = primary.historyMatchCount,
                homeTeamMatchCount = primary.homeTeamMatches,
                awayTeamMatchCount = primary.awayTeamMatches,
                homeExpectedGoals = primary.homeExpectedGoals,
                awayExpectedGoals = primary.awayExpectedGoals,
            )
        }

        val elo = EloEngine.forecast(
            history = asOfHistory,
            forecastKickoffMillis = forecastCutoffMillis,
            homeTeam = homeTeam,
            awayTeam = awayTeam,
        )
        val primaryReason = primaryCoverageReason(asOfHistory, homeTeam, awayTeam)
        return AdvancedPrediction(
            probabilities = elo.probabilities,
            modelVersion = elo.probabilities?.let { CURRENT_ELO_MODEL_VERSION },
            modelName = if (elo.probabilities != null) "Elo · резервная модель" else "Недостаточно истории",
            historyMatchCount = elo.matchesUsed,
            homeTeamMatchCount = elo.homeTeamMatches,
            awayTeamMatchCount = elo.awayTeamMatches,
            fallbackReason = if (elo.probabilities != null) primaryReason else "$primaryReason Для Elo также нужны минимум ${EloEngine.MIN_HISTORY_MATCHES_FOR_FORECAST} результатов лиги и ${EloEngine.MIN_TEAM_MATCHES_FOR_FORECAST} матча каждой команды.",
        )
    }

    fun displayName(modelVersion: String): String = when (modelVersion) {
        CURRENT_ADVANCED_MODEL_VERSION -> "Poisson · затухание 365 дней"
        CURRENT_ELO_MODEL_VERSION -> "Elo · резервная модель"
        else -> modelVersion
    }

    private fun primaryCoverageReason(
        history: Collection<HistoricalMatch>,
        homeTeam: String,
        awayTeam: String,
    ): String {
        val homeMatches = history.count { teamKey(it.homeTeam) == teamKey(homeTeam) || teamKey(it.awayTeam) == teamKey(homeTeam) }
        val awayMatches = history.count { teamKey(it.homeTeam) == teamKey(awayTeam) || teamKey(it.awayTeam) == teamKey(awayTeam) }
        val missing = buildList {
            if (history.size < primaryConfig.minimumLeagueMatches) add("лига ${history.size}/${primaryConfig.minimumLeagueMatches}")
            if (homeMatches < primaryConfig.minimumMatchesPerTeam) add("хозяева ${homeMatches}/${primaryConfig.minimumMatchesPerTeam}")
            if (awayMatches < primaryConfig.minimumMatchesPerTeam) add("гости ${awayMatches}/${primaryConfig.minimumMatchesPerTeam}")
        }
        return "Poisson недоступен: недостаточно предматчевой истории (${missing.joinToString()})."
    }
}
