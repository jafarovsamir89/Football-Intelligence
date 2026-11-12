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
import az.simplesoft.footballintelligence.data.InternationalOddsQualityCounts
import az.simplesoft.footballintelligence.data.InternationalOddsSnapshot
import az.simplesoft.footballintelligence.data.InternationalOddsStatus
import az.simplesoft.footballintelligence.data.THE_ODDS_API_DAILY_LEAGUE_LIMIT
import az.simplesoft.footballintelligence.data.THE_ODDS_API_LEAGUES_PER_BATCH
import az.simplesoft.footballintelligence.data.THE_ODDS_API_MINIMUM_CREDIT_RESERVE
import az.simplesoft.footballintelligence.data.THE_ODDS_API_PROVIDER_ID
import az.simplesoft.footballintelligence.data.THE_ODDS_API_REFRESH_INTERVAL_HOURS
import az.simplesoft.footballintelligence.data.TheOddsApiProvider
import az.simplesoft.footballintelligence.data.TheOddsApiSport
import az.simplesoft.footballintelligence.data.isTheOddsApiCatalogCacheFresh
import az.simplesoft.footballintelligence.data.theOddsApiAuthHeaders
import az.simplesoft.footballintelligence.data.theOddsApiOddsUrl
import az.simplesoft.footballintelligence.data.theOddsApiSportsUrl
import java.io.IOException
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val THE_ODDS_API_PERIODIC_WORK_NAME = "football-intelligence-the-odds-api"
private const val THE_ODDS_API_ONCE_WORK_NAME = "football-intelligence-the-odds-api-now"
private const val THE_ODDS_API_PREFS = "football_intelligence_the_odds_api"
private const val SPORTS_CACHE_KEY = "active_soccer_sports"
private const val SPORTS_CACHE_AT_KEY = "active_soccer_sports_at"
private const val ROTATION_CURSOR_KEY = "league_rotation_cursor"
private const val LAST_BATCH_ATTEMPT_KEY = "last_batch_attempt_at"
private const val RECENT_BATCH_ATTEMPTS_KEY = "recent_batch_attempts"
private const val RETRY_AFTER_UNTIL_KEY = "retry_after_until"
private const val QUOTA_REMAINING_KEY = "quota_remaining"
private const val QUOTA_OBSERVED_AT_KEY = "quota_observed_at"
private val THE_ODDS_API_STALE_WINDOW_MILLIS = TimeUnit.HOURS.toMillis(THE_ODDS_API_REFRESH_INTERVAL_HOURS)
private val THE_ODDS_API_ATTEMPT_WINDOW_MILLIS = TimeUnit.HOURS.toMillis(24)
private val THE_ODDS_API_CLOCK_SKEW_TOLERANCE_MILLIS = TimeUnit.MINUTES.toMillis(1)

internal fun isTheOddsApiSyncStale(lastCheckedAtMillis: Long?, nowMillis: Long): Boolean =
    lastCheckedAtMillis == null || lastCheckedAtMillis <= 0L ||
        lastCheckedAtMillis > nowMillis + 60_000L ||
        nowMillis - lastCheckedAtMillis >= THE_ODDS_API_STALE_WINDOW_MILLIS

internal fun canStartTheOddsApiBatch(
    recentAttemptsMillis: Collection<Long>,
    nowMillis: Long,
    retryAfterUntilMillis: Long = 0L,
): Boolean {
    if (retryAfterUntilMillis > nowMillis) return false
    val attempts = recentAttemptsMillis.filter { it > 0L }
    if (attempts.any { it - nowMillis > THE_ODDS_API_CLOCK_SKEW_TOLERANCE_MILLIS }) return false
    val inWindow = attempts.filter { it > nowMillis - THE_ODDS_API_ATTEMPT_WINDOW_MILLIS }
    if (inWindow.size >= THE_ODDS_API_DAILY_LEAGUE_LIMIT) return false
    val latestAttempt = inWindow.maxOrNull() ?: return true
    return nowMillis - latestAttempt >= THE_ODDS_API_STALE_WINDOW_MILLIS
}

internal fun hasTheOddsApiCreditReserve(
    remainingCredits: Int?,
    quotaObservedAtMillis: Long?,
    nowMillis: Long,
): Boolean {
    if (remainingCredits == null || remainingCredits > THE_ODDS_API_MINIMUM_CREDIT_RESERVE) return true
    val observedAt = quotaObservedAtMillis ?: return false
    if (observedAt <= 0L || observedAt > nowMillis + THE_ODDS_API_CLOCK_SKEW_TOLERANCE_MILLIS) return false
    val observedMonth = YearMonth.from(Instant.ofEpochMilli(observedAt).atZone(ZoneOffset.UTC))
    val currentDate = Instant.ofEpochMilli(nowMillis).atZone(ZoneOffset.UTC)
    val currentMonth = YearMonth.from(currentDate)
    // The quota resets monthly. Wait through the first UTC day before trusting the new allowance.
    return currentMonth.isAfter(observedMonth) && currentDate.dayOfMonth > 1
}

internal object TheOddsApiBatchGate {
    @Synchronized
    fun tryBegin(prefs: android.content.SharedPreferences, nowMillis: Long): Boolean {
        val savedAttempts = prefs.getString(RECENT_BATCH_ATTEMPTS_KEY, null)
            ?.split(',')
            ?.mapNotNull(String::toLongOrNull)
            .orEmpty()
        val legacyAttempt = prefs.getLong(LAST_BATCH_ATTEMPT_KEY, 0L)
        if (!prefs.contains(RECENT_BATCH_ATTEMPTS_KEY) && legacyAttempt > 0L &&
            nowMillis - legacyAttempt < THE_ODDS_API_ATTEMPT_WINDOW_MILLIS
        ) {
            // The previous app version could issue a 12-league batch but stored only its start time.
            return false
        }
        val attempts = (savedAttempts + legacyAttempt).distinct()
        val retryAfterUntil = prefs.getLong(RETRY_AFTER_UNTIL_KEY, 0L)
        val quotaRemaining = if (prefs.contains(QUOTA_REMAINING_KEY)) prefs.getInt(QUOTA_REMAINING_KEY, 0) else null
        val quotaObservedAt = prefs.getLong(QUOTA_OBSERVED_AT_KEY, 0L).takeIf { it > 0L }
        if (!hasTheOddsApiCreditReserve(quotaRemaining, quotaObservedAt, nowMillis)) return false
        if (!canStartTheOddsApiBatch(attempts, nowMillis, retryAfterUntil)) return false
        val retained = attempts.filter { it > nowMillis - THE_ODDS_API_ATTEMPT_WINDOW_MILLIS } + nowMillis
        return prefs.edit()
            .putLong(LAST_BATCH_ATTEMPT_KEY, nowMillis)
            .putString(RECENT_BATCH_ATTEMPTS_KEY, retained.distinct().sorted().joinToString(","))
            .commit()
    }
}

/** Key-enabled personal builds get quota-limited network refreshes; public CI APKs remain keyless. */
object TheOddsApiScheduler {
    fun scheduleOnAppOpen(context: Context) {
        val appContext = context.applicationContext
        if (BuildConfig.THE_ODDS_API_KEY.isBlank()) {
            cancelPendingWork(appContext)
            return
        }
        val lastChecked = runCatching {
            Store(appContext).let { store ->
                try {
                    maxOf(
                        store.internationalOddsSyncState(THE_ODDS_API_PROVIDER_ID)?.lastCheckedAtMillis ?: 0L,
                        appContext.getSharedPreferences(THE_ODDS_API_PREFS, Context.MODE_PRIVATE).getLong(LAST_BATCH_ATTEMPT_KEY, 0L),
                    ).takeIf { it > 0L }
                }
                finally { store.close() }
            }
        }.getOrNull()
        enqueueForFreshness(appContext, lastChecked, System.currentTimeMillis())
    }

    internal fun enqueueForFreshness(
        context: Context,
        lastCheckedAtMillis: Long?,
        nowMillis: Long,
        immediateInitialDelayMillis: Long = 0L,
        apiKeyAvailable: Boolean = BuildConfig.THE_ODDS_API_KEY.isNotBlank(),
    ) {
        require(immediateInitialDelayMillis >= 0L)
        if (!apiKeyAvailable) {
            cancelPendingWork(context.applicationContext)
            return
        }
        val manager = WorkManager.getInstance(context.applicationContext)
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val refreshMillis = TimeUnit.HOURS.toMillis(THE_ODDS_API_REFRESH_INTERVAL_HOURS)
        val periodic = withAutomaticSyncRetryBackoff(
            PeriodicWorkRequestBuilder<TheOddsApiOddsWorker>(THE_ODDS_API_REFRESH_INTERVAL_HOURS, TimeUnit.HOURS)
                .setInitialDelay(refreshMillis, TimeUnit.MILLISECONDS)
                .setConstraints(constraints),
        ).build()
        manager.enqueueUniquePeriodicWork(THE_ODDS_API_PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, periodic)
        if (isTheOddsApiSyncStale(lastCheckedAtMillis, nowMillis)) {
            val onceBuilder = withAutomaticSyncRetryBackoff(
                OneTimeWorkRequestBuilder<TheOddsApiOddsWorker>().setConstraints(constraints),
            )
            if (immediateInitialDelayMillis > 0L) onceBuilder.setInitialDelay(immediateInitialDelayMillis, TimeUnit.MILLISECONDS)
            manager.enqueueUniqueWork(THE_ODDS_API_ONCE_WORK_NAME, ExistingWorkPolicy.KEEP, onceBuilder.build())
        }
    }

    private fun cancelPendingWork(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(THE_ODDS_API_ONCE_WORK_NAME)
        manager.cancelUniqueWork(THE_ODDS_API_PERIODIC_WORK_NAME)
    }

    const val UNIQUE_ONCE_NAME = THE_ODDS_API_ONCE_WORK_NAME
    const val UNIQUE_PERIODIC_NAME = THE_ODDS_API_PERIODIC_WORK_NAME
}

class TheOddsApiOddsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    internal var providerHttpClient: ProviderHttpClient = PublicJsonClient()
    internal var apiKeyProvider: () -> String = { BuildConfig.THE_ODDS_API_KEY }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = Store(applicationContext)
        val checkedAt = System.currentTimeMillis()
        val prefs = applicationContext.getSharedPreferences(THE_ODDS_API_PREFS, Context.MODE_PRIVATE)
        try {
            val apiKey = apiKeyProvider().trim()
            if (apiKey.isBlank()) {
                store.saveInternationalOddsCapture(
                    checkedAt,
                    InternationalOddsStatus.UNAVAILABLE,
                    emptyList(),
                    "В этой сборке не задан личный ключ The Odds API; автоматические запросы отключены.",
                    providerId = THE_ODDS_API_PROVIDER_ID,
                )
                return@withContext Result.success()
            }

            if (!TheOddsApiBatchGate.tryBegin(prefs, checkedAt)) {
                return@withContext Result.success()
            }

            val provider = TheOddsApiProvider()
            val sports = loadActiveSports(provider, apiKey, prefs)
            if (sports.isEmpty()) {
                store.saveInternationalOddsCapture(
                    checkedAt,
                    InternationalOddsStatus.UNAVAILABLE,
                    emptyList(),
                    "The Odds API не вернул активный каталог футбольных лиг.",
                    providerId = THE_ODDS_API_PROVIDER_ID,
                )
                return@withContext Result.success()
            }

            val cursor = prefs.getInt(ROTATION_CURSOR_KEY, 0)
            val selected = provider.nextSports(sports, cursor, THE_ODDS_API_LEAGUES_PER_BATCH)
            val fixtures = store.matchIdentities()
            val snapshots = mutableListOf<InternationalOddsSnapshot>()
            val counts = QualityCountAccumulator()
            var remainingCredits: Int? = null
            var usedCredits: Int? = null
            var lastRequestCost: Int? = null
            var requested = 0
            var requestError: String? = null
            var blocked = false

            for (sport in selected) {
                requested++
                try {
                    val response = providerHttpClient.getWithHeaders(
                        theOddsApiOddsUrl(sport.key),
                        theOddsApiAuthHeaders(apiKey),
                    )
                    if (response.statusCode in setOf(401, 403, 451)) {
                        blocked = true
                        requestError = "The Odds API вернул HTTP ${response.statusCode}; повторные запросы приостановлены."
                        break
                    }
                    if (response.statusCode == 429 || response.statusCode == 503) {
                        rememberTheOddsApiRetryAfter(prefs, checkedAt, response.retryAfterDelayMillis)
                        requestError = "The Odds API временно ограничил запросы (HTTP ${response.statusCode}); повторный запрос отложен согласно Retry-After и лимиту квоты."
                        break
                    }
                    if (response.statusCode !in 200..299) {
                        requestError = "The Odds API вернул HTTP ${response.statusCode} для ${sport.key}."
                        continue
                    }
                    response.oddsApiRequestsRemaining?.let { remaining ->
                        remainingCredits = remaining
                        rememberTheOddsApiQuota(prefs, remaining, response.serverDateMillis ?: System.currentTimeMillis())
                    }
                    usedCredits = response.oddsApiRequestsUsed ?: usedCredits
                    lastRequestCost = response.oddsApiRequestCost ?: lastRequestCost
                    val parsed = provider.parseOdds(
                        responseBody = response.body,
                        capturedAtMillis = System.currentTimeMillis(),
                        responseDateMillis = response.serverDateMillis,
                        sportKey = sport.key,
                        fixtures = fixtures,
                    )
                    snapshots += parsed.snapshots
                    counts.add(parsed.qualityCounts)
                    if (remainingCredits != null && remainingCredits <= THE_ODDS_API_MINIMUM_CREDIT_RESERVE) break
                } catch (error: ProviderHttpException) {
                    if (error.statusCode in setOf(401, 403, 451)) blocked = true
                    if (error.statusCode == 429 || error.statusCode == 503) {
                        rememberTheOddsApiRetryAfter(prefs, checkedAt, error.retryAfterDelayMillis)
                    }
                    requestError = if (error.statusCode == 429 || error.statusCode == 503) {
                        "The Odds API временно ограничил запросы (HTTP ${error.statusCode}); повторный запрос отложен согласно Retry-After и лимиту квоты."
                    } else {
                        "The Odds API отказал запросу для ${sport.key} (HTTP ${error.statusCode})."
                    }
                    break
                } catch (error: Exception) {
                    requestError = safeTheOddsApiError(error, apiKey)
                    break
                }
            }

            if (selected.isNotEmpty()) {
                prefs.edit().putInt(ROTATION_CURSOR_KEY, (cursor + requested) % sports.size).apply()
            }
            val qualityCounts = counts.toQualityCountsOrNull()
            val status = when {
                blocked -> InternationalOddsStatus.BLOCKED
                requestError != null -> InternationalOddsStatus.ERROR
                snapshots.isNotEmpty() -> InternationalOddsStatus.LIVE
                (qualityCounts?.staleMatches ?: 0) > 0 -> InternationalOddsStatus.STALE
                (qualityCounts?.unverifiedMatches ?: 0) > 0 -> InternationalOddsStatus.UNVERIFIED
                else -> InternationalOddsStatus.UNAVAILABLE
            }
            val quotaText = remainingCredits?.let {
                " Квота: последний запрос ${lastRequestCost ?: "?"} кредитов, использовано ${usedCredits ?: "?"}, осталось $it."
            }.orEmpty()
            val resultDetail = buildString {
                append("Проверено лиг: $requested/${selected.size} из ${sports.size} активных футбольных лиг.")
                if (qualityCounts != null) append(" Принято линий: ${qualityCounts.acceptedMatches}/${qualityCounts.receivedMatches}.")
                append(quotaText)
                if (remainingCredits != null && remainingCredits!! <= THE_ODDS_API_MINIMUM_CREDIT_RESERVE) {
                    append(" Запросы приостановлены до следующего месячного сброса квоты.")
                }
                requestError?.let { append(" Ошибка/ограничение: $it") }
            }
            store.saveInternationalOddsCapture(
                checkedAt,
                status,
                snapshots,
                resultDetail,
                replaceCurrentBoard = false,
                qualityCounts = qualityCounts,
                providerId = THE_ODDS_API_PROVIDER_ID,
            )
            if (snapshots.isNotEmpty()) store.relinkUnmatchedInternationalOdds(checkedAt)
            Result.success()
        } catch (error: ProviderHttpException) {
            val blocked = error.statusCode in setOf(401, 403, 451)
            if (error.statusCode == 429 || error.statusCode == 503) {
                rememberTheOddsApiRetryAfter(prefs, checkedAt, error.retryAfterDelayMillis)
            }
            store.saveInternationalOddsCapture(
                checkedAt,
                if (blocked) InternationalOddsStatus.BLOCKED else InternationalOddsStatus.ERROR,
                emptyList(),
                "The Odds API запрос каталога завершился HTTP ${error.statusCode}; ключ не сохраняется в журнале.",
                providerId = THE_ODDS_API_PROVIDER_ID,
            )
            Result.success()
        } catch (error: Exception) {
            store.saveInternationalOddsCapture(
                checkedAt,
                InternationalOddsStatus.ERROR,
                emptyList(),
                "Ошибка The Odds API: ${safeTheOddsApiError(error, apiKeyProvider().trim())}",
                providerId = THE_ODDS_API_PROVIDER_ID,
            )
            Result.success()
        } finally {
            store.close()
        }
    }

    private fun loadActiveSports(
        provider: TheOddsApiProvider,
        apiKey: String,
        prefs: android.content.SharedPreferences,
    ): List<TheOddsApiSport> {
        val now = System.currentTimeMillis()
        val cachedAt = prefs.getLong(SPORTS_CACHE_AT_KEY, 0L)
        val cached = prefs.getString(SPORTS_CACHE_KEY, null)
            ?.let { runCatching { provider.parseActiveSoccerSports(it) }.getOrNull() }
            .orEmpty()
        if (cached.isNotEmpty() && isTheOddsApiCatalogCacheFresh(cachedAt, now)) return cached

        val response = providerHttpClient.getWithHeaders(theOddsApiSportsUrl(), theOddsApiAuthHeaders(apiKey))
        if (response.statusCode in setOf(401, 403, 451)) throw ProviderHttpException(response.statusCode)
        if (response.statusCode == 429 || response.statusCode == 503) {
            rememberTheOddsApiRetryAfter(prefs, now, response.retryAfterDelayMillis)
            throw ProviderHttpException(response.statusCode, response.retryAfterDelayMillis)
        }
        if (response.statusCode !in 200..299) throw IOException("The Odds API returned HTTP ${response.statusCode} for its sports catalog.")
        val sports = provider.parseActiveSoccerSports(response.body)
        val cachedJson = JSONArray().apply {
            sports.forEach { sport -> put(JSONObject().put("key", sport.key).put("title", sport.title).put("active", true)) }
        }
        prefs.edit().putString(SPORTS_CACHE_KEY, cachedJson.toString()).putLong(SPORTS_CACHE_AT_KEY, now).apply()
        return sports
    }

    private class QualityCountAccumulator {
        private var received = 0
        private var accepted = 0
        private var rejected = 0
        private var stale = 0
        private var duplicates = 0
        private var unverified = 0

        fun add(counts: InternationalOddsQualityCounts) {
            received += counts.receivedMatches
            accepted += counts.acceptedMatches
            rejected += counts.rejectedMatches
            stale += counts.staleMatches
            duplicates += counts.duplicateMatches
            unverified += counts.unverifiedMatches
        }

        fun toQualityCountsOrNull(): InternationalOddsQualityCounts? =
            if (received == 0) null else InternationalOddsQualityCounts(received, accepted, rejected, stale, duplicates, unverified)
    }
}

private fun safeTheOddsApiError(error: Throwable, apiKey: String): String =
    (error.message ?: error.javaClass.simpleName)
        .let { if (apiKey.isBlank()) it else it.replace(apiKey, "[ключ скрыт]") }
        .take(240)

private fun rememberTheOddsApiRetryAfter(
    prefs: android.content.SharedPreferences,
    nowMillis: Long,
    delayMillis: Long?,
) {
    if (delayMillis == null || delayMillis <= 0L) return
    val until = if (Long.MAX_VALUE - nowMillis < delayMillis) Long.MAX_VALUE else nowMillis + delayMillis
    prefs.edit().putLong(RETRY_AFTER_UNTIL_KEY, until).commit()
}

private fun rememberTheOddsApiQuota(
    prefs: android.content.SharedPreferences,
    remainingCredits: Int,
    observedAtMillis: Long,
) {
    prefs.edit()
        .putInt(QUOTA_REMAINING_KEY, remainingCredits)
        .putLong(QUOTA_OBSERVED_AT_KEY, observedAtMillis)
        .commit()
}
