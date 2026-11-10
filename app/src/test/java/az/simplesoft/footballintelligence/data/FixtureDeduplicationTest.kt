package az.simplesoft.footballintelligence.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixtureDeduplicationTest {
    @Test fun matchesOnlySameDateAndHomeAwayOrderAfterConservativeNormalization() {
        assertTrue(FixtureDeduplication.sameCandidate("2026-11-10", "Arsenal FC", "Chelsea (Q)", "2026-11-10", "Arsenal", "Chelsea"))
        assertFalse(FixtureDeduplication.sameCandidate("2026-11-10", "Arsenal", "Chelsea", "2026-11-11", "Arsenal", "Chelsea"))
        assertFalse(FixtureDeduplication.sameCandidate("2026-11-10", "Arsenal", "Chelsea", "2026-11-10", "Chelsea", "Arsenal"))
    }
}
