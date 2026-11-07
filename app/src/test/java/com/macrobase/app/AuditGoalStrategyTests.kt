package com.macrobase.app

import com.macrobase.app.domain.model.FitnessGoal
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.UserPreferences
import com.macrobase.app.domain.repository.GoalsRepository
import com.macrobase.app.domain.repository.PreferencesRepository
import com.macrobase.app.domain.usecase.CancelScheduledGoalUseCase
import com.macrobase.app.domain.usecase.GetGoalsUseCase
import com.macrobase.app.domain.usecase.UpdateGoalsUseCase
import com.macrobase.app.feature.preferences.PreferenceField
import com.macrobase.app.feature.preferences.PreferencesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * BUG-007: a weight edit records a weight. It never replaces a strategy the user is on, because a
 * wrong strategy goes into the goal history and costs rank points that cannot be repaired.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GoalStrategyStabilityTests {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val cutting = Goal(dailyCalorieGoal = 2000.0, fitnessGoal = FitnessGoal.CUTTING, maintenanceCalories = 2500.0)

    private class Prefs(initial: UserPreferences) : PreferencesRepository {
        val flow = MutableStateFlow(initial)
        override suspend fun getPreferences() = flow.value
        override fun observePreferences() = flow
        override suspend fun updatePreferences(preferences: UserPreferences) {
            flow.value = preferences
        }
    }

    /** Targets save at once; a different strategy waits for tomorrow, as in GoalsRepositoryImpl. */
    private class Goals(initial: Goal, private var saved: Boolean = true) : GoalsRepository {
        val flow = MutableStateFlow(initial)
        override suspend fun getGoals() = flow.value
        override fun observeGoals() = flow
        override suspend fun hasSavedGoals() = saved
        override suspend fun updateGoals(goals: Goal) {
            saved = true
            val current = flow.value
            val sameStrategy = goals.fitnessGoal == current.fitnessGoal && goals.maintenanceCalories == current.maintenanceCalories
            val targets = current.copy(dailyCalorieGoal = goals.dailyCalorieGoal)
            flow.value = if (sameStrategy) {
                targets
            } else {
                targets.copy(
                    scheduledFitnessGoal = goals.fitnessGoal,
                    scheduledMaintenanceCalories = goals.maintenanceCalories,
                    scheduledEffectiveDate = LocalDate.now().plusDays(1)
                )
            }
        }
    }

    private fun viewModel(prefs: Prefs, goals: Goals) =
        PreferencesViewModel(prefs, GetGoalsUseCase(goals), UpdateGoalsUseCase(goals), CancelScheduledGoalUseCase(goals))

    @Test
    fun aWeightEditWithNoTargetKeepsTheStrategyInForce() {
        val prefs = Prefs(UserPreferences(currentWeightKg = 80.0, targetWeightKg = null))
        val goals = Goals(cutting)
        val vm = viewModel(prefs, goals)

        vm.onFieldChanged(PreferenceField.WEIGHT, "79")
        assertEquals(FitnessGoal.CUTTING, vm.form.value.selectedGoal)
        vm.save()

        assertEquals(FitnessGoal.CUTTING, goals.flow.value.fitnessGoal)
        assertNull(goals.flow.value.scheduledFitnessGoal)
        assertEquals(79.0, prefs.flow.value.currentWeightKg!!, 0.0)
    }

    @Test
    fun clearingTheTargetKeepsTheStrategyInForce() {
        val prefs = Prefs(UserPreferences(currentWeightKg = 80.0, targetWeightKg = 70.0))
        val goals = Goals(cutting)
        val vm = viewModel(prefs, goals)

        vm.onFieldChanged(PreferenceField.TARGET_WEIGHT, "")
        vm.save()

        assertNull(goals.flow.value.scheduledFitnessGoal)
        assertNull(prefs.flow.value.targetWeightKg)
    }

    @Test
    fun aWeighInBelowTheTargetDoesNotTurnACutIntoABulk() {
        val prefs = Prefs(UserPreferences(currentWeightKg = 72.0, targetWeightKg = 70.0))
        val goals = Goals(cutting)
        val vm = viewModel(prefs, goals)

        vm.onFieldChanged(PreferenceField.WEIGHT, "69")
        assertEquals(FitnessGoal.CUTTING, vm.form.value.selectedGoal)
        vm.save()

        assertEquals(FitnessGoal.CUTTING, goals.flow.value.fitnessGoal)
        assertNull(goals.flow.value.scheduledFitnessGoal)
        assertEquals(69.0, prefs.flow.value.currentWeightKg!!, 0.0)
    }

    @Test
    fun aNewTargetAloneDoesNotChangeTheStrategy() {
        val prefs = Prefs(UserPreferences(currentWeightKg = 80.0, targetWeightKg = 70.0))
        val goals = Goals(cutting)
        val vm = viewModel(prefs, goals)

        vm.onFieldChanged(PreferenceField.TARGET_WEIGHT, "90")
        vm.save()

        assertNull(goals.flow.value.scheduledFitnessGoal)
        assertEquals(90.0, prefs.flow.value.targetWeightKg!!, 0.0)
    }

    // The contradiction notice offers this; the user's tap is the choice
    @Test
    fun autoMatchIsStillTheWayToFollowTheWeights() {
        val prefs = Prefs(UserPreferences(currentWeightKg = 72.0, targetWeightKg = 70.0))
        val goals = Goals(cutting)
        val vm = viewModel(prefs, goals)

        vm.onFieldChanged(PreferenceField.WEIGHT, "69")
        vm.onAutoMatchStrategy()
        assertEquals(FitnessGoal.BULKING, vm.form.value.selectedGoal)
        vm.save()

        assertEquals(FitnessGoal.BULKING, goals.flow.value.scheduledFitnessGoal)
    }

    @Test
    fun blankingAWeightAfterAutoMatchKeepsTheMatchedStrategy() {
        val prefs = Prefs(UserPreferences(currentWeightKg = 72.0, targetWeightKg = 70.0))
        val vm = viewModel(prefs, Goals(cutting))

        vm.onFieldChanged(PreferenceField.WEIGHT, "69")
        vm.onAutoMatchStrategy()
        vm.onFieldChanged(PreferenceField.TARGET_WEIGHT, "")

        assertEquals(FitnessGoal.BULKING, vm.form.value.selectedGoal)
    }

    // Nobody chose anything yet, so the weights are the best guess
    @Test
    fun aPhoneThatNeverSavedGoalsStillFollowsTheWeights() {
        val prefs = Prefs(UserPreferences())
        val goals = Goals(Goal(), saved = false)
        val vm = viewModel(prefs, goals)

        vm.onFieldChanged(PreferenceField.WEIGHT, "80")
        assertEquals(FitnessGoal.MAINTAINING, vm.form.value.selectedGoal)
        vm.onFieldChanged(PreferenceField.TARGET_WEIGHT, "70")
        assertEquals(FitnessGoal.CUTTING, vm.form.value.selectedGoal)
        vm.save()

        assertEquals(FitnessGoal.CUTTING, goals.flow.value.scheduledFitnessGoal)
    }

    @Test
    fun onceGoalsAreSavedWeightEditsStopFollowing() {
        val prefs = Prefs(UserPreferences())
        val goals = Goals(Goal(), saved = false)
        val vm = viewModel(prefs, goals)
        vm.onFieldChanged(PreferenceField.WEIGHT, "80")
        vm.onFieldChanged(PreferenceField.TARGET_WEIGHT, "70")
        vm.save()

        vm.onFieldChanged(PreferenceField.TARGET_WEIGHT, "90")

        assertEquals(FitnessGoal.CUTTING, vm.form.value.selectedGoal)
    }
}
