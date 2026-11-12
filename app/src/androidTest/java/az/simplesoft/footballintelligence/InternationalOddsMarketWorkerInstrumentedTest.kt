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
import az.simplesoft.footballintelligence.data.InternationalOddsQuoteSide
import az.simplesoft.footballintelligence.data.InternationalMatchLinkBasis
import az.simplesoft.footballintelligence.data.MOVIODDS_API_URL
import az.simplesoft.footballintelligence.data.KickoffPrecision
import az.simplesoft.footballintelligence.data.ProviderMatch
import az.simplesoft.footballintelligence.data.THE_ODDS_API_BASE_URL
import az.simplesoft.footballintelligence.MOVIODDS_AGE_CONSENT_KEY
import az.simplesoft.footballintelligence.MOVIODDS_AGE_CONSENT_PREFS
import az.simplesoft.footballintelligence.data.ProviderFileCache
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
                assertEquals(18,store.writableDatabase.version)
                store.saveInternationalOddsCapture(now,InternationalOddsStatus.LIVE,listOf(
                    InternationalOddsSnapshot("moviodds_bet365","bet365","migration-event",MOVIODDS_API_URL,"Serie A",null,now,now,now+60*60*1000L,"1X2","Roma","Napoli",2.1,3.2,3.5,now,now,now),
                ),"migration test")
                store.writableDatabase.rawQuery("SELECT value FROM legacy_probe WHERE id=1",null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("retained",cursor.getString(0))
                }
            }
            Store(base,name).use { store ->
                assertEquals(18,store.writableDatabase.version)
                assertEquals("migration-event",store.internationalOddsHistory().single().eventId)
            }
        } finally { base.deleteDatabase(name) }
    }

    @Test fun v16UpgradePreservesOddsAndLabelsPreviouslyLinkedRowsAsExact() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val name="fi-v16-link-basis-${UUID.randomUUID()}.db"
        base.deleteDatabase(name)
        val captured=System.currentTimeMillis()
        val kickoff=captured+2*60*60*1000L
        try {
            base.openOrCreateDatabase(name,Context.MODE_PRIVATE,null).use { db ->
                val columns="id INTEGER PRIMARY KEY AUTOINCREMENT, provider_id TEXT NOT NULL, bookmaker_id TEXT NOT NULL, event_id TEXT NOT NULL, match_id INTEGER, source_url TEXT NOT NULL, league TEXT NOT NULL, region TEXT, captured_at INTEGER NOT NULL, provider_polled_at INTEGER NOT NULL, kickoff INTEGER NOT NULL, market TEXT NOT NULL, home_team TEXT NOT NULL, away_team TEXT NOT NULL, home_odds REAL NOT NULL, draw_odds REAL NOT NULL, away_odds REAL NOT NULL, home_price_changed_at INTEGER NOT NULL, draw_price_changed_at INTEGER NOT NULL, away_price_changed_at INTEGER NOT NULL"
                db.execSQL("CREATE TABLE international_odds_snapshots ($columns, UNIQUE(provider_id,bookmaker_id,event_id,captured_at))")
                db.execSQL("CREATE TABLE international_odds_current ($columns, UNIQUE(provider_id,bookmaker_id,event_id))")
                val row="provider_id,bookmaker_id,event_id,match_id,source_url,league,region,captured_at,provider_polled_at,kickoff,market,home_team,away_team,home_odds,draw_odds,away_odds,home_price_changed_at,draw_price_changed_at,away_price_changed_at"
                val values="'the_odds_api','unibet_fr','linked-event',42,'https://api.the-odds-api.com/v4/sports/soccer_epl/odds','EPL','eu',$captured,$captured,$kickoff,'1X2','Manchester City','Fulham',1.27,4.5,5.8,$captured,$captured,$captured"
                db.execSQL("INSERT INTO international_odds_snapshots($row) VALUES($values)")
                db.execSQL("INSERT INTO international_odds_current($row) VALUES($values)")
                db.version=16
            }
            Store(base,name).use { store ->
                assertEquals(18,store.writableDatabase.version)
                val history=store.internationalOddsHistory().single()
                assertEquals(42,history.matchId)
                assertEquals(InternationalMatchLinkBasis.EXACT_KICKOFF,history.matchLinkBasis)
                assertEquals(InternationalMatchLinkBasis.EXACT_KICKOFF,store.freshInternationalOdds(captured).single().matchLinkBasis)
            }
        } finally { base.deleteDatabase(name) }
    }

    @Test fun v17UpgradePreservesOddsAndAllowsBackAndLayForOneBookEvent() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "fi-v17-back-lay-${UUID.randomUUID()}.db"
        base.deleteDatabase(name)
        val captured = System.currentTimeMillis()
        val kickoff = captured + TimeUnit.HOURS.toMillis(2)
        try {
            base.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
                val common = "id INTEGER PRIMARY KEY AUTOINCREMENT, provider_id TEXT NOT NULL, bookmaker_id TEXT NOT NULL, " +
                    "event_id TEXT NOT NULL, match_id INTEGER, match_link_basis TEXT, source_url TEXT NOT NULL, " +
                    "league TEXT NOT NULL, region TEXT, captured_at INTEGER NOT NULL, provider_polled_at INTEGER NOT NULL, " +
                    "kickoff INTEGER NOT NULL, market TEXT NOT NULL, home_team TEXT NOT NULL, away_team TEXT NOT NULL, " +
                    "home_odds REAL NOT NULL, draw_odds REAL NOT NULL, away_odds REAL NOT NULL, " +
                    "home_price_changed_at INTEGER NOT NULL, draw_price_changed_at INTEGER NOT NULL, away_price_changed_at INTEGER NOT NULL"
                db.execSQL("CREATE TABLE international_odds_snapshots ($common, UNIQUE(provider_id,bookmaker_id,event_id,captured_at))")
                db.execSQL("CREATE TABLE international_odds_current ($common, UNIQUE(provider_id,bookmaker_id,event_id))")
                db.execSQL("CREATE TABLE international_odds_sync_state (provider_id TEXT PRIMARY KEY, status TEXT NOT NULL, last_checked INTEGER NOT NULL, last_success INTEGER, fresh_records_stored INTEGER NOT NULL DEFAULT 0, detail TEXT, quality_received INTEGER, quality_accepted INTEGER, quality_rejected INTEGER, quality_stale INTEGER, quality_duplicates INTEGER, quality_unverified INTEGER)")
                db.execSQL("CREATE TABLE international_odds_sync_attempts (id INTEGER PRIMARY KEY AUTOINCREMENT, provider_id TEXT NOT NULL, checked_at INTEGER NOT NULL, status TEXT NOT NULL, quality_received INTEGER, quality_accepted INTEGER, quality_rejected INTEGER, quality_stale INTEGER, quality_duplicates INTEGER, quality_unverified INTEGER, detail TEXT)")
                val columns = "provider_id,bookmaker_id,event_id,match_id,match_link_basis,source_url,league,region,captured_at,provider_polled_at,kickoff,market,home_team,away_team,home_odds,draw_odds,away_odds,home_price_changed_at,draw_price_changed_at,away_price_changed_at"
                val values = "'the_odds_api','betfair_ex_eu','exchange-event',42,'EXACT_KICKOFF','https://api.the-odds-api.com/v4/sports/soccer_epl/odds','EPL','eu',$captured,$captured,$kickoff,'1X2','Manchester City','Fulham',2.1,3.4,3.8,$captured,$captured,$captured"
                db.execSQL("INSERT INTO international_odds_snapshots($columns) VALUES($values)")
                db.execSQL("INSERT INTO international_odds_current($columns) VALUES($values)")
                db.version = 17
            }

            Store(base, name).use { store ->
                assertEquals(18, store.writableDatabase.version)
                val migrated = store.internationalOddsHistory().single()
                assertEquals("h2h", migrated.sourceMarketKey)
                assertEquals(InternationalOddsQuoteSide.BACK, migrated.quoteSide)
                assertNull(migrated.homeBetLimit)
                assertEquals(42, migrated.matchId)
                assertEquals(1, store.freshInternationalOdds(captured).size)

                val nextCapture = captured + 1_000L
                val back = migrated.copy(
                    capturedAtMillis = nextCapture,
                    providerPolledAtMillis = nextCapture,
                    home = 2.12,
                    homePriceChangedAtMillis = nextCapture,
                    drawPriceChangedAtMillis = nextCapture,
                    awayPriceChangedAtMillis = nextCapture,
                )
                val lay = back.copy(
                    home = 2.16,
                    draw = 3.48,
                    away = 3.88,
                    sourceMarketKey = "h2h_lay",
                    quoteSide = InternationalOddsQuoteSide.LAY,
                    homeBetLimit = 30.0,
                    drawBetLimit = 25.0,
                    awayBetLimit = 35.0,
                )
                store.saveInternationalOddsCapture(
                    nextCapture,
                    InternationalOddsStatus.LIVE,
                    listOf(back, lay),
                    "back and lay saved",
                    providerId = "the_odds_api",
                )
                val current = store.freshInternationalOdds(nextCapture)
                assertEquals(2, current.size)
                assertEquals(setOf(InternationalOddsQuoteSide.BACK, InternationalOddsQuoteSide.LAY), current.map { it.quoteSide }.toSet())
                assertEquals(3, store.internationalOddsHistory().size)
                val savedLay = current.single { it.quoteSide == InternationalOddsQuoteSide.LAY }
                assertEquals("h2h_lay", savedLay.sourceMarketKey)
                assertEquals(30.0, savedLay.homeBetLimit!!, 0.0)
            }
        } finally {
            base.deleteDatabase(name)
        }
    }

    @Test fun v17UpgradeRepairsMissingCurrentTableAndPreservesLegacySnapshot() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "fi-v17-partial-odds-${UUID.randomUUID()}.db"
        base.deleteDatabase(name)
        val captured = System.currentTimeMillis()
        val kickoff = captured + TimeUnit.HOURS.toMillis(2)
        try {
            base.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
                val legacyColumns = "id INTEGER PRIMARY KEY AUTOINCREMENT, provider_id TEXT NOT NULL, bookmaker_id TEXT NOT NULL, " +
                    "event_id TEXT NOT NULL, match_id INTEGER, match_link_basis TEXT, source_url TEXT NOT NULL, " +
                    "league TEXT NOT NULL, region TEXT, captured_at INTEGER NOT NULL, provider_polled_at INTEGER NOT NULL, " +
                    "kickoff INTEGER NOT NULL, market TEXT NOT NULL, home_team TEXT NOT NULL, away_team TEXT NOT NULL, " +
                    "home_odds REAL NOT NULL, draw_odds REAL NOT NULL, away_odds REAL NOT NULL, " +
                    "home_price_changed_at INTEGER NOT NULL, draw_price_changed_at INTEGER NOT NULL, away_price_changed_at INTEGER NOT NULL"
                db.execSQL(
                    "CREATE TABLE international_odds_snapshots ($legacyColumns, " +
                        "UNIQUE(provider_id,bookmaker_id,event_id,market,captured_at))",
                )
                db.execSQL(
                    "INSERT INTO international_odds_snapshots(id,provider_id,bookmaker_id,event_id,match_id,match_link_basis, " +
                        "source_url,league,region,captured_at,provider_polled_at,kickoff,market,home_team,away_team," +
                        "home_odds,draw_odds,away_odds,home_price_changed_at,draw_price_changed_at,away_price_changed_at) " +
                        "VALUES(7,'the_odds_api','betfair_ex_eu','partial-event',42,'EXACT_KICKOFF'," +
                        "'https://api.the-odds-api.com/v4/sports/soccer_epl/odds','EPL','eu',?,?,?,?,?,?,2.1,3.4,3.8,?,?,?)",
                    arrayOf(captured,captured,kickoff,"1X2","Manchester City","Fulham",captured,captured,captured),
                )
                db.version = 17
            }

            Store(base, name).use { store ->
                val db = store.writableDatabase
                assertEquals(18, db.version)
                assertTrue(tableExists(db, "international_odds_current"))
                assertTrue(columnExists(db, "international_odds_snapshots", "quote_side"))
                assertTrue(columnExists(db, "international_odds_snapshots", "home_bet_limit"))
                assertTrue(columnExists(db, "international_odds_current", "quote_side"))
                val migrated = store.internationalOddsHistory().single()
                assertEquals("partial-event", migrated.eventId)
                assertEquals("h2h", migrated.sourceMarketKey)
                assertEquals(InternationalOddsQuoteSide.BACK, migrated.quoteSide)
                assertEquals(42, migrated.matchId)
                assertEquals(1, store.freshInternationalOdds(captured).size)
                db.rawQuery(
                    "SELECT id,source_market_key,quote_side FROM international_odds_snapshots WHERE event_id='partial-event'",
                    null,
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(7, cursor.getLong(0))
                    assertEquals("h2h", cursor.getString(1))
                    assertEquals("BACK", cursor.getString(2))
                }
            }
        } finally {
            base.deleteDatabase(name)
        }
    }

    @Test fun dateOnlyLinkIsLabeledAndUpgradedWhenExactKickoffArrives() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val name="fi-date-only-odds-link-${UUID.randomUUID()}.db"
        base.deleteDatabase(name)
        val isolated=IsolatedDatabaseContext(base,name)
        val captured=System.currentTimeMillis()
        val date=LocalDate.of(2026,11,21)
        val dateOnlyKickoff=date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val exactKickoff=Instant.parse("2026-11-21T12:30:00Z").toEpochMilli()
        try {
            Store(isolated,name).use { store ->
                store.upsertProviderMatches(listOf(
                    ProviderMatch(
                        providerId="openfootball",providerMatchId="epl-date-only",competitionCode="E0",
                        competitionName="Premier League",season="2026-27",eventDate=date,
                        kickoffMillis=dateOnlyKickoff,kickoffPrecision=KickoffPrecision.DATE_ONLY,
                        homeTeam="Manchester City",awayTeam="Fulham",homeGoals=null,awayGoals=null,
                        safeAvailableAtMillis=null,sourceUrl="https://github.com/openfootball/football.json",
                        capturedAtMillis=captured,
                    ),
                ))
                val fixture=store.matchIdentities().single()
                assertEquals(false,fixture.exactKickoff)
                val snapshot=InternationalOddsSnapshot(
                    providerId="the_odds_api",bookmakerId="unibet_fr",eventId="epl-event",
                    sourceUrl="$THE_ODDS_API_BASE_URL/sports/soccer_epl/odds",league="EPL",region="eu",
                    capturedAtMillis=captured,providerPolledAtMillis=captured,kickoffMillis=exactKickoff,
                    market="1X2",homeTeam="Manchester City",awayTeam="Fulham",home=1.27,draw=4.5,away=5.8,
                    homePriceChangedAtMillis=captured,drawPriceChangedAtMillis=captured,awayPriceChangedAtMillis=captured,
                    matchId=fixture.matchId,matchLinkBasis=InternationalMatchLinkBasis.UNIQUE_DATE_ONLY,
                )
                store.saveInternationalOddsCapture(captured,InternationalOddsStatus.LIVE,listOf(snapshot),"date-only link",providerId="the_odds_api")
                assertEquals(InternationalMatchLinkBasis.UNIQUE_DATE_ONLY,store.internationalOddsHistory().single().matchLinkBasis)

                store.upsertProviderMatches(listOf(
                    ProviderMatch(
                        providerId="openligadb",providerMatchId="990021",competitionCode="E0",
                        competitionName="Premier League",season="2026-27",eventDate=date,
                        kickoffMillis=exactKickoff,kickoffPrecision=KickoffPrecision.INSTANT,
                        homeTeam="Manchester City",awayTeam="Fulham",homeGoals=null,awayGoals=null,
                        safeAvailableAtMillis=null,sourceUrl="https://www.openligadb.de/api/getmatchdata",
                        capturedAtMillis=captured+1,
                    ),
                ))
                assertEquals(true,store.matchIdentities().single().exactKickoff)
                assertTrue(store.relinkUnmatchedInternationalOdds(captured+2)>0)
                val relinked=store.internationalOddsHistory().single()
                assertEquals(fixture.matchId,relinked.matchId)
                assertEquals(InternationalMatchLinkBasis.EXACT_KICKOFF,relinked.matchLinkBasis)
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

    private fun tableExists(db:SQLiteDatabase,table:String):Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
        arrayOf(table),
    ).use { it.moveToFirst() }

    private fun columnExists(db:SQLiteDatabase,table:String,column:String):Boolean = db.rawQuery(
        "PRAGMA table_info($table)",
        null,
    ).use { cursor ->
        var found=false
        while(cursor.moveToNext()) if(cursor.getString(cursor.getColumnIndexOrThrow("name"))==column) found=true
        found
    }

    private class IsolatedDatabaseContext(base:Context,private val databaseName:String):ContextWrapper(base) {
        override fun getApplicationContext():Context=this
        override fun getDatabasePath(name:String):File=super.getDatabasePath(databaseName)
        override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?):SQLiteDatabase = super.openOrCreateDatabase(databaseName,mode,factory)
        override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?,errorHandler:DatabaseErrorHandler?):SQLiteDatabase = super.openOrCreateDatabase(databaseName,mode,factory,errorHandler)
    }
}
