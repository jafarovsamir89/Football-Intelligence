package az.simplesoft.footballintelligence.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeParseException

const val THE_ODDS_API_PROVIDER_ID = "the_odds_api"
const val THE_ODDS_API_BASE_URL = "https://api.the-odds-api.com/v4"
const val THE_ODDS_API_REGION = "eu"
const val THE_ODDS_API_MARKET = "h2h"
const val THE_ODDS_API_DAILY_LEAGUE_LIMIT = 12
const val THE_ODDS_API_MINIMUM_CREDIT_RESERVE = 20
const val THE_ODDS_API_CATALOG_TTL_MILLIS = 7L * 24L * 60L * 60L * 1_000L
const val THE_ODDS_API_REFRESH_INTERVAL_HOURS = 24L

internal fun isTheOddsApiCatalogCacheFresh(cachedAtMillis: Long, nowMillis: Long): Boolean =
    cachedAtMillis > 0L && cachedAtMillis <= nowMillis &&
        nowMillis - cachedAtMillis < THE_ODDS_API_CATALOG_TTL_MILLIS

data class TheOddsApiSport(val key: String, val title: String)

/** Parses only documented V4 soccer 1X2 markets and retains the API's market timestamp. */
class TheOddsApiProvider {
    private val preferredSoccerKeys = listOf(
        "soccer_epl",
        "soccer_uefa_champs_league",
        "soccer_uefa_europa_league",
        "soccer_uefa_europa_conference_league",
        "soccer_germany_bundesliga",
        "soccer_spain_la_liga",
        "soccer_italy_serie_a",
        "soccer_france_ligue_one",
        "soccer_portugal_primeira_liga",
        "soccer_netherlands_eredivisie",
        "soccer_turkey_super_league",
        "soccer_efl_champ",
    ).withIndex().associate { it.value to it.index }

    fun parseOdds(
        responseBody: String,
        capturedAtMillis: Long,
        responseDateMillis: Long?,
        sportKey: String,
        region: String = THE_ODDS_API_REGION,
        fixtures: List<FixtureIdentity> = emptyList(),
    ): InternationalOddsParseResult {
        require(capturedAtMillis > 0L)
        require(sportKey.startsWith("soccer_"))
        val events = JSONArray(responseBody)
        val received = countMarketObservations(events)
        val serverTime = responseDateMillis
        if (serverTime == null || !OddsQualityGate.deviceClockVerified(capturedAtMillis, serverTime)) {
            return InternationalOddsParseResult(
                snapshots = emptyList(), status = InternationalOddsStatus.UNVERIFIED,
                receivedMatches = received, rejectedMatches = 0, staleMatches = 0, duplicateMatches = 0,
                unverifiedMatches = received, serverTimeMillis = serverTime,
                detail = "Время HTTP-ответа отсутствует или часы устройства расходятся; линии исключены.",
            )
        }

        val parsed = mutableListOf<InternationalOddsSnapshot>()
        var rejected = 0
        var stale = 0
        var duplicates = 0
        var unverified = 0

        for (eventIndex in 0 until events.length()) {
            val event = events.optJSONObject(eventIndex)
            if (event == null) {
                rejected += 1
                continue
            }
            val eventId = event.optString("id").trim()
            val home = event.optString("home_team").trim()
            val away = event.optString("away_team").trim()
            val league = event.optString("sport_title").trim()
            val kickoff = parseInstant(event.optString("commence_time"))
            val bookmakers = event.optJSONArray("bookmakers")
            if (eventId.isBlank() || home.isBlank() || away.isBlank() || league.isBlank() ||
                TeamNameIdentity.canonical(home).isBlank() || TeamNameIdentity.canonical(away).isBlank() ||
                TeamNameIdentity.canonical(home) == TeamNameIdentity.canonical(away) ||
                kickoff == null || kickoff <= serverTime || bookmakers == null
            ) {
                rejected += eventObservationCount(event)
                continue
            }

            var hasMarket = false
            for (bookmakerIndex in 0 until bookmakers.length()) {
                val bookmaker = bookmakers.optJSONObject(bookmakerIndex) ?: continue
                val bookmakerId = bookmaker.optString("key").trim()
                val bookmakerTitle = bookmaker.optString("title").trim()
                val markets = bookmaker.optJSONArray("markets")
                if (markets == null) continue
                for (marketIndex in 0 until markets.length()) {
                    val market = markets.optJSONObject(marketIndex) ?: continue
                    if (market.optString("key") != THE_ODDS_API_MARKET) continue
                    hasMarket = true
                    if (bookmakerId.isBlank() || bookmakerTitle.isBlank()) {
                        rejected++
                        continue
                    }
                    val updatedAt = parseInstant(market.optString("last_update"))
                    if (updatedAt == null) {
                        unverified++
                        continue
                    }
                    when (OddsQualityGate.sourcePollStatus(updatedAt, serverTime, explicitlyStale = false)) {
                        InternationalOddsStatus.STALE -> {
                            stale++
                            continue
                        }
                        InternationalOddsStatus.UNVERIFIED -> {
                            unverified++
                            continue
                        }
                        else -> Unit
                    }

                    val outcomes = market.optJSONArray("outcomes")
                    if (outcomes == null) {
                        rejected++
                        continue
                    }
                    val selections = mutableMapOf<String, Double>()
                    var invalid = false
                    for (outcomeIndex in 0 until outcomes.length()) {
                        val outcome = outcomes.optJSONObject(outcomeIndex) ?: continue
                        val label = TeamNameIdentity.canonical(outcome.optString("name").trim())
                        val key = when (label) {
                            TeamNameIdentity.canonical(home) -> "home"
                            TeamNameIdentity.canonical(away) -> "away"
                            "draw", "the draw", "tie", "x" -> "draw"
                            else -> continue
                        }
                        val price = outcome.optDouble("price", Double.NaN)
                        if (!price.isFinite() || price <= 1.0 || price > 1_000.0 || selections.put(key, price) != null) {
                            invalid = true
                        }
                    }
                    if (invalid || selections.keys != setOf("home", "draw", "away")) {
                        rejected++
                        continue
                    }

                    val sourceUrl = "$THE_ODDS_API_BASE_URL/sports/$sportKey/odds"
                    val fixtureLink = uniqueInternationalFixtureLink(home, away, kickoff, fixtures)
                    val candidate = InternationalOddsSnapshot(
                        providerId = THE_ODDS_API_PROVIDER_ID,
                        bookmakerId = bookmakerId,
                        eventId = eventId,
                        sourceUrl = sourceUrl,
                        league = league,
                        region = region,
                        capturedAtMillis = capturedAtMillis,
                        providerPolledAtMillis = updatedAt,
                        kickoffMillis = kickoff,
                        market = "1X2",
                        homeTeam = home,
                        awayTeam = away,
                        home = selections.getValue("home"),
                        draw = selections.getValue("draw"),
                        away = selections.getValue("away"),
                        // V4 exposes the update time for the full h2h market, not each outcome.
                        homePriceChangedAtMillis = updatedAt,
                        drawPriceChangedAtMillis = updatedAt,
                        awayPriceChangedAtMillis = updatedAt,
                        matchId = fixtureLink?.matchId,
                        matchLinkBasis = fixtureLink?.basis,
                    )
                    val duplicateIndex = parsed.indexOfFirst { existing ->
                        existing.bookmakerId == bookmakerId && OddsQualityGate.sameUnderlyingEvent(
                            existing.homeTeam, existing.awayTeam, existing.kickoffMillis,
                            candidate.homeTeam, candidate.awayTeam, candidate.kickoffMillis,
                        )
                    }
                    if (duplicateIndex >= 0) {
                        duplicates++
                        val existing = parsed[duplicateIndex]
                        if (candidate.providerPolledAtMillis > existing.providerPolledAtMillis ||
                            (candidate.providerPolledAtMillis == existing.providerPolledAtMillis &&
                                candidate.capturedAtMillis > existing.capturedAtMillis)
                        ) {
                            parsed[duplicateIndex] = candidate
                        }
                    } else {
                        parsed += candidate
                    }
                }
            }
            if (!hasMarket) rejected++
        }

        val status = when {
            parsed.isNotEmpty() -> InternationalOddsStatus.LIVE
            stale > 0 -> InternationalOddsStatus.STALE
            unverified > 0 -> InternationalOddsStatus.UNVERIFIED
            else -> InternationalOddsStatus.UNAVAILABLE
        }
        val detail = when (status) {
            InternationalOddsStatus.LIVE -> "Принято свежих prematch 1X2 линий из $sportKey: ${parsed.size}."
            InternationalOddsStatus.STALE -> "Ответ $sportKey содержит только устаревшие линии; они исключены."
            InternationalOddsStatus.UNVERIFIED -> "Время одной или нескольких линий $sportKey не подтверждено; они исключены."
            else -> "В ответе $sportKey нет подтверждённых будущих рынков 1X2."
        }
        return InternationalOddsParseResult(parsed, status, received, rejected, stale, duplicates, unverified, serverTime, detail)
    }

    fun parseActiveSoccerSports(responseBody: String): List<TheOddsApiSport> {
        val rows = JSONArray(responseBody)
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val key = row.optString("key").trim()
                val title = row.optString("title").trim()
                if (row.optBoolean("active", false) && key.startsWith("soccer_") && title.isNotBlank()) {
                    add(TheOddsApiSport(key, title))
                }
            }
        }.distinctBy { it.key }.sortedWith(
            compareBy<TheOddsApiSport> { preferredSoccerKeys[it.key] ?: Int.MAX_VALUE }.thenBy { it.key },
        )
    }

    /** Selects a bounded round-robin slice, so the free plan does not get spent in one launch. */
    fun nextSports(sports: List<TheOddsApiSport>, cursor: Int, limit: Int = THE_ODDS_API_DAILY_LEAGUE_LIMIT): List<TheOddsApiSport> {
        require(limit in 1..THE_ODDS_API_DAILY_LEAGUE_LIMIT)
        if (sports.isEmpty()) return emptyList()
        val start = Math.floorMod(cursor, sports.size)
        return List(minOf(limit, sports.size)) { offset -> sports[(start + offset) % sports.size] }
    }

    private fun countMarketObservations(events: JSONArray): Int {
        var count = 0
        for (eventIndex in 0 until events.length()) {
            val event = events.optJSONObject(eventIndex)
            if (event == null) {
                count++
                continue
            }
            count += eventObservationCount(event)
        }
        return count
    }

    private fun eventObservationCount(event: JSONObject): Int {
        val bookmakers = event.optJSONArray("bookmakers") ?: return 1
        var count = 0
        for (bookmakerIndex in 0 until bookmakers.length()) {
            val markets = bookmakers.optJSONObject(bookmakerIndex)?.optJSONArray("markets") ?: continue
            for (marketIndex in 0 until markets.length()) {
                if (markets.optJSONObject(marketIndex)?.optString("key") == THE_ODDS_API_MARKET) count++
            }
        }
        return count.coerceAtLeast(1)
    }

    private fun parseInstant(value: String): Long? = try {
        Instant.parse(value).toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    } catch (_: RuntimeException) {
        null
    }
}

fun theOddsApiSportsUrl(): String = "$THE_ODDS_API_BASE_URL/sports/"

fun theOddsApiOddsUrl(sportKey: String, region: String = THE_ODDS_API_REGION): String {
    require(sportKey.startsWith("soccer_"))
    require(region.matches(Regex("^[a-z0-9,]+$")))
    return "$THE_ODDS_API_BASE_URL/sports/$sportKey/odds/?regions=$region&markets=$THE_ODDS_API_MARKET&oddsFormat=decimal&dateFormat=iso"
}

fun theOddsApiAuthHeaders(apiKey:String):Map<String,String> {
    require(apiKey.isNotBlank())
    return mapOf("x-api-key" to apiKey)
}
