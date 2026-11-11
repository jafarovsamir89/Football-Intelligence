package az.simplesoft.footballintelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import az.simplesoft.footballintelligence.data.MARKET_REFERENCE_APP_OPEN_STALE_MINUTES

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

    @Test fun activeSyncStageExplainsWhichProviderIsRunning() {
        assertEquals("Загружается история и расписание OpenFootball…", automaticSyncStageMessage("OpenFootball"))
        assertEquals("Обновляются футбольные лиги OpenLigaDB…", automaticSyncStageMessage("OpenLigaDB"))
        assertEquals("Идёт автоматическая синхронизация источников…", automaticSyncStageMessage(null))
    }

    @Test fun providerBudgetContinuationIsDeferredRatherThanAnError() {
        assertEquals("DEFERRED", automaticProviderSyncStatus(completedUnits = 0, hasErrors = false, continuationNeeded = true))
        assertEquals("ERROR", automaticProviderSyncStatus(completedUnits = 0, hasErrors = true, continuationNeeded = false))
        assertEquals("ERROR", automaticProviderSyncStatus(completedUnits = 0, hasErrors = true, continuationNeeded = true))
        assertEquals("PARTIAL", automaticProviderSyncStatus(completedUnits = 1, hasErrors = true, continuationNeeded = false))
        assertEquals("PARTIAL", automaticProviderSyncStatus(completedUnits = 1, hasErrors = false, continuationNeeded = true))
        assertEquals("OK", automaticProviderSyncStatus(completedUnits = 1, hasErrors = false, continuationNeeded = false))
    }

    @Test fun eachProviderGetsTimeWithinOneWorkerSegment() {
        val startedAt = 1_000L
        val deadlines = syncRunDeadlines(startedAt)

        assertEquals(TimeUnit.MINUTES.toMillis(3), deadlines.openFootballDeadlineMillis - startedAt)
        assertEquals(TimeUnit.MINUTES.toMillis(7), deadlines.workerDeadlineMillis - startedAt)
        assertTrue(deadlines.openFootballDeadlineMillis < deadlines.workerDeadlineMillis)
    }

    @Test fun appOpenRefreshBecomesDueAtEightHoursAndMissingSuccessIsStale() {
        val now = 10 * TimeUnit.DAYS.toMillis(1)
        val eightHours = TimeUnit.HOURS.toMillis(APP_OPEN_SYNC_STALE_AFTER_HOURS)
        assertFalse(isAppOpenSyncStale(now - eightHours + 1L, now))
        assertTrue(isAppOpenSyncStale(now - eightHours, now))
        assertTrue(isAppOpenSyncStale(null, now))
        assertTrue(isAppOpenSyncStale(0L, now))
    }

    @Test fun marketReferenceRefreshUsesFifteenMinuteFreshnessWindow() {
        val now=10*TimeUnit.DAYS.toMillis(1)
        val fifteenMinutes=TimeUnit.MINUTES.toMillis(MARKET_REFERENCE_APP_OPEN_STALE_MINUTES)
        assertFalse(isTipsAuditReferenceSyncStale(now-fifteenMinutes+1L,now))
        assertTrue(isTipsAuditReferenceSyncStale(now-fifteenMinutes,now))
        assertTrue(isTipsAuditReferenceSyncStale(null,now))
        assertTrue(isTipsAuditReferenceSyncStale(0L,now))
    }
}
