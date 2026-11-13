package az.simplesoft.footballintelligence

import org.junit.Assert.assertEquals
import org.junit.Test

class FootballHomeTabTest {
    @Test
    fun featuredListPutsForecastReadyMatchesFirstAndPreservesKickoffOrderWithinGroups() {
        val upcoming = listOf(
            match(1, "earliest-unready"),
            match(2, "second-ready"),
            match(3, "third-unready"),
            match(4, "latest-ready"),
        )

        val featured = featuredHomeMatches(upcoming, forecastMatchIds = setOf(2, 4), limit = 3)

        assertEquals(listOf(2, 4, 1), featured.map { it.id })
    }

    @Test
    fun featuredListAcceptsEmptyAndZeroLimitInputs() {
        assertEquals(emptyList<Match>(), featuredHomeMatches(emptyList(), emptySet()))
        assertEquals(emptyList<Match>(), featuredHomeMatches(listOf(match(1, "one")), setOf(1), limit = 0))
    }

    private fun match(id: Int, name: String) = Match(
        id = id,
        league = "League",
        home = "$name home",
        away = "$name away",
        kickoff = "2027-01-${id.toString().padStart(2, '0')}T18:00:00Z",
        finished = false,
        homeScore = null,
        awayScore = null,
    )
}
