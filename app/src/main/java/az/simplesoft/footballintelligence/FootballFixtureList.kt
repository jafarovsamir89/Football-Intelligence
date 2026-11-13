package az.simplesoft.footballintelligence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import az.simplesoft.footballintelligence.analytics.ForecastedMatch
import az.simplesoft.footballintelligence.data.BookmakerOddsSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
internal fun FootballFixtureList(
    matches: List<Match>,
    forecasts: List<ForecastedMatch>,
    misliOdds: List<BookmakerOddsSnapshot>,
    topazOdds: List<BookmakerOddsSnapshot>,
    storeLoaded: Boolean,
    storeError: String?,
    onSelectMatch: (Int) -> Unit,
) {
    val baku = ZoneId.of("Asia/Baku")
    val now = rememberClockMinute()
    val today = Instant.ofEpochMilli(now).atZone(baku).toLocalDate()
    val forecastById = remember(forecasts) { forecasts.associateBy { it.matchId } }
    val quotesByKey = remember(misliOdds, topazOdds) {
        (misliOdds + topazOdds).filter { it.matchId != null }.groupBy { it.providerId to it.matchId!! }
    }
    val upcoming = remember(matches, now) {
        upcomingFixtures(matches, now, baku)
    }
    val leagueOptions = remember(upcoming) { listOf("Все лиги") + upcoming.map { it.league }.distinct().sorted() }
    var dateFilter by remember { mutableStateOf("Все") }
    var selectedLeague by remember { mutableStateOf("Все лиги") }
    var onlyWithOdds by remember { mutableStateOf(false) }
    var leagueMenuOpen by remember { mutableStateOf(false) }
    val filtered = upcoming.filter { match ->
        val date = fixtureLocalDate(match, baku)
        val matchesDate = when (dateFilter) {
            "Сегодня" -> date == today
            "Завтра" -> date == today.plusDays(1)
            "7 дней" -> date != null && !date.isBefore(today) && !date.isAfter(today.plusDays(7))
            else -> true
        }
        val hasOdds = listOf("misli", "topaz").any { provider ->
            quotesByKey[provider to match.id].orEmpty().any { isFreshBookmakerQuote(it, now) }
        }
        matchesDate && (selectedLeague == "Все лиги" || match.league == selectedLeague) && (!onlyWithOdds || hasOdds)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Матчи", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Точное время указано по Баку; у части матчей известна только дата.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf("Сегодня", "Завтра", "7 дней", "Все").chunked(2).forEach { rowFilters ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowFilters.forEach { filter ->
                    FilterChip(
                        selected = dateFilter == filter,
                        onClick = { dateFilter = filter },
                        label = { Text(filter) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { leagueMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selectedLeague, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = leagueMenuOpen, onDismissRequest = { leagueMenuOpen = false }) {
                leagueOptions.forEach { league ->
                    DropdownMenuItem(text = { Text(league) }, onClick = { selectedLeague = league; leagueMenuOpen = false })
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            FilterChip(selected = onlyWithOdds, onClick = { onlyWithOdds = !onlyWithOdds }, label = { Text("Есть котировки") })
        }
        if (filtered.isEmpty()) {
            val message = when {
                !storeLoaded -> "Загружаю локальную базу…"
                storeError != null -> "Не удалось прочитать базу: $storeError"
                onlyWithOdds -> "Для этих фильтров нет подтверждённых свежих коэффициентов."
                upcoming.isEmpty() && matches.isEmpty() -> "Матчи загружаются автоматически и сохраняются на устройстве для офлайн-доступа."
                else -> "Нет ближайших матчей для выбранных фильтров."
            }
            Text(message, Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(filtered, key = { it.id }) { match ->
                    MatchCard(
                        match = match,
                        forecast = forecastById[match.id],
                        misliQuote = quotesByKey["misli" to match.id]?.firstOrNull(),
                        topazQuote = quotesByKey["topaz" to match.id]?.firstOrNull(),
                        onClick = { onSelectMatch(match.id) },
                    )
                }
            }
        }
    }
}
