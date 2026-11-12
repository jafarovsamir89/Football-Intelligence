package az.simplesoft.footballintelligence

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import az.simplesoft.footballintelligence.analytics.*
import java.time.Instant
import java.time.ZoneOffset

@Composable
internal fun FootballBacktestTabContent(
    automaticBacktestRunning: Boolean,
    automaticBacktestMessage: String,
    automaticBacktestReport: EloWalkForwardReport?,
    automaticEloExperiment: EloChronologicalExperimentReport?,
    automaticGoalComparison: GoalModelComparisonReport?,
    importedCompetitionCodes: List<String>,
    activeBacktestCode: String,
    onSelectBacktestCode: (String) -> Unit,
    onRunImportedBacktest: (String) -> Unit,
    backtestRunning: Boolean,
    backtestMessage: String,
    backtestReportCode: String,
    backtestReport: EloWalkForwardReport?,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("Elo walk-forward",style=MaterialTheme.typography.titleLarge)
                Text("Автоматическая оценка локальной истории OpenFootball/OpenLigaDB по отдельным соревнованиям. Для каждой цели модель использует только результаты, доступные до её временной границы.",style=MaterialTheme.typography.bodySmall)
                Text("Это ретроспективная оценка текущих файлов, а не архив исходных публикаций. Для OpenFootball без времени kickoff используется календарная дата и консервативная доступность результата через два дня; поздно полученные результаты OpenLigaDB не попадают в прошлое обучение. Поэтому цифры исследовательские и не доказывают будущую точность или преимущество.",style=MaterialTheme.typography.bodySmall)
                if(automaticBacktestRunning) LinearProgressIndicator(Modifier.fillMaxWidth())
                if(automaticBacktestMessage.isNotBlank()) Text(automaticBacktestMessage,style=MaterialTheme.typography.bodySmall)
                automaticBacktestReport?.let { report ->
                    Text("Автоматическая база: целей ${report.targetCount} · рассчитано прогнозов ${report.forecastCount} · пропущено из-за малого прошлого сэмпла ${report.skippedForInsufficientHistory}",fontWeight=FontWeight.SemiBold)
                    if(report.meanBrierScore==null||report.meanLogLoss==null) Text("Пока недостаточно предматчевой истории для проверяемого прогноза.",style=MaterialTheme.typography.bodySmall)
                    else Text("Средний Brier: ${"%.4f".format(report.meanBrierScore)} · средний Log Loss: ${"%.4f".format(report.meanLogLoss)}")
                    WalkForwardCalibrationSummary(report)
                    Text("Срез рассчитан отдельно в границах соревнований. Результат не является обещанием прибыли.",style=MaterialTheme.typography.bodySmall)
                }
                automaticEloExperiment?.let { experiment ->
                    HorizontalDivider()
                    Text("Хронологический выбор параметров Elo",fontWeight=FontWeight.SemiBold)
                    Text(
                        "Первые 60% временных срезов — разогрев, следующие 20% — выбор по Log Loss, последние 20% — отдельная проверка. Рейтинг ведётся отдельно по соревнованиям; holdout не участвует в выборе.",
                        style=MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Разогрев: ${experiment.training.forecastCount}/${experiment.training.targetCount} прогнозов · Brier ${experiment.training.meanBrierScore?.let { "%.4f".format(it) } ?: "—"} · Log Loss ${experiment.training.meanLogLoss?.let { "%.4f".format(it) } ?: "—"}",
                        style=MaterialTheme.typography.bodySmall,
                    )
                    val validationDay=experiment.validationStartMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() } ?: "—"
                    val testDay=experiment.testStartMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() } ?: "—"
                    Text("Начало validation: $validationDay · начало holdout: $testDay · сетка ${experiment.candidates.size} конфигураций",style=MaterialTheme.typography.bodySmall)
                    val selected=experiment.selectedConfig
                    val selectedValidation=experiment.selectedValidation
                    val selectedTest=experiment.selectedTest
                    if(selected!=null&&selectedValidation!=null&&selectedTest!=null) {
                        Text(
                            "Выбор validation: K=${selected.kFactor.toInt()}, home=${selected.homeAdvantage.toInt()}, draw prior=${"%.2f".format(selected.priorDrawProbability)} · Log Loss ${selectedValidation.meanLogLoss?.let { "%.4f".format(it) } ?: "—"} (n=${selectedValidation.forecastCount})",
                            fontWeight=FontWeight.Medium,
                            style=MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Holdout: выбранные Brier ${selectedTest.meanBrierScore?.let { "%.4f".format(it) } ?: "—"}, Log Loss ${selectedTest.meanLogLoss?.let { "%.4f".format(it) } ?: "—"} (n=${selectedTest.forecastCount}); текущие настройки Brier ${experiment.defaultTest.meanBrierScore?.let { "%.4f".format(it) } ?: "—"}, Log Loss ${experiment.defaultTest.meanLogLoss?.let { "%.4f".format(it) } ?: "—"} (n=${experiment.defaultTest.forecastCount}).",
                            style=MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        val bestValidationForecasts=experiment.candidates.maxOfOrNull { it.validation.forecastCount } ?: 0
                        Text(
                            "Настройки не выбраны: validation дала максимум $bestValidationForecasts прогнозов; нужно не меньше ${experiment.minimumValidationForecastsForSelection}. Holdout показан только для текущих настроек.",
                            style=MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Holdout текущих настроек: Brier ${experiment.defaultTest.meanBrierScore?.let { "%.4f".format(it) } ?: "—"}, Log Loss ${experiment.defaultTest.meanLogLoss?.let { "%.4f".format(it) } ?: "—"} (n=${experiment.defaultTest.forecastCount}).",
                            style=MaterialTheme.typography.bodySmall,
                        )
                    }
                    experiment.candidates.take(3).forEachIndexed { index,candidate ->
                        Text(
                            "${index+1}. K=${candidate.config.kFactor.toInt()}, home=${candidate.config.homeAdvantage.toInt()}, draw prior=${"%.2f".format(candidate.config.priorDrawProbability)} · validation Log Loss ${candidate.validation.meanLogLoss?.let { "%.4f".format(it) } ?: "—"} · Brier ${candidate.validation.meanBrierScore?.let { "%.4f".format(it) } ?: "—"} (n=${candidate.validation.forecastCount})",
                            style=MaterialTheme.typography.labelSmall,
                        )
                    }
                    Text("Это один ретроспективный split текущей локальной базы, без архивных версий исходных файлов. Параметры не меняют сохранённые прогнозы и не означают будущую точность или прибыль.",style=MaterialTheme.typography.labelSmall)
                }
                automaticGoalComparison?.let { comparison ->
                    HorizontalDivider()
                    Text("Poisson по голам: сравнение с Elo",fontWeight=FontWeight.SemiBold)
                    if(comparison.validationStartMillis==null||comparison.holdoutStartMillis==null) {
                        Text("Недостаточно отдельных временных срезов для validation/holdout.",style=MaterialTheme.typography.bodySmall)
                    } else {
                        Text(
                            "Validation Poisson при ρ=0: Brier ${comparison.validationGoalMetrics.meanBrierScore?.let { "%.4f".format(it) } ?: "—"}, Log Loss ${comparison.validationGoalMetrics.meanLogLoss?.let { "%.4f".format(it) } ?: "—"} · прогнозов ${comparison.validationGoalMetrics.forecastCount}/${comparison.validationTargetCount}.",
                            style=MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            if(comparison.rhoSelectionAvailable) {
                                "Dixon–Coles ρ=${comparison.selectedDixonColesRho?.let { "%.2f".format(it) } ?: "—"} выбран только на validation по Log Loss (n=${comparison.selectedRhoValidationMetrics?.forecastCount}); порог ${comparison.minimumValidationForecastsForRhoSelection}."
                            } else {
                                "Dixon–Coles ρ оставлен 0: validation дала ${comparison.rhoCandidates.maxOfOrNull { it.validation.forecastCount } ?: 0} прогнозов; выбор требует минимум ${comparison.minimumValidationForecastsForRhoSelection}."
                            },
                            style=MaterialTheme.typography.bodySmall,
                        )
                        val eloPaired=comparison.eloPairedHoldout
                        val goalPaired=comparison.goalPairedHoldout
                        Text(
                            "Holdout на общих матчах ${comparison.pairedHoldoutTargetCount}: Elo ${eloPaired.meanBrierScore?.let { "%.4f".format(it) } ?: "—"} Brier / ${eloPaired.meanLogLoss?.let { "%.4f".format(it) } ?: "—"} Log Loss; Poisson ${goalPaired.meanBrierScore?.let { "%.4f".format(it) } ?: "—"} / ${goalPaired.meanLogLoss?.let { "%.4f".format(it) } ?: "—"}. Доступность прогнозов на holdout: Elo ${comparison.eloHoldoutForecastCount}, Poisson ${comparison.goalHoldoutForecastCount} из ${comparison.holdoutTargetCount}.",
                            style=MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Elo-параметры ${if(comparison.usedValidationSelectedEloConfig) "выбраны на validation" else "оставлены по умолчанию"}; Poisson сглаживает атаки/оборону на ${comparison.goalConfig.smoothingMatches.toInt()} псевдоматчах, требует ${comparison.goalConfig.minimumLeagueMatches} матчей лиги и ${comparison.goalConfig.minimumMatchesPerTeam} матчей команды. Holdout не используется для выбора ρ.",
                            style=MaterialTheme.typography.labelSmall,
                        )
                    }
                    Text("Обе модели используют только результаты, доступные до cutoff. Это одна проверка текущих исторических файлов; она не подтверждает будущую точность или преимущество.",style=MaterialTheme.typography.labelSmall)
                }
                HorizontalDivider()
                Text("Дополнительный импортированный CSV",fontWeight=FontWeight.SemiBold)
                Text("Ретроспективная оценка по импортированным результатам. Для каждого календарного дня используется один срез, без результатов этого дня; строки CSV становятся доступны только спустя два дня.",style=MaterialTheme.typography.bodySmall)
                Text("Это не воспроизведение архивных котировок и не сравнение с рынком: временные версии исходных файлов не сохранены, а вероятности не калиброваны.",style=MaterialTheme.typography.bodySmall)
                if(importedCompetitionCodes.isEmpty()) {
                    Text("Дополнительный walk-forward по CSV станет доступен после импорта в настройках. Для обычного анализа работает автоматическая база.")
                } else {
                    Text("Код соревнования из CSV",fontWeight=FontWeight.SemiBold)
                    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        importedCompetitionCodes.forEach { code ->
                            FilterChip(selected=activeBacktestCode==code,onClick={onSelectBacktestCode(code)},label={Text(code)})
                        }
                    }
                    Text("Минимум для прогноза: ${EloEngine.MIN_HISTORY_MATCHES_FOR_FORECAST} прошлых матчей соревнования и ${EloEngine.MIN_TEAM_MATCHES_FOR_FORECAST} матча каждой команды.",style=MaterialTheme.typography.bodySmall)
                    Button(onClick={onRunImportedBacktest(activeBacktestCode)},enabled=!backtestRunning,modifier=Modifier.fillMaxWidth()) { Text("Рассчитать walk-forward") }
                    if(backtestRunning) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(backtestMessage.isNotBlank()) Text(backtestMessage,style=MaterialTheme.typography.bodySmall)
                    if(backtestReportCode==activeBacktestCode) backtestReport?.let { report ->
                        HorizontalDivider()
                        Text("Матчей: ${report.targetCount} · прогнозов: ${report.forecastCount} · пропущено из-за малого сэмпла: ${report.skippedForInsufficientHistory}",fontWeight=FontWeight.SemiBold)
                        if(report.meanBrierScore==null||report.meanLogLoss==null) {
                            Text("Недостаточно предматчевой истории для расчёта показателей.",style=MaterialTheme.typography.bodySmall)
                        } else {
                            Text("Средний Brier: ${"%.4f".format(report.meanBrierScore)} · средний Log Loss: ${"%.4f".format(report.meanLogLoss)}")
                        }
                        WalkForwardCalibrationSummary(report)
                        Text("Результаты показывают только качество вероятностных оценок на этом локальном файле; они не доказывают преимущество, прибыль или превосходство над букмекерами.",style=MaterialTheme.typography.bodySmall)
                    }
                }
    }
}

@Composable
internal fun WalkForwardCalibrationSummary(report:EloWalkForwardReport) {
    if(report.predictions.isEmpty()) return
    val calibration=remember(report) { CalibrationDiagnostics.evaluateWalkForward(report.predictions) }
    var expanded by remember(report) { mutableStateOf(false) }
    val ece=calibration.meanOneVsRestEce?.let { "%.1f".format(it*100) } ?: "н/д"
    Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
        Text("Калибровочная диагностика: mean one-vs-rest ECE $ece% · ${calibration.predictionCount} прогнозов",fontWeight=FontWeight.SemiBold)
        calibration.outcomes.forEach { outcome ->
            val outcomeEce=outcome.expectedCalibrationError?.let { "%.1f".format(it*100) } ?: "н/д"
            Text("${outcome.outcome.label}: ECE $outcomeEce% · n=${outcome.sampleCount}",style=MaterialTheme.typography.bodySmall)
        }
        Text("Равные интервалы вероятности 0–100%; ECE показывает среднее расхождение прогноза и частоты исхода в занятых корзинах. Это описание ретроспективной выборки, не калибровка модели.",style=MaterialTheme.typography.labelSmall)
        TextButton(onClick={expanded=!expanded},contentPadding=PaddingValues(0.dp)) {
            Text(if(expanded) "Скрыть корзины" else "Показать занятые корзины")
        }
        if(expanded) {
            calibration.outcomes.forEach { outcome ->
                if(outcome.occupiedBins.isNotEmpty()) {
                    Text("${outcome.outcome.label} · средняя вероятность / частота / число",fontWeight=FontWeight.Medium,style=MaterialTheme.typography.labelMedium)
                    outcome.occupiedBins.forEach { bin ->
                        Text(
                            "${"%.0f".format(bin.lowerInclusive*100)}–${"%.0f".format(bin.upperBound*100)}% · " +
                                "${"%.1f".format(bin.meanPredictedProbability*100)}% / " +
                                "${"%.1f".format(bin.observedFrequency*100)}% / ${bin.sampleCount}",
                            style=MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
