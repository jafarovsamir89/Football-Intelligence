package az.simplesoft.footballintelligence.data

import org.json.JSONObject
import org.json.JSONArray
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import az.simplesoft.footballintelligence.analytics.TeamDataQuality

data class OpenFootballCompetitionFile(
    val season: String,
    val fileName: String,
    val downloadUrl: String,
) {
    val competitionCode: String get() = fileName.removeSuffix(".json")
}

object OpenFootballJsonParser {
    const val PROVIDER_ID = "openfootball"
    const val REPOSITORY = "https://github.com/openfootball/football.json"
    private const val RAW_BASE = "https://raw.githubusercontent.com/openfootball/football.json/master"

    fun planSeasons(today: LocalDate, seasonCount: Int = 5): List<String> {
        require(seasonCount > 0)
        val currentSeasonStart = if (today.monthValue >= 7) today.year else today.year - 1
        return (0 until seasonCount).map { offset ->
            val start = currentSeasonStart - offset
            "$start-${(start + 1).toString().takeLast(2)}"
        }
    }

    fun competitionFile(season: String, fileName: String): OpenFootballCompetitionFile? {
        if (!season.matches(Regex("\\d{4}-\\d{2}"))) return null
        if (!fileName.matches(Regex("[a-z0-9][a-z0-9.-]*\\.json"))) return null
        return OpenFootballCompetitionFile(
            season = season,
            fileName = fileName,
            downloadUrl = "$RAW_BASE/$season/$fileName",
        )
    }

    fun parseSeasonDirectory(json: String, season: String): List<OpenFootballCompetitionFile> {
        val rows = JSONArray(json)
        val result = mutableListOf<OpenFootballCompetitionFile>()
        val seen = mutableSetOf<String>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            if (row.optString("type") != "file") continue
            val name = row.optString("name")
            val file = competitionFile(season, name) ?: continue
            if (seen.add(file.fileName.lowercase(Locale.ROOT))) result.add(file)
        }
        return result.sortedBy { it.fileName }
    }

    /** Parses only match facts visible in the public CC0 JSON file. Date-only records stay date-only. */
    fun parse(
        json: String,
        season: String,
        fileName: String,
        sourceUrl: String,
        capturedAtMillis: Long,
    ): List<ProviderMatch> {
        require(season.matches(Regex("\\d{4}-\\d{2}"))) { "Invalid season." }
        val competition = fileName.removeSuffix(".json")
        val root = JSONObject(json)
        val competitionName = root.optString("name").trim().ifBlank { competition }
        val matches = root.optJSONArray("matches") ?: error("OpenFootball JSON has no matches array.")
        val result = ArrayList<ProviderMatch>(matches.length())
        val seen = mutableSetOf<String>()
        for (index in 0 until matches.length()) {
            val row = matches.optJSONObject(index) ?: continue
            val date = runCatching { LocalDate.parse(row.optString("date")) }.getOrNull() ?: continue
            val home = row.optString("team1").trim()
            val away = row.optString("team2").trim()
            if (!TeamDataQuality.isValidFixture(home,away) || canonicalTeam(home) == canonicalTeam(away)) continue
            val round = row.optString("round").trim()
            val homeGoals = row.optJSONObject("score")?.optJSONArray("ft")?.let { score ->
                if (score.length() < 2) null else score.optInt(0, -1).takeIf { it >= 0 }
            }
            val awayGoals = row.optJSONObject("score")?.optJSONArray("ft")?.let { score ->
                if (score.length() < 2) null else score.optInt(1, -1).takeIf { it >= 0 }
            }
            val validScore = if (homeGoals != null && awayGoals != null) homeGoals to awayGoals else null
            val keyParts = listOf(
                competition.lowercase(Locale.ROOT), season, date.toString(), round.lowercase(Locale.ROOT),
                canonicalTeam(home), canonicalTeam(away),
            )
            val matchKey = keyParts.joinToString(":")
            if (!seen.add(matchKey)) continue
            val kickoff = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val availableAt = if (validScore == null) null else date.plusDays(2)
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            result.add(
                ProviderMatch(
                    providerId = PROVIDER_ID,
                    providerMatchId = matchKey,
                    competitionCode = competition,
                    competitionName = competitionName,
                    season = season,
                    eventDate = date,
                    kickoffMillis = kickoff,
                    kickoffPrecision = KickoffPrecision.DATE_ONLY,
                    homeTeam = home,
                    awayTeam = away,
                    homeGoals = validScore?.first,
                    awayGoals = validScore?.second,
                    safeAvailableAtMillis = availableAt,
                    sourceUrl = sourceUrl,
                    capturedAtMillis = capturedAtMillis,
                ),
            )
        }
        return result
    }

    private fun canonicalTeam(name: String): String = name.trim().lowercase(Locale.ROOT)
        .replace("&", "and")
        .replace(Regex("\\b(fc|afc|cf|sc|ac|club)\\b", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}
