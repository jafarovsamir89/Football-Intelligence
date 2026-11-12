package az.simplesoft.footballintelligence.data

import org.json.JSONArray
import org.json.JSONObject
import az.simplesoft.footballintelligence.analytics.normalizeProviderTimestampUtc
import az.simplesoft.footballintelligence.analytics.TeamDataQuality
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

data class OpenLigaLeague(
    val id: Int,
    val name: String,
    val shortcut: String,
    val season: String,
)

object OpenLigaDbParser {
    const val PROVIDER_ID = "openligadb"
    const val BASE_URL = "https://api.openligadb.de"

    fun parseAvailableLeagues(json: String, season: String): List<OpenLigaLeague> {
        val rows = JSONArray(json)
        val result = mutableListOf<OpenLigaLeague>()
        val seen = mutableSetOf<String>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val sport = row.optJSONObject("sport")
            val sportId = sport?.optInt("sportId", -1) ?: -1
            val sportName = sport?.optString("sportName").orEmpty()
            if (sportId != 1 && !sportName.contains("fußball", ignoreCase = true) &&
                !sportName.contains("fussball", ignoreCase = true) &&
                !sportName.contains("football", ignoreCase = true)
            ) continue
            val shortcut = row.optString("leagueShortcut").trim()
            val name = row.optString("leagueName").trim()
            if (shortcut.isBlank() || !seen.add(shortcut.lowercase())) continue
            result.add(
                OpenLigaLeague(
                    id = row.optInt("leagueId", 0),
                    name = name.ifBlank { shortcut },
                    shortcut = shortcut,
                    season = row.optString("leagueSeason").takeIf { it.isNotBlank() } ?: season,
                ),
            )
        }
        return result.sortedBy { it.shortcut.lowercase() }
    }

    fun parseCurrentGroupOrderId(json: String): Int? = runCatching {
        JSONObject(json).optInt("groupOrderID", JSONObject(json).optInt("groupOrderId", 0))
            .takeIf { it > 0 }
    }.getOrNull()

    fun parseLastChangedAtMillis(raw: String): Long? = normalizeProviderTimestampUtc(raw)
        ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    fun latestGroupOrderId(json: String): Int? = runCatching {
        val rows = JSONArray(json)
        (0 until rows.length()).mapNotNull { index ->
            rows.optJSONObject(index)?.optJSONObject("group")?.let { group ->
                group.optInt("groupOrderID", group.optInt("groupOrderId", 0)).takeIf { it > 0 }
            }
        }.maxOrNull()
    }.getOrNull()

    fun parseMatches(
        json: String,
        league: OpenLigaLeague,
        capturedAtMillis: Long,
        sourceUrl: String,
    ): List<ProviderMatch> {
        val rows = JSONArray(json)
        val matches = ArrayList<ProviderMatch>(rows.length())
        val seen = mutableSetOf<String>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val providerMatchId = row.optInt("matchID", 0).takeIf { it > 0 }?.toString() ?: continue
            val kickoffText = normalizeProviderTimestampUtc(row.optString("matchDateTimeUTC")) ?: continue
            val kickoffMillis = runCatching { Instant.parse(kickoffText).toEpochMilli() }.getOrNull() ?: continue
            val home = row.optJSONObject("team1")?.optString("teamName").orEmpty().trim()
            val away = row.optJSONObject("team2")?.optString("teamName").orEmpty().trim()
            if (!TeamDataQuality.isValidFixture(home,away)) continue
            val score = finalScore(row)
            val key = "$PROVIDER_ID:$providerMatchId"
            if (!seen.add(key)) continue
            val eventDate = runCatching {
                Instant.ofEpochMilli(kickoffMillis).atZone(ZoneOffset.UTC).toLocalDate()
            }.getOrNull() ?: continue
            if(!SeasonDateValidity.isPlausible(eventDate.toString(),league.season)) continue
            matches.add(
                ProviderMatch(
                    providerId = PROVIDER_ID,
                    providerMatchId = providerMatchId,
                    competitionCode = league.shortcut,
                    competitionName = league.name,
                    season = league.season,
                    eventDate = eventDate,
                    kickoffMillis = kickoffMillis,
                    kickoffPrecision = KickoffPrecision.INSTANT,
                    homeTeam = home,
                    awayTeam = away,
                    homeGoals = score?.first,
                    awayGoals = score?.second,
                    // This is the time this app actually received the result snapshot. It is safe
                    // for future forecasts but intentionally cannot power a historical replay.
                    safeAvailableAtMillis = capturedAtMillis.takeIf { score != null },
                    sourceUrl = sourceUrl,
                    capturedAtMillis = capturedAtMillis,
                ),
            )
        }
        return matches
    }

    private fun finalScore(match: JSONObject): Pair<Int, Int>? {
        if (!match.optBoolean("matchIsFinished", false)) return null
        val results = match.optJSONArray("matchResults") ?: return null
        val rows = (0 until results.length()).mapNotNull { results.optJSONObject(it) }
        val hasSemanticKinds = rows.any { it.optString("resultTypeKind").isNotBlank() }
        val finalRow = if (hasSemanticKinds) {
            rows.firstOrNull { it.optString("resultTypeKind").equals("After90Minutes", ignoreCase = true) }
        } else {
            // Compatibility with older payloads that predate resultTypeKind.
            rows.firstOrNull { it.optInt("resultTypeID", -1) == 2 }
        } ?: return null
        val home = finalRow.optInt("pointsTeam1", -1)
        val away = finalRow.optInt("pointsTeam2", -1)
        return (home to away).takeIf { home >= 0 && away >= 0 }
    }
}
