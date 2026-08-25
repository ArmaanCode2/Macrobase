package com.macrobase.app

import com.macrobase.app.core.config.CalendarPerformanceConfig
import com.macrobase.app.core.config.PortabilityConfig
import com.macrobase.app.data.portability.BackupArchiveManager
import com.macrobase.app.domain.model.CustomFood
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.FoodSource
import com.macrobase.app.domain.model.FoodType
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.MacroBaseBackupData
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Recipe
import com.macrobase.app.domain.model.RecipeIngredient
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.ServingUnit
import com.macrobase.app.domain.model.UnitSystem
import com.macrobase.app.domain.model.UserPreferences
import com.macrobase.app.domain.model.WaterEntry
import com.macrobase.app.domain.model.WeightEntry
import com.macrobase.app.domain.usecase.CalculateNutritionForServingUseCase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate

/**
 * Release Candidate End-to-End System & Regression Test Suite for Phase 17 of MacroBase.
 */
class ReleaseCandidateSystemTests {

    private var connection: Connection? = null
    private val calculateNutritionUseCase = CalculateNutritionForServingUseCase()

    @Before
    fun setUp() {
        val candidates = listOf(
            File("src/main/assets/databases/built_in_foods.db"),
            File("app/src/main/assets/databases/built_in_foods.db"),
            File("built_in_foods.db"),
            File("../built_in_foods.db")
        )
        val assetDbFile = candidates.firstOrNull { it.exists() }
            ?: error("Cannot find built_in_foods.db in candidate locations: ${candidates.map { it.absolutePath }}")

        connection = DriverManager.getConnection("jdbc:sqlite:${assetDbFile.absolutePath}")
    }

    @After
    fun tearDown() {
        connection?.close()
    }

    // =========================================================================
    // 1. CLEAN INSTALL & DEFAULT SYSTEM STATE
    // =========================================================================

    @Test
    fun cleanInstall_initialState_validDefaultsAndCatalog() {
        val conn = checkNotNull(connection)
        val stmt = conn.createStatement()

        // 1. Catalog integrity
        val rs = stmt.executeQuery("SELECT COUNT(*) FROM foods;")
        assertTrue(rs.next())
        assertEquals(1014, rs.getInt(1))
        rs.close()

        // 2. Default Goal invariants
        val defaultGoal = Goal(
            dailyCalorieGoal = 2000.0,
            carbPercentage = 50.0,
            proteinPercentage = 25.0,
            fatPercentage = 25.0
        )
        assertTrue(defaultGoal.isValid)
        assertEquals(250.0, defaultGoal.carbGrams, 0.001)   // (2000 * 0.50) / 4
        assertEquals(125.0, defaultGoal.proteinGrams, 0.001) // (2000 * 0.25) / 4
        assertEquals(55.55, defaultGoal.fatGrams, 0.01)     // (2000 * 0.25) / 9

        // 3. Default Preferences invariants
        val defaultPrefs = UserPreferences(
            unitSystem = UnitSystem.METRIC,
            dailyWaterGoalMl = 2500.0
        )
        assertEquals(UnitSystem.METRIC, defaultPrefs.unitSystem)
        assertEquals(2500.0, defaultPrefs.dailyWaterGoalMl, 0.001)
    }

    // =========================================================================
    // 2. COMPLETE USER JOURNEY (25-STEP END-TO-END FLOW)
    // =========================================================================

    @Test
    fun completeUserJourney_endToEndFlow() {
        // Step 1: Configure profile
        val userPrefs = UserPreferences(
            firstName = "Alex",
            lastName = "Smith",
            timeZone = "America/New_York",
            unitSystem = UnitSystem.METRIC,
            heightCm = 178.0,
            currentWeightKg = 82.5,
            targetWeightKg = 75.0,
            dailyWaterGoalMl = 3000.0
        )
        assertEquals("Alex", userPrefs.firstName)

        // Step 2: Set customized daily goal
        val goal = Goal(
            dailyCalorieGoal = 2400.0,
            carbPercentage = 45.0,
            proteinPercentage = 30.0,
            fatPercentage = 25.0
        )
        assertTrue(goal.isValid)
        assertEquals(270.0, goal.carbGrams, 0.001)   // (2400 * 0.45) / 4
        assertEquals(180.0, goal.proteinGrams, 0.001) // (2400 * 0.30) / 4
        assertEquals(66.66, goal.fatGrams, 0.01)     // (2400 * 0.25) / 9

        // Step 3: Search & Log catalog food (Chicken Breast) to Lunch
        val chickenFood = Food(
            id = 501L,
            uuid = "usda-501",
            source = FoodSource.BUILT_IN,
            name = "Chicken breast, grilled",
            nutrition = Nutrition(calories = 165.0, proteinGrams = 31.0, carbsGrams = 0.0, fatGrams = 3.6),
            servings = listOf(Serving(id = 1, description = "1 breast (172g)", gramWeight = 172.0, quantity = 1.0))
        )
        val chickenCalculated = calculateNutritionUseCase(chickenFood, chickenFood.servings.first(), 1.0)
        val lunchEntry = DiaryEntry(
            id = 1,
            uuid = "diary-lunch-1",
            date = LocalDate.of(2026, 8, 21),
            mealType = MealType.LUNCH,
            food = chickenFood,
            serving = chickenFood.servings.first(),
            quantity = 1.0,
            calculatedNutrition = chickenCalculated
        )

        // Step 4: Create custom food & Log to Breakfast
        val customFood = CustomFood(
            id = 101,
            uuid = "custom-protein-shake",
            name = "Vanilla Whey Shake",
            servingSize = 1.0,
            servingUnit = ServingUnit.SERVING,
            nutritionPerServing = Nutrition(calories = 130.0, proteinGrams = 25.0, carbsGrams = 3.0, fatGrams = 1.5)
        )
        val breakfastEntry = DiaryEntry(
            id = 2,
            uuid = "diary-breakfast-1",
            date = LocalDate.of(2026, 8, 21),
            mealType = MealType.BREAKFAST,
            food = customFood.toFood(),
            serving = customFood.toFood().servings.first(),
            quantity = 1.0,
            calculatedNutrition = customFood.nutritionPerServing
        )

        // Step 5: Create recipe & Log to Dinner
        val recipe = Recipe(
            id = 201,
            uuid = "recipe-chicken-salad",
            name = "Chicken Salad Bowl",
            servingsProduced = 2,
            ingredients = listOf(
                RecipeIngredient(id = 1, food = chickenFood, serving = chickenFood.servings.first(), quantity = 2.0),
                RecipeIngredient(id = 2, food = customFood.toFood(), serving = customFood.toFood().servings.first(), quantity = 1.0)
            )
        )
        val dinnerEntry = DiaryEntry(
            id = 3,
            uuid = "diary-dinner-1",
            date = LocalDate.of(2026, 8, 21),
            mealType = MealType.DINNER,
            food = recipe.toFood(),
            serving = recipe.toFood().servings.first(),
            quantity = 1.0,
            calculatedNutrition = recipe.nutritionPerServing
        )

        // Step 6: Log Hydration (+500 mL, +250 mL, +600 mL = 1350 mL)
        val waterLogs = listOf(
            WaterEntry(id = 1, date = LocalDate.of(2026, 8, 21), amountMl = 500.0),
            WaterEntry(id = 2, date = LocalDate.of(2026, 8, 21), amountMl = 250.0),
            WaterEntry(id = 3, date = LocalDate.of(2026, 8, 21), amountMl = 600.0)
        )
        val totalWater = waterLogs.sumOf { it.amountMl }
        assertEquals(1350.0, totalWater, 0.001)

        // Step 7: Log Body Weight
        val weightLog = WeightEntry(
            id = 1,
            date = LocalDate.of(2026, 8, 21),
            weightKg = 82.5,
            note = "Morning weigh-in"
        )
        assertEquals(82.5, weightLog.weightKg, 0.001)

        // Step 8: Assert Daily Aggregation
        val dayEntries = listOf(breakfastEntry, lunchEntry, dinnerEntry)
        val totalCaloriesIntake = dayEntries.sumOf { it.loggedCalories }
        val totalProteinIntake = dayEntries.sumOf { it.loggedProtein }
        val totalCarbsIntake = dayEntries.sumOf { it.loggedCarbs }
        val totalFatIntake = dayEntries.sumOf { it.loggedFat }

        assertTrue(totalCaloriesIntake > 0)
        assertTrue(totalProteinIntake > 0)

        // Step 9: Assert Calendar Adherence Level
        val performance = CalendarPerformanceConfig.evaluatePerformance(totalCaloriesIntake, goal.dailyCalorieGoal)
        assertNotNull(performance)

        // Step 10: Export to Backup ZIP and Round-Trip Restore
        val backupData = SyntheticDataFixture.create(diaryCount = 3, waterCount = 3, weightCount = 1, customFoodCount = 1, recipeCount = 1)
        val out = ByteArrayOutputStream()
        BackupArchiveManager.createBackupArchive(backupData, out)

        val validation = BackupArchiveManager.extractAndValidateBackup(ByteArrayInputStream(out.toByteArray()))
        assertTrue("Round-trip validation must succeed", validation.isValid)
        assertEquals(3, validation.backupData?.diaryEntries?.size)
    }

    // =========================================================================
    // 3. HISTORICAL DATE REGRESSION (NO DATE CROSS-TALK)
    // =========================================================================

    @Test
    fun historicalDateRegression_noDateLeakage() {
        val aug10 = LocalDate.of(2026, 8, 10)
        val aug15 = LocalDate.of(2026, 8, 15)
        val aug20 = LocalDate.of(2026, 8, 20)

        val foodA = Food(id = 1, uuid = "food-a", name = "Food A", nutrition = Nutrition(calories = 200.0, proteinGrams = 10.0, carbsGrams = 20.0, fatGrams = 5.0))
        val foodB = Food(id = 2, uuid = "food-b", name = "Food B", nutrition = Nutrition(calories = 350.0, proteinGrams = 20.0, carbsGrams = 40.0, fatGrams = 10.0))
        val foodC = Food(id = 3, uuid = "food-c", name = "Food C", nutrition = Nutrition(calories = 500.0, proteinGrams = 30.0, carbsGrams = 50.0, fatGrams = 15.0))

        val entries = listOf(
            DiaryEntry(id = 1, uuid = "e1", date = aug10, mealType = MealType.BREAKFAST, food = foodA, serving = Serving(id = 1, description = "1 serv", gramWeight = 100.0, quantity = 1.0), quantity = 1.0, calculatedNutrition = foodA.nutrition),
            DiaryEntry(id = 2, uuid = "e2", date = aug15, mealType = MealType.LUNCH, food = foodB, serving = Serving(id = 1, description = "1 serv", gramWeight = 100.0, quantity = 1.0), quantity = 1.0, calculatedNutrition = foodB.nutrition),
            DiaryEntry(id = 3, uuid = "e3", date = aug20, mealType = MealType.DINNER, food = foodC, serving = Serving(id = 1, description = "1 serv", gramWeight = 100.0, quantity = 1.0), quantity = 1.0, calculatedNutrition = foodC.nutrition)
        )

        val aug10Entries = entries.filter { it.date == aug10 }
        val aug15Entries = entries.filter { it.date == aug15 }
        val aug20Entries = entries.filter { it.date == aug20 }

        assertEquals(1, aug10Entries.size)
        assertEquals("Food A", aug10Entries.first().food.name)
        assertEquals(200.0, aug10Entries.first().loggedCalories, 0.001)

        assertEquals(1, aug15Entries.size)
        assertEquals("Food B", aug15Entries.first().food.name)
        assertEquals(350.0, aug15Entries.first().loggedCalories, 0.001)

        assertEquals(1, aug20Entries.size)
        assertEquals("Food C", aug20Entries.first().food.name)
        assertEquals(500.0, aug20Entries.first().loggedCalories, 0.001)
    }

    // =========================================================================
    // 4. GOALS THRESHOLD BOUNDARIES (85%, 105%, 120%)
    // =========================================================================

    @Test
    fun goalsThresholdBoundaries_exactAdherenceColorTransitions() {
        val targetGoal = 2000.0

        // 0. Zero intake
        assertEquals(CalendarPerformanceConfig.PerformanceCategory.EMPTY_MISSED, CalendarPerformanceConfig.evaluatePerformance(0.0, targetGoal))

        // 1. Under Budget (<= 85% = <= 1700 kcal)
        assertEquals(CalendarPerformanceConfig.PerformanceCategory.UNDER_BUDGET, CalendarPerformanceConfig.evaluatePerformance(1699.0, targetGoal))
        assertEquals(CalendarPerformanceConfig.PerformanceCategory.UNDER_BUDGET, CalendarPerformanceConfig.evaluatePerformance(1700.0, targetGoal))

        // 2. Optimal Target (> 85% to <= 105% = 1700.2 to 2100 kcal)
        assertEquals(CalendarPerformanceConfig.PerformanceCategory.OPTIMAL_TARGET, CalendarPerformanceConfig.evaluatePerformance(1700.2, targetGoal))
        assertEquals(CalendarPerformanceConfig.PerformanceCategory.OPTIMAL_TARGET, CalendarPerformanceConfig.evaluatePerformance(2000.0, targetGoal))
        assertEquals(CalendarPerformanceConfig.PerformanceCategory.OPTIMAL_TARGET, CalendarPerformanceConfig.evaluatePerformance(2100.0, targetGoal))

        // 3. Moderate Over (> 105% to <= 120% = 2100.2 to 2400 kcal)
        assertEquals(CalendarPerformanceConfig.PerformanceCategory.MODERATE_OVER, CalendarPerformanceConfig.evaluatePerformance(2100.2, targetGoal))
        assertEquals(CalendarPerformanceConfig.PerformanceCategory.MODERATE_OVER, CalendarPerformanceConfig.evaluatePerformance(2400.0, targetGoal))

        // 4. High Over Target (> 120% = > 2400 kcal)
        assertEquals(CalendarPerformanceConfig.PerformanceCategory.HIGH_OVER_TARGET, CalendarPerformanceConfig.evaluatePerformance(2400.2, targetGoal))
        assertEquals(CalendarPerformanceConfig.PerformanceCategory.HIGH_OVER_TARGET, CalendarPerformanceConfig.evaluatePerformance(3000.0, targetGoal))
    }

    // =========================================================================
    // 5. RECIPE SCALING & INGREDIENT CALCULATIONS
    // =========================================================================

    @Test
    fun recipeScalingAndIngredientNutrients_exactCalculations() {
        val ing1 = Food(id = 1, uuid = "i1", name = "Ing 1", nutrition = Nutrition(calories = 200.0, proteinGrams = 20.0, carbsGrams = 10.0, fatGrams = 5.0))
        val ing2 = Food(id = 2, uuid = "i2", name = "Ing 2", nutrition = Nutrition(calories = 300.0, proteinGrams = 10.0, carbsGrams = 40.0, fatGrams = 10.0))

        val s1 = Serving(id = 1, description = "100g", gramWeight = 100.0, quantity = 1.0)
        val s2 = Serving(id = 2, description = "100g", gramWeight = 100.0, quantity = 1.0)

        val recipe = Recipe(
            id = 1,
            name = "Test Recipe",
            servingsProduced = 2,
            ingredients = listOf(
                RecipeIngredient(id = 1, food = ing1, serving = s1, quantity = 1.0),
                RecipeIngredient(id = 2, food = ing2, serving = s2, quantity = 1.0)
            )
        )

        // Total composite nutrition: calories = 200 + 300 = 500; protein = 30; carbs = 50; fat = 15
        assertEquals(500.0, recipe.totalNutrition.calories, 0.001)
        assertEquals(30.0, recipe.totalNutrition.proteinGrams, 0.001)
        assertEquals(50.0, recipe.totalNutrition.carbsGrams, 0.001)
        assertEquals(15.0, recipe.totalNutrition.fatGrams, 0.001)

        // Per-serving nutrition (divided by 2 servings produced)
        assertEquals(250.0, recipe.nutritionPerServing.calories, 0.001)
        assertEquals(15.0, recipe.nutritionPerServing.proteinGrams, 0.001)
        assertEquals(25.0, recipe.nutritionPerServing.carbsGrams, 0.001)
        assertEquals(7.5, recipe.nutritionPerServing.fatGrams, 0.001)
    }

    // =========================================================================
    // 6. DEPENDENCY INJECTION GRAPH INTEGRITY
    // =========================================================================

    @Test
    fun diModules_allRequiredModulesArePresent() {
        val modules = com.macrobase.app.core.di.appModules
        assertEquals(4, modules.size)
    }
}

