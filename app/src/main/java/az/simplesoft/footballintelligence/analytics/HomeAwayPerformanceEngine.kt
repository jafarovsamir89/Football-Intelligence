package az.simplesoft.footballintelligence.analytics

data class HomeAwayPerformanceConfig(
    val smoothingMatches: Double = 8.0,
    val minimumLeagueMatches: Int = 50,
    val minimumMatchesPerSplit: Int = 5,
    val maxGoals: Int = 10,
) {
    init {
        require(smoothingMatches.isFinite() && smoothingMatches > 0.0)
        require(minimumLeagueMatches >= 0)
        require(minimumMatchesPerSplit >= 0)
        require(maxGoals in 4..20)
    }
}

data class HomeAwayPerformancePrediction(
    val probabilities: OutcomeProbabilities,
    val homeExpectedGoals: Double,
    val awayExpectedGoals: Double,
    val historyMatchCount: Int,
    val homeTeamHomeMatches: Int,
    val awayTeamAwayMatches: Int,
)

data class HomeAwayPerformanceWalkForwardPrediction(
    val targetId: String,
    val forecastCutoffMillis: Long,
    val probabilities: OutcomeProbabilities,
    val homeExpectedGoals: Double,
    val awayExpectedGoals: Double,
    val historyMatchCount: Int,
    val brierScore: Double,
    val logLoss: Double,
)

data class HomeAwayPerformanceWalkForwardReport(
    val targetCount: Int,
    val forecastCount: Int,
    val skippedForInsufficientHistory: Int,
    val meanBrierScore: Double?,
    val meanLogLoss: Double?,
    val predictions: List<HomeAwayPerformanceWalkForwardPrediction>,
)

internal data class TeamVenueGoals(
    var homeMatches: Int = 0,
    var homeGoalsFor: Long = 0L,
    var homeGoalsAgainst: Long = 0L,
    var awayMatches: Int = 0,
    var awayGoalsFor: Long = 0L,
    var awayGoalsAgainst: Long = 0L,
)

internal data class HomeAwayState(
    var matches: Int = 0,
    var homeGoals: Long = 0L,
    var awayGoals: Long = 0L,
    val teams: MutableMap<String, TeamVenueGoals> = mutableMapOf(),
) {
    fun add(match: HistoricalMatch) {
        GoalExpectationEngine.validate(match)
        val home = teams.getOrPut(teamKey(match.homeTeam)) { TeamVenueGoals() }
        val away = teams.getOrPut(teamKey(match.awayTeam)) { TeamVenueGoals() }
        matches++
        homeGoals += match.homeGoals
        awayGoals += match.awayGoals
        home.homeMatches++
        home.homeGoalsFor += match.homeGoals
        home.homeGoalsAgainst += match.awayGoals
        away.awayMatches++
        away.awayGoalsFor += match.awayGoals
        away.awayGoalsAgainst += match.homeGoals
    }
}

/** Venue-split goal baseline; it is research-only until future evidence supports live use. */
object HomeAwayPerformanceEngine {
    fun forecast(
        history: Collection<HistoricalMatch>,
        forecastCutoffMillis: Long,
        homeTeam: String,
        awayTeam: String,
        config: HomeAwayPerformanceConfig = HomeAwayPerformanceConfig(),
    ): HomeAwayPerformancePrediction? {
        validateFixture(homeTeam, awayTeam)
        val state = HomeAwayState()
        history.asSequence()
            .filter { it.safeAvailabilityMillisOrNull()?.let { available -> available < forecastCutoffMillis } == true }
            .forEach(state::add)
        return predict(state, homeTeam, awayTeam, config)
    }

    private fun predict(
        state: HomeAwayState,
        homeTeam: String,
        awayTeam: String,
        config: HomeAwayPerformanceConfig,
    ): HomeAwayPerformancePrediction? {
        if (state.matches < config.minimumLeagueMatches || state.matches == 0) return null
        val home = state.teams[teamKey(homeTeam)] ?: return null
        val away = state.teams[teamKey(awayTeam)] ?: return null
        if (home.homeMatches < config.minimumMatchesPerSplit || away.awayMatches < config.minimumMatchesPerSplit) return null

        val leagueHomeRate = state.homeGoals.toDouble() / state.matches
        val leagueAwayRate = state.awayGoals.toDouble() / state.matches
        if (leagueHomeRate <= 0.0 || leagueAwayRate <= 0.0) return null

        fun strength(goals: Long, matches: Int, leagueRate: Double): Double =
            ((goals + leagueRate * config.smoothingMatches) / (matches + config.smoothingMatches)) / leagueRate

        val homeAttack = strength(home.homeGoalsFor, home.homeMatches, leagueHomeRate)
        val homeDefense = strength(home.homeGoalsAgainst, home.homeMatches, leagueAwayRate)
        val awayAttack = strength(away.awayGoalsFor, away.awayMatches, leagueAwayRate)
        val awayDefense = strength(away.awayGoalsAgainst, away.awayMatches, leagueHomeRate)
        val homeLambda = (leagueHomeRate * homeAttack * awayDefense)
            .coerceIn(GoalExpectationEngine.MIN_EXPECTED_GOALS, GoalExpectationEngine.MAX_EXPECTED_GOALS)
        val awayLambda = (leagueAwayRate * awayAttack * homeDefense)
            .coerceIn(GoalExpectationEngine.MIN_EXPECTED_GOALS, GoalExpectationEngine.MAX_EXPECTED_GOALS)
        val probabilities = GoalModel.predict1x2(homeLambda, awayLambda, config.maxGoals).probabilities

        return HomeAwayPerformancePrediction(
            probabilities = probabilities,
            homeExpectedGoals = homeLambda,
            awayExpectedGoals = awayLambda,
            historyMatchCount = state.matches,
            homeTeamHomeMatches = home.homeMatches,
            awayTeamAwayMatches = away.awayMatches,
        )
    }

    internal fun predictFromState(
        state: HomeAwayState,
        homeTeam: String,
        awayTeam: String,
        config: HomeAwayPerformanceConfig,
    ): HomeAwayPerformancePrediction? = predict(state, homeTeam, awayTeam, config)

    private fun validateFixture(homeTeam: String, awayTeam: String) {
        require(homeTeam.isNotBlank() && awayTeam.isNotBlank())
        require(teamKey(homeTeam) != teamKey(awayTeam))
    }
}

/** Prequential venue-split evaluation with the same strict availability batches as production research. */
object HomeAwayPerformanceWalkForwardEngine {
    fun evaluate(
        history: Collection<HistoricalMatch>,
        targets: Collection<WalkForwardTarget>,
        config: HomeAwayPerformanceConfig = HomeAwayPerformanceConfig(),
    ): HomeAwayPerformanceWalkForwardReport {
        require(targets.map { it.id }.distinct().size == targets.size) { "Target IDs must be unique." }
        targets.forEach { target ->
            require(target.id.isNotBlank())
            require(target.homeGoals >= 0 && target.awayGoals >= 0)
            require(target.homeTeam.isNotBlank() && target.awayTeam.isNotBlank())
            require(teamKey(target.homeTeam) != teamKey(target.awayTeam))
        }
        val orderedHistory = history.filter { it.safeAvailabilityMillisOrNull() != null }.sortedWith(
            compareBy<HistoricalMatch> { requireNotNull(it.safeAvailabilityMillisOrNull()) }
                .thenBy { it.kickoffMillis }
                .thenBy { teamKey(it.homeTeam) }
                .thenBy { teamKey(it.awayTeam) },
        )
        val targetsByCutoff = targets.groupBy { it.forecastCutoffMillis }.toSortedMap()
        val state = HomeAwayState()
        val predictions = mutableListOf<HomeAwayPerformanceWalkForwardPrediction>()
        var historyIndex = 0

        for ((cutoff, sameCutoffTargets) in targetsByCutoff) {
            while (historyIndex < orderedHistory.size &&
                requireNotNull(orderedHistory[historyIndex].safeAvailabilityMillisOrNull()) < cutoff
            ) {
                val availableAt = requireNotNull(orderedHistory[historyIndex].safeAvailabilityMillisOrNull())
                var batchEnd = historyIndex + 1
                while (batchEnd < orderedHistory.size && orderedHistory[batchEnd].safeAvailabilityMillisOrNull() == availableAt) {
                    batchEnd++
                }
                for (index in historyIndex until batchEnd) state.add(orderedHistory[index])
                historyIndex = batchEnd
            }

            for (target in sameCutoffTargets.sortedWith(
                compareBy<WalkForwardTarget> { teamKey(it.homeTeam) }
                    .thenBy { teamKey(it.awayTeam) }
                    .thenBy { it.id },
            )) {
                val prediction = HomeAwayPerformanceEngine.predictFromState(
                    state,
                    target.homeTeam,
                    target.awayTeam,
                    config,
                ) ?: continue
                val observed = when {
                    target.homeGoals > target.awayGoals -> OutcomeProbabilities(1.0, 0.0, 0.0)
                    target.homeGoals == target.awayGoals -> OutcomeProbabilities(0.0, 1.0, 0.0)
                    else -> OutcomeProbabilities(0.0, 0.0, 1.0)
                }
                predictions += HomeAwayPerformanceWalkForwardPrediction(
                    targetId = target.id,
                    forecastCutoffMillis = cutoff,
                    probabilities = prediction.probabilities,
                    homeExpectedGoals = prediction.homeExpectedGoals,
                    awayExpectedGoals = prediction.awayExpectedGoals,
                    historyMatchCount = prediction.historyMatchCount,
                    brierScore = ForecastMetrics.brierScore(prediction.probabilities, observed),
                    logLoss = ForecastMetrics.logLoss(prediction.probabilities, observed),
                )
            }
        }
        return HomeAwayPerformanceWalkForwardReport(
            targetCount = targets.size,
            forecastCount = predictions.size,
            skippedForInsufficientHistory = targets.size - predictions.size,
            meanBrierScore = predictions.map { it.brierScore }.average().takeIf { predictions.isNotEmpty() },
            meanLogLoss = predictions.map { it.logLoss }.average().takeIf { predictions.isNotEmpty() },
            predictions = predictions,
        )
    }
}
