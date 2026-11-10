package az.simplesoft.footballintelligence

import android.os.Bundle
import android.provider.OpenableColumns
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.platform.LocalContext
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.Context
import android.content.ContentValues
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import az.simplesoft.footballintelligence.analytics.MarginMethod
import az.simplesoft.footballintelligence.analytics.ProbabilityEngine
import az.simplesoft.footballintelligence.analytics.EloEngine
import az.simplesoft.footballintelligence.analytics.EloForecast
import az.simplesoft.footballintelligence.analytics.EloWalkForwardEngine
import az.simplesoft.footballintelligence.analytics.EloWalkForwardReport
import az.simplesoft.footballintelligence.analytics.CalibrationDiagnostics
import az.simplesoft.footballintelligence.analytics.CURRENT_ELO_MODEL_VERSION
import az.simplesoft.footballintelligence.analytics.HistoricalMatch
import az.simplesoft.footballintelligence.analytics.ProspectiveForecastObservation
import az.simplesoft.footballintelligence.analytics.ProspectiveForecastEvaluator
import az.simplesoft.footballintelligence.analytics.ProspectiveForecastReport
import az.simplesoft.footballintelligence.analytics.historyAvailableAt
import az.simplesoft.footballintelligence.analytics.isProspectiveForecastEligible
import az.simplesoft.footballintelligence.analytics.conservativeResultAvailability
import az.simplesoft.footballintelligence.analytics.ForecastedMatch
import az.simplesoft.footballintelligence.analytics.VirtualCouponCandidate
import az.simplesoft.footballintelligence.analytics.VirtualCouponBuilder
import az.simplesoft.footballintelligence.analytics.LoggedCouponPick
import az.simplesoft.footballintelligence.analytics.ThreeWayDecimalOdds
import az.simplesoft.footballintelligence.analytics.WalkForwardTarget
import az.simplesoft.footballintelligence.analytics.AccumulatorEngine
import az.simplesoft.footballintelligence.analytics.AccumulatorMatchResult
import az.simplesoft.footballintelligence.analytics.VirtualAccumulatorHistoryEntry
import az.simplesoft.footballintelligence.analytics.AccumulatorSettlementState
import az.simplesoft.footballintelligence.analytics.AccumulatorPerformanceEngine
import az.simplesoft.footballintelligence.analytics.SettledSinglePrediction
import az.simplesoft.footballintelligence.analytics.CouponSelectionBasis
import az.simplesoft.footballintelligence.analytics.TeamDataQuality
import az.simplesoft.footballintelligence.analytics.isFreshQuote
import az.simplesoft.footballintelligence.data.FootballDataCsvImporter
import az.simplesoft.footballintelligence.data.FootballCsvImportReport
import az.simplesoft.footballintelligence.data.StoredFootballCsvImport
import az.simplesoft.footballintelligence.data.ImportedHistoryRow
import az.simplesoft.footballintelligence.data.KickoffPrecision
import az.simplesoft.footballintelligence.data.ProviderMatch
import az.simplesoft.footballintelligence.data.ProviderSyncSummary
import az.simplesoft.footballintelligence.data.ProviderFileCache
import az.simplesoft.footballintelligence.data.OpenLigaLeagueState
import az.simplesoft.footballintelligence.data.MisliOddsProvider
import az.simplesoft.footballintelligence.data.SeasonDateValidity
import az.simplesoft.footballintelligence.data.TopazOddsProvider
import az.simplesoft.footballintelligence.data.FixtureDeduplication
import az.simplesoft.footballintelligence.data.BookmakerOddsSnapshot
import az.simplesoft.footballintelligence.data.BookmakerProviderStatus
import az.simplesoft.footballintelligence.data.OddsProviderStatus
import az.simplesoft.footballintelligence.data.visibleBookmakerStatus
import az.simplesoft.footballintelligence.data.latestBookmakerSnapshotsPerEvent
import az.simplesoft.footballintelligence.data.MisliVisiblePage
import az.simplesoft.footballintelligence.data.FixtureIdentity
import az.simplesoft.footballintelligence.data.uniqueExactFixtureMatchId
import androidx.work.WorkInfo
import androidx.work.WorkManager

data class Match(val id: Int, val league: String, val home: String, val away: String,
                 val kickoff: String, val finished: Boolean, val homeScore: Int?, val awayScore: Int?,
                 val sourceKey: String = "openligadb:$id", val sourceName: String = "OpenLigaDB",
                 val exactKickoff: Boolean = true, val availableAtMillis: Long? = null,
                 val sourceUrl: String? = null, val eventDate: String? = null, val season: String? = null,
                 val capturedAtMillis: Long? = null)
data class Quote(val id: Long, val matchId: Int, val bookmaker: String,
                 val h: Double, val d: Double, val a: Double, val capturedAt: Long)
data class Pick(val id: Long, val matchId: Int, val source: String, val outcome: String, val timestamp: Long)
data class Ticket(val id: Long, val matchId: Int, val outcome: String, val price: Double,
                  val stake: Double, val bookmaker: String, val createdAt: Long)
data class BookmakerSyncState(
    val providerId:String,
    val status:OddsProviderStatus,
    val lastCheckedAtMillis:Long?,
    val lastSuccessAtMillis:Long?,
    val recordsStored:Int,
    val error:String?,
)
fun labelOutcome(outcome: String) = when(outcome) { "H" -> "П1"; "D" -> "X"; else -> "П2" }
fun niceTime(raw: String): String = try {
    val instant = Instant.parse(raw)
    DateTimeFormatter.ofPattern("dd.MM HH:mm", Locale("ru"))
        .withZone(ZoneId.systemDefault()).format(instant)
} catch (_: Exception) { raw.take(16) }
fun niceMatchTime(match:Match):String {
    if(match.exactKickoff) return niceTime(match.kickoff)
    val sourceDate=match.eventDate ?: match.kickoff.take(10)
    return runCatching { "${DateTimeFormatter.ofPattern("dd.MM.yyyy").format(LocalDate.parse(sourceDate))} · время не указано" }
        .getOrDefault("Дата матча · точное время не указано")
}
fun scoreResult(m: Match): String? = if (!m.finished || m.homeScore == null || m.awayScore == null) null
    else if (m.homeScore > m.awayScore) "H" else if (m.homeScore < m.awayScore) "A" else "D"
fun matchStatusLabel(m:Match,nowMillis:Long=System.currentTimeMillis()):String {
    if(m.finished) return "Завершён: ${m.homeScore ?: "?"} : ${m.awayScore ?: "?"}"
    val now=Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault())
    val past=if(m.exactKickoff) {
        runCatching { Instant.parse(m.kickoff).toEpochMilli()<=nowMillis }.getOrDefault(false)
    } else {
        val date=m.eventDate ?: m.kickoff.take(10)
        runCatching { LocalDate.parse(date).isBefore(now.toLocalDate()) }.getOrDefault(false)
    }
    return if(past) "Матч прошёл · итоговый счёт не получен" else "Ожидается · Нажми для анализа"
}
fun fairProbabilities(q: Quote, method: MarginMethod = MarginMethod.BASIC): Triple<Double, Double, Double> {
    val p = ProbabilityEngine.removeMargin(q.h, q.d, q.a, method)
    return Triple(p.home, p.draw, p.away)
}

class Store(context: Context, databaseName:String="football_intelligence.db") : SQLiteOpenHelper(context, databaseName, null, 11) {
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
        db.execSQL("CREATE TABLE IF NOT EXISTS virtual_accumulator_legs (id INTEGER PRIMARY KEY AUTOINCREMENT, accumulator_id INTEGER NOT NULL, match_id INTEGER NOT NULL, home_team TEXT NOT NULL, away_team TEXT NOT NULL, scheduled_kickoff INTEGER NOT NULL, outcome TEXT NOT NULL, model_probability REAL NOT NULL, bookmaker_decimal_odds REAL, bookmaker_odds_captured_at INTEGER, UNIQUE(accumulator_id,match_id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS virtual_accumulator_legs_match_idx ON virtual_accumulator_legs(match_id,accumulator_id)")
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
    fun upsert(matches: List<Match>) {
        val db = writableDatabase
        db.beginTransaction()
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
        } finally { db.endTransaction() }
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
        db.beginTransaction()
        try {
            val expires=db.rawQuery("SELECT expires_at FROM provider_sync_lock WHERE id=1",null).use { c -> if(c.moveToFirst()) c.getLong(0) else 0L }
            if(expires>nowMillis) return false
            val values=ContentValues().apply { put("id",1);put("expires_at",nowMillis+leaseMillis) }
            db.insertWithOnConflict("provider_sync_lock",null,values,SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }
    fun releaseProviderSyncLock() { writableDatabase.delete("provider_sync_lock","id=1",null) }
    fun saveProviderSync(summary:ProviderSyncSummary) {
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
    }
    fun providerFileCache(cacheKey:String):ProviderFileCache? = readableDatabase.rawQuery("SELECT etag,last_modified,last_checked,record_count,cached_body FROM provider_file_cache WHERE cache_key=?",arrayOf(cacheKey)).use { c ->
        if(!c.moveToFirst()) null else ProviderFileCache(if(c.isNull(0)) null else c.getString(0),if(c.isNull(1)) null else c.getString(1),c.getLong(2),c.getInt(3),if(c.isNull(4)) null else c.getString(4))
    }
    fun saveProviderFileCache(cacheKey:String,providerId:String,etag:String?,lastModified:String?,checkedAt:Long,recordCount:Int,cachedBody:String?=null) {
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
    }
    fun openLigaState(leagueKey:String):OpenLigaLeagueState? = readableDatabase.rawQuery("SELECT source_change,current_group_order,last_full_sync,checked_at,record_count FROM provider_league_state WHERE league_key=?",arrayOf(leagueKey)).use { c ->
        if(!c.moveToFirst()) null else OpenLigaLeagueState(if(c.isNull(0)) null else c.getString(0),if(c.isNull(1)) null else c.getInt(1),c.getLong(2),c.getLong(3),c.getInt(4))
    }
    fun saveOpenLigaState(leagueKey:String,sourceChange:String?,currentGroupOrderId:Int?,fullSyncAt:Long,checkedAt:Long,recordCount:Int) {
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
    fun automaticWalkForwardReport():EloWalkForwardReport {
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
        val reports=byLeague.values.map { rows ->
            EloWalkForwardEngine.evaluate(rows.map { it.history },rows.map { it.target })
        }
        val predictions=reports.flatMap { it.predictions }
        return EloWalkForwardReport(
            targetCount=reports.sumOf { it.targetCount },
            forecastCount=predictions.size,
            skippedForInsufficientHistory=reports.sumOf { it.skippedForInsufficientHistory },
            meanBrierScore=predictions.map { it.brierScore }.average().takeIf { predictions.isNotEmpty() },
            meanLogLoss=predictions.map { it.logLoss }.average().takeIf { predictions.isNotEmpty() },
            predictions=predictions,
        )
    }
    fun recordProspectiveForecastSnapshots(
        forecasts:List<ForecastedMatch>,
        nowMillis:Long=System.currentTimeMillis(),
    ):Int {
        if(forecasts.isEmpty()) return 0
        val db=writableDatabase
        var inserted=0
        db.beginTransaction()
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
            db.endTransaction()
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
        db.beginTransaction()
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
            db.endTransaction()
        }
        return inserted
    }
    fun prospectiveForecastHistory(limit:Int=100):List<ProspectiveForecastObservation> {
        val result=mutableListOf<ProspectiveForecastObservation>()
        readableDatabase.rawQuery(
            "SELECT s.id,s.model_version,s.match_id,s.competition,s.home_team,s.away_team,s.forecasted_at,s.kickoff_at," +
                "s.history_match_count,s.home_probability,s.draw_probability,s.away_probability," +
                "r.actual_outcome,r.home_goals,r.away_goals,r.settled_at,r.result_available_at,r.result_source_name,r.result_source_url " +
                "FROM prospective_forecast_snapshots s LEFT JOIN prospective_forecast_settlements r ON r.snapshot_id=s.id " +
                "ORDER BY s.forecasted_at DESC,s.id DESC LIMIT ?",
            arrayOf(limit.coerceIn(1,500).toString()),
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
        db.beginTransaction()
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
        } finally { db.endTransaction() }
        return inserted
    }
    fun recordAccumulatorDrafts(candidates:List<VirtualCouponCandidate>, nowMillis:Long=System.currentTimeMillis()):Int {
        val db=writableDatabase
        var inserted=0
        db.beginTransaction()
        try {
            for(candidate in candidates) {
                val size=candidate.requestedLegCount
                val bounds=candidate.jointProbabilityBounds ?: continue
                if(size !in VirtualCouponBuilder.supportedAccumulatorSizes || candidate.picks.size!=size || candidate.picks.any { it.kickoffMillis<=nowMillis }) continue
                val signature=candidate.selectionBasis.name+":"+candidate.picks.joinToString(";") { pick ->
                    listOf(pick.matchId,pick.outcome,pick.modelProbability.toBits(),pick.bookmakerDecimalOdds?.toBits()).joinToString(":")
                }
                val values=ContentValues().apply {
                    put("provider_id",candidate.providerId);put("leg_count",size);put("captured_at",nowMillis)
                    put("snapshot_key",signature);put("strategy_version","elo-max-1x2-frechet-v1")
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
                    }
                    db.insertOrThrow("virtual_accumulator_legs",null,leg)
                }
                inserted++
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
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
                "SELECT l.match_id,l.home_team,l.away_team,l.scheduled_kickoff,l.outcome,l.model_probability,l.bookmaker_decimal_odds,l.bookmaker_odds_captured_at,m.kickoff,m.finished,m.home_score,m.away_score FROM virtual_accumulator_legs l LEFT JOIN matches m ON m.id=l.match_id WHERE l.accumulator_id=? ORDER BY l.scheduled_kickoff",
                arrayOf(header.id.toString()),
            ).use { c ->
                while(c.moveToNext()) {
                    val matchId=c.getInt(0)
                    picks+=az.simplesoft.footballintelligence.analytics.VirtualCouponPick(
                        matchId,c.getString(1),c.getString(2),c.getLong(3),c.getString(4),c.getDouble(5),
                        if(c.isNull(6)) null else c.getDouble(6),if(c.isNull(7)) null else c.getLong(7),
                    )
                    if(!c.isNull(8)) {
                        val actualKickoff=runCatching { Instant.parse(c.getString(8)).toEpochMilli() }.getOrNull()
                        if(actualKickoff!=null) results[matchId]=AccumulatorMatchResult(
                            matchId,actualKickoff,c.getInt(9)==1,if(c.isNull(10)) null else c.getInt(10),if(c.isNull(11)) null else c.getInt(11),
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
        readableDatabase.rawQuery("SELECT id,home,away,kickoff FROM matches WHERE finished=0 AND exact_kickoff=1",null).use { c ->
            while(c.moveToNext()) {
                val kickoff=runCatching { Instant.parse(c.getString(3)).toEpochMilli() }.getOrNull() ?: continue
                if(TeamDataQuality.isValidFixture(c.getString(1),c.getString(2))) result += FixtureIdentity(c.getInt(0),c.getString(1),c.getString(2),kickoff)
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
        db.beginTransaction()
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
        } finally { db.endTransaction() }
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
        db.beginTransaction()
        try {
            links.forEach { (rowId,matchId) ->
                db.update("bookmaker_odds_snapshots",ContentValues().apply { put("match_id",matchId) },"id=? AND match_id IS NULL",arrayOf(rowId.toString()))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
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
        db.beginTransaction()
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
        } finally { db.endTransaction() }
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
    fun saveQuote(matchId:Int, book:String, h:Double,d:Double,a:Double) =
        writableDatabase.execSQL("INSERT INTO quotes(match_id,bookmaker,h,d,a,captured_at) VALUES(?,?,?,?,?,?)",arrayOf(matchId,book,h,d,a,System.currentTimeMillis()))
    fun savePick(matchId:Int, source:String,outcome:String) =
        writableDatabase.execSQL("INSERT INTO picks(match_id,source,outcome,created_at) VALUES(?,?,?,?)",arrayOf(matchId,source,outcome,System.currentTimeMillis()))
    fun saveTicket(matchId:Int,outcome:String,price:Double,stake:Double,book:String) =
        writableDatabase.execSQL("INSERT INTO tickets(match_id,outcome,price,stake,bookmaker,created_at) VALUES(?,?,?,?,?,?)",arrayOf(matchId,outcome,price,stake,book,System.currentTimeMillis()))
}

class MainActivity:ComponentActivity() {
    private lateinit var store:Store
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        store=Store(this)
        FootballSyncScheduler.scheduleOnAppOpen(this)
        setContent { FootballScreen(store) }
    }
    override fun onDestroy() {
        store.close()
        super.onDestroy()
    }
}

/**
 * Reads only the visible rendered content of Misli's public football page. The WebView uses the
 * ordinary Android user agent and never visits account, ticket, API, or live-detail paths.
 */
@Composable private fun MisliPublicPageCollector(store:Store,onCaptured:()->Unit) {
    val activity=LocalContext.current as ComponentActivity
    val latestOnCaptured by rememberUpdatedState(onCaptured)
    val previousCheck=remember(store) { store.bookmakerSyncState("misli")?.lastCheckedAtMillis }
    val now=System.currentTimeMillis()
    val shouldLoad=previousCheck==null || now-previousCheck>=30L*60*1000
    if(!shouldLoad) return
    val parser=remember { MisliOddsProvider() }
    val handler=remember { Handler(Looper.getMainLooper()) }
    val webView=remember(activity) {
        WebView(activity).apply {
            settings.javaScriptEnabled=true
            settings.domStorageEnabled=true
            webViewClient=object:WebViewClient() {
                private var capturePending=false
                override fun onPageFinished(view:WebView,url:String) {
                    val uri=runCatching { java.net.URI(url) }.getOrNull()
                    if(uri==null || uri.host !in setOf("misli.az","www.misli.az") || uri.path!="/idman-novleri/futbol") return
                    handler.removeCallbacksAndMessages(null)
                    capturePending=true
                    handler.postDelayed({
                        if(!capturePending) return@postDelayed
                        view.evaluateJavascript(
                            "JSON.stringify({title:document.title,url:location.href,text:(document.body?.innerText||'').slice(0,100000),timeZoneId:Intl.DateTimeFormat().resolvedOptions().timeZone||'UTC'})"
                        ) { encoded ->
                            capturePending=false
                            val checkedAt=System.currentTimeMillis()
                            try {
                                val decoded=org.json.JSONTokener(encoded ?: "null").nextValue()
                                val json=when(decoded) {
                                    is org.json.JSONObject -> decoded
                                    is String -> org.json.JSONObject(decoded)
                                    else -> error("Страница не вернула видимые данные.")
                                }
                                val page=MisliVisiblePage(
                                    url=json.optString("url"),title=json.optString("title"),
                                    visibleText=json.optString("text"),timeZoneId=json.optString("timeZoneId","UTC")
                                )
                                Thread {
                                    try {
                                        val result=parser.parseVisiblePage(page,checkedAt,store.matchIdentities())
                                        val status=if(result.snapshots.isEmpty()) OddsProviderStatus.UNAVAILABLE else OddsProviderStatus.LIVE
                                        store.saveBookmakerCapture("misli",checkedAt,status,result.snapshots,result.detail)
                                    } catch(error:Exception) {
                                        store.saveBookmakerCapture("misli",checkedAt,OddsProviderStatus.ERROR,emptyList(),error.message ?: "Ошибка разбора публичной страницы.")
                                    }
                                    activity.runOnUiThread {
                                        latestOnCaptured()
                                        handler.postDelayed({ if(!activity.isFinishing) view.reload() },30L*60*1000)
                                    }
                                }.start()
                            } catch(error:Exception) {
                                store.saveBookmakerCapture("misli",checkedAt,OddsProviderStatus.ERROR,emptyList(),error.message ?: "Не удалось прочитать видимый текст.")
                                latestOnCaptured()
                                handler.postDelayed({ if(!activity.isFinishing) view.reload() },30L*60*1000)
                            }
                        }
                    },4_000)
                }

                override fun shouldOverrideUrlLoading(view:WebView,url:String):Boolean {
                    val uri=runCatching { java.net.URI(url) }.getOrNull()
                    return uri==null || uri.scheme!="https" || uri.host !in setOf("misli.az","www.misli.az")
                }
            }
            loadUrl("https://www.misli.az/idman-novleri/futbol")
        }
    }
    androidx.compose.runtime.DisposableEffect(webView) {
        onDispose {
            handler.removeCallbacksAndMessages(null)
            webView.stopLoading()
            webView.destroy()
        }
    }
    AndroidView(factory={webView},modifier=Modifier.size(1.dp).alpha(0.01f))
}

/** Uses only the public eTopaz page and selects its ordinary visible "İdman" tab. */
@Composable private fun TopazPublicPageCollector(store:Store,onCaptured:()->Unit) {
    val activity=LocalContext.current as ComponentActivity
    val latestOnCaptured by rememberUpdatedState(onCaptured)
    val previousCheck=remember(store) { store.bookmakerSyncState("topaz")?.lastCheckedAtMillis }
    val shouldLoad=previousCheck==null || System.currentTimeMillis()-previousCheck>=30L*60*1000
    if(!shouldLoad) return
    val parser=remember { TopazOddsProvider() }
    val handler=remember { Handler(Looper.getMainLooper()) }
    val webView=remember(activity) {
        WebView(activity).apply {
            settings.javaScriptEnabled=true
            settings.domStorageEnabled=true
            webViewClient=object:WebViewClient() {
                private var capturePending=false
                override fun onPageFinished(view:WebView,url:String) {
                    val uri=runCatching { java.net.URI(url) }.getOrNull()
                    if(uri==null||uri.host !in setOf("etopaz.az","www.etopaz.az")||uri.path!="/home") return
                    handler.removeCallbacksAndMessages(null)
                    capturePending=true
                    // This clicks an ordinary visible tab by its exact accessible label; it does
                    // not navigate to a private route or inject any bookmaker request.
                    handler.postDelayed({
                        if(!capturePending) return@postDelayed
                        view.evaluateJavascript("""(function(){const els=[...document.querySelectorAll('button,[role="tab"],[role="button"],a,div,span')];const el=els.find(e=>e.innerText?.trim()==='İdman'&&e.getClientRects().length&&e.clientWidth>0&&e.clientHeight>0);if(!el)return false;el.click();return true;})()""") {
                            handler.postDelayed({
                                if(!capturePending) return@postDelayed
                                view.evaluateJavascript(
                                    "JSON.stringify({title:document.title,url:location.href,text:(document.body?.innerText||'').slice(0,100000),timeZoneId:Intl.DateTimeFormat().resolvedOptions().timeZone||'UTC'})"
                                ) { encoded ->
                                    capturePending=false
                                    val checkedAt=System.currentTimeMillis()
                                    try {
                                        val decoded=org.json.JSONTokener(encoded ?: "null").nextValue()
                                        val json=when(decoded) {
                                            is org.json.JSONObject -> decoded
                                            is String -> org.json.JSONObject(decoded)
                                            else -> error("Страница eTopaz не вернула видимые данные.")
                                        }
                                        val page=MisliVisiblePage(json.optString("url"),json.optString("title"),json.optString("text"),json.optString("timeZoneId","UTC"))
                                        Thread {
                                            try {
                                                val result=parser.parseVisiblePage(page,checkedAt,store.matchIdentities())
                                                val status=if(result.snapshots.isEmpty()) OddsProviderStatus.UNAVAILABLE else OddsProviderStatus.LIVE
                                                store.saveBookmakerCapture("topaz",checkedAt,status,result.snapshots,result.detail)
                                            } catch(error:Exception) {
                                                store.saveBookmakerCapture("topaz",checkedAt,OddsProviderStatus.ERROR,emptyList(),error.message ?: "Ошибка разбора публичной страницы.")
                                            }
                                            activity.runOnUiThread {
                                                latestOnCaptured()
                                                handler.postDelayed({ if(!activity.isFinishing) view.reload() },30L*60*1000)
                                            }
                                        }.start()
                                    } catch(error:Exception) {
                                        store.saveBookmakerCapture("topaz",checkedAt,OddsProviderStatus.ERROR,emptyList(),error.message ?: "Не удалось прочитать видимый текст.")
                                        latestOnCaptured()
                                        handler.postDelayed({ if(!activity.isFinishing) view.reload() },30L*60*1000)
                                    }
                                }
                            },3_000)
                        }
                    },4_000)
                }

                override fun shouldOverrideUrlLoading(view:WebView,url:String):Boolean {
                    val uri=runCatching { java.net.URI(url) }.getOrNull()
                    return uri==null||uri.scheme!="https"||uri.host !in setOf("etopaz.az","www.etopaz.az")
                }
            }
            loadUrl("https://etopaz.az/home?sport-id=1%3Asr%3Asport%3A1")
        }
    }
    androidx.compose.runtime.DisposableEffect(webView) {
        onDispose { handler.removeCallbacksAndMessages(null);webView.stopLoading();webView.destroy() }
    }
    AndroidView(factory={webView},modifier=Modifier.size(1.dp).alpha(0.01f))
}

@Composable fun FootballScreen(store:Store) {
    val activity = LocalContext.current as ComponentActivity
    var revision by remember { mutableIntStateOf(0) }
    var selected by remember { mutableIntStateOf(-1) }
    var tab by remember { mutableIntStateOf(0) }
    var importingCsv by remember { mutableStateOf(false) }
    var csvImportMessage by remember { mutableStateOf("") }
    var fallbackCompetition by remember { mutableStateOf("") }
    var selectedBacktestCode by remember { mutableStateOf("") }
    var backtestRunning by remember { mutableStateOf(false) }
    var backtestMessage by remember { mutableStateOf("") }
    var backtestReport by remember { mutableStateOf<EloWalkForwardReport?>(null) }
    var backtestReportCode by remember { mutableStateOf("") }
    var automaticBacktestRunning by remember { mutableStateOf(false) }
    var automaticBacktestMessage by remember { mutableStateOf("") }
    var automaticBacktestReport by remember { mutableStateOf<EloWalkForwardReport?>(null) }
    var automaticBacktestRevision by remember { mutableIntStateOf(-1) }
    var marginMethod by remember { mutableStateOf(MarginMethod.BASIC) }
    var showBookmakerQuotes by remember { mutableStateOf(false) }
    val workManager=remember(activity) { WorkManager.getInstance(activity) }
    val oneTimeSync by remember(workManager) { workManager.getWorkInfosForUniqueWorkLiveData(FootballSyncScheduler.UNIQUE_ONCE_NAME) }.observeAsState(emptyList())
    val periodicSync by remember(workManager) { workManager.getWorkInfosForUniqueWorkLiveData(FootballSyncScheduler.UNIQUE_PERIODIC_NAME) }.observeAsState(emptyList())
    val syncSummaries=remember(revision) { store.providerSyncSummaries() }
    val syncBusy=(oneTimeSync+periodicSync).any { it.state==WorkInfo.State.RUNNING }
    val syncQueued=oneTimeSync.any { it.state==WorkInfo.State.ENQUEUED || it.state==WorkInfo.State.BLOCKED }
    val lastSuccessfulSync=syncSummaries.mapNotNull { it.lastSuccessAtMillis }.maxOrNull()
    val syncStatusText=when {
        syncBusy -> "Идёт автоматическая синхронизация источников…"
        syncQueued -> "Автоматическая синхронизация стоит в очереди WorkManager; возможна системная задержка или ожидание сети. Локальная база доступна."
        lastSuccessfulSync!=null -> "Последняя синхронизация: ${DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(lastSuccessfulSync))}"
        else -> "Автоматическая синхронизация запланирована; ожидается сеть или первый успешный ответ источника."
    }
    LaunchedEffect(
        oneTimeSync.map { it.state to (it.runAttemptCount to it.progress.getString("stage")) },
        periodicSync.map { it.state to (it.runAttemptCount to it.progress.getString("stage")) },
    ) { revision++ }
    LaunchedEffect(store) {
        val relinked=runCatching { withContext(Dispatchers.IO) { store.relinkUnmatchedBookmakerOdds() } }.getOrDefault(0)
        if(relinked>0) revision++
    }
    var matches by remember { mutableStateOf<List<Match>>(emptyList()) }
    var recentResults by remember { mutableStateOf<List<Match>>(emptyList()) }
    var importedCompetitionCodes by remember { mutableStateOf<List<String>>(emptyList()) }
    val matchRefreshKey=syncSummaries.map { it.providerId to it.lastCheckedAtMillis }
    LaunchedEffect(matchRefreshKey) {
        val loaded=withContext(Dispatchers.IO) {
            Triple(store.matches(),store.recentCompletedMatches(),store.importedCompetitionCodes())
        }
        matches=loaded.first
        recentResults=loaded.second
        importedCompetitionCodes=loaded.third
    }
    val activeBacktestCode=selectedBacktestCode.takeIf { it in importedCompetitionCodes }
        ?: importedCompetitionCodes.firstOrNull().orEmpty()
    val active=matches.firstOrNull { it.id==selected }
    var eloForecast by remember { mutableStateOf<EloForecast?>(null) }
    LaunchedEffect(active?.id,revision) {
        val match=active
        val kickoff=match?.let { runCatching { Instant.parse(it.kickoff).toEpochMilli() }.getOrNull() }
        if(match==null||match.finished||kickoff==null||!match.exactKickoff) {
            eloForecast=null
        } else {
            val history=withContext(Dispatchers.IO) { store.historicalResultsBefore(match.league,kickoff) }
            eloForecast=withContext(Dispatchers.Default) {
                EloEngine.forecast(history,kickoff,match.home,match.away)
            }
        }
    }
    val quoteList=remember(selected,revision) { if(selected>=0) store.quotes(selected) else emptyList() }
    val pickList=remember(selected,revision) { if(selected>=0) store.picks(selected) else emptyList() }
    val tickets=remember(revision) { store.tickets() }
    val ticketMatches=remember(revision) { store.ticketMatches() }
    val oddsProviders=remember { listOf(MisliOddsProvider(),TopazOddsProvider()) }
    val misliSyncState=remember(revision) { store.bookmakerSyncState("misli") }
    val misliOdds=remember(revision) { store.freshBookmakerOdds("misli") }
    val topazSyncState=remember(revision) { store.bookmakerSyncState("topaz") }
    val topazOdds=remember(revision) { store.freshBookmakerOdds("topaz") }
    val historyRefreshKey=syncSummaries.map { it.providerId to it.lastSuccessAtMillis }
    var historicalByLeague by remember { mutableStateOf<Map<String,List<HistoricalMatch>>>(emptyMap()) }
    var historyVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(historyRefreshKey) {
        val loaded=withContext(Dispatchers.IO) { store.historicalResultsByLeague() }
        historicalByLeague=loaded
        historyVersion++
    }
    val matchForecastKey=remember(matches) {
        matches.map { listOf(it.id,it.league,it.home,it.away,it.kickoff,it.finished,it.homeScore,it.awayScore,it.exactKickoff,it.availableAtMillis) }.hashCode()
    }
    val oddsForecastKey=remember(misliOdds,topazOdds) {
        (misliOdds+topazOdds).map { listOf(it.providerId,it.matchId,it.kickoffMillis,it.home,it.draw,it.away,it.capturedAtMillis) }.hashCode()
    }
    var forecastedMatches by remember { mutableStateOf<List<ForecastedMatch>>(emptyList()) }
    LaunchedEffect(matchForecastKey,oddsForecastKey,historyVersion) {
        val matchSnapshot=matches
        val oddsSnapshot=misliOdds+topazOdds
        val historySnapshot=historicalByLeague
        val now=System.currentTimeMillis()
        forecastedMatches=withContext(Dispatchers.Default) {
            val linkedOddsMatchIds=oddsSnapshot.mapNotNull { it.matchId }.toSet()
            val futureMatches=matchSnapshot.filter { !it.finished&&it.exactKickoff&&TeamDataQuality.isValidFixture(it.home,it.away) }
                .sortedBy { runCatching { Instant.parse(it.kickoff).toEpochMilli() }.getOrDefault(Long.MAX_VALUE) }
            val orderedMatches=(futureMatches.filter { it.id in linkedOddsMatchIds }+futureMatches.filterNot { it.id in linkedOddsMatchIds })
                .distinctBy { it.id }.take(80)
            orderedMatches.mapNotNull { match ->
                val kickoff=runCatching { Instant.parse(match.kickoff).toEpochMilli() }.getOrNull() ?: return@mapNotNull null
                if(kickoff<=now) return@mapNotNull null
                val history=historyAvailableAt(historySnapshot[match.league].orEmpty(),now)
                val forecast=EloEngine.forecast(history,now,match.home,match.away)
                val probabilities=forecast.probabilities ?: return@mapNotNull null
                ForecastedMatch(
                    matchId=match.id,
                    homeTeam=match.home,
                    awayTeam=match.away,
                    kickoffMillis=kickoff,
                    probabilities=probabilities,
                    forecastedAtMillis=now,
                    historyMatchCount=forecast.matchesUsed,
                    modelVersion=CURRENT_ELO_MODEL_VERSION,
                    competition=match.league,
                )
            }
        }
    }
    val bookmakerCouponPrices=remember(misliOdds,topazOdds) {
        mapOf(
            "misli" to misliOdds.mapNotNull { q -> q.matchId?.let { it to ThreeWayDecimalOdds(q.home,q.draw,q.away,q.capturedAtMillis) } }.toMap(),
            "topaz" to topazOdds.mapNotNull { q -> q.matchId?.let { it to ThreeWayDecimalOdds(q.home,q.draw,q.away,q.capturedAtMillis) } }.toMap(),
        )
    }
    val modelPickDrafts=remember(forecastedMatches,misliOdds,topazOdds) {
        VirtualCouponBuilder.buildModelOnly(forecastedMatches,oddsProviders.map { it.providerId to it.displayName },bookmakerCouponPrices)
    }
    val accumulatorDrafts=remember(forecastedMatches,misliOdds,topazOdds) {
        VirtualCouponBuilder.buildAccumulators(forecastedMatches,oddsProviders.map { it.providerId to it.displayName },bookmakerCouponPrices)
    }
    LaunchedEffect(forecastedMatches) {
        val forecastSnapshotsAdded=store.recordProspectiveForecastSnapshots(forecastedMatches)
        val forecastResultsSettled=store.settleProspectiveForecastSnapshots()
        if(forecastSnapshotsAdded+forecastResultsSettled>0) revision++
    }
    LaunchedEffect(modelPickDrafts,accumulatorDrafts) {
        val picksAdded=store.recordCouponCandidates(modelPickDrafts)
        val couponsAdded=store.recordAccumulatorDrafts(accumulatorDrafts)
        if(picksAdded+couponsAdded>0) revision++
    }
    val checkedCouponHistory=remember(revision) { store.loggedCouponPicks() }
    val prospectiveForecastHistory=remember(revision) { store.prospectiveForecastHistory(100) }
    val prospectiveForecastReport=remember(prospectiveForecastHistory) {
        ProspectiveForecastEvaluator.evaluate(prospectiveForecastHistory)
    }
    val accumulatorHistory=remember(revision) { store.virtualAccumulatorHistory() }
    val mapped=matches.associateBy { it.id }
    val settled=tickets.mapNotNull { t -> ticketMatches[t.matchId]?.let { m -> scoreResult(m)?.let { it to t } } }
    val exposure=settled.sumOf { it.second.stake }
    val returned=settled.sumOf { (r,t)->if(r==t.outcome) t.stake*t.price else 0.0 }
    val roi=if(exposure>0) (returned/exposure-1)*100 else 0.0
    var source by remember(selected) { mutableStateOf("") }
    var outcome by remember(selected) { mutableStateOf("H") }
    var stake by remember(selected) { mutableStateOf("10") }
    var warning by remember(selected) { mutableStateOf("") }
    val blue=MaterialTheme.colorScheme.primary

    val csvLauncher=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null&&!importingCsv) {
            importingCsv=true
            csvImportMessage="Импорт CSV…"
            val fallback=fallbackCompetition.trim().ifBlank { null }
            Thread {
                try {
                    val resolver=activity.contentResolver
                    val fileName=resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { c ->
                        if(c.moveToFirst()) c.getString(0) else null
                    } ?: uri.lastPathSegment ?: "football-results.csv"
                    val input=resolver.openInputStream(uri) ?: error("Файл недоступен для чтения.")
                    val bytes=input.use { stream ->
                        val output=ByteArrayOutputStream()
                        val buffer=ByteArray(8192)
                        while(true) {
                            val count=stream.read(buffer)
                            if(count<0) break
                            require(output.size()+count<=20*1024*1024) { "CSV-файл больше 20 МБ." }
                            output.write(buffer,0,count)
                        }
                        output.toByteArray()
                    }
                    val report=FootballDataCsvImporter.parse(FootballDataCsvImporter.decode(bytes),fallback)
                    require(report.results.isNotEmpty()) { "Не найдено корректных завершённых матчей. Проверь Date, HomeTeam, AwayTeam, FTHG/HG и FTAG/AG." }
                    val stored=store.importHistoricalResults(fileName,report)
                    activity.runOnUiThread {
                        revision++
                        importingCsv=false
                        backtestReport=null
                        backtestReportCode=""
                        val examples=report.examples.take(2).joinToString("; ").let { if(it.isBlank()) "" else " Примеры: $it" }
                        csvImportMessage="Импорт: добавлено ${stored.insertedRows}, дублей ${stored.duplicateRows}, пропущено строк ${report.rejectedRows} из ${report.rowsRead}.$examples"
                    }
                } catch(e:Exception) {
                    activity.runOnUiThread {
                        importingCsv=false
                        csvImportMessage="Ошибка импорта: ${e.message ?: "неизвестная ошибка"}"
                    }
                }
            }.start()
        }
    }

    fun runImportedBacktest(competitionCode:String) {
        if(backtestRunning||competitionCode.isBlank()) return
        backtestRunning=true
        backtestMessage="Расчёт по сохранённым результатам…"
        backtestReport=null
        backtestReportCode=""
        Thread {
            try {
                val rows=store.importedHistory(competitionCode)
                val history=rows.map { row ->
                    HistoricalMatch(
                        kickoffMillis=FootballDataCsvImporter.dateBucketForecastCutoffMillis(row.eventDate),
                        homeTeam=row.homeTeam,
                        awayTeam=row.awayTeam,
                        homeGoals=row.homeGoals,
                        awayGoals=row.awayGoals,
                        availableAtMillis=row.availableAtMillis,
                    )
                }
                val targets=rows.map { row ->
                    WalkForwardTarget(
                        id=row.id.toString(),
                        forecastCutoffMillis=FootballDataCsvImporter.dateBucketForecastCutoffMillis(row.eventDate),
                        homeTeam=row.homeTeam,
                        awayTeam=row.awayTeam,
                        homeGoals=row.homeGoals,
                        awayGoals=row.awayGoals,
                    )
                }
                val report=EloWalkForwardEngine.evaluate(history,targets)
                activity.runOnUiThread {
                    backtestReportCode=competitionCode
                    backtestReport=report
                    backtestRunning=false
                    backtestMessage=""
                }
            } catch(e:Exception) {
                activity.runOnUiThread {
                    backtestRunning=false
                    backtestMessage="Ошибка исторического теста: ${e.message ?: "неизвестная ошибка"}"
                }
            }
        }.start()
    }

    fun runAutomaticBacktest() {
        if(automaticBacktestRunning||automaticBacktestRevision==revision) return
        automaticBacktestRunning=true
        automaticBacktestMessage="Расчёт по локальной автоматической истории…"
        Thread {
            try {
                val report=store.automaticWalkForwardReport()
                activity.runOnUiThread {
                    automaticBacktestReport=report
                    automaticBacktestRevision=revision
                    automaticBacktestRunning=false
                    automaticBacktestMessage=""
                }
            } catch(error:Exception) {
                activity.runOnUiThread {
                    automaticBacktestRunning=false
                    automaticBacktestMessage="Ошибка проверки: ${error.message ?: "неизвестная ошибка"}"
                }
            }
        }.start()
    }

    MaterialTheme {
      MisliPublicPageCollector(store) { revision++ }
      TopazPublicPageCollector(store) { revision++ }
      Scaffold(topBar={ Surface(color=MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("FOOTBALL INTELLIGENCE",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            Text("Личный аналитический журнал • Бесплатно • Без реальных ставок",style=MaterialTheme.typography.bodySmall)
        }
      }}) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("Матчи","Анализ","Купоны","Результаты","Бэктест","Настройки").forEachIndexed { i,title ->
                    FilterChip(selected=tab==i,onClick={tab=i;if(i==4) runAutomaticBacktest()},label={Text(title)})
                }
            }
            when(tab) {
              0 -> {
                Column(Modifier.fillMaxWidth().padding(horizontal=14.dp, vertical=8.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text("Автоматические футбольные данные",fontWeight=FontWeight.Bold)
                    Text(syncStatusText,style=MaterialTheme.typography.bodySmall)
                    val misliChecked=misliSyncState?.lastCheckedAtMillis?.let { DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) } ?: "ещё не было"
                    Text("Misli.az: ${visibleBookmakerStatus(misliSyncState?.status,misliSyncState?.lastCheckedAtMillis,misliOdds.size)} · снимок $misliChecked · актуальных будущих линий: ${misliOdds.size}${misliSyncState?.error?.let { " · $it" }.orEmpty()}",style=MaterialTheme.typography.bodySmall)
                    val topazChecked=topazSyncState?.lastCheckedAtMillis?.let { DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) } ?: "проверяется при открытии"
                    Text("TOPAZ/eTopaz: ${visibleBookmakerStatus(topazSyncState?.status,topazSyncState?.lastCheckedAtMillis,topazOdds.size)} · снимок $topazChecked · актуальных будущих линий: ${topazOdds.size}${topazSyncState?.error?.let { " · $it" }.orEmpty()}",style=MaterialTheme.typography.bodySmall)
                    syncSummaries.forEach { summary ->
                        val checked=DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(summary.lastCheckedAtMillis))
                        val successful=summary.lastSuccessAtMillis?.let { DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) } ?: "ещё не было"
                        Text("${summary.providerId}: ${summary.status} · до ${summary.recordsStored} матчей · проверка $checked · успех $successful${summary.error?.let { " · $it" }.orEmpty()}",style=MaterialTheme.typography.bodySmall)
                    }
                    if(syncBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Источники: OpenFootball (CC0, доступные сезоны) и OpenLigaDB (динамический каталог). Загрузка выполняется в фоне и может быть отложена Android.",style=MaterialTheme.typography.bodySmall)
                }
                LazyColumn(contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    if(matches.isEmpty()) {
                        item { Text("База пока пуста. Первый импорт начнётся автоматически при доступной сети; после загрузки данные останутся на устройстве.",Modifier.padding(12.dp),style=MaterialTheme.typography.bodyMedium) }
                    } else {
                        items(matches,key={it.id}) { m ->
                            ElevatedCard(onClick={selected=m.id;tab=1},modifier=Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp)) {
                                    Text("${m.league} · ${niceMatchTime(m)}",style=MaterialTheme.typography.labelMedium,color=blue)
                                    Spacer(Modifier.height(5.dp))
                                    Text("${m.home} — ${m.away}",fontWeight=FontWeight.SemiBold)
                                    Text(matchStatusLabel(m),style=MaterialTheme.typography.bodySmall)
                                    Text("Источник: ${m.sourceName}",style=MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
              }
              1 -> Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                if(active==null) { Text("Сначала выбери матч на вкладке «Матчи».") }
                else {
                  Text("${active.home} — ${active.away}",style=MaterialTheme.typography.titleLarge)
                  Text("${niceMatchTime(active)} · ${if(active.finished) "Завершён" else "До начала"}",style=MaterialTheme.typography.labelSmall)
                  HorizontalDivider()
                  Text("Экспериментальный Elo-baseline",fontWeight=FontWeight.Bold)
                  val activeForecast=eloForecast
                  if(active.finished) {
                      Text("Предматчевый расчёт для завершённого матча не показывается.",style=MaterialTheme.typography.bodySmall)
                  } else if(!active.exactKickoff) {
                      Text("Для этой записи есть только дата без точного kickoff; предматчевый расчёт недоступен.",style=MaterialTheme.typography.bodySmall)
                  } else if(activeForecast==null) {
                      Text("Расчёт Elo по локальной истории загружается…",style=MaterialTheme.typography.bodySmall)
                  } else {
                      val probabilities=activeForecast.probabilities
                      if(probabilities==null) {
                          Text("Недостаточно истории: ${activeForecast.matchesUsed} матчей в лиге, ${activeForecast.homeTeamMatches} для ${active.home} и ${activeForecast.awayTeamMatches} для ${active.away}. Для показа нужно минимум ${EloEngine.MIN_HISTORY_MATCHES_FOR_FORECAST} матчей в лиге и ${EloEngine.MIN_TEAM_MATCHES_FOR_FORECAST} для каждой команды.",style=MaterialTheme.typography.bodySmall)
                      } else {
                          Text("Оценка 1X2: ${"%.1f".format(probabilities.home*100)}% / ${"%.1f".format(probabilities.draw*100)}% / ${"%.1f".format(probabilities.away*100)}%",fontWeight=FontWeight.SemiBold)
                          Text("На основе ${activeForecast.matchesUsed} прошлых матчей лиги; ${active.home}: ${activeForecast.homeTeamMatches}, ${active.away}: ${activeForecast.awayTeamMatches}.",style=MaterialTheme.typography.bodySmall)
                      }
                      Text("Источник: сохранённые результаты OpenLigaDB этой лиги до времени начала матча. Elo с фиксированными параметрами, без калибровки и walk-forward проверки; экспериментальная оценка, не рекомендация и не показатель уверенности.",style=MaterialTheme.typography.bodySmall)
                  }

                }
              }
              2 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("Автоматические модельные черновики",style=MaterialTheme.typography.titleLarge)
                Text("Подбор наиболее вероятного исхода строится по локальному Elo для матчей с точным временем и достаточной историей. Черновики раздельны по операторам. Реальная цена показывается только для свежей линии того же букмекера и матча; модель экспериментальная и не откалибрована.",style=MaterialTheme.typography.bodySmall)
                Text("Misli.az: ${visibleBookmakerStatus(misliSyncState?.status,misliSyncState?.lastCheckedAtMillis,misliOdds.size)} · свежих линий ${misliOdds.size}, точных матчей ${misliOdds.count { it.matchId!=null }}. TOPAZ/eTopaz: ${visibleBookmakerStatus(topazSyncState?.status,topazSyncState?.lastCheckedAtMillis,topazOdds.size)} · свежих линий ${topazOdds.size}, точных матчей ${topazOdds.count { it.matchId!=null }}.",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={showBookmakerQuotes=!showBookmakerQuotes}) {
                    Text(if(showBookmakerQuotes) "Скрыть публичные линии" else "Показать публичные линии (${misliOdds.size+topazOdds.size})")
                }
                if(showBookmakerQuotes) {
                ElevatedCard {
                    Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text("Misli.az · публичные линии 1X2",fontWeight=FontWeight.Bold)
                        Text("Снимок: ${misliSyncState?.lastCheckedAtMillis?.let { DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) } ?: "ещё не получен"}. Свежесть 30 минут. Командные названия сопоставляются только по точным проверенным псевдонимам, часу и порядку хозяева/гости.",style=MaterialTheme.typography.bodySmall)
                        if(misliOdds.isEmpty()) Text(misliSyncState?.error ?: "Ожидается открытая страница Misli; старые и прошедшие линии не показываются.",style=MaterialTheme.typography.bodySmall)
                        misliOdds.forEach { quote ->
                            val forecast=quote.matchId?.let { id -> forecastedMatches.firstOrNull { it.matchId==id } }
                            val comparison=forecast?.let { f ->
                                val p=f.probabilities
                                val market=fairProbabilities(Quote(0,quote.matchId ?: 0,"Misli",quote.home,quote.draw,quote.away,quote.capturedAtMillis))
                                "Elo ${"%.1f".format(p.home*100)}/${"%.1f".format(p.draw*100)}/${"%.1f".format(p.away*100)}% · линия без маржи ${"%.1f".format(market.first*100)}/${"%.1f".format(market.second*100)}/${"%.1f".format(market.third*100)}%"
                            } ?: "Проверенного матча с локальным прогнозом нет"
                            val captured=DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(quote.capturedAtMillis))
                            Text("${niceTime(Instant.ofEpochMilli(quote.kickoffMillis).toString())} · ${quote.homeTeam} — ${quote.awayTeam} · 1 ${quote.home} / X ${quote.draw} / 2 ${quote.away} · снимок $captured",fontWeight=FontWeight.SemiBold)
                            Text("$comparison · источник: Misli.az public football page${if(quote.matchId==null) " · матч не найден в локальной базе" else ""}",style=MaterialTheme.typography.bodySmall)
                        }
                        Text("Цены — сохранённый снимок публичной страницы, не гарантированно текущая линия. Расчёт потенциальной доходности отключён, пока модель не проверена и не откалибрована.",style=MaterialTheme.typography.labelSmall)
                    }
                }
                ElevatedCard {
                    Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text("TOPAZ / eTopaz",fontWeight=FontWeight.Bold)
                        Text(topazSyncState?.error ?: "Публичная страница проверяется автоматически при открытии приложения; свежесть 30 минут.",style=MaterialTheme.typography.bodySmall)
                        if(topazOdds.isEmpty()) Text("Пока нет подтверждённых будущих коэффициентов TOPAZ.",style=MaterialTheme.typography.bodySmall)
                        topazOdds.forEach { quote ->
                            val forecast=quote.matchId?.let { id -> forecastedMatches.firstOrNull { it.matchId==id } }
                            val comparison=forecast?.let { f ->
                                val p=f.probabilities
                                val market=fairProbabilities(Quote(0,quote.matchId ?: 0,"TOPAZ",quote.home,quote.draw,quote.away,quote.capturedAtMillis))
                                "Elo ${"%.1f".format(p.home*100)}/${"%.1f".format(p.draw*100)}/${"%.1f".format(p.away*100)}% · линия без маржи ${"%.1f".format(market.first*100)}/${"%.1f".format(market.second*100)}/${"%.1f".format(market.third*100)}%"
                            } ?: "Нет точного локального матча с доступным прогнозом"
                            val captured=DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(quote.capturedAtMillis))
                            Text("${niceTime(Instant.ofEpochMilli(quote.kickoffMillis).toString())} · ${quote.homeTeam} — ${quote.awayTeam} · 1 ${quote.home} / X ${quote.draw} / 2 ${quote.away} · снимок $captured${if(quote.eventYearInferred) " · год по ближайшей будущей дате" else ""}",fontWeight=FontWeight.SemiBold)
                            Text("$comparison${if(quote.matchId==null) " · матч не найден в локальной базе" else ""}",style=MaterialTheme.typography.bodySmall)
                        }
                        Text("TOPAZ использует только собственные линии; коэффициенты Misli в этот купон не смешиваются.",style=MaterialTheme.typography.labelSmall)
                    }
                }
                }
                Text("Виртуальные экспрессы",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                Text("Размеры 2 / 3 / 5 / 8 / 10 доступны только при достаточном числе будущих матчей с точным kickoff и историей для модели. Отбираются исходы с наибольшими расчётными маргинальными вероятностями; более слабые матчи не добавляются ради размера.",style=MaterialTheme.typography.bodySmall)
                if(accumulatorDrafts.isEmpty()) Text("Пока недостаточно прогнозируемых матчей для экспресса из двух исходов. Доступно матчей: ${forecastedMatches.size}.",style=MaterialTheme.typography.bodySmall)
                accumulatorDrafts.forEach { draft ->
                    ElevatedCard {
                        Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                            val basisLabel=if(draft.selectionBasis==CouponSelectionBasis.MATCHED_FRESH_BOOKMAKER_ODDS) "по свежим линиям" else "по модели"
                            Text("${draft.providerName} · ${draft.requestedLegCount} исхода · $basisLabel",fontWeight=FontWeight.Bold)
                            val currentQuotes=if(draft.providerId=="misli") misliOdds else topazOdds
                            val linkedCurrentQuotes=currentQuotes.count { it.matchId!=null }
                            Text(if(currentQuotes.isEmpty()) {
                                (if(draft.providerId=="misli") misliSyncState?.error else topazSyncState?.error)
                                    ?: oddsProviders.first { it.providerId==draft.providerId }.status().detail
                            } else "Свежие котировки ${draft.providerName}: ${currentQuotes.size}; точно сопоставлено локальным матчам: $linkedCurrentQuotes.",style=MaterialTheme.typography.bodySmall)
                            draft.picks.forEach { pick ->
                                val price=pick.bookmakerDecimalOdds?.let { odds ->
                                    val quoteTime=pick.bookmakerOddsCapturedAtMillis?.let { DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) }
                                    " · ${draft.providerName} $odds${quoteTime?.let { " (снимок $it)" }.orEmpty()}"
                                }.orEmpty()
                                Text("${niceTime(Instant.ofEpochMilli(pick.kickoffMillis).toString())} · ${pick.homeTeam} — ${pick.awayTeam}: ${labelOutcome(pick.outcome)} ${"%.1f".format(pick.modelProbability*100)}%$price")
                            }
                            draft.jointProbabilityBounds?.let { bounds ->
                                Text("Совместная вероятность: только математические границы ${(bounds.lower*100).roundToInt()}–${(bounds.upper*100).roundToInt()}%; зависимость матчей не смоделирована.",style=MaterialTheme.typography.bodySmall)
                                draft.combinedDecimalOdds?.let { odds ->
                                    val evLower=(odds*bounds.lower-1.0)*100.0
                                    val evUpper=(odds*bounds.upper-1.0)*100.0
                                    Text("Условный EV по этим модельным границам: ${"%.1f".format(evLower)}–${"%.1f".format(evUpper)}% на условную единицу; Elo не откалиброван, это не оценка реальной прибыли.",style=MaterialTheme.typography.bodySmall)
                                }
                            }
                            if(draft.combinedDecimalOdds!=null) Text("Условный общий коэффициент ${"%.2f".format(draft.combinedDecimalOdds)} — произведение только свежих линий ${draft.providerName}.",fontWeight=FontWeight.SemiBold)
                            Text(if(draft.combinedDecimalOdds!=null) "Снимки коэффициентов фиксируются вместе с виртуальным купоном до матчей. Это не прогнозируемая прибыль: Elo не откалиброван, а совместный шанс известен лишь в указанном диапазоне." else "Нет полной свежей линии ${draft.providerName} для каждого исхода; общий коэффициент и денежная доходность недоступны. Виртуальный черновик сохраняет только модельные вероятности.",style=MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                HorizontalDivider()
                Text("История виртуальных экспрессов",fontWeight=FontWeight.Bold)
                Text("Снимки сохраняются автоматически и затем сверяются с локальными результатами. Перенос kickoff помечается отдельно; отмена матча не трактуется как поражение.",style=MaterialTheme.typography.bodySmall)
                if(accumulatorHistory.isEmpty()) Text("История начнёт заполняться автоматически при наличии минимум двух доступных прогнозов.",style=MaterialTheme.typography.bodySmall)
                accumulatorHistory.take(30).forEach { entry ->
                    val provider=oddsProviders.firstOrNull { it.providerId==entry.providerId }?.displayName ?: entry.providerId
                    val basisLabel=if(entry.selectionBasis==CouponSelectionBasis.MATCHED_FRESH_BOOKMAKER_ODDS) "по линиям" else "по модели"
                    val checked=DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(entry.capturedAtMillis))
                    val stateLabel=when(entry.settlement.state) {
                        AccumulatorSettlementState.PENDING -> "Ожидает матчей"
                        AccumulatorSettlementState.IN_PROGRESS -> "Завершено ${entry.settlement.settledLegs}/${entry.legCount}"
                        AccumulatorSettlementState.WON -> "Все ${entry.legCount} совпали"
                        AccumulatorSettlementState.LOST -> "Не совпало ${entry.settlement.incorrectLegs}; завершено ${entry.legCount}/${entry.legCount}"
                        AccumulatorSettlementState.SCHEDULE_CHANGED -> "Kickoff изменён · требуется повторная проверка"
                        AccumulatorSettlementState.INVALID -> "Некорректная запись"
                    }
                    Text("$checked · $provider · ${entry.legCount} исхода $basisLabel · $stateLabel${entry.combinedDecimalOdds?.let { " · общий коэффициент ${"%.2f".format(it)}" }.orEmpty()}",style=MaterialTheme.typography.bodySmall)
                }
                accumulatorHistory.groupBy { Triple(it.providerId,it.legCount,it.selectionBasis) }.forEach { (key, entries) ->
                    val singles=checkedCouponHistory.asSequence().filter { it.providerId==key.first }
                        .mapNotNull { pick ->
                            val home=pick.homeGoals ?: return@mapNotNull null
                            val away=pick.awayGoals ?: return@mapNotNull null
                            if(!pick.finished) return@mapNotNull null
                            val actual=when { home>away -> "H";home<away -> "A";else -> "D" }
                            SettledSinglePrediction(pick.matchId,actual==pick.outcome)
                        }.toList()
                    val report=AccumulatorPerformanceEngine.evaluate(entries,key.first,key.second,key.third,singles)
                    val provider=oddsProviders.firstOrNull { it.providerId==key.first }?.displayName ?: key.first
                    val basisLabel=if(key.third==CouponSelectionBasis.MATCHED_FRESH_BOOKMAKER_ODDS) "по линиям" else "по модели"
                    val hit=report.couponHitRate?.let { "${report.wonCoupons}/${report.settledCoupons} (${"%.1f".format(it*100)}%)" } ?: "ожидает исходов"
                    val singleHit=report.singlePickHitRate?.let { " · одиночные прогнозы тех же матчей ${report.matchingSinglePicks}, ${"%.1f".format(it*100)}%" }.orEmpty()
                    val roi=report.virtualRoi?.let { " · виртуальный ROI по полной линии ${"%.1f".format(it*100)}% (n=${report.pricedSettledCoupons})" }
                        ?: " · ROI/прибыль недоступны: нет полного снимка коэффициентов для завершённых купонов"
                    Text("Проверка $provider · ${key.second} исхода $basisLabel: купоны ${report.settledCoupons}, попадания $hit$singleHit$roi",style=MaterialTheme.typography.bodySmall)
                    if(report.settledCoupons>0&&report.couponHitRate95Low!=null&&report.couponHitRate95High!=null) {
                        Text("95% Wilson-интервал доли выигравших экспрессов: ${(report.couponHitRate95Low*100).roundToInt()}–${(report.couponHitRate95High*100).roundToInt()}%; CLV не вычисляется без зафиксированной цены около kickoff.",style=MaterialTheme.typography.labelSmall)
                    }
                }
                HorizontalDivider()
                Text("Проверка сохранённых прогнозов",fontWeight=FontWeight.Bold)
                if(checkedCouponHistory.isEmpty()) Text("Результаты появятся после автоматического сохранения предматчевых черновиков.",style=MaterialTheme.typography.bodySmall)
                checkedCouponHistory.forEach { pick ->
                    val resolved=pick.finished&&pick.homeGoals!=null&&pick.awayGoals!=null
                    val actual=if(!resolved) "Ожидает результата" else "Результат ${pick.homeGoals}:${pick.awayGoals} · ${if(scoreResult(Match(pick.matchId,"","","","",true,pick.homeGoals,pick.awayGoals))==pick.outcome) "совпало" else "не совпало"}"
                    Text("${pick.providerId} · ${pick.homeTeam} — ${pick.awayTeam} · ${labelOutcome(pick.outcome)} ${(pick.modelProbability*100).toInt()}% · $actual",style=MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                Text("Замороженные прогнозы модели",fontWeight=FontWeight.Bold)
                Text(
                    "Модель ${CURRENT_ELO_MODEL_VERSION}. Сохраняется один неизменяемый снимок на матч, версию и UTC-день до kickoff; итог записывается отдельно, когда результат стал доступен. Это будущая проверка без оценки прибыли.",
                    style=MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Снимков в последних ${prospectiveForecastHistory.size}: проверено результатов ${prospectiveForecastReport.settledCount}" +
                        (prospectiveForecastReport.meanBrierScore?.let {
                            " · Brier ${"%.4f".format(it)} · Log Loss ${"%.4f".format(prospectiveForecastReport.meanLogLoss)} · попадание наиболее вероятного ${"%.1f".format((prospectiveForecastReport.topOutcomeAccuracy ?: 0.0)*100)}% (n=${prospectiveForecastReport.settledCount})"
                        } ?: " · для оценки ждём завершившиеся матчи"),
                    style=MaterialTheme.typography.bodySmall,
                )
                if(prospectiveForecastHistory.isEmpty()) {
                    Text("Первые прогнозы появятся после автоматического расчёта матчей с достаточной предматчевой историей.",style=MaterialTheme.typography.bodySmall)
                } else {
                    prospectiveForecastHistory.take(20).forEach { snapshot ->
                        val captured=niceTime(Instant.ofEpochMilli(snapshot.forecastedAtMillis).toString())
                        val probabilities=snapshot.probabilities
                        val actual=if(snapshot.actualOutcome==null) {
                            "ожидает доступного результата"
                        } else {
                            val scores="${snapshot.homeGoals}:${snapshot.awayGoals}"
                            val mostLikely=listOf("H","D","A")[probabilities.values().indices.maxBy { probabilities.values()[it] }]
                            val available=snapshot.resultAvailableAtMillis?.let {
                                " · доступен ${niceTime(Instant.ofEpochMilli(it).toString())}"
                            }.orEmpty()
                            "${labelOutcome(snapshot.actualOutcome)} $scores · ${if(mostLikely==snapshot.actualOutcome) "совпало" else "не совпало"} · ${snapshot.resultSourceName ?: "источник не указан"}$available"
                        }
                        Text(
                            "$captured · ${snapshot.homeTeam} — ${snapshot.awayTeam} · ${"%.1f".format(probabilities.home*100)}/${"%.1f".format(probabilities.draw*100)}/${"%.1f".format(probabilities.away*100)}% · история ${snapshot.historyMatchCount} · $actual",
                            style=MaterialTheme.typography.bodySmall,
                        )
                    }
                }
              }
              3 -> Column(Modifier.fillMaxSize().padding(14.dp)) {
                Text("История результатов",style=MaterialTheme.typography.titleLarge)
                Text("Сохранённые завершённые матчи из подключённых источников. Данные остаются доступны офлайн.",style=MaterialTheme.typography.bodySmall)
                LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    items(recentResults,key={it.id}) { m ->
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("${m.league} · ${niceMatchTime(m)}",style=MaterialTheme.typography.labelSmall)
                                Text("${m.home} ${m.homeScore}:${m.awayScore} ${m.away}",fontWeight=FontWeight.SemiBold)
                                Text("${m.sourceName} · сезон ${m.season ?: "не указан"}",style=MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
              }
              4 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("Elo walk-forward",style=MaterialTheme.typography.titleLarge)
                Text("Автоматическая оценка локальной истории OpenFootball/OpenLigaDB по отдельным соревнованиям. Для каждой цели модель использует только результаты, доступные до её временной границы.",style=MaterialTheme.typography.bodySmall)
                Text("Это ретроспективная оценка текущих файлов, а не архив исходных публикаций. Для OpenFootball без времени kickoff используется календарная дата и консервативная доступность результата через два дня; поздно полученные результаты OpenLigaDB не попадают в прошлое обучение. Поэтому цифры исследовательские и не доказывают будущую точность или преимущество.",style=MaterialTheme.typography.bodySmall)
                if(automaticBacktestRunning) LinearProgressIndicator(Modifier.fillMaxWidth())
                if(automaticBacktestMessage.isNotBlank()) Text(automaticBacktestMessage,style=MaterialTheme.typography.bodySmall)
                automaticBacktestReport?.let { report ->
                    Text("Автоматическая база: целей ${report.targetCount} · рассчитано прогнозов ${report.forecastCount} · пропущено из-за малого прошлого сэмпла ${report.skippedForInsufficientHistory}",fontWeight=FontWeight.SemiBold)
                    if(report.meanBrierScore==null||report.meanLogLoss==null) Text("Пока недостаточно предматчевой истории для проверяемого прогноза.",style=MaterialTheme.typography.bodySmall)
                    else Text("Средний Brier: ${"%.4f".format(report.meanBrierScore)} · средний Log Loss: ${"%.4f".format(report.meanLogLoss)}")
                    WalkForwardCalibrationSummary(report)
                    Text("Срез рассчитан отдельно в границах соревнований. Результат не является обещанием прибыли.",style=MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                Text("Дополнительный импортированный CSV",fontWeight=FontWeight.SemiBold)
                Text("Ретроспективная оценка по импортированным результатам. Для каждого календарного дня используется один срез, без результатов этого дня; строки CSV становятся доступны только спустя два дня.",style=MaterialTheme.typography.bodySmall)
                Text("Это не воспроизведение архивных котировок и не сравнение с рынком: временные версии исходных файлов не сохранены, а вероятности не калиброваны.",style=MaterialTheme.typography.bodySmall)
                if(importedCompetitionCodes.isEmpty()) {
                    Text("Дополнительный walk-forward по CSV станет доступен после импорта в настройках. Для обычного анализа работает автоматическая база.")
                } else {
                    Text("Код соревнования из CSV",fontWeight=FontWeight.SemiBold)
                    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        importedCompetitionCodes.forEach { code ->
                            FilterChip(selected=activeBacktestCode==code,onClick={selectedBacktestCode=code},label={Text(code)})
                        }
                    }
                    Text("Минимум для прогноза: ${EloEngine.MIN_HISTORY_MATCHES_FOR_FORECAST} прошлых матчей соревнования и ${EloEngine.MIN_TEAM_MATCHES_FOR_FORECAST} матча каждой команды.",style=MaterialTheme.typography.bodySmall)
                    Button(onClick={runImportedBacktest(activeBacktestCode)},enabled=!backtestRunning,modifier=Modifier.fillMaxWidth()) { Text("Рассчитать walk-forward") }
                    if(backtestRunning) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(backtestMessage.isNotBlank()) Text(backtestMessage,style=MaterialTheme.typography.bodySmall)
                    if(backtestReportCode==activeBacktestCode) backtestReport?.let { report ->
                        HorizontalDivider()
                        Text("Матчей: ${report.targetCount} · прогнозов: ${report.forecastCount} · пропущено из-за малого сэмпла: ${report.skippedForInsufficientHistory}",fontWeight=FontWeight.SemiBold)
                        if(report.meanBrierScore==null||report.meanLogLoss==null) {
                            Text("Недостаточно предматчевой истории для расчёта показателей.",style=MaterialTheme.typography.bodySmall)
                        } else {
                            Text("Средний Brier: ${"%.4f".format(report.meanBrierScore)} · средний Log Loss: ${"%.4f".format(report.meanLogLoss)}")
                        }
                        WalkForwardCalibrationSummary(report)
                        Text("Результаты показывают только качество вероятностных оценок на этом локальном файле; они не доказывают преимущество, прибыль или превосходство над букмекерами.",style=MaterialTheme.typography.bodySmall)
                    }
                }
              }
              else -> Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                  Text("Football Intelligence Lab",style=MaterialTheme.typography.titleLarge)
                  Text("Личная лаборатория исследования футбольных ставок, без оплаты и обязательных ключей.")
                  Text("Автоматические источники: OpenFootball и OpenLigaDB. Коэффициенты Misli/TOPAZ пока не подтверждены как автоматический prematch-поток.")
                  Text("Дополнительные функции")
                  OutlinedTextField(value=fallbackCompetition,onValueChange={fallbackCompetition=it},label={Text("Необязательный код соревнования для CSV")},singleLine=true,modifier=Modifier.fillMaxWidth())
                  Button(onClick={csvLauncher.launch(arrayOf("text/*","application/csv"))},enabled=!importingCsv,modifier=Modifier.fillMaxWidth()) { Text(if(importingCsv) "Импорт…" else "Импорт CSV (дополнительно)") }
                  if(csvImportMessage.isNotBlank()) Text(csvImportMessage,style=MaterialTheme.typography.bodySmall)
                  Text("CSV не нужен для обычного запуска: матчи и результаты загружаются автоматически.",style=MaterialTheme.typography.bodySmall)
                  Text("Вероятности Elo экспериментальные и не калиброваны. Купоны виртуальные.")
                  Text("Система не выполняет ставки, не входит в букмекерские аккаунты и не обещает доход.")
                  Text("Открытые источники могут временно не отвечать. Уже сохранённые данные доступны без сети.")
              }
            }
        }
      }
    }
}
@Composable fun OutcomeSelect(value:String,onChange:(String)->Unit) {
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf("H","D","A").forEach { v -> FilterChip(selected=value==v,onClick={onChange(v)},label={Text(labelOutcome(v))}) }
    }
}

@Composable
private fun WalkForwardCalibrationSummary(report:EloWalkForwardReport) {
    if(report.predictions.isEmpty()) return
    val calibration=remember(report) { CalibrationDiagnostics.evaluateWalkForward(report.predictions) }
    var expanded by remember(report) { mutableStateOf(false) }
    val ece=calibration.meanOneVsRestEce?.let { "%.1f".format(it*100) } ?: "н/д"
    Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
        Text("Калибровочная диагностика: mean one-vs-rest ECE $ece% · ${calibration.predictionCount} прогнозов",fontWeight=FontWeight.SemiBold)
        calibration.outcomes.forEach { outcome ->
            val outcomeEce=outcome.expectedCalibrationError?.let { "%.1f".format(it*100) } ?: "н/д"
            Text("${outcome.outcome.label}: ECE $outcomeEce% · n=${outcome.sampleCount}",style=MaterialTheme.typography.bodySmall)
        }
        Text("Равные интервалы вероятности 0–100%; ECE показывает среднее расхождение прогноза и частоты исхода в занятых корзинах. Это описание ретроспективной выборки, не калибровка модели.",style=MaterialTheme.typography.labelSmall)
        TextButton(onClick={expanded=!expanded},contentPadding=PaddingValues(0.dp)) {
            Text(if(expanded) "Скрыть корзины" else "Показать занятые корзины")
        }
        if(expanded) {
            calibration.outcomes.forEach { outcome ->
                if(outcome.occupiedBins.isNotEmpty()) {
                    Text("${outcome.outcome.label} · средняя вероятность / частота / число",fontWeight=FontWeight.Medium,style=MaterialTheme.typography.labelMedium)
                    outcome.occupiedBins.forEach { bin ->
                        Text(
                            "${"%.0f".format(bin.lowerInclusive*100)}–${"%.0f".format(bin.upperBound*100)}% · " +
                                "${"%.1f".format(bin.meanPredictedProbability*100)}% / " +
                                "${"%.1f".format(bin.observedFrequency*100)}% / ${bin.sampleCount}",
                            style=MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
