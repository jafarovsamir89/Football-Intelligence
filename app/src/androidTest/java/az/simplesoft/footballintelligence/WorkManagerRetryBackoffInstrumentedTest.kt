package az.simplesoft.footballintelligence

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.OneTimeWorkRequestBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val WORK_MANAGER_RETRY_PREFERENCES_NAME = "work-manager-retry-instrumentation"

@RunWith(AndroidJUnit4::class)
class WorkManagerRetryBackoffInstrumentedTest {
    @Test fun retryIsPersistedAndReattemptedAfterExponentialBackoff() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val workManager = WorkManager.getInstance(context)
        val testKey = "retry-${UUID.randomUUID()}"
        val attemptsKey = "attempts-$testKey"
        val preferences = context.getSharedPreferences(WORK_MANAGER_RETRY_PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.edit().putInt(attemptsKey, 0).commit()

        val request = withAutomaticSyncRetryBackoff(
            OneTimeWorkRequestBuilder<RetryOnceThenSucceedWorker>()
                .setInputData(Data.Builder().putString(RetryOnceThenSucceedWorker.INPUT_KEY, attemptsKey).build()),
        ).build()

        try {
            workManager.enqueue(request).result.get(10, TimeUnit.SECONDS)

            val firstRetry = awaitWorkInfo(context, request.id, TimeUnit.SECONDS.toMillis(20)) {
                it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount >= 1
            }
            assertEquals(WorkInfo.State.ENQUEUED, firstRetry.state)
            assertEquals(1, preferences.getInt(attemptsKey, 0))

            // WorkManager must keep the retry queued well into its configured 30-second backoff,
            // rather than immediately running the worker a second time.
            Thread.sleep(RETRY_NOT_BEFORE_CHECK_MILLIS)
            val stillWaiting = workManager.getWorkInfoById(request.id).get(10, TimeUnit.SECONDS)
            assertNotNull(stillWaiting)
            assertEquals(WorkInfo.State.ENQUEUED, stillWaiting!!.state)
            assertEquals(1, stillWaiting.runAttemptCount)
            assertEquals(1, preferences.getInt(attemptsKey, 0))

            val success = awaitWorkInfo(context, request.id, TimeUnit.SECONDS.toMillis(90)) {
                it.state == WorkInfo.State.SUCCEEDED
            }
            assertEquals(WorkInfo.State.SUCCEEDED, success.state)
            assertEquals(2, success.runAttemptCount)
            assertEquals(2, preferences.getInt(attemptsKey, 0))
        } finally {
            workManager.cancelWorkById(request.id).result.get(10, TimeUnit.SECONDS)
            preferences.edit().remove(attemptsKey).commit()
        }
    }

    private fun awaitWorkInfo(
        context: Context,
        workId: UUID,
        timeoutMillis: Long,
        predicate: (WorkInfo) -> Boolean,
    ): WorkInfo {
        val manager = WorkManager.getInstance(context)
        val deadline = System.currentTimeMillis() + timeoutMillis
        var latest: WorkInfo? = null
        while (System.currentTimeMillis() < deadline) {
            latest = manager.getWorkInfoById(workId).get(10, TimeUnit.SECONDS)
            if (latest != null && predicate(latest)) return latest
            Thread.sleep(100)
        }
        throw AssertionError("Timed out waiting for WorkManager state; last state was ${latest?.state}.")
    }

    companion object {
        private const val RETRY_NOT_BEFORE_CHECK_MILLIS = 20_000L
    }
}

class RetryOnceThenSucceedWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val key = inputData.getString(INPUT_KEY) ?: return Result.failure()
        val preferences = applicationContext.getSharedPreferences(
            WORK_MANAGER_RETRY_PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        val attempts = preferences.getInt(key, 0) + 1
        if (!preferences.edit().putInt(key, attempts).commit()) return Result.failure()
        return if (attempts == 1) Result.retry() else Result.success()
    }

    companion object {
        const val INPUT_KEY = "attemptsPreferenceKey"
    }
}
