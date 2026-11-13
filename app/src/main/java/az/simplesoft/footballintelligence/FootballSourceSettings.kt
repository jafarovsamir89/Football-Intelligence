package az.simplesoft.footballintelligence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import az.simplesoft.footballintelligence.data.FootballDataStatsAttempt
import az.simplesoft.footballintelligence.data.INTERNATIONAL_ODDS_FRESHNESS_MILLIS
import az.simplesoft.footballintelligence.data.InternationalOddsSnapshot
import az.simplesoft.footballintelligence.data.MOVIODDS_PROVIDER_ID
import az.simplesoft.footballintelligence.data.ProviderSyncSummary
import az.simplesoft.footballintelligence.data.THE_ODDS_API_PROVIDER_ID

@Composable
internal fun FootballSourceSettings(
    providerSyncSummaries: List<ProviderSyncSummary>,
    syncStatus: String,
    misliCount: Int,
    misliStatus: String,
    topazCount: Int,
    topazStatus: String,
    internationalOdds: List<InternationalOddsSnapshot>,
    internationalStatus: String,
    theOddsApiStatus: String,
    theOddsApiConfigured: Boolean,
    statsAttempt: FootballDataStatsAttempt?,
) {
    val nowMillis = rememberClockMinute()
    val freshInternationalOdds = internationalOdds.filter {
        it.kickoffMillis > nowMillis && it.capturedAtMillis <= nowMillis + 60_000L &&
            nowMillis - it.capturedAtMillis <= INTERNATIONAL_ODDS_FRESHNESS_MILLIS
    }
    val moviCount = freshInternationalOdds.count { it.providerId == MOVIODDS_PROVIDER_ID }
    val theOddsApiCount = freshInternationalOdds.count { it.providerId == THE_ODDS_API_PROVIDER_ID }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Статус источников", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(syncStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        providerSyncSummaries.forEach { source ->
            SourceStatus(
                title = source.providerId,
                status = source.status,
                detail = "${source.recordsStored} сохранённых записей${source.lastSuccessAtMillis?.let { " · последнее успешное обновление ${niceTime(java.time.Instant.ofEpochMilli(it).toString())}" } ?: ""}",
                healthy = when (source.status.uppercase()) { "OK" -> true; "ERROR" -> false; else -> null },
            )
            source.error?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        }
        SourceStatus("Misli", misliStatus, "$misliCount свежих будущих линий", healthy = misliStatus == "LIVE")
        SourceStatus("TOPAZ / eTopaz", topazStatus, "$topazCount свежих будущих линий", healthy = topazStatus == "LIVE")
        SourceStatus("bet365 · MoviOdds", internationalStatus, "$moviCount свежих будущих линий", healthy = moviCount > 0)
        SourceStatus(
            "The Odds API",
            if (theOddsApiConfigured) theOddsApiStatus else "Ключ не включён в эту сборку",
            "$theOddsApiCount свежих будущих линий · ключ вшивается только в личный APK",
            healthy = if (theOddsApiConfigured) theOddsApiCount > 0 else null,
        )
        statsAttempt?.let { attempt ->
            ElevatedCard(shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Дополнительная статистика", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("Football-Data.co.uk · ${attempt.status} · ${attempt.division}/${attempt.season} · найдено ${attempt.rowsMatched} из ${attempt.rowsRead}", style = MaterialTheme.typography.bodySmall)
                    attempt.detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        Text("Android может отложить фоновые обновления. Сохранённые матчи и результаты доступны офлайн. Коэффициенты отображаются только при наличии свежего подтверждённого снимка.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
