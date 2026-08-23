package com.macrobase.app.domain.model

import com.macrobase.app.core.config.NutritionStandardConfig
import kotlin.math.abs

/**
 * Domain model representing user calorie and macronutrient split targets.
 *
 * Implements strict calculation of macro gram goals from caloric energy conversion
 * factors (4 kcal/g for carbs & protein, 9 kcal/g for fat) and verifies percentage sum.
 */
data class Goal(
    val dailyCalorieGoal: Double = 2000.0,
    val carbPercentage: Double = 50.0,
    val proteinPercentage: Double = 25.0,
    val fatPercentage: Double = 25.0
) {
    /**
     * Target carbohydrate amount in grams.
     */
    val carbGrams: Double
        get() = if (dailyCalorieGoal > 0) (dailyCalorieGoal * (carbPercentage / 100.0)) / NutritionStandardConfig.CALORIES_PER_GRAM_CARB else 0.0

    /**
     * Target protein amount in grams.
     */
    val proteinGrams: Double
        get() = if (dailyCalorieGoal > 0) (dailyCalorieGoal * (proteinPercentage / 100.0)) / NutritionStandardConfig.CALORIES_PER_GRAM_PROTEIN else 0.0

    /**
     * Target dietary fat amount in grams.
     */
    val fatGrams: Double
        get() = if (dailyCalorieGoal > 0) (dailyCalorieGoal * (fatPercentage / 100.0)) / NutritionStandardConfig.CALORIES_PER_GRAM_FAT else 0.0

    /**
     * Total percentage sum as an integer.
     */
    val totalPercentage: Int
        get() = (carbPercentage + proteinPercentage + fatPercentage).toInt()

    val totalPercentageExact: Double
        get() = carbPercentage + proteinPercentage + fatPercentage

    /**
     * Validates that carb + protein + fat percentages total exactly 100% (within tolerance).
     */
    val isValidPercentageSum: Boolean
        get() = abs(totalPercentage - 100.0) < 0.01

    /**
     * Validates overall goal configuration.
     */
    val isValid: Boolean
        get() = dailyCalorieGoal > 0 && carbPercentage >= 0 && proteinPercentage >= 0 && fatPercentage >= 0 && isValidPercentageSum
}
