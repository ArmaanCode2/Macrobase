package com.macrobase.app.core.config

import androidx.compose.ui.graphics.Color
import com.macrobase.app.core.designsystem.AppColors

/**
 * Configurable performance color rules and adherence thresholds for the Food Logging Calendar.
 * Formula and color codes are strictly loaded from this configuration.
 */
object CalendarPerformanceConfig {
    const val OPTIMAL_MIN_RATIO: Double = 0.85     // 85% of daily calorie goal
    const val OPTIMAL_MAX_RATIO: Double = 1.05     // 105% of daily calorie goal
    const val OVER_BUDGET_RATIO: Double = 1.20     // 120% of daily calorie goal

    enum class PerformanceCategory {
        EMPTY_MISSED,
        UNDER_BUDGET,
        OPTIMAL_TARGET,
        MODERATE_OVER,
        HIGH_OVER_TARGET
    }

    /**
     * Determines performance category based on logged calories vs daily goal.
     */
    fun evaluatePerformance(loggedCalories: Double, dailyGoalCalories: Double): PerformanceCategory {
        if (loggedCalories <= 0.0 || dailyGoalCalories <= 0.0) {
            return PerformanceCategory.EMPTY_MISSED
        }
        val ratio = loggedCalories / dailyGoalCalories
        return when {
            ratio <= OPTIMAL_MIN_RATIO -> PerformanceCategory.UNDER_BUDGET
            ratio <= OPTIMAL_MAX_RATIO -> PerformanceCategory.OPTIMAL_TARGET
            ratio <= OVER_BUDGET_RATIO -> PerformanceCategory.MODERATE_OVER
            else -> PerformanceCategory.HIGH_OVER_TARGET
        }
    }

    /**
     * Returns the design system color token for a given performance category.
     */
    fun getColorForCategory(category: PerformanceCategory): Color {
        return when (category) {
            PerformanceCategory.EMPTY_MISSED -> AppColors.CalendarEmpty
            PerformanceCategory.UNDER_BUDGET -> AppColors.CalendarGreenSubtle
            PerformanceCategory.OPTIMAL_TARGET -> AppColors.CalendarGreenOptimal
            PerformanceCategory.MODERATE_OVER -> AppColors.CalendarRedWarning
            PerformanceCategory.HIGH_OVER_TARGET -> AppColors.CalendarRedAlert
        }
    }
}
