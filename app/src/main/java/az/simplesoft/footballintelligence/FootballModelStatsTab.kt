package az.simplesoft.footballintelligence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import az.simplesoft.footballintelligence.analytics.ProspectiveForecastObservation
import az.simplesoft.footballintelligence.analytics.ProspectiveForecastReport
import java.util.Locale

@Composable
internal fun FootballModelStatsTab(
    report: ProspectiveForecastReport,
    history: List<ProspectiveForecastObservation>,
    updating: Boolean,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Качество моделей", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Только сохранённые прогнозы и уже доступные результаты", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (updating) {
            SourceStatus("Проверка", "Обновляется", "Сохраняю согласованный снимок метрик")
        } else if (report.modelReports.isEmpty()) {
            SourceStatus("Проверка ещё накапливается", "${report.snapshotCount} снимков", "Метрики появятся после завершения матчей и синхронизации счёта")
        } else {
            report.modelReports.forEach { model ->
                androidx.compose.material3.ElevatedCard(shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(model.modelVersion, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MetricCard("Проверено", model.settledCount.toString(), "матчей", Modifier.weight(1f))
                            MetricCard("Ожидают", model.pendingCount.toString(), "результатов", Modifier.weight(1f))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MetricCard("Brier", model.meanBrierScore?.let { "%.4f".format(Locale.US, it) } ?: "—", "ниже лучше", Modifier.weight(1f))
                            MetricCard("Log Loss", model.meanLogLoss?.let { "%.4f".format(Locale.US, it) } ?: "—", "ниже лучше", Modifier.weight(1f))
                        }
                        Text(
                            model.topOutcomeAccuracy?.let { "Самый вероятный исход совпал в ${"%.1f".format(Locale.US, it * 100)}% случаев (n=${model.settledCount})." }
                                ?: "Точность наиболее вероятного исхода пока не рассчитана.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if(model.frequencyBaselineReports.isEmpty()) {
                            Text(
                                "Сравнение будущих прогнозов с базовой частотой начнётся на новых снимках. Старые снимки не содержат сохранённую базу.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            model.frequencyBaselineReports.forEach { baseline ->
                                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text("Проверка Elo против простой базы",fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.bodyMedium)
                                    Text("База — доли побед хозяев, ничьих и побед гостей в той же лиге до этого прогноза.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Одинаковая выборка: проверено ${baseline.settledCount} / ${baseline.fixtureCount} матчей.",style=MaterialTheme.typography.bodySmall)
                                    if(baseline.settledCount==0) {
                                        Text("Снимки сохранены до матчей; сравнение появится после получения результатов.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else {
                                        if(baseline.settledCount<30) {
                                            Text("Мало завершённых наблюдений: пока нельзя делать вывод, что Elo лучше базы.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text(
                                            "Brier: Elo ${baseline.eloMeanBrierScore?.let { "%.4f".format(Locale.US,it) } ?: "—"} · база ${baseline.baselineMeanBrierScore?.let { "%.4f".format(Locale.US,it) } ?: "—"} · Δ Elo−база ${baseline.eloMinusBaselineBrierScore?.let { "%+.4f".format(Locale.US,it) } ?: "—"} (ниже лучше).",
                                            style=MaterialTheme.typography.bodySmall,
                                        )
                                        Text(
                                            "Log Loss: Elo ${baseline.eloMeanLogLoss?.let { "%.4f".format(Locale.US,it) } ?: "—"} · база ${baseline.baselineMeanLogLoss?.let { "%.4f".format(Locale.US,it) } ?: "—"} · Δ Elo−база ${baseline.eloMinusBaselineLogLoss?.let { "%+.4f".format(Locale.US,it) } ?: "—"} (ниже лучше).",
                                            style=MaterialTheme.typography.bodySmall,
                                        )
                                        Text("Δ = ошибка Elo минус ошибка базы: отрицательное значение означает меньшую ошибку Elo. Это не совет ставить и не расчёт прибыли.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Text("Недавняя проверка", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        val recent = history.take(12)
        if (recent.isEmpty()) {
            Text("Первые проверяемые прогнозы появятся автоматически после накопления достаточной предматчевой истории.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            recent.forEach { snapshot ->
                val actual = when {
                    snapshot.actualOutcome == null -> "Ожидает результата"
                    else -> "${labelOutcome(snapshot.actualOutcome)} ${snapshot.homeGoals}:${snapshot.awayGoals} · ${if (snapshot.probabilities.values().indices.maxBy { snapshot.probabilities.values()[it] }.let { listOf("H", "D", "A")[it] } == snapshot.actualOutcome) "совпало" else "не совпало"}"
                }
                SourceStatus(
                    title = "${snapshot.homeTeam} — ${snapshot.awayTeam}",
                    status = actual,
                    detail = "${snapshot.competition} · модель ${snapshot.modelVersion} · ${"%.0f".format(Locale.US, snapshot.probabilities.home * 100)} / ${"%.0f".format(Locale.US, snapshot.probabilities.draw * 100)} / ${"%.0f".format(Locale.US, snapshot.probabilities.away * 100)}%",
                    healthy = when {
                        snapshot.actualOutcome == null -> null
                        else -> snapshot.probabilities.values().indices.maxBy { snapshot.probabilities.values()[it] }.let { listOf("H", "D", "A")[it] == snapshot.actualOutcome }
                    },
                )
            }
        }
        Text("Оценки описывают сохранённую выборку и не доказывают будущую точность или прибыль.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
