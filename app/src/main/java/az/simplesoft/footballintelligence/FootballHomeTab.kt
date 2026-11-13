package az.simplesoft.footballintelligence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import az.simplesoft.footballintelligence.analytics.ForecastedMatch
import az.simplesoft.footballintelligence.analytics.LoggedCouponPick
import az.simplesoft.footballintelligence.data.BookmakerOddsSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val BAKU_ZONE: ZoneId = ZoneId.of("Asia/Baku")

@Composable
internal fun FootballHomeTabContent(
    matches: List<Match>,
    forecasts: List<ForecastedMatch>,
    misliOdds: List<BookmakerOddsSnapshot>,
    topazOdds: List<BookmakerOddsSnapshot>,
    misliSyncState: BookmakerSyncState? = null,
    topazSyncState: BookmakerSyncState? = null,
    checkedPredictions: List<LoggedCouponPick>,
    syncBusy: Boolean,
    lastSyncLabel: String,
    syncSummaryDetail: String = "Сохранённая база доступна офлайн.",
    syncHealthy: Boolean? = null,
    syncDetails: String,
    storeLoaded: Boolean,
    storeError: String?,
    onOpenMatches: () -> Unit,
    onOpenCoupons: () -> Unit,
    onSelectMatch: (Int) -> Unit,
) {
    val now = rememberClockMinute()
    val forecastById = remember(forecasts) { forecasts.associateBy { it.matchId } }
    val upcoming = remember(matches, now) {
        upcomingFixtures(matches, now, BAKU_ZONE)
    }
    val today = remember(now) { Instant.ofEpochMilli(now).atZone(BAKU_ZONE).toLocalDate() }
    val todayCount = upcoming.count { fixtureLocalDate(it, BAKU_ZONE) == today }
    val freshMisliOdds = remember(misliOdds, now) { misliOdds.filter { isFreshBookmakerQuote(it, now) } }
    val freshTopazOdds = remember(topazOdds, now) { topazOdds.filter { isFreshBookmakerQuote(it, now) } }
    val forecastReadyIds = remember(forecasts) { forecasts.mapTo(mutableSetOf()) { it.matchId } }
    val featured = remember(upcoming, forecastReadyIds) { featuredHomeMatches(upcoming, forecastReadyIds) }
    val nextAnalysisMatch = featured.firstOrNull()
    val nextAnalysisForecast = nextAnalysisMatch?.let { forecastById[it.id] }
    val featuredIncludesForecast = featured.any { it.id in forecastReadyIds }
    val checked = checkedPredictions.asSequence().filter { it.finished && it.homeGoals != null && it.awayGoals != null }.take(3).toList()
    var showHowTo by remember { mutableStateOf(false) }
    var showSyncDetails by remember { mutableStateOf(matches.isEmpty()) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("FOOTBALL INTELLIGENCE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text("Матчи, прогнозы и цены", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale("ru")).format(today), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Практическая цель: увидеть расчёт П1 / X / П2, сравнить с реальной ценой если она есть, затем проверить исход по счёту.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (nextAnalysisMatch != null) {
                ElevatedCard(shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("Расчёт для ближайшего матча", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("${nextAnalysisMatch.home} — ${nextAnalysisMatch.away}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("${nextAnalysisMatch.league} · ${niceMatchTime(nextAnalysisMatch)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ProbabilityBar(nextAnalysisForecast)
                        Text("Реальные цены · П1 / X / П2", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OddsRow("misli", freshMisliOdds.firstOrNull { it.matchId == nextAnalysisMatch.id }, now)
                        OddsRow("topaz", freshTopazOdds.firstOrNull { it.matchId == nextAnalysisMatch.id }, now)
                        Text("Вероятности — экспериментальная оценка модели, не рекомендация. Коэффициент — цена букмекера, не вероятность.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { onSelectMatch(nextAnalysisMatch.id) }) { Text("Подробный разбор") }
                    }
                }
            }
            TextButton(onClick = { showHowTo = !showHowTo }) {
                Text(if (showHowTo) "Скрыть описание" else "Как читать расчёт")
            }
            if (showHowTo) {
                ElevatedCard(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Что вы видите", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("П1 / X / П2 — оценка модели по результатам, доступным до матча. Для неё нужны минимум 10 результатов лиги и по 3 матча каждой команды.", style = MaterialTheme.typography.bodySmall)
                        Text("Цены Misli/TOPAZ показываются только при свежей полной линии и точной связи с тем же матчем. Если источники не дали цену, она остаётся пустой.", style = MaterialTheme.typography.bodySmall)
                        Text("Виртуальные купоны не делают ставок. После доступного итогового счёта приложение проверяет сохранённые исходы; это не доказательство будущей прибыли.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        ElevatedCard(shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SourceStatus(
                    title = "Автоматическая синхронизация",
                    status = if (syncBusy) "Обновление" else lastSyncLabel,
                    detail = if (syncBusy) "Матчи и результаты обновляются в фоне" else syncSummaryDetail,
                    healthy = if (syncBusy) null else syncHealthy,
                )
                FilterChip(selected = showSyncDetails, onClick = { showSyncDetails = !showSyncDetails }, label = { Text(if (showSyncDetails) "Скрыть детали" else "Статус источников") })
                if (showSyncDetails) {
                    Text(syncDetails, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!storeLoaded) Text("Читаю сохранённую базу…", style = MaterialTheme.typography.bodySmall)
                    storeError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        val upcomingIds = remember(upcoming) { upcoming.map { it.id }.toSet() }
        val modelReadyCount = remember(forecasts, upcomingIds) {
            forecasts.asSequence().map { it.matchId }.filter { it in upcomingIds }.distinct().count()
        }
        val misliLinkedCount = remember(freshMisliOdds, upcomingIds) {
            freshMisliOdds.asSequence().mapNotNull { it.matchId }.filter { it in upcomingIds }.distinct().count()
        }
        val topazLinkedCount = remember(freshTopazOdds, upcomingIds) {
            freshTopazOdds.asSequence().mapNotNull { it.matchId }.filter { it in upcomingIds }.distinct().count()
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("Сегодня · Баку", todayCount.toString(), "предстоящих матчей", Modifier.weight(1f))
            MetricCard("Прогноз Elo", "$modelReadyCount / ${upcoming.size}", "готовых оценок 1X2", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("Линия Misli", misliLinkedCount.toString(), "матчей со свежей ценой", Modifier.weight(1f))
            MetricCard("Линия TOPAZ", topazLinkedCount.toString(), "матчей со свежей ценой", Modifier.weight(1f))
        }

        val misliOverview = remember(misliSyncState, misliLinkedCount, now) {
            homeOddsProviderOverview(misliSyncState, misliLinkedCount, now)
        }
        val topazOverview = remember(topazSyncState, topazLinkedCount, now) {
            homeOddsProviderOverview(topazSyncState, topazLinkedCount, now)
        }
        ElevatedCard(shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Проверка коэффициентов", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                SourceStatus("Misli · публичная линия", misliOverview.status, misliOverview.detail, misliOverview.healthy)
                SourceStatus("TOPAZ · публичная линия", topazOverview.status, topazOverview.detail, topazOverview.healthy)
                Text(
                    "Видимая страница проверяется автоматически при работе приложения. Android не гарантирует обновление коэффициентов в фоне; цена старше 30 минут не считается свежей.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(if (featuredIncludesForecast) "Матчи с готовым прогнозом" else "Ближайшие матчи", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = onOpenMatches) { Text("Все матчи") }
        }
        if (featuredIncludesForecast) {
            Text("Сначала показаны игры с оценкой Elo, затем остальные ближайшие матчи.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (featured.isEmpty()) {
            ElevatedCard(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        when {
                            !storeLoaded -> "Открываю сохранённую базу матчей…"
                            storeError != null -> storeError
                            matches.isEmpty() && syncBusy -> "Первый автоматический импорт уже идёт. Он загрузит доступные сезоны, результаты и расписание."
                            matches.isEmpty() -> "В этой установке база пока пустая. При подключении к интернету приложение само запросит доступные матчи и результаты."
                            else -> "В базе ${matches.size} матчей, завершённых: ${matches.count { it.finished }}. Сейчас нет ближайших будущих игр; история остаётся доступна для анализа моделей."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("Как пользоваться: откройте «Матчи», выберите игру и посмотрите её вероятности. В «Купонах» доступны виртуальные варианты; коэффициенты показываются только при наличии свежей линии источника.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onOpenMatches, modifier = Modifier.weight(1f)) { Text("Открыть матчи") }
                        OutlinedButton(onClick = onOpenCoupons, modifier = Modifier.weight(1f)) { Text("Виртуальные купоны") }
                    }
                }
            }
        } else {
            featured.forEach { match ->
                MatchCard(
                    match = match,
                    forecast = forecastById[match.id],
                    misliQuote = freshMisliOdds.firstOrNull { it.matchId == match.id },
                    topazQuote = freshTopazOdds.firstOrNull { it.matchId == match.id },
                    onClick = { onSelectMatch(match.id) },
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Результаты проверки", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = onOpenCoupons) { Text("Купоны") }
        }
        if (checked.isEmpty()) {
            Text("Проверенные исходы появятся после завершения матчей и получения итогового счёта.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            checked.forEach { pick ->
                val actual = scoreResult(Match(pick.matchId, "", "", "", "", true, pick.homeGoals, pick.awayGoals))
                val correct = actual == pick.outcome
                SourceStatus(
                    title = "${pick.homeTeam} — ${pick.awayTeam}",
                    status = "${labelOutcome(pick.outcome)} · ${if (correct) "совпало" else "не совпало"}",
                    detail = "Счёт ${pick.homeGoals}:${pick.awayGoals} · модель ${"%.0f".format(Locale.US, pick.modelProbability * 100)}%",
                    healthy = correct,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

internal fun featuredHomeMatches(upcoming: List<Match>, forecastMatchIds: Set<Int>, limit: Int = 3): List<Match> {
    require(limit >= 0)
    val (forecastReady, awaitingHistory) = upcoming.partition { it.id in forecastMatchIds }
    return (forecastReady + awaitingHistory).take(limit)
}
