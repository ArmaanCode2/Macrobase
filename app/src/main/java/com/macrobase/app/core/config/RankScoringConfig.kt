package com.macrobase.app.core.config

import com.macrobase.app.domain.model.FitnessGoal
import kotlin.math.abs
import kotlin.math.roundToInt

object RankScoringConfig {
    // 1. Updated Component Weights (Sum = 1.0, Water = 0.0)
    const val CONSISTENCY_WEIGHT = 0.35
    const val CALORIE_WEIGHT = 0.30
    const val MACRO_WEIGHT = 0.27
    const val WEIGHT_TREND_WEIGHT = 0.08  // Exactly 8% weightage
    const val WATER_WEIGHT = 0.0

    // Macro Sub-component Weights (Sum = 1.0)
    const val MACRO_PROTEIN_WEIGHT = 0.40
    const val MACRO_CARB_WEIGHT = 0.30
    const val MACRO_FAT_WEIGHT = 0.30

    // Rolling Windows and Evaluation
    const val ROLLING_WINDOW_DAYS = 30
    const val PROVISIONAL_DAYS_THRESHOLD = 7
    const val REDUCED_MOVEMENT_DAYS_THRESHOLD = 14

    // RR Delta Mapping Parameters
    const val NEUTRAL_LIFESTYLE_SCORE = 60.0
    const val RR_MULTIPLIER = 0.6
    const val MAX_RR_GAIN_PER_EVAL = 20   // Strictly capped at +20 RR
    const val MAX_RR_LOSS_PER_EVAL = 20   // Strictly capped at -20 RR

    // Macro Quality Threshold: Must score >= 60.0 to be eligible for positive RR
    const val MIN_MACRO_SCORE_FOR_POSITIVE_RR = 60.0

    // Rating Limits
    const val MIN_RATING = 0
    const val MAX_RATING = 2399
    const val RR_PER_TIER = 100
    const val TIERS_PER_RANK = 3
    const val RR_PER_RANK = 300
    const val TOTAL_RANKS = 8

    /**
     * Strategy-specific calorie scoring based on Maintenance Calories and Fitness Goal.
     * Evaluates calorie difference: delta = actualCalories - maintenanceCalories.
     */
    fun evaluateCalorieScore(
        actualCalories: Double,
        maintenanceCalories: Double,
        goal: FitnessGoal
    ): Double {
        if (maintenanceCalories <= 0.0) return 50.0
        if (actualCalories <= 0.0) return 0.0
        val delta = actualCalories - maintenanceCalories

        return when (goal) {
            FitnessGoal.BULKING -> {
                when {
                    // Deficit while bulking: severe penalty
                    delta < -200.0 -> 0.0
                    delta < 0.0 -> 35.0 + (delta + 200.0) / 200.0 * 25.0 // 35..60
                    // Small surplus: 0 to +150 kcal (+100 kcal yields score ~70.0 -> +6 RR)
                    delta in 0.0..150.0 -> 60.0 + (delta / 150.0) * 15.0 // 60..75
                    // Optimal lean bulk sweet spot: +150 to +450 kcal
                    delta in 150.0..350.0 -> 75.0 + ((delta - 150.0) / 200.0) * 25.0 // 75..100
                    delta in 350.0..450.0 -> 100.0 - ((delta - 350.0) / 100.0) * 10.0 // 100..90
                    delta in 450.0..500.0 -> 90.0 - ((delta - 450.0) / 50.0) * 10.0   // 90..80
                    // Excessive surplus: +500 to +750 kcal
                    delta in 500.0..750.0 -> 80.0 - ((delta - 500.0) / 250.0) * 45.0 // 80..35
                    // Massive overage (> +750 kcal)
                    else -> 0.0
                }
            }
            FitnessGoal.CUTTING -> {
                val deficit = -delta // positive value representing deficit amount
                when {
                    // Surplus while cutting: severe penalty
                    deficit < -200.0 -> 0.0
                    deficit < 0.0 -> 35.0 + (deficit + 200.0) / 200.0 * 25.0 // 35..60
                    // Small deficit: 0 to 150 kcal (-100 kcal deficit yields score ~70.0 -> +6 RR)
                    deficit in 0.0..150.0 -> 60.0 + (deficit / 150.0) * 15.0 // 60..75
                    // Optimal deficit sweet spot: 150 to 450 kcal deficit
                    deficit in 150.0..350.0 -> 75.0 + ((deficit - 150.0) / 200.0) * 25.0 // 75..100
                    deficit in 350.0..450.0 -> 100.0 - ((deficit - 350.0) / 100.0) * 10.0 // 100..90
                    deficit in 450.0..500.0 -> 90.0 - ((deficit - 450.0) / 50.0) * 10.0   // 90..80
                    // Excessive crash deficit: 500 to 750 kcal
                    deficit in 500.0..750.0 -> 80.0 - ((deficit - 500.0) / 250.0) * 45.0 // 80..35
                    // Extreme starvation deficit (> 750 kcal)
                    else -> 0.0
                }
            }
            FitnessGoal.MAINTAINING -> {
                val absDiff = abs(delta)
                when {
                    absDiff <= 100.0 -> 100.0
                    absDiff in 100.0..250.0 -> 100.0 - ((absDiff - 100.0) / 150.0) * 20.0 // 100..80
                    absDiff in 250.0..400.0 -> 80.0 - ((absDiff - 250.0) / 150.0) * 25.0   // 80..55
                    absDiff in 400.0..600.0 -> 55.0 - ((absDiff - 400.0) / 200.0) * 35.0   // 55..20
                    else -> 0.0
                }
            }
        }.coerceIn(0.0, 100.0)
    }

    /**
     * Backward-compatible overload for legacy calls or default maintaining goal.
     */
    fun evaluateCalorieScore(actualCalories: Double, targetCalories: Double): Double {
        return evaluateCalorieScore(actualCalories, targetCalories, FitnessGoal.MAINTAINING)
    }

    /**
     * Evaluates protein adherence against target in grams (0..100 score).
     */
    fun evaluateProteinScore(actualGrams: Double, targetGrams: Double): Double {
        if (targetGrams <= 0.0) return 50.0
        if (actualGrams <= 0.0) return 0.0

        val ratio = actualGrams / targetGrams
        return when {
            ratio in 0.90..1.10 -> 100.0
            ratio in 0.80..<0.90 || ratio in 1.10..1.25 -> 85.0
            ratio in 0.65..<0.80 || ratio in 1.25..1.40 -> 60.0
            ratio in 0.45..<0.65 || ratio in 1.40..1.75 -> 30.0
            ratio in 0.20..<0.45 || ratio in 1.75..2.25 -> 10.0
            else -> 0.0
        }
    }

    /**
     * Evaluates carbohydrate adherence against target in grams (0..100 score).
     */
    fun evaluateCarbScore(actualGrams: Double, targetGrams: Double): Double {
        if (targetGrams <= 0.0) return 50.0
        if (actualGrams <= 0.0) return 0.0

        val ratio = actualGrams / targetGrams
        return when {
            ratio in 0.85..1.15 -> 100.0
            ratio in 0.70..<0.85 || ratio in 1.15..1.30 -> 75.0
            ratio in 0.50..<0.70 || ratio in 1.30..1.60 -> 40.0
            ratio in 0.25..<0.50 || ratio in 1.60..2.00 -> 15.0
            else -> 0.0
        }
    }

    /**
     * Evaluates fat adherence against target in grams (0..100 score).
     */
    fun evaluateFatScore(actualGrams: Double, targetGrams: Double): Double {
        if (targetGrams <= 0.0) return 50.0
        if (actualGrams <= 0.0) return 0.0

        val ratio = actualGrams / targetGrams
        return when {
            ratio in 0.85..1.15 -> 100.0
            ratio in 0.70..<0.85 || ratio in 1.15..1.30 -> 75.0
            ratio in 0.50..<0.70 || ratio in 1.30..1.60 -> 40.0
            ratio in 0.25..<0.50 || ratio in 1.60..2.00 -> 15.0
            else -> 0.0
        }
    }

    /**
     * Evaluates macro balance composite score (0..100) using 40% Protein, 30% Carbs, 30% Fat.
     */
    fun evaluateMacroBalanceScore(
        actualP: Double, targetP: Double,
        actualC: Double, targetC: Double,
        actualF: Double, targetF: Double
    ): Double {
        val pScore = evaluateProteinScore(actualP, targetP)
        val cScore = evaluateCarbScore(actualC, targetC)
        val fScore = evaluateFatScore(actualF, targetF)

        return (pScore * MACRO_PROTEIN_WEIGHT + cScore * MACRO_CARB_WEIGHT + fScore * MACRO_FAT_WEIGHT).coerceIn(0.0, 100.0)
    }

    /**
     * Evaluates weight trend score (8% weightage) based on Fitness Goal.
     */
    fun evaluateWeightTrendScore(
        deltaWeightKg: Double?,
        entryCount: Int,
        goal: FitnessGoal
    ): Double {
        if (deltaWeightKg == null || entryCount < 2) {
            return 50.0 // Neutral score if insufficient weigh-ins
        }
        return when (goal) {
            FitnessGoal.BULKING -> {
                when {
                    deltaWeightKg in 0.2..1.5 -> 100.0  // Healthy progressive gain
                    deltaWeightKg in 0.0..<0.2 -> 75.0   // Maintenance / slight gain
                    deltaWeightKg in 1.5..2.5 -> 70.0    // Rapid gain
                    deltaWeightKg > 2.5 -> 40.0         // Excessive gain (mostly fat)
                    deltaWeightKg in -0.5..<0.0 -> 50.0  // Slight drop
                    else -> 20.0                        // Active weight loss during bulk
                }
            }
            FitnessGoal.CUTTING -> {
                when {
                    deltaWeightKg in -2.5..-0.5 -> 100.0 // Healthy progressive fat loss
                    deltaWeightKg in -0.5..0.0 -> 75.0   // Maintenance / slight loss
                    deltaWeightKg in -4.0..-2.5 -> 70.0  // Rapid loss
                    deltaWeightKg < -4.0 -> 40.0        // Crash loss (muscle loss risk)
                    deltaWeightKg in 0.0..0.5 -> 50.0    // Slight gain
                    else -> 20.0                        // Active weight gain during cut
                }
            }
            FitnessGoal.MAINTAINING -> {
                val absDelta = abs(deltaWeightKg)
                when {
                    absDelta <= 0.5 -> 100.0
                    absDelta <= 1.0 -> 75.0
                    absDelta <= 1.5 -> 50.0
                    else -> 25.0
                }
            }
        }.coerceIn(0.0, 100.0)
    }

    fun evaluateWeightTrendScore(
        currentWeightKg: Double?,
        targetWeightKg: Double?,
        deltaWeightKg: Double?,
        entryCount: Int
    ): Double {
        return evaluateWeightTrendScore(deltaWeightKg, entryCount, FitnessGoal.MAINTAINING)
    }

    fun calculateLifestyleScore(
        consistencyScore: Double,
        calorieScore: Double,
        macroScore: Double,
        weightTrendScore: Double
    ): Double {
        return (
            consistencyScore * CONSISTENCY_WEIGHT +
            calorieScore * CALORIE_WEIGHT +
            macroScore * MACRO_WEIGHT +
            weightTrendScore * WEIGHT_TREND_WEIGHT
        ).coerceIn(0.0, 100.0)
    }

    /**
     * Converts LifestyleScore into daily RR delta (-20..+20).
     * Enforces that positive RR is gated by acceptable macro balance (macroScore >= 60.0).
     */
    fun calculateRRDelta(
        lifestyleScore: Double,
        loggedDaysCount: Int,
        macroScore: Double = 100.0
    ): Int {
        if (loggedDaysCount < PROVISIONAL_DAYS_THRESHOLD) {
            return 0
        }
        val rawDelta = (lifestyleScore - NEUTRAL_LIFESTYLE_SCORE) * RR_MULTIPLIER
        var delta = rawDelta.roundToInt().coerceIn(-MAX_RR_LOSS_PER_EVAL, MAX_RR_GAIN_PER_EVAL)

        // Strict Macro Gate: If macro score is below 60, no positive RR can be awarded
        if (delta > 0 && macroScore < MIN_MACRO_SCORE_FOR_POSITIVE_RR) {
            delta = 0
        }

        val dampening = if (loggedDaysCount < REDUCED_MOVEMENT_DAYS_THRESHOLD) 0.5 else 1.0

        return (delta * dampening).roundToInt().coerceIn(-MAX_RR_LOSS_PER_EVAL, MAX_RR_GAIN_PER_EVAL)
    }
}
