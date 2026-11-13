package az.simplesoft.footballintelligence

import az.simplesoft.footballintelligence.data.ProviderSyncSummary
import az.simplesoft.footballintelligence.data.OddsProviderStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FootballSyncOverviewTest {
    @Test
    fun bookmakerOverviewSeparatesVisibleRowsFromExactFreshMatches() {
        val now = 1_800_000_000_000L
        val overview = homeOddsProviderOverview(
            state = BookmakerSyncState("misli", OddsProviderStatus.LIVE, now, now, 8, null),
            freshExactLinkedCount = 0,
            nowMillis = now,
        )

        assertEquals("Видимые линии получены", overview.status)
        assertTrue(overview.detail.contains("видимых линий: 8"))
        assertTrue(overview.detail.contains("свежих точных совпадений: 0"))
        assertNull(overview.healthy)
    }

    @Test
    fun bookmakerOverviewOnlyMarksUsableExactFreshLinesAsHealthy() {
        val now = 1_800_000_000_000L
        val overview = homeOddsProviderOverview(
            state = BookmakerSyncState("topaz", OddsProviderStatus.LIVE, now, now, 4, null),
            freshExactLinkedCount = 2,
            nowMillis = now,
        )

        assertTrue(overview.healthy!!)
        assertTrue(overview.detail.contains("свежих точных совпадений: 2"))
    }

    @Test
    fun bookmakerOverviewExplainsStaleAndFailedCaptures() {
        val now = 1_800_000_000_000L
        val stale = homeOddsProviderOverview(
            state = BookmakerSyncState("misli", OddsProviderStatus.LIVE, now - 31L * 60 * 1000, now - 31L * 60 * 1000, 6, null),
            freshExactLinkedCount = 0,
            nowMillis = now,
        )
        val failed = homeOddsProviderOverview(
            state = BookmakerSyncState("topaz", OddsProviderStatus.ERROR, now, null, 0, "HTTP 503"),
            freshExactLinkedCount = 0,
            nowMillis = now,
        )

        assertEquals("Последняя проверка устарела", stale.status)
        assertNull(stale.healthy)
        assertEquals("Ошибка проверки", failed.status)
        assertFalse(failed.healthy!!)
        assertTrue(failed.detail.contains("HTTP 503"))
    }

    @Test
    fun bookmakerOverviewSaysWhenNeverCheckedAndDisclaimsBackgroundGuarantee() {
        val overview = homeOddsProviderOverview(state = null, freshExactLinkedCount = 0, nowMillis = 1L)

        assertEquals("Ещё не проверялся", overview.status)
        assertTrue(overview.detail.contains("не гарантирует её в фоне"))
        assertNull(overview.healthy)
    }

    @Test
    fun failedLatestAttemptIsRedAndDoesNotLookLikeAHealthyOldSync() {
        val overview = automaticFootballSyncOverview(
            running = false,
            queued = false,
            providers = listOf(
                ProviderSyncSummary("openligadb", "ERROR", 2_000L, 1_000L, 34, "HTTP 503"),
                ProviderSyncSummary("openfootball", "OK", 1_500L, 1_500L, 120, null),
            ),
        )

        assertEquals("Ошибка источника", overview.status)
        assertFalse(overview.healthy!!)
        assertTrue(overview.detail.contains("OpenLigaDB: HTTP 503"))
        assertTrue(overview.detail.contains("доступны офлайн"))
    }

    @Test
    fun successfulRetryClearsOldProviderFailure() {
        val overview = automaticFootballSyncOverview(
            running = false,
            queued = false,
            providers = listOf(ProviderSyncSummary("openfootball", "OK", 2_000L, 2_000L, 120, null)),
        )

        assertTrue(overview.status.startsWith("Обновлено "))
        assertTrue(overview.healthy!!)
    }

    @Test
    fun partialAndDeferredStatesAreNotPresentedAsFailuresOrSuccess() {
        val partial = automaticFootballSyncOverview(
            running = false,
            queued = false,
            providers = listOf(ProviderSyncSummary("openfootball", "PARTIAL", 2_000L, 1_900L, 120, "continued later")),
        )
        val deferred = automaticFootballSyncOverview(
            running = false,
            queued = false,
            providers = listOf(ProviderSyncSummary("openligadb", "DEFERRED", 2_000L, null, 0, null)),
        )

        assertEquals("Импорт частичный", partial.status)
        assertNull(partial.healthy)
        assertEquals("Импорт продолжится автоматически", deferred.status)
        assertNull(deferred.healthy)
    }

    @Test
    fun activeWorkTakesPrecedenceOverLastPersistedFailure() {
        val failure = ProviderSyncSummary("openfootball", "ERROR", 2_000L, 1_000L, 120, "timeout")

        val running = automaticFootballSyncOverview(true, false, listOf(failure))
        val queued = automaticFootballSyncOverview(false, true, listOf(failure))

        assertEquals("Идёт обновление", running.status)
        assertNull(running.healthy)
        assertEquals("Ожидает запуска Android", queued.status)
        assertNull(queued.healthy)
    }

    @Test
    fun sourceDetailsIncludeEachProviderStatusAndLastSuccess() {
        val text = providerSyncDetails(
            listOf(
                ProviderSyncSummary("openfootball", "OK", 2_000L, 1_900L, 120, null),
                ProviderSyncSummary("openligadb", "ERROR", 2_000L, null, 0, "timeout"),
            ),
        )

        assertTrue(text.contains("OpenFootball · OK · записей 120"))
        assertTrue(text.contains("OpenLigaDB · ERROR · записей 0"))
        assertTrue(text.contains("timeout"))
    }
}
