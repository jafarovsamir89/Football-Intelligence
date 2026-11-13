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
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import java.time.LocalDate
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
    checkedPredictions: List<LoggedCouponPick>,
    syncBusy: Boolean,
    lastSyncLabel: String,
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
        matches.asSequence().filter { !it.finished && it.exactKickoff }
            .filter { runCatching { Instant.parse(it.kickoff).toEpochMilli() > now }.getOrDefault(false) }
            .sortedBy { runCatching { Instant.parse(it.kickoff).toEpochMilli() }.getOrDefault(Long.MAX_VALUE) }
            .toList()
    }
    val today = remember(now) { Instant.ofEpochMilli(now).atZone(BAKU_ZONE).toLocalDate() }
    val todayCount = upcoming.count { kickoffDate(it) == today }
    val freshMisliOdds = remember(misliOdds, now) { misliOdds.filter { isFreshBookmakerQuote(it, now) } }
    val freshTopazOdds = remember(topazOdds, now) { topazOdds.filter { isFreshBookmakerQuote(it, now) } }
    val featured = upcoming.take(3)
    val checked = checkedPredictions.asSequence().filter { it.finished && it.homeGoals != null && it.awayGoals != null }.take(3).toList()
    var showSyncDetails by remember { mutableStateOf(matches.isEmpty()) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("FOOTBALL INTELLIGENCE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text("Матчи и модельный обзор", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale("ru")).format(today), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Приложение само собирает результаты, оценивает вероятность победы, ничьей и поражения и сравнивает её со свежими линиями. Здесь только виртуальный анализ.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ElevatedCard(shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SourceStatus(
                    title = "Автоматическая синхронизация",
                    status = if (syncBusy) "Обновление" else lastSyncLabel,
                    detail = if (syncBusy) "Матчи и результаты обновляются в фоне" else "Сохранённые данные доступны офлайн",
                    healthy = if (syncBusy) null else true,
                )
                FilterChip(selected = showSyncDetails, onClick = { showSyncDetails = !showSyncDetails }, label = { Text(if (showSyncDetails) "Скрыть детали" else "Статус источников") })
                if (showSyncDetails) {
                    Text(syncDetails, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!storeLoaded) Text("Читаю сохранённую базу…", style = MaterialTheme.typography.bodySmall)
                    storeError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("Сегодня · Баку", todayCount.toString(), "матчей", Modifier.weight(1f))
            MetricCard("Коэффициенты", "${freshMisliOdds.size} / ${freshTopazOdds.size}", "Misli / TOPAZ", Modifier.weight(1f))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Ближайшие матчи", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = onOpenMatches) { Text("Все матчи") }
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

private fun kickoffDate(match: Match): LocalDate? = runCatching {
    Instant.parse(match.kickoff).atZone(BAKU_ZONE).toLocalDate()
}.getOrNull()
