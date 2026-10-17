package com.macrobase.app.feature.weight

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppShapes
import com.macrobase.app.core.designsystem.AppSpacing
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.Ph
import com.macrobase.app.core.designsystem.components.MbButton
import com.macrobase.app.core.designsystem.components.MbChip
import com.macrobase.app.core.designsystem.components.MbChipRow
import com.macrobase.app.core.designsystem.components.MbDatePickerDialog
import com.macrobase.app.core.designsystem.components.MbIcon
import com.macrobase.app.core.designsystem.components.MbIconButton
import com.macrobase.app.core.designsystem.components.MbPanel
import com.macrobase.app.core.designsystem.components.MbRowTile
import com.macrobase.app.core.designsystem.components.MbTextField
import com.macrobase.app.core.designsystem.components.isScrolled
import com.macrobase.app.core.designsystem.components.mbClickable
import com.macrobase.app.core.navigation.ReportContentScrolled
import com.macrobase.app.core.util.formatDecimalInput
import com.macrobase.app.core.util.parseDecimalInput
import com.macrobase.app.domain.model.UnitConversions
import com.macrobase.app.domain.model.UnitSystem
import com.macrobase.app.domain.model.WeightEntry
import com.macrobase.app.domain.model.windowStart
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
import java.util.Locale
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

/**
 * The days [this] covers, ending [today]. "Last 30 days" is today and the 29 before it, and the
 * month and year ranges leave out the extra day at the start (BUG-033).
 */
fun WeightTimeInterval.dateRange(today: LocalDate, customFrom: LocalDate, customTo: LocalDate): Pair<LocalDate, LocalDate> =
    when (this) {
        WeightTimeInterval.LAST_30_DAYS -> windowStart(today, 30) to today
        WeightTimeInterval.LAST_3_MONTHS -> today.minusMonths(3).plusDays(1) to today
        WeightTimeInterval.LAST_6_MONTHS -> today.minusMonths(6).plusDays(1) to today
        WeightTimeInterval.ONE_YEAR -> today.minusYears(1).plusDays(1) to today
        WeightTimeInterval.ALL_TIME -> LocalDate.of(2000, 1, 1) to today
        WeightTimeInterval.CUSTOM -> (if (customFrom.isAfter(customTo)) customTo else customFrom) to customTo
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
    val fromDate: LocalDate = windowStart(LocalDate.now(), 30),
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
    private val preferencesRepository: PreferencesRepository,
    private val clock: java.time.Clock = com.macrobase.app.core.util.DeviceClock
) : ViewModel() {

    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    private val _selectedInterval = MutableStateFlow(WeightTimeInterval.LAST_30_DAYS)
    val selectedInterval: StateFlow<WeightTimeInterval> = _selectedInterval.asStateFlow()

    private val _customFromDate = MutableStateFlow(windowStart(LocalDate.now(clock), 30))
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
    ): Pair<LocalDate, LocalDate> = interval.dateRange(LocalDate.now(clock), customFrom, customTo)

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

/**
 * An interval's name for its chip, in sentence case ("1 Year" shows as "1 year"). The enum's
 * label stays as it is.
 */
internal fun weightIntervalChipLabel(interval: WeightTimeInterval): String =
    interval.label.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }

/** Which end of the custom range the date picker sets. */
private enum class RangeEnd { FROM, TO }

/**
 * The weight tracker. The scaffold draws the pinned "Weight" header with its back arrow, so this
 * screen starts with the date ribbon; [onNavigateBack] stays for the navigation graph.
 */
@Composable
fun WeightScreen(
    viewModel: WeightViewModel,
    onNavigateBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    openLogOnStart: Boolean = false
) {
    val uiState by viewModel.uiState.collectAsState()
    val isImperial = uiState.unitSystem == UnitSystem.IMPERIAL
    val unitLabel = if (isImperial) "lb" else "kg"

    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEEE, MM/dd") }
    val shortDateFormatter = remember { DateTimeFormatter.ofPattern("MMM dd") }

    var showLogDialog by remember { mutableStateOf(false) }

    // "Log weight" from Home or the "+" sheet opens the dialog once; coming back to this screen
    // later must not open it again
    var startLogHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(openLogOnStart) {
        if (openLogOnStart && !startLogHandled) {
            startLogHandled = true
            showLogDialog = true
        }
    }
    var entryToEdit by remember { mutableStateOf<WeightEntry?>(null) }
    var rangeEndToPick by remember { mutableStateOf<RangeEnd?>(null) }

    val scrollState = rememberScrollState()
    ReportContentScrolled(scrollState.isScrolled)
    val margin = Modifier.padding(horizontal = AppSpacing.screenMargin)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        // 1. Date ribbon
        TrackerDateRibbon(
            label = uiState.selectedDate.format(dateFormatter),
            onPrevious = { viewModel.onPreviousDay() },
            onNext = { viewModel.onNextDay() },
            modifier = margin
        )

        // 2. Interval chips, scrolling sideways from edge to edge
        MbChipRow(
            modifier = Modifier.padding(top = 4.dp),
            contentPadding = PaddingValues(horizontal = AppSpacing.screenMargin)
        ) {
            WeightTimeInterval.entries.forEach { interval ->
                MbChip(
                    text = weightIntervalChipLabel(interval),
                    selected = uiState.selectedInterval == interval,
                    onClick = { viewModel.onSelectInterval(interval) }
                )
            }
        }

        // 3. From / To: picking either day sets a custom range
        Row(
            modifier = margin
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RangeDateButton(
                text = "From ${uiState.fromDate.format(shortDateFormatter)}",
                highlighted = uiState.selectedInterval == WeightTimeInterval.CUSTOM,
                onClick = { rangeEndToPick = RangeEnd.FROM },
                modifier = Modifier.weight(1f)
            )
            RangeDateButton(
                text = "To ${uiState.toDate.format(shortDateFormatter)}",
                highlighted = uiState.selectedInterval == WeightTimeInterval.CUSTOM,
                onClick = { rangeEndToPick = RangeEnd.TO },
                modifier = Modifier.weight(1f)
            )
        }

        // 4. Start / Current / Change
        val startDisplay = formatWeightValue(uiState.metrics.startWeightKg, isImperial)
        val currentDisplay = formatWeightValue(uiState.metrics.currentWeightKg, isImperial)
        val deltaVal = uiState.metrics.deltaWeightKg
        val deltaDisplay = formatWeightDelta(deltaVal, isImperial)
        val percentDisplay = uiState.metrics.percentageChange?.let { "(${String.format(Locale.US, "%.1f", abs(it))}%)" } ?: ""

        val changeColor = if (deltaVal != null) {
            if (deltaVal <= 0.0) AppColors.Success else AppColors.Error
        } else AppColors.TextSecondary

        Row(
            modifier = margin
                .fillMaxWidth()
                .padding(top = 14.dp)
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WeightStatTile(label = "Start", value = startDisplay, modifier = Modifier.weight(1f).fillMaxHeight())
            WeightStatTile(label = "Current", value = currentDisplay, modifier = Modifier.weight(1f).fillMaxHeight())
            WeightStatTile(
                label = "Change $percentDisplay".trimEnd(),
                value = deltaDisplay,
                valueColor = changeColor,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }

        // 5. Graph or empty state
        MbPanel(
            modifier = margin
                .fillMaxWidth()
                .padding(top = 8.dp),
            contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 6.dp)
        ) {
            if (uiState.hasSufficientDataForGraph) {
                WeightLineGraph(
                    entries = uiState.entries,
                    isImperial = isImperial,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                )
            } else {
                Text(
                    text = "Log at least 2 weight entries to generate progress graph",
                    style = AppTypography.Body2,
                    color = AppColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 30.dp)
                )
            }
        }

        // 6. Log or edit the selected day
        val hasSelectedDateWeight = uiState.selectedDateWeight != null
        MbButton(
            text = if (hasSelectedDateWeight) "Edit Today" else "Log Weight",
            onClick = { showLogDialog = true },
            icon = if (hasSelectedDateWeight) Ph.PencilSimple.regular else Ph.Plus.regular,
            modifier = margin
                .fillMaxWidth()
                .padding(top = 14.dp)
        )

        // 7. Weight Entries List
        TrackerSection(title = "Weight logs (${uiState.entries.size})", modifier = margin) {
            if (uiState.entries.isEmpty()) {
                Text(text = "No weight logs for this interval.", style = AppTypography.Body2, color = AppColors.TextMuted)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.rowGap)) {
                    uiState.entries.reversed().forEach { entry ->
                        val displayWeight = formatWeightValue(entry.weightKg, isImperial)
                        val displayDate = entry.date.format(dateFormatter)

                        MbRowTile(
                            title = displayWeight,
                            subtitle = displayDate,
                            icon = Ph.Scales.light,
                            trailing = {
                                MbIconButton(
                                    icon = Ph.PencilSimple.light,
                                    contentDescription = "Edit $displayWeight on $displayDate",
                                    onClick = { entryToEdit = entry },
                                    iconSize = 19.dp
                                )
                                MbIconButton(
                                    icon = Ph.Trash.light,
                                    contentDescription = "Delete $displayWeight on $displayDate",
                                    onClick = { viewModel.deleteWeight(entry.date) },
                                    iconSize = 19.dp,
                                    tint = AppColors.Error
                                )
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.xxl))
    }

    // From / To date picker: same days allowed as before; the picked day sets the custom range
    rangeEndToPick?.let { end ->
        MbDatePickerDialog(
            initialDate = if (end == RangeEnd.FROM) uiState.fromDate else uiState.toDate,
            onDateSelected = { picked ->
                if (end == RangeEnd.FROM) {
                    viewModel.onSetCustomRange(picked, uiState.toDate)
                } else {
                    viewModel.onSetCustomRange(uiState.fromDate, picked)
                }
            },
            onDismiss = { rangeEndToPick = null }
        )
    }

    // 8. Log Weight Dialog
    if (showLogDialog) {
        val prefillKg = remember { uiState.selectedDateWeight?.weightKg }
        var inputWeight by remember {
            mutableStateOf(prefillKg?.let { weightPrefillText(it, isImperial) } ?: "")
        }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showLogDialog = false },
            title = { Text("Log Body Weight", style = AppTypography.Header1) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                    Text(
                        text = "Enter weight for ${uiState.selectedDate.format(dateFormatter)} ($unitLabel):",
                        style = AppTypography.Body2,
                        color = AppColors.TextSecondary
                    )
                    MbTextField(
                        value = inputWeight,
                        onValueChange = {
                            inputWeight = it
                            errorMessage = null
                        },
                        label = "Weight ($unitLabel)",
                        suffix = unitLabel,
                        errorText = errorMessage,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                MbButton(
                    text = "Save",
                    onClick = {
                        val kg = weightFromInput(inputWeight, prefillKg, isImperial)
                        if (kg == null || kg <= 0.0) {
                            errorMessage = "Please enter a valid positive number"
                            return@MbButton
                        }
                        if (kg < 20.0 || kg > 500.0) {
                            errorMessage = "Weight must be between 20.0 kg and 500.0 kg"
                            return@MbButton
                        }
                        viewModel.logWeight(uiState.selectedDate, kg)
                        showLogDialog = false
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { showLogDialog = false }) {
                    Text("Cancel", color = AppColors.TextSecondary)
                }
            },
            containerColor = AppColors.SheetFill,
            shape = AppShapes.Dialog
        )
    }

    // 9. Edit Weight Dialog
    entryToEdit?.let { entry ->
        val initVal = weightPrefillText(entry.weightKg, isImperial)
        var editInput by remember { mutableStateOf(initVal) }
        var editError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { entryToEdit = null },
            title = { Text("Edit Weight Log", style = AppTypography.Header1) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                    Text(
                        text = "Update weight for ${entry.date.format(dateFormatter)} ($unitLabel):",
                        style = AppTypography.Body2,
                        color = AppColors.TextSecondary
                    )
                    MbTextField(
                        value = editInput,
                        onValueChange = {
                            editInput = it
                            editError = null
                        },
                        label = "Weight ($unitLabel)",
                        suffix = unitLabel,
                        errorText = editError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                MbButton(
                    text = "Save",
                    onClick = {
                        val kg = weightFromInput(editInput, entry.weightKg, isImperial)
                        if (kg == null || kg <= 0.0) {
                            editError = "Please enter a valid positive number"
                            return@MbButton
                        }
                        if (kg < 20.0 || kg > 500.0) {
                            editError = "Weight must be between 20.0 kg and 500.0 kg"
                            return@MbButton
                        }
                        viewModel.logWeight(entry.date, kg)
                        entryToEdit = null
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { entryToEdit = null }) {
                    Text("Cancel", color = AppColors.TextSecondary)
                }
            },
            containerColor = AppColors.SheetFill,
            shape = AppShapes.Dialog
        )
    }
}

/** Previous day / the day / next day, as the app had it, with round caret buttons. */
@Composable
private fun TrackerDateRibbon(label: String, onPrevious: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MbIconButton(
            icon = Ph.CaretLeft.light,
            contentDescription = "Previous Day",
            onClick = onPrevious,
            tint = AppColors.TextPrimary
        )
        Text(
            text = label,
            style = AppTypography.Header3.copy(fontSize = 16.sp),
            color = AppColors.TextPrimary
        )
        MbIconButton(
            icon = Ph.CaretRight.light,
            contentDescription = "Next Day",
            onClick = onNext,
            tint = AppColors.TextPrimary
        )
    }
}

/** A titled block of the tracker ("Weight logs (N)"), 22 dp below the one above. */
@Composable
private fun TrackerSection(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = modifier.fillMaxWidth().padding(top = AppSpacing.sectionGap)) {
        Text(
            text = title,
            style = AppTypography.Header2,
            color = AppColors.TextPrimary,
            modifier = Modifier
                .padding(bottom = 10.dp)
                .semantics { heading() }
        )
        content()
    }
}

/** One of the Start / Current / Change tiles: a small panel with a caption and the value. */
@Composable
private fun WeightStatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = AppColors.TextPrimary
) {
    MbPanel(
        modifier = modifier.semantics(mergeDescendants = true) {},
        shape = AppShapes.CardSmall,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = AppTypography.Caption,
            color = AppColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(text = value, style = AppTypography.ValueMd, color = valueColor)
    }
}

/** The From or To day of the range, as a field-like button that opens the date picker. */
@Composable
private fun RangeDateButton(text: String, highlighted: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = AppShapes.InputBox
    Row(
        modifier = modifier
            .height(44.dp)
            .mbClickable(pressedScale = 0.98f, onClick = onClick)
            .clip(shape)
            .background(AppColors.FieldFill)
            .border(1.dp, if (highlighted) AppColors.ChipOnBorder else AppColors.FieldBorder, shape)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MbIcon(icon = Ph.CalendarBlank.light, contentDescription = null, size = 16.dp, tint = AppColors.TextSecondary)
        Text(
            text = text,
            style = AppTypography.Body2,
            color = AppColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * The weight line graph (design.txt C18), also shown by Statistics: a smooth line with the area
 * below it, the latest point as a dot ringed in the canvas colour, gridlines with their weights and
 * dates along the bottom. A tap selects the nearest point and shows its date and weight in a chip.
 */
@Composable
fun WeightLineGraph(
    entries: List<WeightEntry>,
    isImperial: Boolean,
    modifier: Modifier = Modifier
) {
    val shortDateFormatter = remember { DateTimeFormatter.ofPattern("MMM dd") }
    val tooltipDateFormatter = remember { DateTimeFormatter.ofPattern("MMM d") }

    val weights = remember(entries, isImperial) {
        entries.map {
            if (isImperial) UnitConversions.kgToLbs(it.weightKg) else it.weightKg
        }
    }

    val minW = remember(weights) { floor(weights.minOrNull() ?: 50.0) - 1.0 }
    val maxW = remember(weights) { ceil(weights.maxOrNull() ?: 100.0) + 1.0 }
    val rangeW = remember(minW, maxW) { (maxW - minW).coerceAtLeast(1.0) }

    val textMeasurer = rememberTextMeasurer()
    val axisStyle = remember { AppTypography.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp, color = AppColors.TextMuted) }
    val tooltipDateStyle = remember { AppTypography.Caption.copy(fontSize = 10.sp, lineHeight = 13.sp, color = AppColors.TextSecondary) }
    val tooltipWeightStyle = remember { AppTypography.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp, color = AppColors.TextPrimary) }

    val graphDescription = remember(entries, isImperial) {
        if (entries.isEmpty()) {
            "Weight graph"
        } else {
            "Weight from ${formatWeightValue(entries.first().weightKg, isImperial)} to ${formatWeightValue(entries.last().weightKg, isImperial)}"
        }
    }

    // Interactive tooltip state: resets when entries or date interval changes
    var selectedIndex by remember(entries) { mutableStateOf<Int?>(null) }
    var currentPointOffsets by remember { mutableStateOf<List<Offset>>(emptyList()) }

    Canvas(
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .semantics { contentDescription = graphDescription }
            .pointerInput(entries, isImperial) {
                detectTapGestures { tapOffset ->
                    val points = currentPointOffsets
                    if (points.isNotEmpty()) {
                        val hitRadius = 32.dp.toPx()
                        val nearest = WeightGraphHelper.findNearestPointIndex(tapOffset, points, hitRadius)
                        selectedIndex = nearest
                    } else {
                        selectedIndex = null
                    }
                }
            }
    ) {
        val width = size.width
        val height = size.height
        val paddingLeft = 40.dp.toPx()
        val paddingBottom = 24.dp.toPx()
        val paddingTop = 12.dp.toPx()
        val paddingRight = 16.dp.toPx()

        val plotHeight = height - paddingTop - paddingBottom
        val plotBottom = paddingTop + plotHeight

        // 1. Gridlines with their weights, right-aligned left of the plot
        val ySteps = 4
        val stepWeight = rangeW / ySteps
        for (i in 0..ySteps) {
            val wVal = minW + i * stepWeight
            val yPos = paddingTop + plotHeight - (i.toFloat() / ySteps.toFloat()) * plotHeight

            drawLine(
                color = AppColors.ChartGrid,
                start = Offset(paddingLeft, yPos),
                end = Offset(width - paddingRight, yPos),
                strokeWidth = 1.dp.toPx()
            )

            val label = textMeasurer.measure(String.format(Locale.US, "%.0f", wVal), axisStyle)
            drawText(
                textLayoutResult = label,
                topLeft = Offset(paddingLeft - 8.dp.toPx() - label.size.width, yPos - label.size.height / 2f)
            )
        }

        // 2. Map Data Points to Coordinates
        val pointOffsets = WeightGraphHelper.calculatePointOffsets(
            entries = entries,
            weights = weights,
            minW = minW,
            rangeW = rangeW,
            width = width,
            height = height,
            paddingLeft = paddingLeft,
            paddingRight = paddingRight,
            paddingTop = paddingTop,
            paddingBottom = paddingBottom
        )
        currentPointOffsets = pointOffsets

        if (pointOffsets.isNotEmpty()) {
            // 3. Construct Cubic Bezier Path
            val linePath = Path()
            val fillPath = Path()

            linePath.moveTo(pointOffsets.first().x, pointOffsets.first().y)
            fillPath.moveTo(pointOffsets.first().x, plotBottom)
            fillPath.lineTo(pointOffsets.first().x, pointOffsets.first().y)

            for (i in 0 until pointOffsets.size - 1) {
                val p0 = pointOffsets[i]
                val p1 = pointOffsets[i + 1]
                val controlX = (p0.x + p1.x) / 2f

                linePath.cubicTo(controlX, p0.y, controlX, p1.y, p1.x, p1.y)
                fillPath.cubicTo(controlX, p0.y, controlX, p1.y, p1.x, p1.y)
            }

            fillPath.lineTo(pointOffsets.last().x, plotBottom)
            fillPath.close()

            val activeIdx = selectedIndex
            val selected = activeIdx?.takeIf { it in entries.indices && it in pointOffsets.indices }

            // 4. Area under the curve, the tapped day's dashed guide, then the line
            drawPath(path = fillPath, color = AppColors.ChartArea)

            if (selected != null) {
                val x = pointOffsets[selected].x
                drawLine(
                    color = AppColors.ChartLine.copy(alpha = 0.55f),
                    start = Offset(x, paddingTop),
                    end = Offset(x, plotBottom),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                )
            }

            drawPath(
                path = linePath,
                color = AppColors.ChartLine,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // 5. The latest point: a dot in the line colour with a 3 dp ring in the canvas colour
            val lastPoint = pointOffsets.last()
            drawCircle(color = AppColors.Background, radius = 7.dp.toPx(), center = lastPoint)
            drawCircle(color = AppColors.ChartLine, radius = 4.dp.toPx(), center = lastPoint)

            // The tapped point, when it is not the latest: a hollow dot
            if (selected != null && selected != pointOffsets.lastIndex) {
                val pt = pointOffsets[selected]
                drawCircle(color = AppColors.Background, radius = 5.dp.toPx(), center = pt)
                drawCircle(color = AppColors.ChartLine, radius = 4.dp.toPx(), center = pt, style = Stroke(width = 2.dp.toPx()))
            }

            // 6. X-axis dates: up to 5 evenly spaced, kept inside the canvas
            fun drawDateLabel(index: Int) {
                val label = textMeasurer.measure(entries[index].date.format(shortDateFormatter), axisStyle)
                val maxLeft = (width - label.size.width).coerceAtLeast(0f)
                val left = (pointOffsets[index].x - label.size.width / 2f).coerceIn(0f, maxLeft)
                drawText(textLayoutResult = label, topLeft = Offset(left, height - label.size.height))
            }

            val step = (entries.size / 4).coerceAtLeast(1)
            for (i in entries.indices step step) {
                drawDateLabel(i)
            }
            if ((entries.size - 1) % step != 0) {
                drawDateLabel(entries.lastIndex)
            }

            // 7. Tooltip for the tapped point: a small rounded dark chip
            if (selected != null) {
                val selPoint = pointOffsets[selected]
                val selEntry = entries[selected]

                val dateLayout = textMeasurer.measure(selEntry.date.format(tooltipDateFormatter), tooltipDateStyle)
                val weightLayout = textMeasurer.measure(formatWeightValue(selEntry.weightKg, isImperial), tooltipWeightStyle)

                val padX = 10.dp.toPx()
                val padY = 6.dp.toPx()
                val tooltipWidth = maxOf(dateLayout.size.width, weightLayout.size.width) + 2 * padX
                val tooltipHeight = dateLayout.size.height + weightLayout.size.height + 2 * padY
                val cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx())

                val bounds = WeightGraphHelper.calculateTooltipBounds(
                    point = selPoint,
                    tooltipWidth = tooltipWidth,
                    tooltipHeight = tooltipHeight,
                    canvasWidth = width,
                    canvasHeight = height,
                    marginPx = 4.dp.toPx()
                )

                drawRoundRect(
                    color = AppColors.SheetFill,
                    topLeft = Offset(bounds.leftX, bounds.topY),
                    size = Size(bounds.width, bounds.height),
                    cornerRadius = cornerRadius
                )
                drawRoundRect(
                    color = AppColors.Edge2,
                    topLeft = Offset(bounds.leftX, bounds.topY),
                    size = Size(bounds.width, bounds.height),
                    cornerRadius = cornerRadius,
                    style = Stroke(width = 1.dp.toPx())
                )

                val centerX = bounds.leftX + bounds.width / 2f
                drawText(
                    textLayoutResult = dateLayout,
                    topLeft = Offset(centerX - dateLayout.size.width / 2f, bounds.topY + padY)
                )
                drawText(
                    textLayoutResult = weightLayout,
                    topLeft = Offset(centerX - weightLayout.size.width / 2f, bounds.topY + padY + dateLayout.size.height)
                )
            }
        }
    }
}

internal object WeightGraphHelper {
    fun calculatePointOffsets(
        entries: List<WeightEntry>,
        weights: List<Double>,
        minW: Double,
        rangeW: Double,
        width: Float,
        height: Float,
        paddingLeft: Float,
        paddingRight: Float,
        paddingTop: Float,
        paddingBottom: Float
    ): List<Offset> {
        if (entries.isEmpty()) return emptyList()
        val plotWidth = width - paddingLeft - paddingRight
        val plotHeight = height - paddingTop - paddingBottom
        val n = entries.size
        return entries.indices.map { i ->
            val x = if (n > 1) {
                paddingLeft + (i.toFloat() / (n - 1).toFloat()) * plotWidth
            } else {
                paddingLeft + plotWidth / 2f
            }
            val w = weights[i]
            val y = paddingTop + plotHeight - ((w - minW) / rangeW).toFloat() * plotHeight
            Offset(x, y)
        }
    }

    fun findNearestPointIndex(
        tapOffset: Offset,
        pointOffsets: List<Offset>,
        hitThresholdPx: Float
    ): Int? {
        if (pointOffsets.isEmpty()) return null
        var bestIndex = -1
        var minDistance = Float.MAX_VALUE
        for (i in pointOffsets.indices) {
            val dist = (pointOffsets[i] - tapOffset).getDistance()
            if (dist < minDistance && dist <= hitThresholdPx) {
                minDistance = dist
                bestIndex = i
            }
        }
        return if (bestIndex >= 0) bestIndex else null
    }

    data class TooltipBounds(
        val leftX: Float,
        val topY: Float,
        val width: Float,
        val height: Float
    )

    fun calculateTooltipBounds(
        point: Offset,
        tooltipWidth: Float,
        tooltipHeight: Float,
        canvasWidth: Float,
        canvasHeight: Float,
        marginPx: Float = 4f
    ): TooltipBounds {
        var topY = point.y - tooltipHeight - 8f
        if (topY < marginPx) {
            topY = point.y + 10f
        }
        if (topY + tooltipHeight > canvasHeight - marginPx) {
            topY = canvasHeight - marginPx - tooltipHeight
        }

        var leftX = point.x - tooltipWidth / 2f
        if (leftX < marginPx) {
            leftX = marginPx
        }
        if (leftX + tooltipWidth > canvasWidth - marginPx) {
            leftX = canvasWidth - marginPx - tooltipWidth
        }

        return TooltipBounds(
            leftX = leftX,
            topY = topY,
            width = tooltipWidth,
            height = tooltipHeight
        )
    }

    fun formatTooltipDate(date: LocalDate): String {
        return date.format(DateTimeFormatter.ofPattern("MMM d"))
    }
}

internal fun formatWeightValue(kg: Double?, isImperial: Boolean): String {
    if (kg == null || kg <= 0.0) return "--"
    val v = if (isImperial) UnitConversions.kgToLbs(kg) else kg
    val unit = if (isImperial) "lb" else "kg"
    return "${String.format(Locale.US, "%.1f", v)} $unit"
}

internal fun formatWeightDelta(deltaKg: Double?, isImperial: Boolean): String {
    if (deltaKg == null) return "--"
    val v = if (isImperial) UnitConversions.kgToLbs(deltaKg) else deltaKg
    val unit = if (isImperial) "lb" else "kg"
    val arrow = if (v < 0) "\u2193 " else if (v > 0) "\u2191 " else ""
    return "$arrow${String.format(Locale.US, "%.1f", abs(v))} $unit"
}

/** A weight for an editable field: always with "." (BUG-027), so [parseWeightInput] reads it back. */
internal fun weightPrefillText(kg: Double, isImperial: Boolean): String =
    formatDecimalInput(if (isImperial) UnitConversions.kgToLbs(kg) else kg)

/** A typed weight in kilograms, accepting "72,5" as well as "72.5"; null when it is not a number. */
internal fun parseWeightInput(text: String, isImperial: Boolean): Double? =
    parseDecimalInput(text)?.let { if (isImperial) UnitConversions.lbsToKg(it) else it }

/** [parseWeightInput], except that an untouched pre-fill keeps the exact stored kilograms. */
internal fun weightFromInput(text: String, prefillKg: Double?, isImperial: Boolean): Double? =
    if (prefillKg != null && text == weightPrefillText(prefillKg, isImperial)) prefillKg else parseWeightInput(text, isImperial)
