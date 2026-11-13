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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import az.simplesoft.footballintelligence.analytics.AccumulatorSettlementState
import az.simplesoft.footballintelligence.analytics.AccumulatorPerformanceEngine
import az.simplesoft.footballintelligence.analytics.CouponSelectionBasis
import az.simplesoft.footballintelligence.analytics.EloEngine
import az.simplesoft.footballintelligence.analytics.LoggedCouponPick
import az.simplesoft.footballintelligence.analytics.SettledSinglePrediction
import az.simplesoft.footballintelligence.analytics.VirtualAccumulatorHistoryEntry
import az.simplesoft.footballintelligence.analytics.VirtualCouponCandidate
import az.simplesoft.footballintelligence.analytics.VirtualCouponBuilder
import az.simplesoft.footballintelligence.data.BookmakerOddsSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun FootballCouponWorkspace(
    matches: List<Match> = emptyList(),
    modelForecastCount: Int = 0,
    misliOdds: List<BookmakerOddsSnapshot>,
    topazOdds: List<BookmakerOddsSnapshot>,
    candidates: List<VirtualCouponCandidate>,
    history: List<VirtualAccumulatorHistoryEntry>,
    evaluationHistory: List<VirtualAccumulatorHistoryEntry>,
    checkedPicks: List<LoggedCouponPick>,
    onOpenMatches: () -> Unit = {},
) {
    var provider by remember { mutableStateOf("misli") }
    var legCount by remember { mutableIntStateOf(2) }
    var showCouponDetails by remember { mutableStateOf(false) }
    var showOddsDetails by remember { mutableStateOf(false) }
    val selectedName = if (provider == "misli") "Misli" else "TOPAZ"
    val nowMillis = rememberClockMinute()
    val upcomingFixtureCount = remember(matches, nowMillis) {
        matches.count { match ->
            !match.finished && match.exactKickoff &&
                runCatching { Instant.parse(match.kickoff).toEpochMilli() > nowMillis }.getOrDefault(false)
        }
    }
    val selectedOdds = (if (provider == "misli") misliOdds else topazOdds)
        .filter { isFreshBookmakerQuote(it, nowMillis) }
    val sizedCandidates = candidates.filter { it.providerId == provider && it.requestedLegCount == legCount }
        .sortedBy { candidate ->
            when (candidate.selectionBasis) {
                CouponSelectionBasis.MATCHED_FRESH_BOOKMAKER_ODDS -> 0
                CouponSelectionBasis.MODEL_RANKED -> 1
                CouponSelectionBasis.MODEL_MARKET_DISAGREEMENT -> 2
            }
        }
    val selectedCandidate = sizedCandidates.firstOrNull()
    val providerHistory = history.filter { it.providerId == provider }.take(8)
    val providerPicks = checkedPicks.filter { it.providerId == provider }.take(8)
    val providerEvaluationHistory = evaluationHistory.filter { it.providerId == provider }
    val settledSingles = checkedPicks.asSequence().filter { it.providerId == provider && it.finished }
        .mapNotNull { pick ->
            val home = pick.homeGoals ?: return@mapNotNull null
            val away = pick.awayGoals ?: return@mapNotNull null
            val actual = when { home > away -> "H"; home < away -> "A"; else -> "D" }
            SettledSinglePrediction(pick.matchId, actual == pick.outcome)
        }.toList()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Виртуальные купоны", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Исходы рассчитываются отдельно для каждой БК", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Оценку выгодности купона не рассчитываем: Elo ещё не прошёл независимую проверку на будущих матчах. Коэффициент — только цена букмекера.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("misli" to "Misli", "topaz" to "TOPAZ").forEach { (id, name) ->
                FilterChip(selected = provider == id, onClick = { provider = id }, label = { Text(name) }, modifier = Modifier.weight(1f))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(2, 3, 5).forEach { size ->
                    FilterChip(selected = legCount == size, onClick = { legCount = size }, label = { Text("$size исхода") }, modifier = Modifier.weight(1f))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(8, 10).forEach { size ->
                    FilterChip(selected = legCount == size, onClick = { legCount = size }, label = { Text("$size исходов") }, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.weight(1f))
            }
        }
        CouponPerformanceSummary(provider, legCount, providerEvaluationHistory, settledSingles)
        if (selectedCandidate == null) {
            ElevatedCard(shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Экспресс из $legCount исходов пока не собран", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val explanation = when {
                        upcomingFixtureCount == 0 -> "В локальной базе сейчас нет предстоящих матчей с точным временем. После автоматической загрузки расписания оценки появятся здесь."
                        modelForecastCount == 0 -> "Готовых прогнозов: 0. В базе есть $upcomingFixtureCount предстоящих матчей, но Elo требует минимум ${EloEngine.MIN_HISTORY_MATCHES_FOR_FORECAST} завершённых матчей лиги до kickoff и ${EloEngine.MIN_TEAM_MATCHES_FOR_FORECAST} предыдущих матчей у каждой команды."
                        modelForecastCount < legCount -> "Готовых прогнозов: $modelForecastCount. Для купона на $legCount исходов нужно минимум $legCount; пока прогнозов меньше."
                        else -> "Расчётов достаточно, но черновик купона пока недоступен. Открой список матчей и проверь детали оценок."
                    }
                    Text(explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Свежие букмекерские цены для модельного подбора не обязательны: без них показываются только исходы и их вероятности, без выдуманного коэффициента.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onOpenMatches) { Text("Посмотреть матчи") }
                }
            }
        } else {
            CouponCard(selectedCandidate, showDetails = showCouponDetails)
            val candidateHasFreshPrices = selectedCandidate.picks.size == selectedCandidate.requestedLegCount &&
                selectedCandidate.picks.all { pick ->
                    pick.bookmakerDecimalOdds != null && pick.bookmakerOddsCapturedAtMillis?.let { capturedAt ->
                        pick.kickoffMillis > nowMillis && capturedAt <= nowMillis + 60_000L &&
                            nowMillis - capturedAt <= VirtualCouponBuilder.BOOKMAKER_QUOTE_FRESHNESS_MILLIS
                    } == true
                }
            Text(
                when {
                    selectedCandidate.selectionBasis == CouponSelectionBasis.MATCHED_FRESH_BOOKMAKER_ODDS && !candidateHasFreshPrices -> "Снимок цен устарел. Сейчас показаны только вероятности модели; цены вернутся после следующей проверки."
                    selectedCandidate.selectionBasis == CouponSelectionBasis.MODEL_RANKED -> "Подбор по наибольшим модельным вероятностям."
                    selectedCandidate.selectionBasis == CouponSelectionBasis.MATCHED_FRESH_BOOKMAKER_ODDS -> "Подбор среди матчей с полной свежей линией этого букмекера."
                    else -> "Исследовательский подбор по сравнению модели и линии без маржи; это не EV."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { showCouponDetails = !showCouponDetails }) { Text(if (showCouponDetails) "Скрыть детали купона" else "Показать расчёты и другие варианты") }
            if (showCouponDetails) {
                sizedCandidates.drop(1).forEach { alternative ->
                    Text("Другой подбор · ${when (alternative.selectionBasis) {
                        CouponSelectionBasis.MODEL_RANKED -> "по модели"
                        CouponSelectionBasis.MATCHED_FRESH_BOOKMAKER_ODDS -> "по матчам с линиями"
                        CouponSelectionBasis.MODEL_MARKET_DISAGREEMENT -> "по расхождению модели и рынка"
                    }}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CouponCard(alternative, showDetails = true)
                }
            }
        }

        ElevatedCard(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${selectedName} · свежие коэффициенты", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("${selectedOdds.size} подтверждённых будущих линий · цены старше 30 минут не используются.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { showOddsDetails = !showOddsDetails }) { Text(if (showOddsDetails) "Свернуть цены" else "Подробнее о ценах") }
                if (showOddsDetails) {
                    selectedOdds.take(12).forEach { quote ->
                        Text("${quote.homeTeam} — ${quote.awayTeam} · ${quote.home} / ${quote.draw} / ${quote.away} · снимок ${bakuTime(quote.capturedAtMillis)}", style = MaterialTheme.typography.labelMedium)
                    }
                    if (selectedOdds.isEmpty()) Text("Подтверждённых цен сейчас нет; виртуальный купон показывает только вероятности модели.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Text("История купонов", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (providerHistory.isEmpty()) {
            Text("История этого букмекера появится после автоматического сохранения купонов.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            providerHistory.forEach { entry -> CouponHistoryCard(entry, selectedName) }
        }

        Text("Проверенные одиночные прогнозы", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (providerPicks.isEmpty()) {
            Text("Итоги появятся после завершения матчей и автоматической синхронизации результатов.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            providerPicks.forEach { pick ->
                val resolved = pick.finished && pick.homeGoals != null && pick.awayGoals != null
                val hit = if (resolved) scoreResult(Match(pick.matchId, "", "", "", "", true, pick.homeGoals, pick.awayGoals)) == pick.outcome else null
                SourceStatus(
                    title = "${pick.homeTeam} — ${pick.awayTeam}",
                    status = if (!resolved) "Ожидает результата" else "${labelOutcome(pick.outcome)} · ${if (hit == true) "совпало" else "не совпало"}",
                    detail = "Модель ${"%.1f".format(Locale.US, pick.modelProbability * 100)}% · ${if (resolved) "счёт ${pick.homeGoals}:${pick.awayGoals}" else "цена не гарантируется"}",
                    healthy = hit,
                )
            }
        }
        Text("Все купоны виртуальные. Вероятности не являются рекомендацией и не доказывают доходность.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CouponPerformanceSummary(
    providerId: String,
    legCount: Int,
    history: List<VirtualAccumulatorHistoryEntry>,
    settledSingles: List<SettledSinglePrediction>,
) {
    val saved = history.filter { it.legCount == legCount }
    ElevatedCard(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Проверка прошлых купонов · $legCount исхода", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("Показываются все сохранённые купоны, не только последние записи. Это история попаданий, не вероятность будущего выигрыша.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (saved.isEmpty()) {
                Text("Завершённых купонов пока нет. Приложение будет сверять виртуальные черновики с результатами матчей автоматически.", style = MaterialTheme.typography.bodySmall)
            } else {
                CouponSelectionBasis.values().forEach { basis ->
                    val cohort = saved.filter { it.selectionBasis == basis }
                    if (cohort.isNotEmpty()) {
                        val report = AccumulatorPerformanceEngine.evaluate(cohort, providerId, legCount, basis, settledSingles)
                        val pending = cohort.count {
                            it.settlement.state == AccumulatorSettlementState.PENDING ||
                                it.settlement.state == AccumulatorSettlementState.IN_PROGRESS
                        }
                        val needsReview = cohort.count {
                            it.settlement.state == AccumulatorSettlementState.SCHEDULE_CHANGED ||
                                it.settlement.state == AccumulatorSettlementState.INVALID
                        }
                        val heading = when (basis) {
                            CouponSelectionBasis.MODEL_RANKED -> "По вероятности модели"
                            CouponSelectionBasis.MATCHED_FRESH_BOOKMAKER_ODDS -> "По модели среди матчей со свежими линиями"
                            CouponSelectionBasis.MODEL_MARKET_DISAGREEMENT -> "По расхождению модели с линией"
                        }
                        val details = if (report.settledCoupons == 0) {
                            "Сохранено ${cohort.size}; завершено 0; ждут результата $pending; требуют проверки $needsReview"
                        } else {
                            val rate = report.couponHitRate?.let { " · ${"%.1f".format(Locale.US, it * 100)}% попаданий" }.orEmpty()
                            val interval = if (report.couponHitRate95Low != null && report.couponHitRate95High != null) {
                                " · 95% интервал ${"%.1f".format(Locale.US, report.couponHitRate95Low * 100)}–${"%.1f".format(Locale.US, report.couponHitRate95High * 100)}%"
                            } else ""
                            "${report.wonCoupons}/${report.settledCoupons} завершённых выиграно$rate$interval · ждут результата $pending; требуют проверки $needsReview"
                        }
                        Text("$heading: $details", style = MaterialTheme.typography.bodySmall)
                        if (report.matchingSinglePicks > 0 && report.singlePickHitRate != null && report.settledCoupons > 0) {
                            Text("Одиночные прогнозы на тех же матчах: ${report.matchingSinglePicks}, ${"%.1f".format(Locale.US, report.singlePickHitRate * 100)}% совпадений", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CouponHistoryCard(entry: VirtualAccumulatorHistoryEntry, providerName: String) {
    val state = when (entry.settlement.state) {
        AccumulatorSettlementState.PENDING -> "Ожидает результатов"
        AccumulatorSettlementState.IN_PROGRESS -> "Проверено ${entry.settlement.settledLegs}/${entry.legCount}"
        AccumulatorSettlementState.WON -> "Все исходы совпали"
        AccumulatorSettlementState.LOST -> "Есть несовпавший исход"
        AccumulatorSettlementState.SCHEDULE_CHANGED -> "Время матча изменено · проверка ожидается"
        AccumulatorSettlementState.INVALID -> "Запись требует проверки"
    }
    ElevatedCard(shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$providerName · ${entry.legCount} исхода", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(state, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (entry.combinedDecimalOdds != null) Text("Общий коэффициент ${"%.2f".format(Locale.US, entry.combinedDecimalOdds)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            entry.picks.take(3).forEach { pick ->
                Text("${pick.homeTeam} — ${pick.awayTeam} · ${labelOutcome(pick.outcome)} ${"%.0f".format(Locale.US, pick.modelProbability * 100)}%", style = MaterialTheme.typography.labelSmall)
            }
            if (entry.legCount > 3) Text("+ ещё ${entry.legCount - 3} исходов", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun bakuTime(millis: Long): String = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.of("Asia/Baku")).format(Instant.ofEpochMilli(millis))
