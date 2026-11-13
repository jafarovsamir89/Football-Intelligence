package az.simplesoft.footballintelligence

import org.junit.Assert.assertEquals
import org.junit.Test

class FootballModelsTest {
    @Test
    fun matchTimesAreRenderedInBakuRegardlessOfHostTimezone() {
        assertEquals("01.01 04:00", niceTime("2026-01-01T00:00:00Z"))
    }
}
