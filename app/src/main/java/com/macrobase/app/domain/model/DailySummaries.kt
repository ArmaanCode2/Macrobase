package com.macrobase.app.domain.model

import com.macrobase.app.core.config.CalendarPerformanceConfig
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Aggregated daily summary model for the Home Dashboard.
 */
data class DailyNutritionSummary(
    val date: LocalDate,
    val totalCaloriesIntake: Double = 0.0,
    val totalCaloriesBurned: Double = 0.0,
    val calorieGoal: Double = 2000.0,
    val totalProteinGrams: Double = 0.0,
    val totalCarbsGrams: Double = 0.0,
    val totalFatGrams: Double = 0.0,
    // Null when no entry of the day states the nutrient; shown as "-" (AGENTS.md section 2.4)
    val totalFiberGrams: Double? = null,
    val totalSugarGrams: Double? = null,
    val totalSodiumMg: Double? = null,
    val totalWaterMl: Double = 0.0,
    val waterGoalMl: Double = 2500.0,
    val meals: List<Meal> = emptyList()
) {
    val calorieBalance: Double
        get() = calorieGoal - totalCaloriesIntake

    /**
     * Balance as displayed: rounded goal minus rounded intake, so the dashboard strip always
     * agrees with the intake it shows (2000 intake never reads "1 Remaining").
     */
    val displayedCalorieBalance: Int
        get() = calorieGoal.roundToInt() - totalCaloriesIntake.roundToInt()

    val isOverBudget: Boolean
        get() = displayedCalorieBalance < 0

    val overBudgetAmount: Double
        get() = if (isOverBudget) totalCaloriesIntake - calorieGoal else 0.0

    val adherenceCategory: CalendarPerformanceConfig.PerformanceCategory
        get() = CalendarPerformanceConfig.evaluatePerformance(totalCaloriesIntake, calorieGoal)
}

/**
 * Calendar adherence summary model for an individual date cell.
 */
data class CalendarDaySummary(
    val date: LocalDate,
    val loggedCalories: Double,
    val calorieGoal: Double,
    val hasLogs: Boolean = loggedCalories > 0.0,
    val performanceCategory: CalendarPerformanceConfig.PerformanceCategory =
        CalendarPerformanceConfig.evaluatePerformance(loggedCalories, calorieGoal)
)
