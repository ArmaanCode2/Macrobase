package com.macrobase.app

import androidx.room.Room
import com.macrobase.app.data.database.UserDatabase
import com.macrobase.app.data.repository.DiaryRepositoryImpl
import com.macrobase.app.data.repository.basket.InMemoryBasketRepository
import com.macrobase.app.domain.model.CalendarDaySummary
import com.macrobase.app.domain.model.DailyNutritionSummary
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.FoodSource
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.Meal
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.repository.DailyMacroAggregation
import com.macrobase.app.domain.repository.DiaryRepository
import com.macrobase.app.domain.repository.GoalsRepository
import com.macrobase.app.domain.usecase.DeleteDiaryEntryUseCase
import com.macrobase.app.domain.usecase.GetDailyDiaryUseCase
import com.macrobase.app.domain.usecase.MoveDiaryEntriesUseCase
import com.macrobase.app.domain.usecase.basket.AddFoodToBasketUseCase
import com.macrobase.app.feature.dashboard.HomeViewModel
import com.macrobase.app.feature.dashboard.foodCountLabel
import com.macrobase.app.feature.dashboard.isMealMoveChange
import com.macrobase.app.feature.dashboard.mealSummaryLine
import com.macrobase.app.testutil.MutableTestClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

/** Copy, Move and Delete for a whole meal from the meal card, against the real diary table. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MealActionsTests {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var db: UserDatabase
    private lateinit var diary: DiaryRepositoryImpl
    private val basket = InMemoryBasketRepository()

    private val today = LocalDate.of(2026, 10, 15)
    private val day = today.minusDays(1)

    private val goals = object : GoalsRepository {
        private val state = MutableStateFlow(Goal())
        override suspend fun getGoals() = state.value
        override fun observeGoals() = state
        override suspend fun updateGoals(goals: Goal) { state.value = goals }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), UserDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        diary = DiaryRepositoryImpl(db, goals)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun entry(
        uuid: String,
        name: String,
        meal: MealType,
        calories: Double,
        date: LocalDate = day,
        fiber: Double? = null,
        sugar: Double? = 0.0
    ): DiaryEntry {
        val serving = Serving(description = "1 bowl", gramWeight = 150.0, isDefault = true)
        val nutrition = Nutrition(
            calories = calories,
            proteinGrams = calories / 20.0,
            carbsGrams = calories / 10.0,
            fatGrams = calories / 40.0,
            fiberGrams = fiber,
            sugarGrams = sugar
        )
        return DiaryEntry(
            uuid = uuid,
            date = date,
            mealType = meal,
            food = Food(id = 1_896_880_878_330_341L + uuid.hashCode(), uuid = "f-$uuid", source = FoodSource.BUILT_IN, name = name, nutrition = nutrition, servings = listOf(serving)),
            serving = serving,
            quantity = 1.5,
            calculatedNutrition = nutrition,
            loggedAt = Instant.ofEpochMilli(1_790_000_000_000L + uuid.hashCode())
        )
    }

    private val lunchFoods = listOf(
        entry("l1", "Rice", MealType.LUNCH, 300.0),
        entry("l2", "Dal", MealType.LUNCH, 220.0, fiber = 6.0),
        entry("l3", "Curd", MealType.LUNCH, 120.0, sugar = null)
    )

    private fun logDiary() = runBlocking {
        diary.addEntries(lunchFoods + entry("d1", "Roti", MealType.DINNER, 180.0))
        diary.addEntries(listOf(entry("b0", "Oats", MealType.BREAKFAST, 250.0, date = day.minusDays(1))))
    }

    private fun meal(date: LocalDate, type: MealType): Meal = runBlocking {
        diary.getDiaryForDate(date).meals.single { it.type == type }
    }

    private fun viewModel() = HomeViewModel(
        getDailyDiaryUseCase = GetDailyDiaryUseCase(diary),
        deleteDiaryEntryUseCase = DeleteDiaryEntryUseCase(diary),
        addFoodToBasketUseCase = AddFoodToBasketUseCase(basket),
        clock = MutableTestClock(today),
        moveDiaryEntriesUseCase = MoveDiaryEntriesUseCase(diary)
    )

    @Test
    fun copyingAMealPutsEveryFoodInTheBasketInTheMealsOrder() {
        logDiary()
        val lunch = meal(day, MealType.LUNCH)

        viewModel().copyMeal(lunch)
        testDispatcher.scheduler.advanceUntilIdle()

        val items = basket.items.value
        assertEquals(listOf("Rice", "Dal", "Curd"), items.map { it.foodNameSnapshot })
        // Under the day and meal they came from, as Copy on one food does; the basket picks where they go
        assertTrue(items.all { it.date == day && it.mealType == MealType.LUNCH })
        assertEquals(lunch.entries.map { it.calculatedNutrition }, items.map { it.calculatedNutrition })
        assertEquals(listOf(1.5, 1.5, 1.5), items.map { it.quantity })
        // Nothing is logged until the user logs the basket
        assertEquals(3, meal(day, MealType.LUNCH).entries.size)
        assertEquals(1, meal(day, MealType.DINNER).entries.size)
    }

    @Test
    fun movingAMealChangesOnlyItsDayAndMeal() {
        logDiary()
        val lunch = meal(day, MealType.LUNCH)
        val target = day.minusDays(1)

        viewModel().moveMeal(lunch, target, MealType.DINNER)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(meal(day, MealType.LUNCH).entries.isEmpty())
        assertEquals(listOf("d1"), meal(day, MealType.DINNER).entries.map { it.uuid })
        assertEquals(listOf("b0"), meal(target, MealType.BREAKFAST).entries.map { it.uuid })

        val moved = meal(target, MealType.DINNER).entries
        // Same rows with everything they logged; only the day and meal differ
        assertEquals(lunch.entries.map { it.copy(date = target, mealType = MealType.DINNER) }, moved)
        // A nutrient the food did not state stays unknown, and a real zero stays zero
        assertNull(moved.single { it.uuid == "l1" }.calculatedNutrition.fiberGrams)
        assertEquals(0.0, moved.single { it.uuid == "l1" }.calculatedNutrition.sugarGrams!!, 0.0)
        assertNull(moved.single { it.uuid == "l3" }.calculatedNutrition.sugarGrams)
    }

    @Test
    fun movingAMealIntoAMealWithFoodKeepsBoth() {
        logDiary()

        viewModel().moveMeal(meal(day, MealType.LUNCH), day, MealType.DINNER)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(setOf("l1", "l2", "l3", "d1"), meal(day, MealType.DINNER).entries.map { it.uuid }.toSet())
        assertEquals(820.0, meal(day, MealType.DINNER).totalCalories, 1e-9)
    }

    @Test
    fun deletingAMealLeavesEveryOtherMeal() {
        logDiary()

        viewModel().deleteMeal(meal(day, MealType.LUNCH))
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(meal(day, MealType.LUNCH).entries.isEmpty())
        assertEquals(listOf("d1"), meal(day, MealType.DINNER).entries.map { it.uuid })
        assertEquals(listOf("b0"), meal(day.minusDays(1), MealType.BREAKFAST).entries.map { it.uuid })
        assertTrue(basket.items.value.isEmpty())
    }

    @Test
    fun anEmptyMealHasNothingToCopyMoveOrDelete() {
        logDiary()
        val empty = meal(day, MealType.AM_SNACK)
        val vm = viewModel()

        vm.copyMeal(empty)
        vm.moveMeal(empty, today, MealType.LUNCH)
        vm.deleteMeal(empty)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(basket.items.value.isEmpty())
        assertEquals(3, meal(day, MealType.LUNCH).entries.size)
        assertTrue(meal(today, MealType.LUNCH).entries.isEmpty())
    }

    // A repository that only deletes and updates one entry at a time still moves and deletes a meal
    @Test
    fun aRepositoryWithoutMealCallsUsesItsOneEntryCalls() = runBlocking {
        val stored = lunchFoods.mapIndexed { i, e -> e.copy(id = i + 1L) }.associateBy { it.id }.toMutableMap()
        val oneAtATime = object : DiaryRepository {
            override suspend fun getDiaryForDate(date: LocalDate) = DailyNutritionSummary(date = date)
            override fun observeDiaryForDate(date: LocalDate): Flow<DailyNutritionSummary> = flowOf(DailyNutritionSummary(date = date))
            override suspend fun getEntryById(entryId: Long) = stored[entryId]
            override suspend fun addEntry(entry: DiaryEntry) = entry.id
            override suspend fun addEntries(entries: List<DiaryEntry>) {}
            override suspend fun updateEntry(entry: DiaryEntry) { stored[entry.id] = entry }
            override suspend fun deleteEntry(entryId: Long) { stored.remove(entryId) }
            override suspend fun getMonthlyAdherence(year: Int, month: Int) = emptyList<CalendarDaySummary>()
            override fun observeMonthlyAdherence(year: Int, month: Int): Flow<List<CalendarDaySummary>> = flowOf(emptyList())
            override fun observeNutritionAggregations(startDate: LocalDate, endDate: LocalDate): Flow<List<DailyMacroAggregation>> = flowOf(emptyList())
            override suspend fun getNutritionAggregations(startDate: LocalDate, endDate: LocalDate) = emptyList<DailyMacroAggregation>()
        }

        MoveDiaryEntriesUseCase(oneAtATime)(listOf(1L, 2L), today, MealType.PM_SNACK)
        assertEquals(listOf(MealType.PM_SNACK, MealType.PM_SNACK, MealType.LUNCH), stored.values.sortedBy { it.id }.map { it.mealType })
        assertEquals(listOf(today, today, day), stored.values.sortedBy { it.id }.map { it.date })

        DeleteDiaryEntryUseCase(oneAtATime)(listOf(1L, 3L))
        assertEquals(setOf(2L), stored.keys)
    }

    @Test
    fun theCardNamesTheMealItActsOn() {
        logDiary()
        val lunch = meal(day, MealType.LUNCH)

        assertEquals("1 food", foodCountLabel(1))
        assertEquals("3 foods", foodCountLabel(3))
        assertEquals("3 foods \u2022 640 kcal \u2022 Yesterday", mealSummaryLine(lunch, day, today))
        assertEquals("3 foods \u2022 640 kcal \u2022 Today", mealSummaryLine(lunch, today, today))
    }

    // Save waits until another day or meal is picked
    @Test
    fun movingAMealOntoItselfIsNotAChange() {
        assertFalse(isMealMoveChange(day, MealType.LUNCH, day, MealType.LUNCH))
        assertTrue(isMealMoveChange(day, MealType.LUNCH, today, MealType.LUNCH))
        assertTrue(isMealMoveChange(day, MealType.LUNCH, day, MealType.DINNER))
    }
}
