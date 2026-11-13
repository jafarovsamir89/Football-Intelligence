package az.simplesoft.footballintelligence

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import az.simplesoft.footballintelligence.data.GDELT_NEWS_PROVIDER_ID
import az.simplesoft.footballintelligence.data.ProviderFileCache
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NewsIntelligenceWorkerInstrumentedTest {
    @Test fun firstRunWithoutFixturesRecordsWaitingStateWithoutCallingGdelt() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "fi-news-worker-${UUID.randomUUID()}.db"
        base.deleteDatabase(databaseName)
        val context = IsolatedDatabaseContext(base, databaseName)
        val http = NeverCalledProviderHttpClient()
        try {
            val worker = TestListenableWorkerBuilder<NewsIntelligenceWorker>(context).build().apply {
                providerHttpClient = http
            }

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
            assertFalse(http.called)
            Store(base, databaseName).use { store ->
                val state = store.providerSyncSummaries().single { it.providerId == GDELT_NEWS_PROVIDER_ID }
                assertEquals("NO_FIXTURES", state.status)
                assertEquals(0, state.recordsStored)
            }
        } finally {
            base.deleteDatabase(databaseName)
        }
    }

    private class NeverCalledProviderHttpClient : ProviderHttpClient {
        var called = false
        override fun get(url: String, cache: ProviderFileCache?): ProviderHttpResult {
            called = true
            error("The worker must not call GDELT until exact-kickoff fixtures are cached.")
        }
    }

    private class IsolatedDatabaseContext(base: Context, private val databaseName: String) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File = super.getDatabasePath(databaseName)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            super.openOrCreateDatabase(databaseName, mode, factory)
        override fun openOrCreateDatabase(
            name: String,
            mode: Int,
            factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?,
        ): SQLiteDatabase = super.openOrCreateDatabase(databaseName, mode, factory, errorHandler)
    }
}
