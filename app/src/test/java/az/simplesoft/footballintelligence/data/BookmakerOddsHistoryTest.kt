package az.simplesoft.footballintelligence.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BookmakerOddsHistoryTest {
    @Test
    fun reportsMovementFromFirstToLatestCaptureForOneExactBookmakerEvent() {
        val first = quote(captured = 1_000, home = 2.0, draw = 3.0, away = 4.0)
        val latest = quote(captured = 2_000, home = 2.2, draw = 2.7, away = 4.4)

        val summary = summarizeBookmakerOddsHistory(listOf(latest, first), "misli", 7, nowMillis = 2_500)

        assertEquals(BookmakerOddsHistoryState.MOVEMENT_AVAILABLE, summary.state)
        assertEquals(2, summary.snapshotCount)
        assertSame(first, summary.firstSnapshot)
        assertSame(latest, summary.latestSnapshot)
        val movement = bookmakerOddsMovementPercent(first, latest)!!
        assertEquals(10.0, movement.home, 1e-9)
        assertEquals(-10.0, movement.draw, 1e-9)
        assertEquals(10.0, movement.away, 1e-9)
    }

    @Test
    fun neverMixesDifferentProvidersFixturesOrExternalEventIdentities() {
        val first = quote(captured = 1_000)
        val otherEvent = quote(captured = 2_000, eventId = "second-event")
        val otherProvider = first.copy(providerId = "topaz", eventId = "topaz-event")
        val unmatched = first.copy(capturedAtMillis = 2_500, matchId = null)
        val afterKickoff = first.copy(capturedAtMillis = first.kickoffMillis + 1)

        val ambiguous = summarizeBookmakerOddsHistory(
            listOf(first, otherEvent, otherProvider, unmatched, afterKickoff),
            "misli",
            7,
            nowMillis = afterKickoff.capturedAtMillis,
        )
        val topazOnly = summarizeBookmakerOddsHistory(listOf(first, otherProvider), "topaz", 7, nowMillis = 2_500)

        assertEquals(BookmakerOddsHistoryState.AMBIGUOUS_EVENT_IDENTITY, ambiguous.state)
        assertEquals(2, ambiguous.snapshotCount)
        assertEquals(BookmakerOddsHistoryState.SINGLE_SNAPSHOT, topazOnly.state)
        assertEquals(1, topazOnly.snapshotCount)
        assertNull(bookmakerOddsMovementPercent(first, otherProvider))
    }

    @Test
    fun explainsMissingSingleAndFutureDatedHistoryStates() {
        val first = quote(captured = 1_000)

        val missing = summarizeBookmakerOddsHistory(emptyList(), "misli", 7, nowMillis = 2_000)
        val single = summarizeBookmakerOddsHistory(listOf(first), "misli", 7, nowMillis = 2_000)
        val futureCapture = summarizeBookmakerOddsHistory(
            listOf(first.copy(capturedAtMillis = 70_000, kickoffMillis = 100_000)),
            "misli",
            7,
            nowMillis = 2_000,
        )

        assertEquals(BookmakerOddsHistoryState.NO_MATCHED_HISTORY, missing.state)
        assertEquals(BookmakerOddsHistoryState.SINGLE_SNAPSHOT, single.state)
        assertEquals(1, single.snapshotCount)
        assertEquals(BookmakerOddsHistoryState.NO_MATCHED_HISTORY, futureCapture.state)
    }

    @Test
    fun countsRepeatedPageRowsAsOneObservationAndRejectsTeamOrderChanges() {
        val first = quote(captured = 1_000)
        val duplicate = first.copy()
        val latest = quote(captured = 2_000, home = 2.2)
        val summary = summarizeBookmakerOddsHistory(
            listOf(first, duplicate, latest),
            "misli",
            7,
            nowMillis = 2_500,
        )

        assertEquals(BookmakerOddsHistoryState.MOVEMENT_AVAILABLE, summary.state)
        assertEquals(2, summary.snapshotCount)
        assertSame(first, summary.firstSnapshot)
        assertNull(bookmakerOddsMovementPercent(first, latest.copy(homeTeam = "Away")))

        val conflicting = summarizeBookmakerOddsHistory(
            listOf(first, first.copy(home = 2.1), latest),
            "misli",
            7,
            nowMillis = 2_500,
        )
        assertEquals(BookmakerOddsHistoryState.CONFLICTING_CAPTURE, conflicting.state)
        assertNull(conflicting.firstSnapshot)
        assertNull(conflicting.latestSnapshot)
    }

    private fun quote(
        captured: Long,
        home: Double = 2.0,
        draw: Double = 3.0,
        away: Double = 4.0,
        eventId: String = "event-1",
    ) = BookmakerOddsSnapshot(
        providerId = "misli",
        eventId = eventId,
        sourceUrl = "https://www.misli.az/idman-novleri/futbol",
        capturedAtMillis = captured,
        kickoffMillis = 10_000,
        market = "1X2",
        homeTeam = "Home",
        awayTeam = "Away",
        home = home,
        draw = draw,
        away = away,
        eventDate = "1970-01-01",
        timeZoneId = "Asia/Baku",
        matchId = 7,
    )
}
