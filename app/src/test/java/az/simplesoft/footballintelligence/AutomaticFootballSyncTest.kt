package az.simplesoft.footballintelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class AutomaticFootballSyncTest {
    @Test fun runtimeBudgetQueuesContinuationInsteadOfBackoff() {
        assertEquals(SyncNextStep.CONTINUE, decideSyncNextStep(retryNeeded = false, continuationNeeded = true, anySuccess = true))
    }

    @Test fun transientErrorsKeepWorkManagerRetryBackoff() {
        assertEquals(SyncNextStep.RETRY, decideSyncNextStep(retryNeeded = true, continuationNeeded = true, anySuccess = true))
        assertEquals(SyncNextStep.RETRY, decideSyncNextStep(retryNeeded = true, continuationNeeded = false, anySuccess = false))
    }

    @Test fun fullyProcessedSyncCompletesWithoutAnotherRequest() {
        assertEquals(SyncNextStep.SUCCESS, decideSyncNextStep(retryNeeded = false, continuationNeeded = false, anySuccess = true))
    }

    @Test fun unsuccessfulSyncWithoutContinuationIsRetried() {
        assertEquals(SyncNextStep.RETRY, decideSyncNextStep(retryNeeded = false, continuationNeeded = false, anySuccess = false))
    }

    @Test fun appOpenRefreshBecomesDueAtEightHoursAndMissingSuccessIsStale() {
        val now = 10 * TimeUnit.DAYS.toMillis(1)
        val eightHours = TimeUnit.HOURS.toMillis(APP_OPEN_SYNC_STALE_AFTER_HOURS)
        assertFalse(isAppOpenSyncStale(now - eightHours + 1L, now))
        assertTrue(isAppOpenSyncStale(now - eightHours, now))
        assertTrue(isAppOpenSyncStale(null, now))
        assertTrue(isAppOpenSyncStale(0L, now))
    }
}
