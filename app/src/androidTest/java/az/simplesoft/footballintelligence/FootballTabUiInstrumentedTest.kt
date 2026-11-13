package az.simplesoft.footballintelligence

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import az.simplesoft.footballintelligence.analytics.InternationalBookmakerEvaluationReport
import az.simplesoft.footballintelligence.analytics.InternationalMarketEngine
import az.simplesoft.footballintelligence.analytics.InternationalMarketEvaluationEngine
import az.simplesoft.footballintelligence.analytics.InternationalMarketEvaluationReport
import az.simplesoft.footballintelligence.analytics.MetricConfidenceInterval95
import az.simplesoft.footballintelligence.analytics.ProspectiveForecastReport
import az.simplesoft.footballintelligence.analytics.EloConfig
import az.simplesoft.footballintelligence.analytics.EloExperimentMetrics
import az.simplesoft.footballintelligence.analytics.GoalExpectationConfig
import az.simplesoft.footballintelligence.analytics.GoalModelComparisonReport
import az.simplesoft.footballintelligence.analytics.EloWalkForwardReport
import az.simplesoft.footballintelligence.analytics.CompetitionSeasonWalkForwardMetrics
import az.simplesoft.footballintelligence.analytics.CombinedModelCandidate
import az.simplesoft.footballintelligence.analytics.CombinedModelCalibrationMetrics
import az.simplesoft.footballintelligence.analytics.CombinedModelResearchReport
import az.simplesoft.footballintelligence.analytics.HomeAwayPerformanceConfig
import az.simplesoft.footballintelligence.data.InternationalOddsSnapshot
import az.simplesoft.footballintelligence.data.InternationalOddsQuoteSide
import az.simplesoft.footballintelligence.data.InternationalMatchLinkBasis
import az.simplesoft.footballintelligence.data.InternationalOddsStatus
import az.simplesoft.footballintelligence.data.InternationalOddsSyncState
import az.simplesoft.footballintelligence.data.FootballDataStatsAttempt
import az.simplesoft.footballintelligence.data.FootballMatchStatValues
import az.simplesoft.footballintelligence.data.StoredFootballMatchStats
import az.simplesoft.footballintelligence.data.THE_ODDS_API_PROVIDER_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FootballTabUiInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun matchesTabRendersFixtureAndReturnsSelectionToHost() {
        var selectedMatchId: Int? = null
        composeRule.setContent {
            MaterialTheme {
                FootballMatchesTabContent(
                    syncStatusText = "Test sync status",
                    misliSyncState = null,
                    misliOdds = emptyList(),
                    topazSyncState = null,
                    topazOdds = emptyList(),
                    internationalAgeConfirmed = false,
                    internationalOddsSyncState = null,
                    internationalOdds = emptyList(),
                    marketReferenceSyncState = null,
                    marketReferences = emptyList(),
                    syncSummaries = emptyList(),
                    syncBusy = false,
                    footballDataStatsAttempt = null,
                    footballDataStatsNextAllowedAtMillis = 0L,
                    matches = listOf(testMatch),
                    storeLoaded = true,
                    storeError = null,
                    onSelectMatch = { selectedMatchId = it },
                    blue = MaterialTheme.colorScheme.primary,
                )
            }
        }

        composeRule.onNodeWithText("Test sync status").assertIsDisplayed()
        composeRule.onNodeWithText("Football-Data.co.uk статистика: ожидает первой проверки", substring=true).assertExists()
        composeRule.onNodeWithText("Source Home FC — Source Away FC").performClick()

        assertEquals(testMatch.id, selectedMatchId)
    }

    @Test
    fun couponTabExpandsQuotesAndKeepsBookmakersSeparate() {
        val showQuotes = mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                FootballCouponTabContent(
                    misliSyncState = null,
                    misliOdds = emptyList(),
                    topazSyncState = null,
                    topazOdds = emptyList(),
                    forecastedMatches = emptyList(),
                    showBookmakerQuotes = showQuotes.value,
                    onToggleBookmakerQuotes = { showQuotes.value = !showQuotes.value },
                    oddsProviders = emptyList(),
                    accumulatorDrafts = emptyList(),
                    accumulatorHistory = emptyList(),
                    checkedCouponHistory = emptyList(),
                    prospectiveForecastHistory = emptyList(),
                    prospectiveForecastReport = ProspectiveForecastReport(0, 0, null, null, null),
                )
            }
        }

        composeRule.onNodeWithText("Показать публичные линии (0)").performClick()
        composeRule.onNodeWithText("Скрыть публичные линии").assertIsDisplayed()
        composeRule.onNodeWithText("Misli.az · публичные линии 1X2").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("TOPAZ / eTopaz").performScrollTo().assertIsDisplayed()
        assertTrue(showQuotes.value)
    }

    @Test
    fun couponTabHidesOldProspectiveSummaryWhileAnalyticsRefreshes() {
        composeRule.setContent {
            MaterialTheme {
                FootballCouponTabContent(
                    misliSyncState = null,
                    misliOdds = emptyList(),
                    topazSyncState = null,
                    topazOdds = emptyList(),
                    forecastedMatches = emptyList(),
                    showBookmakerQuotes = false,
                    onToggleBookmakerQuotes = {},
                    oddsProviders = emptyList(),
                    accumulatorDrafts = emptyList(),
                    accumulatorHistory = emptyList(),
                    checkedCouponHistory = emptyList(),
                    prospectiveForecastHistory = emptyList(),
                    prospectiveForecastReport = ProspectiveForecastReport(9, 4, .26, .5, 1.0),
                    analyticsUpdating = true,
                )
            }
        }

        composeRule.onNodeWithText("Обновляю метрики прогнозов; предыдущая сводка временно скрыта.").performScrollTo().assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("В локальном журнале 9 снимков.").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun resultsTabRendersSavedScore() {
        composeRule.setContent {
            MaterialTheme {
                FootballResultsTabContent(
                    recentResults = listOf(testMatch.copy(finished = true, homeScore = 2, awayScore = 1)),
                    matchStats = mapOf(testMatch.id to StoredFootballMatchStats(
                        matchId=testMatch.id,
                        division="E0",
                        eventDate=java.time.LocalDate.of(2026,11,1),
                        sourceUrl="https://www.football-data.co.uk/mmz4281/2627/E0.csv",
                        capturedAtMillis=1_800_000_000_000L,
                        availableAtMillis=1_800_000_000_000L,
                        values=FootballMatchStatValues(homeShots=14,awayShots=5,homeCorners=7,awayCorners=2),
                    )),
                    latestStatsAttempt=FootballDataStatsAttempt(
                        id=1,attemptedAtMillis=1_800_000_000_000L,division="E0",season="2627",
                        status="OK",rowsRead=12,rowsMatched=4,detail=null,
                    ),
                    nextStatsSyncAtMillis=1_800_000_000_000L,
                )
            }
        }

        composeRule.onNodeWithText("Source Home FC 2:1 Source Away FC").assertIsDisplayed()
        composeRule.onNodeWithText("Удары 14:5 · Угловые 7:2").assertIsDisplayed()
        composeRule.onNodeWithText("Football-Data.co.uk · E0", substring=true).assertExists()
    }

    @Test
    fun backtestTabReturnsCompetitionAndRunActionsToHost() {
        var selectedCompetition: String? = null
        var requestedBacktest: String? = null
        composeRule.setContent {
            MaterialTheme {
                FootballBacktestTabContent(
                    automaticBacktestRunning = false,
                    automaticBacktestMessage = "",
                    automaticBacktestReport = null,
                    automaticEloExperiment = null,
                    automaticGoalComparison = null,
                    importedCompetitionCodes = listOf("TEST"),
                    activeBacktestCode = "TEST",
                    onSelectBacktestCode = { selectedCompetition = it },
                    onRunImportedBacktest = { requestedBacktest = it },
                    backtestRunning = false,
                    backtestMessage = "",
                    backtestReportCode = "",
                    backtestReport = null,
                )
            }
        }

        composeRule.onNodeWithText("TEST").performClick()
        composeRule.onNodeWithText("Рассчитать walk-forward").performScrollTo().performClick()

        assertEquals("TEST", selectedCompetition)
        assertEquals("TEST", requestedBacktest)
    }

    @Test
    fun backtestShowsCompetitionSeasonEloDiagnostics() {
        composeRule.setContent {
            MaterialTheme {
                FootballBacktestTabContent(
                    automaticBacktestRunning = false,
                    automaticBacktestMessage = "",
                    automaticBacktestReport = EloWalkForwardReport(1,1,0,0.5,1.0,emptyList()),
                    automaticEloByCompetitionSeason = listOf(
                        CompetitionSeasonWalkForwardMetrics("League A","2024-25",20,18,2,0.51,1.04),
                    ),
                    automaticEloExperiment = null,
                    automaticGoalComparison = null,
                    importedCompetitionCodes = emptyList(),
                    activeBacktestCode = "",
                    onSelectBacktestCode = {},
                    onRunImportedBacktest = {},
                    backtestRunning = false,
                    backtestMessage = "",
                    backtestReportCode = "",
                    backtestReport = null,
                )
            }
        }

        composeRule.onNodeWithText("Показать сезонные результаты").performScrollTo().performClick()
        composeRule.onNodeWithText("League A · 2024-25: прогнозов 18/20",substring=true).assertIsDisplayed()
    }

    @Test
    fun backtestShowsHomeAwayResearchBaselineAndPairedHoldoutCoverage() {
        val metrics = EloExperimentMetrics(2, 2, 0.45, 1.02)
        val comparison = GoalModelComparisonReport(
            validationStartMillis = 100L,
            holdoutStartMillis = 200L,
            validationTargetCount = 4,
            validationGoalMetrics = metrics,
            validationHomeAwayMetrics = metrics,
            selectedRhoValidationMetrics = null,
            rhoCandidates = emptyList(),
            selectedDixonColesRho = null,
            rhoSelectionAvailable = false,
            minimumValidationForecastsForRhoSelection = 100,
            holdoutTargetCount = 2,
            eloHoldoutForecastCount = 2,
            goalHoldoutForecastCount = 2,
            homeAwayHoldoutForecastCount = 2,
            pairedHoldoutTargetCount = 2,
            homeAwayPairedHoldoutTargetCount = 1,
            eloPairedHoldout = metrics,
            goalPairedHoldout = metrics,
            eloHomeAwayPairedHoldout = metrics,
            homeAwayPairedHoldout = metrics.copy(targetCount = 1, forecastCount = 1),
            eloMinusPoissonBrier95 = null,
            eloMinusPoissonLogLoss95 = null,
            eloMinusHomeAwayBrier95 = null,
            eloMinusHomeAwayLogLoss95 = null,
            minimumPairedFixturesForIntervals = 30,
            selectedEloConfig = EloConfig(),
            usedValidationSelectedEloConfig = true,
            goalConfig = GoalExpectationConfig(),
            homeAwayConfig = HomeAwayPerformanceConfig(),
            combinedModel = CombinedModelResearchReport(
                validationPairedForecastCount = 600,
                minimumValidationForecasts = 500,
                validationEloMetrics = metrics,
                validationPoissonMetrics = metrics,
                validationCandidate = CombinedModelCandidate(0.5, metrics.copy(meanBrierScore = 0.40, meanLogLoss = 0.90)),
                validationGatePassed = true,
                minimumHoldoutForecasts = 1,
                holdoutPairedForecastCount = 2,
                holdoutEloMetrics = metrics,
                holdoutPoissonMetrics = metrics,
                holdoutMetrics = metrics.copy(meanBrierScore = 0.42, meanLogLoss = 0.95),
                holdoutGatePassed = true,
                validationCalibration = CombinedModelCalibrationMetrics(600, 0.12, 0.13, 0.10),
                holdoutCalibration = CombinedModelCalibrationMetrics(2, 0.20, 0.22, 0.18),
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                FootballBacktestTabContent(
                    automaticBacktestRunning = false,
                    automaticBacktestMessage = "",
                    automaticBacktestReport = null,
                    automaticEloExperiment = null,
                    automaticGoalComparison = comparison,
                    importedCompetitionCodes = emptyList(),
                    activeBacktestCode = "",
                    onSelectBacktestCode = {},
                    onRunImportedBacktest = {},
                    backtestRunning = false,
                    backtestMessage = "",
                    backtestReportCode = "",
                    backtestReport = null,
                )
            }
        }

        composeRule.onNodeWithText("Сравнение моделей и интервалы").performScrollTo().performClick()
        composeRule.onNodeWithText("Elo, Poisson и Home/Away performance").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Validation Home/Away:", substring = true).assertExists()
        composeRule.onNodeWithText("Holdout Elo/Home-Away на общих матчах 1:", substring = true).assertExists()
        composeRule.onNodeWithText("Парный bootstrap 95% Elo−Poisson", substring = true).assertExists()
        assertTrue(
            composeRule.onAllNodesWithText("не рассчитан при n<30", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        composeRule.onNodeWithText("Home/Away пока исследовательская модель", substring = true).assertExists()
        composeRule.onNodeWithText("Elo + Poisson: экспериментальная смесь").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Порог validation пройден", substring = true).assertExists()
        composeRule.onNodeWithText("Отдельный holdout для validation-выбранного веса", substring = true).assertExists()
        composeRule.onNodeWithText("Holdout-gate пройден", substring = true).assertExists()
        composeRule.onNodeWithText("Temperature scaling", substring = true).assertExists()
        composeRule.onNodeWithText("One-vs-rest ECE holdout", substring = true).assertExists()
    }

    @Test
    fun settingsTabReturnsCsvInputAndLaunchActionsToHost() {
        val enteredCompetition = mutableStateOf("")
        var launchedImport = false
        composeRule.setContent {
            MaterialTheme {
                FootballSettingsTabContent(
                    fallbackCompetition = enteredCompetition.value,
                    onFallbackCompetitionChange = { enteredCompetition.value = it },
                    importingCsv = false,
                    csvImportMessage = "",
                    onLaunchCsvImport = { launchedImport = true },
                )
            }
        }

        composeRule.onNodeWithText("Необязательный код соревнования для CSV").performTextInput("EPL")
        composeRule.onNodeWithText("EPL").assertIsDisplayed()
        composeRule.onNodeWithText("Импорт CSV (дополнительно)").performScrollTo().performClick()

        assertEquals("EPL", enteredCompetition.value)
        assertTrue(launchedImport)
    }

    @Test
    fun worldMarketShowsTheOddsApiProviderAndFreshNamedBookLines() {
        showWorldMarket(marketEvaluation(30), includeSecondBook = true)

        composeRule.onNodeWithText("The Odds API · EU · LIVE", substring = true).assertIsDisplayed()
        val marketList = composeRule.onNodeWithTag("internationalMarketList")
        marketList.performScrollToNode(hasText("Предварительная проверка рынка и Elo"))
        composeRule.onNodeWithText("Предварительная проверка рынка и Elo").assertIsDisplayed()
        marketList.performScrollToNode(hasText("95% парный bootstrap", substring = true))
        composeRule.onNodeWithText("95% парный bootstrap", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Brier [-0.200; -0.100]", substring = true).assertIsDisplayed()
        marketList.performScrollToNode(hasText("Знак Δ Elo−рынок", substring = true))
        composeRule.onNodeWithText("Знак Δ Elo−рынок", substring = true).assertIsDisplayed()
        marketList.performScrollToNode(hasText("Интервалы — парная выборка", substring = true))
        composeRule.onNodeWithText("Интервалы — парная выборка", substring = true).assertIsDisplayed()
        marketList.performScrollToNode(hasText("Unibet Fr · Back (не сохранён в старой записи) · 1 1.27", substring = true))
        composeRule.onNodeWithText("Unibet Fr · Back (не сохранён в старой записи) · 1 1.27", substring = true).assertIsDisplayed()
        marketList.performScrollToNode(hasText("Источник: The Odds API", substring = true))
        assertTrue(
            composeRule.onAllNodesWithText("Источник: The Odds API", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        marketList.performScrollToNode(hasText("Сравнение снятия маржи · равное среднее по 2", substring = true))
        assertEquals(
            1,
            composeRule.onAllNodesWithText("Сравнение снятия маржи · равное среднее по 2", substring = true)
                .fetchSemanticsNodes().size,
        )
        composeRule.onNodeWithText("Power", substring = true).assertExists()
        composeRule.onNodeWithText("Shin", substring = true).assertExists()
    }

    @Test
    fun worldMarketSeparatesExchangeBackAndLayAndShowsOnlySourceData() {
        showWorldMarket(marketEvaluation(30), includeExchangePair = true)

        val marketList = composeRule.onNodeWithTag("internationalMarketList")
        marketList.performScrollToNode(hasText("Betfair Exchange · Back (h2h)", substring = true))
        composeRule.onNodeWithText("Betfair Exchange · Back (h2h)", substring = true).assertIsDisplayed()
        marketList.performScrollToNode(hasText("Betfair Exchange · Lay (h2h_lay)", substring = true))
        composeRule.onNodeWithText("Betfair Exchange · Lay (h2h_lay)", substring = true).assertIsDisplayed()
        marketList.performScrollToNode(hasText("Спред котировок Lay − Back: 1 0.04", substring = true))
        composeRule.onNodeWithText("Спред котировок Lay − Back: 1 0.04 · X 0.10 · 2 0.10", substring = true).assertIsDisplayed()
        assertEquals(1, composeRule.onAllNodesWithText("Спред котировок Lay − Back:", substring = true).fetchSemanticsNodes().size)
        marketList.performScrollToNode(hasText("Лимит ставки, как передан источником", substring = true))
        composeRule.onNodeWithText("Лимит ставки, как передан источником (единица не указана): 1 250.00 / X 125.00 / 2 200.00. Это не оценка доступной ликвидности.").assertIsDisplayed()
        marketList.performScrollToNode(hasText("Комиссия Betfair не передана источником", substring = true))
        composeRule.onNodeWithText("Комиссия Betfair не передана источником и не вычтена; чистая биржевая доходность не рассчитывается.").assertIsDisplayed()
    }

    @Test
    fun worldMarketExplainsWhyIntervalsAreUnavailableBelowThirtySettlements() {
        showWorldMarket(marketEvaluation(29))

        val marketList = composeRule.onNodeWithTag("internationalMarketList")
        marketList.performScrollToNode(hasText("95% интервалы пока недоступны", substring = true))
        composeRule.onNodeWithText("95% интервалы пока недоступны", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("нужно ≥30 завершённых матчей, сейчас 29", substring = true).assertIsDisplayed()
    }

    @Test
    fun worldMarketHidesOldSummaryButKeepsCurrentQuotesWhileAnalyticsRefreshes() {
        showWorldMarket(marketEvaluation(30), analyticsUpdating = true)

        val marketList = composeRule.onNodeWithTag("internationalMarketList")
        marketList.performScrollToNode(hasText("Обновляю оценку рынка", substring = true))
        composeRule.onNodeWithText("Обновляю оценку рынка; предыдущие сводные показатели временно скрыты.").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("Котировок БК–матч:", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("95% парный bootstrap", substring = true).fetchSemanticsNodes().isEmpty())
        marketList.performScrollToNode(hasText("Unibet Fr · Back (не сохранён в старой записи) · 1 1.27", substring = true))
        composeRule.onNodeWithText("Unibet Fr · Back (не сохранён в старой записи) · 1 1.27", substring = true).assertIsDisplayed()
    }

    private fun showWorldMarket(
        evaluation: InternationalMarketEvaluationReport,
        analyticsUpdating: Boolean = false,
        includeExchangePair: Boolean = false,
        includeSecondBook: Boolean = false,
    ) {
        val now = System.currentTimeMillis()
        val quote = InternationalOddsSnapshot(
            providerId = THE_ODDS_API_PROVIDER_ID,
            bookmakerId = "unibet_fr",
            eventId = "api-event-1",
            sourceUrl = "https://api.the-odds-api.com/v4/sports/soccer_epl/odds",
            league = "Premier League",
            region = "eu",
            capturedAtMillis = now,
            providerPolledAtMillis = now,
            kickoffMillis = now + 86_400_000L,
            market = "1X2",
            homeTeam = "Manchester City",
            awayTeam = "Fulham",
            home = 1.27,
            draw = 4.5,
            away = 5.8,
            homePriceChangedAtMillis = now,
            drawPriceChangedAtMillis = now,
            awayPriceChangedAtMillis = now,
            matchId = 42,
            matchLinkBasis = InternationalMatchLinkBasis.EXACT_KICKOFF,
        )
        val quotes = when {
            includeExchangePair -> {
                val back = quote.copy(
                    bookmakerId = "betfair_ex_eu",
                    eventId = "betfair-event-1",
                    home = 2.10,
                    draw = 3.40,
                    away = 3.80,
                    sourceMarketKey = "h2h",
                    quoteSide = InternationalOddsQuoteSide.BACK,
                    homeBetLimit = 250.0,
                    drawBetLimit = 125.0,
                    awayBetLimit = 200.0,
                )
                listOf(
                    quote,
                    back,
                    back.copy(
                        home = 2.14,
                        draw = 3.50,
                        away = 3.90,
                        sourceMarketKey = "h2h_lay",
                        quoteSide = InternationalOddsQuoteSide.LAY,
                        homeBetLimit = 30.0,
                        drawBetLimit = 25.0,
                        awayBetLimit = 35.0,
                    ),
                )
            }
            includeSecondBook -> listOf(
                quote,
                quote.copy(bookmakerId = "pinnacle_eu", eventId = "pinnacle-event", home = 2.05, draw = 3.55, away = 3.85),
            )
            else -> listOf(quote)
        }
        composeRule.setContent {
            MaterialTheme {
                InternationalMarketTabContent(
                    internationalAgeConfirmed = true,
                    theOddsApiConfigured = true,
                    internationalOddsSyncState = null,
                    internationalOddsSyncHistory = emptyList(),
                    theOddsApiSyncState = InternationalOddsSyncState(
                        THE_ODDS_API_PROVIDER_ID, InternationalOddsStatus.LIVE, now, now, quotes.size, "Квота проверена",
                    ),
                    theOddsApiSyncHistory = emptyList(),
                    internationalOdds = quotes,
                    internationalOddsHistory = emptyList(),
                    internationalMarketEvaluation = evaluation,
                    internationalConsensus = InternationalMarketEngine.consensus(quotes),
                    forecastedMatches = emptyList(),
                    misliOdds = emptyList(),
                    topazOdds = emptyList(),
                    workerBusy = false,
                    showInternationalHistory = false,
                    onToggleInternationalHistory = {},
                    onOpenMoviOddsTerms = {},
                    onConfirmMoviOddsAge = {},
                    analyticsUpdating = analyticsUpdating,
                )
            }
        }
    }

    private fun marketEvaluation(settledFixtures: Int): InternationalMarketEvaluationReport {
        val enoughSettled = settledFixtures >= InternationalMarketEvaluationEngine.MIN_SETTLED_FIXTURES_FOR_INTERVALS
        val bookmakerReport = InternationalBookmakerEvaluationReport(
            bookmakerId = "unibet_fr",
            sourceProviderIds = listOf(THE_ODDS_API_PROVIDER_ID),
            quoteFixtureCount = settledFixtures,
            medianHoursBeforeKickoff = 12.0,
            pairedFixtureCount = settledFixtures,
            settledFixtureCount = settledFixtures,
            pendingFixtureCount = 0,
            marketBrierScore = 0.5,
            marketLogLoss = 1.0,
            marketTopOutcomeAccuracy = 0.5,
            modelBrierScore = 0.4,
            modelLogLoss = 0.9,
            modelTopOutcomeAccuracy = 0.6,
            eloMinusMarketBrier95 = if (enoughSettled) MetricConfidenceInterval95(-0.2, -0.1) else null,
            eloMinusMarketLogLoss95 = if (enoughSettled) MetricConfidenceInterval95(-0.2, -0.1) else null,
            eloMinusMarketTopAccuracy95 = if (enoughSettled) MetricConfidenceInterval95(0.05, 0.15) else null,
        )
        return InternationalMarketEvaluationReport(
            quoteFixtureCount = settledFixtures,
            pairedFixtureCount = settledFixtures,
            settledFixtureCount = settledFixtures,
            pendingFixtureCount = 0,
            bookmakerReports = listOf(bookmakerReport),
        )
    }

    private companion object {
        val testMatch = Match(
            id = 981001,
            league = "Compose UI League",
            home = "Source Home FC",
            away = "Source Away FC",
            kickoff = "2026-12-01T18:00:00Z",
            finished = false,
            homeScore = null,
            awayScore = null,
            sourceKey = "compose-ui-test:981001",
        )
    }
}
