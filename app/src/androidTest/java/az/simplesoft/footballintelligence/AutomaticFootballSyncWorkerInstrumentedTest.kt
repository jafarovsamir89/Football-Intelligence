package az.simplesoft.footballintelligence

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import az.simplesoft.footballintelligence.data.OpenFootballJsonParser
import az.simplesoft.footballintelligence.data.OpenLigaDbParser
import az.simplesoft.footballintelligence.data.KickoffPrecision
import az.simplesoft.footballintelligence.data.ProviderFileCache
import az.simplesoft.footballintelligence.data.ProviderMatch
import java.io.File
import java.io.IOException
import java.time.LocalDate
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
