package com.macrobase.app

import androidx.lifecycle.SavedStateHandle
import com.macrobase.app.core.config.DatabaseConfig
import com.macrobase.app.data.repository.RecipeIngredientsJson
import com.macrobase.app.data.repository.basket.InMemoryBasketRepository
import com.macrobase.app.domain.model.CustomFood
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.FoodSource
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Recipe
import com.macrobase.app.domain.model.RecipeIngredient
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.ServingUnit
import com.macrobase.app.domain.repository.DiaryRepository
import com.macrobase.app.domain.repository.FoodRepository
import com.macrobase.app.domain.repository.RecipeRepository
import com.macrobase.app.domain.usecase.basket.AddFoodToBasketUseCase
import com.macrobase.app.domain.usecase.basket.CommitSingleBasketItemUseCase
import com.macrobase.app.domain.usecase.basket.RemoveBasketItemUseCase
import com.macrobase.app.feature.recipes.RecipeForm
import com.macrobase.app.feature.recipes.RecipesViewModel
import com.macrobase.app.feature.recipes.ingredientServings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate

private val rice = Food(
    id = 1_896_880_878_330_341L,
    uuid = "rice",
    name = "Rice",
    nutrition = Nutrition(calories = 130.0, proteinGrams = 2.7, carbsGrams = 28.0, fatGrams = 0.3),
    servings = listOf(
        Serving(description = "100 g", gramWeight = 100.0, isDefault = true),
        Serving(description = "1 cup", unit = ServingUnit.CUP, gramWeight = 158.0)
    )
)

private val oil = Food(
    id = 100_000_004L,
    uuid = "oil",
    source = FoodSource.CUSTOM_USER,
    name = "Oil",
    isUserOwned = true,
    nutrition = Nutrition(calories = 120.0, proteinGrams = 0.0, carbsGrams = 0.0, fatGrams = 13.6),
    servings = listOf(
        Serving(description = "15 ml", unit = ServingUnit.MILLILITERS, gramWeight = 15.0, isDefault = true),
        Serving(description = "1 ml", unit = ServingUnit.MILLILITERS, gramWeight = 1.0)
    )
)

private val dalIngredient = RecipeIngredient(food = rice, serving = rice.servings.first(), quantity = 2.0)

/** BUG-018: a blank, 0 or fractional servings count saved as 1, so every serving was too big. */
class RecipeServingsTests {

    private val dal = RecipeForm(name = "Dal", servingsProduced = "4", ingredients = listOf(dalIngredient))

    @Test
    fun aWholeNumberOfServingsCanBeSaved() {
        assertEquals(4, dal.servingsCount)
        assertTrue(dal.canSave)
    }

    @Test
    fun blankZeroOrFractionalServingsCannotBeSaved() {
        for (typed in listOf("", " ", "0", "2.5", "-2", "two")) {
            val form = dal.copy(servingsProduced = typed)
            assertNull("\"$typed\"", form.servingsCount)
            assertFalse("\"$typed\"", form.canSave)
        }
    }
}

/**
 * BUG-019: Add Ingredient took the food's "100 g" with no choice, so 250 typed for grams was 25 kg.
 * BUG-026: its search ran on every keystroke with no cancel or catch. The dialog now lives in the
 * ViewModel, which rotation keeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IngredientPickerTests {

    private class Foods(private val search: suspend (String) -> List<Food> = { emptyList() }) : FoodRepository {
        override suspend fun searchFoods(query: String, limit: Int) = search(query)
        override suspend fun getFoodById(id: Long): Food? = null
        override suspend fun getFoodByBarcode(barcode: String): Food? = null
        override suspend fun getFoodServings(foodId: Long) = emptyList<Serving>()
        override suspend fun getRecentFoods(limit: Int) = emptyList<Food>()
        override suspend fun getFavoriteFoods() = emptyList<Food>()
        override fun observeCustomFoods() = flowOf(emptyList<Food>())
        override suspend fun getCustomFoods() = emptyList<Food>()
        override suspend fun saveCustomFood(food: CustomFood) = 0L
        override suspend fun deleteCustomFood(id: Long) {}
    }

    private class Recipes : RecipeRepository {
        override suspend fun createRecipe(recipe: Recipe) = 1L
        override suspend fun updateRecipe(recipe: Recipe) {}
        override suspend fun deleteRecipe(recipeId: Long) {}
        override fun observeRecipes() = flowOf(emptyList<Recipe>())
        override suspend fun getRecipes() = emptyList<Recipe>()
        override suspend fun getRecipeById(recipeId: Long): Recipe? = null
        override fun encodeIngredients(ingredients: List<RecipeIngredient>) = RecipeIngredientsJson.encode(ingredients)
        override fun decodeIngredients(text: String) = RecipeIngredientsJson.decode(text) ?: emptyList()
    }

    private class NoDiary : DiaryRepository {
        override suspend fun addEntries(entries: List<DiaryEntry>) {}
        override suspend fun addEntry(entry: DiaryEntry) = 0L
        override suspend fun getDiaryForDate(date: LocalDate) = com.macrobase.app.domain.model.DailyNutritionSummary(date = date)
        override fun observeDiaryForDate(date: LocalDate) = flowOf(com.macrobase.app.domain.model.DailyNutritionSummary(date = date))
        override suspend fun getEntryById(entryId: Long): DiaryEntry? = null
        override suspend fun updateEntry(entry: DiaryEntry) {}
        override suspend fun deleteEntry(entryId: Long) {}
        override suspend fun getMonthlyAdherence(year: Int, month: Int) = emptyList<com.macrobase.app.domain.model.CalendarDaySummary>()
        override fun observeMonthlyAdherence(year: Int, month: Int) = flowOf(emptyList<com.macrobase.app.domain.model.CalendarDaySummary>())
        override fun observeNutritionAggregations(startDate: LocalDate, endDate: LocalDate) = flowOf(emptyList<com.macrobase.app.domain.repository.DailyMacroAggregation>())
        override suspend fun getNutritionAggregations(startDate: LocalDate, endDate: LocalDate) = emptyList<com.macrobase.app.domain.repository.DailyMacroAggregation>()
    }

    private val dispatcher = StandardTestDispatcher()
    private val basket = InMemoryBasketRepository()

    private fun recipes(foods: FoodRepository = Foods()) = RecipesViewModel(
        Recipes(),
        foods,
        AddFoodToBasketUseCase(basket),
        CommitSingleBasketItemUseCase(basket, NoDiary()),
        RemoveBasketItemUseCase(basket),
        SavedStateHandle()
    )

    private fun source(path: String): String =
        listOf(File("src/main/java/com/macrobase/app/$path"), File("app/src/main/java/com/macrobase/app/$path"))
            .first { it.isFile }.readText()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aCatalogFoodOffersItsOwnServingsAndGrams() {
        assertEquals(listOf("100 g", "1 cup", "1 g"), ingredientServings(rice).map { it.description })
    }

    @Test
    fun aFoodWithASingleUnitServingGetsNoSecondOne() {
        assertEquals(listOf("15 ml", "1 ml"), ingredientServings(oil).map { it.description })
    }

    // "1 leaf" weighs 1 g but does not read as grams
    @Test
    fun aOneGramServingOfAnotherUnitStillGetsAGramServing() {
        val mint = rice.copy(
            name = "Mint",
            servings = listOf(
                Serving(description = "100 g", gramWeight = 100.0, isDefault = true),
                Serving(description = "1 leaf", unit = ServingUnit.PIECE, gramWeight = 1.0)
            )
        )

        assertEquals(listOf("100 g", "1 leaf", "1 g"), ingredientServings(mint).map { it.description })
    }

    @Test
    fun aFoodWithNoWeightGetsNoGramServing() {
        val roti = oil.copy(name = "Roti", servings = listOf(Serving(description = "1 piece", unit = ServingUnit.PIECE, isDefault = true)))

        assertEquals(listOf("1 piece"), ingredientServings(roti).map { it.description })
    }

    @Test
    fun twoHundredFiftyGramsOfRiceIsTwoAndAHalfTimesItsPer100G() = runTest(dispatcher) {
        val vm = recipes()
        vm.openIngredientPicker()
        vm.selectIngredientFood(rice)
        vm.selectIngredientServing(vm.ingredientPicker.value!!.servings.single { it.description == "1 g" })
        vm.onIngredientQuantity("250")
        assertEquals(250.0, vm.ingredientPicker.value!!.totalGrams!!, 0.001)

        vm.addPickedIngredient()

        assertEquals(130.0 * 2.5, vm.form.value.ingredients.single().nutrition.calories, 0.001)
        assertNull("the dialog closes", vm.ingredientPicker.value)
    }

    @Test
    fun theDefaultServingShowsHowManyGramsTheAmountIs() = runTest(dispatcher) {
        val vm = recipes()
        vm.openIngredientPicker()
        vm.selectIngredientFood(rice)
        vm.onIngredientQuantity("250")

        val picker = vm.ingredientPicker.value!!
        assertEquals("100 g", picker.serving!!.description)
        assertEquals(25_000.0, picker.totalGrams!!, 0.001)
    }

    @Test
    fun anOlderQueryFinishingLastKeepsTheNewerResults() = runTest(dispatcher) {
        val slow = CompletableDeferred<List<Food>>()
        val vm = recipes(Foods { query -> if (query == "ri") slow.await() else listOf(rice) })
        vm.openIngredientPicker()

        vm.onIngredientQuery("ri")
        advanceTimeBy(DatabaseConfig.SEARCH_DEBOUNCE_MILLIS + 1) // "ri" is running
        vm.onIngredientQuery("rice")
        advanceUntilIdle()
        slow.complete(listOf(oil))
        advanceUntilIdle()

        assertEquals(listOf("Rice"), vm.ingredientPicker.value!!.results.map { it.name })
    }

    @Test
    fun aFailedSearchSaysSoAndKeepsTheRecipe() = runTest(dispatcher) {
        val vm = recipes(Foods { throw IllegalStateException("catalog could not be opened") })
        vm.updateForm { it.copy(name = "Dal") }
        vm.openIngredientPicker()

        vm.onIngredientQuery("rice")
        advanceUntilIdle()

        val picker = vm.ingredientPicker.value!!
        assertTrue(picker.results.isEmpty())
        assertEquals("Failed to perform search.", picker.searchError)
        assertEquals("Dal", vm.form.value.name)
    }

    @Test
    fun aBlankQueryClearsTheList() = runTest(dispatcher) {
        val vm = recipes(Foods { listOf(rice) })
        vm.openIngredientPicker()
        vm.onIngredientQuery("rice")
        advanceUntilIdle()

        vm.onIngredientQuery("  ")
        advanceUntilIdle()

        assertTrue(vm.ingredientPicker.value!!.results.isEmpty())
    }

    // Rotation keeps the ViewModel and loses the composition
    @Test
    fun theDialogKeepsNothingInTheComposition() {
        val screen = source("feature/recipes/RecipesScreen.kt")

        assertFalse(screen.contains("var selectedFood by remember"))
        assertFalse(screen.contains("viewModel.viewModelScope.launch"))
    }
}
