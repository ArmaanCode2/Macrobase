package com.macrobase.app

import androidx.room.Room
import com.macrobase.app.core.config.DatabaseConfig
import com.macrobase.app.core.di.appModules
import com.macrobase.app.data.database.UserDatabase
import com.macrobase.app.data.repository.DiaryRepositoryImpl
import com.macrobase.app.data.repository.WaterRepositoryImpl
import com.macrobase.app.data.repository.basket.InMemoryBasketRepository
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.UnitConversions
import com.macrobase.app.domain.model.UnitSystem
import com.macrobase.app.domain.model.UserPreferences
import com.macrobase.app.domain.repository.GoalsRepository
import com.macrobase.app.domain.repository.PreferencesRepository
import com.macrobase.app.domain.usecase.AddWaterEntryUseCase
import com.macrobase.app.domain.usecase.DeleteDiaryEntryUseCase
import com.macrobase.app.domain.usecase.GetDailyDiaryUseCase
import com.macrobase.app.domain.usecase.GetGoalsUseCase
import com.macrobase.app.domain.usecase.basket.AddFoodToBasketUseCase
import com.macrobase.app.feature.dashboard.HomeProfile
import com.macrobase.app.feature.dashboard.HomeViewModel
import com.macrobase.app.feature.dashboard.formatKcal
import com.macrobase.app.feature.dashboard.homeDayLabel
import com.macrobase.app.feature.dashboard.macroTargetGrams
import com.macrobase.app.feature.dashboard.quickAddWaterMl
import com.macrobase.app.testutil.MutableTestClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.koinApplication
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/**
 * The redesigned Home (design phase 4): rings' targets, the water quick add, the day and calorie
 * labels. Collectors run in their own scope, so cancelling one never leaves runBlocking waiting on
 * the paused test dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DesignHomeTests {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var db: UserDatabase
    private val today = LocalDate.of(2026, 10, 17)

    private val goals = object : GoalsRepository {
        val state = MutableStateFlow(Goal(dailyCalorieGoal = 2000.0, proteinPercentage = 30.0, carbPercentage = 40.0, fatPercentage = 30.0))
        override suspend fun getGoals() = state.value
        override fun observeGoals() = state
        override suspend fun updateGoals(goals: Goal) { state.value = goals }
    }

    private class FakePreferences(initial: UserPreferences) : PreferencesRepository {
        val state = MutableStateFlow(initial)
        override suspend fun getPreferences() = state.value
        override fun observePreferences() = state
        override suspend fun updatePreferences(preferences: UserPreferences) { state.value = preferences }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), UserDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(prefs: PreferencesRepository) = HomeViewModel(
        getDailyDiaryUseCase = GetDailyDiaryUseCase(DiaryRepositoryImpl(db, goals)),
        deleteDiaryEntryUseCase = DeleteDiaryEntryUseCase(DiaryRepositoryImpl(db, goals)),
        addFoodToBasketUseCase = AddFoodToBasketUseCase(InMemoryBasketRepository()),
        clock = MutableTestClock(today),
        preferencesRepository = prefs,
        getGoalsUseCase = GetGoalsUseCase(goals),
        addWaterEntryUseCase = AddWaterEntryUseCase(WaterRepositoryImpl(db))
    )

    @Test
    fun ringTargetsFollowTheDaysCalorieGoalAndTheMacroSplit() {
        val (protein, carbs, fat) = macroTargetGrams(2000.0, HomeProfile())
        // The default 25 / 50 / 25 split, as Goal works out grams
        assertEquals(Goal().proteinGrams, protein, 0.001)
        assertEquals(Goal().carbGrams, carbs, 0.001)
        assertEquals(Goal().fatGrams, fat, 0.001)
        assertEquals(125.0, protein, 0.001)
        assertEquals(250.0, carbs, 0.001)
        assertEquals(55.556, fat, 0.001)
        // A past day with a different goal uses that day's calories
        assertEquals(150.0, macroTargetGrams(2400.0, HomeProfile(proteinPercentage = 25.0)).first, 0.001)
        assertEquals(Triple(0.0, 0.0, 0.0), macroTargetGrams(0.0, HomeProfile()))
    }

    @Test
    fun theHomeProfileReadsTheNameUnitsAndSplit() = runBlocking {
        val prefs = FakePreferences(UserPreferences(firstName = "  Meera ", unitSystem = UnitSystem.IMPERIAL))
        val vm = viewModel(prefs)
        val collector = CoroutineScope(testDispatcher).launch { vm.homeProfile.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        val profile = vm.homeProfile.value
        assertEquals("Meera", profile.firstName)
        assertEquals(UnitSystem.IMPERIAL, profile.unitSystem)
        assertEquals(30.0, profile.proteinPercentage, 0.0)
        assertEquals(40.0, profile.carbPercentage, 0.0)
        assertEquals(30.0, profile.fatPercentage, 0.0)
        collector.cancel()
    }

    @Test
    fun quickAddLogsTheFirstWaterAmountToTheDayOnScreen() = runBlocking {
        assertEquals(250.0, quickAddWaterMl(UnitSystem.METRIC), 0.0)
        assertEquals(UnitConversions.flOzToMl(8.0), quickAddWaterMl(UnitSystem.IMPERIAL), 0.0)

        val vm = viewModel(FakePreferences(UserPreferences()))
        val collector = CoroutineScope(testDispatcher).launch { vm.homeProfile.collect { } }
        vm.onSelectDate(today.minusDays(2))
        var added = false
        vm.quickAddWater { added = true }
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(added)
        val water = WaterRepositoryImpl(db)
        assertEquals(250.0, water.getWaterTotalForDate(today.minusDays(2)), 0.0)
        assertEquals(0.0, water.getWaterTotalForDate(today), 0.0)
        collector.cancel()
    }

    @Test
    fun imperialQuickAddLogsEightFluidOunces() = runBlocking {
        val vm = viewModel(FakePreferences(UserPreferences(unitSystem = UnitSystem.IMPERIAL)))
        val collector = CoroutineScope(testDispatcher).launch { vm.homeProfile.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()
        vm.quickAddWater()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(UnitConversions.flOzToMl(8.0), WaterRepositoryImpl(db).getWaterTotalForDate(today), 0.0001)
        collector.cancel()
    }

    @Test
    fun dayAndCalorieLabels() {
        assertEquals("Today", homeDayLabel(today, today))
        assertEquals("Yesterday", homeDayLabel(today.minusDays(1), today))
        assertEquals("Wednesday, 10/14", homeDayLabel(today.minusDays(3), today))
        // Rounded, never truncated (AGENTS.md section 2.2), with a thousands separator
        assertEquals("2,000", formatKcal(1999.8))
        assertEquals("776", formatKcal(776.4))
        assertEquals("1,348", formatKcal(1348.0))
    }

    // Without these the rings would show no targets and "+250 mL" would do nothing on the phone
    @Test
    fun theAppBuildsHomeWithItsProfileGoalsAndWaterDependencies() {
        val app = RuntimeEnvironment.getApplication()
        val catalog = listOf(File("src/main/assets/databases/built_in_foods.db"), File("app/src/main/assets/databases/built_in_foods.db"))
            .first { it.isFile }
        val installed = app.getDatabasePath(DatabaseConfig.BUILT_IN_FOODS_DB_NAME)
        installed.parentFile?.mkdirs()
        catalog.copyTo(installed, overwrite = true)
        val koin = koinApplication {
            androidContext(app)
            modules(appModules)
        }.koin
        try {
            val home = koin.get<HomeViewModel>()
            listOf("preferencesRepository", "getGoalsUseCase", "addWaterEntryUseCase").forEach { name ->
                val value = HomeViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(home)
                assertNotNull("HomeViewModel.$name is not injected", value)
            }
        } finally {
            koin.get<UserDatabase>().close()
            koin.close()
        }
    }
}
