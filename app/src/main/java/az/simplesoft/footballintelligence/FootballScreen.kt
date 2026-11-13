package az.simplesoft.footballintelligence

import android.provider.OpenableColumns
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import az.simplesoft.footballintelligence.analytics.EloEngine
import az.simplesoft.footballintelligence.analytics.EloForecast
import az.simplesoft.footballintelligence.analytics.EloWalkForwardEngine
import az.simplesoft.footballintelligence.analytics.EloWalkForwardReport
import az.simplesoft.footballintelligence.analytics.CompetitionSeasonWalkForwardMetrics
import az.simplesoft.footballintelligence.analytics.EloChronologicalExperimentReport
import az.simplesoft.footballintelligence.analytics.GoalModelComparisonReport
import az.simplesoft.footballintelligence.analytics.CURRENT_ELO_MODEL_VERSION
import az.simplesoft.footballintelligence.analytics.HistoricalMatch
import az.simplesoft.footballintelligence.analytics.historyAvailableAt
import az.simplesoft.footballintelligence.analytics.ForecastedMatch
import az.simplesoft.footballintelligence.analytics.VirtualCouponBuilder
import az.simplesoft.footballintelligence.analytics.ThreeWayDecimalOdds
import az.simplesoft.footballintelligence.analytics.WalkForwardTarget
import az.simplesoft.footballintelligence.analytics.TeamDataQuality
import az.simplesoft.footballintelligence.data.FootballDataCsvImporter
import az.simplesoft.footballintelligence.data.FootballScreenRepository
import az.simplesoft.footballintelligence.data.MisliOddsProvider
import az.simplesoft.footballintelligence.data.TopazOddsProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager

@Composable fun FootballScreen(repository:FootballScreenRepository) {
    val activity = LocalContext.current as ComponentActivity
    val coroutineScope = rememberCoroutineScope()
    var storeLoadState by remember(repository) { mutableStateOf(FootballScreenStoreLoadState()) }
    var screenAnalytics by remember(repository) { mutableStateOf(FootballScreenAnalytics.empty()) }
    var screenAnalyticsUpdating by remember(repository) { mutableStateOf(true) }
    var revision by remember { mutableIntStateOf(0) }
    var selected by remember { mutableIntStateOf(-1) }
    var destination by remember { mutableStateOf(FootballDestination.HOME) }
    var analyticsSection by remember { mutableStateOf(FootballAnalyticsSection.MARKET) }
    var showMatchDetail by remember { mutableStateOf(false) }
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
    var automaticEloByCompetitionSeason by remember { mutableStateOf<List<CompetitionSeasonWalkForwardMetrics>>(emptyList()) }
    var automaticEloExperiment by remember { mutableStateOf<EloChronologicalExperimentReport?>(null) }
    var automaticGoalComparison by remember { mutableStateOf<GoalModelComparisonReport?>(null) }
    var automaticBacktestRevision by remember { mutableIntStateOf(-1) }
    var showInternationalHistory by remember { mutableStateOf(false) }
    var internationalAgeConfirmed by remember(activity) { mutableStateOf(isMoviOddsAgeConfirmed(activity)) }
    val workManager=remember(activity) { WorkManager.getInstance(activity) }
    val oneTimeSync by remember(workManager) { workManager.getWorkInfosForUniqueWorkLiveData(FootballSyncScheduler.UNIQUE_ONCE_NAME) }.observeAsState(emptyList())
    val periodicSync by remember(workManager) { workManager.getWorkInfosForUniqueWorkLiveData(FootballSyncScheduler.UNIQUE_PERIODIC_NAME) }.observeAsState(emptyList())
    val marketReferenceOnceSync by remember(workManager) { workManager.getWorkInfosForUniqueWorkLiveData(TipsAuditMarketReferenceScheduler.UNIQUE_ONCE_NAME) }.observeAsState(emptyList())
    val marketReferencePeriodicSync by remember(workManager) { workManager.getWorkInfosForUniqueWorkLiveData(TipsAuditMarketReferenceScheduler.UNIQUE_PERIODIC_NAME) }.observeAsState(emptyList())
    val internationalOddsOnceSync by remember(workManager) { workManager.getWorkInfosForUniqueWorkLiveData(InternationalOddsMarketScheduler.UNIQUE_ONCE_NAME) }.observeAsState(emptyList())
    val internationalOddsPeriodicSync by remember(workManager) { workManager.getWorkInfosForUniqueWorkLiveData(InternationalOddsMarketScheduler.UNIQUE_PERIODIC_NAME) }.observeAsState(emptyList())
    val theOddsApiOnceSync by remember(workManager) { workManager.getWorkInfosForUniqueWorkLiveData(TheOddsApiScheduler.UNIQUE_ONCE_NAME) }.observeAsState(emptyList())
    val theOddsApiPeriodicSync by remember(workManager) { workManager.getWorkInfosForUniqueWorkLiveData(TheOddsApiScheduler.UNIQUE_PERIODIC_NAME) }.observeAsState(emptyList())
    val footballDataStatsOnceSync by remember(workManager) { workManager.getWorkInfosForUniqueWorkLiveData(FootballDataStatsScheduler.UNIQUE_ONCE_NAME) }.observeAsState(emptyList())
    val footballDataStatsPeriodicSync by remember(workManager) { workManager.getWorkInfosForUniqueWorkLiveData(FootballDataStatsScheduler.UNIQUE_PERIODIC_NAME) }.observeAsState(emptyList())
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
    LaunchedEffect(
        repository,
        storeSnapshot.internationalOdds,
        storeSnapshot.internationalOddsEvaluationHistory,
        storeSnapshot.prospectiveForecastEvaluationHistory,
    ) {
        screenAnalyticsUpdating=true
        val snapshot = storeSnapshot
        screenAnalytics = withContext(Dispatchers.Default) {
            calculateFootballScreenAnalytics(snapshot)
        }
        screenAnalyticsUpdating=false
    }
    val syncSummaries=storeSnapshot.providerSyncSummaries
    val syncBusy=(oneTimeSync+periodicSync).any { it.state==WorkInfo.State.RUNNING }
    val syncQueued=oneTimeSync.any { it.state==WorkInfo.State.ENQUEUED || it.state==WorkInfo.State.BLOCKED }
    val activeSyncStage=(oneTimeSync+periodicSync).firstOrNull { it.state==WorkInfo.State.RUNNING }
        ?.progress?.getString("stage")
    val syncOverview=remember(syncBusy,syncQueued,syncSummaries) {
        automaticFootballSyncOverview(syncBusy,syncQueued,syncSummaries)
    }
    val syncStatusLabel=when {
        storeLoadState.error!=null -> "Ошибка локальной базы"
        !storeLoadState.loaded -> "Читаю локальную базу"
        syncBusy -> automaticSyncStageMessage(activeSyncStage)
        else -> syncOverview.status
    }
    val syncSummaryDetail=when {
        storeLoadState.error!=null -> storeLoadState.error ?: "Не удалось прочитать локальную базу."
        !storeLoadState.loaded -> "Загружаю сохранённые матчи и результаты."
        else -> syncOverview.detail
    }
    val syncHealthy=when {
        storeLoadState.error!=null -> false
        !storeLoadState.loaded || syncBusy || syncQueued -> null
        else -> syncOverview.healthy
    }
    val syncStatusText="$syncStatusLabel · $syncSummaryDetail"
    val syncHomeDetails=listOf(syncStatusText,providerSyncDetails(syncSummaries)).joinToString("\n")
    LaunchedEffect(
        oneTimeSync.map { it.state to (it.runAttemptCount to it.progress.getString("stage")) },
        periodicSync.map { it.state to (it.runAttemptCount to it.progress.getString("stage")) },
    ) { revision++ }
    LaunchedEffect(marketReferenceOnceSync.map { it.state },marketReferencePeriodicSync.map { it.state }) { revision++ }
    LaunchedEffect(internationalOddsOnceSync.map { it.state },internationalOddsPeriodicSync.map { it.state },theOddsApiOnceSync.map { it.state },theOddsApiPeriodicSync.map { it.state }) { revision++ }
    LaunchedEffect(footballDataStatsOnceSync.map { it.state },footballDataStatsPeriodicSync.map { it.state }) { revision++ }
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
    val oddsProviders=remember { listOf(MisliOddsProvider(),TopazOddsProvider()) }
    val misliSyncState=storeSnapshot.misliSyncState
    val misliOdds=storeSnapshot.misliOdds
    val topazSyncState=storeSnapshot.topazSyncState
    val topazOdds=storeSnapshot.topazOdds
    val internationalOddsSyncState=storeSnapshot.internationalOddsSyncState
    val internationalOddsSyncHistory=storeSnapshot.internationalOddsSyncHistory
    val theOddsApiSyncState=storeSnapshot.theOddsApiSyncState
    val theOddsApiSyncHistory=storeSnapshot.theOddsApiSyncHistory
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
    LaunchedEffect(forecastedMatches, syncBusy, syncQueued) {
        val recordsChanged=repository.recordProspectiveForecastsAndSettle(forecastedMatches)
        if(recordsChanged>0) revision++
        if(shouldScheduleTheOddsApiAfterFootballSync(
                syncBusy = syncBusy,
                syncQueued = syncQueued,
                forecastCount = forecastedMatches.size,
                apiKeyAvailable = BuildConfig.THE_ODDS_API_KEY.isNotBlank(),
            )) {
            TheOddsApiScheduler.scheduleAfterFootballSync(activity)
        }
    }
    LaunchedEffect(modelPickDrafts,accumulatorDrafts) {
        val recordsAdded=repository.recordCouponDrafts(modelPickDrafts,accumulatorDrafts)
        if(recordsAdded>0) revision++
    }
    val checkedCouponHistory=storeSnapshot.checkedCouponHistory
    val prospectiveForecastHistory=storeSnapshot.prospectiveForecastHistory
    val internationalConsensus=screenAnalytics.internationalConsensus
    val internationalMarketEvaluation=screenAnalytics.internationalMarketEvaluation
    val prospectiveForecastReport=screenAnalytics.prospectiveForecastReport
    val accumulatorHistory=storeSnapshot.accumulatorHistory
    val accumulatorEvaluationHistory=storeSnapshot.accumulatorEvaluationHistory
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
                automaticEloByCompetitionSeason=reports.eloByCompetitionSeason
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

    BackHandler(enabled = showMatchDetail || destination == FootballDestination.SETTINGS) {
        if (showMatchDetail) showMatchDetail = false else destination = FootballDestination.HOME
    }

    FootballTheme {
      if(storeLoadState.loaded) {
          MisliPublicPageCollector(repository,misliSyncState?.lastCheckedAtMillis) { revision++ }
          TopazPublicPageCollector(repository,topazSyncState?.lastCheckedAtMillis) { revision++ }
      }
      val pageTitle = when {
          showMatchDetail -> "Анализ матча"
          destination == FootballDestination.HOME -> "Football Intelligence"
          destination == FootballDestination.MATCHES -> "Матчи"
          destination == FootballDestination.COUPONS -> "Купоны"
          destination == FootballDestination.ANALYTICS -> "Аналитика"
          else -> "Настройки"
      }
      val topDetail = when {
          showMatchDetail -> active?.league ?: "Локальная модель и линии"
          destination == FootballDestination.HOME -> "Матчи · данные · модельный обзор"
          destination == FootballDestination.MATCHES -> "Фильтр по дате, лиге и линии"
          destination == FootballDestination.COUPONS -> "Только виртуальные купоны"
          destination == FootballDestination.ANALYTICS -> analyticsSection.label
          else -> "Источники и дополнительные настройки"
      }
      Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
          FootballTopBar(
            title = pageTitle,
            detail = topDetail,
            showBack = showMatchDetail || destination == FootballDestination.SETTINGS,
            onBack = { if (showMatchDetail) showMatchDetail = false else destination = FootballDestination.HOME },
            onSettings = { destination = FootballDestination.SETTINGS; showMatchDetail = false },
          )
        },
        bottomBar = {
          if (!showMatchDetail && destination != FootballDestination.SETTINGS) {
            FootballBottomNavigation(destination) { destination = it; showMatchDetail = false }
          }
        },
      ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
          if (!showMatchDetail && destination == FootballDestination.ANALYTICS) {
            FootballAnalyticsNavigation(analyticsSection) {
              analyticsSection = it
              if (it == FootballAnalyticsSection.BACKTEST) runAutomaticBacktest()
            }
          }
          when {
            showMatchDetail -> MatchAnalysisTab(
              active = active,
              eloForecast = eloForecast,
              activeMarketReference = activeMarketReference,
              marketReferenceSyncState = marketReferenceSyncState,
              misliOdds = misliOdds,
              topazOdds = topazOdds,
              internationalOdds = internationalOdds,
              localOddsHistory = storeSnapshot.selectedBookmakerOddsHistory,
              onOpenTipsAudit = { runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://tipsaudit.com/data"))) } },
            )
            destination == FootballDestination.HOME -> FootballHomeTabContent(
              matches = matches,
              forecasts = forecastedMatches,
              misliOdds = misliOdds,
              topazOdds = topazOdds,
              checkedPredictions = checkedCouponHistory,
              syncBusy = syncBusy,
              lastSyncLabel = syncStatusLabel,
              syncSummaryDetail = syncSummaryDetail,
              syncHealthy = syncHealthy,
              syncDetails = syncHomeDetails,
              storeLoaded = storeLoadState.loaded,
              storeError = storeLoadState.error,
              onOpenMatches = { destination = FootballDestination.MATCHES },
              onOpenCoupons = { destination = FootballDestination.COUPONS },
              onSelectMatch = { selected = it; showMatchDetail = true },
            )
            destination == FootballDestination.MATCHES -> FootballFixtureList(
              matches = matches,
              forecasts = forecastedMatches,
              misliOdds = misliOdds,
              topazOdds = topazOdds,
              storeLoaded = storeLoadState.loaded,
              storeError = storeLoadState.error,
              onSelectMatch = { selected = it; showMatchDetail = true },
            )
            destination == FootballDestination.COUPONS -> FootballCouponWorkspace(
              matches = matches,
              modelForecastCount = forecastedMatches.size,
              misliOdds = misliOdds,
              topazOdds = topazOdds,
              candidates = accumulatorDrafts,
              history = accumulatorHistory,
              evaluationHistory = accumulatorEvaluationHistory,
              checkedPicks = checkedCouponHistory,
              onOpenMatches = { destination = FootballDestination.MATCHES },
            )
            destination == FootballDestination.ANALYTICS -> when (analyticsSection) {
              FootballAnalyticsSection.MARKET -> InternationalMarketTabContent(
                internationalAgeConfirmed=internationalAgeConfirmed,
                theOddsApiConfigured=BuildConfig.THE_ODDS_API_KEY.isNotBlank(),
                internationalOddsSyncState=internationalOddsSyncState,
                internationalOddsSyncHistory=internationalOddsSyncHistory,
                theOddsApiSyncState=theOddsApiSyncState,
                theOddsApiSyncHistory=theOddsApiSyncHistory,
                internationalOdds=internationalOdds,
                internationalOddsHistory=internationalOddsHistory,
                internationalMarketEvaluation=internationalMarketEvaluation,
                internationalConsensus=internationalConsensus,
                forecastedMatches=forecastedMatches,
                misliOdds=misliOdds,
                topazOdds=topazOdds,
                workerBusy=(internationalOddsOnceSync+internationalOddsPeriodicSync+theOddsApiOnceSync+theOddsApiPeriodicSync).any { it.state==WorkInfo.State.RUNNING },
                showInternationalHistory=showInternationalHistory,
                onToggleInternationalHistory={showInternationalHistory=!showInternationalHistory},
                onOpenMoviOddsTerms={runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://moviodds.com/terms/"))) }},
                onConfirmMoviOddsAge={
                    activity.getSharedPreferences(MOVIODDS_AGE_CONSENT_PREFS,Context.MODE_PRIVATE)
                        .edit().putBoolean(MOVIODDS_AGE_CONSENT_KEY,true).apply()
                    internationalAgeConfirmed=true
                    InternationalOddsMarketScheduler.scheduleOnAppOpen(activity)
                },
                analyticsUpdating=screenAnalyticsUpdating,
              )
              FootballAnalyticsSection.BACKTEST -> FootballBacktestTabContent(
                automaticBacktestRunning=automaticBacktestRunning,
                automaticBacktestMessage=automaticBacktestMessage,
                automaticBacktestReport=automaticBacktestReport,
                automaticEloByCompetitionSeason=automaticEloByCompetitionSeason,
                automaticEloExperiment=automaticEloExperiment,
                automaticGoalComparison=automaticGoalComparison,
                importedCompetitionCodes=importedCompetitionCodes,
                activeBacktestCode=activeBacktestCode,
                onSelectBacktestCode={selectedBacktestCode=it},
                onRunImportedBacktest={runImportedBacktest(it)},
                backtestRunning=backtestRunning,
                backtestMessage=backtestMessage,
                backtestReportCode=backtestReportCode,
                backtestReport=backtestReport,
              )
              FootballAnalyticsSection.MODELS -> FootballModelStatsTab(prospectiveForecastReport, prospectiveForecastHistory, screenAnalyticsUpdating)
              FootballAnalyticsSection.RESULTS -> FootballResultsTabContent(
                recentResults=recentResults,
                matchStats=storeSnapshot.recentMatchStats,
                latestStatsAttempt=storeSnapshot.footballDataStatsAttempt,
                nextStatsSyncAtMillis=storeSnapshot.footballDataStatsNextAllowedAtMillis,
              )
            }
            else -> FootballSettingsTabContent(
                fallbackCompetition=fallbackCompetition,
                onFallbackCompetitionChange={fallbackCompetition=it},
                importingCsv=importingCsv,
                csvImportMessage=csvImportMessage,
                onLaunchCsvImport={csvLauncher.launch(arrayOf("text/*","application/csv"))},
                trailingContent = { FootballSourceSettings(
                providerSyncSummaries=syncSummaries,
                syncStatus=syncStatusText,
                misliCount=misliOdds.size,
                misliStatus=misliSyncState?.status?.name ?: "ЕЩЁ НЕ ПРОВЕРЕН",
                topazCount=topazOdds.size,
                topazStatus=topazSyncState?.status?.name ?: "ЕЩЁ НЕ ПРОВЕРЕН",
                internationalOdds=internationalOdds,
                internationalStatus=internationalOddsSyncState?.status?.name ?: "ЕЩЁ НЕ ПРОВЕРЕН",
                theOddsApiStatus=theOddsApiSyncState?.status?.name ?: "ЕЩЁ НЕ ПРОВЕРЕН",
                theOddsApiConfigured=BuildConfig.THE_ODDS_API_KEY.isNotBlank(),
                statsAttempt=storeSnapshot.footballDataStatsAttempt,
              ) },
            )
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
