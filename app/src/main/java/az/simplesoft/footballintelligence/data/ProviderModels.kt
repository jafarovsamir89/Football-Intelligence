package az.simplesoft.footballintelligence.data

import java.time.LocalDate

enum class KickoffPrecision { INSTANT, DATE_ONLY }

data class ProviderMatch(
    val providerId: String,
    val providerMatchId: String,
    val competitionCode: String,
    val competitionName: String,
    val season: String,
    val eventDate: LocalDate,
    val kickoffMillis: Long,
    val kickoffPrecision: KickoffPrecision,
    val homeTeam: String,
    val awayTeam: String,
    val homeGoals: Int?,
    val awayGoals: Int?,
    val safeAvailableAtMillis: Long?,
    val sourceUrl: String,
    val capturedAtMillis: Long,
) {
    val finished: Boolean get() = homeGoals != null && awayGoals != null
}

data class ProviderSyncSummary(
    val providerId: String,
    val status: String,
    val lastCheckedAtMillis: Long,
    val lastSuccessAtMillis: Long?,
    val recordsStored: Int,
    val error: String?,
)

data class ProviderFileCache(
    val etag: String?,
    val lastModified: String?,
    val lastCheckedAtMillis: Long,
    val recordCount: Int,
    val cachedBody: String?,
)

data class OpenLigaLeagueState(
    val sourceChange: String?,
    val currentGroupOrderId: Int?,
    val lastFullSyncAtMillis: Long,
    val checkedAtMillis: Long,
    val recordCount: Int,
)
