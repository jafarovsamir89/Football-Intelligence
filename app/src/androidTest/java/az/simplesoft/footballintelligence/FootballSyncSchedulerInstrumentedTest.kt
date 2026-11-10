package az.simplesoft.footballintelligence

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FootballSyncSchedulerInstrumentedTest {
    @Test fun uniqueWorkUsesNetworkConstraintAndFreshDataSkipsImmediateRequest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = WorkManager.getInstance(context)
        cancelTestWork(manager)

        try {
            val now = System.currentTimeMillis()
            val safeTestDelay = TimeUnit.HOURS.toMillis(1)
            FootballSyncScheduler.enqueueForFreshness(
                context = context,
                mostRecentSuccessAtMillis = null,
                nowMillis = now,
                immediateInitialDelayMillis = safeTestDelay,
            )

            val firstPeriodic = activeWork(manager, FootballSyncScheduler.UNIQUE_PERIODIC_NAME).single()
            val firstImmediate = activeWork(manager, FootballSyncScheduler.UNIQUE_ONCE_NAME).single()
            assertEquals(WorkInfo.State.ENQUEUED, firstPeriodic.state)
            assertEquals(WorkInfo.State.ENQUEUED, firstImmediate.state)
            assertEquals(NetworkType.CONNECTED, firstPeriodic.constraints.requiredNetworkType)
            assertEquals(NetworkType.CONNECTED, firstImmediate.constraints.requiredNetworkType)
            assertEquals(
                TimeUnit.HOURS.toMillis(PERIODIC_SYNC_INTERVAL_HOURS),
                firstPeriodic.periodicityInfo?.repeatIntervalMillis,
            )
            assertEquals(safeTestDelay, firstImmediate.initialDelayMillis)
            assertEquals(0, firstImmediate.runAttemptCount)

            // Existing unique requests stay queued on a repeated app-open schedule.
            FootballSyncScheduler.enqueueForFreshness(
                context = context,
                mostRecentSuccessAtMillis = null,
                nowMillis = now,
                immediateInitialDelayMillis = safeTestDelay,
            )
            assertEquals(firstPeriodic.id, activeWork(manager, FootballSyncScheduler.UNIQUE_PERIODIC_NAME).single().id)
            assertEquals(firstImmediate.id, activeWork(manager, FootballSyncScheduler.UNIQUE_ONCE_NAME).single().id)

            cancelTestWork(manager)
            val freshNow = System.currentTimeMillis()
            FootballSyncScheduler.enqueueForFreshness(
                context = context,
                mostRecentSuccessAtMillis = freshNow - TimeUnit.HOURS.toMillis(1),
                nowMillis = freshNow,
                immediateInitialDelayMillis = safeTestDelay,
            )
            assertEquals(1, activeWork(manager, FootballSyncScheduler.UNIQUE_PERIODIC_NAME).size)
            assertTrue(activeWork(manager, FootballSyncScheduler.UNIQUE_ONCE_NAME).isEmpty())
        } finally {
            cancelTestWork(manager)
        }
    }

    private fun activeWork(manager: WorkManager, uniqueName: String): List<WorkInfo> =
        manager.getWorkInfosForUniqueWork(uniqueName).get(10, TimeUnit.SECONDS)
            .filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }

    private fun cancelTestWork(manager: WorkManager) {
        manager.cancelUniqueWork(FootballSyncScheduler.UNIQUE_ONCE_NAME)
            .result.get(10, TimeUnit.SECONDS)
        manager.cancelUniqueWork(FootballSyncScheduler.UNIQUE_PERIODIC_NAME)
            .result.get(10, TimeUnit.SECONDS)
    }
}
