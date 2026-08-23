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
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.usecase.GetGoalsUseCase
import com.macrobase.app.domain.usecase.UpdateGoalsUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.abs

class DailyGoalsViewModel(
    private val getGoalsUseCase: GetGoalsUseCase,
    private val updateGoalsUseCase: UpdateGoalsUseCase
) : ViewModel() {

    val goals: StateFlow<Goal> = getGoalsUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Goal())

    fun updateGoals(calories: Double, carbsPct: Double, proteinPct: Double, fatPct: Double, onSuccess: () -> Unit) {
        viewModelScope.launch {
            val updated = Goal(
                dailyCalorieGoal = calories,
                carbPercentage = carbsPct,
                proteinPercentage = proteinPct,
                fatPercentage = fatPct
            )
            val res = updateGoalsUseCase(updated)
            if (res.isSuccess) {
                onSuccess()
            }
        }
    }
}

@Composable
fun DailyGoalsScreen(
    viewModel: DailyGoalsViewModel,
    onSaveSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val goals by viewModel.goals.collectAsState()

    var caloriesText by remember(goals) { mutableStateOf(goals.dailyCalorieGoal.toInt().toString()) }
    var carbsText by remember(goals) { mutableStateOf(goals.carbPercentage.toInt().toString()) }
    var proteinText by remember(goals) { mutableStateOf(goals.proteinPercentage.toInt().toString()) }
    var fatText by remember(goals) { mutableStateOf(goals.fatPercentage.toInt().toString()) }

    val calorieVal = (caloriesText.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
    val carbPctVal = carbsText.toDoubleOrNull() ?: 0.0
    val proteinPctVal = proteinText.toDoubleOrNull() ?: 0.0
    val fatPctVal = fatText.toDoubleOrNull() ?: 0.0

    val previewGoal = Goal(
        dailyCalorieGoal = calorieVal,
        carbPercentage = carbPctVal,
        proteinPercentage = proteinPctVal,
        fatPercentage = fatPctVal
    )

    val totalPercentage = previewGoal.totalPercentage
    val isValidSum = abs(totalPercentage - 100.0) < 0.01
    val isFormValid = calorieVal > 0.0 && carbPctVal >= 0.0 && proteinPctVal >= 0.0 && fatPctVal >= 0.0 && isValidSum

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Background)
            .padding(AppSpacing.lg)
            .verticalScroll(rememberScrollState())
    ) {
        Text(text = "Daily Goals", style = AppTypography.Header1)
        Text(
            text = "Configure your daily caloric target and macronutrient distribution split.",
            style = AppTypography.Body2,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = AppSpacing.xs)
        )

        Spacer(modifier = Modifier.height(AppSpacing.lg))

        // 1. Calorie Target Input Card
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
                    label = { Text("Daily Calorie Target (kcal)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = androidx.compose.ui.text.input.ImeAction.Next),
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

        // 2. Macronutrient Split Configuration Card
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
                        label = { Text("Carbs (%)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = androidx.compose.ui.text.input.ImeAction.Next),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppColors.MacroCarbs,
                            unfocusedBorderColor = AppColors.Divider,
                            focusedTextColor = AppColors.TextPrimary,
                            unfocusedTextColor = AppColors.TextPrimary
                        )
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Carb Target", style = AppTypography.Caption, color = AppColors.TextSecondary)
                        Text(text = "${previewGoal.carbGrams.toInt()} g", style = AppTypography.Header2, color = AppColors.MacroCarbs)
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
                        label = { Text("Protein (%)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = androidx.compose.ui.text.input.ImeAction.Next),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppColors.MacroProtein,
                            unfocusedBorderColor = AppColors.Divider,
                            focusedTextColor = AppColors.TextPrimary,
                            unfocusedTextColor = AppColors.TextPrimary
                        )
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Protein Target", style = AppTypography.Caption, color = AppColors.TextSecondary)
                        Text(text = "${previewGoal.proteinGrams.toInt()} g", style = AppTypography.Header2, color = AppColors.MacroProtein)
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
                        label = { Text("Fat (%)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppColors.MacroFat,
                            unfocusedBorderColor = AppColors.Divider,
                            focusedTextColor = AppColors.TextPrimary,
                            unfocusedTextColor = AppColors.TextPrimary
                        )
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Fat Target", style = AppTypography.Caption, color = AppColors.TextSecondary)
                        Text(text = "${previewGoal.fatGrams.toInt()} g", style = AppTypography.Header2, color = AppColors.MacroFat)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))

        // 3. Validation Banner
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
                        text = "Current sum: ${totalPercentage.toInt()}% (Carbs ${carbPctVal.toInt()}%, Protein ${proteinPctVal.toInt()}%, Fat ${fatPctVal.toInt()}%)",
                        style = AppTypography.Caption,
                        color = AppColors.TextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.xl))

        // 4. Save Button
        PrimaryButton(
            text = "Save Goals",
            enabled = isFormValid,
            onClick = {
                viewModel.updateGoals(
                    calories = calorieVal,
                    carbsPct = carbPctVal,
                    proteinPct = proteinPctVal,
                    fatPct = fatPctVal,
                    onSuccess = onSaveSuccess
                )
            }
        )
    }
}
