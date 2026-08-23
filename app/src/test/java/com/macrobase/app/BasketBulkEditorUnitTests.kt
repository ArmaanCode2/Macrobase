package com.macrobase.app

import com.macrobase.app.data.repository.basket.InMemoryBasketRepository
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.ServingUnit
import com.macrobase.app.domain.repository.DiaryRepository
import com.macrobase.app.domain.usecase.CalculateNutritionForServingUseCase
import com.macrobase.app.domain.usecase.basket.AddFoodToBasketUseCase
import com.macrobase.app.domain.usecase.basket.ClearBasketUseCase
import com.macrobase.app.domain.usecase.basket.CommitBasketUseCase
import com.macrobase.app.domain.usecase.basket.GetBasketItemsUseCase
import com.macrobase.app.domain.usecase.basket.RemoveBasketItemUseCase
import com.macrobase.app.domain.usecase.basket.UpdateAllBasketItemsUseCase
import com.macrobase.app.domain.usecase.basket.UpdateBasketItemUseCase
import com.macrobase.app.feature.basket.BasketViewModel
import com.macrobase.app.feature.basket.LoggingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class BasketBulkEditorUnitTests {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var basketRepository: InMemoryBasketRepository
    private lateinit var diaryRepository: FakeDiaryRepository
    private lateinit var calculateNutritionUseCase: CalculateNutritionForServingUseCase

    private lateinit var addFoodToBasketUseCase: AddFoodToBasketUseCase
    private lateinit var getBasketItemsUseCase: GetBasketItemsUseCase
    private lateinit var removeBasketItemUseCase: RemoveBasketItemUseCase
    private lateinit var updateBasketItemUseCase: UpdateBasketItemUseCase
    private lateinit var updateAllBasketItemsUseCase: UpdateAllBasketItemsUseCase
    private lateinit var clearBasketUseCase: ClearBasketUseCase
    private lateinit var commitBasketUseCase: CommitBasketUseCase

    private lateinit var viewModel: BasketViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        basketRepository = InMemoryBasketRepository()
        diaryRepository = FakeDiaryRepository()
        calculateNutritionUseCase = CalculateNutritionForServingUseCase()

        addFoodToBasketUseCase = AddFoodToBasketUseCase(basketRepository)
        getBasketItemsUseCase = GetBasketItemsUseCase(basketRepository)
        removeBasketItemUseCase = RemoveBasketItemUseCase(basketRepository)
        updateBasketItemUseCase = UpdateBasketItemUseCase(basketRepository)
        updateAllBasketItemsUseCase = UpdateAllBasketItemsUseCase(basketRepository)
        clearBasketUseCase = ClearBasketUseCase(basketRepository)
        commitBasketUseCase = CommitBasketUseCase(basketRepository, diaryRepository)

        viewModel = BasketViewModel(
            getBasketItemsUseCase = getBasketItemsUseCase,
            removeBasketItemUseCase = removeBasketItemUseCase,
            updateBasketItemUseCase = updateBasketItemUseCase,
            updateAllBasketItemsUseCase = updateAllBasketItemsUseCase,
            clearBasketUseCase = clearBasketUseCase,
            commitBasketUseCase = commitBasketUseCase,
            calculateNutritionForServingUseCase = calculateNutritionUseCase
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createSampleFood(
        id: Long,
        name: String,
        calPer100g: Double,
        protein: Double,
        carbs: Double,
        fat: Double,
        servings: List<Serving> = emptyList()
    ): Food {
        val defaultServing = Serving(description = "1.0 g", gramWeight = 1.0, isDefault = true)
        val allServings = if (servings.isEmpty()) listOf(defaultServing) else servings
        return Food(
            id = id,
            uuid = "food-$id",
            name = name,
            nutrition = Nutrition(
                calories = calPer100g,
                proteinGrams = protein,
                carbsGrams = carbs,
                fatGrams = fat
            ),
            servings = allServings
        )
    }

    @Test
    fun test1_oneBasketItem_rendersAndCalculatesCorrectly() = runTest(testDispatcher) {
        val food = createSampleFood(1L, "Paneer", 265.0, 18.0, 3.0, 20.0)
        val serving = food.servings.first()
        val nutrition = calculateNutritionUseCase(food, serving, 30.0)

        addFoodToBasketUseCase(
            food = food,
            serving = serving,
            quantity = 30.0,
            calculatedNutrition = nutrition,
            date = LocalDate.of(2026, 8, 23),
            mealType = MealType.BREAKFAST
        )
        advanceUntilIdle()

        val items = viewModel.items.value
        assertEquals(1, items.size)
        assertEquals("Paneer", items[0].foodNameSnapshot)
        assertEquals(30.0, items[0].quantity, 0.001)
        assertEquals(79.5, items[0].calculatedNutrition.calories, 0.1)
        assertEquals(LocalDate.of(2026, 8, 23), viewModel.commonDate.value)
        assertEquals(MealType.BREAKFAST, viewModel.commonMealType.value)
    }

    @Test
    fun test2_threeBasketItems_rendersAndCalculatesCorrectly() = runTest(testDispatcher) {
        val bread = createSampleFood(1L, "Brown Bread", 264.0, 8.0, 48.0, 2.0, listOf(Serving(description = "1 slice", gramWeight = 25.0)))
        val cheese = createSampleFood(2L, "Mozz Cheese", 250.0, 20.0, 2.0, 18.0)
        val paneer = createSampleFood(3L, "Paneer", 265.0, 18.0, 3.0, 20.0)

        addFoodToBasketUseCase(bread, bread.servings[0], 4.0, calculateNutritionUseCase(bread, bread.servings[0], 4.0), LocalDate.now(), MealType.BREAKFAST)
        addFoodToBasketUseCase(cheese, cheese.servings[0], 30.0, calculateNutritionUseCase(cheese, cheese.servings[0], 30.0), LocalDate.now(), MealType.BREAKFAST)
        addFoodToBasketUseCase(paneer, paneer.servings[0], 30.0, calculateNutritionUseCase(paneer, paneer.servings[0], 30.0), LocalDate.now(), MealType.BREAKFAST)
        advanceUntilIdle()

        val items = viewModel.items.value
        assertEquals(3, items.size)
        val totalCalories = items.sumOf { it.calculatedNutrition.calories }
        assertEquals(264.0 + 75.0 + 79.5, totalCalories, 0.5)
    }

    @Test
    fun test3_fiveBasketItems_allPresentOnOneScreen() = runTest(testDispatcher) {
        for (i in 1..5) {
            val food = createSampleFood(i.toLong(), "Food $i", 100.0 * i, 10.0, 10.0, 2.0)
            addFoodToBasketUseCase(food, food.servings[0], 100.0, calculateNutritionUseCase(food, food.servings[0], 100.0), LocalDate.now(), MealType.LUNCH)
        }
        advanceUntilIdle()

        val items = viewModel.items.value
        assertEquals(5, items.size)
    }

    @Test
    fun test4_inlineQuantityEditing_updatesNutritionInstantly() = runTest(testDispatcher) {
        val paneer = createSampleFood(1L, "Paneer", 265.0, 18.0, 3.0, 20.0)
        val itemId = addFoodToBasketUseCase(paneer, paneer.servings[0], 30.0, calculateNutritionUseCase(paneer, paneer.servings[0], 30.0), LocalDate.now(), MealType.BREAKFAST)
        advanceUntilIdle()

        assertEquals(79.5, viewModel.items.value[0].calculatedNutrition.calories, 0.1)

        // Edit quantity inline to 35
        viewModel.onQuantityChange(itemId, "35")
        advanceUntilIdle()

        val updated = viewModel.items.value[0]
        assertEquals(35.0, updated.quantity, 0.001)
        assertEquals(92.75, updated.calculatedNutrition.calories, 0.1)
    }

    @Test
    fun test5_inlineServingEditing_updatesNutritionInstantly() = runTest(testDispatcher) {
        val servingSlice = Serving(description = "1 slice", gramWeight = 25.0)
        val servingLoaf = Serving(description = "1 loaf", gramWeight = 400.0)
        val bread = createSampleFood(1L, "Bread", 264.0, 8.0, 48.0, 2.0, listOf(servingSlice, servingLoaf))

        val itemId = addFoodToBasketUseCase(bread, servingSlice, 2.0, calculateNutritionUseCase(bread, servingSlice, 2.0), LocalDate.now(), MealType.BREAKFAST)
        advanceUntilIdle()

        // 2 slices = 50g -> 132 kcal
        assertEquals(132.0, viewModel.items.value[0].calculatedNutrition.calories, 0.1)

        // Change serving unit to loaf
        viewModel.onServingChange(itemId, servingLoaf)
        advanceUntilIdle()

        // 2 loaves = 800g -> 2112 kcal
        assertEquals(2112.0, viewModel.items.value[0].calculatedNutrition.calories, 0.1)
    }

    @Test
    fun test6_removeOneItem_updatesTotalsAndCount() = runTest(testDispatcher) {
        val food1 = createSampleFood(1L, "Bread", 264.0, 8.0, 48.0, 2.0)
        val food2 = createSampleFood(2L, "Cheese", 250.0, 20.0, 2.0, 18.0)

        val id1 = addFoodToBasketUseCase(food1, food1.servings[0], 100.0, calculateNutritionUseCase(food1, food1.servings[0], 100.0), LocalDate.now(), MealType.BREAKFAST)
        val id2 = addFoodToBasketUseCase(food2, food2.servings[0], 100.0, calculateNutritionUseCase(food2, food2.servings[0], 100.0), LocalDate.now(), MealType.BREAKFAST)
        advanceUntilIdle()

        assertEquals(2, viewModel.items.value.size)

        // Remove food1
        viewModel.removeItem(id1)
        advanceUntilIdle()

        assertEquals(1, viewModel.items.value.size)
        assertEquals(id2, viewModel.items.value[0].id)
        assertEquals("Cheese", viewModel.items.value[0].foodNameSnapshot)
    }

    @Test
    fun test7_removeFirstMiddleAndLastItems() = runTest(testDispatcher) {
        val f1 = createSampleFood(1L, "F1", 100.0, 1.0, 1.0, 1.0)
        val f2 = createSampleFood(2L, "F2", 100.0, 1.0, 1.0, 1.0)
        val f3 = createSampleFood(3L, "F3", 100.0, 1.0, 1.0, 1.0)

        val id1 = addFoodToBasketUseCase(f1, f1.servings[0], 100.0, calculateNutritionUseCase(f1, f1.servings[0], 100.0), LocalDate.now(), MealType.BREAKFAST)
        val id2 = addFoodToBasketUseCase(f2, f2.servings[0], 100.0, calculateNutritionUseCase(f2, f2.servings[0], 100.0), LocalDate.now(), MealType.BREAKFAST)
        val id3 = addFoodToBasketUseCase(f3, f3.servings[0], 100.0, calculateNutritionUseCase(f3, f3.servings[0], 100.0), LocalDate.now(), MealType.BREAKFAST)
        advanceUntilIdle()

        // Remove middle
        viewModel.removeItem(id2)
        advanceUntilIdle()
        assertEquals(listOf(id1, id3), viewModel.items.value.map { it.id })

        // Remove first
        viewModel.removeItem(id1)
        advanceUntilIdle()
        assertEquals(listOf(id3), viewModel.items.value.map { it.id })

        // Remove last
        viewModel.removeItem(id3)
        advanceUntilIdle()
        assertTrue(viewModel.items.value.isEmpty())
    }

    @Test
    fun test8_commonDateChange_updatesAllPendingItems() = runTest(testDispatcher) {
        val f1 = createSampleFood(1L, "F1", 100.0, 1.0, 1.0, 1.0)
        val f2 = createSampleFood(2L, "F2", 100.0, 1.0, 1.0, 1.0)

        addFoodToBasketUseCase(f1, f1.servings[0], 100.0, calculateNutritionUseCase(f1, f1.servings[0], 100.0), LocalDate.now(), MealType.BREAKFAST)
        addFoodToBasketUseCase(f2, f2.servings[0], 100.0, calculateNutritionUseCase(f2, f2.servings[0], 100.0), LocalDate.now(), MealType.BREAKFAST)
        advanceUntilIdle()

        val newDate = LocalDate.now().minusDays(1)
        viewModel.setCommonDate(newDate)
        advanceUntilIdle()

        assertEquals(newDate, viewModel.commonDate.value)
        viewModel.items.value.forEach { item ->
            assertEquals(newDate, item.date)
        }
    }

    @Test
    fun test9_commonMealChange_updatesAllPendingItems() = runTest(testDispatcher) {
        val f1 = createSampleFood(1L, "F1", 100.0, 1.0, 1.0, 1.0)
        val f2 = createSampleFood(2L, "F2", 100.0, 1.0, 1.0, 1.0)

        addFoodToBasketUseCase(f1, f1.servings[0], 100.0, calculateNutritionUseCase(f1, f1.servings[0], 100.0), LocalDate.now(), MealType.BREAKFAST)
        addFoodToBasketUseCase(f2, f2.servings[0], 100.0, calculateNutritionUseCase(f2, f2.servings[0], 100.0), LocalDate.now(), MealType.BREAKFAST)
        advanceUntilIdle()

        viewModel.setCommonMealType(MealType.DINNER)
        advanceUntilIdle()

        assertEquals(MealType.DINNER, viewModel.commonMealType.value)
        viewModel.items.value.forEach { item ->
            assertEquals(MealType.DINNER, item.mealType)
        }
    }

    @Test
    fun test10_swipeReveal_doesNotDeleteUntilExplicitTapped() = runTest(testDispatcher) {
        val f1 = createSampleFood(1L, "F1", 100.0, 1.0, 1.0, 1.0)
        val id1 = addFoodToBasketUseCase(f1, f1.servings[0], 100.0, calculateNutritionUseCase(f1, f1.servings[0], 100.0), LocalDate.now(), MealType.BREAKFAST)
        advanceUntilIdle()

        viewModel.setRevealedItem(id1)
        assertEquals(id1, viewModel.revealedItemId.value)
        // Basket item is still present
        assertEquals(1, viewModel.items.value.size)

        // Close swipe
        viewModel.setRevealedItem(null)
        assertNull(viewModel.revealedItemId.value)
        assertEquals(1, viewModel.items.value.size)

        // Explicit remove
        viewModel.removeItem(id1)
        advanceUntilIdle()
        assertTrue(viewModel.items.value.isEmpty())
        assertNull(viewModel.revealedItemId.value)
    }

    @Test
    fun test11_logAllFoods_transactionallyCommitsAndClearsBasket() = runTest(testDispatcher) {
        val f1 = createSampleFood(1L, "Bread", 264.0, 8.0, 48.0, 2.0)
        val f2 = createSampleFood(2L, "Cheese", 250.0, 20.0, 2.0, 18.0)
        val targetDate = LocalDate.of(2026, 8, 23)

        addFoodToBasketUseCase(f1, f1.servings[0], 100.0, calculateNutritionUseCase(f1, f1.servings[0], 100.0), targetDate, MealType.LUNCH)
        addFoodToBasketUseCase(f2, f2.servings[0], 100.0, calculateNutritionUseCase(f2, f2.servings[0], 100.0), targetDate, MealType.LUNCH)
        advanceUntilIdle()

        var loggedSuccess = false
        viewModel.submitBasket(
            onSuccess = { loggedSuccess = true },
            onError = {}
        )
        advanceUntilIdle()

        assertTrue(loggedSuccess)
        assertTrue(viewModel.items.value.isEmpty())
        assertEquals(2, diaryRepository.storedEntries.size)
        assertEquals("Bread", diaryRepository.storedEntries[0].food.name)
        assertEquals("Cheese", diaryRepository.storedEntries[1].food.name)
        assertEquals(targetDate, diaryRepository.storedEntries[0].date)
        assertEquals(MealType.LUNCH, diaryRepository.storedEntries[0].mealType)
    }

    @Test
    fun test12_clearBasket_clearsAllPendingItems() = runTest(testDispatcher) {
        val f1 = createSampleFood(1L, "Bread", 264.0, 8.0, 48.0, 2.0)
        addFoodToBasketUseCase(f1, f1.servings[0], 100.0, calculateNutritionUseCase(f1, f1.servings[0], 100.0), LocalDate.now(), MealType.BREAKFAST)
        advanceUntilIdle()

        assertEquals(1, viewModel.items.value.size)

        viewModel.clearBasket()
        advanceUntilIdle()

        assertTrue(viewModel.items.value.isEmpty())
    }

    @Test
    fun test13_loggingMode_canBeToggled() {
        assertEquals(LoggingMode.MULTIPLE_FOODS, viewModel.loggingMode.value)
        viewModel.setLoggingMode(LoggingMode.SINGLE_FOOD_RECIPE)
        assertEquals(LoggingMode.SINGLE_FOOD_RECIPE, viewModel.loggingMode.value)
    }

    // Fake Diary Repository
    private class FakeDiaryRepository : DiaryRepository {
        val storedEntries = mutableListOf<DiaryEntry>()
        override suspend fun getDiaryForDate(date: LocalDate) = com.macrobase.app.domain.model.DailyNutritionSummary(date = date)
        override fun observeDiaryForDate(date: LocalDate) = flowOf(com.macrobase.app.domain.model.DailyNutritionSummary(date = date))
        override suspend fun getEntryById(entryId: Long) = storedEntries.firstOrNull { it.id == entryId }
        override suspend fun addEntry(entry: DiaryEntry): Long {
            storedEntries.add(entry)
            return entry.id
        }
        override suspend fun addEntries(entries: List<DiaryEntry>) {
            storedEntries.addAll(entries)
        }
        override suspend fun updateEntry(entry: DiaryEntry) {}
        override suspend fun deleteEntry(entryId: Long) {
            storedEntries.removeAll { it.id == entryId }
        }
        override suspend fun getMonthlyAdherence(year: Int, month: Int) = emptyList<com.macrobase.app.domain.model.CalendarDaySummary>()
        override fun observeMonthlyAdherence(year: Int, month: Int) = flowOf(emptyList<com.macrobase.app.domain.model.CalendarDaySummary>())
        override fun observeNutritionAggregations(startDate: LocalDate, endDate: LocalDate) = flowOf(emptyList<com.macrobase.app.domain.repository.DailyMacroAggregation>())
        override suspend fun getNutritionAggregations(startDate: LocalDate, endDate: LocalDate) = emptyList<com.macrobase.app.domain.repository.DailyMacroAggregation>()
    }
}
