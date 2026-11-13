package az.simplesoft.footballintelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class FootballModelsTest {
    @Test
    fun matchTimesAreRenderedInBakuRegardlessOfHostTimezone() {
        assertEquals("01.01 04:00", niceTime("2026-01-01T00:00:00Z"))
    }

    @Test
    fun dateOnlyFixturesRemainVisibleWithoutPretendingTheyHaveAnExactKickoff() {
        val zone = ZoneId.of("Asia/Baku")
        val now = Instant.parse("2026-11-13T06:00:00Z").toEpochMilli()
        val fixtures = listOf(
            exactFixture(1, "2026-11-13T07:00:00Z"),
            dateOnlyFixture(2, "2026-11-13"),
            dateOnlyFixture(3, "2026-11-14"),
            exactFixture(4, "2026-11-13T05:00:00Z"),
            dateOnlyFixture(5, "2026-11-12"),
            dateOnlyFixture(6, "2026-11-14").copy(finished = true),
        )

        val upcoming = upcomingFixtures(fixtures, now, zone)

        assertEquals(listOf(1, 2, 3), upcoming.map { it.id })
        assertEquals(LocalDate.parse("2026-11-13"), fixtureLocalDate(fixtures[1], zone))
        assertFalse(fixtures[1].exactKickoff)
        assertEquals("Ожидается · время начала не указано", matchStatusLabel(fixtures[1], now))
        assertTrue(upcoming.any { !it.exactKickoff })
    }

    private fun exactFixture(id: Int, kickoff: String) = Match(
        id, "League", "Home $id", "Away $id", kickoff, false, null, null,
    )

    private fun dateOnlyFixture(id: Int, eventDate: String) = Match(
        id, "League", "Home $id", "Away $id", eventDate, false, null, null,
        exactKickoff = false,
        eventDate = eventDate,
    )
}
