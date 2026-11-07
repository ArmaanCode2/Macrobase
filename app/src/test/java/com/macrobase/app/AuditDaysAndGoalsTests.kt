package com.macrobase.app

import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.macrobase.app.core.designsystem.components.MACRO_RING_FONT_SIZES
import com.macrobase.app.core.designsystem.components.fittingFontSize
import com.macrobase.app.data.database.UserDatabase
import com.macrobase.app.data.database.entity.WaterLogEntity
import com.macrobase.app.data.repository.DiaryRepositoryImpl
import com.macrobase.app.data.repository.GoalsRepositoryImpl
import com.macrobase.app.data.repository.PortabilityRepositoryImpl
import com.macrobase.app.data.repository.PreferencesRepositoryImpl
import com.macrobase.app.data.repository.basket.InMemoryBasketRepository
import com.macrobase.app.domain.model.BackupManifestDto
import com.macrobase.app.domain.model.BackupRecordCountsDto
import com.macrobase.app.domain.model.CustomFood
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.FitnessGoal
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.ImportMode
import com.macrobase.app.domain.model.MacroBaseBackupData
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.WaterLogBackupDto
import com.macrobase.app.domain.repository.DiaryRepository
import com.macrobase.app.domain.repository.FoodRepository
import com.macrobase.app.domain.usecase.CalculateNutritionForServingUseCase
import com.macrobase.app.domain.usecase.DeleteDiaryEntryUseCase
import com.macrobase.app.domain.usecase.GetCalendarAdherenceUseCase
import com.macrobase.app.domain.usecase.GetDiaryEntryUseCase
import com.macrobase.app.domain.usecase.GetFoodDetailsUseCase
import com.macrobase.app.domain.usecase.GetGoalsUseCase
import com.macrobase.app.domain.usecase.UpdateDiaryEntryUseCase
import com.macrobase.app.domain.usecase.basket.AddFoodToBasketUseCase
import com.macrobase.app.domain.usecase.basket.CommitSingleBasketItemUseCase
import com.macrobase.app.domain.usecase.basket.GetBasketItemsUseCase
import com.macrobase.app.domain.usecase.basket.RemoveBasketItemUseCase
import com.macrobase.app.domain.usecase.basket.UpdateBasketItemUseCase
import com.macrobase.app.feature.calendar.CalendarViewModel
import com.macrobase.app.feature.dashboard.macroTargetGrams
import com.macrobase.app.feature.detail.FoodDetailViewModel
import com.macrobase.app.testutil.MutableTestClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

private fun mainSource(path: String): String =
    listOf(File("src/main/java/com/macrobase/app/$path"), File("app/src/main/java/com/macrobase/app/$path"))
        .first { it.isFile }.readText()

/** A diary with nothing logged, for the calendar and Food Detail. */
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

private class NoFoods : FoodRepository {
    override suspend fun searchFoods(query: String, limit: Int) = emptyList<Food>()
    override suspend fun getFoodById(id: Long): Food? = null
    override suspend fun getFoodByBarcode(barcode: String): Food? = null
    override suspend fun getFoodServings(foodId: Long) = emptyList<Serving>()
    override suspend fun getRecentFoods(limit: Int) = emptyList<Food>()
    override suspend fun getFavoriteFoods() = emptyList<Food>()
    override fun observeCustomFoods() = flowOf(emptyList<Food>())
    override suspend fun getCustomFoods() = emptyList<Food>()
    override suspend fun saveCustomFood(food: CustomFood) = 0L
    override suspend fun deleteCustomFood(id: Long) {}
}

/**
 * BUG-020, BUG-047 and BUG-052, on the real goals store, diary and restore. The goals: 2,000 kcal at
 * 40 / 30 / 30 from 10 October, then 1,600 kcal at 45 / 35 / 20 from 15 October.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DayGoalTests {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val context get() = RuntimeEnvironment.getApplication()
    private val oct10 = LocalDate.of(2026, 10, 10)
    private val oct12 = LocalDate.of(2026, 10, 12)
    private val oct15 = LocalDate.of(2026, 10, 15)
    private var clockDay = oct10

    private lateinit var db: UserDatabase
    private val goals by lazy {
        GoalsRepositoryImpl(context, PreferenceDataStoreFactory.create(scope = scope) { File(tempFolder.root, "goals.preferences_pb") }) { clockDay }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
        Dispatchers.resetMain()
    }

    private suspend fun twoSplits() {
        clockDay = oct10
        goals.updateGoals(Goal(2000.0, 40.0, 30.0, 30.0, FitnessGoal.MAINTAINING, 2000.0))
        clockDay = oct15
        goals.updateGoals(Goal(1600.0, 45.0, 35.0, 20.0, FitnessGoal.MAINTAINING, 2000.0))
    }

    // BUG-020: the rings of a past day use that day's split, not today's
    @Test
    fun aPastDaysRingsUseThatDaysSplit() = runBlocking {
        twoSplits()

        val summary = DiaryRepositoryImpl(db, goals).observeDiaryForDate(oct12).first()
        val (protein, carbs, fat) = macroTargetGrams(summary)

        assertEquals(2000.0, summary.calorieGoal, 0.0)
        assertEquals(150.0, protein, 0.001)
        assertEquals(200.0, carbs, 0.001)
        assertEquals(66.667, fat, 0.001)
    }

    @Test
    fun homesRingsTakeTheSplitFromTheDaysSummary() {
        assertTrue(mainSource("feature/dashboard/HomeScreen.kt").contains("macroTargetGrams(summary)"))
    }

    // BUG-020: "% of your daily target" in Food Detail is against the goal of the day it logs to
    @Test
    fun foodDetailsDailyTargetIsTheGoalOfTheDayItLogsTo() = runBlocking {
        twoSplits()
        val basket = InMemoryBasketRepository()
        val diary = NoDiary()
        val vm = FoodDetailViewModel(
            GetFoodDetailsUseCase(NoFoods()),
            CalculateNutritionForServingUseCase(),
            AddFoodToBasketUseCase(basket),
            GetBasketItemsUseCase(basket),
            UpdateBasketItemUseCase(basket),
            RemoveBasketItemUseCase(basket),
            CommitSingleBasketItemUseCase(basket, diary),
            UpdateDiaryEntryUseCase(diary),
            DeleteDiaryEntryUseCase(diary),
            GetDiaryEntryUseCase(diary),
            MutableTestClock(oct15, LocalTime.of(9, 0)),
            GetGoalsUseCase(goals)
        )

        vm.onDateSelected(oct12)

        assertEquals(2000.0, withTimeout(5_000) { vm.dailyCalorieGoal.first { it == 2000.0 } }, 0.0)
    }

    // BUG-052: on a fresh install, a second save the same day asked for the pending strategy and started it today
    @Test
    fun aSecondSaveOnAFreshInstallKeepsTodaysStrategy() = runBlocking {
        clockDay = oct15
        val cutting = Goal(2000.0, 50.0, 25.0, 25.0, FitnessGoal.CUTTING, 2500.0)

        goals.updateGoals(cutting)
        goals.updateGoals(cutting.copy(carbPercentage = 45.0, proteinPercentage = 30.0))

        val today = goals.getGoals()
        assertEquals(FitnessGoal.MAINTAINING, today.fitnessGoal)
        assertEquals(FitnessGoal.CUTTING, today.scheduledFitnessGoal)
        clockDay = oct15.plusDays(1)
        assertEquals(FitnessGoal.CUTTING, goals.getGoals().fitnessGoal)
    }

    // BUG-047: editing a water log keeps its time, so its old copy in a backup is the same log
    @Test
    fun mergingAWaterLogEditedSinceTheBackupKeepsThePhonesAmount() = runBlocking {
        val day = oct12.toEpochDay()
        db.waterDao().insertWaterLogs(listOf(WaterLogEntity(dateEpochDay = day, amountMl = 500.0, timestamp = 1_000L)))
        val backup = MacroBaseBackupData(
            manifest = BackupManifestDto(exportedAt = "2026-10-12T08:00:00Z", timeZone = "UTC", counts = BackupRecordCountsDto()),
            waterEntries = listOf(WaterLogBackupDto(day, oct12.toString(), 250.0, 1_000L))
        )
        val preferences = PreferencesRepositoryImpl(
            context,
            PreferenceDataStoreFactory.create(scope = scope) { File(tempFolder.root, "prefs.preferences_pb") }
        )

        val result = PortabilityRepositoryImpl(db, goals, preferences).importUserData(backup, ImportMode.MERGE)

        val logs = db.waterDao().getAllWaterLogsList()
        assertTrue(result.errorMessage, result.isSuccess)
        assertEquals(listOf(500.0), logs.map { it.amountMl })
        assertEquals(1, result.conflictsResolved)
    }
}

/** BUG-029: the calendar's today ring and missed days stayed on yesterday overnight. */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarTodayTests {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun calendar(clock: MutableTestClock) = CalendarViewModel(GetCalendarAdherenceUseCase(NoDiary()), clock)

    @Test
    fun leftOpenOvernightTheCalendarMovesToTheNewDay() = runBlocking {
        val clock = MutableTestClock(LocalDate.of(2026, 11, 5), LocalTime.of(23, 0))
        val vm = calendar(clock)
        val before = withTimeout(5_000) { vm.uiState.first { state -> state.dayCells.any { it.isToday } } }
        assertEquals(5, before.dayCells.single { it.isToday }.dayNumber)

        clock.advanceBy(Duration.ofHours(2))
        vm.onShown()

        val after = withTimeout(5_000) { vm.uiState.first { state -> state.dayCells.any { it.isToday && it.dayNumber == 6 } } }
        assertEquals(before.daysMissed + 1, after.daysMissed)
    }

    @Test
    fun overAMonthEndItShowsTheNewMonth() = runBlocking {
        val clock = MutableTestClock(LocalDate.of(2026, 10, 31), LocalTime.of(23, 0))
        val vm = calendar(clock)
        withTimeout(5_000) { vm.uiState.first { state -> state.dayCells.any { it.isToday } } }

        clock.advanceBy(Duration.ofHours(2))
        vm.onShown()

        val after = withTimeout(5_000) { vm.uiState.first { it.displayedYearMonth == YearMonth.of(2026, 11) } }
        assertEquals(1, after.dayCells.single { it.isToday && it.isCurrentMonth }.dayNumber)
    }

    // The screen being shown again is a resume, not only its first composition
    @Test
    fun theCalendarCatchesUpWheneverItIsShownAgain() {
        val graph = mainSource("core/navigation/NavGraph.kt")
        val stats = mainSource("feature/statistics/StatisticsScreen.kt")

        assertFalse(graph.contains("LaunchedEffect(viewModel) { viewModel.onShown() }"))
        assertFalse(stats.contains("LaunchedEffect(calendarViewModel) { calendarViewModel.onShown() }"))
        assertTrue(Regex("""LifecycleResumeEffect\(viewModel\) \{\s+viewModel\.onShown\(\)""").containsMatchIn(graph))
        assertTrue(Regex("""LifecycleResumeEffect\(calendarViewModel\) \{\s+calendarViewModel\.onShown\(\)""").containsMatchIn(stats))
    }
}

/** BUG-030: the ring's "eaten/target" was cut off at large font sizes. */
class MacroRingTextTests {

    @Test
    fun theNumbersShrinkUntilTheyFit() {
        // Text grows with its size: 6 px per sp here
        assertEquals(14.sp, fittingFontSize(MACRO_RING_FONT_SIZES, available = 100f) { it.value * 6f })
        assertEquals(10.sp, fittingFontSize(MACRO_RING_FONT_SIZES, available = 60f) { it.value * 6f })
        assertEquals("nothing fits: the smallest", MACRO_RING_FONT_SIZES.last(), fittingFontSize(MACRO_RING_FONT_SIZES, available = 10f) { it.value * 6f })
    }

    @Test
    fun theRingMeasuresItsNumbers() {
        val ring = mainSource("core/designsystem/components/MbMacroRing.kt")

        assertTrue(ring.contains("rememberTextMeasurer()"))
        assertTrue(ring.contains("fittingFontSize("))
    }
}

/** The basket's "Add food" opened Search for today, whatever day Home was on. */
class BasketAddFoodTests {

    @Test
    fun addFoodOpensSearchForHomesDay() {
        val graph = mainSource("core/navigation/NavGraph.kt")

        assertFalse(graph.contains("onNavigateToSearch = { navController.navigate(Screen.Search.createRoute()) }"))
        assertTrue(graph.contains("onNavigateToSearch = { navController.navigate(homeViewModel.logContext().searchRoute) }"))
    }
}
