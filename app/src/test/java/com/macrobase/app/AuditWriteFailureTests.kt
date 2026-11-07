package com.macrobase.app

import androidx.lifecycle.SavedStateHandle
import com.macrobase.app.core.util.WRITE_FAILED_MESSAGE
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
import com.macrobase.app.domain.model.UserPreferences
import com.macrobase.app.domain.model.WaterEntry
import com.macrobase.app.domain.model.WeightEntry
import com.macrobase.app.domain.repository.DiaryRepository
import com.macrobase.app.domain.repository.FoodRepository
import com.macrobase.app.domain.repository.PreferencesRepository
import com.macrobase.app.domain.repository.RecipeRepository
import com.macrobase.app.domain.repository.WaterRepository
import com.macrobase.app.domain.repository.WeightRepository
import com.macrobase.app.domain.usecase.AddWaterEntryUseCase
import com.macrobase.app.domain.usecase.AddWeightEntryUseCase
import com.macrobase.app.domain.usecase.DeleteDiaryEntryUseCase
import com.macrobase.app.domain.usecase.DeleteWaterEntryUseCase
import com.macrobase.app.domain.usecase.DeleteWeightEntryUseCase
import com.macrobase.app.domain.usecase.GetDailyDiaryUseCase
import com.macrobase.app.domain.usecase.GetWaterEntriesUseCase
import com.macrobase.app.domain.usecase.GetWaterForDateUseCase
import com.macrobase.app.domain.usecase.GetWeightForDateUseCase
import com.macrobase.app.domain.usecase.GetWeightHistoryUseCase
import com.macrobase.app.domain.usecase.UpdateWaterEntryUseCase
import com.macrobase.app.domain.usecase.basket.AddFoodToBasketUseCase
import com.macrobase.app.domain.usecase.basket.CommitSingleBasketItemUseCase
import com.macrobase.app.domain.usecase.basket.RemoveBasketItemUseCase
import com.macrobase.app.feature.customfoods.CustomFoodsViewModel
import com.macrobase.app.feature.dashboard.HomeViewModel
import com.macrobase.app.feature.recipes.RecipesViewModel
import com.macrobase.app.feature.water.WaterViewModel
import com.macrobase.app.feature.weight.WeightViewModel
import com.macrobase.app.testutil.MutableTestClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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
import java.time.LocalTime

/** What a phone with a full disk throws from SQLite. */
private fun diskFull() = IllegalStateException("SQLITE_FULL: database or disk is full")

/**
 * BUG-022: a failed water, weight, custom food, recipe or Home write reached the thread and closed
 * the app, after the dialog had already closed. Now it shows a message, and a dialog stays open.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WriteFailureTests {

    private class Prefs : PreferencesRepository {
        override suspend fun getPreferences() = UserPreferences()
        override fun observePreferences() = flowOf(UserPreferences())
        override suspend fun updatePreferences(preferences: UserPreferences) {}
    }

    private class Water(var failing: Boolean = true) : WaterRepository {
        override suspend fun addWaterEntry(entry: WaterEntry): Long = if (failing) throw diskFull() else 1L
        override suspend fun updateWaterEntry(entry: WaterEntry) { if (failing) throw diskFull() }
        override suspend fun deleteWaterEntry(entryId: Long) { if (failing) throw diskFull() }
        override fun observeWaterTotalForDate(date: LocalDate) = flowOf(0.0)
        override suspend fun getWaterTotalForDate(date: LocalDate) = 0.0
        override fun observeWaterEntriesForDate(date: LocalDate) = flowOf(emptyList<WaterEntry>())
        override suspend fun getWaterEntriesForDate(date: LocalDate) = emptyList<WaterEntry>()
        override fun observeWaterHistory(startDate: LocalDate, endDate: LocalDate) = flowOf(emptyList<WaterEntry>())
        override suspend fun getWaterHistory(startDate: LocalDate, endDate: LocalDate) = emptyList<WaterEntry>()
    }

    private class Weight(var failing: Boolean = true) : WeightRepository {
        override suspend fun addWeightEntry(entry: WeightEntry): Long = if (failing) throw diskFull() else 1L
        override suspend fun updateWeightEntry(entry: WeightEntry) { if (failing) throw diskFull() }
        override suspend fun deleteWeightEntry(entryId: Long) { if (failing) throw diskFull() }
        override suspend fun deleteWeightEntryForDate(date: LocalDate) { if (failing) throw diskFull() }
        override fun observeWeightForDate(date: LocalDate) = flowOf<WeightEntry?>(null)
        override suspend fun getWeightForDate(date: LocalDate): WeightEntry? = null
        override fun observeWeightHistory() = flowOf(emptyList<WeightEntry>())
        override suspend fun getWeightHistory() = emptyList<WeightEntry>()
        override fun observeWeightRange(startDate: LocalDate, endDate: LocalDate) = flowOf(emptyList<WeightEntry>())
        override suspend fun getWeightRange(startDate: LocalDate, endDate: LocalDate) = emptyList<WeightEntry>()
    }

    private class Foods(var failing: Boolean = true) : FoodRepository {
        override suspend fun searchFoods(query: String, limit: Int) = emptyList<Food>()
        override suspend fun getFoodById(id: Long): Food? = null
        override suspend fun getFoodByBarcode(barcode: String): Food? = null
        override suspend fun getFoodServings(foodId: Long) = emptyList<Serving>()
        override suspend fun getRecentFoods(limit: Int) = emptyList<Food>()
        override suspend fun getFavoriteFoods() = emptyList<Food>()
        override fun observeCustomFoods() = flowOf(emptyList<Food>())
        override suspend fun getCustomFoods() = emptyList<Food>()
        override suspend fun saveCustomFood(food: CustomFood): Long = if (failing) throw diskFull() else 1L
        override suspend fun deleteCustomFood(id: Long) {}
    }

    private class Recipes(var failing: Boolean = true) : RecipeRepository {
        override suspend fun createRecipe(recipe: Recipe): Long = if (failing) throw diskFull() else 1L
        override suspend fun updateRecipe(recipe: Recipe) { if (failing) throw diskFull() }
        override suspend fun deleteRecipe(recipeId: Long) {}
        override fun observeRecipes() = flowOf(emptyList<Recipe>())
        override suspend fun getRecipes() = emptyList<Recipe>()
        override suspend fun getRecipeById(recipeId: Long): Recipe? = null
        override fun encodeIngredients(ingredients: List<RecipeIngredient>) = RecipeIngredientsJson.encode(ingredients)
        override fun decodeIngredients(text: String) = RecipeIngredientsJson.decode(text) ?: emptyList()
    }

    private class Diary(var failing: Boolean = true) : DiaryRepository {
        override suspend fun addEntries(entries: List<DiaryEntry>) {}
        override suspend fun addEntry(entry: DiaryEntry) = 0L
        override suspend fun getDiaryForDate(date: LocalDate) = com.macrobase.app.domain.model.DailyNutritionSummary(date = date)
        override fun observeDiaryForDate(date: LocalDate) = flowOf(com.macrobase.app.domain.model.DailyNutritionSummary(date = date))
        override suspend fun getEntryById(entryId: Long): DiaryEntry? = null
        override suspend fun updateEntry(entry: DiaryEntry) {}
        override suspend fun deleteEntry(entryId: Long) { if (failing) throw diskFull() }
        override suspend fun getMonthlyAdherence(year: Int, month: Int) = emptyList<com.macrobase.app.domain.model.CalendarDaySummary>()
        override fun observeMonthlyAdherence(year: Int, month: Int) = flowOf(emptyList<com.macrobase.app.domain.model.CalendarDaySummary>())
        override fun observeNutritionAggregations(startDate: LocalDate, endDate: LocalDate) = flowOf(emptyList<com.macrobase.app.domain.repository.DailyMacroAggregation>())
        override suspend fun getNutritionAggregations(startDate: LocalDate, endDate: LocalDate) = emptyList<com.macrobase.app.domain.repository.DailyMacroAggregation>()
    }

    private val today = LocalDate.of(2026, 11, 6)
    private val clock = MutableTestClock(today, LocalTime.of(9, 0))
    private val basket = InMemoryBasketRepository()

    private fun water(repo: Water) = WaterViewModel(
        GetWaterForDateUseCase(repo), GetWaterEntriesUseCase(repo), AddWaterEntryUseCase(repo),
        UpdateWaterEntryUseCase(repo), DeleteWaterEntryUseCase(repo), Prefs(), clock
    )

    private fun weight(repo: Weight) = WeightViewModel(
        GetWeightHistoryUseCase(repo), GetWeightForDateUseCase(repo), AddWeightEntryUseCase(repo),
        DeleteWeightEntryUseCase(repo), Prefs(), clock
    )

    private fun recipes(repo: Recipes) = RecipesViewModel(
        repo, Foods(), AddFoodToBasketUseCase(basket), CommitSingleBasketItemUseCase(basket, Diary(failing = false)),
        RemoveBasketItemUseCase(basket), SavedStateHandle()
    )

    private fun source(path: String): String =
        listOf(File("src/main/java/com/macrobase/app/$path"), File("app/src/main/java/com/macrobase/app/$path"))
            .first { it.isFile }.readText()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aFailedWaterWriteShowsAMessage() {
        val vm = water(Water())

        vm.addWater(250.0)
        assertEquals(WRITE_FAILED_MESSAGE, vm.writes.error.value)

        vm.writes.dismiss()
        vm.deleteWaterEntry(3L)
        assertEquals(WRITE_FAILED_MESSAGE, vm.writes.error.value)
    }

    @Test
    fun theWeightDialogClosesOnlyOnceTheWeightIsSaved() {
        val repo = Weight()
        val vm = weight(repo)
        var closed = false

        vm.logWeight(today, 70.0, onSaved = { closed = true })
        assertFalse("the dialog stays open", closed)
        assertEquals(WRITE_FAILED_MESSAGE, vm.writes.error.value)

        repo.failing = false
        vm.writes.dismiss()
        vm.logWeight(today, 70.0, onSaved = { closed = true })
        assertTrue(closed)
        assertNull(vm.writes.error.value)
    }

    @Test
    fun aFailedCustomFoodSaveKeepsTheFormAndCanBeRetried() {
        val repo = Foods()
        val vm = CustomFoodsViewModel(repo, SavedStateHandle())
        var saved = 0
        fun save() = vm.saveCustomFood(
            name = "Bar", servingSize = 1.0, servingUnit = ServingUnit.SERVING,
            calories = 200.0, protein = 10.0, carbs = 20.0, fat = 8.0, onComplete = { saved++ }
        )

        save()
        assertEquals(0, saved)
        assertEquals(WRITE_FAILED_MESSAGE, vm.writes.error.value)

        repo.failing = false
        save()
        assertEquals("the retry saves", 1, saved)
    }

    @Test
    fun aFailedRecipeSaveKeepsTheFormAndCanBeRetried() {
        val repo = Recipes()
        val vm = recipes(repo)
        val dal = Recipe(name = "Dal", servingsProduced = 2)
        var saved = 0

        vm.saveRecipe(dal) { saved++ }
        assertEquals(0, saved)
        assertEquals(WRITE_FAILED_MESSAGE, vm.writes.error.value)

        repo.failing = false
        vm.saveRecipe(dal) { saved++ }
        assertEquals(1, saved)
    }

    @Test
    fun aFailedQuickAddOrDeleteOnHomeShowsAMessage() {
        val vm = HomeViewModel(
            getDailyDiaryUseCase = GetDailyDiaryUseCase(Diary()),
            deleteDiaryEntryUseCase = DeleteDiaryEntryUseCase(Diary()),
            addFoodToBasketUseCase = AddFoodToBasketUseCase(basket),
            clock = clock,
            addWaterEntryUseCase = AddWaterEntryUseCase(Water())
        )
        var added = false

        vm.quickAddWater { added = true }
        assertFalse(added)
        assertEquals(WRITE_FAILED_MESSAGE, vm.writes.error.value)

        vm.writes.dismiss()
        vm.deleteEntry(5L)
        assertEquals(WRITE_FAILED_MESSAGE, vm.writes.error.value)
    }

    @Test
    fun everyScreenShowsTheMessageAndItsDialogsWaitForTheSave() {
        for (path in listOf(
            "feature/water/WaterScreen.kt", "feature/weight/WeightScreen.kt", "feature/dashboard/HomeScreen.kt",
            "feature/customfoods/CustomFoodsScreen.kt", "feature/recipes/RecipesScreen.kt"
        )) {
            assertTrue(path, source(path).contains("WriteFailedDialog(viewModel.writes)"))
        }
        val weightScreen = source("feature/weight/WeightScreen.kt")
        assertTrue(weightScreen.contains("onSaved = { showLogDialog = false }"))
        assertTrue(weightScreen.contains("onSaved = { entryToEdit = null }"))
        val waterScreen = source("feature/water/WaterScreen.kt")
        assertTrue(waterScreen.contains("onSaved = { showCustomAmountDialog = false }"))
        assertTrue(waterScreen.contains("onSaved = { entryToEdit = null }"))
    }
}
