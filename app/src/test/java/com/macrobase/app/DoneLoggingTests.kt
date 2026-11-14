package com.macrobase.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.room.Room
import com.macrobase.app.data.database.UserDatabase
import com.macrobase.app.data.portability.BackupArchiveManager
import com.macrobase.app.data.portability.BackupJsonSerializer
import com.macrobase.app.data.repository.DiaryRepositoryImpl
import com.macrobase.app.data.repository.GoalsRepositoryImpl
import com.macrobase.app.data.repository.PortabilityRepositoryImpl
import com.macrobase.app.data.repository.PreferencesRepositoryImpl
import com.macrobase.app.data.repository.StatisticsRepositoryImpl
import com.macrobase.app.data.repository.WaterRepositoryImpl
import com.macrobase.app.data.repository.basket.InMemoryBasketRepository
import com.macrobase.app.domain.model.BackupManifestDto
import com.macrobase.app.domain.model.BackupRecordCountsDto
import com.macrobase.app.domain.model.CalendarDaySummary
import com.macrobase.app.domain.model.DailyNutritionSummary
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.ImportMode
import com.macrobase.app.domain.model.MacroBaseBackupData
import com.macrobase.app.domain.model.UserPreferences
import com.macrobase.app.domain.model.UserPreferencesBackupDto
import com.macrobase.app.domain.repository.DailyMacroAggregation
import com.macrobase.app.domain.repository.DiaryRepository
import com.macrobase.app.domain.repository.GoalsRepository
import com.macrobase.app.domain.repository.PreferencesRepository
import com.macrobase.app.domain.repository.WeightRepository
import com.macrobase.app.domain.model.WeightEntry
import com.macrobase.app.domain.usecase.AddWaterEntryUseCase
import com.macrobase.app.domain.usecase.DeleteDiaryEntryUseCase
import com.macrobase.app.domain.usecase.GetDailyDiaryUseCase
import com.macrobase.app.domain.usecase.GetGoalsUseCase
import com.macrobase.app.domain.usecase.GetStatisticsUseCase
import com.macrobase.app.domain.usecase.basket.AddFoodToBasketUseCase
import com.macrobase.app.feature.dashboard.HomeViewModel
import com.macrobase.app.feature.dashboard.canMarkDayFinished
import com.macrobase.app.feature.statistics.MacroInterval
import com.macrobase.app.feature.statistics.StatisticsViewModel
import com.macrobase.app.feature.statistics.averagesEmptyMessage
import com.macrobase.app.testutil.MutableTestClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate

/** Stats idea 9: the "Done logging" mark per day, and averages over finished days only. */
private val TODAY: LocalDate = LocalDate.of(2026, 11, 14)

private fun doneLoggingSource(path: String): String =
    listOf(File("src/main/java/com/macrobase/app/$path"), File("app/src/main/java/com/macrobase/app/$path"))
        .first { it.isFile }.readText().replace("\r\n", "\n")

/** The marks and the Averages switch in memory, for view model tests. */
internal class FinishedDaysPrefs(days: Set<LocalDate> = emptySet(), finishedOnly: Boolean = false) : PreferencesRepository {
    val profile = MutableStateFlow(UserPreferences())
    val days = MutableStateFlow(days)
    val finishedOnly = MutableStateFlow(finishedOnly)
    override suspend fun getPreferences() = profile.value
    override fun observePreferences(): Flow<UserPreferences> = profile
    override suspend fun updatePreferences(preferences: UserPreferences) { profile.value = preferences }
    override fun observeFinishedDays(): Flow<Set<LocalDate>> = days
    override suspend fun setDayFinished(date: LocalDate, finished: Boolean): Boolean {
        days.value = if (finished) days.value + date else days.value - date
        return true
    }
    override fun observeStatsFinishedDaysOnly(): Flow<Boolean> = finishedOnly
    override suspend fun setStatsFinishedDaysOnly(finishedOnly: Boolean) { this.finishedOnly.value = finishedOnly }
}

/** Day totals only, as the averages read them. */
private class AggregationsDiary(days: List<DailyMacroAggregation>) : DiaryRepository {
    val days = MutableStateFlow(days)
    override fun observeNutritionAggregations(startDate: LocalDate, endDate: LocalDate): Flow<List<DailyMacroAggregation>> =
        days.map { list -> list.filter { !it.date.isBefore(startDate) && !it.date.isAfter(endDate) }.sortedBy { it.date } }
    override suspend fun getNutritionAggregations(startDate: LocalDate, endDate: LocalDate) = observeNutritionAggregations(startDate, endDate).first()
    override suspend fun getDiaryForDate(date: LocalDate) = DailyNutritionSummary(date = date)
    override fun observeDiaryForDate(date: LocalDate): Flow<DailyNutritionSummary> = flowOf(DailyNutritionSummary(date = date))
    override suspend fun getEntryById(entryId: Long): DiaryEntry? = null
    override suspend fun addEntry(entry: DiaryEntry): Long = 0L
    override suspend fun addEntries(entries: List<DiaryEntry>) {}
    override suspend fun updateEntry(entry: DiaryEntry) {}
    override suspend fun deleteEntry(entryId: Long) {}
    override suspend fun getMonthlyAdherence(year: Int, month: Int): List<CalendarDaySummary> = emptyList()
    override fun observeMonthlyAdherence(year: Int, month: Int): Flow<List<CalendarDaySummary>> = flowOf(emptyList())
}

/** Goals that can differ by day. */
private class DayGoals(private val byDate: Map<LocalDate, Goal> = emptyMap()) : GoalsRepository {
    private val current = MutableStateFlow(Goal(dailyCalorieGoal = 2000.0, carbPercentage = 50.0, proteinPercentage = 25.0, fatPercentage = 25.0))
    override suspend fun getGoals() = current.value
    override fun observeGoals(): Flow<Goal> = current
    override suspend fun updateGoals(goals: Goal) { current.value = goals }
    override suspend fun getGoalForDate(date: LocalDate) = byDate[date] ?: current.value
}

private object NoWeights : WeightRepository {
    override suspend fun addWeightEntry(entry: WeightEntry) = 0L
    override suspend fun updateWeightEntry(entry: WeightEntry) {}
    override suspend fun deleteWeightEntry(entryId: Long) {}
    override suspend fun deleteWeightEntryForDate(date: LocalDate) {}
    override fun observeWeightForDate(date: LocalDate): Flow<WeightEntry?> = flowOf(null)
    override suspend fun getWeightForDate(date: LocalDate): WeightEntry? = null
    override fun observeWeightHistory(): Flow<List<WeightEntry>> = flowOf(emptyList())
    override suspend fun getWeightHistory(): List<WeightEntry> = emptyList()
    override fun observeWeightRange(startDate: LocalDate, endDate: LocalDate): Flow<List<WeightEntry>> = flowOf(emptyList())
    override suspend fun getWeightRange(startDate: LocalDate, endDate: LocalDate): List<WeightEntry> = emptyList()
}

private fun day(date: LocalDate, calories: Double, protein: Double = calories * 0.25 / 4.0) =
    DailyMacroAggregation(date, calories, protein, calories * 0.5 / 4.0, calories * 0.25 / 9.0)

/** The mark is saved in the profile's DataStore, as ISO dates, and never for a day still to come. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DoneLoggingPreferencesTests {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After
    fun tearDown() {
        scope.cancel()
    }

    private val store by lazy { PreferenceDataStoreFactory.create(scope = scope) { File(tempFolder.root, "done-prefs.preferences_pb") } }

    private fun prefs() = PreferencesRepositoryImpl(RuntimeEnvironment.getApplication(), store, MutableTestClock(TODAY))

    @Test
    fun markingAndUnmarkingADayIsSeenAtOnce() = runBlocking {
        val prefs = prefs()
        assertTrue(prefs.observeFinishedDays().first().isEmpty())

        assertTrue(prefs.setDayFinished(TODAY, true))
        assertTrue(prefs.setDayFinished(TODAY.minusDays(3), true))
        assertEquals(setOf(TODAY, TODAY.minusDays(3)), prefs.observeFinishedDays().first())

        // Marking again unmarks: the screen passes false for a day already marked
        assertTrue(prefs.setDayFinished(TODAY, false))
        assertEquals(setOf(TODAY.minusDays(3)), prefs.observeFinishedDays().first())
        assertEquals(setOf(TODAY.minusDays(3)), prefs.getFinishedDaysForBackup())
    }

    @Test
    fun aDayStillToComeIsRefused() = runBlocking {
        val prefs = prefs()

        assertFalse(prefs.setDayFinished(TODAY.plusDays(1), true))

        assertTrue(prefs.observeFinishedDays().first().isEmpty())
    }

    @Test
    fun theDaysAreStoredAsAStringSetOfIsoDates() = runBlocking {
        val prefs = prefs()
        prefs.setDayFinished(LocalDate.of(2026, 11, 2), true)
        prefs.setDayFinished(TODAY, true)

        val stored = store.data.first()[stringSetPreferencesKey("finished_log_days")]
        assertEquals(setOf("2026-11-02", "2026-11-14"), stored)
    }

    // Marking a day or picking "Finished days" is not saving a profile, so a Merge still fills one
    @Test
    fun theMarksAndTheSwitchAreNotASavedProfile() = runBlocking {
        val prefs = prefs()
        assertFalse(prefs.observeStatsFinishedDaysOnly().first())

        prefs.setDayFinished(TODAY, true)
        prefs.setStatsFinishedDaysOnly(true)

        assertTrue(prefs.observeStatsFinishedDaysOnly().first())
        assertFalse(prefs.hasSavedPreferences())
    }
}

/** With "Finished days" the averages, the target split and the day count cover finished days only. */
class FinishedDayAveragesTests {

    private val full = TODAY.minusDays(2)
    private val half = TODAY.minusDays(1)

    private fun statistics(goals: GoalsRepository = DayGoals()) = GetStatisticsUseCase(
        StatisticsRepositoryImpl(AggregationsDiary(listOf(day(full, 2000.0), day(half, 600.0))), goals, NoWeights)
    )

    @Test
    fun aHalfLoggedDayCountsOnlyInLoggedMode() = runBlocking {
        val stats = statistics()

        val logged = stats.observeMacroAverages(full, half).first()
        val finished = stats.observeFinishedDayAverages(full, half, setOf(full)).first()

        assertEquals(2, logged.loggedDaysCount)
        assertEquals(1300.0, logged.averageCalories, 1e-9)
        assertEquals(1, finished.loggedDaysCount)
        assertEquals(2000.0, finished.averageCalories, 1e-9)
        assertEquals(125.0, finished.averageProteinGrams, 1e-9)
    }

    @Test
    fun theTargetsComeFromTheFinishedDaysGoals() = runBlocking {
        val goals = DayGoals(
            mapOf(
                full to Goal(dailyCalorieGoal = 2400.0, carbPercentage = 40.0, proteinPercentage = 35.0, fatPercentage = 25.0),
                half to Goal(dailyCalorieGoal = 1600.0, carbPercentage = 60.0, proteinPercentage = 20.0, fatPercentage = 20.0)
            )
        )

        val finished = statistics(goals).observeFinishedDayAverages(full, half, setOf(full)).first()

        assertEquals(2400.0, finished.targetCalories, 1e-9)
        assertEquals(35.0, finished.targetProteinPercent, 1e-9)
        assertEquals(40.0, finished.targetCarbsPercent, 1e-9)
    }

    @Test
    fun noFinishedDayWithFoodGivesTheEmptyState() = runBlocking {
        val stats = statistics()

        // A finished day with nothing logged does not count either
        val none = stats.observeFinishedDayAverages(full, half, setOf(TODAY.minusDays(5), TODAY.minusDays(9))).first()
        val empty = stats.observeFinishedDayAverages(full, half, emptySet()).first()

        assertEquals(0, none.loggedDaysCount)
        assertFalse(none.hasSufficientData)
        assertEquals(0, empty.loggedDaysCount)
        assertFalse(empty.hasSufficientData)
    }

    @Test
    fun theEmptyMessageSaysWhereToMarkDays() {
        assertEquals("Mark days as done on Home to see averages of finished days.", averagesEmptyMessage(finishedDaysOnly = true))
        assertEquals(
            "Log meals in this period to see average macro distribution. Today counts once it ends.",
            averagesEmptyMessage(finishedDaysOnly = false)
        )
    }
}

/** The Averages view follows the saved switch; a user who never marks a day sees today's figures. */
@OptIn(ExperimentalCoroutinesApi::class)
class FinishedDayStatisticsViewModelTests {

    private val full = TODAY.minusDays(2)
    private val half = TODAY.minusDays(1)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(prefs: FinishedDaysPrefs) = StatisticsViewModel(
        GetStatisticsUseCase(StatisticsRepositoryImpl(AggregationsDiary(listOf(day(full, 2000.0), day(half, 600.0))), DayGoals(), NoWeights)),
        prefs,
        MutableTestClock(TODAY)
    ).also { it.onSelectMacroInterval(MacroInterval.DAYS_7) }

    @Test
    fun loggedDaysIsTheDefault() = runTest {
        val vm = viewModel(FinishedDaysPrefs(days = setOf(full)))

        val state = withTimeout(5_000) { vm.uiState.first { it.macroStats != null && it.macroInterval == MacroInterval.DAYS_7 } }

        assertFalse(state.finishedDaysOnly)
        assertEquals(2, state.macroStats!!.loggedDaysCount)
    }

    @Test
    fun finishedDaysCountsOnlyTheMarkedDaysAndFollowsNewMarks() = runTest {
        val prefs = FinishedDaysPrefs(days = setOf(full), finishedOnly = true)
        val vm = viewModel(prefs)

        val state = withTimeout(5_000) { vm.uiState.first { it.finishedDaysOnly && it.macroStats?.loggedDaysCount == 1 } }
        assertEquals(2000.0, state.macroStats!!.averageCalories, 1e-9)

        prefs.setDayFinished(half, true)
        val both = withTimeout(5_000) { vm.uiState.first { it.macroStats?.loggedDaysCount == 2 } }
        assertEquals(1300.0, both.macroStats!!.averageCalories, 1e-9)
    }

    @Test
    fun theSwitchIsSaved() = runTest {
        val prefs = FinishedDaysPrefs(days = setOf(full))
        val vm = viewModel(prefs)

        vm.onSetFinishedDaysOnly(true)

        val state = withTimeout(5_000) { vm.uiState.first { it.finishedDaysOnly && it.macroStats?.loggedDaysCount == 1 } }
        assertTrue(prefs.finishedOnly.value)
        assertEquals(1, state.macroStats!!.loggedDaysCount)
    }
}

/** Home's control marks the day on screen, today or before, and a finished day still takes food. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class DoneLoggingHomeTests {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var db: UserDatabase

    private val goals = DayGoals()

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
        clock = MutableTestClock(TODAY),
        preferencesRepository = prefs,
        getGoalsUseCase = GetGoalsUseCase(goals),
        addWaterEntryUseCase = AddWaterEntryUseCase(WaterRepositoryImpl(db))
    )

    @Test
    fun onlyTodayAndPastDaysCanBeMarked() {
        assertTrue(canMarkDayFinished(TODAY, TODAY))
        assertTrue(canMarkDayFinished(TODAY.minusDays(40), TODAY))
        assertFalse(canMarkDayFinished(TODAY.plusDays(1), TODAY))
    }

    @Test
    fun tappingMarksTheDayOnScreenAndTappingAgainUnmarksIt() = runBlocking {
        val prefs = FinishedDaysPrefs()
        val vm = viewModel(prefs)
        val collector = CoroutineScope(testDispatcher).launch { vm.isDayFinished.collect { } }
        vm.onPreviousDay()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.isDayFinished.value)

        vm.onToggleDayFinished()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(setOf(TODAY.minusDays(1)), prefs.days.value)
        assertTrue(vm.isDayFinished.value)

        // Today is not marked by marking yesterday
        vm.onNextDay()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.isDayFinished.value)

        vm.onPreviousDay()
        vm.onToggleDayFinished()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(prefs.days.value.isEmpty())
        assertFalse(vm.isDayFinished.value)
        collector.cancel()
    }

    @Test
    fun aDayStillToComeIsNeverMarked() = runBlocking {
        val prefs = FinishedDaysPrefs()
        val vm = viewModel(prefs)
        vm.onNextDay()

        vm.onToggleDayFinished()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(prefs.days.value.isEmpty())
    }

    @Test
    fun homeShowsTheControlBelowTheLastMealForTodayAndPastDaysOnly() {
        val home = doneLoggingSource("feature/dashboard/HomeScreen.kt")
        val meals = home.indexOf("visibleMeals.forEachIndexed")
        val control = home.indexOf("item(key = \"finished\"")
        val trackers = home.indexOf("item(key = \"trackers\"")

        assertTrue(meals in 0 until control)
        assertTrue(control < trackers)
        assertTrue(home.substring(meals, control).contains("if (canMarkDayFinished(selectedDate, viewModel.today()))"))
        assertTrue(home.contains("\"Done logging this day\""))
        assertTrue(home.contains("\"Day finished\""))
        assertTrue(home.contains("\"Undo\""))
        assertTrue(home.contains("viewModel.onToggleDayFinished()"))
    }

    // Marking a day never blocks logging: the meal "+" buttons stay as they are
    @Test
    fun aDayMarkedFinishedStillTakesFood() {
        val home = doneLoggingSource("feature/dashboard/HomeScreen.kt")
        val mealHeader = home.substring(home.indexOf("MealHeader(\n"), home.indexOf("item(key = \"finished\""))

        assertTrue(mealHeader.contains("onAddFoodClick = { onNavigateToSearch(meal.type, viewModel.dateForNewLog()?.toEpochDay()) }"))
        assertFalse(mealHeader.contains("isDayFinished"))
    }
}

/** The Averages view has the two-option switch at the top. */
class DoneLoggingStatsSourceTests {

    @Test
    fun theAveragesViewHasTheLoggedAndFinishedSwitch() {
        val stats = doneLoggingSource("feature/statistics/StatisticsScreen.kt")
        val averages = stats.substring(stats.indexOf("private fun MacroAveragesTab(")).substringBefore("\n}\n")

        assertTrue(averages.contains("\"Logged days\""))
        assertTrue(averages.contains("\"Finished days\""))
        assertTrue(averages.contains("viewModel.onSetFinishedDaysOnly("))
        assertTrue(averages.contains("averagesEmptyMessage(uiState.finishedDaysOnly)"))
        // The switch comes before the figures
        assertTrue(averages.indexOf("\"Finished days\"") < averages.indexOf("Daily intake averages"))
    }

    // The averages snapshot on disk is for logged days only: finished days skip it
    @Test
    fun finishedDayAveragesSkipTheSavedSnapshot() {
        val cache = doneLoggingSource("data/cache/FiguresCache.kt")

        assertFalse(cache.contains("observeFinishedDayAverages"))
    }
}

/** Backups carry the finished days: Merge adds them to the phone's, Replace uses the backup's. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DoneLoggingBackupTests {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val databases = mutableListOf<UserDatabase>()

    @After
    fun tearDown() {
        databases.forEach { it.close() }
        scope.cancel()
    }

    private class Phone(val preferences: PreferencesRepositoryImpl, val portability: PortabilityRepositoryImpl)

    private fun phone(name: String): Phone {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
            .also { databases += it }
        val goals = GoalsRepositoryImpl(context, PreferenceDataStoreFactory.create(scope = scope) { File(tempFolder.root, "$name-goals.preferences_pb") })
        val preferences = PreferencesRepositoryImpl(
            context,
            PreferenceDataStoreFactory.create(scope = scope) { File(tempFolder.root, "$name-prefs.preferences_pb") },
            MutableTestClock(TODAY)
        )
        return Phone(preferences, PortabilityRepositoryImpl(db, goals, preferences))
    }

    private fun backup(finishedDays: List<String>?) = MacroBaseBackupData(
        manifest = BackupManifestDto(exportedAt = "2026-11-14T08:00:00Z", timeZone = "UTC", counts = BackupRecordCountsDto(goals = 0, preferences = 1)),
        preferences = UserPreferencesBackupDto("Asha", "Rao", "UTC", "METRIC", null, null, null, 2500.0, finishedDays = finishedDays)
    )

    private suspend fun Phone.mark(vararg days: LocalDate) = days.forEach { preferences.setDayFinished(it, true) }

    private val nov10 = LocalDate.of(2026, 11, 10)
    private val nov11 = LocalDate.of(2026, 11, 11)
    private val nov12 = LocalDate.of(2026, 11, 12)

    @Test
    fun theExportCarriesTheFinishedDaysInOrder() = runBlocking {
        val phone = phone("export")
        phone.mark(nov12, nov10)

        val prefs = phone.portability.exportAllUserData().preferences!!

        assertEquals(listOf("2026-11-10", "2026-11-12"), prefs.finishedDays)
    }

    @Test
    fun theArchiveKeepsTheFinishedDays() = runBlocking {
        val phone = phone("archive")
        phone.mark(nov10, nov11)
        val out = ByteArrayOutputStream()
        phone.portability.writeBackupArchive(out)

        val read = BackupArchiveManager.extractAndValidateBackup(ByteArrayInputStream(out.toByteArray()))

        assertTrue(read.errorMessage, read.isValid)
        assertEquals(listOf("2026-11-10", "2026-11-11"), read.backupData!!.preferences!!.finishedDays)
    }

    // preferences.json as v1.2.8 wrote it: no finishedDays field
    @Test
    fun anOldPreferencesFileHasNoFinishedDays() {
        val old = """{
  "firstName": "Asha",
  "lastName": "Rao",
  "timeZone": "UTC",
  "unitSystem": "METRIC",
  "heightCm": null,
  "currentWeightKg": null,
  "targetWeightKg": null,
  "dailyWaterGoalMl": 2500.0
}"""
        assertNull(BackupJsonSerializer.parsePreferences(old).finishedDays)

        val written = BackupJsonSerializer.serializePreferences(backup(listOf("2026-11-10")).preferences!!)
        assertEquals(listOf("2026-11-10"), BackupJsonSerializer.parsePreferences(written).finishedDays)
    }

    @Test
    fun replacingWithAnOldBackupLeavesNoFinishedDays() = runBlocking {
        val phone = phone("replace-old")
        phone.mark(nov10)

        val result = phone.portability.importUserData(backup(finishedDays = null), ImportMode.OVERWRITE)

        assertTrue(result.errorMessage, result.isSuccess)
        assertTrue(phone.preferences.observeFinishedDays().first().isEmpty())
    }

    @Test
    fun mergingAnOldBackupKeepsThePhonesFinishedDays() = runBlocking {
        val phone = phone("merge-old")
        phone.mark(nov10)

        val result = phone.portability.importUserData(backup(finishedDays = null), ImportMode.MERGE)

        assertTrue(result.errorMessage, result.isSuccess)
        assertEquals(setOf(nov10), phone.preferences.observeFinishedDays().first())
    }

    // The union holds even when Merge keeps this phone's profile
    @Test
    fun mergeAddsTheBackupsDaysToThePhones() = runBlocking {
        val phone = phone("merge")
        phone.preferences.updatePreferences(UserPreferences(firstName = "Ravi"))
        phone.mark(nov10, nov11)

        val result = phone.portability.importUserData(backup(listOf("2026-11-11", "2026-11-12")), ImportMode.MERGE)

        assertTrue(result.errorMessage, result.isSuccess)
        assertEquals(setOf(nov10, nov11, nov12), phone.preferences.observeFinishedDays().first())
        assertEquals("Ravi", phone.preferences.getPreferences().firstName)
    }

    @Test
    fun replaceUsesTheBackupsDays() = runBlocking {
        val phone = phone("replace")
        phone.mark(nov10, nov11)

        val result = phone.portability.importUserData(backup(listOf("2026-11-12")), ImportMode.OVERWRITE)

        assertTrue(result.errorMessage, result.isSuccess)
        assertEquals(setOf(nov12), phone.preferences.observeFinishedDays().first())
    }

    // A damaged date or one still to come on this phone is left out, never a crash
    @Test
    fun badOrFutureDatesInABackupAreLeftOut() = runBlocking {
        val phone = phone("bad")

        val result = phone.portability.importUserData(backup(listOf("2026-11-12", "not a date", "2026-12-25")), ImportMode.OVERWRITE)

        assertTrue(result.errorMessage, result.isSuccess)
        assertEquals(setOf(nov12), phone.preferences.observeFinishedDays().first())
    }
}
