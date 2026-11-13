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
import az.simplesoft.footballintelligence.analytics.AccumulatorSettlement
import az.simplesoft.footballintelligence.analytics.AccumulatorSettlementState
import az.simplesoft.footballintelligence.analytics.AdvancedPrediction
import az.simplesoft.footballintelligence.analytics.ForecastedMatch
import az.simplesoft.footballintelligence.analytics.JointProbabilityBounds
import az.simplesoft.footballintelligence.analytics.OutcomeProbabilities
import az.simplesoft.footballintelligence.analytics.VirtualCouponCandidate
import az.simplesoft.footballintelligence.analytics.VirtualCouponPick
import az.simplesoft.footballintelligence.analytics.VirtualAccumulatorHistoryEntry
import az.simplesoft.footballintelligence.data.BookmakerOddsSnapshot
import az.simplesoft.footballintelligence.data.FootballNewsItem
import az.simplesoft.footballintelligence.data.OddsProviderStatus
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
        var selectedMatch: Int? = null
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
                    misliSyncState = BookmakerSyncState("misli", OddsProviderStatus.LIVE, now, now, 2, null),
                    topazSyncState = BookmakerSyncState("topaz", OddsProviderStatus.UNAVAILABLE, now, now, 0, "Нет будущих линий"),
                    checkedPredictions = emptyList(),
                    syncBusy = false,
                    lastSyncLabel = "Обновлено 12.11 10:00",
                    syncDetails = "OpenFootball OK",
                    storeLoaded = true,
                    storeError = null,
                    onOpenMatches = {},
                    onOpenCoupons = {},
                    onSelectMatch = { selectedMatch = it },
                )
            }
        }

        composeRule.onNodeWithText("Расчёт для ближайшего матча").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("${match.home} — ${match.away}").performScrollTo().assertIsDisplayed()
        listOf("П1 55%", "X 25%", "П2 20%").forEach { label ->
            assertTrue(composeRule.onAllNodesWithText(label).fetchSemanticsNodes().size >= 2)
        }
        assertTrue(composeRule.onAllNodesWithText("2.00   3.20   4.00").fetchSemanticsNodes().size >= 2)
        assertTrue(composeRule.onAllNodesWithText("нет линии").fetchSemanticsNodes().size >= 2)
        composeRule.onNodeWithText("Подробный разбор").performScrollTo().performClick()
        assertEquals(match.id, selectedMatch)
        composeRule.onNodeWithText("Как читать расчёт").performScrollTo().performClick()
        composeRule.onNodeWithText("Что вы видите").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("не рекомендация", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Матчи с готовым прогнозом").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("1 / 1").assertIsDisplayed()
        composeRule.onNodeWithText("Линия Misli").assertIsDisplayed()
        composeRule.onNodeWithText("Проверка коэффициентов").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("свежих точных совпадений: 1", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Нет будущих линий", substring = true).performScrollTo().assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("1", substring = false).fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("2", substring = false).fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText("Source Home FC").performScrollTo().assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("П1 55%").fetchSemanticsNodes().size >= 2)
        assertTrue(composeRule.onAllNodesWithText("Misli").fetchSemanticsNodes().size >= 2)
        assertTrue(composeRule.onAllNodesWithText("нет линии").fetchSemanticsNodes().size >= 2)
    }

    @Test
    fun homeNewsCardShowsHeadlineOnlyMatchContextAndOpensItsPrimarySource() {
        val match = tomorrowMatch()
        val now = System.currentTimeMillis()
        val item = FootballNewsItem(
            title = "${match.home} injury update before the weekend fixture",
            url = "https://news.example/story",
            sourceDomain = "news.example",
            language = "English",
            indexedAtMillis = now,
            capturedAtMillis = now,
        )
        var openedUrl: String? = null

        composeRule.setContent {
            FootballTheme {
                FootballHomeTabContent(
                    matches = listOf(match),
                    forecasts = emptyList(),
                    newsItems = listOf(item),
                    newsSyncSummary = az.simplesoft.footballintelligence.data.ProviderSyncSummary("gdelt_football_news", "OK", now, now, 1, null),
                    misliOdds = emptyList(),
                    topazOdds = emptyList(),
                    checkedPredictions = emptyList(),
                    syncBusy = false,
                    lastSyncLabel = "Обновлено",
                    syncDetails = "OpenFootball OK",
                    storeLoaded = true,
                    storeError = null,
                    onOpenMatches = {},
                    onOpenCoupons = {},
                    onSelectMatch = {},
                    onOpenNews = { openedUrl = it },
                )
            }
        }

        composeRule.onNodeWithText(item.title).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(item.url, openedUrl)
        composeRule.onNodeWithText("травмы · по заголовку", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Новости не меняют вероятности модели", substring = true).performScrollTo().assertIsDisplayed()
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

        composeRule.onNodeWithText("Практическая цель", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Как читать расчёт").performScrollTo().performClick()
        composeRule.onNodeWithText("Цены Misli/TOPAZ показываются только при свежей полной линии", substring = true).performScrollTo().assertIsDisplayed()
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
        composeRule.onNodeWithText("Статус источников").performScrollTo().performClick()
        composeRule.onNodeWithText("OpenLigaDB · ERROR · HTTP 503", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun matchAnalysisExplainsTheExactHistoryGapInsteadOfShowingBlankProbabilities() {
        composeRule.setContent {
            FootballTheme {
                MatchAnalysisTab(
                    active = tomorrowMatch(),
                    activePrediction = AdvancedPrediction(probabilities = null, modelVersion = null, modelName = "Недостаточно истории", historyMatchCount = 8, homeTeamMatchCount = 2, awayTeamMatchCount = 1, fallbackReason = "Poisson недоступен: недостаточно предматчевой истории (лига 8/50, хозяева 2/5, гости 1/5). Для Elo также нужны минимум 10 результатов лиги и 3 матча каждой команды."),
                    activeMarketReference = null,
                    marketReferenceSyncState = null,
                    misliOdds = emptyList(),
                    topazOdds = emptyList(),
                    onOpenTipsAudit = {},
                )
            }
        }

        composeRule.onNodeWithText("Poisson недоступен: недостаточно предматчевой истории", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("лига 8/50, хозяева 2/5, гости 1/5", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Для Elo также нужны минимум 10 результатов лиги", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun matchAnalysisExplainsWhyTheModelProducedItsProbabilityEstimate() {
        composeRule.setContent {
            FootballTheme {
                MatchAnalysisTab(
                    active = tomorrowMatch(),
                    activePrediction = AdvancedPrediction(
                        probabilities = OutcomeProbabilities(.55, .25, .20),
                        modelVersion = az.simplesoft.footballintelligence.analytics.CURRENT_ELO_MODEL_VERSION,
                        modelName = "Elo · резервная модель",
                        historyMatchCount = 22,
                        homeTeamMatchCount = 7,
                        awayTeamMatchCount = 9,
                        fallbackReason = "Poisson недоступен: недостаточно предматчевой истории (лига 22/50).",
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
        composeRule.onNodeWithText("Основа: 22 результатов лиги", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("22 результатов лиги, доступных до начала", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Используется резервная Elo-модель, потому что для основной Poisson не хватило истории. Составы и новости не используются; оценка остаётся экспериментальной.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun matchAnalysisShowsOnlyObservedLocalOddsMovementAndKeepsProviderHistorySeparate() {
        val match = tomorrowMatch()
        val kickoff = java.time.Instant.parse(match.kickoff).toEpochMilli()
        val captured = System.currentTimeMillis()
        val first = localQuote(match, "misli", "misli-event", captured - 9 * 60_000L, kickoff, 2.0, 3.0, 4.0)
        val intermediate = localQuote(match, "misli", "misli-event", captured - 6 * 60_000L, kickoff, 2.1, 3.1, 4.2)
        val laterIntermediate = localQuote(match, "misli", "misli-event", captured - 3 * 60_000L, kickoff, 2.15, 2.9, 4.3)
        val latest = localQuote(match, "misli", "misli-event", captured - 60_000L, kickoff, 2.2, 2.7, 4.4)
        val topaz = localQuote(match, "topaz", "topaz-event", captured - 60_000L, kickoff, 3.3, 4.4, 5.5)

        composeRule.setContent {
            FootballTheme {
                MatchAnalysisTab(
                    active = match,
                    activePrediction = null,
                    activeMarketReference = null,
                    marketReferenceSyncState = null,
                    misliOdds = listOf(latest),
                    topazOdds = listOf(topaz),
                    localOddsHistory = listOf(first, intermediate, laterIntermediate, latest, topaz),
                    onOpenTipsAudit = {},
                )
            }
        }

        composeRule.onNodeWithText("Наблюдаемое изменение местных цен").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Misli · наблюдений 4").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Изменение цены: 1 +10.0% · X -10.0% · 2 +10.0%.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Промежуточные снимки:").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("1 2.10 / X 3.10 / 2 4.20", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("TOPAZ · наблюдений 1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Это не официальная opening/closing линия и не CLV.", substring = true).performScrollTo().assertIsDisplayed()
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
                    activePrediction = AdvancedPrediction(
                        probabilities = OutcomeProbabilities(.55, .25, .20),
                        modelVersion = az.simplesoft.footballintelligence.analytics.CURRENT_ELO_MODEL_VERSION,
                        modelName = "Elo · резервная модель",
                        historyMatchCount = 22,
                        homeTeamMatchCount = 7,
                        awayTeamMatchCount = 9,
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

        composeRule.onNodeWithText("Расчёт модели пока недоступен · открой матч, чтобы увидеть причину").assertIsDisplayed()
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
                    evaluationHistory = emptyList(),
                    checkedPicks = emptyList(),
                )
            }
        }

        composeRule.onNodeWithText("Misli · 2 исхода").assertIsDisplayed()
        composeRule.onNodeWithText("Показать расчёты и другие варианты").performScrollTo().performClick()
        composeRule.onNodeWithText("Границы Фреше по модельным вероятностям: 0–33%", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Зависимость матчей неизвестна", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Оценку выгодности купона не рассчитываем", substring = true).performScrollTo().assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("Условный EV", substring = true).fetchSemanticsNodes().isEmpty())
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
                    evaluationHistory = emptyList(),
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
    fun couponWorkspaceShowsSettledHitRateAndSampleSize() {
        val now = System.currentTimeMillis()
        val history = listOf(
            VirtualAccumulatorHistoryEntry(
                id = 1,
                providerId = "misli",
                legCount = 2,
                capturedAtMillis = now,
                combinedDecimalOdds = null,
                jointProbabilityBounds = JointProbabilityBounds(.0, .3),
                settlement = AccumulatorSettlement(AccumulatorSettlementState.WON, 2, 0, 0),
                picks = listOf(
                    VirtualCouponPick(201, "Alpha FC", "Beta FC", now + 86_400_000, "H", .61),
                    VirtualCouponPick(202, "Gamma FC", "Delta FC", now + 172_800_000, "A", .48),
                ),
                selectionBasis = CouponSelectionBasis.MODEL_RANKED,
            ),
        )
        composeRule.setContent {
            FootballTheme {
                FootballCouponWorkspace(
                    misliOdds = emptyList(),
                    topazOdds = emptyList(),
                    candidates = emptyList(),
                    history = history,
                    evaluationHistory = history,
                    checkedPicks = emptyList(),
                )
            }
        }

        composeRule.onNodeWithText("Проверка прошлых купонов · 2 исхода").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("1/1 завершённых выиграно", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("95% интервал", substring = true).assertExists()
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
        assertTrue(composeRule.onAllNodesWithText("Расчёт модели пока недоступен", substring = true).fetchSemanticsNodes().isNotEmpty())
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
        assertTrue(composeRule.onAllNodesWithText("Расчёт модели пока недоступен", substring = true).fetchSemanticsNodes().size >= 2)
    }

    private fun tomorrowMatch(): Match {
        val kickoff = LocalDate.now(ZoneId.of("Asia/Baku")).plusDays(1)
            .atTime(19, 30).atZone(ZoneId.of("Asia/Baku")).toInstant().toString()
        return Match(901001, "Test League", "Source Home FC", "Source Away FC", kickoff, false, null, null, "test:901001")
    }

    private fun localQuote(
        match: Match,
        providerId: String,
        eventId: String,
        capturedAt: Long,
        kickoff: Long,
        home: Double,
        draw: Double,
        away: Double,
    ) = BookmakerOddsSnapshot(
        providerId = providerId,
        eventId = eventId,
        sourceUrl = if (providerId == "misli") "https://www.misli.az/idman-novleri/futbol" else "https://www.etopaz.az/",
        capturedAtMillis = capturedAt,
        kickoffMillis = kickoff,
        market = "1X2",
        homeTeam = match.home,
        awayTeam = match.away,
        home = home,
        draw = draw,
        away = away,
        eventDate = match.kickoff.take(10),
        timeZoneId = "Asia/Baku",
        matchId = match.id,
    )
}
