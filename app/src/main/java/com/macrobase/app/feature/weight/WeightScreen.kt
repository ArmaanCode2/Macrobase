package com.macrobase.app.feature.weight

import android.app.DatePickerDialog
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppShapes
import com.macrobase.app.core.designsystem.AppSpacing
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.Dimensions
import com.macrobase.app.domain.model.UnitConversions
import com.macrobase.app.domain.model.UnitSystem
import com.macrobase.app.domain.model.WeightEntry
import com.macrobase.app.domain.repository.PreferencesRepository
import com.macrobase.app.domain.usecase.AddWeightEntryUseCase
import com.macrobase.app.domain.usecase.DeleteWeightEntryUseCase
import com.macrobase.app.domain.usecase.GetWeightForDateUseCase
import com.macrobase.app.domain.usecase.GetWeightHistoryUseCase
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
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

enum class WeightTimeInterval(val label: String) {
    LAST_30_DAYS("Last 30 days"),
    LAST_3_MONTHS("Last 3 months"),
    LAST_6_MONTHS("Last 6 months"),
    ONE_YEAR("1 Year"),
    ALL_TIME("All Time"),
    CUSTOM("Custom")
}

data class WeightMetrics(
    val startWeightKg: Double? = null,
    val currentWeightKg: Double? = null,
    val deltaWeightKg: Double? = null,
    val percentageChange: Double? = null,
    val targetWeightKg: Double? = null,
    val deltaToTargetKg: Double? = null
)

data class WeightUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val selectedInterval: WeightTimeInterval = WeightTimeInterval.LAST_30_DAYS,
    val fromDate: LocalDate = LocalDate.now().minusDays(30),
    val toDate: LocalDate = LocalDate.now(),
    val entries: List<WeightEntry> = emptyList(),
    val metrics: WeightMetrics = WeightMetrics(),
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val targetWeightKg: Double? = null,
    val selectedDateWeight: WeightEntry? = null,
    val isLoading: Boolean = false
) {
    val hasSufficientDataForGraph: Boolean
        get() = entries.size >= 2
}

@OptIn(ExperimentalCoroutinesApi::class)
class WeightViewModel(
    private val getWeightHistoryUseCase: GetWeightHistoryUseCase,
    private val getWeightForDateUseCase: GetWeightForDateUseCase,
    private val addWeightEntryUseCase: AddWeightEntryUseCase,
    private val deleteWeightEntryUseCase: DeleteWeightEntryUseCase,
    private val preferencesRepository: PreferencesRepository
) : ViewModel() {

    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    private val _selectedInterval = MutableStateFlow(WeightTimeInterval.LAST_30_DAYS)
    val selectedInterval: StateFlow<WeightTimeInterval> = _selectedInterval.asStateFlow()

    private val _customFromDate = MutableStateFlow(LocalDate.now().minusDays(30))
    val customFromDate: StateFlow<LocalDate> = _customFromDate.asStateFlow()

    private val _customToDate = MutableStateFlow(LocalDate.now())
    val customToDate: StateFlow<LocalDate> = _customToDate.asStateFlow()

    val uiState: StateFlow<WeightUiState> = combine(
        _selectedDate,
        _selectedInterval,
        _customFromDate,
        _customToDate
    ) { selDate, interval, customFrom, customTo ->
        val (from, to) = calculateDateRange(interval, customFrom, customTo)
        DateRangeParams(selDate, interval, from, to)
    }.flatMapLatest { params ->
        combine(
            getWeightHistoryUseCase(params.fromDate, params.toDate),
            getWeightForDateUseCase(params.selectedDate),
            preferencesRepository.observePreferences()
        ) { entries, dateEntry, prefs ->
            val sortedEntries = entries.sortedBy { it.date }
            val metrics = calculateMetrics(sortedEntries, prefs.targetWeightKg)

            WeightUiState(
                selectedDate = params.selectedDate,
                selectedInterval = params.interval,
                fromDate = params.fromDate,
                toDate = params.toDate,
                entries = sortedEntries,
                metrics = metrics,
                unitSystem = prefs.unitSystem,
                targetWeightKg = prefs.targetWeightKg,
                selectedDateWeight = dateEntry,
                isLoading = false
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = WeightUiState()
    )

    private data class DateRangeParams(
        val selectedDate: LocalDate,
        val interval: WeightTimeInterval,
        val fromDate: LocalDate,
        val toDate: LocalDate
    )

    private fun calculateDateRange(
        interval: WeightTimeInterval,
        customFrom: LocalDate,
        customTo: LocalDate
    ): Pair<LocalDate, LocalDate> {
        val today = LocalDate.now()
        return when (interval) {
            WeightTimeInterval.LAST_30_DAYS -> today.minusDays(30) to today
            WeightTimeInterval.LAST_3_MONTHS -> today.minusMonths(3) to today
            WeightTimeInterval.LAST_6_MONTHS -> today.minusMonths(6) to today
            WeightTimeInterval.ONE_YEAR -> today.minusYears(1) to today
            WeightTimeInterval.ALL_TIME -> LocalDate.of(2000, 1, 1) to today
            WeightTimeInterval.CUSTOM -> {
                val from = if (customFrom.isAfter(customTo)) customTo else customFrom
                from to customTo
            }
        }
    }

    private fun calculateMetrics(entries: List<WeightEntry>, targetWeightKg: Double?): WeightMetrics {
        if (entries.isEmpty()) {
            return WeightMetrics(targetWeightKg = targetWeightKg)
        }
        val start = entries.first().weightKg
        val current = entries.last().weightKg
        val delta = current - start
        val percent = if (start > 0.0) (delta / start) * 100.0 else 0.0
        val deltaTarget = targetWeightKg?.let { current - it }

        return WeightMetrics(
            startWeightKg = start,
            currentWeightKg = current,
            deltaWeightKg = delta,
            percentageChange = percent,
            targetWeightKg = targetWeightKg,
            deltaToTargetKg = deltaTarget
        )
    }

    fun onSelectDate(date: LocalDate) {
        _selectedDate.value = date
    }

    fun onPreviousDay() {
        _selectedDate.value = _selectedDate.value.minusDays(1)
    }

    fun onNextDay() {
        _selectedDate.value = _selectedDate.value.plusDays(1)
    }

    fun onSelectInterval(interval: WeightTimeInterval) {
        _selectedInterval.value = interval
    }

    fun onSetCustomRange(from: LocalDate, to: LocalDate) {
        _customFromDate.value = from
        _customToDate.value = to
        _selectedInterval.value = WeightTimeInterval.CUSTOM
    }

    fun logWeight(date: LocalDate, weightKg: Double, note: String? = null) {
        viewModelScope.launch {
            addWeightEntryUseCase(date, weightKg, note)
        }
    }

    fun deleteWeight(date: LocalDate) {
        viewModelScope.launch {
            deleteWeightEntryUseCase(date)
        }
    }

    fun deleteWeightById(id: Long) {
        viewModelScope.launch {
            deleteWeightEntryUseCase(id)
        }
    }
}

@Composable
fun WeightScreen(
    viewModel: WeightViewModel,
    onNavigateBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val isImperial = uiState.unitSystem == UnitSystem.IMPERIAL
    val unitLabel = if (isImperial) "lb" else "kg"

    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEEE, MM/dd") }
    val shortDateFormatter = remember { DateTimeFormatter.ofPattern("MMM dd") }

    var showLogDialog by remember { mutableStateOf(false) }
    var entryToEdit by remember { mutableStateOf<WeightEntry?>(null) }
    var showIntervalDropdown by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Background)
    ) {
        // 1. Top Bar
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
            Text(text = "Weight Graph", style = AppTypography.Header1)
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
            // 3. Main Weight Graph Container Card (Surface #1E1E1E, Radius 8dp, Padding 16dp)
            Card(
                shape = AppShapes.Card,
                colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(AppSpacing.md)) {
                    // Header & Range Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Weight Graph", style = AppTypography.Header2)

                        // Interval Dropdown
                        Box {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(AppColors.SurfaceAlt)
                                    .clickable { showIntervalDropdown = true }
                                    .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = uiState.selectedInterval.label,
                                    style = AppTypography.Caption,
                                    color = AppColors.WaterCyan
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = AppColors.WaterCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = showIntervalDropdown,
                                onDismissRequest = { showIntervalDropdown = false },
                                modifier = Modifier.background(AppColors.Surface)
                            ) {
                                WeightTimeInterval.entries.forEach { interval ->
                                    DropdownMenuItem(
                                        text = { Text(interval.label, color = AppColors.TextPrimary) },
                                        onClick = {
                                            viewModel.onSelectInterval(interval)
                                            showIntervalDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(AppSpacing.xs))

                    // From - To Date Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable {
                                    val d = uiState.fromDate
                                    DatePickerDialog(context, { _, y, m, day ->
                                        val newFrom = LocalDate.of(y, m + 1, day)
                                        viewModel.onSetCustomRange(newFrom, uiState.toDate)
                                    }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CalendarToday, contentDescription = null, tint = AppColors.TextSecondary, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "From ${uiState.fromDate.format(shortDateFormatter)}", style = AppTypography.Caption, color = AppColors.TextSecondary)
                        }

                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable {
                                    val d = uiState.toDate
                                    DatePickerDialog(context, { _, y, m, day ->
                                        val newTo = LocalDate.of(y, m + 1, day)
                                        viewModel.onSetCustomRange(uiState.fromDate, newTo)
                                    }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CalendarToday, contentDescription = null, tint = AppColors.TextSecondary, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "To ${uiState.toDate.format(shortDateFormatter)}", style = AppTypography.Caption, color = AppColors.TextSecondary)
                        }
                    }

                    Spacer(modifier = Modifier.height(AppSpacing.md))
                    HorizontalDivider(color = AppColors.Divider)
                    Spacer(modifier = Modifier.height(AppSpacing.md))

                    // Metric Strip: START / CURRENT / CHANGE (3 Columns)
                    val startDisplay = formatWeightValue(uiState.metrics.startWeightKg, isImperial)
                    val currentDisplay = formatWeightValue(uiState.metrics.currentWeightKg, isImperial)
                    val deltaVal = uiState.metrics.deltaWeightKg
                    val deltaDisplay = formatWeightDelta(deltaVal, isImperial)
                    val percentDisplay = uiState.metrics.percentageChange?.let { "(${String.format("%.1f", abs(it))}%)" } ?: ""

                    val changeColor = if (deltaVal != null) {
                        if (deltaVal <= 0.0) Color(0xFF00C853) else AppColors.ProgressOver
                    } else AppColors.TextSecondary

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Start
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            Text(text = startDisplay, style = AppTypography.Header2)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(text = "START", style = AppTypography.Caption, color = AppColors.TextSecondary)
                        }

                        Box(modifier = Modifier.width(1.dp).height(36.dp).background(AppColors.Divider))

                        // Current
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            Text(text = currentDisplay, style = AppTypography.Header2)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(text = "CURRENT", style = AppTypography.Caption, color = AppColors.TextSecondary)
                        }

                        Box(modifier = Modifier.width(1.dp).height(36.dp).background(AppColors.Divider))

                        // Change
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            Text(text = deltaDisplay, style = AppTypography.Header2, color = changeColor)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(text = "CHANGE $percentDisplay", style = AppTypography.Caption, color = AppColors.TextSecondary)
                        }
                    }

                    Spacer(modifier = Modifier.height(AppSpacing.lg))

                    // Graph Canvas or Empty State
                    if (uiState.hasSufficientDataForGraph) {
                        WeightLineGraph(
                            entries = uiState.entries,
                            isImperial = isImperial,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = AppSpacing.lg),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Log at least 2 weight entries to generate progress graph",
                                style = AppTypography.Body2,
                                color = AppColors.TextMuted
                            )
                            Spacer(modifier = Modifier.height(AppSpacing.md))
                            Button(
                                onClick = { showLogDialog = true },
                                shape = AppShapes.PillButton,
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.WaterCyan)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = AppColors.Background, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("+ Log Weight", color = AppColors.Background, style = AppTypography.Button)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.lg))

            // 4. Quick Action Button & Header for Selected Date
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Weight Logs (${uiState.entries.size})",
                    style = AppTypography.Header2
                )
                Button(
                    onClick = { showLogDialog = true },
                    shape = AppShapes.PillButton,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.SurfaceAlt)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = AppColors.WaterCyan, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (uiState.selectedDateWeight != null) "Edit Today" else "+ Log Weight",
                        color = AppColors.WaterCyan,
                        style = AppTypography.Caption
                    )
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.sm))

            // 5. Weight Entries List
            if (uiState.entries.isEmpty()) {
                Card(
                    shape = AppShapes.Card,
                    colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(AppSpacing.lg),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "No weight logs for this interval.", style = AppTypography.Body2, color = AppColors.TextMuted)
                    }
                }
            } else {
                uiState.entries.reversed().forEach { entry ->
                    val displayWeight = formatWeightValue(entry.weightKg, isImperial)

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
                                    imageVector = Icons.Default.Scale,
                                    contentDescription = null,
                                    tint = AppColors.MacroProtein,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(AppSpacing.sm))
                                Column {
                                    Text(text = displayWeight, style = AppTypography.Header3)
                                    Text(
                                        text = entry.date.format(dateFormatter),
                                        style = AppTypography.Caption,
                                        color = AppColors.TextSecondary
                                    )
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
                                IconButton(onClick = { viewModel.deleteWeight(entry.date) }) {
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

    // 6. Log Weight Dialog
    if (showLogDialog) {
        var inputWeight by remember {
            mutableStateOf(
                uiState.selectedDateWeight?.let {
                    val w = if (isImperial) UnitConversions.kgToLbs(it.weightKg) else it.weightKg
                    String.format("%.1f", w)
                } ?: ""
            )
        }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showLogDialog = false },
            title = { Text("Log Body Weight", style = AppTypography.Header2) },
            text = {
                Column {
                    Text(
                        text = "Enter weight for ${uiState.selectedDate.format(dateFormatter)} ($unitLabel):",
                        style = AppTypography.Body2,
                        color = AppColors.TextSecondary
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                    OutlinedTextField(
                        value = inputWeight,
                        onValueChange = {
                            inputWeight = it
                            errorMessage = null
                        },
                        label = { Text("Weight ($unitLabel)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
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
                        Text(text = errorMessage ?: "", style = AppTypography.Caption, color = AppColors.ProgressOver)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsed = inputWeight.toDoubleOrNull()
                        if (parsed == null || parsed <= 0.0) {
                            errorMessage = "Please enter a valid positive number"
                            return@Button
                        }
                        val kg = if (isImperial) UnitConversions.lbsToKg(parsed) else parsed
                        if (kg < 20.0 || kg > 500.0) {
                            errorMessage = "Weight must be between 20.0 kg and 500.0 kg"
                            return@Button
                        }
                        viewModel.logWeight(uiState.selectedDate, kg)
                        showLogDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.WaterCyan)
                ) {
                    Text("Save", color = AppColors.Background)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogDialog = false }) {
                    Text("Cancel", color = AppColors.TextSecondary)
                }
            },
            containerColor = AppColors.Surface
        )
    }

    // 7. Edit Weight Dialog
    entryToEdit?.let { entry ->
        val initVal = if (isImperial) {
            String.format("%.1f", UnitConversions.kgToLbs(entry.weightKg))
        } else {
            String.format("%.1f", entry.weightKg)
        }
        var editInput by remember { mutableStateOf(initVal) }
        var editError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { entryToEdit = null },
            title = { Text("Edit Weight Log", style = AppTypography.Header2) },
            text = {
                Column {
                    Text(
                        text = "Update weight for ${entry.date.format(dateFormatter)} ($unitLabel):",
                        style = AppTypography.Body2,
                        color = AppColors.TextSecondary
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                    OutlinedTextField(
                        value = editInput,
                        onValueChange = {
                            editInput = it
                            editError = null
                        },
                        label = { Text("Weight ($unitLabel)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        singleLine = true,
                        isError = editError != null,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppColors.WaterCyan,
                            unfocusedBorderColor = AppColors.TextSecondary,
                            errorBorderColor = AppColors.ProgressOver
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (editError != null) {
                        Spacer(modifier = Modifier.height(AppSpacing.xs))
                        Text(text = editError ?: "", style = AppTypography.Caption, color = AppColors.ProgressOver)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsed = editInput.toDoubleOrNull()
                        if (parsed == null || parsed <= 0.0) {
                            editError = "Please enter a valid positive number"
                            return@Button
                        }
                        val kg = if (isImperial) UnitConversions.lbsToKg(parsed) else parsed
                        if (kg < 20.0 || kg > 500.0) {
                            editError = "Weight must be between 20.0 kg and 500.0 kg"
                            return@Button
                        }
                        viewModel.logWeight(entry.date, kg)
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

/**
 * Dedicated Canvas-based Cubic Bezier Line Graph matching Section 8 of UI_UX_SPECIFICATION.md.
 */
@Composable
fun WeightLineGraph(
    entries: List<WeightEntry>,
    isImperial: Boolean,
    modifier: Modifier = Modifier
) {
    val shortDateFormatter = remember { DateTimeFormatter.ofPattern("MMM dd") }

    val weights = entries.map {
        if (isImperial) UnitConversions.kgToLbs(it.weightKg) else it.weightKg
    }

    val minW = floor(weights.minOrNull() ?: 50.0) - 1.0
    val maxW = ceil(weights.maxOrNull() ?: 100.0) + 1.0
    val rangeW = (maxW - minW).coerceAtLeast(1.0)

    val graphCyan = Color(0xFF81D4FA)
    val textGray = android.graphics.Color.parseColor("#888888")

    Canvas(modifier = modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
        val width = size.width
        val height = size.height
        val paddingLeft = 40.dp.toPx()
        val paddingBottom = 24.dp.toPx()
        val paddingTop = 12.dp.toPx()
        val paddingRight = 16.dp.toPx()

        val plotWidth = width - paddingLeft - paddingRight
        val plotHeight = height - paddingTop - paddingBottom

        // 1. Draw Y-Axis Step Lines and Labels
        val ySteps = 4
        val stepWeight = rangeW / ySteps
        val textPaint = android.graphics.Paint().apply {
            color = textGray
            textSize = 10.sp.toPx()
            textAlign = android.graphics.Paint.Align.RIGHT
            isAntiAlias = true
        }

        for (i in 0..ySteps) {
            val wVal = minW + i * stepWeight
            val yPos = paddingTop + plotHeight - (i.toFloat() / ySteps.toFloat()) * plotHeight

            // Grid Line
            drawLine(
                color = Color(0xFF333333),
                start = Offset(paddingLeft, yPos),
                end = Offset(width - paddingRight, yPos),
                strokeWidth = 1.dp.toPx()
            )

            // Y Label
            drawContext.canvas.nativeCanvas.drawText(
                String.format("%.0f", wVal),
                paddingLeft - 8.dp.toPx(),
                yPos + 4.dp.toPx(),
                textPaint
            )
        }

        // 2. Map Data Points to Coordinates
        val pointOffsets = mutableListOf<Offset>()
        val n = entries.size
        for (i in entries.indices) {
            val x = if (n > 1) {
                paddingLeft + (i.toFloat() / (n - 1).toFloat()) * plotWidth
            } else {
                paddingLeft + plotWidth / 2f
            }
            val w = weights[i]
            val y = paddingTop + plotHeight - ((w - minW) / rangeW).toFloat() * plotHeight
            pointOffsets.add(Offset(x, y))
        }

        // 3. Construct Cubic Bezier Path
        val linePath = Path()
        val fillPath = Path()

        if (pointOffsets.isNotEmpty()) {
            linePath.moveTo(pointOffsets.first().x, pointOffsets.first().y)
            fillPath.moveTo(pointOffsets.first().x, paddingTop + plotHeight)
            fillPath.lineTo(pointOffsets.first().x, pointOffsets.first().y)

            for (i in 0 until pointOffsets.size - 1) {
                val p0 = pointOffsets[i]
                val p1 = pointOffsets[i + 1]
                val controlX = (p0.x + p1.x) / 2f

                linePath.cubicTo(controlX, p0.y, controlX, p1.y, p1.x, p1.y)
                fillPath.cubicTo(controlX, p0.y, controlX, p1.y, p1.x, p1.y)
            }

            fillPath.lineTo(pointOffsets.last().x, paddingTop + plotHeight)
            fillPath.close()

            // 4. Draw Gradient Fill Area Under Curve
            val gradient = Brush.verticalGradient(
                colors = listOf(graphCyan.copy(alpha = 0.25f), Color.Transparent),
                startY = paddingTop,
                endY = paddingTop + plotHeight
            )
            drawPath(path = fillPath, brush = gradient)

            // 5. Draw Plot Line
            drawPath(
                path = linePath,
                color = graphCyan,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
            )

            // 6. Draw Circular Data Points (White filled with Cyan border)
            val pointRadius = 4.dp.toPx()
            val borderThickness = 2.dp.toPx()

            pointOffsets.forEach { pt ->
                // Outer Cyan Border
                drawCircle(
                    color = graphCyan,
                    radius = pointRadius + borderThickness / 2f,
                    center = pt
                )
                // Inner White Fill
                drawCircle(
                    color = Color.White,
                    radius = pointRadius,
                    center = pt
                )
            }

            // 7. Draw X-Axis Date Labels
            val xTextPaint = android.graphics.Paint().apply {
                color = textGray
                textSize = 9.sp.toPx()
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
            }

            // Draw up to 5 evenly spaced date labels
            val step = (entries.size / 4).coerceAtLeast(1)
            for (i in entries.indices step step) {
                val pt = pointOffsets[i]
                val dateStr = entries[i].date.format(shortDateFormatter)
                drawContext.canvas.nativeCanvas.drawText(
                    dateStr,
                    pt.x,
                    height - 2.dp.toPx(),
                    xTextPaint
                )
            }
            if ((entries.size - 1) % step != 0) {
                val lastPt = pointOffsets.last()
                val lastDateStr = entries.last().date.format(shortDateFormatter)
                drawContext.canvas.nativeCanvas.drawText(
                    lastDateStr,
                    lastPt.x,
                    height - 2.dp.toPx(),
                    xTextPaint
                )
            }
        }
    }
}

private fun formatWeightValue(kg: Double?, isImperial: Boolean): String {
    if (kg == null || kg <= 0.0) return "--"
    val v = if (isImperial) UnitConversions.kgToLbs(kg) else kg
    val unit = if (isImperial) "lb" else "kg"
    return "${String.format("%.1f", v)} $unit"
}

private fun formatWeightDelta(deltaKg: Double?, isImperial: Boolean): String {
    if (deltaKg == null) return "--"
    val v = if (isImperial) UnitConversions.kgToLbs(deltaKg) else deltaKg
    val unit = if (isImperial) "lb" else "kg"
    val arrow = if (v < 0) "↓ " else if (v > 0) "↑ " else ""
    return "$arrow${String.format("%.1f", abs(v))} $unit"
}
