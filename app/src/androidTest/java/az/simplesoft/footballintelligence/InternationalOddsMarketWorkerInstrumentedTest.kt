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
import az.simplesoft.footballintelligence.data.InternationalOddsStatus
import az.simplesoft.footballintelligence.data.InternationalOddsQualityCounts
import az.simplesoft.footballintelligence.data.InternationalOddsSnapshot
import az.simplesoft.footballintelligence.data.MOVIODDS_API_URL
import az.simplesoft.footballintelligence.MOVIODDS_AGE_CONSENT_KEY
import az.simplesoft.footballintelligence.MOVIODDS_AGE_CONSENT_PREFS
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
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class InternationalOddsMarketWorkerInstrumentedTest {
    @Test fun v12UpgradeAddsLedgerWithoutDroppingExistingRows() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val name="fi-intl-migration-${UUID.randomUUID()}.db"
        base.deleteDatabase(name)
        val isolated=IsolatedDatabaseContext(base,name)
        val now=System.currentTimeMillis()
        try {
            isolated.openOrCreateDatabase(name,Context.MODE_PRIVATE,null).use { db ->
                db.execSQL("CREATE TABLE legacy_probe (id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
                db.execSQL("INSERT INTO legacy_probe VALUES (1,'retained')")
                db.version=12
            }
            Store(isolated,name).use { store ->
                assertEquals(16,store.writableDatabase.version)
                store.saveInternationalOddsCapture(now,InternationalOddsStatus.LIVE,listOf(
                    InternationalOddsSnapshot("moviodds_bet365","bet365","migration-event",MOVIODDS_API_URL,"Serie A",null,now,now,now+60*60*1000L,"1X2","Roma","Napoli",2.1,3.2,3.5,now,now,now),
                ),"migration test")
                store.writableDatabase.rawQuery("SELECT value FROM legacy_probe WHERE id=1",null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("retained",cursor.getString(0))
                }
            }
            Store(base,name).use { store ->
                assertEquals(16,store.writableDatabase.version)
                assertEquals("migration-event",store.internationalOddsHistory().single().eventId)
            }
        } finally { base.deleteDatabase(name) }
    }

    @Test fun workerStoresAndReopensFreshExactLinkedPriceHistory()=runBlocking {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val name="fi-intl-odds-${UUID.randomUUID()}.db"
        base.deleteDatabase(name)
        val isolated=IsolatedDatabaseContext(base,name)
        val now=System.currentTimeMillis()
        val kickoff=now+24*60*60*1000L
        setAgeConfirmation(base,true)
        try {
            Store(isolated,name).use { store ->
                store.upsert(listOf(Match(701,"ita","Roma","Napoli",Instant.ofEpochMilli(kickoff).toString(),false,null,null,"openligadb:701")))
            }
            val worker=TestListenableWorkerBuilder<InternationalOddsMarketWorker>(isolated).build()
            worker.providerHttpClient=StaticClient(feed(now,kickoff))

            val result=worker.doWork()

            assertEquals(ListenableWorker.Result.success().javaClass,result.javaClass)
            Store(base,name).use { store ->
                val state=store.internationalOddsSyncState()!!
                assertEquals(InternationalOddsStatus.LIVE,state.status)
                assertEquals(1,state.freshRecordsStored)
                assertEquals(InternationalOddsQualityCounts(1,1,0,0,0,0),state.qualityCounts)
                val attempt=store.internationalOddsSyncHistory().single()
                assertEquals(InternationalOddsStatus.LIVE,attempt.status)
                assertEquals(InternationalOddsQualityCounts(1,1,0,0,0,0),attempt.qualityCounts)
                val saved=store.freshInternationalOdds().single()
                assertEquals("bet365",saved.bookmakerId)
                assertEquals("9001",saved.eventId)
                assertEquals(701,saved.matchId)
                assertTrue(saved.providerPolledAtMillis>0L)
                assertEquals(1,store.internationalOddsHistory().size)
                val nextCapture=saved.capturedAtMillis+60_000L
                store.saveInternationalOddsCapture(nextCapture,InternationalOddsStatus.LIVE,listOf(
                    saved.copy(capturedAtMillis=nextCapture,providerPolledAtMillis=saved.providerPolledAtMillis+60_000L),
                ),"same prices; newly confirmed")
                assertEquals("unchanged price polls update only current state",1,store.internationalOddsHistory().size)
                assertEquals(nextCapture,store.freshInternationalOdds(nextCapture).single().capturedAtMillis)
                store.saveInternationalOddsCapture(nextCapture+60_000L,InternationalOddsStatus.UNAVAILABLE,emptyList(),"event disappeared from the current board",replaceCurrentBoard=true)
                assertTrue(store.freshInternationalOdds(nextCapture+60_000L).isEmpty())
                assertEquals("removed current rows remain as history",1,store.internationalOddsHistory().size)
            }
        } finally { setAgeConfirmation(base,false);base.deleteDatabase(name) }
    }

    @Test fun staleSourceReadIsSavedAsStaleAndNeverPromotedToFresh()=runBlocking {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val name="fi-intl-stale-${UUID.randomUUID()}.db"
        base.deleteDatabase(name)
        val isolated=IsolatedDatabaseContext(base,name)
        val now=System.currentTimeMillis()
        setAgeConfirmation(base,true)
        try {
            val worker=TestListenableWorkerBuilder<InternationalOddsMarketWorker>(isolated).build()
            worker.providerHttpClient=StaticClient(feed(now,now+60*60*1000L,pollAt=now-16*60*1000L))

            assertEquals(ListenableWorker.Result.success().javaClass,worker.doWork().javaClass)
            Store(base,name).use { store ->
                assertEquals(InternationalOddsStatus.STALE,store.internationalOddsSyncState()!!.status)
                assertTrue(store.freshInternationalOdds().isEmpty())
                assertTrue(store.internationalOddsHistory().isEmpty())
            }
        } finally { setAgeConfirmation(base,false);base.deleteDatabase(name) }
    }

    @Test fun blockedAndOfflineResponsesAreRecordedWithoutAccessEvasion()=runBlocking {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val name="fi-intl-blocked-${UUID.randomUUID()}.db"
        base.deleteDatabase(name)
        val isolated=IsolatedDatabaseContext(base,name)
        setAgeConfirmation(base,true)
        try {
            val blocked=TestListenableWorkerBuilder<InternationalOddsMarketWorker>(isolated).build()
            blocked.providerHttpClient=object:ProviderHttpClient {
                override fun get(url:String,cache:ProviderFileCache?):ProviderHttpResult {
                    assertEquals(MOVIODDS_API_URL,url)
                    return ProviderHttpResult(403,"",null,null)
                }
            }
            assertEquals(ListenableWorker.Result.success().javaClass,blocked.doWork().javaClass)
            Store(base,name).use { store -> assertEquals(InternationalOddsStatus.BLOCKED,store.internationalOddsSyncState()!!.status) }

            val offline=TestListenableWorkerBuilder<InternationalOddsMarketWorker>(isolated).build()
            offline.providerHttpClient=object:ProviderHttpClient {
                override fun get(url:String,cache:ProviderFileCache?):ProviderHttpResult = throw IOException("simulated offline")
            }
            assertEquals(ListenableWorker.Result.retry().javaClass,offline.doWork().javaClass)
            Store(base,name).use { store -> assertEquals(InternationalOddsStatus.ERROR,store.internationalOddsSyncState()!!.status) }
        } finally { setAgeConfirmation(base,false);base.deleteDatabase(name) }
    }

    @Test fun workerDoesNotRequestFeedBeforeAgeConfirmation()=runBlocking {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val name="fi-intl-age-${UUID.randomUUID()}.db"
        base.deleteDatabase(name)
        setAgeConfirmation(base,false)
        val isolated=IsolatedDatabaseContext(base,name)
        var requests=0
        try {
            val worker=TestListenableWorkerBuilder<InternationalOddsMarketWorker>(isolated).build()
            worker.providerHttpClient=object:ProviderHttpClient {
                override fun get(url:String,cache:ProviderFileCache?):ProviderHttpResult {
                    requests++
                    throw AssertionError("Feed must not be requested before age confirmation.")
                }
            }
            assertEquals(ListenableWorker.Result.success().javaClass,worker.doWork().javaClass)
            assertEquals(0,requests)
            Store(base,name).use { store ->
                assertEquals(InternationalOddsStatus.UNVERIFIED,store.internationalOddsSyncState()!!.status)
                assertTrue(store.freshInternationalOdds().isEmpty())
            }
        } finally { setAgeConfirmation(base,false);base.deleteDatabase(name) }
    }

    @Test fun schedulerKeepsInternationalFeedDisabledUntilAgeConfirmation() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val manager=WorkManager.getInstance(context)
        clearInternationalWork(manager)
        setAgeConfirmation(context,false)
        try {
            InternationalOddsMarketScheduler.scheduleOnAppOpen(context)
            assertTrue(activeInternationalWork(manager,InternationalOddsMarketScheduler.UNIQUE_ONCE_NAME).isEmpty())
            assertTrue(activeInternationalWork(manager,InternationalOddsMarketScheduler.UNIQUE_PERIODIC_NAME).isEmpty())

            setAgeConfirmation(context,true)
            val delay=TimeUnit.HOURS.toMillis(1)
            InternationalOddsMarketScheduler.enqueueForFreshness(context,null,System.currentTimeMillis(),delay)
            val once=awaitSingleInternationalWork(manager,InternationalOddsMarketScheduler.UNIQUE_ONCE_NAME)
            val periodic=awaitSingleInternationalWork(manager,InternationalOddsMarketScheduler.UNIQUE_PERIODIC_NAME)
            assertEquals(WorkInfo.State.ENQUEUED,once.state)
            assertEquals(WorkInfo.State.ENQUEUED,periodic.state)
            assertEquals(NetworkType.CONNECTED,once.constraints.requiredNetworkType)
            assertEquals(NetworkType.CONNECTED,periodic.constraints.requiredNetworkType)
            assertEquals(delay,once.initialDelayMillis)
            assertEquals(TimeUnit.MINUTES.toMillis(30),periodic.periodicityInfo?.repeatIntervalMillis)
        } finally {
            clearInternationalWork(manager)
            setAgeConfirmation(context,false)
        }
    }

    private fun feed(serverMillis:Long,kickoffMillis:Long,pollAt:Long=serverMillis-1_000L):String {
        val priceTime=Instant.ofEpochMilli(serverMillis-20*60_000L)
        return """{"server_time":"${Instant.ofEpochMilli(serverMillis)}","match_count":1,"matches":[{
          "id":"9001","home":"Roma","away":"Napoli","league":"Serie A","sport":"soccer","region":"uk","in_play":false,
          "kickoff_utc":"${Instant.ofEpochMilli(kickoffMillis)}","group_status":{"40":{"polled_at":"${Instant.ofEpochMilli(pollAt)}","stale":false}},
          "quotes":[
            {"group_id":"40","market_id":"40","label":"Roma","odds_dec":2.15,"ts":"$priceTime"},
            {"group_id":"40","market_id":"40","label":"Draw","odds_dec":3.2,"ts":"$priceTime"},
            {"group_id":"40","market_id":"40","label":"Napoli","odds_dec":3.4,"ts":"$priceTime"}
          ]
        }]}"""
    }

    private class StaticClient(private val body:String):ProviderHttpClient {
        override fun get(url:String,cache:ProviderFileCache?)=ProviderHttpResult(200,body,null,null)
    }

    private fun setAgeConfirmation(context:Context,confirmed:Boolean) {
        context.getSharedPreferences(MOVIODDS_AGE_CONSENT_PREFS,Context.MODE_PRIVATE)
            .edit().putBoolean(MOVIODDS_AGE_CONSENT_KEY,confirmed).commit()
    }

    private fun activeInternationalWork(manager:WorkManager,name:String):List<WorkInfo> =
        manager.getWorkInfosForUniqueWork(name).get(10,TimeUnit.SECONDS)
            .filter { it.state==WorkInfo.State.ENQUEUED||it.state==WorkInfo.State.RUNNING }

    private fun awaitSingleInternationalWork(manager:WorkManager,name:String):WorkInfo {
        val deadline=System.currentTimeMillis()+TimeUnit.SECONDS.toMillis(30)
        while(System.currentTimeMillis()<deadline) {
            val active=activeInternationalWork(manager,name)
            if(active.size==1) return active.single()
            check(active.size<=1) { "Expected at most one active request for $name; got ${active.size}." }
            Thread.sleep(100)
        }
        throw AssertionError("Timed out waiting for $name.")
    }

    private fun clearInternationalWork(manager:WorkManager) {
        manager.cancelUniqueWork(InternationalOddsMarketScheduler.UNIQUE_ONCE_NAME).result.get(10,TimeUnit.SECONDS)
        manager.cancelUniqueWork(InternationalOddsMarketScheduler.UNIQUE_PERIODIC_NAME).result.get(10,TimeUnit.SECONDS)
    }

    private class IsolatedDatabaseContext(base:Context,private val databaseName:String):ContextWrapper(base) {
        override fun getApplicationContext():Context=this
        override fun getDatabasePath(name:String):File=super.getDatabasePath(databaseName)
        override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?):SQLiteDatabase = super.openOrCreateDatabase(databaseName,mode,factory)
        override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?,errorHandler:DatabaseErrorHandler?):SQLiteDatabase = super.openOrCreateDatabase(databaseName,mode,factory,errorHandler)
    }
}
