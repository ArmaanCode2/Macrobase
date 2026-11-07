package com.macrobase.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.room.RoomDatabase
import com.macrobase.app.data.database.UserDatabase
import com.macrobase.app.data.repository.DiaryRepositoryImpl
import com.macrobase.app.data.repository.GoalsRepositoryImpl
import com.macrobase.app.data.repository.PortabilityRepositoryImpl
import com.macrobase.app.data.repository.PreferencesRepositoryImpl
import com.macrobase.app.data.repository.rank.RankRepositoryImpl
import com.macrobase.app.domain.model.BackupManifestDto
import com.macrobase.app.domain.model.BackupRecordCountsDto
import com.macrobase.app.domain.model.BackupValidationResult
import com.macrobase.app.domain.model.DiaryEntryBackupDto
import com.macrobase.app.domain.model.FitnessGoal
import com.macrobase.app.domain.model.GoalBackupDto
import com.macrobase.app.domain.model.GoalTransitionBackupDto
import com.macrobase.app.domain.model.ImportMode
import com.macrobase.app.domain.model.ImportResult
import com.macrobase.app.domain.model.MacroBaseBackupData
import com.macrobase.app.domain.model.UserPreferencesBackupDto
import com.macrobase.app.domain.model.WeightEntryBackupDto
import com.macrobase.app.domain.repository.PortabilityRepository
import com.macrobase.app.domain.usecase.ExportUserDataUseCase
import com.macrobase.app.domain.usecase.ImportUserDataUseCase
import com.macrobase.app.domain.usecase.ValidateBackupUseCase
import com.macrobase.app.feature.importexport.ImportExportViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The backup used by the restore tests: one diary day, one weight, goals and a profile. */
private fun restoreBackup(goals: GoalBackupDto) = MacroBaseBackupData(
    manifest = BackupManifestDto(exportedAt = "2026-10-05T08:00:00Z", timeZone = "UTC", counts = BackupRecordCountsDto()),
    diaryEntries = listOf(
        DiaryEntryBackupDto(
            uuid = "d-1",
            dateEpochDay = 20_366L,
            dateString = "2025-10-05",
            mealType = "LUNCH",
            foodId = 1_234L,
            foodName = "Oats",
            userQuantity = 1.0,
            servingDescription = "100 g",
            gramWeight = 100.0,
            loggedCalories = 389.0,
            loggedProtein = 16.9,
            loggedCarbs = 66.3,
            loggedFat = 6.9,
            createdAt = 1_000L
        )
    ),
    customFoods = emptyList(),
    recipes = emptyList(),
    weightEntries = listOf(WeightEntryBackupDto(20_366L, "2025-10-05", 72.5, null, 1_000L)),
    waterEntries = emptyList(),
    goals = goals,
    preferences = UserPreferencesBackupDto("Asha", "Rao", "Asia/Kolkata", "METRIC", 165.0, 72.5, 65.0, 3000.0)
)

/**
 * BUG-010: a restore is several stores, so leaving the screen during it must not stop it half way.
 * These tests use Room's real transaction executor: the other backup tests run it inline, where a
 * cancelled caller and a running transaction can never disagree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreCancellationTests {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    /** Stands in for viewModelScope, which is cancelled when the user leaves the screen. */
    private val screenScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val context get() = RuntimeEnvironment.getApplication()
    private val insideTransaction = CountDownLatch(1)
    private val releaseTransaction = CountDownLatch(1)
    private val refreshes = AtomicInteger()

    private val db: UserDatabase = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), UserDatabase::class.java)
        .setQueryCallback(
            RoomDatabase.QueryCallback { sql, _ ->
                // Runs on the transaction's own thread: the restore stops here, half written
                if (sql.contains("weight_entries") && sql.startsWith("INSERT", ignoreCase = true)) {
                    insideTransaction.countDown()
                    releaseTransaction.await(20, TimeUnit.SECONDS)
                }
            },
            Runnable::run
        )
        .build()

    private val goals = GoalsRepositoryImpl(
        context,
        PreferenceDataStoreFactory.create(scope = storeScope) { File(tempFolder.root, "goals.preferences_pb") }
    )
    private val preferences = PreferencesRepositoryImpl(
        context,
        PreferenceDataStoreFactory.create(scope = storeScope) { File(tempFolder.root, "prefs.preferences_pb") }
    )

    @After
    fun tearDown() {
        releaseTransaction.countDown()
        screenScope.cancel()
        db.close()
        storeScope.cancel()
    }

    private fun backup() = restoreBackup(
        GoalBackupDto(
            dailyCalorieGoal = 1600.0,
            carbPercentage = 40.0,
            proteinPercentage = 35.0,
            fatPercentage = 25.0,
            fitnessGoal = "CUTTING",
            maintenanceCalories = 2000.0,
            transitions = listOf(GoalTransitionBackupDto("2000-01-01", "CUTTING", 2000.0, 1600.0, 40.0, 35.0, 25.0))
        )
    )

    private fun leaveMidRestore(mode: ImportMode) = runBlocking {
        val portability = PortabilityRepositoryImpl(db, goals, preferences) { refreshes.incrementAndGet() }

        val restore = screenScope.launch { portability.importUserData(backup(), mode) }
        assertTrue("the restore never reached its transaction", insideTransaction.await(20, TimeUnit.SECONDS))
        restore.cancel()
        releaseTransaction.countDown()

        withTimeout(20_000) {
            restore.join()
            // The transaction's own thread decides when the rows appear
            while (db.diaryDao().getAllEntries().isEmpty()) delay(20)
        }
    }

    @Test
    fun overwriteLeftMidTransactionStillRestoresGoalsProfileAndTheWidget() {
        leaveMidRestore(ImportMode.OVERWRITE)

        runBlocking {
            assertEquals(listOf("d-1"), db.diaryDao().getAllEntries().map { it.uuid })
            assertEquals(1600.0, goals.getGoals().dailyCalorieGoal, 0.0)
            assertEquals(FitnessGoal.CUTTING, goals.getGoals().fitnessGoal)
            assertEquals("Asha", preferences.getPreferences().firstName)
            assertEquals(1, refreshes.get())
        }
    }

    @Test
    fun mergeOnAFreshPhoneLeftMidTransactionStillFillsGoalsAndProfile() {
        leaveMidRestore(ImportMode.MERGE)

        runBlocking {
            assertEquals(1600.0, goals.getGoals().dailyCalorieGoal, 0.0)
            assertEquals("Asha", preferences.getPreferences().firstName)
            assertEquals(1, refreshes.get())
        }
    }
}

/** BUG-010: the screen's blocking dialog follows isImporting, so it must cover the whole restore. */
@OptIn(ExperimentalCoroutinesApi::class)
class RestoreProgressTests {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class SlowRestore(val gate: CompletableDeferred<Unit>) : PortabilityRepository {
        override suspend fun exportAllUserData() = restoreBackup(GoalBackupDto(2000.0, 50.0, 25.0, 25.0))
        override suspend fun writeBackupArchive(outputStream: OutputStream) = Unit
        override suspend fun validateBackupArchive(inputStream: InputStream) = BackupValidationResult(
            isValid = true,
            manifest = exportAllUserData().manifest,
            backupData = exportAllUserData()
        )
        override suspend fun importUserData(backupData: MacroBaseBackupData, mode: ImportMode): ImportResult {
            gate.await()
            return ImportResult(true, mode, message = "done")
        }
    }

    @Test
    fun theScreenStaysBusyUntilTheRestoreHasItsResult() {
        val gate = CompletableDeferred<Unit>()
        val repo = SlowRestore(gate)
        val vm = ImportExportViewModel(ExportUserDataUseCase(repo), ValidateBackupUseCase(repo), ImportUserDataUseCase(repo))
        vm.validateAndPreviewBackup { ByteArrayInputStream(ByteArray(0)) }

        vm.onSelectImportMode(ImportMode.MERGE)
        assertTrue(vm.uiState.value.isImporting)
        assertNull(vm.uiState.value.importResult)

        gate.complete(Unit)
        assertFalse(vm.uiState.value.isImporting)
        assertEquals("done", vm.uiState.value.importResult?.message)
    }
}

/**
 * BUG-011: Merge keeps this phone's goal, but goals are per-day history: the imported days must keep
 * the goals they had. The phone was set up on 20 October; the backup's diary starts in March.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MergeGoalHistoryTests {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val context get() = RuntimeEnvironment.getApplication()
    private val today = LocalDate.of(2026, 10, 25)
    private val phoneFirstDay = LocalDate.of(2026, 10, 20)
    private val clock = Clock.fixed(today.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault())

    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), UserDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor { it.run() }
        .setTransactionExecutor { it.run() }
        .build()
    private val store = PreferenceDataStoreFactory.create(scope = scope) { File(tempFolder.root, "goals.preferences_pb") }
    private val goals = GoalsRepositoryImpl(context, store) { today }
    private val preferences = PreferencesRepositoryImpl(
        context,
        PreferenceDataStoreFactory.create(scope = scope) { File(tempFolder.root, "prefs.preferences_pb") }
    )
    private val portability = PortabilityRepositoryImpl(db, goals, preferences)

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
    }

    /** The new phone: Maintaining at 2,000 kcal, saved on 20 October. */
    private suspend fun phoneSetUp() {
        store.edit {
            it[doublePreferencesKey("daily_calorie_goal")] = 2000.0
            it[doublePreferencesKey("carb_percentage")] = 50.0
            it[doublePreferencesKey("protein_percentage")] = 25.0
            it[doublePreferencesKey("fat_percentage")] = 25.0
            it[stringPreferencesKey("fitness_goal")] = "MAINTAINING"
            it[doublePreferencesKey("maintenance_calories")] = 2000.0
            it[stringPreferencesKey("transitions_history")] =
                "2000-01-01|MAINTAINING|2000.0|2000.0|50.0|25.0|25.0\n2026-10-20|MAINTAINING|2000.0|2000.0|50.0|25.0|25.0"
        }
    }

    private fun oldPhoneGoals(transitions: List<GoalTransitionBackupDto>? = oldPhoneHistory) = GoalBackupDto(
        dailyCalorieGoal = 1600.0,
        carbPercentage = 40.0,
        proteinPercentage = 35.0,
        fatPercentage = 25.0,
        fitnessGoal = "CUTTING",
        maintenanceCalories = 2000.0,
        transitions = transitions
    )

    private val oldPhoneHistory = listOf(
        GoalTransitionBackupDto("2000-01-01", "MAINTAINING", 1800.0, 1800.0, 50.0, 25.0, 25.0),
        GoalTransitionBackupDto("2026-03-01", "CUTTING", 2000.0, 1600.0, 40.0, 35.0, 25.0),
        // After this phone's first saved day, so this phone's own goal stands
        GoalTransitionBackupDto("2026-10-22", "BULKING", 2800.0, 3000.0, 50.0, 25.0, 25.0)
    )

    private fun backupWith(goalBackup: GoalBackupDto, vararg days: String) = restoreBackup(goalBackup).let { base ->
        base.copy(
            diaryEntries = days.map { day ->
                base.diaryEntries.single().copy(uuid = "d-$day", dateEpochDay = LocalDate.parse(day).toEpochDay(), dateString = day)
            }
        )
    }

    @Test
    fun theImportedDaysKeepTheGoalsTheyHad() = runBlocking {
        phoneSetUp()

        val result = portability.importUserData(backupWith(oldPhoneGoals(), "2026-04-01"), ImportMode.MERGE)

        assertTrue(result.errorMessage, result.isSuccess)
        assertEquals(LocalDate.of(2026, 3, 1), goals.getGoalHistoryStart())
        val april = goals.getGoalForDate(LocalDate.of(2026, 4, 1))
        assertEquals(1600.0, april.dailyCalorieGoal, 0.0)
        assertEquals(35.0, april.proteinPercentage, 0.0)
        assertEquals(FitnessGoal.CUTTING, april.fitnessGoal)
        // The days before the backup's first change had the backup's starting goal, not this phone's
        assertEquals(1800.0, goals.getGoalForDate(LocalDate.of(2026, 2, 1)).dailyCalorieGoal, 0.0)
    }

    @Test
    fun thePhonesOwnGoalAndDaysStayItsOwn() = runBlocking {
        phoneSetUp()

        portability.importUserData(backupWith(oldPhoneGoals(), "2026-04-01"), ImportMode.MERGE)

        val current = goals.getGoals()
        assertEquals(2000.0, current.dailyCalorieGoal, 0.0)
        assertEquals(FitnessGoal.MAINTAINING, current.fitnessGoal)
        assertNull(current.scheduledFitnessGoal)
        assertEquals(2000.0, goals.getGoalForDate(phoneFirstDay).dailyCalorieGoal, 0.0)
        assertEquals(2000.0, goals.getGoalForDate(LocalDate.of(2026, 10, 22)).dailyCalorieGoal, 0.0)
        assertEquals(FitnessGoal.MAINTAINING, goals.getGoalForDate(LocalDate.of(2026, 10, 22)).fitnessGoal)
    }

    @Test
    fun theRankCountsTheImportedDays() = runBlocking {
        phoneSetUp()
        val rank = RankRepositoryImpl(goals, DiaryRepositoryImpl(db, goals), clock = clock, dispatcher = Dispatchers.Unconfined)

        portability.importUserData(backupWith(oldPhoneGoals(), "2026-04-01", "2026-04-02", "2026-04-03"), ImportMode.MERGE)

        assertEquals(3, rank.getRankProfile().loggedDaysCount)
    }

    @Test
    fun mergingTheSameBackupTwiceChangesNothingMore() = runBlocking {
        phoneSetUp()
        val backup = backupWith(oldPhoneGoals(), "2026-04-01")

        portability.importUserData(backup, ImportMode.MERGE)
        val once = goals.exportGoalBackup()
        portability.importUserData(backup, ImportMode.MERGE)

        assertEquals(once, goals.exportGoalBackup())
    }

    @Test
    fun aBackupWithoutHistoryLeavesThePhonesHistoryAlone() = runBlocking {
        phoneSetUp()
        val before = goals.exportGoalBackup()

        portability.importUserData(backupWith(oldPhoneGoals(transitions = null), "2026-04-01"), ImportMode.MERGE)

        assertEquals(before, goals.exportGoalBackup())
        assertEquals(phoneFirstDay, goals.getGoalHistoryStart())
    }

    @Test
    fun aDamagedHistoryRecordIsLeftOut() = runBlocking {
        phoneSetUp()
        val damaged = listOf(
            GoalTransitionBackupDto("not-a-date", "CUTTING", 2000.0, 1600.0, 40.0, 35.0, 25.0),
            GoalTransitionBackupDto("2026-02-01", "CUT|TING", 2000.0, 1600.0, 40.0, 35.0, 25.0),
            GoalTransitionBackupDto("2026-03-01", "CUTTING", 2000.0, 1600.0, 40.0, 35.0, 25.0)
        )

        portability.importUserData(backupWith(oldPhoneGoals(damaged), "2026-04-01"), ImportMode.MERGE)

        assertEquals(LocalDate.of(2026, 3, 1), goals.getGoalHistoryStart())
        assertEquals(1600.0, goals.getGoalForDate(LocalDate.of(2026, 4, 1)).dailyCalorieGoal, 0.0)
    }

    @Test
    fun aPhoneWithGoalsButNoHistoryYetStillGetsTheImportedDays() = runBlocking {
        store.edit {
            it[doublePreferencesKey("daily_calorie_goal")] = 2000.0
            it[stringPreferencesKey("fitness_goal")] = "MAINTAINING"
        }

        portability.importUserData(backupWith(oldPhoneGoals(), "2026-04-01"), ImportMode.MERGE)

        assertEquals(LocalDate.of(2026, 3, 1), goals.getGoalHistoryStart())
        assertEquals(1600.0, goals.getGoalForDate(LocalDate.of(2026, 4, 1)).dailyCalorieGoal, 0.0)
        assertEquals(2000.0, goals.getGoals().dailyCalorieGoal, 0.0)
    }
}
