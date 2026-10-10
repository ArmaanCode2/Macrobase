package com.macrobase.app

import com.macrobase.app.core.config.CalendarPerformanceConfig
import com.macrobase.app.domain.model.DailyNutritionSummary
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.FitnessGoal
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.GoalStrategyHelper
import com.macrobase.app.domain.model.Meal
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.ServingUnit
import com.macrobase.app.domain.model.UnitConversions
import com.macrobase.app.domain.model.UnitSystem
import com.macrobase.app.domain.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

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

    @Test
    fun testGoalInputValidation_variousFormats() {
        // Test parsing helper logic matching DailyGoalsScreen
        fun validateInputs(calText: String, cText: String, pText: String, fText: String): Boolean {
            val parsedCalorie = calText.toDoubleOrNull()
            val isCalorieValid = parsedCalorie != null && parsedCalorie > 0.0
            val calorieVal = (parsedCalorie ?: 0.0).coerceAtLeast(0.0)

            val parsedCarbs = cText.toDoubleOrNull()
            val isCarbsValid = parsedCarbs != null && parsedCarbs >= 0.0
            val carbPctVal = parsedCarbs ?: 0.0

            val parsedProtein = pText.toDoubleOrNull()
            val isProteinValid = parsedProtein != null && parsedProtein >= 0.0
            val proteinPctVal = parsedProtein ?: 0.0

            val parsedFat = fText.toDoubleOrNull()
            val isFatValid = parsedFat != null && parsedFat >= 0.0
            val fatPctVal = parsedFat ?: 0.0

            val previewGoal = Goal(
                dailyCalorieGoal = calorieVal,
                carbPercentage = carbPctVal,
                proteinPercentage = proteinPctVal,
                fatPercentage = fatPctVal
            )
            val isValidSum = kotlin.math.abs(previewGoal.totalPercentage - 100.0) < 0.01
            return isCalorieValid && isCarbsValid && isProteinValid && isFatValid && isValidSum
        }

        // 1. Empty string -> invalid
        assertFalse(validateInputs("", "50", "25", "25"))

        // 2. Blank string -> invalid
        assertFalse(validateInputs("   ", "50", "25", "25"))

        // 3. "0" calories -> invalid
        assertFalse(validateInputs("0", "50", "25", "25"))

        // 4. Non-numeric "abc" -> invalid
        assertFalse(validateInputs("abc", "50", "25", "25"))

        // 5. Valid integer "2000" -> valid
        assertTrue(validateInputs("2000", "50", "25", "25"))

        // 6. Valid decimal "2000.5" -> valid
        assertTrue(validateInputs("2000.5", "50", "25", "25"))

        // 7. Invalid macro sum (totals 90%) -> invalid
        assertFalse(validateInputs("2000", "40", "25", "25"))

        // 8. Negative percentage -> invalid
        assertFalse(validateInputs("2000", "-10", "60", "50"))
    }

    @Test
    fun goalStrategyHelper_autoDetectStrategy_resolvesAccurately() {
        // Current 70kg, Target 75kg -> Bulking
        assertEquals(FitnessGoal.BULKING, GoalStrategyHelper.autoDetectStrategy(70.0, 75.0))

        // Current 80kg, Target 72kg -> Cutting
        assertEquals(FitnessGoal.CUTTING, GoalStrategyHelper.autoDetectStrategy(80.0, 72.0))

        // Current 70kg, Target 70kg -> Maintaining
        assertEquals(FitnessGoal.MAINTAINING, GoalStrategyHelper.autoDetectStrategy(70.0, 70.0))

        // Current 70kg, Target 70.05kg (< 0.1 delta threshold) -> Maintaining
        assertEquals(FitnessGoal.MAINTAINING, GoalStrategyHelper.autoDetectStrategy(70.0, 70.05))

        // Current 70kg, Target 69.95kg (< 0.1 delta threshold) -> Maintaining
        assertEquals(FitnessGoal.MAINTAINING, GoalStrategyHelper.autoDetectStrategy(70.0, 69.95))

        // Null weights -> Maintaining
        assertEquals(FitnessGoal.MAINTAINING, GoalStrategyHelper.autoDetectStrategy(null, 75.0))
        assertEquals(FitnessGoal.MAINTAINING, GoalStrategyHelper.autoDetectStrategy(70.0, null))
        assertEquals(FitnessGoal.MAINTAINING, GoalStrategyHelper.autoDetectStrategy(null, null))
    }

    @Test
    fun goalStrategyHelper_getContradictionWarning_bulkingMismatch_returnsWarning() {
        // Bulking with target < current
        val warningMetric = GoalStrategyHelper.getContradictionWarning(
            selectedStrategy = FitnessGoal.BULKING,
            currentWeightKg = 80.0,
            targetWeightKg = 75.0,
            unitSystem = UnitSystem.METRIC
        )
        assertEquals(
            "Warning: Your strategy is set to Bulking, but your target weight (75.0 kg) is lower than your current weight (80.0 kg).",
            warningMetric
        )

        val warningImperial = GoalStrategyHelper.getContradictionWarning(
            selectedStrategy = FitnessGoal.BULKING,
            currentWeightKg = 80.0,
            targetWeightKg = 75.0,
            unitSystem = UnitSystem.IMPERIAL
        )
        val expectedTargetLb = String.format(Locale.US, "%.1f lb", UnitConversions.kgToLbs(75.0))
        val expectedCurrentLb = String.format(Locale.US, "%.1f lb", UnitConversions.kgToLbs(80.0))
        assertEquals(
            "Warning: Your strategy is set to Bulking, but your target weight ($expectedTargetLb) is lower than your current weight ($expectedCurrentLb).",
            warningImperial
        )
    }

    @Test
    fun goalStrategyHelper_getContradictionWarning_cuttingMismatch_returnsWarning() {
        // Cutting with target > current
        val warningMetric = GoalStrategyHelper.getContradictionWarning(
            selectedStrategy = FitnessGoal.CUTTING,
            currentWeightKg = 70.0,
            targetWeightKg = 76.0,
            unitSystem = UnitSystem.METRIC
        )
        assertEquals(
            "Warning: Your strategy is set to Cutting, but your target weight (76.0 kg) is higher than your current weight (70.0 kg).",
            warningMetric
        )

        val warningImperial = GoalStrategyHelper.getContradictionWarning(
            selectedStrategy = FitnessGoal.CUTTING,
            currentWeightKg = 70.0,
            targetWeightKg = 76.0,
            unitSystem = UnitSystem.IMPERIAL
        )
        val expectedTargetLb = String.format(Locale.US, "%.1f lb", UnitConversions.kgToLbs(76.0))
        val expectedCurrentLb = String.format(Locale.US, "%.1f lb", UnitConversions.kgToLbs(70.0))
        assertEquals(
            "Warning: Your strategy is set to Cutting, but your target weight ($expectedTargetLb) is higher than your current weight ($expectedCurrentLb).",
            warningImperial
        )
    }

    @Test
    fun goalStrategyHelper_getContradictionWarning_maintainingMismatch_returnsNotice() {
        // Maintaining with > 0.5kg difference
        val notice = GoalStrategyHelper.getContradictionWarning(
            selectedStrategy = FitnessGoal.MAINTAINING,
            currentWeightKg = 70.0,
            targetWeightKg = 72.0,
            unitSystem = UnitSystem.METRIC
        )
        assertEquals(
            "Notice: Your strategy is set to Maintaining, but your target weight (72.0 kg) differs from your current weight (70.0 kg).",
            notice
        )

        // Maintaining within 0.5kg -> null
        val noticeWithinThreshold = GoalStrategyHelper.getContradictionWarning(
            selectedStrategy = FitnessGoal.MAINTAINING,
            currentWeightKg = 70.0,
            targetWeightKg = 70.3,
            unitSystem = UnitSystem.METRIC
        )
        assertNull(noticeWithinThreshold)
    }

    @Test
    fun goalStrategyHelper_getContradictionWarning_consistentTargets_returnsNull() {
        // Bulking with target > current -> null
        assertNull(GoalStrategyHelper.getContradictionWarning(FitnessGoal.BULKING, 70.0, 75.0))

        // Cutting with target < current -> null
        assertNull(GoalStrategyHelper.getContradictionWarning(FitnessGoal.CUTTING, 80.0, 75.0))

        // Maintaining with same weight -> null
        assertNull(GoalStrategyHelper.getContradictionWarning(FitnessGoal.MAINTAINING, 70.0, 70.0))

        // Null weights -> null
        assertNull(GoalStrategyHelper.getContradictionWarning(FitnessGoal.BULKING, null, 75.0))
        assertNull(GoalStrategyHelper.getContradictionWarning(FitnessGoal.CUTTING, 70.0, null))
        assertNull(GoalStrategyHelper.getContradictionWarning(FitnessGoal.MAINTAINING, null, null))
    }

    private fun createDummyWidgetEntry(mealType: MealType): DiaryEntry {
        return DiaryEntry(
            id = 1L,
            uuid = "widget-test-uuid",
            date = LocalDate.now(),
            mealType = mealType,
            food = Food(id = 1L, uuid = "food-uuid", name = "Test Food", nutrition = Nutrition.ZERO),
            serving = Serving(description = "1 serving", unit = ServingUnit.SERVING, gramWeight = 100.0),
            quantity = 1.0,
            calculatedNutrition = Nutrition(calories = 500.0, proteinGrams = 40.0, carbsGrams = 50.0, fatGrams = 15.0)
        )
    }

    private fun formatWidgetEmptyMeals(summary: DailyNutritionSummary?): String {
        val mainMeals = listOf(MealType.BREAKFAST, MealType.LUNCH, MealType.DINNER)
        val emptyMeals = mainMeals.filter { type ->
            val meal = summary?.meals?.firstOrNull { it.type == type }
            meal == null || meal.entries.isEmpty()
        }
        return if (emptyMeals.isEmpty()) {
            "All main meals logged \u2713"
        } else {
            "Remaining: " + emptyMeals.joinToString(", ") {
                it.displayName.lowercase().replaceFirstChar { c -> c.uppercase() }
            }
        }
    }

    private fun formatWidgetMacros(
        proteinIntake: Double, proteinGoal: Double,
        carbIntake: Double, carbGoal: Double,
        fatIntake: Double, fatGoal: Double
    ): String {
        return String.format(
            Locale.US,
            "Protein: %d/%dg   Carb: %d/%dg   Fat: %d/%dg",
            proteinIntake.roundToInt(), proteinGoal.roundToInt(),
            carbIntake.roundToInt(), carbGoal.roundToInt(),
            fatIntake.roundToInt(), fatGoal.roundToInt()
        )
    }

    @Test
    fun widget_emptyMeals_whenNoEntries_returnsAllMainMealsRemaining() {
        val emptySummary = DailyNutritionSummary(
            date = LocalDate.now(),
            totalCaloriesIntake = 0.0,
            totalProteinGrams = 0.0,
            totalCarbsGrams = 0.0,
            totalFatGrams = 0.0,
            calorieGoal = 2000.0,
            meals = emptyList()
        )
        assertEquals("Remaining: Breakfast, Lunch, Dinner", formatWidgetEmptyMeals(emptySummary))
        assertEquals("Remaining: Breakfast, Lunch, Dinner", formatWidgetEmptyMeals(null))
    }

    @Test
    fun widget_emptyMeals_whenBreakfastLogged_returnsLunchAndDinnerRemaining() {
        val summary = DailyNutritionSummary(
            date = LocalDate.now(),
            totalCaloriesIntake = 500.0,
            totalProteinGrams = 40.0,
            totalCarbsGrams = 50.0,
            totalFatGrams = 15.0,
            calorieGoal = 2000.0,
            meals = listOf(
                Meal(MealType.BREAKFAST, listOf(createDummyWidgetEntry(MealType.BREAKFAST))),
                Meal(MealType.LUNCH, emptyList()),
                Meal(MealType.DINNER, emptyList())
            )
        )
        assertEquals("Remaining: Lunch, Dinner", formatWidgetEmptyMeals(summary))
    }

    @Test
    fun widget_emptyMeals_whenAllLogged_returnsAllMainMealsLoggedWithCheckmark() {
        val summary = DailyNutritionSummary(
            date = LocalDate.now(),
            totalCaloriesIntake = 1500.0,
            totalProteinGrams = 120.0,
            totalCarbsGrams = 150.0,
            totalFatGrams = 45.0,
            calorieGoal = 2000.0,
            meals = listOf(
                Meal(MealType.BREAKFAST, listOf(createDummyWidgetEntry(MealType.BREAKFAST))),
                Meal(MealType.LUNCH, listOf(createDummyWidgetEntry(MealType.LUNCH))),
                Meal(MealType.DINNER, listOf(createDummyWidgetEntry(MealType.DINNER)))
            )
        )
        assertEquals("All main meals logged \u2713", formatWidgetEmptyMeals(summary))
    }

    @Test
    fun widget_macrosFormatting_usesLocaleUSAndRoundToInt() {
        val formatted = formatWidgetMacros(
            proteinIntake = 49.6, proteinGoal = 150.0,
            carbIntake = 199.4, carbGoal = 250.0,
            fatIntake = 29.8, fatGoal = 60.0
        )
        assertEquals("Protein: 50/150g   Carb: 199/250g   Fat: 30/60g", formatted)
    }
}
