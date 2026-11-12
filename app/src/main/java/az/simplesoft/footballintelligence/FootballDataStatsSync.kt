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
import az.simplesoft.footballintelligence.data.FOOTBALL_DATA_STATS_DIVISIONS
import az.simplesoft.footballintelligence.data.FootballDataCsvImporter
import az.simplesoft.footballintelligence.data.FootballDataStatsAttempt
import az.simplesoft.footballintelligence.data.FootballStatsFixtureIdentity
import az.simplesoft.footballintelligence.data.StoredFootballMatchStats
import az.simplesoft.footballintelligence.data.TeamNameIdentity
import az.simplesoft.footballintelligence.data.footballDataCurrentSeasonCode
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

private const val FOOTBALL_DATA_STATS_ONCE_NAME = "football-data-match-stats-now"
private const val FOOTBALL_DATA_STATS_PERIODIC_NAME = "football-data-match-stats-periodic"

data class FootballDataStatsHttpResponse(val statusCode: Int, val body: String)

fun interface FootballDataStatsHttpClient {
    fun get(url: String): FootballDataStatsHttpResponse
}

class PublicFootballDataStatsHttpClient : FootballDataStatsHttpClient {
    override fun get(url: String): FootballDataStatsHttpResponse {
        require(url.startsWith("https://www.football-data.co.uk/"))
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "text/csv,text/plain;q=0.9,*/*;q=0.1")
            connection.setRequestProperty("User-Agent", "Football-Intelligence-Android/0.1 (personal research)")
            val status = connection.responseCode
            if (status !in 200..299) return FootballDataStatsHttpResponse(status, "")
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8_192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(bytes.size() + count <= 12 * 1024 * 1024) { "CSV response exceeds 12 MiB." }
                    bytes.write(buffer, 0, count)
                }
            }
            return FootballDataStatsHttpResponse(status, FootballDataCsvImporter.decode(bytes.toByteArray()))
        } finally {
            connection.disconnect()
        }
    }
}

data class FootballDataStatsSyncResult(
    val attempted: Boolean,
    val status: String,
    val division: String? = null,
    val rowsRead: Int = 0,
    val rowsMatched: Int = 0,
    val nextAllowedAtMillis: Long? = null,
)

/** One request per run; the persisted reservation is written before the network call. */
class FootballDataStatsSyncer(
    private val store: Store,
    private val http: FootballDataStatsHttpClient,
) {
    fun sync(nowMillis: Long): FootballDataStatsSyncResult {
        val date = Instant.ofEpochMilli(nowMillis).atZone(ZoneOffset.UTC).toLocalDate()
        val season = footballDataCurrentSeasonCode(date)
        val reservation = store.reserveFootballDataStatsAttempt(nowMillis, season)
            ?: return FootballDataStatsSyncResult(
                attempted = false,
                status = "LIMITED",
                nextAllowedAtMillis = store.footballDataStatsNextAllowedAt(nowMillis),
            )
        val url = footballDataStatsUrl(reservation.season, reservation.division)
        try {
            val response = http.get(url)
            if (response.statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
                store.completeFootballDataStatsAttempt(
                    reservation.id, "UNAVAILABLE", 0, 0,
                    "Архив ${reservation.season}/${reservation.division} пока недоступен.", emptyList(),
                )
                return FootballDataStatsSyncResult(true, "UNAVAILABLE", reservation.division)
            }
            if (response.statusCode !in 200..299) {
                error("HTTP ${response.statusCode} from Football-Data.")
            }
            val report = FootballDataCsvImporter.parse(response.body, reservation.division)
            val parsedStats = report.results.mapNotNull { row ->
                row.matchStats?.let { row to it }
            }
            val candidates = store.completedFootballStatsFixtures().groupBy(::statsFixtureKey)
            val matched = parsedStats.mapNotNull { (row, values) ->
                val key = statsRowKey(row.eventDate, row.homeTeam, row.awayTeam)
                val fixture = candidates[key]?.singleOrNull() ?: return@mapNotNull null
                StoredFootballMatchStats(
                    matchId = fixture.matchId,
                    division = reservation.division,
                    eventDate = row.eventDate,
                    sourceUrl = url,
                    capturedAtMillis = nowMillis,
                    availableAtMillis = maxOf(nowMillis, FootballDataCsvImporter.conservativeAvailabilityMillis(row.eventDate)),
                    values = values,
                )
            }.distinctBy { it.matchId }
            val status = when {
                parsedStats.isEmpty() -> "EMPTY"
                matched.isEmpty() -> "NO_MATCH"
                else -> "OK"
            }
            val detail = "Строк со статистикой: ${parsedStats.size}; точно сопоставлено: ${matched.size}. Коэффициенты CSV не используются."
            store.completeFootballDataStatsAttempt(
                reservation.id, status, parsedStats.size, matched.size, detail, matched,
            )
            return FootballDataStatsSyncResult(true, status, reservation.division, parsedStats.size, matched.size)
        } catch (error: Exception) {
            val safe = (error.message ?: error.javaClass.simpleName).take(300)
            store.completeFootballDataStatsAttempt(reservation.id, "ERROR", 0, 0, safe, emptyList())
            return FootballDataStatsSyncResult(true, "ERROR", reservation.division)
        }
    }

    private fun statsFixtureKey(fixture: FootballStatsFixtureIdentity) =
        statsRowKey(fixture.eventDate, fixture.homeTeam, fixture.awayTeam)

    private fun statsRowKey(date: LocalDate, home: String, away: String) =
        listOf(date.toString(), TeamNameIdentity.canonical(home), TeamNameIdentity.canonical(away))
}

fun footballDataStatsUrl(season: String, division: String): String {
    require(season.matches(Regex("\\d{4}")))
    require(division in FOOTBALL_DATA_STATS_DIVISIONS)
    return "https://www.football-data.co.uk/mmz4281/$season/$division.csv"
}

object FootballDataStatsScheduler {
    const val REFRESH_INTERVAL_HOURS = 24L
    const val UNIQUE_ONCE_NAME = FOOTBALL_DATA_STATS_ONCE_NAME
    const val UNIQUE_PERIODIC_NAME = FOOTBALL_DATA_STATS_PERIODIC_NAME

    fun scheduleOnAppOpen(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        val appContext = context.applicationContext
        val nextAllowed = runCatching {
            Store(appContext).let { store -> try { store.footballDataStatsNextAllowedAt(nowMillis) } finally { store.close() } }
        }.getOrDefault(nowMillis + TimeUnit.DAYS.toMillis(1))
        enqueueForNextAllowedAt(appContext,nextAllowed,nowMillis)
    }

    internal fun enqueueForNextAllowedAt(context:Context,nextAllowedAtMillis:Long,nowMillis:Long):Boolean = runCatching {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val manager = WorkManager.getInstance(context.applicationContext)
        val periodic = PeriodicWorkRequestBuilder<FootballDataStatsWorker>(REFRESH_INTERVAL_HOURS, TimeUnit.HOURS)
            .setInitialDelay(REFRESH_INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        manager.enqueueUniquePeriodicWork(UNIQUE_PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, periodic)
        val oneTime = OneTimeWorkRequestBuilder<FootballDataStatsWorker>()
            .setInitialDelay((nextAllowedAtMillis - nowMillis).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .build()
        manager.enqueueUniqueWork(UNIQUE_ONCE_NAME, ExistingWorkPolicy.KEEP, oneTime)
    }.isSuccess
}

class FootballDataStatsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val store = Store(applicationContext)
        try {
            FootballDataStatsSyncer(store, PublicFootballDataStatsHttpClient()).sync(System.currentTimeMillis())
            Result.success()
        } catch (_: Exception) {
            Result.success()
        } finally {
            store.close()
        }
    }
}
