package az.simplesoft.footballintelligence.data

import java.util.Locale

/** Conservative cross-feed identity: same UTC/event date, same home/away order, normalized names. */
object FixtureDeduplication {
    fun sameCandidate(dateA: String, homeA: String, awayA: String, dateB: String, homeB: String, awayB: String): Boolean =
        dateA == dateB && canonicalTeam(homeA) == canonicalTeam(homeB) && canonicalTeam(awayA) == canonicalTeam(awayB)

    fun canonicalTeam(name: String): String = name.trim().lowercase(Locale.ROOT)
        .replace(Regex("""\s*\((q|w|u\d{2})\)\s*$""", RegexOption.IGNORE_CASE), " ")
        .replace("&", "and")
        .replace(Regex("\\b(fc|afc|cf|sc|ac|club)\\b", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}
