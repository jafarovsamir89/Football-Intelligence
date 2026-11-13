package az.simplesoft.footballintelligence

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.testing.TestListenableWorkerBuilder
import az.simplesoft.footballintelligence.data.InternationalOddsSnapshot
import az.simplesoft.footballintelligence.data.InternationalOddsStatus
import az.simplesoft.footballintelligence.data.MOVIODDS_API_URL
import az.simplesoft.footballintelligence.data.THE_ODDS_API_PROVIDER_ID
import az.simplesoft.footballintelligence.data.ProviderFileCache
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TheOddsApiWorkerInstrumentedTest {
    @Test fun workerFetchesCatalogAndLeagueThenStoresExactFreshLinesWithoutKeyInHistory() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "fi-the-odds-${UUID.randomUUID()}.db"
        val isolated = IsolatedDatabaseContext(base, name)
        val prefs = base.getSharedPreferences("football_intelligence_the_odds_api", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val kickoff = now + TimeUnit.DAYS.toMillis(2)
        base.deleteDatabase(name)
        prefs.edit().clear().commit()
        try {
            Store(isolated, name).use { store ->
                store.upsert(listOf(Match(1701, "eng.1", "Manchester City", "Fulham", Instant.ofEpochMilli(kickoff).toString(), false, null, null, "openligadb:1701")))
            }
            val worker = TestListenableWorkerBuilder<TheOddsApiOddsWorker>(isolated).build()
            worker.apiKeyProvider = { "test-private-key" }
            var catalogRequests = 0
            var oddsRequests = 0
            worker.providerHttpClient = object : ProviderHttpClient {
                override fun get(url: String, cache: ProviderFileCache?): ProviderHttpResult =
                    throw AssertionError("The Odds API must send its key in the x-api-key header.")

                override fun getWithHeaders(url: String, headers: Map<String, String>): ProviderHttpResult {
                    assertEquals("test-private-key", headers["x-api-key"])
                    assertFalse(url.contains("apiKey"))
                    return when {
                        url.endsWith("/sports/") -> {
                            catalogRequests++
                            ProviderHttpResult(
                                200,
                                """[{"key":"soccer_epl","title":"EPL","active":true},{"key":"soccer_spain_la_liga","title":"La Liga","active":true},{"key":"soccer_germany_bundesliga","title":"Bundesliga","active":true}]""",
                                null,
                                null,
                                serverDateMillis = System.currentTimeMillis(),
                            )
                        }
                        url.contains("/sports/soccer_epl/odds/") -> {
                            oddsRequests++
                            ProviderHttpResult(
                                200,
                                liveEplPayload(kickoff, System.currentTimeMillis()),
                                null,
                                null,
                                serverDateMillis = System.currentTimeMillis(),
                                oddsApiRequestsRemaining = 499,
                                oddsApiRequestsUsed = 1,
                                oddsApiRequestCost = 1,
                            )
                        }
                        else -> throw AssertionError("Unexpected API route: ${url.substringBefore('?')}.")
                    }
                }
            }

            assertEquals(ListenableWorker.Result.success().javaClass, worker.doWork().javaClass)

            assertEquals(1, catalogRequests)
            assertEquals(1, oddsRequests)
            Store(base, name).use { store ->
                val state = store.internationalOddsSyncState(THE_ODDS_API_PROVIDER_ID)!!
                assertEquals(InternationalOddsStatus.LIVE, state.status)
                assertEquals(1, state.freshRecordsStored)
                assertTrue(state.detail!!.contains("Проверено лиг: 1/1 из 3 активных футбольных лиг."))
                assertTrue(state.detail!!.contains("Квота: последний запрос 1 кредитов, использовано 1, осталось 499."))
                assertFalse(state.detail!!.contains("test-private-key"))
                assertEquals(499, prefs.getInt("quota_remaining", -1))
                assertTrue(prefs.getLong("quota_observed_at", 0L) > 0L)
                val saved = store.freshInternationalOdds(System.currentTimeMillis(), THE_ODDS_API_PROVIDER_ID).single()
                assertEquals("unibet_fr", saved.bookmakerId)
                assertEquals("api-event-1", saved.eventId)
                assertEquals(1701, saved.matchId)
                assertEquals(THE_ODDS_API_PROVIDER_ID, saved.providerId)
                assertEquals(1, store.internationalOddsHistory(50, THE_ODDS_API_PROVIDER_ID).size)

                val moviTime = System.currentTimeMillis()
                store.saveInternationalOddsCapture(
                    moviTime,
                    InternationalOddsStatus.LIVE,
                    listOf(InternationalOddsSnapshot("moviodds_bet365", "bet365", "movi-event", MOVIODDS_API_URL, "Serie A", null, moviTime, moviTime, moviTime + 60 * 60 * 1000L, "1X2", "Roma", "Napoli", 2.1, 3.2, 3.5, moviTime, moviTime, moviTime)),
                    "separate provider row",
                )
                val nextCheck = moviTime + 1_000L
                store.saveInternationalOddsCapture(
                    nextCheck,
                    InternationalOddsStatus.UNAVAILABLE,
                    emptyList(),
                    "The Odds API board was empty",
                    replaceCurrentBoard = true,
                    providerId = THE_ODDS_API_PROVIDER_ID,
                )
                assertEquals("a board replacement is isolated to its provider", 1, store.freshInternationalOdds(nextCheck).size)
                assertEquals(InternationalOddsStatus.LIVE, store.internationalOddsSyncState("moviodds_bet365")!!.status)
            }

            // A second app-open or periodic enqueue within the two-hour cooldown cannot spend credits twice.
            val second = TestListenableWorkerBuilder<TheOddsApiOddsWorker>(isolated).build()
            second.apiKeyProvider = { "test-private-key" }
            second.providerHttpClient = object : ProviderHttpClient {
                override fun get(url: String, cache: ProviderFileCache?): ProviderHttpResult {
                    throw AssertionError("The API request gate should prevent a second request in the same window.")
                }
            }
            assertEquals(ListenableWorker.Result.success().javaClass, second.doWork().javaClass)
        } finally {
            prefs.edit().clear().commit()
            base.deleteDatabase(name)
        }
    }

    @Test fun missingPersonalKeyDoesNotMakeHttpRequests() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "fi-the-odds-no-key-${UUID.randomUUID()}.db"
        val isolated = IsolatedDatabaseContext(base, name)
        base.deleteDatabase(name)
        var calls = 0
        try {
            val worker = TestListenableWorkerBuilder<TheOddsApiOddsWorker>(isolated).build()
            worker.apiKeyProvider = { "" }
            worker.providerHttpClient = object : ProviderHttpClient {
                override fun get(url: String, cache: ProviderFileCache?): ProviderHttpResult {
                    calls++
                    throw AssertionError("No key means no network request.")
                }
            }
            assertEquals(ListenableWorker.Result.success().javaClass, worker.doWork().javaClass)
            assertEquals(0, calls)
            Store(base, name).use { store ->
                assertEquals(InternationalOddsStatus.UNAVAILABLE, store.internationalOddsSyncState(THE_ODDS_API_PROVIDER_ID)!!.status)
            }
        } finally {
            base.getSharedPreferences("football_intelligence_the_odds_api", Context.MODE_PRIVATE).edit().clear().commit()
            base.deleteDatabase(name)
        }
    }

    @Test fun workerHonorsRetryAfterBeforeSpendingAnotherOddsRequest() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "fi-the-odds-retry-after-${UUID.randomUUID()}.db"
        val isolated = IsolatedDatabaseContext(base, name)
        val prefs = base.getSharedPreferences("football_intelligence_the_odds_api", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val retryDelay = TimeUnit.HOURS.toMillis(6)
        base.deleteDatabase(name)
        prefs.edit().clear().commit()
        try {
            val worker = TestListenableWorkerBuilder<TheOddsApiOddsWorker>(isolated).build()
            worker.apiKeyProvider = { "test-private-key" }
            worker.providerHttpClient = object : ProviderHttpClient {
                override fun get(url: String, cache: ProviderFileCache?): ProviderHttpResult =
                    throw AssertionError("The Odds API must send its key in the x-api-key header.")

                override fun getWithHeaders(url: String, headers: Map<String, String>): ProviderHttpResult {
                    assertEquals("test-private-key", headers["x-api-key"])
                    throw ProviderHttpException(429, retryDelay)
                }
            }

            assertEquals(ListenableWorker.Result.success().javaClass, worker.doWork().javaClass)
            assertFalse(TheOddsApiBatchGate.tryBegin(prefs, now + TimeUnit.HOURS.toMillis(4)))
            assertTrue(TheOddsApiBatchGate.tryBegin(prefs, now + TimeUnit.HOURS.toMillis(8)))
        } finally {
            prefs.edit().clear().commit()
            base.deleteDatabase(name)
        }
    }

    @Test fun schedulerIsKeyGatedAndUsesAConnectedTwoHourWindow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = WorkManager.getInstance(context)
        clearWork(manager)
        try {
            TheOddsApiScheduler.enqueueForFreshness(context, null, System.currentTimeMillis(), apiKeyAvailable = false)
            assertTrue(activeWork(manager, TheOddsApiScheduler.UNIQUE_ONCE_NAME).isEmpty())
            assertTrue(activeWork(manager, TheOddsApiScheduler.UNIQUE_PERIODIC_NAME).isEmpty())

            manager.enqueueUniquePeriodicWork(
                TheOddsApiScheduler.UNIQUE_PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<TheOddsApiOddsWorker>(24, TimeUnit.HOURS)
                    .setInitialDelay(24, TimeUnit.HOURS)
                    .build(),
            ).result.get(10, TimeUnit.SECONDS)
            assertEquals(TimeUnit.HOURS.toMillis(24), awaitOne(manager, TheOddsApiScheduler.UNIQUE_PERIODIC_NAME).periodicityInfo?.repeatIntervalMillis)

            TheOddsApiScheduler.enqueueForFreshness(
                context,
                null,
                System.currentTimeMillis(),
                immediateInitialDelayMillis = TimeUnit.MINUTES.toMillis(5),
                apiKeyAvailable = true,
            )
            val once = awaitOne(manager, TheOddsApiScheduler.UNIQUE_ONCE_NAME)
            val periodic = awaitPeriodicInterval(manager, TheOddsApiScheduler.UNIQUE_PERIODIC_NAME, TimeUnit.HOURS.toMillis(2))
            assertEquals(WorkInfo.State.ENQUEUED, once.state)
            assertEquals(WorkInfo.State.ENQUEUED, periodic.state)
            assertEquals(NetworkType.CONNECTED, once.constraints.requiredNetworkType)
            assertEquals(NetworkType.CONNECTED, periodic.constraints.requiredNetworkType)
            assertEquals(TimeUnit.HOURS.toMillis(2), periodic.periodicityInfo?.repeatIntervalMillis)
        } finally {
            clearWork(manager)
        }
    }

    @Test fun schedulerDefersFirstOneTimeOddsRequestUntilFootballBaselineIsReady() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = WorkManager.getInstance(context)
        clearWork(manager)
        try {
            TheOddsApiScheduler.enqueueForFreshness(
                context,
                lastCheckedAtMillis = null,
                nowMillis = System.currentTimeMillis(),
                apiKeyAvailable = true,
                footballBaselineReady = false,
            )
            assertTrue(activeWork(manager, TheOddsApiScheduler.UNIQUE_ONCE_NAME).isEmpty())
            val periodic = awaitPeriodicInterval(manager, TheOddsApiScheduler.UNIQUE_PERIODIC_NAME, TimeUnit.HOURS.toMillis(2))
            assertEquals(NetworkType.CONNECTED, periodic.constraints.requiredNetworkType)

            TheOddsApiScheduler.enqueueImmediateIfStale(
                context,
                lastCheckedAtMillis = null,
                nowMillis = System.currentTimeMillis(),
                apiKeyAvailable = true,
                footballBaselineReady = false,
            )
            assertTrue(activeWork(manager, TheOddsApiScheduler.UNIQUE_ONCE_NAME).isEmpty())

            TheOddsApiScheduler.enqueueImmediateIfStale(
                context,
                lastCheckedAtMillis = null,
                nowMillis = System.currentTimeMillis(),
                immediateInitialDelayMillis = TimeUnit.HOURS.toMillis(1),
                apiKeyAvailable = true,
                footballBaselineReady = true,
            )
            val once = awaitOne(manager, TheOddsApiScheduler.UNIQUE_ONCE_NAME)
            assertEquals(WorkInfo.State.ENQUEUED, once.state)
            assertEquals(NetworkType.CONNECTED, once.constraints.requiredNetworkType)
            val periodicAfter = awaitPeriodicInterval(manager, TheOddsApiScheduler.UNIQUE_PERIODIC_NAME, TimeUnit.HOURS.toMillis(2))
            assertEquals(periodic.id, periodicAfter.id)
        } finally {
            clearWork(manager)
        }
    }

    @Test fun persistedOddsRequestBudgetCapsTwelveRunsPerRollingDay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("fi-odds-budget-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val start = 1_800_000_000_000L
        val twoHours = TimeUnit.HOURS.toMillis(2)
        try {
            prefs.edit().clear().commit()
            prefs.edit().putLong("last_batch_attempt_at", start).commit()
            assertFalse(TheOddsApiBatchGate.tryBegin(prefs, start + twoHours))
            assertTrue(TheOddsApiBatchGate.tryBegin(prefs, start + TimeUnit.HOURS.toMillis(24)))

            prefs.edit().clear().commit()
            assertTrue(TheOddsApiBatchGate.tryBegin(prefs, start))
            assertFalse(TheOddsApiBatchGate.tryBegin(prefs, start + TimeUnit.HOURS.toMillis(1)))
            for (index in 1..11) {
                assertTrue("request $index should fit the rolling 24-hour budget", TheOddsApiBatchGate.tryBegin(prefs, start + index * twoHours))
            }
            assertEquals(12, prefs.getString("recent_batch_attempts", null)!!.split(',').size)
            val rollingDay = TimeUnit.HOURS.toMillis(24)
            assertFalse(TheOddsApiBatchGate.tryBegin(prefs, start + rollingDay - 1L))
            assertTrue(TheOddsApiBatchGate.tryBegin(prefs, start + rollingDay))
            assertEquals(12, prefs.getString("recent_batch_attempts", null)!!.split(',').size)

            prefs.edit().clear()
                .putInt("quota_remaining", 20)
                .putLong("quota_observed_at", Instant.parse("2026-11-12T12:00:00Z").toEpochMilli())
                .commit()
            assertFalse(TheOddsApiBatchGate.tryBegin(prefs, Instant.parse("2026-11-30T12:00:00Z").toEpochMilli()))
            assertFalse(TheOddsApiBatchGate.tryBegin(prefs, Instant.parse("2026-12-01T23:59:59Z").toEpochMilli()))
            assertTrue(TheOddsApiBatchGate.tryBegin(prefs, Instant.parse("2026-12-02T00:00:00Z").toEpochMilli()))
        } finally {
            prefs.edit().clear().commit()
        }
    }

    private fun liveEplPayload(kickoff: Long, updated: Long): String = """[
      {"id":"api-event-1","sport_key":"soccer_epl","sport_title":"EPL","commence_time":"${Instant.ofEpochMilli(kickoff)}","home_team":"Manchester City","away_team":"Fulham","bookmakers":[
        {"key":"unibet_fr","title":"Unibet (FR)","markets":[{"key":"h2h","last_update":"${Instant.ofEpochMilli(updated - 5_000L)}","outcomes":[
          {"name":"Fulham","price":5.8},{"name":"Draw","price":4.5},{"name":"Manchester City","price":1.27}
        ]}]}
      ]}
    ]"""

    private fun activeWork(manager: WorkManager, name: String): List<WorkInfo> =
        manager.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS)
            .filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }

    private fun awaitOne(manager: WorkManager, name: String): WorkInfo {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30)
        while (System.currentTimeMillis() < deadline) {
            val active = activeWork(manager, name)
            if (active.size == 1) return active.single()
            check(active.size <= 1) { "Expected at most one active request for $name; got ${active.size}." }
            Thread.sleep(100)
        }
        throw AssertionError("Timed out waiting for $name.")
    }

    private fun awaitPeriodicInterval(manager: WorkManager, name: String, expectedMillis: Long): WorkInfo {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30)
        while (System.currentTimeMillis() < deadline) {
            val active = activeWork(manager, name)
            check(active.size <= 1) { "Expected at most one active request for $name; got ${active.size}." }
            val work = active.singleOrNull()
            if (work?.periodicityInfo?.repeatIntervalMillis == expectedMillis) return work
            Thread.sleep(100)
        }
        throw AssertionError("Timed out waiting for $name periodic interval $expectedMillis.")
    }

    private fun clearWork(manager: WorkManager) {
        manager.cancelUniqueWork(TheOddsApiScheduler.UNIQUE_ONCE_NAME).result.get(10, TimeUnit.SECONDS)
        manager.cancelUniqueWork(TheOddsApiScheduler.UNIQUE_PERIODIC_NAME).result.get(10, TimeUnit.SECONDS)
    }

    private class IsolatedDatabaseContext(base: Context, private val databaseName: String) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File = super.getDatabasePath(databaseName)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            super.openOrCreateDatabase(databaseName, mode, factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            super.openOrCreateDatabase(databaseName, mode, factory, errorHandler)
    }
}
