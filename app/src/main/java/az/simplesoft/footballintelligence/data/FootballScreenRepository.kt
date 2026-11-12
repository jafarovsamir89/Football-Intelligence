package az.simplesoft.footballintelligence.data

import az.simplesoft.footballintelligence.FootballScreenStoreSnapshot
import az.simplesoft.footballintelligence.Store
import az.simplesoft.footballintelligence.readFootballScreenSnapshot
import az.simplesoft.footballintelligence.analytics.AutomaticBacktestReports
import az.simplesoft.footballintelligence.analytics.ForecastedMatch
import az.simplesoft.footballintelligence.analytics.HistoricalMatch
import az.simplesoft.footballintelligence.analytics.VirtualCouponCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable

/** Screen-facing persistence boundary. Workers may continue using Store directly. */
interface FootballScreenRepository : Closeable {
    suspend fun readScreenSnapshot(selectedMatchId: Int?): FootballScreenStoreSnapshot
    suspend fun historicalResultsBefore(league: String, cutoffMillis: Long): List<HistoricalMatch>
    suspend fun recordProspectiveForecastsAndSettle(forecasts: List<ForecastedMatch>): Int
    suspend fun recordCouponDrafts(modelDrafts: List<VirtualCouponCandidate>, accumulators: List<VirtualCouponCandidate>): Int
    suspend fun importHistoricalResults(fileName: String, report: FootballCsvImportReport): StoredFootballCsvImport
    suspend fun importedHistory(competitionCode: String): List<ImportedHistoryRow>
    suspend fun automaticBacktestReports(): AutomaticBacktestReports
    suspend fun fixtureIdentities(): List<FixtureIdentity>
    suspend fun saveBookmakerCapture(
        providerId: String,
        checkedAtMillis: Long,
        status: OddsProviderStatus,
        snapshots: List<BookmakerOddsSnapshot>,
        error: String? = null,
    )
}

/** SQLite-backed repository adapter; database operations are confined to Dispatchers.IO. */
class StoreFootballScreenRepository(
    private val store: Store,
) : FootballScreenRepository {
    override suspend fun readScreenSnapshot(selectedMatchId: Int?) =
        store.readFootballScreenSnapshot(selectedMatchId)

    override suspend fun historicalResultsBefore(league: String, cutoffMillis: Long) =
        withContext(Dispatchers.IO) { store.historicalResultsBefore(league, cutoffMillis) }

    override suspend fun recordProspectiveForecastsAndSettle(forecasts: List<ForecastedMatch>) =
        withContext(Dispatchers.IO) {
            store.recordProspectiveForecastSnapshots(forecasts) + store.settleProspectiveForecastSnapshots()
        }

    override suspend fun recordCouponDrafts(
        modelDrafts: List<VirtualCouponCandidate>,
        accumulators: List<VirtualCouponCandidate>,
    ) = withContext(Dispatchers.IO) {
        store.recordCouponCandidates(modelDrafts) + store.recordAccumulatorDrafts(accumulators)
    }

    override suspend fun importHistoricalResults(fileName: String, report: FootballCsvImportReport) =
        withContext(Dispatchers.IO) { store.importHistoricalResults(fileName, report) }

    override suspend fun importedHistory(competitionCode: String) =
        withContext(Dispatchers.IO) { store.importedHistory(competitionCode) }

    override suspend fun automaticBacktestReports() =
        withContext(Dispatchers.IO) { store.automaticBacktestReports() }

    override suspend fun fixtureIdentities() =
        withContext(Dispatchers.IO) { store.matchIdentities() }

    override suspend fun saveBookmakerCapture(
        providerId: String,
        checkedAtMillis: Long,
        status: OddsProviderStatus,
        snapshots: List<BookmakerOddsSnapshot>,
        error: String?,
    ) = withContext(Dispatchers.IO) {
        store.saveBookmakerCapture(providerId, checkedAtMillis, status, snapshots, error)
    }

    override fun close() = store.close()
}
