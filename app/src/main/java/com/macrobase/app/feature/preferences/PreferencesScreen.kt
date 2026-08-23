package com.macrobase.app.feature.preferences

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppShapes
import com.macrobase.app.core.designsystem.AppSpacing
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.components.PrimaryButton
import com.macrobase.app.domain.model.UnitConversions
import com.macrobase.app.domain.model.UnitSystem
import com.macrobase.app.domain.model.UserPreferences
import com.macrobase.app.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PreferencesViewModel(
    private val preferencesRepository: PreferencesRepository
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = preferencesRepository.observePreferences()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences())

    fun savePreferences(
        firstName: String,
        lastName: String,
        timeZone: String,
        unitSystem: UnitSystem,
        heightCm: Double?,
        weightKg: Double?,
        targetWeightKg: Double?,
        waterGoalMl: Double,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            val updated = UserPreferences(
                firstName = firstName.trim(),
                lastName = lastName.trim(),
                timeZone = timeZone.trim(),
                unitSystem = unitSystem,
                heightCm = heightCm,
                currentWeightKg = weightKg,
                targetWeightKg = targetWeightKg,
                dailyWaterGoalMl = waterGoalMl
            )
            preferencesRepository.updatePreferences(updated)
            onSuccess()
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

    var firstName by remember(prefs) { mutableStateOf(prefs.firstName) }
    var lastName by remember(prefs) { mutableStateOf(prefs.lastName) }
    var timeZone by remember(prefs) { mutableStateOf(prefs.timeZone) }
    var selectedUnitSystem by remember(prefs) { mutableStateOf(prefs.unitSystem) }

    // Internal stored values are always Metric (kg, cm, mL)
    var weightInput by remember(prefs, selectedUnitSystem) {
        val kg = prefs.currentWeightKg
        val text = if (kg != null) {
            if (selectedUnitSystem == UnitSystem.IMPERIAL) String.format("%.1f", UnitConversions.kgToLbs(kg))
            else String.format("%.1f", kg)
        } else ""
        mutableStateOf(text)
    }

    var targetWeightInput by remember(prefs, selectedUnitSystem) {
        val kg = prefs.targetWeightKg
        val text = if (kg != null) {
            if (selectedUnitSystem == UnitSystem.IMPERIAL) String.format("%.1f", UnitConversions.kgToLbs(kg))
            else String.format("%.1f", kg)
        } else ""
        mutableStateOf(text)
    }

    var heightInput by remember(prefs, selectedUnitSystem) {
        val cm = prefs.heightCm
        val text = if (cm != null) {
            if (selectedUnitSystem == UnitSystem.IMPERIAL) String.format("%.1f", UnitConversions.cmToInches(cm))
            else String.format("%.1f", cm)
        } else ""
        mutableStateOf(text)
    }

    var waterGoalInput by remember(prefs, selectedUnitSystem) {
        val ml = prefs.dailyWaterGoalMl
        val text = if (selectedUnitSystem == UnitSystem.IMPERIAL) String.format("%.0f", UnitConversions.mlToFlOz(ml))
        else String.format("%.0f", ml)
        mutableStateOf(text)
    }

    var showSaveMessage by remember { mutableStateOf(false) }

    val weightLabel = if (selectedUnitSystem == UnitSystem.IMPERIAL) "Weight (lb)" else "Weight (kg)"
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
            text = "Manage your biometric measurements, unit systems, and hydration targets.",
            style = AppTypography.Body2,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = AppSpacing.xs)
        )

        Spacer(modifier = Modifier.height(AppSpacing.lg))

        // 1. Profile Section Card
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
                    onValueChange = { firstName = it },
                    label = { Text("First Name") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(AppSpacing.sm))

                OutlinedTextField(
                    value = lastName,
                    onValueChange = { lastName = it },
                    label = { Text("Last Name") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(AppSpacing.sm))

                OutlinedTextField(
                    value = timeZone,
                    onValueChange = { timeZone = it },
                    label = { Text("Time Zone (e.g. UTC, America/New_York)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))

        // 2. Unit System Selection Card
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
                        .clickable { selectedUnitSystem = UnitSystem.METRIC },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedUnitSystem == UnitSystem.METRIC,
                        onClick = { selectedUnitSystem = UnitSystem.METRIC },
                        colors = RadioButtonDefaults.colors(selectedColor = AppColors.Primary)
                    )
                    Text(text = "Metric (kg, cm, mL)", style = AppTypography.Body1)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedUnitSystem = UnitSystem.IMPERIAL },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedUnitSystem == UnitSystem.IMPERIAL,
                        onClick = { selectedUnitSystem = UnitSystem.IMPERIAL },
                        colors = RadioButtonDefaults.colors(selectedColor = AppColors.Primary)
                    )
                    Text(text = "Imperial (lb, in, fl oz)", style = AppTypography.Body1)
                }
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))

        // 3. Biometrics & Targets Card
        Card(
            shape = AppShapes.Card,
            colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(AppSpacing.md)) {
                Text(text = "BIOMETRICS & TARGETS", style = AppTypography.Caption, color = AppColors.TextSecondary)
                Spacer(modifier = Modifier.height(AppSpacing.sm))

                OutlinedTextField(
                    value = heightInput,
                    onValueChange = { heightInput = it },
                    label = { Text(heightLabel) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(AppSpacing.sm))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
                ) {
                    OutlinedTextField(
                        value = weightInput,
                        onValueChange = { weightInput = it },
                        label = { Text(weightLabel) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = targetWeightInput,
                        onValueChange = { targetWeightInput = it },
                        label = { Text(targetWeightLabel) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(AppSpacing.sm))

                OutlinedTextField(
                    value = waterGoalInput,
                    onValueChange = { waterGoalInput = it },
                    label = { Text(waterGoalLabel) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.lg))

        // 4. Save Button
        PrimaryButton(
            text = "Save Preferences",
            onClick = {
                // Convert back to base Metric if in Imperial
                val rawHeight = heightInput.toDoubleOrNull()
                val heightCm = if (rawHeight != null) {
                    if (selectedUnitSystem == UnitSystem.IMPERIAL) UnitConversions.inchesToCm(rawHeight) else rawHeight
                } else null

                val rawWeight = weightInput.toDoubleOrNull()
                val weightKg = if (rawWeight != null) {
                    if (selectedUnitSystem == UnitSystem.IMPERIAL) UnitConversions.lbsToKg(rawWeight) else rawWeight
                } else null

                val rawTargetWeight = targetWeightInput.toDoubleOrNull()
                val targetWeightKg = if (rawTargetWeight != null) {
                    if (selectedUnitSystem == UnitSystem.IMPERIAL) UnitConversions.lbsToKg(rawTargetWeight) else rawTargetWeight
                } else null

                val rawWater = waterGoalInput.toDoubleOrNull() ?: 2500.0
                val waterMl = if (selectedUnitSystem == UnitSystem.IMPERIAL) UnitConversions.flOzToMl(rawWater) else rawWater

                viewModel.savePreferences(
                    firstName = firstName,
                    lastName = lastName,
                    timeZone = timeZone,
                    unitSystem = selectedUnitSystem,
                    heightCm = heightCm,
                    weightKg = weightKg,
                    targetWeightKg = targetWeightKg,
                    waterGoalMl = waterMl,
                    onSuccess = { showSaveMessage = true }
                )
            }
        )

        Spacer(modifier = Modifier.height(AppSpacing.md))
        HorizontalDivider(color = AppColors.Divider)
        Spacer(modifier = Modifier.height(AppSpacing.md))

        // 5. Data & Backup Navigation Link
        Card(
            shape = AppShapes.Card,
            colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigateToImportExport() }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(AppSpacing.md),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ImportExport, contentDescription = null, tint = AppColors.Primary)
                    Spacer(modifier = Modifier.size(AppSpacing.sm))
                    Column {
                        Text(text = "Data & Backup", style = AppTypography.Header3)
                        Text(text = "Export and restore your offline database.", style = AppTypography.Caption, color = AppColors.TextSecondary)
                    }
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = AppColors.TextSecondary)
            }
        }
    }
}
