package az.simplesoft.footballintelligence.analytics

/** Outcome channels used by a one-vs-rest forecast reliability check. */
enum class ForecastOutcome(val label: String) {
    HOME("П1"),
    DRAW("X"),
    AWAY("П2"),
}

data class CalibrationObservation(
    val probabilities: OutcomeProbabilities,
    val observedOutcome: ForecastOutcome,
)

data class ReliabilityBin(
    val index: Int,
    val lowerInclusive: Double,
    val upperBound: Double,
    val includesUpperBound: Boolean,
    val sampleCount: Int,
    val meanPredictedProbability: Double,
    val observedFrequency: Double,
)

data class OutcomeCalibration(
    val outcome: ForecastOutcome,
    val sampleCount: Int,
    val expectedCalibrationError: Double?,
    /** Only occupied bins are returned; their counts sum to sampleCount. */
    val occupiedBins: List<ReliabilityBin>,
)

data class CalibrationReport(
    val predictionCount: Int,
    val binCount: Int,
    /** Macro average of the three one-vs-rest ECE values. */
    val meanOneVsRestEce: Double?,
    val outcomes: List<OutcomeCalibration>,
)

/**
 * Equal-width, one-vs-rest reliability diagnostics for categorical 1X2 forecasts.
 * This describes retrospective calibration; it neither fits a calibrator nor validates future performance.
 */
object CalibrationDiagnostics {
    fun evaluate(
        observations: Collection<CalibrationObservation>,
        binCount: Int = 10,
    ): CalibrationReport {
        require(binCount in 2..100) { "Reliability diagnostics require 2 to 100 bins." }

        val outcomeReports = ForecastOutcome.entries.map { outcome ->
            val samples = Array(binCount) { mutableListOf<Pair<Double, Double>>() }
            for (observation in observations) {
                val probability = when (outcome) {
                    ForecastOutcome.HOME -> observation.probabilities.home
                    ForecastOutcome.DRAW -> observation.probabilities.draw
                    ForecastOutcome.AWAY -> observation.probabilities.away
                }
                val observed = if (observation.observedOutcome == outcome) 1.0 else 0.0
                val index = (probability * binCount).toInt().coerceAtMost(binCount - 1)
                samples[index].add(probability to observed)
            }

            val occupiedBins = samples.mapIndexedNotNull { index, values ->
                if (values.isEmpty()) return@mapIndexedNotNull null
                ReliabilityBin(
                    index = index,
                    lowerInclusive = index.toDouble() / binCount,
                    upperBound = (index + 1.0) / binCount,
                    includesUpperBound = index == binCount - 1,
                    sampleCount = values.size,
                    meanPredictedProbability = values.sumOf { it.first } / values.size,
                    observedFrequency = values.sumOf { it.second } / values.size,
                )
            }
            val count = observations.size
            val ece = if (count == 0) null else occupiedBins.sumOf { bin ->
                bin.sampleCount.toDouble() / count *
                    kotlin.math.abs(bin.meanPredictedProbability - bin.observedFrequency)
            }
            OutcomeCalibration(outcome, count, ece, occupiedBins)
        }
        val meanEce = outcomeReports.mapNotNull { it.expectedCalibrationError }
            .average().takeIf { outcomeReports.any { it.expectedCalibrationError != null } }
        return CalibrationReport(observations.size, binCount, meanEce, outcomeReports)
    }

    fun evaluateWalkForward(
        predictions: Collection<WalkForwardPrediction>,
        binCount: Int = 10,
    ): CalibrationReport = evaluate(
        predictions.map { prediction ->
            CalibrationObservation(
                probabilities = prediction.probabilities,
                observedOutcome = when {
                    prediction.observedHomeGoals > prediction.observedAwayGoals -> ForecastOutcome.HOME
                    prediction.observedHomeGoals < prediction.observedAwayGoals -> ForecastOutcome.AWAY
                    else -> ForecastOutcome.DRAW
                },
            )
        },
        binCount,
    )
}
