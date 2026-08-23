package com.macrobase.app

import com.macrobase.app.domain.model.CustomFood
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.FoodSource
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Recipe
import com.macrobase.app.domain.model.RecipeIngredient
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.ServingUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Unit test suite for Phase 7: Custom Foods, Recipes, and Historical Snapshot Integrity.
 */
class CustomFoodsAndRecipesUnitTests {

    @Test
    fun customFood_createsAndMapsToDomainFoodAccurately() {
        val customFood = CustomFood(
            id = 10,
            uuid = "custom-uuid-10",
            name = "Homemade Protein Shake",
            brand = "My Kitchen",
            servingSize = 1.0,
            servingUnit = ServingUnit.SERVING,
            nutritionPerServing = Nutrition(
                calories = 250.0,
                proteinGrams = 30.0,
                carbsGrams = 15.0,
                fatGrams = 5.0,
                fiberGrams = 3.0,
                sugarGrams = 2.0,
                sodiumMg = 150.0,
                calciumMg = 200.0,
                ironMg = null // missing unanalyzed nutrient preserved as null
            )
        )

        assertEquals("Homemade Protein Shake", customFood.name)
        assertEquals(250.0, customFood.nutritionPerServing.calories, 0.001)
        assertEquals(30.0, customFood.nutritionPerServing.proteinGrams, 0.001)
        assertEquals(null, customFood.nutritionPerServing.ironMg)

        val domainFood = customFood.toFood()
        assertEquals("Homemade Protein Shake", domainFood.name)
        assertTrue(domainFood.isUserOwned)
        assertEquals(FoodSource.CUSTOM_USER, domainFood.source)
        assertEquals(250.0, domainFood.nutrition.calories, 0.001)
    }

    @Test
    fun recipe_calculatesLiveTotalAndPerServingNutritionCorrectly() {
        val ingredient1 = Food(
            id = 1,
            uuid = "ing-1",
            name = "Rolled Oats",
            nutrition = Nutrition(calories = 389.0, proteinGrams = 16.9, carbsGrams = 66.3, fatGrams = 6.9) // per 100g
        )

        val ingredient2 = Food(
            id = 2,
            uuid = "ing-2",
            name = "Whey Protein Powder",
            nutrition = Nutrition(calories = 400.0, proteinGrams = 80.0, carbsGrams = 6.0, fatGrams = 4.0) // per 100g
        )

        val ingredient3 = Food(
            id = 3,
            uuid = "ing-3",
            name = "Peanut Butter",
            nutrition = Nutrition(calories = 588.0, proteinGrams = 25.0, carbsGrams = 20.0, fatGrams = 50.0) // per 100g
        )

        // Recipe: 200g Oats (2x), 60g Whey (0.6x), 50g PB (0.5x), produces 4 bars
        val recipe = Recipe(
            id = 1,
            uuid = "recipe-uuid-1",
            name = "High Protein Oat Bars",
            servingsProduced = 4,
            ingredients = listOf(
                RecipeIngredient(id = 1, food = ingredient1, serving = Serving(description = "100g", gramWeight = 100.0), quantity = 2.0),
                RecipeIngredient(id = 2, food = ingredient2, serving = Serving(description = "100g", gramWeight = 100.0), quantity = 0.6),
                RecipeIngredient(id = 3, food = ingredient3, serving = Serving(description = "100g", gramWeight = 100.0), quantity = 0.5)
            )
        )

        // Total Recipe Nutrition:
        // Oats: 2 * 389 = 778 kcal, P: 33.8g, C: 132.6g, F: 13.8g
        // Whey: 0.6 * 400 = 240 kcal, P: 48.0g, C: 3.6g, F: 2.4g
        // PB: 0.5 * 588 = 294 kcal, P: 12.5g, C: 10.0g, F: 25.0g
        // Total: 778 + 240 + 294 = 1312 kcal
        // Total P: 33.8 + 48.0 + 12.5 = 94.3g
        // Total C: 132.6 + 3.6 + 10.0 = 146.2g
        // Total F: 13.8 + 2.4 + 25.0 = 41.2g

        val total = recipe.totalNutrition
        assertEquals(1312.0, total.calories, 0.1)
        assertEquals(94.3, total.proteinGrams, 0.1)
        assertEquals(146.2, total.carbsGrams, 0.1)
        assertEquals(41.2, total.fatGrams, 0.1)

        // Per Serving (4 servings):
        // Calories: 1312 / 4 = 328.0 kcal
        // Protein: 94.3 / 4 = 23.575g
        // Carbs: 146.2 / 4 = 36.55g
        // Fat: 41.2 / 4 = 10.3g
        val perServing = recipe.nutritionPerServing
        assertEquals(328.0, perServing.calories, 0.1)
        assertEquals(23.575, perServing.proteinGrams, 0.01)
        assertEquals(36.55, perServing.carbsGrams, 0.01)
        assertEquals(10.3, perServing.fatGrams, 0.01)
    }

    @Test
    fun recipe_updatingServingsCount_dynamicallyRecalculatesPerServingValues() {
        val ingredient = Food(
            id = 1,
            uuid = "ing-1",
            name = "Rolled Oats",
            nutrition = Nutrition(calories = 400.0, proteinGrams = 20.0, carbsGrams = 60.0, fatGrams = 8.0)
        )

        val recipe4 = Recipe(
            id = 1,
            name = "Oat Porridge",
            servingsProduced = 4,
            ingredients = listOf(RecipeIngredient(id = 1, food = ingredient, serving = Serving(description = "100g", gramWeight = 100.0), quantity = 2.0))
        )
        // Total: 800 kcal -> 4 servings = 200 kcal/serving
        assertEquals(200.0, recipe4.nutritionPerServing.calories, 0.1)

        // Change to 2 servings -> 400 kcal/serving
        val recipe2 = recipe4.copy(servingsProduced = 2)
        assertEquals(400.0, recipe2.nutritionPerServing.calories, 0.1)
        assertEquals(20.0, recipe2.nutritionPerServing.proteinGrams, 0.1)
    }

    @Test
    fun historicalSnapshotIntegrity_loggingCustomFood_remainsImmutableWhenCustomFoodIsEditedOrDeleted() {
        // Step 1: User creates custom food X (300 kcal, 20g P)
        val initialCustomFood = CustomFood(
            id = 5,
            uuid = "custom-5-uuid",
            name = "Special Energy Bar",
            servingSize = 1.0,
            servingUnit = ServingUnit.SERVING,
            nutritionPerServing = Nutrition(calories = 300.0, proteinGrams = 20.0, carbsGrams = 30.0, fatGrams = 10.0)
        )

        // Step 2: User logs 1 serving to August 18
        val logDate = LocalDate.of(2026, 8, 18)
        val historicalEntry = DiaryEntry(
            id = 1,
            uuid = "diary-log-1",
            date = logDate,
            mealType = MealType.BREAKFAST,
            food = initialCustomFood.toFood(),
            serving = Serving(description = "1 serving", gramWeight = 100.0),
            quantity = 1.0,
            calculatedNutrition = initialCustomFood.nutritionPerServing,
            loggedAt = Instant.now()
        )

        // Snapshot check:
        assertEquals(300.0, historicalEntry.calculatedNutrition.calories, 0.001)
        assertEquals(20.0, historicalEntry.calculatedNutrition.proteinGrams, 0.001)

        // Step 3: Later, user edits Custom Food X (now changed to 450 kcal, 35g P)
        val modifiedCustomFood = initialCustomFood.copy(
            nutritionPerServing = Nutrition(calories = 450.0, proteinGrams = 35.0, carbsGrams = 40.0, fatGrams = 15.0)
        )

        // Step 4: Verify the historical diary entry from August 18 remains 300 kcal, NOT 450 kcal!
        assertEquals(300.0, historicalEntry.calculatedNutrition.calories, 0.001)
        assertEquals(20.0, historicalEntry.calculatedNutrition.proteinGrams, 0.001)
        assertEquals("Special Energy Bar", historicalEntry.food.name)

        // Step 5: If Custom Food X is deleted, the historical entry remains 100% readable!
        assertNotNull(historicalEntry.food)
        assertEquals(300.0, historicalEntry.calculatedNutrition.calories, 0.001)
    }

    @Test
    fun historicalSnapshotIntegrity_loggingRecipe_remainsImmutableWhenRecipeIsModifiedOrDeleted() {
        // Step 1: User creates recipe with 2 ingredients (Total 500 kcal / 2 servings = 250 kcal/serving)
        val recipe = Recipe(
            id = 10,
            uuid = "recipe-10",
            name = "Morning Protein Smoothie",
            servingsProduced = 2,
            ingredients = listOf(
                RecipeIngredient(
                    id = 1,
                    food = Food(id = 1, uuid = "milk", name = "Milk", nutrition = Nutrition(calories = 200.0, proteinGrams = 16.0, carbsGrams = 24.0, fatGrams = 4.0)),
                    serving = Serving(description = "100g", gramWeight = 100.0),
                    quantity = 1.0
                ),
                RecipeIngredient(
                    id = 2,
                    food = Food(id = 2, uuid = "banana", name = "Banana", nutrition = Nutrition(calories = 300.0, proteinGrams = 4.0, carbsGrams = 70.0, fatGrams = 1.0)),
                    serving = Serving(description = "100g", gramWeight = 100.0),
                    quantity = 1.0
                )
            )
        )

        assertEquals(250.0, recipe.nutritionPerServing.calories, 0.1)

        // Step 2: Log 1 serving of the recipe to August 19
        val loggedRecipeEntry = DiaryEntry(
            id = 99,
            uuid = "diary-recipe-entry",
            date = LocalDate.of(2026, 8, 19),
            mealType = MealType.BREAKFAST,
            food = Food(
                id = 200_000_010L,
                uuid = recipe.uuid,
                name = "Recipe: ${recipe.name}",
                nutrition = recipe.nutritionPerServing
            ),
            serving = Serving(description = "1 serving", gramWeight = 100.0),
            quantity = 1.0,
            calculatedNutrition = recipe.nutritionPerServing,
            loggedAt = Instant.now()
        )

        assertEquals(250.0, loggedRecipeEntry.calculatedNutrition.calories, 0.1)

        // Step 3: Later, user modifies the recipe to 4 servings (so current per-serving becomes 125 kcal)
        val modifiedRecipe = recipe.copy(servingsProduced = 4)
        assertEquals(125.0, modifiedRecipe.nutritionPerServing.calories, 0.1)

        // Step 4: The historical log from August 19 MUST retain the original 250 kcal snapshot!
        assertEquals(250.0, loggedRecipeEntry.calculatedNutrition.calories, 0.1)
        assertEquals(10.0, loggedRecipeEntry.calculatedNutrition.proteinGrams, 0.1) // 20 / 2 = 10g
    }

    @Test
    fun customServingUnit_supportsAllStandardAndCustomLabels() {
        assertEquals(ServingUnit.SERVING, ServingUnit.fromString("serving"))
        assertEquals(ServingUnit.GRAMS, ServingUnit.fromString("g"))
        assertEquals(ServingUnit.KILOGRAMS, ServingUnit.fromString("kg"))
        assertEquals(ServingUnit.MILLILITERS, ServingUnit.fromString("ml"))
        assertEquals(ServingUnit.LITERS, ServingUnit.fromString("L"))
        assertEquals(ServingUnit.CUP, ServingUnit.fromString("cup"))
        assertEquals(ServingUnit.TABLESPOON, ServingUnit.fromString("tablespoon"))
        assertEquals(ServingUnit.TEASPOON, ServingUnit.fromString("teaspoon"))
        assertEquals(ServingUnit.PIECE, ServingUnit.fromString("piece"))
        assertEquals(ServingUnit.SLICE, ServingUnit.fromString("slice"))
        assertEquals(ServingUnit.BOWL, ServingUnit.fromString("bowl"))
        assertEquals(ServingUnit.GLASS, ServingUnit.fromString("glass"))
        assertEquals(ServingUnit.OUNCE, ServingUnit.fromString("ounce"))
        assertEquals(ServingUnit.FLUID_OUNCE, ServingUnit.fromString("fluid ounce"))
        assertEquals(ServingUnit.CUSTOM, ServingUnit.fromString("roti"))
        assertEquals(ServingUnit.CUSTOM, ServingUnit.fromString("chapati"))
        assertEquals(ServingUnit.CUSTOM, ServingUnit.fromString("scoop"))
    }

    @Test
    fun customServingUnit_preservesCustomLabelAndCalculatesScalingWithoutArbitraryGrams() {
        val rotiFood = CustomFood(
            id = 15,
            uuid = "roti-uuid",
            name = "Handmade Roti",
            servingSize = 1.0,
            servingUnit = ServingUnit.CUSTOM,
            customUnitName = "roti",
            nutritionPerServing = Nutrition(calories = 120.0, proteinGrams = 3.5, carbsGrams = 22.0, fatGrams = 1.5)
        )

        val domainFood = rotiFood.toFood()
        val serving = domainFood.servings.first()

        assertEquals("1.0 roti", serving.description)
        assertEquals("roti", serving.displayUnitName)
        assertEquals(ServingUnit.CUSTOM, serving.unit)
        assertEquals(0.0, serving.gramWeight, 0.001) // Not pretending to have an arbitrary gram weight

        // Test scaling calculation for 1.5 roti
        val calculateUseCase = com.macrobase.app.domain.usecase.CalculateNutritionForServingUseCase()
        val scaled1_5 = calculateUseCase(domainFood, serving, 1.5)
        assertEquals(180.0, scaled1_5.calories, 0.01) // 120 * 1.5 = 180
        assertEquals(5.25, scaled1_5.proteinGrams, 0.01) // 3.5 * 1.5 = 5.25
        assertEquals(33.0, scaled1_5.carbsGrams, 0.01) // 22 * 1.5 = 33
        assertEquals(2.25, scaled1_5.fatGrams, 0.01) // 1.5 * 1.5 = 2.25

        // Test scaling for 0.5 roti
        val scaled0_5 = calculateUseCase(domainFood, serving, 0.5)
        assertEquals(60.0, scaled0_5.calories, 0.01)
        assertEquals(1.75, scaled0_5.proteinGrams, 0.01)

        // Test scaling for 2 roti
        val scaled2 = calculateUseCase(domainFood, serving, 2.0)
        assertEquals(240.0, scaled2.calories, 0.01)
        assertEquals(7.0, scaled2.proteinGrams, 0.01)
    }
}
