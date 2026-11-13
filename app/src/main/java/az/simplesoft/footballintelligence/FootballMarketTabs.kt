package az.simplesoft.footballintelligence

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import az.simplesoft.footballintelligence.analytics.AdvancedPrediction
import az.simplesoft.footballintelligence.analytics.AdvancedPredictionEngine
import az.simplesoft.footballintelligence.analytics.CURRENT_ADVANCED_MODEL_VERSION
import az.simplesoft.footballintelligence.analytics.ForecastedMatch
import az.simplesoft.footballintelligence.analytics.InternationalMarketConsensus
import az.simplesoft.footballintelligence.analytics.InternationalMarketEngine
import az.simplesoft.footballintelligence.analytics.InternationalMarketEvaluationEngine
import az.simplesoft.footballintelligence.analytics.InternationalMarketEvaluationReport
import az.simplesoft.footballintelligence.analytics.MetricConfidenceInterval95
import az.simplesoft.footballintelligence.analytics.MarginMethod
import az.simplesoft.footballintelligence.analytics.OutcomeProbabilities
import az.simplesoft.footballintelligence.analytics.ProbabilityEngine
import az.simplesoft.footballintelligence.data.BookmakerOddsSnapshot
import az.simplesoft.footballintelligence.data.BookmakerOddsHistoryState
import az.simplesoft.footballintelligence.data.bookmakerOddsMovementPercent
import az.simplesoft.footballintelligence.data.summarizeBookmakerOddsHistory
import az.simplesoft.footballintelligence.data.InternationalOddsSnapshot
import az.simplesoft.footballintelligence.data.InternationalOddsQuoteSide
import az.simplesoft.footballintelligence.data.INTERNATIONAL_ODDS_FRESHNESS_MILLIS
import az.simplesoft.footballintelligence.data.InternationalMatchLinkBasis
import az.simplesoft.footballintelligence.data.InternationalOddsSyncAttempt
import az.simplesoft.footballintelligence.data.InternationalOddsSyncState
import az.simplesoft.footballintelligence.data.MarketReferenceSnapshot
import az.simplesoft.footballintelligence.data.MarketReferenceSyncState
import az.simplesoft.footballintelligence.data.MOVIODDS_PROVIDER_ID
import az.simplesoft.footballintelligence.data.THE_ODDS_API_PROVIDER_ID
import az.simplesoft.footballintelligence.data.internationalExchangeSpread
import az.simplesoft.footballintelligence.data.visibleInternationalOddsStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun MatchAnalysisTab(
    active: Match?,
    activePrediction: AdvancedPrediction?,
    activeMarketReference: MarketReferenceSnapshot?,
    marketReferenceSyncState: MarketReferenceSyncState?,
    misliOdds: List<BookmakerOddsSnapshot>,
    topazOdds: List<BookmakerOddsSnapshot>,
    internationalOdds: List<InternationalOddsSnapshot> = emptyList(),
    localOddsHistory: List<BookmakerOddsSnapshot> = emptyList(),
    onOpenTipsAudit: () -> Unit,
) {
    var showDetails by remember(active?.id) { mutableStateOf(false) }
    val activeForecast = activePrediction
    val nowMillis = rememberClockMinute()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (active == null) {
            Text("Выберите матч в списке.", style = MaterialTheme.typography.bodyLarge)
        } else {
            Text(active.league, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text("${active.home} — ${active.away}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("${bakuKickoff(active)} · ${if (active.finished) "Завершён" else "До начала"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            ElevatedCard(shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Вероятности · ${activeForecast?.modelName ?: "модель"}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val probabilities = activeForecast?.probabilities
                    if (active.finished) {
                        Text("Предматчевая оценка для завершённой игры не показывается.", style = MaterialTheme.typography.bodySmall)
                    } else if (!active.exactKickoff) {
                        Text("Для этой записи нет точного времени начала, поэтому предматчевый расчёт недоступен.", style = MaterialTheme.typography.bodySmall)
                    } else if (probabilities == null) {
                        if (activeForecast == null) {
                            Text("Считаю оценку по истории, доступной до начала матча…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            val missing = buildList {
                                if (activeForecast.historyMatchCount < AdvancedPredictionEngine.MINIMUM_LEAGUE_MATCHES) {
                                    add("лига ${activeForecast.historyMatchCount}/${AdvancedPredictionEngine.MINIMUM_LEAGUE_MATCHES}")
                                }
                                if (activeForecast.homeTeamMatchCount < AdvancedPredictionEngine.MINIMUM_MATCHES_PER_TEAM) {
                                    add("хозяева ${activeForecast.homeTeamMatchCount}/${AdvancedPredictionEngine.MINIMUM_MATCHES_PER_TEAM}")
                                }
                                if (activeForecast.awayTeamMatchCount < AdvancedPredictionEngine.MINIMUM_MATCHES_PER_TEAM) {
                                    add("гости ${activeForecast.awayTeamMatchCount}/${AdvancedPredictionEngine.MINIMUM_MATCHES_PER_TEAM}")
                                }
                            }
                            Text(activeForecast.fallbackReason ?: "Прогноз недоступен: недостаточно истории (${missing.joinToString()}).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        val forecast = ForecastedMatch(
                            matchId = active.id,
                            homeTeam = active.home,
                            awayTeam = active.away,
                            kickoffMillis = runCatching { Instant.parse(active.kickoff).toEpochMilli() }.getOrDefault(0L),
                            probabilities = probabilities,
                            modelVersion = activeForecast.modelVersion ?: CURRENT_ADVANCED_MODEL_VERSION,
                            competition = active.league,
                        )
                        ProbabilityBar(forecast)
                        AdvancedPredictionExplanation(activeForecast)
                    }
                }
            }

            ElevatedCard(shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Наблюдаемое изменение местных цен", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Только сохранённые снимки Misli/TOPAZ с точной связью по матчу. Это не официальная opening/closing линия и не CLV.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LocalOddsMovementSummary("Misli", "misli", active.id, localOddsHistory, nowMillis)
                    LocalOddsMovementSummary("TOPAZ", "topaz", active.id, localOddsHistory, nowMillis)
                }
            }

            ElevatedCard(shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Сравнение цен 1X2", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("П1 · X · П2", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val misli = misliOdds.firstOrNull { it.matchId == active.id }
                    val topaz = topazOdds.firstOrNull { it.matchId == active.id }
                    val model = activeForecast?.probabilities
                    OddsRow("misli", misli, nowMillis)
                    if (model != null && misli != null && isFreshBookmakerQuote(misli, nowMillis)) {
                        val fair = fairProbabilities(Quote(0, active.id, "Misli", misli.home, misli.draw, misli.away, misli.capturedAtMillis))
                        Text("Модель − Misli без маржи: ${marketPercentagePoints(model.home-fair.first)} / ${marketPercentagePoints(model.draw-fair.second)} / ${marketPercentagePoints(model.away-fair.third)} п.п.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LocalModelMarketGapNote("Misli", model, misli.home, misli.draw, misli.away)
                    }
                    OddsRow("topaz", topaz, nowMillis)
                    if (model != null && topaz != null && isFreshBookmakerQuote(topaz, nowMillis)) {
                        val fair = fairProbabilities(Quote(0, active.id, "TOPAZ", topaz.home, topaz.draw, topaz.away, topaz.capturedAtMillis))
                        Text("Модель − TOPAZ без маржи: ${marketPercentagePoints(model.home-fair.first)} / ${marketPercentagePoints(model.draw-fair.second)} / ${marketPercentagePoints(model.away-fair.third)} п.п.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LocalModelMarketGapNote("TOPAZ", model, topaz.home, topaz.draw, topaz.away)
                    }
                    if (model != null) {
                        Text("«+» значит, что модель оценивает исход выше вероятности рынка без маржи; «−» — ниже. Это расхождение для проверки, а не рекомендация: модель ещё не прошла проспективную калибровку и не подтверждает преимущество или прибыль.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val now = nowMillis
                    val international = internationalOdds.filter {
                        it.matchId == active.id && it.quoteSide == InternationalOddsQuoteSide.BACK &&
                            it.kickoffMillis > now && it.capturedAtMillis <= now + 60_000L &&
                            now - it.capturedAtMillis <= INTERNATIONAL_ODDS_FRESHNESS_MILLIS
                    }
                    if (international.isEmpty()) {
                        Text("Международный рынок · —", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        international.take(4).forEach { quote ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(quote.bookmakerId, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                Text("${"%.2f".format(Locale.US, quote.home)}  ${"%.2f".format(Locale.US, quote.draw)}  ${"%.2f".format(Locale.US, quote.away)}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            }
                            Text("Снимок ${bakuTime(quote.capturedAtMillis)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (model != null) {
                                val fair = fairProbabilities(Quote(0, active.id, quote.bookmakerId, quote.home, quote.draw, quote.away, quote.capturedAtMillis))
                                Text("Модель − рынок без маржи: ${marketPercentagePoints(model.home-fair.first)} / ${marketPercentagePoints(model.draw-fair.second)} / ${marketPercentagePoints(model.away-fair.third)} п.п.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    val fairReference = activeMarketReference?.let { Triple(it.homeProbability, it.drawProbability, it.awayProbability) }
                    if (model != null && fairReference != null) {
                        Text("Модель − рыночный ориентир: ${marketPercentagePoints(model.home - fairReference.first)} / ${marketPercentagePoints(model.draw - fairReference.second)} / ${marketPercentagePoints(model.away - fairReference.third)} п.п.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            ElevatedCard(shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Рыночный ориентир", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (activeMarketReference == null) {
                        Text("Свежей однозначно связанной оценки пока нет.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        val reference = activeMarketReference
                        Text("TipsAudit · ${"%.1f".format(Locale.US, reference.homeProbability * 100)} / ${"%.1f".format(Locale.US, reference.drawProbability * 100)} / ${"%.1f".format(Locale.US, reference.awayProbability * 100)}%", fontWeight = FontWeight.SemiBold)
                        Text("Производная оценка, не коэффициент конкретной БК.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { showDetails = !showDetails }) { Text(if (showDetails) "Скрыть подробности" else "Источники и методика") }
                    if (showDetails) {
                        Text("Модель использует сохранённые результаты лиги, доступные до времени начала. Оценка экспериментальная и ещё не проспективно откалибрована.", style = MaterialTheme.typography.bodySmall)
                        Text("Свежесть Misli/TOPAZ — 30 минут. Международная линия показывается только при точной локальной связи и свежем сохранённом снимке.", style = MaterialTheme.typography.bodySmall)
                        Text("${activeMarketReference?.let { "TipsAudit · ${it.methodologyVersion} · рассчитано ${bakuTime(it.fairReadAtMillis)}" } ?: "TipsAudit ещё не связал свежую линию с этим матчем"}. Расхождение описывает данные, не прибыль.", style = MaterialTheme.typography.bodySmall)
                        Text("Последняя проверка TipsAudit: ${marketReferenceSyncState?.lastCheckedAtMillis?.let(::bakuTime) ?: "не выполнялась"}.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onOpenTipsAudit) { Text("Открыть описание источника") }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalOddsMovementSummary(
    bookmakerName: String,
    providerId: String,
    matchId: Int,
    history: List<BookmakerOddsSnapshot>,
    nowMillis: Long,
) {
    val summary = summarizeBookmakerOddsHistory(history, providerId, matchId, nowMillis)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("$bookmakerName · наблюдений ${summary.snapshotCount}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        when (summary.state) {
            BookmakerOddsHistoryState.NO_MATCHED_HISTORY -> Text(
                "Нет сохранённой истории с точной связью по этому матчу.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BookmakerOddsHistoryState.AMBIGUOUS_EVENT_IDENTITY -> Text(
                "История не объединена: по видимым данным найдены разные варианты идентичности события.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BookmakerOddsHistoryState.CONFLICTING_CAPTURE -> Text(
                "У одного снимка найдены разные цены; движение не рассчитано.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BookmakerOddsHistoryState.SINGLE_SNAPSHOT -> {
                val only = requireNotNull(summary.firstSnapshot)
                Text("Первая сохранённая цена: ${localOddsVector(only)} · ${bakuTime(only.capturedAtMillis)}.", style = MaterialTheme.typography.bodySmall)
                Text("Изменение появится после второго сохранённого снимка.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BookmakerOddsHistoryState.MOVEMENT_AVAILABLE -> {
                val first = requireNotNull(summary.firstSnapshot)
                val latest = requireNotNull(summary.latestSnapshot)
                val movement = requireNotNull(bookmakerOddsMovementPercent(first, latest))
                Text("Первая сохранённая цена: ${localOddsVector(first)} · ${bakuTime(first.capturedAtMillis)}.", style = MaterialTheme.typography.bodySmall)
                val intermediate = summary.observations.drop(1).dropLast(1).takeLast(3)
                if (intermediate.isNotEmpty()) {
                    Text("Промежуточные снимки:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    intermediate.forEach { snapshot ->
                        Text("${bakuTime(snapshot.capturedAtMillis)} · ${localOddsVector(snapshot)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("Последний снимок: ${localOddsVector(latest)} · ${bakuTime(latest.capturedAtMillis)}.", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Изменение цены: 1 ${"%+.1f".format(Locale.US, movement.home)}% · X ${"%+.1f".format(Locale.US, movement.draw)}% · 2 ${"%+.1f".format(Locale.US, movement.away)}%.",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private fun localOddsVector(snapshot: BookmakerOddsSnapshot): String =
    "1 ${"%.2f".format(Locale.US, snapshot.home)} / X ${"%.2f".format(Locale.US, snapshot.draw)} / 2 ${"%.2f".format(Locale.US, snapshot.away)}"

@Composable
private fun LocalModelMarketGapNote(
    bookmaker: String,
    model: OutcomeProbabilities,
    homeOdds: Double,
    drawOdds: Double,
    awayOdds: Double,
) {
    val market = ProbabilityEngine.removeMargin(homeOdds, drawOdds, awayOdds, MarginMethod.BASIC)
    val gap = ProbabilityEngine.largestPositiveModelMarketGap(model, market)
    val text = if (gap == null) {
        "$bookmaker: нет положительного расхождения модели с рынком без маржи."
    } else {
        val outcome = when (gap.outcomeCode) {
            "H" -> "П1"
            "D" -> "X"
            else -> "П2"
        }
        "$bookmaker: наибольшая положительная разница — $outcome +${"%.1f".format(Locale.US, gap.deltaProbability * 100)} п.п."
    }
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AdvancedPredictionExplanation(forecast: AdvancedPrediction) {
    val probabilities = forecast.probabilities ?: return
    val isPrimary = forecast.modelVersion == CURRENT_ADVANCED_MODEL_VERSION
    val highest = listOf("П1" to probabilities.home, "X" to probabilities.draw, "П2" to probabilities.away)
        .maxBy { it.second }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("Как читать эту оценку", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Самая высокая оценка: ${highest.first} (${"%.1f".format(Locale.US, highest.second * 100)}%). Это наиболее вероятный исход по модели, а не совет о ставке.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (forecast.homeExpectedGoals != null && forecast.awayExpectedGoals != null) {
            Text(
                "Poisson оценивает голы: хозяева ${"%.2f".format(Locale.US, forecast.homeExpectedGoals)}, гости ${"%.2f".format(Locale.US, forecast.awayExpectedGoals)}. Вероятности 1X2 получаются из распределения возможных счетов.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "Основа: ${forecast.historyMatchCount} результатов лиги, доступных до начала; у команд ${forecast.homeTeamMatchCount} и ${forecast.awayTeamMatchCount} прошлых матчей.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (forecast.modelVersion == CURRENT_ADVANCED_MODEL_VERSION) {
            Text(
                AdvancedPredictionEngine.HISTORICAL_EVIDENCE_SUMMARY,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        forecast.fallbackReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(
            if (isPrimary) "Основная модель Poisson использует голы и показатели атаки/обороны той же лиги; старые результаты теряют половину веса за 365 дней. Внешний xG, составы и новости не используются. Исторический тест не заменяет будущую проверку."
            else "Используется резервная Elo-модель, потому что для основной Poisson не хватило истории. Составы и новости не используются; оценка остаётся экспериментальной.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Прогноз сохраняется до матча и автоматически сверяется с итоговым счётом.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun bakuKickoff(match: Match): String = if (!match.exactKickoff) niceMatchTime(match) else runCatching {
    DateTimeFormatter.ofPattern("dd.MM.yyyy · HH:mm", Locale("ru")).withZone(ZoneId.of("Asia/Baku")).format(Instant.parse(match.kickoff))
}.getOrDefault("Время уточняется")

private fun bakuTime(millis: Long): String = DateTimeFormatter.ofPattern("dd.MM HH:mm", Locale("ru")).withZone(ZoneId.of("Asia/Baku")).format(Instant.ofEpochMilli(millis))

@Composable
internal fun InternationalMarketTabContent(
    internationalAgeConfirmed: Boolean,
    theOddsApiConfigured: Boolean,
    internationalOddsSyncState: InternationalOddsSyncState?,
    internationalOddsSyncHistory: List<InternationalOddsSyncAttempt>,
    theOddsApiSyncState: InternationalOddsSyncState?,
    theOddsApiSyncHistory: List<InternationalOddsSyncAttempt>,
    internationalOdds: List<InternationalOddsSnapshot>,
    internationalOddsHistory: List<InternationalOddsSnapshot>,
    internationalMarketEvaluation: InternationalMarketEvaluationReport,
    internationalConsensus: List<InternationalMarketConsensus>,
    forecastedMatches: List<ForecastedMatch>,
    misliOdds: List<BookmakerOddsSnapshot>,
    topazOdds: List<BookmakerOddsSnapshot>,
    workerBusy: Boolean,
    showInternationalHistory: Boolean,
    onToggleInternationalHistory: () -> Unit,
    onOpenMoviOddsTerms: () -> Unit,
    onConfirmMoviOddsAge: () -> Unit,
    analyticsUpdating: Boolean = false,
) {
    var showProviderDetails by remember { mutableStateOf(false) }
    val nowMillis = rememberClockMinute()
    val currentInternationalOdds = remember(internationalOdds, nowMillis) {
        internationalOdds.filter {
            it.kickoffMillis > nowMillis && it.capturedAtMillis <= nowMillis + 60_000L &&
                nowMillis - it.capturedAtMillis <= INTERNATIONAL_ODDS_FRESHNESS_MILLIS
        }
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal=14.dp).testTag("internationalMarketList"),
        verticalArrangement=Arrangement.spacedBy(8.dp),
        contentPadding=PaddingValues(bottom=12.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                val moviOddsLines=currentInternationalOdds.count { it.providerId==MOVIODDS_PROVIDER_ID }
                val checked=internationalOddsSyncState?.lastCheckedAtMillis?.let { DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.of("Asia/Baku")).format(Instant.ofEpochMilli(it)) } ?: "ещё не проверен"
                val theOddsApiLines=currentInternationalOdds.count { it.providerId==THE_ODDS_API_PROVIDER_ID }
                val theOddsApiChecked=theOddsApiSyncState?.lastCheckedAtMillis?.let { DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.of("Asia/Baku")).format(Instant.ofEpochMilli(it)) } ?: "ещё не проверен"
                val theOddsApiStatus=if(theOddsApiConfigured) visibleInternationalOddsStatus(theOddsApiSyncState,theOddsApiLines) else "КЛЮЧ НЕ ВСТРОЕН В ЭТУ СБОРКУ"
                Text("Мировой рынок · футбольный 1X2",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                SourceStatus("bet365 · MoviOdds", if(internationalAgeConfirmed) visibleInternationalOddsStatus(internationalOddsSyncState,moviOddsLines) else "ТРЕБУЕТСЯ 18+", "$moviOddsLines свежих линий · проверено $checked${if(workerBusy) " · обновляется" else ""}", healthy = moviOddsLines > 0)
                Text("The Odds API · EU · $theOddsApiStatus · $theOddsApiLines свежих линий · проверено $theOddsApiChecked", style=MaterialTheme.typography.labelMedium)
                TextButton(onClick = { showProviderDetails = !showProviderDetails }) {
                    Text(if (showProviderDetails) "Скрыть детали источников" else "Подробности источников")
                }
                if(showProviderDetails) {
                    internationalOddsSyncState?.detail?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
                    internationalOddsSyncState?.qualityCounts?.let { quality ->
                        Text("Последний ответ: принято ${quality.acceptedMatches}/${quality.receivedMatches}, отклонено ${quality.rejectedMatches}, устарело ${quality.staleMatches}, дубликаты ${quality.duplicateMatches}, непроверено ${quality.unverifiedMatches}. Это счётчики разбора, не точности.", style=MaterialTheme.typography.bodySmall)
                    }
                    if(internationalOddsSyncHistory.isNotEmpty()) {
                        Text("Проверки MoviOdds · до пяти последних",fontWeight=FontWeight.SemiBold)
                        internationalOddsSyncHistory.take(5).forEach { attempt ->
                            val attemptTime=DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.of("Asia/Baku")).format(Instant.ofEpochMilli(attempt.checkedAtMillis))
                            Text("$attemptTime · ${attempt.status} · сохранено ${attempt.qualityCounts?.acceptedMatches ?: "—"}",style=MaterialTheme.typography.bodySmall)
                        }
                    }
                    theOddsApiSyncState?.detail?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
                    if(theOddsApiSyncHistory.isNotEmpty()) {
                        Text("Проверки The Odds API · до трёх последних",fontWeight=FontWeight.SemiBold)
                        theOddsApiSyncHistory.take(3).forEach { attempt ->
                            val time=DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.of("Asia/Baku")).format(Instant.ofEpochMilli(attempt.checkedAtMillis))
                            Text("$time · ${attempt.status} · ${attempt.qualityCounts?.acceptedMatches ?: 0} линий",style=MaterialTheme.typography.bodySmall)
                        }
                    }
                    if(!theOddsApiConfigured) Text("В этой сборке нет личного API-ключа; ключ используется только в закрытом личном APK.", style=MaterialTheme.typography.bodySmall)
                    Text("The Odds API: одна лига за проход раз в 2 часа, до 12 запросов в сутки, регион EU и рынок 1X2. Обновление WorkManager может задерживаться Android.",style=MaterialTheme.typography.bodySmall)
                    Text("Международные котировки не входят в купоны Misli/TOPAZ. Они считаются свежими только до 15 минут; оценка рынка не доказывает доходность.",style=MaterialTheme.typography.bodySmall)
                }
                if(!internationalAgeConfirmed) {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text("Международный источник доступен только пользователям 18+.",fontWeight=FontWeight.SemiBold)
                            Text("Подтвердите возраст один раз, чтобы включить автоматическое обновление bet365 через MoviOdds. Остальная футбольная база обновляется независимо.",style=MaterialTheme.typography.bodySmall)
                            TextButton(onClick=onOpenMoviOddsTerms) {
                                Text("Условия MoviOdds")
                            }
                            Button(onClick=onConfirmMoviOddsAge) { Text("Подтверждаю, мне 18 лет или больше") }
                        }
                    }
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("Предварительная проверка рынка и модели",fontWeight=FontWeight.SemiBold)
                    Text(
                        "Последняя сохранённая свежая 1X2-котировка до начала матча сравнивается с прогнозом модели, который уже существовал к моменту её получения. Счёт учитывается только после подтверждённого результата.",
                        style=MaterialTheme.typography.bodySmall,
                    )
                    if(analyticsUpdating) {
                        Text("Обновляю оценку рынка; предыдущие сводные показатели временно скрыты.",style=MaterialTheme.typography.bodySmall)
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MetricCard("Связано", internationalMarketEvaluation.quoteFixtureCount.toString(), "БК · матч", Modifier.weight(1f))
                            MetricCard("С моделью", internationalMarketEvaluation.pairedFixtureCount.toString(), "сопоставимых пар", Modifier.weight(1f))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MetricCard("Проверено", internationalMarketEvaluation.settledFixtureCount.toString(), "результатов", Modifier.weight(1f))
                            MetricCard("Ожидают", internationalMarketEvaluation.pendingFixtureCount.toString(), "результатов", Modifier.weight(1f))
                        }
                        Text(
                            "История ограничена последними 30 днями и 5 000 сохранёнными изменениями цен.",
                            style=MaterialTheme.typography.bodySmall,
                        )
                        if(internationalMarketEvaluation.bookmakerReports.isEmpty()) {
                            Text("Пока нет подходящих связанных предматчевых котировок для сравнения.",style=MaterialTheme.typography.bodySmall)
                        } else {
                            internationalMarketEvaluation.bookmakerReports.take(6).forEach { report ->
                                val metricText = if(report.settledFixtureCount == 0) {
                                    "без проверенного исхода: ${report.pendingFixtureCount}/${report.pairedFixtureCount} пар"
                                } else {
                                    "Brier рынок/модель ${report.marketBrierScore!!.formatScore()} / ${report.modelBrierScore!!.formatScore()} · Log Loss рынок/модель ${report.marketLogLoss!!.formatScore()} / ${report.modelLogLoss!!.formatScore()} · top-1 рынок/модель ${report.marketTopOutcomeAccuracy!!.formatPercent()} / ${report.modelTopOutcomeAccuracy!!.formatPercent()} · n=${report.settledFixtureCount}/${report.pairedFixtureCount}"
                                }
                                val providers=report.sourceProviderIds.joinToString(" + ") { internationalProviderName(it) }
                                ElevatedCard(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(internationalBookmakerName(THE_ODDS_API_PROVIDER_ID,report.bookmakerId), style=MaterialTheme.typography.titleSmall, fontWeight=FontWeight.SemiBold)
                                        Text("$providers · медиана до начала ${report.medianHoursBeforeKickoff.formatLeadTime()}",style=MaterialTheme.typography.labelSmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(metricText,style=MaterialTheme.typography.bodySmall)
                                    }
                                }
                                val confidenceText = if(report.settledFixtureCount >= InternationalMarketEvaluationEngine.MIN_SETTLED_FIXTURES_FOR_INTERVALS) {
                                    "95% парный bootstrap, Δ модель−рынок: Brier ${report.eloMinusMarketBrier95!!.formatScoreInterval()} · Log Loss ${report.eloMinusMarketLogLoss95!!.formatScoreInterval()} · top-1 ${report.eloMinusMarketTopAccuracy95!!.formatPercentInterval()}"
                                } else {
                                    "95% интервалы пока недоступны: нужно ≥${InternationalMarketEvaluationEngine.MIN_SETTLED_FIXTURES_FOR_INTERVALS} завершённых матчей, сейчас ${report.settledFixtureCount}."
                                }
                                Text(confidenceText,style=MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text(
                            "Знак Δ модель−рынок: для Brier и Log Loss отрицательное значение означает меньшую ошибку модели; для top-1 положительное — больше правильных исходов модели.",
                            style=MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Интервалы — парная выборка матчей bootstrap, предполагающая независимость матчей; повторяющиеся команды и туры могут занижать неопределённость. Эти описательные метрики не означают прибыль и не подтверждают превосходство модели. История цен ограничена хранением источника.",
                            style=MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        if(currentInternationalOdds.isEmpty()) {
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text("Сейчас нет подтверждённых свежих международных котировок.",fontWeight=FontWeight.SemiBold)
                        Text("Последние сохранённые снимки доступны ниже как исторические данные и не помечаются текущими ценами.",style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        items(currentInternationalOdds,key={"${it.providerId}:${it.bookmakerId}:${it.eventId}:${it.market}:${it.sourceMarketKey}:${it.quoteSide}"}) { quote ->
            val isLay=quote.quoteSide==InternationalOddsQuoteSide.LAY
            val isBetfairExchange=quote.providerId==THE_ODDS_API_PROVIDER_ID&&quote.bookmakerId.startsWith("betfair_ex_")
            val eventConsensus=if(analyticsUpdating||isLay) null else internationalConsensus.firstOrNull { it.matchId!=null&&it.matchId==quote.matchId }
            val isConsensusSummaryQuote=eventConsensus!=null && quote.quoteSide==InternationalOddsQuoteSide.BACK &&
                quote.providerId==eventConsensus.representativeProviderId &&
                quote.bookmakerId==eventConsensus.representativeBookmakerId &&
                quote.eventId==eventConsensus.representativeEventId
            val model=if(isLay) null else quote.matchId?.let { id -> forecastedMatches.firstOrNull { it.matchId==id } }
            val localMisli=if(isLay) null else quote.matchId?.let { id -> misliOdds.firstOrNull { it.matchId==id } }
            val localTopaz=if(isLay) null else quote.matchId?.let { id -> topazOdds.firstOrNull { it.matchId==id } }
            val internationalFair=if(isLay) null else ProbabilityEngine.removeMargin(quote.home,quote.draw,quote.away,MarginMethod.BASIC)
            val movement=InternationalMarketEngine.latestMovement(quote,internationalOddsHistory)
            val distinctBooks=eventConsensus?.distinctBookmakerCount ?: 1
            val peer=currentInternationalOdds.firstOrNull { candidate ->
                candidate.providerId==quote.providerId&&candidate.bookmakerId==quote.bookmakerId&&candidate.eventId==quote.eventId&&
                    candidate.quoteSide!=quote.quoteSide&&candidate.market==quote.market
            }
            val exchangeSpread=peer?.let { paired ->
                if(isLay) internationalExchangeSpread(paired,quote) else internationalExchangeSpread(quote,paired)
            }
            val pollTime=DateTimeFormatter.ofPattern("dd.MM HH:mm:ss").withZone(ZoneId.of("Asia/Baku")).format(Instant.ofEpochMilli(quote.providerPolledAtMillis))
            val captureTime=DateTimeFormatter.ofPattern("dd.MM HH:mm:ss").withZone(ZoneId.of("Asia/Baku")).format(Instant.ofEpochMilli(quote.capturedAtMillis))
            val priceTimes=listOf(quote.homePriceChangedAtMillis,quote.drawPriceChangedAtMillis,quote.awayPriceChangedAtMillis).joinToString(" / ") {
                DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("Asia/Baku")).format(Instant.ofEpochMilli(it))
            }
            val updateDescription=if(quote.providerId==THE_ODDS_API_PROVIDER_ID) "рынок обновлён $pollTime" else "времена цен 1/X/2: $priceTimes"
            val sideLabel=if(isLay) "Lay" else "Back"
            val sourceMarket=when(quote.sourceMarketKey) {
                "h2h" -> "h2h"
                "h2h_lay" -> "h2h_lay"
                "group=40;market=40" -> "group 40 / market 40"
                else -> "не сохранён в старой записи"
            }
            val sourceLimits=listOf("1" to quote.homeBetLimit,"X" to quote.drawBetLimit,"2" to quote.awayBetLimit)
                .mapNotNull { (label,value) -> value?.let { "$label ${"%.2f".format(it)}" } }.joinToString(" / ")
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(13.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text("${quote.league} · ${niceTime(Instant.ofEpochMilli(quote.kickoffMillis).toString())}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                    Text("${quote.homeTeam} — ${quote.awayTeam}",fontWeight=FontWeight.SemiBold)
                    val bookmakerName=internationalBookmakerName(quote.providerId,quote.bookmakerId)
                    val sourceName=internationalProviderName(quote.providerId)
                    Text("$bookmakerName · $sideLabel ($sourceMarket) · 1 ${"%.2f".format(quote.home)} / X ${"%.2f".format(quote.draw)} / 2 ${"%.2f".format(quote.away)}",fontWeight=FontWeight.Bold)
                    if(internationalFair==null) {
                        Text("Lay — цена против выбранного исхода; она не трактуется как back-коэффициент и не входит в рыночный консенсус или сравнение Elo.",style=MaterialTheme.typography.bodySmall)
                    } else {
                        val probabilityLabel=if(isBetfairExchange) "Нормированные implied вероятности Back (без поправки на спред/комиссию)" else "Рыночные вероятности без маржи"
                        Text("$probabilityLabel: ${"%.1f".format(internationalFair.home*100)} / ${"%.1f".format(internationalFair.draw*100)} / ${"%.1f".format(internationalFair.away*100)}%${if(distinctBooks>=2) " · линии ${distinctBooks} разных БК" else " · ориентир одной БК"}${quote.region?.let { " · регион источника $it" }.orEmpty()}",style=MaterialTheme.typography.bodySmall)
                    }
                    Text("Источник: $sourceName · рынок $sourceMarket · $updateDescription · сохранено приложением $captureTime",style=MaterialTheme.typography.bodySmall)
                    if(internationalFair!=null) {
                        if(eventConsensus==null) {
                            Text("Сводка методов недоступна без однозначной связи котировок с одним локальным матчем.",style=MaterialTheme.typography.labelSmall)
                        } else if(isConsensusSummaryQuote) {
                            val summaryBasis = if(distinctBooks>=2) {
                                "равное среднее по $distinctBooks уникальным ID букмекера; независимость источников не доказана"
                            } else {
                                "одна линия; это не межкнижный консенсус"
                            }
                            val basic=eventConsensus.probabilities
                            val power=eventConsensus.powerProbabilities
                            val shin=eventConsensus.shinProbabilities
                            val shinText=shin?.let {
                                "Shin ${"%.1f".format(it.home*100)}/${"%.1f".format(it.draw*100)}/${"%.1f".format(it.away*100)}%"
                            } ?: "Shin недоступен: не все линии имеют положительную маржу"
                            Text(
                                "Сравнение снятия маржи · $summaryBasis: Basic ${"%.1f".format(basic.home*100)}/${"%.1f".format(basic.draw*100)}/${"%.1f".format(basic.away*100)}% · Power ${"%.1f".format(power.home*100)}/${"%.1f".format(power.draw*100)}/${"%.1f".format(power.away*100)}% · $shinText.",
                                style=MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    if(sourceLimits.isNotBlank()) {
                        Text("Лимит ставки, как передан источником (единица не указана): $sourceLimits. Это не оценка доступной ликвидности.",style=MaterialTheme.typography.bodySmall)
                    }
                    if(isBetfairExchange && !isLay) {
                        exchangeSpread?.let {
                            Text("Спред котировок Lay − Back: 1 ${"%.2f".format(it.home)} · X ${"%.2f".format(it.draw)} · 2 ${"%.2f".format(it.away)} (разница цен, не доходность).",style=MaterialTheme.typography.bodySmall)
                        } ?: Text("Спред Back/Lay не рассчитан: нет обеих сторон из одного подтверждённого снимка.",style=MaterialTheme.typography.bodySmall)
                        Text("Комиссия Betfair не передана источником и не вычтена; чистая биржевая доходность не рассчитывается.",style=MaterialTheme.typography.bodySmall)
                    }
                    movement?.let {
                        Text("Изменение с предыдущим снимком той же стороны: 1 ${"%+.2f".format(it.homeDelta)} · X ${"%+.2f".format(it.drawDelta)} · 2 ${"%+.2f".format(it.awayDelta)} (дельта цены).",style=MaterialTheme.typography.bodySmall)
                    }
                    if(isLay) {
                        Text(if(quote.matchId==null) "Нет единственного безопасного совпадения с локальным матчем." else "Сопоставлено с локальным матчем ID ${quote.matchId}; Lay-цены не используются для сравнения модели.",style=MaterialTheme.typography.bodySmall)
                    } else if(quote.matchId==null) {
                        Text("Нет единственного безопасного совпадения с локальным матчем; сравнение с моделью и местными котировками скрыто.",style=MaterialTheme.typography.bodySmall)
                    } else {
                        val linkDescription=when(quote.matchLinkBasis) {
                            InternationalMatchLinkBasis.EXACT_KICKOFF -> "точное время kickoff и порядок команд"
                            InternationalMatchLinkBasis.UNIQUE_DATE_ONLY -> "команды и UTC-дата совпали с единственной записью; локальный источник содержит только дату"
                            null -> "точность сопоставления не записана"
                        }
                        Text("Локальный матч ID ${quote.matchId} · $linkDescription.",style=MaterialTheme.typography.labelSmall)
                        if(model==null) {
                            Text("Модель не рассчитана: для этого матча недостаточно доступной предматчевой истории.",style=MaterialTheme.typography.bodySmall)
                        } else {
                            val p=model.probabilities
                            val fair=requireNotNull(internationalFair)
                            val comparisonBasis=if(isBetfairExchange) "нормированным Back до комиссии" else "вероятностями без маржи"
                            Text("${AdvancedPredictionEngine.displayName(model.modelVersion)} ${"%.1f".format(p.home*100)} / ${"%.1f".format(p.draw*100)} / ${"%.1f".format(p.away*100)}% · модель минус $bookmakerName $comparisonBasis: ${"%+.1f".format((p.home-fair.home)*100)} / ${"%+.1f".format((p.draw-fair.draw)*100)} / ${"%+.1f".format((p.away-fair.away)*100)} п.п. · модель экспериментальная, не откалибрована.",style=MaterialTheme.typography.bodySmall)
                        }
                        listOf("Misli" to localMisli,"TOPAZ" to localTopaz).forEach { (name,local) ->
                            if(local==null) Text("$name: нет свежей локальной линии по этому матчу.",style=MaterialTheme.typography.bodySmall)
                            else {
                                val localFair=ProbabilityEngine.removeMargin(local.home,local.draw,local.away,MarginMethod.BASIC)
                                val fair=requireNotNull(internationalFair)
                                val localCaptured=DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("Asia/Baku")).format(Instant.ofEpochMilli(local.capturedAtMillis))
                                Text("$name ${"%.2f".format(local.home)}/${"%.2f".format(local.draw)}/${"%.2f".format(local.away)} · без маржи ${"%.1f".format(localFair.home*100)}/${"%.1f".format(localFair.draw*100)}/${"%.1f".format(localFair.away*100)}% · Δ к $bookmakerName ${"%+.1f".format((localFair.home-fair.home)*100)}/${"%+.1f".format((localFair.draw-fair.draw)*100)}/${"%+.1f".format((localFair.away-fair.away)*100)} п.п. · снимок $localCaptured. Котировки не смешиваются в купон.",style=MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        item {
            TextButton(onClick={onToggleInternationalHistory()}) {
                Text(if(showInternationalHistory) "Скрыть историю изменений международных цен" else "Показать историю изменений международных цен (${internationalOddsHistory.size})")
            }
        }
        if(showInternationalHistory) {
            item { Text("Сохранённые изменения котировок · не являются текущими линиями; одинаковые повторные опросы не дублируются",fontWeight=FontWeight.SemiBold) }
            items(internationalOddsHistory.take(120),key={"history:${it.providerId}:${it.bookmakerId}:${it.eventId}:${it.market}:${it.sourceMarketKey}:${it.quoteSide}:${it.capturedAtMillis}"}) { old ->
                val side=if(old.quoteSide==InternationalOddsQuoteSide.LAY) "Lay" else "Back"
                val sourceLimits=listOf(old.homeBetLimit,old.drawBetLimit,old.awayBetLimit)
                    .mapNotNull { it?.let { limit -> "%.2f".format(limit) } }.takeIf { it.isNotEmpty() }
                    ?.joinToString("/") ?: "нет"
                Text("${old.league} · ${internationalBookmakerName(old.providerId,old.bookmakerId)} · $side (${old.sourceMarketKey}) · ${internationalProviderName(old.providerId)} · ${old.homeTeam} — ${old.awayTeam} · ${"%.2f".format(old.home)}/${"%.2f".format(old.draw)}/${"%.2f".format(old.away)} · лимиты источника $sourceLimits (единица неизвестна) · рынок опрошен ${DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.of("Asia/Baku")).format(Instant.ofEpochMilli(old.providerPolledAtMillis))} · снимок ${DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.of("Asia/Baku")).format(Instant.ofEpochMilli(old.capturedAtMillis))}",style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun internationalProviderName(providerId:String):String = when(providerId) {
    MOVIODDS_PROVIDER_ID -> "MoviOdds"
    THE_ODDS_API_PROVIDER_ID -> "The Odds API"
    else -> providerId
}

private fun internationalBookmakerName(providerId:String,bookmakerId:String):String = when(providerId) {
    MOVIODDS_PROVIDER_ID -> "bet365"
    THE_ODDS_API_PROVIDER_ID -> when(bookmakerId) {
        "betfair_ex_eu", "betfair_ex_uk", "betfair_ex_au" -> "Betfair Exchange"
        "betfair_sb_uk" -> "Betfair Sportsbook"
        "pinnacle" -> "Pinnacle"
        "bet365_au" -> "Bet365 (AU)"
        else -> bookmakerId.split('_').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
    }
    else -> bookmakerId
}

private fun Double.formatScore():String = String.format(Locale.ROOT,"%.3f",this)
private fun Double.formatPercent():String = String.format(Locale.ROOT,"%.1f%%",this*100.0)
private fun MetricConfidenceInterval95.formatScoreInterval():String =
    String.format(Locale.ROOT,"[%+.3f; %+.3f]",lower,upper)
private fun MetricConfidenceInterval95.formatPercentInterval():String =
    String.format(Locale.ROOT,"[%+.1f; %+.1f] п.п.",lower*100.0,upper*100.0)
private fun Double.formatLeadTime():String = when {
    this < 1.0/60.0 -> "<1 мин"
    this < 1.0 -> String.format(Locale.ROOT,"%.0f мин",this*60.0)
    else -> String.format(Locale.ROOT,"%.1f ч",this)
}

private fun marketPercentagePoints(value: Double): String = "%.1f".format(value * 100.0)
