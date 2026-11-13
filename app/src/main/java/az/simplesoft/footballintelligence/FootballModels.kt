package az.simplesoft.footballintelligence

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import az.simplesoft.footballintelligence.analytics.MarginMethod
import az.simplesoft.footballintelligence.analytics.ProbabilityEngine
import az.simplesoft.footballintelligence.data.OddsProviderStatus

data class Match(val id: Int, val league: String, val home: String, val away: String,
                 val kickoff: String, val finished: Boolean, val homeScore: Int?, val awayScore: Int?,
                 val sourceKey: String = "openligadb:$id", val sourceName: String = "OpenLigaDB",
                 val exactKickoff: Boolean = true, val availableAtMillis: Long? = null,
                 val sourceUrl: String? = null, val eventDate: String? = null, val season: String? = null,
                 val capturedAtMillis: Long? = null)

private val MATCH_DISPLAY_ZONE: ZoneId = ZoneId.of("Asia/Baku")

internal fun fixtureLocalDate(match: Match, zone: ZoneId = MATCH_DISPLAY_ZONE): LocalDate? = if (match.exactKickoff) {
    runCatching { Instant.parse(match.kickoff).atZone(zone).toLocalDate() }.getOrNull()
} else {
    runCatching { LocalDate.parse(match.eventDate ?: match.kickoff.take(10)) }.getOrNull()
}

internal fun upcomingFixtures(
    matches: Collection<Match>,
    nowMillis: Long = System.currentTimeMillis(),
    zone: ZoneId = MATCH_DISPLAY_ZONE,
): List<Match> {
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    return matches.asSequence()
        .filter { match ->
            if (match.finished) false
            else if (match.exactKickoff) runCatching { Instant.parse(match.kickoff).toEpochMilli() > nowMillis }.getOrDefault(false)
            else fixtureLocalDate(match, zone)?.let { !it.isBefore(today) } == true
        }
        .sortedWith(
            compareBy<Match> { fixtureLocalDate(it, zone) ?: LocalDate.MAX }
                .thenBy { if (it.exactKickoff) 0 else 1 }
                .thenBy { if (it.exactKickoff) runCatching { Instant.parse(it.kickoff).toEpochMilli() }.getOrDefault(Long.MAX_VALUE) else Long.MAX_VALUE }
                .thenBy { it.id },
        )
        .toList()
}

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
        .withZone(ZoneId.of("Asia/Baku")).format(instant)
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
    if (!m.exactKickoff) {
        val date = fixtureLocalDate(m)
        val today = Instant.ofEpochMilli(nowMillis).atZone(MATCH_DISPLAY_ZONE).toLocalDate()
        return when {
            date == null -> "Дата матча неизвестна"
            date.isBefore(today) -> "Матч прошёл · итоговый счёт не получен"
            else -> "Ожидается · время начала не указано"
        }
    }
    val now=Instant.ofEpochMilli(nowMillis).atZone(ZoneId.of("Asia/Baku"))
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
