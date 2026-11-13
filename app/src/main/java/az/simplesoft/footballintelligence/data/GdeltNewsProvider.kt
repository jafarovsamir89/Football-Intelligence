package az.simplesoft.footballintelligence.data

import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

const val GDELT_NEWS_PROVIDER_ID = "gdelt_football_news"
const val GDELT_NEWS_CACHE_KEY = "news:gdelt:football:v1"
const val GDELT_NEWS_ATTRIBUTION_URL = "https://www.gdeltproject.org/"
const val GDELT_NEWS_API_URL = "https://api.gdeltproject.org/api/v2/doc/doc"

data class FootballNewsItem(
    val title: String,
    val url: String,
    val sourceDomain: String,
    val language: String,
    /** GDELT's index/seen time; this is not asserted to be the publisher's publication time. */
    val indexedAtMillis: Long?,
    val capturedAtMillis: Long,
)

enum class FootballNewsTopic(val label: String) {
    INJURY("травмы · по заголовку"),
    SUSPENSION("дисквалификация · по заголовку"),
    LINEUP("состав · по заголовку"),
    TRANSFER("трансфер · по заголовку"),
    GENERAL("новость · по заголовку"),
}

data class FixtureNewsMatch(val team: String, val opponent: String)

object GdeltNewsProvider {
    private val seenDateFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT)
        .withZone(ZoneOffset.UTC)

    /** GDELT documents one OR-block; cap names so a single request stays small. */
    fun requestUrl(teamNames: List<String>, maximumTeams: Int = 12): String? {
        require(maximumTeams in 1..20)
        val teams = teamNames.asSequence()
            .map(String::trim)
            .filter { it.length in 4..80 && it.any(Char::isLetter) }
            .distinctBy(::normalizeForNews)
            .take(maximumTeams)
            .toList()
        if (teams.isEmpty()) return null
        val orTerms = teams.joinToString(" OR ") { "\"${it.replace("\\", " ").replace("\"", " ").trim()}\"" }
        val query = "($orTerms) AND soccer"
        return "$GDELT_NEWS_API_URL?query=${URLEncoder.encode(query, StandardCharsets.UTF_8.name())}" +
            "&mode=ArtList&format=json&maxrecords=100&sort=DateDesc&timespan=7d"
    }

    fun parse(body: String, capturedAtMillis: Long): List<FootballNewsItem> {
        require(body.length <= 2_000_000) { "News response exceeds 2 MiB." }
        val root = try {
            JSONObject(body)
        } catch (error: Exception) {
            val message = body.trim().take(240)
            throw IllegalArgumentException(
                if (message.contains("limit requests", ignoreCase = true)) "GDELT frequency limit: $message"
                else "GDELT returned a non-JSON response.",
                error,
            )
        }
        val articles = root.optJSONArray("articles") ?: return emptyList()
        val result = LinkedHashMap<String, FootballNewsItem>()
        for (index in 0 until minOf(articles.length(), 200)) {
            val row = articles.optJSONObject(index) ?: continue
            val title = row.optString("title").trim().take(240)
            val url = safeArticleUrl(row.optString("url")) ?: continue
            if (title.length < 8) continue
            val domain = row.optString("domain").trim().lowercase(Locale.ROOT).take(120)
                .ifBlank { runCatching { java.net.URI(url).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("") }
            val language = row.optString("language").trim().take(40)
            val indexTime = row.optString("seendate").trim().takeIf { it.isNotEmpty() }
                ?.let { runCatching { Instant.from(seenDateFormat.parse(it)).toEpochMilli() }.getOrNull() }
            result.putIfAbsent(url, FootballNewsItem(title, url, domain, language, indexTime, capturedAtMillis))
        }
        return result.values.toList()
    }

    /** Headline-only exact phrase linking deliberately avoids fuzzy/team-initial false positives. */
    fun matchHeadline(item: FootballNewsItem, fixtureTeams: List<String>): List<String> {
        val title = " ${normalizeForNews(item.title)} "
        return fixtureTeams.distinctBy(::normalizeForNews).filter { team ->
            val key = normalizeForNews(team)
            key.length >= 4 && title.contains(" $key ")
        }
    }

    fun classifyHeadline(title: String): FootballNewsTopic {
        val normalized = " ${normalizeForNews(title)} "
        fun containsAny(vararg terms: String) = terms.any { normalized.contains(" ${normalizeForNews(it)} ") }
        return when {
            containsAny("injury", "injured", "injury blow", "fitness doubt", "ruled out") -> FootballNewsTopic.INJURY
            containsAny("suspended", "suspension", "ban", "sent off") -> FootballNewsTopic.SUSPENSION
            containsAny("lineup", "starting xi", "team news", "squad") -> FootballNewsTopic.LINEUP
            containsAny("transfer", "signs", "signed", "joins") -> FootballNewsTopic.TRANSFER
            else -> FootballNewsTopic.GENERAL
        }
    }

    private fun safeArticleUrl(value: String): String? = runCatching {
        val uri = java.net.URI(value.trim())
        if (uri.scheme !in setOf("https", "http") || uri.host.isNullOrBlank()) null
        else uri.toASCIIString().takeIf { it.length <= 2_000 }
    }.getOrNull()

    private fun normalizeForNews(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
}

