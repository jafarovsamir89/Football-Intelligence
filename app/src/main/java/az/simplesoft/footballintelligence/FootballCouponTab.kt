package az.simplesoft.footballintelligence

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import az.simplesoft.footballintelligence.analytics.*
import az.simplesoft.footballintelligence.data.BookmakerOddsSnapshot
import az.simplesoft.footballintelligence.data.BookmakerOddsProvider
import az.simplesoft.footballintelligence.data.visibleBookmakerStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
internal fun FootballCouponTabContent(
    misliSyncState: BookmakerSyncState?,
    misliOdds: List<BookmakerOddsSnapshot>,
    topazSyncState: BookmakerSyncState?,
    topazOdds: List<BookmakerOddsSnapshot>,
    forecastedMatches: List<ForecastedMatch>,
    showBookmakerQuotes: Boolean,
    onToggleBookmakerQuotes: () -> Unit,
    oddsProviders: List<BookmakerOddsProvider>,
    accumulatorDrafts: List<VirtualCouponCandidate>,
    accumulatorHistory: List<VirtualAccumulatorHistoryEntry>,
    accumulatorEvaluationHistory: List<VirtualAccumulatorHistoryEntry>,
    checkedCouponHistory: List<LoggedCouponPick>,
    prospectiveForecastHistory: List<ProspectiveForecastObservation>,
    prospectiveForecastReport: ProspectiveForecastReport,
    analyticsUpdating: Boolean = false,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("Автоматические модельные черновики",style=MaterialTheme.typography.titleLarge)
                Text("Подбор наиболее вероятного исхода строится по Poisson с затуханием старых результатов; при короткой истории используется резервный Elo. Черновики раздельны по операторам. Цена показывается только для свежей линии того же букмекера и матча; модель не проверена на будущих матчах.",style=MaterialTheme.typography.bodySmall)
                Text("Misli.az: ${visibleBookmakerStatus(misliSyncState?.status,misliSyncState?.lastCheckedAtMillis,misliOdds.size)} · свежих линий ${misliOdds.size}, точных матчей ${misliOdds.count { it.matchId!=null }}. TOPAZ/eTopaz: ${visibleBookmakerStatus(topazSyncState?.status,topazSyncState?.lastCheckedAtMillis,topazOdds.size)} · свежих линий ${topazOdds.size}, точных матчей ${topazOdds.count { it.matchId!=null }}.",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick=onToggleBookmakerQuotes) {
                    Text(if(showBookmakerQuotes) "Скрыть публичные линии" else "Показать публичные линии (${misliOdds.size+topazOdds.size})")
                }
                if(showBookmakerQuotes) {
                ElevatedCard {
                    Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text("Misli.az · публичные линии 1X2",fontWeight=FontWeight.Bold)
                        Text("Снимок: ${misliSyncState?.lastCheckedAtMillis?.let { DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) } ?: "ещё не получен"}. Свежесть 30 минут. Командные названия сопоставляются только по точным проверенным псевдонимам, часу и порядку хозяева/гости.",style=MaterialTheme.typography.bodySmall)
                        if(misliOdds.isEmpty()) Text(misliSyncState?.error ?: "Ожидается открытая страница Misli; старые и прошедшие линии не показываются.",style=MaterialTheme.typography.bodySmall)
                        misliOdds.forEach { quote ->
                            val forecast=quote.matchId?.let { id -> forecastedMatches.firstOrNull { it.matchId==id } }
                            val comparison=forecast?.let { f ->
                                val p=f.probabilities
                                val market=fairProbabilities(Quote(0,quote.matchId ?: 0,"Misli",quote.home,quote.draw,quote.away,quote.capturedAtMillis))
                                "${AdvancedPredictionEngine.displayName(f.modelVersion)} ${"%.1f".format(p.home*100)}/${"%.1f".format(p.draw*100)}/${"%.1f".format(p.away*100)}% · линия без маржи ${"%.1f".format(market.first*100)}/${"%.1f".format(market.second*100)}/${"%.1f".format(market.third*100)}%"
                            } ?: "Проверенного матча с локальным прогнозом нет"
                            val captured=DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(quote.capturedAtMillis))
                            Text("${niceTime(Instant.ofEpochMilli(quote.kickoffMillis).toString())} · ${quote.homeTeam} — ${quote.awayTeam} · 1 ${quote.home} / X ${quote.draw} / 2 ${quote.away} · снимок $captured",fontWeight=FontWeight.SemiBold)
                            Text("$comparison · источник: Misli.az public football page${if(quote.matchId==null) " · матч не найден в локальной базе" else ""}",style=MaterialTheme.typography.bodySmall)
                        }
                        Text("Цены — сохранённый снимок публичной страницы, не гарантированно текущая линия. Расчёт потенциальной доходности отключён, пока модель не проверена и не откалибрована.",style=MaterialTheme.typography.labelSmall)
                    }
                }
                ElevatedCard {
                    Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text("TOPAZ / eTopaz",fontWeight=FontWeight.Bold)
                        Text(topazSyncState?.error ?: "Публичная страница проверяется автоматически при открытии приложения; свежесть 30 минут.",style=MaterialTheme.typography.bodySmall)
                        if(topazOdds.isEmpty()) Text("Пока нет подтверждённых будущих коэффициентов TOPAZ.",style=MaterialTheme.typography.bodySmall)
                        topazOdds.forEach { quote ->
                            val forecast=quote.matchId?.let { id -> forecastedMatches.firstOrNull { it.matchId==id } }
                            val comparison=forecast?.let { f ->
                                val p=f.probabilities
                                val market=fairProbabilities(Quote(0,quote.matchId ?: 0,"TOPAZ",quote.home,quote.draw,quote.away,quote.capturedAtMillis))
                                "${AdvancedPredictionEngine.displayName(f.modelVersion)} ${"%.1f".format(p.home*100)}/${"%.1f".format(p.draw*100)}/${"%.1f".format(p.away*100)}% · линия без маржи ${"%.1f".format(market.first*100)}/${"%.1f".format(market.second*100)}/${"%.1f".format(market.third*100)}%"
                            } ?: "Нет точного локального матча с доступным прогнозом"
                            val captured=DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(quote.capturedAtMillis))
                            Text("${niceTime(Instant.ofEpochMilli(quote.kickoffMillis).toString())} · ${quote.homeTeam} — ${quote.awayTeam} · 1 ${quote.home} / X ${quote.draw} / 2 ${quote.away} · снимок $captured${if(quote.eventYearInferred) " · год по ближайшей будущей дате" else ""}",fontWeight=FontWeight.SemiBold)
                            Text("$comparison${if(quote.matchId==null) " · матч не найден в локальной базе" else ""}",style=MaterialTheme.typography.bodySmall)
                        }
                        Text("TOPAZ использует только собственные линии; коэффициенты Misli в этот купон не смешиваются.",style=MaterialTheme.typography.labelSmall)
                    }
                }
                }
                Text("Виртуальные экспрессы",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                Text("Размеры 2 / 3 / 5 / 8 / 10 доступны только при достаточном числе будущих матчей с точным kickoff и историей для модели. Отбираются исходы с наибольшими расчётными маргинальными вероятностями; более слабые матчи не добавляются ради размера.",style=MaterialTheme.typography.bodySmall)
                Text("Исследовательские купоны по расхождению показывают положительную разницу между активной моделью и вероятностью без маржи из полной свежей линии того же букмекера. Это не EV, не прибыль и не рекомендация: качество на будущих матчах пока проверяется.",style=MaterialTheme.typography.labelSmall)
                if(accumulatorDrafts.isEmpty()) Text("Пока недостаточно прогнозируемых матчей для экспресса из двух исходов. Доступно матчей: ${forecastedMatches.size}.",style=MaterialTheme.typography.bodySmall)
                accumulatorDrafts.forEach { draft ->
                    ElevatedCard {
                        Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                            val basisLabel=couponSelectionBasisLabel(draft.selectionBasis)
                            Text("${draft.providerName} · ${draft.requestedLegCount} исхода · $basisLabel",fontWeight=FontWeight.Bold)
                            val currentQuotes=if(draft.providerId=="misli") misliOdds else topazOdds
                            val linkedCurrentQuotes=currentQuotes.count { it.matchId!=null }
                            Text(if(currentQuotes.isEmpty()) {
                                (if(draft.providerId=="misli") misliSyncState?.error else topazSyncState?.error)
                                    ?: oddsProviders.first { it.providerId==draft.providerId }.status().detail
                            } else "Свежие котировки ${draft.providerName}: ${currentQuotes.size}; точно сопоставлено локальным матчам: $linkedCurrentQuotes.",style=MaterialTheme.typography.bodySmall)
                            draft.picks.forEach { pick ->
                                val price=pick.bookmakerDecimalOdds?.let { odds ->
                                    val quoteTime=pick.bookmakerOddsCapturedAtMillis?.let { DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) }
                                    " · ${draft.providerName} $odds${quoteTime?.let { " (снимок $it)" }.orEmpty()}"
                                }.orEmpty()
                                val modelMarket=if(draft.selectionBasis in setOf(CouponSelectionBasis.MODEL_MARKET_DISAGREEMENT,CouponSelectionBasis.MODEL_PRICE_EDGE)) {
                                    val marketProbability=pick.marketFairProbability
                                    val difference=pick.modelMinusMarket
                                    if(marketProbability!=null&&difference!=null) " · рынок без маржи ${"%.1f".format(marketProbability*100)}% · Δ ${signedPercentagePoints(difference)} п.п." else ""
                                } else ""
                                val priceBreakEven=if(draft.selectionBasis==CouponSelectionBasis.MODEL_PRICE_EDGE) {
                                    val breakEven=pick.breakEvenProbability
                                    val gap=pick.modelMinusBreakEvenProbability
                                    if(breakEven!=null&&gap!=null) " · порог цены ${"%.1f".format(breakEven*100)}% · выше на ${signedPercentagePoints(gap)} п.п." else " · нет проверенной свежей цены"
                                } else ""
                                Text("${niceTime(Instant.ofEpochMilli(pick.kickoffMillis).toString())} · ${pick.homeTeam} — ${pick.awayTeam}: ${labelOutcome(pick.outcome)} · модель ${"%.1f".format(pick.modelProbability*100)}%$modelMarket$price$priceBreakEven")
                            }
                            draft.jointProbabilityBounds?.let { bounds ->
                                Text("Совместная вероятность: только математические границы ${(bounds.lower*100).roundToInt()}–${(bounds.upper*100).roundToInt()}%; зависимость матчей не смоделирована.",style=MaterialTheme.typography.bodySmall)
                                if(draft.combinedDecimalOdds!=null && draft.selectionBasis !in setOf(CouponSelectionBasis.MODEL_MARKET_DISAGREEMENT,CouponSelectionBasis.MODEL_PRICE_EDGE)) {
                                    Text("Оценку выгодности экспресса не рассчитываем: модельный шанс пересечения матчей неизвестен без модели зависимости. Общий коэффициент — цена букмекера.",style=MaterialTheme.typography.bodySmall)
                                }
                            }
                            if(draft.combinedDecimalOdds!=null) Text("Условный общий коэффициент ${"%.2f".format(draft.combinedDecimalOdds)} — произведение только свежих линий ${draft.providerName}.",fontWeight=FontWeight.SemiBold)
                            Text(when {
                                draft.selectionBasis==CouponSelectionBasis.MODEL_MARKET_DISAGREEMENT -> "Положительное расхождение активной модели с линией без маржи — только исследовательский фильтр. EV/ROI скрыты и прибыль не оценивается."
                                draft.selectionBasis==CouponSelectionBasis.MODEL_PRICE_EDGE -> "Отбор требует модельную вероятность выше 1/коэффициент. Это арифметическая точка безубыточности, не подтверждённое преимущество и не обещание результата; совместная вероятность экспресса остаётся только в указанных границах."
                                draft.combinedDecimalOdds!=null -> "Снимки коэффициентов фиксируются вместе с виртуальным купоном до матчей. Это не прогнозируемая прибыль: активная модель не откалибрована, а совместный шанс известен лишь в указанном диапазоне."
                                else -> "Нет полной свежей линии ${draft.providerName} для каждого исхода; общий коэффициент и денежная доходность недоступны. Виртуальный черновик сохраняет только модельные вероятности."
                            },style=MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                HorizontalDivider()
                Text("История виртуальных экспрессов",fontWeight=FontWeight.Bold)
                Text("Снимки сохраняются автоматически и затем сверяются с локальными результатами. Перенос kickoff помечается отдельно; отмена матча не трактуется как поражение.",style=MaterialTheme.typography.bodySmall)
                if(accumulatorHistory.isEmpty()) Text("История начнёт заполняться автоматически при наличии минимум двух доступных прогнозов.",style=MaterialTheme.typography.bodySmall)
                accumulatorHistory.take(30).forEach { entry ->
                    val provider=oddsProviders.firstOrNull { it.providerId==entry.providerId }?.displayName ?: entry.providerId
                    val basisLabel=couponSelectionBasisLabel(entry.selectionBasis)
                    val checked=DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(entry.capturedAtMillis))
                    val stateLabel=when(entry.settlement.state) {
                        AccumulatorSettlementState.PENDING -> "Ожидает матчей"
                        AccumulatorSettlementState.IN_PROGRESS -> "Завершено ${entry.settlement.settledLegs}/${entry.legCount}"
                        AccumulatorSettlementState.WON -> "Все ${entry.legCount} совпали"
                        AccumulatorSettlementState.LOST -> "Купон проиграл: не совпало ${entry.settlement.incorrectLegs}; проверено ${entry.settlement.settledLegs}/${entry.legCount}"
                        AccumulatorSettlementState.SCHEDULE_CHANGED -> "Kickoff изменён · требуется повторная проверка"
                        AccumulatorSettlementState.INVALID -> "Некорректная запись"
                    }
                    Text("$checked · $provider · ${entry.legCount} исхода $basisLabel · $stateLabel${entry.combinedDecimalOdds?.let { " · общий коэффициент ${"%.2f".format(it)}" }.orEmpty()}",style=MaterialTheme.typography.bodySmall)
                    if(entry.selectionBasis in setOf(CouponSelectionBasis.MODEL_MARKET_DISAGREEMENT,CouponSelectionBasis.MODEL_PRICE_EDGE)) entry.picks.forEach pickLoop@ { pick ->
                        val market=pick.marketFairProbability ?: return@pickLoop
                        val delta=pick.modelMinusMarket ?: return@pickLoop
                        val breakEven=pick.breakEvenProbability
                        val priceGap=pick.modelMinusBreakEvenProbability
                        Text(
                            "${pick.homeTeam} — ${pick.awayTeam}: модель ${"%.1f".format(pick.modelProbability*100)}%, линия без маржи ${"%.1f".format(market*100)}%, Δ ${signedPercentagePoints(delta)} п.п." +
                                if(entry.selectionBasis==CouponSelectionBasis.MODEL_PRICE_EDGE&&breakEven!=null&&priceGap!=null) "; порог ${"%.1f".format(breakEven*100)}%, выше на ${signedPercentagePoints(priceGap)} п.п." else "",
                            style=MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                accumulatorEvaluationHistory.groupBy { Triple(it.providerId,it.legCount,it.selectionBasis) }.forEach { (key, entries) ->
                    val singles=checkedCouponHistory.asSequence().filter { it.providerId==key.first }
                        .mapNotNull { pick ->
                            val home=pick.homeGoals ?: return@mapNotNull null
                            val away=pick.awayGoals ?: return@mapNotNull null
                            if(!pick.finished) return@mapNotNull null
                            val actual=when { home>away -> "H";home<away -> "A";else -> "D" }
                            SettledSinglePrediction(pick.matchId,actual==pick.outcome)
                        }.toList()
                    val report=AccumulatorPerformanceEngine.evaluate(entries,key.first,key.second,key.third,singles)
                    val provider=oddsProviders.firstOrNull { it.providerId==key.first }?.displayName ?: key.first
                    val basisLabel=couponSelectionBasisLabel(key.third)
                    val hit=report.couponHitRate?.let { "${report.wonCoupons}/${report.settledCoupons} (${"%.1f".format(it*100)}%)" } ?: "ожидает исходов"
                    val singleHit=report.singlePickHitRate?.let { " · одиночные прогнозы тех же матчей ${report.matchingSinglePicks}, ${"%.1f".format(it*100)}%" }.orEmpty()
                    val roi=when {
                        key.third==CouponSelectionBasis.MODEL_MARKET_DISAGREEMENT -> " · ROI скрыт: исследовательское сравнение модели с рынком"
                        report.virtualRoi!=null -> " · виртуальный ROI по полной линии ${"%.1f".format(report.virtualRoi*100)}% (n=${report.pricedSettledCoupons})"
                        report.settledCoupons>0&&report.pricedSettledCoupons>0 -> " · ROI скрыт: цены есть только у части завершённых купонов"
                        else -> " · ROI недоступен: нет полной цены у завершённых купонов"
                    }
                    val priceCoverage=report.settledPriceCoverage?.let { "${report.pricedSettledCoupons}/${report.settledCoupons} (${"%.0f".format(it*100)}%; без полной цены ${report.settledCouponsWithoutPrice})" } ?: "нет завершённых купонов"
                    Text("Проверка $provider · ${key.second} исхода $basisLabel: купоны ${report.settledCoupons}, попадания $hit$singleHit · покрытие ценами $priceCoverage$roi",style=MaterialTheme.typography.bodySmall)
                    if(report.settledCoupons>0&&report.couponHitRate95Low!=null&&report.couponHitRate95High!=null) {
                        Text("95% Wilson-интервал доли выигравших экспрессов: ${(report.couponHitRate95Low*100).roundToInt()}–${(report.couponHitRate95High*100).roundToInt()}%; CLV не вычисляется без зафиксированной цены около kickoff.",style=MaterialTheme.typography.labelSmall)
                    }
                }
                HorizontalDivider()
                Text("Проверка сохранённых прогнозов",fontWeight=FontWeight.Bold)
                if(checkedCouponHistory.isEmpty()) Text("Результаты появятся после автоматического сохранения предматчевых черновиков.",style=MaterialTheme.typography.bodySmall)
                checkedCouponHistory.forEach { pick ->
                    val resolved=pick.finished&&pick.homeGoals!=null&&pick.awayGoals!=null
                    val actual=if(!resolved) "Ожидает результата" else "Результат ${pick.homeGoals}:${pick.awayGoals} · ${if(scoreResult(Match(pick.matchId,"","","","",true,pick.homeGoals,pick.awayGoals))==pick.outcome) "совпало" else "не совпало"}"
                    Text("${pick.providerId} · ${pick.homeTeam} — ${pick.awayTeam} · ${labelOutcome(pick.outcome)} ${(pick.modelProbability*100).toInt()}% · $actual",style=MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                Text("Замороженные прогнозы модели",fontWeight=FontWeight.Bold)
                Text(
                    "Сохраняются снимки Poisson и резервного Elo. Для каждой версии остаётся один прогноз на матч и UTC-день до kickoff; итог фиксируется отдельно после доступности результата. Это будущая проверка без оценки прибыли.",
                    style=MaterialTheme.typography.bodySmall,
                )
                if(analyticsUpdating) {
                    Text("Обновляю метрики прогнозов; предыдущая сводка временно скрыта.",style=MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        "В локальном журнале ${prospectiveForecastReport.snapshotCount} снимков. Оценка учитывает последний снимок до kickoff для каждого матча отдельно по версии модели; повторные ежедневные снимки одного матча не увеличивают выборку.",
                        style=MaterialTheme.typography.bodySmall,
                    )
                    if(prospectiveForecastReport.modelReports.isEmpty()) {
                        Text("Предматчевые результаты появятся после автоматической фиксации прогнозов и доступности итоговых счётов.",style=MaterialTheme.typography.bodySmall)
                    }
                    prospectiveForecastReport.modelReports.forEach { report ->
                        val metrics=report.meanBrierScore?.let {
                            " · Brier ${"%.4f".format(it)} · Log Loss ${"%.4f".format(report.meanLogLoss)} · попадание наиболее вероятного ${"%.1f".format((report.topOutcomeAccuracy ?: 0.0)*100)}% (n=${report.settledCount})"
                        } ?: " · проверяемых результатов пока нет"
                        Text(
                            "${report.modelVersion}: прогнозы по матчам ${report.fixtureCount}, проверено ${report.settledCount}, ожидают ${report.pendingCount}$metrics",
                            style=MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if(prospectiveForecastHistory.isEmpty()) {
                    Text("Первые прогнозы появятся после автоматического расчёта матчей с достаточной предматчевой историей.",style=MaterialTheme.typography.bodySmall)
                } else {
                    prospectiveForecastHistory.take(20).forEach { snapshot ->
                        val captured=niceTime(Instant.ofEpochMilli(snapshot.forecastedAtMillis).toString())
                        val probabilities=snapshot.probabilities
                        val actual=if(snapshot.actualOutcome==null) {
                            "ожидает доступного результата"
                        } else {
                            val scores="${snapshot.homeGoals}:${snapshot.awayGoals}"
                            val mostLikely=listOf("H","D","A")[probabilities.values().indices.maxBy { probabilities.values()[it] }]
                            val available=snapshot.resultAvailableAtMillis?.let {
                                " · доступен ${niceTime(Instant.ofEpochMilli(it).toString())}"
                            }.orEmpty()
                            "${labelOutcome(snapshot.actualOutcome)} $scores · ${if(mostLikely==snapshot.actualOutcome) "совпало" else "не совпало"} · ${snapshot.resultSourceName ?: "источник не указан"}$available"
                        }
                        Text(
                            "$captured · ${snapshot.homeTeam} — ${snapshot.awayTeam} · ${"%.1f".format(probabilities.home*100)}/${"%.1f".format(probabilities.draw*100)}/${"%.1f".format(probabilities.away*100)}% · история ${snapshot.historyMatchCount} · $actual",
                            style=MaterialTheme.typography.bodySmall,
                        )
                    }
                }
    }
}

private fun signedPercentagePoints(value:Double):String = "%+.1f".format(value*100.0)

private fun couponSelectionBasisLabel(basis:CouponSelectionBasis):String = when(basis) {
    CouponSelectionBasis.MODEL_RANKED -> "по вероятности модели"
    CouponSelectionBasis.MATCHED_FRESH_BOOKMAKER_ODDS -> "по вероятности модели среди матчей со свежими линиями"
    CouponSelectionBasis.MODEL_MARKET_DISAGREEMENT -> "по расхождению модели с линией"
    CouponSelectionBasis.MODEL_PRICE_EDGE -> "выше точки безубыточности цены"
}
