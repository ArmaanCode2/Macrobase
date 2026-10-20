package com.macrobase.app

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.ui.unit.dp
import org.robolectric.RuntimeEnvironment
import com.macrobase.app.core.config.CalendarPerformanceConfig.PerformanceCategory
import com.macrobase.app.core.designsystem.components.MbHaptics
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.FoodSource
import com.macrobase.app.domain.model.HeatmapCellData
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.feature.dashboard.formatDiaryEntrySubtitle
import com.macrobase.app.feature.statistics.ConsistencyInterval
import com.macrobase.app.feature.statistics.heatmapCellLabel
import com.macrobase.app.feature.statistics.heatmapCellSize
import com.macrobase.app.feature.statistics.heatmapGap
import com.macrobase.app.feature.statistics.heatmapSlotAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

/**
 * Performance phase 3: the Consistency grid drawn as one picture still opens the day that was
 * tapped, Home's food rows read the same, and the light ticks play the right kind of vibration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LighterScreensTests {

    private val cell = 12f
    private val gap = 3f
    private val pitch = cell + gap

    @Test
    fun aTapOnACellFindsItsWeekAndDay() {
        assertEquals(0 to 0, heatmapSlotAt(1f, 1f, cell, gap, weekCount = 5))
        assertEquals(2 to 4, heatmapSlotAt(2 * pitch + 6f, 4 * pitch + 6f, cell, gap, weekCount = 5))
        // The last row and column, at their far edges
        assertEquals(4 to 6, heatmapSlotAt(4 * pitch + cell - 0.1f, 6 * pitch + cell - 0.1f, cell, gap, weekCount = 5))
    }

    @Test
    fun aTapInAGapGoesToTheNearerCell() {
        // The gap after week 1 runs from 1 * pitch + cell to 2 * pitch
        assertEquals(1 to 0, heatmapSlotAt(pitch + cell + 1f, 1f, cell, gap, weekCount = 5))
        assertEquals(2 to 0, heatmapSlotAt(pitch + cell + 2f, 1f, cell, gap, weekCount = 5))
        assertEquals(0 to 3, heatmapSlotAt(1f, 2 * pitch + cell + 2f, cell, gap, weekCount = 5))
    }

    @Test
    fun aTapOutsideTheGridFindsNothing() {
        assertNull(heatmapSlotAt(-1f, 1f, cell, gap, weekCount = 5))
        assertNull(heatmapSlotAt(1f, -1f, cell, gap, weekCount = 5))
        // Past the last week's trailing gap, and below the last row
        assertNull(heatmapSlotAt(5 * pitch, 1f, cell, gap, weekCount = 5))
        assertNull(heatmapSlotAt(1f, 7 * pitch, cell, gap, weekCount = 5))
        assertNull(heatmapSlotAt(1f, 1f, cell, gap, weekCount = 0))
    }

    @Test
    fun theGridKeepsItsSpacing() {
        assertEquals(5.dp, heatmapGap(heatmapCellSize(ConsistencyInterval.DAYS_30)))
        assertEquals(3.dp, heatmapGap(heatmapCellSize(ConsistencyInterval.DAYS_90)))
        assertEquals(3.dp, heatmapGap(heatmapCellSize(ConsistencyInterval.YEAR_1)))
    }

    @Test
    fun aScreenReaderHearsEachDaysDateAndWhatWasLogged() {
        fun day(calories: Double, eligible: Boolean = true, future: Boolean = false) = HeatmapCellData(
            date = LocalDate.of(2026, 10, 12),
            dayOfWeek = 1,
            caloriesLogged = calories,
            dailyGoalCalories = 2000.0,
            percentageOfGoal = 95,
            category = PerformanceCategory.OPTIMAL_TARGET,
            isEligible = eligible,
            isFuture = future
        )
        val logged = heatmapCellLabel(day(1899.6))
        assertTrue(logged, logged.contains("12"))
        assertTrue(logged, logged.endsWith(": 1900 kcal, 95% of goal"))
        assertTrue(heatmapCellLabel(day(0.0)).endsWith(": no food logged"))
        assertTrue(heatmapCellLabel(day(0.0, eligible = false)).endsWith(": nothing logged yet"))
        assertTrue(heatmapCellLabel(day(0.0, eligible = false, future = true)).endsWith(": still to come"))
    }

    @Test
    fun aFoodRowReadsTheSameAmountAsBefore() {
        assertEquals("2 bowl", formatDiaryEntrySubtitle(entry("1 bowl", 2.0)))
        assertEquals("1.5 bowl", formatDiaryEntrySubtitle(entry("1 bowl", 1.5)))
        assertEquals("200 g", formatDiaryEntrySubtitle(entry("100 g", 2.0)))
        assertEquals("150 g", formatDiaryEntrySubtitle(entry("100g", 1.5)))
        assertEquals("2 slice of bread", formatDiaryEntrySubtitle(entry("slice of bread", 2.0)))
        assertEquals("0.3", formatDiaryEntrySubtitle(entry("", 0.25)))
        // Called again, the hoisted pattern gives the same answer
        assertEquals("2 bowl", formatDiaryEntrySubtitle(entry("1 bowl", 2.0)))
    }

    @Test
    fun switchingTicksAndASwipeReachingItsPointPlaysTheGestureTick() {
        val view = View(RuntimeEnvironment.getApplication())
        val haptics = MbHaptics(view)

        haptics.switched()
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, shadowOf(view).lastHapticFeedbackPerformed())

        haptics.reachedOpen()
        assertEquals(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE, shadowOf(view).lastHapticFeedbackPerformed())
    }

    private fun entry(description: String, quantity: Double): DiaryEntry {
        val serving = Serving(description = description, gramWeight = 100.0, isDefault = true)
        val nutrition = Nutrition(calories = 100.0, proteinGrams = 1.0, carbsGrams = 1.0, fatGrams = 1.0)
        return DiaryEntry(
            uuid = "e-$description-$quantity",
            date = LocalDate.of(2026, 10, 12),
            mealType = MealType.LUNCH,
            food = Food(id = 1_896_880_878_330_341L, uuid = "f-1", source = FoodSource.BUILT_IN, name = "Dal", nutrition = nutrition, servings = listOf(serving)),
            serving = serving,
            quantity = quantity,
            calculatedNutrition = nutrition,
            loggedAt = Instant.ofEpochMilli(1_790_000_000_000L)
        )
    }
}
