package az.simplesoft.footballintelligence.data

import org.json.JSONObject
import java.net.URI
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlin.math.abs

const val TIPSAUDIT_PROVIDER_ID = "tipsaudit_fair_reference"
const val TIPSAUDIT_API_URL = "https://tipsaudit.com/api/v1/predictions"
const val TIPSAUDIT_ATTRIBUTION_URL = "https://tipsaudit.com/data"
const val MARKET_REFERENCE_FRESHNESS_MILLIS = 30L * 60L * 1000L
const val MARKET_REFERENCE_REFRESH_INTERVAL_MINUTES = 30L
const val MARKET_REFERENCE_APP_OPEN_STALE_MINUTES = 15L

enum class MarketReferenceStatus { OK, UNAVAILABLE, ERROR }

data class MarketReferenceSyncState(
    val providerId: String,
    val status: MarketReferenceStatus,
    val lastCheckedAtMillis: Long,
    val lastSuccessAtMillis: Long?,
    val freshRecordsStored: Int,
    val methodologyVersion: String?,
    val detail: String?,
)

/** A derived fair-market reference. This is not a raw quote from a named bookmaker. */
data class MarketReferenceSnapshot(
    val providerId: String,
    val externalEventId: String,
    val matchId: Int?,
    val sourceUrl: String,
    val capturedAtMillis: Long,
    val fairReadAtMillis: Long,
    val kickoffMillis: Long,
    val market: String,
    val homeTeam: String,
    val awayTeam: String,
    val homeFairOdds: Double,
    val drawFairOdds: Double,
    val awayFairOdds: Double,
    val homeProbability: Double,
    val drawProbability: Double,
    val awayProbability: Double,
    val fairSource: String,
    val methodologyVersion: String,
) {
    init {
        require(providerId == TIPSAUDIT_PROVIDER_ID)
        require(externalEventId.isNotBlank())
        require(sourceUrl.startsWith("https://tipsaudit.com/"))
        require(market == "1X2" && fairSource == "live")
        require(homeTeam.isNotBlank() && awayTeam.isNotBlank())
        require(listOf(homeFairOdds, drawFairOdds, awayFairOdds).all { it.isFinite() && it > 1.0 && it <= 1000.0 })
        require(listOf(homeProbability, drawProbability, awayProbability).all { it.isFinite() && it in 0.0..1.0 })
        require(abs(homeProbability + drawProbability + awayProbability - 1.0) <= 0.005)
        require(methodologyVersion.isNotBlank())
    }
}

data class TipsAuditParseResult(
    val snapshots: List<MarketReferenceSnapshot>,
    val receivedRows: Int,
    val rejectedRows: Int,
    val staleRows: Int,
    val duplicateRows: Int,
    val methodologyVersion: String,
    val detail: String,
)

/** Parses only fresh, pre-match, `fair_source=live` values from the public TipsAudit API. */
class TipsAuditFairOddsProvider {
    fun parse(
        responseBody: String,
        capturedAtMillis: Long,
        fixtures: List<FixtureIdentity> = emptyList(),
    ): TipsAuditParseResult {
        require(capturedAtMillis > 0L)
        val root = JSONObject(responseBody)
        val rows = root.optJSONArray("predictions") ?: error("TipsAudit response has no predictions array.")
        val methodology = root.optString("methodology_version").takeIf { it.isNotBlank() }
            ?: error("TipsAudit response has no methodology version.")
        val parsed = mutableListOf<MarketReferenceSnapshot>()
        var rejected = 0
        var stale = 0
        var duplicate = 0
        val seenIds = mutableSetOf<String>()

        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index)
            if (row == null) {
                rejected++
                continue
            }
            val id = row.opt("id")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
            if (id == null) {
                rejected++
                continue
            }
            if (!seenIds.add(id)) {
                duplicate++
                continue
            }

            val candidate = runCatching {
                val home = row.getString("home").trim()
                val away = row.getString("away").trim()
                val kickoff = parseTimestamp(row.getString("kickoff_utc"))
                val sourceRead = parseTimestamp(
                    row.optString("fair_read_at").ifBlank { row.optString("benchmark_read_at") },
                )
                val sourceType = row.getString("fair_source").lowercase()
                val url = row.getString("url")
                val uri = URI(url)
                require(uri.scheme == "https" && uri.host in setOf("tipsaudit.com", "www.tipsaudit.com"))
                require(home.isNotBlank() && away.isNotBlank() && home != away)
                require(sourceType == "live")

                val probabilities = row.getJSONObject("fair_probabilities")
                val homeProbability = probabilities.getDouble("home")
                val drawProbability = probabilities.getDouble("draw")
                val awayProbability = probabilities.getDouble("away")
                require(listOf(homeProbability, drawProbability, awayProbability).all { it.isFinite() && it in 0.0..1.0 })
                require(abs(homeProbability + drawProbability + awayProbability - 1.0) <= 0.005)

                val odds = row.getJSONObject("fair_odds")
                val homeOdds = odds.getDouble("home")
                val drawOdds = odds.getDouble("draw")
                val awayOdds = odds.getDouble("away")
                require(listOf(homeOdds, drawOdds, awayOdds).all { it.isFinite() && it > 1.0 && it <= 1000.0 })
                // Fair prices should be the reciprocal of the published fair probabilities, within rounding.
                require(abs(1.0 / homeOdds - homeProbability) <= 0.006)
                require(abs(1.0 / drawOdds - drawProbability) <= 0.006)
                require(abs(1.0 / awayOdds - awayProbability) <= 0.006)

                val matchedId = uniqueExactMarketFixtureMatchId(home, away, kickoff, fixtures)
                MarketReferenceSnapshot(
                    providerId = TIPSAUDIT_PROVIDER_ID,
                    externalEventId = id,
                    matchId = matchedId,
                    sourceUrl = url,
                    capturedAtMillis = capturedAtMillis,
                    fairReadAtMillis = sourceRead,
                    kickoffMillis = kickoff,
                    market = "1X2",
                    homeTeam = home,
                    awayTeam = away,
                    homeFairOdds = homeOdds,
                    drawFairOdds = drawOdds,
                    awayFairOdds = awayOdds,
                    homeProbability = homeProbability,
                    drawProbability = drawProbability,
                    awayProbability = awayProbability,
                    fairSource = sourceType,
                    methodologyVersion = methodology,
                )
            }.getOrNull()

            if (candidate == null) {
                // Distinguish well-formed but old/forecast-ineligible records from malformed rows.
                val sourceType = row.optString("fair_source").lowercase()
                val sourceRead = runCatching {
                    parseTimestamp(row.optString("fair_read_at").ifBlank { row.optString("benchmark_read_at") })
                }.getOrNull()
                val kickoff = runCatching { parseTimestamp(row.optString("kickoff_utc")) }.getOrNull()
                if (sourceType == "live" && sourceRead != null && kickoff != null &&
                    (sourceRead > capturedAtMillis || capturedAtMillis - sourceRead > MARKET_REFERENCE_FRESHNESS_MILLIS || kickoff <= capturedAtMillis)
                ) stale++ else rejected++
                continue
            }

            if (candidate.fairReadAtMillis > capturedAtMillis ||
                capturedAtMillis - candidate.fairReadAtMillis > MARKET_REFERENCE_FRESHNESS_MILLIS ||
                candidate.kickoffMillis <= capturedAtMillis
            ) {
                stale++
                continue
            }
            parsed += candidate
        }

        val details = "получено ${rows.length()} строк, свежих допущено ${parsed.size}, устаревших/непредматчевых $stale, отклонено $rejected, дублей $duplicate"
        return TipsAuditParseResult(
            snapshots = parsed.sortedBy { it.kickoffMillis },
            receivedRows = rows.length(),
            rejectedRows = rejected,
            staleRows = stale,
            duplicateRows = duplicate,
            methodologyVersion = methodology,
            detail = details,
        )
    }

    private fun parseTimestamp(value: String): Long = try {
        OffsetDateTime.parse(value).toInstant().toEpochMilli()
    } catch (error: DateTimeParseException) {
        throw IllegalArgumentException("Invalid ISO-8601 timestamp.", error)
    }
}

fun uniqueExactMarketFixtureMatchId(
    homeTeam: String,
    awayTeam: String,
    kickoffMillis: Long,
    fixtures: List<FixtureIdentity>,
    kickoffToleranceMillis: Long = 20L * 60L * 1000L,
): Int? {
    require(kickoffToleranceMillis >= 0L)
    val eventDate = java.time.Instant.ofEpochMilli(kickoffMillis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
    val candidates = fixtures.filter { fixture ->
        java.time.Instant.ofEpochMilli(fixture.kickoffMillis).atZone(java.time.ZoneOffset.UTC).toLocalDate() == eventDate &&
            TeamNameIdentity.canonical(fixture.homeTeam) == TeamNameIdentity.canonical(homeTeam) &&
            TeamNameIdentity.canonical(fixture.awayTeam) == TeamNameIdentity.canonical(awayTeam) &&
            kotlin.math.abs(fixture.kickoffMillis - kickoffMillis) <= kickoffToleranceMillis
    }
    return candidates.singleOrNull()?.matchId
}
