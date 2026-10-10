package com.macrobase.app.feature.goals

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppShapes
import com.macrobase.app.core.designsystem.AppSpacing
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.components.PrimaryButton
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.text.font.FontWeight
import com.macrobase.app.domain.model.FitnessGoal
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.usecase.GetGoalsUseCase
import com.macrobase.app.domain.usecase.UpdateGoalsUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

class DailyGoalsViewModel(
    private val getGoalsUseCase: GetGoalsUseCase,
    private val updateGoalsUseCase: UpdateGoalsUseCase
) : ViewModel() {

    val goals: StateFlow<Goal> = getGoalsUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Goal())

    fun updateGoals(
        calories: Double,
        carbsPct: Double,
        proteinPct: Double,
        fatPct: Double,
        fitnessGoal: FitnessGoal = FitnessGoal.MAINTAINING,
        maintenanceCalories: Double = calories,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            val updated = Goal(
                dailyCalorieGoal = calories,
                carbPercentage = carbsPct,
                proteinPercentage = proteinPct,
                fatPercentage = fatPct,
                fitnessGoal = fitnessGoal,
                maintenanceCalories = maintenanceCalories
            )
            val res = updateGoalsUseCase(updated)
            if (res.isSuccess) {
                onSuccess()
            }
        }
    }
}

@Deprecated("Use PreferencesScreen which consolidates all profile, biometrics, and daily goals")
@Composable
fun DailyGoalsScreen(
    viewModel: DailyGoalsViewModel,
    onSaveSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val goals by viewModel.goals.collectAsState()

    var selectedGoal by remember(goals) { mutableStateOf(goals.fitnessGoal) }
    var maintenanceCaloriesText by remember(goals) { mutableStateOf(goals.maintenanceCalories.roundToInt().toString()) }
    var caloriesText by remember(goals) { mutableStateOf(goals.dailyCalorieGoal.roundToInt().toString()) }
    var carbsText by remember(goals) { mutableStateOf(goals.carbPercentage.roundToInt().toString()) }
    var proteinText by remember(goals) { mutableStateOf(goals.proteinPercentage.roundToInt().toString()) }
    var fatText by remember(goals) { mutableStateOf(goals.fatPercentage.roundToInt().toString()) }

    val parsedMaintenance = maintenanceCaloriesText.toDoubleOrNull()
    val isMaintenanceValid = parsedMaintenance != null && parsedMaintenance > 0.0
    val maintenanceVal = (parsedMaintenance ?: 0.0).coerceAtLeast(0.0)

    val parsedCalorie = caloriesText.toDoubleOrNull()
    val isCalorieValid = parsedCalorie != null && parsedCalorie > 0.0
    val calorieVal = (parsedCalorie ?: 0.0).coerceAtLeast(0.0)

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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Background)
            .padding(AppSpacing.lg)
            .verticalScroll(rememberScrollState())
    ) {
        Text(text = "Daily Goals", style = AppTypography.Header1)
        Text(
            text = "Configure your lifestyle strategy, maintenance calories, and macronutrient targets.",
            style = AppTypography.Body2,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = AppSpacing.xs)
        )

        Spacer(modifier = Modifier.height(AppSpacing.md))

        // Next-Day Notice Banner
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

        // 1. Strategy & Maintenance Card
        Card(
            shape = AppShapes.Card,
            colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(AppSpacing.md)) {
                Text(text = "FITNESS STRATEGY", style = AppTypography.Caption, color = AppColors.TextSecondary)
                Spacer(modifier = Modifier.height(AppSpacing.sm))

                // Strategy Segmented Control / FilterChips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    val strategies = listOf(FitnessGoal.BULKING, FitnessGoal.MAINTAINING, FitnessGoal.CUTTING)
                    strategies.forEach { strategy ->
                        FilterChip(
                            selected = selectedGoal == strategy,
                            onClick = { selectedGoal = strategy },
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

                Spacer(modifier = Modifier.height(AppSpacing.sm))

                // Dynamic Helper Card
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

                Spacer(modifier = Modifier.height(AppSpacing.md))

                // Maintenance Calories Input
                OutlinedTextField(
                    value = maintenanceCaloriesText,
                    onValueChange = { maintenanceCaloriesText = it },
                    isError = maintenanceCaloriesText.isNotBlank() && !isMaintenanceValid,
                    label = { Text("Maintenance Calories (kcal)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = androidx.compose.ui.text.input.ImeAction.Next),
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

        // 2. Calorie Target Input Card
        Card(
            shape = AppShapes.Card,
            colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(AppSpacing.md)) {
                Text(text = "DAILY ENERGY TARGET", style = AppTypography.Caption, color = AppColors.TextSecondary)
                Spacer(modifier = Modifier.height(AppSpacing.sm))

                OutlinedTextField(
                    value = caloriesText,
                    onValueChange = { caloriesText = it },
                    isError = caloriesText.isNotBlank() && !isCalorieValid,
                    label = { Text("Daily Calorie Target (kcal)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = androidx.compose.ui.text.input.ImeAction.Next),
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

        // 3. Macronutrient Split Configuration Card
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
                        onValueChange = { carbsText = it },
                        isError = carbsText.isNotBlank() && !isCarbsValid,
                        label = { Text("Carbs (%)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = androidx.compose.ui.text.input.ImeAction.Next),
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
                        onValueChange = { proteinText = it },
                        isError = proteinText.isNotBlank() && !isProteinValid,
                        label = { Text("Protein (%)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = androidx.compose.ui.text.input.ImeAction.Next),
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
                        onValueChange = { fatText = it },
                        isError = fatText.isNotBlank() && !isFatValid,
                        label = { Text("Fat (%)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
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

        // 4. Validation Banner
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

        Spacer(modifier = Modifier.height(AppSpacing.xl))

        // 5. Save Button
        PrimaryButton(
            text = "Save Goals",
            enabled = isFormValid,
            onClick = {
                viewModel.updateGoals(
                    calories = calorieVal,
                    carbsPct = carbPctVal,
                    proteinPct = proteinPctVal,
                    fatPct = fatPctVal,
                    fitnessGoal = selectedGoal,
                    maintenanceCalories = maintenanceVal,
                    onSuccess = onSaveSuccess
                )
            }
        )
    }
}
