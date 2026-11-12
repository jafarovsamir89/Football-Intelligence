package az.simplesoft.footballintelligence

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import az.simplesoft.footballintelligence.data.FOOTBALL_DATA_STATS_MIN_GAP_MILLIS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class FootballDataStatsWorkerInstrumentedTest {
    @Test fun schedulerUsesConnectedConstraintAndPreservesTheProviderDueTime() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val manager=WorkManager.getInstance(context)
        manager.cancelUniqueWork(FootballDataStatsScheduler.UNIQUE_ONCE_NAME).result.get(10,TimeUnit.SECONDS)
        manager.cancelUniqueWork(FootballDataStatsScheduler.UNIQUE_PERIODIC_NAME).result.get(10,TimeUnit.SECONDS)
        try {
            val now=System.currentTimeMillis()
            val delay=TimeUnit.HOURS.toMillis(1)
            assertTrue(FootballDataStatsScheduler.enqueueForNextAllowedAt(context,now+delay,now))
            val once=manager.getWorkInfosForUniqueWork(FootballDataStatsScheduler.UNIQUE_ONCE_NAME).get(10,TimeUnit.SECONDS).single()
            val periodic=manager.getWorkInfosForUniqueWork(FootballDataStatsScheduler.UNIQUE_PERIODIC_NAME).get(10,TimeUnit.SECONDS).single()
            assertEquals(WorkInfo.State.ENQUEUED,once.state)
            assertEquals(WorkInfo.State.ENQUEUED,periodic.state)
            assertEquals(NetworkType.CONNECTED,once.constraints.requiredNetworkType)
            assertEquals(NetworkType.CONNECTED,periodic.constraints.requiredNetworkType)
            assertEquals(delay,once.initialDelayMillis)
            assertEquals(TimeUnit.HOURS.toMillis(FootballDataStatsScheduler.REFRESH_INTERVAL_HOURS),periodic.initialDelayMillis)
        } finally {
            manager.cancelUniqueWork(FootballDataStatsScheduler.UNIQUE_ONCE_NAME).result.get(10,TimeUnit.SECONDS)
            manager.cancelUniqueWork(FootballDataStatsScheduler.UNIQUE_PERIODIC_NAME).result.get(10,TimeUnit.SECONDS)
        }
    }

    @Test fun downloadsOneLeagueStoresExactMatchStatsAndEnforcesRequestGap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "fi-football-stats-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        val now = Instant.parse("2026-11-12T12:00:00Z").toEpochMilli()
        val response = """Div,Date,HomeTeam,AwayTeam,FTHG,FTAG,HS,AS,HST,AST,HC,AC,HF,AF,HY,AY,HR,AR,B365H,B365D,B365A
            E0,01/11/26,Arsenal,Manchester City,2,0,14,5,8,1,7,2,11,14,1,3,0,0,2.10,3.20,3.50
        """.trimIndent()
        val requests = AtomicInteger()
        try {
            Store(context, databaseName).use { store ->
                store.upsert(listOf(Match(
                    id = 910031,
                    league = "E0",
                    home = "Arsenal",
                    away = "Manchester City",
                    kickoff = "2026-11-01T15:00:00Z",
                    finished = true,
                    homeScore = 2,
                    awayScore = 0,
                    sourceKey = "stats-fixture:910031",
                    eventDate = "2026-11-01",
                )))
                val syncer = FootballDataStatsSyncer(store) { url ->
                    requests.incrementAndGet()
                    assertEquals("https://www.football-data.co.uk/mmz4281/2627/E0.csv", url)
                    FootballDataStatsHttpResponse(200, response)
                }

                val first = syncer.sync(now)
                val stats = store.recentFootballMatchStats(listOf(910031))[910031]
                assertTrue(first.attempted)
                assertEquals("OK", first.status)
                assertEquals(1, first.rowsMatched)
                assertNotNull(stats)
                assertEquals(14, stats!!.values.homeShots)
                assertEquals(5, stats.values.awayShots)
                assertEquals("https://www.football-data.co.uk/mmz4281/2627/E0.csv", stats.sourceUrl)
                assertEquals(now, stats.availableAtMillis)
                assertTrue(store.quotes(910031).isEmpty())

                val second = syncer.sync(now + 60_000L)
                assertEquals("LIMITED", second.status)
                assertFalse(second.attempted)
                assertEquals(1, requests.get())
                assertEquals(now + FOOTBALL_DATA_STATS_MIN_GAP_MILLIS, store.footballDataStatsNextAllowedAt(now))
                assertEquals("OK", store.latestFootballDataStatsAttempt()?.status)
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test fun failedHttpRequestIsPersistedAndStillConsumesTheLocalQuota() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "fi-football-stats-failure-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        val now = Instant.parse("2026-11-12T12:00:00Z").toEpochMilli()
        try {
            Store(context, databaseName).use { store ->
                val syncer = FootballDataStatsSyncer(store) {
                    FootballDataStatsHttpResponse(503, "")
                }
                assertEquals("ERROR", syncer.sync(now).status)
                assertEquals("ERROR", store.latestFootballDataStatsAttempt()?.status)
                assertFalse(syncer.sync(now + 1).attempted)
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }
}
