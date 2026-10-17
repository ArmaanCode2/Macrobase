package com.macrobase.app

import androidx.compose.ui.graphics.Color
import com.macrobase.app.core.config.CalendarPerformanceConfig.PerformanceCategory
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.feature.calendar.CalendarDayCellModel
import com.macrobase.app.feature.calendar.calendarCellColors
import com.macrobase.app.feature.statistics.ConsistencyInterval
import com.macrobase.app.feature.statistics.MacroInterval
import com.macrobase.app.feature.statistics.RankHistoryInterval
import com.macrobase.app.feature.statistics.StatisticsViews
import com.macrobase.app.feature.statistics.heatmapCellSize
import com.macrobase.app.feature.weight.weightIntervalChipLabel
import com.macrobase.app.feature.weight.WeightTimeInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Design phase 7, Stats: the dock views, the chip texts and the calendar cell colours. */
class DesignStatsTests {

    @Test
    fun theDockListsTheFiveStatisticsViewsInTheAppsOrder() {
        assertEquals(listOf("Calendar", "Weight", "Consistency", "Averages", "Rank history"), StatisticsViews)
    }

    @Test
    fun everyRangeChipIsInSentenceCase() {
        assertEquals(
            listOf("Last 30 days", "Last 3 months", "Last 6 months", "1 year", "All time", "Custom"),
            WeightTimeInterval.entries.map(::weightIntervalChipLabel)
        )
        // The Weight screen keeps its own labels
        assertEquals("1 Year", WeightTimeInterval.ONE_YEAR.label)

        assertEquals(listOf("30 days", "90 days", "6 months", "1 year"), ConsistencyInterval.entries.map { it.label })
        assertEquals(listOf("7-day", "30-day", "90-day"), MacroInterval.entries.map { it.label })
        assertEquals(listOf("30 days", "90 days", "This season", "All"), RankHistoryInterval.entries.map { it.label })
    }

    @Test
    fun longerConsistencyRangesUseSmallerHeatmapCells() {
        val sizes = ConsistencyInterval.entries.map(::heatmapCellSize)
        assertTrue(sizes.zipWithNext().all { (a, b) -> a >= b })
        assertTrue(heatmapCellSize(ConsistencyInterval.DAYS_30) > heatmapCellSize(ConsistencyInterval.YEAR_1))
    }

    @Test
    fun calendarCellsUseTheDesignColours() {
        fun cell(category: PerformanceCategory, currentMonth: Boolean = true, future: Boolean = false) = CalendarDayCellModel(
            date = LocalDate.of(2026, 10, 12),
            dayNumber = 12,
            isCurrentMonth = currentMonth,
            isToday = false,
            isFuture = future,
            loggedCalories = 0.0,
            calorieGoal = 2000.0,
            performanceCategory = category
        )

        assertEquals(AppColors.CalendarGreenOptimal to AppColors.CalendarOptimalInk, calendarCellColors(cell(PerformanceCategory.OPTIMAL_TARGET)))
        assertEquals(AppColors.CalendarGreenSubtle to AppColors.CalendarUnderInk, calendarCellColors(cell(PerformanceCategory.UNDER_BUDGET)))
        assertEquals(AppColors.CalendarRedWarning, calendarCellColors(cell(PerformanceCategory.MODERATE_OVER)).first)
        assertEquals(AppColors.CalendarRedAlert, calendarCellColors(cell(PerformanceCategory.HIGH_OVER_TARGET)).first)
        // A past day with nothing logged is a missed day
        assertEquals(AppColors.CalendarEmpty, calendarCellColors(cell(PerformanceCategory.EMPTY_MISSED)).first)
        // An upcoming day with nothing logged, and a day of the next or previous month, have no fill
        assertEquals(Color.Transparent, calendarCellColors(cell(PerformanceCategory.EMPTY_MISSED, future = true)).first)
        assertEquals(Color.Transparent, calendarCellColors(cell(PerformanceCategory.EMPTY_MISSED, currentMonth = false)).first)
    }
}
