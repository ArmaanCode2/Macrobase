package com.macrobase.app

import com.macrobase.app.data.repository.basket.InMemoryBasketRepository
import com.macrobase.app.domain.model.CustomFood
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.FoodSource
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.ServingUnit
import com.macrobase.app.domain.model.basket.BasketItem
import com.macrobase.app.domain.repository.DiaryRepository
import com.macrobase.app.domain.repository.FoodRepository
import com.macrobase.app.domain.usecase.CalculateNutritionForServingUseCase
import com.macrobase.app.domain.usecase.DeleteDiaryEntryUseCase
import com.macrobase.app.domain.usecase.GetDiaryEntryUseCase
import com.macrobase.app.domain.usecase.GetFoodDetailsUseCase
import com.macrobase.app.domain.usecase.UpdateDiaryEntryUseCase
import com.macrobase.app.domain.usecase.basket.AddFoodToBasketUseCase
import com.macrobase.app.domain.usecase.basket.ClearBasketUseCase
import com.macrobase.app.domain.usecase.basket.CommitBasketUseCase
import com.macrobase.app.domain.usecase.basket.CommitSingleBasketItemUseCase
import com.macrobase.app.domain.usecase.basket.GetBasketItemsUseCase
import com.macrobase.app.domain.usecase.basket.RemoveBasketItemUseCase
import com.macrobase.app.domain.usecase.basket.UpdateAllBasketItemsUseCase
import com.macrobase.app.domain.usecase.basket.UpdateBasketItemUseCase
import com.macrobase.app.feature.basket.BasketViewModel
import com.macrobase.app.feature.detail.FoodDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * BUG-008: a diary entry keeps the values it was logged with (AGENTS.md 1.4). Changing its amount
 * scales those values; the food's current values are used only after a deliberate serving change.
 * "Protein bar" was logged at 200 kcal a bar and has since been edited to 250.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SnapshotEditTests {

    private val monday = LocalDate.of(2026, 10, 19)
    private val barServing = Serving(id = 1, description = "1 bar", unit = ServingUnit.PIECE, quantity = 1.0, gramWeight = 40.0, isDefault = true)
    private val gramServing = Serving(id = 2, description = "1 g", unit = ServingUnit.GRAMS, quantity = 1.0, gramWeight = 1.0)

    private fun nutrition(calories: Double, protein: Double, fiber: Double? = null) =
        Nutrition(calories = calories, proteinGrams = protein, carbsGrams = 20.0, fatGrams = 8.0, fiberGrams = fiber)

    private val editedFood = Food(
        id = 100_000_007L,
        uuid = "bar",
        source = FoodSource.CUSTOM_USER,
        name = "Protein bar",
        isUserOwned = true,
        nutrition = nutrition(250.0, 25.0),
        servings = listOf(barServing, gramServing)
    )

    /** The diary's own view of the entry: a snapshot food, per one unit of the logged serving. */
    private val loggedEntry = DiaryEntry(
        id = 11L,
        uuid = "entry",
        date = monday,
        mealType = MealType.LUNCH,
        food = editedFood.copy(nutrition = nutrition(200.0, 20.0), servings = listOf(barServing), isSnapshot = true),
        serving = barServing.copy(id = 0, isDefault = false),
        quantity = 1.0,
        calculatedNutrition = nutrition(200.0, 20.0),
        loggedAt = Instant.ofEpochMilli(1_000L)
    )

    private class Diary(var entry: DiaryEntry?) : DiaryRepository {
        override suspend fun addEntries(entries: List<DiaryEntry>) {}
        override suspend fun addEntry(entry: DiaryEntry) = 0L
        override suspend fun getDiaryForDate(date: LocalDate) = com.macrobase.app.domain.model.DailyNutritionSummary(date = date)
        override fun observeDiaryForDate(date: LocalDate) = flowOf(com.macrobase.app.domain.model.DailyNutritionSummary(date = date))
        override suspend fun getEntryById(entryId: Long) = entry?.takeIf { it.id == entryId }
        override suspend fun updateEntry(entry: DiaryEntry) { this.entry = entry }
        override suspend fun deleteEntry(entryId: Long) { entry = null }
        override suspend fun getMonthlyAdherence(year: Int, month: Int) = emptyList<com.macrobase.app.domain.model.CalendarDaySummary>()
        override fun observeMonthlyAdherence(year: Int, month: Int) = flowOf(emptyList<com.macrobase.app.domain.model.CalendarDaySummary>())
        override fun observeNutritionAggregations(startDate: LocalDate, endDate: LocalDate) = flowOf(emptyList<com.macrobase.app.domain.repository.DailyMacroAggregation>())
        override suspend fun getNutritionAggregations(startDate: LocalDate, endDate: LocalDate) = emptyList<com.macrobase.app.domain.repository.DailyMacroAggregation>()
    }

    private class Foods(private val food: Food) : FoodRepository {
        override suspend fun searchFoods(query: String, limit: Int) = listOf(food)
        override suspend fun getFoodById(id: Long) = food.takeIf { it.id == id }
        override suspend fun getFoodByBarcode(barcode: String): Food? = null
        override suspend fun getFoodServings(foodId: Long) = food.servings
        override suspend fun getRecentFoods(limit: Int) = emptyList<Food>()
        override suspend fun getFavoriteFoods() = emptyList<Food>()
        override fun observeCustomFoods() = flowOf(emptyList<Food>())
        override suspend fun getCustomFoods() = emptyList<Food>()
        override suspend fun saveCustomFood(food: CustomFood) = 0L
        override suspend fun deleteCustomFood(id: Long) {}
    }

    private val basket = InMemoryBasketRepository()
    private val diary = Diary(loggedEntry)

    private fun foodDetail(food: Food = editedFood) = FoodDetailViewModel(
        GetFoodDetailsUseCase(Foods(food)),
        CalculateNutritionForServingUseCase(),
        AddFoodToBasketUseCase(basket),
        GetBasketItemsUseCase(basket),
        UpdateBasketItemUseCase(basket),
        RemoveBasketItemUseCase(basket),
        CommitSingleBasketItemUseCase(basket, diary),
        UpdateDiaryEntryUseCase(diary),
        DeleteDiaryEntryUseCase(diary),
        GetDiaryEntryUseCase(diary)
    )

    private fun basketScreen() = BasketViewModel(
        getBasketItemsUseCase = GetBasketItemsUseCase(basket),
        removeBasketItemUseCase = RemoveBasketItemUseCase(basket),
        updateBasketItemUseCase = UpdateBasketItemUseCase(basket),
        updateAllBasketItemsUseCase = UpdateAllBasketItemsUseCase(basket),
        clearBasketUseCase = ClearBasketUseCase(basket),
        commitBasketUseCase = CommitBasketUseCase(basket, diary),
        calculateNutritionForServingUseCase = CalculateNutritionForServingUseCase()
    )

    private fun FoodDetailViewModel.openEntry() = openFood(editedFood.id, entryId = loggedEntry.id)

    /** A basket item copied from the entry: the live food, with the amount and values as logged. */
    private fun copiedItem() = BasketItem.create(editedFood, barServing, 1.0, nutrition(200.0, 20.0), monday, MealType.LUNCH)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun anAmountChangeScalesWhatWasLoggedNotTheFoodsNewValues() {
        val vm = foodDetail()
        vm.openEntry()
        assertEquals(200.0, vm.uiState.value.calculatedNutrition.calories, 0.001)

        vm.onQuantityChange("1.5")

        assertEquals(300.0, vm.uiState.value.calculatedNutrition.calories, 0.001)
        assertEquals(30.0, vm.uiState.value.calculatedNutrition.proteinGrams, 0.001)
    }

    @Test
    fun settingTheAmountBackSavesTheLoggedValues() {
        val vm = foodDetail()
        vm.openEntry()

        vm.onQuantityChange("1.5")
        vm.onQuantityChange("1")
        vm.logFood {}

        assertEquals(200.0, diary.entry!!.calculatedNutrition.calories, 0.001)
        assertEquals(20.0, diary.entry!!.calculatedNutrition.proteinGrams, 0.001)
        assertEquals(1.0, diary.entry!!.quantity, 0.001)
    }

    @Test
    fun aCatalogFoodWhoseValuesChangedStaysOnItsLoggedValuesToo() {
        val portion = Serving(id = 1, description = "100 g", unit = ServingUnit.GRAMS, quantity = 1.0, gramWeight = 100.0, isDefault = true)
        val catalogFood = Food(id = 1_896_880_878_330_341L, uuid = "dosa", name = "Dosa", nutrition = nutrition(165.0, 4.0), servings = listOf(portion))
        val logged = loggedEntry.copy(
            food = catalogFood.copy(nutrition = nutrition(381.0, 10.0), servings = listOf(portion), isSnapshot = true),
            serving = portion.copy(id = 0, isDefault = false),
            quantity = 1.0,
            calculatedNutrition = nutrition(381.0, 10.0)
        )
        diary.entry = logged
        val vm = foodDetail(catalogFood)
        vm.openFood(catalogFood.id, entryId = logged.id)

        vm.onQuantityChange("2")

        assertEquals(762.0, vm.uiState.value.calculatedNutrition.calories, 0.001)
    }

    @Test
    fun aSecondaryNutrientFilledFromTheFoodScalesWithTheAmount() {
        // Logged before fiber was recorded: the fill comes from the food, then scales like the rest
        val vm = foodDetail(editedFood.copy(nutrition = nutrition(250.0, 25.0, fiber = 4.0)))
        vm.openEntry()
        assertEquals(4.0, vm.uiState.value.calculatedNutrition.fiberGrams!!, 0.001)

        vm.onQuantityChange("2")

        assertEquals(400.0, vm.uiState.value.calculatedNutrition.calories, 0.001)
        assertEquals(8.0, vm.uiState.value.calculatedNutrition.fiberGrams!!, 0.001)
    }

    @Test
    fun aDeliberateServingChangeUsesTheFoodThenTheLoggedServingComesBack() {
        val vm = foodDetail()
        vm.openEntry()

        vm.onServingSelected(gramServing)
        assertEquals(6.25, vm.uiState.value.calculatedNutrition.calories, 0.001) // 250 per 40 g bar

        vm.onServingSelected(vm.uiState.value.availableServings.first { it.description == "1 bar" })
        assertEquals(200.0, vm.uiState.value.calculatedNutrition.calories, 0.001)
    }

    @Test
    fun anEntryWithoutAnAmountFallsBackToTheFood() {
        // An older version could log 0; there is nothing to scale from
        diary.entry = loggedEntry.copy(quantity = 0.0, calculatedNutrition = Nutrition.ZERO)
        val vm = foodDetail()
        vm.openEntry()

        vm.onQuantityChange("2")

        assertEquals(500.0, vm.uiState.value.calculatedNutrition.calories, 0.001)
    }

    @Test
    fun aCopiedItemOpenedInFoodDetailShowsItsOwnValuesAndScalesThem() {
        val item = copiedItem()
        basket.addItem(item)
        val vm = foodDetail()
        vm.openFood(editedFood.id, basketItemId = item.id)
        assertEquals(200.0, vm.uiState.value.calculatedNutrition.calories, 0.001)

        vm.onQuantityChange("2")

        assertEquals(400.0, vm.uiState.value.calculatedNutrition.calories, 0.001)
        assertEquals(400.0, basket.items.value.single().calculatedNutrition.calories, 0.001)
    }

    @Test
    fun theBasketStepperScalesTheItemsOwnValues() {
        val item = copiedItem()
        basket.addItem(item)

        basketScreen().onQuantityChange(item.id, "2")

        assertEquals(400.0, basket.items.value.single().calculatedNutrition.calories, 0.001)
    }

    @Test
    fun theNumberNeverFallsAsTheAmountRises() {
        val item = copiedItem().copy(food = loggedEntry.food, serving = loggedEntry.serving)
        basket.addItem(item)
        val vm = foodDetail()
        vm.openFood(editedFood.id, basketItemId = item.id)
        vm.onQuantityChange("1.5")
        val fromDetail = basket.items.value.single().calculatedNutrition.calories

        basketScreen().onQuantityChange(item.id, "1.75")

        assertTrue(basket.items.value.single().calculatedNutrition.calories > fromDetail)
    }
}
