package az.simplesoft.footballintelligence

import az.simplesoft.footballintelligence.data.INTERNATIONAL_ODDS_APP_OPEN_STALE_MINUTES
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InternationalOddsSchedulerTest {
    @Test fun appOpenRefreshUsesTenMinuteFreshnessAndDoesNotEnqueueForRecentCapture() {
        val now=1_800_000_000_000L
        val staleMillis=INTERNATIONAL_ODDS_APP_OPEN_STALE_MINUTES*60_000L

        assertTrue(isInternationalOddsReferenceSyncStale(null,now))
        assertTrue(isInternationalOddsReferenceSyncStale(now-staleMillis,now))
        assertFalse(isInternationalOddsReferenceSyncStale(now-staleMillis+1_000L,now))
        assertFalse(isInternationalOddsReferenceSyncStale(now+60_000L,now))
    }
}
