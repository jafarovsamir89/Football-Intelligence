package az.simplesoft.footballintelligence

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import az.simplesoft.footballintelligence.data.MarketReferenceStatus
import az.simplesoft.footballintelligence.data.ProviderFileCache
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TipsAuditMarketReferenceWorkerInstrumentedTest {
    @Test fun workerStoresFreshDerivedReferenceAndExactFixtureLink() = runBlocking {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName="fi-tipsaudit-worker-${UUID.randomUUID()}.db"
        base.deleteDatabase(databaseName)
        val context=IsolatedDatabaseContext(base,databaseName)
        val now=System.currentTimeMillis()
        val kickoff=now+24*60*60*1000L
        try {
            Store(context,databaseName).use { store ->
                store.upsert(listOf(Match(16388,"nla","Türkei","Belgien",Instant.ofEpochMilli(kickoff).toString(),false,null,null,"openligadb:16388")))
            }
            val worker=TestListenableWorkerBuilder<TipsAuditMarketReferenceWorker>(context).build()
            worker.providerHttpClient=StaticProviderHttpClient(response(now,kickoff))

            val result=worker.doWork()

            assertEquals(ListenableWorker.Result.success().javaClass,result.javaClass)
            Store(base,databaseName).use { store ->
                val state=store.marketReferenceSyncState()!!
                assertEquals(MarketReferenceStatus.OK,state.status)
                assertEquals(1,state.freshRecordsStored)
                val saved=store.freshMarketReferences().single()
                assertEquals(16388,saved.matchId)
                assertEquals("16388",saved.externalEventId)
                assertTrue(saved.homeFairOdds>1.0)
            }
        } finally {
            base.deleteDatabase(databaseName)
        }
    }

    @Test fun networkFailureIsRecordedAndRetried() = runBlocking {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName="fi-tipsaudit-error-${UUID.randomUUID()}.db"
        base.deleteDatabase(databaseName)
        val context=IsolatedDatabaseContext(base,databaseName)
        try {
            val worker=TestListenableWorkerBuilder<TipsAuditMarketReferenceWorker>(context).build()
            worker.providerHttpClient=object:ProviderHttpClient {
                override fun get(url:String,cache:ProviderFileCache?):ProviderHttpResult = throw IOException("simulated offline")
            }

            val result=worker.doWork()

            assertEquals(ListenableWorker.Result.retry().javaClass,result.javaClass)
            Store(base,databaseName).use { store ->
                assertEquals(MarketReferenceStatus.ERROR,store.marketReferenceSyncState()!!.status)
                assertTrue(store.freshMarketReferences().isEmpty())
            }
        } finally {
            base.deleteDatabase(databaseName)
        }
    }

    private fun response(readAtMillis:Long,kickoffMillis:Long):String = """
        {"methodology_version":"2.0","predictions":[{
          "id":16388,"home":"Türkiye","away":"Belgium",
          "kickoff_utc":"${Instant.ofEpochMilli(kickoffMillis)}",
          "fair_source":"live","fair_read_at":"${Instant.ofEpochMilli(readAtMillis-1_000L)}",
          "fair_probabilities":{"home":0.2427,"draw":0.2282,"away":0.5291},
          "fair_odds":{"home":4.12,"draw":4.38,"away":1.89},
          "url":"https://tipsaudit.com/match/16388/turkiye-vs-belgium"
        }]}
    """.trimIndent()

    private class StaticProviderHttpClient(private val body:String):ProviderHttpClient {
        override fun get(url:String,cache:ProviderFileCache?):ProviderHttpResult = ProviderHttpResult(200,body,null,null)
    }

    private class IsolatedDatabaseContext(base:Context,private val databaseName:String):ContextWrapper(base) {
        override fun getApplicationContext():Context=this
        override fun getDatabasePath(name:String):File=super.getDatabasePath(databaseName)
        override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?):SQLiteDatabase = super.openOrCreateDatabase(databaseName,mode,factory)
        override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?,errorHandler:DatabaseErrorHandler?):SQLiteDatabase = super.openOrCreateDatabase(databaseName,mode,factory,errorHandler)
    }
}
