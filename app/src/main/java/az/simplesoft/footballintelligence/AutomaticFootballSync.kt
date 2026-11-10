package az.simplesoft.footballintelligence

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import az.simplesoft.footballintelligence.data.OpenFootballCompetitionFile
import az.simplesoft.footballintelligence.data.OpenFootballJsonParser
import az.simplesoft.footballintelligence.data.OpenLigaDbParser
import az.simplesoft.footballintelligence.data.OpenLigaLeague
import az.simplesoft.footballintelligence.data.ProviderFileCache
import az.simplesoft.footballintelligence.data.ProviderMatch
import az.simplesoft.footballintelligence.data.ProviderSyncSummary
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PERIODIC_SYNC_NAME = "football-intelligence-daily-sync"
private const val ONCE_SYNC_NAME = "football-intelligence-sync-now"
private const val MIN_OPENLIGA_REQUEST_GAP_MS = 1_250L // 48 requests/minute, below the provider's 60/minute cap.

object FootballSyncScheduler {
    fun scheduleOnAppOpen(context: Context) {
        val appContext = context.applicationContext
        val manager = WorkManager.getInstance(appContext)
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val periodic = PeriodicWorkRequestBuilder<AutomaticFootballSyncWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        manager.enqueueUniquePeriodicWork(PERIODIC_SYNC_NAME, ExistingPeriodicWorkPolicy.KEEP, periodic)

        val summaries = runCatching { Store(appContext).providerSyncSummaries() }.getOrDefault(emptyList())
        val mostRecentSuccess = summaries.mapNotNull { it.lastSuccessAtMillis }.maxOrNull() ?: 0L
        val stale = mostRecentSuccess == 0L || System.currentTimeMillis() - mostRecentSuccess >= TimeUnit.HOURS.toMillis(8)
        if (stale) {
            val immediate = OneTimeWorkRequestBuilder<AutomaticFootballSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            manager.enqueueUniqueWork(ONCE_SYNC_NAME, ExistingWorkPolicy.KEEP, immediate)
        }
    }

    const val UNIQUE_ONCE_NAME = ONCE_SYNC_NAME
    const val UNIQUE_PERIODIC_NAME = PERIODIC_SYNC_NAME
}

class AutomaticFootballSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = Store(applicationContext)
        if (!store.tryAcquireProviderSyncLock(System.currentTimeMillis(), TimeUnit.MINUTES.toMillis(9))) {
            setProgress(workDataOf("stage" to "waiting_for_sync_lock"))
            return@withContext Result.retry()
        }
        try {
        val startedAt = System.currentTimeMillis()
        val today = LocalDate.now(ZoneOffset.UTC)
        val seasonCount = 5
        var anySuccess = false
        var retryNeeded = false
        val summaryText = mutableListOf<String>()

        val openFootball = runCatching { OpenFootballSync(store).sync(today, startedAt) }
        openFootball.onSuccess { outcome ->
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
            anySuccess = outcome.successAtMillis != null
            retryNeeded = retryNeeded || outcome.retryNeeded
            summaryText += "OpenFootball ${outcome.status}: ${outcome.records}"
        }.onFailure { error ->
            retryNeeded = true
            store.saveProviderSync(ProviderSyncSummary(OpenFootballJsonParser.PROVIDER_ID, "ERROR", System.currentTimeMillis(), null, 0, safeError(error)))
            summaryText += "OpenFootball: ошибка каталога"
        }
        setProgress(workDataOf("stage" to "OpenLigaDB", "openFootballRecords" to (openFootball.getOrNull()?.records ?: 0)))

        val openLiga = runCatching { OpenLigaDbSync(store).sync(today, startedAt) }
        openLiga.onSuccess { outcome ->
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
            summaryText += "OpenLigaDB ${outcome.status}: ${outcome.records}"
        }.onFailure { error ->
            retryNeeded = true
            store.saveProviderSync(ProviderSyncSummary(OpenLigaDbParser.PROVIDER_ID, "ERROR", System.currentTimeMillis(), null, 0, safeError(error)))
            summaryText += "OpenLigaDB: ошибка каталога"
        }

        setProgress(workDataOf("stage" to "complete", "summary" to summaryText.joinToString(" · ")))
        when {
            retryNeeded -> Result.retry()
            anySuccess -> Result.success()
            else -> Result.retry()
        }
        } finally {
            store.releaseProviderSyncLock()
        }
    }
}

private data class SyncOutcome(
    val status: String,
    val records: Int,
    val successAtMillis: Long?,
    val retryNeeded: Boolean,
    val errors: List<String>,
)

private data class HttpResult(
    val statusCode: Int,
    val body: String,
    val etag: String?,
    val lastModified: String?,
)

private fun safeError(error: Throwable): String = (error.message ?: error.javaClass.simpleName).take(400)

private class PublicJsonClient {
    fun get(url: String, cache: ProviderFileCache? = null): HttpResult {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Football-Intelligence-Android/0.1 (personal open-data client)")
            cache?.etag?.let { connection.setRequestProperty("If-None-Match", it) }
            cache?.lastModified?.let { connection.setRequestProperty("If-Modified-Since", it) }
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return HttpResult(code, "", connection.getHeaderField("ETag"), connection.getHeaderField("Last-Modified"))
            }
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                return HttpResult(code, "", connection.getHeaderField("ETag"), connection.getHeaderField("Last-Modified"))
            }
            if (code !in 200..299) throw IOException("HTTP $code for ${URL(url).host}")
            val input = connection.inputStream
            val output = ByteArrayOutputStream()
            input.use { stream ->
                val buffer = ByteArray(8_192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 8 * 1024 * 1024) throw IOException("Response exceeds 8 MiB limit.")
                    output.write(buffer, 0, count)
                }
            }
            return HttpResult(
                code,
                output.toString(StandardCharsets.UTF_8.name()),
                connection.getHeaderField("ETag"),
                connection.getHeaderField("Last-Modified"),
            )
        } finally {
            connection.disconnect()
        }
    }
}

private class OpenFootballSync(private val store: Store) {
    private val http = PublicJsonClient()
    private val recentFileTtl = TimeUnit.HOURS.toMillis(24)
    private val historicalFileTtl = TimeUnit.DAYS.toMillis(30)
    private val currentCatalogTtl = TimeUnit.HOURS.toMillis(24)
    private val historicalCatalogTtl = TimeUnit.DAYS.toMillis(30)

    fun sync(today: LocalDate, syncStartedAt: Long): SyncOutcome {
        val errors = mutableListOf<String>()
        var recordCount = 0
        var completedFiles = 0
        var needsRetry = false
        var consecutiveErrors = 0
        val seasons = OpenFootballJsonParser.planSeasons(today, 5)
        for (season in seasons) {
            val currentSeason = season == seasons.first()
            val directory = getSeasonDirectory(season, currentSeason, syncStartedAt, errors)
            if (directory.isEmpty()) continue
            for (file in directory) {
                if (System.currentTimeMillis() - syncStartedAt >= TimeUnit.MINUTES.toMillis(8)) {
                    needsRetry = true
                    errors += "sync continues on the next WorkManager attempt"
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
                    consecutiveErrors++
                    if (consecutiveErrors >= 3) break
                }
            }
            if (consecutiveErrors >= 3 || System.currentTimeMillis() - syncStartedAt >= TimeUnit.MINUTES.toMillis(8)) break
        }
        return SyncOutcome(
            status = when {
                completedFiles == 0 -> "ERROR"
                errors.isNotEmpty() -> "PARTIAL"
                else -> "OK"
            },
            records = recordCount,
            successAtMillis = System.currentTimeMillis().takeIf { completedFiles > 0 },
            retryNeeded = needsRetry || errors.isNotEmpty(),
            errors = errors,
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
            errors += "catalog $season: ${safeError(error)}"
            // Use the last successful catalog while offline or rate-limited.
            cached?.cachedBody?.let { OpenFootballJsonParser.parseSeasonDirectory(it, season) }.orEmpty()
        }
    }
}

private class OpenLigaDbSync(private val store: Store) {
    private val http = PublicJsonClient()
    private var lastRequestAtMillis = 0L
    private val weeklyFullSyncTtl = TimeUnit.DAYS.toMillis(7)
    private val checkTtl = TimeUnit.HOURS.toMillis(12)
    private val maxRuntimeMillis = TimeUnit.MINUTES.toMillis(8)

    fun sync(today: LocalDate, syncStartedAt: Long): SyncOutcome {
        val errors = mutableListOf<String>()
        var recordCount = 0
        var successfulLeagues = 0
        var catalogsFound = 0
        var retryNeeded = false
        val started = syncStartedAt

        seasonLoop@ for (seasonYear in today.year downTo today.year - 4) {
            val catalog = getCatalog(seasonYear, syncStartedAt)
            if (catalog.isEmpty()) continue
            catalogsFound++
            for (league in catalog) {
                if (System.currentTimeMillis() - started >= maxRuntimeMillis) {
                    retryNeeded = true
                    errors += "sync continues on the next WorkManager attempt"
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
                    // A failed check must not mark this league fresh; WorkManager's retry should retry it.
                    store.saveOpenLigaState(leagueKey, state?.sourceChange, state?.currentGroupOrderId, 0L, 0L, state?.recordCount ?: 0)
                }
            }
        }
        if (catalogsFound == 0) {
            return SyncOutcome("ERROR", 0, null, true, listOf("OpenLigaDB football catalogs were unavailable."))
        }
        return SyncOutcome(
            status = when {
                successfulLeagues == 0 -> "ERROR"
                errors.isNotEmpty() -> "PARTIAL"
                else -> "OK"
            },
            records = recordCount,
            successAtMillis = System.currentTimeMillis().takeIf { successfulLeagues > 0 },
            retryNeeded = retryNeeded,
            errors = errors,
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
        val response = runCatching { throttledGet(url, cached) }.getOrElse {
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

    private fun throttledGet(url: String, cache: ProviderFileCache? = null): HttpResult {
        val waitMillis = MIN_OPENLIGA_REQUEST_GAP_MS - (System.currentTimeMillis() - lastRequestAtMillis)
        if (waitMillis > 0) Thread.sleep(waitMillis)
        lastRequestAtMillis = System.currentTimeMillis()
        return http.get(url, cache)
    }
}
