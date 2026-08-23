package com.macrobase.app

import com.macrobase.app.core.config.CalendarPerformanceConfig
import com.macrobase.app.domain.model.DailyNutritionSummary
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.UnitConversions
import com.macrobase.app.domain.model.UnitSystem
import com.macrobase.app.domain.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Unit test suite for Phase 8: Goals, Preferences, Unit Conversions, and Calendar Performance.
 */
class GoalsAndPreferencesUnitTests {

    @Test
    fun goalMacroCalculations_standard2000Kcal502525_calculatesAccurateGrams() {
        val goal = Goal(
            dailyCalorieGoal = 2000.0,
            carbPercentage = 50.0,
            proteinPercentage = 25.0,
            fatPercentage = 25.0
        )

        // 2000 * 0.50 = 1000 kcal carbs / 4 = 250.0g
        assertEquals(250.0, goal.carbGrams, 0.001)

        // 2000 * 0.25 = 500 kcal protein / 4 = 125.0g
        assertEquals(125.0, goal.proteinGrams, 0.001)

        // 2000 * 0.25 = 500 kcal fat / 9 = 55.555g
        assertEquals(55.555, goal.fatGrams, 0.01)

        assertTrue(goal.isValidPercentageSum)
        assertTrue(goal.isValid)
    }

    @Test
    fun goalMacroCalculations_customCalorieAndMacroSplits() {
        // High protein: 1800 kcal, 40% Carb, 40% Protein, 20% Fat
        val highProteinGoal = Goal(
            dailyCalorieGoal = 1800.0,
            carbPercentage = 40.0,
            proteinPercentage = 40.0,
            fatPercentage = 20.0
        )

        // 1800 * 0.40 = 720 / 4 = 180g
        assertEquals(180.0, highProteinGoal.carbGrams, 0.001)
        assertEquals(180.0, highProteinGoal.proteinGrams, 0.001)
        // 1800 * 0.20 = 360 / 9 = 40g
        assertEquals(40.0, highProteinGoal.fatGrams, 0.001)
        assertTrue(highProteinGoal.isValidPercentageSum)

        // Keto/Low Carb: 2500 kcal, 10% Carb, 25% Protein, 65% Fat
        val ketoGoal = Goal(
            dailyCalorieGoal = 2500.0,
            carbPercentage = 10.0,
            proteinPercentage = 25.0,
            fatPercentage = 65.0
        )

        assertEquals(62.5, ketoGoal.carbGrams, 0.001)
        assertEquals(156.25, ketoGoal.proteinGrams, 0.001)
        assertEquals(180.555, ketoGoal.fatGrams, 0.01)
        assertTrue(ketoGoal.isValidPercentageSum)
    }

    @Test
    fun goalValidation_detectsInvalidSumsAndZeroGoals() {
        // Sum = 110% (Invalid)
        val overSumGoal = Goal(
            dailyCalorieGoal = 2000.0,
            carbPercentage = 50.0,
            proteinPercentage = 30.0,
            fatPercentage = 30.0
        )
        assertFalse(overSumGoal.isValidPercentageSum)
        assertFalse(overSumGoal.isValid)
        assertEquals(110, overSumGoal.totalPercentage)

        // Sum = 80% (Invalid)
        val underSumGoal = Goal(
            dailyCalorieGoal = 2000.0,
            carbPercentage = 40.0,
            proteinPercentage = 20.0,
            fatPercentage = 20.0
        )
        assertFalse(underSumGoal.isValidPercentageSum)
        assertFalse(underSumGoal.isValid)

        // Zero calorie target (Invalid)
        val zeroGoal = Goal(
            dailyCalorieGoal = 0.0,
            carbPercentage = 50.0,
            proteinPercentage = 25.0,
            fatPercentage = 25.0
        )
        assertTrue(zeroGoal.isValidPercentageSum)
        assertFalse(zeroGoal.isValid)
    }

    @Test
    fun calendarPerformance_evaluatesExactThresholdsAccurately() {
        val goal = 2000.0

        // 1. Zero calories logged -> EMPTY_MISSED
        assertEquals(
            CalendarPerformanceConfig.PerformanceCategory.EMPTY_MISSED,
            CalendarPerformanceConfig.evaluatePerformance(0.0, goal)
        )

        // 2. Zero or negative goal -> EMPTY_MISSED (safe division)
        assertEquals(
            CalendarPerformanceConfig.PerformanceCategory.EMPTY_MISSED,
            CalendarPerformanceConfig.evaluatePerformance(1500.0, 0.0)
        )

        // 3. Exactly 85% (1700 kcal) -> UNDER_BUDGET
        assertEquals(
            CalendarPerformanceConfig.PerformanceCategory.UNDER_BUDGET,
            CalendarPerformanceConfig.evaluatePerformance(1700.0, goal)
        )

        // 4. Just above 85% (1701 kcal -> 85.05%) -> OPTIMAL_TARGET
        assertEquals(
            CalendarPerformanceConfig.PerformanceCategory.OPTIMAL_TARGET,
            CalendarPerformanceConfig.evaluatePerformance(1701.0, goal)
        )

        // 5. Exactly 100% (2000 kcal) -> OPTIMAL_TARGET
        assertEquals(
            CalendarPerformanceConfig.PerformanceCategory.OPTIMAL_TARGET,
            CalendarPerformanceConfig.evaluatePerformance(2000.0, goal)
        )

        // 6. Exactly 105% (2100 kcal) -> OPTIMAL_TARGET
        assertEquals(
            CalendarPerformanceConfig.PerformanceCategory.OPTIMAL_TARGET,
            CalendarPerformanceConfig.evaluatePerformance(2100.0, goal)
        )

        // 7. Just above 105% (2101 kcal -> 105.05%) -> MODERATE_OVER
        assertEquals(
            CalendarPerformanceConfig.PerformanceCategory.MODERATE_OVER,
            CalendarPerformanceConfig.evaluatePerformance(2101.0, goal)
        )

        // 8. Exactly 120% (2400 kcal) -> MODERATE_OVER
        assertEquals(
            CalendarPerformanceConfig.PerformanceCategory.MODERATE_OVER,
            CalendarPerformanceConfig.evaluatePerformance(2400.0, goal)
        )

        // 9. Just above 120% (2401 kcal -> 120.05%) -> HIGH_OVER_TARGET
        assertEquals(
            CalendarPerformanceConfig.PerformanceCategory.HIGH_OVER_TARGET,
            CalendarPerformanceConfig.evaluatePerformance(2401.0, goal)
        )

        // 10. Large overage (4000 kcal -> 200%) -> HIGH_OVER_TARGET
        assertEquals(
            CalendarPerformanceConfig.PerformanceCategory.HIGH_OVER_TARGET,
            CalendarPerformanceConfig.evaluatePerformance(4000.0, goal)
        )
    }

    @Test
    fun unitConversions_metricAndImperialMathIsExactAndReversible() {
        // Weight: 70 kg <-> 154.3235 lbs
        val kg = 70.0
        val lbs = UnitConversions.kgToLbs(kg)
        assertEquals(154.3235, lbs, 0.01)
        assertEquals(kg, UnitConversions.lbsToKg(lbs), 0.0001)

        // Height: 175 cm <-> 68.8976 inches
        val cm = 175.0
        val inches = UnitConversions.cmToInches(cm)
        assertEquals(68.8976, inches, 0.01)
        assertEquals(cm, UnitConversions.inchesToCm(inches), 0.0001)

        // Volume: 2500 mL <-> 84.535 fl oz
        val ml = 2500.0
        val flOz = UnitConversions.mlToFlOz(ml)
        assertEquals(84.535, flOz, 0.01)
        assertEquals(ml, UnitConversions.flOzToMl(flOz), 0.0001)
    }

    @Test
    fun dashboardIntegration_reactsToGoalChangesImmediately() {
        val loggedIntake = 1500.0

        // Case 1: Calorie goal = 2000 kcal -> remaining = 500 kcal, not over budget
        val summary1 = DailyNutritionSummary(
            date = LocalDate.now(),
            totalCaloriesIntake = loggedIntake,
            calorieGoal = 2000.0
        )
        assertFalse(summary1.isOverBudget)
        assertEquals(500.0, summary1.calorieBalance, 0.001)
        assertEquals(0.0, summary1.overBudgetAmount, 0.001)

        // Case 2: User changes goal to 1800 kcal -> remaining = 300 kcal, not over budget
        val summary2 = DailyNutritionSummary(
            date = LocalDate.now(),
            totalCaloriesIntake = loggedIntake,
            calorieGoal = 1800.0
        )
        assertFalse(summary2.isOverBudget)
        assertEquals(300.0, summary2.calorieBalance, 0.001)
        assertEquals(0.0, summary2.overBudgetAmount, 0.001)

        // Case 3: User changes goal to 1200 kcal -> over budget by 300 kcal
        val summary3 = DailyNutritionSummary(
            date = LocalDate.now(),
            totalCaloriesIntake = loggedIntake,
            calorieGoal = 1200.0
        )
        assertTrue(summary3.isOverBudget)
        assertEquals(-300.0, summary3.calorieBalance, 0.001)
        assertEquals(300.0, summary3.overBudgetAmount, 0.001)
    }
}
