package az.simplesoft.footballintelligence.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeRulesTest {
    @Test fun parsesZoneLessProviderTimeAsUtc() {
        assertEquals("2026-11-09T12:30:00Z", normalizeProviderTimestampUtc("2026-11-09T12:30:00"))
    }

    @Test fun preservesExplicitOffsetAsUtcInstant() {
        assertEquals("2026-11-09T12:30:00Z", normalizeProviderTimestampUtc("2026-11-09T14:30:00+02:00"))
    }

    @Test fun rejectsInvalidTimestamp() {
        assertEquals(null, normalizeProviderTimestampUtc("not-a-date"))
    }

    @Test fun futureQuotesAreNeverFresh() {
        assertFalse(isFreshQuote(1_001L, 1_000L))
        assertTrue(isFreshQuote(1_000L, 1_000L))
        assertTrue(isFreshQuote(400L, 1_000L, 600L))
        assertFalse(isFreshQuote(399L, 1_000L, 600L))
    }
}
