package com.macrobase.app.domain.model

import com.macrobase.app.core.config.CalendarPerformanceConfig
import java.time.LocalDate

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
    val totalFiberGrams: Double = 0.0,
    val totalSugarGrams: Double = 0.0,
    val totalSodiumMg: Double = 0.0,
    val totalWaterMl: Double = 0.0,
    val waterGoalMl: Double = 2500.0,
    val meals: List<Meal> = emptyList()
) {
    val calorieBalance: Double
        get() = calorieGoal - totalCaloriesIntake

    val isOverBudget: Boolean
        get() = totalCaloriesIntake > calorieGoal

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
