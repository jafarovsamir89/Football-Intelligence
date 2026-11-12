package az.simplesoft.footballintelligence

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import az.simplesoft.footballintelligence.analytics.ProspectiveForecastReport
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
