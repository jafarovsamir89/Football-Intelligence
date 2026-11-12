package az.simplesoft.footballintelligence

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import az.simplesoft.footballintelligence.analytics.EloEngine
import az.simplesoft.footballintelligence.analytics.EloForecast
import az.simplesoft.footballintelligence.analytics.ForecastedMatch
import az.simplesoft.footballintelligence.analytics.InternationalMarketConsensus
import az.simplesoft.footballintelligence.analytics.InternationalMarketEngine
import az.simplesoft.footballintelligence.analytics.MarginMethod
import az.simplesoft.footballintelligence.analytics.ProbabilityEngine
import az.simplesoft.footballintelligence.data.BookmakerOddsSnapshot
import az.simplesoft.footballintelligence.data.InternationalOddsSnapshot
import az.simplesoft.footballintelligence.data.InternationalOddsSyncAttempt
import az.simplesoft.footballintelligence.data.InternationalOddsSyncState
import az.simplesoft.footballintelligence.data.MarketReferenceSnapshot
import az.simplesoft.footballintelligence.data.MarketReferenceSyncState
import az.simplesoft.footballintelligence.data.MOVIODDS_PROVIDER_ID
import az.simplesoft.footballintelligence.data.THE_ODDS_API_PROVIDER_ID
import az.simplesoft.footballintelligence.data.visibleInternationalOddsStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun MatchAnalysisTab(
    active: Match?,
    eloForecast: EloForecast?,
    activeMarketReference: MarketReferenceSnapshot?,
    marketReferenceSyncState: MarketReferenceSyncState?,
    misliOdds: List<BookmakerOddsSnapshot>,
    topazOdds: List<BookmakerOddsSnapshot>,
    onOpenTipsAudit: () -> Unit,
) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        if(active==null) { Text("Сначала выбери матч на вкладке «Матчи».") }
        else {
            Text("${active.home} — ${active.away}",style=MaterialTheme.typography.titleLarge)
            Text("${niceMatchTime(active)} · ${if(active.finished) "Завершён" else "До начала"}",style=MaterialTheme.typography.labelSmall)
            HorizontalDivider()
            Text("Экспериментальный Elo-baseline",fontWeight=FontWeight.Bold)
            val activeForecast=eloForecast
            if(active.finished) {
                Text("Предматчевый расчёт для завершённого матча не показывается.",style=MaterialTheme.typography.bodySmall)
            } else if(!active.exactKickoff) {
                Text("Для этой записи есть только дата без точного kickoff; предматчевый расчёт недоступен.",style=MaterialTheme.typography.bodySmall)
            } else if(activeForecast==null) {
                Text("Расчёт Elo по локальной истории загружается…",style=MaterialTheme.typography.bodySmall)
            } else {
                val probabilities=activeForecast.probabilities
                if(probabilities==null) {
                    Text("Недостаточно истории: ${activeForecast.matchesUsed} матчей в лиге, ${activeForecast.homeTeamMatches} для ${active.home} и ${activeForecast.awayTeamMatches} для ${active.away}. Для показа нужно минимум ${EloEngine.MIN_HISTORY_MATCHES_FOR_FORECAST} матчей в лиге и ${EloEngine.MIN_TEAM_MATCHES_FOR_FORECAST} для каждой команды.",style=MaterialTheme.typography.bodySmall)
                } else {
                    Text("Оценка 1X2: ${"%.1f".format(probabilities.home*100)}% / ${"%.1f".format(probabilities.draw*100)}% / ${"%.1f".format(probabilities.away*100)}%",fontWeight=FontWeight.SemiBold)
                    Text("На основе ${activeForecast.matchesUsed} прошлых матчей лиги; ${active.home}: ${activeForecast.homeTeamMatches}, ${active.away}: ${activeForecast.awayTeamMatches}.",style=MaterialTheme.typography.bodySmall)
                }
                Text("Источник: сохранённые результаты OpenLigaDB этой лиги до времени начала матча. Elo с фиксированными параметрами, без калибровки и walk-forward проверки; экспериментальная оценка, не рекомендация и не показатель уверенности.",style=MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
            Text("Рыночный ориентир 1X2",fontWeight=FontWeight.Bold)
            if(activeMarketReference==null) {
                Text("Нет свежей, однозначно сопоставленной оценки TipsAudit для этого матча. Устаревшие значения не используются. Последняя проверка: ${marketReferenceSyncState?.lastCheckedAtMillis?.let { DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) } ?: "не выполнялась"}.",style=MaterialTheme.typography.bodySmall)
            } else {
                val reference=activeMarketReference
                val marketProb=Triple(reference.homeProbability,reference.drawProbability,reference.awayProbability)
                val fairRead=DateTimeFormatter.ofPattern("dd.MM HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(reference.fairReadAtMillis))
                Text("TipsAudit fair reference: ${"%.1f".format(marketProb.first*100)}% / ${"%.1f".format(marketProb.second*100)}% / ${"%.1f".format(marketProb.third*100)}% · ориентирные цены ${reference.homeFairOdds} / ${reference.drawFairOdds} / ${reference.awayFairOdds} · источник считал в $fairRead · метод ${reference.methodologyVersion}.",style=MaterialTheme.typography.bodySmall)
                eloForecast?.probabilities?.let { model ->
                    Text("Расхождение Elo с рыночным ориентиром: ${marketPercentagePoints(model.home-marketProb.first)} / ${marketPercentagePoints(model.draw-marketProb.second)} / ${marketPercentagePoints(model.away-marketProb.third)} п.п. Это сравнение, не сигнал доходности.",style=MaterialTheme.typography.bodySmall)
                }
                (misliOdds+topazOdds).filter { it.matchId==active.id }.forEach { quote ->
                    val bookmakerProb=fairProbabilities(Quote(0,active.id,quote.providerId,quote.home,quote.draw,quote.away,quote.capturedAtMillis))
                    Text("${if(quote.providerId=="misli") "Misli" else "TOPAZ"} без маржи против ориентира: ${marketPercentagePoints(bookmakerProb.first-marketProb.first)} / ${marketPercentagePoints(bookmakerProb.second-marketProb.second)} / ${marketPercentagePoints(bookmakerProb.third-marketProb.third)} п.п. Линия той же БК, 1X2; рассчитано пропорциональным снятием маржи.",style=MaterialTheme.typography.bodySmall)
                }
                Text("Один внешний производный ориентир; это не независимый консенсус и не сырые цены Pinnacle, Betfair или bet365. Значения не используются как цены купона или для расчёта EV.",style=MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick={
                onOpenTipsAudit()
            }) { Text("Источник и методика: TipsAudit · бесплатное использование с атрибуцией") }

        }
    }
}

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
    internationalConsensus: List<InternationalMarketConsensus>,
    forecastedMatches: List<ForecastedMatch>,
    misliOdds: List<BookmakerOddsSnapshot>,
    topazOdds: List<BookmakerOddsSnapshot>,
    workerBusy: Boolean,
    showInternationalHistory: Boolean,
    onToggleInternationalHistory: () -> Unit,
    onOpenMoviOddsTerms: () -> Unit,
    onConfirmMoviOddsAge: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal=14.dp)) {
        Column(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            val moviOddsLines=internationalOdds.count { it.providerId==MOVIODDS_PROVIDER_ID }
            val checked=internationalOddsSyncState?.lastCheckedAtMillis?.let { DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) } ?: "ещё не проверен"
            Text("Мировой рынок · футбольный 1X2",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            Text("bet365 через независимый агрегатор MoviOdds · ${if(internationalAgeConfirmed) visibleInternationalOddsStatus(internationalOddsSyncState,moviOddsLines) else "ТРЕБУЕТСЯ 18+"} · проверка $checked · свежих линий $moviOddsLines${if(workerBusy) " · обновление выполняется" else ""}",style=MaterialTheme.typography.bodySmall)
            internationalOddsSyncState?.detail?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
            internationalOddsSyncState?.qualityCounts?.let { quality ->
                Text(
                    "Quality gate последнего ответа: принято ${quality.acceptedMatches}/${quality.receivedMatches}, отклонено ${quality.rejectedMatches}, устарело ${quality.staleMatches}, дубликаты ${quality.duplicateMatches}, не подтверждено ${quality.unverifiedMatches}. Это счётчики разбора, не рейтинг точности.",
                    style=MaterialTheme.typography.bodySmall,
                )
            }
            if(internationalOddsSyncHistory.isNotEmpty()) {
                Text("История проверок источника · последние ${internationalOddsSyncHistory.size} попыток за 30 дней",fontWeight=FontWeight.SemiBold)
                internationalOddsSyncHistory.take(5).forEach { attempt ->
                    val attemptTime=DateTimeFormatter.ofPattern("dd.MM HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(attempt.checkedAtMillis))
                    val attemptCounts=attempt.qualityCounts?.let { " · принято ${it.acceptedMatches}/${it.receivedMatches}, отклонено ${it.rejectedMatches}, устарело ${it.staleMatches}, дубликаты ${it.duplicateMatches}, не подтверждено ${it.unverifiedMatches}" }
                        ?: " · разбор ответа недоступен"
                    Text("$attemptTime · ${attempt.status}$attemptCounts",style=MaterialTheme.typography.bodySmall)
                }
                Text("Это журнал наблюдений, а не процент доступности или гарантия будущей работы источника.",style=MaterialTheme.typography.bodySmall)
            }
            val theOddsApiLines=internationalOdds.count { it.providerId==THE_ODDS_API_PROVIDER_ID }
            val theOddsApiChecked=theOddsApiSyncState?.lastCheckedAtMillis?.let { DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) } ?: "ещё не проверен"
            val theOddsApiStatus=if(theOddsApiConfigured) visibleInternationalOddsStatus(theOddsApiSyncState,theOddsApiLines) else "КЛЮЧ НЕ ВСТРОЕН В ЭТУ СБОРКУ"
            Text("The Odds API · EU · $theOddsApiStatus · проверка $theOddsApiChecked · свежих линий $theOddsApiLines",style=MaterialTheme.typography.bodySmall)
            theOddsApiSyncState?.detail?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
            if(theOddsApiSyncHistory.isNotEmpty()) {
                Text("The Odds API · последние проверки",fontWeight=FontWeight.SemiBold)
                theOddsApiSyncHistory.take(3).forEach { attempt ->
                    val time=DateTimeFormatter.ofPattern("dd.MM HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(attempt.checkedAtMillis))
                    Text("$time · ${attempt.status} · сохранено линий ${attempt.qualityCounts?.acceptedMatches ?: 0}",style=MaterialTheme.typography.bodySmall)
                }
            }
            if(!theOddsApiConfigured) {
                Text("Эта публичная/CI-сборка не содержит личный ключ. Персональная APK-сборка читает ключ только из игнорируемого local.properties; не публикуй её или сам APK-ключ в открытом репозитории.",style=MaterialTheme.typography.bodySmall)
            }
            Text("Для ограничения бесплатной квоты The Odds API проверяет максимум 12 активных лиг за суточный проход, один регион EU и рынок 1X2; очередность лиг вращается. Обновление остальных лиг растянуто по циклу.",style=MaterialTheme.typography.bodySmall)
            Text("Котировка считается свежей только при недавнем времени опроса рынка у источника (до 15 минут), независимо от того, когда изменился коэффициент. Приложение и источник сохраняются с разными отметками времени.",style=MaterialTheme.typography.bodySmall)
            Text("Данные bet365 поступают через MoviOdds, который не аффилирован с bet365. Условия MoviOdds требуют возраст 18+ и разрешают использование в собственных приложениях; точная доступность в Азербайджане на физическом телефоне ещё не проверена. Источник может быть неполным или недоступным.",style=MaterialTheme.typography.bodySmall)
            Text("Misli/TOPAZ остаются отдельными местными линиями. Международная цена не используется для виртуальных купонов Misli или TOPAZ. Цены и модели не являются доказательством преимущества или прибыли.",style=MaterialTheme.typography.bodySmall)
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
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=12.dp)) {
            if(internationalOdds.isEmpty()) {
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text("Сейчас нет подтверждённых свежих международных котировок.",fontWeight=FontWeight.SemiBold)
                            Text("Последние сохранённые снимки доступны ниже как исторические данные и не помечаются текущими ценами.",style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            items(internationalOdds,key={"${it.providerId}:${it.bookmakerId}:${it.eventId}"}) { quote ->
                val eventConsensus=internationalConsensus.firstOrNull { it.matchId!=null&&it.matchId==quote.matchId }
                val model=quote.matchId?.let { id -> forecastedMatches.firstOrNull { it.matchId==id } }
                val localMisli=quote.matchId?.let { id -> misliOdds.firstOrNull { it.matchId==id } }
                val localTopaz=quote.matchId?.let { id -> topazOdds.firstOrNull { it.matchId==id } }
                val internationalFair=ProbabilityEngine.removeMargin(quote.home,quote.draw,quote.away,MarginMethod.BASIC)
                val movement=InternationalMarketEngine.latestMovement(quote,internationalOddsHistory)
                val distinctBooks=eventConsensus?.distinctBookmakerCount ?: 1
                val pollTime=DateTimeFormatter.ofPattern("dd.MM HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(quote.providerPolledAtMillis))
                val captureTime=DateTimeFormatter.ofPattern("dd.MM HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(quote.capturedAtMillis))
                val priceTimes=listOf(quote.homePriceChangedAtMillis,quote.drawPriceChangedAtMillis,quote.awayPriceChangedAtMillis).joinToString(" / ") {
                    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))
                }
                val updateDescription=if(quote.providerId==THE_ODDS_API_PROVIDER_ID) "рынок обновлён $pollTime" else "времена цен 1/X/2: $priceTimes"
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(13.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        Text("${quote.league} · ${niceTime(Instant.ofEpochMilli(quote.kickoffMillis).toString())}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                        Text("${quote.homeTeam} — ${quote.awayTeam}",fontWeight=FontWeight.SemiBold)
                        val bookmakerName=internationalBookmakerName(quote.providerId,quote.bookmakerId)
                        val sourceName=internationalProviderName(quote.providerId)
                        Text("$bookmakerName · 1 ${"%.2f".format(quote.home)} / X ${"%.2f".format(quote.draw)} / 2 ${"%.2f".format(quote.away)}",fontWeight=FontWeight.Bold)
                        Text("Рыночные вероятности без маржи: ${"%.1f".format(internationalFair.home*100)} / ${"%.1f".format(internationalFair.draw*100)} / ${"%.1f".format(internationalFair.away*100)}%${if(distinctBooks>=2) " · линии ${distinctBooks} разных БК" else " · ориентир одной БК"}${quote.region?.let { " · регион источника $it" }.orEmpty()}",style=MaterialTheme.typography.bodySmall)
                        Text("Источник: $sourceName · $updateDescription · сохранено приложением $captureTime",style=MaterialTheme.typography.bodySmall)
                        Text(if(distinctBooks>=2) "Межкнижный ориентир: среднее вероятностей без маржи по $distinctBooks разным букмекерам; это описательное сравнение, не подтверждённый edge." else "Международный консенсус не рассчитан: доступен один букмекерский ориентир.",style=MaterialTheme.typography.labelSmall)
                        movement?.let {
                            Text("Изменение с предыдущего сохранённого снимка: 1 ${"%+.2f".format(it.homeDelta)} · X ${"%+.2f".format(it.drawDelta)} · 2 ${"%+.2f".format(it.awayDelta)} (дельта цены, не вероятности).",style=MaterialTheme.typography.bodySmall)
                        }
                        if(quote.matchId==null) {
                            Text("Нет уникального точного совпадения с локальным матчем; сравнение с Elo и местными котировками скрыто.",style=MaterialTheme.typography.bodySmall)
                        } else {
                            Text("Локальный матч ID ${quote.matchId} · сопоставление команд, порядка хозяев и времени строгое.",style=MaterialTheme.typography.labelSmall)
                            if(model==null) {
                                Text("Elo не рассчитан: для этого матча недостаточно доступной предматчевой истории.",style=MaterialTheme.typography.bodySmall)
                            } else {
                                val p=model.probabilities
                                Text("Elo ${"%.1f".format(p.home*100)} / ${"%.1f".format(p.draw*100)} / ${"%.1f".format(p.away*100)}% · Elo минус $bookmakerName без маржи: ${"%+.1f".format((p.home-internationalFair.home)*100)} / ${"%+.1f".format((p.draw-internationalFair.draw)*100)} / ${"%+.1f".format((p.away-internationalFair.away)*100)} п.п. · модель экспериментальная, не откалибрована.",style=MaterialTheme.typography.bodySmall)
                            }
                            listOf("Misli" to localMisli,"TOPAZ" to localTopaz).forEach { (name,local) ->
                                if(local==null) Text("$name: нет свежей локальной линии по этому матчу.",style=MaterialTheme.typography.bodySmall)
                                else {
                                    val localFair=ProbabilityEngine.removeMargin(local.home,local.draw,local.away,MarginMethod.BASIC)
                                    val localCaptured=DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(local.capturedAtMillis))
                                    Text("$name ${"%.2f".format(local.home)}/${"%.2f".format(local.draw)}/${"%.2f".format(local.away)} · без маржи ${"%.1f".format(localFair.home*100)}/${"%.1f".format(localFair.draw*100)}/${"%.1f".format(localFair.away*100)}% · Δ к $bookmakerName ${"%+.1f".format((localFair.home-internationalFair.home)*100)}/${"%+.1f".format((localFair.draw-internationalFair.draw)*100)}/${"%+.1f".format((localFair.away-internationalFair.away)*100)} п.п. · снимок $localCaptured. Котировки не смешиваются в купон.",style=MaterialTheme.typography.bodySmall)
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
                items(internationalOddsHistory.take(120),key={"history:${it.providerId}:${it.bookmakerId}:${it.eventId}:${it.capturedAtMillis}"}) { old ->
                    Text("${old.league} · ${internationalBookmakerName(old.providerId,old.bookmakerId)} (${internationalProviderName(old.providerId)}) · ${old.homeTeam} — ${old.awayTeam} · ${"%.2f".format(old.home)}/${"%.2f".format(old.draw)}/${"%.2f".format(old.away)} · рынок опрошен ${DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(old.providerPolledAtMillis))} · снимок ${DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(old.capturedAtMillis))}",style=MaterialTheme.typography.bodySmall)
                }
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
    THE_ODDS_API_PROVIDER_ID -> bookmakerId.split('_').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
    else -> bookmakerId
}

private fun marketPercentagePoints(value: Double): String = "%.1f".format(value * 100.0)
