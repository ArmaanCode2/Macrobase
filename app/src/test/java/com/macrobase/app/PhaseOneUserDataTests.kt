package com.macrobase.app

import androidx.room.Room
import com.macrobase.app.data.database.UserDatabase
import com.macrobase.app.data.database.entity.RecipeEntity
import com.macrobase.app.data.provider.FoodDataProvider
import com.macrobase.app.data.repository.FoodRepositoryImpl
import com.macrobase.app.data.repository.RecipeIngredientsJson
import com.macrobase.app.data.repository.RecipeRepositoryImpl
import com.macrobase.app.domain.model.CustomFood
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Recipe
import com.macrobase.app.domain.model.RecipeIngredient
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.ServingUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * BUG-002 / BUG-003 / BUG-004 / BUG-013 / BUG-014 against a real Room database,
 * including rows written by earlier app versions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhaseOneUserDataTests {

    private lateinit var db: UserDatabase
    private lateinit var foodRepository: FoodRepositoryImpl
    private lateinit var recipeRepository: RecipeRepositoryImpl

    private val noCatalog = object : FoodDataProvider {
        override val providerId = "test"
        override val displayName = "test"
        override val isLocal = true
        override suspend fun searchFoods(query: String, limit: Int) = emptyList<Food>()
        override suspend fun getFoodById(id: Long): Food? = null
        override suspend fun getFoodByBarcode(barcode: String): Food? = null
        override suspend fun getFoodServings(foodId: Long) = emptyList<Serving>()
    }

    private val chapati = Food(
        id = 1896880878330341L,
        uuid = "chapati",
        sourceId = "INDB:0100",
        name = "Chapati/Roti",
        nutrition = Nutrition(calories = 202.31, proteinGrams = 5.88, carbsGrams = 35.65, fatGrams = 3.56, fiberGrams = 4.9),
        servings = listOf(
            Serving(id = 1, description = "100 g", unit = ServingUnit.GRAMS, quantity = 1.0, gramWeight = 100.0, isDefault = true),
            Serving(id = 2, description = "1 chapati", unit = ServingUnit.CUSTOM, quantity = 1.0, gramWeight = 36.0, sequence = 1)
        )
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), UserDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        foodRepository = FoodRepositoryImpl(noCatalog, db.customFoodDao())
        recipeRepository = RecipeRepositoryImpl(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun customFood(
        name: String,
        servingSize: Double,
        unit: ServingUnit,
        calories: Double,
        potassium: Double? = null
    ) = CustomFood(
        uuid = "uuid-$name",
        name = name,
        servingSize = servingSize,
        servingUnit = unit,
        nutritionPerServing = Nutrition(calories = calories, proteinGrams = 8.0, carbsGrams = 12.0, fatGrams = 5.0, potassiumMg = potassium),
        createdAt = Instant.ofEpochMilli(1_700_000_000_000L)
    )

    // BUG-003
    @Test
    fun customMlFood_perMlServing_scalesByMillilitres() = runBlocking {
        val id = foodRepository.saveCustomFood(customFood("Milk", 250.0, ServingUnit.MILLILITERS, 150.0))
        val milk = checkNotNull(foodRepository.getFoodById(id))
        val perMl = milk.servings.first { it.description == "1 ml" }

        assertEquals(120.0, milk.nutritionFor(perMl, 200.0).calories, 0.001)
        assertEquals(150.0, milk.nutritionFor(milk.defaultServing!!, 1.0).calories, 0.001)
    }

    @Test
    fun customLitreFood_perMlServing_scalesByMillilitres() = runBlocking {
        val id = foodRepository.saveCustomFood(customFood("Juice", 1.0, ServingUnit.LITERS, 400.0))
        val juice = checkNotNull(foodRepository.getFoodById(id))
        val perMl = juice.servings.first { it.description == "1 ml" }

        assertEquals(100.0, juice.nutritionFor(perMl, 250.0).calories, 0.001)
    }

    @Test
    fun customGramFood_perGramServing_isUnchanged() = runBlocking {
        val id = foodRepository.saveCustomFood(customFood("Cooked rice", 150.0, ServingUnit.GRAMS, 199.6))
        val rice = checkNotNull(foodRepository.getFoodById(id))
        val perGram = rice.servings.first { it.description == "1 g" }

        assertEquals(199.6, rice.nutritionFor(perGram, 150.0).calories, 0.001)
        assertEquals(listOf("150 g", "1 g"), rice.servings.map { it.description })
    }

    // BUG-004
    @Test
    fun customFood_editRoundTrip_keepsServingSizeExactCaloriesAndHiddenFields() = runBlocking {
        val id = foodRepository.saveCustomFood(customFood("Cooked rice", 150.0, ServingUnit.GRAMS, 199.6, potassium = 35.0))
        val before = db.customFoodDao().getCustomFoodById(id - FoodRepositoryImpl.CUSTOM_FOOD_ID_OFFSET)

        val loaded = checkNotNull(foodRepository.getCustomFood(id))
        assertEquals(150.0, loaded.servingSize, 0.0)
        assertEquals(199.6, loaded.nutritionPerServing.calories, 0.0)
        assertEquals(35.0, loaded.nutritionPerServing.potassiumMg!!, 0.0)

        // Saving what the edit form loaded must leave the stored row untouched
        foodRepository.saveCustomFood(loaded)
        val after = db.customFoodDao().getCustomFoodById(id - FoodRepositoryImpl.CUSTOM_FOOD_ID_OFFSET)
        assertEquals(before, after)
    }

    // BUG-002, BUG-013
    @Test
    fun recipe_savedWithIngredients_reloadsThemAndLogsRealNutrition() = runBlocking {
        val shakeId = foodRepository.saveCustomFood(customFood("Shake", 1.0, ServingUnit.SERVING, 120.0))
        val shake = checkNotNull(foodRepository.getFoodById(shakeId))
        val recipe = Recipe(
            name = "Roti and shake",
            servingsProduced = 2,
            ingredients = listOf(
                RecipeIngredient(food = chapati, serving = chapati.servings[1], quantity = 4.0),
                RecipeIngredient(food = shake, serving = shake.defaultServing!!, quantity = 1.5)
            )
        )
        // 4 x 36 g chapati = 291.3 kcal, 1.5 shakes = 180 kcal, over 2 servings
        val expectedPerServing = (202.31 * 1.44 + 180.0) / 2.0
        assertEquals(expectedPerServing, recipe.nutritionPerServing.calories, 0.01)

        recipeRepository.createRecipe(recipe)
        val reloaded = recipeRepository.getRecipes().single()

        assertEquals(2, reloaded.ingredients.size)
        assertEquals(listOf("Chapati/Roti", "Shake"), reloaded.ingredients.map { it.food.name })
        assertEquals(expectedPerServing, reloaded.nutritionPerServing.calories, 0.01)
        assertEquals(expectedPerServing, reloaded.toFood().nutrition.calories, 0.01)
        assertEquals(4.9 * 1.44 / 2.0, reloaded.nutritionPerServing.fiberGrams!!, 0.01)
    }

    @Test
    fun recipeIngredient_customGramFood_usesItsOwnPortionNotPer100g() {
        val rice = customFood("Cooked rice", 150.0, ServingUnit.GRAMS, 199.6).copy(id = 100_000_001L).toFood()
        val ingredient = RecipeIngredient(food = rice, serving = rice.defaultServing!!, quantity = 1.0)
        assertEquals(199.6, ingredient.nutrition.calories, 0.001)
    }

    // BUG-014
    @Test
    fun recipe_update_replacesTheSameRowAndKeepsIngredients() = runBlocking {
        val id = recipeRepository.createRecipe(
            Recipe(name = "Dal", servingsProduced = 4, ingredients = listOf(RecipeIngredient(food = chapati, serving = chapati.servings[0], quantity = 3.0)))
        )
        val loaded = checkNotNull(recipeRepository.getRecipeById(id))
        assertEquals(1, loaded.ingredients.size)

        recipeRepository.updateRecipe(loaded.copy(name = "Dal tadka"))
        val all = recipeRepository.getRecipes()
        assertEquals(1, all.size)
        assertEquals("Dal tadka", all.single().name)
        assertEquals(loaded.nutritionPerServing.calories, all.single().nutritionPerServing.calories, 0.001)
    }

    // Recipes saved by earlier versions: ingredientsJson "[]" plus correct per-serving columns
    @Test
    fun legacyRecipe_withoutStoredIngredients_logsItsStoredPerServingValues() = runBlocking {
        db.recipeDao().insertRecipe(
            RecipeEntity(
                uuid = "legacy", name = "Old curry", servingsProduced = 4, ingredientsJson = "[]",
                caloriesPerServing = 400.0, proteinPerServing = 20.0, carbsPerServing = 30.0, fatPerServing = 18.0
            )
        )
        val legacy = recipeRepository.getRecipes().single()

        assertEquals(0, legacy.ingredients.size)
        assertEquals(400.0, legacy.nutritionPerServing.calories, 0.0)
        assertEquals(1600.0, legacy.totalNutrition.calories, 0.0)
        assertEquals(400.0, legacy.toFood().nutrition.calories, 0.0)
        assertNull("Unrecorded nutrients stay unknown", legacy.nutritionPerServing.fiberGrams)
    }

    @Test
    fun recipe_withUnreadableIngredientsJson_fallsBackToStoredValues() = runBlocking {
        db.recipeDao().insertRecipe(
            RecipeEntity(
                uuid = "broken", name = "Broken", servingsProduced = 2, ingredientsJson = "[{\"foodId\":",
                caloriesPerServing = 250.0, proteinPerServing = 10.0, carbsPerServing = 20.0, fatPerServing = 9.0
            )
        )
        assertEquals(250.0, recipeRepository.getRecipes().single().nutritionPerServing.calories, 0.0)
    }

    // Diary rows written by earlier versions: "150.0 g" / "1.0 g" / weightless "250.0 ml" labels
    @Test
    fun legacyDiaryServings_resolveToTheMatchingCustomServing() = runBlocking {
        val rice = checkNotNull(foodRepository.getFoodById(foodRepository.saveCustomFood(customFood("Cooked rice", 150.0, ServingUnit.GRAMS, 199.6))))
        val milk = checkNotNull(foodRepository.getFoodById(foodRepository.saveCustomFood(customFood("Milk", 250.0, ServingUnit.MILLILITERS, 150.0))))

        val riceBase = rice.resolveLoggedServing(Serving(description = "150.0 g", gramWeight = 150.0))
        assertEquals(rice.servings[0], riceBase)
        assertEquals(199.6, rice.nutritionFor(riceBase, 1.0).calories, 0.001)

        val ricePerGram = rice.resolveLoggedServing(Serving(description = "1.0 g", gramWeight = 1.0))
        assertEquals(rice.servings[1], ricePerGram)
        assertEquals(199.6, rice.nutritionFor(ricePerGram, 150.0).calories, 0.001)

        val milkBase = milk.resolveLoggedServing(Serving(description = "250.0 ml", gramWeight = 0.0))
        assertEquals("Must reuse the existing serving, not add a duplicate", milk.servings[0], milkBase)
        assertEquals(300.0, milk.nutritionFor(milkBase, 2.0).calories, 0.001)
    }

    @Test
    fun backup_largeIngredientList_isNotTruncatedOnRestore() {
        val ingredients = (1..200).map { RecipeIngredient(food = chapati.copy(name = "Ingredient $it"), serving = chapati.servings[1], quantity = 1.0) }
        val json = RecipeIngredientsJson.encode(ingredients)
        assertTrue("Fixture must exceed the 50,000 character string cap", json.length > 50_000)

        val dto = com.macrobase.app.domain.model.RecipeBackupDto(
            uuid = "big", name = "Big thali", servingsProduced = 8, ingredientsJson = json,
            caloriesPerServing = 1.0, proteinPerServing = 1.0, carbsPerServing = 1.0, fatPerServing = 1.0, createdAt = 1L
        )
        val restored = com.macrobase.app.data.portability.BackupJsonSerializer.parseRecipes(
            com.macrobase.app.data.portability.BackupJsonSerializer.serializeRecipes(listOf(dto))
        ).single()

        assertEquals(json, restored.ingredientsJson)
        assertEquals(200, RecipeIngredientsJson.decode(restored.ingredientsJson)!!.size)
    }

    @Test
    fun savedIngredient_quantityChangeScalesItsSnapshot() {
        val json = RecipeIngredientsJson.encode(listOf(RecipeIngredient(food = chapati, serving = chapati.servings[1], quantity = 2.0)))
        val saved = checkNotNull(RecipeIngredientsJson.decode(json)).single()
        assertEquals(202.31 * 0.72, saved.nutrition.calories, 0.01)
        assertEquals(202.31 * 1.44, saved.copy(quantity = 4.0).nutrition.calories, 0.01)
    }

    @Test
    fun ingredientsJson_roundTripsTextAndMissingNutrients() {
        val odd = chapati.copy(name = "Roti \"tandoori\"\nstyle \\ 50%")
        val json = RecipeIngredientsJson.encode(listOf(RecipeIngredient(food = odd, serving = odd.servings[1], quantity = 2.5)))
        val decoded = checkNotNull(RecipeIngredientsJson.decode(json)).single()

        assertEquals(odd.name, decoded.food.name)
        assertEquals(2.5, decoded.quantity, 0.0)
        assertEquals("1 chapati", decoded.serving.description)
        assertEquals(36.0, decoded.serving.gramWeight, 0.0)
        assertNotNull(decoded.nutrition.fiberGrams)
        assertNull(decoded.nutrition.sodiumMg)
        assertNull(RecipeIngredientsJson.decode("not json"))
        assertEquals(0, RecipeIngredientsJson.decode("[]")!!.size)
    }
}
