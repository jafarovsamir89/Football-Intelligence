package az.simplesoft.footballintelligence

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import az.simplesoft.footballintelligence.data.OpenFootballJsonParser
import az.simplesoft.footballintelligence.data.OpenLigaDbParser
import az.simplesoft.footballintelligence.data.KickoffPrecision
import az.simplesoft.footballintelligence.data.ProviderFileCache
import az.simplesoft.footballintelligence.data.ProviderMatch
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomaticFootballSyncWorkerInstrumentedTest {
    @Test fun workerRecordsForecastFromCachedResultsWhenProviderRefreshFails() = runBlocking {
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "fi-worker-prospective-${UUID.randomUUID()}.db"
        baseContext.deleteDatabase(databaseName)
        val isolatedContext = IsolatedDatabaseContext(baseContext, databaseName)
        val now = System.currentTimeMillis()

        try {
            Store(isolatedContext, databaseName).use { store ->
                store.upsertProviderMatches(cachedHistoryAndFixture(now))
            }
            val worker = TestListenableWorkerBuilder<AutomaticFootballSyncWorker>(isolatedContext)
                .setRunAttemptCount(1)
                .build()
            worker.providerHttpClient = AlwaysFailingProviderHttpClient()

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.retry().javaClass, result.javaClass)
            Store(isolatedContext, databaseName).use { store ->
                val snapshot = store.prospectiveForecastHistory().single()
                assertEquals(29999, snapshot.matchId)
                assertEquals("Home FC", snapshot.homeTeam)
                assertEquals("Away FC", snapshot.awayTeam)
                assertEquals(10, snapshot.historyMatchCount)
                assertEquals(1.0, snapshot.probabilities.values().sum(), 1e-9)
                assertNull(snapshot.actualOutcome)
            }
        } finally {
            baseContext.deleteDatabase(databaseName)
        }
    }

    @Test fun notModifiedCatalogAndFilesRetainRowsAndRefreshCacheChecks() {
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "fi-openfootball-304-${UUID.randomUUID()}.db"
        baseContext.deleteDatabase(databaseName)
        val isolatedContext = IsolatedDatabaseContext(baseContext, databaseName)
        val now = System.currentTimeMillis()
        val today = LocalDate.of(2026, 11, 12)
        val staleAt = now - TimeUnit.DAYS.toMillis(60)
        val seasons = OpenFootballJsonParser.planSeasons(today, 5)

        try {
            Store(isolatedContext, databaseName).use { store ->
                seasons.forEachIndexed { index, season ->
                    val fileName = "league-$index.json"
                    val directoryBody = """[{"type":"file","name":"$fileName"}]"""
                    store.saveProviderFileCache(
                        "openfootball:directory:$season",
                        OpenFootballJsonParser.PROVIDER_ID,
                        "\"directory-$index\"",
                        "Wed, 11 Nov 2026 00:00:00 GMT",
                        staleAt,
                        1,
                        directoryBody,
                    )
                    store.saveProviderFileCache(
                        "openfootball:$season:$fileName",
                        OpenFootballJsonParser.PROVIDER_ID,
                        "\"file-$index\"",
                        "Wed, 11 Nov 2026 00:00:00 GMT",
                        staleAt,
                        1,
                    )
                    val eventDate = LocalDate.of(season.substringBefore('-').toInt(), 8, 1)
                    val kickoff = eventDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                    store.upsertProviderMatches(
                        listOf(
                            ProviderMatch(
                                providerId = OpenFootballJsonParser.PROVIDER_ID,
                                providerMatchId = "cached:$season",
                                competitionCode = "league-$index",
                                competitionName = "League $index",
                                season = season,
                                eventDate = eventDate,
                                kickoffMillis = kickoff,
                                kickoffPrecision = KickoffPrecision.DATE_ONLY,
                                homeTeam = "Cached Home $index",
                                awayTeam = "Cached Away $index",
                                homeGoals = 1,
                                awayGoals = 0,
                                safeAvailableAtMillis = kickoff + TimeUnit.DAYS.toMillis(2),
                                sourceUrl = "https://github.com/openfootball/football.json/$season/$fileName",
                                capturedAtMillis = now,
                            ),
                        ),
                    )
                }

                val matchesBefore = store.matches()
                val client = NotModifiedProviderHttpClient()
                val outcome = OpenFootballSync(store, client).sync(
                    today = today,
                    syncStartedAt = now,
                    runtimeDeadlineMillis = now + TimeUnit.MINUTES.toMillis(10),
                )

                assertEquals("OK", outcome.status)
                assertEquals(5, outcome.records)
                assertEquals(10, client.requests.size)
                assertTrue(client.requests.all { (_, cache) -> cache?.etag != null && cache.lastModified != null })
                assertEquals(matchesBefore.map { it.sourceKey }, store.matches().map { it.sourceKey })
                assertEquals(matchesBefore.map { it.home }, store.matches().map { it.home })
                seasons.forEachIndexed { index, season ->
                    val directory = store.providerFileCache("openfootball:directory:$season")!!
                    val file = store.providerFileCache("openfootball:$season:league-$index.json")!!
                    assertEquals("\"directory-$index\"", directory.etag)
                    assertEquals("\"file-$index\"", file.etag)
                    assertEquals(1, directory.recordCount)
                    assertEquals(1, file.recordCount)
                    assertEquals("""[{"type":"file","name":"league-$index.json"}]""", directory.cachedBody)
                    assertTrue(directory.lastCheckedAtMillis > staleAt)
                    assertTrue(file.lastCheckedAtMillis > staleAt)
                }
            }
        } finally {
            baseContext.deleteDatabase(databaseName)
        }
    }

    @Test fun workerReturnsRetryWhenBothProviderCatalogsFail() = runBlocking {
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "fi-worker-retry-${UUID.randomUUID()}.db"
        baseContext.deleteDatabase(databaseName)
        val isolatedContext = IsolatedDatabaseContext(baseContext, databaseName)

        try {
            val failingClient = AlwaysFailingProviderHttpClient()
            val worker = TestListenableWorkerBuilder<AutomaticFootballSyncWorker>(isolatedContext)
                .setRunAttemptCount(1)
                .build()
            worker.providerHttpClient = failingClient

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.retry().javaClass, result.javaClass)
            assertTrue(
                "OpenFootball should request its season catalogs through the injected client.",
                failingClient.requestedUrls.any {
                    it.startsWith("https://api.github.com/repos/openfootball/football.json/contents/")
                },
            )
            assertTrue(
                "OpenLigaDB should request its league catalogs through the injected client.",
                failingClient.requestedUrls.any { it.startsWith("https://api.openligadb.de/getavailableleagues/") },
            )
            Store(baseContext, databaseName).use { store ->
                val summaries = store.providerSyncSummaries().associateBy { it.providerId }
                val openFootball = summaries.getValue(OpenFootballJsonParser.PROVIDER_ID)
                val openLigaDb = summaries.getValue(OpenLigaDbParser.PROVIDER_ID)
                assertEquals("ERROR", openFootball.status)
                assertEquals("ERROR", openLigaDb.status)
                assertNull(openFootball.lastSuccessAtMillis)
                assertNull(openLigaDb.lastSuccessAtMillis)
                store.writableDatabase.rawQuery(
                    "SELECT COUNT(*) FROM provider_sync_lock WHERE id=1",
                    null,
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            }
        } finally {
            baseContext.deleteDatabase(databaseName)
        }
    }

    @Test fun rateLimitedProvidersStopTheirRequestsAndQueueOneDelayedRetry() = runBlocking {
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "fi-worker-retry-after-${UUID.randomUUID()}.db"
        baseContext.deleteDatabase(databaseName)
        val isolatedContext = IsolatedDatabaseContext(baseContext, databaseName)
        val workManager = WorkManager.getInstance(baseContext)
        workManager.cancelUniqueWork(FootballSyncScheduler.UNIQUE_ONCE_NAME).result.get(10, TimeUnit.SECONDS)
        val client = RateLimitedProviderHttpClient(
            openFootballDelayMillis = TimeUnit.MINUTES.toMillis(2),
            openLigaDelayMillis = TimeUnit.MINUTES.toMillis(5),
        )

        try {
            val worker = TestListenableWorkerBuilder<AutomaticFootballSyncWorker>(isolatedContext)
                .setRunAttemptCount(1)
                .build()
            worker.providerHttpClient = client

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
            assertEquals(
                "Each independent provider must stop after its first 429 instead of continuing its catalog loop.",
                2,
                client.requestedUrls.size,
            )
            val queued = awaitActiveWork(workManager, FootballSyncScheduler.UNIQUE_ONCE_NAME)
            assertEquals(WorkInfo.State.ENQUEUED, queued.state)
            assertEquals(TimeUnit.MINUTES.toMillis(5), queued.initialDelayMillis)
            assertEquals(NetworkType.CONNECTED, queued.constraints.requiredNetworkType)
            assertEquals(0, queued.runAttemptCount)
            Store(baseContext, databaseName).use { store ->
                val summaries = store.providerSyncSummaries().associateBy { it.providerId }
                assertEquals(setOf(OpenFootballJsonParser.PROVIDER_ID, OpenLigaDbParser.PROVIDER_ID), summaries.keys)
                assertTrue(summaries.values.all { it.status == "ERROR" })
                store.writableDatabase.rawQuery("SELECT COUNT(*) FROM provider_sync_lock WHERE id=1", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            }
        } finally {
            workManager.cancelUniqueWork(FootballSyncScheduler.UNIQUE_ONCE_NAME).result.get(10, TimeUnit.SECONDS)
            baseContext.deleteDatabase(databaseName)
        }
    }

    @Test fun openFootballStopsAfterRateLimitOnFirstCompetitionFile() {
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "fi-openfootball-file-429-${UUID.randomUUID()}.db"
        baseContext.deleteDatabase(databaseName)
        val isolatedContext = IsolatedDatabaseContext(baseContext, databaseName)
        val now = System.currentTimeMillis()
        val today = LocalDate.of(2026, 11, 12)
        val season = OpenFootballJsonParser.planSeasons(today, 5).first()
        val delay = TimeUnit.MINUTES.toMillis(3)
        val client = RateLimitedProviderHttpClient(delay, delay)

        try {
            Store(isolatedContext, databaseName).use { store ->
                store.saveProviderFileCache(
                    "openfootball:directory:$season",
                    OpenFootballJsonParser.PROVIDER_ID,
                    null,
                    null,
                    now,
                    2,
                    """[{"type":"file","name":"one.json"},{"type":"file","name":"two.json"}]""",
                )

                val outcome = OpenFootballSync(store, client).sync(today, now, now + TimeUnit.MINUTES.toMillis(10))

                assertEquals(1, client.requestedUrls.size)
                assertEquals("ERROR", outcome.status)
                assertTrue(outcome.retryNeeded)
                assertEquals(delay, outcome.retryAfterDelayMillis)
            }
        } finally {
            baseContext.deleteDatabase(databaseName)
        }
    }

    @Test fun openLigaDbStopsAfterRateLimitOnFirstLeagueMatchRequest() {
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "fi-openligadb-match-429-${UUID.randomUUID()}.db"
        baseContext.deleteDatabase(databaseName)
        val isolatedContext = IsolatedDatabaseContext(baseContext, databaseName)
        val now = System.currentTimeMillis()
        val today = LocalDate.of(2026, 11, 12)
        val delay = TimeUnit.MINUTES.toMillis(4)
        val client = RateLimitedProviderHttpClient(delay, delay)
        val catalogBody = """[
            {"leagueId":1,"leagueName":"Test One","leagueShortcut":"tst1","leagueSeason":"2026","sport":{"sportId":1,"sportName":"Football"}},
            {"leagueId":2,"leagueName":"Test Two","leagueShortcut":"tst2","leagueSeason":"2026","sport":{"sportId":1,"sportName":"Football"}}
        ]"""

        try {
            Store(isolatedContext, databaseName).use { store ->
                store.saveProviderFileCache(
                    "openligadb:catalog:2026",
                    OpenLigaDbParser.PROVIDER_ID,
                    null,
                    null,
                    now,
                    2,
                    catalogBody,
                )

                val outcome = OpenLigaDbSync(store, client).sync(today, now, now + TimeUnit.MINUTES.toMillis(10))

                assertEquals(1, client.requestedUrls.size)
                assertTrue(client.requestedUrls.single().endsWith("/getmatchdata/tst1/2026"))
                assertEquals("ERROR", outcome.status)
                assertTrue(outcome.retryNeeded)
                assertEquals(delay, outcome.retryAfterDelayMillis)
            }
        } finally {
            baseContext.deleteDatabase(databaseName)
        }
    }

    private fun awaitActiveWork(workManager: WorkManager, uniqueName: String): WorkInfo {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30)
        while (System.currentTimeMillis() < deadline) {
            val active = workManager.getWorkInfosForUniqueWork(uniqueName).get(10, TimeUnit.SECONDS)
                .filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
            if (active.size == 1) return active.single()
            Thread.sleep(100)
        }
        throw AssertionError("No delayed retry was queued for $uniqueName.")
    }

    private fun cachedHistoryAndFixture(nowMillis: Long): List<ProviderMatch> {
        val competition = "worker-prospective-test"
        val season = "${Instant.ofEpochMilli(nowMillis).atZone(ZoneOffset.UTC).year}"
        val teams = listOf(
            "Home FC" to "Other FC",
            "Away FC" to "Another FC",
            "Other FC" to "Away FC",
            "Another FC" to "Home FC",
            "Home FC" to "Away FC",
            "Other FC" to "Another FC",
            "Away FC" to "Home FC",
            "Another FC" to "Other FC",
            "Home FC" to "Another FC",
            "Away FC" to "Other FC",
        )
        val history = teams.mapIndexed { index, (home, away) ->
            val kickoff = nowMillis - TimeUnit.DAYS.toMillis(20L - index)
            ProviderMatch(
                providerId = "openligadb",
                providerMatchId = (20000 + index).toString(),
                competitionCode = competition,
                competitionName = "Worker prospective test",
                season = season,
                eventDate = Instant.ofEpochMilli(kickoff).atZone(ZoneOffset.UTC).toLocalDate(),
                kickoffMillis = kickoff,
                kickoffPrecision = KickoffPrecision.INSTANT,
                homeTeam = home,
                awayTeam = away,
                homeGoals = index % 3,
                awayGoals = (index + 1) % 3,
                safeAvailableAtMillis = kickoff + TimeUnit.HOURS.toMillis(3),
                sourceUrl = "https://example.org/worker-history/$index",
                capturedAtMillis = nowMillis - TimeUnit.MINUTES.toMillis(1),
            )
        }
        val kickoff = nowMillis + TimeUnit.DAYS.toMillis(1)
        val futureFixture = ProviderMatch(
            providerId = "openligadb",
            providerMatchId = "29999",
            competitionCode = competition,
            competitionName = "Worker prospective test",
            season = season,
            eventDate = Instant.ofEpochMilli(kickoff).atZone(ZoneOffset.UTC).toLocalDate(),
            kickoffMillis = kickoff,
            kickoffPrecision = KickoffPrecision.INSTANT,
            homeTeam = "Home FC",
            awayTeam = "Away FC",
            homeGoals = null,
            awayGoals = null,
            safeAvailableAtMillis = null,
            sourceUrl = "https://example.org/worker-fixture",
            capturedAtMillis = nowMillis - TimeUnit.MINUTES.toMillis(1),
        )
        return history + futureFixture
    }

    private class RateLimitedProviderHttpClient(
        private val openFootballDelayMillis: Long,
        private val openLigaDelayMillis: Long,
    ) : ProviderHttpClient {
        val requestedUrls = ConcurrentLinkedQueue<String>()

        override fun get(url: String, cache: ProviderFileCache?): ProviderHttpResult {
            requestedUrls.add(url)
            val delay = if (url.startsWith("https://api.github.com/") || url.startsWith("https://raw.githubusercontent.com/")) {
                openFootballDelayMillis
            } else {
                openLigaDelayMillis
            }
            throw ProviderHttpException(429, delay)
        }
    }

    private class AlwaysFailingProviderHttpClient : ProviderHttpClient {
        val requestedUrls = ConcurrentLinkedQueue<String>()

        override fun get(url: String, cache: ProviderFileCache?): ProviderHttpResult {
            requestedUrls.add(url)
            throw IOException("Simulated provider catalog failure.")
        }
    }

    private class NotModifiedProviderHttpClient : ProviderHttpClient {
        val requests = mutableListOf<Pair<String, ProviderFileCache?>>()

        override fun get(url: String, cache: ProviderFileCache?): ProviderHttpResult {
            requests += url to cache
            return ProviderHttpResult(304, "", null, null)
        }
    }

    private class IsolatedDatabaseContext(
        base: Context,
        private val databaseName: String,
    ) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getDatabasePath(name: String): File = super.getDatabasePath(databaseName)

        override fun openOrCreateDatabase(
            name: String,
            mode: Int,
            factory: SQLiteDatabase.CursorFactory?,
        ): SQLiteDatabase = super.openOrCreateDatabase(databaseName, mode, factory)

        override fun openOrCreateDatabase(
            name: String,
            mode: Int,
            factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?,
        ): SQLiteDatabase = super.openOrCreateDatabase(databaseName, mode, factory, errorHandler)
    }
}
