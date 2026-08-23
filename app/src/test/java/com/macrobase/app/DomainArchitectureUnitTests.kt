package com.macrobase.app

import com.macrobase.app.core.config.CalendarPerformanceConfig
import com.macrobase.app.core.config.NutritionStandardConfig
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.FoodSource
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Recipe
import com.macrobase.app.domain.model.RecipeIngredient
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.ServingUnit
import com.macrobase.app.domain.usecase.CalculateNutritionForServingUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class DomainArchitectureUnitTests {

    @Test
    fun goal_calculatesAccurateGramsFromPercentages() {
        val standardGoal = Goal(
            dailyCalorieGoal = 2000.0,
            carbPercentage = 50.0,
            proteinPercentage = 25.0,
            fatPercentage = 25.0
        )

        assertTrue(standardGoal.isValidPercentageSum)
        assertEquals(100, standardGoal.totalPercentage)

        // Carbs: (2000 * 0.50) / 4 = 250.0g
        assertEquals(250.0, standardGoal.carbGrams, 0.001)

        // Protein: (2000 * 0.25) / 4 = 125.0g
        assertEquals(125.0, standardGoal.proteinGrams, 0.001)

        // Fat: (2000 * 0.25) / 9 = 55.555g
        assertEquals(55.555, standardGoal.fatGrams, 0.01)
    }

    @Test
    fun goal_detectsInvalidMacroPercentages() {
        val underGoal = Goal(
            dailyCalorieGoal = 2000.0,
            carbPercentage = 40.0,
            proteinPercentage = 20.0,
            fatPercentage = 20.0
        )
        assertFalse(underGoal.isValidPercentageSum)
        assertEquals(80, underGoal.totalPercentage)

        val overGoal = Goal(
            dailyCalorieGoal = 2000.0,
            carbPercentage = 50.0,
            proteinPercentage = 30.0,
            fatPercentage = 30.0
        )
        assertFalse(overGoal.isValidPercentageSum)
        assertEquals(110, overGoal.totalPercentage)
    }

    @Test
    fun calendarPerformance_evaluatesCorrectCategories() {
        val goal = 2000.0

        // Zero logged -> EMPTY_MISSED
        assertEquals(
            CalendarPerformanceConfig.PerformanceCategory.EMPTY_MISSED,
            CalendarPerformanceConfig.evaluatePerformance(0.0, goal)
        )

        // 1600 calories logged (80%) -> UNDER_BUDGET (<= 85%)
        assertEquals(
            CalendarPerformanceConfig.PerformanceCategory.UNDER_BUDGET,
            CalendarPerformanceConfig.evaluatePerformance(1600.0, goal)
        )

        // 1700 calories logged (85% boundary) -> UNDER_BUDGET (<= 0.85 * goal)
        assertEquals(
            CalendarPerformanceConfig.PerformanceCategory.UNDER_BUDGET,
            CalendarPerformanceConfig.evaluatePerformance(1700.0, goal)
        )

        // 1800 calories logged (90%) -> OPTIMAL_TARGET (0.85 * goal < ratio <= 1.05 * goal)
        assertEquals(
            CalendarPerformanceConfig.PerformanceCategory.OPTIMAL_TARGET,
            CalendarPerformanceConfig.evaluatePerformance(1800.0, goal)
        )

        // 2000 calories logged (100%) -> OPTIMAL_TARGET
        assertEquals(
            CalendarPerformanceConfig.PerformanceCategory.OPTIMAL_TARGET,
            CalendarPerformanceConfig.evaluatePerformance(2000.0, goal)
        )

        // 2100 calories logged (105% boundary) -> OPTIMAL_TARGET
        assertEquals(
            CalendarPerformanceConfig.PerformanceCategory.OPTIMAL_TARGET,
            CalendarPerformanceConfig.evaluatePerformance(2100.0, goal)
        )

        // 2300 calories logged (115%) -> MODERATE_OVER (105% < ratio <= 120%)
        assertEquals(
            CalendarPerformanceConfig.PerformanceCategory.MODERATE_OVER,
            CalendarPerformanceConfig.evaluatePerformance(2300.0, goal)
        )

        // 2400 calories logged (120% boundary) -> MODERATE_OVER
        assertEquals(
            CalendarPerformanceConfig.PerformanceCategory.MODERATE_OVER,
            CalendarPerformanceConfig.evaluatePerformance(2400.0, goal)
        )

        // 2500 calories logged (125%) -> HIGH_OVER_TARGET (> 120%)
        assertEquals(
            CalendarPerformanceConfig.PerformanceCategory.HIGH_OVER_TARGET,
            CalendarPerformanceConfig.evaluatePerformance(2500.0, goal)
        )
    }

    @Test
    fun serving_calculatesAccurateMultipliers() {
        // 1 cup = 244g (quantity=1.0)
        val cupServing = Serving(
            id = 1,
            description = "1 cup",
            unit = ServingUnit.CUP,
            quantity = 1.0,
            gramWeight = 244.0
        )

        // Multiplier for 1.0 cup = 244 / 100 = 2.44x
        assertEquals(2.44, cupServing.calculateGramMultiplier(1.0), 0.001)

        // Multiplier for 2.5 cups = (244 * 2.5) / 100 = 6.1x
        assertEquals(6.1, cupServing.calculateGramMultiplier(2.5), 0.001)

        // 2 tablespoons = 32g (quantity=2.0)
        val tbspServing = Serving(
            id = 2,
            description = "2 tbsp",
            unit = ServingUnit.TABLESPOON,
            quantity = 2.0,
            gramWeight = 32.0
        )

        // Multiplier for 3 tablespoons = (32 / 2 * 3) / 100 = 0.48x
        assertEquals(0.48, tbspServing.calculateGramMultiplier(3.0), 0.001)
    }

    @Test
    fun calculateNutritionForServingUseCase_scalesValuesAndPreservesNulls() {
        val useCase = CalculateNutritionForServingUseCase()

        val food = Food(
            id = 10,
            uuid = "test-food-uuid",
            name = "Greek Yogurt Plain",
            nutrition = Nutrition(
                calories = 59.0,         // per 100g
                proteinGrams = 10.0,
                carbsGrams = 3.6,
                fatGrams = 0.4,
                fiberGrams = 0.0,
                sugarGrams = 3.2,
                sodiumMg = 36.0,
                potassiumMg = 141.0,
                calciumMg = 110.0,
                ironMg = null,           // Unanalyzed / missing -> preserved as null
                vitaminDMcg = null       // Unanalyzed / missing -> preserved as null
            )
        )

        // 1 container = 170g
        val containerServing = Serving(
            id = 1,
            description = "1 container (170g)",
            unit = ServingUnit.SERVING,
            quantity = 1.0,
            gramWeight = 170.0
        )

        // Calculate for 1.5 containers (255g -> 2.55x)
        val calculated = useCase(food, containerServing, 1.5)

        assertEquals(150.45, calculated.calories, 0.01)
        assertEquals(25.5, calculated.proteinGrams, 0.01)
        assertEquals(9.18, calculated.carbsGrams, 0.01)
        assertEquals(1.02, calculated.fatGrams, 0.01)
        assertEquals(9.18, calculated.netCarbsGrams, 0.01)
        assertEquals(91.8, calculated.sodiumMg!!, 0.01)
        assertEquals(359.55, calculated.potassiumMg!!, 0.01)

        // Missing nutrients must remain null, NOT silently coerced to 0.0
        assertNull(calculated.ironMg)
        assertNull(calculated.vitaminDMcg)
    }

    @Test
    fun recipe_calculatesPerServingNutritionAccurately() {
        val ingredient1 = Food(
            id = 1,
            uuid = "ing-1",
            name = "Oats",
            nutrition = Nutrition(calories = 389.0, proteinGrams = 16.9, carbsGrams = 66.3, fatGrams = 6.9)
        )
        val ingredient2 = Food(
            id = 2,
            uuid = "ing-2",
            name = "Peanut Butter",
            nutrition = Nutrition(calories = 588.0, proteinGrams = 25.0, carbsGrams = 20.0, fatGrams = 50.0)
        )

        val recipe = Recipe(
            id = 1,
            uuid = "recipe-oat-bars",
            name = "Peanut Butter Oat Bars",
            servingsProduced = 4,
            ingredients = listOf(
                RecipeIngredient(
                    id = 1,
                    food = ingredient1,
                    serving = Serving(description = "100g", gramWeight = 100.0),
                    quantity = 2.0 // 200g oats -> 778 kcal, 33.8g P, 132.6g C, 13.8g F
                ),
                RecipeIngredient(
                    id = 2,
                    food = ingredient2,
                    serving = Serving(description = "100g", gramWeight = 100.0),
                    quantity = 1.0 // 100g PB -> 588 kcal, 25.0g P, 20.0g C, 50.0g F
                )
            )
        )

        // Total: 778 + 588 = 1366 kcal
        assertEquals(1366.0, recipe.totalNutrition.calories, 0.1)

        // Per serving (4 servings): 1366 / 4 = 341.5 kcal
        val perServing = recipe.nutritionPerServing
        assertEquals(341.5, perServing.calories, 0.1)
        assertEquals(14.7, perServing.proteinGrams, 0.1) // (33.8 + 25.0) / 4 = 14.7g
        assertEquals(38.15, perServing.carbsGrams, 0.1) // (132.6 + 20.0) / 4 = 38.15g
        assertEquals(15.95, perServing.fatGrams, 0.1)   // (13.8 + 50.0) / 4 = 15.95g
    }

    @Test
    fun dateHandling_createsConsistentEpochAndMonthlyRanges() {
        val testDate = LocalDate.of(2026, 8, 19)
        val epochDay = testDate.toEpochDay()

        assertEquals(testDate, LocalDate.ofEpochDay(epochDay))

        val yearMonth = YearMonth.of(2026, 8)
        val firstDay = yearMonth.atDay(1)
        val lastDay = yearMonth.atEndOfMonth()

        assertEquals(LocalDate.of(2026, 8, 1), firstDay)
        assertEquals(LocalDate.of(2026, 8, 31), lastDay)
        assertEquals(31, yearMonth.lengthOfMonth())
    }

    @Test
    fun navigationDestinations_companionListsAreFullyInitializedAndNonNull() {
        val bottomNav = com.macrobase.app.core.navigation.Screen.bottomNavScreens
        org.junit.Assert.assertNotNull(bottomNav)
        org.junit.Assert.assertTrue(bottomNav.isNotEmpty())
        bottomNav.forEach { screen ->
            org.junit.Assert.assertNotNull(screen)
            org.junit.Assert.assertNotNull(screen.route)
            org.junit.Assert.assertNotNull(screen.title)
            org.junit.Assert.assertNotNull(screen.icon)
        }

        val drawer = com.macrobase.app.core.navigation.Screen.drawerScreens
        org.junit.Assert.assertNotNull(drawer)
        org.junit.Assert.assertTrue(drawer.isNotEmpty())
        drawer.forEach { screen ->
            org.junit.Assert.assertNotNull(screen)
            org.junit.Assert.assertNotNull(screen.route)
            org.junit.Assert.assertNotNull(screen.title)
        }
    }
}
