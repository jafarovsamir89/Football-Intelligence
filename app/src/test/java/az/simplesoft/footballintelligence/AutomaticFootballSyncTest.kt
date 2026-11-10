package az.simplesoft.footballintelligence

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
