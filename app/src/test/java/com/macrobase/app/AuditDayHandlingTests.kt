package com.macrobase.app

import com.macrobase.app.data.repository.basket.InMemoryBasketRepository
import com.macrobase.app.domain.model.CustomFood
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.UserPreferences
import com.macrobase.app.domain.model.WaterEntry
import com.macrobase.app.domain.model.WeightEntry
import com.macrobase.app.domain.repository.DiaryRepository
import com.macrobase.app.domain.repository.FoodRepository
import com.macrobase.app.domain.repository.PreferencesRepository
import com.macrobase.app.domain.repository.WaterRepository
import com.macrobase.app.domain.repository.WeightRepository
import com.macrobase.app.domain.usecase.AddWaterEntryUseCase
import com.macrobase.app.domain.usecase.AddWeightEntryUseCase
import com.macrobase.app.domain.usecase.CalculateNutritionForServingUseCase
import com.macrobase.app.domain.usecase.DeleteDiaryEntryUseCase
import com.macrobase.app.domain.usecase.DeleteWaterEntryUseCase
import com.macrobase.app.domain.usecase.DeleteWeightEntryUseCase
import com.macrobase.app.domain.usecase.GetDailyDiaryUseCase
import com.macrobase.app.domain.usecase.GetDiaryEntryUseCase
import com.macrobase.app.domain.usecase.GetFoodDetailsUseCase
import com.macrobase.app.domain.usecase.GetWaterEntriesUseCase
import com.macrobase.app.domain.usecase.GetWaterForDateUseCase
import com.macrobase.app.domain.usecase.GetWeightForDateUseCase
import com.macrobase.app.domain.usecase.GetWeightHistoryUseCase
import com.macrobase.app.domain.usecase.SearchFoodsUseCase
import com.macrobase.app.domain.usecase.UpdateDiaryEntryUseCase
import com.macrobase.app.domain.usecase.UpdateWaterEntryUseCase
import com.macrobase.app.domain.usecase.basket.AddFoodToBasketUseCase
import com.macrobase.app.domain.usecase.basket.CommitSingleBasketItemUseCase
import com.macrobase.app.domain.usecase.basket.GetBasketItemsUseCase
import com.macrobase.app.domain.usecase.basket.RemoveBasketItemUseCase
import com.macrobase.app.domain.usecase.basket.UpdateBasketItemUseCase
import com.macrobase.app.feature.dashboard.HomeViewModel
import com.macrobase.app.feature.detail.FoodDetailViewModel
import com.macrobase.app.feature.search.SearchViewModel
import com.macrobase.app.feature.water.WaterViewModel
import com.macrobase.app.feature.weight.WeightViewModel
import com.macrobase.app.testutil.MutableTestClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

private class EmptyDiary : DiaryRepository {
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

private class OneFood(private val food: Food) : FoodRepository {
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

/**
 * BUG-003: every way of starting a log takes the day on Home and a meal that fits the clock. Home's
 * "+" buttons always did; the search pill, Log water, Log weight and Log a recipe did not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogContextTests {

    private val today = LocalDate.of(2026, 10, 20)
    private val yesterday = today.minusDays(1)
    private val basket = InMemoryBasketRepository()
    private val rice = Food(
        id = 1_896_880_878_330_341L,
        uuid = "rice",
        name = "Rice",
        nutrition = Nutrition(calories = 130.0, proteinGrams = 2.7, carbsGrams = 28.0, fatGrams = 0.3),
        servings = listOf(Serving(description = "100 g", gramWeight = 100.0, isDefault = true))
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun home(time: LocalTime = LocalTime.of(19, 0)) = HomeViewModel(
        getDailyDiaryUseCase = GetDailyDiaryUseCase(EmptyDiary()),
        deleteDiaryEntryUseCase = DeleteDiaryEntryUseCase(EmptyDiary()),
        addFoodToBasketUseCase = AddFoodToBasketUseCase(basket),
        clock = MutableTestClock(today, time)
    )

    private fun foodDetail(time: LocalTime): FoodDetailViewModel {
        val diary = EmptyDiary()
        return FoodDetailViewModel(
            GetFoodDetailsUseCase(OneFood(rice)),
            CalculateNutritionForServingUseCase(),
            AddFoodToBasketUseCase(basket),
            GetBasketItemsUseCase(basket),
            UpdateBasketItemUseCase(basket),
            RemoveBasketItemUseCase(basket),
            CommitSingleBasketItemUseCase(basket, diary),
            UpdateDiaryEntryUseCase(diary),
            DeleteDiaryEntryUseCase(diary),
            GetDiaryEntryUseCase(diary),
            MutableTestClock(today, time)
        )
    }

    private fun source(path: String): String =
        listOf(File("src/main/java/com/macrobase/app/$path"), File("app/src/main/java/com/macrobase/app/$path"))
            .first { it.isFile }.readText()

    @Test
    fun onAPastDayEveryRouteCarriesThatDay() {
        val vm = home()
        vm.onSelectDate(yesterday)
        val epochDay = yesterday.toEpochDay()

        val context = vm.logContext()

        assertEquals("search?mealType=DINNER&dateEpochDay=$epochDay", context.searchRoute)
        assertEquals("water?dateEpochDay=$epochDay", context.waterRoute)
        assertEquals("weight?dateEpochDay=$epochDay&openLog=true", context.weightLogRoute)
    }

    @Test
    fun onTodayNoRouteCarriesADaySoOvernightScreensFollowTheCalendar() {
        val context = home().logContext()

        assertEquals("search?mealType=DINNER", context.searchRoute)
        assertEquals("water", context.waterRoute)
        assertEquals("weight?openLog=true", context.weightLogRoute)
    }

    @Test
    fun theMealFollowsTheClockUnlessOneWasChosen() {
        assertEquals(MealType.BREAKFAST, home(LocalTime.of(8, 0)).logContext().meal)
        assertEquals(MealType.LUNCH, home(LocalTime.of(13, 0)).logContext().meal)
        assertEquals(MealType.DINNER, home(LocalTime.of(20, 0)).logContext().meal)
        assertEquals(MealType.AM_SNACK, home().logContext(MealType.AM_SNACK).meal)
    }

    @Test
    fun theSearchPillAndTheSheetUseTheDayOnHome() {
        val graph = source("core/navigation/NavGraph.kt")
        val scaffold = source("core/navigation/MacroBaseScaffold.kt")

        assertFalse("the pill must not open a search without the day", graph.contains("onOpenSearch = { navController.navigate(Screen.Search.createRoute()) }"))
        assertTrue(graph.contains("onOpenSearch = { navController.navigate(homeViewModel.logContext().searchRoute) }"))
        assertFalse("Log water must not ignore the day", scaffold.contains("onLogWater = { navController.navigate(Screen.Water.createRoute()) }"))
        assertFalse("Log weight must not ignore the day", scaffold.contains("Screen.Weight.createRoute(openLog = true)"))
        assertTrue(scaffold.contains(".waterRoute"))
        assertTrue(scaffold.contains(".weightLogRoute"))
    }

    @Test
    fun aFoodOpenedWithoutAMealFollowsTheClock() {
        val lunchtime = foodDetail(LocalTime.of(13, 0))
        lunchtime.openFood(rice.id)
        assertEquals(MealType.LUNCH, lunchtime.uiState.value.targetMealType)

        val evening = foodDetail(LocalTime.of(20, 0))
        evening.openFood(rice.id)
        assertEquals(MealType.DINNER, evening.uiState.value.targetMealType)
    }

    @Test
    fun aMealChosenBeforeOpeningAFoodStays() {
        val vm = foodDetail(LocalTime.of(20, 0))

        vm.openFood(rice.id, mealType = MealType.BREAKFAST)

        assertEquals(MealType.BREAKFAST, vm.uiState.value.targetMealType)
    }

    @Test
    fun aSearchOpenedWithoutAMealFollowsTheClock() {
        val repo = OneFood(rice)
        val vm = SearchViewModel(SearchFoodsUseCase(repo), repo, MutableTestClock(today, LocalTime.of(20, 0)))

        assertEquals(MealType.DINNER, vm.uiState.value.targetMealType)
    }

    @Test
    fun theRecipeSheetStartsOnTheContextItWasGiven() {
        val recipes = source("feature/recipes/RecipesScreen.kt")

        assertFalse("the recipe sheet must not start on a fixed meal", recipes.contains("mutableStateOf(MealType.LUNCH)"))
        assertFalse("the recipe sheet must log to the day it was opened for", recipes.contains("viewModel.addRecipeToBasket(recipe, selectedMeal, LocalDate.now(), qty)"))
    }
}

/**
 * BUG-006: Water and Weight fixed their day once, so a screen left open overnight logged to
 * yesterday, and a morning weigh-in replaced yesterday's. The route's day is Friday, the screen
 * stays open, and it is Saturday when the user comes back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackerDayRolloverTests {

    private val friday = LocalDate.of(2026, 10, 23)
    private val saturday = friday.plusDays(1)
    private val wednesday = friday.minusDays(2)
    private val clock = MutableTestClock(friday, LocalTime.of(23, 0))

    private class Prefs : PreferencesRepository {
        override suspend fun getPreferences() = UserPreferences()
        override fun observePreferences() = flowOf(UserPreferences())
        override suspend fun updatePreferences(preferences: UserPreferences) {}
    }

    private class Water : WaterRepository {
        val added = mutableListOf<WaterEntry>()
        override suspend fun addWaterEntry(entry: WaterEntry): Long {
            added += entry
            return added.size.toLong()
        }
        override suspend fun updateWaterEntry(entry: WaterEntry) {}
        override suspend fun deleteWaterEntry(entryId: Long) {}
        override fun observeWaterTotalForDate(date: LocalDate) = flowOf(0.0)
        override suspend fun getWaterTotalForDate(date: LocalDate) = 0.0
        override fun observeWaterEntriesForDate(date: LocalDate) = flowOf(emptyList<WaterEntry>())
        override suspend fun getWaterEntriesForDate(date: LocalDate) = emptyList<WaterEntry>()
        override fun observeWaterHistory(startDate: LocalDate, endDate: LocalDate) = flowOf(emptyList<WaterEntry>())
        override suspend fun getWaterHistory(startDate: LocalDate, endDate: LocalDate) = emptyList<WaterEntry>()
    }

    private class Weight : WeightRepository {
        override suspend fun addWeightEntry(entry: WeightEntry) = 0L
        override suspend fun updateWeightEntry(entry: WeightEntry) {}
        override suspend fun deleteWeightEntry(entryId: Long) {}
        override suspend fun deleteWeightEntryForDate(date: LocalDate) {}
        override fun observeWeightForDate(date: LocalDate) = flowOf<WeightEntry?>(null)
        override suspend fun getWeightForDate(date: LocalDate): WeightEntry? = null
        override fun observeWeightHistory() = flowOf(emptyList<WeightEntry>())
        override suspend fun getWeightHistory() = emptyList<WeightEntry>()
        override fun observeWeightRange(startDate: LocalDate, endDate: LocalDate) = flowOf(emptyList<WeightEntry>())
        override suspend fun getWeightRange(startDate: LocalDate, endDate: LocalDate) = emptyList<WeightEntry>()
    }

    private val water = Water()
    private val weight = Weight()

    private fun waterScreen() = WaterViewModel(
        GetWaterForDateUseCase(water),
        GetWaterEntriesUseCase(water),
        AddWaterEntryUseCase(water),
        UpdateWaterEntryUseCase(water),
        DeleteWaterEntryUseCase(water),
        Prefs(),
        clock
    )

    private fun weightScreen() = WeightViewModel(
        GetWeightHistoryUseCase(weight),
        GetWeightForDateUseCase(weight),
        AddWeightEntryUseCase(weight),
        DeleteWeightEntryUseCase(weight),
        Prefs(),
        clock
    )

    private fun overnight() = clock.advanceBy(Duration.ofHours(9))

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
    fun waterLeftOnTodayMovesToTheNewDayAndLogsThere() {
        val vm = waterScreen()
        vm.onSelectDate(friday)

        overnight()
        vm.refreshDay()
        vm.addWater(250.0)

        assertEquals(saturday, vm.selectedDate.value)
        assertEquals(listOf(saturday), water.added.map { it.date })
    }

    // No lifecycle event fires when the clock passes midnight with the screen on
    @Test
    fun waterTappedJustAfterMidnightLogsToTheNewDayWithoutAResume() {
        val vm = waterScreen()
        vm.onSelectDate(friday)

        overnight()
        vm.addWater(250.0)

        assertEquals(listOf(saturday), water.added.map { it.date })
    }

    @Test
    fun aPastDayPickedOnWaterStays() {
        val vm = waterScreen()
        vm.onSelectDate(wednesday)

        overnight()
        vm.refreshDay()
        vm.addWater(250.0)

        assertEquals(wednesday, vm.selectedDate.value)
        assertEquals(listOf(wednesday), water.added.map { it.date })
    }

    @Test
    fun steppingBackToTodayOnWaterFollowsTheCalendarAgain() {
        val vm = waterScreen()
        vm.onPreviousDay()
        vm.onNextDay()

        overnight()
        vm.refreshDay()

        assertEquals(saturday, vm.selectedDate.value)
    }

    @Test
    fun weightLeftOnTodayMovesToTheNewDayAndItsRangeFollows() = runBlocking {
        val vm = weightScreen()
        vm.onSelectDate(friday)
        assertEquals(friday, withTimeout(5_000) { vm.uiState.first { it.selectedDate == friday } }.toDate)

        overnight()
        vm.refreshDay()

        val state = withTimeout(5_000) { vm.uiState.first { it.selectedDate == saturday } }
        assertEquals(saturday, state.toDate)
    }

    @Test
    fun aPastDayPickedOnWeightStaysWhileItsRangeStillMoves() = runBlocking {
        val vm = weightScreen()
        vm.onSelectDate(wednesday)
        withTimeout(5_000) { vm.uiState.first { it.selectedDate == wednesday } }

        overnight()
        vm.refreshDay()

        val state = withTimeout(5_000) { vm.uiState.first { it.toDate == saturday } }
        assertEquals(wednesday, state.selectedDate)
    }

    @Test
    fun theTrackersCheckTheDayWhenShownAndApplyTheirRouteDayOnce() {
        val graph = source("core/navigation/NavGraph.kt")

        assertTrue(source("feature/water/WaterScreen.kt").contains("LifecycleResumeEffect(viewModel)"))
        assertTrue(source("feature/weight/WeightScreen.kt").contains("LifecycleResumeEffect(viewModel)"))
        // Re-applied after a process restart or rotation, the route's day would undo the rollover
        assertFalse(graph.contains("androidx.compose.runtime.LaunchedEffect(dateEpochDayArg)"))
        assertEquals(2, Regex("""ApplyRouteDateOnce\(dateEpochDayArg,""").findAll(graph).count())
    }
}
