package com.macrobase.app

import androidx.activity.ComponentActivity
import androidx.lifecycle.SavedStateHandle
import com.macrobase.app.core.di.appModules
import com.macrobase.app.data.repository.RecipeIngredientsJson
import com.macrobase.app.data.repository.basket.InMemoryBasketRepository
import com.macrobase.app.domain.model.CustomFood
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Recipe
import com.macrobase.app.domain.model.RecipeIngredient
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.ServingUnit
import com.macrobase.app.domain.model.scanner.NutritionBasis
import com.macrobase.app.domain.model.scanner.NutritionLabelDraft
import com.macrobase.app.domain.model.scanner.ParsedNutrientValue
import com.macrobase.app.domain.repository.DiaryRepository
import com.macrobase.app.domain.repository.FoodRepository
import com.macrobase.app.domain.repository.RecipeRepository
import com.macrobase.app.domain.usecase.basket.AddFoodToBasketUseCase
import com.macrobase.app.domain.usecase.basket.CommitSingleBasketItemUseCase
import com.macrobase.app.domain.usecase.basket.RemoveBasketItemUseCase
import com.macrobase.app.feature.customfoods.CustomFoodsViewModel
import com.macrobase.app.feature.recipes.RecipesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.ext.android.getViewModel
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * BUG-002: the custom food and recipe forms live in their ViewModel and saved state. A ViewModel
 * outlives rotation and a trip to the scanner; building a new one on the same SavedStateHandle is
 * what happens after the app was closed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FormStateTests {

    private class Foods(private val stored: CustomFood? = null) : FoodRepository {
        override suspend fun searchFoods(query: String, limit: Int) = emptyList<Food>()
        override suspend fun getFoodById(id: Long): Food? = null
        override suspend fun getFoodByBarcode(barcode: String): Food? = null
        override suspend fun getFoodServings(foodId: Long) = emptyList<Serving>()
        override suspend fun getRecentFoods(limit: Int) = emptyList<Food>()
        override suspend fun getFavoriteFoods() = emptyList<Food>()
        override fun observeCustomFoods() = flowOf(emptyList<Food>())
        override suspend fun getCustomFoods() = emptyList<Food>()
        override suspend fun getCustomFood(id: Long) = stored?.takeIf { it.id == id }
        override suspend fun saveCustomFood(food: CustomFood) = 0L
        override suspend fun deleteCustomFood(id: Long) {}
    }

    private class Recipes(private val stored: Recipe? = null) : RecipeRepository {
        override suspend fun createRecipe(recipe: Recipe) = 1L
        override suspend fun updateRecipe(recipe: Recipe) {}
        override suspend fun deleteRecipe(recipeId: Long) {}
        override fun observeRecipes() = flowOf(emptyList<Recipe>())
        override suspend fun getRecipes() = listOfNotNull(stored)
        override suspend fun getRecipeById(recipeId: Long) = stored?.takeIf { it.id == recipeId }
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

    private val basket = InMemoryBasketRepository()
    private val rice = Food(
        id = 1_896_880_878_330_341L,
        uuid = "rice",
        name = "Rice",
        nutrition = Nutrition(calories = 130.0, proteinGrams = 2.7, carbsGrams = 28.0, fatGrams = 0.3),
        servings = listOf(Serving(description = "100 g", gramWeight = 100.0, isDefault = true))
    )
    private val ingredient = RecipeIngredient(food = rice, serving = rice.servings.single(), quantity = 2.0)
    private val storedFood = CustomFood(
        id = 7L,
        uuid = "stored",
        name = "Protein bar",
        servingSize = 1.0,
        servingUnit = ServingUnit.SERVING,
        nutritionPerServing = Nutrition(calories = 200.0, proteinGrams = 20.0, carbsGrams = 20.0, fatGrams = 8.0)
    )
    private val storedRecipe = Recipe(id = 3L, name = "Dal", servingsProduced = 2, ingredients = listOf(ingredient))

    private fun scanned(calories: Double) = NutritionLabelDraft(
        foodName = null,
        detectedBasis = NutritionBasis.PER_100_G,
        calories = ParsedNutrientValue(calories, "kcal")
    )

    private fun customFoods(state: SavedStateHandle = SavedStateHandle(), stored: CustomFood? = null) =
        CustomFoodsViewModel(Foods(stored), state)

    private fun recipes(state: SavedStateHandle = SavedStateHandle(), stored: Recipe? = null) = RecipesViewModel(
        Recipes(stored),
        Foods(),
        AddFoodToBasketUseCase(basket),
        CommitSingleBasketItemUseCase(basket, NoDiary()),
        RemoveBasketItemUseCase(basket),
        state
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aNameTypedBeforeScanningStaysAfterTheScan() {
        val vm = customFoods()
        vm.updateForm { it.copy(name = "Oat bar", protein = "9") }

        vm.setScannedDraft(scanned(420.0))

        assertEquals("Oat bar", vm.form.value.name)
        assertEquals("420", vm.form.value.calories)
        assertEquals("9", vm.form.value.protein)
    }

    @Test
    fun theReviewBannerStaysUntilItIsDismissed() {
        val vm = customFoods()

        vm.setScannedDraft(scanned(420.0))
        vm.updateForm { it.copy(name = "Oat bar") }
        assertNotNull(vm.scannedDraft.value)

        vm.clearScannedDraft()
        assertNull(vm.scannedDraft.value)
        assertEquals("420", vm.form.value.calories)
    }

    @Test
    fun aCustomFoodFormComesBackAfterTheAppWasClosed() {
        val saved = SavedStateHandle()
        customFoods(saved).updateForm { it.copy(name = "Oat bar", servingUnit = ServingUnit.GRAMS) }

        val reopened = customFoods(saved)

        assertEquals("Oat bar", reopened.form.value.name)
        assertEquals(ServingUnit.GRAMS, reopened.form.value.servingUnit)
    }

    @Test
    fun editingAFoodFillsTheFormOnceAndKeepsWhatWasTyped() {
        val saved = SavedStateHandle()
        val vm = customFoods(saved, storedFood)
        vm.openForEdit(storedFood.id)
        assertEquals("Protein bar", vm.form.value.name)
        assertEquals("200", vm.form.value.calories)

        vm.updateForm { it.copy(name = "Renamed bar") }
        vm.openForEdit(storedFood.id)
        assertEquals("Renamed bar", vm.form.value.name)

        val reopened = customFoods(saved, storedFood)
        reopened.openForEdit(storedFood.id)
        assertEquals("Renamed bar", reopened.form.value.name)
        assertEquals(storedFood.id, reopened.existingFood.value?.id)
    }

    @Test
    fun leavingACustomFoodFormAsksOnlyWhenSomethingWouldBeLost() {
        val new = customFoods()
        assertFalse(new.hasUnsavedChanges())
        new.updateForm { it.copy(name = "x") }
        assertTrue(new.hasUnsavedChanges())
        new.updateForm { it.copy(name = "") }
        assertFalse(new.hasUnsavedChanges())

        val edit = customFoods(stored = storedFood)
        edit.openForEdit(storedFood.id)
        assertFalse(edit.hasUnsavedChanges())
        edit.updateForm { it.copy(calories = "210") }
        assertTrue(edit.hasUnsavedChanges())
    }

    @Test
    fun aScanCountsAsSomethingToLose() {
        val vm = customFoods()

        vm.setScannedDraft(scanned(420.0))

        assertTrue(vm.hasUnsavedChanges())
    }

    @Test
    fun aHalfBuiltRecipeComesBackWithItsIngredients() {
        val saved = SavedStateHandle()
        recipes(saved).updateForm { it.copy(name = "Dal rice", servingsProduced = "6", ingredients = listOf(ingredient)) }

        val reopened = recipes(saved).form.value

        assertEquals("Dal rice", reopened.name)
        assertEquals("6", reopened.servingsProduced)
        assertEquals(listOf("Rice"), reopened.ingredients.map { it.food.name })
        assertEquals(260.0, reopened.ingredients.single().nutrition.calories, 0.001)
    }

    @Test
    fun editingARecipeFillsTheFormOnceAndKeepsWhatWasTyped() {
        val saved = SavedStateHandle()
        val vm = recipes(saved, storedRecipe)
        vm.openForEdit(storedRecipe.id)
        assertEquals("Dal", vm.form.value.name)
        assertEquals("2", vm.form.value.servingsProduced)
        assertFalse(vm.hasUnsavedChanges())

        vm.updateForm { it.copy(name = "Dal tadka") }
        assertTrue(vm.hasUnsavedChanges())

        val reopened = recipes(saved, storedRecipe)
        reopened.openForEdit(storedRecipe.id)
        assertEquals("Dal tadka", reopened.form.value.name)
    }

    @Test
    fun leavingARecipeFormAsksOnlyWhenSomethingWouldBeLost() {
        val vm = recipes()
        assertFalse(vm.hasUnsavedChanges())

        vm.updateForm { it.copy(ingredients = listOf(ingredient)) }
        assertTrue(vm.hasUnsavedChanges())

        vm.updateForm { it.copy(ingredients = emptyList()) }
        assertFalse(vm.hasUnsavedChanges())
    }
}

/**
 * The forms take a SavedStateHandle, which Koin can only supply through a real ViewModel owner. A
 * mistake here would crash the Foods tab on the phone and nowhere else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FormViewModelWiringTests {

    @Before
    fun setUp() {
        startKoin {
            androidContext(RuntimeEnvironment.getApplication())
            modules(appModules)
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun koinBuildsTheFormViewModelsForAScreen() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        val customFoods = activity.getViewModel<CustomFoodsViewModel>()
        val recipes = activity.getViewModel<RecipesViewModel>()

        customFoods.updateForm { it.copy(name = "Oat bar") }
        recipes.updateForm { it.copy(name = "Dal rice") }
        assertEquals("Oat bar", customFoods.form.value.name)
        assertEquals("Dal rice", recipes.form.value.name)
    }
}
