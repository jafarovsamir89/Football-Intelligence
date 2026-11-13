package az.simplesoft.footballintelligence

import az.simplesoft.footballintelligence.data.ProviderSyncSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FootballSyncOverviewTest {
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
