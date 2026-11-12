package az.simplesoft.footballintelligence.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class FootballDataStatsPolicyTest {
    @Test fun activeSeasonChangesAtJulyBoundary() {
        assertEquals("2526", footballDataCurrentSeasonCode(LocalDate.of(2026, 6, 30)))
        assertEquals("2627", footballDataCurrentSeasonCode(LocalDate.of(2026, 7, 1)))
    }

    @Test fun firstDownloadCanRunImmediatelyAndSecondWaitsFourDays() {
        val now = 1_800_000_000_000L
        assertEquals(now, footballDataStatsNextAllowedAt(emptyList(), now))
        assertEquals(now + FOOTBALL_DATA_STATS_MIN_GAP_MILLIS,
            footballDataStatsNextAllowedAt(listOf(now), now))
    }

    @Test fun thirdDownloadWaitsUntilTwoPriorRequestsLeaveRollingSevenDayWindow() {
        val now = 1_800_000_000_000L
        val attempts = listOf(now - FOOTBALL_DATA_STATS_MIN_GAP_MILLIS, now)
        assertEquals(attempts.first() + FOOTBALL_DATA_STATS_WEEK_MILLIS + 1,
            footballDataStatsNextAllowedAt(attempts, now))
    }

    @Test fun oldAndFutureTimestampsCannotCauseAnEarlyDownload() {
        val now = 1_800_000_000_000L
        assertEquals(now, footballDataStatsNextAllowedAt(listOf(now - FOOTBALL_DATA_STATS_WEEK_MILLIS - 1), now))
        assertEquals(now + 60_000L + FOOTBALL_DATA_STATS_MIN_GAP_MILLIS,
            footballDataStatsNextAllowedAt(listOf(now + 60_000), now))
    }
}
