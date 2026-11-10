package az.simplesoft.footballintelligence.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeasonDateValidityTest {
    @Test fun acceptsCalendarAndCrossYearSeasonDates() {
        assertTrue(SeasonDateValidity.isPlausible("2026-08-01","2026"))
        assertTrue(SeasonDateValidity.isPlausible("2027-05-01","2026"))
        assertTrue(SeasonDateValidity.isPlausible("2023-02-01","2022-23"))
    }

    @Test fun rejectsPlaceholderEpochsAndKeepsUnknownSeasonPermissive() {
        assertFalse(SeasonDateValidity.isPlausible("1970-01-01","2026"))
        assertFalse(SeasonDateValidity.isPlausible("2026-01-01","2022-23"))
        assertTrue(SeasonDateValidity.isPlausible("2026-01-01",null))
    }
}
