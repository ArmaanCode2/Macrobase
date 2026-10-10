package com.macrobase.app.domain.model

import java.util.Locale
import kotlin.math.abs

/**
 * Domain helper for resolving fitness strategies and validating consistency
 * between target weight and selected lifestyle strategy.
 */
object GoalStrategyHelper {
    private const val WEIGHT_EPSILON = 0.1 // 100g delta threshold

    /**
     * Determines fitness strategy automatically based on current and target weights in kg.
     * - Target > Current: Bulking
     * - Target < Current: Cutting
     * - Target == Current (or missing/blank): Maintaining
     */
    fun autoDetectStrategy(currentWeightKg: Double?, targetWeightKg: Double?): FitnessGoal {
        if (currentWeightKg == null || targetWeightKg == null) {
            return FitnessGoal.MAINTAINING
        }
        val diff = targetWeightKg - currentWeightKg
        return when {
            diff > WEIGHT_EPSILON -> FitnessGoal.BULKING
            diff < -WEIGHT_EPSILON -> FitnessGoal.CUTTING
            else -> FitnessGoal.MAINTAINING
        }
    }

    /**
     * Evaluates whether the user's selected strategy contradicts their weight targets.
     * Returns a clear, situational warning message if there is a mismatch, or null if consistent.
     *
     * @param selectedStrategy The currently active or selected FitnessGoal.
     * @param currentWeightKg Current body weight in kg.
     * @param targetWeightKg Target body weight in kg.
     * @param unitSystem The active unit system for localized text formatting.
     */
    fun getContradictionWarning(
        selectedStrategy: FitnessGoal,
        currentWeightKg: Double?,
        targetWeightKg: Double?,
        unitSystem: UnitSystem = UnitSystem.METRIC
    ): String? {
        if (currentWeightKg == null || targetWeightKg == null) return null
        val diff = targetWeightKg - currentWeightKg
        val isImperial = unitSystem == UnitSystem.IMPERIAL
        val unitLabel = if (isImperial) "lb" else "kg"

        val displayCurrent = if (isImperial) UnitConversions.kgToLbs(currentWeightKg) else currentWeightKg
        val displayTarget = if (isImperial) UnitConversions.kgToLbs(targetWeightKg) else targetWeightKg
        val currentStr = String.format(Locale.US, "%.1f %s", displayCurrent, unitLabel)
        val targetStr = String.format(Locale.US, "%.1f %s", displayTarget, unitLabel)
        return when {
            selectedStrategy == FitnessGoal.BULKING && diff < -WEIGHT_EPSILON -> {
                "Warning: Your strategy is set to Bulking, but your target weight ($targetStr) is lower than your current weight ($currentStr)."
            }
            selectedStrategy == FitnessGoal.CUTTING && diff > WEIGHT_EPSILON -> {
                "Warning: Your strategy is set to Cutting, but your target weight ($targetStr) is higher than your current weight ($currentStr)."
            }
            selectedStrategy == FitnessGoal.MAINTAINING && abs(diff) > 0.5 -> {
                "Notice: Your strategy is set to Maintaining, but your target weight ($targetStr) differs from your current weight ($currentStr)."
            }
            else -> null
        }
    }
}
