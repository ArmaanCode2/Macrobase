package com.macrobase.app

import androidx.room.Room
import com.macrobase.app.core.designsystem.components.basketButtonLabel
import com.macrobase.app.core.designsystem.components.mealLabel
import com.macrobase.app.core.navigation.Screen
import com.macrobase.app.core.navigation.copyFromYesterdayLabel
import com.macrobase.app.data.database.UserDatabase
import com.macrobase.app.data.repository.DiaryRepositoryImpl
import com.macrobase.app.data.repository.basket.InMemoryBasketRepository
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.FoodSource
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.repository.GoalsRepository
import com.macrobase.app.domain.usecase.DeleteDiaryEntryUseCase
import com.macrobase.app.domain.usecase.GetDailyDiaryUseCase
import com.macrobase.app.domain.usecase.basket.AddFoodToBasketUseCase
import com.macrobase.app.feature.dashboard.HomeViewModel
import com.macrobase.app.feature.dashboard.mealForTime
import com.macrobase.app.testutil.MutableTestClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * The new app shell's behaviour (design phase 3): the "+" sheet's meal for the time of day, its
 * "Copy ... from yesterday", and the labels the bar and the sheet show.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DesignShellTests {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var db: UserDatabase
    private lateinit var diary: DiaryRepositoryImpl
    private val basket = InMemoryBasketRepository()

    private val today = LocalDate.of(2026, 10, 17)
    private val yesterday = today.minusDays(1)

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

    private fun entry(uuid: String, name: String, meal: MealType, date: LocalDate, calories: Double): DiaryEntry {
        val serving = Serving(description = "1 bowl", gramWeight = 150.0, isDefault = true)
        val nutrition = Nutrition(calories = calories, proteinGrams = 10.0, carbsGrams = 30.0, fatGrams = 5.0)
        return DiaryEntry(
            uuid = uuid,
            date = date,
            mealType = meal,
            food = Food(id = 1_896_880_878_330_341L + uuid.hashCode(), uuid = "f-$uuid", source = FoodSource.BUILT_IN, name = name, nutrition = nutrition, servings = listOf(serving)),
            serving = serving,
            quantity = 2.0,
            calculatedNutrition = nutrition,
            loggedAt = Instant.ofEpochMilli(1_790_000_000_000L + uuid.hashCode())
        )
    }

    private fun viewModel(time: LocalTime = LocalTime.NOON) = HomeViewModel(
        getDailyDiaryUseCase = GetDailyDiaryUseCase(diary),
        deleteDiaryEntryUseCase = DeleteDiaryEntryUseCase(diary),
        addFoodToBasketUseCase = AddFoodToBasketUseCase(basket),
        clock = MutableTestClock(today, time)
    )

    @Test
    fun theSheetStartsOnTheMealForTheTimeOfDay() {
        assertEquals(MealType.BREAKFAST, mealForTime(LocalTime.of(0, 0)))
        assertEquals(MealType.BREAKFAST, mealForTime(LocalTime.of(10, 59)))
        assertEquals(MealType.LUNCH, mealForTime(LocalTime.of(11, 0)))
        assertEquals(MealType.LUNCH, mealForTime(LocalTime.of(15, 59)))
        assertEquals(MealType.DINNER, mealForTime(LocalTime.of(16, 0)))
        assertEquals(MealType.DINNER, mealForTime(LocalTime.of(23, 59)))
        assertEquals(MealType.BREAKFAST, viewModel(LocalTime.of(8, 30)).currentMeal())
        assertEquals(MealType.DINNER, viewModel(LocalTime.of(19, 0)).currentMeal())
    }

    // Logging the copy must never log yesterday a second time, and must not move what was already staged
    @Test
    fun copyFromYesterdayFillsTheBasketForTodayAndLeavesOtherItemsAlone() = runBlocking {
        diary.addEntries(
            listOf(
                entry("b1", "Poha", MealType.BREAKFAST, yesterday, 272.0),
                entry("b2", "Tea", MealType.BREAKFAST, yesterday, 71.0),
                entry("l1", "Rice", MealType.LUNCH, yesterday, 300.0)
            )
        )
        val staged = entry("s1", "Apple", MealType.PM_SNACK, today.minusDays(3), 95.0)
        AddFoodToBasketUseCase(basket)(staged.food, staged.serving, 1.0, staged.calculatedNutrition, staged.date, staged.mealType)

        var done = false
        viewModel().copyMealFrom(yesterday, MealType.BREAKFAST, toMeal = MealType.BREAKFAST) { done = true }
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(done)
        val items = basket.items.value
        assertEquals(listOf("Apple", "Poha", "Tea"), items.map { it.foodNameSnapshot })
        val copied = items.drop(1)
        assertTrue(copied.all { it.date == today && it.mealType == MealType.BREAKFAST })
        assertEquals(listOf(2.0, 2.0), copied.map { it.quantity })
        assertEquals(listOf(272.0, 71.0), copied.map { it.calculatedNutrition.calories })
        // The item staged earlier keeps its own day and meal
        assertEquals(today.minusDays(3), items.first().date)
        assertEquals(MealType.PM_SNACK, items.first().mealType)
        // Nothing is logged until the basket is
        assertTrue(diary.getDiaryForDate(today).meals.all { it.entries.isEmpty() })
    }

    @Test
    fun copyFromYesterdayCanLandInAnotherMeal() {
        runBlocking { diary.addEntries(listOf(entry("l1", "Rice", MealType.LUNCH, yesterday, 300.0))) }

        viewModel().copyMealFrom(yesterday, MealType.LUNCH, toMeal = MealType.DINNER)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(today to MealType.DINNER), basket.items.value.map { it.date to it.mealType })
    }

    @Test
    fun anEmptyMealYesterdayCopiesNothing() {
        var done = false
        viewModel().copyMealFrom(yesterday, MealType.DINNER) { done = true }
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(done)
        assertTrue(basket.items.value.isEmpty())
    }

    @Test
    fun labelsReadInSentenceCase() {
        assertEquals(
            listOf("Breakfast", "Lunch", "Dinner", "AM snack", "PM snack", "Late snack"),
            MealType.entries.sortedBy { it.order }.map(::mealLabel)
        )
        // The stored names stay upper case
        assertEquals("BREAKFAST", MealType.BREAKFAST.displayName)
        assertEquals("Copy breakfast from yesterday", copyFromYesterdayLabel(MealType.BREAKFAST))
        assertEquals("Copy AM snack from yesterday", copyFromYesterdayLabel(MealType.AM_SNACK))
        assertEquals("Copy late snack from yesterday", copyFromYesterdayLabel(MealType.SNACK))
        assertEquals("Basket, 1 item", basketButtonLabel(1))
        assertEquals("Basket, 3 items", basketButtonLabel(3))
    }

    @Test
    fun logWeightOpensTheWeightScreenWithItsDialog() {
        assertEquals("weight?openLog=true", Screen.Weight.createRoute(openLog = true))
        assertEquals("weight?dateEpochDay=5&openLog=true", Screen.Weight.createRoute(5L, openLog = true))
        // Existing links are unchanged
        assertEquals("weight", Screen.Weight.createRoute())
        assertEquals("weight?dateEpochDay=5", Screen.Weight.createRoute(5L))
    }
}
