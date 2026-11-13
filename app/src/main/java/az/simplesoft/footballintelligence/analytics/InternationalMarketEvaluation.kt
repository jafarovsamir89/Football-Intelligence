package az.simplesoft.footballintelligence.analytics

import az.simplesoft.footballintelligence.data.INTERNATIONAL_ODDS_FRESHNESS_MILLIS
import az.simplesoft.footballintelligence.data.InternationalOddsSnapshot
import az.simplesoft.footballintelligence.data.InternationalOddsQuoteSide
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

data class MetricConfidenceInterval95(
    val lower: Double,
    val upper: Double,
)

/** Paired, prospective score report for one underlying bookmaker. */
data class InternationalBookmakerEvaluationReport(
    val bookmakerId: String,
    val sourceProviderIds: List<String>,
    val quoteFixtureCount: Int,
    val medianHoursBeforeKickoff: Double,
    val pairedFixtureCount: Int,
    val settledFixtureCount: Int,
    val pendingFixtureCount: Int,
    val marketBrierScore: Double?,
    val marketLogLoss: Double?,
    val marketTopOutcomeAccuracy: Double?,
    val modelBrierScore: Double?,
    val modelLogLoss: Double?,
    val modelTopOutcomeAccuracy: Double?,
    /** Paired model-minus-market differences; negative loss differences favor the model. */
    val eloMinusMarketBrier95: MetricConfidenceInterval95?,
    val eloMinusMarketLogLoss95: MetricConfidenceInterval95?,
    /** Positive accuracy differences favor the model. */
    val eloMinusMarketTopAccuracy95: MetricConfidenceInterval95?,
)

data class InternationalMarketEvaluationReport(
    /** One selected last-observed pre-kickoff quote per linked fixture and underlying bookmaker. */
    val quoteFixtureCount: Int,
    /** Quotes for which an eligible saved production-model forecast existed by capture time. */
    val pairedFixtureCount: Int,
    val settledFixtureCount: Int,
    val pendingFixtureCount: Int,
    val bookmakerReports: List<InternationalBookmakerEvaluationReport>,
)

/**
 * Compares market and production-model probabilities on the same saved fixture/bookmaker cohort.
 * Only linked, complete 1X2 quotes captured before kickoff and within the provider freshness
 * window are used. One quote per underlying bookmaker/fixture is selected, so repeated polls and
 * intermediary duplicates cannot increase the sample. The paired model snapshot must have existed
 * by quote capture; result data must have become available after kickoff.
 */
object InternationalMarketEvaluationEngine {
    private const val FUTURE_PROVIDER_TIME_TOLERANCE_MILLIS = 2L * 60_000L
    const val MIN_SETTLED_FIXTURES_FOR_INTERVALS = 30
    private const val BOOTSTRAP_RESAMPLES = 2_000

    private data class PairedMetricDifference(
        val brier: Double,
        val logLoss: Double,
        val topAccuracy: Double,
    )

    private data class FixtureKey(val matchId: Int, val kickoffMillis: Long)
    private data class QuoteKey(val fixture: FixtureKey, val bookmakerId: String)
    private data class ScoredPair(
        val market: OutcomeProbabilities,
        val model: OutcomeProbabilities,
        val outcome: OutcomeProbabilities?,
    )

    fun evaluate(
        marketHistory: Collection<InternationalOddsSnapshot>,
        forecastHistory: Collection<ProspectiveForecastObservation>,
        modelVersion: String = CURRENT_PRODUCTION_MODEL_VERSION,
    ): InternationalMarketEvaluationReport {
        val validForecasts = forecastHistory.filter { forecast ->
            forecast.modelVersion == modelVersion &&
                TeamDataQuality.isValidFixture(forecast.homeTeam, forecast.awayTeam) &&
                forecast.forecastedAtMillis > 0L &&
                forecast.forecastedAtMillis < forecast.kickoffMillis &&
                forecast.historyMatchCount >= EloEngine.MIN_HISTORY_MATCHES_FOR_FORECAST &&
                validProbabilities(forecast.probabilities)
        }
        val forecastsByFixture = validForecasts.groupBy { FixtureKey(it.matchId, it.kickoffMillis) }
        val selectedQuotes = marketHistory.asSequence()
            .filter(::isEligibleQuote)
            .groupBy { quote ->
                QuoteKey(
                    FixtureKey(requireNotNull(quote.matchId), quote.kickoffMillis),
                    quote.bookmakerId.trim().lowercase(Locale.ROOT),
                )
            }
            .mapNotNull { (key, quotes) ->
                quotes.maxWithOrNull(
                    compareBy<InternationalOddsSnapshot> { it.capturedAtMillis }
                        .thenBy { it.providerPolledAtMillis }
                        .thenBy { it.providerId },
                )?.let { key to it }
            }

        val quoteCountByBook = selectedQuotes.groupingBy { it.first.bookmakerId }.eachCount()
        val sourceIdsByBook = selectedQuotes.groupBy({ it.first.bookmakerId }, { it.second.providerId })
            .mapValues { (_, providerIds) -> providerIds.distinct().sorted() }
        val medianLeadHoursByBook = selectedQuotes.groupBy({ it.first.bookmakerId }, { it.second })
            .mapValues { (_, quotes) -> median(quotes.map { it.kickoffMillis - it.capturedAtMillis }) / 3_600_000.0 }
        val pairsByBook = selectedQuotes.mapNotNull { (key, quote) ->
            val forecast = forecastsByFixture[key.fixture].orEmpty()
                .asSequence()
                .filter { it.forecastedAtMillis <= quote.capturedAtMillis }
                .maxWithOrNull(compareBy<ProspectiveForecastObservation> { it.forecastedAtMillis }.thenBy { it.snapshotId })
                ?: return@mapNotNull null
            val marketProbabilities = runCatching {
                ProbabilityEngine.removeMargin(quote.home, quote.draw, quote.away, MarginMethod.BASIC)
            }.getOrNull() ?: return@mapNotNull null
            val outcome = observedOutcome(
                forecastsByFixture[key.fixture].orEmpty(),
                quote.capturedAtMillis,
                quote.kickoffMillis,
            )
            key.bookmakerId to ScoredPair(marketProbabilities, forecast.probabilities, outcome)
        }

        // OutcomeProbabilities intentionally requires a distribution that sums to one. Keep pending
        // rows separately so they contribute coverage counts but never enter score calculations.
        val finalized = pairsByBook.groupBy({ it.first }, { it.second })
        val reports = quoteCountByBook.keys.sorted().mapNotNull { bookmaker ->
            val rows = finalized[bookmaker].orEmpty()
            val settled = rows.filter { it.outcome != null }
            val intervals = pairedBootstrapIntervals(bookmaker, settled)
            InternationalBookmakerEvaluationReport(
                bookmakerId = bookmaker,
                sourceProviderIds = sourceIdsByBook[bookmaker].orEmpty(),
                quoteFixtureCount = quoteCountByBook.getValue(bookmaker),
                medianHoursBeforeKickoff = medianLeadHoursByBook.getValue(bookmaker),
                pairedFixtureCount = rows.size,
                settledFixtureCount = settled.size,
                pendingFixtureCount = (rows.size - settled.size).coerceAtLeast(0),
                marketBrierScore = settled.map { ForecastMetrics.brierScore(it.market, requireNotNull(it.outcome)) }.averageOrNull(),
                marketLogLoss = settled.map { ForecastMetrics.logLoss(it.market, requireNotNull(it.outcome)) }.averageOrNull(),
                marketTopOutcomeAccuracy = settled.map { topOutcomeCorrect(it.market, requireNotNull(it.outcome)) }.averageOrNull(),
                modelBrierScore = settled.map { ForecastMetrics.brierScore(it.model, requireNotNull(it.outcome)) }.averageOrNull(),
                modelLogLoss = settled.map { ForecastMetrics.logLoss(it.model, requireNotNull(it.outcome)) }.averageOrNull(),
                modelTopOutcomeAccuracy = settled.map { topOutcomeCorrect(it.model, requireNotNull(it.outcome)) }.averageOrNull(),
                eloMinusMarketBrier95 = intervals?.first,
                eloMinusMarketLogLoss95 = intervals?.second,
                eloMinusMarketTopAccuracy95 = intervals?.third,
            )
        }.sortedWith(compareByDescending<InternationalBookmakerEvaluationReport> { it.settledFixtureCount }
            .thenByDescending { it.pairedFixtureCount }
            .thenBy { it.bookmakerId })

        return InternationalMarketEvaluationReport(
            quoteFixtureCount = selectedQuotes.size,
            pairedFixtureCount = reports.sumOf { it.pairedFixtureCount },
            settledFixtureCount = reports.sumOf { it.settledFixtureCount },
            pendingFixtureCount = reports.sumOf { it.pendingFixtureCount },
            bookmakerReports = reports,
        )
    }

    private fun isEligibleQuote(quote: InternationalOddsSnapshot): Boolean {
        val matchId = quote.matchId ?: return false
        if (matchId <= 0 || quote.matchLinkBasis == null || quote.market != "1X2" ||
            quote.quoteSide != InternationalOddsQuoteSide.BACK
        ) return false
        if (quote.capturedAtMillis <= 0L || quote.capturedAtMillis >= quote.kickoffMillis) return false
        if (quote.providerPolledAtMillis <= 0L ||
            quote.providerPolledAtMillis > quote.capturedAtMillis + FUTURE_PROVIDER_TIME_TOLERANCE_MILLIS ||
            quote.capturedAtMillis - quote.providerPolledAtMillis > INTERNATIONAL_ODDS_FRESHNESS_MILLIS
        ) return false
        if (quote.bookmakerId.isBlank() || !TeamDataQuality.isValidFixture(quote.homeTeam, quote.awayTeam)) return false
        return listOf(quote.home, quote.draw, quote.away).all { it.isFinite() && it > 1.0 && it <= 1_000.0 }
    }

    private fun observedOutcome(
        observations: List<ProspectiveForecastObservation>,
        quoteCapturedAtMillis: Long,
        kickoffMillis: Long,
    ): OutcomeProbabilities? {
        val valid = observations.mapNotNull { observation ->
            val availableAt = observation.resultAvailableAtMillis ?: return@mapNotNull null
            val settledAt = observation.settledAtMillis ?: return@mapNotNull null
            val source = observation.resultSourceName
            val homeGoals = observation.homeGoals ?: return@mapNotNull null
            val awayGoals = observation.awayGoals ?: return@mapNotNull null
            if (homeGoals < 0 || awayGoals < 0 || source.isNullOrBlank() ||
                availableAt < kickoffMillis || availableAt <= quoteCapturedAtMillis || settledAt < availableAt
            ) return@mapNotNull null
            val outcome = when {
                homeGoals > awayGoals -> "H"
                homeGoals < awayGoals -> "A"
                else -> "D"
            }
            if (observation.actualOutcome != outcome) return@mapNotNull null
            Triple(settledAt, homeGoals, awayGoals)
        }
        if (valid.isEmpty()) return null
        val newestSettledAt = valid.maxOf { it.first }
        val latestScores = valid.filter { it.first == newestSettledAt }.map { it.second to it.third }.distinct()
        if (latestScores.size != 1) return null
        val (homeGoals, awayGoals) = latestScores.single()
        return when {
            homeGoals > awayGoals -> OutcomeProbabilities(1.0, 0.0, 0.0)
            homeGoals < awayGoals -> OutcomeProbabilities(0.0, 0.0, 1.0)
            else -> OutcomeProbabilities(0.0, 1.0, 0.0)
        }
    }

    private fun validProbabilities(probabilities: OutcomeProbabilities): Boolean =
        probabilities.values().all { it.isFinite() && it in 0.0..1.0 } && abs(probabilities.values().sum() - 1.0) < 1e-8

    private fun topOutcomeCorrect(prediction: OutcomeProbabilities, outcome: OutcomeProbabilities): Double =
        if (prediction.values().indexOf(prediction.values().maxOrNull()) == outcome.values().indexOf(1.0)) 1.0 else 0.0

    /**
     * Percentile bootstrap over the same fixture pairs for market and Elo. Fixtures are sampled
     * independently with replacement; repeated teams or competition rounds can violate that
     * assumption, so intervals are descriptive and may be too narrow for clustered schedules.
     */
    private fun pairedBootstrapIntervals(
        bookmakerId: String,
        settled: List<ScoredPair>,
    ): Triple<MetricConfidenceInterval95, MetricConfidenceInterval95, MetricConfidenceInterval95>? {
        if (settled.size < MIN_SETTLED_FIXTURES_FOR_INTERVALS) return null
        val differences = settled.map { row ->
            val outcome = requireNotNull(row.outcome)
            PairedMetricDifference(
                brier = ForecastMetrics.brierScore(row.model, outcome) - ForecastMetrics.brierScore(row.market, outcome),
                logLoss = ForecastMetrics.logLoss(row.model, outcome) - ForecastMetrics.logLoss(row.market, outcome),
                topAccuracy = topOutcomeCorrect(row.model, outcome) - topOutcomeCorrect(row.market, outcome),
            )
        }
        val random = Random(stableSeed(bookmakerId, settled.size))
        val brierSamples = DoubleArray(BOOTSTRAP_RESAMPLES)
        val logLossSamples = DoubleArray(BOOTSTRAP_RESAMPLES)
        val accuracySamples = DoubleArray(BOOTSTRAP_RESAMPLES)
        repeat(BOOTSTRAP_RESAMPLES) { sampleIndex ->
            var brier = 0.0
            var logLoss = 0.0
            var accuracy = 0.0
            repeat(differences.size) {
                val selected = differences[random.nextInt(differences.size)]
                brier += selected.brier
                logLoss += selected.logLoss
                accuracy += selected.topAccuracy
            }
            val divisor = differences.size.toDouble()
            brierSamples[sampleIndex] = brier / divisor
            logLossSamples[sampleIndex] = logLoss / divisor
            accuracySamples[sampleIndex] = accuracy / divisor
        }
        return Triple(
            percentileInterval95(brierSamples),
            percentileInterval95(logLossSamples),
            percentileInterval95(accuracySamples),
        )
    }

    private fun stableSeed(bookmakerId: String, sampleSize: Int): Long {
        var hash = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis
        (bookmakerId + ":" + sampleSize).forEach { character ->
            hash = (hash xor character.code.toLong()) * 0x100000001b3L
        }
        return hash
    }

    private fun percentileInterval95(samples: DoubleArray): MetricConfidenceInterval95 {
        samples.sort()
        fun percentile(probability: Double): Double {
            val position = probability * (samples.lastIndex)
            val lowerIndex = floor(position).toInt()
            val upperIndex = ceil(position).toInt()
            val fraction = position - lowerIndex
            return samples[lowerIndex] * (1.0 - fraction) + samples[upperIndex] * fraction
        }
        return MetricConfidenceInterval95(percentile(0.025), percentile(0.975))
    }

    private fun List<Double>.averageOrNull(): Double? = average().takeIf { isNotEmpty() }

    private fun median(values: List<Long>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1].toDouble() + sorted[middle].toDouble()) / 2.0
        else sorted[middle].toDouble()
    }
}
