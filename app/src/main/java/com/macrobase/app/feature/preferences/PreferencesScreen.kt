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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class PreferencesViewModel(
    private val preferencesRepository: PreferencesRepository,
    private val getGoalsUseCase: GetGoalsUseCase,
    private val updateGoalsUseCase: UpdateGoalsUseCase
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = preferencesRepository.observePreferences()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences())

    val goals: StateFlow<Goal> = getGoalsUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Goal())

    fun saveAll(
        firstName: String,
        lastName: String,
        timeZone: String,
        unitSystem: UnitSystem,
        heightCm: Double?,
        weightKg: Double?,
        targetWeightKg: Double?,
        waterGoalMl: Double,
        calorieGoal: Double,
        carbPct: Double,
        proteinPct: Double,
        fatPct: Double,
        fitnessGoal: FitnessGoal,
        maintenanceCalories: Double,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            val updatedPrefs = UserPreferences(
                firstName = firstName.trim(),
                lastName = lastName.trim(),
                timeZone = timeZone.trim(),
                unitSystem = unitSystem,
                heightCm = heightCm,
                currentWeightKg = weightKg,
                targetWeightKg = targetWeightKg,
                dailyWaterGoalMl = waterGoalMl
            )
            val updatedGoal = Goal(
                dailyCalorieGoal = calorieGoal,
                carbPercentage = carbPct,
                proteinPercentage = proteinPct,
                fatPercentage = fatPct,
                fitnessGoal = fitnessGoal,
                maintenanceCalories = maintenanceCalories
            )
            preferencesRepository.updatePreferences(updatedPrefs)
            val goalRes = updateGoalsUseCase(updatedGoal)
            if (goalRes.isSuccess) {
                onSuccess()
            }
        }
    }
}

@Composable
fun PreferencesScreen(
    viewModel: PreferencesViewModel,
    onNavigateToImportExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val prefs by viewModel.preferences.collectAsState()
    val goals by viewModel.goals.collectAsState()

    var firstName by remember(prefs) { mutableStateOf(prefs.firstName) }
    var lastName by remember(prefs) { mutableStateOf(prefs.lastName) }
    var timeZone by remember(prefs) { mutableStateOf(prefs.timeZone) }
    var selectedUnitSystem by remember(prefs) { mutableStateOf(prefs.unitSystem) }

    // Internal stored values are always Metric (kg, cm, mL)
    var weightInput by remember(prefs, selectedUnitSystem) {
        val kg = prefs.currentWeightKg
        val text = if (kg != null) {
            if (selectedUnitSystem == UnitSystem.IMPERIAL) String.format(Locale.US, "%.1f", UnitConversions.kgToLbs(kg))
            else String.format(Locale.US, "%.1f", kg)
        } else ""
        mutableStateOf(text)
    }

    var targetWeightInput by remember(prefs, selectedUnitSystem) {
        val kg = prefs.targetWeightKg
        val text = if (kg != null) {
            if (selectedUnitSystem == UnitSystem.IMPERIAL) String.format(Locale.US, "%.1f", UnitConversions.kgToLbs(kg))
            else String.format(Locale.US, "%.1f", kg)
        } else ""
        mutableStateOf(text)
    }

    var heightInput by remember(prefs, selectedUnitSystem) {
        val cm = prefs.heightCm
        val text = if (cm != null) {
            if (selectedUnitSystem == UnitSystem.IMPERIAL) String.format(Locale.US, "%.1f", UnitConversions.cmToInches(cm))
            else String.format(Locale.US, "%.1f", cm)
        } else ""
        mutableStateOf(text)
    }

    var waterGoalInput by remember(prefs, selectedUnitSystem) {
        val ml = prefs.dailyWaterGoalMl
        val text = if (selectedUnitSystem == UnitSystem.IMPERIAL) String.format(Locale.US, "%.0f", UnitConversions.mlToFlOz(ml))
        else String.format(Locale.US, "%.0f", ml)
        mutableStateOf(text)
    }

    // Strategy & Overrides
    var isManuallyOverridden by remember { mutableStateOf(false) }
    var selectedGoal by remember(goals) { mutableStateOf(goals.fitnessGoal) }

    // Goal & Nutrition inputs
    var maintenanceCaloriesText by remember(goals) { mutableStateOf(goals.maintenanceCalories.roundToInt().toString()) }
    var caloriesText by remember(goals) { mutableStateOf(goals.dailyCalorieGoal.roundToInt().toString()) }
    var carbsText by remember(goals) { mutableStateOf(goals.carbPercentage.roundToInt().toString()) }
    var proteinText by remember(goals) { mutableStateOf(goals.proteinPercentage.roundToInt().toString()) }
    var fatText by remember(goals) { mutableStateOf(goals.fatPercentage.roundToInt().toString()) }

    var showSaveMessage by remember { mutableStateOf(false) }

    // Convert inputs to kg for strategy auto-detection
    val rawWeight = weightInput.toDoubleOrNull()
    val rawTargetWeight = targetWeightInput.toDoubleOrNull()
    val currentKg = if (selectedUnitSystem == UnitSystem.IMPERIAL) rawWeight?.let { UnitConversions.lbsToKg(it) } else rawWeight
    val targetKg = if (selectedUnitSystem == UnitSystem.IMPERIAL) rawTargetWeight?.let { UnitConversions.lbsToKg(it) } else rawTargetWeight
    val autoDetectedGoal = GoalStrategyHelper.autoDetectStrategy(currentKg, targetKg)
    val contradictionWarning = GoalStrategyHelper.getContradictionWarning(selectedGoal, currentKg, targetKg, selectedUnitSystem)

    // Goal validation & preview
    val parsedCalorie = caloriesText.toDoubleOrNull()
    val isCalorieValid = parsedCalorie != null && parsedCalorie > 0.0
    val calorieVal = (parsedCalorie ?: 0.0).coerceAtLeast(0.0)

    val parsedMaintenance = maintenanceCaloriesText.toDoubleOrNull()
    val isMaintenanceValid = parsedMaintenance != null && parsedMaintenance > 0.0
    val maintenanceVal = (parsedMaintenance ?: 0.0).coerceAtLeast(0.0)

    val parsedCarbs = carbsText.toDoubleOrNull()
    val isCarbsValid = parsedCarbs != null && parsedCarbs >= 0.0
    val carbPctVal = parsedCarbs ?: 0.0

    val parsedProtein = proteinText.toDoubleOrNull()
    val isProteinValid = parsedProtein != null && parsedProtein >= 0.0
    val proteinPctVal = parsedProtein ?: 0.0

    val parsedFat = fatText.toDoubleOrNull()
    val isFatValid = parsedFat != null && parsedFat >= 0.0
    val fatPctVal = parsedFat ?: 0.0

    val previewGoal = Goal(
        dailyCalorieGoal = calorieVal,
        carbPercentage = carbPctVal,
        proteinPercentage = proteinPctVal,
        fatPercentage = fatPctVal,
        fitnessGoal = selectedGoal,
        maintenanceCalories = maintenanceVal
    )

    val totalPercentage = previewGoal.totalPercentageExact
    val isValidSum = abs(totalPercentage - 100.0) < 0.01

    val isFormValid = isCalorieValid && isMaintenanceValid && isCarbsValid && isProteinValid && isFatValid && isValidSum

    val weightLabel = if (selectedUnitSystem == UnitSystem.IMPERIAL) "Current Weight (lb)" else "Current Weight (kg)"
    val targetWeightLabel = if (selectedUnitSystem == UnitSystem.IMPERIAL) "Target Weight (lb)" else "Target Weight (kg)"
    val heightLabel = if (selectedUnitSystem == UnitSystem.IMPERIAL) "Height (inches)" else "Height (cm)"
    val waterGoalLabel = if (selectedUnitSystem == UnitSystem.IMPERIAL) "Daily Water Goal (fl oz)" else "Daily Water Goal (mL)"

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
                    value = firstName,
                    onValueChange = {
                        firstName = it
                        showSaveMessage = false
                    },
                    label = { Text("First Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppColors.Primary,
                        unfocusedBorderColor = AppColors.Divider,
                        focusedTextColor = AppColors.TextPrimary,
                        unfocusedTextColor = AppColors.TextPrimary
                    )
                )
                Spacer(modifier = Modifier.height(AppSpacing.sm))

                OutlinedTextField(
                    value = lastName,
                    onValueChange = {
                        lastName = it
                        showSaveMessage = false
                    },
                    label = { Text("Last Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppColors.Primary,
                        unfocusedBorderColor = AppColors.Divider,
                        focusedTextColor = AppColors.TextPrimary,
                        unfocusedTextColor = AppColors.TextPrimary
                    )
                )
                Spacer(modifier = Modifier.height(AppSpacing.sm))

                OutlinedTextField(
                    value = timeZone,
                    onValueChange = {
                        timeZone = it
                        showSaveMessage = false
                    },
                    label = { Text("Time Zone (e.g. UTC, America/New_York)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppColors.Primary,
                        unfocusedBorderColor = AppColors.Divider,
                        focusedTextColor = AppColors.TextPrimary,
                        unfocusedTextColor = AppColors.TextPrimary
                    )
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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedUnitSystem = UnitSystem.METRIC
                            showSaveMessage = false
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedUnitSystem == UnitSystem.METRIC,
                        onClick = {
                            selectedUnitSystem = UnitSystem.METRIC
                            showSaveMessage = false
                        },
                        colors = RadioButtonDefaults.colors(selectedColor = AppColors.Primary)
                    )
                    Text(text = "Metric (kg, cm, mL)", style = AppTypography.Body1)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedUnitSystem = UnitSystem.IMPERIAL
                            showSaveMessage = false
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedUnitSystem == UnitSystem.IMPERIAL,
                        onClick = {
                            selectedUnitSystem = UnitSystem.IMPERIAL
                            showSaveMessage = false
                        },
                        colors = RadioButtonDefaults.colors(selectedColor = AppColors.Primary)
                    )
                    Text(text = "Imperial (lb, in, fl oz)", style = AppTypography.Body1)
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

                OutlinedTextField(
                    value = heightInput,
                    onValueChange = {
                        heightInput = it
                        showSaveMessage = false
                    },
                    label = { Text(heightLabel) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppColors.Primary,
                        unfocusedBorderColor = AppColors.Divider,
                        focusedTextColor = AppColors.TextPrimary,
                        unfocusedTextColor = AppColors.TextPrimary
                    )
                )
                Spacer(modifier = Modifier.height(AppSpacing.sm))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
                ) {
                    OutlinedTextField(
                        value = weightInput,
                        onValueChange = {
                            weightInput = it
                            showSaveMessage = false
                            if (!isManuallyOverridden) {
                                val w = it.toDoubleOrNull()
                                val cKg = if (selectedUnitSystem == UnitSystem.IMPERIAL) w?.let { UnitConversions.lbsToKg(it) } else w
                                selectedGoal = GoalStrategyHelper.autoDetectStrategy(cKg, targetKg)
                            }
                        },
                        label = { Text(weightLabel) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppColors.Primary,
                            unfocusedBorderColor = AppColors.Divider,
                            focusedTextColor = AppColors.TextPrimary,
                            unfocusedTextColor = AppColors.TextPrimary
                        )
                    )
                    OutlinedTextField(
                        value = targetWeightInput,
                        onValueChange = {
                            targetWeightInput = it
                            showSaveMessage = false
                            if (!isManuallyOverridden) {
                                val tw = it.toDoubleOrNull()
                                val tKg = if (selectedUnitSystem == UnitSystem.IMPERIAL) tw?.let { UnitConversions.lbsToKg(it) } else tw
                                selectedGoal = GoalStrategyHelper.autoDetectStrategy(currentKg, tKg)
                            }
                        },
                        label = { Text(targetWeightLabel) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppColors.Primary,
                            unfocusedBorderColor = AppColors.Divider,
                            focusedTextColor = AppColors.TextPrimary,
                            unfocusedTextColor = AppColors.TextPrimary
                        )
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
                            selected = selectedGoal == strategy,
                            onClick = {
                                selectedGoal = strategy
                                isManuallyOverridden = true
                                showSaveMessage = false
                            },
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
                                onClick = {
                                    selectedGoal = autoDetectedGoal
                                    isManuallyOverridden = false
                                    showSaveMessage = false
                                },
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

                // Dynamic Strategy Tip Box
                val helperText = when (selectedGoal) {
                    FitnessGoal.BULKING -> "Target intake: +0 to +500 kcal surplus above maintenance. +350 kcal is optimal for maximum RR reward."
                    FitnessGoal.CUTTING -> "Target intake: 0 to -500 kcal deficit below maintenance. -350 to -400 kcal is optimal for maximum RR reward."
                    FitnessGoal.MAINTAINING -> "Target intake: Stay within \u00b1100 kcal of maintenance for maximum RR reward."
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(AppColors.SurfaceAlt)
                        .padding(AppSpacing.sm)
                ) {
                    Text(
                        text = helperText,
                        style = AppTypography.Caption,
                        color = AppColors.TextSecondary
                    )
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

                OutlinedTextField(
                    value = maintenanceCaloriesText,
                    onValueChange = {
                        maintenanceCaloriesText = it
                        showSaveMessage = false
                    },
                    isError = maintenanceCaloriesText.isNotBlank() && !isMaintenanceValid,
                    label = { Text("Maintenance Calories (kcal)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppColors.Primary,
                        unfocusedBorderColor = AppColors.Divider,
                        focusedTextColor = AppColors.TextPrimary,
                        unfocusedTextColor = AppColors.TextPrimary,
                        errorBorderColor = AppColors.ProgressOver
                    )
                )

                Spacer(modifier = Modifier.height(AppSpacing.sm))

                OutlinedTextField(
                    value = caloriesText,
                    onValueChange = {
                        caloriesText = it
                        showSaveMessage = false
                    },
                    isError = caloriesText.isNotBlank() && !isCalorieValid,
                    label = { Text("Daily Calorie Target (kcal)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppColors.Primary,
                        unfocusedBorderColor = AppColors.Divider,
                        focusedTextColor = AppColors.TextPrimary,
                        unfocusedTextColor = AppColors.TextPrimary,
                        errorBorderColor = AppColors.ProgressOver
                    )
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

                // Carbohydrates
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = carbsText,
                        onValueChange = {
                            carbsText = it
                            showSaveMessage = false
                        },
                        isError = carbsText.isNotBlank() && !isCarbsValid,
                        label = { Text("Carbs (%)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppColors.MacroCarbs,
                            unfocusedBorderColor = AppColors.Divider,
                            focusedTextColor = AppColors.TextPrimary,
                            unfocusedTextColor = AppColors.TextPrimary,
                            errorBorderColor = AppColors.ProgressOver
                        )
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Carb Target", style = AppTypography.Caption, color = AppColors.TextSecondary)
                        Text(text = "${previewGoal.carbGrams.roundToInt()} g", style = AppTypography.Header2, color = AppColors.MacroCarbs)
                    }
                }

                Spacer(modifier = Modifier.height(AppSpacing.sm))

                // Protein
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = proteinText,
                        onValueChange = {
                            proteinText = it
                            showSaveMessage = false
                        },
                        isError = proteinText.isNotBlank() && !isProteinValid,
                        label = { Text("Protein (%)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppColors.MacroProtein,
                            unfocusedBorderColor = AppColors.Divider,
                            focusedTextColor = AppColors.TextPrimary,
                            unfocusedTextColor = AppColors.TextPrimary,
                            errorBorderColor = AppColors.ProgressOver
                        )
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Protein Target", style = AppTypography.Caption, color = AppColors.TextSecondary)
                        Text(text = "${previewGoal.proteinGrams.roundToInt()} g", style = AppTypography.Header2, color = AppColors.MacroProtein)
                    }
                }

                Spacer(modifier = Modifier.height(AppSpacing.sm))

                // Fat
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = fatText,
                        onValueChange = {
                            fatText = it
                            showSaveMessage = false
                        },
                        isError = fatText.isNotBlank() && !isFatValid,
                        label = { Text("Fat (%)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppColors.MacroFat,
                            unfocusedBorderColor = AppColors.Divider,
                            focusedTextColor = AppColors.TextPrimary,
                            unfocusedTextColor = AppColors.TextPrimary,
                            errorBorderColor = AppColors.ProgressOver
                        )
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Fat Target", style = AppTypography.Caption, color = AppColors.TextSecondary)
                        Text(text = "${previewGoal.fatGrams.roundToInt()} g", style = AppTypography.Header2, color = AppColors.MacroFat)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))

        // 100% Macro Validation Banner
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
                        text = "Current sum: ${totalPercentage.roundToInt()}% (Carbs ${carbPctVal.roundToInt()}%, Protein ${proteinPctVal.roundToInt()}%, Fat ${fatPctVal.roundToInt()}%)",
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

                OutlinedTextField(
                    value = waterGoalInput,
                    onValueChange = {
                        waterGoalInput = it
                        showSaveMessage = false
                    },
                    label = { Text(waterGoalLabel) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppColors.Primary,
                        unfocusedBorderColor = AppColors.Divider,
                        focusedTextColor = AppColors.TextPrimary,
                        unfocusedTextColor = AppColors.TextPrimary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.lg))

        // Unified Save Button
        PrimaryButton(
            text = "Save Preferences & Goals",
            enabled = isFormValid,
            onClick = {
                val rawHeight = heightInput.toDoubleOrNull()
                val heightCm = if (rawHeight != null) {
                    if (selectedUnitSystem == UnitSystem.IMPERIAL) UnitConversions.inchesToCm(rawHeight) else rawHeight
                } else null

                val parsedW = weightInput.toDoubleOrNull()
                val weightKg = if (parsedW != null) {
                    if (selectedUnitSystem == UnitSystem.IMPERIAL) UnitConversions.lbsToKg(parsedW) else parsedW
                } else null

                val parsedTW = targetWeightInput.toDoubleOrNull()
                val targetWeightKg = if (parsedTW != null) {
                    if (selectedUnitSystem == UnitSystem.IMPERIAL) UnitConversions.lbsToKg(parsedTW) else parsedTW
                } else null

                val rawWater = waterGoalInput.toDoubleOrNull() ?: 2500.0
                val waterMl = if (selectedUnitSystem == UnitSystem.IMPERIAL) UnitConversions.flOzToMl(rawWater) else rawWater

                viewModel.saveAll(
                    firstName = firstName,
                    lastName = lastName,
                    timeZone = timeZone,
                    unitSystem = selectedUnitSystem,
                    heightCm = heightCm,
                    weightKg = weightKg,
                    targetWeightKg = targetWeightKg,
                    waterGoalMl = waterMl,
                    calorieGoal = calorieVal,
                    carbPct = carbPctVal,
                    proteinPct = proteinPctVal,
                    fatPct = fatPctVal,
                    fitnessGoal = selectedGoal,
                    maintenanceCalories = maintenanceVal,
                    onSuccess = { showSaveMessage = true }
                )
            }
        )

        // Save confirmation banner
        if (showSaveMessage) {
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
