package az.simplesoft.footballintelligence

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import az.simplesoft.footballintelligence.analytics.ForecastedMatch
import az.simplesoft.footballintelligence.analytics.OutcomeProbabilities
import az.simplesoft.footballintelligence.analytics.ProspectiveForecastEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StoreMigrationInstrumentedTest {
    @Test fun versionOneUpgradeKeepsMatchesQuotesPicksTicketsAndCreatesCurrentTables() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "fi-v1-migration-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        try {
            createVersionOneDatabase(context, databaseName)

            Store(context, databaseName).use { store ->
                val db = store.writableDatabase
                assertEquals(11, db.version)
                assertTrue(tableExists(db, "historical_results"))
                assertTrue(tableExists(db, "provider_sync_state"))
                assertTrue(tableExists(db, "bookmaker_odds_snapshots"))
                assertTrue(tableExists(db, "virtual_accumulators"))
                assertTrue(tableExists(db, "prospective_forecast_snapshots"))
                assertTrue(tableExists(db, "prospective_forecast_settlements"))
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
                assertEquals(11, upgraded.version)
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
                assertEquals(11, upgraded.version)
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
