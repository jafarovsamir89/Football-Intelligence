package az.simplesoft.footballintelligence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.TimeUnit

class TheOddsApiSchedulerTest {
    @Test fun appOpenRefreshUsesTwoHourCadenceAndRejectsFutureClockState() {
        val now = 1_800_000_000_000L

        assertTrue(isTheOddsApiSyncStale(null, now))
        assertTrue(isTheOddsApiSyncStale(0L, now))
        assertFalse(isTheOddsApiSyncStale(now - TimeUnit.HOURS.toMillis(1) - TimeUnit.MINUTES.toMillis(59), now))
        assertTrue(isTheOddsApiSyncStale(now - TimeUnit.HOURS.toMillis(2), now))
        assertTrue(isTheOddsApiSyncStale(now + TimeUnit.MINUTES.toMillis(2), now))
    }

    @Test fun initialOddsRefreshWaitsUntilForecastBaselineExists() {
        val now = 1_800_000_000_000L

        assertFalse(shouldScheduleTheOddsApiImmediateRefresh(false, null, now))
        assertTrue(shouldScheduleTheOddsApiImmediateRefresh(true, null, now))
        assertFalse(shouldScheduleTheOddsApiImmediateRefresh(true, now - TimeUnit.MINUTES.toMillis(5), now))
        assertTrue(shouldScheduleTheOddsApiImmediateRefresh(true, now - TimeUnit.HOURS.toMillis(2), now))
        assertFalse(shouldScheduleTheOddsApiImmediateRefresh(false, now - TimeUnit.HOURS.toMillis(2), now))
    }

    @Test fun initialOddsRefreshWaitsForCompletedFootballSyncAndSavedForecasts() {
        assertFalse(shouldScheduleTheOddsApiAfterFootballSync(syncBusy = true, syncQueued = false, forecastCount = 4, apiKeyAvailable = true))
        assertFalse(shouldScheduleTheOddsApiAfterFootballSync(syncBusy = false, syncQueued = true, forecastCount = 4, apiKeyAvailable = true))
        assertFalse(shouldScheduleTheOddsApiAfterFootballSync(syncBusy = false, syncQueued = false, forecastCount = 0, apiKeyAvailable = true))
        assertFalse(shouldScheduleTheOddsApiAfterFootballSync(syncBusy = false, syncQueued = false, forecastCount = 4, apiKeyAvailable = false))
        assertTrue(shouldScheduleTheOddsApiAfterFootballSync(syncBusy = false, syncQueued = false, forecastCount = 4, apiKeyAvailable = true))
    }

    @Test fun oddsRequestsAreSpacedAndHardLimitedToTwelvePerRollingDay() {
        val now = 1_800_000_000_000L
        val hour = TimeUnit.HOURS.toMillis(1)
        val day = TimeUnit.HOURS.toMillis(24)

        assertTrue(canStartTheOddsApiBatch(emptyList(), now))
        assertFalse(canStartTheOddsApiBatch(listOf(now - hour), now))
        assertTrue(canStartTheOddsApiBatch(listOf(now - 2 * hour), now))
        assertFalse(canStartTheOddsApiBatch((0 until 12).map { now - it * 2 * hour }, now))
        assertFalse(canStartTheOddsApiBatch((0 until 12).map { now - day + 1L + it * 2 * hour }, now))
        assertTrue(canStartTheOddsApiBatch((0 until 12).map { now - day + it * 2 * hour }, now))
        assertFalse(canStartTheOddsApiBatch(listOf(now + TimeUnit.MINUTES.toMillis(2)), now))
        assertFalse(canStartTheOddsApiBatch(emptyList(), now, retryAfterUntilMillis = now + TimeUnit.HOURS.toMillis(6)))
        assertTrue(canStartTheOddsApiBatch(emptyList(), now, retryAfterUntilMillis = now - 1L))
    }

    @Test fun knownLowQuotaBlocksUntilTheNextMonthlyResetHasPassed() {
        val observed = Instant.parse("2026-11-12T12:00:00Z").toEpochMilli()
        assertTrue(hasTheOddsApiCreditReserve(null, null, observed))
        assertTrue(hasTheOddsApiCreditReserve(21, observed, observed))
        assertFalse(hasTheOddsApiCreditReserve(20, observed, observed))
        assertFalse(hasTheOddsApiCreditReserve(0, observed, Instant.parse("2026-12-01T23:59:59Z").toEpochMilli()))
        assertTrue(hasTheOddsApiCreditReserve(0, observed, Instant.parse("2026-12-02T00:00:00Z").toEpochMilli()))
        assertFalse(hasTheOddsApiCreditReserve(10, observed + TimeUnit.DAYS.toMillis(1), observed))
    }
}
