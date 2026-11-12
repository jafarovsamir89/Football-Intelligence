package az.simplesoft.footballintelligence.data

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import kotlin.math.abs

const val MOVIODDS_PROVIDER_ID = "moviodds_bet365"
const val MOVIODDS_API_URL = "https://moviodds.com/odds?sport=soccer&groups=40"
const val INTERNATIONAL_ODDS_FRESHNESS_MILLIS = 15L * 60L * 1000L
const val INTERNATIONAL_ODDS_APP_OPEN_STALE_MINUTES = 10L
const val INTERNATIONAL_ODDS_REFRESH_INTERVAL_MINUTES = 30L

enum class InternationalOddsStatus { LIVE, STALE, UNAVAILABLE, BLOCKED, ERROR, UNVERIFIED }
enum class InternationalOddsQuoteSide { BACK, LAY }

data class InternationalOddsQualityCounts(
    val receivedMatches: Int,
    val acceptedMatches: Int,
    val rejectedMatches: Int,
    val staleMatches: Int,
    val duplicateMatches: Int,
    val unverifiedMatches: Int,
) {
    init {
        val counts = listOf(receivedMatches, acceptedMatches, rejectedMatches, staleMatches, duplicateMatches, unverifiedMatches)
        require(counts.all { it >= 0 })
        require(receivedMatches == acceptedMatches + rejectedMatches + staleMatches + duplicateMatches + unverifiedMatches)
    }
}

data class InternationalOddsSyncState(
    val providerId: String,
    val status: InternationalOddsStatus,
    val lastCheckedAtMillis: Long,
    val lastSuccessAtMillis: Long?,
    val freshRecordsStored: Int,
    val detail: String?,
    val qualityCounts: InternationalOddsQualityCounts? = null,
)

data class InternationalOddsSyncAttempt(
    val checkedAtMillis: Long,
    val status: InternationalOddsStatus,
    val qualityCounts: InternationalOddsQualityCounts?,
    val detail: String?,
)

/**
 * A complete prematch 1X2 observation. `bookmakerId` names the bookmaker whose price the
 * intermediary reports; `providerId` records the intermediary and is never inferred from a quote.
 */
data class InternationalOddsSnapshot(
    val providerId: String,
    val bookmakerId: String,
    val eventId: String,
    val sourceUrl: String,
    val league: String,
    val region: String?,
    val capturedAtMillis: Long,
    val providerPolledAtMillis: Long,
    val kickoffMillis: Long,
    val market: String,
    val homeTeam: String,
    val awayTeam: String,
    val home: Double,
    val draw: Double,
    val away: Double,
    val homePriceChangedAtMillis: Long,
    val drawPriceChangedAtMillis: Long,
    val awayPriceChangedAtMillis: Long,
    val matchId: Int? = null,
    val matchLinkBasis: InternationalMatchLinkBasis? = null,
    val sourceMarketKey: String = "unknown",
    val quoteSide: InternationalOddsQuoteSide = InternationalOddsQuoteSide.BACK,
    val homeBetLimit: Double? = null,
    val drawBetLimit: Double? = null,
    val awayBetLimit: Double? = null,
) {
    init {
        require(providerId.isNotBlank() && bookmakerId.isNotBlank() && eventId.isNotBlank())
        require(sourceUrl.startsWith("https://"))
        require(market == "1X2" && league.isNotBlank())
        require(homeTeam.isNotBlank() && awayTeam.isNotBlank())
        require(listOf(home, draw, away).all { it.isFinite() && it > 1.0 && it <= 1000.0 })
        require(capturedAtMillis > 0L && providerPolledAtMillis > 0L && kickoffMillis > 0L)
        require(listOf(homePriceChangedAtMillis, drawPriceChangedAtMillis, awayPriceChangedAtMillis).all { it > 0L })
        require(sourceMarketKey.isNotBlank())
        require(listOf(homeBetLimit, drawBetLimit, awayBetLimit).filterNotNull().all { it.isFinite() && it > 0.0 })
    }
}

data class InternationalExchangeSpread(val home: Double, val draw: Double, val away: Double)

/** Returns Lay minus Back only for the same source snapshot, event, bookmaker, and 1X2 market. */
fun internationalExchangeSpread(
    back: InternationalOddsSnapshot,
    lay: InternationalOddsSnapshot,
): InternationalExchangeSpread? {
    if (back.quoteSide != InternationalOddsQuoteSide.BACK || lay.quoteSide != InternationalOddsQuoteSide.LAY) return null
    if (back.sourceMarketKey != "h2h" || lay.sourceMarketKey != "h2h_lay") return null
    if (back.providerId != lay.providerId || back.bookmakerId != lay.bookmakerId || back.eventId != lay.eventId) return null
    if (back.market != "1X2" || lay.market != "1X2" || back.kickoffMillis != lay.kickoffMillis) return null
    if (back.capturedAtMillis != lay.capturedAtMillis || back.providerPolledAtMillis != lay.providerPolledAtMillis) return null
    if (TeamNameIdentity.canonical(back.homeTeam) != TeamNameIdentity.canonical(lay.homeTeam) ||
        TeamNameIdentity.canonical(back.awayTeam) != TeamNameIdentity.canonical(lay.awayTeam)
    ) return null
    val home = lay.home - back.home
    val draw = lay.draw - back.draw
    val away = lay.away - back.away
    if (listOf(home, draw, away).any { !it.isFinite() || it < 0.0 }) return null
    return InternationalExchangeSpread(home, draw, away)
}

data class InternationalOddsParseResult(
    val snapshots: List<InternationalOddsSnapshot>,
    val status: InternationalOddsStatus,
    val receivedMatches: Int,
    val rejectedMatches: Int,
    val staleMatches: Int,
    val duplicateMatches: Int,
    val unverifiedMatches: Int,
    val serverTimeMillis: Long?,
    val detail: String,
) {
    val qualityCounts: InternationalOddsQualityCounts
        get() = InternationalOddsQualityCounts(
            receivedMatches = receivedMatches,
            acceptedMatches = snapshots.size,
            rejectedMatches = rejectedMatches,
            staleMatches = staleMatches,
            duplicateMatches = duplicateMatches,
            unverifiedMatches = unverifiedMatches,
        )
}

/** Shared price gate for provider time, complete outcomes, source identity, and duplicate events. */
object OddsQualityGate {
    private const val MAX_DEVICE_SERVER_CLOCK_SKEW_MILLIS = 5L * 60L * 1000L
    private const val FUTURE_TIMESTAMP_TOLERANCE_MILLIS = 2L * 60L * 1000L
    private const val EVENT_DUPLICATE_KICKOFF_TOLERANCE_MILLIS = 5L * 60_000L

    fun deviceClockVerified(capturedAtMillis:Long,serverTimeMillis:Long):Boolean =
        abs(serverTimeMillis-capturedAtMillis)<=MAX_DEVICE_SERVER_CLOCK_SKEW_MILLIS

    fun sourcePollStatus(polledAtMillis:Long,serverTimeMillis:Long,explicitlyStale:Boolean):InternationalOddsStatus? = when {
        explicitlyStale -> InternationalOddsStatus.STALE
        polledAtMillis>serverTimeMillis+FUTURE_TIMESTAMP_TOLERANCE_MILLIS -> InternationalOddsStatus.UNVERIFIED
        serverTimeMillis-polledAtMillis>INTERNATIONAL_ODDS_FRESHNESS_MILLIS -> InternationalOddsStatus.STALE
        else -> null
    }

    fun validPriceAndChangeTime(odds:Double,changedAtMillis:Long,polledAtMillis:Long,serverTimeMillis:Long):Boolean =
        odds.isFinite()&&odds>1.0&&odds<=1000.0&&changedAtMillis>0L&&
            changedAtMillis<=serverTimeMillis+FUTURE_TIMESTAMP_TOLERANCE_MILLIS&&
            changedAtMillis<=polledAtMillis+FUTURE_TIMESTAMP_TOLERANCE_MILLIS

    fun sameUnderlyingEvent(
        firstHome:String,firstAway:String,firstKickoffMillis:Long,
        secondHome:String,secondAway:String,secondKickoffMillis:Long,
    ):Boolean = TeamNameIdentity.canonical(firstHome).isNotBlank()&&
        TeamNameIdentity.canonical(firstHome)==TeamNameIdentity.canonical(secondHome)&&
        TeamNameIdentity.canonical(firstAway).isNotBlank()&&
        TeamNameIdentity.canonical(firstAway)==TeamNameIdentity.canonical(secondAway)&&
        abs(firstKickoffMillis-secondKickoffMillis)<=EVENT_DUPLICATE_KICKOFF_TOLERANCE_MILLIS

}

/** Parses only MoviOdds' documented, no-key soccer Full Time Result group (40) response. */
class Bet365ViaMoviOddsProvider {
    fun parse(
        responseBody: String,
        capturedAtMillis: Long,
        fixtures: List<FixtureIdentity> = emptyList(),
    ): InternationalOddsParseResult {
        require(capturedAtMillis > 0L)
        val root = JSONObject(responseBody)
        val rows = root.optJSONArray("matches") ?: error("MoviOdds response has no matches array.")
        val serverTime = parseInstant(root.optString("server_time"))
            ?: error("MoviOdds response has no valid server_time.")
        val received = rows.length()
        val warming = root.optBoolean("warming", false)
        if (!OddsQualityGate.deviceClockVerified(capturedAtMillis,serverTime)) {
            return InternationalOddsParseResult(
                snapshots = emptyList(), status = InternationalOddsStatus.UNVERIFIED,
                receivedMatches = received, rejectedMatches = 0, staleMatches = 0, duplicateMatches = 0,
                unverifiedMatches = received,
                serverTimeMillis = serverTime,
                detail = "Часы устройства и источник расходятся более чем на 5 минут; котировки не подтверждены.",
            )
        }

        val parsed = mutableListOf<InternationalOddsSnapshot>()
        var rejected = 0
        var stale = 0
        var unverified = 0
        var duplicates = 0

        for (index in 0 until rows.length()) {
            val match = rows.optJSONObject(index)
            if (match == null) {
                rejected++
                continue
            }
            val eventId = match.optString("id").trim()
            val home = match.optString("home").trim()
            val away = match.optString("away").trim()
            val league = match.optString("league").trim()
            val kickoff = parseInstant(match.optString("kickoff_utc"))
            if (eventId.isBlank() || home.isBlank() || away.isBlank() || league.isBlank() ||
                TeamNameIdentity.canonical(home).isBlank() ||
                TeamNameIdentity.canonical(away).isBlank() ||
                TeamNameIdentity.canonical(home) == TeamNameIdentity.canonical(away) ||
                kickoff == null || match.optString("sport", "soccer") != "soccer" || match.optBoolean("in_play", true)
            ) {
                rejected++
                continue
            }
            if (kickoff <= capturedAtMillis) {
                rejected++
                continue
            }

            val groupStatus = match.optJSONObject("group_status")?.optJSONObject("40")
            val pollAt = groupStatus?.let { parseInstant(it.optString("polled_at")) }
            if (groupStatus == null || pollAt == null) {
                unverified++
                continue
            }
            val pollStatus=OddsQualityGate.sourcePollStatus(pollAt,serverTime,groupStatus.optBoolean("stale",false))
            if (pollStatus!=null) {
                if(pollStatus==InternationalOddsStatus.STALE) stale++ else unverified++
                continue
            }

            val quotes = match.optJSONArray("quotes")
            if (quotes == null) {
                rejected++
                continue
            }
            val selections = linkedMapOf<OutcomeKey, ParsedSelection>()
            var invalidSelection = false
            for (quoteIndex in 0 until quotes.length()) {
                val quote = quotes.optJSONObject(quoteIndex) ?: continue
                if (quote.optString("group_id") != "40" || quote.optString("market_id") != "40") continue
                if (quote.optBoolean("suspended", false)) {
                    invalidSelection = true
                    continue
                }
                val label = quote.optString("label").trim()
                val outcome = when (TeamNameIdentity.canonical(label)) {
                    TeamNameIdentity.canonical(home) -> OutcomeKey.HOME
                    TeamNameIdentity.canonical(away) -> OutcomeKey.AWAY
                    "draw", "x" -> OutcomeKey.DRAW
                    else -> null
                } ?: continue
                val odds = quote.optDouble("odds_dec", Double.NaN)
                val changedAt = parseInstant(quote.optString("ts"))
                if (changedAt==null||!OddsQualityGate.validPriceAndChangeTime(odds,changedAt,pollAt,serverTime)) {
                    invalidSelection = true
                    continue
                }
                if (selections.put(outcome, ParsedSelection(odds, changedAt)) != null) invalidSelection = true
            }
            if (invalidSelection || selections.keys != OutcomeKey.values().toSet()) {
                rejected++
                continue
            }

            val h = selections.getValue(OutcomeKey.HOME)
            val d = selections.getValue(OutcomeKey.DRAW)
            val a = selections.getValue(OutcomeKey.AWAY)
            val fixtureLink = uniqueInternationalFixtureLink(home, away, kickoff, fixtures)
            val candidate = InternationalOddsSnapshot(
                providerId = MOVIODDS_PROVIDER_ID,
                bookmakerId = "bet365",
                eventId = eventId,
                sourceUrl = MOVIODDS_API_URL,
                league = league,
                region = match.optString("region").takeIf { it.isNotBlank() && it != "null" },
                capturedAtMillis = capturedAtMillis,
                providerPolledAtMillis = pollAt,
                kickoffMillis = kickoff,
                market = "1X2",
                homeTeam = home,
                awayTeam = away,
                home = h.odds,
                draw = d.odds,
                away = a.odds,
                homePriceChangedAtMillis = h.changedAt,
                drawPriceChangedAtMillis = d.changedAt,
                awayPriceChangedAtMillis = a.changedAt,
                matchId = fixtureLink?.matchId,
                matchLinkBasis = fixtureLink?.basis,
                sourceMarketKey = "group=40;market=40",
            )
            if(parsed.any { existing ->
                    OddsQualityGate.sameUnderlyingEvent(
                        existing.homeTeam,existing.awayTeam,existing.kickoffMillis,
                        candidate.homeTeam,candidate.awayTeam,candidate.kickoffMillis,
                    )
                }) {
                duplicates++
            } else {
                parsed+=candidate
            }
        }

        val status = when {
            parsed.isNotEmpty() -> InternationalOddsStatus.LIVE
            stale > 0 -> InternationalOddsStatus.STALE
            unverified > 0 -> InternationalOddsStatus.UNVERIFIED
            warming -> InternationalOddsStatus.UNAVAILABLE
            else -> InternationalOddsStatus.UNAVAILABLE
        }
        val detail = when (status) {
            InternationalOddsStatus.LIVE -> "Проверено линий bet365 через MoviOdds: ${parsed.size}; свежесть подтверждена временем опроса рынка."
            InternationalOddsStatus.STALE -> "MoviOdds вернул линии, но время подтверждения рынка устарело."
            InternationalOddsStatus.UNVERIFIED -> "Время опроса рынка отсутствует или не прошло проверку; котировки исключены."
            else -> "Сейчас нет подтверждённых будущих prematch-линий 1X2."
        }
        return InternationalOddsParseResult(parsed, status, received, rejected, stale, duplicates, unverified, serverTime, detail)
    }

    private enum class OutcomeKey { HOME, DRAW, AWAY }
    private data class ParsedSelection(val odds: Double, val changedAt: Long)

    private fun parseInstant(value: String): Long? = try {
        Instant.parse(value).toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    } catch (_: RuntimeException) {
        null
    }
}

enum class InternationalMatchLinkBasis {
    EXACT_KICKOFF,
    UNIQUE_DATE_ONLY,
    ;

    companion object {
        fun fromStorage(value: String?): InternationalMatchLinkBasis? =
            entries.firstOrNull { it.name == value }
    }
}

data class InternationalFixtureLink(
    val matchId: Int,
    val basis: InternationalMatchLinkBasis,
)

/**
 * Prefers a unique exact-time match. Date-only local rows may link only when there is no exact-time
 * candidate on that date and orientation, UTC date and normalized teams identify one local row.
 */
fun uniqueInternationalFixtureLink(
    homeTeam: String,
    awayTeam: String,
    kickoffMillis: Long,
    fixtures: List<FixtureIdentity>,
    kickoffToleranceMillis: Long = 20L * 60_000L,
): InternationalFixtureLink? {
    if (kickoffToleranceMillis < 0L || homeTeam.isBlank() || awayTeam.isBlank()) return null
    val eventDate = Instant.ofEpochMilli(kickoffMillis).atZone(ZoneOffset.UTC).toLocalDate()
    val homeKey = TeamNameIdentity.canonical(homeTeam)
    val awayKey = TeamNameIdentity.canonical(awayTeam)
    val sameIdentity = fixtures.filter { fixture ->
        Instant.ofEpochMilli(fixture.kickoffMillis).atZone(ZoneOffset.UTC).toLocalDate() == eventDate &&
            TeamNameIdentity.canonical(fixture.homeTeam) == homeKey &&
            TeamNameIdentity.canonical(fixture.awayTeam) == awayKey
    }
    val exactMatches = sameIdentity.filter { fixture ->
        fixture.exactKickoff && abs(fixture.kickoffMillis - kickoffMillis) <= kickoffToleranceMillis
    }
    if (exactMatches.size == 1) {
        return InternationalFixtureLink(exactMatches.single().matchId, InternationalMatchLinkBasis.EXACT_KICKOFF)
    }
    if (exactMatches.size > 1 || sameIdentity.any { it.exactKickoff }) return null
    val dateOnly = sameIdentity.filterNot { it.exactKickoff }.singleOrNull() ?: return null
    return InternationalFixtureLink(dateOnly.matchId, InternationalMatchLinkBasis.UNIQUE_DATE_ONLY)
}

/** UTC date, exact normalized team order, <=20 minute kickoff difference, one match only. */
fun uniqueExactInternationalFixtureMatchId(
    homeTeam: String,
    awayTeam: String,
    kickoffMillis: Long,
    fixtures: List<FixtureIdentity>,
    kickoffToleranceMillis: Long = 20L * 60_000L,
): Int? {
    return uniqueInternationalFixtureLink(homeTeam, awayTeam, kickoffMillis, fixtures, kickoffToleranceMillis)
        ?.takeIf { it.basis == InternationalMatchLinkBasis.EXACT_KICKOFF }
        ?.matchId
}

fun visibleInternationalOddsStatus(
    state: InternationalOddsSyncState?,
    freshCount: Int,
    nowMillis: Long = System.currentTimeMillis(),
): String = when {
    state == null -> "ОЖИДАЕТ ПРОВЕРКИ"
    state.status == InternationalOddsStatus.BLOCKED -> "BLOCKED"
    state.status == InternationalOddsStatus.ERROR -> "ERROR"
    state.status == InternationalOddsStatus.UNVERIFIED -> "UNVERIFIED"
    freshCount > 0 -> "LIVE"
    state.status == InternationalOddsStatus.STALE -> "STALE"
    state.lastCheckedAtMillis > nowMillis + 60_000L -> "UNVERIFIED"
    nowMillis - state.lastCheckedAtMillis > INTERNATIONAL_ODDS_FRESHNESS_MILLIS -> "STALE"
    else -> "UNAVAILABLE"
}
