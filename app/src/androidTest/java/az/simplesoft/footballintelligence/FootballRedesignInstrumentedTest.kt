package az.simplesoft.footballintelligence

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import az.simplesoft.footballintelligence.analytics.CouponSelectionBasis
import az.simplesoft.footballintelligence.analytics.EloForecast
import az.simplesoft.footballintelligence.analytics.ForecastedMatch
import az.simplesoft.footballintelligence.analytics.JointProbabilityBounds
import az.simplesoft.footballintelligence.analytics.OutcomeProbabilities
import az.simplesoft.footballintelligence.analytics.VirtualCouponCandidate
import az.simplesoft.footballintelligence.analytics.VirtualCouponPick
import az.simplesoft.footballintelligence.data.BookmakerOddsSnapshot
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
        val now = System.currentTimeMillis()
        val kickoff = java.time.Instant.parse(match.kickoff).toEpochMilli()
        val linkedLine = BookmakerOddsSnapshot(
            providerId = "misli",
            eventId = "test-linked",
            sourceUrl = "https://www.misli.az/",
            capturedAtMillis = now,
            kickoffMillis = kickoff,
            market = "1X2",
            homeTeam = match.home,
            awayTeam = match.away,
            home = 2.0,
            draw = 3.2,
            away = 4.0,
            eventDate = match.kickoff.take(10),
            timeZoneId = "Asia/Baku",
            matchId = match.id,
        )
        val unmatchedLine = linkedLine.copy(eventId = "test-unmatched", matchId = null)
        composeRule.setContent {
            FootballTheme {
                FootballHomeTabContent(
                    matches = listOf(match),
                    forecasts = listOf(ForecastedMatch(match.id, match.home, match.away, kickoff, OutcomeProbabilities(.55, .25, .20), competition = match.league)),
                    misliOdds = listOf(linkedLine, unmatchedLine),
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

        composeRule.onNodeWithText("Прогноз Elo").assertIsDisplayed()
        composeRule.onNodeWithText("Матчи с готовым прогнозом").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("1 / 1").assertIsDisplayed()
        composeRule.onNodeWithText("Линия Misli").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("1", substring = false).fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("2", substring = false).fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText("Source Home FC").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("П1 55%").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Misli").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("нет линии").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun emptyHomeExplainsFirstRunAndLinksToMainWorkflows() {
        var openedMatches = false
        composeRule.setContent {
            FootballTheme {
                FootballHomeTabContent(
                    matches = emptyList(),
                    forecasts = emptyList(),
                    misliOdds = emptyList(),
                    topazOdds = emptyList(),
                    checkedPredictions = emptyList(),
                    syncBusy = false,
                    lastSyncLabel = "Ожидается сеть",
                    syncDetails = "Нет успешной синхронизации",
                    storeLoaded = true,
                    storeError = null,
                    onOpenMatches = { openedMatches = true },
                    onOpenCoupons = {},
                    onSelectMatch = {},
                )
            }
        }

        composeRule.onNodeWithText("Нажми на матч", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("В этой установке база пока пустая", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Виртуальные купоны").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Открыть матчи").performScrollTo().performClick()
        assertTrue(openedMatches)
    }

    @Test
    fun homeShowsFailedAutomaticSyncInsteadOfAHealthyOldTimestamp() {
        composeRule.setContent {
            FootballTheme {
                FootballHomeTabContent(
                    matches = listOf(tomorrowMatch()),
                    forecasts = emptyList(),
                    misliOdds = emptyList(),
                    topazOdds = emptyList(),
                    checkedPredictions = emptyList(),
                    syncBusy = false,
                    lastSyncLabel = "Ошибка источника",
                    syncSummaryDetail = "OpenLigaDB: HTTP 503. Ранее сохранённые матчи остаются доступны офлайн.",
                    syncHealthy = false,
                    syncDetails = "OpenLigaDB · ERROR · HTTP 503",
                    storeLoaded = true,
                    storeError = null,
                    onOpenMatches = {},
                    onOpenCoupons = {},
                    onSelectMatch = {},
                )
            }
        }

        composeRule.onNodeWithText("Ошибка источника", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("OpenLigaDB: HTTP 503", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Статус источников").performClick()
        composeRule.onNodeWithText("OpenLigaDB · ERROR · HTTP 503", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun matchAnalysisExplainsTheExactHistoryGapInsteadOfShowingBlankProbabilities() {
        composeRule.setContent {
            FootballTheme {
                MatchAnalysisTab(
                    active = tomorrowMatch(),
                    eloForecast = EloForecast(probabilities = null, matchesUsed = 8, homeTeamMatches = 2, awayTeamMatches = 1),
                    activeMarketReference = null,
                    marketReferenceSyncState = null,
                    misliOdds = emptyList(),
                    topazOdds = emptyList(),
                    onOpenTipsAudit = {},
                )
            }
        }

        composeRule.onNodeWithText("Прогноз не рассчитан: недостаточно истории", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("лига 8/10, хозяева 2/3, гости 1/3", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("10 завершённых матчей лиги", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun matchAnalysisExplainsWhyTheModelProducedItsProbabilityEstimate() {
        composeRule.setContent {
            FootballTheme {
                MatchAnalysisTab(
                    active = tomorrowMatch(),
                    eloForecast = EloForecast(
                        probabilities = OutcomeProbabilities(.55, .25, .20),
                        matchesUsed = 22,
                        homeTeamMatches = 7,
                        awayTeamMatches = 9,
                        homeTeamRating = 1540.0,
                        awayTeamRating = 1480.0,
                        homeAdvantagePoints = 65.0,
                        smoothedDrawProbability = .26,
                    ),
                    activeMarketReference = null,
                    marketReferenceSyncState = null,
                    misliOdds = emptyList(),
                    topazOdds = emptyList(),
                    onOpenTipsAudit = {},
                )
            }
        }

        composeRule.onNodeWithText("Как читать эту оценку").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Самая высокая оценка: П1 (55.0%)", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Elo хозяев 1540, гостей 1480", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("22 завершённых результатов лиги, доступных до начала", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Модель использует только результаты и фактор домашнего поля. Она экспериментальная, не учитывает составы и не подтверждает преимущество или прибыль.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun matchAnalysisExplainsPositiveLocalMarketDifferenceWithoutCallingItARecommendation() {
        val match = tomorrowMatch()
        val now = System.currentTimeMillis()
        val quote = BookmakerOddsSnapshot(
            providerId = "misli",
            eventId = "misli-market-gap-test",
            sourceUrl = "https://www.misli.az/",
            capturedAtMillis = now,
            kickoffMillis = java.time.Instant.parse(match.kickoff).toEpochMilli(),
            market = "1X2",
            homeTeam = match.home,
            awayTeam = match.away,
            home = 2.0,
            draw = 3.2,
            away = 4.0,
            eventDate = match.kickoff.take(10),
            timeZoneId = "Asia/Baku",
            matchId = match.id,
        )
        composeRule.setContent {
            FootballTheme {
                MatchAnalysisTab(
                    active = match,
                    eloForecast = EloForecast(
                        probabilities = OutcomeProbabilities(.55, .25, .20),
                        matchesUsed = 22,
                        homeTeamMatches = 7,
                        awayTeamMatches = 9,
                    ),
                    activeMarketReference = null,
                    marketReferenceSyncState = null,
                    misliOdds = listOf(quote),
                    topazOdds = emptyList(),
                    onOpenTipsAudit = {},
                )
            }
        }

        composeRule.onNodeWithText("Misli: наибольшая положительная разница — П1 +7.9 п.п.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("расхождение для проверки, а не рекомендация", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun matchCardLabelsUnavailableModelEstimateAsAnActionableDetail() {
        composeRule.setContent {
            FootballTheme {
                MatchCard(
                    match = tomorrowMatch(),
                    forecast = null,
                    misliQuote = null,
                    topazQuote = null,
                    onClick = {},
                )
            }
        }

        composeRule.onNodeWithText("Оценка Elo пока недоступна · открой матч, чтобы увидеть причину").assertIsDisplayed()
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
        composeRule.onNodeWithText("Показать расчёты и другие варианты").performScrollTo().performClick()
        composeRule.onNodeWithText("Границы Фреше по модельным вероятностям: 0–33%", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Зависимость матчей неизвестна", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("TOPAZ").performClick()
        composeRule.onNodeWithText("TOPAZ · 2 исхода").assertIsDisplayed()
        composeRule.onNodeWithText("3 исхода").performClick()
        composeRule.onNodeWithText("Экспресс из 3 исходов пока не собран").assertIsDisplayed()
    }

    @Test
    fun emptyCouponShowsModelReadinessAndLinksToMatches() {
        val match = tomorrowMatch()
        var openedMatches = false
        composeRule.setContent {
            FootballTheme {
                FootballCouponWorkspace(
                    matches = listOf(match),
                    modelForecastCount = 1,
                    misliOdds = emptyList(),
                    topazOdds = emptyList(),
                    candidates = emptyList(),
                    history = emptyList(),
                    checkedPicks = emptyList(),
                    onOpenMatches = { openedMatches = true },
                )
            }
        }

        composeRule.onNodeWithText("Готовых прогнозов: 1", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("без них показываются только исходы и их вероятности", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Посмотреть матчи").performScrollTo().performClick()
        assertTrue(openedMatches)
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

    @Test
    fun matchListShowsDateOnlyFixtureAndLetsUserOpenItsAnalysis() {
        val sourceDate = LocalDate.now(ZoneId.of("Asia/Baku")).plusDays(1).toString()
        val match = tomorrowMatch().copy(exactKickoff = false, eventDate = sourceDate, kickoff = sourceDate)
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

        composeRule.onNodeWithText("Source Home FC").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Ожидается · время начала не указано").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Оценка Elo пока недоступна", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Завтра").performClick()
        composeRule.onNodeWithText("Source Away FC").performScrollTo().performClick()
        assertEquals(match.id, selectedMatch)
    }

    @Test
    fun homeCountsAndShowsUpcomingDateOnlyFixtureWithoutInventingForecast() {
        val sourceDate = LocalDate.now(ZoneId.of("Asia/Baku")).toString()
        val match = tomorrowMatch().copy(exactKickoff = false, eventDate = sourceDate, kickoff = sourceDate)
        composeRule.setContent {
            FootballTheme {
                FootballHomeTabContent(
                    matches = listOf(match),
                    forecasts = emptyList(),
                    misliOdds = emptyList(),
                    topazOdds = emptyList(),
                    checkedPredictions = emptyList(),
                    syncBusy = false,
                    lastSyncLabel = "Обновлено сейчас",
                    syncDetails = "OpenFootball OK",
                    storeLoaded = true,
                    storeError = null,
                    onOpenMatches = {},
                    onOpenCoupons = {},
                    onSelectMatch = {},
                )
            }
        }

        composeRule.onNodeWithText("предстоящих матчей").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("0 / 1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Source Home FC").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Оценка Elo пока недоступна", substring = true).performScrollTo().assertIsDisplayed()
    }

    private fun tomorrowMatch(): Match {
        val kickoff = LocalDate.now(ZoneId.of("Asia/Baku")).plusDays(1)
            .atTime(19, 30).atZone(ZoneId.of("Asia/Baku")).toInstant().toString()
        return Match(901001, "Test League", "Source Home FC", "Source Away FC", kickoff, false, null, null, "test:901001")
    }
}
