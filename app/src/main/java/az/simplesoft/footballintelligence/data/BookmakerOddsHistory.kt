package az.simplesoft.footballintelligence.data

enum class BookmakerOddsHistoryState {
    NO_MATCHED_HISTORY,
    SINGLE_SNAPSHOT,
    AMBIGUOUS_EVENT_IDENTITY,
    CONFLICTING_CAPTURE,
    MOVEMENT_AVAILABLE,
}

data class BookmakerOddsHistorySummary(
    val state: BookmakerOddsHistoryState,
    val snapshotCount: Int,
    val firstSnapshot: BookmakerOddsSnapshot?,
    val latestSnapshot: BookmakerOddsSnapshot?,
)

data class BookmakerOddsMovementPercent(
    val home: Double,
    val draw: Double,
    val away: Double,
)

/** Summarizes only exact local-fixture links from one bookmaker and one visible source event. */
fun summarizeBookmakerOddsHistory(
    snapshots: List<BookmakerOddsSnapshot>,
    providerId: String,
    matchId: Int,
    nowMillis: Long = System.currentTimeMillis(),
): BookmakerOddsHistorySummary {
    require(providerId.isNotBlank())
    require(matchId > 0)
    val eligible = snapshots.filter { snapshot ->
        snapshot.providerId == providerId && snapshot.matchId == matchId && snapshot.market == "1X2" &&
            snapshot.capturedAtMillis <= nowMillis + 60_000L && snapshot.capturedAtMillis < snapshot.kickoffMillis
    }.sortedWith(compareBy<BookmakerOddsSnapshot> { it.capturedAtMillis }.thenBy { it.eventId })
    if (eligible.isEmpty()) {
        return BookmakerOddsHistorySummary(BookmakerOddsHistoryState.NO_MATCHED_HISTORY, 0, null, null)
    }
    val uniqueObservations = eligible.distinctBy { it.eventId to it.capturedAtMillis }
    val hasConflictingDuplicate = eligible
        .groupBy { it.eventId to it.capturedAtMillis }
        .values
        .any { rows -> rows.distinct().size > 1 }
    if (hasConflictingDuplicate) {
        return BookmakerOddsHistorySummary(
            BookmakerOddsHistoryState.CONFLICTING_CAPTURE,
            uniqueObservations.size,
            null,
            null,
        )
    }
    if (uniqueObservations.map { it.eventId }.distinct().size != 1) {
        return BookmakerOddsHistorySummary(
            BookmakerOddsHistoryState.AMBIGUOUS_EVENT_IDENTITY,
            uniqueObservations.size,
            null,
            null,
        )
    }
    val uniqueCaptureTimes = uniqueObservations.distinctBy { it.capturedAtMillis }
    val first = uniqueCaptureTimes.first()
    val latest = uniqueCaptureTimes.last()
    val state = if (uniqueCaptureTimes.size >= 2) {
        BookmakerOddsHistoryState.MOVEMENT_AVAILABLE
    } else {
        BookmakerOddsHistoryState.SINGLE_SNAPSHOT
    }
    return BookmakerOddsHistorySummary(state, uniqueCaptureTimes.size, first, latest)
}

/** Percentage change between two pre-kickoff observations of the same source event. */
fun bookmakerOddsMovementPercent(
    first: BookmakerOddsSnapshot,
    latest: BookmakerOddsSnapshot,
): BookmakerOddsMovementPercent? {
    if (first.providerId != latest.providerId || first.eventId != latest.eventId || first.matchId == null ||
        first.matchId != latest.matchId || first.market != "1X2" || latest.market != "1X2" ||
        TeamNameIdentity.canonical(first.homeTeam) != TeamNameIdentity.canonical(latest.homeTeam) ||
        TeamNameIdentity.canonical(first.awayTeam) != TeamNameIdentity.canonical(latest.awayTeam) ||
        TeamNameIdentity.canonical(first.homeTeam).isBlank() ||
        TeamNameIdentity.canonical(first.awayTeam).isBlank() ||
        TeamNameIdentity.canonical(first.homeTeam) == TeamNameIdentity.canonical(first.awayTeam) ||
        first.capturedAtMillis >= latest.capturedAtMillis || first.capturedAtMillis >= first.kickoffMillis ||
        latest.capturedAtMillis >= latest.kickoffMillis ||
        listOf(first.home, first.draw, first.away, latest.home, latest.draw, latest.away).any { !it.isFinite() || it <= 1.0 }
    ) return null
    return BookmakerOddsMovementPercent(
        home = percentChange(first.home, latest.home),
        draw = percentChange(first.draw, latest.draw),
        away = percentChange(first.away, latest.away),
    )
}

private fun percentChange(initial: Double, latest: Double): Double = (latest - initial) / initial * 100.0
