package az.simplesoft.footballintelligence.analytics

import java.util.Locale

/** Rejects source placeholders that are not real team identities before forecasts or evaluation. */
object TeamDataQuality {
    private val placeholderPhrases = listOf(
        "dummy", "empty stands", "placeholder", "to be announced", "to be decided",
        "unknown team", "team tbd", "team tbc", "tbd", "tbc", "winner of", "loser of",
    )

    fun isPlausibleTeamName(name: String): Boolean {
        val normalized = name.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        if (normalized.length !in 1..100 || normalized.none { it.isLetterOrDigit() }) return false
        return placeholderPhrases.none { phrase -> normalized == phrase || normalized.contains(phrase) }
    }

    fun isValidFixture(homeTeam: String, awayTeam: String): Boolean =
        isPlausibleTeamName(homeTeam) && isPlausibleTeamName(awayTeam) && teamKey(homeTeam) != teamKey(awayTeam)
}
