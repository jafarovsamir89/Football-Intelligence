package az.simplesoft.footballintelligence.analytics

import az.simplesoft.footballintelligence.data.InternationalOddsSnapshot
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
     * five-minute kickoff difference; no fuzzy team matching is used.
     */
    fun consensus(snapshots: List<InternationalOddsSnapshot>): List<InternationalMarketConsensus> {
        val eventGroups = mutableListOf<MutableList<InternationalOddsSnapshot>>()
        snapshots.sortedWith(compareByDescending<InternationalOddsSnapshot> { it.providerPolledAtMillis }
            .thenByDescending { it.capturedAtMillis }).forEach { candidate ->
            val group = eventGroups.firstOrNull { existing ->
                existing.any { sameUnderlyingEvent(it, candidate) }
            }
            if (group == null) eventGroups += mutableListOf(candidate) else group += candidate
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
                it.eventId == current.eventId && it.capturedAtMillis < current.capturedAtMillis }
            .maxByOrNull { it.capturedAtMillis } ?: return null
        return InternationalLineMovement(previous, current)
    }

    private fun sameUnderlyingEvent(a: InternationalOddsSnapshot, b: InternationalOddsSnapshot): Boolean =
        a.market == b.market &&
            TeamNameIdentity.canonical(a.homeTeam) == TeamNameIdentity.canonical(b.homeTeam) &&
            TeamNameIdentity.canonical(a.awayTeam) == TeamNameIdentity.canonical(b.awayTeam) &&
            abs(a.kickoffMillis - b.kickoffMillis) <= EVENT_KICKOFF_TOLERANCE_MILLIS
}
