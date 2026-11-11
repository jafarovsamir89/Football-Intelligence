package az.simplesoft.footballintelligence

import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
import android.provider.Settings
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
    @Test fun immediateSyncRemainsQueuedWithoutNetworkAndDoesNotStartWorker() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = WorkManager.getInstance(context)
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val wasAirplaneModeEnabled = Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.AIRPLANE_MODE_ON,
            0,
        ) == 1
        val wasWifiEnabled = Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, 0) == 1
        cancelTestWork(manager)

        try {
            if (!wasAirplaneModeEnabled) runShellCommand("cmd connectivity airplane-mode enable")
            if (wasWifiEnabled) runShellCommand("cmd wifi set-wifi-enabled disabled")
            waitUntil(timeoutMillis = TimeUnit.SECONDS.toMillis(30)) {
                Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1 &&
                    Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, 0) == 0 &&
                    connectivityManager.activeNetwork == null
            }

            FootballSyncScheduler.enqueueForFreshness(
                context = context,
                mostRecentSuccessAtMillis = null,
                nowMillis = System.currentTimeMillis(),
            )

            val immediate = awaitSingleActiveWork(manager, FootballSyncScheduler.UNIQUE_ONCE_NAME)
            assertEquals(WorkInfo.State.ENQUEUED, immediate.state)
            assertEquals(0, immediate.runAttemptCount)
            assertTrue(immediate.progress.keyValueMap.isEmpty())
            val periodic = awaitSingleActiveWork(manager, FootballSyncScheduler.UNIQUE_PERIODIC_NAME)
            assertEquals(WorkInfo.State.ENQUEUED, periodic.state)
            assertEquals(0, periodic.runAttemptCount)
        } finally {
            cancelTestWork(manager)
            val shouldRestoreAirplaneMode = wasAirplaneModeEnabled
            val isAirplaneModeEnabled = Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                0,
            ) == 1
            if (isAirplaneModeEnabled != shouldRestoreAirplaneMode) {
                runShellCommand(
                    "cmd connectivity airplane-mode ${if (shouldRestoreAirplaneMode) "enable" else "disable"}",
                )
            }
            val isWifiEnabled = Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, 0) == 1
            if (isWifiEnabled != wasWifiEnabled) {
                runShellCommand("cmd wifi set-wifi-enabled ${if (wasWifiEnabled) "enabled" else "disabled"}")
            }
        }
    }

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

            val firstPeriodic = awaitSingleActiveWork(manager, FootballSyncScheduler.UNIQUE_PERIODIC_NAME)
            val firstImmediate = awaitSingleActiveWork(manager, FootballSyncScheduler.UNIQUE_ONCE_NAME)
            assertEquals(WorkInfo.State.ENQUEUED, firstPeriodic.state)
            assertEquals(WorkInfo.State.ENQUEUED, firstImmediate.state)
            assertEquals(NetworkType.CONNECTED, firstPeriodic.constraints.requiredNetworkType)
            assertEquals(NetworkType.CONNECTED, firstImmediate.constraints.requiredNetworkType)
            assertEquals(
                TimeUnit.HOURS.toMillis(PERIODIC_SYNC_INTERVAL_HOURS),
                firstPeriodic.periodicityInfo?.repeatIntervalMillis,
            )
            assertEquals(
                TimeUnit.HOURS.toMillis(PERIODIC_SYNC_INTERVAL_HOURS),
                firstPeriodic.initialDelayMillis,
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
            assertEquals(
                firstPeriodic.id,
                awaitSingleActiveWork(manager, FootballSyncScheduler.UNIQUE_PERIODIC_NAME).id,
            )
            assertEquals(
                firstImmediate.id,
                awaitSingleActiveWork(manager, FootballSyncScheduler.UNIQUE_ONCE_NAME).id,
            )

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

    @Test fun marketReferenceUsesIndependentThirtyMinuteConnectedRefresh() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val manager=WorkManager.getInstance(context)
        cancelTestWork(manager)
        try {
            val now=System.currentTimeMillis()
            val safeDelay=TimeUnit.HOURS.toMillis(1)
            TipsAuditMarketReferenceScheduler.enqueueForFreshness(context,null,now,safeDelay)
            val periodic=awaitSingleActiveWork(manager,TipsAuditMarketReferenceScheduler.UNIQUE_PERIODIC_NAME)
            val once=awaitSingleActiveWork(manager,TipsAuditMarketReferenceScheduler.UNIQUE_ONCE_NAME)
            assertEquals(NetworkType.CONNECTED,periodic.constraints.requiredNetworkType)
            assertEquals(NetworkType.CONNECTED,once.constraints.requiredNetworkType)
            assertEquals(TimeUnit.MINUTES.toMillis(30),periodic.periodicityInfo?.repeatIntervalMillis)
            assertEquals(TimeUnit.MINUTES.toMillis(30),periodic.initialDelayMillis)
            assertEquals(safeDelay,once.initialDelayMillis)

            cancelTestWork(manager)
            val freshNow=System.currentTimeMillis()
            TipsAuditMarketReferenceScheduler.enqueueForFreshness(context,freshNow-TimeUnit.MINUTES.toMillis(1),freshNow,safeDelay)
            assertEquals(1,activeWork(manager,TipsAuditMarketReferenceScheduler.UNIQUE_PERIODIC_NAME).size)
            assertTrue(activeWork(manager,TipsAuditMarketReferenceScheduler.UNIQUE_ONCE_NAME).isEmpty())
        } finally {
            cancelTestWork(manager)
        }
    }

    private fun activeWork(manager: WorkManager, uniqueName: String): List<WorkInfo> =
        manager.getWorkInfosForUniqueWork(uniqueName).get(10, TimeUnit.SECONDS)
            .filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }

    private fun awaitSingleActiveWork(manager: WorkManager, uniqueName: String): WorkInfo {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30)
        var lastObserved = emptyList<WorkInfo>()
        while (System.currentTimeMillis() < deadline) {
            lastObserved = activeWork(manager, uniqueName)
            check(lastObserved.size <= 1) {
                "Expected one active request for $uniqueName, observed ${lastObserved.size}."
            }
            if (lastObserved.size == 1) return lastObserved.single()
            Thread.sleep(100)
        }
        throw AssertionError(
            "Timed out waiting for active request $uniqueName; " +
                "observed states: ${manager.getWorkInfosForUniqueWork(uniqueName).get(10, TimeUnit.SECONDS).map { it.state }}",
        )
    }

    private fun cancelTestWork(manager: WorkManager) {
        manager.cancelUniqueWork(FootballSyncScheduler.UNIQUE_ONCE_NAME)
            .result.get(10, TimeUnit.SECONDS)
        manager.cancelUniqueWork(FootballSyncScheduler.UNIQUE_PERIODIC_NAME)
            .result.get(10, TimeUnit.SECONDS)
        manager.cancelUniqueWork(TipsAuditMarketReferenceScheduler.UNIQUE_ONCE_NAME)
            .result.get(10, TimeUnit.SECONDS)
        manager.cancelUniqueWork(TipsAuditMarketReferenceScheduler.UNIQUE_PERIODIC_NAME)
            .result.get(10, TimeUnit.SECONDS)
    }

    private fun runShellCommand(command: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for device airplane-mode state." }
            Thread.sleep(100)
        }
    }
}
