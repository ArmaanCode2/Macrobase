package com.macrobase.app.feature.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.macrobase.app.core.config.CalendarPerformanceConfig
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppShapes
import com.macrobase.app.core.designsystem.AppSpacing
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.Ph
import com.macrobase.app.core.designsystem.components.MbButton
import com.macrobase.app.core.designsystem.components.MbButtonKind
import com.macrobase.app.core.designsystem.components.MbChip
import com.macrobase.app.core.designsystem.components.MbChipRow
import com.macrobase.app.core.designsystem.components.MbDatePickerDialog
import com.macrobase.app.core.designsystem.components.MbIcon
import com.macrobase.app.core.designsystem.components.MbPanel
import com.macrobase.app.core.designsystem.components.MbSheet
import com.macrobase.app.core.designsystem.components.isScrolled
import com.macrobase.app.core.designsystem.components.mbClickable
import com.macrobase.app.core.navigation.DeclareDockViews
import com.macrobase.app.core.navigation.LocalDockInset
import com.macrobase.app.core.navigation.ReportContentScrolled
import com.macrobase.app.core.util.DeviceClock
import com.macrobase.app.domain.model.ConsistencyStatistics
import com.macrobase.app.domain.model.HeatmapCellData
import com.macrobase.app.domain.model.MacroAveragesStatistics
import com.macrobase.app.domain.model.UnitConversions
import com.macrobase.app.domain.model.UnitSystem
import com.macrobase.app.domain.model.WeightStatistics
import com.macrobase.app.domain.model.rank.RankProfile
import com.macrobase.app.domain.model.windowStart
import com.macrobase.app.domain.repository.PreferencesRepository
import com.macrobase.app.domain.usecase.GetStatisticsUseCase
import com.macrobase.app.domain.usecase.rank.GetRankProfileUseCase
import com.macrobase.app.feature.calendar.CalendarViewModel
import com.macrobase.app.feature.calendar.FoodLoggingCalendar
import com.macrobase.app.feature.weight.WeightGraphHelper
import com.macrobase.app.feature.weight.WeightLineGraph
import com.macrobase.app.feature.weight.weightIntervalChipLabel
import com.macrobase.app.feature.weight.WeightTimeInterval
import com.macrobase.app.feature.weight.dateRange
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ConsistencyInterval(val label: String) {
    DAYS_30("30 days"),
    DAYS_90("90 days"),
    MONTHS_6("6 months"),
    YEAR_1("1 year");

    /**
     * The first day of the window ending [today]. "6 months" and "1 year" are calendar months
     * and years, the same span as on the weight tab, not 180 and 365 days.
     */
    fun startDate(today: LocalDate): LocalDate = when (this) {
        DAYS_30 -> windowStart(today, 30)
        DAYS_90 -> windowStart(today, 90)
        MONTHS_6 -> WeightTimeInterval.LAST_6_MONTHS.dateRange(today, today, today).first
        YEAR_1 -> WeightTimeInterval.ONE_YEAR.dateRange(today, today, today).first
    }
}

enum class MacroInterval(val label: String, val days: Long) {
    DAYS_7("7-day", 7),
    DAYS_30("30-day", 30),
    DAYS_90("90-day", 90)
}

data class StatisticsUiState(
    val selectedTab: Int = 0,
    // Weight Section
    val weightInterval: WeightTimeInterval = WeightTimeInterval.LAST_30_DAYS,
    val weightFromDate: LocalDate = windowStart(LocalDate.now(), 30),
    val weightToDate: LocalDate = LocalDate.now(),
    val weightStats: WeightStatistics? = null,
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    // Consistency Section
    val consistencyInterval: ConsistencyInterval = ConsistencyInterval.DAYS_30,
    val consistencyStats: ConsistencyStatistics? = null,
    // Macro Averages Section
    val macroInterval: MacroInterval = MacroInterval.DAYS_30,
    val macroStats: MacroAveragesStatistics? = null,
    val isLoading: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsViewModel(
    private val getStatisticsUseCase: GetStatisticsUseCase,
    private val preferencesRepository: PreferencesRepository,
    private val clock: Clock = DeviceClock,
    /** Null leaves the Rank History tab empty. */
    getRankProfileUseCase: GetRankProfileUseCase? = null
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    private val _weightInterval = MutableStateFlow(WeightTimeInterval.LAST_30_DAYS)
    val weightInterval: StateFlow<WeightTimeInterval> = _weightInterval.asStateFlow()

    private val _weightCustomFrom = MutableStateFlow(windowStart(LocalDate.now(clock), 30))
    private val _weightCustomTo = MutableStateFlow(LocalDate.now(clock))

    private val _consistencyInterval = MutableStateFlow(ConsistencyInterval.DAYS_30)
    val consistencyInterval: StateFlow<ConsistencyInterval> = _consistencyInterval.asStateFlow()

    private val _macroInterval = MutableStateFlow(MacroInterval.DAYS_30)
    val macroInterval: StateFlow<MacroInterval> = _macroInterval.asStateFlow()

    val uiState: StateFlow<StatisticsUiState> = combine(
        combine(_selectedTab, _weightInterval, _weightCustomFrom) { tab, wInt, wFrom -> Triple(tab, wInt, wFrom) },
        combine(_weightCustomTo, _consistencyInterval, _macroInterval) { wTo, cInt, mInt -> Triple(wTo, cInt, mInt) }
    ) { (tab, wInt, wFrom), (wTo, cInt, mInt) ->
        val (weightFrom, weightTo) = calculateWeightDateRange(wInt, wFrom, wTo)
        val today = LocalDate.now(clock)
        // "30 Days" is today and the 29 before it, not 31 days (BUG-033)
        val cFrom = cInt.startDate(today)
        // Averages cover the days that have ended, so a half-eaten today does not pull them
        // down every morning
        val lastEndedDay = today.minusDays(1)
        val mFrom = windowStart(lastEndedDay, mInt.days)

        StatsParams(tab, wInt, weightFrom, weightTo, cInt, cFrom, today, mInt, mFrom, lastEndedDay)
    }.flatMapLatest { p ->
        combine(
            getStatisticsUseCase.observeWeightStatistics(p.weightFrom, p.weightTo),
            getStatisticsUseCase.observeNutritionConsistency(p.cFrom, p.cTo),
            getStatisticsUseCase.observeMacroAverages(p.mFrom, p.mTo),
            preferencesRepository.observePreferences()
        ) { weightStats, consistencyStats, macroStats, prefs ->
            StatisticsUiState(
                selectedTab = p.tab,
                weightInterval = p.wInt,
                weightFromDate = p.weightFrom,
                weightToDate = p.weightTo,
                weightStats = weightStats,
                unitSystem = prefs.unitSystem,
                consistencyInterval = p.cInt,
                consistencyStats = consistencyStats,
                macroInterval = p.mInt,
                macroStats = macroStats,
                isLoading = false
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = StatisticsUiState()
    )

    private data class StatsParams(
        val tab: Int,
        val wInt: WeightTimeInterval,
        val weightFrom: LocalDate,
        val weightTo: LocalDate,
        val cInt: ConsistencyInterval,
        val cFrom: LocalDate,
        val cTo: LocalDate,
        val mInt: MacroInterval,
        val mFrom: LocalDate,
        val mTo: LocalDate
    )

    private fun calculateWeightDateRange(
        interval: WeightTimeInterval,
        customFrom: LocalDate,
        customTo: LocalDate
    ): Pair<LocalDate, LocalDate> = interval.dateRange(LocalDate.now(clock), customFrom, customTo)

    private val _rankInterval = MutableStateFlow(RankHistoryInterval.DAYS_30)

    /**
     * The Rank History tab. Building the rank replays the whole diary, so it runs only while the
     * tab collects this.
     */
    val rankHistory: StateFlow<RankHistoryUiState> = combine(
        getRankProfileUseCase?.invoke() ?: flowOf<RankProfile?>(null),
        _rankInterval
    ) { profile, interval ->
        if (profile == null) {
            RankHistoryUiState(interval = interval, isLoading = false)
        } else {
            rankHistoryState(profile, interval, LocalDate.now(clock))
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = RankHistoryUiState()
    )

    fun onSelectRankInterval(interval: RankHistoryInterval) {
        _rankInterval.value = interval
    }

    fun onSelectTab(index: Int) {
        _selectedTab.value = index
    }

    fun onSelectWeightInterval(interval: WeightTimeInterval) {
        _weightInterval.value = interval
    }

    fun onSetWeightCustomRange(from: LocalDate, to: LocalDate) {
        _weightCustomFrom.value = from
        _weightCustomTo.value = to
        _weightInterval.value = WeightTimeInterval.CUSTOM
    }

    fun onSelectConsistencyInterval(interval: ConsistencyInterval) {
        _consistencyInterval.value = interval
    }

    fun onSelectMacroInterval(interval: MacroInterval) {
        _macroInterval.value = interval
    }
}

/** The five views in the dock's switch, in the app's order (design.txt C8). */
internal val StatisticsViews = listOf("Calendar", "Weight", "Consistency", "Averages", "Rank history")

@Composable
fun StatisticsScreen(
    viewModel: StatisticsViewModel,
    calendarViewModel: CalendarViewModel = koinViewModel(),
    onDateClick: (LocalDate) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Collected only while visible, so coming back after midnight reads the new day (BUG-033)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val calendarUiState by calendarViewModel.uiState.collectAsStateWithLifecycle()

    // The views sit in the dock's switch; the chosen one stays in the ViewModel
    DeclareDockViews(
        items = StatisticsViews,
        selectedIndex = uiState.selectedTab,
        onSelect = { viewModel.onSelectTab(it) }
    )

    // Each view scrolls on its own, under the header and behind the dock, and ends clear of the dock
    Box(modifier = modifier.fillMaxSize()) {
        when (uiState.selectedTab) {
            0 -> FoodLoggingCalendar(
                uiState = calendarUiState,
                onPreviousMonth = { calendarViewModel.onPreviousMonth() },
                onNextMonth = { calendarViewModel.onNextMonth() },
                onDateClick = onDateClick
            )
            1 -> WeightStatsTab(uiState = uiState, viewModel = viewModel)
            2 -> NutritionConsistencyTab(uiState = uiState, viewModel = viewModel)
            3 -> MacroAveragesTab(uiState = uiState, viewModel = viewModel)
            4 -> RankHistoryTabRoute(viewModel = viewModel)
        }
    }
}

/**
 * One statistics view: its range chips in a row that scrolls sideways to the screen edges, then
 * its sections at the 16 dp side margin. It reports its scroll to the header and leaves room at
 * the end so the last section clears the dock.
 */
@Composable
internal fun StatsPage(
    chips: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val scrollState = rememberScrollState()
    ReportContentScrolled(scrollState.isScrolled)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        Spacer(modifier = Modifier.height(AppSpacing.sm))
        MbChipRow(
            modifier = Modifier.selectableGroup(),
            contentPadding = PaddingValues(horizontal = AppSpacing.screenMargin),
            content = chips
        )
        Spacer(modifier = Modifier.height(AppSpacing.sectionGap))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.screenMargin),
            content = content
        )
        Spacer(modifier = Modifier.height(LocalDockInset.current + 16.dp))
    }
}

/** A section heading: 17 sp, sentence case. */
@Composable
internal fun StatsSectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = AppTypography.Header2,
        color = AppColors.TextPrimary,
        modifier = modifier.semantics { heading() }
    )
}

/** The note under a section heading or a chart: 12.5 sp light, text secondary. */
internal val StatsNoteStyle = AppTypography.Body2.copy(fontSize = 12.5.sp, lineHeight = 18.sp)

/** A panel holding only a short message, such as an empty or loading state. */
@Composable
internal fun StatsMessagePanel(text: String, modifier: Modifier = Modifier) {
    MbPanel(modifier = modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = AppTypography.Body2,
            color = AppColors.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp)
        )
    }
}

/**
 * Weight: the range chips, the From / To dates of the range (tapping one sets a custom range), the
 * Start / Current / Change tiles and the graph, or its empty state.
 */
@Composable
private fun WeightStatsTab(
    uiState: StatisticsUiState,
    viewModel: StatisticsViewModel
) {
    val isImperial = uiState.unitSystem == UnitSystem.IMPERIAL
    val shortDateFormatter = remember { DateTimeFormatter.ofPattern("MMM dd") }
    // true while picking the From date, false for the To date
    var pickingFromDate by remember { mutableStateOf<Boolean?>(null) }

    val stats = uiState.weightStats

    StatsPage(
        chips = {
            WeightTimeInterval.entries.forEach { interval ->
                MbChip(
                    text = weightIntervalChipLabel(interval),
                    selected = uiState.weightInterval == interval,
                    onClick = { viewModel.onSelectWeightInterval(interval) }
                )
            }
        }
    ) {
        StatsSectionHeading(text = "Weight graph")
        Spacer(modifier = Modifier.height(10.dp))

        // From / To dates of the range
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            StatsDateButton(
                text = "From ${uiState.weightFromDate.format(shortDateFormatter)}",
                onClick = { pickingFromDate = true },
                modifier = Modifier.weight(1f)
            )
            StatsDateButton(
                text = "To ${uiState.weightToDate.format(shortDateFormatter)}",
                onClick = { pickingFromDate = false },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Start / Current / Change tiles
        val startDisplay = formatWeightValue(stats?.initialWeightKg, isImperial)
        val currentDisplay = formatWeightValue(stats?.latestWeightKg, isImperial)
        val deltaVal = stats?.deltaWeightKg
        val deltaDisplay = formatWeightDelta(deltaVal, isImperial)
        val percentDisplay = stats?.percentageChange?.let { "(${String.format(Locale.US, "%.1f", abs(it))}%)" } ?: ""

        val changeColor = if (deltaVal != null) {
            if (deltaVal <= 0.0) AppColors.Success else AppColors.ProgressOver
        } else AppColors.TextSecondary

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            WeightStatTile(
                label = "Start",
                value = startDisplay,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
            WeightStatTile(
                label = "Current",
                value = currentDisplay,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
            WeightStatTile(
                label = "Change",
                value = deltaDisplay,
                valueColor = changeColor,
                note = percentDisplay,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Graph or empty state
        MbPanel(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 10.dp)
        ) {
            if (stats != null && stats.hasSufficientData) {
                WeightLineGraph(
                    entries = stats.entries,
                    isImperial = isImperial,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Log at least 2 weight entries to generate progress graph",
                        style = AppTypography.Body2,
                        color = AppColors.TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    pickingFromDate?.let { pickingFrom ->
        MbDatePickerDialog(
            initialDate = if (pickingFrom) uiState.weightFromDate else uiState.weightToDate,
            onDateSelected = { picked ->
                if (pickingFrom) {
                    viewModel.onSetWeightCustomRange(picked, uiState.weightToDate)
                } else {
                    viewModel.onSetWeightCustomRange(uiState.weightFromDate, picked)
                }
            },
            onDismiss = { pickingFromDate = null }
        )
    }
}

/** A From / To date of the weight range: a rounded field-like button with a calendar icon. */
@Composable
private fun StatsDateButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = AppShapes.InputBox
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 44.dp)
            .mbClickable(pressedScale = 0.97f, onClick = onClick)
            .clip(shape)
            .background(AppColors.FieldFill)
            .border(1.dp, AppColors.FieldBorder, shape)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MbIcon(icon = Ph.CalendarBlank.light, contentDescription = null, size = 16.dp, tint = AppColors.TextSecondary)
        Text(
            text = text,
            style = AppTypography.Body2.copy(fontSize = 13.5.sp),
            color = AppColors.TextPrimary,
            maxLines = 1
        )
    }
}

/** A Start / Current / Change tile: a small panel with the label over the value. */
@Composable
private fun WeightStatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = AppColors.TextPrimary,
    note: String = ""
) {
    MbPanel(
        modifier = modifier,
        shape = AppShapes.CardSmall,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(text = label, style = AppTypography.Caption, color = AppColors.TextSecondary, maxLines = 1)
        Text(
            text = value,
            style = AppTypography.Header3.copy(fontSize = 16.sp),
            color = valueColor,
            maxLines = 1
        )
        if (note.isNotEmpty()) {
            Text(
                text = note,
                style = AppTypography.Caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Light),
                color = AppColors.TextMuted,
                maxLines = 1
            )
        }
    }
}

/** The heatmap's cell size for a range: bigger cells when there are fewer weeks to show. */
internal fun heatmapCellSize(interval: ConsistencyInterval): Dp = when (interval) {
    ConsistencyInterval.DAYS_30 -> 24.dp
    ConsistencyInterval.DAYS_90 -> 16.dp
    ConsistencyInterval.MONTHS_6, ConsistencyInterval.YEAR_1 -> 12.dp
}

/**
 * Consistency: the range chips, then the weekday-row heatmap (it scrolls sideways), the
 * "Less ... More" legend and the score. Tapping a day opens its details in a sheet.
 */
@Composable
private fun NutritionConsistencyTab(
    uiState: StatisticsUiState,
    viewModel: StatisticsViewModel
) {
    val stats = uiState.consistencyStats
    var selectedCellForDialog by remember { mutableStateOf<HeatmapCellData?>(null) }

    StatsPage(
        chips = {
            ConsistencyInterval.entries.forEach { interval ->
                MbChip(
                    text = interval.label,
                    selected = uiState.consistencyInterval == interval,
                    onClick = { viewModel.onSelectConsistencyInterval(interval) }
                )
            }
        }
    ) {
        StatsSectionHeading(text = "Logging consistency (${uiState.consistencyInterval.label})")
        Spacer(modifier = Modifier.height(10.dp))

        MbPanel(modifier = Modifier.fillMaxWidth()) {
            ConsistencyHeatmap(
                stats = stats,
                cellSize = heatmapCellSize(uiState.consistencyInterval),
                onCellClick = { selectedCellForDialog = it }
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Legend: Less, the five colours, More
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Less", style = AppTypography.Caption, color = AppColors.TextSecondary)
                Spacer(modifier = Modifier.width(6.dp))
                listOf(
                    CalendarPerformanceConfig.PerformanceCategory.EMPTY_MISSED,
                    CalendarPerformanceConfig.PerformanceCategory.UNDER_BUDGET,
                    CalendarPerformanceConfig.PerformanceCategory.OPTIMAL_TARGET,
                    CalendarPerformanceConfig.PerformanceCategory.MODERATE_OVER,
                    CalendarPerformanceConfig.PerformanceCategory.HIGH_OVER_TARGET
                ).forEach { cat ->
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(CalendarPerformanceConfig.getColorForCategory(cat))
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                }
                Spacer(modifier = Modifier.width(3.dp))
                Text(text = "More", style = AppTypography.Caption, color = AppColors.TextSecondary)
            }

            HorizontalDivider(color = AppColors.Divider, modifier = Modifier.padding(vertical = 14.dp))

            // Consistency score
            val score = stats?.consistencyScorePercentage ?: 0
            val loggedCount = stats?.loggedDaysCount ?: 0
            val totalEligible = stats?.totalEligibleDays ?: 0

            Text(
                text = "Consistency score: $score% ($loggedCount of $totalEligible days logged)",
                style = AppTypography.Body1,
                color = AppColors.AccentStrong
            )
        }
    }

    selectedCellForDialog?.let { cell ->
        ConsistencyDaySheet(cell = cell, onDismiss = { selectedCellForDialog = null })
    }
}

/** The days as weekday rows (Sun to Sat) and week columns, scrolling sideways. */
@Composable
private fun ConsistencyHeatmap(
    stats: ConsistencyStatistics?,
    cellSize: Dp,
    onCellClick: (HeatmapCellData) -> Unit
) {
    val daysOfWeekLabels = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    val gap = if (cellSize >= 20.dp) 5.dp else 3.dp
    val cellShape = RoundedCornerShape(if (cellSize >= 20.dp) 5.dp else 3.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.Top
    ) {
        // Day of week column
        Column(
            verticalArrangement = Arrangement.spacedBy(gap),
            modifier = Modifier.padding(end = 6.dp)
        ) {
            daysOfWeekLabels.forEach { label ->
                Box(
                    modifier = Modifier.size(width = 28.dp, height = cellSize),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = label,
                        style = AppTypography.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp),
                        color = AppColors.TextMuted,
                        maxLines = 1
                    )
                }
            }
        }

        // Week columns
        stats?.weeks?.forEach { week ->
            Column(
                verticalArrangement = Arrangement.spacedBy(gap),
                modifier = Modifier.padding(end = gap)
            ) {
                week.cells.forEach { cell ->
                    if (cell != null) {
                        // An unlogged today is not a missed day yet, so it looks like one to come
                        val toCome = cell.isFuture || !cell.isEligible
                        Box(
                            modifier = Modifier
                                .size(cellSize)
                                .mbClickable(pressedScale = 0.9f, onClick = { onCellClick(cell) })
                                .clip(cellShape)
                                .then(
                                    if (toCome) {
                                        Modifier.border(1.dp, AppColors.Divider, cellShape)
                                    } else {
                                        Modifier.background(CalendarPerformanceConfig.getColorForCategory(cell.category))
                                    }
                                )
                        )
                    } else {
                        Spacer(modifier = Modifier.size(cellSize))
                    }
                }
            }
        }
    }
}

/** A heatmap day's details, as a bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsistencyDaySheet(cell: HeatmapCellData, onDismiss: () -> Unit) {
    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEEE, MMM dd, yyyy") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    MbSheet(
        onDismiss = onDismiss,
        title = cell.date.format(dateFormatter),
        sheetState = sheetState,
        footer = {
            MbButton(
                text = "Close",
                kind = MbButtonKind.Secondary,
                onClick = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() }
                },
                modifier = Modifier.weight(1f)
            )
        }
    ) {
        Column(
            modifier = Modifier.padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (cell.isFuture) {
                Text("Future date (not yet logged)", style = AppTypography.Body2, color = AppColors.TextSecondary)
            } else if (cell.caloriesLogged <= 0.0 && !cell.isEligible) {
                // Today, nothing logged yet: not counted as a missed day (BUG-032)
                Text("Nothing logged yet today.", style = AppTypography.Body2, color = AppColors.TextSecondary)
            } else if (cell.caloriesLogged <= 0.0) {
                Text("No food logged on this day.", style = AppTypography.Body2, color = AppColors.TextSecondary)
                Text("Daily goal: ${cell.dailyGoalCalories.roundToInt()} kcal", style = AppTypography.Caption, color = AppColors.TextMuted)
            } else {
                Text("Calories logged: ${cell.caloriesLogged.roundToInt()} kcal", style = AppTypography.Body1, color = AppColors.TextPrimary)
                Text("Daily target: ${cell.dailyGoalCalories.roundToInt()} kcal", style = AppTypography.Body2, color = AppColors.TextSecondary)
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // The day's heatmap colour, as a swatch: the darker colours are too dim for text
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(CalendarPerformanceConfig.getColorForCategory(cell.category))
                    )
                    Text("Adherence: ${cell.percentageOfGoal}% of goal", style = AppTypography.Body2, color = AppColors.TextPrimary)
                }
            }
        }
    }
}

/**
 * Averages: the range chips, then the daily intake averages and the macro distribution against
 * the target, or the empty text.
 */
@Composable
private fun MacroAveragesTab(
    uiState: StatisticsUiState,
    viewModel: StatisticsViewModel
) {
    val stats = uiState.macroStats

    StatsPage(
        chips = {
            MacroInterval.entries.forEach { interval ->
                MbChip(
                    text = interval.label,
                    selected = uiState.macroInterval == interval,
                    onClick = { viewModel.onSelectMacroInterval(interval) }
                )
            }
        }
    ) {
        if (stats == null || !stats.hasSufficientData) {
            StatsMessagePanel(text = "Log meals in this period to see average macro distribution. Today counts once it ends.")
        } else {
            // 1. Average daily intake
            StatsSectionHeading(text = "Daily intake averages")
            Text(
                text = "Based on ${stats.loggedDaysCount} logged days; today counts once it ends",
                style = StatsNoteStyle,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))

            MbPanel(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "${stats.averageCalories.roundToInt()}",
                        style = AppTypography.BigStat,
                        color = AppColors.TextPrimary,
                        modifier = Modifier.alignByBaseline()
                    )
                    Text(
                        text = "kcal / day",
                        style = AppTypography.HeroUnit,
                        modifier = Modifier
                            .alignByBaseline()
                            .padding(start = 8.dp)
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
                ) {
                    MacroAverageValue(
                        name = "Protein",
                        value = "${String.format(Locale.US, "%.1f", stats.averageProteinGrams)}g",
                        color = AppColors.MacroProtein,
                        modifier = Modifier.weight(1f)
                    )
                    MacroAverageValue(
                        name = "Carbs",
                        value = "${String.format(Locale.US, "%.1f", stats.averageCarbsGrams)}g",
                        color = AppColors.MacroCarbs,
                        modifier = Modifier.weight(1f)
                    )
                    MacroAverageValue(
                        name = "Fat",
                        value = "${String.format(Locale.US, "%.1f", stats.averageFatGrams)}g",
                        color = AppColors.MacroFat,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.sectionGap))

            // 2. Actual against target macro distribution
            StatsSectionHeading(text = "Macro distribution (actual vs target)")
            Spacer(modifier = Modifier.height(10.dp))

            MbPanel(modifier = Modifier.fillMaxWidth()) {
                Text(text = "Actual consumed ratio", style = AppTypography.Body2, color = AppColors.TextSecondary)
                Spacer(modifier = Modifier.height(6.dp))
                MacroSplitBar(
                    proteinPercent = stats.actualProteinPercent,
                    carbsPercent = stats.actualCarbsPercent,
                    fatPercent = stats.actualFatPercent
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(text = "Target goal ratio", style = AppTypography.Body2, color = AppColors.TextSecondary)
                Spacer(modifier = Modifier.height(6.dp))
                MacroSplitBar(
                    proteinPercent = stats.targetProteinPercent,
                    carbsPercent = stats.targetCarbsPercent,
                    fatPercent = stats.targetFatPercent
                )

                HorizontalDivider(color = AppColors.Divider, modifier = Modifier.padding(vertical = 14.dp))

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MacroComparisonRow(
                        name = "Protein",
                        color = AppColors.MacroProtein,
                        actualPercent = stats.actualProteinPercent,
                        targetPercent = stats.targetProteinPercent
                    )
                    MacroComparisonRow(
                        name = "Carbohydrates",
                        color = AppColors.MacroCarbs,
                        actualPercent = stats.actualCarbsPercent,
                        targetPercent = stats.targetCarbsPercent
                    )
                    MacroComparisonRow(
                        name = "Fat",
                        color = AppColors.MacroFat,
                        actualPercent = stats.actualFatPercent,
                        targetPercent = stats.targetFatPercent
                    )
                }
            }
        }
    }
}

/** A macro's daily average: a colour dot and the name over the grams. */
@Composable
private fun MacroAverageValue(name: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(text = name, style = AppTypography.Caption, color = AppColors.TextSecondary, maxLines = 1)
        }
        Text(text = value, style = AppTypography.Header3.copy(fontSize = 16.sp), color = AppColors.TextPrimary, maxLines = 1)
    }
}

@Composable
private fun MacroSplitBar(
    proteinPercent: Double,
    carbsPercent: Double,
    fatPercent: Double
) {
    val total = (proteinPercent + carbsPercent + fatPercent).coerceAtLeast(1.0)
    val pWeight = (proteinPercent / total).toFloat()
    val cWeight = (carbsPercent / total).toFloat()
    val fWeight = (fatPercent / total).toFloat()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(CircleShape)
            .background(AppColors.BarTrack)
    ) {
        if (pWeight > 0f) {
            Box(modifier = Modifier.weight(pWeight).fillMaxSize().background(AppColors.MacroProtein))
        }
        if (cWeight > 0f) {
            Box(modifier = Modifier.weight(cWeight).fillMaxSize().background(AppColors.MacroCarbs))
        }
        if (fWeight > 0f) {
            Box(modifier = Modifier.weight(fWeight).fillMaxSize().background(AppColors.MacroFat))
        }
    }
}

@Composable
private fun MacroComparisonRow(
    name: String,
    color: Color,
    actualPercent: Double,
    targetPercent: Double
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(modifier = Modifier.width(AppSpacing.md))
            Text(text = name, style = AppTypography.Body2, color = AppColors.TextPrimary)
        }
        Text(
            text = "${String.format(Locale.US, "%.1f", actualPercent)}% (target: ${String.format(Locale.US, "%.0f", targetPercent)}%)",
            style = AppTypography.Body2.copy(fontSize = 13.sp),
            color = AppColors.TextSecondary
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Chart pieces of the rank chart (design.txt C18); the weight chart is WeightLineGraph
// ---------------------------------------------------------------------------------------------

/** Chart labels: 10 sp in text muted. */
internal val ChartLabelStyle = AppTypography.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp, color = AppColors.TextMuted)

/** The tooltip's main line. */
internal val ChartTooltipStyle = AppTypography.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp, color = AppColors.ToastInk)

/** The toast's border, rgba(91,216,106,0.35), around a chart tooltip. */
private val ChartTooltipBorder = Color(0x595BD86A)

/** The newest point: a dot in the line colour with a 3 dp canvas-coloured outline. */
internal fun DrawScope.drawChartEndDot(center: Offset) {
    drawCircle(AppColors.Background, radius = 6.5.dp.toPx(), center = center)
    drawCircle(AppColors.ChartLine, radius = 3.5.dp.toPx(), center = center)
}

/** A tapped point: a hollow dot in the line colour. */
internal fun DrawScope.drawChartHollowDot(center: Offset) {
    drawCircle(AppColors.Background, radius = 4.dp.toPx(), center = center)
    drawCircle(AppColors.ChartLine, radius = 4.dp.toPx(), center = center, style = Stroke(width = 2.5.dp.toPx()))
}

/** The dashed vertical line through a tapped point, from [top] to [bottom]. */
internal fun DrawScope.drawChartGuide(x: Float, top: Float, bottom: Float) {
    drawLine(
        color = AppColors.ChartGrid,
        start = Offset(x, top),
        end = Offset(x, bottom),
        strokeWidth = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
    )
}

/** A tooltip's box, in the toast's colours. */
internal fun DrawScope.drawChartTooltipBox(bounds: WeightGraphHelper.TooltipBounds) {
    val corner = CornerRadius(10.dp.toPx(), 10.dp.toPx())
    val topLeft = Offset(bounds.leftX, bounds.topY)
    val boxSize = Size(bounds.width, bounds.height)
    drawRoundRect(color = AppColors.ToastFill, topLeft = topLeft, size = boxSize, cornerRadius = corner)
    drawRoundRect(color = ChartTooltipBorder, topLeft = topLeft, size = boxSize, cornerRadius = corner, style = Stroke(width = 1.dp.toPx()))
}

private fun formatWeightValue(kg: Double?, isImperial: Boolean): String {
    if (kg == null || kg <= 0.0) return "--"
    val v = if (isImperial) UnitConversions.kgToLbs(kg) else kg
    val unit = if (isImperial) "lb" else "kg"
    return "${String.format(Locale.US, "%.1f", v)} $unit"
}

private fun formatWeightDelta(deltaKg: Double?, isImperial: Boolean): String {
    if (deltaKg == null) return "--"
    val v = if (isImperial) UnitConversions.kgToLbs(deltaKg) else deltaKg
    val unit = if (isImperial) "lb" else "kg"
    val arrow = if (v < 0) "\u2193 " else if (v > 0) "\u2191 " else ""
    return "$arrow${String.format(Locale.US, "%.1f", abs(v))} $unit"
}
