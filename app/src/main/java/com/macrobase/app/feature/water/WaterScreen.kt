package com.macrobase.app.feature.water

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppShapes
import com.macrobase.app.core.designsystem.AppSpacing
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.Dimensions
import com.macrobase.app.domain.model.UnitConversions
import com.macrobase.app.domain.model.UnitSystem
import com.macrobase.app.domain.model.UserPreferences
import com.macrobase.app.domain.model.WaterEntry
import com.macrobase.app.domain.repository.PreferencesRepository
import com.macrobase.app.domain.usecase.AddWaterEntryUseCase
import com.macrobase.app.domain.usecase.DeleteWaterEntryUseCase
import com.macrobase.app.domain.usecase.GetWaterEntriesUseCase
import com.macrobase.app.domain.usecase.GetWaterForDateUseCase
import com.macrobase.app.domain.usecase.UpdateWaterEntryUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

data class WaterUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val totalIntakeMl: Double = 0.0,
    val dailyGoalMl: Double = 2500.0,
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val entries: List<WaterEntry> = emptyList(),
    val isLoading: Boolean = false
) {
    val progressRatio: Float
        get() = if (dailyGoalMl > 0) (totalIntakeMl / dailyGoalMl).toFloat().coerceIn(0f, 1f) else 0f

    val progressPercentage: Int
        get() = if (dailyGoalMl > 0) ((totalIntakeMl / dailyGoalMl) * 100.0).roundToInt() else 0

    val remainingMl: Double
        get() = (dailyGoalMl - totalIntakeMl).coerceAtLeast(0.0)

    val isGoalReached: Boolean
        get() = totalIntakeMl >= dailyGoalMl && dailyGoalMl > 0
}

@OptIn(ExperimentalCoroutinesApi::class)
class WaterViewModel(
    private val getWaterForDateUseCase: GetWaterForDateUseCase,
    private val getWaterEntriesUseCase: GetWaterEntriesUseCase,
    private val addWaterEntryUseCase: AddWaterEntryUseCase,
    private val updateWaterEntryUseCase: UpdateWaterEntryUseCase,
    private val deleteWaterEntryUseCase: DeleteWaterEntryUseCase,
    private val preferencesRepository: PreferencesRepository
) : ViewModel() {

    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    val uiState: StateFlow<WaterUiState> = _selectedDate.flatMapLatest { date ->
        combine(
            getWaterForDateUseCase(date),
            getWaterEntriesUseCase(date),
            preferencesRepository.observePreferences()
        ) { totalMl, entries, prefs ->
            WaterUiState(
                selectedDate = date,
                totalIntakeMl = totalMl,
                dailyGoalMl = prefs.dailyWaterGoalMl,
                unitSystem = prefs.unitSystem,
                entries = entries,
                isLoading = false
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = WaterUiState()
    )

    fun onSelectDate(date: LocalDate) {
        _selectedDate.value = date
    }

    fun onPreviousDay() {
        _selectedDate.value = _selectedDate.value.minusDays(1)
    }

    fun onNextDay() {
        _selectedDate.value = _selectedDate.value.plusDays(1)
    }

    fun addWater(amountMl: Double) {
        if (amountMl <= 0.0) return
        viewModelScope.launch {
            addWaterEntryUseCase(_selectedDate.value, amountMl)
        }
    }

    fun updateWaterEntry(entry: WaterEntry) {
        if (entry.amountMl <= 0.0) return
        viewModelScope.launch {
            updateWaterEntryUseCase(entry)
        }
    }

    fun deleteWaterEntry(entryId: Long) {
        viewModelScope.launch {
            deleteWaterEntryUseCase(entryId)
        }
    }
}

@Composable
fun WaterScreen(
    viewModel: WaterViewModel,
    onNavigateBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEEE, MM/dd") }
    val timeFormatter = remember { DateTimeFormatter.ofPattern("hh:mm a") }

    var showCustomAmountDialog by remember { mutableStateOf(false) }
    var entryToEdit by remember { mutableStateOf<WaterEntry?>(null) }

    val animatedProgress by animateFloatAsState(
        targetValue = uiState.progressRatio,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "WaterProgressBarAnimation"
    )

    val isImperial = uiState.unitSystem == UnitSystem.IMPERIAL

    val displayTotal = if (isImperial) {
        "${UnitConversions.mlToFlOz(uiState.totalIntakeMl).roundToInt()} fl oz"
    } else {
        "${uiState.totalIntakeMl.toInt()} mL"
    }

    val displayGoal = if (isImperial) {
        "${UnitConversions.mlToFlOz(uiState.dailyGoalMl).roundToInt()} fl oz"
    } else {
        "${uiState.dailyGoalMl.toInt()} mL"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Background)
    ) {
        // 1. Top Bar with back navigation
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = AppColors.TextPrimary
                )
            }
            Text(text = "Water Tracker", style = AppTypography.Header1)
        }

        // 2. Date Selector Ribbon
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Dimensions.DateRibbonHeight)
                .background(AppColors.SurfaceAlt)
                .padding(horizontal = AppSpacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewModel.onPreviousDay() }) {
                Icon(
                    imageVector = Icons.Default.ChevronLeft,
                    contentDescription = "Previous Day",
                    tint = AppColors.TextPrimary
                )
            }

            Text(
                text = uiState.selectedDate.format(dateFormatter),
                style = AppTypography.Header2
            )

            IconButton(onClick = { viewModel.onNextDay() }) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Next Day",
                    tint = AppColors.TextPrimary
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(AppSpacing.lg)
                .verticalScroll(rememberScrollState())
        ) {
            // 3. Main Water Intake Card
            Card(
                shape = AppShapes.Card,
                colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(AppSpacing.lg)) {
                    Text(text = "💧 Water Intake", style = AppTypography.Header2)
                    Spacer(modifier = Modifier.height(AppSpacing.sm))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$displayTotal / $displayGoal",
                            style = AppTypography.Body1,
                            color = AppColors.TextPrimary
                        )
                        Text(
                            text = "${uiState.progressPercentage}%",
                            style = AppTypography.Header2,
                            color = AppColors.WaterCyan
                        )
                    }

                    Spacer(modifier = Modifier.height(AppSpacing.sm))

                    // Animated Smooth Progress Bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(Dimensions.WaterProgressHeight)
                            .clip(RoundedCornerShape(4.dp))
                            .background(AppColors.SurfaceAlt)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(animatedProgress)
                                .height(Dimensions.WaterProgressHeight)
                                .clip(RoundedCornerShape(4.dp))
                                .background(AppColors.WaterCyan)
                        )
                    }

                    Spacer(modifier = Modifier.height(AppSpacing.lg))

                    Text(text = "Quick Add:", style = AppTypography.Caption, color = AppColors.TextSecondary)
                    Spacer(modifier = Modifier.height(AppSpacing.xs))

                    // 4 Quick-Add Pill Buttons (+250mL, +500mL, +750mL, + Custom)
                    val quickAddList = if (isImperial) {
                        listOf(
                            Triple("+8 oz", UnitConversions.flOzToMl(8.0), 8.0),
                            Triple("+16 oz", UnitConversions.flOzToMl(16.0), 16.0),
                            Triple("+24 oz", UnitConversions.flOzToMl(24.0), 24.0)
                        )
                    } else {
                        listOf(
                            Triple("+250 mL", 250.0, 250.0),
                            Triple("+500 mL", 500.0, 500.0),
                            Triple("+750 mL", 750.0, 750.0)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                    ) {
                        quickAddList.forEach { (label, ml, _) ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(Dimensions.ButtonHeightSmall)
                                    .clip(AppShapes.PillButton)
                                    .background(AppColors.SurfaceAlt)
                                    .border(1.dp, AppColors.WaterCyan, AppShapes.PillButton)
                                    .clickable { viewModel.addWater(ml) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    style = AppTypography.Caption,
                                    color = AppColors.TextPrimary
                                )
                            }
                        }

                        // Custom Pill
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(Dimensions.ButtonHeightSmall)
                                .clip(AppShapes.PillButton)
                                .background(AppColors.SurfaceAlt)
                                .border(1.dp, AppColors.WaterCyan, AppShapes.PillButton)
                                .clickable { showCustomAmountDialog = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "+ Custom",
                                style = AppTypography.Caption,
                                color = AppColors.WaterCyan
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.lg))

            // 4. Daily Logs History Section
            Text(
                text = "Water Logs (${uiState.entries.size})",
                style = AppTypography.Header2
            )
            Spacer(modifier = Modifier.height(AppSpacing.sm))

            if (uiState.entries.isEmpty()) {
                Card(
                    shape = AppShapes.Card,
                    colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(AppSpacing.lg),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No water logged for this date. Use Quick Add above to log water.",
                            style = AppTypography.Body2,
                            color = AppColors.TextMuted
                        )
                    }
                }
            } else {
                uiState.entries.forEach { entry ->
                    val entryTime = entry.loggedAt.atZone(ZoneId.systemDefault()).format(timeFormatter)
                    val entryAmountDisplay = if (isImperial) {
                        "${UnitConversions.mlToFlOz(entry.amountMl).roundToInt()} fl oz"
                    } else {
                        "${entry.amountMl.toInt()} mL"
                    }

                    Card(
                        shape = AppShapes.Card,
                        colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = AppSpacing.xs)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Opacity,
                                    contentDescription = null,
                                    tint = AppColors.WaterCyan,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(AppSpacing.sm))
                                Column {
                                    Text(text = entryAmountDisplay, style = AppTypography.Header3)
                                    Text(text = entryTime, style = AppTypography.Caption, color = AppColors.TextSecondary)
                                }
                            }

                            Row {
                                IconButton(onClick = { entryToEdit = entry }) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Edit",
                                        tint = AppColors.TextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(onClick = { viewModel.deleteWaterEntry(entry.id) }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = AppColors.ProgressOver,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 5. Custom Amount Dialog
    if (showCustomAmountDialog) {
        var inputAmount by remember { mutableStateOf("") }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        val unitLabel = if (isImperial) "fl oz" else "mL"

        AlertDialog(
            onDismissRequest = { showCustomAmountDialog = false },
            title = { Text(text = "Add Custom Water Amount", style = AppTypography.Header2) },
            text = {
                Column {
                    Text(
                        text = "Enter water amount in $unitLabel:",
                        style = AppTypography.Body2,
                        color = AppColors.TextSecondary
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                    OutlinedTextField(
                        value = inputAmount,
                        onValueChange = {
                            inputAmount = it
                            errorMessage = null
                        },
                        label = { Text("Amount ($unitLabel)") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        ),
                        singleLine = true,
                        isError = errorMessage != null,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppColors.WaterCyan,
                            unfocusedBorderColor = AppColors.TextSecondary,
                            errorBorderColor = AppColors.ProgressOver
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(AppSpacing.xs))
                        Text(
                            text = errorMessage ?: "",
                            style = AppTypography.Caption,
                            color = AppColors.ProgressOver
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsed = inputAmount.toDoubleOrNull()
                        if (parsed == null || parsed <= 0.0) {
                            errorMessage = "Please enter a valid positive number"
                            return@Button
                        }
                        val ml = if (isImperial) UnitConversions.flOzToMl(parsed) else parsed
                        if (ml > 10000.0) {
                            errorMessage = "Amount exceeds 10,000 mL maximum threshold"
                            return@Button
                        }
                        viewModel.addWater(ml)
                        showCustomAmountDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.WaterCyan)
                ) {
                    Text("Add", color = AppColors.Background)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomAmountDialog = false }) {
                    Text("Cancel", color = AppColors.TextSecondary)
                }
            },
            containerColor = AppColors.Surface
        )
    }

    // 6. Edit Entry Dialog
    entryToEdit?.let { entry ->
        val initialAmount = if (isImperial) {
            UnitConversions.mlToFlOz(entry.amountMl).roundToInt().toString()
        } else {
            entry.amountMl.toInt().toString()
        }
        var editInput by remember { mutableStateOf(initialAmount) }
        var editErrorMessage by remember { mutableStateOf<String?>(null) }
        val unitLabel = if (isImperial) "fl oz" else "mL"

        AlertDialog(
            onDismissRequest = { entryToEdit = null },
            title = { Text(text = "Edit Water Entry", style = AppTypography.Header2) },
            text = {
                Column {
                    Text(
                        text = "Update amount in $unitLabel:",
                        style = AppTypography.Body2,
                        color = AppColors.TextSecondary
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                    OutlinedTextField(
                        value = editInput,
                        onValueChange = {
                            editInput = it
                            editErrorMessage = null
                        },
                        label = { Text("Amount ($unitLabel)") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        ),
                        singleLine = true,
                        isError = editErrorMessage != null,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppColors.WaterCyan,
                            unfocusedBorderColor = AppColors.TextSecondary,
                            errorBorderColor = AppColors.ProgressOver
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (editErrorMessage != null) {
                        Spacer(modifier = Modifier.height(AppSpacing.xs))
                        Text(
                            text = editErrorMessage ?: "",
                            style = AppTypography.Caption,
                            color = AppColors.ProgressOver
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsed = editInput.toDoubleOrNull()
                        if (parsed == null || parsed <= 0.0) {
                            editErrorMessage = "Please enter a valid positive number"
                            return@Button
                        }
                        val ml = if (isImperial) UnitConversions.flOzToMl(parsed) else parsed
                        if (ml > 10000.0) {
                            editErrorMessage = "Amount exceeds 10,000 mL maximum threshold"
                            return@Button
                        }
                        viewModel.updateWaterEntry(entry.copy(amountMl = ml))
                        entryToEdit = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.WaterCyan)
                ) {
                    Text("Save", color = AppColors.Background)
                }
            },
            dismissButton = {
                TextButton(onClick = { entryToEdit = null }) {
                    Text("Cancel", color = AppColors.TextSecondary)
                }
            },
            containerColor = AppColors.Surface
        )
    }
}
