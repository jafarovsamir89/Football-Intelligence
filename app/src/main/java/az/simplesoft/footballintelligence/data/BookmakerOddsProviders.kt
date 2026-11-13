package az.simplesoft.footballintelligence.data

import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import androidx.lifecycle.Lifecycle
import java.util.concurrent.atomic.AtomicLong

/** Status of the most recent parsed page capture; local model linking remains a separate exact-match check. */
enum class OddsProviderStatus { LIVE, STALE, UNAVAILABLE, BLOCKED, ERROR }

fun visibleBookmakerStatus(
    status:OddsProviderStatus?,
    lastCheckedAtMillis:Long?,
    freshUpcomingCount:Int,
    nowMillis:Long=System.currentTimeMillis(),
):String {
    if(status==null) return "ОЖИДАЕТ ПРОВЕРКИ"
    if(status!=OddsProviderStatus.LIVE) return status.name
    val checked=lastCheckedAtMillis ?: return OddsProviderStatus.STALE.name
    if(checked>nowMillis+60_000L || nowMillis-checked>BOOKMAKER_CAPTURE_INTERVAL_MILLIS) return OddsProviderStatus.STALE.name
    return if(freshUpcomingCount>0) OddsProviderStatus.LIVE.name else "НЕТ БУДУЩИХ ЛИНИЙ"
}

data class BookmakerOddsSnapshot(
    val providerId: String,
    /** Stable local identity derived from visible date, teams and kickoff; not an operator event id. */
    val eventId: String,
    val sourceUrl: String,
    val capturedAtMillis: Long,
    val kickoffMillis: Long,
    val market: String,
    val homeTeam: String,
    val awayTeam: String,
    val home: Double,
    val draw: Double,
    val away: Double,
    val eventDate: String,
    val timeZoneId: String,
    val matchId: Int? = null,
    val eventYearInferred: Boolean = false,
) {
    init {
        require(providerId.isNotBlank() && eventId.isNotBlank())
        require(sourceUrl.startsWith("https://"))
        require(market == "1X2")
        require(homeTeam.isNotBlank() && awayTeam.isNotBlank())
        require(listOf(home, draw, away).all { it.isFinite() && it > 1.0 && it <= 1000.0 })
        require(timeZoneId.isNotBlank())
    }
}

fun latestBookmakerSnapshotsPerEvent(snapshots:List<BookmakerOddsSnapshot>):List<BookmakerOddsSnapshot> =
    snapshots.groupBy { it.providerId to it.eventId }.values.mapNotNull { rows ->
        rows.maxByOrNull { it.capturedAtMillis }
    }.sortedBy { it.kickoffMillis }

internal const val BOOKMAKER_CAPTURE_INTERVAL_MILLIS = 30L * 60 * 1000

/** Enforces one WebView capture window across Activity pause/resume transitions. */
internal fun shouldCollectPublicOdds(lifecycleState: Lifecycle.State): Boolean =
    lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

/** Delay before the next public-page request; malformed future timestamps never delay it over a window. */
internal fun bookmakerCaptureDelayMillis(lastCheckedAtMillis: Long?, nowMillis: Long): Long {
    if (lastCheckedAtMillis == null || lastCheckedAtMillis > nowMillis + 60_000L) return 0L
    val age = (nowMillis - lastCheckedAtMillis).coerceAtLeast(0L)
    return (BOOKMAKER_CAPTURE_INTERVAL_MILLIS - age).coerceAtLeast(0L)
}

/** Coalesces duplicate WebView page-finished callbacks into one capture per freshness window. */
internal class ForegroundBookmakerCaptureGate(
    private val intervalMillis: Long = BOOKMAKER_CAPTURE_INTERVAL_MILLIS,
) {
    private var captureInProgress = false
    private var nextAllowedAtMillis = 0L

    init {
        require(intervalMillis > 0L)
    }

    @Synchronized fun tryStart(nowMillis: Long): Boolean {
        if (captureInProgress || nowMillis < nextAllowedAtMillis) return false
        captureInProgress = true
        return true
    }

    @Synchronized fun finish(capturedAtMillis: Long) {
        captureInProgress = false
        nextAllowedAtMillis = capturedAtMillis + intervalMillis
    }

    @Synchronized fun cancelInProgress() {
        captureInProgress = false
    }

    @Synchronized fun isCaptureInProgress(): Boolean = captureInProgress

    @Synchronized fun millisUntilAllowed(nowMillis: Long): Long =
        (nextAllowedAtMillis - nowMillis).coerceAtLeast(0L)
}

/** Invalidates a page read when the Activity pauses, without losing the completed refresh limit. */
internal class ForegroundBookmakerCaptureSession(
    intervalMillis: Long = BOOKMAKER_CAPTURE_INTERVAL_MILLIS,
) {
    val gate = ForegroundBookmakerCaptureGate(intervalMillis)
    private val generation = AtomicLong(0L)

    fun currentGeneration(): Long = generation.get()

    fun isCurrent(token: Long): Boolean = token == generation.get()

    fun invalidate() {
        generation.incrementAndGet()
        gate.cancelInProgress()
    }
}

private fun publicRouteLabel(rawUrl:String):String = runCatching {
    val uri=java.net.URI(rawUrl)
    "${uri.host ?: "unknown"}${uri.path.orEmpty()}".take(160)
}.getOrDefault("некорректный URL")

data class BookmakerProviderStatus(
    val providerId: String,
    val displayName: String,
    val status: OddsProviderStatus,
    val checkedAtMillis: Long?,
    val detail: String,
)

interface BookmakerOddsProvider {
    val providerId: String
    val displayName: String

    /** Synchronous fetch is intentionally empty: page loading belongs to a foreground WebView. */
    fun fetchPrematchOdds(): List<BookmakerOddsSnapshot>
    fun status(): BookmakerProviderStatus
}

data class FixtureIdentity(
    val matchId: Int,
    val homeTeam: String,
    val awayTeam: String,
    val kickoffMillis: Long,
    val exactKickoff: Boolean = true,
)

/** Strictly links one visible bookmaker event to one local fixture using reviewed team aliases. */
fun uniqueExactFixtureMatchId(
    snapshot: BookmakerOddsSnapshot,
    fixtures: List<FixtureIdentity>,
    kickoffToleranceMillis: Long = 20 * 60 * 1000L,
): Int? {
    require(kickoffToleranceMillis >= 0)
    val zone = runCatching { ZoneId.of(snapshot.timeZoneId) }.getOrNull() ?: return null
    val eventDate = Instant.ofEpochMilli(snapshot.kickoffMillis).atZone(zone).toLocalDate()
    val candidates = fixtures.filter { fixture ->
        fixture.exactKickoff &&
        Instant.ofEpochMilli(fixture.kickoffMillis).atZone(zone).toLocalDate() == eventDate &&
            TeamNameIdentity.canonical(fixture.homeTeam) == TeamNameIdentity.canonical(snapshot.homeTeam) &&
            TeamNameIdentity.canonical(fixture.awayTeam) == TeamNameIdentity.canonical(snapshot.awayTeam) &&
            kotlin.math.abs(fixture.kickoffMillis - snapshot.kickoffMillis) <= kickoffToleranceMillis
    }
    return candidates.singleOrNull()?.matchId
}

data class MisliVisiblePage(
    val url: String,
    val title: String,
    val visibleText: String,
    val timeZoneId: String,
)

data class MisliParseResult(
    val snapshots: List<BookmakerOddsSnapshot>,
    val rejectedRows: Int,
    val detail: String,
)

data class TopazParseResult(
    val snapshots: List<BookmakerOddsSnapshot>,
    val rejectedRows: Int,
    val detail: String,
)

/**
 * Parser for text rendered in the ordinary public Misli football page. It accepts only complete,
 * visible 1X2 rows with a future local kickoff. It does not call undocumented endpoints.
 */
class MisliOddsProvider : BookmakerOddsProvider {
    override val providerId = "misli"
    override val displayName = "Misli.az"
    private var lastCheckedAtMillis: Long? = null
    private var lastCount = 0
    private var lastError: String? = null

    override fun fetchPrematchOdds(): List<BookmakerOddsSnapshot> = emptyList()

    fun parseVisiblePage(
        page: MisliVisiblePage,
        capturedAtMillis: Long,
        fixtures: List<FixtureIdentity> = emptyList(),
    ): MisliParseResult {
        lastCheckedAtMillis = capturedAtMillis
        lastCount = 0
        lastError = null
        val validUrl = runCatching {
            val uri = java.net.URI(page.url)
            uri.scheme == "https" && uri.host in setOf("misli.az", "www.misli.az") &&
                uri.path == "/idman-novleri/futbol"
        }.getOrDefault(false)
        if (!validUrl) return failed("Страница Misli открыла маршрут ${publicRouteLabel(page.url)} вне ожидаемой публичной футбольной страницы.")
        val zone = runCatching { ZoneId.of(page.timeZoneId) }.getOrElse {
            return failed("Не удалось определить часовой пояс страницы.")
        }
        if (page.visibleText.length > MAX_TEXT_LENGTH) return failed("Текст страницы превышает безопасный лимит.")
        if (page.title.isBlank() || page.visibleText.isBlank()) return failed("Публичная страница не вернула видимые события.")

        val now = Instant.ofEpochMilli(capturedAtMillis).atZone(zone)
        val localToday = now.toLocalDate()
        val lines = page.visibleText.lineSequence().map(String::trim).toList()
        var sectionDate: LocalDate? = null
        val rawSnapshots = mutableListOf<BookmakerOddsSnapshot>()
        var rejected = 0

        for (i in lines.indices) {
            val line = lines[i]
            val explicitDate = parseExplicitDate(line)
            if (explicitDate != null) {
                sectionDate = explicitDate
                continue
            }
            when (line.lowercase(Locale.ROOT)) {
                "bu gün", "bugün" -> sectionDate = localToday
                "sabah" -> sectionDate = localToday.plusDays(1)
            }
            if (!TIME.matches(line)) continue

            val eventDate = sectionDate
            val parsed = if (eventDate == null) null else parseRow(lines, i, page, zone, eventDate, capturedAtMillis)
            if (parsed == null || parsed.kickoffMillis <= capturedAtMillis) {
                rejected++
            } else {
                rawSnapshots += parsed
            }
        }

        // Duplicate event cards can appear in more than one visible section. Identical copies
        // collapse; conflicting prices are rejected rather than arbitrarily selected.
        val snapshots = rawSnapshots.groupBy { it.eventId }.values.mapNotNull { duplicates ->
            val distinctValues = duplicates.distinctBy { listOf(it.home, it.draw, it.away) }
            if (distinctValues.size == 1) distinctValues.first() else {
                rejected += duplicates.size
                null
            }
        }.map { snapshot -> snapshot.copy(matchId = uniqueExactFixtureMatchId(snapshot, fixtures)) }
            .sortedBy { it.kickoffMillis }

        lastCount = snapshots.size
        if (snapshots.isEmpty()) lastError = "На публичной странице не найдено проверяемых будущих prematch-линий 1X2."
        return MisliParseResult(snapshots, rejected, lastError ?: "Получено видимых prematch-линий: ${snapshots.size}.")
    }

    override fun status(): BookmakerProviderStatus {
        val status = when {
            lastCheckedAtMillis == null -> OddsProviderStatus.UNAVAILABLE
            lastCount > 0 -> OddsProviderStatus.LIVE
            else -> OddsProviderStatus.UNAVAILABLE
        }
        val message = lastError ?: if (lastCount > 0) {
            "Стандартный WebView прочитал ${lastCount} будущих видимых линий 1X2; цены имеют время снимка."
        } else {
            "Ожидается чтение открытой страницы Misli без входа в аккаунт."
        }
        return BookmakerProviderStatus(providerId, displayName, status, lastCheckedAtMillis, message)
    }

    private fun failed(message: String): MisliParseResult {
        lastError = message
        return MisliParseResult(emptyList(), 0, message)
    }

    private fun parseRow(
        lines: List<String>,
        timeIndex: Int,
        page: MisliVisiblePage,
        zone: ZoneId,
        eventDate: LocalDate,
        capturedAtMillis: Long,
    ): BookmakerOddsSnapshot? {
        var cursor = timeIndex + 1
        // Misli's visible card has a market-count badge between kickoff and the home team.
        if (cursor < lines.size && lines[cursor].matches(Regex("^\\d{1,3}$"))) cursor++
        if (cursor + 8 >= lines.size) return null

        var home: String
        var away: String
        if (lines[cursor + 1] == "-") {
            home = lines[cursor]
            away = lines[cursor + 2]
            cursor += 3
        } else {
            val paired = splitVisiblePair(lines[cursor]) ?: return null
            home = paired.first
            away = paired.second
            cursor++
        }
        if (listOf(home, away).any { it.isBlank() || it.length > 100 }) return null
        // Only accept the labeled 1, X, 2 sequence; this excludes live score text and other markets.
        if (lines.getOrNull(cursor) != "1") return null
        val homeOdds = parseOdds(lines.getOrNull(cursor + 1)) ?: return null
        if (lines.getOrNull(cursor + 2)?.uppercase(Locale.ROOT) != "X") return null
        val drawOdds = parseOdds(lines.getOrNull(cursor + 3)) ?: return null
        if (lines.getOrNull(cursor + 4) != "2") return null
        val awayOdds = parseOdds(lines.getOrNull(cursor + 5)) ?: return null

        val time = runCatching { LocalDateTime.parse("${eventDate} ${lines[timeIndex]}", ROW_TIME) }.getOrNull() ?: return null
        val kickoff = time.atZone(zone).toInstant().toEpochMilli()
        if (kickoff <= capturedAtMillis) return null
        val homeKey = TeamNameIdentity.canonical(home)
        val awayKey = TeamNameIdentity.canonical(away)
        if (homeKey.isBlank() || awayKey.isBlank() || homeKey == awayKey) return null
        val eventId = "misli:${eventDate}:${time.format(DateTimeFormatter.ofPattern("HHmm"))}:$homeKey:$awayKey"
        return BookmakerOddsSnapshot(
            providerId = providerId,
            eventId = eventId,
            sourceUrl = "https://www.misli.az/idman-novleri/futbol",
            capturedAtMillis = capturedAtMillis,
            kickoffMillis = kickoff,
            market = "1X2",
            homeTeam = home,
            awayTeam = away,
            home = homeOdds,
            draw = drawOdds,
            away = awayOdds,
            eventDate = eventDate.toString(),
            timeZoneId = zone.id,
        )
    }

    private fun splitVisiblePair(value: String): Pair<String, String>? {
        val match = Regex("^(.+?)\\s+[–—-]\\s+(.+)$").matchEntire(value) ?: return null
        return match.groupValues[1].trim() to match.groupValues[2].trim()
    }

    private fun parseOdds(value: String?): Double? = value?.replace(',', '.')?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it > 1.0 && it <= 1000.0 }

    private fun parseExplicitDate(value: String): LocalDate? = try {
        if (DATE.matches(value)) LocalDate.parse(value, EXPLICIT_DATE) else null
    } catch (_: DateTimeParseException) { null }

    companion object {
        private const val MAX_TEXT_LENGTH = 100_000
        private val TIME = Regex("^\\d{1,2}:\\d{2}$")
        private val DATE = Regex("^\\d{2}\\.\\d{2}\\.\\d{4}$")
        private val EXPLICIT_DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu")
        private val ROW_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
    }
}

/** Reviewed aliases plus club-form normalization and Unicode cleanup; no fuzzy similarity is used. */
object TeamNameIdentity {
    private val aliases = mapOf(
        "haken" to "hacken",
        "hacken" to "hacken",
        "celsi" to "chelsea",
        "chelsea" to "chelsea",
        "fc paris" to "paris fc",
        "paris fc" to "paris fc",
        "manchester city" to "manchester city",
        "manchester siti" to "manchester city",
        "man city" to "manchester city",
        "man utd" to "manchester united",
        "manchester utd" to "manchester united",
        "manchester united" to "manchester united",
        "psj" to "paris saint germain",
        "paris saint germain" to "paris saint germain",
        "bavariya" to "bayern munich",
        "bayern munich" to "bayern munich",
        "bayern munchen" to "bayern munich",
        "turkiye" to "turkey",
        "turkei" to "turkey",
        "turkey" to "turkey",
        "belcika" to "belgium",
        "belgien" to "belgium",
        "belgium" to "belgium",
        "italya" to "italy",
        "italiya" to "italy",
        "italien" to "italy",
        "italy" to "italy",
        "fransa" to "france",
        "frankreich" to "france",
        "france" to "france",
        "ingiltere" to "england",
        "england" to "england",
        "xorvatiya" to "croatia",
        "kroatien" to "croatia",
        "croatia" to "croatia",
        "cexiya" to "czech republic",
        "czechia" to "czech republic",
        "tschechien" to "czech republic",
        "czech republic" to "czech republic",
        "ispaniya" to "spain",
        "spanien" to "spain",
        "spain" to "spain",
        "albaniya" to "albania",
        "albanien" to "albania",
        "albania" to "albania",
        "finlandiya" to "finland",
        "finnland" to "finland",
        "finland" to "finland",
        "cernoqoriya" to "montenegro",
        "montenegro" to "montenegro",
        "latviya" to "latvia",
        "lettland" to "latvia",
        "latvia" to "latvia",
        "moldova respublikasi" to "moldova",
        "moldawien" to "moldova",
        "moldova" to "moldova",
        "qazaxstan" to "kazakhstan",
        "kasachstan" to "kazakhstan",
        "kazakhstan" to "kazakhstan",
        "niderland" to "netherlands",
        "niederlande" to "netherlands",
        "netherlands" to "netherlands",
        "yunanistan" to "greece",
        "griechenland" to "greece",
        "greece" to "greece",
        "serbiya" to "serbia",
        "serbien" to "serbia",
        "serbia" to "serbia",
        "almaniya" to "germany",
        "deutschland" to "germany",
        "germany" to "germany",
        "lixtensteyn" to "liechtenstein",
        "liechtenstein" to "liechtenstein",
        "azerbaycan" to "azerbaijan",
        "aserbaidschan" to "azerbaijan",
        "azerbaijan" to "azerbaijan",
        "bolqaristan" to "bulgaria",
        "bulgarien" to "bulgaria",
        "bulgaria" to "bulgaria",
        "islandiya" to "iceland",
        "island" to "iceland",
        "iceland" to "iceland",
        "luksemburq" to "luxembourg",
        "luxembourg" to "luxembourg",
        "luxemburg" to "luxembourg",
        "estoniya" to "estonia",
        "estonia" to "estonia",
        "estland" to "estonia",
        "slovakiya" to "slovakia",
        "slovakia" to "slovakia",
        "slowakei" to "slovakia",
        "farer adalari" to "faroe islands",
        "faroer inseln" to "faroe islands",
        "faroe islands" to "faroe islands",
        "simali makedoniya" to "north macedonia",
        "nordmazedonien" to "north macedonia",
        "north macedonia" to "north macedonia",
        "sloveniya" to "slovenia",
        "slowenien" to "slovenia",
        "slovenia" to "slovenia",
        "isvecere" to "switzerland",
        "isvecre" to "switzerland",
        "switzerland" to "switzerland",
        "schweiz" to "switzerland",
        "cebeli tariq" to "gibraltar",
        "gibraltar" to "gibraltar",
        "kosovo" to "kosovo",
        "israil" to "israel",
        "israel" to "israel",
        "norvec" to "norway",
        "norway" to "norway",
        "uels" to "wales",
        "wales" to "wales",
        "sotlandiya" to "scotland",
        "scotland" to "scotland",
        "schottland" to "scotland",
        "gurcustan" to "georgia",
        "georgia" to "georgia",
        "georgien" to "georgia",
        "macaristan" to "hungary",
        "hungary" to "hungary",
        "ungarn" to "hungary",
        "portuqaliya" to "portugal",
        "portugal" to "portugal",
        "danimarka" to "denmark",
        "denmark" to "denmark",
        "daenemark" to "denmark",
        "danemark" to "denmark",
        "ruminiya" to "romania",
        "romania" to "romania",
        "rumaenien" to "romania",
        "rumanien" to "romania",
        "polsa" to "poland",
        "poland" to "poland",
        "polen" to "poland",
        "isvec" to "sweden",
        "sweden" to "sweden",
        "schweden" to "sweden",
        "bosniya ve herseqovina" to "bosnia and herzegovina",
        "bosnia and herzegovina" to "bosnia and herzegovina",
        "bosnien und herzegowina" to "bosnia and herzegovina",
        "avstriya" to "austria",
        "austria" to "austria",
        "osterreich" to "austria",
        "irlandiya" to "ireland",
        "ireland" to "ireland",
        "irland" to "ireland",
        "simali irlandiya" to "northern ireland",
        "northern ireland" to "northern ireland",
        "nordirland" to "northern ireland",
        "ukrayna" to "ukraine",
        "ukraine" to "ukraine",
        "roma" to "roma",
        "fk servette" to "servette",
        "servette" to "servette",
    )

    fun canonical(name: String): String {
        val withoutQualifier = name.replace(Regex("\\s*\\(Q\\)\\s*$", RegexOption.IGNORE_CASE), "")
        val ascii = Normalizer.normalize(withoutQualifier, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace('ə','e')
            .replace('ı','i')
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
        aliases[ascii]?.let { return it }
        val withoutClubMarker = ascii
            .replace(Regex("^(afc|fc|cf|sc|cd|ud)\\s+"), "")
            .replace(Regex("\\s+(association football club|football club|futbol club|afc|fc|cf|sc|cd|ud|calcio)$"), "")
            .trim()
        return aliases[withoutClubMarker] ?: withoutClubMarker
    }
}

class TopazOddsProvider : BookmakerOddsProvider {
    override val providerId = "topaz"
    override val displayName = "TOPAZ / eTopaz"
    private var lastCheckedAtMillis: Long? = null
    private var lastCount = 0
    private var lastError: String? = null

    override fun fetchPrematchOdds(): List<BookmakerOddsSnapshot> = emptyList()

    /** Parses the visible event rows on eTopaz's ordinary English pre-match page. */
    fun parseVisiblePage(
        page: MisliVisiblePage,
        capturedAtMillis: Long,
        fixtures: List<FixtureIdentity> = emptyList(),
    ): TopazParseResult {
        lastCheckedAtMillis=capturedAtMillis
        lastCount=0
        lastError=null
        if(!isSupportedPublicRoute(page.url)) return failed("Страница eTopaz открыла маршрут ${publicRouteLabel(page.url)} вне публичной вкладки Pre-match.")
        val zone=runCatching { ZoneId.of(page.timeZoneId) }.getOrElse { return failed("Не удалось определить часовой пояс страницы.") }
        if(page.visibleText.length>100_000) return failed("Текст страницы превышает безопасный лимит.")
        if(page.title.isBlank()||page.visibleText.isBlank()) return failed("Публичная страница не вернула видимые события.")
        val now=Instant.ofEpochMilli(capturedAtMillis).atZone(zone)
        val lines=page.visibleText.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val parsed=mutableListOf<BookmakerOddsSnapshot>()
        var rejected=0
        for(i in lines.indices) {
            val dateLabel=parseDateLabel(lines[i]) ?: continue
            val event=parseVisibleEvent(lines,i,dateLabel)
            if(event==null) { rejected++;continue }
            val targetDate=resolveUpcomingDate(dateLabel.first,dateLabel.second,now.toLocalDate())
            if(targetDate==null) { rejected++;continue }
            val time=event.time
            val eventDateTime=runCatching { LocalDateTime.parse("$targetDate $time",TOPAZ_ROW_TIME) }.getOrNull()
            val kickoff=eventDateTime?.atZone(zone)?.toInstant()?.toEpochMilli()
            if(kickoff==null||kickoff<=capturedAtMillis) { rejected++;continue }
            val odds=parseLabeledOneXTwo(lines,event.oddsStart)
            if(odds==null) { rejected++;continue }
            val homeKey=TeamNameIdentity.canonical(event.home)
            val awayKey=TeamNameIdentity.canonical(event.away)
            if(homeKey.isBlank()||awayKey.isBlank()||homeKey==awayKey) { rejected++;continue }
            val eventDate=targetDate.toString()
            val eventId="topaz:$eventDate:${time.replace(":", "")}:$homeKey:$awayKey"
            val snapshot=BookmakerOddsSnapshot(
                providerId=providerId,eventId=eventId,sourceUrl=page.url,capturedAtMillis=capturedAtMillis,
                kickoffMillis=kickoff,market="1X2",homeTeam=event.home,awayTeam=event.away,
                home=odds.first,draw=odds.second,away=odds.third,eventDate=eventDate,timeZoneId=zone.id,
                eventYearInferred=true,
            )
            parsed+=snapshot.copy(matchId=uniqueExactFixtureMatchId(snapshot,fixtures))
        }
        val snapshots=parsed.groupBy { it.eventId }.values.mapNotNull { rows ->
            val prices=rows.distinctBy { Triple(it.home,it.draw,it.away) }
            if(prices.size==1) prices.first() else { rejected+=rows.size;null }
        }.sortedBy { it.kickoffMillis }
        lastCount=snapshots.size
        if(snapshots.isEmpty()) {
            lastError = if (page.visibleText.contains("There are currently no events to show for this sport", ignoreCase = true)) {
                "Публичная страница eTopaz сообщает, что сейчас нет событий для этого вида спорта."
            } else {
                "Публичная страница не содержит проверяемых будущих prematch-линий 1X2."
            }
        }
        return TopazParseResult(snapshots,rejected,lastError ?: "Получено видимых prematch-линий: ${snapshots.size}.")
    }

    override fun status():BookmakerProviderStatus {
        val state=if(lastCheckedAtMillis==null) OddsProviderStatus.UNAVAILABLE else if(lastCount>0) OddsProviderStatus.LIVE else OddsProviderStatus.UNAVAILABLE
        return BookmakerProviderStatus(providerId,displayName,state,lastCheckedAtMillis,
            lastError ?: if(lastCount>0) "Получено видимых будущих prematch-линий: $lastCount." else "Нет подтверждённых публичных будущих линий eTopaz.")
    }

    private fun failed(detail:String):TopazParseResult { lastError=detail;return TopazParseResult(emptyList(),0,detail) }

    private fun parseDateLabel(value:String):Triple<Int,Int,String?>? {
        val match=TOPAZ_DATE.matchEntire(value) ?: return null
        val day=match.groupValues[1].toIntOrNull() ?: return null
        val month=TOPAZ_MONTHS[match.groupValues[2].lowercase(Locale.ROOT)] ?: return null
        return Triple(day,month,match.groupValues[3].ifBlank { null })
    }

    private fun resolveUpcomingDate(day:Int,month:Int,today:LocalDate):LocalDate? {
        val sameYear=runCatching { LocalDate.of(today.year,month,day) }.getOrNull() ?: return null
        val candidate=if(sameYear>=today) sameYear else runCatching { sameYear.plusYears(1) }.getOrNull() ?: return null
        return candidate.takeIf { !it.isAfter(today.plusDays(90)) }
    }

    private data class VisibleEvent(
        val time:String,
        val home:String,
        val away:String,
        val oddsStart:Int,
    )

    private fun parseVisibleEvent(lines:List<String>,dateIndex:Int,date:Triple<Int,Int,String?>):VisibleEvent? {
        val inlineTime=date.third
        if(inlineTime!=null) {
            val teams=(dateIndex-1 downTo maxOf(0,dateIndex-5))
                .firstNotNullOfOrNull { candidate -> splitVisiblePair(lines[candidate]) }
                ?: return null
            return VisibleEvent(inlineTime,teams.first,teams.second,dateIndex+1)
        }

        val time=lines.getOrNull(dateIndex+1)?.takeIf(TOPAZ_TIME::matches) ?: return null
        val home=lines.getOrNull(dateIndex+2)?.takeIf(::isTeamLabel) ?: return null
        val away=lines.getOrNull(dateIndex+3)?.takeIf(::isTeamLabel) ?: return null
        return VisibleEvent(time,home,away,dateIndex+4)
    }

    private fun isTeamLabel(value:String):Boolean =
        value.length in 2..64 && TOPAZ_TEAM_LABEL.matches(value) &&
            parseDateLabel(value)==null && !TOPAZ_TIME.matches(value) &&
            value !in setOf("1","2","x","X","Pre-match","Live") &&
            parseOdds(value)==null && !value.startsWith("+")

    private fun parseLabeledOneXTwo(lines:List<String>,start:Int):Triple<Double,Double,Double>? {
        var i=start
        while(i<minOf(lines.size,start+16)) {
            if(lines.getOrNull(i)=="1") {
                val prices=mutableListOf<Double>()
                var cursor=i+1
                while(cursor<minOf(lines.size,i+13)) {
                    val value=lines[cursor]
                    if(parseDateLabel(value)!=null) return null
                    if(value.equals("x",ignoreCase=true)||value=="2") {
                        cursor++
                        continue
                    }
                    val price=parseOdds(value)
                    if(price==null) break
                    prices+=price
                    if(prices.size==3) return Triple(prices[0],prices[1],prices[2])
                    cursor++
                }
            }
            if(parseDateLabel(lines.getOrNull(i).orEmpty())!=null) return null
            i++
        }
        return null
    }

    private fun splitVisiblePair(value:String):Pair<String,String>? {
        val match=Regex("^(.+?)\\s+[–—-]\\s+(.+)$").matchEntire(value) ?: return null
        return match.groupValues[1].trim() to match.groupValues[2].trim()
    }

    private fun parseOdds(value:String?):Double?=value?.replace(',','.')?.toDoubleOrNull()?.takeIf { it.isFinite()&&it>1.0&&it<=1000.0 }

    companion object {
        const val PUBLIC_PREMATCH_URL="https://etopaz.az/eng/home?match-type=pre-match"

        fun isSupportedPublicRoute(url:String):Boolean=runCatching {
            val uri=java.net.URI(url)
            uri.scheme=="https" && uri.host in setOf("etopaz.az","www.etopaz.az") &&
                uri.path=="/eng/home" && uri.query.orEmpty().split('&').any { parameter ->
                    parameter.substringBefore('=')=="match-type" && parameter.substringAfter('=',"")=="pre-match"
                }
        }.getOrDefault(false)

        private val TOPAZ_DATE=Regex("^(\\d{1,2})\\s+([\\p{L}]+)(?:\\s*\\|\\s*(\\d{1,2}:\\d{2}))?$",RegexOption.IGNORE_CASE)
        private val TOPAZ_TIME=Regex("^\\d{1,2}:\\d{2}$")
        private val TOPAZ_TEAM_LABEL=Regex("^[\\p{L}\\p{M}\\p{N}][\\p{L}\\p{M}\\p{N} .&'’()/-]*$")
        private val TOPAZ_ROW_TIME=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
        private val TOPAZ_MONTHS=mapOf(
            "yan" to 1,"yanvar" to 1,"fev" to 2,"fevral" to 2,"mar" to 3,"mart" to 3,
            "apr" to 4,"aprel" to 4,"may" to 5,"iyn" to 6,"iyun" to 6,"iyl" to 7,"iyul" to 7,
            "avq" to 8,"avqust" to 8,"sen" to 9,"sentyabr" to 9,"okt" to 10,"oktyabr" to 10,
            "noy" to 11,"noyabr" to 11,"nov" to 11,"dek" to 12,"dekabr" to 12,"dec" to 12,
            "jan" to 1,"feb" to 2,"jun" to 6,"jul" to 7,"aug" to 8,"sep" to 9,
            "oct" to 10,
        )
    }

}
