package com.macrobase.app.domain.model

import com.macrobase.app.core.config.CalendarPerformanceConfig
import java.time.LocalDate

/**
 * Domain model representing a single cell in the GitHub-style Nutrition Consistency Heatmap.
 */
data class HeatmapCellData(
    val date: LocalDate,
    val dayOfWeek: Int, // 1 = Monday .. 7 = Sunday
    val caloriesLogged: Double,
    val dailyGoalCalories: Double,
    val percentageOfGoal: Int,
    val category: CalendarPerformanceConfig.PerformanceCategory,
    val isEligible: Boolean, // date <= today
    val isFuture: Boolean    // date > today
)

/**
 * Domain model representing a single week column in the heatmap grid.
 */
data class HeatmapWeek(
    val weekIndex: Int,
    val cells: List<HeatmapCellData?> // Size 7: 0 = Mon, 1 = Tue, ..., 6 = Sun
)

/**
 * The first day of a [days]-day window that ends on [today], so the window holds exactly [days]
 * days: "30 days" is today and the 29 before it (BUG-033).
 */
fun windowStart(today: LocalDate, days: Long): LocalDate {
    require(days >= 1) { "A window holds at least one day, not $days" }
    return today.minusDays(days - 1)
}

/**
 * Domain statistics model for Nutrition Consistency Heatmap & Consistency Score.
 */
data class ConsistencyStatistics(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val totalEligibleDays: Int,
    val loggedDaysCount: Int,
    val consistencyScorePercentage: Int, // (loggedDaysCount / totalEligibleDays * 100).roundToInt()
    val weeks: List<HeatmapWeek> = emptyList(),
    val allCells: List<HeatmapCellData> = emptyList()
)

/**
 * Domain statistics model for Macro Averages & Target vs Actual Distribution.
 */
data class MacroAveragesStatistics(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val loggedDaysCount: Int,
    val averageCalories: Double,
    val averageProteinGrams: Double,
    val averageCarbsGrams: Double,
    val averageFatGrams: Double,
    // Actual distribution percentages (based on consumed calories: P*4, C*4, F*9)
    val actualCarbsPercent: Double,
    val actualProteinPercent: Double,
    val actualFatPercent: Double,
    // Target distribution percentages from Goal
    val targetCarbsPercent: Double,
    val targetProteinPercent: Double,
    val targetFatPercent: Double,
    val targetCalories: Double
) {
    val hasSufficientData: Boolean
        get() = loggedDaysCount > 0 && averageCalories > 0.0
}

/**
 * Domain statistics model summarizing body weight trend over a selected date period.
 */
data class WeightStatistics(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val initialWeightKg: Double? = null,
    val latestWeightKg: Double? = null,
    val minWeightKg: Double? = null,
    val maxWeightKg: Double? = null,
    val averageWeightKg: Double? = null,
    val deltaWeightKg: Double? = null,
    val percentageChange: Double? = null,
    val entries: List<WeightEntry> = emptyList()
) {
    val hasSufficientData: Boolean
        get() = entries.size >= 2
}
