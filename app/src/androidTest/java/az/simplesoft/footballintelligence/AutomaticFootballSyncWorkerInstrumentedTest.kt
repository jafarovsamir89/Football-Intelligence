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
import az.simplesoft.footballintelligence.data.ProviderFileCache
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomaticFootballSyncWorkerInstrumentedTest {
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
