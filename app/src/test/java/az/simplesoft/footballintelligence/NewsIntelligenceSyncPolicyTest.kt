package az.simplesoft.footballintelligence

import az.simplesoft.footballintelligence.data.GDELT_NEWS_PROVIDER_ID
import az.simplesoft.footballintelligence.data.ProviderSyncSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsIntelligenceSyncPolicyTest {
    @Test fun noFixtureStateDoesNotConsumeTwelveHourRefreshWindow() {
        val now = 2_000_000L
        val noFixtures = ProviderSyncSummary(GDELT_NEWS_PROVIDER_ID, "NO_FIXTURES", now, null, 0, null)

        assertFalse(shouldDeferNewsAttempt(noFixtures, now))
    }

    @Test fun successfulOrFailedRequestIsDeferredUntilTwelveHoursHavePassed() {
        val now = 2_000_000L
        val request = ProviderSyncSummary(GDELT_NEWS_PROVIDER_ID, "RATE_LIMITED", now, null, 0, null)

        assertTrue(shouldDeferNewsAttempt(request, now + 1L))
        assertTrue(shouldDeferNewsAttempt(request, now + 12L * 60L * 60L * 1_000L - 1L))
        assertFalse(shouldDeferNewsAttempt(request, now + 12L * 60L * 60L * 1_000L))
        assertTrue(shouldDeferNewsAttempt(request, now - 1L))
        assertFalse(shouldDeferNewsAttempt(null, now))
    }

    @Test fun newsRefreshIsQueuedOnlyAfterAProviderSyncCompletesSuccessfully() {
        assertTrue(shouldScheduleNewsAfterFootballSync(SyncNextStep.SUCCESS, anyProviderSucceeded = true))
        assertFalse(shouldScheduleNewsAfterFootballSync(SyncNextStep.SUCCESS, anyProviderSucceeded = false))
        assertFalse(shouldScheduleNewsAfterFootballSync(SyncNextStep.RETRY, anyProviderSucceeded = true))
        assertFalse(shouldScheduleNewsAfterFootballSync(SyncNextStep.CONTINUE, anyProviderSucceeded = true))
    }
}
