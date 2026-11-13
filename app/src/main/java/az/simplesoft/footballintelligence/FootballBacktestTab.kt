package az.simplesoft.footballintelligence

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    automaticEloByCompetitionSeason: List<CompetitionSeasonWalkForwardMetrics> = emptyList(),
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
    var showMethodology by remember { mutableStateOf(false) }
    var showModelComparisons by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("Бэктест",style=MaterialTheme.typography.headlineMedium, fontWeight=FontWeight.Bold)
                Text("Оценка вероятностей на сохранённой футбольной истории.",style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { showMethodology = !showMethodology }) { Text(if (showMethodology) "Скрыть методику" else "Методика и ограничения") }
                if (showMethodology) Text("Walk-forward соблюдает временную границу каждого прогноза: в расчёт входят только результаты, доступные до cutoff. Это ретроспективная оценка текущих файлов, не архив публикаций и не доказательство будущей точности или прибыли. Date-only матчи используют календарную дату и консервативную доступность результата через два дня.", style=MaterialTheme.typography.bodySmall)
                if(automaticBacktestRunning) LinearProgressIndicator(Modifier.fillMaxWidth())
                if(automaticBacktestMessage.isNotBlank()) Text(automaticBacktestMessage,style=MaterialTheme.typography.bodySmall)
                automaticBacktestReport?.let { report ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCard("Прогнозов", report.forecastCount.toString(), "из ${report.targetCount} целей", Modifier.weight(1f))
                        MetricCard("Пропущено", report.skippedForInsufficientHistory.toString(), "мало истории", Modifier.weight(1f))
                    }
                    if(report.meanBrierScore==null||report.meanLogLoss==null) Text("Пока недостаточно предматчевой истории для проверяемого прогноза.",style=MaterialTheme.typography.bodySmall)
                    else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCard("Brier", "%.4f".format(report.meanBrierScore), "ниже лучше", Modifier.weight(1f))
                        MetricCard("Log Loss", "%.4f".format(report.meanLogLoss), "ниже лучше", Modifier.weight(1f))
                    }
                    WalkForwardCalibrationSummary(report)
                    Text("Срез рассчитан отдельно в границах соревнований. Результат не является обещанием прибыли.",style=MaterialTheme.typography.bodySmall)
                    if(automaticEloByCompetitionSeason.isNotEmpty()) {
                        var seasonDetailsExpanded by remember(automaticEloByCompetitionSeason) { mutableStateOf(false) }
                        val rowsWithForecasts=automaticEloByCompetitionSeason.count { it.forecastCount > 0 }
                        val noForecastRows=automaticEloByCompetitionSeason.size-rowsWithForecasts
                        Text("Качество Elo по сохранённым меткам лиги и сезона",fontWeight=FontWeight.SemiBold)
                        Text(
                            "Сезон не выводится из даты: используются метки источников. Групп ${automaticEloByCompetitionSeason.size}, с прогнозами $rowsWithForecasts, без прогноза $noForecastRows.",
                            style=MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick={seasonDetailsExpanded=!seasonDetailsExpanded},contentPadding=PaddingValues(0.dp)) {
                            Text(if(seasonDetailsExpanded) "Скрыть сезонные результаты" else "Показать сезонные результаты")
                        }
                        if(seasonDetailsExpanded) {
                            LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
                                items(
                                    items=automaticEloByCompetitionSeason,
                                    key={ "${it.competition}|${it.season ?: "<unknown>"}" },
                                ) { seasonal ->
                                    val seasonLabel=seasonal.season ?: "метка сезона не сохранена"
                                    Text(
                                        "${seasonal.competition} · $seasonLabel: прогнозов ${seasonal.forecastCount}/${seasonal.targetCount} · " +
                                            "Brier ${seasonal.meanBrierScore?.let { "%.4f".format(it) } ?: "—"} · " +
                                            "Log Loss ${seasonal.meanLogLoss?.let { "%.4f".format(it) } ?: "—"}" +
                                            if(seasonal.forecastCount in 1..29) " · малая выборка" else "",
                                        style=MaterialTheme.typography.bodySmall,
                                        modifier=Modifier.padding(vertical=3.dp),
                                    )
                                }
                            }
                            Text("Эти строки — объединённые as-of walk-forward прогнозы по исходным меткам сезона. Значения с n<30 описательны и слишком малы для вывода о качестве.",style=MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (automaticEloExperiment != null || automaticGoalComparison != null) {
                    TextButton(onClick = { showModelComparisons = !showModelComparisons }) {
                        Text(if (showModelComparisons) "Скрыть сравнение моделей и интервалы" else "Сравнение моделей и интервалы")
                    }
                }
                if (showModelComparisons) {
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
                    Text("Elo, Poisson и Home/Away performance",fontWeight=FontWeight.SemiBold)
                    if(comparison.validationStartMillis==null||comparison.holdoutStartMillis==null) {
                        Text("Недостаточно отдельных временных срезов для validation/holdout.",style=MaterialTheme.typography.bodySmall)
                    } else {
                        Text(
                            "Validation Poisson при ρ=0: Brier ${comparison.validationGoalMetrics.meanBrierScore?.let { "%.4f".format(it) } ?: "—"}, Log Loss ${comparison.validationGoalMetrics.meanLogLoss?.let { "%.4f".format(it) } ?: "—"} · прогнозов ${comparison.validationGoalMetrics.forecastCount}/${comparison.validationTargetCount}.",
                            style=MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Validation Home/Away: Brier ${comparison.validationHomeAwayMetrics.meanBrierScore?.let { "%.4f".format(it) } ?: "—"}, Log Loss ${comparison.validationHomeAwayMetrics.meanLogLoss?.let { "%.4f".format(it) } ?: "—"} · прогнозов ${comparison.validationHomeAwayMetrics.forecastCount}/${comparison.validationTargetCount}.",
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
                            "Парный bootstrap 95% Elo−Poisson (Δ=Elo−Poisson; минус означает ниже loss), n=${comparison.pairedHoldoutTargetCount}: Brier ${comparison.eloMinusPoissonBrier95?.let { "[%.4f; %.4f]".format(it.lower, it.upper) } ?: "не рассчитан при n<${comparison.minimumPairedFixturesForIntervals}"}; Log Loss ${comparison.eloMinusPoissonLogLoss95?.let { "[%.4f; %.4f]".format(it.lower, it.upper) } ?: "не рассчитан при n<${comparison.minimumPairedFixturesForIntervals}"}.",
                            style=MaterialTheme.typography.bodySmall,
                        )
                        val eloHomeAwayPaired=comparison.eloHomeAwayPairedHoldout
                        val homeAwayPaired=comparison.homeAwayPairedHoldout
                        Text(
                            "Holdout Elo/Home-Away на общих матчах ${comparison.homeAwayPairedHoldoutTargetCount}: Elo ${eloHomeAwayPaired.meanBrierScore?.let { "%.4f".format(it) } ?: "—"} Brier / ${eloHomeAwayPaired.meanLogLoss?.let { "%.4f".format(it) } ?: "—"} Log Loss; Home/Away ${homeAwayPaired.meanBrierScore?.let { "%.4f".format(it) } ?: "—"} / ${homeAwayPaired.meanLogLoss?.let { "%.4f".format(it) } ?: "—"}. Прогнозов Home/Away: ${comparison.homeAwayHoldoutForecastCount} из ${comparison.holdoutTargetCount}.",
                            style=MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Парный bootstrap 95% Elo−Home/Away (Δ=Elo−Home/Away; минус означает ниже loss), n=${comparison.homeAwayPairedHoldoutTargetCount}: Brier ${comparison.eloMinusHomeAwayBrier95?.let { "[%.4f; %.4f]".format(it.lower, it.upper) } ?: "не рассчитан при n<${comparison.minimumPairedFixturesForIntervals}"}; Log Loss ${comparison.eloMinusHomeAwayLogLoss95?.let { "[%.4f; %.4f]".format(it.lower, it.upper) } ?: "не рассчитан при n<${comparison.minimumPairedFixturesForIntervals}"}.",
                            style=MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Elo-параметры ${if(comparison.usedValidationSelectedEloConfig) "выбраны на validation" else "оставлены по умолчанию"}. Poisson использует общие голы команды, Home/Away отдельно считает домашние и гостевые голы и сглаживает их на ${comparison.homeAwayConfig.smoothingMatches.toInt()} псевдоматчах. Для Home/Away нужно ${comparison.homeAwayConfig.minimumLeagueMatches} матчей лиги и минимум ${comparison.homeAwayConfig.minimumMatchesPerSplit} домашних матчей хозяев и гостевых матчей гостей. Holdout не используется для выбора параметров.",
                            style=MaterialTheme.typography.labelSmall,
                        )
                        val combined = comparison.combinedModel
                        Text("Elo + Poisson: экспериментальная смесь",fontWeight=FontWeight.SemiBold)
                        if(combined.validationPairedForecastCount < combined.minimumValidationForecasts) {
                            Text(
                                "Вес не выбран: общих прогнозов на validation ${combined.validationPairedForecastCount}; требуется ${combined.minimumValidationForecasts}. Holdout смеси не рассчитывался.",
                                style=MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            val candidate = combined.validationCandidate
                            if(candidate == null) {
                                Text("Не удалось выбрать смесь по доступным validation-прогнозам.",style=MaterialTheme.typography.bodySmall)
                            } else {
                                val blendMetrics = candidate.metrics
                                Text(
                                    "На validation выбраны веса Elo ${"%.1f".format(candidate.eloWeight)} / Poisson ${"%.1f".format(1.0-candidate.eloWeight)} из сетки 0.1–0.9 (n=${combined.validationPairedForecastCount}). Elo: Brier ${combined.validationEloMetrics.meanBrierScore?.let { "%.4f".format(it) } ?: "—"}, Log Loss ${combined.validationEloMetrics.meanLogLoss?.let { "%.4f".format(it) } ?: "—"}; Poisson: ${combined.validationPoissonMetrics.meanBrierScore?.let { "%.4f".format(it) } ?: "—"}, ${combined.validationPoissonMetrics.meanLogLoss?.let { "%.4f".format(it) } ?: "—"}; смесь: ${blendMetrics.meanBrierScore?.let { "%.4f".format(it) } ?: "—"}, ${blendMetrics.meanLogLoss?.let { "%.4f".format(it) } ?: "—"}.",
                                    style=MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    if(combined.validationGatePassed) {
                                        "Порог validation пройден: смесь лучше Elo и Poisson одновременно по Brier и Log Loss."
                                    } else {
                                        "Порог validation не пройден: смесь не улучшила оба простых метода одновременно по обоим показателям."
                                    },
                                    style=MaterialTheme.typography.bodySmall,
                                )
                                val holdout = combined.holdoutMetrics
                                Text(
                                    "Отдельный holdout для validation-выбранного веса: смесь ${holdout?.meanBrierScore?.let { "%.4f".format(it) } ?: "—"} Brier / ${holdout?.meanLogLoss?.let { "%.4f".format(it) } ?: "—"} Log Loss; Elo ${combined.holdoutEloMetrics.meanBrierScore?.let { "%.4f".format(it) } ?: "—"} / ${combined.holdoutEloMetrics.meanLogLoss?.let { "%.4f".format(it) } ?: "—"}; Poisson ${combined.holdoutPoissonMetrics.meanBrierScore?.let { "%.4f".format(it) } ?: "—"} / ${combined.holdoutPoissonMetrics.meanLogLoss?.let { "%.4f".format(it) } ?: "—"} (n=${combined.holdoutPairedForecastCount}/${combined.minimumHoldoutForecasts}).",
                                    style=MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    when {
                                        combined.holdoutPairedForecastCount < combined.minimumHoldoutForecasts ->
                                            "Holdout-порог не достигнут: для проверки нужно не менее ${combined.minimumHoldoutForecasts} общих прогнозов."
                                        combined.holdoutGatePassed ->
                                            "Holdout-gate пройден: validation-выбранная смесь лучше Elo и Poisson по обоим показателям. Это всё ещё одна ретроспективная проверка."
                                        else ->
                                            "Holdout-gate не пройден: смесь не превзошла оба базовых метода одновременно по Brier и Log Loss."
                                    },
                                    style=MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        Text(
                            "Смесь остаётся только исследовательской: веса выбираются на validation, holdout не настраивается, live-прогнозы и купоны не меняются. Один исторический split не подтверждает будущую точность.",
                            style=MaterialTheme.typography.labelSmall,
                        )
                    }
                    Text("Все сравниваемые модели используют только результаты, доступные до cutoff. Bootstrap независимо пересэмплирует матчи; повторяющиеся команды и туры могут занижать неопределённость. Это одна проверка текущих исторических файлов, не подтверждение будущей точности или преимущества. Home/Away пока исследовательская модель и не влияет на live-прогнозы или купоны.",style=MaterialTheme.typography.labelSmall)
                }
                }
                HorizontalDivider()
                Text("Дополнительный импортированный CSV",fontWeight=FontWeight.SemiBold)
                Text("Ретроспективная оценка по импортированным результатам. Для каждого календарного дня используется один срез, без результатов этого дня; строки CSV становятся доступны только спустя два дня.",style=MaterialTheme.typography.bodySmall)
                Text("Это не воспроизведение архивных котировок и не сравнение с рынком: временные версии исходных файлов не сохранены, а вероятности не калиброваны.",style=MaterialTheme.typography.bodySmall)
                if(importedCompetitionCodes.isEmpty()) {
                    Text("Дополнительный walk-forward по CSV станет доступен после импорта в настройках. Для обычного анализа работает автоматическая база.")
                } else {
                    Text("Код соревнования из CSV",fontWeight=FontWeight.SemiBold)
                    importedCompetitionCodes.chunked(2).forEach { codeRow ->
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            codeRow.forEach { code ->
                                FilterChip(
                                    selected=activeBacktestCode==code,
                                    onClick={onSelectBacktestCode(code)},
                                    label={Text(code, maxLines=1, overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)},
                                    modifier=Modifier.weight(1f),
                                )
                            }
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
