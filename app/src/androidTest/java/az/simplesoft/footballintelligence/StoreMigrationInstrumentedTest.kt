package az.simplesoft.footballintelligence

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import az.simplesoft.footballintelligence.analytics.ForecastedMatch
import az.simplesoft.footballintelligence.analytics.AccumulatorPerformanceEngine
import az.simplesoft.footballintelligence.analytics.CouponSelectionBasis
import az.simplesoft.footballintelligence.analytics.JointProbabilityBounds
import az.simplesoft.footballintelligence.analytics.OutcomeProbabilities
import az.simplesoft.footballintelligence.analytics.ProspectiveForecastEvaluator
import az.simplesoft.footballintelligence.analytics.VirtualCouponCandidate
import az.simplesoft.footballintelligence.analytics.VirtualCouponPick
import az.simplesoft.footballintelligence.data.KickoffPrecision
import az.simplesoft.footballintelligence.data.InternationalOddsStatus
import az.simplesoft.footballintelligence.data.InternationalOddsQualityCounts
import az.simplesoft.footballintelligence.data.ProviderMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class StoreMigrationInstrumentedTest {
    @Test fun storeCanBeUsedAsCloseableOnMinimumApi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "fi-store-closeable-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        val store = Store(context, databaseName)

        try {
            assertTrue(java.io.Closeable::class.java.isAssignableFrom(Store::class.java))
            store.writableDatabase
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test fun foregroundForecastWriteWaitsForBackgroundStoreTransaction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "fi-wal-writer-serialization-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        val backgroundStore = Store(context, databaseName)
        val foregroundStore = Store(context, databaseName)
        val executor = Executors.newFixedThreadPool(2)
        val transactionStarted = CountDownLatch(1)
        val releaseTransaction = CountDownLatch(1)
        val now = System.currentTimeMillis()
        val kickoff = now + TimeUnit.HOURS.toMillis(3)
        try {
            backgroundStore.upsert(
                listOf(
                    Match(
                        id = 910001,
                        league = "writer-race",
                        home = "Home FC",
                        away = "Away FC",
                        kickoff = Instant.ofEpochMilli(kickoff).toString(),
                        finished = false,
                        homeScore = null,
                        awayScore = null,
                    ),
                ),
            )

            val backgroundTransaction = executor.submit {
                StoreWriteCoordinator.withLock {
                    val db = backgroundStore.writableDatabase
                    db.beginTransaction()
                    try {
                        db.execSQL(
                            "INSERT INTO provider_sync_state(provider_id,status,last_checked) VALUES(?,?,?)",
                            arrayOf("held-writer", "RUNNING", now),
                        )
                        transactionStarted.countDown()
                        check(releaseTransaction.await(5, TimeUnit.SECONDS)) {
                            "Timed out waiting to release the background writer."
                        }
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                }
            }
            assertTrue(transactionStarted.await(3, TimeUnit.SECONDS))

            val forecast = ForecastedMatch(
                matchId = 910001,
                homeTeam = "Home FC",
                awayTeam = "Away FC",
                kickoffMillis = kickoff,
                probabilities = OutcomeProbabilities(.6, .25, .15),
                forecastedAtMillis = now,
                historyMatchCount = 10,
                competition = "writer-race",
            )
            val foregroundThread = AtomicReference<Thread?>()
            val foregroundWrite = executor.submit<Int> {
                foregroundThread.set(Thread.currentThread())
                foregroundStore.recordProspectiveForecastSnapshots(listOf(forecast), now)
            }
            val queueDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (foregroundThread.get()?.let(StoreWriteCoordinator::isQueued) != true &&
                System.nanoTime() < queueDeadlineNanos
            ) {
                Thread.sleep(10)
            }
            assertTrue(
                "Foreground writer must queue on the in-process coordinator while the background transaction is active.",
                foregroundThread.get()?.let(StoreWriteCoordinator::isQueued) == true,
            )

            releaseTransaction.countDown()
            backgroundTransaction.get(5, TimeUnit.SECONDS)
            assertEquals(1, foregroundWrite.get(5, TimeUnit.SECONDS))
            assertEquals(1, foregroundStore.prospectiveForecastHistory(10).size)
        } finally {
            releaseTransaction.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            foregroundStore.close()
            backgroundStore.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test fun writeAheadLoggingAllowsForegroundReadsDuringProviderWrite() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "fi-wal-concurrency-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        val writer = Store(context, databaseName)
        val reader = Store(context, databaseName)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val db = writer.writableDatabase
            assertTrue("Store must enable WAL for concurrent provider reads.", db.isWriteAheadLoggingEnabled)
            db.beginTransaction()
            try {
                db.execSQL(
                    "INSERT INTO matches(id,league,home,away,kickoff,finished) " +
                        "VALUES(900001,'test','Alpha','Beta','2030-01-01T12:00:00Z',0)",
                )
                val count = executor.submit<Int> {
                    reader.readableDatabase.rawQuery("SELECT COUNT(*) FROM matches", null).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        cursor.getInt(0)
                    }
                }.get(3, TimeUnit.SECONDS)
                // A WAL reader sees the last committed snapshot while the provider batch is open.
                assertEquals(0, count)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            reader.readableDatabase.rawQuery("SELECT COUNT(*) FROM matches", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        } finally {
            executor.shutdownNow()
            reader.close()
            writer.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test fun versionOneUpgradeKeepsMatchesQuotesPicksTicketsAndCreatesCurrentTables() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "fi-v1-migration-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        try {
            createVersionOneDatabase(context, databaseName)

            Store(context, databaseName).use { store ->
                val db = store.writableDatabase
                assertEquals(19, db.version)
                assertTrue(tableExists(db, "historical_results"))
                assertTrue(tableExists(db, "provider_sync_state"))
                assertTrue(tableExists(db, "bookmaker_odds_snapshots"))
                assertTrue(tableExists(db, "virtual_accumulators"))
                assertTrue(tableExists(db, "prospective_forecast_snapshots"))
                assertTrue(tableExists(db, "prospective_forecast_settlements"))
                assertTrue(tableExists(db, "market_reference_snapshots"))
                assertTrue(tableExists(db, "market_reference_sync_state"))
                assertTrue(columnExists(db, "virtual_accumulator_legs", "market_fair_probability"))
                assertTrue(columnExists(db, "virtual_accumulator_legs", "model_minus_market"))
                assertTrue(columnExists(db, "international_odds_sync_state", "quality_received"))
                assertTrue(columnExists(db, "international_odds_sync_state", "quality_unverified"))
                assertTrue(tableExists(db, "international_odds_sync_attempts"))
                assertTrue(tableExists(db, "football_data_stats_attempts"))
                assertTrue(tableExists(db, "football_data_match_stats"))
                assertTrue(columnExists(db, "matches", "source_key"))
                assertTrue(columnExists(db, "matches", "captured_at"))

                val match = store.matches().single { it.id == 41 }
                assertEquals("Alpha", match.home)
                assertEquals("Beta", match.away)
                assertEquals("openligadb:41", match.sourceKey)
                assertTrue(match.finished)
                assertEquals(2, match.homeScore)
                assertEquals(1, match.awayScore)
                assertTrue((match.availableAtMillis ?: 0L) > 0L)
                assertEquals(1, store.quotes(41).size)
                assertEquals(1, store.picks(41).size)
                assertEquals(1, store.tickets().size)

                // A later unfinished provider observation must not erase the settled v1 result.
                store.upsert(
                    listOf(
                        Match(
                            id = 41,
                            league = "bl1",
                            home = "Alpha",
                            away = "Beta",
                            kickoff = "2025-05-01T17:00:00Z",
                            finished = false,
                            homeScore = null,
                            awayScore = null,
                            sourceKey = "openligadb:41",
                        ),
                    ),
                )
                val afterIncompleteRefresh = store.matches().single { it.id == 41 }
                assertTrue(afterIncompleteRefresh.finished)
                assertEquals(2, afterIncompleteRefresh.homeScore)
                assertEquals(1, afterIncompleteRefresh.awayScore)
                assertEquals(1, store.tickets().size)
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test fun versionEighteenUpgradeAddsFootballDataStatsTablesAndPreservesMatches() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "fi-v18-stats-migration-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        val db = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        db.execSQL("CREATE TABLE matches (id INTEGER PRIMARY KEY, league TEXT NOT NULL, home TEXT NOT NULL, away TEXT NOT NULL, kickoff TEXT NOT NULL, finished INTEGER NOT NULL DEFAULT 0, home_score INTEGER, away_score INTEGER, source_key TEXT UNIQUE, source_name TEXT NOT NULL DEFAULT 'OpenLigaDB', exact_kickoff INTEGER NOT NULL DEFAULT 1, available_at INTEGER, source_url TEXT, event_date TEXT, season TEXT, captured_at INTEGER)")
        db.execSQL("INSERT INTO matches(id,league,home,away,kickoff,finished,home_score,away_score,source_key,event_date) VALUES(91,'E0','Alpha','Beta','2026-11-01T15:00:00Z',1,1,0,'legacy:91','2026-11-01')")
        db.version = 18
        db.close()
        try {
            Store(context, databaseName).use { store ->
                val upgraded = store.writableDatabase
                assertEquals(19, upgraded.version)
                assertTrue(tableExists(upgraded, "football_data_stats_attempts"))
                assertTrue(tableExists(upgraded, "football_data_match_stats"))
                upgraded.rawQuery("SELECT home,away,home_score,away_score FROM matches WHERE id=91", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("Alpha", cursor.getString(0))
                    assertEquals("Beta", cursor.getString(1))
                    assertEquals(1, cursor.getInt(2))
                    assertEquals(0, cursor.getInt(3))
                }
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test fun versionNineUpgradeAddsSelectionBasisWithoutDroppingAccumulator() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "fi-v9-migration-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        try {
            val db = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
            db.execSQL(
                "CREATE TABLE virtual_accumulators (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, provider_id TEXT NOT NULL, leg_count INTEGER NOT NULL, " +
                    "captured_at INTEGER NOT NULL, snapshot_key TEXT NOT NULL, strategy_version TEXT NOT NULL, " +
                    "combined_decimal_odds REAL, joint_probability_lower REAL NOT NULL, " +
                    "joint_probability_upper REAL NOT NULL, UNIQUE(provider_id,leg_count,snapshot_key))",
            )
            db.execSQL(
                "INSERT INTO virtual_accumulators(provider_id,leg_count,captured_at,snapshot_key,strategy_version," +
                    "combined_decimal_odds,joint_probability_lower,joint_probability_upper) VALUES(?,?,?,?,?,?,?,?)",
                arrayOf("topaz", 2, 1000L, "prior-snapshot", "accumulator-v1", 3.25, 0.1, 0.4),
            )
            db.version = 9
            db.close()

            Store(context, databaseName).use { store ->
                val upgraded = store.writableDatabase
                assertEquals(19, upgraded.version)
                assertTrue(columnExists(upgraded, "virtual_accumulators", "selection_basis"))
                upgraded.rawQuery(
                    "SELECT provider_id,leg_count,snapshot_key,selection_basis FROM virtual_accumulators",
                    null,
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("topaz", cursor.getString(0))
                    assertEquals(2, cursor.getInt(1))
                    assertEquals("prior-snapshot", cursor.getString(2))
                    assertEquals("MODEL_RANKED", cursor.getString(3))
                    assertFalse(cursor.moveToNext())
                }
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test fun versionTenUpgradeAddsForecastTablesAndPreservesAccumulator() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "fi-v10-migration-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        try {
            val db = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
            db.execSQL(
                "CREATE TABLE virtual_accumulators (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, provider_id TEXT NOT NULL, leg_count INTEGER NOT NULL, " +
                    "captured_at INTEGER NOT NULL, snapshot_key TEXT NOT NULL, strategy_version TEXT NOT NULL, " +
                    "selection_basis TEXT NOT NULL DEFAULT 'MODEL_RANKED', combined_decimal_odds REAL, " +
                    "joint_probability_lower REAL NOT NULL, joint_probability_upper REAL NOT NULL)",
            )
            db.execSQL(
                "INSERT INTO virtual_accumulators(provider_id,leg_count,captured_at,snapshot_key,strategy_version," +
                    "selection_basis,combined_decimal_odds,joint_probability_lower,joint_probability_upper) " +
                    "VALUES(?,?,?,?,?,?,?,?,?)",
                arrayOf("misli", 2, 1200L, "before-v11", "elo-max-v1", "MODEL_RANKED", null, 0.1, 0.4),
            )
            db.version = 10
            db.close()

            Store(context, databaseName).use { store ->
                val upgraded = store.writableDatabase
                assertEquals(19, upgraded.version)
                assertTrue(tableExists(upgraded, "prospective_forecast_snapshots"))
                assertTrue(tableExists(upgraded, "prospective_forecast_settlements"))
                upgraded.rawQuery(
                    "SELECT provider_id,leg_count,snapshot_key,selection_basis FROM virtual_accumulators",
                    null,
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("misli", cursor.getString(0))
                    assertEquals(2, cursor.getInt(1))
                    assertEquals("before-v11", cursor.getString(2))
                    assertEquals("MODEL_RANKED", cursor.getString(3))
                    assertFalse(cursor.moveToNext())
                }
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test fun versionElevenUpgradeAddsMarketReferenceTablesWithoutChangingSavedMatches() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "fi-v11-market-migration-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        try {
            val db=context.openOrCreateDatabase(databaseName,Context.MODE_PRIVATE,null)
            db.execSQL("CREATE TABLE matches (id INTEGER PRIMARY KEY, league TEXT NOT NULL, home TEXT NOT NULL, away TEXT NOT NULL, kickoff TEXT NOT NULL, finished INTEGER NOT NULL DEFAULT 0, home_score INTEGER, away_score INTEGER, source_key TEXT UNIQUE, source_name TEXT NOT NULL DEFAULT 'OpenLigaDB', exact_kickoff INTEGER NOT NULL DEFAULT 1, available_at INTEGER, source_url TEXT, event_date TEXT, season TEXT, captured_at INTEGER)")
            db.execSQL("INSERT INTO matches(id,league,home,away,kickoff,finished,source_key) VALUES(41,'nla','Türkiye','Belgium','2026-11-12T17:00:00Z',0,'openligadb:41')")
            db.version=11
            db.close()

            Store(context,databaseName).use { store ->
                val upgraded=store.writableDatabase
                assertEquals(19, upgraded.version)
                assertTrue(tableExists(upgraded,"market_reference_snapshots"))
                assertTrue(tableExists(upgraded,"market_reference_sync_state"))
                val match=store.matches().single()
                assertEquals(41,match.id)
                assertEquals("Türkiye",match.home)
                assertEquals("Belgium",match.away)
                assertTrue(store.freshMarketReferences().isEmpty())
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test fun versionThirteenUpgradeAddsMarketComparisonFieldsWithoutDroppingCouponLegs() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "fi-v13-market-comparison-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        try {
            val db = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
            db.execSQL(
                "CREATE TABLE virtual_accumulator_legs (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, accumulator_id INTEGER NOT NULL, match_id INTEGER NOT NULL, " +
                    "home_team TEXT NOT NULL, away_team TEXT NOT NULL, scheduled_kickoff INTEGER NOT NULL, " +
                    "outcome TEXT NOT NULL, model_probability REAL NOT NULL, bookmaker_decimal_odds REAL, " +
                    "bookmaker_odds_captured_at INTEGER, UNIQUE(accumulator_id,match_id))",
            )
            db.execSQL(
                "INSERT INTO virtual_accumulator_legs(accumulator_id,match_id,home_team,away_team,scheduled_kickoff," +
                    "outcome,model_probability,bookmaker_decimal_odds,bookmaker_odds_captured_at) " +
                    "VALUES(1,42,'Alpha FC','Beta FC',200000,'H',0.61,1.9,150000)",
            )
            db.version = 13
            db.close()

            Store(context, databaseName).use { store ->
                val upgraded = store.writableDatabase
                assertEquals(19, upgraded.version)
                assertTrue(columnExists(upgraded, "virtual_accumulator_legs", "market_fair_probability"))
                assertTrue(columnExists(upgraded, "virtual_accumulator_legs", "model_minus_market"))
                upgraded.rawQuery(
                    "SELECT match_id,home_team,away_team,outcome,model_probability,bookmaker_decimal_odds," +
                        "bookmaker_odds_captured_at,market_fair_probability,model_minus_market " +
                        "FROM virtual_accumulator_legs WHERE accumulator_id=1",
                    null,
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(42, cursor.getInt(0))
                    assertEquals("Alpha FC", cursor.getString(1))
                    assertEquals("Beta FC", cursor.getString(2))
                    assertEquals("H", cursor.getString(3))
                    assertEquals(.61, cursor.getDouble(4), 1e-12)
                    assertEquals(1.9, cursor.getDouble(5), 1e-12)
                    assertEquals(150000L, cursor.getLong(6))
                    assertTrue(cursor.isNull(7))
                    assertTrue(cursor.isNull(8))
                    assertFalse(cursor.moveToNext())
                }
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test fun versionFourteenUpgradeAddsQualityCountersAndPreservesInternationalSyncState() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName="fi-v14-odds-quality-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        try {
            context.openOrCreateDatabase(databaseName,Context.MODE_PRIVATE,null).use { db ->
                db.execSQL(
                    "CREATE TABLE international_odds_sync_state (provider_id TEXT PRIMARY KEY, status TEXT NOT NULL, " +
                        "last_checked INTEGER NOT NULL, last_success INTEGER, fresh_records_stored INTEGER NOT NULL DEFAULT 0, detail TEXT)",
                )
                db.execSQL(
                    "INSERT INTO international_odds_sync_state(provider_id,status,last_checked,last_success,fresh_records_stored,detail) " +
                        "VALUES('moviodds_bet365','LIVE',1000,900,3,'old state')",
                )
                db.version=14
            }

            Store(context,databaseName).use { store ->
                val db=store.writableDatabase
                assertEquals(19, db.version)
                assertTrue(columnExists(db,"international_odds_sync_state","quality_received"))
                assertTrue(columnExists(db,"international_odds_sync_state","quality_accepted"))
                assertTrue(columnExists(db,"international_odds_sync_state","quality_rejected"))
                assertTrue(columnExists(db,"international_odds_sync_state","quality_stale"))
                assertTrue(columnExists(db,"international_odds_sync_state","quality_duplicates"))
                assertTrue(columnExists(db,"international_odds_sync_state","quality_unverified"))
                assertTrue(tableExists(db,"international_odds_sync_attempts"))
                val prior=store.internationalOddsSyncState()!!
                assertEquals(InternationalOddsStatus.LIVE,prior.status)
                assertEquals(1000L,prior.lastCheckedAtMillis)
                assertEquals(900L,prior.lastSuccessAtMillis)
                assertEquals(3,prior.freshRecordsStored)
                assertEquals("old state",prior.detail)
                assertNull(prior.qualityCounts)

                db.execSQL(
                    "UPDATE international_odds_sync_state SET quality_received=8,quality_accepted=5,quality_rejected=1," +
                        "quality_stale=1,quality_duplicates=1,quality_unverified=0 WHERE provider_id='moviodds_bet365'",
                )
                assertEquals(InternationalOddsQualityCounts(8,5,1,1,1,0),store.internationalOddsSyncState()!!.qualityCounts)
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test fun versionFifteenUpgradeAddsInternationalAttemptHistoryAndPreservesSyncState() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName="fi-v15-odds-attempts-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        val firstCheck=System.currentTimeMillis()
        try {
            context.openOrCreateDatabase(databaseName,Context.MODE_PRIVATE,null).use { db ->
                db.execSQL(
                    "CREATE TABLE international_odds_sync_state (provider_id TEXT PRIMARY KEY, status TEXT NOT NULL, " +
                        "last_checked INTEGER NOT NULL, last_success INTEGER, fresh_records_stored INTEGER NOT NULL DEFAULT 0, detail TEXT, " +
                        "quality_received INTEGER, quality_accepted INTEGER, quality_rejected INTEGER, quality_stale INTEGER, " +
                        "quality_duplicates INTEGER, quality_unverified INTEGER)",
                )
                db.execSQL(
                    "INSERT INTO international_odds_sync_state(provider_id,status,last_checked,last_success,fresh_records_stored,detail, " +
                        "quality_received,quality_accepted,quality_rejected,quality_stale,quality_duplicates,quality_unverified) " +
                        "VALUES('moviodds_bet365','LIVE',?, ?,2,'saved v15 state',2,2,0,0,0,0)",
                    arrayOf(firstCheck,firstCheck-1),
                )
                db.version=15
            }

            Store(context,databaseName).use { store ->
                val db=store.writableDatabase
                assertEquals(19, db.version)
                val preserved=store.internationalOddsSyncState()!!
                assertEquals(InternationalOddsStatus.LIVE,preserved.status)
                assertEquals(firstCheck,preserved.lastCheckedAtMillis)
                assertEquals(2,preserved.freshRecordsStored)
                assertEquals("saved v15 state",preserved.detail)
                assertEquals(InternationalOddsQualityCounts(2,2,0,0,0,0),preserved.qualityCounts)

                val parsedCounts=InternationalOddsQualityCounts(2,0,1,1,0,0)
                db.execSQL(
                    "INSERT INTO international_odds_sync_attempts(provider_id,checked_at,status, " +
                        "quality_received,quality_accepted,quality_rejected,quality_stale,quality_duplicates,quality_unverified,detail) " +
                        "VALUES('moviodds_bet365',?,'STALE',2,0,1,1,0,0,'stale response')",
                    arrayOf(firstCheck+1),
                )
                db.execSQL(
                    "INSERT INTO international_odds_sync_attempts(provider_id,checked_at,status,detail) " +
                        "VALUES('moviodds_bet365',?,'ERROR','request failed')",
                    arrayOf(firstCheck+2),
                )
                val attempts=store.internationalOddsSyncHistory(10)
                assertEquals(2,attempts.size)
                assertEquals(InternationalOddsStatus.ERROR,attempts[0].status)
                assertNull(attempts[0].qualityCounts)
                assertEquals(InternationalOddsStatus.STALE,attempts[1].status)
                assertEquals(parsedCounts,attempts[1].qualityCounts)
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test fun marketDisagreementCouponPersistsAndReloadsItsPriceComparison() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "fi-market-comparison-roundtrip-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        val capturedAt = System.currentTimeMillis()
        val kickoff = capturedAt + TimeUnit.HOURS.toMillis(3)
        val candidate = VirtualCouponCandidate(
            providerId = "misli",
            providerName = "Misli",
            picks = listOf(
                VirtualCouponPick(910101,"Alpha FC","Beta FC",kickoff,"H",.65,2.1,capturedAt,.50,.15),
                VirtualCouponPick(910102,"Gamma FC","Delta FC",kickoff+60_000,"D",.42,3.0,capturedAt,.35,.07),
            ),
            combinedDecimalOdds = 6.3,
            requestedLegCount = 2,
            jointProbabilityBounds = JointProbabilityBounds(0.07,0.42),
            selectionBasis = CouponSelectionBasis.MODEL_MARKET_DISAGREEMENT,
        )
        try {
            Store(context,databaseName).use { store ->
                assertEquals(1,store.recordAccumulatorDrafts(listOf(candidate),capturedAt))
                assertEquals(0,store.recordAccumulatorDrafts(listOf(candidate),capturedAt))
                val saved=store.virtualAccumulatorHistory().single()
                assertEquals(CouponSelectionBasis.MODEL_MARKET_DISAGREEMENT,saved.selectionBasis)
                assertEquals(.50,saved.picks.first().marketFairProbability!!,1e-12)
                assertEquals(.15,saved.picks.first().modelMinusMarket!!,1e-12)
                assertEquals(2.1,saved.picks.first().bookmakerDecimalOdds!!,1e-12)
                store.writableDatabase.rawQuery("SELECT strategy_version FROM virtual_accumulators",null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(CouponSelectionBasis.MODEL_MARKET_DISAGREEMENT.strategyVersion,cursor.getString(0))
                }
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test fun accumulatorEvaluationLoadsEverySavedCouponWhileVisibleHistoryStaysBounded() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "fi-accumulator-evaluation-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        val capturedAt = 1_700_000_000_000L
        val firstKickoff = capturedAt + TimeUnit.DAYS.toMillis(2)
        val secondKickoff = firstKickoff + TimeUnit.MINUTES.toMillis(90)
        try {
            Store(context, databaseName).use { store ->
                store.upsert(
                    listOf(
                        Match(930201, "test", "Alpha FC", "Beta FC", Instant.ofEpochMilli(firstKickoff).toString(), true, 2, 1),
                        Match(930202, "test", "Gamma FC", "Delta FC", Instant.ofEpochMilli(secondKickoff).toString(), true, 1, 0),
                    ),
                )
                val drafts = (0 until 120).map { index ->
                    val firstProbability = .51 + index * .001
                    VirtualCouponCandidate(
                        providerId = "misli",
                        providerName = "Misli",
                        picks = listOf(
                            VirtualCouponPick(930201, "Alpha FC", "Beta FC", firstKickoff, "H", firstProbability),
                            VirtualCouponPick(930202, "Gamma FC", "Delta FC", secondKickoff, "H", .60),
                        ),
                        requestedLegCount = 2,
                        jointProbabilityBounds = JointProbabilityBounds(.0, .60),
                    )
                }

                assertEquals(120, store.recordAccumulatorDrafts(drafts, capturedAt))
                assertEquals(100, store.virtualAccumulatorHistory().size)
                val fullHistory = store.virtualAccumulatorHistory(limit = 0)
                assertEquals(120, fullHistory.size)

                val report = AccumulatorPerformanceEngine.evaluate(
                    entries = fullHistory,
                    providerId = "misli",
                    legCount = 2,
                    selectionBasis = CouponSelectionBasis.MODEL_RANKED,
                    settledSingles = emptyList(),
                )
                assertEquals(120, report.settledCoupons)
                assertEquals(120, report.wonCoupons)
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test fun prospectiveForecastIsImmutablePerUtcDayAndSettlementTracksAvailableResultCorrections() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "fi-forecast-ledger-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        val forecastedAt = 1_700_000_000_000L
        val kickoff = forecastedAt + 24 * 60 * 60 * 1000L
        val resultAvailableAt = kickoff + 6 * 60 * 60 * 1000L
        val sourceAvailabilityBeforeForecast = forecastedAt - 100L
        try {
            Store(context, databaseName).use { store ->
                store.upsert(
                    listOf(
                        Match(
                            id = 81,
                            league = "test-league",
                            home = "Home FC",
                            away = "Away FC",
                            kickoff = Instant.ofEpochMilli(kickoff).toString(),
                            finished = false,
                            homeScore = null,
                            awayScore = null,
                        ),
                    ),
                )
                val first = ForecastedMatch(
                    matchId = 81,
                    homeTeam = "Home FC",
                    awayTeam = "Away FC",
                    kickoffMillis = kickoff,
                    probabilities = OutcomeProbabilities(.6, .3, .1),
                    forecastedAtMillis = forecastedAt,
                    historyMatchCount = 12,
                    competition = "test-league",
                )
                assertEquals(1, store.recordProspectiveForecastSnapshots(listOf(first), forecastedAt))
                assertEquals(
                    0,
                    store.recordProspectiveForecastSnapshots(
                        listOf(first.copy(probabilities = OutcomeProbabilities(.1, .2, .7), forecastedAtMillis = forecastedAt + 1_000)),
                        forecastedAt + 1_000,
                    ),
                )
                assertEquals(.6, store.prospectiveForecastHistory().single().probabilities.home, 1e-12)

                store.upsert(
                    listOf(
                        Match(
                            id = 81,
                            league = "test-league",
                            home = "Home FC",
                            away = "Away FC",
                            kickoff = Instant.ofEpochMilli(kickoff).toString(),
                            finished = true,
                            homeScore = 2,
                            awayScore = 1,
                            sourceName = "OpenLigaDB",
                            availableAtMillis = sourceAvailabilityBeforeForecast,
                            sourceUrl = "https://api.openligadb.de/match/81",
                            capturedAtMillis = resultAvailableAt,
                        ),
                    ),
                )
                assertEquals(0, store.settleProspectiveForecastSnapshots(kickoff + 1))
                assertEquals(1, store.settleProspectiveForecastSnapshots(resultAvailableAt))
                assertEquals(0, store.settleProspectiveForecastSnapshots(resultAvailableAt + 1))

                val settled = store.prospectiveForecastHistory().single()
                assertEquals("H", settled.actualOutcome)
                assertEquals(2, settled.homeGoals)
                assertEquals(1, settled.awayGoals)
                assertEquals("OpenLigaDB", settled.resultSourceName)
                assertEquals("https://api.openligadb.de/match/81", settled.resultSourceUrl)
                assertEquals(forecastedAt, settled.forecastedAtMillis)
                assertEquals(resultAvailableAt, settled.resultAvailableAtMillis)
                assertEquals(.6, settled.probabilities.home, 1e-12)
                val report = ProspectiveForecastEvaluator.evaluate(listOf(settled))
                assertEquals(1, report.settledCount)
                assertEquals(.26, report.meanBrierScore!!, 1e-12)

                val correctedAvailableAt = resultAvailableAt + 1_000
                store.upsert(
                    listOf(
                        Match(
                            id = 81,
                            league = "test-league",
                            home = "Home FC",
                            away = "Away FC",
                            kickoff = Instant.ofEpochMilli(kickoff).toString(),
                            finished = true,
                            homeScore = 1,
                            awayScore = 1,
                            sourceName = "OpenLigaDB",
                            availableAtMillis = correctedAvailableAt,
                            sourceUrl = "https://api.openligadb.de/match/81",
                            capturedAtMillis = correctedAvailableAt,
                        ),
                    ),
                )
                store.writableDatabase.rawQuery(
                    "SELECT home_score,away_score,available_at,source_url FROM matches WHERE id=81",
                    null,
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                    assertEquals(1, cursor.getInt(1))
                    assertEquals(correctedAvailableAt, cursor.getLong(2))
                    assertEquals("https://api.openligadb.de/match/81", cursor.getString(3))
                }
                assertEquals(1, store.settleProspectiveForecastSnapshots(correctedAvailableAt))
                val corrected = store.prospectiveForecastHistory().single()
                assertEquals("D", corrected.actualOutcome)
                assertEquals(1, corrected.homeGoals)
                assertEquals(1, corrected.awayGoals)
                assertEquals(forecastedAt, corrected.forecastedAtMillis)
                assertEquals(.6, corrected.probabilities.home, 1e-12)
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test fun crossProviderDuplicateKeepsSettledScoreAndMergesProvenance() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "fi-cross-provider-final-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        val date = LocalDate.of(2025, 3, 1)
        val kickoff = date.atTime(17, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val resultCaptured = kickoff + 3 * 60 * 60 * 1_000L
        try {
            Store(context, databaseName).use { store ->
                store.upsertProviderMatches(
                    listOf(
                        providerMatch(
                            providerId = "openligadb",
                            providerMatchId = "99221",
                            date = date,
                            kickoffMillis = kickoff,
                            precision = KickoffPrecision.INSTANT,
                            homeGoals = 2,
                            awayGoals = 1,
                            availableAtMillis = resultCaptured,
                            capturedAtMillis = resultCaptured,
                            sourceUrl = "https://api.openligadb.de/match/99221",
                        ),
                    ),
                )
                store.upsertProviderMatches(
                    listOf(
                        providerMatch(
                            providerId = "openfootball",
                            providerMatchId = "2024-25:bl1:2025-03-01:Northbridge:Riverside",
                            date = date,
                            kickoffMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                            precision = KickoffPrecision.DATE_ONLY,
                            homeGoals = null,
                            awayGoals = null,
                            availableAtMillis = null,
                            capturedAtMillis = resultCaptured + 1_000,
                            sourceUrl = "https://github.com/openfootball/football.json/blob/master/2024-25/bl1.json",
                        ),
                    ),
                )
                store.upsertProviderMatches(
                    listOf(
                        providerMatch(
                            providerId = "openfootball",
                            providerMatchId = "conflicting-copy",
                            date = date,
                            kickoffMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                            precision = KickoffPrecision.DATE_ONLY,
                            homeGoals = 0,
                            awayGoals = 4,
                            availableAtMillis = resultCaptured + 2_000,
                            capturedAtMillis = resultCaptured + 2_000,
                            sourceUrl = "https://example.invalid/alternate-feed",
                        ),
                    ),
                )

                store.writableDatabase.rawQuery(
                    "SELECT id,source_key,finished,home_score,away_score,source_name,source_url,exact_kickoff,available_at " +
                        "FROM matches WHERE event_date=?",
                    arrayOf(date.toString()),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("openligadb:99221", cursor.getString(1))
                    assertEquals(1, cursor.getInt(2))
                    assertEquals(2, cursor.getInt(3))
                    assertEquals(1, cursor.getInt(4))
                    assertTrue(cursor.getString(5).contains("OpenLigaDB"))
                    assertTrue(cursor.getString(5).contains("OpenFootball (CC0)"))
                    val urls = cursor.getString(6).lines()
                    assertTrue(urls.contains("https://api.openligadb.de/match/99221"))
                    assertTrue(urls.contains("https://github.com/openfootball/football.json/blob/master/2024-25/bl1.json"))
                    assertTrue(urls.contains("https://example.invalid/alternate-feed"))
                    assertEquals(1, cursor.getInt(7))
                    assertEquals(resultCaptured, cursor.getLong(8))
                    assertFalse(cursor.moveToNext())
                }
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test fun crossProviderCompletedMatchFillsDateOnlyFixtureWithoutAddingDuplicate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "fi-cross-provider-fill-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        val date = LocalDate.of(2025, 3, 8)
        val kickoff = date.atTime(17, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val capture = kickoff + 2 * 60 * 60 * 1_000L
        try {
            Store(context, databaseName).use { store ->
                store.upsertProviderMatches(
                    listOf(
                        providerMatch(
                            providerId = "openfootball",
                            providerMatchId = "fixture-date-only",
                            date = date,
                            kickoffMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                            precision = KickoffPrecision.DATE_ONLY,
                            homeGoals = null,
                            awayGoals = null,
                            availableAtMillis = null,
                            capturedAtMillis = kickoff - 24 * 60 * 60 * 1_000L,
                            sourceUrl = "https://github.com/openfootball/football.json/blob/master/2024-25/bl1.json",
                        ),
                    ),
                )
                store.upsertProviderMatches(
                    listOf(
                        providerMatch(
                            providerId = "openligadb",
                            providerMatchId = "99308",
                            date = date,
                            kickoffMillis = kickoff,
                            precision = KickoffPrecision.INSTANT,
                            homeGoals = 3,
                            awayGoals = 0,
                            availableAtMillis = capture,
                            capturedAtMillis = capture,
                            sourceUrl = "https://api.openligadb.de/match/99308",
                        ),
                    ),
                )

                store.writableDatabase.rawQuery(
                    "SELECT source_key,finished,home_score,away_score,kickoff,exact_kickoff,source_name,source_url " +
                        "FROM matches WHERE event_date=?",
                    arrayOf(date.toString()),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("openfootball:fixture-date-only", cursor.getString(0))
                    assertEquals(1, cursor.getInt(1))
                    assertEquals(3, cursor.getInt(2))
                    assertEquals(0, cursor.getInt(3))
                    assertEquals(Instant.ofEpochMilli(kickoff).toString(), cursor.getString(4))
                    assertEquals(1, cursor.getInt(5))
                    assertTrue(cursor.getString(6).contains("OpenFootball (CC0)"))
                    assertTrue(cursor.getString(6).contains("OpenLigaDB"))
                    assertTrue(cursor.getString(7).contains("99308"))
                    assertFalse(cursor.moveToNext())
                }
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun providerMatch(
        providerId: String,
        providerMatchId: String,
        date: LocalDate,
        kickoffMillis: Long,
        precision: KickoffPrecision,
        homeGoals: Int?,
        awayGoals: Int?,
        availableAtMillis: Long?,
        capturedAtMillis: Long,
        sourceUrl: String,
    ) = ProviderMatch(
        providerId = providerId,
        providerMatchId = providerMatchId,
        competitionCode = "bl1",
        competitionName = "Bundesliga",
        season = "2024-25",
        eventDate = date,
        kickoffMillis = kickoffMillis,
        kickoffPrecision = precision,
        homeTeam = "Northbridge FC",
        awayTeam = "Riverside United",
        homeGoals = homeGoals,
        awayGoals = awayGoals,
        safeAvailableAtMillis = availableAtMillis,
        sourceUrl = sourceUrl,
        capturedAtMillis = capturedAtMillis,
    )

    private fun createVersionOneDatabase(context: Context, name: String) {
        val db = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        db.execSQL(
            "CREATE TABLE matches (id INTEGER PRIMARY KEY, league TEXT NOT NULL, home TEXT NOT NULL, " +
                "away TEXT NOT NULL, kickoff TEXT NOT NULL, finished INTEGER NOT NULL DEFAULT 0, " +
                "home_score INTEGER, away_score INTEGER)",
        )
        db.execSQL(
            "CREATE TABLE quotes (id INTEGER PRIMARY KEY AUTOINCREMENT, match_id INTEGER NOT NULL, " +
                "bookmaker TEXT NOT NULL, h REAL NOT NULL, d REAL NOT NULL, a REAL NOT NULL, captured_at INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE picks (id INTEGER PRIMARY KEY AUTOINCREMENT, match_id INTEGER NOT NULL, " +
                "source TEXT NOT NULL, outcome TEXT NOT NULL, created_at INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE tickets (id INTEGER PRIMARY KEY AUTOINCREMENT, match_id INTEGER NOT NULL, " +
                "outcome TEXT NOT NULL, price REAL NOT NULL, stake REAL NOT NULL, bookmaker TEXT NOT NULL, created_at INTEGER NOT NULL)",
        )
        db.execSQL(
            "INSERT INTO matches(id,league,home,away,kickoff,finished,home_score,away_score) VALUES(41,'bl1','Alpha','Beta','2025-05-01T17:00:00Z',1,2,1)",
        )
        db.execSQL("INSERT INTO quotes(match_id,bookmaker,h,d,a,captured_at) VALUES(41,'LegacyBook',2.1,3.2,3.5,500)")
        db.execSQL("INSERT INTO picks(match_id,source,outcome,created_at) VALUES(41,'legacy-source','H',600)")
        db.execSQL("INSERT INTO tickets(match_id,outcome,price,stake,bookmaker,created_at) VALUES(41,'H',2.1,5,'LegacyBook',700)")
        db.version = 1
        db.close()
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
        arrayOf(table),
    ).use { it.moveToFirst() }

    private fun columnExists(db: SQLiteDatabase, table: String, column: String): Boolean = db.rawQuery(
        "PRAGMA table_info($table)",
        null,
    ).use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow("name")
        var found = false
        while (cursor.moveToNext()) if (cursor.getString(nameIndex) == column) found = true
        found
    }
}
