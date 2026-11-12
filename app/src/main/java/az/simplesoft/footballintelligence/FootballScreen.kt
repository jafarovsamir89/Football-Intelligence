package az.simplesoft.footballintelligence

import android.provider.OpenableColumns
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import az.simplesoft.footballintelligence.analytics.MarginMethod
import az.simplesoft.footballintelligence.analytics.ProbabilityEngine
import az.simplesoft.footballintelligence.analytics.EloEngine
import az.simplesoft.footballintelligence.analytics.InternationalMarketEngine
import az.simplesoft.footballintelligence.analytics.EloForecast
import az.simplesoft.footballintelligence.analytics.EloWalkForwardEngine
import az.simplesoft.footballintelligence.analytics.EloWalkForwardReport
import az.simplesoft.footballintelligence.analytics.EloChronologicalExperimentReport
import az.simplesoft.footballintelligence.analytics.GoalModelComparisonReport
import az.simplesoft.footballintelligence.analytics.CalibrationDiagnostics
import az.simplesoft.footballintelligence.analytics.CURRENT_ELO_MODEL_VERSION
import az.simplesoft.footballintelligence.analytics.HistoricalMatch
import az.simplesoft.footballintelligence.analytics.ProspectiveForecastEvaluator
import az.simplesoft.footballintelligence.analytics.historyAvailableAt
import az.simplesoft.footballintelligence.analytics.ForecastedMatch
import az.simplesoft.footballintelligence.analytics.VirtualCouponBuilder
import az.simplesoft.footballintelligence.analytics.ThreeWayDecimalOdds
import az.simplesoft.footballintelligence.analytics.WalkForwardTarget
import az.simplesoft.footballintelligence.analytics.AccumulatorSettlementState
import az.simplesoft.footballintelligence.analytics.AccumulatorPerformanceEngine
import az.simplesoft.footballintelligence.analytics.SettledSinglePrediction
import az.simplesoft.footballintelligence.analytics.CouponSelectionBasis
import az.simplesoft.footballintelligence.analytics.TeamDataQuality
import az.simplesoft.footballintelligence.data.FootballDataCsvImporter
import az.simplesoft.footballintelligence.data.FootballScreenRepository
import az.simplesoft.footballintelligence.data.MisliOddsProvider
import az.simplesoft.footballintelligence.data.TopazOddsProvider
import az.simplesoft.footballintelligence.data.visibleBookmakerStatus
import az.simplesoft.footballintelligence.data.visibleInternationalOddsStatus
import az.simplesoft.footballintelligence.data.MarketReferenceStatus
import androidx.work.WorkInfo
import androidx.work.WorkManager

private fun percentagePoints(value:Double):String = "%.1f".format(value*100.0)
private fun signedPercentagePoints(value:Double):String = "%+.1f".format(value*100.0)
private fun couponSelectionBasisLabel(basis:CouponSelectionBasis):String = when(basis) {
    CouponSelectionBasis.MODEL_RANKED -> "по вероятности модели"
    CouponSelectionBasis.MATCHED_FRESH_BOOKMAKER_ODDS -> "по вероятности модели среди матчей со свежими линиями"
    CouponSelectionBasis.MODEL_MARKET_DISAGREEMENT -> "по расхождению модели с линией"
}

@Composable fun FootballScreen(repository:FootballScreenRepository) {
    val activity = LocalContext.current as ComponentActivity
    val coroutineScope = rememberCoroutineScope()
    var storeLoadState by remember(repository) { mutableStateOf(FootballScreenStoreLoadState()) }
    var revision by remember { mutableIntStateOf(0) }
    var selected by remember { mutableIntStateOf(-1) }
    var tab by remember { mutableIntStateOf(0) }
    var importingCsv by remember { mutableStateOf(false) }
    var csvImportMessage by remember { mutableStateOf("") }
    var fallbackCompetition by remember { mutableStateOf("") }
    var selectedBacktestCode by remember { mutableStateOf("") }
    var backtestRunning by remember { mutableStateOf(false) }
    var backtestMessage by remember { mutableStateOf("") }
    var backtestReport by remember { mutableStateOf<EloWalkForwardReport?>(null) }
    var backtestReportCode by remember { mutableStateOf("") }
    var automaticBacktestRunning by remember { mutableStateOf(false) }
    var automaticBacktestMessage by remember { mutableStateOf("") }
    var automaticBacktestReport by remember { mutableStateOf<EloWalkForwardReport?>(null) }
    var automaticEloExperiment by remember { mutableStateOf<EloChronologicalExperimentReport?>(null) }
    var automaticGoalComparison by remember { mutableStateOf<GoalModelComparisonReport?>(null) }
    var automaticBacktestRevision by remember { mutableIntStateOf(-1) }
    var marginMethod by remember { mutableStateOf(MarginMethod.BASIC) }
    var showBookmakerQuotes by remember { mutableStateOf(false) }
    var showInternationalHistory by remember { mutableStateOf(false) }
    var internationalAgeConfirmed by remember(activity) { mutableStateOf(isMoviOddsAgeConfirmed(activity)) }
    val workManager=remember(activity) { WorkManager.getInstance(activity) }
    val oneTimeSync by remember(workManager) { workManager.getWorkInfosForUniqueWorkLiveData(FootballSyncScheduler.UNIQUE_ONCE_NAME) }.observeAsState(emptyList())
    val periodicSync by remember(workManager) { workManager.getWorkInfosForUniqueWorkLiveData(FootballSyncScheduler.UNIQUE_PERIODIC_NAME) }.observeAsState(emptyList())
    val marketReferenceOnceSync by remember(workManager) { workManager.getWorkInfosForUniqueWorkLiveData(TipsAuditMarketReferenceScheduler.UNIQUE_ONCE_NAME) }.observeAsState(emptyList())
    val marketReferencePeriodicSync by remember(workManager) { workManager.getWorkInfosForUniqueWorkLiveData(TipsAuditMarketReferenceScheduler.UNIQUE_PERIODIC_NAME) }.observeAsState(emptyList())
    val internationalOddsOnceSync by remember(workManager) { workManager.getWorkInfosForUniqueWorkLiveData(InternationalOddsMarketScheduler.UNIQUE_ONCE_NAME) }.observeAsState(emptyList())
    val internationalOddsPeriodicSync by remember(workManager) { workManager.getWorkInfosForUniqueWorkLiveData(InternationalOddsMarketScheduler.UNIQUE_PERIODIC_NAME) }.observeAsState(emptyList())
    LaunchedEffect(repository,revision,selected) {
        try {
            val snapshot=repository.readScreenSnapshot(selected.takeIf { it>=0 })
            storeLoadState=FootballScreenStoreLoadState(
                snapshot=snapshot,
                loaded=true,
                version=storeLoadState.version+1,
            )
        } catch(error:CancellationException) {
            throw error
        } catch(error:Exception) {
            storeLoadState=storeLoadState.copy(error=error.localizedMessage ?: "Не удалось прочитать локальную базу.")
        }
    }
    val storeSnapshot=storeLoadState.snapshot
    val syncSummaries=storeSnapshot.providerSyncSummaries
    val syncBusy=(oneTimeSync+periodicSync).any { it.state==WorkInfo.State.RUNNING }
    val syncQueued=oneTimeSync.any { it.state==WorkInfo.State.ENQUEUED || it.state==WorkInfo.State.BLOCKED }
    val activeSyncStage=(oneTimeSync+periodicSync).firstOrNull { it.state==WorkInfo.State.RUNNING }
        ?.progress?.getString("stage")
    val lastSuccessfulSync=syncSummaries.mapNotNull { it.lastSuccessAtMillis }.maxOrNull()
    val syncStatusText=when {
        storeLoadState.error!=null -> "Ошибка чтения локальной базы: ${storeLoadState.error}"
        !storeLoadState.loaded -> "Читаю сохранённую локальную базу…"
        syncBusy -> automaticSyncStageMessage(activeSyncStage)
        syncQueued -> "Автоматическая синхронизация стоит в очереди WorkManager; возможна системная задержка или ожидание сети. Локальная база доступна."
        lastSuccessfulSync!=null -> "Последняя синхронизация: ${DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(lastSuccessfulSync))}"
        else -> "Автоматическая синхронизация запланирована; ожидается сеть или первый успешный ответ источника."
    }
    LaunchedEffect(
        oneTimeSync.map { it.state to (it.runAttemptCount to it.progress.getString("stage")) },
        periodicSync.map { it.state to (it.runAttemptCount to it.progress.getString("stage")) },
    ) { revision++ }
    LaunchedEffect(marketReferenceOnceSync.map { it.state },marketReferencePeriodicSync.map { it.state }) { revision++ }
    LaunchedEffect(internationalOddsOnceSync.map { it.state },internationalOddsPeriodicSync.map { it.state }) { revision++ }
    val matches=storeSnapshot.matches
    val recentResults=storeSnapshot.recentResults
    val importedCompetitionCodes=storeSnapshot.importedCompetitionCodes
    val activeBacktestCode=selectedBacktestCode.takeIf { it in importedCompetitionCodes }
        ?: importedCompetitionCodes.firstOrNull().orEmpty()
    val active=matches.firstOrNull { it.id==selected }
    var eloForecast by remember { mutableStateOf<EloForecast?>(null) }
    LaunchedEffect(active?.id,revision) {
        val match=active
        val kickoff=match?.let { runCatching { Instant.parse(it.kickoff).toEpochMilli() }.getOrNull() }
        if(match==null||match.finished||kickoff==null||!match.exactKickoff) {
            eloForecast=null
        } else {
            val history=repository.historicalResultsBefore(match.league,kickoff)
            eloForecast=withContext(Dispatchers.Default) {
                EloEngine.forecast(history,kickoff,match.home,match.away)
            }
        }
    }
    val quoteList=storeSnapshot.selectedQuotes
    val pickList=storeSnapshot.selectedPicks
    val tickets=storeSnapshot.tickets
    val ticketMatches=storeSnapshot.ticketMatches
    val oddsProviders=remember { listOf(MisliOddsProvider(),TopazOddsProvider()) }
    val misliSyncState=storeSnapshot.misliSyncState
    val misliOdds=storeSnapshot.misliOdds
    val topazSyncState=storeSnapshot.topazSyncState
    val topazOdds=storeSnapshot.topazOdds
    val internationalOddsSyncState=storeSnapshot.internationalOddsSyncState
    val internationalOddsSyncHistory=storeSnapshot.internationalOddsSyncHistory
    val internationalOdds=storeSnapshot.internationalOdds
    val internationalOddsHistory=storeSnapshot.internationalOddsHistory
    val marketReferenceSyncState=storeSnapshot.marketReferenceSyncState
    val marketReferences=storeSnapshot.marketReferences
    val activeMarketReference=remember(active?.id,marketReferences) { marketReferences.firstOrNull { it.matchId==active?.id } }
    val historicalByLeague=storeSnapshot.historicalByLeague
    val matchForecastKey=remember(matches) {
        matches.map { listOf(it.id,it.league,it.home,it.away,it.kickoff,it.finished,it.homeScore,it.awayScore,it.exactKickoff,it.availableAtMillis) }.hashCode()
    }
    val oddsForecastKey=remember(misliOdds,topazOdds) {
        (misliOdds+topazOdds).map { listOf(it.providerId,it.matchId,it.kickoffMillis,it.home,it.draw,it.away,it.capturedAtMillis) }.hashCode()
    }
    var forecastedMatches by remember { mutableStateOf<List<ForecastedMatch>>(emptyList()) }
    val internationalConsensus=remember(internationalOdds) { InternationalMarketEngine.consensus(internationalOdds) }
    LaunchedEffect(matchForecastKey,oddsForecastKey,storeLoadState.version) {
        val matchSnapshot=matches
        val oddsSnapshot=misliOdds+topazOdds
        val historySnapshot=historicalByLeague
        val now=System.currentTimeMillis()
        forecastedMatches=withContext(Dispatchers.Default) {
            val linkedOddsMatchIds=oddsSnapshot.mapNotNull { it.matchId }.toSet()
            val futureMatches=matchSnapshot.filter { !it.finished&&it.exactKickoff&&TeamDataQuality.isValidFixture(it.home,it.away) }
                .sortedBy { runCatching { Instant.parse(it.kickoff).toEpochMilli() }.getOrDefault(Long.MAX_VALUE) }
            val orderedMatches=(futureMatches.filter { it.id in linkedOddsMatchIds }+futureMatches.filterNot { it.id in linkedOddsMatchIds })
                .distinctBy { it.id }.take(80)
            orderedMatches.mapNotNull { match ->
                val kickoff=runCatching { Instant.parse(match.kickoff).toEpochMilli() }.getOrNull() ?: return@mapNotNull null
                if(kickoff<=now) return@mapNotNull null
                val history=historyAvailableAt(historySnapshot[match.league].orEmpty(),now)
                val forecast=EloEngine.forecast(history,now,match.home,match.away)
                val probabilities=forecast.probabilities ?: return@mapNotNull null
                ForecastedMatch(
                    matchId=match.id,
                    homeTeam=match.home,
                    awayTeam=match.away,
                    kickoffMillis=kickoff,
                    probabilities=probabilities,
                    forecastedAtMillis=now,
                    historyMatchCount=forecast.matchesUsed,
                    modelVersion=CURRENT_ELO_MODEL_VERSION,
                    competition=match.league,
                )
            }
        }
    }
    val bookmakerCouponPrices=remember(misliOdds,topazOdds) {
        mapOf(
            "misli" to misliOdds.mapNotNull { q -> q.matchId?.let { it to ThreeWayDecimalOdds(q.home,q.draw,q.away,q.capturedAtMillis) } }.toMap(),
            "topaz" to topazOdds.mapNotNull { q -> q.matchId?.let { it to ThreeWayDecimalOdds(q.home,q.draw,q.away,q.capturedAtMillis) } }.toMap(),
        )
    }
    val modelPickDrafts=remember(forecastedMatches,misliOdds,topazOdds) {
        VirtualCouponBuilder.buildModelOnly(forecastedMatches,oddsProviders.map { it.providerId to it.displayName },bookmakerCouponPrices)
    }
    val accumulatorDrafts=remember(forecastedMatches,misliOdds,topazOdds) {
        VirtualCouponBuilder.buildAccumulators(forecastedMatches,oddsProviders.map { it.providerId to it.displayName },bookmakerCouponPrices)
    }
    LaunchedEffect(forecastedMatches) {
        val recordsChanged=repository.recordProspectiveForecastsAndSettle(forecastedMatches)
        if(recordsChanged>0) revision++
    }
    LaunchedEffect(modelPickDrafts,accumulatorDrafts) {
        val recordsAdded=repository.recordCouponDrafts(modelPickDrafts,accumulatorDrafts)
        if(recordsAdded>0) revision++
    }
    val checkedCouponHistory=storeSnapshot.checkedCouponHistory
    val prospectiveForecastHistory=storeSnapshot.prospectiveForecastHistory
    val prospectiveForecastReport=remember(prospectiveForecastHistory) {
        ProspectiveForecastEvaluator.evaluate(prospectiveForecastHistory)
    }
    val accumulatorHistory=storeSnapshot.accumulatorHistory
    val mapped=matches.associateBy { it.id }
    val settled=tickets.mapNotNull { t -> ticketMatches[t.matchId]?.let { m -> scoreResult(m)?.let { it to t } } }
    val exposure=settled.sumOf { it.second.stake }
    val returned=settled.sumOf { (r,t)->if(r==t.outcome) t.stake*t.price else 0.0 }
    val roi=if(exposure>0) (returned/exposure-1)*100 else 0.0
    var source by remember(selected) { mutableStateOf("") }
    var outcome by remember(selected) { mutableStateOf("H") }
    var stake by remember(selected) { mutableStateOf("10") }
    var warning by remember(selected) { mutableStateOf("") }
    val blue=MaterialTheme.colorScheme.primary

    val csvLauncher=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null&&!importingCsv) {
            importingCsv=true
            csvImportMessage="Импорт CSV…"
            val fallback=fallbackCompetition.trim().ifBlank { null }
            coroutineScope.launch {
                try {
                    val (fileName,bytes)=withContext(Dispatchers.IO) {
                        val resolver=activity.contentResolver
                        val name=resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { c ->
                            if(c.moveToFirst()) c.getString(0) else null
                        } ?: uri.lastPathSegment ?: "football-results.csv"
                        val input=resolver.openInputStream(uri) ?: error("Файл недоступен для чтения.")
                        val contents=input.use { stream ->
                            val output=ByteArrayOutputStream()
                            val buffer=ByteArray(8192)
                            while(true) {
                                val count=stream.read(buffer)
                                if(count<0) break
                                require(output.size()+count<=20*1024*1024) { "CSV-файл больше 20 МБ." }
                                output.write(buffer,0,count)
                            }
                            output.toByteArray()
                        }
                        name to contents
                    }
                    val report=withContext(Dispatchers.Default) {
                        FootballDataCsvImporter.parse(FootballDataCsvImporter.decode(bytes),fallback)
                    }
                    require(report.results.isNotEmpty()) { "Не найдено корректных завершённых матчей. Проверь Date, HomeTeam, AwayTeam, FTHG/HG и FTAG/AG." }
                    val stored=repository.importHistoricalResults(fileName,report)
                    revision++
                    importingCsv=false
                    backtestReport=null
                    backtestReportCode=""
                    val examples=report.examples.take(2).joinToString("; ").let { if(it.isBlank()) "" else " Примеры: $it" }
                    csvImportMessage="Импорт: добавлено ${stored.insertedRows}, дублей ${stored.duplicateRows}, пропущено строк ${report.rejectedRows} из ${report.rowsRead}.$examples"
                } catch(e:CancellationException) {
                    throw e
                } catch(e:Exception) {
                    importingCsv=false
                    csvImportMessage="Ошибка импорта: ${e.message ?: "неизвестная ошибка"}"
                }
            }
        }
    }

    fun runImportedBacktest(competitionCode:String) {
        if(backtestRunning||competitionCode.isBlank()) return
        backtestRunning=true
        backtestMessage="Расчёт по сохранённым результатам…"
        backtestReport=null
        backtestReportCode=""
        coroutineScope.launch {
            try {
                val rows=repository.importedHistory(competitionCode)
                val (history,targets)=withContext(Dispatchers.Default) {
                    val historicalMatches=rows.map { row ->
                        HistoricalMatch(
                            kickoffMillis=FootballDataCsvImporter.dateBucketForecastCutoffMillis(row.eventDate),
                            homeTeam=row.homeTeam,
                            awayTeam=row.awayTeam,
                            homeGoals=row.homeGoals,
                            awayGoals=row.awayGoals,
                            availableAtMillis=row.availableAtMillis,
                        )
                    }
                    val walkForwardTargets=rows.map { row ->
                        WalkForwardTarget(
                            id=row.id.toString(),
                            forecastCutoffMillis=FootballDataCsvImporter.dateBucketForecastCutoffMillis(row.eventDate),
                            homeTeam=row.homeTeam,
                            awayTeam=row.awayTeam,
                            homeGoals=row.homeGoals,
                            awayGoals=row.awayGoals,
                        )
                    }
                    historicalMatches to walkForwardTargets
                }
                val report=withContext(Dispatchers.Default) { EloWalkForwardEngine.evaluate(history,targets) }
                backtestReportCode=competitionCode
                backtestReport=report
                backtestRunning=false
                backtestMessage=""
            } catch(e:CancellationException) {
                throw e
            } catch(e:Exception) {
                backtestRunning=false
                backtestMessage="Ошибка исторического теста: ${e.message ?: "неизвестная ошибка"}"
            }
        }
    }

    fun runAutomaticBacktest() {
        if(automaticBacktestRunning||automaticBacktestRevision==revision) return
        automaticBacktestRunning=true
        automaticBacktestMessage="Расчёт по локальной автоматической истории…"
        coroutineScope.launch {
            try {
                val reports=repository.automaticBacktestReports()
                automaticBacktestReport=reports.eloWalkForward
                automaticEloExperiment=reports.eloParameterExperiment
                automaticGoalComparison=reports.goalModelComparison
                automaticBacktestRevision=revision
                automaticBacktestRunning=false
                automaticBacktestMessage=""
            } catch(error:CancellationException) {
                throw error
            } catch(error:Exception) {
                automaticBacktestRunning=false
                automaticBacktestMessage="Ошибка проверки: ${error.message ?: "неизвестная ошибка"}"
            }
        }
    }

    MaterialTheme {
      if(storeLoadState.loaded) {
          MisliPublicPageCollector(repository,misliSyncState?.lastCheckedAtMillis) { revision++ }
          TopazPublicPageCollector(repository,topazSyncState?.lastCheckedAtMillis) { revision++ }
      }
      Scaffold(topBar={ Surface(color=MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("FOOTBALL INTELLIGENCE",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            Text("Личный аналитический журнал • Бесплатно • Без реальных ставок",style=MaterialTheme.typography.bodySmall)
        }
      }}) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    listOf("Матчи","Анализ","Купоны","Результаты","Бэктест","Настройки","Мировой рынок").forEachIndexed { i,title ->
                    FilterChip(selected=tab==i,onClick={tab=i;if(i==4) runAutomaticBacktest()},label={Text(title)})
                }
            }
            when(tab) {
              0 -> {
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
                                !storeLoadState.loaded -> "Читаю сохранённую локальную базу…"
                                storeLoadState.error!=null -> "Не удалось прочитать локальную базу: ${storeLoadState.error}"
                                else -> "База пока пуста. Первый импорт начнётся автоматически при доступной сети; после загрузки данные останутся на устройстве."
                            }
                            Text(message,Modifier.padding(12.dp),style=MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        items(matches,key={it.id}) { m ->
                            ElevatedCard(onClick={selected=m.id;tab=1},modifier=Modifier.fillMaxWidth()) {
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
              1 -> Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
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
                          Text("Расхождение Elo с рыночным ориентиром: ${percentagePoints(model.home-marketProb.first)} / ${percentagePoints(model.draw-marketProb.second)} / ${percentagePoints(model.away-marketProb.third)} п.п. Это сравнение, не сигнал доходности.",style=MaterialTheme.typography.bodySmall)
                      }
                      (misliOdds+topazOdds).filter { it.matchId==active.id }.forEach { quote ->
                          val bookmakerProb=fairProbabilities(Quote(0,active.id,quote.providerId,quote.home,quote.draw,quote.away,quote.capturedAtMillis))
                          Text("${if(quote.providerId=="misli") "Misli" else "TOPAZ"} без маржи против ориентира: ${percentagePoints(bookmakerProb.first-marketProb.first)} / ${percentagePoints(bookmakerProb.second-marketProb.second)} / ${percentagePoints(bookmakerProb.third-marketProb.third)} п.п. Линия той же БК, 1X2; рассчитано пропорциональным снятием маржи.",style=MaterialTheme.typography.bodySmall)
                      }
                      Text("Один внешний производный ориентир; это не независимый консенсус и не сырые цены Pinnacle, Betfair или bet365. Значения не используются как цены купона или для расчёта EV.",style=MaterialTheme.typography.bodySmall)
                  }
                  TextButton(onClick={
                      runCatching { activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("https://tipsaudit.com/data"))) }
                  }) { Text("Источник и методика: TipsAudit · бесплатное использование с атрибуцией") }

                }
              }
              2 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("Автоматические модельные черновики",style=MaterialTheme.typography.titleLarge)
                Text("Подбор наиболее вероятного исхода строится по локальному Elo для матчей с точным временем и достаточной историей. Черновики раздельны по операторам. Реальная цена показывается только для свежей линии того же букмекера и матча; модель экспериментальная и не откалибрована.",style=MaterialTheme.typography.bodySmall)
                Text("Misli.az: ${visibleBookmakerStatus(misliSyncState?.status,misliSyncState?.lastCheckedAtMillis,misliOdds.size)} · свежих линий ${misliOdds.size}, точных матчей ${misliOdds.count { it.matchId!=null }}. TOPAZ/eTopaz: ${visibleBookmakerStatus(topazSyncState?.status,topazSyncState?.lastCheckedAtMillis,topazOdds.size)} · свежих линий ${topazOdds.size}, точных матчей ${topazOdds.count { it.matchId!=null }}.",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={showBookmakerQuotes=!showBookmakerQuotes}) {
                    Text(if(showBookmakerQuotes) "Скрыть публичные линии" else "Показать публичные линии (${misliOdds.size+topazOdds.size})")
                }
                if(showBookmakerQuotes) {
                ElevatedCard {
                    Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text("Misli.az · публичные линии 1X2",fontWeight=FontWeight.Bold)
                        Text("Снимок: ${misliSyncState?.lastCheckedAtMillis?.let { DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) } ?: "ещё не получен"}. Свежесть 30 минут. Командные названия сопоставляются только по точным проверенным псевдонимам, часу и порядку хозяева/гости.",style=MaterialTheme.typography.bodySmall)
                        if(misliOdds.isEmpty()) Text(misliSyncState?.error ?: "Ожидается открытая страница Misli; старые и прошедшие линии не показываются.",style=MaterialTheme.typography.bodySmall)
                        misliOdds.forEach { quote ->
                            val forecast=quote.matchId?.let { id -> forecastedMatches.firstOrNull { it.matchId==id } }
                            val comparison=forecast?.let { f ->
                                val p=f.probabilities
                                val market=fairProbabilities(Quote(0,quote.matchId ?: 0,"Misli",quote.home,quote.draw,quote.away,quote.capturedAtMillis))
                                "Elo ${"%.1f".format(p.home*100)}/${"%.1f".format(p.draw*100)}/${"%.1f".format(p.away*100)}% · линия без маржи ${"%.1f".format(market.first*100)}/${"%.1f".format(market.second*100)}/${"%.1f".format(market.third*100)}%"
                            } ?: "Проверенного матча с локальным прогнозом нет"
                            val captured=DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(quote.capturedAtMillis))
                            Text("${niceTime(Instant.ofEpochMilli(quote.kickoffMillis).toString())} · ${quote.homeTeam} — ${quote.awayTeam} · 1 ${quote.home} / X ${quote.draw} / 2 ${quote.away} · снимок $captured",fontWeight=FontWeight.SemiBold)
                            Text("$comparison · источник: Misli.az public football page${if(quote.matchId==null) " · матч не найден в локальной базе" else ""}",style=MaterialTheme.typography.bodySmall)
                        }
                        Text("Цены — сохранённый снимок публичной страницы, не гарантированно текущая линия. Расчёт потенциальной доходности отключён, пока модель не проверена и не откалибрована.",style=MaterialTheme.typography.labelSmall)
                    }
                }
                ElevatedCard {
                    Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text("TOPAZ / eTopaz",fontWeight=FontWeight.Bold)
                        Text(topazSyncState?.error ?: "Публичная страница проверяется автоматически при открытии приложения; свежесть 30 минут.",style=MaterialTheme.typography.bodySmall)
                        if(topazOdds.isEmpty()) Text("Пока нет подтверждённых будущих коэффициентов TOPAZ.",style=MaterialTheme.typography.bodySmall)
                        topazOdds.forEach { quote ->
                            val forecast=quote.matchId?.let { id -> forecastedMatches.firstOrNull { it.matchId==id } }
                            val comparison=forecast?.let { f ->
                                val p=f.probabilities
                                val market=fairProbabilities(Quote(0,quote.matchId ?: 0,"TOPAZ",quote.home,quote.draw,quote.away,quote.capturedAtMillis))
                                "Elo ${"%.1f".format(p.home*100)}/${"%.1f".format(p.draw*100)}/${"%.1f".format(p.away*100)}% · линия без маржи ${"%.1f".format(market.first*100)}/${"%.1f".format(market.second*100)}/${"%.1f".format(market.third*100)}%"
                            } ?: "Нет точного локального матча с доступным прогнозом"
                            val captured=DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(quote.capturedAtMillis))
                            Text("${niceTime(Instant.ofEpochMilli(quote.kickoffMillis).toString())} · ${quote.homeTeam} — ${quote.awayTeam} · 1 ${quote.home} / X ${quote.draw} / 2 ${quote.away} · снимок $captured${if(quote.eventYearInferred) " · год по ближайшей будущей дате" else ""}",fontWeight=FontWeight.SemiBold)
                            Text("$comparison${if(quote.matchId==null) " · матч не найден в локальной базе" else ""}",style=MaterialTheme.typography.bodySmall)
                        }
                        Text("TOPAZ использует только собственные линии; коэффициенты Misli в этот купон не смешиваются.",style=MaterialTheme.typography.labelSmall)
                    }
                }
                }
                Text("Виртуальные экспрессы",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                Text("Размеры 2 / 3 / 5 / 8 / 10 доступны только при достаточном числе будущих матчей с точным kickoff и историей для модели. Отбираются исходы с наибольшими расчётными маргинальными вероятностями; более слабые матчи не добавляются ради размера.",style=MaterialTheme.typography.bodySmall)
                Text("Исследовательские купоны по расхождению показывают только положительную разницу между Elo и вероятностью без маржи из полной свежей линии того же букмекера. Модель не калибрована: это не EV, не прибыль и не рекомендация; при отсутствии точной свежей линии такие исходы не строятся.",style=MaterialTheme.typography.labelSmall)
                if(accumulatorDrafts.isEmpty()) Text("Пока недостаточно прогнозируемых матчей для экспресса из двух исходов. Доступно матчей: ${forecastedMatches.size}.",style=MaterialTheme.typography.bodySmall)
                accumulatorDrafts.forEach { draft ->
                    ElevatedCard {
                        Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                            val basisLabel=couponSelectionBasisLabel(draft.selectionBasis)
                            Text("${draft.providerName} · ${draft.requestedLegCount} исхода · $basisLabel",fontWeight=FontWeight.Bold)
                            val currentQuotes=if(draft.providerId=="misli") misliOdds else topazOdds
                            val linkedCurrentQuotes=currentQuotes.count { it.matchId!=null }
                            Text(if(currentQuotes.isEmpty()) {
                                (if(draft.providerId=="misli") misliSyncState?.error else topazSyncState?.error)
                                    ?: oddsProviders.first { it.providerId==draft.providerId }.status().detail
                            } else "Свежие котировки ${draft.providerName}: ${currentQuotes.size}; точно сопоставлено локальным матчам: $linkedCurrentQuotes.",style=MaterialTheme.typography.bodySmall)
                            draft.picks.forEach { pick ->
                                val price=pick.bookmakerDecimalOdds?.let { odds ->
                                    val quoteTime=pick.bookmakerOddsCapturedAtMillis?.let { DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) }
                                    " · ${draft.providerName} $odds${quoteTime?.let { " (снимок $it)" }.orEmpty()}"
                                }.orEmpty()
                                val modelMarket=if(draft.selectionBasis==CouponSelectionBasis.MODEL_MARKET_DISAGREEMENT) {
                                    val marketProbability=pick.marketFairProbability
                                    val difference=pick.modelMinusMarket
                                    if(marketProbability!=null&&difference!=null) " · рынок без маржи ${"%.1f".format(marketProbability*100)}% · Δ ${signedPercentagePoints(difference)} п.п." else ""
                                } else ""
                                Text("${niceTime(Instant.ofEpochMilli(pick.kickoffMillis).toString())} · ${pick.homeTeam} — ${pick.awayTeam}: ${labelOutcome(pick.outcome)} · модель ${"%.1f".format(pick.modelProbability*100)}%$modelMarket$price")
                            }
                            draft.jointProbabilityBounds?.let { bounds ->
                                Text("Совместная вероятность: только математические границы ${(bounds.lower*100).roundToInt()}–${(bounds.upper*100).roundToInt()}%; зависимость матчей не смоделирована.",style=MaterialTheme.typography.bodySmall)
                                draft.combinedDecimalOdds?.takeIf { draft.selectionBasis!=CouponSelectionBasis.MODEL_MARKET_DISAGREEMENT }?.let { odds ->
                                    val evLower=(odds*bounds.lower-1.0)*100.0
                                    val evUpper=(odds*bounds.upper-1.0)*100.0
                                    Text("Условный EV по этим модельным границам: ${"%.1f".format(evLower)}–${"%.1f".format(evUpper)}% на условную единицу; Elo не откалиброван, это не оценка реальной прибыли.",style=MaterialTheme.typography.bodySmall)
                                }
                            }
                            if(draft.combinedDecimalOdds!=null) Text("Условный общий коэффициент ${"%.2f".format(draft.combinedDecimalOdds)} — произведение только свежих линий ${draft.providerName}.",fontWeight=FontWeight.SemiBold)
                            Text(when {
                                draft.selectionBasis==CouponSelectionBasis.MODEL_MARKET_DISAGREEMENT -> "Положительное расхождение Elo с линией без маржи — только исследовательский фильтр. Модель не откалибрована; EV/ROI скрыты и прибыль не оценивается."
                                draft.combinedDecimalOdds!=null -> "Снимки коэффициентов фиксируются вместе с виртуальным купоном до матчей. Это не прогнозируемая прибыль: Elo не откалиброван, а совместный шанс известен лишь в указанном диапазоне."
                                else -> "Нет полной свежей линии ${draft.providerName} для каждого исхода; общий коэффициент и денежная доходность недоступны. Виртуальный черновик сохраняет только модельные вероятности."
                            },style=MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                HorizontalDivider()
                Text("История виртуальных экспрессов",fontWeight=FontWeight.Bold)
                Text("Снимки сохраняются автоматически и затем сверяются с локальными результатами. Перенос kickoff помечается отдельно; отмена матча не трактуется как поражение.",style=MaterialTheme.typography.bodySmall)
                if(accumulatorHistory.isEmpty()) Text("История начнёт заполняться автоматически при наличии минимум двух доступных прогнозов.",style=MaterialTheme.typography.bodySmall)
                accumulatorHistory.take(30).forEach { entry ->
                    val provider=oddsProviders.firstOrNull { it.providerId==entry.providerId }?.displayName ?: entry.providerId
                    val basisLabel=couponSelectionBasisLabel(entry.selectionBasis)
                    val checked=DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(entry.capturedAtMillis))
                    val stateLabel=when(entry.settlement.state) {
                        AccumulatorSettlementState.PENDING -> "Ожидает матчей"
                        AccumulatorSettlementState.IN_PROGRESS -> "Завершено ${entry.settlement.settledLegs}/${entry.legCount}"
                        AccumulatorSettlementState.WON -> "Все ${entry.legCount} совпали"
                        AccumulatorSettlementState.LOST -> "Купон проиграл: не совпало ${entry.settlement.incorrectLegs}; проверено ${entry.settlement.settledLegs}/${entry.legCount}"
                        AccumulatorSettlementState.SCHEDULE_CHANGED -> "Kickoff изменён · требуется повторная проверка"
                        AccumulatorSettlementState.INVALID -> "Некорректная запись"
                    }
                    Text("$checked · $provider · ${entry.legCount} исхода $basisLabel · $stateLabel${entry.combinedDecimalOdds?.let { " · общий коэффициент ${"%.2f".format(it)}" }.orEmpty()}",style=MaterialTheme.typography.bodySmall)
                    if(entry.selectionBasis==CouponSelectionBasis.MODEL_MARKET_DISAGREEMENT) entry.picks.forEach pickLoop@ { pick ->
                        val market=pick.marketFairProbability ?: return@pickLoop
                        val delta=pick.modelMinusMarket ?: return@pickLoop
                        Text("${pick.homeTeam} — ${pick.awayTeam}: модель ${"%.1f".format(pick.modelProbability*100)}%, линия без маржи ${"%.1f".format(market*100)}%, Δ ${signedPercentagePoints(delta)} п.п.",style=MaterialTheme.typography.labelSmall)
                    }
                }
                accumulatorHistory.groupBy { Triple(it.providerId,it.legCount,it.selectionBasis) }.forEach { (key, entries) ->
                    val singles=checkedCouponHistory.asSequence().filter { it.providerId==key.first }
                        .mapNotNull { pick ->
                            val home=pick.homeGoals ?: return@mapNotNull null
                            val away=pick.awayGoals ?: return@mapNotNull null
                            if(!pick.finished) return@mapNotNull null
                            val actual=when { home>away -> "H";home<away -> "A";else -> "D" }
                            SettledSinglePrediction(pick.matchId,actual==pick.outcome)
                        }.toList()
                    val report=AccumulatorPerformanceEngine.evaluate(entries,key.first,key.second,key.third,singles)
                    val provider=oddsProviders.firstOrNull { it.providerId==key.first }?.displayName ?: key.first
                    val basisLabel=couponSelectionBasisLabel(key.third)
                    val hit=report.couponHitRate?.let { "${report.wonCoupons}/${report.settledCoupons} (${"%.1f".format(it*100)}%)" } ?: "ожидает исходов"
                    val singleHit=report.singlePickHitRate?.let { " · одиночные прогнозы тех же матчей ${report.matchingSinglePicks}, ${"%.1f".format(it*100)}%" }.orEmpty()
                    val roi=when {
                        key.third==CouponSelectionBasis.MODEL_MARKET_DISAGREEMENT -> " · ROI скрыт: исследовательское сравнение модели с рынком"
                        report.virtualRoi!=null -> " · виртуальный ROI по полной линии ${"%.1f".format(report.virtualRoi*100)}% (n=${report.pricedSettledCoupons})"
                        report.settledCoupons>0&&report.pricedSettledCoupons>0 -> " · ROI скрыт: цены есть только у части завершённых купонов"
                        else -> " · ROI недоступен: нет полной цены у завершённых купонов"
                    }
                    val priceCoverage=report.settledPriceCoverage?.let { "${report.pricedSettledCoupons}/${report.settledCoupons} (${"%.0f".format(it*100)}%; без полной цены ${report.settledCouponsWithoutPrice})" } ?: "нет завершённых купонов"
                    Text("Проверка $provider · ${key.second} исхода $basisLabel: купоны ${report.settledCoupons}, попадания $hit$singleHit · покрытие ценами $priceCoverage$roi",style=MaterialTheme.typography.bodySmall)
                    if(report.settledCoupons>0&&report.couponHitRate95Low!=null&&report.couponHitRate95High!=null) {
                        Text("95% Wilson-интервал доли выигравших экспрессов: ${(report.couponHitRate95Low*100).roundToInt()}–${(report.couponHitRate95High*100).roundToInt()}%; CLV не вычисляется без зафиксированной цены около kickoff.",style=MaterialTheme.typography.labelSmall)
                    }
                }
                HorizontalDivider()
                Text("Проверка сохранённых прогнозов",fontWeight=FontWeight.Bold)
                if(checkedCouponHistory.isEmpty()) Text("Результаты появятся после автоматического сохранения предматчевых черновиков.",style=MaterialTheme.typography.bodySmall)
                checkedCouponHistory.forEach { pick ->
                    val resolved=pick.finished&&pick.homeGoals!=null&&pick.awayGoals!=null
                    val actual=if(!resolved) "Ожидает результата" else "Результат ${pick.homeGoals}:${pick.awayGoals} · ${if(scoreResult(Match(pick.matchId,"","","","",true,pick.homeGoals,pick.awayGoals))==pick.outcome) "совпало" else "не совпало"}"
                    Text("${pick.providerId} · ${pick.homeTeam} — ${pick.awayTeam} · ${labelOutcome(pick.outcome)} ${(pick.modelProbability*100).toInt()}% · $actual",style=MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                Text("Замороженные прогнозы модели",fontWeight=FontWeight.Bold)
                Text(
                    "Модель ${CURRENT_ELO_MODEL_VERSION}. Сохраняется один неизменяемый снимок на матч, версию и UTC-день до kickoff; итог записывается отдельно, когда результат стал доступен. Это будущая проверка без оценки прибыли.",
                    style=MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Снимков в последних ${prospectiveForecastHistory.size}: проверено результатов ${prospectiveForecastReport.settledCount}" +
                        (prospectiveForecastReport.meanBrierScore?.let {
                            " · Brier ${"%.4f".format(it)} · Log Loss ${"%.4f".format(prospectiveForecastReport.meanLogLoss)} · попадание наиболее вероятного ${"%.1f".format((prospectiveForecastReport.topOutcomeAccuracy ?: 0.0)*100)}% (n=${prospectiveForecastReport.settledCount})"
                        } ?: " · для оценки ждём завершившиеся матчи"),
                    style=MaterialTheme.typography.bodySmall,
                )
                if(prospectiveForecastHistory.isEmpty()) {
                    Text("Первые прогнозы появятся после автоматического расчёта матчей с достаточной предматчевой историей.",style=MaterialTheme.typography.bodySmall)
                } else {
                    prospectiveForecastHistory.take(20).forEach { snapshot ->
                        val captured=niceTime(Instant.ofEpochMilli(snapshot.forecastedAtMillis).toString())
                        val probabilities=snapshot.probabilities
                        val actual=if(snapshot.actualOutcome==null) {
                            "ожидает доступного результата"
                        } else {
                            val scores="${snapshot.homeGoals}:${snapshot.awayGoals}"
                            val mostLikely=listOf("H","D","A")[probabilities.values().indices.maxBy { probabilities.values()[it] }]
                            val available=snapshot.resultAvailableAtMillis?.let {
                                " · доступен ${niceTime(Instant.ofEpochMilli(it).toString())}"
                            }.orEmpty()
                            "${labelOutcome(snapshot.actualOutcome)} $scores · ${if(mostLikely==snapshot.actualOutcome) "совпало" else "не совпало"} · ${snapshot.resultSourceName ?: "источник не указан"}$available"
                        }
                        Text(
                            "$captured · ${snapshot.homeTeam} — ${snapshot.awayTeam} · ${"%.1f".format(probabilities.home*100)}/${"%.1f".format(probabilities.draw*100)}/${"%.1f".format(probabilities.away*100)}% · история ${snapshot.historyMatchCount} · $actual",
                            style=MaterialTheme.typography.bodySmall,
                        )
                    }
                }
              }
              3 -> Column(Modifier.fillMaxSize().padding(14.dp)) {
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
              4 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("Elo walk-forward",style=MaterialTheme.typography.titleLarge)
                Text("Автоматическая оценка локальной истории OpenFootball/OpenLigaDB по отдельным соревнованиям. Для каждой цели модель использует только результаты, доступные до её временной границы.",style=MaterialTheme.typography.bodySmall)
                Text("Это ретроспективная оценка текущих файлов, а не архив исходных публикаций. Для OpenFootball без времени kickoff используется календарная дата и консервативная доступность результата через два дня; поздно полученные результаты OpenLigaDB не попадают в прошлое обучение. Поэтому цифры исследовательские и не доказывают будущую точность или преимущество.",style=MaterialTheme.typography.bodySmall)
                if(automaticBacktestRunning) LinearProgressIndicator(Modifier.fillMaxWidth())
                if(automaticBacktestMessage.isNotBlank()) Text(automaticBacktestMessage,style=MaterialTheme.typography.bodySmall)
                automaticBacktestReport?.let { report ->
                    Text("Автоматическая база: целей ${report.targetCount} · рассчитано прогнозов ${report.forecastCount} · пропущено из-за малого прошлого сэмпла ${report.skippedForInsufficientHistory}",fontWeight=FontWeight.SemiBold)
                    if(report.meanBrierScore==null||report.meanLogLoss==null) Text("Пока недостаточно предматчевой истории для проверяемого прогноза.",style=MaterialTheme.typography.bodySmall)
                    else Text("Средний Brier: ${"%.4f".format(report.meanBrierScore)} · средний Log Loss: ${"%.4f".format(report.meanLogLoss)}")
                    WalkForwardCalibrationSummary(report)
                    Text("Срез рассчитан отдельно в границах соревнований. Результат не является обещанием прибыли.",style=MaterialTheme.typography.bodySmall)
                }
                automaticEloExperiment?.let { experiment ->
                    HorizontalDivider()
                    Text("Хронологический выбор параметров Elo",fontWeight=FontWeight.SemiBold)
                    Text(
                        "Первые 60% временных срезов — разогрев, следующие 20% — выбор по Log Loss, последние 20% — отдельная проверка. Рейтинг ведётся отдельно по соревнованиям; holdout не участвует в выборе.",
                        style=MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Разогрев: ${experiment.training.forecastCount}/${experiment.training.targetCount} прогнозов · Brier ${experiment.training.meanBrierScore?.let { "%.4f".format(it) } ?: "—"} · Log Loss ${experiment.training.meanLogLoss?.let { "%.4f".format(it) } ?: "—"}",
                        style=MaterialTheme.typography.bodySmall,
                    )
                    val validationDay=experiment.validationStartMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() } ?: "—"
                    val testDay=experiment.testStartMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() } ?: "—"
                    Text("Начало validation: $validationDay · начало holdout: $testDay · сетка ${experiment.candidates.size} конфигураций",style=MaterialTheme.typography.bodySmall)
                    val selected=experiment.selectedConfig
                    val selectedValidation=experiment.selectedValidation
                    val selectedTest=experiment.selectedTest
                    if(selected!=null&&selectedValidation!=null&&selectedTest!=null) {
                        Text(
                            "Выбор validation: K=${selected.kFactor.toInt()}, home=${selected.homeAdvantage.toInt()}, draw prior=${"%.2f".format(selected.priorDrawProbability)} · Log Loss ${selectedValidation.meanLogLoss?.let { "%.4f".format(it) } ?: "—"} (n=${selectedValidation.forecastCount})",
                            fontWeight=FontWeight.Medium,
                            style=MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Holdout: выбранные Brier ${selectedTest.meanBrierScore?.let { "%.4f".format(it) } ?: "—"}, Log Loss ${selectedTest.meanLogLoss?.let { "%.4f".format(it) } ?: "—"} (n=${selectedTest.forecastCount}); текущие настройки Brier ${experiment.defaultTest.meanBrierScore?.let { "%.4f".format(it) } ?: "—"}, Log Loss ${experiment.defaultTest.meanLogLoss?.let { "%.4f".format(it) } ?: "—"} (n=${experiment.defaultTest.forecastCount}).",
                            style=MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        val bestValidationForecasts=experiment.candidates.maxOfOrNull { it.validation.forecastCount } ?: 0
                        Text(
                            "Настройки не выбраны: validation дала максимум $bestValidationForecasts прогнозов; нужно не меньше ${experiment.minimumValidationForecastsForSelection}. Holdout показан только для текущих настроек.",
                            style=MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Holdout текущих настроек: Brier ${experiment.defaultTest.meanBrierScore?.let { "%.4f".format(it) } ?: "—"}, Log Loss ${experiment.defaultTest.meanLogLoss?.let { "%.4f".format(it) } ?: "—"} (n=${experiment.defaultTest.forecastCount}).",
                            style=MaterialTheme.typography.bodySmall,
                        )
                    }
                    experiment.candidates.take(3).forEachIndexed { index,candidate ->
                        Text(
                            "${index+1}. K=${candidate.config.kFactor.toInt()}, home=${candidate.config.homeAdvantage.toInt()}, draw prior=${"%.2f".format(candidate.config.priorDrawProbability)} · validation Log Loss ${candidate.validation.meanLogLoss?.let { "%.4f".format(it) } ?: "—"} · Brier ${candidate.validation.meanBrierScore?.let { "%.4f".format(it) } ?: "—"} (n=${candidate.validation.forecastCount})",
                            style=MaterialTheme.typography.labelSmall,
                        )
                    }
                    Text("Это один ретроспективный split текущей локальной базы, без архивных версий исходных файлов. Параметры не меняют сохранённые прогнозы и не означают будущую точность или прибыль.",style=MaterialTheme.typography.labelSmall)
                }
                automaticGoalComparison?.let { comparison ->
                    HorizontalDivider()
                    Text("Poisson по голам: сравнение с Elo",fontWeight=FontWeight.SemiBold)
                    if(comparison.validationStartMillis==null||comparison.holdoutStartMillis==null) {
                        Text("Недостаточно отдельных временных срезов для validation/holdout.",style=MaterialTheme.typography.bodySmall)
                    } else {
                        Text(
                            "Validation Poisson при ρ=0: Brier ${comparison.validationGoalMetrics.meanBrierScore?.let { "%.4f".format(it) } ?: "—"}, Log Loss ${comparison.validationGoalMetrics.meanLogLoss?.let { "%.4f".format(it) } ?: "—"} · прогнозов ${comparison.validationGoalMetrics.forecastCount}/${comparison.validationTargetCount}.",
                            style=MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            if(comparison.rhoSelectionAvailable) {
                                "Dixon–Coles ρ=${comparison.selectedDixonColesRho?.let { "%.2f".format(it) } ?: "—"} выбран только на validation по Log Loss (n=${comparison.selectedRhoValidationMetrics?.forecastCount}); порог ${comparison.minimumValidationForecastsForRhoSelection}."
                            } else {
                                "Dixon–Coles ρ оставлен 0: validation дала ${comparison.rhoCandidates.maxOfOrNull { it.validation.forecastCount } ?: 0} прогнозов; выбор требует минимум ${comparison.minimumValidationForecastsForRhoSelection}."
                            },
                            style=MaterialTheme.typography.bodySmall,
                        )
                        val eloPaired=comparison.eloPairedHoldout
                        val goalPaired=comparison.goalPairedHoldout
                        Text(
                            "Holdout на общих матчах ${comparison.pairedHoldoutTargetCount}: Elo ${eloPaired.meanBrierScore?.let { "%.4f".format(it) } ?: "—"} Brier / ${eloPaired.meanLogLoss?.let { "%.4f".format(it) } ?: "—"} Log Loss; Poisson ${goalPaired.meanBrierScore?.let { "%.4f".format(it) } ?: "—"} / ${goalPaired.meanLogLoss?.let { "%.4f".format(it) } ?: "—"}. Доступность прогнозов на holdout: Elo ${comparison.eloHoldoutForecastCount}, Poisson ${comparison.goalHoldoutForecastCount} из ${comparison.holdoutTargetCount}.",
                            style=MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Elo-параметры ${if(comparison.usedValidationSelectedEloConfig) "выбраны на validation" else "оставлены по умолчанию"}; Poisson сглаживает атаки/оборону на ${comparison.goalConfig.smoothingMatches.toInt()} псевдоматчах, требует ${comparison.goalConfig.minimumLeagueMatches} матчей лиги и ${comparison.goalConfig.minimumMatchesPerTeam} матчей команды. Holdout не используется для выбора ρ.",
                            style=MaterialTheme.typography.labelSmall,
                        )
                    }
                    Text("Обе модели используют только результаты, доступные до cutoff. Это одна проверка текущих исторических файлов; она не подтверждает будущую точность или преимущество.",style=MaterialTheme.typography.labelSmall)
                }
                HorizontalDivider()
                Text("Дополнительный импортированный CSV",fontWeight=FontWeight.SemiBold)
                Text("Ретроспективная оценка по импортированным результатам. Для каждого календарного дня используется один срез, без результатов этого дня; строки CSV становятся доступны только спустя два дня.",style=MaterialTheme.typography.bodySmall)
                Text("Это не воспроизведение архивных котировок и не сравнение с рынком: временные версии исходных файлов не сохранены, а вероятности не калиброваны.",style=MaterialTheme.typography.bodySmall)
                if(importedCompetitionCodes.isEmpty()) {
                    Text("Дополнительный walk-forward по CSV станет доступен после импорта в настройках. Для обычного анализа работает автоматическая база.")
                } else {
                    Text("Код соревнования из CSV",fontWeight=FontWeight.SemiBold)
                    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        importedCompetitionCodes.forEach { code ->
                            FilterChip(selected=activeBacktestCode==code,onClick={selectedBacktestCode=code},label={Text(code)})
                        }
                    }
                    Text("Минимум для прогноза: ${EloEngine.MIN_HISTORY_MATCHES_FOR_FORECAST} прошлых матчей соревнования и ${EloEngine.MIN_TEAM_MATCHES_FOR_FORECAST} матча каждой команды.",style=MaterialTheme.typography.bodySmall)
                    Button(onClick={runImportedBacktest(activeBacktestCode)},enabled=!backtestRunning,modifier=Modifier.fillMaxWidth()) { Text("Рассчитать walk-forward") }
                    if(backtestRunning) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(backtestMessage.isNotBlank()) Text(backtestMessage,style=MaterialTheme.typography.bodySmall)
                    if(backtestReportCode==activeBacktestCode) backtestReport?.let { report ->
                        HorizontalDivider()
                        Text("Матчей: ${report.targetCount} · прогнозов: ${report.forecastCount} · пропущено из-за малого сэмпла: ${report.skippedForInsufficientHistory}",fontWeight=FontWeight.SemiBold)
                        if(report.meanBrierScore==null||report.meanLogLoss==null) {
                            Text("Недостаточно предматчевой истории для расчёта показателей.",style=MaterialTheme.typography.bodySmall)
                        } else {
                            Text("Средний Brier: ${"%.4f".format(report.meanBrierScore)} · средний Log Loss: ${"%.4f".format(report.meanLogLoss)}")
                        }
                        WalkForwardCalibrationSummary(report)
                        Text("Результаты показывают только качество вероятностных оценок на этом локальном файле; они не доказывают преимущество, прибыль или превосходство над букмекерами.",style=MaterialTheme.typography.bodySmall)
                    }
                }
              }
              6 -> Column(Modifier.fillMaxSize().padding(horizontal=14.dp)) {
                Column(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    val checked=internationalOddsSyncState?.lastCheckedAtMillis?.let { DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) } ?: "ещё не проверен"
                    val workerBusy=(internationalOddsOnceSync+internationalOddsPeriodicSync).any { it.state==WorkInfo.State.RUNNING }
                    Text("Мировой рынок · футбольный 1X2",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                    Text("bet365 через независимый агрегатор MoviOdds · ${if(internationalAgeConfirmed) visibleInternationalOddsStatus(internationalOddsSyncState,internationalOdds.size) else "ТРЕБУЕТСЯ 18+"} · проверка $checked · свежих матчей ${internationalOdds.size}${if(workerBusy) " · обновление выполняется" else ""}",style=MaterialTheme.typography.bodySmall)
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
                    Text("Котировка считается свежей только при недавнем времени опроса рынка у источника (до 15 минут), независимо от того, когда изменился коэффициент. Приложение и источник сохраняются с разными отметками времени.",style=MaterialTheme.typography.bodySmall)
                    Text("Данные bet365 поступают через MoviOdds, который не аффилирован с bet365. Условия MoviOdds требуют возраст 18+ и разрешают использование в собственных приложениях; точная доступность в Азербайджане на физическом телефоне ещё не проверена. Источник может быть неполным или недоступным.",style=MaterialTheme.typography.bodySmall)
                    Text("Misli/TOPAZ остаются отдельными местными линиями. Международная цена не используется для виртуальных купонов Misli или TOPAZ. Цены и модели не являются доказательством преимущества или прибыли.",style=MaterialTheme.typography.bodySmall)
                    if(!internationalAgeConfirmed) {
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                Text("Международный источник доступен только пользователям 18+.",fontWeight=FontWeight.SemiBold)
                                Text("Подтвердите возраст один раз, чтобы включить автоматическое обновление bet365 через MoviOdds. Остальная футбольная база обновляется независимо.",style=MaterialTheme.typography.bodySmall)
                                TextButton(onClick={runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://moviodds.com/terms/"))) }}) {
                                    Text("Условия MoviOdds")
                                }
                                Button(onClick={
                                    activity.getSharedPreferences(MOVIODDS_AGE_CONSENT_PREFS,Context.MODE_PRIVATE)
                                        .edit().putBoolean(MOVIODDS_AGE_CONSENT_KEY,true).apply()
                                    internationalAgeConfirmed=true
                                    InternationalOddsMarketScheduler.scheduleOnAppOpen(activity)
                                }) { Text("Подтверждаю, мне 18 лет или больше") }
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
                    items(internationalOdds,key={"${it.bookmakerId}:${it.eventId}"}) { quote ->
                        val eventConsensus=internationalConsensus.firstOrNull { it.matchId!=null&&it.matchId==quote.matchId }
                        val model=quote.matchId?.let { id -> forecastedMatches.firstOrNull { it.matchId==id } }
                        val localMisli=quote.matchId?.let { id -> misliOdds.firstOrNull { it.matchId==id } }
                        val localTopaz=quote.matchId?.let { id -> topazOdds.firstOrNull { it.matchId==id } }
                        val internationalFair=ProbabilityEngine.removeMargin(quote.home,quote.draw,quote.away,MarginMethod.BASIC)
                        val movement=InternationalMarketEngine.latestMovement(quote,internationalOddsHistory)
                        val pollTime=DateTimeFormatter.ofPattern("dd.MM HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(quote.providerPolledAtMillis))
                        val captureTime=DateTimeFormatter.ofPattern("dd.MM HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(quote.capturedAtMillis))
                        val priceTimes=listOf(quote.homePriceChangedAtMillis,quote.drawPriceChangedAtMillis,quote.awayPriceChangedAtMillis).joinToString(" / ") {
                            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))
                        }
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(13.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                Text("${quote.league} · ${niceTime(Instant.ofEpochMilli(quote.kickoffMillis).toString())}",style=MaterialTheme.typography.labelMedium,color=blue)
                                Text("${quote.homeTeam} — ${quote.awayTeam}",fontWeight=FontWeight.SemiBold)
                                Text("bet365 · 1 ${"%.2f".format(quote.home)} / X ${"%.2f".format(quote.draw)} / 2 ${"%.2f".format(quote.away)}",fontWeight=FontWeight.Bold)
                                Text("Рыночные вероятности без маржи: ${"%.1f".format(internationalFair.home*100)} / ${"%.1f".format(internationalFair.draw*100)} / ${"%.1f".format(internationalFair.away*100)}% · один букмекерский ориентир${quote.region?.let { " · регион источника $it" }.orEmpty()}",style=MaterialTheme.typography.bodySmall)
                                Text("Проверено MoviOdds $pollTime · сохранено приложением $captureTime · цены менялись 1/X/2: $priceTimes",style=MaterialTheme.typography.bodySmall)
                                Text(if((eventConsensus?.distinctBookmakerCount ?: 1)>=2) "Среднее после снятия маржи по ${eventConsensus?.distinctBookmakerCount} независимым букмекерам." else "Международный консенсус пока недоступен: есть только одна БК; цена не считается несколькими независимыми наблюдениями.",style=MaterialTheme.typography.labelSmall)
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
                                        Text("Elo ${"%.1f".format(p.home*100)} / ${"%.1f".format(p.draw*100)} / ${"%.1f".format(p.away*100)}% · Elo минус bet365 без маржи: ${"%+.1f".format((p.home-internationalFair.home)*100)} / ${"%+.1f".format((p.draw-internationalFair.draw)*100)} / ${"%+.1f".format((p.away-internationalFair.away)*100)} п.п. · модель экспериментальная, не откалибрована.",style=MaterialTheme.typography.bodySmall)
                                    }
                                    listOf("Misli" to localMisli,"TOPAZ" to localTopaz).forEach { (name,local) ->
                                        if(local==null) Text("$name: нет свежей локальной линии по этому матчу.",style=MaterialTheme.typography.bodySmall)
                                        else {
                                            val localFair=ProbabilityEngine.removeMargin(local.home,local.draw,local.away,MarginMethod.BASIC)
                                            val localCaptured=DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(local.capturedAtMillis))
                                            Text("$name ${"%.2f".format(local.home)}/${"%.2f".format(local.draw)}/${"%.2f".format(local.away)} · без маржи ${"%.1f".format(localFair.home*100)}/${"%.1f".format(localFair.draw*100)}/${"%.1f".format(localFair.away*100)}% · Δ к bet365 ${"%+.1f".format((localFair.home-internationalFair.home)*100)}/${"%+.1f".format((localFair.draw-internationalFair.draw)*100)}/${"%+.1f".format((localFair.away-internationalFair.away)*100)} п.п. · снимок $localCaptured. Котировки не смешиваются в купон.",style=MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item {
                            TextButton(onClick={showInternationalHistory=!showInternationalHistory}) {
                            Text(if(showInternationalHistory) "Скрыть историю изменений международных цен" else "Показать историю изменений международных цен (${internationalOddsHistory.size})")
                        }
                    }
                    if(showInternationalHistory) {
                        item { Text("Сохранённые изменения котировок · не являются текущими линиями; одинаковые повторные опросы не дублируются",fontWeight=FontWeight.SemiBold) }
                        items(internationalOddsHistory.take(120),key={"history:${it.bookmakerId}:${it.eventId}:${it.capturedAtMillis}"}) { old ->
                            Text("${old.league} · ${old.homeTeam} — ${old.awayTeam} · ${"%.2f".format(old.home)}/${"%.2f".format(old.draw)}/${"%.2f".format(old.away)} · рынок опрошен ${DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(old.providerPolledAtMillis))} · снимок ${DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(old.capturedAtMillis))}",style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
              }
              else -> Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                  Text("Football Intelligence Lab",style=MaterialTheme.typography.titleLarge)
                  Text("Личная лаборатория исследования футбольных ставок, без оплаты и обязательных ключей.")
                  Text("Автоматические футбольные источники: OpenFootball и OpenLigaDB. Misli/TOPAZ читаются только из публичных видимых страниц при открытом приложении; bet365 доступен через MoviOdds при наличии подтверждённой свежей линии.")
                  Text("Дополнительные функции")
                  OutlinedTextField(value=fallbackCompetition,onValueChange={fallbackCompetition=it},label={Text("Необязательный код соревнования для CSV")},singleLine=true,modifier=Modifier.fillMaxWidth())
                  Button(onClick={csvLauncher.launch(arrayOf("text/*","application/csv"))},enabled=!importingCsv,modifier=Modifier.fillMaxWidth()) { Text(if(importingCsv) "Импорт…" else "Импорт CSV (дополнительно)") }
                  if(csvImportMessage.isNotBlank()) Text(csvImportMessage,style=MaterialTheme.typography.bodySmall)
                  Text("CSV не нужен для обычного запуска: матчи и результаты загружаются автоматически.",style=MaterialTheme.typography.bodySmall)
                  Text("Вероятности Elo экспериментальные и не калиброваны. Купоны виртуальные.")
                  Text("Система не выполняет ставки, не входит в букмекерские аккаунты и не обещает доход.")
                  Text("Открытые источники могут временно не отвечать. Уже сохранённые данные доступны без сети.")
              }
            }
        }
      }
    }
}
@Composable fun OutcomeSelect(value:String,onChange:(String)->Unit) {
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf("H","D","A").forEach { v -> FilterChip(selected=value==v,onClick={onChange(v)},label={Text(labelOutcome(v))}) }
    }
}

@Composable
private fun WalkForwardCalibrationSummary(report:EloWalkForwardReport) {
    if(report.predictions.isEmpty()) return
    val calibration=remember(report) { CalibrationDiagnostics.evaluateWalkForward(report.predictions) }
    var expanded by remember(report) { mutableStateOf(false) }
    val ece=calibration.meanOneVsRestEce?.let { "%.1f".format(it*100) } ?: "н/д"
    Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
        Text("Калибровочная диагностика: mean one-vs-rest ECE $ece% · ${calibration.predictionCount} прогнозов",fontWeight=FontWeight.SemiBold)
        calibration.outcomes.forEach { outcome ->
            val outcomeEce=outcome.expectedCalibrationError?.let { "%.1f".format(it*100) } ?: "н/д"
            Text("${outcome.outcome.label}: ECE $outcomeEce% · n=${outcome.sampleCount}",style=MaterialTheme.typography.bodySmall)
        }
        Text("Равные интервалы вероятности 0–100%; ECE показывает среднее расхождение прогноза и частоты исхода в занятых корзинах. Это описание ретроспективной выборки, не калибровка модели.",style=MaterialTheme.typography.labelSmall)
        TextButton(onClick={expanded=!expanded},contentPadding=PaddingValues(0.dp)) {
            Text(if(expanded) "Скрыть корзины" else "Показать занятые корзины")
        }
        if(expanded) {
            calibration.outcomes.forEach { outcome ->
                if(outcome.occupiedBins.isNotEmpty()) {
                    Text("${outcome.outcome.label} · средняя вероятность / частота / число",fontWeight=FontWeight.Medium,style=MaterialTheme.typography.labelMedium)
                    outcome.occupiedBins.forEach { bin ->
                        Text(
                            "${"%.0f".format(bin.lowerInclusive*100)}–${"%.0f".format(bin.upperBound*100)}% · " +
                                "${"%.1f".format(bin.meanPredictedProbability*100)}% / " +
                                "${"%.1f".format(bin.observedFrequency*100)}% / ${bin.sampleCount}",
                            style=MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
