package az.simplesoft.footballintelligence.analytics

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/** Three-way probabilities in home/draw/away order. */
data class OutcomeProbabilities(
    val home: Double,
    val draw: Double,
    val away: Double,
) {
    fun values(): List<Double> = listOf(home, draw, away)

    init {
        require(values().all { it.isFinite() && it >= 0.0 }) { "Probabilities must be finite and non-negative." }
        require(kotlin.math.abs(values().sum() - 1.0) < 1e-8) { "Probabilities must sum to 1." }
    }
}

enum class MarginMethod { BASIC, POWER, SHIN }

/** Removes the overround from decimal 1X2 odds. Does not establish that a quoted price is available. */
object ProbabilityEngine {
    fun removeMargin(homeOdds: Double, drawOdds: Double, awayOdds: Double, method: MarginMethod): OutcomeProbabilities {
        val odds = listOf(homeOdds, drawOdds, awayOdds)
        require(odds.all { it.isFinite() && it > 1.0 }) { "Decimal odds must be finite and greater than 1." }
        val implied = odds.map { 1.0 / it }
        val total = implied.sum()
        if (method == MarginMethod.SHIN) {
            require(total >= 1.0 - 1e-12) { "Shin margin removal requires a 1X2 market with an overround." }
            if (kotlin.math.abs(total - 1.0) <= 1e-12) {
                return OutcomeProbabilities(implied[0], implied[1], implied[2])
            }
        }

        val probabilities = when (method) {
            MarginMethod.BASIC -> implied.map { it / total }
            MarginMethod.POWER -> powerProbabilities(implied)
            MarginMethod.SHIN -> shinProbabilities(implied, total)
        }
        return OutcomeProbabilities(probabilities[0], probabilities[1], probabilities[2])
    }

    private fun powerProbabilities(implied: List<Double>): List<Double> {
        // Sum(q^k) decreases continuously from 3 to 0 for 0 < q < 1.
        var low = 1e-8
        var high = 1.0
        while (implied.sumOf { it.pow(high) } > 1.0 && high < 1e18) high *= 2.0
        require(implied.sumOf { it.pow(high) } <= 1.0) { "Could not bracket the Power method solution for these odds." }
        repeat(160) {
            val mid = (low + high) / 2.0
            if (implied.sumOf { it.pow(mid) } > 1.0) low = mid else high = mid
        }
        val powered = implied.map { it.pow((low + high) / 2.0) }
        val total = powered.sum()
        return powered.map { it / total }
    }

    private fun shinProbabilities(implied: List<Double>, total: Double): List<Double> {
        fun sumAt(insiderShare: Double): Double = implied.sumOf { q ->
            (kotlin.math.sqrt(insiderShare * insiderShare + 4.0 * (1.0 - insiderShare) * q * q / total) - insiderShare) /
                (2.0 * (1.0 - insiderShare))
        }

        var low = 0.0
        var high = 1.0 - 1e-12
        repeat(160) {
            val mid = (low + high) / 2.0
            if (sumAt(mid) > 1.0) low = mid else high = mid
        }
        val z = (low + high) / 2.0
        val values = implied.map { q ->
            (kotlin.math.sqrt(z * z + 4.0 * (1.0 - z) * q * q / total) - z) / (2.0 * (1.0 - z))
        }
        val normalizedTotal = values.sum()
        return values.map { it / normalizedTotal }
    }
}

data class GoalModelResult(
    val probabilities: OutcomeProbabilities,
    val scoreProbabilities: Map<Pair<Int, Int>, Double>,
)

/** Independent Poisson score model with an optional Dixon–Coles low-score correction. */
object GoalModel {
    fun predict1x2(
        homeExpectedGoals: Double,
        awayExpectedGoals: Double,
        maxGoals: Int = 10,
        dixonColesRho: Double? = null,
    ): GoalModelResult {
        require(homeExpectedGoals.isFinite() && homeExpectedGoals > 0.0) { "Home expected goals must be positive and finite." }
        require(awayExpectedGoals.isFinite() && awayExpectedGoals > 0.0) { "Away expected goals must be positive and finite." }
        require(maxGoals in 4..20) { "maxGoals must be between 4 and 20." }
        require(dixonColesRho == null || dixonColesRho.isFinite()) { "Dixon–Coles rho must be finite." }

        val homeMass = poissonMasses(homeExpectedGoals, maxGoals)
        val awayMass = poissonMasses(awayExpectedGoals, maxGoals)
        val grid = linkedMapOf<Pair<Int, Int>, Double>()
        for (home in 0..maxGoals) for (away in 0..maxGoals) {
            var probability = homeMass[home] * awayMass[away]
            if (dixonColesRho != null) {
                val correction = when (home to away) {
                    0 to 0 -> 1.0 - homeExpectedGoals * awayExpectedGoals * dixonColesRho
                    0 to 1 -> 1.0 + homeExpectedGoals * dixonColesRho
                    1 to 0 -> 1.0 + awayExpectedGoals * dixonColesRho
                    1 to 1 -> 1.0 - dixonColesRho
                    else -> 1.0
                }
                require(correction > 0.0) { "Dixon–Coles rho makes a low-score probability non-positive." }
                probability *= correction
            }
            grid[home to away] = probability
        }

        // Normalize the truncated grid, so the returned scoreline probabilities sum to one.
        val gridTotal = grid.values.sum()
        val normalized = grid.mapValues { it.value / gridTotal }
        val home = normalized.filterKeys { it.first > it.second }.values.sum()
        val draw = normalized.filterKeys { it.first == it.second }.values.sum()
        val away = normalized.filterKeys { it.first < it.second }.values.sum()
        return GoalModelResult(OutcomeProbabilities(home, draw, away), normalized)
    }

    private fun poissonMasses(lambda: Double, maxGoals: Int): List<Double> {
        val result = MutableList(maxGoals + 1) { 0.0 }
        result[0] = exp(-lambda)
        for (goals in 1..maxGoals) result[goals] = result[goals - 1] * lambda / goals
        return result
    }
}

object ForecastMetrics {
    /** Multiclass Brier score: sum((p_i - y_i)^2), where exactly one outcome is observed. */
    fun brierScore(prediction: OutcomeProbabilities, observed: OutcomeProbabilities): Double =
        prediction.values().zip(observed.values()).sumOf { (p, y) -> (p - y) * (p - y) }

    /** Multiclass log loss with a small floor to keep a zero-probability miss finite. */
    fun logLoss(prediction: OutcomeProbabilities, observed: OutcomeProbabilities): Double {
        val epsilon = 1e-15
        return -prediction.values().zip(observed.values()).sumOf { (p, y) ->
            if (y == 0.0) 0.0 else y * ln(p.coerceAtLeast(epsilon))
        }
    }

    /** Expected net return per unit stake at decimal odds; not an assurance of realized profit. */
    fun expectedValuePerUnit(probability: Double, decimalOdds: Double): Double {
        require(probability.isFinite() && probability in 0.0..1.0)
        require(decimalOdds.isFinite() && decimalOdds > 1.0)
        return probability * decimalOdds - 1.0
    }
}
