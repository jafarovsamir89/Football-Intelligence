package az.simplesoft.footballintelligence

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import az.simplesoft.footballintelligence.analytics.CouponSelectionBasis
import az.simplesoft.footballintelligence.analytics.ForecastedMatch
import az.simplesoft.footballintelligence.analytics.JointProbabilityBounds
import az.simplesoft.footballintelligence.analytics.OutcomeProbabilities
import az.simplesoft.footballintelligence.analytics.VirtualCouponCandidate
import az.simplesoft.footballintelligence.analytics.VirtualCouponPick
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FootballRedesignInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun bottomNavigationHasFourPrimarySectionsAndSettingsStaysInTopBar() {
        var selected: FootballDestination? = null
        composeRule.setContent {
            FootballTheme {
                FootballBottomNavigation(FootballDestination.HOME) { selected = it }
            }
        }

        listOf("Главная", "Матчи", "Купоны", "Аналитика").forEach {
            composeRule.onNodeWithText(it).assertIsDisplayed()
        }
        assertTrue(composeRule.onAllNodesWithContentDescription("Настройки").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText("Аналитика").performClick()
        assertEquals(FootballDestination.ANALYTICS, selected)
    }

    @Test
    fun homeDashboardShowsSourceStateOddsAndClosestMatch() {
        val match = tomorrowMatch()
        composeRule.setContent {
            FootballTheme {
                FootballHomeTabContent(
                    matches = listOf(match),
                    forecasts = listOf(ForecastedMatch(match.id, match.home, match.away, match.kickoff.let { java.time.Instant.parse(it).toEpochMilli() }, OutcomeProbabilities(.55, .25, .20), competition = match.league)),
                    misliOdds = emptyList(),
                    topazOdds = emptyList(),
                    checkedPredictions = emptyList(),
                    syncBusy = false,
                    lastSyncLabel = "Обновлено 12.11 10:00",
                    syncDetails = "OpenFootball OK",
                    storeLoaded = true,
                    storeError = null,
                    onOpenMatches = {},
                    onOpenCoupons = {},
                    onSelectMatch = {},
                )
            }
        }

        composeRule.onNodeWithText("Коэффициенты").assertIsDisplayed()
        composeRule.onNodeWithText("Source Home FC").assertIsDisplayed()
        composeRule.onNodeWithText("П1 55%").assertIsDisplayed()
        composeRule.onNodeWithText("Misli").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("нет линии").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun couponWorkspaceSwitchesBookmakerAndLegSize() {
        val kickoff = tomorrowMatch().let { java.time.Instant.parse(it.kickoff).toEpochMilli() }
        val capturedAt = System.currentTimeMillis()
        val candidate = { provider: String, name: String ->
            VirtualCouponCandidate(
                providerId = provider,
                providerName = name,
                picks = listOf(
                    VirtualCouponPick(101, "Home A", "Away A", kickoff, "H", .62, 1.8, capturedAt),
                    VirtualCouponPick(102, "Home B", "Away B", kickoff + 3_600_000, "D", .33, 3.1, capturedAt),
                ),
                combinedDecimalOdds = 5.58,
                requestedLegCount = 2,
                jointProbabilityBounds = JointProbabilityBounds(0.0, .33),
                selectionBasis = CouponSelectionBasis.MATCHED_FRESH_BOOKMAKER_ODDS,
            )
        }
        composeRule.setContent {
            FootballTheme {
                FootballCouponWorkspace(
                    misliOdds = emptyList(),
                    topazOdds = emptyList(),
                    candidates = listOf(candidate("misli", "Misli"), candidate("topaz", "TOPAZ")),
                    history = emptyList(),
                    checkedPicks = emptyList(),
                )
            }
        }

        composeRule.onNodeWithText("Misli · 2 исхода").assertIsDisplayed()
        composeRule.onNodeWithText("TOPAZ").performClick()
        composeRule.onNodeWithText("TOPAZ · 2 исхода").assertIsDisplayed()
        composeRule.onNodeWithText("3 исхода").performClick()
        composeRule.onNodeWithText("Экспресс из 3 исходов пока не собран").assertIsDisplayed()
    }

    @Test
    fun matchFiltersUseBakuLocalTomorrowDate() {
        val match = tomorrowMatch()
        var selectedMatch: Int? = null
        composeRule.setContent {
            FootballTheme {
                FootballFixtureList(
                    matches = listOf(match),
                    forecasts = emptyList(),
                    misliOdds = emptyList(),
                    topazOdds = emptyList(),
                    storeLoaded = true,
                    storeError = null,
                    onSelectMatch = { selectedMatch = it },
                )
            }
        }

        composeRule.onNodeWithText("Source Home FC").assertIsDisplayed()
        listOf("Сегодня", "Завтра", "7 дней", "Все").forEach { composeRule.onNodeWithText(it).assertIsDisplayed() }
        composeRule.onNodeWithText("Завтра").performClick()
        composeRule.onNodeWithText("Source Away FC").assertIsDisplayed()
        composeRule.onNodeWithText("Source Away FC").performClick()
        assertEquals(match.id, selectedMatch)
    }

    private fun tomorrowMatch(): Match {
        val kickoff = LocalDate.now(ZoneId.of("Asia/Baku")).plusDays(1)
            .atTime(19, 30).atZone(ZoneId.of("Asia/Baku")).toInstant().toString()
        return Match(901001, "Test League", "Source Home FC", "Source Away FC", kickoff, false, null, null, "test:901001")
    }
}
