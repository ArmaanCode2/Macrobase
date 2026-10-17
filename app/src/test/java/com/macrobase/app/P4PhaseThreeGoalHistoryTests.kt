package com.macrobase.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import com.macrobase.app.core.config.CalendarPerformanceConfig.PerformanceCategory
import com.macrobase.app.core.config.CalendarPerformanceConfig
import com.macrobase.app.data.database.UserDatabase
import com.macrobase.app.data.database.entity.DiaryEntryEntity
import com.macrobase.app.data.repository.DiaryRepositoryImpl
import com.macrobase.app.data.repository.GoalsRepositoryImpl
import com.macrobase.app.data.repository.StatisticsRepositoryImpl
import com.macrobase.app.domain.model.FitnessGoal
import com.macrobase.app.domain.model.GoalBackupDto
import com.macrobase.app.domain.model.GoalTransitionBackupDto
import com.macrobase.app.domain.repository.DailyMacroAggregation
import com.macrobase.app.domain.usecase.GetCalendarAdherenceUseCase
import com.macrobase.app.feature.calendar.CalendarViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * BUG-049: every past day is judged against the goal in force on it. The goal history is 2,000
 * kcal until 15 October and 1,600 kcal from the 16th; today is 20 October.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P4PhaseThreeGoalHistoryTests {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val context get() = RuntimeEnvironment.getApplication()
    private val today = LocalDate.of(2026, 10, 20)
    private val clock = Clock.fixed(today.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault())
    private val october10 = LocalDate.of(2026, 10, 10)
    private val october17 = LocalDate.of(2026, 10, 17)

    private lateinit var db: UserDatabase
    private lateinit var goals: GoalsRepositoryImpl
    private lateinit var diary: DiaryRepositoryImpl

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tempFolder.root, "goals.preferences_pb") }
        store.edit {
            it[doublePreferencesKey("daily_calorie_goal")] = 1600.0
            it[doublePreferencesKey("carb_percentage")] = 40.0
            it[doublePreferencesKey("protein_percentage")] = 35.0
            it[doublePreferencesKey("fat_percentage")] = 25.0
            it[stringPreferencesKey("fitness_goal")] = "CUTTING"
            it[doublePreferencesKey("maintenance_calories")] = 2000.0
            it[stringPreferencesKey("transitions_history")] =
                "2000-01-01|MAINTAINING|2000.0|2000.0|50.0|25.0|25.0\n2026-10-16|CUTTING|2000.0|1600.0|40.0|35.0|25.0"
        }
        goals = GoalsRepositoryImpl(context, store) { today }
        db = Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        diary = DiaryRepositoryImpl(db, goals)
    }

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
        Dispatchers.resetMain()
    }

    private suspend fun log(date: LocalDate, calories: Double) {
        db.diaryDao().insertEntry(
            DiaryEntryEntity(
                uuid = "e-$date-$calories",
                dateEpochDay = date.toEpochDay(),
                mealType = "LUNCH",
                foodId = 1L,
                foodName = "Dal",
                userQuantity = 1.0,
                servingDescription = "1 bowl",
                gramWeight = 300.0,
                loggedCalories = calories,
                loggedProtein = 20.0,
                loggedCarbs = 40.0,
                loggedFat = 10.0
            )
        )
    }

    // ---------------------------------------------------------------------------------------
    // The goal in force on each day
    // ---------------------------------------------------------------------------------------

    @Test
    fun eachDateGetsTheGoalInForceOnIt() = runBlocking {
        val dayGoals = goals.observeGoalsForDates(listOf(october10, october17, today)).first()

        assertEquals(listOf(2000.0, 1600.0, 1600.0), dayGoals.map { it.dailyCalorieGoal })
        assertEquals(dayGoals, goals.getGoalsForDates(listOf(october10, october17, today)))
    }

    // With no history, every day resolves to the current goal, as before
    @Test
    fun withNoHistoryEveryDayHasTheCurrentGoal() = runBlocking {
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tempFolder.root, "plain.preferences_pb") }
        store.edit { it[doublePreferencesKey("daily_calorie_goal")] = 1750.0 }
        val plain = GoalsRepositoryImpl(context, store) { today }

        assertEquals(listOf(1750.0, 1750.0), plain.observeGoalsForDates(listOf(october10, today)).first().map { it.dailyCalorieGoal })
    }

    // ---------------------------------------------------------------------------------------
    // The first day with a saved goal, from which the rank counts
    // ---------------------------------------------------------------------------------------

    @Test
    fun theRankCountsFromTheFirstSavedChange() = runBlocking {
        // The 2000-01-01 record only stands in for the days before the first change
        assertEquals(LocalDate.of(2026, 10, 16), goals.getGoalHistoryStart())
    }

    // An older version kept no history, so nothing tells how long today's goal has been in force
    @Test
    fun withNoHistoryTodaysGoalIsSavedAndTheRankCountsFromToday() = runBlocking {
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tempFolder.root, "none.preferences_pb") }
        store.edit { it[doublePreferencesKey("daily_calorie_goal")] = 1750.0 }
        val none = GoalsRepositoryImpl(context, store) { today }

        assertEquals(today, none.getGoalHistoryStart())
        // Earlier days keep the goal they had until now
        assertEquals(1750.0, none.getGoalForDate(october10).dailyCalorieGoal, 0.0)
        // The saved day goes into backups, and asking again saves nothing more
        assertEquals(today, none.getGoalHistoryStart())
        assertEquals(listOf("2000-01-01", today.toString()), none.exportGoalBackup().transitions!!.map { it.effectiveDate })
    }

    // A change saved yesterday for today has no saved day before it, but today's goal is known
    @Test
    fun onlyAChangeScheduledForTomorrowCountsFromToday() = runBlocking {
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tempFolder.root, "scheduled.preferences_pb") }
        store.edit {
            it[doublePreferencesKey("daily_calorie_goal")] = 2000.0
            it[stringPreferencesKey("fitness_goal")] = "MAINTAINING"
            it[doublePreferencesKey("maintenance_calories")] = 2000.0
            it[stringPreferencesKey("scheduled_fitness_goal")] = "CUTTING"
            it[doublePreferencesKey("scheduled_maintenance_calories")] = 2200.0
            it[stringPreferencesKey("scheduled_effective_date")] = today.plusDays(1).toString()
            it[stringPreferencesKey("transitions_history")] =
                "2000-01-01|MAINTAINING|2000.0|2000.0|50.0|25.0|25.0\n${today.plusDays(1)}|CUTTING|2200.0|2000.0|50.0|25.0|25.0"
        }
        val repo = GoalsRepositoryImpl(context, store) { today }

        assertEquals(today, repo.getGoalHistoryStart())
        assertEquals(FitnessGoal.MAINTAINING, repo.getGoalForDate(today).fitnessGoal)
        assertEquals(FitnessGoal.CUTTING, repo.getGoalForDate(today.plusDays(1)).fitnessGoal)
    }

    @Test
    fun aRestoredBackupBringsItsFirstSavedDay() = runBlocking {
        goals.restoreGoalBackup(
            GoalBackupDto(
                dailyCalorieGoal = 1600.0, carbPercentage = 40.0, proteinPercentage = 35.0, fatPercentage = 25.0,
                fitnessGoal = "CUTTING", maintenanceCalories = 2000.0,
                transitions = listOf(
                    GoalTransitionBackupDto("2000-01-01", "MAINTAINING", 2000.0, 2000.0, 50.0, 25.0, 25.0),
                    GoalTransitionBackupDto("2026-09-01", "CUTTING", 2000.0, 1600.0, 40.0, 35.0, 25.0)
                )
            )
        )

        assertEquals(LocalDate.of(2026, 9, 1), goals.getGoalHistoryStart())
    }

    // ---------------------------------------------------------------------------------------
    // Calendar
    // ---------------------------------------------------------------------------------------

    @Test
    fun aPastOnTargetDayStaysOnTargetAfterTheGoalIsLowered() = runBlocking {
        log(october10, 1950.0)

        val day = diary.observeMonthlyAdherence(2026, 10).first().single { it.date == october10 }

        assertEquals(2000.0, day.calorieGoal, 0.0)
        assertEquals(PerformanceCategory.OPTIMAL_TARGET, day.performanceCategory)
        assertEquals(day, diary.getMonthlyAdherence(2026, 10).single { it.date == october10 })
        // Judged against today's 1,600 the same day would be over, so the test can fail
        assertNotEquals(PerformanceCategory.OPTIMAL_TARGET, CalendarPerformanceConfig.evaluatePerformance(1950.0, 1600.0))
    }

    // The user lowers the goal today, through the same save the Preferences screen uses
    @Test
    fun loweringTheGoalTodayLeavesAnEarlierDayAsItWas() = runBlocking {
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tempFolder.root, "steady.preferences_pb") }
        store.edit {
            it[doublePreferencesKey("daily_calorie_goal")] = 2000.0
            it[stringPreferencesKey("fitness_goal")] = "MAINTAINING"
            it[doublePreferencesKey("maintenance_calories")] = 2000.0
            it[stringPreferencesKey("transitions_history")] = "2026-01-01|MAINTAINING|2000.0|2000.0|50.0|25.0|25.0"
        }
        val steady = GoalsRepositoryImpl(context, store) { today }
        val steadyDiary = DiaryRepositoryImpl(db, steady)
        log(october10, 1950.0)

        steady.updateGoals(steady.getGoals().copy(dailyCalorieGoal = 1600.0))

        val month = steadyDiary.observeMonthlyAdherence(2026, 10).first()
        assertEquals(2000.0, month.single { it.date == october10 }.calorieGoal, 0.0)
        assertEquals(PerformanceCategory.OPTIMAL_TARGET, month.single { it.date == october10 }.performanceCategory)
        assertEquals(1600.0, month.single { it.date == today }.calorieGoal, 0.0)
    }

    // A change to past days' history refreshes the month even though today's goal is the same
    @Test
    fun theMonthRefreshesWhenGoalHistoryChanges() = runBlocking {
        val goalsOn10th = Channel<Double>(Channel.UNLIMITED)
        val watcher = scope.launch {
            diary.observeMonthlyAdherence(2026, 10).collect { month -> goalsOn10th.send(month.single { it.date == october10 }.calorieGoal) }
        }
        assertEquals(2000.0, withTimeout(10_000) { goalsOn10th.receive() }, 0.0)

        goals.restoreGoalBackup(
            GoalBackupDto(
                dailyCalorieGoal = 1600.0,
                carbPercentage = 40.0,
                proteinPercentage = 35.0,
                fatPercentage = 25.0,
                fitnessGoal = "CUTTING",
                maintenanceCalories = 2000.0,
                transitions = listOf(
                    GoalTransitionBackupDto("2000-01-01", "MAINTAINING", 1800.0, 1800.0, 50.0, 25.0, 25.0),
                    GoalTransitionBackupDto("2026-10-16", "CUTTING", 2000.0, 1600.0, 40.0, 35.0, 25.0)
                )
            )
        )

        withTimeout(10_000) { while (goalsOn10th.receive() != 1800.0) Unit }
        assertEquals(1600.0, goals.getGoals().dailyCalorieGoal, 0.0)
        watcher.cancel()
    }

    // D7: until something is logged today, today is not a missed day
    @Test
    fun anUnloggedTodayIsNotAMissedDay() = runBlocking {
        for (day in 1..19) log(LocalDate.of(2026, 10, day), 1900.0)
        val calendar = CalendarViewModel(GetCalendarAdherenceUseCase(diary), clock)

        val state = withTimeout(10_000) { calendar.uiState.first { it.dayCells.isNotEmpty() } }

        assertEquals(0, state.daysMissed)
    }

    @Test
    fun pastDaysWithNothingLoggedAreMissed() = runBlocking {
        log(LocalDate.of(2026, 10, 5), 1900.0)
        log(today, 500.0)
        val calendar = CalendarViewModel(GetCalendarAdherenceUseCase(diary), clock)

        val state = withTimeout(10_000) { calendar.uiState.first { it.dayCells.isNotEmpty() } }

        // 1 to 19 October minus the 5th; today is logged, so it is judged, not missed
        assertEquals(18, state.daysMissed)
        assertEquals(2000.0, state.dayCells.single { it.isCurrentMonth && it.date == october10 }.calorieGoal, 0.0)
        assertEquals(1600.0, state.dayCells.single { it.isCurrentMonth && it.date == today }.calorieGoal, 0.0)
    }

    // ---------------------------------------------------------------------------------------
    // Dashboard on a past day
    // ---------------------------------------------------------------------------------------

    @Test
    fun aPastDayOnTheDashboardShowsItsOwnGoal() = runBlocking {
        assertEquals(2000.0, diary.observeDiaryForDate(october10).first().calorieGoal, 0.0)
        assertEquals(2000.0, diary.getDiaryForDate(october10).calorieGoal, 0.0)
        assertEquals(1600.0, diary.getDiaryForDate(today).calorieGoal, 0.0)
    }

    // ---------------------------------------------------------------------------------------
    // Statistics
    // ---------------------------------------------------------------------------------------

    private fun statistics(aggregations: List<DailyMacroAggregation>) = StatisticsRepositoryImpl(
        diaryRepository = P2PhaseFourRankReplayTests.Diary(aggregations),
        goalsRepository = goals,
        weightRepository = P2PhaseFourRankReplayTests.Weights(emptyList()),
        clock = clock
    )

    private fun eaten(date: LocalDate, calories: Double) = DailyMacroAggregation(date, calories, 80.0, 200.0, 60.0)

    @Test
    fun theHeatmapJudgesEachDayAgainstItsOwnGoal() = runBlocking {
        val stats = statistics(listOf(eaten(october10, 1950.0), eaten(october17, 1950.0)))

        val cells = stats.observeNutritionConsistency(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 19)).first().allCells

        assertEquals(PerformanceCategory.OPTIMAL_TARGET, cells.single { it.date == october10 }.category)
        assertEquals(2000.0, cells.single { it.date == october10 }.dailyGoalCalories, 0.0)
        assertNotEquals(PerformanceCategory.OPTIMAL_TARGET, cells.single { it.date == october17 }.category)
        assertEquals(1600.0, cells.single { it.date == october17 }.dailyGoalCalories, 0.0)
    }

    // The averages cover logged days, so the targets come from those days' goals
    @Test
    fun macroAverageTargetsAreTheMeanOfTheLoggedDaysGoals() = runBlocking {
        val stats = statistics(listOf(eaten(october10, 1950.0), eaten(october17, 1650.0)))

        val averages = stats.observeMacroAverages(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 10, 19)).first()

        assertEquals(1800.0, averages.targetCalories, 1e-9)
        // Each day's split counts by its calorie goal, as the actual split counts calories eaten:
        // (2000 x 50 + 1600 x 40) / 3600 for carbs
        assertEquals(164000.0 / 3600.0, averages.targetCarbsPercent, 1e-9)
        assertEquals(106000.0 / 3600.0, averages.targetProteinPercent, 1e-9)
        assertEquals(25.0, averages.targetFatPercent, 1e-9)
    }

    @Test
    fun withNoLoggedDayTheTargetsAreTodaysGoal() = runBlocking {
        val averages = statistics(emptyList()).observeMacroAverages(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 10, 19)).first()

        assertEquals(1600.0, averages.targetCalories, 0.0)
        assertEquals(40.0, averages.targetCarbsPercent, 0.0)
    }
}
