package az.simplesoft.footballintelligence

import az.simplesoft.footballintelligence.analytics.ProspectiveForecastObservation
import az.simplesoft.footballintelligence.analytics.VirtualAccumulatorHistoryEntry
import az.simplesoft.footballintelligence.analytics.LoggedCouponPick
import az.simplesoft.footballintelligence.analytics.HistoricalMatch
import az.simplesoft.footballintelligence.data.BookmakerOddsSnapshot
import az.simplesoft.footballintelligence.data.InternationalOddsSnapshot
import az.simplesoft.footballintelligence.data.InternationalOddsSyncAttempt
import az.simplesoft.footballintelligence.data.InternationalOddsSyncState
import az.simplesoft.footballintelligence.data.MarketReferenceSnapshot
import az.simplesoft.footballintelligence.data.MarketReferenceSyncState
import az.simplesoft.footballintelligence.data.ProviderSyncSummary
import az.simplesoft.footballintelligence.data.THE_ODDS_API_PROVIDER_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A single, immutable read model for the Compose screen; every SQLite read runs off the UI thread. */
data class FootballScreenStoreSnapshot(
    val providerSyncSummaries: List<ProviderSyncSummary>,
    val matches: List<Match>,
    val recentResults: List<Match>,
    val importedCompetitionCodes: List<String>,
    val selectedQuotes: List<Quote>,
    val selectedPicks: List<Pick>,
    val tickets: List<Ticket>,
    val ticketMatches: Map<Int, Match>,
    val misliSyncState: BookmakerSyncState?,
    val misliOdds: List<BookmakerOddsSnapshot>,
    val topazSyncState: BookmakerSyncState?,
    val topazOdds: List<BookmakerOddsSnapshot>,
    val internationalOddsSyncState: InternationalOddsSyncState?,
    val internationalOddsSyncHistory: List<InternationalOddsSyncAttempt>,
    val theOddsApiSyncState: InternationalOddsSyncState?,
    val theOddsApiSyncHistory: List<InternationalOddsSyncAttempt>,
    val internationalOdds: List<InternationalOddsSnapshot>,
    val internationalOddsHistory: List<InternationalOddsSnapshot>,
    val marketReferenceSyncState: MarketReferenceSyncState?,
    val marketReferences: List<MarketReferenceSnapshot>,
    val historicalByLeague: Map<String, List<HistoricalMatch>>,
    val checkedCouponHistory: List<LoggedCouponPick>,
    val prospectiveForecastHistory: List<ProspectiveForecastObservation>,
    val prospectiveForecastEvaluationHistory: List<ProspectiveForecastObservation>,
    val accumulatorHistory: List<VirtualAccumulatorHistoryEntry>,
) {
    companion object {
        fun empty() = FootballScreenStoreSnapshot(
            providerSyncSummaries = emptyList(),
            matches = emptyList(),
            recentResults = emptyList(),
            importedCompetitionCodes = emptyList(),
            selectedQuotes = emptyList(),
            selectedPicks = emptyList(),
            tickets = emptyList(),
            ticketMatches = emptyMap(),
            misliSyncState = null,
            misliOdds = emptyList(),
            topazSyncState = null,
            topazOdds = emptyList(),
            internationalOddsSyncState = null,
            internationalOddsSyncHistory = emptyList(),
            theOddsApiSyncState = null,
            theOddsApiSyncHistory = emptyList(),
            internationalOdds = emptyList(),
            internationalOddsHistory = emptyList(),
            marketReferenceSyncState = null,
            marketReferences = emptyList(),
            historicalByLeague = emptyMap(),
            checkedCouponHistory = emptyList(),
            prospectiveForecastHistory = emptyList(),
            prospectiveForecastEvaluationHistory = emptyList(),
            accumulatorHistory = emptyList(),
        )
    }
}

data class FootballScreenStoreLoadState(
    val snapshot: FootballScreenStoreSnapshot = FootballScreenStoreSnapshot.empty(),
    val loaded: Boolean = false,
    val error: String? = null,
    val version: Int = 0,
)

suspend fun Store.readFootballScreenSnapshot(selectedMatchId: Int?): FootballScreenStoreSnapshot =
    withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        fun checkActive() = context.ensureActive()

        val summaries = providerSyncSummaries()
        checkActive()
        val matchRows = matches()
        checkActive()
        val recent = recentCompletedMatches()
        checkActive()
        val competitionCodes = importedCompetitionCodes()
        checkActive()
        val selected = selectedMatchId?.let { id -> quotes(id) to picks(id) }
        checkActive()
        val savedTickets = tickets()
        checkActive()
        val savedTicketMatches = ticketMatches()
        checkActive()
        val misliState = bookmakerSyncState("misli")
        checkActive()
        val misliLines = freshBookmakerOdds("misli")
        checkActive()
        val topazState = bookmakerSyncState("topaz")
        checkActive()
        val topazLines = freshBookmakerOdds("topaz")
        checkActive()
        val internationalState = internationalOddsSyncState()
        checkActive()
        val internationalAttempts = internationalOddsSyncHistory(50)
        checkActive()
        val theOddsApiState = internationalOddsSyncState(THE_ODDS_API_PROVIDER_ID)
        checkActive()
        val theOddsApiAttempts = internationalOddsSyncHistory(50, THE_ODDS_API_PROVIDER_ID)
        checkActive()
        val internationalLines = freshInternationalOdds()
        checkActive()
        val internationalHistory = internationalOddsHistory(700)
        checkActive()
        val referenceState = marketReferenceSyncState()
        checkActive()
        val references = freshMarketReferences()
        checkActive()
        val history = historicalResultsByLeague()
        checkActive()
        val couponHistory = loggedCouponPicks()
        checkActive()
        val completeForecastHistory = prospectiveForecastHistory(0)
        checkActive()
        val forecastHistory = completeForecastHistory.take(100)
        val accumulators = virtualAccumulatorHistory()

        FootballScreenStoreSnapshot(
            providerSyncSummaries = summaries,
            matches = matchRows,
            recentResults = recent,
            importedCompetitionCodes = competitionCodes,
            selectedQuotes = selected?.first.orEmpty(),
            selectedPicks = selected?.second.orEmpty(),
            tickets = savedTickets,
            ticketMatches = savedTicketMatches,
            misliSyncState = misliState,
            misliOdds = misliLines,
            topazSyncState = topazState,
            topazOdds = topazLines,
            internationalOddsSyncState = internationalState,
            internationalOddsSyncHistory = internationalAttempts,
            theOddsApiSyncState = theOddsApiState,
            theOddsApiSyncHistory = theOddsApiAttempts,
            internationalOdds = internationalLines,
            internationalOddsHistory = internationalHistory,
            marketReferenceSyncState = referenceState,
            marketReferences = references,
            historicalByLeague = history,
            checkedCouponHistory = couponHistory,
            prospectiveForecastHistory = forecastHistory,
            prospectiveForecastEvaluationHistory = completeForecastHistory,
            accumulatorHistory = accumulators,
        )
    }
