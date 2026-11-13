package az.simplesoft.footballintelligence

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import az.simplesoft.footballintelligence.data.FixtureNewsMatch
import az.simplesoft.footballintelligence.data.GDELT_NEWS_CACHE_KEY
import az.simplesoft.footballintelligence.data.GDELT_NEWS_PROVIDER_ID
import az.simplesoft.footballintelligence.data.GdeltNewsProvider
import az.simplesoft.footballintelligence.data.ProviderSyncSummary
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val NEWS_ONCE_WORK_NAME = "football-news-intelligence-now"
private const val NEWS_PERIODIC_WORK_NAME = "football-news-intelligence-periodic"
private const val NEWS_REFRESH_HOURS = 24L
private const val NEWS_MIN_ATTEMPT_INTERVAL_MILLIS = 12 * 60 * 60 * 1_000L

object NewsIntelligenceScheduler {
    const val UNIQUE_ONCE_NAME = NEWS_ONCE_WORK_NAME
    const val UNIQUE_PERIODIC_NAME = NEWS_PERIODIC_WORK_NAME

    fun scheduleOnAppOpen(context: Context) {
        val app = context.applicationContext
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val manager = WorkManager.getInstance(app)
        manager.enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<NewsIntelligenceWorker>(NEWS_REFRESH_HOURS, TimeUnit.HOURS)
                .setInitialDelay(NEWS_REFRESH_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build(),
        )
        manager.enqueueUniqueWork(
            UNIQUE_ONCE_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<NewsIntelligenceWorker>().setConstraints(constraints).build(),
        )
    }
}

class NewsIntelligenceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    internal var providerHttpClient: ProviderHttpClient = PublicJsonClient()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = Store(applicationContext)
        var lock = false
        try {
            val now = System.currentTimeMillis()
            val previous = store.providerSyncSummaries().firstOrNull { it.providerId == GDELT_NEWS_PROVIDER_ID }
            if (previous != null && now - previous.lastCheckedAtMillis < NEWS_MIN_ATTEMPT_INTERVAL_MILLIS) {
                setProgress(androidx.work.workDataOf("stage" to "news_cooldown"))
                return@withContext Result.success()
            }
            if (!store.tryAcquireProviderSyncLock(now, TimeUnit.MINUTES.toMillis(9))) return@withContext Result.retry()
            lock = true
            val teams = upcomingNewsTeams(store.matches(), now)
            val url = GdeltNewsProvider.requestUrl(teams)
            if (url == null) {
                store.saveProviderSync(ProviderSyncSummary(
                    GDELT_NEWS_PROVIDER_ID, "NO_FIXTURES", now, null, 0,
                    "Нет точных будущих матчей, по командам которых можно выполнить поиск.",
                ))
                return@withContext Result.success()
            }
            setProgress(androidx.work.workDataOf("stage" to "GDELT_news"))
            try {
                val response = providerHttpClient.get(url)
                if (response.statusCode == 304) {
                    val cached = store.providerFileCache(GDELT_NEWS_CACHE_KEY)
                    store.saveProviderFileCache(
                        GDELT_NEWS_CACHE_KEY, GDELT_NEWS_PROVIDER_ID, response.etag, response.lastModified,
                        now, cached?.recordCount ?: 0, cached?.cachedBody,
                    )
                    store.saveProviderSync(ProviderSyncSummary(
                        GDELT_NEWS_PROVIDER_ID, "OK", now, now, cached?.recordCount ?: 0, null,
                    ))
                    return@withContext Result.success()
                }
                if (response.statusCode !in 200..299) error("HTTP ${response.statusCode} from GDELT.")
                val articles = GdeltNewsProvider.parse(response.body, now)
                store.saveProviderFileCache(
                    GDELT_NEWS_CACHE_KEY, GDELT_NEWS_PROVIDER_ID, response.etag, response.lastModified,
                    now, articles.size, response.body,
                )
                store.saveProviderSync(ProviderSyncSummary(
                    GDELT_NEWS_PROVIDER_ID, if (articles.isEmpty()) "EMPTY" else "OK", now, now,
                    articles.size, null,
                ))
                setProgress(androidx.work.workDataOf("stage" to "complete", "articles" to articles.size))
            } catch (error: CancellationException) {
                throw error
            } catch (error: ProviderHttpException) {
                val state = if (error.statusCode == 429) "RATE_LIMITED" else "ERROR"
                val detail = if (error.statusCode == 429) {
                    "GDELT ограничил частоту запросов; сохранённые заголовки оставлены без изменений."
                } else "Ошибка получения новостей (HTTP ${error.statusCode})."
                store.saveProviderSync(ProviderSyncSummary(GDELT_NEWS_PROVIDER_ID, state, now, null, 0, detail))
            } catch (error: Exception) {
                val detail = when {
                    error.message.orEmpty().contains("frequency limit", ignoreCase = true) ->
                        "GDELT ограничил частоту запросов; сохранённые заголовки оставлены без изменений."
                    error is java.io.IOException -> "Сеть временно недоступна; сохранённые заголовки оставлены без изменений."
                    else -> "Ответ GDELT не прошёл проверку; кэш не заменён."
                }
                val state = if (detail.startsWith("GDELT ограничил")) "RATE_LIMITED" else "ERROR"
                store.saveProviderSync(ProviderSyncSummary(GDELT_NEWS_PROVIDER_ID, state, now, null, 0, detail))
            }
            return@withContext Result.success()
        } finally {
            try {
                if (lock) store.releaseProviderSyncLock()
            } finally {
                store.close()
            }
        }
    }
}

internal fun upcomingNewsTeams(matches: List<Match>, nowMillis: Long, maximumFixtures: Int = 6): List<String> {
    require(maximumFixtures in 1..20)
    val cutoff = nowMillis + TimeUnit.DAYS.toMillis(14)
    val fixtures = matches.asSequence()
        .filter { !it.finished && it.exactKickoff }
        .mapNotNull { match ->
            val kickoff = runCatching { Instant.parse(match.kickoff).toEpochMilli() }.getOrNull() ?: return@mapNotNull null
            if (kickoff <= nowMillis || kickoff > cutoff || match.home.isBlank() || match.away.isBlank()) null
            else kickoff to FixtureNewsMatch(match.home, match.away)
        }
        .sortedBy { it.first }
        .take(maximumFixtures)
        .toList()
    return fixtures.flatMap { listOf(it.second.team, it.second.opponent) }.distinct()
}

