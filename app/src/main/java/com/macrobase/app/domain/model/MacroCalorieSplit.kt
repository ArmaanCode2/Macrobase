package com.macrobase.app.domain.model

import com.macrobase.app.core.config.NutritionStandardConfig

/**
 * Calculates and encapsulates the "Source of Calories" macronutrient energy distribution.
 * Implements strict zero/empty handling to prevent NaN or division-by-zero errors.
 */
data class MacroCalorieSplit(
    val proteinCalories: Double,
    val carbCalories: Double,
    val fatCalories: Double,
    val totalMacroCalories: Double,
    val proteinPercentage: Double,
    val carbPercentage: Double,
    val fatPercentage: Double
) {
    companion object {
        val ZERO = MacroCalorieSplit(
            proteinCalories = 0.0,
            carbCalories = 0.0,
            fatCalories = 0.0,
            totalMacroCalories = 0.0,
            proteinPercentage = 0.0,
            carbPercentage = 0.0,
            fatPercentage = 0.0
        )

        fun fromNutrition(nutrition: Nutrition): MacroCalorieSplit {
            val pCal = (nutrition.proteinGrams * NutritionStandardConfig.CALORIES_PER_GRAM_PROTEIN).coerceAtLeast(0.0)
            val cCal = (nutrition.carbsGrams * NutritionStandardConfig.CALORIES_PER_GRAM_CARB).coerceAtLeast(0.0)
            val fCal = (nutrition.fatGrams * NutritionStandardConfig.CALORIES_PER_GRAM_FAT).coerceAtLeast(0.0)
            val total = pCal + cCal + fCal

            if (total <= 0.0) {
                return ZERO
            }

            val pPct = (pCal / total) * 100.0
            val cPct = (cCal / total) * 100.0
            val fPct = (fCal / total) * 100.0

            return MacroCalorieSplit(
                proteinCalories = pCal,
                carbCalories = cCal,
                fatCalories = fCal,
                totalMacroCalories = total,
                proteinPercentage = pPct,
                carbPercentage = cPct,
                fatPercentage = fPct
            )
        }
    }
}
