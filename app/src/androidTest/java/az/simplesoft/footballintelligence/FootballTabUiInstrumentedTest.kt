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
import az.simplesoft.footballintelligence.analytics.InternationalMarketEvaluationEngine
import az.simplesoft.footballintelligence.analytics.InternationalMarketEvaluationReport
import az.simplesoft.footballintelligence.analytics.MetricConfidenceInterval95
import az.simplesoft.footballintelligence.analytics.ProspectiveForecastReport
import az.simplesoft.footballintelligence.data.InternationalOddsSnapshot
import az.simplesoft.footballintelligence.data.InternationalOddsStatus
import az.simplesoft.footballintelligence.data.InternationalOddsSyncState
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
                    matches = listOf(testMatch),
                    storeLoaded = true,
                    storeError = null,
                    onSelectMatch = { selectedMatchId = it },
                    blue = MaterialTheme.colorScheme.primary,
                )
            }
        }

        composeRule.onNodeWithText("Test sync status").assertIsDisplayed()
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
                )
            }
        }

        composeRule.onNodeWithText("Source Home FC 2:1 Source Away FC").assertIsDisplayed()
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
        showWorldMarket(marketEvaluation(30))

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
        marketList.performScrollToNode(hasText("Unibet Fr · 1 1.27", substring = true))
        composeRule.onNodeWithText("Unibet Fr · 1 1.27", substring = true).assertIsDisplayed()
        marketList.performScrollToNode(hasText("Источник: The Odds API", substring = true))
        composeRule.onNodeWithText("Источник: The Odds API", substring = true).assertIsDisplayed()
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
        marketList.performScrollToNode(hasText("Unibet Fr · 1 1.27", substring = true))
        composeRule.onNodeWithText("Unibet Fr · 1 1.27", substring = true).assertIsDisplayed()
    }

    private fun showWorldMarket(
        evaluation: InternationalMarketEvaluationReport,
        analyticsUpdating: Boolean = false,
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
        )
        composeRule.setContent {
            MaterialTheme {
                InternationalMarketTabContent(
                    internationalAgeConfirmed = true,
                    theOddsApiConfigured = true,
                    internationalOddsSyncState = null,
                    internationalOddsSyncHistory = emptyList(),
                    theOddsApiSyncState = InternationalOddsSyncState(
                        THE_ODDS_API_PROVIDER_ID, InternationalOddsStatus.LIVE, now, now, 1, "Квота проверена",
                    ),
                    theOddsApiSyncHistory = emptyList(),
                    internationalOdds = listOf(quote),
                    internationalOddsHistory = emptyList(),
                    internationalMarketEvaluation = evaluation,
                    internationalConsensus = emptyList(),
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
