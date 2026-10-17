package com.macrobase.app

import com.macrobase.app.data.repository.basket.InMemoryBasketRepository
import com.macrobase.app.domain.model.CustomFood
import com.macrobase.app.domain.model.DailyNutritionSummary
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.ServingUnit
import com.macrobase.app.domain.repository.DiaryRepository
import com.macrobase.app.domain.repository.FoodRepository
import com.macrobase.app.domain.usecase.CalculateNutritionForServingUseCase
import com.macrobase.app.domain.usecase.DeleteDiaryEntryUseCase
import com.macrobase.app.domain.usecase.GetDailyDiaryUseCase
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
import com.macrobase.app.feature.basket.basketWhenLabel
import com.macrobase.app.feature.dashboard.HomeViewModel
import com.macrobase.app.feature.detail.FoodDetailViewModel
import com.macrobase.app.feature.widget.MacroBaseWidgetProvider
import com.macrobase.app.testutil.MutableTestClock
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * P3 phase 4: the logging journey never loses input or logs to the wrong day, and a widget tap
 * opens today's dashboard once (BUG-030, BUG-038, BUG-039, BUG-040).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class P3PhaseFourLoggingFlowTests {

    private val testDispatcher = StandardTestDispatcher()
    private val day = LocalDate.of(2026, 10, 9)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---------------------------------------------------------------------------------------
    // BUG-030: a widget tap opens today's dashboard once
    // ---------------------------------------------------------------------------------------

    @Test
    fun aWidgetTapOpensTodayOnAFreshStartOrANewIntent() {
        assertTrue(shouldOpenTodayFromWidget(MacroBaseWidgetProvider.ACTION_WIDGET_OPEN_TODAY, isRecreated = false, launchedFromHistory = false))
    }

    // A widget drawn before 1.2.4 sends the old action until it is redrawn
    @Test
    fun aWidgetDrawnBeforeTheUpdateAlsoOpensToday() {
        assertTrue(shouldOpenTodayFromWidget(MacroBaseWidgetProvider.LEGACY_ACTION_WIDGET_LOG_FOOD, isRecreated = false, launchedFromHistory = false))
    }

    @Test
    fun aRecreatedActivityDoesNotOpenTodayAgain() {
        assertFalse(shouldOpenTodayFromWidget(MacroBaseWidgetProvider.ACTION_WIDGET_OPEN_TODAY, isRecreated = true, launchedFromHistory = false))
    }

    // Android relaunches a task from Recents with the intent that first started it
    @Test
    fun reopeningFromRecentsDoesNotOpenTodayAgain() {
        assertFalse(shouldOpenTodayFromWidget(MacroBaseWidgetProvider.ACTION_WIDGET_OPEN_TODAY, isRecreated = false, launchedFromHistory = true))
    }

    @Test
    fun anOrdinaryLaunchDoesNotNavigate() {
        assertFalse(shouldOpenTodayFromWidget("android.intent.action.MAIN", isRecreated = false, launchedFromHistory = false))
        assertFalse(shouldOpenTodayFromWidget(null, isRecreated = false, launchedFromHistory = false))
    }

    // ---------------------------------------------------------------------------------------
    // BUG-038: a recreated Food Detail screen keeps what the user typed
    // ---------------------------------------------------------------------------------------

    @Test
    fun openingAgainWithTheSameArgumentsKeepsWhatTheUserChose() = runTest(testDispatcher) {
        val env = FoodDetailEnv()
        env.viewModel.openFood(OATS_ID, MealType.LUNCH, day)
        advanceUntilIdle()

        env.viewModel.onServingSelected(oats.servings[1])
        env.viewModel.onQuantityChange("2.")
        env.viewModel.onMealTypeSelected(MealType.DINNER)
        env.viewModel.onDateSelected(day.minusDays(1))
        env.viewModel.openFood(OATS_ID, MealType.LUNCH, day)
        advanceUntilIdle()

        val state = env.viewModel.uiState.value
        assertEquals("2.", state.quantityInputText)
        assertEquals(oats.servings[1], state.selectedServing)
        assertEquals(MealType.DINNER, state.targetMealType)
        assertEquals(day.minusDays(1), state.targetDate)
        assertEquals(1, env.foods.lookups)
    }

    // Shown again after its entry was saved elsewhere: show the saved entry, so Save cannot undo it
    @Test
    fun aDiaryEntrySavedElsewhereIsShownFresh() = runTest(testDispatcher) {
        val env = FoodDetailEnv()
        env.diary.put(entry(id = 1L, quantity = 1.0))
        env.viewModel.openFood(OATS_ID, MealType.LUNCH, day, entryId = 1L)
        advanceUntilIdle()
        env.viewModel.onQuantityChange("2")

        env.viewModel.openFood(OATS_ID, MealType.LUNCH, day, entryId = 1L)
        advanceUntilIdle()
        assertEquals("2", env.viewModel.uiState.value.quantityInputText)

        env.diary.put(entry(id = 1L, quantity = 3.0))
        env.viewModel.openFood(OATS_ID, MealType.LUNCH, day, entryId = 1L)
        advanceUntilIdle()
        assertEquals("3", env.viewModel.uiState.value.quantityInputText)
    }

    @Test
    fun openingAnotherFoodLoadsIt() = runTest(testDispatcher) {
        val env = FoodDetailEnv()
        env.viewModel.openFood(OATS_ID, MealType.LUNCH, day)
        advanceUntilIdle()
        env.viewModel.onQuantityChange("2.5")

        env.viewModel.openFood(RICE_ID, MealType.LUNCH, day)
        advanceUntilIdle()

        assertEquals("Rice", env.viewModel.uiState.value.food?.name)
        assertEquals("1", env.viewModel.uiState.value.quantityInputText)
    }

    // Copy switches the screen to the copied basket item; rotating afterwards must not go back
    @Test
    fun rotatingAfterCopyKeepsTheCopiedItem() = runTest(testDispatcher) {
        val env = FoodDetailEnv()
        env.diary.put(entry(id = 1L, quantity = 1.0))
        env.viewModel.openFood(OATS_ID, MealType.LUNCH, day, entryId = 1L)
        advanceUntilIdle()

        // What the Copy button does
        env.viewModel.copyEntry { foodId, mealType, dateEpochDay, basketItemId ->
            env.viewModel.loadFood(foodId, mealType, LocalDate.ofEpochDay(dateEpochDay), 0L, basketItemId)
        }
        advanceUntilIdle()
        env.viewModel.onQuantityChange("3")
        env.viewModel.openFood(OATS_ID, MealType.LUNCH, day, entryId = 1L)
        advanceUntilIdle()

        assertFalse(env.viewModel.isEditingDiary())
        assertTrue(env.viewModel.isEditingBasket())
        assertEquals("3", env.viewModel.uiState.value.quantityInputText)
        assertEquals(3.0, env.basket.items.value.single().quantity, 0.0)
    }

    @Test
    fun aFoodThatWasNotFoundIsLookedUpAgain() = runTest(testDispatcher) {
        val env = FoodDetailEnv()
        env.viewModel.openFood(MISSING_ID, MealType.LUNCH, day)
        advanceUntilIdle()
        assertEquals("Food record not found", env.viewModel.uiState.value.errorMessage)

        env.viewModel.openFood(MISSING_ID, MealType.LUNCH, day)
        advanceUntilIdle()

        assertEquals(2, env.foods.lookups)
    }

    // ---------------------------------------------------------------------------------------
    // BUG-040: a food opened without a day logs to the day it is logged on
    // ---------------------------------------------------------------------------------------

    @Test
    fun aFoodOpenedWithoutADayMovesToTheNewDayOnResume() = runTest(testDispatcher) {
        val clock = MutableTestClock(day, LocalTime.of(23, 59))
        val env = FoodDetailEnv(clock)
        env.viewModel.openFood(OATS_ID, MealType.LUNCH, null)
        advanceUntilIdle()
        assertEquals(day, env.viewModel.uiState.value.targetDate)

        clock.advanceBy(Duration.ofMinutes(2))
        env.viewModel.onResumed()

        assertEquals(day.plusDays(1), env.viewModel.uiState.value.targetDate)
    }

    // Left open in the foreground across midnight: no resume, but the log still goes to the new day
    @Test
    fun aFoodOpenedWithoutADayLogsToTheNewDayWithoutAResume() = runTest(testDispatcher) {
        val clock = MutableTestClock(day, LocalTime.of(23, 59))
        val env = FoodDetailEnv(clock)
        env.viewModel.openFood(OATS_ID, MealType.LUNCH, null)
        advanceUntilIdle()

        clock.advanceBy(Duration.ofMinutes(2))
        env.viewModel.keepInBasket {}
        advanceUntilIdle()

        assertEquals(day.plusDays(1), env.basket.items.value.single().date)
    }

    @Test
    fun aDayGivenOrChosenStaysAfterMidnight() = runTest(testDispatcher) {
        val clock = MutableTestClock(day, LocalTime.of(23, 59))
        val given = FoodDetailEnv(clock)
        given.viewModel.openFood(OATS_ID, MealType.LUNCH, day)
        val chosen = FoodDetailEnv(clock)
        chosen.viewModel.openFood(OATS_ID, MealType.LUNCH, null)
        advanceUntilIdle()
        chosen.viewModel.onDateSelected(day)

        clock.advanceBy(Duration.ofMinutes(2))
        given.viewModel.onResumed()
        chosen.viewModel.onResumed()

        assertEquals(day, given.viewModel.uiState.value.targetDate)
        assertEquals(day, chosen.viewModel.uiState.value.targetDate)
    }

    // ---------------------------------------------------------------------------------------
    // BUG-039: a basket of different days or meals says so
    // ---------------------------------------------------------------------------------------

    @Test
    fun aMixedBasketShowsMixedAndLogsEachItemUnderItsOwnDayAndMeal() = runTest(testDispatcher) {
        val env = BasketEnv()
        env.add(oats, day, MealType.BREAKFAST)
        env.add(rice, day.minusDays(1), MealType.DINNER)
        advanceUntilIdle()

        assertTrue(env.viewModel.isMixedDateOrMeal.value)
        assertNull(env.viewModel.commonDate.value)
        assertNull(env.viewModel.commonMealType.value)

        env.viewModel.submitBasket(onSuccess = {})
        advanceUntilIdle()

        val logged = env.diary.entries.associate { it.food.name to (it.date to it.mealType) }
        assertEquals(day to MealType.BREAKFAST, logged["Rolled Oats"])
        assertEquals(day.minusDays(1) to MealType.DINNER, logged["Rice"])
    }

    @Test
    fun theSameDayWithDifferentMealsShowsTheDay() = runTest(testDispatcher) {
        val env = BasketEnv()
        env.add(oats, day, MealType.BREAKFAST)
        env.add(rice, day, MealType.LUNCH)
        advanceUntilIdle()

        assertTrue(env.viewModel.isMixedDateOrMeal.value)
        assertEquals(day, env.viewModel.commonDate.value)
        assertNull(env.viewModel.commonMealType.value)
    }

    @Test
    fun pickingOneDayAndMealForAllClearsTheMix() = runTest(testDispatcher) {
        val env = BasketEnv()
        env.add(oats, day, MealType.BREAKFAST)
        env.add(rice, day.minusDays(1), MealType.DINNER)
        advanceUntilIdle()

        env.viewModel.setCommonDate(day)
        env.viewModel.setCommonMealType(MealType.LUNCH)
        advanceUntilIdle()

        assertFalse(env.viewModel.isMixedDateOrMeal.value)
        assertEquals(day, env.viewModel.commonDate.value)
        assertEquals(MealType.LUNCH, env.viewModel.commonMealType.value)
    }

    // An item added later with its own day makes the basket mixed again instead of hiding it
    @Test
    fun anItemAddedAfterPickingADayKeepsItsOwnDayAndShowsMixed() = runTest(testDispatcher) {
        val env = BasketEnv()
        env.add(oats, day, MealType.BREAKFAST)
        advanceUntilIdle()
        env.viewModel.setCommonDate(day.minusDays(2))
        advanceUntilIdle()

        env.add(rice, day, MealType.BREAKFAST)
        advanceUntilIdle()

        assertTrue(env.viewModel.isMixedDateOrMeal.value)
        assertNull(env.viewModel.commonDate.value)
    }

    @Test
    fun theWhenLabelSaysWhichPartIsMixed() {
        val today = day
        assertEquals("Today, BREAKFAST", basketWhenLabel(today, MealType.BREAKFAST, today))
        assertEquals("Yesterday, mixed meals", basketWhenLabel(today.minusDays(1), null, today))
        assertEquals("Mixed days, DINNER", basketWhenLabel(null, MealType.DINNER, today))
        assertEquals("Mixed (see each item)", basketWhenLabel(null, null, today))
    }

    // ---------------------------------------------------------------------------------------
    // BUG-040: the dashboard moves to the new day after midnight
    // ---------------------------------------------------------------------------------------

    @Test
    fun aDashboardLeftOnTodayMovesToTheNewDayWhenTheAppResumes() {
        val clock = MutableTestClock(day, LocalTime.of(23, 59))
        val vm = homeViewModel(clock)

        clock.advanceBy(Duration.ofMinutes(2))
        vm.onAppResumed()

        assertEquals(day.plusDays(1), vm.selectedDate.value)
        assertEquals(day.plusDays(1), vm.today())
    }

    @Test
    fun aDayTheUserPickedStaysAfterMidnight() {
        val clock = MutableTestClock(day, LocalTime.of(23, 59))
        val vm = homeViewModel(clock)
        vm.onSelectDate(day.minusDays(3))

        clock.advanceBy(Duration.ofMinutes(2))
        vm.onAppResumed()

        assertEquals(day.minusDays(3), vm.selectedDate.value)
    }

    @Test
    fun goingBackToTodayFollowsTheCalendarAgain() {
        val clock = MutableTestClock(day, LocalTime.of(23, 59))
        val vm = homeViewModel(clock)
        vm.onPreviousDay()
        vm.onNextDay()

        clock.advanceBy(Duration.ofMinutes(2))
        vm.onAppResumed()

        assertEquals(day.plusDays(1), vm.selectedDate.value)
    }

    // Search and Food Detail get no day, so they log to whatever day it is when the food is logged
    @Test
    fun aDashboardOnTodayGivesNoDayForNewLogs() {
        val clock = MutableTestClock(day, LocalTime.of(9, 0))
        val vm = homeViewModel(clock)
        assertNull(vm.dateForNewLog())

        vm.onPreviousDay()
        assertEquals(day.minusDays(1), vm.dateForNewLog())
    }

    @Test
    fun aPickedDayThatBecomesTodayFollowsTheCalendarFromThen() {
        val clock = MutableTestClock(day, LocalTime.of(23, 0))
        val vm = homeViewModel(clock)
        vm.onNextDay()

        clock.advanceBy(Duration.ofHours(2))
        vm.onAppResumed()
        clock.advanceBy(Duration.ofDays(1))
        vm.onAppResumed()

        assertEquals(day.plusDays(2), vm.selectedDate.value)
    }

    @Test
    fun resumingOnTheSameDayChangesNothing() {
        val clock = MutableTestClock(day, LocalTime.of(9, 0))
        val vm = homeViewModel(clock)
        vm.setRevealedEntry(7L)

        clock.advanceBy(Duration.ofHours(2))
        vm.onAppResumed()

        assertEquals(day, vm.selectedDate.value)
        assertEquals(7L, vm.revealedEntryId.value)
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private val oats = food(OATS_ID, "Rolled Oats", 389.0)
    private val rice = food(RICE_ID, "Rice", 130.0)

    private fun food(id: Long, name: String, calories: Double) = Food(
        id = id,
        uuid = "food-$id",
        name = name,
        nutrition = Nutrition(calories = calories, proteinGrams = 5.0, carbsGrams = 20.0, fatGrams = 2.0),
        servings = listOf(
            Serving(id = id * 10, description = "100 g", unit = ServingUnit.GRAMS, quantity = 1.0, gramWeight = 100.0, isDefault = true),
            Serving(id = id * 10 + 1, description = "1/2 cup", unit = ServingUnit.CUP, quantity = 0.5, gramWeight = 40.0, sequence = 1)
        )
    )

    private fun entry(id: Long, quantity: Double): DiaryEntry {
        val serving = oats.servings[0]
        return DiaryEntry(
            id = id,
            uuid = "entry-$id",
            date = day,
            mealType = MealType.LUNCH,
            food = oats,
            serving = serving,
            quantity = quantity,
            calculatedNutrition = CalculateNutritionForServingUseCase()(oats, serving, quantity),
            loggedAt = Instant.ofEpochMilli(1_790_000_000_000L)
        )
    }

    private fun homeViewModel(clock: MutableTestClock): HomeViewModel {
        val diary = RecordingDiaryRepository()
        return HomeViewModel(
            getDailyDiaryUseCase = GetDailyDiaryUseCase(diary),
            deleteDiaryEntryUseCase = DeleteDiaryEntryUseCase(diary),
            addFoodToBasketUseCase = AddFoodToBasketUseCase(InMemoryBasketRepository()),
            clock = clock
        )
    }

    private inner class FoodDetailEnv(clock: Clock = MutableTestClock(day)) {
        val basket = InMemoryBasketRepository()
        val diary = RecordingDiaryRepository()
        val foods = CountingFoodRepository(listOf(oats, rice))
        val viewModel = FoodDetailViewModel(
            GetFoodDetailsUseCase(foods),
            CalculateNutritionForServingUseCase(),
            AddFoodToBasketUseCase(basket),
            GetBasketItemsUseCase(basket),
            UpdateBasketItemUseCase(basket),
            RemoveBasketItemUseCase(basket),
            CommitSingleBasketItemUseCase(basket, diary),
            UpdateDiaryEntryUseCase(diary),
            DeleteDiaryEntryUseCase(diary),
            GetDiaryEntryUseCase(diary),
            clock
        )
    }

    private inner class BasketEnv {
        val basket = InMemoryBasketRepository()
        val diary = RecordingDiaryRepository()
        private val calculate = CalculateNutritionForServingUseCase()
        val viewModel = BasketViewModel(
            getBasketItemsUseCase = GetBasketItemsUseCase(basket),
            removeBasketItemUseCase = RemoveBasketItemUseCase(basket),
            updateBasketItemUseCase = UpdateBasketItemUseCase(basket),
            updateAllBasketItemsUseCase = UpdateAllBasketItemsUseCase(basket),
            clearBasketUseCase = ClearBasketUseCase(basket),
            commitBasketUseCase = CommitBasketUseCase(basket, diary),
            calculateNutritionForServingUseCase = calculate
        )

        fun add(food: Food, date: LocalDate, mealType: MealType) {
            val serving = food.servings.first()
            AddFoodToBasketUseCase(basket)(food, serving, 1.0, calculate(food, serving, 1.0), date, mealType)
        }
    }

    private class CountingFoodRepository(private val foods: List<Food>) : FoodRepository {
        var lookups = 0
        override suspend fun searchFoods(query: String, limit: Int) = foods
        override suspend fun getFoodById(id: Long): Food? {
            lookups++
            return foods.firstOrNull { it.id == id }
        }
        override suspend fun getFoodByBarcode(barcode: String): Food? = null
        override suspend fun getFoodServings(foodId: Long) = foods.firstOrNull { it.id == foodId }?.servings.orEmpty()
        override suspend fun getRecentFoods(limit: Int) = emptyList<Food>()
        override suspend fun getFavoriteFoods() = emptyList<Food>()
        override fun observeCustomFoods() = flowOf(emptyList<Food>())
        override suspend fun getCustomFoods() = emptyList<Food>()
        override suspend fun saveCustomFood(food: CustomFood) = 0L
        override suspend fun deleteCustomFood(id: Long) {}
    }

    private class RecordingDiaryRepository : DiaryRepository {
        val entries = mutableListOf<DiaryEntry>()

        /** Stores [entry], replacing one with the same id, as a save from another screen would. */
        fun put(entry: DiaryEntry) {
            entries.removeAll { it.id == entry.id }
            entries.add(entry)
        }

        override suspend fun addEntries(entries: List<DiaryEntry>) {
            entries.forEach { this.entries.add(it.copy(id = this.entries.size + 1L)) }
        }
        override suspend fun addEntry(entry: DiaryEntry): Long {
            addEntries(listOf(entry))
            return entries.last().id
        }
        override suspend fun getDiaryForDate(date: LocalDate) = DailyNutritionSummary(date = date)
        override fun observeDiaryForDate(date: LocalDate) = flowOf(DailyNutritionSummary(date = date))
        override suspend fun getEntryById(entryId: Long) = entries.firstOrNull { it.id == entryId }
        override suspend fun updateEntry(entry: DiaryEntry) {}
        override suspend fun deleteEntry(entryId: Long) {
            entries.removeAll { it.id == entryId }
        }
        override suspend fun getMonthlyAdherence(year: Int, month: Int) = emptyList<com.macrobase.app.domain.model.CalendarDaySummary>()
        override fun observeMonthlyAdherence(year: Int, month: Int) = flowOf(emptyList<com.macrobase.app.domain.model.CalendarDaySummary>())
        override fun observeNutritionAggregations(startDate: LocalDate, endDate: LocalDate) = flowOf(emptyList<com.macrobase.app.domain.repository.DailyMacroAggregation>())
        override suspend fun getNutritionAggregations(startDate: LocalDate, endDate: LocalDate) = emptyList<com.macrobase.app.domain.repository.DailyMacroAggregation>()
    }

    private companion object {
        const val OATS_ID = 1_001L
        const val RICE_ID = 1_002L
        const val MISSING_ID = 9_999L
    }
}
