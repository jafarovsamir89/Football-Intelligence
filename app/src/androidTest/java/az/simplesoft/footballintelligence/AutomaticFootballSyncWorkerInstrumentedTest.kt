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
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
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
            LocalRejectingProxy().use { proxy ->
                val worker = TestListenableWorkerBuilder<AutomaticFootballSyncWorker>(isolatedContext)
                    .setRunAttemptCount(1)
                    .build()

                val result = worker.doWork()

                assertEquals(ListenableWorker.Result.retry().javaClass, result.javaClass)
                assertTrue(
                    "OpenFootball should be routed to the local failing proxy.",
                    "api.github.com" in proxy.requestedHosts,
                )
                assertTrue(
                    "OpenLigaDB should be routed to the local failing proxy.",
                    "api.openligadb.de" in proxy.requestedHosts,
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
            }
        } finally {
            baseContext.deleteDatabase(databaseName)
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

    private class LocalRejectingProxy : Closeable {
        private val originalSelector = ProxySelector.getDefault()
        private val running = AtomicBoolean(true)
        private val server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        val requestedHosts = ConcurrentLinkedQueue<String>()
        private val acceptThread = Thread {
            while (running.get()) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                runCatching { socket.close() }
            }
        }.apply {
            name = "football-test-rejecting-proxy"
            isDaemon = true
            start()
        }

        init {
            val proxyAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort)
            val localProxy = Proxy(Proxy.Type.HTTP, proxyAddress)
            ProxySelector.setDefault(
                object : ProxySelector() {
                    override fun select(uri: URI): List<Proxy> {
                        uri.host?.let(requestedHosts::add)
                        return listOf(localProxy)
                    }
                    override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) = Unit
                },
            )
        }

        override fun close() {
            ProxySelector.setDefault(originalSelector)
            running.set(false)
            runCatching { server.close() }
            runCatching { acceptThread.join(1_000) }
        }
    }
}
