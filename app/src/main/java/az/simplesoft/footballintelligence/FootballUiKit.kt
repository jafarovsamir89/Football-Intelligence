package az.simplesoft.footballintelligence

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import az.simplesoft.footballintelligence.analytics.ForecastedMatch
import az.simplesoft.footballintelligence.analytics.JointProbabilityBounds
import az.simplesoft.footballintelligence.analytics.VirtualCouponCandidate
import az.simplesoft.footballintelligence.analytics.VirtualCouponBuilder
import az.simplesoft.footballintelligence.data.BookmakerOddsSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val FootballNightColors = darkColorScheme(
    primary = Color(0xFFB7F36A),
    onPrimary = Color(0xFF1B2B0B),
    primaryContainer = Color(0xFF263B19),
    onPrimaryContainer = Color(0xFFD9F7B7),
    secondary = Color(0xFF83D9C2),
    onSecondary = Color(0xFF062019),
    background = Color(0xFF0B100E),
    onBackground = Color(0xFFE6ECE7),
    surface = Color(0xFF111815),
    onSurface = Color(0xFFE6ECE7),
    surfaceVariant = Color(0xFF1A231E),
    onSurfaceVariant = Color(0xFFB5C1B8),
    outline = Color(0xFF35433A),
    error = Color(0xFFFF8F86),
)

@Composable
internal fun FootballTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = FootballNightColors, content = content)
}

@Composable
internal fun rememberClockMinute(): Long = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(60_000L)
        value = System.currentTimeMillis()
    }
}.value

internal enum class FootballDestination(val label: String, val icon: ImageVector) {
    HOME("Главная", Icons.Default.Home),
    MATCHES("Матчи", Icons.Default.SportsSoccer),
    COUPONS("Купоны", Icons.Default.ConfirmationNumber),
    ANALYTICS("Аналитика", Icons.Default.QueryStats),
    SETTINGS("Настройки", Icons.Default.Settings),
}

@Composable
internal fun FootballBottomNavigation(
    selected: FootballDestination,
    onSelect: (FootballDestination) -> Unit,
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        FootballDestination.entries.filter { it != FootballDestination.SETTINGS }.forEach { destination ->
            NavigationBarItem(
                selected = selected == destination,
                onClick = { onSelect(destination) },
                icon = { androidx.compose.material3.Icon(destination.icon, contentDescription = destination.label) },
                label = { Text(destination.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
internal fun FootballTopBar(title: String, detail: String, showBack: Boolean, onBack: () -> Unit, onSettings: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
            } else {
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!showBack) {
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Настройки") }
            }
        }
    }
}

internal enum class FootballAnalyticsSection(val label: String) {
    MARKET("Мировой рынок"), BACKTEST("Бэктест"), MODELS("Модели"), RESULTS("Результаты"),
}

@Composable
internal fun FootballAnalyticsNavigation(selected: FootballAnalyticsSection, onSelect: (FootballAnalyticsSection) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FootballAnalyticsSection.entries.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { section ->
                    FilterChip(
                        selected = selected == section,
                        onClick = { onSelect(section) },
                        label = { Text(section.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
internal fun SourceStatus(title: String, status: String, detail: String, healthy: Boolean? = null) {
    val dotColor = when (healthy) {
        true -> MaterialTheme.colorScheme.primary
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.secondary
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(dotColor, CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text("$status · $detail", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun MetricCard(title: String, value: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ProbabilityBar(forecast: ForecastedMatch?) {
    if (forecast == null) {
        Text("Оценка Elo пока недоступна · открой матч, чтобы увидеть причину", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val values = listOf("П1" to forecast.probabilities.home, "X" to forecast.probabilities.draw, "П2" to forecast.probabilities.away)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            values.forEach { (label, value) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$label ${"%.0f".format(Locale.US, value * 100)}%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Box(Modifier.fillMaxWidth().height(5.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = .45f), RoundedCornerShape(4.dp))) {
                        Box(Modifier.fillMaxWidth(value.coerceIn(0.0, 1.0).toFloat()).height(5.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)))
                    }
                }
            }
        }
    }
}

@Composable
internal fun OddsRow(providerId: String, quote: BookmakerOddsSnapshot?, nowMillis: Long = System.currentTimeMillis()) {
    val provider = if (providerId == "misli") "Misli" else "TOPAZ"
    val fresh = isFreshBookmakerQuote(quote, nowMillis)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(provider, modifier = Modifier.width(54.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (fresh && quote != null) {
            Text(
                "${"%.2f".format(Locale.US, quote.home)}   ${"%.2f".format(Locale.US, quote.draw)}   ${"%.2f".format(Locale.US, quote.away)}",
                modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            )
            val age = ((nowMillis - quote.capturedAtMillis).coerceAtLeast(0L) / 60_000L).toInt()
            Text(if (age == 0) "сейчас" else "${age}м", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        } else {
            Text("—     —     —", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("нет линии", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun isFreshBookmakerQuote(quote: BookmakerOddsSnapshot?, nowMillis: Long = System.currentTimeMillis()): Boolean =
    quote != null && quote.capturedAtMillis <= nowMillis + 60_000L &&
        nowMillis - quote.capturedAtMillis <= VirtualCouponBuilder.BOOKMAKER_QUOTE_FRESHNESS_MILLIS && quote.kickoffMillis > nowMillis

private fun isFreshCouponPrice(pick: az.simplesoft.footballintelligence.analytics.VirtualCouponPick, nowMillis: Long): Boolean {
    val capturedAt = pick.bookmakerOddsCapturedAtMillis ?: return false
    return pick.bookmakerDecimalOdds != null && pick.kickoffMillis > nowMillis &&
        capturedAt <= nowMillis + 60_000L && nowMillis - capturedAt <= VirtualCouponBuilder.BOOKMAKER_QUOTE_FRESHNESS_MILLIS
}

@Composable
internal fun MatchCard(
    match: Match,
    forecast: ForecastedMatch?,
    misliQuote: BookmakerOddsSnapshot?,
    topazQuote: BookmakerOddsSnapshot?,
    onClick: () -> Unit,
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(match.league, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(bakuKickoffLabel(match), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(match.home, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("vs", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(match.away, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(matchStatusLabel(match), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ProbabilityBar(forecast)
            OddsRow("misli", misliQuote)
            OddsRow("topaz", topazQuote)
        }
    }
}

@Composable
internal fun CouponCard(candidate: VirtualCouponCandidate, modifier: Modifier = Modifier, showDetails: Boolean = false) {
    val nowMillis = rememberClockMinute()
    val allPricesFresh = candidate.picks.size == candidate.requestedLegCount && candidate.picks.all { isFreshCouponPrice(it, nowMillis) }
    val visibleCombinedOdds = candidate.combinedDecimalOdds.takeIf { allPricesFresh }
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${candidate.providerName} · ${candidate.requestedLegCount} исхода", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            visibleCombinedOdds != null -> "Цена собрана из свежих линий"
                            candidate.combinedDecimalOdds != null -> "Снимок цен устарел · вероятности модели сохранены"
                            else -> "Полного набора реальных цен нет"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(visibleCombinedOdds?.let { "%.2f".format(Locale.US, it) } ?: "—", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            candidate.picks.forEach { pick ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${pick.homeTeam} — ${pick.awayTeam}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${labelOutcome(pick.outcome)} · модель ${"%.1f".format(Locale.US, pick.modelProbability * 100)}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val freshPrice = pick.bookmakerDecimalOdds.takeIf { isFreshCouponPrice(pick, nowMillis) }
                    Text(freshPrice?.let { "%.2f".format(Locale.US, it) } ?: "—", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
            }
            if (showDetails) candidate.jointProbabilityBounds?.let { bounds: JointProbabilityBounds ->
                Text("Расчётная совместная вероятность: ${(bounds.lower * 100).roundToInt()}–${(bounds.upper * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun bakuKickoffLabel(match: Match): String = if (!match.exactKickoff) {
    runCatching { DateTimeFormatter.ofPattern("dd MMM", Locale("ru")).format(java.time.LocalDate.parse(match.eventDate ?: match.kickoff.take(10))) }.getOrDefault("Дата без времени")
} else {
    runCatching {
        DateTimeFormatter.ofPattern("dd MMM · HH:mm", Locale("ru"))
            .withZone(ZoneId.of("Asia/Baku"))
            .format(Instant.parse(match.kickoff))
    }.getOrDefault("Время уточняется")
}
