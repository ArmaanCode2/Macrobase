package com.macrobase.app

import com.macrobase.app.data.repository.basket.InMemoryBasketRepository
import com.macrobase.app.domain.model.CustomFood
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Recipe
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.UserPreferences
import com.macrobase.app.domain.model.WaterEntry
import com.macrobase.app.domain.model.WeightEntry
import com.macrobase.app.domain.model.basket.BasketItem
import com.macrobase.app.domain.repository.DiaryRepository
import com.macrobase.app.domain.repository.FoodRepository
import com.macrobase.app.domain.repository.PreferencesRepository
import com.macrobase.app.domain.repository.RecipeRepository
import com.macrobase.app.domain.repository.WaterRepository
import com.macrobase.app.domain.repository.WeightRepository
import com.macrobase.app.domain.usecase.AddWaterEntryUseCase
import com.macrobase.app.domain.usecase.AddWeightEntryUseCase
import com.macrobase.app.domain.usecase.CalculateNutritionForServingUseCase
import com.macrobase.app.domain.usecase.DeleteWaterEntryUseCase
import com.macrobase.app.domain.usecase.DeleteWeightEntryUseCase
import com.macrobase.app.domain.usecase.GetWaterEntriesUseCase
import com.macrobase.app.domain.usecase.GetWaterForDateUseCase
import com.macrobase.app.domain.usecase.GetWeightForDateUseCase
import com.macrobase.app.domain.usecase.GetWeightHistoryUseCase
import com.macrobase.app.domain.usecase.UpdateWaterEntryUseCase
import com.macrobase.app.domain.usecase.basket.AddFoodToBasketUseCase
import com.macrobase.app.domain.usecase.basket.ClearBasketUseCase
import com.macrobase.app.domain.usecase.basket.CommitBasketUseCase
import com.macrobase.app.domain.usecase.basket.CommitSingleBasketItemUseCase
import com.macrobase.app.domain.usecase.basket.GetBasketItemsUseCase
import com.macrobase.app.domain.usecase.basket.RemoveBasketItemUseCase
import com.macrobase.app.domain.usecase.basket.UpdateAllBasketItemsUseCase
import com.macrobase.app.domain.usecase.basket.UpdateBasketItemUseCase
import com.macrobase.app.feature.basket.BasketViewModel
import com.macrobase.app.feature.customfoods.CustomFoodsViewModel
import com.macrobase.app.feature.recipes.RecipesViewModel
import com.macrobase.app.feature.water.WaterViewModel
import com.macrobase.app.feature.weight.WeightViewModel
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
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** BUG-005: one tap asks; only the answer deletes. */
@OptIn(ExperimentalCoroutinesApi::class)
class DeleteConfirmationTests {

    private val day = LocalDate.of(2026, 10, 20)

    private class Prefs : PreferencesRepository {
        override suspend fun getPreferences() = UserPreferences()
        override fun observePreferences() = flowOf(UserPreferences())
        override suspend fun updatePreferences(preferences: UserPreferences) {}
    }

    private class Water : WaterRepository {
        val deleted = mutableListOf<Long>()
        override suspend fun addWaterEntry(entry: WaterEntry) = 0L
        override suspend fun updateWaterEntry(entry: WaterEntry) {}
        override suspend fun deleteWaterEntry(entryId: Long) { deleted += entryId }
        override fun observeWaterTotalForDate(date: LocalDate) = flowOf(0.0)
        override suspend fun getWaterTotalForDate(date: LocalDate) = 0.0
        override fun observeWaterEntriesForDate(date: LocalDate) = flowOf(emptyList<WaterEntry>())
        override suspend fun getWaterEntriesForDate(date: LocalDate) = emptyList<WaterEntry>()
        override fun observeWaterHistory(startDate: LocalDate, endDate: LocalDate) = flowOf(emptyList<WaterEntry>())
        override suspend fun getWaterHistory(startDate: LocalDate, endDate: LocalDate) = emptyList<WaterEntry>()
    }

    private class Weight : WeightRepository {
        val deleted = mutableListOf<LocalDate>()
        override suspend fun addWeightEntry(entry: WeightEntry) = 0L
        override suspend fun updateWeightEntry(entry: WeightEntry) {}
        override suspend fun deleteWeightEntry(entryId: Long) {}
        override suspend fun deleteWeightEntryForDate(date: LocalDate) { deleted += date }
        override fun observeWeightForDate(date: LocalDate) = flowOf<WeightEntry?>(null)
        override suspend fun getWeightForDate(date: LocalDate): WeightEntry? = null
        override fun observeWeightHistory() = flowOf(emptyList<WeightEntry>())
        override suspend fun getWeightHistory() = emptyList<WeightEntry>()
        override fun observeWeightRange(startDate: LocalDate, endDate: LocalDate) = flowOf(emptyList<WeightEntry>())
        override suspend fun getWeightRange(startDate: LocalDate, endDate: LocalDate) = emptyList<WeightEntry>()
    }

    private class Foods : FoodRepository {
        val deleted = mutableListOf<Long>()
        override suspend fun searchFoods(query: String, limit: Int) = emptyList<Food>()
        override suspend fun getFoodById(id: Long): Food? = null
        override suspend fun getFoodByBarcode(barcode: String): Food? = null
        override suspend fun getFoodServings(foodId: Long) = emptyList<Serving>()
        override suspend fun getRecentFoods(limit: Int) = emptyList<Food>()
        override suspend fun getFavoriteFoods() = emptyList<Food>()
        override fun observeCustomFoods() = flowOf(emptyList<Food>())
        override suspend fun getCustomFoods() = emptyList<Food>()
        override suspend fun saveCustomFood(food: CustomFood) = 0L
        override suspend fun deleteCustomFood(id: Long) { deleted += id }
    }

    private class Recipes : RecipeRepository {
        val deleted = mutableListOf<Long>()
        override suspend fun createRecipe(recipe: Recipe) = 1L
        override suspend fun updateRecipe(recipe: Recipe) {}
        override suspend fun deleteRecipe(recipeId: Long) { deleted += recipeId }
        override fun observeRecipes() = flowOf(emptyList<Recipe>())
        override suspend fun getRecipes() = emptyList<Recipe>()
        override suspend fun getRecipeById(recipeId: Long): Recipe? = null
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

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun recipes(repo: Recipes) = RecipesViewModel(
        repo,
        Foods(),
        AddFoodToBasketUseCase(basket),
        CommitSingleBasketItemUseCase(basket, NoDiary()),
        RemoveBasketItemUseCase(basket)
    )

    private fun water(repo: Water) = WaterViewModel(
        GetWaterForDateUseCase(repo),
        GetWaterEntriesUseCase(repo),
        AddWaterEntryUseCase(repo),
        UpdateWaterEntryUseCase(repo),
        DeleteWaterEntryUseCase(repo),
        Prefs()
    )

    private fun weight(repo: Weight) = WeightViewModel(
        GetWeightHistoryUseCase(repo),
        GetWeightForDateUseCase(repo),
        AddWeightEntryUseCase(repo),
        DeleteWeightEntryUseCase(repo),
        Prefs()
    )

    private fun basketScreen() = BasketViewModel(
        getBasketItemsUseCase = GetBasketItemsUseCase(basket),
        removeBasketItemUseCase = RemoveBasketItemUseCase(basket),
        updateBasketItemUseCase = UpdateBasketItemUseCase(basket),
        updateAllBasketItemsUseCase = UpdateAllBasketItemsUseCase(basket),
        clearBasketUseCase = ClearBasketUseCase(basket),
        commitBasketUseCase = CommitBasketUseCase(basket, NoDiary()),
        calculateNutritionForServingUseCase = CalculateNutritionForServingUseCase()
    )

    @Test
    fun aCustomFoodGoesOnlyAfterItIsConfirmed() {
        val repo = Foods()
        val vm = CustomFoodsViewModel(repo)

        vm.pendingDelete.ask(7L, "Protein bar")
        assertEquals("Protein bar", vm.pendingDelete.request.value?.label)
        assertEquals(emptyList<Long>(), repo.deleted)

        var finished = false
        vm.confirmDelete { finished = true }

        assertEquals(listOf(7L), repo.deleted)
        assertEquals(true, finished)
        assertNull(vm.pendingDelete.request.value)
    }

    @Test
    fun cancellingKeepsTheCustomFood() {
        val repo = Foods()
        val vm = CustomFoodsViewModel(repo)

        vm.pendingDelete.ask(7L, "Protein bar")
        vm.pendingDelete.cancel()
        vm.confirmDelete()

        assertEquals(emptyList<Long>(), repo.deleted)
    }

    @Test
    fun aRecipeGoesOnlyAfterItIsConfirmed() {
        val repo = Recipes()
        val vm = recipes(repo)

        vm.pendingDelete.ask(3L, "Dal")
        assertEquals(emptyList<Long>(), repo.deleted)
        vm.confirmDelete()

        assertEquals(listOf(3L), repo.deleted)
    }

    @Test
    fun aWaterLogGoesOnlyAfterItIsConfirmed() {
        val repo = Water()
        val vm = water(repo)

        vm.pendingDelete.ask(5L, "250 mL at 08:15 AM")
        assertEquals(emptyList<Long>(), repo.deleted)
        vm.confirmDelete()

        assertEquals(listOf(5L), repo.deleted)
    }

    @Test
    fun aWeighInGoesOnlyAfterItIsConfirmed() {
        val repo = Weight()
        val vm = weight(repo)

        vm.pendingDelete.ask(day, "79.6 kg on Tuesday, 10/20")
        assertEquals(emptyList<LocalDate>(), repo.deleted)
        vm.confirmDelete()

        assertEquals(listOf(day), repo.deleted)
    }

    @Test
    fun theBasketClearsOnlyAfterItIsConfirmed() {
        val food = Food(id = 1L, uuid = "f", name = "Rice", nutrition = Nutrition.ZERO)
        basket.addItem(BasketItem.create(food, Serving(description = "100 g", gramWeight = 100.0), 1.0, Nutrition.ZERO, day, MealType.LUNCH))
        val vm = basketScreen()

        vm.pendingClear.ask(Unit, "basket")
        assertEquals(1, basket.items.value.size)
        vm.pendingClear.cancel()
        vm.confirmClear()
        assertEquals(1, basket.items.value.size)

        vm.pendingClear.ask(Unit, "basket")
        vm.confirmClear()
        assertEquals(0, basket.items.value.size)
    }
}

/** BUG-005: no trash icon or Clear button deletes on the first tap. */
class DeleteWiringTests {

    private fun source(path: String): String =
        listOf(File("src/main/java/com/macrobase/app/$path"), File("app/src/main/java/com/macrobase/app/$path"))
            .first { it.isFile }.readText()

    @Test
    fun noDeleteButtonGoesStraightToTheRepository() {
        val customFoods = source("feature/customfoods/CustomFoodsScreen.kt")

        assertFalse(customFoods.contains("onDelete = { viewModel.deleteCustomFood(item.id) {} }"))
        assertFalse(customFoods.contains("viewModel.deleteCustomFood(existingFood!!.id, onComplete = onSaveSuccess)"))
        assertFalse(source("feature/recipes/RecipesScreen.kt").contains("onDeleteClick = { viewModel.deleteRecipe(recipe.id) {} }"))
        assertFalse(source("feature/weight/WeightScreen.kt").contains("onClick = { viewModel.deleteWeight(entry.date) }"))
        assertFalse(source("feature/water/WaterScreen.kt").contains("onClick = { viewModel.deleteWaterEntry(entry.id) }"))
        assertFalse(source("feature/basket/BasketScreen.kt").contains("onClick = { viewModel.clearBasket() }"))
        assertFalse(source("feature/detail/FoodDetailScreen.kt").contains("onClick = { viewModel.deleteEntry { onNavigateBack() } }"))
    }
}
