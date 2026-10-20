package com.macrobase.app.feature.water

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
import com.macrobase.app.core.designsystem.Ph
import com.macrobase.app.core.designsystem.components.MbButton
import com.macrobase.app.core.designsystem.components.MbIcon
import com.macrobase.app.core.designsystem.components.MbIconButton
import com.macrobase.app.core.designsystem.components.MbPanel
import com.macrobase.app.core.designsystem.components.MbRowTile
import com.macrobase.app.core.designsystem.components.MbTextField
import com.macrobase.app.core.designsystem.components.rememberIsScrolled
import com.macrobase.app.core.designsystem.components.mbClickable
import com.macrobase.app.core.navigation.ReportContentScrolled
import com.macrobase.app.core.util.hasThousandsSeparator
import com.macrobase.app.core.util.parseDecimalInput
import com.macrobase.app.feature.detail.formatQuantityForInput
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

/**
 * The water tracker. The scaffold draws the pinned "Water" header with its back arrow, so this
 * screen starts with the date ribbon; [onNavigateBack] stays for the navigation graph.
 */
@OptIn(ExperimentalLayoutApi::class)
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

    // The hero shows the number large and the goal (with its unit) small after it
    val totalNumber = if (isImperial) {
        UnitConversions.mlToFlOz(uiState.totalIntakeMl).roundToInt()
    } else {
        uiState.totalIntakeMl.roundToInt()
    }

    val displayGoal = if (isImperial) {
        "${UnitConversions.mlToFlOz(uiState.dailyGoalMl).roundToInt()} fl oz"
    } else {
        "${uiState.dailyGoalMl.roundToInt()} mL"
    }

    // The three quick amounts; the chip's "+" icon stands for the "+" of "+250 mL"
    val quickAddList = if (isImperial) {
        listOf(
            "8 oz" to UnitConversions.flOzToMl(8.0),
            "16 oz" to UnitConversions.flOzToMl(16.0),
            "24 oz" to UnitConversions.flOzToMl(24.0)
        )
    } else {
        listOf(
            "250 mL" to 250.0,
            "500 mL" to 500.0,
            "750 mL" to 750.0
        )
    }

    val scrollState = rememberScrollState()
    ReportContentScrolled(scrollState.rememberIsScrolled())

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = AppSpacing.screenMargin)
    ) {
        // 1. Date ribbon
        TrackerDateRibbon(
            label = uiState.selectedDate.format(dateFormatter),
            onPrevious = { viewModel.onPreviousDay() },
            onNext = { viewModel.onNextDay() }
        )

        // 2. Hero: intake against the goal
        MbPanel(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .semantics(mergeDescendants = true) {},
            contentPadding = PaddingValues(18.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "$totalNumber",
                    style = AppTypography.BigStat,
                    color = AppColors.TextPrimary,
                    modifier = Modifier.alignByBaseline()
                )
                Text(
                    text = " / $displayGoal",
                    style = AppTypography.HeroUnit,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.alignByBaseline()
                )
            }
            Text(
                text = "${uiState.progressPercentage}% of your daily goal",
                style = AppTypography.Body2,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp)
            )
            WaterProgressBar(progress = animatedProgress)
        }

        // 3. Quick add
        TrackerSection(title = "Quick add") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickAddList.forEach { (label, ml) ->
                    QuickAddChip(
                        text = label,
                        icon = Ph.Plus.light,
                        onClickLabel = "Add $label",
                        onClick = { viewModel.addWater(ml) }
                    )
                }
                QuickAddChip(
                    text = "Custom",
                    icon = Ph.PencilSimple.light,
                    onClickLabel = "Add a custom amount",
                    onClick = { showCustomAmountDialog = true }
                )
            }
        }

        // 4. The day's logs
        TrackerSection(title = "Water logs (${uiState.entries.size})") {
            if (uiState.entries.isEmpty()) {
                Text(
                    text = "No water logged for this date. Use Quick Add above to log water.",
                    style = AppTypography.Body2,
                    color = AppColors.TextMuted
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.rowGap)) {
                    uiState.entries.forEach { entry ->
                        val entryTime = entry.loggedAt.atZone(ZoneId.systemDefault()).format(timeFormatter)
                        val entryAmountDisplay = if (isImperial) {
                            "${UnitConversions.mlToFlOz(entry.amountMl).roundToInt()} fl oz"
                        } else {
                            "${entry.amountMl.roundToInt()} mL"
                        }

                        MbRowTile(
                            title = entryAmountDisplay,
                            subtitle = entryTime,
                            icon = Ph.Drop.light,
                            trailing = {
                                MbIconButton(
                                    icon = Ph.PencilSimple.light,
                                    contentDescription = "Edit $entryAmountDisplay at $entryTime",
                                    onClick = { entryToEdit = entry },
                                    iconSize = 19.dp
                                )
                                MbIconButton(
                                    icon = Ph.Trash.light,
                                    contentDescription = "Delete $entryAmountDisplay at $entryTime",
                                    onClick = { viewModel.deleteWaterEntry(entry.id) },
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

    // 5. Custom Amount Dialog
    if (showCustomAmountDialog) {
        var inputAmount by remember { mutableStateOf("") }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        val unitLabel = if (isImperial) "fl oz" else "mL"

        AlertDialog(
            onDismissRequest = { showCustomAmountDialog = false },
            title = { Text(text = "Add Custom Water Amount", style = AppTypography.Header1) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                    Text(
                        text = "Enter water amount in $unitLabel:",
                        style = AppTypography.Body2,
                        color = AppColors.TextSecondary
                    )
                    MbTextField(
                        value = inputAmount,
                        onValueChange = {
                            inputAmount = it
                            errorMessage = null
                        },
                        label = "Amount ($unitLabel)",
                        suffix = unitLabel,
                        errorText = errorMessage,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                MbButton(
                    text = "Add",
                    onClick = {
                        val ml = parseWaterInput(inputAmount, isImperial)
                        val problem = waterInputError(inputAmount, ml, isImperial)
                        if (ml == null || problem != null) {
                            errorMessage = problem
                            return@MbButton
                        }
                        viewModel.addWater(ml)
                        showCustomAmountDialog = false
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { showCustomAmountDialog = false }) {
                    Text("Cancel", color = AppColors.TextSecondary)
                }
            },
            containerColor = AppColors.SheetFill,
            shape = AppShapes.Dialog
        )
    }

    // 6. Edit Entry Dialog
    entryToEdit?.let { entry ->
        val initialAmount = waterPrefillText(entry.amountMl, isImperial)
        var editInput by remember { mutableStateOf(initialAmount) }
        var editErrorMessage by remember { mutableStateOf<String?>(null) }
        val unitLabel = if (isImperial) "fl oz" else "mL"

        AlertDialog(
            onDismissRequest = { entryToEdit = null },
            title = { Text(text = "Edit Water Entry", style = AppTypography.Header1) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                    Text(
                        text = "Update amount in $unitLabel:",
                        style = AppTypography.Body2,
                        color = AppColors.TextSecondary
                    )
                    MbTextField(
                        value = editInput,
                        onValueChange = {
                            editInput = it
                            editErrorMessage = null
                        },
                        label = "Amount ($unitLabel)",
                        suffix = unitLabel,
                        errorText = editErrorMessage,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                MbButton(
                    text = "Save",
                    onClick = {
                        // Saving the dialog unchanged keeps the exact stored amount (BUG-050)
                        val ml = waterFromInput(editInput, entry.amountMl, isImperial)
                        val problem = waterInputError(editInput, ml, isImperial)
                        if (ml == null || problem != null) {
                            editErrorMessage = problem
                            return@MbButton
                        }
                        viewModel.updateWaterEntry(entry.copy(amountMl = ml))
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
private fun TrackerDateRibbon(label: String, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier
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

/** A titled block of the tracker ("Quick add", "Water logs (N)"), 22 dp below the one above. */
@Composable
private fun TrackerSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.sectionGap)) {
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

/** The rounded 8 dp bar in the water colour on the faint track. */
@Composable
private fun WaterProgressBar(progress: Float) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(shape)
            .background(AppColors.BarTrack)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .clip(shape)
                .background(AppColors.WaterCyan)
        )
    }
}

/** A chip-style button (C4 look): it acts at once, so it is a button, not a selectable chip. */
@Composable
private fun QuickAddChip(text: String, @DrawableRes icon: Int, onClickLabel: String, onClick: () -> Unit) {
    val shape = AppShapes.PillButton
    Row(
        modifier = Modifier
            .height(40.dp)
            .mbClickable(onClickLabel = onClickLabel, onClick = onClick)
            .clip(shape)
            .background(Color.Transparent)
            .border(1.dp, AppColors.ChipBorder, shape)
            .padding(horizontal = 15.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MbIcon(icon = icon, contentDescription = null, size = 16.dp, tint = AppColors.ChipInk)
        Text(text = text, style = AppTypography.Chip, color = AppColors.ChipInk, maxLines = 1)
    }
}

/**
 * A water amount for an editable field. Metric shows the exact stored mL; imperial shows fl oz to
 * 2 decimals ("8.45" for 250 mL), as more digits only add noise (BUG-050).
 */
internal fun waterPrefillText(ml: Double, isImperial: Boolean): String =
    if (isImperial) {
        formatQuantityForInput(kotlin.math.round(UnitConversions.mlToFlOz(ml) * 100.0) / 100.0)
    } else {
        formatQuantityForInput(ml)
    }

/**
 * A typed water amount in mL, accepting "8,5" as well as "8.5"; null when it is not a number.
 * "1,500" mL is refused: it means 1500, and reading the comma as a decimal mark would log 1.5 mL.
 */
internal fun parseWaterInput(text: String, isImperial: Boolean): Double? =
    if (!isImperial && hasThousandsSeparator(text)) {
        null
    } else {
        parseDecimalInput(text)?.let { if (isImperial) UnitConversions.flOzToMl(it) else it }
    }

/**
 * [parseWaterInput], except that the pre-filled amount keeps the exact stored millilitres. The
 * same number typed again ("8,45" for "8.45") counts as unchanged.
 */
internal fun waterFromInput(text: String, prefillMl: Double?, isImperial: Boolean): Double? =
    if (prefillMl != null && isWaterPrefill(text, prefillMl, isImperial)) prefillMl else parseWaterInput(text, isImperial)

/** True when [text] is the pre-filled amount, left as shown or typed again. */
private fun isWaterPrefill(text: String, prefillMl: Double, isImperial: Boolean): Boolean {
    val typed = parseDecimalInput(text) ?: return false
    return typed == parseDecimalInput(waterPrefillText(prefillMl, isImperial))
}

/**
 * Why a typed water amount cannot be saved, or null when [ml] can be. The separator message only
 * explains a refused amount: an unchanged pre-fill such as "236.588" mL is kept as it is.
 */
internal fun waterInputError(text: String, ml: Double?, isImperial: Boolean): String? = when {
    ml == null && !isImperial && hasThousandsSeparator(text) -> "Leave out the thousands separator"
    ml == null || ml <= 0.0 -> "Please enter a valid positive number"
    ml > 10000.0 -> "Amount exceeds 10,000 mL maximum threshold"
    else -> null
}
