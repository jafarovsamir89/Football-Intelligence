package az.simplesoft.footballintelligence

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MatchStatusLabelTest {
    @Test fun dateOnlyPastFixtureIsNotShownAsUpcoming() {
        val now=Instant.parse("2026-11-09T20:00:00Z").toEpochMilli()
        val match=Match(1,"en.1","Home","Away","2026-08-29T00:00:00Z",false,null,null,
            exactKickoff=false,eventDate="2026-08-29")
        assertTrue(matchStatusLabel(match,now).contains("Матч прошёл"))
    }

    @Test fun dateOnlyTodayAndFutureRemainUpcomingUntilDayEnds() {
        val now=Instant.parse("2026-11-09T20:00:00Z").toEpochMilli()
        val match=Match(1,"en.1","Home","Away","2026-11-09T00:00:00Z",false,null,null,
            exactKickoff=false,eventDate="2026-11-09")
        assertTrue(matchStatusLabel(match,now).startsWith("Ожидается"))
    }
}
