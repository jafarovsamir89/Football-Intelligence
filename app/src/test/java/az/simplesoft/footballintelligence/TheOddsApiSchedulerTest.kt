package az.simplesoft.footballintelligence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class TheOddsApiSchedulerTest {
    @Test fun appOpenRefreshUsesDailyCadenceAndRejectsFutureClockState() {
        val now = 1_800_000_000_000L

        assertTrue(isTheOddsApiSyncStale(null, now))
        assertTrue(isTheOddsApiSyncStale(0L, now))
        assertFalse(isTheOddsApiSyncStale(now - TimeUnit.HOURS.toMillis(23), now))
        assertTrue(isTheOddsApiSyncStale(now - TimeUnit.HOURS.toMillis(24), now))
        assertTrue(isTheOddsApiSyncStale(now + TimeUnit.MINUTES.toMillis(2), now))
    }
}
