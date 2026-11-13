package az.simplesoft.footballintelligence

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import az.simplesoft.footballintelligence.data.OpenFootballCompetitionFile
import az.simplesoft.footballintelligence.data.OpenFootballJsonParser
import az.simplesoft.footballintelligence.data.OpenLigaDbParser
import az.simplesoft.footballintelligence.data.OpenLigaLeague
import az.simplesoft.footballintelligence.data.ProviderFileCache
import az.simplesoft.footballintelligence.data.ProviderMatch
import az.simplesoft.footballintelligence.data.ProviderSyncSummary
import az.simplesoft.footballintelligence.data.TipsAuditFairOddsProvider
import az.simplesoft.footballintelligence.data.TIPSAUDIT_API_URL
import az.simplesoft.footballintelligence.data.MarketReferenceStatus
import az.simplesoft.footballintelligence.data.MARKET_REFERENCE_APP_OPEN_STALE_MINUTES
import az.simplesoft.footballintelligence.data.MARKET_REFERENCE_REFRESH_INTERVAL_MINUTES
import az.simplesoft.footballintelligence.data.Bet365ViaMoviOddsProvider
import az.simplesoft.footballintelligence.data.InternationalOddsStatus
import az.simplesoft.footballintelligence.data.INTERNATIONAL_ODDS_APP_OPEN_STALE_MINUTES
import az.simplesoft.footballintelligence.data.INTERNATIONAL_ODDS_REFRESH_INTERVAL_MINUTES
import az.simplesoft.footballintelligence.data.MOVIODDS_API_URL
import az.simplesoft.footballintelligence.data.MOVIODDS_PROVIDER_ID
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PERIODIC_SYNC_NAME = "football-intelligence-daily-sync"
private const val ONCE_SYNC_NAME = "football-intelligence-sync-now"
private const val TIPSAUDIT_PERIODIC_SYNC_NAME = "football-intelligence-tipsaudit-market-reference"
private const val TIPSAUDIT_ONCE_SYNC_NAME = "football-intelligence-tipsaudit-market-reference-now"
internal const val MOVIODDS_AGE_CONSENT_PREFS = "football_intelligence_consent"
internal const val MOVIODDS_AGE_CONSENT_KEY = "moviodds_user_is_18_or_older"
private const val MIN_OPENLIGA_REQUEST_GAP_MS = 1_250L // 48 requests/minute, below the provider's 60/minute cap.
private const val SYNC_CONTINUATION_MESSAGE = "sync continues in the next queued WorkManager segment"
private const val FIRST_PROVIDER_SYNC_BUDGET_MILLIS = 3L * 60 * 1000
// Keep the shared worker deadline below the 9-minute SQLite lease and Android's 10-minute worker limit.
private const val WORKER_SYNC_BUDGET_MILLIS = 7L * 60 * 1000
internal const val PERIODIC_SYNC_INTERVAL_HOURS = 24L
internal const val APP_OPEN_SYNC_STALE_AFTER_HOURS = 8L
internal const val SYNC_RETRY_BACKOFF_SECONDS = 30L

internal fun parseRetryAfterDelayMillis(value: String?, nowMillis: Long): Long? {
    val header = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (header.all(Char::isDigit)) {
        val seconds = header.toLongOrNull()?.takeIf { it >= 0L } ?: return null
        return runCatching { Math.multiplyExact(seconds, 1_000L) }.getOrNull()
    }
    val retryAtMillis = runCatching {
        ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrNull() ?: return null
    return if (retryAtMillis <= nowMillis) 0L else retryAtMillis - nowMillis
}

internal fun ProviderHttpException.shouldStopProviderRequests(): Boolean = statusCode == 429 || statusCode == 503

internal fun combineRetryAfterDelays(current: Long?, candidate: Long?): Long? = when {
    candidate == null || candidate <= 0L -> current
    current == null || candidate > current -> candidate
    else -> current
}

internal inline fun <reified W : ListenableWorker> enqueueDelayedNetworkRetry(
    context: Context,
    uniqueWorkName: String,
    delayMillis: Long,
): Boolean {
    if (delayMillis <= 0L) return false
    return runCatching {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = withAutomaticSyncRetryBackoff(
            OneTimeWorkRequestBuilder<W>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .setConstraints(constraints),
        ).build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(uniqueWorkName, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }.isSuccess
}

internal fun <B : WorkRequest.Builder<B, W>, W : WorkRequest> withAutomaticSyncRetryBackoff(builder: B): B =
    builder.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, SYNC_RETRY_BACKOFF_SECONDS, TimeUnit.SECONDS)

internal enum class SyncNextStep { CONTINUE, SUCCESS, RETRY }

internal fun decideSyncNextStep(retryNeeded: Boolean, continuationNeeded: Boolean, anySuccess: Boolean): SyncNextStep = when {
    retryNeeded -> SyncNextStep.RETRY
    continuationNeeded -> SyncNextStep.CONTINUE
    anySuccess -> SyncNextStep.SUCCESS
    else -> SyncNextStep.RETRY
}

internal fun isAppOpenSyncStale(mostRecentSuccessAtMillis: Long?, nowMillis: Long): Boolean =
    mostRecentSuccessAtMillis == null ||
        mostRecentSuccessAtMillis <= 0L ||
        nowMillis - mostRecentSuccessAtMillis >= TimeUnit.HOURS.toMillis(APP_OPEN_SYNC_STALE_AFTER_HOURS)

internal fun automaticSyncStageMessage(stage: String?): String = when (stage) {
    "waiting_for_sync_lock" -> "Ожидается завершение другой синхронизации; локальная база остаётся доступной."
    "OpenFootball" -> "Загружается история и расписание OpenFootball…"
    "OpenLigaDB" -> "Обновляются футбольные лиги OpenLigaDB…"
    "prospective_forecasts" -> "Сохраняются прогнозы для последующей проверки…"
    "continuation_queued" -> "Сегмент завершён; импорт продолжится автоматически."
    "complete" -> "Синхронизация источников завершена."
    else -> "Идёт автоматическая синхронизация источников…"
}

internal fun automaticProviderSyncStatus(completedUnits: Int, hasErrors: Boolean, continuationNeeded: Boolean): String {
    require(completedUnits >= 0)
    return when {
        completedUnits == 0 && continuationNeeded && !hasErrors -> "DEFERRED"
        completedUnits == 0 -> "ERROR"
        hasErrors || continuationNeeded -> "PARTIAL"
        else -> "OK"
    }
}

internal data class SyncRunDeadlines(
    val openFootballDeadlineMillis: Long,
    val workerDeadlineMillis: Long,
)

internal fun syncRunDeadlines(startedAtMillis: Long): SyncRunDeadlines = SyncRunDeadlines(
    openFootballDeadlineMillis = startedAtMillis + FIRST_PROVIDER_SYNC_BUDGET_MILLIS,
    workerDeadlineMillis = startedAtMillis + WORKER_SYNC_BUDGET_MILLIS,
)

object FootballSyncScheduler {
    fun scheduleOnAppOpen(context: Context) {
        val appContext = context.applicationContext
        val summaries = runCatching {
            Store(appContext).let { store ->
                try { store.providerSyncSummaries() } finally { store.close() }
            }
        }.getOrDefault(emptyList())
        val mostRecentSuccess = summaries.mapNotNull { it.lastSuccessAtMillis }.maxOrNull() ?: 0L
        enqueueForFreshness(appContext, mostRecentSuccess, System.currentTimeMillis())
        TipsAuditMarketReferenceScheduler.scheduleOnAppOpen(appContext)
        InternationalOddsMarketScheduler.scheduleOnAppOpen(appContext)
        TheOddsApiScheduler.scheduleOnAppOpen(appContext)
        FootballDataStatsScheduler.scheduleOnAppOpen(appContext)
    }

    /**
     * Enqueues the app-open and periodic work for a given freshness snapshot.
     * The optional delay keeps instrumentation requests queued without making external HTTP calls.
     */
    internal fun enqueueForFreshness(
        context: Context,
        mostRecentSuccessAtMillis: Long?,
        nowMillis: Long,
        immediateInitialDelayMillis: Long = 0L,
    ) {
        require(immediateInitialDelayMillis >= 0L)
        val appContext = context.applicationContext
        val manager = WorkManager.getInstance(appContext)
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val periodic = withAutomaticSyncRetryBackoff(
            PeriodicWorkRequestBuilder<AutomaticFootballSyncWorker>(
                PERIODIC_SYNC_INTERVAL_HOURS,
                TimeUnit.HOURS,
            )
                // Periodic work may run immediately on first enqueue; app-open work handles stale data now.
                .setInitialDelay(PERIODIC_SYNC_INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints),
        ).build()
        manager.enqueueUniquePeriodicWork(PERIODIC_SYNC_NAME, ExistingPeriodicWorkPolicy.KEEP, periodic)

        if (isAppOpenSyncStale(mostRecentSuccessAtMillis, nowMillis)) {
            val immediateBuilder = withAutomaticSyncRetryBackoff(
                OneTimeWorkRequestBuilder<AutomaticFootballSyncWorker>().setConstraints(constraints),
            )
            if (immediateInitialDelayMillis > 0L) {
                immediateBuilder.setInitialDelay(immediateInitialDelayMillis, TimeUnit.MILLISECONDS)
            }
            manager.enqueueUniqueWork(ONCE_SYNC_NAME, ExistingWorkPolicy.KEEP, immediateBuilder.build())
        }
    }

    const val UNIQUE_ONCE_NAME = ONCE_SYNC_NAME
    const val UNIQUE_PERIODIC_NAME = PERIODIC_SYNC_NAME
}

internal fun isTipsAuditReferenceSyncStale(lastCheckedAtMillis:Long?,nowMillis:Long):Boolean =
    lastCheckedAtMillis==null || lastCheckedAtMillis<=0L ||
        nowMillis-lastCheckedAtMillis>=TimeUnit.MINUTES.toMillis(MARKET_REFERENCE_APP_OPEN_STALE_MINUTES)

/** Market reference refresh is isolated from the slower historical football-data sync. */
object TipsAuditMarketReferenceScheduler {
    fun scheduleOnAppOpen(context:Context) {
        val appContext=context.applicationContext
        val lastChecked=runCatching {
            Store(appContext).let { store -> try { store.marketReferenceSyncState()?.lastCheckedAtMillis } finally { store.close() } }
        }.getOrNull()
        enqueueForFreshness(appContext,lastChecked,System.currentTimeMillis())
    }

    internal fun enqueueForFreshness(
        context:Context,
        lastCheckedAtMillis:Long?,
        nowMillis:Long,
        immediateInitialDelayMillis:Long=0L,
    ) {
        require(immediateInitialDelayMillis>=0L)
        val manager=WorkManager.getInstance(context.applicationContext)
        val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val periodic=withAutomaticSyncRetryBackoff(
            PeriodicWorkRequestBuilder<TipsAuditMarketReferenceWorker>(MARKET_REFERENCE_REFRESH_INTERVAL_MINUTES,TimeUnit.MINUTES)
                .setInitialDelay(MARKET_REFERENCE_REFRESH_INTERVAL_MINUTES,TimeUnit.MINUTES)
                .setConstraints(constraints),
        ).build()
        manager.enqueueUniquePeriodicWork(TIPSAUDIT_PERIODIC_SYNC_NAME,ExistingPeriodicWorkPolicy.KEEP,periodic)
        if(isTipsAuditReferenceSyncStale(lastCheckedAtMillis,nowMillis)) {
            val onceBuilder=withAutomaticSyncRetryBackoff(
                OneTimeWorkRequestBuilder<TipsAuditMarketReferenceWorker>().setConstraints(constraints),
            )
            if(immediateInitialDelayMillis>0L) onceBuilder.setInitialDelay(immediateInitialDelayMillis,TimeUnit.MILLISECONDS)
            val once=onceBuilder.build()
            manager.enqueueUniqueWork(TIPSAUDIT_ONCE_SYNC_NAME,ExistingWorkPolicy.KEEP,once)
        }
    }

    const val UNIQUE_ONCE_NAME=TIPSAUDIT_ONCE_SYNC_NAME
    const val UNIQUE_PERIODIC_NAME=TIPSAUDIT_PERIODIC_SYNC_NAME
}

class TipsAuditMarketReferenceWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    internal var providerHttpClient:ProviderHttpClient=PublicJsonClient()

    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        val store=Store(applicationContext)
        val checkedAt=System.currentTimeMillis()
        try {
            val response=providerHttpClient.get(TIPSAUDIT_API_URL)
            if(response.statusCode in setOf(429,503)) {
                throw ProviderHttpException(response.statusCode,response.retryAfterDelayMillis)
            }
            if(response.statusCode !in 200..299) throw IOException("Unexpected HTTP ${response.statusCode} for TipsAudit.")
            val parsed=TipsAuditFairOddsProvider().parse(response.body,checkedAt,store.matchIdentities())
            store.saveMarketReferenceCapture(
                checkedAt,
                if(parsed.snapshots.isEmpty()) MarketReferenceStatus.UNAVAILABLE else MarketReferenceStatus.OK,
                parsed.snapshots,
                parsed.methodologyVersion,
                parsed.detail,
            )
            store.relinkUnmatchedMarketReferences(checkedAt)
            Result.success()
        } catch(error:ProviderHttpException) {
            store.saveMarketReferenceCapture(checkedAt,MarketReferenceStatus.ERROR,emptyList(),null,error.message)
            val queued=if(error.shouldStopProviderRequests()) error.retryAfterDelayMillis?.let { delay ->
                enqueueDelayedNetworkRetry<TipsAuditMarketReferenceWorker>(applicationContext,TIPSAUDIT_ONCE_SYNC_NAME,delay)
            } == true else false
            if(queued) {
                Result.success()
            } else Result.retry()
        } catch(error:Exception) {
            store.saveMarketReferenceCapture(checkedAt,MarketReferenceStatus.ERROR,emptyList(),null,safeError(error))
            Result.retry()
        } finally {
            store.close()
        }
    }
}

private const val INTERNATIONAL_ODDS_PERIODIC_WORK_NAME="football-intelligence-international-odds"
private const val INTERNATIONAL_ODDS_ONCE_WORK_NAME="football-intelligence-international-odds-now"

internal fun isInternationalOddsReferenceSyncStale(lastCheckedAtMillis:Long?,nowMillis:Long):Boolean =
    lastCheckedAtMillis==null||lastCheckedAtMillis<=0L||
        nowMillis-lastCheckedAtMillis>=TimeUnit.MINUTES.toMillis(INTERNATIONAL_ODDS_APP_OPEN_STALE_MINUTES)

/** The documented public feed is refreshed independently of local bookmaker WebViews and football history. */
object InternationalOddsMarketScheduler {
    fun scheduleOnAppOpen(context:Context) {
        val appContext=context.applicationContext
        if(!isMoviOddsAgeConfirmed(appContext)) {
            cancelPendingWork(appContext)
            return
        }
        val lastChecked=runCatching {
            Store(appContext).let { store -> try { store.internationalOddsSyncState()?.lastCheckedAtMillis } finally { store.close() } }
        }.getOrNull()
        enqueueForFreshness(appContext,lastChecked,System.currentTimeMillis())
    }

    internal fun enqueueForFreshness(
        context:Context,
        lastCheckedAtMillis:Long?,
        nowMillis:Long,
        immediateInitialDelayMillis:Long=0L,
    ) {
        require(immediateInitialDelayMillis>=0L)
        if(!isMoviOddsAgeConfirmed(context.applicationContext)) {
            cancelPendingWork(context.applicationContext)
            return
        }
        val manager=WorkManager.getInstance(context.applicationContext)
        val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val periodic=withAutomaticSyncRetryBackoff(
            PeriodicWorkRequestBuilder<InternationalOddsMarketWorker>(INTERNATIONAL_ODDS_REFRESH_INTERVAL_MINUTES,TimeUnit.MINUTES)
                .setInitialDelay(INTERNATIONAL_ODDS_REFRESH_INTERVAL_MINUTES,TimeUnit.MINUTES)
                .setConstraints(constraints),
        ).build()
        manager.enqueueUniquePeriodicWork(INTERNATIONAL_ODDS_PERIODIC_WORK_NAME,ExistingPeriodicWorkPolicy.KEEP,periodic)
        if(isInternationalOddsReferenceSyncStale(lastCheckedAtMillis,nowMillis)) {
            val onceBuilder=withAutomaticSyncRetryBackoff(
                OneTimeWorkRequestBuilder<InternationalOddsMarketWorker>().setConstraints(constraints),
            )
            if(immediateInitialDelayMillis>0L) onceBuilder.setInitialDelay(immediateInitialDelayMillis,TimeUnit.MILLISECONDS)
            manager.enqueueUniqueWork(INTERNATIONAL_ODDS_ONCE_WORK_NAME,ExistingWorkPolicy.KEEP,onceBuilder.build())
        }
    }

    private fun cancelPendingWork(context:Context) {
        val manager=WorkManager.getInstance(context)
        manager.cancelUniqueWork(UNIQUE_ONCE_NAME)
        manager.cancelUniqueWork(UNIQUE_PERIODIC_NAME)
    }

    const val UNIQUE_ONCE_NAME=INTERNATIONAL_ODDS_ONCE_WORK_NAME
    const val UNIQUE_PERIODIC_NAME=INTERNATIONAL_ODDS_PERIODIC_WORK_NAME
}

internal fun isMoviOddsAgeConfirmed(context:Context):Boolean =
    BuildConfig.MOVIODDS_AGE_CONFIRMED_LOCALLY ||
        context.getSharedPreferences(MOVIODDS_AGE_CONSENT_PREFS,Context.MODE_PRIVATE)
            .getBoolean(MOVIODDS_AGE_CONSENT_KEY,false)

class InternationalOddsMarketWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    internal var providerHttpClient:ProviderHttpClient=PublicJsonClient()

    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        val store=Store(applicationContext)
        val checkedAt=System.currentTimeMillis()
        try {
            if(!isMoviOddsAgeConfirmed(applicationContext)) {
                store.saveInternationalOddsCapture(
                    checkedAt,InternationalOddsStatus.UNVERIFIED,emptyList(),
                    "Автоматическое обновление приостановлено: условия MoviOdds требуют подтверждения возраста 18+.",
                    replaceCurrentBoard=true,
                )
                return@withContext Result.success()
            }
            val response=providerHttpClient.get(MOVIODDS_API_URL)
            if(response.statusCode in setOf(401,403,451)) {
                store.saveInternationalOddsCapture(checkedAt,InternationalOddsStatus.BLOCKED,emptyList(),"Источник отказал в публичном доступе (HTTP ${response.statusCode}); повторные запросы не выполняются с обходом защиты.",replaceCurrentBoard=true)
                return@withContext Result.success()
            }
            if(response.statusCode==429||response.statusCode==503) {
                store.saveInternationalOddsCapture(checkedAt,InternationalOddsStatus.ERROR,emptyList(),"Источник временно ограничил запрос (HTTP ${response.statusCode}); WorkManager повторит позже.")
                val queued=response.retryAfterDelayMillis?.let { delay ->
                    enqueueDelayedNetworkRetry<InternationalOddsMarketWorker>(applicationContext,INTERNATIONAL_ODDS_ONCE_WORK_NAME,delay)
                } == true
                return@withContext if(queued) Result.success() else Result.retry()
            }
            if(response.statusCode !in 200..299) throw IOException("Unexpected HTTP ${response.statusCode} from MoviOdds.")
            val parsed=Bet365ViaMoviOddsProvider().parse(response.body,checkedAt,store.matchIdentities())
            store.saveInternationalOddsCapture(
                checkedAt,parsed.status,parsed.snapshots,parsed.detail,
                replaceCurrentBoard=true,qualityCounts=parsed.qualityCounts,
            )
            store.relinkUnmatchedInternationalOdds(checkedAt)
            Result.success()
        } catch(error:ProviderHttpException) {
            val blocked=error.statusCode in setOf(401,403,451)
            val status=if(blocked) InternationalOddsStatus.BLOCKED else InternationalOddsStatus.ERROR
            store.saveInternationalOddsCapture(checkedAt,status,emptyList(),safeError(error),replaceCurrentBoard=blocked)
            val delay=error.retryAfterDelayMillis
            val queued=!blocked && error.shouldStopProviderRequests() && delay!=null &&
                enqueueDelayedNetworkRetry<InternationalOddsMarketWorker>(applicationContext,INTERNATIONAL_ODDS_ONCE_WORK_NAME,delay)
            if(blocked || queued) Result.success() else Result.retry()
        } catch(error:Exception) {
            store.saveInternationalOddsCapture(checkedAt,InternationalOddsStatus.ERROR,emptyList(),safeError(error))
            Result.retry()
        } finally {
            store.close()
        }
    }
}

class AutomaticFootballSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    internal var providerHttpClient: ProviderHttpClient = PublicJsonClient()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = Store(applicationContext)
        var syncLockAcquired = false
        try {
        if (!store.tryAcquireProviderSyncLock(System.currentTimeMillis(), TimeUnit.MINUTES.toMillis(9))) {
            setProgress(workDataOf("stage" to "waiting_for_sync_lock"))
            return@withContext Result.retry()
        }
        syncLockAcquired = true
        val startedAt = System.currentTimeMillis()
        val deadlines = syncRunDeadlines(startedAt)
        val today = LocalDate.now(ZoneOffset.UTC)
        var anySuccess = false
        var retryNeeded = false
        var continuationNeeded = false
        var retryAfterDelayMillis: Long? = null
        val summaryText = mutableListOf<String>()
        var openFootballRecords = 0

        suspend fun syncOpenFootball() {
            setProgress(workDataOf("stage" to "OpenFootball", "openFootballRecords" to openFootballRecords))
            val result = runCatching {
                OpenFootballSync(store, providerHttpClient).sync(today, startedAt, deadlines.openFootballDeadlineMillis)
            }
            result.onSuccess { outcome ->
                openFootballRecords = outcome.records
                store.saveProviderSync(
                    ProviderSyncSummary(
                        OpenFootballJsonParser.PROVIDER_ID,
                        outcome.status,
                        System.currentTimeMillis(),
                        outcome.successAtMillis,
                        outcome.records,
                        outcome.errors.takeIf { it.isNotEmpty() }?.joinToString("; ")?.take(500),
                    ),
                )
                anySuccess = anySuccess || outcome.successAtMillis != null
                retryNeeded = retryNeeded || outcome.retryNeeded
                continuationNeeded = continuationNeeded || outcome.continuationNeeded
                retryAfterDelayMillis = combineRetryAfterDelays(retryAfterDelayMillis, outcome.retryAfterDelayMillis)
                summaryText += "OpenFootball ${outcome.status}: ${outcome.records}"
            }.onFailure { error ->
                retryNeeded = true
                store.saveProviderSync(ProviderSyncSummary(OpenFootballJsonParser.PROVIDER_ID, "ERROR", System.currentTimeMillis(), null, 0, safeError(error)))
                summaryText += "OpenFootball: ошибка каталога"
            }
        }

        suspend fun syncOpenLiga() {
            setProgress(workDataOf("stage" to "OpenLigaDB", "openFootballRecords" to openFootballRecords))
            val result = runCatching {
                OpenLigaDbSync(store, providerHttpClient).sync(today, startedAt, deadlines.workerDeadlineMillis)
            }
            result.onSuccess { outcome ->
                store.saveProviderSync(
                    ProviderSyncSummary(
                        OpenLigaDbParser.PROVIDER_ID,
                        outcome.status,
                        System.currentTimeMillis(),
                        outcome.successAtMillis,
                        outcome.records,
                        outcome.errors.takeIf { it.isNotEmpty() }?.joinToString("; ")?.take(500),
                    ),
                )
                anySuccess = anySuccess || outcome.successAtMillis != null
                retryNeeded = retryNeeded || outcome.retryNeeded
                continuationNeeded = continuationNeeded || outcome.continuationNeeded
                retryAfterDelayMillis = combineRetryAfterDelays(retryAfterDelayMillis, outcome.retryAfterDelayMillis)
                summaryText += "OpenLigaDB ${outcome.status}: ${outcome.records}"
            }.onFailure { error ->
                retryNeeded = true
                store.saveProviderSync(ProviderSyncSummary(OpenLigaDbParser.PROVIDER_ID, "ERROR", System.currentTimeMillis(), null, 0, safeError(error)))
                summaryText += "OpenLigaDB: ошибка каталога"
            }
        }

        syncOpenFootball()
        syncOpenLiga()

        val linkedOdds = runCatching { store.relinkUnmatchedBookmakerOdds() }.getOrDefault(0)
        val linkedMarketReferences = runCatching { store.relinkUnmatchedMarketReferences() }.getOrDefault(0)
        setProgress(workDataOf("stage" to "prospective_forecasts"))
        val forecastAtMillis = System.currentTimeMillis()
        var prospectiveSnapshotsSaved = 0
        var prospectiveSnapshotsSettled = 0
        runCatching {
            val forecasts = buildProspectiveForecasts(
                matches = store.matches(),
                historicalByLeague = store.historicalResultsByLeague(),
                forecastedAtMillis = forecastAtMillis,
            )
            prospectiveSnapshotsSaved = store.recordProspectiveForecastSnapshots(forecasts, forecastAtMillis)
            prospectiveSnapshotsSettled = store.settleProspectiveForecastSnapshots(forecastAtMillis)
        }.onFailure { error ->
            retryNeeded = true
            summaryText += "Прогнозы: ${safeError(error)}"
        }
        if (prospectiveSnapshotsSaved > 0 || prospectiveSnapshotsSettled > 0) {
            summaryText += "Прогнозы сохранены: $prospectiveSnapshotsSaved; сверены: $prospectiveSnapshotsSettled"
        }
        val nextStep = decideSyncNextStep(retryNeeded, continuationNeeded, anySuccess)
        setProgress(workDataOf("stage" to if (nextStep == SyncNextStep.CONTINUE) "continuation_queued" else "complete", "summary" to summaryText.joinToString(" · "), "bookmakerFixturesLinked" to linkedOdds, "marketFixturesLinked" to linkedMarketReferences, "prospectiveSnapshotsSaved" to prospectiveSnapshotsSaved, "prospectiveSnapshotsSettled" to prospectiveSnapshotsSettled))
        when (nextStep) {
            SyncNextStep.RETRY -> {
                val delayMillis = retryAfterDelayMillis
                if (delayMillis != null && enqueueDelayedAutomaticRetry(delayMillis)) Result.success() else Result.retry()
            }
            SyncNextStep.SUCCESS -> Result.success()
            SyncNextStep.CONTINUE -> if (enqueueContinuation()) Result.success() else Result.retry()
        }
        } finally {
            try {
                if (syncLockAcquired) store.releaseProviderSyncLock()
            } finally {
                store.close()
            }
        }
    }

    private fun enqueueContinuation(): Boolean = runCatching {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val continuation = withAutomaticSyncRetryBackoff(
            OneTimeWorkRequestBuilder<AutomaticFootballSyncWorker>().setConstraints(constraints),
        ).build()
        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(ONCE_SYNC_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, continuation)
    }.isSuccess

    private fun enqueueDelayedAutomaticRetry(delayMillis: Long): Boolean =
        enqueueDelayedNetworkRetry<AutomaticFootballSyncWorker>(applicationContext, ONCE_SYNC_NAME, delayMillis)
}

internal data class SyncOutcome(
    val status: String,
    val records: Int,
    val successAtMillis: Long?,
    val retryNeeded: Boolean,
    val continuationNeeded: Boolean,
    val errors: List<String>,
    val retryAfterDelayMillis: Long? = null,
)

internal data class ProviderHttpResult(
    val statusCode: Int,
    val body: String,
    val etag: String?,
    val lastModified: String?,
    val retryAfterDelayMillis: Long? = null,
    val serverDateMillis: Long? = null,
    val oddsApiRequestsRemaining: Int? = null,
    val oddsApiRequestsUsed: Int? = null,
    val oddsApiRequestCost: Int? = null,
)

internal class ProviderHttpException(
    val statusCode:Int,
    val retryAfterDelayMillis:Long? = null,
):IOException("HTTP $statusCode from provider.")

private fun safeError(error: Throwable): String = (error.message ?: error.javaClass.simpleName).take(400)

internal fun parseHttpDateMillis(value:String?):Long? {
    val header=value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching {
        ZonedDateTime.parse(header,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrNull()
}

internal interface ProviderHttpClient {
    fun get(url: String, cache: ProviderFileCache? = null): ProviderHttpResult
    fun getWithHeaders(url:String,headers:Map<String,String>):ProviderHttpResult {
        require(headers.isEmpty()) { "This provider client does not support additional request headers." }
        return get(url)
    }
}

internal class PublicJsonClient : ProviderHttpClient {
    override fun get(url: String, cache: ProviderFileCache?): ProviderHttpResult = execute(url,cache,emptyMap())

    override fun getWithHeaders(url:String,headers:Map<String,String>):ProviderHttpResult = execute(url,null,headers)

    private fun execute(url:String,cache:ProviderFileCache?,headers:Map<String,String>):ProviderHttpResult {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding","gzip")
            connection.setRequestProperty("User-Agent", "Football-Intelligence-Android/0.1 (personal open-data client)")
            headers.forEach { (name,value) -> connection.setRequestProperty(name,value) }
            cache?.etag?.let { connection.setRequestProperty("If-None-Match", it) }
            cache?.lastModified?.let { connection.setRequestProperty("If-Modified-Since", it) }
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return ProviderHttpResult(
                    code,"",connection.getHeaderField("ETag"),connection.getHeaderField("Last-Modified"),
                    serverDateMillis=parseHttpDateMillis(connection.getHeaderField("Date")),
                )
            }
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                return ProviderHttpResult(
                    code,"",connection.getHeaderField("ETag"),connection.getHeaderField("Last-Modified"),
                    serverDateMillis=parseHttpDateMillis(connection.getHeaderField("Date")),
                )
            }
            if (code !in 200..299) {
                throw ProviderHttpException(
                    code,
                    parseRetryAfterDelayMillis(connection.getHeaderField("Retry-After"), System.currentTimeMillis()),
                )
            }
            val rawInput = connection.inputStream
            val input = if(connection.getHeaderField("Content-Encoding").orEmpty().contains("gzip",ignoreCase=true)) GZIPInputStream(rawInput) else rawInput
            val output = ByteArrayOutputStream()
            input.use { stream ->
                val buffer = ByteArray(8_192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 16 * 1024 * 1024) throw IOException("Response exceeds 16 MiB uncompressed limit.")
                    output.write(buffer, 0, count)
                }
            }
            return ProviderHttpResult(
                code,
                output.toString(StandardCharsets.UTF_8.name()),
                connection.getHeaderField("ETag"),
                connection.getHeaderField("Last-Modified"),
                serverDateMillis=parseHttpDateMillis(connection.getHeaderField("Date")),
                oddsApiRequestsRemaining=connection.getHeaderField("x-requests-remaining")?.toIntOrNull(),
                oddsApiRequestsUsed=connection.getHeaderField("x-requests-used")?.toIntOrNull(),
                oddsApiRequestCost=connection.getHeaderField("x-requests-last")?.toIntOrNull(),
            )
        } finally {
            connection.disconnect()
        }
    }
}

internal class OpenFootballSync(private val store: Store, private val http: ProviderHttpClient) {
    private val recentFileTtl = TimeUnit.HOURS.toMillis(24)
    private val historicalFileTtl = TimeUnit.DAYS.toMillis(30)
    private val currentCatalogTtl = TimeUnit.HOURS.toMillis(24)
    private val historicalCatalogTtl = TimeUnit.DAYS.toMillis(30)

    fun sync(today: LocalDate, syncStartedAt: Long, runtimeDeadlineMillis: Long): SyncOutcome {
        val errors = mutableListOf<String>()
        var recordCount = 0
        var completedFiles = 0
        var needsRetry = false
        var continuationNeeded = false
        var consecutiveErrors = 0
        val seasons = OpenFootballJsonParser.planSeasons(today, 5)
        var retryAfterDelayMillis: Long? = null
        seasonLoop@ for (season in seasons) {
            if (System.currentTimeMillis() >= runtimeDeadlineMillis) {
                continuationNeeded = true
                errors += SYNC_CONTINUATION_MESSAGE
                break
            }
            val currentSeason = season == seasons.first()
            val directory = try {
                getSeasonDirectory(season, currentSeason, syncStartedAt, errors)
            } catch (error: ProviderHttpException) {
                if (!error.shouldStopProviderRequests()) throw error
                errors += "catalog $season: ${safeError(error)}"
                needsRetry = true
                retryAfterDelayMillis = combineRetryAfterDelays(retryAfterDelayMillis, error.retryAfterDelayMillis)
                break@seasonLoop
            }
            if (directory.isEmpty()) continue
            for (file in directory) {
                if (System.currentTimeMillis() >= runtimeDeadlineMillis) {
                    continuationNeeded = true
                    errors += SYNC_CONTINUATION_MESSAGE
                    break
                }
                val key = "openfootball:${file.season}:${file.fileName}"
                val cached = store.providerFileCache(key)
                val ttl = if (currentSeason) recentFileTtl else historicalFileTtl
                if (cached != null && syncStartedAt - cached.lastCheckedAtMillis < ttl) {
                    completedFiles++
                    recordCount += cached.recordCount
                    continue
                }
                try {
                    val response = http.get(file.downloadUrl, cached)
                    when (response.statusCode) {
                        HttpURLConnection.HTTP_NOT_MODIFIED -> {
                            store.saveProviderFileCache(key, OpenFootballJsonParser.PROVIDER_ID, response.etag, response.lastModified, System.currentTimeMillis(), cached?.recordCount ?: 0)
                            recordCount += cached?.recordCount ?: 0
                            completedFiles++
                            consecutiveErrors = 0
                        }
                        HttpURLConnection.HTTP_NOT_FOUND -> {
                            store.saveProviderFileCache(key, OpenFootballJsonParser.PROVIDER_ID, response.etag, response.lastModified, System.currentTimeMillis(), 0)
                            completedFiles++
                            consecutiveErrors = 0
                        }
                        else -> {
                            val rows = OpenFootballJsonParser.parse(response.body, file.season, file.fileName, file.downloadUrl, System.currentTimeMillis())
                            store.upsertProviderMatches(rows)
                            store.saveProviderFileCache(key, OpenFootballJsonParser.PROVIDER_ID, response.etag, response.lastModified, System.currentTimeMillis(), rows.size)
                            recordCount += rows.size
                            completedFiles++
                            consecutiveErrors = 0
                        }
                    }
                } catch (error: Exception) {
                    errors += "${file.season}/${file.fileName}: ${safeError(error)}"
                    needsRetry = true
                    if (error is ProviderHttpException && error.shouldStopProviderRequests()) {
                        retryAfterDelayMillis = combineRetryAfterDelays(retryAfterDelayMillis, error.retryAfterDelayMillis)
                        break@seasonLoop
                    }
                    consecutiveErrors++
                    if (consecutiveErrors >= 3) break
                }
            }
            if (consecutiveErrors >= 3) break
        }
        return SyncOutcome(
            status = automaticProviderSyncStatus(
                completedFiles,
                errors.any { it != SYNC_CONTINUATION_MESSAGE },
                continuationNeeded,
            ),
            records = recordCount,
            successAtMillis = System.currentTimeMillis().takeIf { completedFiles > 0 },
            retryNeeded = needsRetry || errors.any { it != SYNC_CONTINUATION_MESSAGE },
            continuationNeeded = continuationNeeded,
            errors = errors,
            retryAfterDelayMillis = retryAfterDelayMillis,
        )
    }

    private fun getSeasonDirectory(season: String, currentSeason: Boolean, now: Long, errors: MutableList<String>): List<OpenFootballCompetitionFile> {
        val key = "openfootball:directory:$season"
        val cached = store.providerFileCache(key)
        val ttl = if (currentSeason) currentCatalogTtl else historicalCatalogTtl
        if (cached?.cachedBody != null && now - cached.lastCheckedAtMillis < ttl) {
            return OpenFootballJsonParser.parseSeasonDirectory(cached.cachedBody, season)
        }
        val url = "https://api.github.com/repos/openfootball/football.json/contents/$season"
        return try {
            val response = http.get(url, cached)
            when (response.statusCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    store.saveProviderFileCache(key, OpenFootballJsonParser.PROVIDER_ID, response.etag, response.lastModified, System.currentTimeMillis(), cached?.recordCount ?: 0, cached?.cachedBody)
                    cached?.cachedBody?.let { OpenFootballJsonParser.parseSeasonDirectory(it, season) }.orEmpty()
                }
                HttpURLConnection.HTTP_NOT_FOUND -> {
                    store.saveProviderFileCache(key, OpenFootballJsonParser.PROVIDER_ID, response.etag, response.lastModified, System.currentTimeMillis(), 0)
                    emptyList()
                }
                else -> {
                    val files = OpenFootballJsonParser.parseSeasonDirectory(response.body, season)
                    store.saveProviderFileCache(key, OpenFootballJsonParser.PROVIDER_ID, response.etag, response.lastModified, System.currentTimeMillis(), files.size, response.body)
                    files
                }
            }
        } catch (error: Exception) {
            if (error is ProviderHttpException && error.shouldStopProviderRequests()) throw error
            errors += "catalog $season: ${safeError(error)}"
            // Use the last successful catalog for ordinary failures; 429/503 propagate to stop this source.
            cached?.cachedBody?.let { OpenFootballJsonParser.parseSeasonDirectory(it, season) }.orEmpty()
        }
    }
}

internal class OpenLigaDbSync(private val store: Store, private val http: ProviderHttpClient) {
    private var lastRequestAtMillis = 0L
    private val weeklyFullSyncTtl = TimeUnit.DAYS.toMillis(7)
    private val checkTtl = TimeUnit.HOURS.toMillis(12)
    fun sync(today: LocalDate, syncStartedAt: Long, runtimeDeadlineMillis: Long): SyncOutcome {
        val errors = mutableListOf<String>()
        var recordCount = 0
        var successfulLeagues = 0
        var catalogsFound = 0
        var retryNeeded = false
        var continuationNeeded = false
        var retryAfterDelayMillis: Long? = null
        seasonLoop@ for (seasonYear in today.year downTo today.year - 4) {
            if (System.currentTimeMillis() >= runtimeDeadlineMillis) {
                continuationNeeded = true
                errors += SYNC_CONTINUATION_MESSAGE
                break@seasonLoop
            }
            val catalog = try {
                getCatalog(seasonYear, syncStartedAt)
            } catch (error: ProviderHttpException) {
                if (!error.shouldStopProviderRequests()) throw error
                errors += "catalog $seasonYear: ${safeError(error)}"
                retryNeeded = true
                retryAfterDelayMillis = combineRetryAfterDelays(retryAfterDelayMillis, error.retryAfterDelayMillis)
                break@seasonLoop
            }
            if (catalog.isEmpty()) continue
            catalogsFound++
            for (league in catalog) {
                if (System.currentTimeMillis() >= runtimeDeadlineMillis) {
                    continuationNeeded = true
                    errors += SYNC_CONTINUATION_MESSAGE
                    break@seasonLoop
                }
                val leagueKey = "${league.shortcut.lowercase()}:${league.season}"
                val state = store.openLigaState(leagueKey)
                if (state != null && System.currentTimeMillis() - state.checkedAtMillis < checkTtl &&
                    System.currentTimeMillis() - state.lastFullSyncAtMillis < weeklyFullSyncTtl && state.sourceChange != null
                ) {
                    successfulLeagues++
                    recordCount += state.recordCount
                    continue
                }
                val fullSyncDue = state == null || state.sourceChange == null ||
                    System.currentTimeMillis() - state.lastFullSyncAtMillis >= weeklyFullSyncTtl
                try {
                    var sourceChange: String? = state?.sourceChange
                    var currentGroup: Int? = state?.currentGroupOrderId
                    var fullSync = fullSyncDue
                    // OpenLigaDB's current-group endpoint has no season argument. Use it only for
                    // the current calendar-year catalog; historical seasons refresh weekly.
                    if (!fullSync && seasonYear == today.year) {
                        val observedGroup = getCurrentGroup(league.shortcut)
                        if (observedGroup == null || observedGroup != state?.currentGroupOrderId) {
                            fullSync = true
                            currentGroup = observedGroup
                        } else {
                            currentGroup = observedGroup
                            val changed = getLastChanged(league, observedGroup)
                            if (changed == null) {
                                fullSync = true
                            } else if (OpenLigaDbParser.parseLastChangedAtMillis(changed)!! >
                                (sourceChange?.let(OpenLigaDbParser::parseLastChangedAtMillis) ?: 0L)
                            ) {
                                fullSync = true
                                sourceChange = changed
                            }
                        }
                    }
                    if (fullSync) {
                        val url = "${OpenLigaDbParser.BASE_URL}/getmatchdata/${league.shortcut}/${league.season}"
                        val response = throttledGet(url)
                        if (response.statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
                            store.saveOpenLigaState(leagueKey, sourceChange, currentGroup, System.currentTimeMillis(), System.currentTimeMillis(), 0)
                            successfulLeagues++
                            continue
                        }
                        val rows = OpenLigaDbParser.parseMatches(response.body, league, System.currentTimeMillis(), url)
                        store.upsertProviderMatches(rows)
                        recordCount += rows.size
                        successfulLeagues++
                        if (currentGroup == null) currentGroup = OpenLigaDbParser.latestGroupOrderId(response.body)
                        if (currentGroup != null && (fullSyncDue || sourceChange == null || sourceChange == state?.sourceChange)) {
                            sourceChange = getLastChanged(league, currentGroup)
                        }
                        store.saveOpenLigaState(leagueKey, sourceChange, currentGroup, System.currentTimeMillis(), System.currentTimeMillis(), rows.size)
                    } else {
                        store.saveOpenLigaState(leagueKey, sourceChange, currentGroup, 0L, System.currentTimeMillis(), state?.recordCount ?: 0)
                        successfulLeagues++
                        recordCount += state?.recordCount ?: 0
                    }
                } catch (error: Exception) {
                    errors += "${league.shortcut}/${league.season}: ${safeError(error)}"
                    retryNeeded = true
                    if (error is ProviderHttpException && error.shouldStopProviderRequests()) {
                        retryAfterDelayMillis = combineRetryAfterDelays(retryAfterDelayMillis, error.retryAfterDelayMillis)
                        store.saveOpenLigaState(leagueKey, state?.sourceChange, state?.currentGroupOrderId, 0L, 0L, state?.recordCount ?: 0)
                        break@seasonLoop
                    }
                    // A failed check must not mark this league fresh; WorkManager's retry should retry it.
                    store.saveOpenLigaState(leagueKey, state?.sourceChange, state?.currentGroupOrderId, 0L, 0L, state?.recordCount ?: 0)
                }
            }
        }
        if (catalogsFound == 0 && !continuationNeeded) {
            return SyncOutcome(
                "ERROR", 0, null, true, false,
                errors.ifEmpty { listOf("OpenLigaDB football catalogs were unavailable.") },
                retryAfterDelayMillis,
            )
        }
        return SyncOutcome(
            status = automaticProviderSyncStatus(
                successfulLeagues,
                errors.any { it != SYNC_CONTINUATION_MESSAGE },
                continuationNeeded,
            ),
            records = recordCount,
            successAtMillis = System.currentTimeMillis().takeIf { successfulLeagues > 0 },
            retryNeeded = retryNeeded,
            continuationNeeded = continuationNeeded,
            errors = errors,
            retryAfterDelayMillis = retryAfterDelayMillis,
        )
    }

    private fun getCatalog(season: Int, now: Long): List<OpenLigaLeague> {
        val key = "openligadb:catalog:$season"
        val cached = store.providerFileCache(key)
        val ttl = TimeUnit.HOURS.toMillis(24)
        if (cached?.cachedBody != null && now - cached.lastCheckedAtMillis < ttl) {
            return OpenLigaDbParser.parseAvailableLeagues(cached.cachedBody, season.toString())
        }
        val url = "${OpenLigaDbParser.BASE_URL}/getavailableleagues/$season"
        val response = try {
            throttledGet(url, cached)
        } catch (error: ProviderHttpException) {
            if (error.shouldStopProviderRequests()) throw error
            return cached?.cachedBody?.let { OpenLigaDbParser.parseAvailableLeagues(it, season.toString()) }.orEmpty()
        } catch (_: Exception) {
            return cached?.cachedBody?.let { OpenLigaDbParser.parseAvailableLeagues(it, season.toString()) }.orEmpty()
        }
        if (response.statusCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
            store.saveProviderFileCache(key, OpenLigaDbParser.PROVIDER_ID, response.etag, response.lastModified, System.currentTimeMillis(), cached?.recordCount ?: 0, cached?.cachedBody)
            return cached?.cachedBody?.let { OpenLigaDbParser.parseAvailableLeagues(it, season.toString()) }.orEmpty()
        }
        if (response.statusCode == HttpURLConnection.HTTP_NOT_FOUND) return emptyList()
        val leagues = OpenLigaDbParser.parseAvailableLeagues(response.body, season.toString())
        store.saveProviderFileCache(key, OpenLigaDbParser.PROVIDER_ID, response.etag, response.lastModified, System.currentTimeMillis(), leagues.size, response.body)
        return leagues
    }

    private fun getCurrentGroup(shortcut: String): Int? {
        val response = throttledGet("${OpenLigaDbParser.BASE_URL}/getcurrentgroup/$shortcut")
        return if (response.statusCode == HttpURLConnection.HTTP_NOT_FOUND) null
        else OpenLigaDbParser.parseCurrentGroupOrderId(response.body)
    }

    private fun getLastChanged(league: OpenLigaLeague, groupOrderId: Int): String? {
        val url = "${OpenLigaDbParser.BASE_URL}/getlastchangedate/${league.shortcut}/${league.season}/$groupOrderId"
        val response = throttledGet(url)
        if (response.statusCode == HttpURLConnection.HTTP_NOT_FOUND) return null
        val raw = response.body.trim().removeSurrounding("\"")
        val normalized = az.simplesoft.footballintelligence.analytics.normalizeProviderTimestampUtc(raw) ?: return null
        return normalized
    }

    private fun throttledGet(url: String, cache: ProviderFileCache? = null): ProviderHttpResult {
        val waitMillis = MIN_OPENLIGA_REQUEST_GAP_MS - (System.currentTimeMillis() - lastRequestAtMillis)
        if (waitMillis > 0) Thread.sleep(waitMillis)
        lastRequestAtMillis = System.currentTimeMillis()
        return http.get(url, cache)
    }
}
