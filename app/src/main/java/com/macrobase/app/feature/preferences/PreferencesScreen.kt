package com.macrobase.app.feature.preferences

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.ui.platform.LocalContext
import com.macrobase.app.feature.widget.WidgetPinHelper
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macrobase.app.core.config.RankScoringConfig
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppShapes
import com.macrobase.app.core.designsystem.AppSpacing
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.components.PrimaryButton
import com.macrobase.app.domain.model.FitnessGoal
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.GoalStrategyHelper
import com.macrobase.app.domain.model.UnitConversions
import com.macrobase.app.domain.model.UnitSystem
import com.macrobase.app.domain.model.UserPreferences
import com.macrobase.app.domain.repository.PreferencesRepository
import com.macrobase.app.domain.usecase.GetGoalsUseCase
import com.macrobase.app.domain.usecase.UpdateGoalsUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import java.util.Locale
import com.macrobase.app.core.util.briefErrorText
import com.macrobase.app.core.util.formatDecimalInput
import com.macrobase.app.core.util.hasThousandsSeparator
import com.macrobase.app.core.util.parseDecimalInput
import com.macrobase.app.domain.usecase.CancelScheduledGoalUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import java.math.BigDecimal
import java.math.RoundingMode

/** A numeric field of the preferences form. */
enum class PreferenceField { HEIGHT, WEIGHT, TARGET_WEIGHT, WATER, MAINTENANCE, CALORIES, CARBS, PROTEIN, FAT }

/** A choice on the form that is not a number. */
enum class ProfileField { FIRST_NAME, LAST_NAME, TIME_ZONE, UNIT_SYSTEM, STRATEGY }

/** A number exactly as the user typed it, and the unit system it was typed in. */
data class TypedNumber(val text: String, val unit: UnitSystem)

/**
 * The preferences form. Fields the user edited ([typed], [profileEdited]) keep exactly what was
 * typed (BUG-028/029). Every other field shows the stored value and follows it when it changes,
 * for example after a backup restore.
 */
data class PreferencesFormState(
    val isLoaded: Boolean = false,
    val firstName: String = "",
    val lastName: String = "",
    val timeZone: String = "",
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    /** What each numeric field shows. */
    val text: Map<PreferenceField, String> = emptyMap(),
    val typed: Map<PreferenceField, TypedNumber> = emptyMap(),
    val profileEdited: Set<ProfileField> = emptySet(),
    val selectedGoal: FitnessGoal = FitnessGoal.MAINTAINING,
    /** A strategy was picked by hand, so weight edits no longer change it. */
    val isManuallyOverridden: Boolean = false,
    /** The strategy follows the typed weights (after a weight edit, or "Auto-match"). */
    val followsWeights: Boolean = false,
    val isSaving: Boolean = false,
    val saveError: String? = null,
    val showSavedMessage: Boolean = false,
    /** Why "Cancel this change" did nothing, shown by the strategy card. */
    val cancelMessage: String? = null
) {
    fun text(field: PreferenceField): String = text[field].orEmpty()
}

/** One field's value in metric units (null when blank), or why it cannot be saved. */
data class FieldCheck(val value: Double?, val error: String?)

/** What the form would save. */
class PreferencesCheck(private val fields: Map<PreferenceField, FieldCheck>, val goal: Goal) {
    fun value(field: PreferenceField): Double? = fields[field]?.value
    fun error(field: PreferenceField): String? = fields[field]?.error
    val isMacroSumValid: Boolean get() = goal.isValidPercentageSum
    val isValid: Boolean get() = isMacroSumValid && fields.values.all { it.error == null }
}

class PreferencesViewModel(
    private val preferencesRepository: PreferencesRepository,
    private val getGoalsUseCase: GetGoalsUseCase,
    private val updateGoalsUseCase: UpdateGoalsUseCase,
    private val cancelScheduledGoalUseCase: CancelScheduledGoalUseCase
) : ViewModel() {

    /** Live goals, for the pending-change banner. */
    val goals: StateFlow<Goal> = getGoalsUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Goal())

    private val _form = MutableStateFlow(PreferencesFormState())
    val form: StateFlow<PreferencesFormState> = _form.asStateFlow()

    // The stored values the untouched fields show
    private var storedPrefs = UserPreferences()
    private var storedGoals = Goal()

    // The active maintenance the form put in when the user went back to the active strategy
    private var filledMaintenance: TypedNumber? = null

    init {
        viewModelScope.launch {
            combine(preferencesRepository.observePreferences(), getGoalsUseCase()) { prefs, goals -> prefs to goals }
                .collect { (prefs, goals) -> refresh(prefs, goals) }
        }
    }

    /**
     * The stored values changed: first load, a save, a restore or another screen. Untouched fields
     * follow them; edited ones keep what the user typed (BUG-029).
     */
    private fun refresh(prefs: UserPreferences, goals: Goal) {
        storedPrefs = prefs
        storedGoals = goals
        _form.update { form ->
            val edited = form.profileEdited
            render(
                form.copy(
                    isLoaded = true,
                    firstName = if (ProfileField.FIRST_NAME in edited) form.firstName else prefs.firstName,
                    lastName = if (ProfileField.LAST_NAME in edited) form.lastName else prefs.lastName,
                    timeZone = if (ProfileField.TIME_ZONE in edited) form.timeZone else prefs.timeZone,
                    unitSystem = if (ProfileField.UNIT_SYSTEM in edited) form.unitSystem else prefs.unitSystem,
                    // A change waiting for tomorrow is the user's own choice, so weight edits keep it
                    isManuallyOverridden = if (form.isLoaded) form.isManuallyOverridden else goals.scheduledFitnessGoal != null
                )
            )
        }
    }

    /** Recomputes what the numeric fields and the strategy show. */
    private fun render(form: PreferencesFormState): PreferencesFormState {
        val unit = form.unitSystem
        val withText = form.copy(
            text = PreferenceField.entries.associateWith { field ->
                form.typed[field]?.let { shownTyped(field, it, unit) } ?: storedText(field, unit)
            }
        )
        val selected = when {
            autoMatches(form, storedGoals) -> autoDetected(withText)
            ProfileField.STRATEGY in form.profileEdited -> form.selectedGoal
            // A change waiting for tomorrow is what the user last chose, so the form shows it (BUG-031)
            else -> storedGoals.scheduledFitnessGoal ?: storedGoals.fitnessGoal
        }
        return withText.copy(selectedGoal = selected)
    }

    /**
     * Whether the strategy follows the typed weights. A weight edit alone never replaces a change
     * waiting for tomorrow; only a strategy the user chose does (BUG-031).
     */
    private fun autoMatches(form: PreferencesFormState, goals: Goal): Boolean =
        form.followsWeights && (ProfileField.STRATEGY in form.profileEdited || goals.scheduledFitnessGoal == null)

    private fun autoDetected(form: PreferencesFormState): FitnessGoal {
        val check = check(form)
        return GoalStrategyHelper.autoDetectStrategy(check.value(PreferenceField.WEIGHT), check.value(PreferenceField.TARGET_WEIGHT))
    }

    private fun storedValue(field: PreferenceField): Double? = when (field) {
        PreferenceField.HEIGHT -> storedPrefs.heightCm
        PreferenceField.WEIGHT -> storedPrefs.currentWeightKg
        PreferenceField.TARGET_WEIGHT -> storedPrefs.targetWeightKg
        PreferenceField.WATER -> storedPrefs.dailyWaterGoalMl
        PreferenceField.MAINTENANCE -> storedGoals.scheduledMaintenanceCalories ?: storedGoals.maintenanceCalories
        PreferenceField.CALORIES -> storedGoals.dailyCalorieGoal
        PreferenceField.CARBS -> storedGoals.carbPercentage
        PreferenceField.PROTEIN -> storedGoals.proteinPercentage
        PreferenceField.FAT -> storedGoals.fatPercentage
    }

    private fun storedText(field: PreferenceField, unit: UnitSystem): String =
        storedValue(field)?.let { formatForField(field, toDisplay(field, it, unit), unit) }.orEmpty()

    /**
     * A typed number shown in [unit]. In the unit it was typed in it is the exact text, so switching
     * back and forth never changes it; in the other unit it is converted from that text.
     */
    private fun shownTyped(field: PreferenceField, typed: TypedNumber, unit: UnitSystem): String {
        if (typed.unit == unit || field !in UNIT_FIELDS) return typed.text
        val metric = parseDecimalInput(typed.text)?.let { fromDisplay(field, it, typed.unit) } ?: return typed.text
        return formatForField(field, toDisplay(field, metric, unit), unit)
    }

    fun onFirstNameChanged(text: String) =
        updateForm { it.copy(firstName = text, profileEdited = it.profileEdited + ProfileField.FIRST_NAME) }

    fun onLastNameChanged(text: String) =
        updateForm { it.copy(lastName = text, profileEdited = it.profileEdited + ProfileField.LAST_NAME) }

    fun onTimeZoneChanged(text: String) =
        updateForm { it.copy(timeZone = text, profileEdited = it.profileEdited + ProfileField.TIME_ZONE) }

    fun onFieldChanged(field: PreferenceField, text: String) = updateForm { form ->
        val weightEdit = field == PreferenceField.WEIGHT || field == PreferenceField.TARGET_WEIGHT
        render(
            form.copy(
                typed = form.typed + (field to TypedNumber(text, form.unitSystem)),
                followsWeights = form.followsWeights || (weightEdit && !form.isManuallyOverridden)
            )
        )
    }

    /** Edited numbers keep their typed value; the rest show their stored value in the new unit (BUG-029). */
    fun onUnitSystemChanged(unit: UnitSystem) = updateForm { form ->
        if (form.unitSystem == unit) form
        else render(form.copy(unitSystem = unit, profileEdited = form.profileEdited + ProfileField.UNIT_SYSTEM))
    }

    fun onStrategySelected(goal: FitnessGoal) = updateForm { form ->
        render(
            form.copy(
                selectedGoal = goal,
                typed = withMaintenanceFor(goal, form),
                isManuallyOverridden = true,
                followsWeights = false,
                profileEdited = form.profileEdited + ProfileField.STRATEGY
            )
        )
    }

    /** "Auto-match" is a choice too, so a save after it may drop a pending change (BUG-031). */
    fun onAutoMatchStrategy() = updateForm { form ->
        val matched = render(
            form.copy(isManuallyOverridden = false, followsWeights = true, profileEdited = form.profileEdited + ProfileField.STRATEGY)
        )
        render(matched.copy(typed = withMaintenanceFor(matched.selectedGoal, matched)))
    }

    /**
     * While a change waits for tomorrow the maintenance field shows the pending maintenance. Going
     * back to the strategy in force means going back to its maintenance too; leaving it again
     * undoes that, unless the user typed over it.
     */
    private fun withMaintenanceFor(goal: FitnessGoal, form: PreferencesFormState): Map<PreferenceField, TypedNumber> {
        val typed = form.typed[PreferenceField.MAINTENANCE]
        val pending = storedGoals.scheduledFitnessGoal != null
        return when {
            pending && goal == storedGoals.fitnessGoal && typed == null -> {
                val filled = TypedNumber(formatPlain(storedGoals.maintenanceCalories), form.unitSystem)
                filledMaintenance = filled
                form.typed + (PreferenceField.MAINTENANCE to filled)
            }
            typed != null && typed == filledMaintenance && goal != storedGoals.fitnessGoal -> {
                filledMaintenance = null
                form.typed - PreferenceField.MAINTENANCE
            }
            else -> form.typed
        }
    }

    private fun updateForm(change: (PreferencesFormState) -> PreferencesFormState) {
        _form.update { change(it).copy(showSavedMessage = false, saveError = null, cancelMessage = null) }
    }

    /** Every field's value in metric units, or its error, for [form]. */
    fun check(form: PreferencesFormState = _form.value): PreferencesCheck {
        val unit = form.unitSystem
        val fields = PreferenceField.entries.associateWith { field ->
            val typed = form.typed[field]
            if (typed != null) checkTyped(field, typed, unit) else checkStored(field, unit)
        }
        val goal = Goal(
            dailyCalorieGoal = fields.getValue(PreferenceField.CALORIES).value ?: 0.0,
            carbPercentage = fields.getValue(PreferenceField.CARBS).value ?: 0.0,
            proteinPercentage = fields.getValue(PreferenceField.PROTEIN).value ?: 0.0,
            fatPercentage = fields.getValue(PreferenceField.FAT).value ?: 0.0,
            fitnessGoal = form.selectedGoal,
            maintenanceCalories = fields.getValue(PreferenceField.MAINTENANCE).value ?: 0.0
        )
        return PreferencesCheck(fields, goal)
    }

    private fun checkStored(field: PreferenceField, unit: UnitSystem): FieldCheck {
        val value = storedValue(field)
        return FieldCheck(value, if (value == null) blankError(field, unit) else rangeError(field, value, unit))
    }

    /** Parsed in the unit it was typed in, so a unit switch never changes the value. */
    private fun checkTyped(field: PreferenceField, typed: TypedNumber, unit: UnitSystem): FieldCheck {
        val text = typed.text.trim()
        if (text.isEmpty()) return FieldCheck(null, blankError(field, unit))
        // "2,000" or "2.000" is a thousands separator, not 2: refuse it rather than save 2 kcal.
        // Fluid ounces can have decimals, so only millilitres are checked for water
        val wholeNumbers = field in SEPARATOR_FIELDS && !(field == PreferenceField.WATER && typed.unit == UnitSystem.IMPERIAL)
        if (wholeNumbers && hasThousandsSeparator(text)) {
            return FieldCheck(null, "Leave out the thousands separator")
        }
        val number = parseDecimalInput(text) ?: return FieldCheck(null, "Enter a number")
        val metric = fromDisplay(field, number, typed.unit)
        return FieldCheck(metric, rangeError(field, metric, unit))
    }

    /** Height and the two weights may be left blank; everything else is required. */
    private fun blankError(field: PreferenceField, unit: UnitSystem): String? = when (field) {
        PreferenceField.HEIGHT, PreferenceField.WEIGHT, PreferenceField.TARGET_WEIGHT -> null
        else -> rangeMessage(field, unit) ?: "Required"
    }

    private fun rangeError(field: PreferenceField, metric: Double, unit: UnitSystem): String? {
        val range = RANGES[field]
        return when {
            range != null -> if (metric < range.start || metric > range.endInclusive) rangeMessage(field, unit) else null
            field == PreferenceField.MAINTENANCE || field == PreferenceField.CALORIES ->
                if (metric > 0.0) null else "Enter an amount above 0"
            else -> if (metric >= 0.0) null else "Cannot be negative"
        }
    }

    private fun rangeMessage(field: PreferenceField, unit: UnitSystem): String? {
        val range = RANGES[field] ?: return null
        val low = formatLimit(toDisplay(field, range.start, unit))
        val high = formatLimit(toDisplay(field, range.endInclusive, unit))
        return "Enter $low to $high ${unitLabel(field, unit)}"
    }

    fun save() {
        val form = _form.value
        if (form.isSaving || !form.isLoaded) return
        val check = check(form)
        if (!check.isValid) {
            _form.update { it.copy(saveError = "Fix the highlighted fields first.") }
            return
        }
        _form.update { it.copy(isSaving = true, saveError = null, showSavedMessage = false) }
        viewModelScope.launch {
            try {
                // Fields the user did not touch keep the latest stored value, so nothing drifts
                // through a unit conversion and nothing changed elsewhere is overwritten (BUG-050)
                val currentPrefs = preferencesRepository.getPreferences()
                val currentGoals = getGoalsUseCase().first()
                fun value(field: PreferenceField, latest: Double?): Double? =
                    if (field in form.typed) check.value(field) else latest
                fun edited(field: ProfileField) = field in form.profileEdited
                // A strategy the user did not choose stays whatever is stored now
                val choseStrategy = edited(ProfileField.STRATEGY) || autoMatches(form, currentGoals)
                val goal = Goal(
                    dailyCalorieGoal = value(PreferenceField.CALORIES, currentGoals.dailyCalorieGoal) ?: currentGoals.dailyCalorieGoal,
                    carbPercentage = value(PreferenceField.CARBS, currentGoals.carbPercentage) ?: currentGoals.carbPercentage,
                    proteinPercentage = value(PreferenceField.PROTEIN, currentGoals.proteinPercentage) ?: currentGoals.proteinPercentage,
                    fatPercentage = value(PreferenceField.FAT, currentGoals.fatPercentage) ?: currentGoals.fatPercentage,
                    fitnessGoal = if (choseStrategy) form.selectedGoal else currentGoals.scheduledFitnessGoal ?: currentGoals.fitnessGoal,
                    maintenanceCalories = value(
                        PreferenceField.MAINTENANCE,
                        currentGoals.scheduledMaintenanceCalories ?: currentGoals.maintenanceCalories
                    ) ?: currentGoals.maintenanceCalories
                )
                // Choosing the strategy in force again, with its maintenance, while a change waits
                // for tomorrow means the user changed their mind: drop the change (BUG-031). Only a
                // strategy the user picked does this, never a weight edit
                if (edited(ProfileField.STRATEGY) && currentGoals.scheduledFitnessGoal != null &&
                    goal.fitnessGoal == currentGoals.fitnessGoal && goal.maintenanceCalories == currentGoals.maintenanceCalories
                ) {
                    cancelScheduledGoalUseCase()
                }
                // Goals first: a goal the use case rejects leaves the profile unsaved too (BUG-026)
                updateGoalsUseCase(goal).getOrThrow()
                val prefs = currentPrefs.copy(
                    firstName = if (edited(ProfileField.FIRST_NAME)) form.firstName.trim() else currentPrefs.firstName,
                    lastName = if (edited(ProfileField.LAST_NAME)) form.lastName.trim() else currentPrefs.lastName,
                    timeZone = if (edited(ProfileField.TIME_ZONE)) form.timeZone.trim() else currentPrefs.timeZone,
                    unitSystem = if (edited(ProfileField.UNIT_SYSTEM)) form.unitSystem else currentPrefs.unitSystem,
                    heightCm = value(PreferenceField.HEIGHT, currentPrefs.heightCm),
                    currentWeightKg = value(PreferenceField.WEIGHT, currentPrefs.currentWeightKg),
                    targetWeightKg = value(PreferenceField.TARGET_WEIGHT, currentPrefs.targetWeightKg),
                    dailyWaterGoalMl = value(PreferenceField.WATER, currentPrefs.dailyWaterGoalMl) ?: currentPrefs.dailyWaterGoalMl
                )
                preferencesRepository.updatePreferences(prefs)
                storedPrefs = prefs
                storedGoals = getGoalsUseCase().first()
                _form.update { now ->
                    // Saved fields now show what is stored; anything typed while saving stays edited
                    val typed = now.typed.filter { (field, typed) -> form.typed[field] != typed }
                    val profileEdited = now.profileEdited.filterTo(mutableSetOf()) { changedSince(it, now, form) }
                    val weightTyped = PreferenceField.WEIGHT in typed || PreferenceField.TARGET_WEIGHT in typed
                    if (PreferenceField.MAINTENANCE !in typed) filledMaintenance = null
                    render(
                        now.copy(
                            typed = typed,
                            profileEdited = profileEdited,
                            // Once saved, the strategy follows what is stored again
                            followsWeights = now.followsWeights && (weightTyped || ProfileField.STRATEGY in profileEdited),
                            isSaving = false,
                            showSavedMessage = true
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _form.update { it.copy(isSaving = false, saveError = briefErrorText(e, "Could not save your changes.")) }
            }
        }
    }

    private fun changedSince(field: ProfileField, now: PreferencesFormState, saved: PreferencesFormState) = when (field) {
        ProfileField.FIRST_NAME -> now.firstName != saved.firstName
        ProfileField.LAST_NAME -> now.lastName != saved.lastName
        ProfileField.TIME_ZONE -> now.timeZone != saved.timeZone
        ProfileField.UNIT_SYSTEM -> now.unitSystem != saved.unitSystem
        ProfileField.STRATEGY -> now.selectedGoal != saved.selectedGoal
    }

    /** Keeps the current strategy by dropping the change scheduled for tomorrow (BUG-031). */
    fun cancelScheduledChange() {
        if (_form.value.isSaving) return
        viewModelScope.launch {
            try {
                val dropped = cancelScheduledGoalUseCase()
                storedGoals = getGoalsUseCase().first()
                _form.update { form ->
                    // The maintenance the form filled in goes; one the user typed stays
                    val filled = form.typed[PreferenceField.MAINTENANCE]?.takeIf { it == filledMaintenance }
                    filledMaintenance = null
                    render(
                        form.copy(
                            typed = if (filled != null) form.typed - PreferenceField.MAINTENANCE else form.typed,
                            profileEdited = form.profileEdited - ProfileField.STRATEGY,
                            isManuallyOverridden = false,
                            followsWeights = false
                        )
                    ).copy(
                        cancelMessage = if (dropped) null else "That change has already started, so there is nothing to cancel.",
                        showSavedMessage = false
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _form.update { it.copy(cancelMessage = briefErrorText(e, "Could not cancel the change.")) }
            }
        }
    }

    companion object {
        /** Fields whose number depends on the unit system. */
        private val UNIT_FIELDS = setOf(
            PreferenceField.HEIGHT, PreferenceField.WEIGHT, PreferenceField.TARGET_WEIGHT, PreferenceField.WATER
        )

        /** Large whole numbers people may type with a thousands separator. */
        private val SEPARATOR_FIELDS = setOf(PreferenceField.MAINTENANCE, PreferenceField.CALORIES, PreferenceField.WATER)

        /** Metric limits (owner decision D5): kg, cm and mL. */
        private val RANGES = mapOf(
            PreferenceField.WEIGHT to 20.0..500.0,
            PreferenceField.TARGET_WEIGHT to 20.0..500.0,
            PreferenceField.HEIGHT to 50.0..250.0,
            PreferenceField.WATER to 250.0..10_000.0
        )

        fun unitLabel(field: PreferenceField, unit: UnitSystem): String {
            val imperial = unit == UnitSystem.IMPERIAL
            return when (field) {
                PreferenceField.WEIGHT, PreferenceField.TARGET_WEIGHT -> if (imperial) "lb" else "kg"
                PreferenceField.HEIGHT -> if (imperial) "in" else "cm"
                PreferenceField.WATER -> if (imperial) "fl oz" else "mL"
                PreferenceField.MAINTENANCE, PreferenceField.CALORIES -> "kcal"
                PreferenceField.CARBS, PreferenceField.PROTEIN, PreferenceField.FAT -> "%"
            }
        }

        /** Up to two decimals without trailing zeros: 30.4, 33.33, 2000. */
        fun formatPlain(value: Double): String =
            BigDecimal(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

        private fun toDisplay(field: PreferenceField, metric: Double, unit: UnitSystem): Double {
            if (unit != UnitSystem.IMPERIAL) return metric
            return when (field) {
                PreferenceField.WEIGHT, PreferenceField.TARGET_WEIGHT -> UnitConversions.kgToLbs(metric)
                PreferenceField.HEIGHT -> UnitConversions.cmToInches(metric)
                PreferenceField.WATER -> UnitConversions.mlToFlOz(metric)
                else -> metric
            }
        }

        private fun fromDisplay(field: PreferenceField, shown: Double, unit: UnitSystem): Double {
            if (unit != UnitSystem.IMPERIAL) return shown
            return when (field) {
                PreferenceField.WEIGHT, PreferenceField.TARGET_WEIGHT -> UnitConversions.lbsToKg(shown)
                PreferenceField.HEIGHT -> UnitConversions.inchesToCm(shown)
                PreferenceField.WATER -> UnitConversions.flOzToMl(shown)
                else -> shown
            }
        }

        /** Weight, height and fluid ounces to 0.1; millilitres, kcal and percentages as stored. */
        private fun formatForField(field: PreferenceField, value: Double, unit: UnitSystem): String = when (field) {
            PreferenceField.WEIGHT, PreferenceField.TARGET_WEIGHT, PreferenceField.HEIGHT -> formatDecimalInput(value, 1)
            PreferenceField.WATER -> if (unit == UnitSystem.IMPERIAL) formatDecimalInput(value, 1) else formatPlain(value)
            else -> formatPlain(value)
        }

        private fun formatLimit(value: Double): String =
            if (abs(value - value.roundToInt()) < 0.05) value.roundToInt().toString() else formatDecimalInput(value, 1)
    }
}

private fun formatKcal(value: Double): String = String.format(Locale.US, "%,d", value.roundToInt())

/** What the rank rewards for [strategy], with the zone in kcal once [maintenance] is known. */
internal fun strategyRankHint(strategy: FitnessGoal, maintenance: Double?): String {
    val (zoneRule, lossRule) = when (strategy) {
        FitnessGoal.CUTTING -> "10\u201325% below maintenance" to "Eating within 5% of maintenance, or above it, loses RR."
        FitnessGoal.BULKING -> "10\u201320% above maintenance" to "Eating within 5% of maintenance, or below it, loses RR."
        FitnessGoal.MAINTAINING -> "within 5% of maintenance" to "Eating more than 10% away from maintenance loses RR."
    }
    if (maintenance == null || maintenance <= 0.0) return "Rank calorie zone: $zoneRule. $lossRule"
    val zone = RankScoringConfig.calorieZone(strategy, maintenance)
    return "Rank calorie zone: ${formatKcal(zone.start)} \u2013 ${formatKcal(zone.endInclusive)} kcal ($zoneRule). $lossRule"
}

/** Credit this close to full counts as full: the zone's edges are shown rounded to whole kcal. */
private const val FULL_CREDIT_TOLERANCE = 0.01

/** A warning when days eaten exactly to [calorieTarget] would not get the rank's full calorie credit. */
internal fun calorieTargetWarning(strategy: FitnessGoal, maintenance: Double?, calorieTarget: Double?): String? {
    if (maintenance == null || maintenance <= 0.0 || calorieTarget == null || calorieTarget <= 0.0) return null
    val credit = RankScoringConfig.calorieCredit(strategy, calorieTarget, maintenance)
    return when {
        credit >= 1.0 - FULL_CREDIT_TOLERANCE -> null
        credit <= 0.0 -> "Your daily calorie target (${formatKcal(calorieTarget)} kcal) is not in this zone: days eaten to it lose RR."
        else -> "Your daily calorie target (${formatKcal(calorieTarget)} kcal) is at the edge of this zone: days eaten to it earn less RR."
    }
}

/**
 * A new strategy or maintenance starts tomorrow, but a new calorie target applies at once, so today
 * is still rated with [todayStrategy] and [todayMaintenance]. A warning when eating [calorieTarget]
 * today would lose RR because of that.
 */
internal fun todayStrategyWarning(
    todayStrategy: FitnessGoal,
    todayMaintenance: Double,
    newStrategy: FitnessGoal,
    newMaintenance: Double?,
    calorieTarget: Double?
): String? {
    if (calorieTarget == null || calorieTarget <= 0.0 || todayMaintenance <= 0.0) return null
    if (newStrategy == todayStrategy && (newMaintenance == null || newMaintenance == todayMaintenance)) return null
    val credit = RankScoringConfig.calorieCredit(todayStrategy, calorieTarget, todayMaintenance)
    if (credit >= 1.0 - FULL_CREDIT_TOLERANCE) return null
    val zone = RankScoringConfig.calorieZone(todayStrategy, todayMaintenance)
    return "Today is still rated as ${todayStrategy.displayName} (zone ${formatKcal(zone.start)} \u2013 " +
        "${formatKcal(zone.endInclusive)} kcal), so eating ${formatKcal(calorieTarget)} kcal today " +
        "${if (credit <= 0.0) "loses" else "earns less"} RR. The change starts tomorrow."
}

@Composable
fun PreferencesScreen(
    viewModel: PreferencesViewModel,
    onNavigateToImportExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val goals by viewModel.goals.collectAsState()
    val form by viewModel.form.collectAsState()
    val check = remember(form) { viewModel.check(form) }

    val unit = form.unitSystem
    val currentKg = check.value(PreferenceField.WEIGHT)
    val targetKg = check.value(PreferenceField.TARGET_WEIGHT)
    val autoDetectedGoal = GoalStrategyHelper.autoDetectStrategy(currentKg, targetKg)
    val contradictionWarning = GoalStrategyHelper.getContradictionWarning(form.selectedGoal, currentKg, targetKg, unit)
    val previewGoal = check.goal

    fun label(name: String, field: PreferenceField) = "$name (${PreferencesViewModel.unitLabel(field, unit)})"

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Background)
            .padding(AppSpacing.lg)
            .verticalScroll(rememberScrollState())
    ) {
        Text(text = "User Profile & Preferences", style = AppTypography.Header1)
        Text(
            text = "Manage your biometrics, lifestyle strategy, calorie targets, and hydration goals.",
            style = AppTypography.Body2,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = AppSpacing.xs)
        )

        Spacer(modifier = Modifier.height(AppSpacing.md))

        // Next-Day Evaluation Schedule Notice Banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(AppColors.SurfaceAlt)
                .border(1.dp, AppColors.Divider, RoundedCornerShape(8.dp))
                .padding(AppSpacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = AppColors.Primary,
                    modifier = Modifier
                        .size(20.dp)
                        .padding(top = 2.dp)
                )
                Spacer(modifier = Modifier.width(AppSpacing.sm))
                Column {
                    Text(
                        text = "Next-Day Evaluation Schedule",
                        style = AppTypography.Body2.copy(fontWeight = FontWeight.Bold),
                        color = AppColors.TextPrimary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Note: Changing your strategy or maintenance calories takes effect starting tomorrow. Today's rating will be evaluated under your current strategy.",
                        style = AppTypography.Caption,
                        color = AppColors.TextSecondary
                    )
                    if (goals.scheduledFitnessGoal != null && (goals.scheduledFitnessGoal != goals.fitnessGoal || goals.scheduledMaintenanceCalories != goals.maintenanceCalories)) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Pending: Switching to ${goals.scheduledFitnessGoal?.displayName} (${goals.scheduledMaintenanceCalories?.roundToInt()} kcal) starting tomorrow.",
                            style = AppTypography.Caption.copy(fontWeight = FontWeight.SemiBold, color = AppColors.Primary)
                        )
                        // A change of mind before tomorrow (BUG-031)
                        TextButton(
                            onClick = { viewModel.cancelScheduledChange() },
                            enabled = !form.isSaving,
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(
                                text = "Cancel this change (stay on ${goals.fitnessGoal.displayName})",
                                style = AppTypography.Caption.copy(fontWeight = FontWeight.Bold, color = AppColors.Primary)
                            )
                        }
                    }
                    form.cancelMessage?.let { message ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = message, style = AppTypography.Caption, color = AppColors.ProgressOver)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))

        // 1. Personal Information Card
        Card(
            shape = AppShapes.Card,
            colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(AppSpacing.md)) {
                Text(text = "PERSONAL INFORMATION", style = AppTypography.Caption, color = AppColors.TextSecondary)
                Spacer(modifier = Modifier.height(AppSpacing.sm))

                OutlinedTextField(
                    value = form.firstName,
                    onValueChange = viewModel::onFirstNameChanged,
                    label = { Text("First Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors(AppColors.Primary)
                )
                Spacer(modifier = Modifier.height(AppSpacing.sm))

                OutlinedTextField(
                    value = form.lastName,
                    onValueChange = viewModel::onLastNameChanged,
                    label = { Text("Last Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors(AppColors.Primary)
                )
                Spacer(modifier = Modifier.height(AppSpacing.sm))

                OutlinedTextField(
                    value = form.timeZone,
                    onValueChange = viewModel::onTimeZoneChanged,
                    label = { Text("Time Zone (e.g. UTC, America/New_York)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors(AppColors.Primary)
                )
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))

        // 2. Measurement System Selection Card
        Card(
            shape = AppShapes.Card,
            colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(AppSpacing.md)) {
                Text(text = "MEASUREMENT SYSTEM", style = AppTypography.Caption, color = AppColors.TextSecondary)
                Spacer(modifier = Modifier.height(AppSpacing.sm))

                listOf(
                    UnitSystem.METRIC to "Metric (kg, cm, mL)",
                    UnitSystem.IMPERIAL to "Imperial (lb, in, fl oz)"
                ).forEach { (system, title) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.onUnitSystemChanged(system) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = unit == system,
                            onClick = { viewModel.onUnitSystemChanged(system) },
                            colors = RadioButtonDefaults.colors(selectedColor = AppColors.Primary)
                        )
                        Text(text = title, style = AppTypography.Body1)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))

        // 3. Body Composition & Strategy Card
        Card(
            shape = AppShapes.Card,
            colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(AppSpacing.md)) {
                Text(text = "BODY COMPOSITION & STRATEGY", style = AppTypography.Caption, color = AppColors.TextSecondary)
                Spacer(modifier = Modifier.height(AppSpacing.sm))

                PreferenceNumberField(
                    field = PreferenceField.HEIGHT,
                    label = label("Height", PreferenceField.HEIGHT),
                    form = form,
                    check = check,
                    onChange = viewModel::onFieldChanged,
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(AppSpacing.sm))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
                ) {
                    PreferenceNumberField(
                        field = PreferenceField.WEIGHT,
                        label = label("Current Weight", PreferenceField.WEIGHT),
                        form = form,
                        check = check,
                        onChange = viewModel::onFieldChanged,
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f)
                    )
                    PreferenceNumberField(
                        field = PreferenceField.TARGET_WEIGHT,
                        label = label("Target Weight", PreferenceField.TARGET_WEIGHT),
                        form = form,
                        check = check,
                        onChange = viewModel::onFieldChanged,
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(AppSpacing.md))

                Text(text = "LIFESTYLE STRATEGY", style = AppTypography.Caption, color = AppColors.TextSecondary)
                Spacer(modifier = Modifier.height(AppSpacing.xs))

                // Strategy Segmented Control / FilterChips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    val strategies = listOf(FitnessGoal.BULKING, FitnessGoal.MAINTAINING, FitnessGoal.CUTTING)
                    strategies.forEach { strategy ->
                        FilterChip(
                            selected = form.selectedGoal == strategy,
                            onClick = { viewModel.onStrategySelected(strategy) },
                            label = { Text(strategy.displayName) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AppColors.Primary,
                                selectedLabelColor = AppColors.Background,
                                containerColor = AppColors.SurfaceAlt,
                                labelColor = AppColors.TextSecondary
                            )
                        )
                    }
                }

                // Dynamic Contradiction Warning Banner
                if (contradictionWarning != null) {
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFFFA000).copy(alpha = 0.15f))
                            .border(1.dp, Color(0xFFFFA000).copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .padding(AppSpacing.sm)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color(0xFFFFA000),
                                    modifier = Modifier
                                        .size(18.dp)
                                        .padding(top = 2.dp)
                                )
                                Spacer(modifier = Modifier.width(AppSpacing.xs))
                                Text(
                                    text = contradictionWarning,
                                    style = AppTypography.Caption,
                                    color = AppColors.TextPrimary
                                )
                            }
                            Spacer(modifier = Modifier.height(AppSpacing.xs))
                            TextButton(
                                onClick = { viewModel.onAutoMatchStrategy() },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(
                                    text = "Auto-match to weights (${autoDetectedGoal.displayName})",
                                    style = AppTypography.Caption.copy(fontWeight = FontWeight.Bold, color = AppColors.Primary)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(AppSpacing.sm))

                // Dynamic Strategy Tip Box: the rank's calorie zone for the maintenance in the form
                val typedMaintenance = check.value(PreferenceField.MAINTENANCE)
                val helperText = strategyRankHint(form.selectedGoal, typedMaintenance)
                val typedCalories = check.value(PreferenceField.CALORIES)
                val targetWarning = listOfNotNull(
                    todayStrategyWarning(goals.fitnessGoal, goals.maintenanceCalories, form.selectedGoal, typedMaintenance, typedCalories),
                    calorieTargetWarning(form.selectedGoal, typedMaintenance, typedCalories)
                ).joinToString("\n").ifEmpty { null }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(AppColors.SurfaceAlt)
                        .padding(AppSpacing.sm)
                ) {
                    Column {
                        Text(
                            text = helperText,
                            style = AppTypography.Caption,
                            color = AppColors.TextSecondary
                        )
                        if (targetWarning != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = targetWarning,
                                style = AppTypography.Caption.copy(fontWeight = FontWeight.SemiBold),
                                color = Color(0xFFFFA000)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))

        // 4. Calorie & Energy Targets Card
        Card(
            shape = AppShapes.Card,
            colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(AppSpacing.md)) {
                Text(text = "CALORIE & ENERGY TARGETS", style = AppTypography.Caption, color = AppColors.TextSecondary)
                Spacer(modifier = Modifier.height(AppSpacing.sm))

                PreferenceNumberField(
                    field = PreferenceField.MAINTENANCE,
                    label = "Maintenance Calories (kcal)",
                    form = form,
                    check = check,
                    onChange = viewModel::onFieldChanged,
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(AppSpacing.sm))

                PreferenceNumberField(
                    field = PreferenceField.CALORIES,
                    label = "Daily Calorie Target (kcal)",
                    form = form,
                    check = check,
                    onChange = viewModel::onFieldChanged,
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))

        // 5. Macronutrient Split Configuration Card
        Card(
            shape = AppShapes.Card,
            colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(AppSpacing.md)) {
                Text(text = "MACRONUTRIENT SPLIT (% OF CALORIES)", style = AppTypography.Caption, color = AppColors.TextSecondary)
                Spacer(modifier = Modifier.height(AppSpacing.sm))

                listOf(
                    Triple(PreferenceField.CARBS, "Carbs", AppColors.MacroCarbs),
                    Triple(PreferenceField.PROTEIN, "Protein", AppColors.MacroProtein),
                    Triple(PreferenceField.FAT, "Fat", AppColors.MacroFat)
                ).forEachIndexed { index, (field, name, color) ->
                    if (index > 0) Spacer(modifier = Modifier.height(AppSpacing.sm))
                    val grams = when (field) {
                        PreferenceField.CARBS -> previewGoal.carbGrams
                        PreferenceField.PROTEIN -> previewGoal.proteinGrams
                        else -> previewGoal.fatGrams
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PreferenceNumberField(
                            field = field,
                            label = "$name (%)",
                            form = form,
                            check = check,
                            onChange = viewModel::onFieldChanged,
                            keyboardType = KeyboardType.Decimal,
                            focusedColor = color,
                            modifier = Modifier.weight(1f)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "$name Target", style = AppTypography.Caption, color = AppColors.TextSecondary)
                            Text(text = "${grams.roundToInt()} g", style = AppTypography.Header2, color = color)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))

        // 100% Macro Validation Banner
        val isValidSum = check.isMacroSumValid
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (isValidSum) AppColors.SurfaceAlt else AppColors.ProgressOver.copy(alpha = 0.15f))
                .padding(AppSpacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isValidSum) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (isValidSum) AppColors.CalorieText else AppColors.ProgressOver,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.size(AppSpacing.sm))
                Column {
                    Text(
                        text = if (isValidSum) "Macro split totals 100%" else "Macro split must total exactly 100%",
                        style = AppTypography.Header3,
                        color = if (isValidSum) AppColors.CalorieText else AppColors.ProgressOver
                    )
                    Text(
                        // Two decimals, as validated: 33.33 three times is 99.99%, not 100% (BUG-026)
                        text = "Current sum: ${PreferencesViewModel.formatPlain(previewGoal.totalPercentageExact)}% " +
                            "(Carbs ${PreferencesViewModel.formatPlain(previewGoal.carbPercentage)}%, " +
                            "Protein ${PreferencesViewModel.formatPlain(previewGoal.proteinPercentage)}%, " +
                            "Fat ${PreferencesViewModel.formatPlain(previewGoal.fatPercentage)}%)",
                        style = AppTypography.Caption,
                        color = AppColors.TextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))

        // 6. Hydration Target Card
        Card(
            shape = AppShapes.Card,
            colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(AppSpacing.md)) {
                Text(text = "HYDRATION TARGET", style = AppTypography.Caption, color = AppColors.TextSecondary)
                Spacer(modifier = Modifier.height(AppSpacing.sm))

                PreferenceNumberField(
                    field = PreferenceField.WATER,
                    label = label("Daily Water Goal", PreferenceField.WATER),
                    form = form,
                    check = check,
                    onChange = viewModel::onFieldChanged,
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.lg))

        // Why the last save failed, or what still blocks it (BUG-026)
        form.saveError?.let { error ->
            Text(
                text = error,
                style = AppTypography.Body2,
                color = AppColors.ProgressOver,
                modifier = Modifier.padding(bottom = AppSpacing.sm)
            )
        }

        // Unified Save Button
        PrimaryButton(
            text = "Save Preferences & Goals",
            enabled = form.isLoaded && !form.isSaving && check.isValid,
            onClick = { viewModel.save() }
        )

        // Save confirmation banner
        if (form.showSavedMessage) {
            Spacer(modifier = Modifier.height(AppSpacing.sm))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF4CAF50).copy(alpha = 0.15f))
                    .border(1.dp, Color(0xFF4CAF50).copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                    .padding(AppSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(AppSpacing.xs))
                Text(
                    text = "Preferences and goals saved successfully!",
                    style = AppTypography.Caption.copy(fontWeight = FontWeight.Bold),
                    color = Color(0xFF4CAF50)
                )
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))
        HorizontalDivider(color = AppColors.Divider)
        Spacer(modifier = Modifier.height(AppSpacing.md))

        // 7. Data & Backup Navigation Link
        Card(
            shape = AppShapes.Card,
            colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigateToImportExport() }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppSpacing.md),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ImportExport, contentDescription = null, tint = AppColors.Primary)
                    Spacer(modifier = Modifier.size(AppSpacing.sm))
                    Column {
                        Text(text = "Data & Backup", style = AppTypography.Header3)
                        Text(
                            text = "Export and restore your offline database.",
                            style = AppTypography.Caption,
                            color = AppColors.TextSecondary
                        )
                    }
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = AppColors.TextSecondary)
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))

        // 8. Home Screen Widget Card
        Card(
            shape = AppShapes.Card,
            colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(AppSpacing.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Widgets,
                        contentDescription = null,
                        tint = AppColors.Primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.sm))
                    Column {
                        Text(text = "Home Screen Widget", style = AppTypography.Header3)
                        Text(
                            text = "Track calories, macros, and remaining meals directly from your home screen.",
                            style = AppTypography.Caption,
                            color = AppColors.TextSecondary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(AppSpacing.md))
                val context = LocalContext.current
                PrimaryButton(
                    text = "Add Widget to Home Screen",
                    onClick = {
                        WidgetPinHelper.pinWidgetToHomeScreen(context)
                    }
                )
            }
        }
    }
}

/** A numeric field of the form, with its error shown under it. */
@Composable
private fun PreferenceNumberField(
    field: PreferenceField,
    label: String,
    form: PreferencesFormState,
    check: PreferencesCheck,
    onChange: (PreferenceField, String) -> Unit,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Next,
    focusedColor: Color = AppColors.Primary
) {
    val error = check.error(field)
    OutlinedTextField(
        value = form.text(field),
        onValueChange = { onChange(field, it) },
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { message -> { Text(message) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        singleLine = true,
        modifier = modifier,
        colors = fieldColors(focusedColor)
    )
}

@Composable
private fun fieldColors(focusedColor: Color) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = focusedColor,
    unfocusedBorderColor = AppColors.Divider,
    focusedTextColor = AppColors.TextPrimary,
    unfocusedTextColor = AppColors.TextPrimary,
    errorBorderColor = AppColors.ProgressOver
)
