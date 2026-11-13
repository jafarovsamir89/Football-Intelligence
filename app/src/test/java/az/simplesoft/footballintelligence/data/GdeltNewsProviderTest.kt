package az.simplesoft.footballintelligence.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class GdeltNewsProviderTest {
    @Test
    fun parsesOnlySafeDatedDeduplicatedHeadlineLinks() {
        val now = 1_800_000_000_000L
        val body = """{"articles":[
            {"title":"Arsenal injury update before the weekend fixture","url":"https://news.example/a","domain":"news.example","language":"English","seendate":"20261113T101500Z"},
            {"title":"Arsenal injury update before the weekend fixture","url":"https://news.example/a","domain":"news.example","language":"English","seendate":"20261113T101500Z"},
            {"title":"Short","url":"https://news.example/short"},
            {"title":"Unsafe link headline is ignored","url":"javascript:alert(1)"},
            {"title":"Roma completes signing ahead of the season","url":"https://news.example/roma","domain":"news.example","language":"English","seendate":"not-a-date"}
        ]}"""

        val result = GdeltNewsProvider.parse(body, now)

        assertEquals(2, result.size)
        assertEquals(now, result.first().capturedAtMillis)
        assertTrue(result.first().indexedAtMillis != null)
        assertNull(result.last().indexedAtMillis)
        assertEquals("news.example", result.first().sourceDomain)
    }

    @Test
    fun matchesOnlyWholeHeadlineTeamNamesAndClassifiesAsUnverifiedCue() {
        val item = FootballNewsItem(
            "Arsenal injury update; Roma defender suspended",
            "https://news.example/story", "news.example", "English", null, 100L,
        )

        assertEquals(listOf("Arsenal", "Roma"), GdeltNewsProvider.matchHeadline(item, listOf("Arsenal", "Roma", "Roma FC", "PSG")))
        assertEquals(FootballNewsTopic.INJURY, GdeltNewsProvider.classifyHeadline(item.title))
        assertEquals(FootballNewsTopic.SUSPENSION, GdeltNewsProvider.classifyHeadline("Roma player receives suspension after red card"))
        assertFalse(GdeltNewsProvider.matchHeadline(item.copy(title = "Arenal injury update"), listOf("Arsenal")).isNotEmpty())
    }

    @Test
    fun createsSmallEncodedOrQueryFromUpcomingTeams() {
        val url = GdeltNewsProvider.requestUrl(listOf("Manchester City", "Arsenal", "Arsenal", ""))!!
        val query = URLDecoder.decode(url.substringAfter("query=").substringBefore('&'), StandardCharsets.UTF_8.name())

        assertTrue(url.startsWith("https://api.gdeltproject.org/api/v2/doc/doc?"))
        assertTrue(query.contains("\"Manchester City\""))
        assertTrue(query.contains("(\"Manchester City\" OR \"Arsenal\") AND soccer"))
        assertNull(GdeltNewsProvider.requestUrl(emptyList()))
        assertTrue(url.contains("maxrecords=100"))
    }

    @Test
    fun keepsLastNewsCacheWhenSourceReturnsItsRateLimitMessage() {
        val failure = runCatching {
            GdeltNewsProvider.parse("Please limit requests to one every 5 seconds", 10L)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("frequency limit"))
    }
}
