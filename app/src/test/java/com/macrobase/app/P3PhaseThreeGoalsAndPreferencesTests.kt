package com.macrobase.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.macrobase.app.data.database.UserDatabase
import com.macrobase.app.data.repository.GoalsRepositoryImpl
import com.macrobase.app.data.repository.PortabilityRepositoryImpl
import com.macrobase.app.data.repository.PreferencesRepositoryImpl
import com.macrobase.app.domain.model.BackupManifestDto
import com.macrobase.app.domain.model.BackupRecordCountsDto
import com.macrobase.app.domain.model.FitnessGoal
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.GoalBackupDto
import com.macrobase.app.domain.model.ImportMode
import com.macrobase.app.domain.model.MacroBaseBackupData
import com.macrobase.app.domain.model.UnitSystem
import com.macrobase.app.domain.model.UserPreferences
import com.macrobase.app.domain.model.UserPreferencesBackupDto
import com.macrobase.app.domain.repository.GoalsRepository
import com.macrobase.app.domain.repository.PreferencesRepository
import com.macrobase.app.domain.usecase.CancelScheduledGoalUseCase
import com.macrobase.app.domain.usecase.GetGoalsUseCase
import com.macrobase.app.domain.usecase.UpdateGoalsUseCase
import com.macrobase.app.feature.preferences.PreferenceField
import com.macrobase.app.feature.preferences.PreferencesViewModel
import com.macrobase.app.feature.weight.parseWeightInput
import com.macrobase.app.feature.weight.weightFromInput
import com.macrobase.app.feature.weight.weightPrefillText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.Locale

/**
 * P3 phase 3: what the user types in Preferences and Weight is what gets saved, in every locale,
 * or the user sees why not (BUG-026, BUG-027, BUG-028, BUG-029, BUG-031).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class P3PhaseThreeGoalsAndPreferencesTests {

    private val originalLocale = Locale.getDefault()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
        Dispatchers.resetMain()
    }

    // ---------------------------------------------------------------------------------------
    // BUG-026: macro split
    // ---------------------------------------------------------------------------------------

    @Test
    fun aSplitThatAddsUpTo100IsValidEvenWithFloatingPointError() {
        assertTrue(Goal(carbPercentage = 30.4, proteinPercentage = 34.8, fatPercentage = 34.8).isValidPercentageSum)
        assertFalse(Goal(carbPercentage = 33.5, proteinPercentage = 33.5, fatPercentage = 33.5).isValidPercentageSum)
        assertEquals(100, Goal(carbPercentage = 30.4, proteinPercentage = 34.8, fatPercentage = 34.8).totalPercentage)
    }

    @Test
    fun theSplitThatUsedToFailSilentlyNowSaves() {
        val goals = FakeGoals()
        val vm = viewModel(goals = goals)

        vm.onFieldChanged(PreferenceField.CARBS, "30.4")
        vm.onFieldChanged(PreferenceField.PROTEIN, "34.8")
        vm.onFieldChanged(PreferenceField.FAT, "34.8")
        assertTrue(vm.check().isValid)
        vm.save()

        assertTrue(vm.form.value.showSavedMessage)
        assertEquals(30.4, goals.flow.value.carbPercentage, 0.0)
    }

    // The banner and the error show the sum as validated, so 99.99 never reads as "100.0"
    @Test
    fun threeThirdsAreRefusedAndShownAs99Point99() = runBlocking {
        val vm = viewModel()
        listOf(PreferenceField.CARBS, PreferenceField.PROTEIN, PreferenceField.FAT).forEach { vm.onFieldChanged(it, "33.33") }

        val check = vm.check()
        assertFalse(check.isMacroSumValid)
        assertEquals("99.99", PreferencesViewModel.formatPlain(check.goal.totalPercentageExact))
        val error = UpdateGoalsUseCase(FakeGoals())(check.goal).exceptionOrNull()
        assertTrue(error!!.message!!.contains("99.99%"))
    }

    @Test
    fun storedKcalAndMacroValuesShowTheirDecimals() {
        val vm = viewModel(goals = FakeGoals(Goal(dailyCalorieGoal = 1850.5, carbPercentage = 33.33, proteinPercentage = 33.33, fatPercentage = 33.34)))

        assertEquals("1850.5", vm.form.value.text(PreferenceField.CALORIES))
        assertEquals("33.33", vm.form.value.text(PreferenceField.CARBS))
        assertEquals("2000", vm.form.value.text(PreferenceField.MAINTENANCE))
        assertTrue(vm.check().isValid)
    }

    @Test
    fun aGoalThatCannotBeSavedShowsWhyAndLeavesTheProfileUnsaved() {
        val prefs = FakePrefs()
        val vm = viewModel(prefs = prefs, goals = FakeGoals(failWrites = true))

        vm.onFirstNameChanged("Asha")
        vm.save()

        assertNotNull(vm.form.value.saveError)
        assertFalse(vm.form.value.showSavedMessage)
        assertEquals(0, prefs.saves)
    }

    // ---------------------------------------------------------------------------------------
    // BUG-028: validation and clearing
    // ---------------------------------------------------------------------------------------

    @Test
    fun aNegativeWeightIsRefusedAndNothingIsSaved() {
        val prefs = FakePrefs()
        val vm = viewModel(prefs = prefs)

        vm.onFieldChanged(PreferenceField.WEIGHT, "-5")
        assertEquals("Enter 20 to 500 kg", vm.check().error(PreferenceField.WEIGHT))
        assertFalse(vm.check().isValid)
        vm.save()

        assertEquals(0, prefs.saves)
        assertEquals(72.5, prefs.flow.value.currentWeightKg!!, 0.0)
    }

    @Test
    fun aCommaDecimalSavesTheNumberTyped() {
        Locale.setDefault(Locale.GERMANY)
        val prefs = FakePrefs()
        val vm = viewModel(prefs = prefs)

        vm.onFieldChanged(PreferenceField.WEIGHT, "72,5")
        vm.onFieldChanged(PreferenceField.TARGET_WEIGHT, "68,4")
        vm.save()

        assertEquals(72.5, prefs.flow.value.currentWeightKg!!, 1e-9)
        assertEquals(68.4, prefs.flow.value.targetWeightKg!!, 1e-9)
    }

    @Test
    fun clearingTheTargetWeightSavesNoTarget() {
        val prefs = FakePrefs()
        val vm = viewModel(prefs = prefs)

        vm.onFieldChanged(PreferenceField.TARGET_WEIGHT, "")
        assertNull(vm.check().error(PreferenceField.TARGET_WEIGHT))
        vm.save()

        assertNull(prefs.flow.value.targetWeightKg)
    }

    @Test
    fun theWaterGoalIsRequiredAndShownInTheChosenUnit() {
        val vm = viewModel(prefs = FakePrefs(UserPreferences(unitSystem = UnitSystem.IMPERIAL)))

        vm.onFieldChanged(PreferenceField.WATER, "")
        assertEquals("Enter 8.5 to 338.1 fl oz", vm.check().error(PreferenceField.WATER))
        vm.onFieldChanged(PreferenceField.WATER, "64")
        assertNull(vm.check().error(PreferenceField.WATER))
        // Fluid ounces can have decimals, so this is not a thousands separator
        vm.onFieldChanged(PreferenceField.WATER, "67.628")
        assertNull(vm.check().error(PreferenceField.WATER))
    }

    // "2,000" read as 2 would save a 2 kcal target
    @Test
    fun aThousandsSeparatorIsRefusedRatherThanSavedAsTwoKcal() {
        Locale.setDefault(Locale.GERMANY)
        val goals = FakeGoals()
        val vm = viewModel(goals = goals)

        for (text in listOf("2,000", "2.000", "1.850")) {
            vm.onFieldChanged(PreferenceField.CALORIES, text)
            assertEquals("Leave out the thousands separator", vm.check().error(PreferenceField.CALORIES))
        }
        vm.onFieldChanged(PreferenceField.WATER, "2.500")
        assertEquals("Leave out the thousands separator", vm.check().error(PreferenceField.WATER))
        vm.save()
        assertEquals(2000.0, goals.flow.value.dailyCalorieGoal, 0.0)

        vm.onFieldChanged(PreferenceField.CALORIES, "1850,5")
        assertNull(vm.check().error(PreferenceField.CALORIES))
    }

    // ---------------------------------------------------------------------------------------
    // BUG-029: the unit toggle and later emissions keep what was typed
    // ---------------------------------------------------------------------------------------

    @Test
    fun switchingUnitsConvertsTheTypedWeight() {
        val vm = viewModel()

        vm.onFieldChanged(PreferenceField.WEIGHT, "80")
        vm.onUnitSystemChanged(UnitSystem.IMPERIAL)
        assertEquals("176.4", vm.form.value.text(PreferenceField.WEIGHT))
        vm.onUnitSystemChanged(UnitSystem.METRIC)
        assertEquals("80", vm.form.value.text(PreferenceField.WEIGHT))
    }

    @Test
    fun switchingUnitsBackShowsExactlyWhatWasTyped() {
        val vm = viewModel()

        vm.onFieldChanged(PreferenceField.WATER, "2000")
        vm.onFieldChanged(PreferenceField.HEIGHT, "180")
        vm.onFieldChanged(PreferenceField.WEIGHT, "72.55")
        vm.onUnitSystemChanged(UnitSystem.IMPERIAL)
        assertEquals("67.6", vm.form.value.text(PreferenceField.WATER))
        vm.onUnitSystemChanged(UnitSystem.METRIC)

        assertEquals("2000", vm.form.value.text(PreferenceField.WATER))
        assertEquals("180", vm.form.value.text(PreferenceField.HEIGHT))
        assertEquals("72.55", vm.form.value.text(PreferenceField.WEIGHT))
    }

    @Test
    fun aNumberTypedInOneUnitSavesExactlyAfterSwitching() {
        val prefs = FakePrefs()
        val vm = viewModel(prefs = prefs)

        vm.onFieldChanged(PreferenceField.WEIGHT, "72.55")
        vm.onFieldChanged(PreferenceField.WATER, "2000")
        vm.onUnitSystemChanged(UnitSystem.IMPERIAL)
        vm.save()

        assertEquals(UnitSystem.IMPERIAL, prefs.flow.value.unitSystem)
        assertEquals(72.55, prefs.flow.value.currentWeightKg!!, 1e-9)
        assertEquals(2000.0, prefs.flow.value.dailyWaterGoalMl, 1e-9)
    }

    @Test
    fun textThatIsNotANumberSurvivesTheUnitToggle() {
        val vm = viewModel()

        vm.onFieldChanged(PreferenceField.HEIGHT, "17x")
        vm.onUnitSystemChanged(UnitSystem.IMPERIAL)
        assertEquals("17x", vm.form.value.text(PreferenceField.HEIGHT))
    }

    // Untouched fields keep their exact stored values through a unit round trip (BUG-050)
    @Test
    fun savingInAnotherUnitDoesNotDriftUntouchedValues() {
        val prefs = FakePrefs(UserPreferences(currentWeightKg = 72.53, dailyWaterGoalMl = 2500.0))
        val vm = viewModel(prefs = prefs)

        vm.onUnitSystemChanged(UnitSystem.IMPERIAL)
        vm.save()

        assertEquals(UnitSystem.IMPERIAL, prefs.flow.value.unitSystem)
        assertEquals(72.53, prefs.flow.value.currentWeightKg!!, 0.0)
        assertEquals(2500.0, prefs.flow.value.dailyWaterGoalMl, 0.0)
    }

    @Test
    fun aLaterPreferencesUpdateDoesNotWipeTheTypedText() {
        val prefs = FakePrefs()
        val vm = viewModel(prefs = prefs)

        vm.onFieldChanged(PreferenceField.WEIGHT, "81")
        vm.onFirstNameChanged("Asha")
        prefs.flow.value = prefs.flow.value.copy(firstName = "Changed elsewhere", currentWeightKg = 90.0)

        assertEquals("81", vm.form.value.text(PreferenceField.WEIGHT))
        assertEquals("Asha", vm.form.value.firstName)
    }

    @Test
    fun fieldsTheUserDidNotTouchFollowAnOutsideChange() {
        val prefs = FakePrefs()
        val vm = viewModel(prefs = prefs)

        vm.onFieldChanged(PreferenceField.WEIGHT, "81")
        prefs.flow.value = prefs.flow.value.copy(firstName = "Restored", heightCm = 180.0, currentWeightKg = 90.0)

        assertEquals("Restored", vm.form.value.firstName)
        assertEquals("180.0", vm.form.value.text(PreferenceField.HEIGHT))
        assertEquals("81", vm.form.value.text(PreferenceField.WEIGHT))
    }

    // A backup restored while the screen is open must survive the next save
    @Test
    fun savingAfterARestoreKeepsTheRestoredProfileAndStrategy() {
        val prefs = FakePrefs()
        val goals = FakeGoals()
        val vm = viewModel(prefs = prefs, goals = goals)

        prefs.flow.value = UserPreferences(
            firstName = "Restored", unitSystem = UnitSystem.IMPERIAL, currentWeightKg = 90.0, targetWeightKg = 80.0, heightCm = 180.0
        )
        goals.flow.value = Goal(fitnessGoal = FitnessGoal.BULKING, maintenanceCalories = 2600.0)
        vm.onFieldChanged(PreferenceField.CALORIES, "2700")
        vm.save()

        assertEquals("Restored", prefs.flow.value.firstName)
        assertEquals(UnitSystem.IMPERIAL, prefs.flow.value.unitSystem)
        assertEquals(90.0, prefs.flow.value.currentWeightKg!!, 0.0)
        assertEquals(FitnessGoal.BULKING, goals.flow.value.fitnessGoal)
        assertNull(goals.flow.value.scheduledFitnessGoal)
        assertEquals(2700.0, goals.flow.value.dailyCalorieGoal, 0.0)
    }

    @Test
    fun whatIsTypedDuringASaveStaysAndCancelWaits() {
        val gate = CompletableDeferred<Unit>()
        val goals = FakeGoals(pendingCutting(), gate = gate)
        val vm = viewModel(goals = goals)

        vm.onFieldChanged(PreferenceField.CALORIES, "1900")
        vm.save()
        assertTrue(vm.form.value.isSaving)
        vm.onFieldChanged(PreferenceField.WATER, "3000")
        vm.cancelScheduledChange()
        gate.complete(Unit)

        assertFalse(vm.form.value.isSaving)
        assertEquals(0, goals.cancels)
        assertEquals("1900", vm.form.value.text(PreferenceField.CALORIES))
        assertFalse(PreferenceField.CALORIES in vm.form.value.typed)
        assertEquals("3000", vm.form.value.text(PreferenceField.WATER))
        assertTrue(PreferenceField.WATER in vm.form.value.typed)
    }

    // ---------------------------------------------------------------------------------------
    // BUG-031: a pending change can be undone
    // ---------------------------------------------------------------------------------------

    @Test
    fun theFormShowsThePendingStrategy() {
        val vm = viewModel(goals = FakeGoals(pendingCutting()))
        assertEquals(FitnessGoal.CUTTING, vm.form.value.selectedGoal)
    }

    @Test
    fun pickingTheCurrentStrategyAgainAndSavingCancelsThePendingChange() {
        val goals = FakeGoals(pendingCutting())
        val vm = viewModel(goals = goals)

        vm.onStrategySelected(FitnessGoal.MAINTAINING)
        vm.onFieldChanged(PreferenceField.MAINTENANCE, "2000")
        vm.save()

        assertEquals(1, goals.cancels)
        assertNull(goals.flow.value.scheduledFitnessGoal)
    }

    @Test
    fun goingBackToTheCurrentStrategyBringsBackItsMaintenance() {
        val goals = FakeGoals(pendingCutting())
        val vm = viewModel(goals = goals)
        assertEquals("2200", vm.form.value.text(PreferenceField.MAINTENANCE))

        vm.onStrategySelected(FitnessGoal.MAINTAINING)
        assertEquals("2000", vm.form.value.text(PreferenceField.MAINTENANCE))
        vm.onStrategySelected(FitnessGoal.CUTTING)
        assertEquals("2200", vm.form.value.text(PreferenceField.MAINTENANCE))
        vm.onStrategySelected(FitnessGoal.MAINTAINING)
        vm.save()

        assertEquals(1, goals.cancels)
        assertNull(goals.flow.value.scheduledFitnessGoal)
        assertEquals(FitnessGoal.MAINTAINING, goals.flow.value.fitnessGoal)
    }

    // Equal weights auto-match "Maintaining", the strategy in force; that alone must not cancel
    @Test
    fun aWeightEditNeverCancelsAPendingChange() {
        val goals = FakeGoals(pendingCutting())
        val vm = viewModel(prefs = FakePrefs(UserPreferences(currentWeightKg = 70.0, targetWeightKg = 60.0)), goals = goals)

        vm.onFieldChanged(PreferenceField.TARGET_WEIGHT, "70")
        vm.save()

        assertEquals(0, goals.cancels)
        assertEquals(FitnessGoal.CUTTING, goals.flow.value.scheduledFitnessGoal)
        assertEquals(2200.0, goals.flow.value.scheduledMaintenanceCalories!!, 0.0)
    }

    @Test
    fun aWeightEditKeepsAChangeMadeAfterTheScreenOpened() {
        val goals = FakeGoals()
        val vm = viewModel(prefs = FakePrefs(UserPreferences(currentWeightKg = 70.0, targetWeightKg = 60.0)), goals = goals)
        goals.flow.value = pendingCutting()

        vm.onFieldChanged(PreferenceField.TARGET_WEIGHT, "70")
        assertEquals(FitnessGoal.CUTTING, vm.form.value.selectedGoal)
        vm.save()

        assertEquals(FitnessGoal.CUTTING, goals.flow.value.scheduledFitnessGoal)
        assertEquals(2200.0, goals.flow.value.scheduledMaintenanceCalories!!, 0.0)
    }

    @Test
    fun cancellingWhenNothingIsPendingSaysSo() {
        val vm = viewModel()

        vm.cancelScheduledChange()

        assertNotNull(vm.form.value.cancelMessage)
    }

    @Test
    fun savingOnlyNewTargetsKeepsThePendingChange() {
        val goals = FakeGoals(pendingCutting())
        val vm = viewModel(goals = goals)

        vm.onFieldChanged(PreferenceField.CALORIES, "1900")
        vm.save()

        assertEquals(0, goals.cancels)
        assertEquals(FitnessGoal.CUTTING, goals.flow.value.scheduledFitnessGoal)
        assertEquals(1900.0, goals.flow.value.dailyCalorieGoal, 0.0)
    }

    @Test
    fun theCancelButtonDropsTheChangeAndShowsTheCurrentStrategy() {
        val goals = FakeGoals(pendingCutting())
        val vm = viewModel(goals = goals)

        vm.cancelScheduledChange()

        assertEquals(1, goals.cancels)
        assertEquals(FitnessGoal.MAINTAINING, vm.form.value.selectedGoal)
        assertEquals("2000", vm.form.value.text(PreferenceField.MAINTENANCE))
    }

    // ---------------------------------------------------------------------------------------
    // BUG-027: weight dialogs
    // ---------------------------------------------------------------------------------------

    @Test
    fun theWeightDialogReadsBackItsOwnTextInACommaLocale() {
        Locale.setDefault(Locale.GERMANY)

        assertEquals("72.5", weightPrefillText(72.5, isImperial = false))
        assertEquals(72.5, parseWeightInput(weightPrefillText(72.5, isImperial = false), isImperial = false)!!, 1e-9)
        assertEquals(72.5, parseWeightInput("72,5", isImperial = false)!!, 1e-9)
        assertEquals(72.5, parseWeightInput(weightPrefillText(72.5, isImperial = true), isImperial = true)!!, 0.05)
        assertNull(parseWeightInput("abc", isImperial = false))
    }

    // Saving the dialog unchanged must not round 72.46 kg to 72.5
    @Test
    fun anUntouchedWeightPrefillSavesTheExactStoredWeight() {
        assertEquals(72.46, weightFromInput(weightPrefillText(72.46, isImperial = false), 72.46, isImperial = false)!!, 0.0)
        assertEquals(72.46, weightFromInput(weightPrefillText(72.46, isImperial = true), 72.46, isImperial = true)!!, 0.0)
        assertEquals(73.0, weightFromInput("73", 72.46, isImperial = false)!!, 0.0)
        assertEquals(73.0, weightFromInput("73", null, isImperial = false)!!, 0.0)
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private fun pendingCutting() = Goal(
        fitnessGoal = FitnessGoal.MAINTAINING,
        maintenanceCalories = 2000.0,
        scheduledFitnessGoal = FitnessGoal.CUTTING,
        scheduledMaintenanceCalories = 2200.0,
        scheduledEffectiveDate = LocalDate.now().plusDays(1)
    )

    private fun viewModel(prefs: FakePrefs = FakePrefs(), goals: FakeGoals = FakeGoals()) =
        PreferencesViewModel(prefs, GetGoalsUseCase(goals), UpdateGoalsUseCase(goals), CancelScheduledGoalUseCase(goals))

    private class FakePrefs(
        initial: UserPreferences = UserPreferences(currentWeightKg = 72.5, targetWeightKg = 70.0, heightCm = 172.0)
    ) : PreferencesRepository {
        val flow = MutableStateFlow(initial)
        var saves = 0
        override suspend fun getPreferences() = flow.value
        override fun observePreferences() = flow
        override suspend fun updatePreferences(preferences: UserPreferences) {
            saves++
            flow.value = preferences
        }
    }

    /** Targets save at once; a different strategy waits for tomorrow, as in GoalsRepositoryImpl. */
    private class FakeGoals(
        initial: Goal = Goal(),
        private val failWrites: Boolean = false,
        private val gate: CompletableDeferred<Unit>? = null
    ) : GoalsRepository {
        val flow = MutableStateFlow(initial)
        var cancels = 0
        override suspend fun getGoals() = flow.value
        override fun observeGoals() = flow
        override suspend fun updateGoals(goals: Goal) {
            if (failWrites) throw IOException("No space left on device")
            gate?.await()
            val current = flow.value
            val asksForActive = goals.fitnessGoal == current.fitnessGoal && goals.maintenanceCalories == current.maintenanceCalories
            val asksForPending = goals.fitnessGoal == current.scheduledFitnessGoal &&
                goals.maintenanceCalories == current.scheduledMaintenanceCalories
            val targets = current.copy(
                dailyCalorieGoal = goals.dailyCalorieGoal,
                carbPercentage = goals.carbPercentage,
                proteinPercentage = goals.proteinPercentage,
                fatPercentage = goals.fatPercentage
            )
            flow.value = if (asksForActive || asksForPending) {
                targets
            } else {
                targets.copy(
                    scheduledFitnessGoal = goals.fitnessGoal,
                    scheduledMaintenanceCalories = goals.maintenanceCalories,
                    scheduledEffectiveDate = LocalDate.now().plusDays(1)
                )
            }
        }

        override suspend fun cancelScheduledGoal(): Boolean {
            cancels++
            val pending = flow.value.scheduledFitnessGoal != null
            flow.value = flow.value.copy(scheduledFitnessGoal = null, scheduledMaintenanceCalories = null, scheduledEffectiveDate = null)
            return pending
        }
    }
}

/** The same rules against the real DataStore repositories (BUG-028, BUG-031). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P3PhaseThreeRepositoryTests {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val databases = mutableListOf<UserDatabase>()
    private val today = LocalDate.now()
    private var clockDay = today

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        databases.forEach { it.close() }
        scope.cancel()
        Dispatchers.resetMain()
    }

    private val context get() = RuntimeEnvironment.getApplication()

    private fun goalsRepo(name: String) = GoalsRepositoryImpl(
        context,
        PreferenceDataStoreFactory.create(scope = scope) { File(tempFolder.root, "$name-goals.preferences_pb") }
    ) { clockDay }

    private fun prefsRepo(name: String) = PreferencesRepositoryImpl(
        context,
        PreferenceDataStoreFactory.create(scope = scope) { File(tempFolder.root, "$name-prefs.preferences_pb") }
    )

    private fun maintaining() = Goal(2000.0, 50.0, 25.0, 25.0, FitnessGoal.MAINTAINING, 2000.0)

    // A backup import restores the profile only onto a phone with none saved; the rank notice is not one
    @Test
    fun dismissingTheRankNoticeIsNotASavedProfile() = runBlocking {
        val prefs = prefsRepo("notice")
        assertEquals(0, prefs.observeSeenRankRulesVersion().first())
        assertEquals(Long.MIN_VALUE, prefs.observeSeenRankSeason().first())

        prefs.setSeenRankRulesVersion(2)
        prefs.setSeenRankSeason(20_000L)

        assertEquals(2, prefs.observeSeenRankRulesVersion().first())
        assertEquals(20_000L, prefs.observeSeenRankSeason().first())
        assertFalse(prefs.hasSavedPreferences())
        prefs.updatePreferences(UserPreferences(firstName = "Sam"))
        assertTrue(prefs.hasSavedPreferences())
    }

    @Test
    fun savingNoTargetWeightRemovesTheStoredOne() = runBlocking {
        val prefs = prefsRepo("a")
        prefs.updatePreferences(UserPreferences(targetWeightKg = 65.0, heightCm = 172.0))
        prefs.updatePreferences(prefs.getPreferences().copy(targetWeightKg = null))

        assertNull(prefs.getPreferences().targetWeightKg)
        assertEquals(172.0, prefs.getPreferences().heightCm!!, 0.0)
    }

    // Saving null now clears a value, so a restore must not pass on a backup's missing height
    @Test
    fun restoringABackupWithoutAHeightKeepsThePhonesHeight() = runBlocking {
        val prefs = prefsRepo("b")
        prefs.updatePreferences(UserPreferences(heightCm = 172.0, currentWeightKg = 70.0))
        val db = Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
            .also { databases += it }
        val portability = PortabilityRepositoryImpl(db, goalsRepo("b"), prefs)
        val backup = MacroBaseBackupData(
            manifest = BackupManifestDto(exportedAt = "2026-10-10T00:00:00Z", timeZone = "UTC", counts = BackupRecordCountsDto()),
            preferences = UserPreferencesBackupDto("Asha", "Rao", "UTC", "METRIC", null, 68.0, null, 3000.0)
        )

        assertTrue(portability.importUserData(backup, ImportMode.OVERWRITE).isSuccess)

        val restored = prefs.getPreferences()
        assertEquals(172.0, restored.heightCm!!, 0.0)
        assertEquals(68.0, restored.currentWeightKg!!, 0.0)
        assertEquals("Asha", restored.firstName)
    }

    @Test
    fun cancellingDropsThePendingChangeAndItsHistoryRecord() = runBlocking {
        val repo = goalsRepo("c")
        repo.updateGoals(maintaining())
        repo.updateGoals(maintaining().copy(fitnessGoal = FitnessGoal.CUTTING, maintenanceCalories = 2200.0))
        assertEquals(FitnessGoal.CUTTING, repo.observeGoals().first().scheduledFitnessGoal)

        assertTrue(repo.cancelScheduledGoal())

        assertNull(repo.observeGoals().first().scheduledFitnessGoal)
        assertFalse(repo.cancelScheduledGoal())
        val tomorrow = today.plusDays(1).toString()
        assertTrue(repo.exportGoalBackup().transitions.orEmpty().none { it.effectiveDate == tomorrow })
        clockDay = today.plusDays(1)
        assertEquals(FitnessGoal.MAINTAINING, repo.getGoals().fitnessGoal)
        assertEquals(2000.0, repo.getGoals().maintenanceCalories, 0.0)
    }

    @Test
    fun aChangeOfMindKeepsOnlyTheLastChoice() = runBlocking {
        val repo = goalsRepo("d")
        repo.updateGoals(maintaining())
        repo.updateGoals(maintaining().copy(fitnessGoal = FitnessGoal.CUTTING))
        repo.updateGoals(maintaining().copy(fitnessGoal = FitnessGoal.BULKING))

        assertEquals(FitnessGoal.BULKING, repo.observeGoals().first().scheduledFitnessGoal)
        val tomorrow = today.plusDays(1).toString()
        assertEquals(listOf("BULKING"), repo.exportGoalBackup().transitions.orEmpty().filter { it.effectiveDate == tomorrow }.map { it.fitnessGoal })
    }

    // Saving the pending strategy again (the form now shows it) must not move its date
    @Test
    fun askingForThePendingStrategyAgainKeepsItsDate() = runBlocking {
        val repo = goalsRepo("e")
        val later = today.plusDays(10)
        repo.restoreGoalBackup(
            GoalBackupDto(
                dailyCalorieGoal = 2000.0, carbPercentage = 50.0, proteinPercentage = 25.0, fatPercentage = 25.0,
                fitnessGoal = "CUTTING", maintenanceCalories = 2200.0,
                scheduledFitnessGoal = "BULKING", scheduledMaintenanceCalories = 2500.0, scheduledEffectiveDate = later.toString()
            )
        )

        repo.updateGoals(Goal(2100.0, 40.0, 35.0, 25.0, FitnessGoal.BULKING, 2500.0))

        val goals = repo.observeGoals().first()
        assertEquals(later, goals.scheduledEffectiveDate)
        assertEquals(2100.0, goals.dailyCalorieGoal, 0.0)
    }

    // The whole path: the form, the use cases and the real repository
    @Test
    fun revertingInTheFormKeepsTheCurrentStrategyTomorrow() = runBlocking {
        val goals = goalsRepo("f")
        goals.updateGoals(maintaining())
        goals.updateGoals(maintaining().copy(fitnessGoal = FitnessGoal.CUTTING))
        val vm = PreferencesViewModel(prefsRepo("f"), GetGoalsUseCase(goals), UpdateGoalsUseCase(goals), CancelScheduledGoalUseCase(goals))
        withTimeout(5_000) { vm.form.first { it.isLoaded } }
        assertEquals(FitnessGoal.CUTTING, vm.form.value.selectedGoal)

        vm.onStrategySelected(FitnessGoal.MAINTAINING)
        vm.save()
        val saved = withTimeout(5_000) { vm.form.first { it.showSavedMessage || it.saveError != null } }
        assertNull(saved.saveError)

        assertNull(goals.observeGoals().first().scheduledFitnessGoal)
        clockDay = today.plusDays(1)
        assertEquals(FitnessGoal.MAINTAINING, goals.getGoals().fitnessGoal)
    }
}
