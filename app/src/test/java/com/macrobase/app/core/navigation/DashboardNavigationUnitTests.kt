package com.macrobase.app.core.navigation

import com.macrobase.app.core.util.DashboardDateFormatter
import com.macrobase.app.domain.model.CalendarDaySummary
import com.macrobase.app.domain.model.DailyNutritionSummary
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.Meal
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.repository.DailyMacroAggregation
import com.macrobase.app.domain.repository.DiaryRepository
import com.macrobase.app.domain.usecase.DeleteDiaryEntryUseCase
import com.macrobase.app.domain.usecase.GetDailyDiaryUseCase
import com.macrobase.app.feature.dashboard.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardNavigationUnitTests {

    private val testDispatcher = StandardTestDispatcher()
    private val fixedToday = LocalDate.of(2026, 8, 21) // August 21, 2026
    private val historicalDate = LocalDate.of(2026, 8, 20) // August 20, 2026

    private lateinit var fakeDiaryRepository: FakeDiaryRepository
    private lateinit var getDailyDiaryUseCase: GetDailyDiaryUseCase
    private lateinit var deleteDiaryEntryUseCase: DeleteDiaryEntryUseCase
    private lateinit var homeViewModel: HomeViewModel

    private lateinit var fakeBasketRepository: com.macrobase.app.domain.repository.basket.BasketRepository
    private lateinit var addFoodToBasketUseCase: com.macrobase.app.domain.usecase.basket.AddFoodToBasketUseCase

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeDiaryRepository = FakeDiaryRepository()
        fakeBasketRepository = com.macrobase.app.data.repository.basket.InMemoryBasketRepository()
        getDailyDiaryUseCase = GetDailyDiaryUseCase(fakeDiaryRepository)
        deleteDiaryEntryUseCase = DeleteDiaryEntryUseCase(fakeDiaryRepository)
        addFoodToBasketUseCase = com.macrobase.app.domain.usecase.basket.AddFoodToBasketUseCase(fakeBasketRepository)

        homeViewModel = HomeViewModel(
            getDailyDiaryUseCase = getDailyDiaryUseCase,
            deleteDiaryEntryUseCase = deleteDiaryEntryUseCase,
            addFoodToBasketUseCase = addFoodToBasketUseCase
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Test A: Main Dashboard navigation -> today's date
     */
    @Test
    fun testA_mainDashboardNavigation_opensToday() {
        // When navigating to main dashboard (e.g. from bottom nav or drawer)
        homeViewModel.resetToToday()

        assertEquals(LocalDate.now(ZoneId.systemDefault()), homeViewModel.selectedDate.value)
        assertEquals("Today", DashboardDateFormatter.formatDashboardDate(homeViewModel.selectedDate.value))
    }

    /**
     * Test B: Calendar August 20 -> Dashboard August 20
     */
    @Test
    fun testB_calendarDateClick_opensHistoricalDashboard() {
        // When Calendar selects August 20, 2026
        val route = Screen.Home.dateRoute(historicalDate)
        assertEquals("home?dateEpochDay=${historicalDate.toEpochDay()}", route)

        // NavGraph sets date to epoch day
        homeViewModel.onSelectDate(historicalDate)

        assertEquals(historicalDate, homeViewModel.selectedDate.value)
        assertEquals("Thursday, 08/20", DashboardDateFormatter.formatDashboardDate(homeViewModel.selectedDate.value, fixedToday))
    }

    /**
     * Test C: Calendar August 20 -> Dashboard August 20 -> main Dashboard navigation -> Dashboard today
     */
    @Test
    fun testC_calendarDateThenMainNavigation_resetsToToday() {
        // 1. Calendar opens August 20
        homeViewModel.onSelectDate(historicalDate)
        assertEquals(historicalDate, homeViewModel.selectedDate.value)

        // 2. User taps main Dashboard navigation item
        val mainRoute = Screen.Home.todayRoute()
        assertEquals("home", mainRoute)

        // Main navigation triggers resetToToday()
        homeViewModel.resetToToday()

        assertEquals(LocalDate.now(ZoneId.systemDefault()), homeViewModel.selectedDate.value)
        assertEquals("Today", DashboardDateFormatter.formatDashboardDate(homeViewModel.selectedDate.value))
    }

    /**
     * Test D: Dashboard historical date -> Food Logging flow -> Dashboard historical date remains intact
     */
    @Test
    fun testD_foodLoggingFlow_preservesHistoricalDate() {
        // 1. Dashboard is on historical date (August 20)
        homeViewModel.onSelectDate(historicalDate)
        assertEquals(historicalDate, homeViewModel.selectedDate.value)

        // 2. User clicks Breakfast (+) -> Search -> Detail -> Logs food for August 20
        val loggedEntry = DiaryEntry(
            id = 101L,
            uuid = "entry-101",
            food = Food(
                id = 1L,
                uuid = "food-1",
                name = "Oatmeal",
                nutrition = Nutrition(calories = 150.0, proteinGrams = 5.0, carbsGrams = 27.0, fatGrams = 3.0)
            ),
            mealType = MealType.BREAKFAST,
            date = historicalDate,
            serving = Serving(id = 1L, description = "1 bowl", gramWeight = 40.0, quantity = 1.0),
            quantity = 1.0,
            calculatedNutrition = Nutrition(calories = 150.0, proteinGrams = 5.0, carbsGrams = 27.0, fatGrams = 3.0)
        )
        fakeDiaryRepository.insertEntry(loggedEntry)

        // 3. Detail screen pops back to existing Home screen
        // In-place selectedDate must remain August 20!
        assertEquals(historicalDate, homeViewModel.selectedDate.value)
        assertEquals("Thursday, 08/20", DashboardDateFormatter.formatDashboardDate(homeViewModel.selectedDate.value, fixedToday))
    }

    /**
     * Test E: App launch -> today's date
     */
    @Test
    fun testE_appLaunch_initializesToToday() {
        val initialVm = HomeViewModel(
            getDailyDiaryUseCase = getDailyDiaryUseCase,
            deleteDiaryEntryUseCase = deleteDiaryEntryUseCase,
            addFoodToBasketUseCase = addFoodToBasketUseCase
        )

        assertEquals(LocalDate.now(ZoneId.systemDefault()), initialVm.selectedDate.value)
        assertEquals("Today", DashboardDateFormatter.formatDashboardDate(initialVm.selectedDate.value))
    }

    // -------------------------------------------------------------------------
    // Fake Diary Repository for isolated testing
    // -------------------------------------------------------------------------
    private class FakeDiaryRepository : DiaryRepository {
        private val entries = mutableListOf<DiaryEntry>()
        private val diaryFlow = MutableStateFlow<List<DiaryEntry>>(emptyList())

        fun insertEntry(entry: DiaryEntry) {
            entries.add(entry)
            diaryFlow.value = entries.toList()
        }

        override suspend fun getDiaryForDate(date: LocalDate): DailyNutritionSummary {
            val dateEntries = entries.filter { it.date == date }
            val totalCal = dateEntries.sumOf { it.calculatedNutrition.calories }
            val totalP = dateEntries.sumOf { it.calculatedNutrition.proteinGrams }
            val totalC = dateEntries.sumOf { it.calculatedNutrition.carbsGrams }
            val totalF = dateEntries.sumOf { it.calculatedNutrition.fatGrams }

            return DailyNutritionSummary(
                date = date,
                calorieGoal = 2000.0,
                totalCaloriesIntake = totalCal,
                totalProteinGrams = totalP,
                totalCarbsGrams = totalC,
                totalFatGrams = totalF,
                meals = MealType.entries.map { type ->
                    val mealEntries = dateEntries.filter { it.mealType == type }
                    Meal(
                        type = type,
                        entries = mealEntries
                    )
                }
            )
        }

        override fun observeDiaryForDate(date: LocalDate): Flow<DailyNutritionSummary> {
            val dateEntries = entries.filter { it.date == date }
            val totalCal = dateEntries.sumOf { it.calculatedNutrition.calories }
            val totalP = dateEntries.sumOf { it.calculatedNutrition.proteinGrams }
            val totalC = dateEntries.sumOf { it.calculatedNutrition.carbsGrams }
            val totalF = dateEntries.sumOf { it.calculatedNutrition.fatGrams }

            return flowOf(
                DailyNutritionSummary(
                    date = date,
                    calorieGoal = 2000.0,
                    totalCaloriesIntake = totalCal,
                    totalProteinGrams = totalP,
                    totalCarbsGrams = totalC,
                    totalFatGrams = totalF,
                    meals = MealType.entries.map { type ->
                        val mealEntries = dateEntries.filter { it.mealType == type }
                        Meal(
                            type = type,
                            entries = mealEntries
                        )
                    }
                )
            )
        }

        override suspend fun getEntryById(entryId: Long): DiaryEntry? = entries.firstOrNull { it.id == entryId }

        override suspend fun addEntry(entry: DiaryEntry): Long {
            insertEntry(entry)
            return entry.id
        }

        override suspend fun addEntries(entries: List<DiaryEntry>) {
            entries.forEach { insertEntry(it) }
        }

        override suspend fun updateEntry(entry: DiaryEntry) {}

        override suspend fun deleteEntry(entryId: Long) {
            entries.removeAll { it.id == entryId }
        }

        override suspend fun getMonthlyAdherence(year: Int, month: Int): List<CalendarDaySummary> = emptyList()

        override fun observeMonthlyAdherence(year: Int, month: Int): Flow<List<CalendarDaySummary>> = flowOf(emptyList())

        override fun observeNutritionAggregations(startDate: LocalDate, endDate: LocalDate): Flow<List<DailyMacroAggregation>> = flowOf(emptyList())

        override suspend fun getNutritionAggregations(startDate: LocalDate, endDate: LocalDate): List<DailyMacroAggregation> = emptyList()
    }
}
