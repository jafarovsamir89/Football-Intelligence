package az.simplesoft.footballintelligence

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import az.simplesoft.footballintelligence.data.BookmakerOddsSnapshot
import az.simplesoft.footballintelligence.data.InternationalOddsSnapshot
import az.simplesoft.footballintelligence.data.InternationalOddsSyncState
import az.simplesoft.footballintelligence.data.MarketReferenceSyncState
import az.simplesoft.footballintelligence.data.MarketReferenceSnapshot
import az.simplesoft.footballintelligence.data.MarketReferenceStatus
import az.simplesoft.footballintelligence.data.ProviderSyncSummary
import az.simplesoft.footballintelligence.data.visibleBookmakerStatus
import az.simplesoft.footballintelligence.data.visibleInternationalOddsStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun FootballMatchesTabContent(
    syncStatusText: String,
    misliSyncState: BookmakerSyncState?,
    misliOdds: List<BookmakerOddsSnapshot>,
    topazSyncState: BookmakerSyncState?,
    topazOdds: List<BookmakerOddsSnapshot>,
    internationalAgeConfirmed: Boolean,
    internationalOddsSyncState: InternationalOddsSyncState?,
    internationalOdds: List<InternationalOddsSnapshot>,
    marketReferenceSyncState: MarketReferenceSyncState?,
    marketReferences: List<MarketReferenceSnapshot>,
    syncSummaries: List<ProviderSyncSummary>,
    syncBusy: Boolean,
    matches: List<Match>,
    storeLoaded: Boolean,
    storeError: String?,
    onSelectMatch: (Int) -> Unit,
    blue: Color,
) {
                Column(Modifier.fillMaxWidth().padding(horizontal=14.dp, vertical=8.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text("Автоматические футбольные данные",fontWeight=FontWeight.Bold)
                    Text(syncStatusText,style=MaterialTheme.typography.bodySmall)
                    val misliChecked=misliSyncState?.lastCheckedAtMillis?.let { DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) } ?: "ещё не было"
                    Text("Misli.az: ${visibleBookmakerStatus(misliSyncState?.status,misliSyncState?.lastCheckedAtMillis,misliOdds.size)} · снимок $misliChecked · актуальных будущих линий: ${misliOdds.size}${misliSyncState?.error?.let { " · $it" }.orEmpty()}",style=MaterialTheme.typography.bodySmall)
                    val topazChecked=topazSyncState?.lastCheckedAtMillis?.let { DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) } ?: "проверяется при открытии"
                    Text("TOPAZ/eTopaz: ${visibleBookmakerStatus(topazSyncState?.status,topazSyncState?.lastCheckedAtMillis,topazOdds.size)} · снимок $topazChecked · актуальных будущих линий: ${topazOdds.size}${topazSyncState?.error?.let { " · $it" }.orEmpty()}",style=MaterialTheme.typography.bodySmall)
                    Text("Pinnacle: UNAVAILABLE · официальный API требует запроса доступа и имеет переменную стоимость.",style=MaterialTheme.typography.bodySmall)
                    Text("Betfair Exchange: UNAVAILABLE · API требует аккаунт, App Key и сессию; live-доступ требует платной активации.",style=MaterialTheme.typography.bodySmall)
                    Text("bet365 через MoviOdds: ${if(internationalAgeConfirmed) visibleInternationalOddsStatus(internationalOddsSyncState,internationalOdds.size) else "ТРЕБУЕТСЯ 18+"} · свежих международных линий 1X2: ${internationalOdds.size}",style=MaterialTheme.typography.bodySmall)
                    val referenceChecked=marketReferenceSyncState?.lastCheckedAtMillis?.let { DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) } ?: "ещё не проверен"
                    val referenceStatus=when {
                        marketReferenceSyncState==null -> "ОЖИДАЕТ ПРОВЕРКИ"
                        marketReferenceSyncState.status==MarketReferenceStatus.ERROR -> "ОШИБКА"
                        marketReferences.isNotEmpty() -> "СВЕЖИЙ РЫНОЧНЫЙ ОРИЕНТИР"
                        else -> "СЕЙЧАС НЕДОСТУПЕН"
                    }
                    Text("TipsAudit · производная оценка рынка, не цена конкретной БК: $referenceStatus · проверено $referenceChecked · свежих 1X2: ${marketReferences.size}${marketReferenceSyncState?.detail?.let { " · $it" }.orEmpty()}",style=MaterialTheme.typography.bodySmall)
                    syncSummaries.forEach { summary ->
                        val checked=DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(summary.lastCheckedAtMillis))
                        val successful=summary.lastSuccessAtMillis?.let { DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) } ?: "ещё не было"
                        Text("${summary.providerId}: ${summary.status} · до ${summary.recordsStored} матчей · проверка $checked · успех $successful${summary.error?.let { " · $it" }.orEmpty()}",style=MaterialTheme.typography.bodySmall)
                    }
                    if(syncBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Источники: OpenFootball (CC0, доступные сезоны) и OpenLigaDB (динамический каталог). Загрузка выполняется в фоне и может быть отложена Android.",style=MaterialTheme.typography.bodySmall)
                }
                LazyColumn(contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    if(matches.isEmpty()) {
                        item {
                            val message=when {
                                !storeLoaded -> "Читаю сохранённую локальную базу…"
                                storeError!=null -> "Не удалось прочитать локальную базу: ${storeError}"
                                else -> "База пока пуста. Первый импорт начнётся автоматически при доступной сети; после загрузки данные останутся на устройстве."
                            }
                            Text(message,Modifier.padding(12.dp),style=MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        items(matches,key={it.id}) { m ->
                            ElevatedCard(onClick={onSelectMatch(m.id)},modifier=Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp)) {
                                    Text("${m.league} · ${niceMatchTime(m)}",style=MaterialTheme.typography.labelMedium,color=blue)
                                    Spacer(Modifier.height(5.dp))
                                    Text("${m.home} — ${m.away}",fontWeight=FontWeight.SemiBold)
                                    Text(matchStatusLabel(m),style=MaterialTheme.typography.bodySmall)
                                    Text("Источник: ${m.sourceName}",style=MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
}

@Composable
internal fun FootballResultsTabContent(recentResults: List<Match>) {
    Column(Modifier.fillMaxSize().padding(14.dp)) {
                Text("История результатов",style=MaterialTheme.typography.titleLarge)
                Text("Сохранённые завершённые матчи из подключённых источников. Данные остаются доступны офлайн.",style=MaterialTheme.typography.bodySmall)
                LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    items(recentResults,key={it.id}) { m ->
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("${m.league} · ${niceMatchTime(m)}",style=MaterialTheme.typography.labelSmall)
                                Text("${m.home} ${m.homeScore}:${m.awayScore} ${m.away}",fontWeight=FontWeight.SemiBold)
                                Text("${m.sourceName} · сезон ${m.season ?: "не указан"}",style=MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
    }
}

@Composable
internal fun FootballSettingsTabContent(
    fallbackCompetition: String,
    onFallbackCompetitionChange: (String) -> Unit,
    importingCsv: Boolean,
    csvImportMessage: String,
    onLaunchCsvImport: () -> Unit,
) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                  Text("Football Intelligence Lab",style=MaterialTheme.typography.titleLarge)
                  Text("Личная лаборатория исследования футбольных ставок, без оплаты и обязательных ключей.")
                  Text("Автоматические футбольные источники: OpenFootball и OpenLigaDB. Misli/TOPAZ читаются только из публичных видимых страниц при открытом приложении; bet365 доступен через MoviOdds при наличии подтверждённой свежей линии.")
                  Text("Дополнительные функции")
                  OutlinedTextField(value=fallbackCompetition,onValueChange=onFallbackCompetitionChange,label={Text("Необязательный код соревнования для CSV")},singleLine=true,modifier=Modifier.fillMaxWidth())
                  Button(onClick=onLaunchCsvImport,enabled=!importingCsv,modifier=Modifier.fillMaxWidth()) { Text(if(importingCsv) "Импорт…" else "Импорт CSV (дополнительно)") }
                  if(csvImportMessage.isNotBlank()) Text(csvImportMessage,style=MaterialTheme.typography.bodySmall)
                  Text("CSV не нужен для обычного запуска: матчи и результаты загружаются автоматически.",style=MaterialTheme.typography.bodySmall)
                  Text("Вероятности Elo экспериментальные и не калиброваны. Купоны виртуальные.")
                  Text("Система не выполняет ставки, не входит в букмекерские аккаунты и не обещает доход.")
                  Text("Открытые источники могут временно не отвечать. Уже сохранённые данные доступны без сети.")
    }
}
