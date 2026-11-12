package az.simplesoft.footballintelligence

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.Cursor
import android.content.Context
import android.content.ContentValues
import java.io.Closeable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import az.simplesoft.footballintelligence.analytics.EloWalkForwardEngine
import az.simplesoft.footballintelligence.analytics.EloWalkForwardReport
import az.simplesoft.footballintelligence.analytics.EloExperimentDataset
import az.simplesoft.footballintelligence.analytics.EloParameterExperimentEngine
import az.simplesoft.footballintelligence.analytics.GoalModelComparisonEngine
import az.simplesoft.footballintelligence.analytics.AutomaticBacktestReports
import az.simplesoft.footballintelligence.analytics.HistoricalMatch
import az.simplesoft.footballintelligence.analytics.ProspectiveForecastObservation
import az.simplesoft.footballintelligence.analytics.isProspectiveForecastEligible
import az.simplesoft.footballintelligence.analytics.conservativeResultAvailability
import az.simplesoft.footballintelligence.analytics.ForecastedMatch
import az.simplesoft.footballintelligence.analytics.VirtualCouponCandidate
import az.simplesoft.footballintelligence.analytics.VirtualCouponBuilder
import az.simplesoft.footballintelligence.analytics.LoggedCouponPick
import az.simplesoft.footballintelligence.analytics.WalkForwardTarget
import az.simplesoft.footballintelligence.analytics.AccumulatorEngine
import az.simplesoft.footballintelligence.analytics.AccumulatorMatchResult
import az.simplesoft.footballintelligence.analytics.VirtualAccumulatorHistoryEntry
import az.simplesoft.footballintelligence.analytics.CouponSelectionBasis
import az.simplesoft.footballintelligence.analytics.TeamDataQuality
import az.simplesoft.footballintelligence.data.FootballDataCsvImporter
import az.simplesoft.footballintelligence.data.FootballCsvImportReport
import az.simplesoft.footballintelligence.data.StoredFootballCsvImport
import az.simplesoft.footballintelligence.data.ImportedHistoryRow
import az.simplesoft.footballintelligence.data.KickoffPrecision
import az.simplesoft.footballintelligence.data.ProviderMatch
import az.simplesoft.footballintelligence.data.ProviderSyncSummary
import az.simplesoft.footballintelligence.data.ProviderFileCache
import az.simplesoft.footballintelligence.data.OpenLigaLeagueState
import az.simplesoft.footballintelligence.data.SeasonDateValidity
import az.simplesoft.footballintelligence.data.FixtureDeduplication
import az.simplesoft.footballintelligence.data.BookmakerOddsSnapshot
import az.simplesoft.footballintelligence.data.OddsProviderStatus
import az.simplesoft.footballintelligence.data.latestBookmakerSnapshotsPerEvent
import az.simplesoft.footballintelligence.data.FixtureIdentity
import az.simplesoft.footballintelligence.data.uniqueExactFixtureMatchId
import az.simplesoft.footballintelligence.data.InternationalOddsSnapshot
import az.simplesoft.footballintelligence.data.InternationalOddsQuoteSide
import az.simplesoft.footballintelligence.data.InternationalMatchLinkBasis
import az.simplesoft.footballintelligence.data.InternationalOddsStatus
import az.simplesoft.footballintelligence.data.InternationalOddsQualityCounts
import az.simplesoft.footballintelligence.data.InternationalOddsSyncAttempt
import az.simplesoft.footballintelligence.data.InternationalOddsSyncState
import az.simplesoft.footballintelligence.data.INTERNATIONAL_ODDS_FRESHNESS_MILLIS
import az.simplesoft.footballintelligence.data.MOVIODDS_PROVIDER_ID
import az.simplesoft.footballintelligence.data.uniqueInternationalFixtureLink
import az.simplesoft.footballintelligence.data.MarketReferenceSnapshot
import az.simplesoft.footballintelligence.data.MarketReferenceStatus
import az.simplesoft.footballintelligence.data.MarketReferenceSyncState
import az.simplesoft.footballintelligence.data.MARKET_REFERENCE_FRESHNESS_MILLIS
import az.simplesoft.footballintelligence.data.TIPSAUDIT_PROVIDER_ID
import az.simplesoft.footballintelligence.data.uniqueExactMarketFixtureMatchId

/** Serializes SQLite writers shared by the foreground screen and WorkManager in this process. */
internal object StoreWriteCoordinator {
    private val lock = java.util.concurrent.locks.ReentrantLock(true)

    internal fun isQueued(thread: Thread): Boolean = lock.hasQueuedThread(thread)

    fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    fun beginTransaction(db: SQLiteDatabase) {
        lock.lock()
        try {
            db.beginTransaction()
        } catch (error: Throwable) {
            lock.unlock()
            throw error
        }
    }

    fun endTransaction(db: SQLiteDatabase) {
        try {
            db.endTransaction()
        } finally {
            lock.unlock()
        }
    }
}

private data class InternationalOddsRecordKey(
    val providerId: String,
    val bookmakerId: String,
    val eventId: String,
    val market: String,
    val sourceMarketKey: String,
    val quoteSide: InternationalOddsQuoteSide,
) {
    companion object {
        fun from(snapshot: InternationalOddsSnapshot) = InternationalOddsRecordKey(
            snapshot.providerId, snapshot.bookmakerId, snapshot.eventId, snapshot.market,
            snapshot.sourceMarketKey, snapshot.quoteSide,
        )
    }
}

class Store(context: Context, databaseName:String="football_intelligence.db") : SQLiteOpenHelper(context, databaseName, null, 18), Closeable {
    init {
        // Permit read connections while WorkManager writes a large provider batch. Without WAL,
        // the single SQLite connection can starve foreground WebView capture and UI reads.
        setWriteAheadLoggingEnabled(true)
    }

    private fun beginWriteTransaction(db: SQLiteDatabase) = StoreWriteCoordinator.beginTransaction(db)
    private fun endWriteTransaction(db: SQLiteDatabase) = StoreWriteCoordinator.endTransaction(db)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE matches (id INTEGER PRIMARY KEY, league TEXT NOT NULL, home TEXT NOT NULL, away TEXT NOT NULL, kickoff TEXT NOT NULL, finished INTEGER NOT NULL DEFAULT 0, home_score INTEGER, away_score INTEGER, source_key TEXT UNIQUE, source_name TEXT NOT NULL DEFAULT 'OpenLigaDB', exact_kickoff INTEGER NOT NULL DEFAULT 1, available_at INTEGER, source_url TEXT, event_date TEXT, season TEXT, captured_at INTEGER)")
        db.execSQL("CREATE INDEX matches_event_date_idx ON matches(event_date,home,away)")
        db.execSQL("CREATE TABLE quotes (id INTEGER PRIMARY KEY AUTOINCREMENT, match_id INTEGER NOT NULL, bookmaker TEXT NOT NULL, h REAL NOT NULL, d REAL NOT NULL, a REAL NOT NULL, captured_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE picks (id INTEGER PRIMARY KEY AUTOINCREMENT, match_id INTEGER NOT NULL, source TEXT NOT NULL, outcome TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE tickets (id INTEGER PRIMARY KEY AUTOINCREMENT, match_id INTEGER NOT NULL, outcome TEXT NOT NULL, price REAL NOT NULL, stake REAL NOT NULL, bookmaker TEXT NOT NULL, created_at INTEGER NOT NULL)")
        createHistoricalImportTables(db)
        createProviderTables(db)
        createCouponTables(db)
        createBookmakerTables(db)
        createAccumulatorTables(db)
        createProspectiveForecastTables(db)
        createMarketReferenceTables(db)
        createInternationalOddsTables(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if(oldVersion<2) createHistoricalImportTables(db)
        if(oldVersion<3) {
            db.execSQL("ALTER TABLE matches ADD COLUMN source_key TEXT")
            db.execSQL("ALTER TABLE matches ADD COLUMN source_name TEXT NOT NULL DEFAULT 'OpenLigaDB'")
            db.execSQL("ALTER TABLE matches ADD COLUMN exact_kickoff INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE matches ADD COLUMN available_at INTEGER")
            db.execSQL("ALTER TABLE matches ADD COLUMN source_url TEXT")
            db.execSQL("ALTER TABLE matches ADD COLUMN event_date TEXT")
            db.execSQL("ALTER TABLE matches ADD COLUMN season TEXT")
            db.execSQL("UPDATE matches SET source_key='openligadb:' || id WHERE source_key IS NULL")
            db.execSQL("UPDATE matches SET available_at=? WHERE finished=1 AND available_at IS NULL", arrayOf(System.currentTimeMillis()))
            db.execSQL("CREATE UNIQUE INDEX matches_source_key_idx ON matches(source_key)")
            createProviderTables(db)
        }
        if(oldVersion<4) createCouponTables(db)
        if(oldVersion<5) {
            val hasGroupColumn=db.rawQuery("PRAGMA table_info(provider_league_state)",null).use { c ->
                var found=false
                while(c.moveToNext()) if(c.getString(c.getColumnIndexOrThrow("name"))=="current_group_order") found=true
                found
            }
            if(!hasGroupColumn) db.execSQL("ALTER TABLE provider_league_state ADD COLUMN current_group_order INTEGER")
        }
        if(oldVersion<6) {
            db.execSQL("ALTER TABLE matches ADD COLUMN captured_at INTEGER")
            db.execSQL("CREATE INDEX IF NOT EXISTS matches_event_date_idx ON matches(event_date,home,away)")
        }
        if(oldVersion<7) createBookmakerTables(db)
        if(oldVersion==7) db.execSQL("ALTER TABLE bookmaker_odds_snapshots ADD COLUMN event_year_inferred INTEGER NOT NULL DEFAULT 0")
        if(oldVersion<9) createAccumulatorTables(db)
        else if(oldVersion<10) db.execSQL("ALTER TABLE virtual_accumulators ADD COLUMN selection_basis TEXT NOT NULL DEFAULT 'MODEL_RANKED'")
        if(oldVersion<11) createProspectiveForecastTables(db)
        if(oldVersion<12) createMarketReferenceTables(db)
        if(oldVersion<13) createInternationalOddsTables(db)
        if(oldVersion<14) ensureAccumulatorComparisonColumns(db)
        if(oldVersion<15) ensureInternationalOddsQualityColumns(db)
        if(oldVersion<16) ensureInternationalOddsSyncAttemptsTable(db)
        if(oldVersion<17) ensureInternationalOddsMatchLinkBasisColumns(db)
        if(oldVersion<18) ensureInternationalOddsQuoteSideSchema(db)
    }
    private fun createHistoricalImportTables(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE historical_imports (id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT NOT NULL, file_name TEXT NOT NULL, imported_at INTEGER NOT NULL, rows_read INTEGER NOT NULL, accepted_rows INTEGER NOT NULL, inserted_rows INTEGER NOT NULL, duplicate_rows INTEGER NOT NULL, rejected_rows INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE historical_results (id INTEGER PRIMARY KEY AUTOINCREMENT, import_id INTEGER NOT NULL, competition_code TEXT NOT NULL, competition_key TEXT NOT NULL, event_date TEXT NOT NULL, available_at INTEGER NOT NULL, home_team TEXT NOT NULL, away_team TEXT NOT NULL, home_key TEXT NOT NULL, away_key TEXT NOT NULL, home_goals INTEGER NOT NULL, away_goals INTEGER NOT NULL, UNIQUE(competition_key,event_date,home_key,away_key))")
        db.execSQL("CREATE INDEX historical_results_asof ON historical_results(competition_key,available_at)")
    }
    private fun createProviderTables(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS provider_sync_state (provider_id TEXT PRIMARY KEY, status TEXT NOT NULL, last_checked INTEGER NOT NULL, last_success INTEGER, records_stored INTEGER NOT NULL DEFAULT 0, error TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS provider_file_cache (cache_key TEXT PRIMARY KEY, provider_id TEXT NOT NULL, etag TEXT, last_modified TEXT, last_checked INTEGER NOT NULL, record_count INTEGER NOT NULL DEFAULT 0, cached_body TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS provider_league_state (league_key TEXT PRIMARY KEY, source_change TEXT, last_full_sync INTEGER NOT NULL DEFAULT 0, checked_at INTEGER NOT NULL DEFAULT 0, record_count INTEGER NOT NULL DEFAULT 0, current_group_order INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS provider_sync_lock (id INTEGER PRIMARY KEY CHECK(id=1), expires_at INTEGER NOT NULL)")
    }
    private fun createCouponTables(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS model_coupon_picks (id INTEGER PRIMARY KEY AUTOINCREMENT, provider_id TEXT NOT NULL, match_id INTEGER NOT NULL, outcome TEXT NOT NULL, model_probability REAL NOT NULL, captured_at INTEGER NOT NULL, UNIQUE(provider_id,match_id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS model_coupon_picks_match_idx ON model_coupon_picks(match_id,captured_at)")
    }
    private fun createBookmakerTables(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS bookmaker_sync_state (provider_id TEXT PRIMARY KEY, status TEXT NOT NULL, last_checked INTEGER, last_success INTEGER, records_stored INTEGER NOT NULL DEFAULT 0, error TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS bookmaker_odds_snapshots (id INTEGER PRIMARY KEY AUTOINCREMENT, provider_id TEXT NOT NULL, event_id TEXT NOT NULL, match_id INTEGER, source_url TEXT NOT NULL, captured_at INTEGER NOT NULL, kickoff INTEGER NOT NULL, market TEXT NOT NULL, home_team TEXT NOT NULL, away_team TEXT NOT NULL, home_key TEXT NOT NULL, away_key TEXT NOT NULL, home_odds REAL NOT NULL, draw_odds REAL NOT NULL, away_odds REAL NOT NULL, event_date TEXT NOT NULL, time_zone_id TEXT NOT NULL, event_year_inferred INTEGER NOT NULL DEFAULT 0, UNIQUE(provider_id,event_id,captured_at))")
        db.execSQL("CREATE INDEX IF NOT EXISTS bookmaker_odds_fresh_idx ON bookmaker_odds_snapshots(provider_id,captured_at,kickoff)")
        db.execSQL("CREATE INDEX IF NOT EXISTS bookmaker_odds_match_idx ON bookmaker_odds_snapshots(match_id,provider_id,captured_at)")
    }
    private fun createAccumulatorTables(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS virtual_accumulators (id INTEGER PRIMARY KEY AUTOINCREMENT, provider_id TEXT NOT NULL, leg_count INTEGER NOT NULL, captured_at INTEGER NOT NULL, snapshot_key TEXT NOT NULL, strategy_version TEXT NOT NULL, selection_basis TEXT NOT NULL DEFAULT 'MODEL_RANKED', combined_decimal_odds REAL, joint_probability_lower REAL NOT NULL, joint_probability_upper REAL NOT NULL, UNIQUE(provider_id,leg_count,snapshot_key))")
        db.execSQL("CREATE INDEX IF NOT EXISTS virtual_accumulators_history_idx ON virtual_accumulators(provider_id,leg_count,captured_at)")
        db.execSQL("CREATE TABLE IF NOT EXISTS virtual_accumulator_legs (id INTEGER PRIMARY KEY AUTOINCREMENT, accumulator_id INTEGER NOT NULL, match_id INTEGER NOT NULL, home_team TEXT NOT NULL, away_team TEXT NOT NULL, scheduled_kickoff INTEGER NOT NULL, outcome TEXT NOT NULL, model_probability REAL NOT NULL, bookmaker_decimal_odds REAL, bookmaker_odds_captured_at INTEGER, market_fair_probability REAL, model_minus_market REAL, UNIQUE(accumulator_id,match_id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS virtual_accumulator_legs_match_idx ON virtual_accumulator_legs(match_id,accumulator_id)")
    }
    private fun ensureAccumulatorComparisonColumns(db: SQLiteDatabase) {
        val legsTableExists = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='virtual_accumulator_legs'",
            null,
        ).use { it.moveToFirst() }
        if (!legsTableExists) createAccumulatorTables(db)
        val columns = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info(virtual_accumulator_legs)", null).use { cursor ->
            while (cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
        }
        if ("market_fair_probability" !in columns) {
            db.execSQL("ALTER TABLE virtual_accumulator_legs ADD COLUMN market_fair_probability REAL")
        }
        if ("model_minus_market" !in columns) {
            db.execSQL("ALTER TABLE virtual_accumulator_legs ADD COLUMN model_minus_market REAL")
        }
    }
    private fun createProspectiveForecastTables(db:SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS prospective_forecast_snapshots (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, model_version TEXT NOT NULL, match_id INTEGER NOT NULL, " +
                "forecast_day_utc TEXT NOT NULL, forecasted_at INTEGER NOT NULL, kickoff_at INTEGER NOT NULL, " +
                "competition TEXT NOT NULL, home_team TEXT NOT NULL, away_team TEXT NOT NULL, history_match_count INTEGER NOT NULL, " +
                "home_probability REAL NOT NULL, draw_probability REAL NOT NULL, away_probability REAL NOT NULL, " +
                "UNIQUE(model_version,match_id,forecast_day_utc))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS prospective_forecast_time_idx ON prospective_forecast_snapshots(forecasted_at,kickoff_at)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS prospective_forecast_settlements (" +
                "snapshot_id INTEGER PRIMARY KEY, settled_at INTEGER NOT NULL, result_available_at INTEGER NOT NULL, " +
                "actual_outcome TEXT NOT NULL, home_goals INTEGER NOT NULL, away_goals INTEGER NOT NULL, " +
                "result_source_name TEXT NOT NULL, result_source_url TEXT)",
        )
    }
    private fun createMarketReferenceTables(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS market_reference_sync_state (provider_id TEXT PRIMARY KEY, status TEXT NOT NULL, last_checked INTEGER NOT NULL, last_success INTEGER, fresh_records_stored INTEGER NOT NULL DEFAULT 0, methodology_version TEXT, detail TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS market_reference_snapshots (id INTEGER PRIMARY KEY AUTOINCREMENT, provider_id TEXT NOT NULL, external_event_id TEXT NOT NULL, match_id INTEGER, source_url TEXT NOT NULL, captured_at INTEGER NOT NULL, fair_read_at INTEGER NOT NULL, kickoff INTEGER NOT NULL, market TEXT NOT NULL, home_team TEXT NOT NULL, away_team TEXT NOT NULL, home_key TEXT NOT NULL, away_key TEXT NOT NULL, home_fair_odds REAL NOT NULL, draw_fair_odds REAL NOT NULL, away_fair_odds REAL NOT NULL, home_probability REAL NOT NULL, draw_probability REAL NOT NULL, away_probability REAL NOT NULL, fair_source TEXT NOT NULL, methodology_version TEXT NOT NULL, UNIQUE(provider_id,external_event_id,captured_at))")
        db.execSQL("CREATE INDEX IF NOT EXISTS market_reference_match_idx ON market_reference_snapshots(match_id,provider_id,fair_read_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS market_reference_fresh_idx ON market_reference_snapshots(provider_id,fair_read_at,kickoff)")
    }
    private fun createInternationalOddsTables(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS international_odds_sync_state (provider_id TEXT PRIMARY KEY, status TEXT NOT NULL, last_checked INTEGER NOT NULL, last_success INTEGER, fresh_records_stored INTEGER NOT NULL DEFAULT 0, detail TEXT, quality_received INTEGER, quality_accepted INTEGER, quality_rejected INTEGER, quality_stale INTEGER, quality_duplicates INTEGER, quality_unverified INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS international_odds_snapshots (id INTEGER PRIMARY KEY AUTOINCREMENT, provider_id TEXT NOT NULL, bookmaker_id TEXT NOT NULL, event_id TEXT NOT NULL, match_id INTEGER, match_link_basis TEXT, source_url TEXT NOT NULL, league TEXT NOT NULL, region TEXT, captured_at INTEGER NOT NULL, provider_polled_at INTEGER NOT NULL, kickoff INTEGER NOT NULL, market TEXT NOT NULL, home_team TEXT NOT NULL, away_team TEXT NOT NULL, home_odds REAL NOT NULL, draw_odds REAL NOT NULL, away_odds REAL NOT NULL, home_price_changed_at INTEGER NOT NULL, draw_price_changed_at INTEGER NOT NULL, away_price_changed_at INTEGER NOT NULL, source_market_key TEXT NOT NULL DEFAULT 'unknown', quote_side TEXT NOT NULL DEFAULT 'BACK', home_bet_limit REAL, draw_bet_limit REAL, away_bet_limit REAL, UNIQUE(provider_id,bookmaker_id,event_id,market,source_market_key,quote_side,captured_at))")
        db.execSQL("CREATE TABLE IF NOT EXISTS international_odds_current (id INTEGER PRIMARY KEY AUTOINCREMENT, provider_id TEXT NOT NULL, bookmaker_id TEXT NOT NULL, event_id TEXT NOT NULL, match_id INTEGER, match_link_basis TEXT, source_url TEXT NOT NULL, league TEXT NOT NULL, region TEXT, captured_at INTEGER NOT NULL, provider_polled_at INTEGER NOT NULL, kickoff INTEGER NOT NULL, market TEXT NOT NULL, home_team TEXT NOT NULL, away_team TEXT NOT NULL, home_odds REAL NOT NULL, draw_odds REAL NOT NULL, away_odds REAL NOT NULL, home_price_changed_at INTEGER NOT NULL, draw_price_changed_at INTEGER NOT NULL, away_price_changed_at INTEGER NOT NULL, source_market_key TEXT NOT NULL DEFAULT 'unknown', quote_side TEXT NOT NULL DEFAULT 'BACK', home_bet_limit REAL, draw_bet_limit REAL, away_bet_limit REAL, UNIQUE(provider_id,bookmaker_id,event_id,market,source_market_key,quote_side))")
        db.execSQL("CREATE INDEX IF NOT EXISTS international_odds_fresh_idx ON international_odds_snapshots(provider_id,provider_polled_at,kickoff)")
        db.execSQL("CREATE INDEX IF NOT EXISTS international_odds_current_fresh_idx ON international_odds_current(provider_id,provider_polled_at,kickoff)")
        db.execSQL("CREATE INDEX IF NOT EXISTS international_odds_match_idx ON international_odds_snapshots(match_id,bookmaker_id,provider_polled_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS international_odds_current_match_idx ON international_odds_current(match_id,bookmaker_id,provider_polled_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS international_odds_history_idx ON international_odds_snapshots(bookmaker_id,event_id,captured_at)")
        ensureInternationalOddsSyncAttemptsTable(db)
    }
    private fun ensureInternationalOddsMatchLinkBasisColumns(db:SQLiteDatabase) {
        listOf("international_odds_snapshots", "international_odds_current").forEach { table ->
            val exists=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(table)).use { it.moveToFirst() }
            if(!exists) return@forEach
            val columns=mutableSetOf<String>()
            db.rawQuery("PRAGMA table_info($table)",null).use { cursor ->
                while(cursor.moveToNext()) columns+=cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
            if("match_link_basis" !in columns) {
                db.execSQL("ALTER TABLE $table ADD COLUMN match_link_basis TEXT")
                db.execSQL("UPDATE $table SET match_link_basis='EXACT_KICKOFF' WHERE match_id IS NOT NULL")
            }
        }
    }
    private fun ensureInternationalOddsQuoteSideSchema(db:SQLiteDatabase) {
        val tables = listOf("international_odds_snapshots", "international_odds_current")
        val required = setOf("source_market_key", "quote_side", "home_bet_limit", "draw_bet_limit", "away_bet_limit")
        tables.forEach { table ->
            val exists = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(table),
            ).use { it.moveToFirst() }
            if (!exists) return@forEach
            val columns = mutableSetOf<String>()
            db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                while (cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
            if (required.all { it in columns }) return@forEach

            val temp = "${table}_v18"
            val unique = if (table == "international_odds_snapshots") {
                "UNIQUE(provider_id,bookmaker_id,event_id,market,source_market_key,quote_side,captured_at)"
            } else {
                "UNIQUE(provider_id,bookmaker_id,event_id,market,source_market_key,quote_side)"
            }
            db.execSQL("DROP TABLE IF EXISTS $temp")
            db.execSQL(
                "CREATE TABLE $temp (id INTEGER PRIMARY KEY AUTOINCREMENT, provider_id TEXT NOT NULL, " +
                    "bookmaker_id TEXT NOT NULL, event_id TEXT NOT NULL, match_id INTEGER, match_link_basis TEXT, " +
                    "source_url TEXT NOT NULL, league TEXT NOT NULL, region TEXT, captured_at INTEGER NOT NULL, " +
                    "provider_polled_at INTEGER NOT NULL, kickoff INTEGER NOT NULL, market TEXT NOT NULL, " +
                    "home_team TEXT NOT NULL, away_team TEXT NOT NULL, home_odds REAL NOT NULL, draw_odds REAL NOT NULL, " +
                    "away_odds REAL NOT NULL, home_price_changed_at INTEGER NOT NULL, draw_price_changed_at INTEGER NOT NULL, " +
                    "away_price_changed_at INTEGER NOT NULL, source_market_key TEXT NOT NULL DEFAULT 'unknown', " +
                    "quote_side TEXT NOT NULL DEFAULT 'BACK', home_bet_limit REAL, draw_bet_limit REAL, away_bet_limit REAL, $unique)",
            )
            val linkBasis = if ("match_link_basis" in columns) {
                "match_link_basis"
            } else {
                "CASE WHEN match_id IS NOT NULL THEN 'EXACT_KICKOFF' ELSE NULL END"
            }
            val sourceMarketKey = "CASE WHEN provider_id='the_odds_api' THEN 'h2h' " +
                "WHEN provider_id='moviodds_bet365' THEN 'group=40;market=40' ELSE 'unknown' END"
            val copiedColumns = "id,provider_id,bookmaker_id,event_id,match_id,match_link_basis,source_url,league,region," +
                "captured_at,provider_polled_at,kickoff,market,home_team,away_team,home_odds,draw_odds,away_odds," +
                "home_price_changed_at,draw_price_changed_at,away_price_changed_at,source_market_key,quote_side," +
                "home_bet_limit,draw_bet_limit,away_bet_limit"
            val selectedColumns = "id,provider_id,bookmaker_id,event_id,match_id,$linkBasis,source_url,league,region," +
                "captured_at,provider_polled_at,kickoff,market,home_team,away_team,home_odds,draw_odds,away_odds," +
                "home_price_changed_at,draw_price_changed_at,away_price_changed_at,$sourceMarketKey,'BACK',NULL,NULL,NULL"
            db.execSQL("INSERT INTO $temp($copiedColumns) SELECT $selectedColumns FROM $table")
            db.execSQL("DROP TABLE $table")
            db.execSQL("ALTER TABLE $temp RENAME TO $table")
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS international_odds_fresh_idx ON international_odds_snapshots(provider_id,provider_polled_at,kickoff)")
        db.execSQL("CREATE INDEX IF NOT EXISTS international_odds_current_fresh_idx ON international_odds_current(provider_id,provider_polled_at,kickoff)")
        db.execSQL("CREATE INDEX IF NOT EXISTS international_odds_match_idx ON international_odds_snapshots(match_id,bookmaker_id,provider_polled_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS international_odds_current_match_idx ON international_odds_current(match_id,bookmaker_id,provider_polled_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS international_odds_history_idx ON international_odds_snapshots(bookmaker_id,event_id,captured_at)")
    }
    private fun ensureInternationalOddsSyncAttemptsTable(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS international_odds_sync_attempts (id INTEGER PRIMARY KEY AUTOINCREMENT, provider_id TEXT NOT NULL, checked_at INTEGER NOT NULL, status TEXT NOT NULL, quality_received INTEGER, quality_accepted INTEGER, quality_rejected INTEGER, quality_stale INTEGER, quality_duplicates INTEGER, quality_unverified INTEGER, detail TEXT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS international_odds_attempts_time_idx ON international_odds_sync_attempts(provider_id,checked_at DESC,id DESC)")
    }
    private fun ensureInternationalOddsQualityColumns(db:SQLiteDatabase) {
        val tableExists=db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='international_odds_sync_state'",
            null,
        ).use { it.moveToFirst() }
        if(!tableExists) createInternationalOddsTables(db)
        val columns=mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info(international_odds_sync_state)",null).use { cursor ->
            while(cursor.moveToNext()) columns+=cursor.getString(cursor.getColumnIndexOrThrow("name"))
        }
        listOf("quality_received","quality_accepted","quality_rejected","quality_stale","quality_duplicates","quality_unverified")
            .filterNot { it in columns }
            .forEach { db.execSQL("ALTER TABLE international_odds_sync_state ADD COLUMN $it INTEGER") }
    }
    fun upsert(matches: List<Match>) {
        val db = writableDatabase
        beginWriteTransaction(db)
        try {
            for (m in matches) {
                if(!TeamDataQuality.isValidFixture(m.home,m.away)) continue
                // Never replace/delete a row: tickets and other local records refer to this match ID.
                // An incomplete later provider response must not erase a result already used for settlement.
                val values = ContentValues().apply {
                    put("league", m.league); put("home", m.home); put("away", m.away); put("kickoff", m.kickoff)
                    put("finished", if (m.finished) 1 else 0)
                    if (m.homeScore == null) putNull("home_score") else put("home_score", m.homeScore)
                    if (m.awayScore == null) putNull("away_score") else put("away_score", m.awayScore)
                    put("source_key", m.sourceKey)
                    put("source_name", m.sourceName)
                    put("exact_kickoff", if (m.exactKickoff) 1 else 0)
                    val availableAt = m.availableAtMillis ?: if (m.finished) System.currentTimeMillis() else null
                    if (availableAt == null) putNull("available_at") else put("available_at", availableAt)
                    if (m.sourceUrl == null) putNull("source_url") else put("source_url", m.sourceUrl)
                    if (m.eventDate == null) putNull("event_date") else put("event_date", m.eventDate)
                    if (m.season == null) putNull("season") else put("season", m.season)
                    if (m.capturedAtMillis == null) putNull("captured_at") else put("captured_at",m.capturedAtMillis)
                }
                val existingId = db.rawQuery("SELECT id FROM matches WHERE source_key=? LIMIT 1", arrayOf(m.sourceKey)).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else null
                }
                val naturalDuplicateId = if(existingId==null&&m.eventDate!=null) {
                    val candidates=mutableListOf<Int>()
                    db.rawQuery("SELECT id,home,away,event_date FROM matches WHERE event_date=?",arrayOf(m.eventDate)).use { c ->
                        while(c.moveToNext()) if(FixtureDeduplication.sameCandidate(m.eventDate,m.home,m.away,c.getString(3),c.getString(1),c.getString(2))) candidates+=c.getInt(0)
                    }
                    candidates.singleOrNull()
                } else null
                if (existingId != null) {
                    // Android binds whereArgs as text. Compare against text explicitly;
                    // comparing this parameter to integer 1 silently rejects score corrections.
                    db.update("matches", values, "id=? AND (finished=0 OR (?='1' AND ? IS NOT NULL AND ? IS NOT NULL))",
                        arrayOf(existingId.toString(), if (m.finished) "1" else "0", m.homeScore?.toString(), m.awayScore?.toString()))
                } else if(naturalDuplicateId!=null) {
                    val old=db.rawQuery("SELECT league,kickoff,finished,home_score,away_score,source_name,exact_kickoff,available_at,source_url,captured_at,season FROM matches WHERE id=?",arrayOf(naturalDuplicateId.toString())).use { c ->
                        if(!c.moveToFirst()) null else arrayOf<Any?>(c.getString(0),c.getString(1),c.getInt(2),if(c.isNull(3)) null else c.getInt(3),if(c.isNull(4)) null else c.getInt(4),c.getString(5),c.getInt(6),if(c.isNull(7)) null else c.getLong(7),if(c.isNull(8)) null else c.getString(8),if(c.isNull(9)) null else c.getLong(9),if(c.isNull(10)) null else c.getString(10))
                    }
                    if(old!=null) {
                        val oldFinished=old[2] as Int==1
                        val oldExact=old[6] as Int==1
                        val useIncomingResult=m.finished&&!oldFinished
                        val useIncomingKickoff=m.exactKickoff&&!oldExact
                        val oldNames=(old[5] as String).split(" + ").map { it.trim() }.filter { it.isNotEmpty() }
                        val mergedName=(oldNames+m.sourceName).distinct().joinToString(" + ")
                        val oldUrls=(old[8] as String?).orEmpty().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                        val mergedUrls=(oldUrls+listOfNotNull(m.sourceUrl)).distinct()
                        val mergedCapture=listOfNotNull(old[9] as Long?,m.capturedAtMillis).maxOrNull()
                        val hasProvenanceUpdate=mergedName!=(old[5] as String)||
                            mergedUrls.joinToString("\n")!=(old[8] as String?).orEmpty()||
                            mergedCapture!=(old[9] as Long?)
                        if(useIncomingResult||useIncomingKickoff||hasProvenanceUpdate) {
                            val oldAvailable=old[7] as Long?
                            val newAvailable=if(useIncomingResult) listOfNotNull(oldAvailable,m.availableAtMillis).maxOrNull() else oldAvailable
                            val merged=ContentValues().apply {
                                put("source_name",mergedName)
                                if(useIncomingKickoff) {
                                    put("league",m.league);put("home",m.home);put("away",m.away);put("kickoff",m.kickoff)
                                    put("exact_kickoff",1);put("season",m.season)
                                }
                                if(useIncomingResult) {
                                    put("finished",1);put("home_score",m.homeScore);put("away_score",m.awayScore)
                                }
                                if(newAvailable==null) putNull("available_at") else put("available_at",newAvailable)
                                if(mergedUrls.isNotEmpty()) put("source_url",mergedUrls.joinToString("\n"))
                                if(mergedCapture!=null) put("captured_at",mergedCapture)
                            }
                            db.update("matches",merged,"id=?",arrayOf(naturalDuplicateId.toString()))
                        }
                    }
                } else if (m.id > 0) {
                    values.put("id", m.id)
                    val inserted=db.insertWithOnConflict("matches", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                    if(inserted==-1L) {
                        // New provider IDs can overlap locally generated SQLite row IDs from another source.
                        values.remove("id")
                        db.insertWithOnConflict("matches", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                    }
                } else {
                    db.insertWithOnConflict("matches", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                }
            }
            db.setTransactionSuccessful()
        } finally { endWriteTransaction(db) }
    }
    fun upsertProviderMatches(providerMatches:List<ProviderMatch>):Int {
        val rows = providerMatches.filter { TeamDataQuality.isValidFixture(it.homeTeam,it.awayTeam) }.map { row ->
            val isOpenLiga = row.providerId == "openligadb"
            Match(
                id = if (isOpenLiga) row.providerMatchId.toIntOrNull() ?: 0 else 0,
                league = row.competitionCode,
                home = row.homeTeam,
                away = row.awayTeam,
                kickoff = Instant.ofEpochMilli(row.kickoffMillis).toString(),
                finished = row.finished,
                homeScore = row.homeGoals,
                awayScore = row.awayGoals,
                sourceKey = "${row.providerId}:${row.providerMatchId}",
                sourceName = if (isOpenLiga) "OpenLigaDB" else "OpenFootball (CC0)",
                exactKickoff = row.kickoffPrecision == KickoffPrecision.INSTANT,
                availableAtMillis = row.safeAvailableAtMillis,
                sourceUrl = row.sourceUrl,
                eventDate = row.eventDate.toString(),
                season = row.season,
                capturedAtMillis = row.capturedAtMillis,
            )
        }
        upsert(rows)
        return rows.size
    }
    fun providerSyncSummaries():List<ProviderSyncSummary> {
        val result=mutableListOf<ProviderSyncSummary>()
        readableDatabase.rawQuery("SELECT provider_id,status,last_checked,last_success,records_stored,error FROM provider_sync_state ORDER BY provider_id",null).use { c ->
            while(c.moveToNext()) result.add(ProviderSyncSummary(c.getString(0),c.getString(1),c.getLong(2),if(c.isNull(3)) null else c.getLong(3),c.getInt(4),if(c.isNull(5)) null else c.getString(5)))
        }
        return result
    }
    fun tryAcquireProviderSyncLock(nowMillis:Long,leaseMillis:Long):Boolean {
        val db=writableDatabase
        beginWriteTransaction(db)
        try {
            val expires=db.rawQuery("SELECT expires_at FROM provider_sync_lock WHERE id=1",null).use { c -> if(c.moveToFirst()) c.getLong(0) else 0L }
            if(expires>nowMillis) return false
            val values=ContentValues().apply { put("id",1);put("expires_at",nowMillis+leaseMillis) }
            db.insertWithOnConflict("provider_sync_lock",null,values,SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
            return true
        } finally { endWriteTransaction(db) }
    }
    fun releaseProviderSyncLock() = StoreWriteCoordinator.withLock {
        writableDatabase.delete("provider_sync_lock","id=1",null)
        Unit
    }
    fun saveProviderSync(summary:ProviderSyncSummary) = StoreWriteCoordinator.withLock {
        val db=writableDatabase
        val oldState=db.rawQuery("SELECT last_success,records_stored FROM provider_sync_state WHERE provider_id=?",arrayOf(summary.providerId)).use { c ->
            if(c.moveToFirst()) (if(c.isNull(0)) null else c.getLong(0)) to c.getInt(1) else null
        }
        val values=ContentValues().apply {
            put("provider_id",summary.providerId);put("status",summary.status);put("last_checked",summary.lastCheckedAtMillis)
            val lastSuccess=summary.lastSuccessAtMillis ?: oldState?.first
            if(lastSuccess==null) putNull("last_success") else put("last_success",lastSuccess)
            put("records_stored",maxOf(summary.recordsStored,oldState?.second ?: 0))
            if(summary.error==null) putNull("error") else put("error",summary.error)
        }
        db.insertWithOnConflict("provider_sync_state",null,values,SQLiteDatabase.CONFLICT_REPLACE)
        Unit
    }
    fun providerFileCache(cacheKey:String):ProviderFileCache? = readableDatabase.rawQuery("SELECT etag,last_modified,last_checked,record_count,cached_body FROM provider_file_cache WHERE cache_key=?",arrayOf(cacheKey)).use { c ->
        if(!c.moveToFirst()) null else ProviderFileCache(if(c.isNull(0)) null else c.getString(0),if(c.isNull(1)) null else c.getString(1),c.getLong(2),c.getInt(3),if(c.isNull(4)) null else c.getString(4))
    }
    fun saveProviderFileCache(cacheKey:String,providerId:String,etag:String?,lastModified:String?,checkedAt:Long,recordCount:Int,cachedBody:String?=null) = StoreWriteCoordinator.withLock {
        val db=writableDatabase
        val old=providerFileCache(cacheKey)
        val values=ContentValues().apply {
            put("cache_key",cacheKey);put("provider_id",providerId)
            (etag ?: old?.etag)?.let { put("etag",it) } ?: putNull("etag")
            (lastModified ?: old?.lastModified)?.let { put("last_modified",it) } ?: putNull("last_modified")
            put("last_checked",checkedAt);put("record_count",recordCount)
            (cachedBody ?: old?.cachedBody)?.let { put("cached_body",it) } ?: putNull("cached_body")
        }
        db.insertWithOnConflict("provider_file_cache",null,values,SQLiteDatabase.CONFLICT_REPLACE)
        Unit
    }
    fun openLigaState(leagueKey:String):OpenLigaLeagueState? = readableDatabase.rawQuery("SELECT source_change,current_group_order,last_full_sync,checked_at,record_count FROM provider_league_state WHERE league_key=?",arrayOf(leagueKey)).use { c ->
        if(!c.moveToFirst()) null else OpenLigaLeagueState(if(c.isNull(0)) null else c.getString(0),if(c.isNull(1)) null else c.getInt(1),c.getLong(2),c.getLong(3),c.getInt(4))
    }
    fun saveOpenLigaState(leagueKey:String,sourceChange:String?,currentGroupOrderId:Int?,fullSyncAt:Long,checkedAt:Long,recordCount:Int) = StoreWriteCoordinator.withLock {
        val db=writableDatabase
        val old=db.rawQuery("SELECT source_change,current_group_order,last_full_sync FROM provider_league_state WHERE league_key=?",arrayOf(leagueKey)).use { c ->
            if(c.moveToFirst()) Triple(if(c.isNull(0)) null else c.getString(0),if(c.isNull(1)) null else c.getInt(1),c.getLong(2)) else null
        }
        val values=ContentValues().apply {
            put("league_key",leagueKey)
            (sourceChange ?: old?.first)?.let { put("source_change",it) } ?: putNull("source_change")
            if(currentGroupOrderId==null&&old?.second==null) putNull("current_group_order") else put("current_group_order",currentGroupOrderId ?: old?.second)
            put("last_full_sync",if(fullSyncAt>0) fullSyncAt else old?.third ?: 0L)
            put("checked_at",checkedAt);put("record_count",recordCount)
        }
        db.insertWithOnConflict("provider_league_state",null,values,SQLiteDatabase.CONFLICT_REPLACE)
        Unit
    }
    fun matches(): List<Match> {
        val result=mutableListOf<Match>()
        val today=LocalDate.now().toString()
        val now=Instant.now().toString()
        readableDatabase.rawQuery("SELECT id,league,home,away,kickoff,finished,home_score,away_score,source_key,source_name,exact_kickoff,available_at,source_url,event_date,season,captured_at FROM matches ORDER BY CASE WHEN finished=0 AND ((exact_kickoff=1 AND kickoff>?) OR (exact_kickoff=0 AND COALESCE(event_date,substr(kickoff,1,10))>=?)) THEN 0 ELSE 1 END, CASE WHEN finished=0 AND ((exact_kickoff=1 AND kickoff>?) OR (exact_kickoff=0 AND COALESCE(event_date,substr(kickoff,1,10))>=?)) THEN COALESCE(event_date,substr(kickoff,1,10)) END ASC, CASE WHEN finished=1 THEN 0 ELSE 1 END, kickoff DESC LIMIT 250",arrayOf(now,today,now,today)).use { c ->
            while(c.moveToNext()) {
                val match=Match(c.getInt(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getInt(5)==1,
                    if(c.isNull(6)) null else c.getInt(6),if(c.isNull(7)) null else c.getInt(7),c.getString(8),c.getString(9),c.getInt(10)==1,
                    if(c.isNull(11)) null else c.getLong(11),if(c.isNull(12)) null else c.getString(12),if(c.isNull(13)) null else c.getString(13),if(c.isNull(14)) null else c.getString(14),if(c.isNull(15)) null else c.getLong(15))
                if(SeasonDateValidity.isPlausible(match.eventDate ?: match.kickoff.take(10),match.season)&&TeamDataQuality.isValidFixture(match.home,match.away)) result+=match
            }
        }
        return result
    }
    fun recentCompletedMatches(limit:Int=250):List<Match> {
        val result=mutableListOf<Match>()
        readableDatabase.rawQuery("SELECT id,league,home,away,kickoff,finished,home_score,away_score,source_key,source_name,exact_kickoff,available_at,source_url,event_date,season,captured_at FROM matches WHERE finished=1 ORDER BY kickoff DESC LIMIT ?",arrayOf(limit.toString())).use { c ->
            while(c.moveToNext()) {
                val match=Match(c.getInt(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),true,
                    if(c.isNull(6)) null else c.getInt(6),if(c.isNull(7)) null else c.getInt(7),c.getString(8),c.getString(9),c.getInt(10)==1,
                    if(c.isNull(11)) null else c.getLong(11),if(c.isNull(12)) null else c.getString(12),if(c.isNull(13)) null else c.getString(13),if(c.isNull(14)) null else c.getString(14),if(c.isNull(15)) null else c.getLong(15))
                if(SeasonDateValidity.isPlausible(match.eventDate ?: match.kickoff.take(10),match.season)&&TeamDataQuality.isValidFixture(match.home,match.away)) result+=match
            }
        }
        return result
    }
    fun historicalResultsBefore(league:String, cutoffMillis:Long):List<HistoricalMatch> {
        val result=mutableListOf<HistoricalMatch>()
        readableDatabase.rawQuery(
            "SELECT home,away,kickoff,home_score,away_score,available_at,season,captured_at FROM matches WHERE league=? AND finished=1 AND home_score IS NOT NULL AND away_score IS NOT NULL",
            arrayOf(league)
        ).use { c ->
            while(c.moveToNext()) {
                val kickoff=runCatching { Instant.parse(c.getString(2)).toEpochMilli() }.getOrNull() ?: continue
                val eventDate=Instant.ofEpochMilli(kickoff).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
                if(!SeasonDateValidity.isPlausible(eventDate,if(c.isNull(6)) null else c.getString(6))) continue
                if(!TeamDataQuality.isValidFixture(c.getString(0),c.getString(1))) continue
                val availableAt=conservativeResultAvailability(
                    if(c.isNull(5)) null else c.getLong(5),
                    if(c.isNull(7)) null else c.getLong(7),
                ) ?: continue
                if(kickoff<cutoffMillis && availableAt<cutoffMillis) result.add(HistoricalMatch(kickoff,c.getString(0),c.getString(1),c.getInt(3),c.getInt(4),availableAt))
            }
        }
        return result
    }
    fun historicalResultsByLeague():Map<String,List<HistoricalMatch>> {
        val result=linkedMapOf<String,MutableList<HistoricalMatch>>()
        readableDatabase.rawQuery(
            "SELECT league,home,away,kickoff,home_score,away_score,available_at,season,captured_at FROM matches WHERE finished=1 AND home_score IS NOT NULL AND away_score IS NOT NULL ORDER BY league,kickoff",
            null,
        ).use { c ->
            while(c.moveToNext()) {
                val kickoff=runCatching { Instant.parse(c.getString(3)).toEpochMilli() }.getOrNull() ?: continue
                val eventDate=Instant.ofEpochMilli(kickoff).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
                if(!SeasonDateValidity.isPlausible(eventDate,if(c.isNull(7)) null else c.getString(7))) continue
                if(!TeamDataQuality.isValidFixture(c.getString(1),c.getString(2))) continue
                val availableAt=conservativeResultAvailability(
                    if(c.isNull(6)) null else c.getLong(6),
                    if(c.isNull(8)) null else c.getLong(8),
                ) ?: continue
                result.getOrPut(c.getString(0)) { mutableListOf() }.add(
                    HistoricalMatch(kickoff,c.getString(1),c.getString(2),c.getInt(4),c.getInt(5),availableAt),
                )
            }
        }
        return result
    }
    fun automaticBacktestReports():AutomaticBacktestReports {
        data class Row(val history:HistoricalMatch,val target:WalkForwardTarget)
        val byLeague=linkedMapOf<String,MutableList<Row>>()
        readableDatabase.rawQuery(
            "SELECT id,league,home,away,kickoff,home_score,away_score,available_at,exact_kickoff,event_date,season FROM matches WHERE finished=1 AND home_score IS NOT NULL AND away_score IS NOT NULL ORDER BY league,kickoff",
            null,
        ).use { c ->
            while(c.moveToNext()) {
                val kickoff=runCatching { Instant.parse(c.getString(4)).toEpochMilli() }.getOrNull() ?: continue
                val eventDate=c.getString(9) ?: c.getString(4).take(10)
                val season=if(c.isNull(10)) null else c.getString(10)
                if(!SeasonDateValidity.isPlausible(eventDate,season)) continue
                if(!TeamDataQuality.isValidFixture(c.getString(2),c.getString(3))) continue
                val cutoff=if(c.getInt(8)==1) kickoff else runCatching {
                    LocalDate.parse(eventDate).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
                }.getOrNull() ?: continue
                val home=c.getString(2);val away=c.getString(3)
                val homeGoals=c.getInt(5);val awayGoals=c.getInt(6)
                val safeAvailability=if(c.isNull(7)) Long.MAX_VALUE else c.getLong(7)
                val row=Row(
                    HistoricalMatch(kickoff,home,away,homeGoals,awayGoals,safeAvailability),
                    WalkForwardTarget(c.getInt(0).toString(),cutoff,home,away,homeGoals,awayGoals),
                )
                byLeague.getOrPut(c.getString(1)) { mutableListOf() }.add(row)
            }
        }
        val datasets=byLeague.map { (league,rows) ->
            EloExperimentDataset(league,rows.map { it.history },rows.map { it.target })
        }
        val reports=datasets.map { dataset ->
            EloWalkForwardEngine.evaluate(dataset.history,dataset.targets)
        }
        val predictions=reports.flatMap { it.predictions }
        val walkForward=EloWalkForwardReport(
            targetCount=reports.sumOf { it.targetCount },
            forecastCount=predictions.size,
            skippedForInsufficientHistory=reports.sumOf { it.skippedForInsufficientHistory },
            meanBrierScore=predictions.map { it.brierScore }.average().takeIf { predictions.isNotEmpty() },
            meanLogLoss=predictions.map { it.logLoss }.average().takeIf { predictions.isNotEmpty() },
            predictions=predictions,
        )
        val parameterExperiment=EloParameterExperimentEngine.evaluate(datasets)
        val goalModelComparison=GoalModelComparisonEngine.evaluate(datasets,parameterExperiment)
        return AutomaticBacktestReports(walkForward,parameterExperiment,goalModelComparison)
    }
    fun recordProspectiveForecastSnapshots(
        forecasts:List<ForecastedMatch>,
        nowMillis:Long=System.currentTimeMillis(),
    ):Int {
        if(forecasts.isEmpty()) return 0
        val db=writableDatabase
        var inserted=0
        beginWriteTransaction(db)
        try {
            for(forecast in forecasts) {
                if(!TeamDataQuality.isValidFixture(forecast.homeTeam,forecast.awayTeam)) continue
                if(!isProspectiveForecastEligible(
                        forecast.forecastedAtMillis,
                        forecast.kickoffMillis,
                        nowMillis,
                        forecast.historyMatchCount,
                    )
                ) continue
                val currentMatch=db.rawQuery(
                    "SELECT league,home,away,kickoff,finished,exact_kickoff FROM matches WHERE id=?",
                    arrayOf(forecast.matchId.toString()),
                ).use { c ->
                    if(!c.moveToFirst()) null else {
                        val kickoff=runCatching { Instant.parse(c.getString(3)).toEpochMilli() }.getOrNull()
                        arrayOf<Any?>(c.getString(0),c.getString(1),c.getString(2),kickoff,c.getInt(4)==1,c.getInt(5)==1)
                    }
                } ?: continue
                if(currentMatch[4] as Boolean || !(currentMatch[5] as Boolean)) continue
                if(currentMatch[3] as Long? != forecast.kickoffMillis) continue
                if(currentMatch[1] as String != forecast.homeTeam || currentMatch[2] as String != forecast.awayTeam) continue

                val forecastDayUtc=Instant.ofEpochMilli(forecast.forecastedAtMillis)
                    .atZone(ZoneOffset.UTC).toLocalDate().toString()
                val values=ContentValues().apply {
                    put("model_version",forecast.modelVersion)
                    put("match_id",forecast.matchId)
                    put("forecast_day_utc",forecastDayUtc)
                    put("forecasted_at",forecast.forecastedAtMillis)
                    put("kickoff_at",forecast.kickoffMillis)
                    put("competition",forecast.competition.ifBlank { currentMatch[0] as String })
                    put("home_team",forecast.homeTeam)
                    put("away_team",forecast.awayTeam)
                    put("history_match_count",forecast.historyMatchCount)
                    put("home_probability",forecast.probabilities.home)
                    put("draw_probability",forecast.probabilities.draw)
                    put("away_probability",forecast.probabilities.away)
                }
                if(db.insertWithOnConflict(
                        "prospective_forecast_snapshots",
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_IGNORE,
                    )!=-1L
                ) inserted++
            }
            db.setTransactionSuccessful()
        } finally {
            endWriteTransaction(db)
        }
        return inserted
    }
    fun settleProspectiveForecastSnapshots(nowMillis:Long=System.currentTimeMillis()):Int {
        data class SettledResult(
            val snapshotId:Long,
            val forecastedAt:Long,
            val availableAt:Long,
            val outcome:String,
            val homeGoals:Int,
            val awayGoals:Int,
            val sourceName:String,
            val sourceUrl:String?,
            val previousOutcome:String?,
            val previousHomeGoals:Int?,
            val previousAwayGoals:Int?,
            val previousAvailableAt:Long?,
            val previousSourceName:String?,
            val previousSourceUrl:String?,
        )
        val results=mutableListOf<SettledResult>()
        readableDatabase.rawQuery(
            "SELECT s.id,s.forecasted_at,m.home,m.away,m.home_score,m.away_score," +
                "CASE WHEN m.captured_at IS NULL THEN m.available_at WHEN m.available_at IS NULL THEN m.captured_at " +
                "ELSE MAX(m.available_at,m.captured_at) END,m.kickoff,m.source_name,m.source_url," +
                "r.actual_outcome,r.home_goals,r.away_goals,r.result_available_at,r.result_source_name,r.result_source_url " +
                "FROM prospective_forecast_snapshots s JOIN matches m ON m.id=s.match_id " +
                "LEFT JOIN prospective_forecast_settlements r ON r.snapshot_id=s.id " +
                "WHERE m.finished=1 AND m.home_score IS NOT NULL AND m.away_score IS NOT NULL",
            null,
        ).use { c ->
            while(c.moveToNext()) {
                val home=c.getString(2)
                val away=c.getString(3)
                if(!TeamDataQuality.isValidFixture(home,away)) continue
                val kickoff=runCatching { Instant.parse(c.getString(7)).toEpochMilli() }.getOrNull() ?: continue
                if(c.isNull(6)) continue
                val availableAt=c.getLong(6)
                val forecastedAt=c.getLong(1)
                if(availableAt>nowMillis||availableAt<=forecastedAt||availableAt<kickoff) continue
                val homeGoals=c.getInt(4)
                val awayGoals=c.getInt(5)
                val outcome=when {
                    homeGoals>awayGoals -> "H"
                    homeGoals<awayGoals -> "A"
                    else -> "D"
                }
                results+=SettledResult(
                    c.getLong(0),
                    forecastedAt,
                    availableAt,
                    outcome,
                    homeGoals,
                    awayGoals,
                    c.getString(8),
                    if(c.isNull(9)) null else c.getString(9),
                    if(c.isNull(10)) null else c.getString(10),
                    if(c.isNull(11)) null else c.getInt(11),
                    if(c.isNull(12)) null else c.getInt(12),
                    if(c.isNull(13)) null else c.getLong(13),
                    if(c.isNull(14)) null else c.getString(14),
                    if(c.isNull(15)) null else c.getString(15),
                )
            }
        }
        if(results.isEmpty()) return 0
        val db=writableDatabase
        var inserted=0
        beginWriteTransaction(db)
        try {
            for(result in results) {
                val unchanged=result.outcome==result.previousOutcome&&
                    result.homeGoals==result.previousHomeGoals&&
                    result.awayGoals==result.previousAwayGoals&&
                    result.availableAt==result.previousAvailableAt&&
                    result.sourceName==result.previousSourceName&&
                    result.sourceUrl==result.previousSourceUrl
                if(unchanged) continue
                val values=ContentValues().apply {
                    put("snapshot_id",result.snapshotId)
                    put("settled_at",nowMillis)
                    put("result_available_at",result.availableAt)
                    put("actual_outcome",result.outcome)
                    put("home_goals",result.homeGoals)
                    put("away_goals",result.awayGoals)
                    put("result_source_name",result.sourceName)
                    if(result.sourceUrl==null) putNull("result_source_url") else put("result_source_url",result.sourceUrl)
                }
                if(db.insertWithOnConflict(
                        "prospective_forecast_settlements",
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )!=-1L
                ) inserted++
            }
            db.setTransactionSuccessful()
        } finally {
            endWriteTransaction(db)
        }
        return inserted
    }
    fun prospectiveForecastHistory(limit:Int=100):List<ProspectiveForecastObservation> {
        val result=mutableListOf<ProspectiveForecastObservation>()
        val queryLimit=when {
            limit==0 -> null
            limit>0 -> limit.coerceIn(1,500)
            else -> 1
        }
        val sql="SELECT s.id,s.model_version,s.match_id,s.competition,s.home_team,s.away_team,s.forecasted_at,s.kickoff_at," +
            "s.history_match_count,s.home_probability,s.draw_probability,s.away_probability," +
            "r.actual_outcome,r.home_goals,r.away_goals,r.settled_at,r.result_available_at,r.result_source_name,r.result_source_url " +
            "FROM prospective_forecast_snapshots s LEFT JOIN prospective_forecast_settlements r ON r.snapshot_id=s.id " +
            "ORDER BY s.forecasted_at DESC,s.id DESC" + if(queryLimit==null) "" else " LIMIT ?"
        readableDatabase.rawQuery(
            sql,
            queryLimit?.let { arrayOf(it.toString()) },
        ).use { c ->
            while(c.moveToNext()) result+=ProspectiveForecastObservation(
                snapshotId=c.getLong(0),
                modelVersion=c.getString(1),
                matchId=c.getInt(2),
                competition=c.getString(3),
                homeTeam=c.getString(4),
                awayTeam=c.getString(5),
                forecastedAtMillis=c.getLong(6),
                kickoffMillis=c.getLong(7),
                historyMatchCount=c.getInt(8),
                probabilities=az.simplesoft.footballintelligence.analytics.OutcomeProbabilities(
                    c.getDouble(9),c.getDouble(10),c.getDouble(11),
                ),
                actualOutcome=if(c.isNull(12)) null else c.getString(12),
                homeGoals=if(c.isNull(13)) null else c.getInt(13),
                awayGoals=if(c.isNull(14)) null else c.getInt(14),
                settledAtMillis=if(c.isNull(15)) null else c.getLong(15),
                resultAvailableAtMillis=if(c.isNull(16)) null else c.getLong(16),
                resultSourceName=if(c.isNull(17)) null else c.getString(17),
                resultSourceUrl=if(c.isNull(18)) null else c.getString(18),
            )
        }
        return result
    }
    fun recordCouponCandidates(candidates:List<VirtualCouponCandidate>, nowMillis:Long=System.currentTimeMillis()):Int {
        val db=writableDatabase
        var inserted=0
        beginWriteTransaction(db)
        try {
            for(candidate in candidates) for(pick in candidate.picks) {
                if(pick.kickoffMillis<=nowMillis) continue
                val values=ContentValues().apply {
                    put("provider_id",candidate.providerId);put("match_id",pick.matchId);put("outcome",pick.outcome)
                    put("model_probability",pick.modelProbability);put("captured_at",nowMillis)
                }
                if(db.insertWithOnConflict("model_coupon_picks",null,values,SQLiteDatabase.CONFLICT_IGNORE)!=-1L) inserted++
            }
            db.setTransactionSuccessful()
        } finally { endWriteTransaction(db) }
        return inserted
    }
    fun recordAccumulatorDrafts(candidates:List<VirtualCouponCandidate>, nowMillis:Long=System.currentTimeMillis()):Int {
        val db=writableDatabase
        var inserted=0
        beginWriteTransaction(db)
        try {
            for(candidate in candidates) {
                val size=candidate.requestedLegCount
                val bounds=candidate.jointProbabilityBounds ?: continue
                if(size !in VirtualCouponBuilder.supportedAccumulatorSizes || candidate.picks.size!=size || candidate.picks.any { it.kickoffMillis<=nowMillis }) continue
                val signature=candidate.selectionBasis.name+":"+candidate.picks.joinToString(";") { pick ->
                    listOf(
                        pick.matchId,pick.outcome,pick.modelProbability.toBits(),pick.bookmakerDecimalOdds?.toBits(),
                        pick.marketFairProbability?.toBits(),pick.modelMinusMarket?.toBits(),
                    ).joinToString(":")
                }
                val values=ContentValues().apply {
                    put("provider_id",candidate.providerId);put("leg_count",size);put("captured_at",nowMillis)
                    put("snapshot_key",signature);put("strategy_version",candidate.selectionBasis.strategyVersion)
                    put("selection_basis",candidate.selectionBasis.name)
                    if(candidate.combinedDecimalOdds==null) putNull("combined_decimal_odds") else put("combined_decimal_odds",candidate.combinedDecimalOdds)
                    put("joint_probability_lower",bounds.lower);put("joint_probability_upper",bounds.upper)
                }
                val accumulatorId=db.insertWithOnConflict("virtual_accumulators",null,values,SQLiteDatabase.CONFLICT_IGNORE)
                if(accumulatorId==-1L) continue
                candidate.picks.forEach { pick ->
                    val leg=ContentValues().apply {
                        put("accumulator_id",accumulatorId);put("match_id",pick.matchId);put("home_team",pick.homeTeam);put("away_team",pick.awayTeam)
                        put("scheduled_kickoff",pick.kickoffMillis);put("outcome",pick.outcome);put("model_probability",pick.modelProbability)
                        if(pick.bookmakerDecimalOdds==null) putNull("bookmaker_decimal_odds") else put("bookmaker_decimal_odds",pick.bookmakerDecimalOdds)
                        if(pick.bookmakerOddsCapturedAtMillis==null) putNull("bookmaker_odds_captured_at") else put("bookmaker_odds_captured_at",pick.bookmakerOddsCapturedAtMillis)
                        if(pick.marketFairProbability==null) putNull("market_fair_probability") else put("market_fair_probability",pick.marketFairProbability)
                        if(pick.modelMinusMarket==null) putNull("model_minus_market") else put("model_minus_market",pick.modelMinusMarket)
                    }
                    db.insertOrThrow("virtual_accumulator_legs",null,leg)
                }
                inserted++
            }
            db.setTransactionSuccessful()
        } finally { endWriteTransaction(db) }
        return inserted
    }
    fun virtualAccumulatorHistory(limit:Int=100):List<VirtualAccumulatorHistoryEntry> {
        data class Header(val id:Long,val providerId:String,val size:Int,val capturedAt:Long,val combinedOdds:Double?,val lower:Double,val upper:Double,val selectionBasis:CouponSelectionBasis)
        val headers=mutableListOf<Header>()
        readableDatabase.rawQuery(
            "SELECT id,provider_id,leg_count,captured_at,combined_decimal_odds,joint_probability_lower,joint_probability_upper,selection_basis FROM virtual_accumulators ORDER BY captured_at DESC,id DESC LIMIT ?",
            arrayOf(limit.coerceIn(1,500).toString()),
        ).use { c -> while(c.moveToNext()) headers+=Header(c.getLong(0),c.getString(1),c.getInt(2),c.getLong(3),if(c.isNull(4)) null else c.getDouble(4),c.getDouble(5),c.getDouble(6),runCatching { CouponSelectionBasis.valueOf(c.getString(7)) }.getOrDefault(CouponSelectionBasis.MODEL_RANKED)) }
        return headers.mapNotNull { header ->
            val picks=mutableListOf<az.simplesoft.footballintelligence.analytics.VirtualCouponPick>()
            val results=mutableMapOf<Int,AccumulatorMatchResult>()
            readableDatabase.rawQuery(
                "SELECT l.match_id,l.home_team,l.away_team,l.scheduled_kickoff,l.outcome,l.model_probability,l.bookmaker_decimal_odds,l.bookmaker_odds_captured_at,l.market_fair_probability,l.model_minus_market,m.kickoff,m.finished,m.home_score,m.away_score FROM virtual_accumulator_legs l LEFT JOIN matches m ON m.id=l.match_id WHERE l.accumulator_id=? ORDER BY l.scheduled_kickoff",
                arrayOf(header.id.toString()),
            ).use { c ->
                while(c.moveToNext()) {
                    val matchId=c.getInt(0)
                    picks+=az.simplesoft.footballintelligence.analytics.VirtualCouponPick(
                        matchId,c.getString(1),c.getString(2),c.getLong(3),c.getString(4),c.getDouble(5),
                        if(c.isNull(6)) null else c.getDouble(6),if(c.isNull(7)) null else c.getLong(7),
                        if(c.isNull(8)) null else c.getDouble(8),if(c.isNull(9)) null else c.getDouble(9),
                    )
                    if(!c.isNull(10)) {
                        val actualKickoff=runCatching { Instant.parse(c.getString(10)).toEpochMilli() }.getOrNull()
                        if(actualKickoff!=null) results[matchId]=AccumulatorMatchResult(
                            matchId,actualKickoff,c.getInt(11)==1,if(c.isNull(12)) null else c.getInt(12),if(c.isNull(13)) null else c.getInt(13),
                        )
                    }
                }
            }
            if(picks.size!=header.size) return@mapNotNull null
            VirtualAccumulatorHistoryEntry(
                header.id,header.providerId,header.size,header.capturedAt,header.combinedOdds,
                az.simplesoft.footballintelligence.analytics.JointProbabilityBounds(header.lower,header.upper),
                AccumulatorEngine.settle(picks,results),picks,header.selectionBasis,
            )
        }
    }
    fun bookmakerSyncState(providerId:String):BookmakerSyncState? = readableDatabase.rawQuery(
        "SELECT status,last_checked,last_success,records_stored,error FROM bookmaker_sync_state WHERE provider_id=?",
        arrayOf(providerId)
    ).use { c ->
        if(!c.moveToFirst()) null else BookmakerSyncState(
            providerId,
            runCatching { OddsProviderStatus.valueOf(c.getString(0)) }.getOrDefault(OddsProviderStatus.ERROR),
            if(c.isNull(1)) null else c.getLong(1),
            if(c.isNull(2)) null else c.getLong(2),
            c.getInt(3),
            if(c.isNull(4)) null else c.getString(4),
        )
    }
    fun matchIdentities():List<FixtureIdentity> {
        val result=mutableListOf<FixtureIdentity>()
        readableDatabase.rawQuery("SELECT id,home,away,kickoff,exact_kickoff FROM matches WHERE finished=0",null).use { c ->
            while(c.moveToNext()) {
                val kickoff=runCatching { Instant.parse(c.getString(3)).toEpochMilli() }.getOrNull() ?: continue
                if(TeamDataQuality.isValidFixture(c.getString(1),c.getString(2))) {
                    result += FixtureIdentity(c.getInt(0),c.getString(1),c.getString(2),kickoff,c.getInt(4)==1)
                }
            }
        }
        return result
    }
    fun saveBookmakerCapture(
        providerId:String,
        checkedAtMillis:Long,
        status:OddsProviderStatus,
        snapshots:List<BookmakerOddsSnapshot>,
        error:String?=null,
    ) {
        val db=writableDatabase
        beginWriteTransaction(db)
        try {
            snapshots.forEach { quote ->
                val values=ContentValues().apply {
                    put("provider_id",quote.providerId);put("event_id",quote.eventId)
                    if(quote.matchId==null) putNull("match_id") else put("match_id",quote.matchId)
                    put("source_url",quote.sourceUrl);put("captured_at",quote.capturedAtMillis);put("kickoff",quote.kickoffMillis)
                    put("market",quote.market);put("home_team",quote.homeTeam);put("away_team",quote.awayTeam)
                    put("home_key",az.simplesoft.footballintelligence.data.TeamNameIdentity.canonical(quote.homeTeam))
                    put("away_key",az.simplesoft.footballintelligence.data.TeamNameIdentity.canonical(quote.awayTeam))
                    put("home_odds",quote.home);put("draw_odds",quote.draw);put("away_odds",quote.away)
                    put("event_date",quote.eventDate);put("time_zone_id",quote.timeZoneId)
                    put("event_year_inferred",if(quote.eventYearInferred) 1 else 0)
                }
                db.insertWithOnConflict("bookmaker_odds_snapshots",null,values,SQLiteDatabase.CONFLICT_IGNORE)
            }
            val old=bookmakerSyncState(providerId)
            val values=ContentValues().apply {
                put("provider_id",providerId);put("status",status.name);put("last_checked",checkedAtMillis)
                val successfulCheck=if(status!=OddsProviderStatus.ERROR&&status!=OddsProviderStatus.BLOCKED) checkedAtMillis else old?.lastSuccessAtMillis
                if(successfulCheck==null) putNull("last_success") else put("last_success",successfulCheck)
                put("records_stored",snapshots.size)
                if(error==null) putNull("error") else put("error",error)
            }
            db.insertWithOnConflict("bookmaker_sync_state",null,values,SQLiteDatabase.CONFLICT_REPLACE)
            db.delete("bookmaker_odds_snapshots","captured_at<?",arrayOf((checkedAtMillis-30L*24*60*60*1000).toString()))
            db.setTransactionSuccessful()
        } finally { endWriteTransaction(db) }
    }
    fun internationalOddsSyncState(providerId:String=MOVIODDS_PROVIDER_ID):InternationalOddsSyncState? = readableDatabase.rawQuery(
        "SELECT status,last_checked,last_success,fresh_records_stored,detail,quality_received,quality_accepted,quality_rejected,quality_stale,quality_duplicates,quality_unverified FROM international_odds_sync_state WHERE provider_id=?",
        arrayOf(providerId),
    ).use { c ->
        if(!c.moveToFirst()) null else {
            val qualityCounts=if((5..10).all { !c.isNull(it) }) runCatching {
                InternationalOddsQualityCounts(
                    receivedMatches=c.getInt(5),acceptedMatches=c.getInt(6),rejectedMatches=c.getInt(7),
                    staleMatches=c.getInt(8),duplicateMatches=c.getInt(9),unverifiedMatches=c.getInt(10),
                )
            }.getOrNull() else null
            InternationalOddsSyncState(
                providerId,
                runCatching { InternationalOddsStatus.valueOf(c.getString(0)) }.getOrDefault(InternationalOddsStatus.ERROR),
                c.getLong(1),if(c.isNull(2)) null else c.getLong(2),c.getInt(3),if(c.isNull(4)) null else c.getString(4),
                qualityCounts,
            )
        }
    }
    fun internationalOddsSyncHistory(limit:Int=50,providerId:String=MOVIODDS_PROVIDER_ID):List<InternationalOddsSyncAttempt> = readableDatabase.rawQuery(
        "SELECT checked_at,status,quality_received,quality_accepted,quality_rejected,quality_stale,quality_duplicates,quality_unverified,detail FROM international_odds_sync_attempts WHERE provider_id=? ORDER BY checked_at DESC,id DESC LIMIT ?",
        arrayOf(providerId,limit.coerceIn(1,500).toString()),
    ).use { c ->
        buildList {
            while(c.moveToNext()) {
                val qualityCounts=if((2..7).all { !c.isNull(it) }) runCatching {
                    InternationalOddsQualityCounts(
                        receivedMatches=c.getInt(2),acceptedMatches=c.getInt(3),rejectedMatches=c.getInt(4),
                        staleMatches=c.getInt(5),duplicateMatches=c.getInt(6),unverifiedMatches=c.getInt(7),
                    )
                }.getOrNull() else null
                add(
                    InternationalOddsSyncAttempt(
                        checkedAtMillis=c.getLong(0),
                        status=runCatching { InternationalOddsStatus.valueOf(c.getString(1)) }.getOrDefault(InternationalOddsStatus.ERROR),
                        qualityCounts=qualityCounts,
                        detail=if(c.isNull(8)) null else c.getString(8),
                    ),
                )
            }
        }
    }
    fun saveInternationalOddsCapture(
        checkedAtMillis:Long,
        status:InternationalOddsStatus,
        snapshots:List<InternationalOddsSnapshot>,
        detail:String?,
        replaceCurrentBoard:Boolean=false,
        qualityCounts:InternationalOddsQualityCounts?=null,
        providerId:String=MOVIODDS_PROVIDER_ID,
    ) {
        require(qualityCounts==null||qualityCounts.acceptedMatches==snapshots.size)
        require(snapshots.all { it.providerId==providerId })
        val db=writableDatabase
        beginWriteTransaction(db)
        try {
            val previousCurrent=if(replaceCurrentBoard) {
                readInternationalOdds("international_odds_current","WHERE provider_id=?",arrayOf(providerId),5_000)
                    .associateBy(InternationalOddsRecordKey::from)
            } else null
            if(replaceCurrentBoard) db.delete("international_odds_current","provider_id=?",arrayOf(providerId))
            snapshots.forEach { snapshot ->
                val values=ContentValues().apply {
                    put("provider_id",snapshot.providerId);put("bookmaker_id",snapshot.bookmakerId);put("event_id",snapshot.eventId)
                    if(snapshot.matchId==null) putNull("match_id") else put("match_id",snapshot.matchId)
                    if(snapshot.matchLinkBasis==null) putNull("match_link_basis") else put("match_link_basis",snapshot.matchLinkBasis.name)
                    put("source_url",snapshot.sourceUrl);put("league",snapshot.league)
                    if(snapshot.region==null) putNull("region") else put("region",snapshot.region)
                    put("captured_at",snapshot.capturedAtMillis);put("provider_polled_at",snapshot.providerPolledAtMillis)
                    put("kickoff",snapshot.kickoffMillis);put("market",snapshot.market)
                    put("home_team",snapshot.homeTeam);put("away_team",snapshot.awayTeam)
                    put("home_odds",snapshot.home);put("draw_odds",snapshot.draw);put("away_odds",snapshot.away)
                    put("home_price_changed_at",snapshot.homePriceChangedAtMillis)
                    put("draw_price_changed_at",snapshot.drawPriceChangedAtMillis)
                    put("away_price_changed_at",snapshot.awayPriceChangedAtMillis)
                    put("source_market_key",snapshot.sourceMarketKey)
                    put("quote_side",snapshot.quoteSide.name)
                    if(snapshot.homeBetLimit==null) putNull("home_bet_limit") else put("home_bet_limit",snapshot.homeBetLimit)
                    if(snapshot.drawBetLimit==null) putNull("draw_bet_limit") else put("draw_bet_limit",snapshot.drawBetLimit)
                    if(snapshot.awayBetLimit==null) putNull("away_bet_limit") else put("away_bet_limit",snapshot.awayBetLimit)
                }
                val recordKey=InternationalOddsRecordKey.from(snapshot)
                val previous=previousCurrent?.get(recordKey)
                    ?: if(previousCurrent==null) readInternationalOdds(
                        "international_odds_current",
                        "WHERE provider_id=? AND bookmaker_id=? AND event_id=? AND market=? AND source_market_key=? AND quote_side=?",
                        arrayOf(snapshot.providerId,snapshot.bookmakerId,snapshot.eventId,snapshot.market,snapshot.sourceMarketKey,snapshot.quoteSide.name),1,
                    ).firstOrNull() else null
                val priceOrSourceTimestampChanged=previous==null||
                    previous.home!=snapshot.home||previous.draw!=snapshot.draw||previous.away!=snapshot.away||
                    previous.homePriceChangedAtMillis!=snapshot.homePriceChangedAtMillis||
                    previous.drawPriceChangedAtMillis!=snapshot.drawPriceChangedAtMillis||
                    previous.awayPriceChangedAtMillis!=snapshot.awayPriceChangedAtMillis||
                    previous.homeBetLimit!=snapshot.homeBetLimit||previous.drawBetLimit!=snapshot.drawBetLimit||
                    previous.awayBetLimit!=snapshot.awayBetLimit
                if(priceOrSourceTimestampChanged) {
                    db.insertWithOnConflict("international_odds_snapshots",null,values,SQLiteDatabase.CONFLICT_IGNORE)
                }
                db.insertWithOnConflict("international_odds_current",null,values,SQLiteDatabase.CONFLICT_REPLACE)
            }
            val previous=internationalOddsSyncState(providerId)
            val lastSuccess=if(status in setOf(InternationalOddsStatus.LIVE,InternationalOddsStatus.STALE,InternationalOddsStatus.UNAVAILABLE)) checkedAtMillis else previous?.lastSuccessAtMillis
            val state=ContentValues().apply {
                put("provider_id",providerId);put("status",status.name);put("last_checked",checkedAtMillis)
                if(lastSuccess==null) putNull("last_success") else put("last_success",lastSuccess)
                put("fresh_records_stored",snapshots.size)
                if(detail==null) putNull("detail") else put("detail",detail.take(500))
                if(qualityCounts==null) {
                    listOf("quality_received","quality_accepted","quality_rejected","quality_stale","quality_duplicates","quality_unverified")
                        .forEach { putNull(it) }
                } else {
                    put("quality_received",qualityCounts.receivedMatches);put("quality_accepted",qualityCounts.acceptedMatches)
                    put("quality_rejected",qualityCounts.rejectedMatches);put("quality_stale",qualityCounts.staleMatches)
                    put("quality_duplicates",qualityCounts.duplicateMatches);put("quality_unverified",qualityCounts.unverifiedMatches)
                }
            }
            db.insertWithOnConflict("international_odds_sync_state",null,state,SQLiteDatabase.CONFLICT_REPLACE)
            val attempt=ContentValues().apply {
                put("provider_id",providerId);put("checked_at",checkedAtMillis);put("status",status.name)
                if(qualityCounts==null) {
                    listOf("quality_received","quality_accepted","quality_rejected","quality_stale","quality_duplicates","quality_unverified")
                        .forEach { putNull(it) }
                } else {
                    put("quality_received",qualityCounts.receivedMatches);put("quality_accepted",qualityCounts.acceptedMatches)
                    put("quality_rejected",qualityCounts.rejectedMatches);put("quality_stale",qualityCounts.staleMatches)
                    put("quality_duplicates",qualityCounts.duplicateMatches);put("quality_unverified",qualityCounts.unverifiedMatches)
                }
                if(detail==null) putNull("detail") else put("detail",detail.take(500))
            }
            db.insert("international_odds_sync_attempts",null,attempt)
            db.delete("international_odds_snapshots","captured_at<?",arrayOf((checkedAtMillis-30L*24*60*60*1000).toString()))
            db.delete("international_odds_current","kickoff<=? OR captured_at<?",arrayOf(checkedAtMillis.toString(),(checkedAtMillis-7L*24*60*60*1000).toString()))
            db.delete("international_odds_sync_attempts","checked_at<?",arrayOf((checkedAtMillis-30L*24*60*60*1000).toString()))
            db.setTransactionSuccessful()
        } finally { endWriteTransaction(db) }
    }
    fun freshInternationalOdds(nowMillis:Long=System.currentTimeMillis(),providerId:String?=null):List<InternationalOddsSnapshot> {
        val providerClause=if(providerId==null) "" else "provider_id=? AND "
        val args=buildList {
            if(providerId!=null) add(providerId)
            add((nowMillis-INTERNATIONAL_ODDS_FRESHNESS_MILLIS).toString())
            add(nowMillis.toString())
            add((nowMillis-INTERNATIONAL_ODDS_FRESHNESS_MILLIS).toString())
            add((nowMillis+60_000L).toString())
            add(nowMillis.toString())
        }.toTypedArray()
        return readInternationalOdds(
            "international_odds_current",
            "WHERE ${providerClause}captured_at>=? AND captured_at<=? AND provider_polled_at>=? AND provider_polled_at<=? AND kickoff>?",
            args,5_000,
        ).sortedBy { it.kickoffMillis }
    }
    fun internationalOddsHistory(limit:Int=500,providerId:String?=null):List<InternationalOddsSnapshot> =
        readInternationalOdds(
            "international_odds_snapshots",
            if(providerId==null) "" else "WHERE provider_id=?",
            if(providerId==null) emptyArray() else arrayOf(providerId),
            limit.coerceIn(1,5_000),
        )
    private fun readInternationalOdds(table:String,where:String,args:Array<String>,limit:Int):List<InternationalOddsSnapshot> {
        val result=mutableListOf<InternationalOddsSnapshot>()
        readableDatabase.rawQuery(
            "SELECT provider_id,bookmaker_id,event_id,source_url,league,region,captured_at,provider_polled_at,kickoff,market,home_team,away_team,home_odds,draw_odds,away_odds,home_price_changed_at,draw_price_changed_at,away_price_changed_at,match_id,match_link_basis,source_market_key,quote_side,home_bet_limit,draw_bet_limit,away_bet_limit FROM $table $where ORDER BY captured_at DESC LIMIT ?",
            args+limit.toString(),
        ).use { c -> while(c.moveToNext()) result+=c.toInternationalOddsSnapshot() }
        return result
    }
    private fun Cursor.toInternationalOddsSnapshot()=InternationalOddsSnapshot(
        getString(0),getString(1),getString(2),getString(3),getString(4),if(isNull(5)) null else getString(5),
        getLong(6),getLong(7),getLong(8),getString(9),getString(10),getString(11),getDouble(12),getDouble(13),getDouble(14),
        getLong(15),getLong(16),getLong(17),if(isNull(18)) null else getInt(18),
        InternationalMatchLinkBasis.fromStorage(if(isNull(19)) null else getString(19)),
        getString(20),runCatching { InternationalOddsQuoteSide.valueOf(getString(21)) }.getOrDefault(InternationalOddsQuoteSide.BACK),
        if(isNull(22)) null else getDouble(22),if(isNull(23)) null else getDouble(23),if(isNull(24)) null else getDouble(24),
    )
    /** Relinks saved market rows after football sync and records the precision used by the link. */
    fun relinkUnmatchedInternationalOdds(nowMillis:Long=System.currentTimeMillis()):Int {
        val fixtures=matchIdentities()
        if(fixtures.isEmpty()) return 0
        val rows=listOf("international_odds_current","international_odds_snapshots").flatMap { table ->
            readInternationalOdds(
                table,"WHERE (match_id IS NULL OR match_link_basis='UNIQUE_DATE_ONLY') AND captured_at>=? AND kickoff>?",
                arrayOf((nowMillis-30L*24*60*60*1000).toString(),nowMillis.toString()),5_000,
            )
        }
        data class Link(val providerId:String,val bookmakerId:String,val eventId:String,val matchId:Int,val basis:InternationalMatchLinkBasis)
        val links=rows.mapNotNull { snapshot ->
            uniqueInternationalFixtureLink(snapshot.homeTeam,snapshot.awayTeam,snapshot.kickoffMillis,fixtures)
                ?.let { Link(snapshot.providerId,snapshot.bookmakerId,snapshot.eventId,it.matchId,it.basis) }
        }.distinctBy { Triple(it.providerId,it.bookmakerId,it.eventId) }
        if(links.isEmpty()) return 0
        val db=writableDatabase
        beginWriteTransaction(db)
        try {
            links.forEach { link ->
                val values=ContentValues().apply {
                    put("match_id",link.matchId)
                    put("match_link_basis",link.basis.name)
                }
                arrayOf("international_odds_current","international_odds_snapshots").forEach { table ->
                    db.update(table,values,"provider_id=? AND bookmaker_id=? AND event_id=? AND (match_id IS NULL OR match_link_basis='UNIQUE_DATE_ONLY')",arrayOf(link.providerId,link.bookmakerId,link.eventId))
                }
            }
            db.setTransactionSuccessful()
        } finally { endWriteTransaction(db) }
        return links.size
    }
    fun saveMarketReferenceCapture(
        checkedAtMillis:Long,
        status:MarketReferenceStatus,
        snapshots:List<MarketReferenceSnapshot>,
        methodologyVersion:String?,
        detail:String?,
    ) = StoreWriteCoordinator.withLock {
        val db=writableDatabase
        db.beginTransaction()
        try {
            snapshots.forEach { snapshot ->
                val values=ContentValues().apply {
                    put("provider_id",snapshot.providerId);put("external_event_id",snapshot.externalEventId)
                    if(snapshot.matchId==null) putNull("match_id") else put("match_id",snapshot.matchId)
                    put("source_url",snapshot.sourceUrl);put("captured_at",snapshot.capturedAtMillis)
                    put("fair_read_at",snapshot.fairReadAtMillis);put("kickoff",snapshot.kickoffMillis)
                    put("market",snapshot.market);put("home_team",snapshot.homeTeam);put("away_team",snapshot.awayTeam)
                    put("home_key",az.simplesoft.footballintelligence.data.TeamNameIdentity.canonical(snapshot.homeTeam))
                    put("away_key",az.simplesoft.footballintelligence.data.TeamNameIdentity.canonical(snapshot.awayTeam))
                    put("home_fair_odds",snapshot.homeFairOdds);put("draw_fair_odds",snapshot.drawFairOdds);put("away_fair_odds",snapshot.awayFairOdds)
                    put("home_probability",snapshot.homeProbability);put("draw_probability",snapshot.drawProbability);put("away_probability",snapshot.awayProbability)
                    put("fair_source",snapshot.fairSource);put("methodology_version",snapshot.methodologyVersion)
                }
                db.insertWithOnConflict("market_reference_snapshots",null,values,SQLiteDatabase.CONFLICT_IGNORE)
            }
            val old=marketReferenceSyncState()
            val values=ContentValues().apply {
                put("provider_id",TIPSAUDIT_PROVIDER_ID);put("status",status.name);put("last_checked",checkedAtMillis)
                val successfulCheck=if(status!=MarketReferenceStatus.ERROR) checkedAtMillis else old?.lastSuccessAtMillis
                if(successfulCheck==null) putNull("last_success") else put("last_success",successfulCheck)
                put("fresh_records_stored",snapshots.size)
                if(methodologyVersion==null) putNull("methodology_version") else put("methodology_version",methodologyVersion)
                if(detail==null) putNull("detail") else put("detail",detail.take(500))
            }
            db.insertWithOnConflict("market_reference_sync_state",null,values,SQLiteDatabase.CONFLICT_REPLACE)
            db.delete("market_reference_snapshots","captured_at<?",arrayOf((checkedAtMillis-30L*24*60*60*1000).toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun marketReferenceSyncState():MarketReferenceSyncState? = readableDatabase.rawQuery(
        "SELECT status,last_checked,last_success,fresh_records_stored,methodology_version,detail FROM market_reference_sync_state WHERE provider_id=?",
        arrayOf(TIPSAUDIT_PROVIDER_ID),
    ).use { c ->
        if(!c.moveToFirst()) null else MarketReferenceSyncState(
            TIPSAUDIT_PROVIDER_ID,
            runCatching { MarketReferenceStatus.valueOf(c.getString(0)) }.getOrDefault(MarketReferenceStatus.ERROR),
            c.getLong(1),if(c.isNull(2)) null else c.getLong(2),c.getInt(3),
            if(c.isNull(4)) null else c.getString(4),if(c.isNull(5)) null else c.getString(5),
        )
    }
    fun freshMarketReferences(nowMillis:Long=System.currentTimeMillis()):List<MarketReferenceSnapshot> {
        val result=mutableListOf<MarketReferenceSnapshot>()
        readableDatabase.rawQuery(
            "SELECT external_event_id,match_id,source_url,captured_at,fair_read_at,kickoff,market,home_team,away_team,home_fair_odds,draw_fair_odds,away_fair_odds,home_probability,draw_probability,away_probability,fair_source,methodology_version FROM market_reference_snapshots WHERE provider_id=? AND captured_at>=? AND captured_at<=? AND fair_read_at>=? AND fair_read_at<=? AND kickoff>? AND fair_source='live' ORDER BY kickoff",
            arrayOf(TIPSAUDIT_PROVIDER_ID,(nowMillis-MARKET_REFERENCE_FRESHNESS_MILLIS).toString(),nowMillis.toString(),(nowMillis-MARKET_REFERENCE_FRESHNESS_MILLIS).toString(),nowMillis.toString(),nowMillis.toString()),
        ).use { c ->
            while(c.moveToNext()) result+=MarketReferenceSnapshot(
                TIPSAUDIT_PROVIDER_ID,c.getString(0),if(c.isNull(1)) null else c.getInt(1),c.getString(2),c.getLong(3),c.getLong(4),c.getLong(5),c.getString(6),c.getString(7),c.getString(8),
                c.getDouble(9),c.getDouble(10),c.getDouble(11),c.getDouble(12),c.getDouble(13),c.getDouble(14),c.getString(15),c.getString(16),
            )
        }
        return result.groupBy { it.externalEventId }.values.mapNotNull { rows -> rows.maxByOrNull { it.fairReadAtMillis } }.sortedBy { it.kickoffMillis }
    }
    /** Links current market references only to one exact, same-orientation local fixture. */
    fun relinkUnmatchedMarketReferences(nowMillis:Long=System.currentTimeMillis()):Int {
        val fixtures=matchIdentities()
        if(fixtures.isEmpty()) return 0
        data class Candidate(val rowId:Long,val snapshot:MarketReferenceSnapshot)
        val candidates=mutableListOf<Candidate>()
        readableDatabase.rawQuery(
            "SELECT id,external_event_id,source_url,captured_at,fair_read_at,kickoff,market,home_team,away_team,home_fair_odds,draw_fair_odds,away_fair_odds,home_probability,draw_probability,away_probability,fair_source,methodology_version FROM market_reference_snapshots WHERE provider_id=? AND match_id IS NULL AND captured_at>=? AND fair_read_at>=? AND kickoff>?",
            arrayOf(TIPSAUDIT_PROVIDER_ID,(nowMillis-MARKET_REFERENCE_FRESHNESS_MILLIS).toString(),(nowMillis-MARKET_REFERENCE_FRESHNESS_MILLIS).toString(),nowMillis.toString()),
        ).use { c ->
            while(c.moveToNext()) {
                val rowId=c.getLong(0)
                val snapshot=MarketReferenceSnapshot(
                    TIPSAUDIT_PROVIDER_ID,c.getString(1),null,c.getString(2),c.getLong(3),c.getLong(4),c.getLong(5),c.getString(6),c.getString(7),c.getString(8),
                    c.getDouble(9),c.getDouble(10),c.getDouble(11),c.getDouble(12),c.getDouble(13),c.getDouble(14),c.getString(15),c.getString(16),
                )
                candidates+=Candidate(rowId,snapshot)
            }
        }
        val links=candidates.mapNotNull { candidate ->
            uniqueExactMarketFixtureMatchId(candidate.snapshot.homeTeam,candidate.snapshot.awayTeam,candidate.snapshot.kickoffMillis,fixtures)?.let { candidate.rowId to it }
        }
        if(links.isEmpty()) return 0
        val db=writableDatabase
        beginWriteTransaction(db)
        try {
            links.forEach { (rowId,matchId) -> db.update("market_reference_snapshots",ContentValues().apply { put("match_id",matchId) },"id=? AND match_id IS NULL",arrayOf(rowId.toString())) }
            db.setTransactionSuccessful()
        } finally { endWriteTransaction(db) }
        return links.size
    }
    fun freshBookmakerOdds(providerId:String,nowMillis:Long=System.currentTimeMillis(),freshnessMillis:Long=30L*60*1000):List<BookmakerOddsSnapshot> {
        val result=mutableListOf<BookmakerOddsSnapshot>()
        readableDatabase.rawQuery(
            "SELECT event_id,source_url,captured_at,kickoff,market,home_team,away_team,home_odds,draw_odds,away_odds,event_date,time_zone_id,match_id,event_year_inferred FROM bookmaker_odds_snapshots WHERE provider_id=? AND captured_at>=? AND captured_at<=? AND kickoff>? ORDER BY kickoff",
            arrayOf(providerId,(nowMillis-freshnessMillis).toString(),nowMillis.toString(),nowMillis.toString())
        ).use { c ->
            while(c.moveToNext()) result += BookmakerOddsSnapshot(
                providerId,c.getString(0),c.getString(1),c.getLong(2),c.getLong(3),c.getString(4),c.getString(5),c.getString(6),
                c.getDouble(7),c.getDouble(8),c.getDouble(9),c.getString(10),c.getString(11),if(c.isNull(12)) null else c.getInt(12),c.getInt(13)==1
            )
        }
        return latestBookmakerSnapshotsPerEvent(result)
    }
    /** Rechecks recent unmatched snapshots after local fixture data changes, using exact reviewed aliases. */
    fun relinkUnmatchedBookmakerOdds(nowMillis:Long=System.currentTimeMillis()):Int {
        val fixtures=matchIdentities()
        if(fixtures.isEmpty()) return 0
        data class UnmatchedSnapshot(val rowId:Long,val snapshot:BookmakerOddsSnapshot)
        val unmatched=mutableListOf<UnmatchedSnapshot>()
        readableDatabase.rawQuery(
            "SELECT id,provider_id,event_id,source_url,captured_at,kickoff,market,home_team,away_team,home_odds,draw_odds,away_odds,event_date,time_zone_id,event_year_inferred FROM bookmaker_odds_snapshots WHERE match_id IS NULL AND captured_at>=? AND kickoff>?",
            arrayOf((nowMillis-30L*24*60*60*1000).toString(),nowMillis.toString()),
        ).use { c ->
            while(c.moveToNext()) unmatched+=UnmatchedSnapshot(
                c.getLong(0),
                BookmakerOddsSnapshot(
                    c.getString(1),c.getString(2),c.getString(3),c.getLong(4),c.getLong(5),c.getString(6),c.getString(7),c.getString(8),
                    c.getDouble(9),c.getDouble(10),c.getDouble(11),c.getString(12),c.getString(13),null,c.getInt(14)==1,
                ),
            )
        }
        val links=unmatched.mapNotNull { row -> uniqueExactFixtureMatchId(row.snapshot,fixtures)?.let { row.rowId to it } }
        if(links.isEmpty()) return 0
        val db=writableDatabase
        beginWriteTransaction(db)
        try {
            links.forEach { (rowId,matchId) ->
                db.update("bookmaker_odds_snapshots",ContentValues().apply { put("match_id",matchId) },"id=? AND match_id IS NULL",arrayOf(rowId.toString()))
            }
            db.setTransactionSuccessful()
        } finally { endWriteTransaction(db) }
        return links.size
    }
    fun loggedCouponPicks():List<LoggedCouponPick> {
        val result=mutableListOf<LoggedCouponPick>()
        readableDatabase.rawQuery("SELECT p.provider_id,p.match_id,m.home,m.away,m.kickoff,p.outcome,p.model_probability,p.captured_at,m.finished,m.home_score,m.away_score FROM model_coupon_picks p LEFT JOIN matches m ON m.id=p.match_id ORDER BY p.captured_at DESC",null).use { c ->
            while(c.moveToNext()) {
                if(c.isNull(2)||c.isNull(3)) continue
                if(!TeamDataQuality.isValidFixture(c.getString(2),c.getString(3))) continue
                val kickoff=runCatching { Instant.parse(c.getString(4)).toEpochMilli() }.getOrNull() ?: continue
                result.add(LoggedCouponPick(c.getString(0),c.getInt(1),c.getString(2),c.getString(3),kickoff,c.getString(5),c.getDouble(6),c.getLong(7),c.getInt(8)==1,
                    if(c.isNull(9)) null else c.getInt(9),if(c.isNull(10)) null else c.getInt(10)))
            }
        }
        return result
    }
    fun importHistoricalResults(fileName:String, report:FootballCsvImportReport):StoredFootballCsvImport {
        val db=writableDatabase
        val importedAt=System.currentTimeMillis()
        var inserted=0
        beginWriteTransaction(db)
        try {
            val importValues=ContentValues().apply {
                put("source","Football-Data-style CSV (user-selected; provider unverified)")
                put("file_name",fileName)
                put("imported_at",importedAt)
                put("rows_read",report.rowsRead)
                put("accepted_rows",report.results.size)
                put("inserted_rows",0)
                put("duplicate_rows",0)
                put("rejected_rows",report.rejectedRows)
            }
            val importId=db.insertOrThrow("historical_imports",null,importValues)
            for (row in report.results) {
                val competitionKey=row.competitionCode.trim().lowercase(Locale.ROOT)
                fun teamKey(team:String)=team.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).joinToString(" ")
                // Source files provide a calendar date but no timezone. Make results available
                // only at UTC midnight two days later, a conservative bound across civil zones.
                val availableAt=FootballDataCsvImporter.conservativeAvailabilityMillis(row.eventDate)
                val values=ContentValues().apply {
                    put("import_id",importId)
                    put("competition_code",row.competitionCode.trim())
                    put("competition_key",competitionKey)
                    put("event_date",row.eventDate.toString())
                    put("available_at",availableAt)
                    put("home_team",row.homeTeam.trim())
                    put("away_team",row.awayTeam.trim())
                    put("home_key",teamKey(row.homeTeam))
                    put("away_key",teamKey(row.awayTeam))
                    put("home_goals",row.homeGoals)
                    put("away_goals",row.awayGoals)
                }
                if(db.insertWithOnConflict("historical_results",null,values,SQLiteDatabase.CONFLICT_IGNORE)!=-1L) inserted++
            }
            val duplicateRows=report.duplicateRows+(report.results.size-inserted)
            db.execSQL("UPDATE historical_imports SET inserted_rows=?,duplicate_rows=? WHERE id=?",arrayOf(inserted,duplicateRows,importId))
            db.setTransactionSuccessful()
            return StoredFootballCsvImport(inserted,duplicateRows)
        } finally { endWriteTransaction(db) }
    }
    fun importedCompetitionCodes():List<String> {
        val result=mutableListOf<String>()
        readableDatabase.rawQuery("SELECT competition_code FROM historical_results GROUP BY competition_key ORDER BY competition_key",null).use { c ->
            while(c.moveToNext()) result.add(c.getString(0))
        }
        return result
    }
    fun importedHistory(competitionCode:String):List<ImportedHistoryRow> {
        val result=mutableListOf<ImportedHistoryRow>()
        val key=competitionCode.trim().lowercase(Locale.ROOT)
        readableDatabase.rawQuery(
            "SELECT id,competition_code,event_date,available_at,home_team,away_team,home_goals,away_goals FROM historical_results WHERE competition_key=? ORDER BY available_at,event_date,home_key,away_key",
            arrayOf(key)
        ).use { c ->
            while(c.moveToNext()) {
                val date=runCatching { LocalDate.parse(c.getString(2)) }.getOrNull() ?: continue
                if(!TeamDataQuality.isValidFixture(c.getString(4),c.getString(5))) continue
                result.add(ImportedHistoryRow(c.getLong(0),c.getString(1),date,c.getLong(3),c.getString(4),c.getString(5),c.getInt(6),c.getInt(7)))
            }
        }
        return result
    }
    fun quotes(matchId:Int):List<Quote> {
        val result=mutableListOf<Quote>()
        readableDatabase.rawQuery("SELECT id,match_id,bookmaker,h,d,a,captured_at FROM quotes WHERE match_id=? ORDER BY captured_at DESC",arrayOf(matchId.toString())).use { c ->
            while(c.moveToNext()) result.add(Quote(c.getLong(0),c.getInt(1),c.getString(2),c.getDouble(3),c.getDouble(4),c.getDouble(5),c.getLong(6)))
        }
        return result
    }
    fun picks(matchId:Int):List<Pick> {
        val result=mutableListOf<Pick>()
        readableDatabase.rawQuery("SELECT id,match_id,source,outcome,created_at FROM picks WHERE match_id=? ORDER BY created_at DESC",arrayOf(matchId.toString())).use { c ->
            while(c.moveToNext()) result.add(Pick(c.getLong(0),c.getInt(1),c.getString(2),c.getString(3),c.getLong(4)))
        }
        return result
    }
    fun tickets():List<Ticket> {
        val result=mutableListOf<Ticket>()
        readableDatabase.rawQuery("SELECT id,match_id,outcome,price,stake,bookmaker,created_at FROM tickets ORDER BY id DESC",null).use { c ->
            while(c.moveToNext()) result.add(Ticket(c.getLong(0),c.getInt(1),c.getString(2),c.getDouble(3),c.getDouble(4),c.getString(5),c.getLong(6)))
        }
        return result
    }
    fun ticketMatches():Map<Int,Match> {
        val result=linkedMapOf<Int,Match>()
        readableDatabase.rawQuery("SELECT DISTINCT m.id,m.league,m.home,m.away,m.kickoff,m.finished,m.home_score,m.away_score,m.source_key,m.source_name,m.exact_kickoff,m.available_at,m.source_url,m.event_date,m.season,m.captured_at FROM matches m INNER JOIN tickets t ON t.match_id=m.id",null).use { c ->
            while(c.moveToNext()) result[c.getInt(0)]=Match(c.getInt(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getInt(5)==1,
                if(c.isNull(6)) null else c.getInt(6),if(c.isNull(7)) null else c.getInt(7),c.getString(8),c.getString(9),c.getInt(10)==1,
                if(c.isNull(11)) null else c.getLong(11),if(c.isNull(12)) null else c.getString(12),if(c.isNull(13)) null else c.getString(13),if(c.isNull(14)) null else c.getString(14),if(c.isNull(15)) null else c.getLong(15))
        }
        return result
    }
    fun saveQuote(matchId:Int, book:String, h:Double,d:Double,a:Double) = StoreWriteCoordinator.withLock {
        writableDatabase.execSQL("INSERT INTO quotes(match_id,bookmaker,h,d,a,captured_at) VALUES(?,?,?,?,?,?)",arrayOf(matchId,book,h,d,a,System.currentTimeMillis()))
    }
    fun savePick(matchId:Int, source:String,outcome:String) = StoreWriteCoordinator.withLock {
        writableDatabase.execSQL("INSERT INTO picks(match_id,source,outcome,created_at) VALUES(?,?,?,?)",arrayOf(matchId,source,outcome,System.currentTimeMillis()))
    }
    fun saveTicket(matchId:Int,outcome:String,price:Double,stake:Double,book:String) = StoreWriteCoordinator.withLock {
        writableDatabase.execSQL("INSERT INTO tickets(match_id,outcome,price,stake,bookmaker,created_at) VALUES(?,?,?,?,?,?)",arrayOf(matchId,outcome,price,stake,book,System.currentTimeMillis()))
    }
}
