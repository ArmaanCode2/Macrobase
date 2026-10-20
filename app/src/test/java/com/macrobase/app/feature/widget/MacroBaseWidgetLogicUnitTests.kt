package com.macrobase.app.feature.widget

import com.macrobase.app.domain.model.DailyNutritionSummary
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.Meal
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.ServingUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

class MacroBaseWidgetLogicUnitTests {

    private fun createDummyEntry(mealType: MealType): DiaryEntry {
        return DiaryEntry(
            id = 1L,
            uuid = "test-uuid",
            date = LocalDate.now(),
            mealType = mealType,
            food = Food(id = 1L, uuid = "test-uuid", name = "Test Food", nutrition = Nutrition.ZERO),
            serving = Serving(description = "1 serving", unit = ServingUnit.SERVING, gramWeight = 100.0),
            quantity = 1.0,
            calculatedNutrition = Nutrition(calories = 500.0, proteinGrams = 40.0, carbsGrams = 50.0, fatGrams = 15.0)
        )
    }

    private fun calculateRemaining(targetCal: Double, intake: Double): Double {
        return (targetCal - intake).coerceAtLeast(0.0)
    }

    private fun calculatePercent(intake: Double, targetCal: Double): Int {
        return if (targetCal > 0.0) {
            ((intake / targetCal) * 100.0).roundToInt().coerceAtLeast(0)
        } else 0
    }

    // The provider's own status, so these tests check what the widget shows
    private fun calculateCalorieStatus(intake: Double, targetCal: Double): Pair<String, Int> =
        widgetCalorieStatus(intake, targetCal)

    private fun formatMacrosWithProteinGoal(
        proteinIntake: Double, proteinGoal: Double,
        carbIntake: Double, carbGoal: Double,
        fatIntake: Double, fatGoal: Double
    ): String {
        val proteinText = if (proteinGoal > 0.0 && proteinIntake >= proteinGoal) {
            String.format(Locale.US, "Protein: %d/%dg \u2713", proteinIntake.roundToInt(), proteinGoal.roundToInt())
        } else {
            String.format(Locale.US, "Protein: %d/%dg", proteinIntake.roundToInt(), proteinGoal.roundToInt())
        }
        return String.format(
            Locale.US,
            "%s   Carb: %d/%dg   Fat: %d/%dg",
            proteinText,
            carbIntake.roundToInt(), carbGoal.roundToInt(),
            fatIntake.roundToInt(), fatGoal.roundToInt()
        )
    }

    @Test
    fun widget_caloriesRemaining_calculatesAndClampsCorrectly() {
        assertEquals(500.0, calculateRemaining(2000.0, 1500.0), 0.001)
        assertEquals(0.0, calculateRemaining(2000.0, 2000.0), 0.001)
        assertEquals(0.0, calculateRemaining(2000.0, 2350.0), 0.001)
    }

    @Test
    fun widget_caloriePercentage_clampsAtLeastZero() {
        assertEquals(0, calculatePercent(0.0, 2000.0))
        assertEquals(50, calculatePercent(1000.0, 2000.0))
        assertEquals(67, calculatePercent(1333.3, 2000.0))
        assertEquals(130, calculatePercent(2600.0, 2000.0))
        assertEquals(0, calculatePercent(500.0, 0.0))
    }

    @Test
    fun widget_dynamicCalorieStatus_overBudget_showsOverAmountInRed() {
        val (text, color) = calculateCalorieStatus(intake = 2150.0, targetCal = 2000.0)
        assertEquals("150 kcal over", text)
        assertEquals(0xFFFF8A7E.toInt(), color)
    }

    @Test
    fun widget_dynamicCalorieStatus_targetHit_showsTargetHitInGreen() {
        val (textUnder, colorUnder) = calculateCalorieStatus(intake = 1970.0, targetCal = 2000.0)
        assertEquals("Target Hit \u2713", textUnder)
        assertEquals(0xFF6FE07C.toInt(), colorUnder)

        val (textExact, colorExact) = calculateCalorieStatus(intake = 2000.0, targetCal = 2000.0)
        assertEquals("Target Hit \u2713", textExact)
        assertEquals(0xFF6FE07C.toInt(), colorExact)

        val (textSlightOver, colorSlightOver) = calculateCalorieStatus(intake = 2020.0, targetCal = 2000.0)
        assertEquals("Target Hit \u2713", textSlightOver)
        assertEquals(0xFF6FE07C.toInt(), colorSlightOver)
    }

    @Test
    fun widget_dynamicCalorieStatus_underBudget_showsRemainingInPlainText() {
        val (text, color) = calculateCalorieStatus(intake = 1500.0, targetCal = 2000.0)
        assertEquals("500 kcal remaining", text)
        assertEquals(0xFFEDF2EE.toInt(), color)
    }

    @Test
    fun widget_dynamicCalorieStatus_zeroGoal_showsIntakeOnly() {
        val (text, color) = calculateCalorieStatus(intake = 500.0, targetCal = 0.0)
        assertEquals("500 kcal", text)
        assertEquals(0xFFEDF2EE.toInt(), color)
    }

    @Test
    fun widget_macrosWithProteinGoal_showsCheckmarkOnlyWhenGoalMet() {
        val notMet = formatMacrosWithProteinGoal(
            proteinIntake = 140.0, proteinGoal = 180.0,
            carbIntake = 200.0, carbGoal = 250.0,
            fatIntake = 50.0, fatGoal = 60.0
        )
        assertEquals("Protein: 140/180g   Carb: 200/250g   Fat: 50/60g", notMet)

        val met = formatMacrosWithProteinGoal(
            proteinIntake = 182.0, proteinGoal = 180.0,
            carbIntake = 200.0, carbGoal = 250.0,
            fatIntake = 50.0, fatGoal = 60.0
        )
        assertEquals("Protein: 182/180g \u2713   Carb: 200/250g   Fat: 50/60g", met)
    }
}
