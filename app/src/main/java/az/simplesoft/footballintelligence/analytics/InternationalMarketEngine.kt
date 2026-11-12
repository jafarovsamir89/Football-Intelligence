package az.simplesoft.footballintelligence.analytics

import az.simplesoft.footballintelligence.data.InternationalOddsSnapshot
import az.simplesoft.footballintelligence.data.InternationalOddsQuoteSide
import az.simplesoft.footballintelligence.data.TeamNameIdentity
import kotlin.math.abs

data class InternationalMarketConsensus(
    val matchId: Int?,
    val homeTeam: String,
    val awayTeam: String,
    val kickoffMillis: Long,
    val probabilities: OutcomeProbabilities,
    val distinctBookmakerCount: Int,
    val latestProviderPollAtMillis: Long,
)

data class InternationalLineMovement(
    val previous: InternationalOddsSnapshot,
    val latest: InternationalOddsSnapshot,
) {
    val homeDelta: Double get() = latest.home - previous.home
    val drawDelta: Double get() = latest.draw - previous.draw
    val awayDelta: Double get() = latest.away - previous.away
}

object InternationalMarketEngine {
    private const val EVENT_KICKOFF_TOLERANCE_MILLIS = 5L * 60_000L

    /**
     * A bookmaker contributes once per event, even when two intermediaries report that same
     * bookmaker. The event join is exact after Unicode/punctuation normalization and allows a
     * five-minute kickoff difference; no fuzzy team matching is used. If tolerance matches form
     * an overlapping chain rather than one complete-link group, every row in that component is
     * kept separate because the available identity fields cannot distinguish its events safely.
     */
    fun consensus(snapshots: List<InternationalOddsSnapshot>): List<InternationalMarketConsensus> {
        val backQuotes = snapshots.filter { it.market == "1X2" && it.quoteSide == InternationalOddsQuoteSide.BACK }
        val eventGroups = backQuotes.groupBy(::eventNameKey).values.flatMap { identityCandidates ->
            val ordered = identityCandidates.sortedWith(compareBy<InternationalOddsSnapshot> { it.kickoffMillis }
                .thenBy { it.bookmakerId.lowercase() }.thenBy { it.providerId }
                .thenBy { it.providerPolledAtMillis }.thenBy { it.capturedAtMillis })
            val remaining = ordered.indices.toMutableSet()
            val groups = mutableListOf<MutableList<InternationalOddsSnapshot>>()
            while (remaining.isNotEmpty()) {
                val seed = remaining.first()
                remaining.remove(seed)
                val componentIndices = mutableListOf(seed)
                val frontier = java.util.ArrayDeque<Int>().apply { add(seed) }
                while (frontier.isNotEmpty()) {
                    val current = frontier.removeFirst()
                    val connectedToCurrent = remaining.filter { candidate ->
                        sameUnderlyingEvent(ordered[current], ordered[candidate])
                    }
                    connectedToCurrent.forEach { index ->
                        remaining.remove(index)
                        componentIndices += index
                        frontier.add(index)
                    }
                }
                val component = componentIndices.map { ordered[it] }
                val completeLink = component.indices.all { i ->
                    (i + 1 until component.size).all { j -> sameUnderlyingEvent(component[i], component[j]) }
                }
                if (completeLink) groups += component.toMutableList()
                else component.forEach { groups += mutableListOf(it) }
            }
            groups
        }

        return eventGroups.mapNotNull { eventRows ->
            val uniqueBookmakerRows = eventRows.groupBy { it.bookmakerId.lowercase() }
                .values.mapNotNull { rows -> rows.maxWithOrNull(compareBy<InternationalOddsSnapshot> { it.providerPolledAtMillis }
                    .thenBy { it.capturedAtMillis }) }
            if (uniqueBookmakerRows.isEmpty()) return@mapNotNull null
            val fair = uniqueBookmakerRows.map {
                ProbabilityEngine.removeMargin(it.home, it.draw, it.away, MarginMethod.BASIC)
            }
            val count = fair.size.toDouble()
            val probabilities = OutcomeProbabilities(
                fair.sumOf { it.home } / count,
                fair.sumOf { it.draw } / count,
                fair.sumOf { it.away } / count,
            )
            val representative = uniqueBookmakerRows.maxBy { it.providerPolledAtMillis }
            InternationalMarketConsensus(
                representative.matchId,
                representative.homeTeam,
                representative.awayTeam,
                representative.kickoffMillis,
                probabilities,
                uniqueBookmakerRows.size,
                uniqueBookmakerRows.maxOf { it.providerPolledAtMillis },
            )
        }.sortedBy { it.kickoffMillis }
    }

    fun latestMovement(
        current: InternationalOddsSnapshot,
        history: List<InternationalOddsSnapshot>,
    ): InternationalLineMovement? {
        val previous = history.asSequence()
            .filter { it.providerId == current.providerId && it.bookmakerId == current.bookmakerId &&
                it.eventId == current.eventId && it.market == current.market && it.quoteSide == current.quoteSide &&
                it.sourceMarketKey == current.sourceMarketKey && it.capturedAtMillis < current.capturedAtMillis }
            .maxByOrNull { it.capturedAtMillis } ?: return null
        return InternationalLineMovement(previous, current)
    }

    private data class EventNameKey(val market: String, val home: String, val away: String)

    private fun eventNameKey(snapshot: InternationalOddsSnapshot) = EventNameKey(
        snapshot.market,
        TeamNameIdentity.canonical(snapshot.homeTeam),
        TeamNameIdentity.canonical(snapshot.awayTeam),
    )

    private fun sameUnderlyingEvent(a: InternationalOddsSnapshot, b: InternationalOddsSnapshot): Boolean =
            eventNameKey(a) == eventNameKey(b) &&
            (a.matchId == null || b.matchId == null || a.matchId == b.matchId) &&
            abs(a.kickoffMillis - b.kickoffMillis) <= EVENT_KICKOFF_TOLERANCE_MILLIS
}
