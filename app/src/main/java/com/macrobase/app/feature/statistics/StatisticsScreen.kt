package com.macrobase.app.feature.statistics

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macrobase.app.core.config.CalendarPerformanceConfig
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.feature.calendar.CalendarViewModel
import com.macrobase.app.feature.calendar.FoodLoggingCalendar
import org.koin.androidx.compose.koinViewModel
import com.macrobase.app.core.designsystem.AppShapes
import com.macrobase.app.core.designsystem.AppSpacing
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.Dimensions
import com.macrobase.app.domain.model.ConsistencyStatistics
import com.macrobase.app.domain.model.HeatmapCellData
import com.macrobase.app.domain.model.MacroAveragesStatistics
import com.macrobase.app.domain.model.UnitConversions
import com.macrobase.app.domain.model.UnitSystem
import com.macrobase.app.domain.model.WeightStatistics
import com.macrobase.app.domain.repository.PreferencesRepository
import com.macrobase.app.domain.usecase.GetStatisticsUseCase
import com.macrobase.app.feature.weight.WeightLineGraph
import com.macrobase.app.feature.weight.WeightTimeInterval
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

enum class ConsistencyInterval(val label: String, val days: Long) {
    DAYS_30("30 Days", 30),
    DAYS_90("90 Days", 90),
    MONTHS_6("6 Months", 180),
    YEAR_1("1 Year", 365)
}

enum class MacroInterval(val label: String, val days: Long) {
    DAYS_7("7-Day", 7),
    DAYS_30("30-Day", 30),
    DAYS_90("90-Day", 90)
}

data class StatisticsUiState(
    val selectedTab: Int = 0,
    // Weight Section
    val weightInterval: WeightTimeInterval = WeightTimeInterval.LAST_30_DAYS,
    val weightFromDate: LocalDate = LocalDate.now().minusDays(30),
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
    private val preferencesRepository: PreferencesRepository
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    private val _weightInterval = MutableStateFlow(WeightTimeInterval.LAST_30_DAYS)
    val weightInterval: StateFlow<WeightTimeInterval> = _weightInterval.asStateFlow()

    private val _weightCustomFrom = MutableStateFlow(LocalDate.now().minusDays(30))
    private val _weightCustomTo = MutableStateFlow(LocalDate.now())

    private val _consistencyInterval = MutableStateFlow(ConsistencyInterval.DAYS_30)
    val consistencyInterval: StateFlow<ConsistencyInterval> = _consistencyInterval.asStateFlow()

    private val _macroInterval = MutableStateFlow(MacroInterval.DAYS_30)
    val macroInterval: StateFlow<MacroInterval> = _macroInterval.asStateFlow()

    val uiState: StateFlow<StatisticsUiState> = combine(
        combine(_selectedTab, _weightInterval, _weightCustomFrom) { tab, wInt, wFrom -> Triple(tab, wInt, wFrom) },
        combine(_weightCustomTo, _consistencyInterval, _macroInterval) { wTo, cInt, mInt -> Triple(wTo, cInt, mInt) }
    ) { (tab, wInt, wFrom), (wTo, cInt, mInt) ->
        val (weightFrom, weightTo) = calculateWeightDateRange(wInt, wFrom, wTo)
        val today = LocalDate.now()
        val cFrom = today.minusDays(cInt.days)
        val mFrom = today.minusDays(mInt.days)

        StatsParams(tab, wInt, weightFrom, weightTo, cInt, cFrom, today, mInt, mFrom, today)
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

@Composable
fun StatisticsScreen(
    viewModel: StatisticsViewModel,
    calendarViewModel: CalendarViewModel = koinViewModel(),
    onDateClick: (LocalDate) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val calendarUiState by calendarViewModel.uiState.collectAsState()
    val tabs = listOf("Calendar", "Weight Stats", "Nutrition Consistency", "Macro Averages")

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Background)
    ) {
        // Tab Navigation Header
        ScrollableTabRow(
            selectedTabIndex = uiState.selectedTab,
            containerColor = AppColors.Surface,
            contentColor = AppColors.TextPrimary,
            edgePadding = AppSpacing.md,
            indicator = { tabPositions ->
                if (uiState.selectedTab < tabPositions.size) {
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[uiState.selectedTab]),
                        color = AppColors.Primary
                    )
                }
            }
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = uiState.selectedTab == index,
                    onClick = { viewModel.onSelectTab(index) },
                    text = {
                        Text(
                            text = title,
                            style = AppTypography.Header3,
                            color = if (uiState.selectedTab == index) AppColors.Primary else AppColors.TextSecondary
                        )
                    }
                )
            }
        }

        // Tab Content
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
        }
    }
}

/**
 * Tab 1: Weight Statistics & Progress Graph
 */
@Composable
private fun WeightStatsTab(
    uiState: StatisticsUiState,
    viewModel: StatisticsViewModel
) {
    val context = LocalContext.current
    val isImperial = uiState.unitSystem == UnitSystem.IMPERIAL
    val shortDateFormatter = remember { DateTimeFormatter.ofPattern("MMM dd") }
    var showIntervalDropdown by remember { mutableStateOf(false) }

    val stats = uiState.weightStats

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppSpacing.lg)
            .verticalScroll(rememberScrollState())
    ) {
        Card(
            shape = AppShapes.Card,
            colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(AppSpacing.md)) {
                // Header & Interval Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Weight Graph", style = AppTypography.Header2)

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
                                text = uiState.weightInterval.label,
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
                                        viewModel.onSelectWeightInterval(interval)
                                        showIntervalDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(AppSpacing.xs))

                // From / To Date Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                val d = uiState.weightFromDate
                                DatePickerDialog(context, { _, y, m, day ->
                                    val newFrom = LocalDate.of(y, m + 1, day)
                                    viewModel.onSetWeightCustomRange(newFrom, uiState.weightToDate)
                                }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CalendarToday, contentDescription = null, tint = AppColors.TextSecondary, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "From ${uiState.weightFromDate.format(shortDateFormatter)}", style = AppTypography.Caption, color = AppColors.TextSecondary)
                    }

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                val d = uiState.weightToDate
                                DatePickerDialog(context, { _, y, m, day ->
                                    val newTo = LocalDate.of(y, m + 1, day)
                                    viewModel.onSetWeightCustomRange(uiState.weightFromDate, newTo)
                                }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CalendarToday, contentDescription = null, tint = AppColors.TextSecondary, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "To ${uiState.weightToDate.format(shortDateFormatter)}", style = AppTypography.Caption, color = AppColors.TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(AppSpacing.md))
                HorizontalDivider(color = AppColors.Divider)
                Spacer(modifier = Modifier.height(AppSpacing.md))

                // 3-Column Metric Strip (START / CURRENT / CHANGE)
                val startDisplay = formatWeightValue(stats?.initialWeightKg, isImperial)
                val currentDisplay = formatWeightValue(stats?.latestWeightKg, isImperial)
                val deltaVal = stats?.deltaWeightKg
                val deltaDisplay = formatWeightDelta(deltaVal, isImperial)
                val percentDisplay = stats?.percentageChange?.let { "(${String.format("%.1f", abs(it))}%)" } ?: ""

                val changeColor = if (deltaVal != null) {
                    if (deltaVal <= 0.0) Color(0xFF00C853) else AppColors.ProgressOver
                } else AppColors.TextSecondary

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Text(text = startDisplay, style = AppTypography.Header2)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(text = "START", style = AppTypography.Caption, color = AppColors.TextSecondary)
                    }

                    Box(modifier = Modifier.width(1.dp).height(36.dp).background(AppColors.Divider))

                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Text(text = currentDisplay, style = AppTypography.Header2)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(text = "CURRENT", style = AppTypography.Caption, color = AppColors.TextSecondary)
                    }

                    Box(modifier = Modifier.width(1.dp).height(36.dp).background(AppColors.Divider))

                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Text(text = deltaDisplay, style = AppTypography.Header2, color = changeColor)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(text = "CHANGE $percentDisplay", style = AppTypography.Caption, color = AppColors.TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(AppSpacing.lg))

                // Graph or Empty State
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
                            color = AppColors.TextMuted
                        )
                    }
                }
            }
        }
    }
}

/**
 * Tab 2: Nutrition Consistency (GitHub-Style Weekly Matrix Heatmap)
 */
@Composable
private fun NutritionConsistencyTab(
    uiState: StatisticsUiState,
    viewModel: StatisticsViewModel
) {
    val stats = uiState.consistencyStats
    var selectedCellForDialog by remember { mutableStateOf<HeatmapCellData?>(null) }
    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEEE, MMM dd, yyyy") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppSpacing.lg)
            .verticalScroll(rememberScrollState())
    ) {
        // Range Filter Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            ConsistencyInterval.entries.forEach { interval ->
                FilterChip(
                    selected = uiState.consistencyInterval == interval,
                    onClick = { viewModel.onSelectConsistencyInterval(interval) },
                    label = { Text(interval.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AppColors.Primary,
                        selectedLabelColor = AppColors.Background,
                        containerColor = AppColors.SurfaceAlt,
                        labelColor = AppColors.TextSecondary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))

        // Heatmap Card
        Card(
            shape = AppShapes.Card,
            colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(AppSpacing.lg)) {
                Text(
                    text = "${uiState.consistencyInterval.days}-Day Logging Consistency",
                    style = AppTypography.Header2
                )
                Spacer(modifier = Modifier.height(AppSpacing.md))

                // Weekly Heatmap Matrix
                val daysOfWeekLabels = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.Top
                ) {
                    // Day of Week Column
                    Column(
                        verticalArrangement = Arrangement.spacedBy(Dimensions.HeatmapCellSpacing),
                        modifier = Modifier.padding(end = AppSpacing.sm)
                    ) {
                        daysOfWeekLabels.forEach { label ->
                            Box(
                                modifier = Modifier
                                    .size(width = 28.dp, height = Dimensions.HeatmapCellSize),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    text = label,
                                    style = AppTypography.Caption,
                                    color = AppColors.TextSecondary,
                                    fontSize = 9.sp
                                )
                            }
                        }
                    }

                    // Week Columns
                    stats?.weeks?.forEach { week ->
                        Column(
                            verticalArrangement = Arrangement.spacedBy(Dimensions.HeatmapCellSpacing),
                            modifier = Modifier.padding(end = Dimensions.HeatmapCellSpacing)
                        ) {
                            week.cells.forEach { cell ->
                                if (cell != null) {
                                    val cellColor = if (cell.isFuture) {
                                        AppColors.SurfaceAlt
                                    } else {
                                        CalendarPerformanceConfig.getColorForCategory(cell.category)
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(Dimensions.HeatmapCellSize)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(cellColor)
                                            .clickable { selectedCellForDialog = cell }
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(Dimensions.HeatmapCellSize)
                                            .background(Color.Transparent)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(AppSpacing.md))

                // Heatmap Legend: Less [ ■ ■ ■ ■ ■ ] More
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Less", style = AppTypography.Caption, color = AppColors.TextSecondary)
                    Spacer(modifier = Modifier.width(4.dp))
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
                                .clip(RoundedCornerShape(2.dp))
                                .background(CalendarPerformanceConfig.getColorForCategory(cat))
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "More", style = AppTypography.Caption, color = AppColors.TextSecondary)
                }

                Spacer(modifier = Modifier.height(AppSpacing.md))
                HorizontalDivider(color = AppColors.Divider)
                Spacer(modifier = Modifier.height(AppSpacing.md))

                // Consistency Score Badge
                val score = stats?.consistencyScorePercentage ?: 0
                val loggedCount = stats?.loggedDaysCount ?: 0
                val totalEligible = stats?.totalEligibleDays ?: 0

                Text(
                    text = "Consistency Score: $score% ($loggedCount of $totalEligible Days Logged)",
                    style = AppTypography.Body1,
                    color = AppColors.CalorieText
                )
            }
        }
    }

    // Heatmap Cell Detail Dialog
    selectedCellForDialog?.let { cell ->
        AlertDialog(
            onDismissRequest = { selectedCellForDialog = null },
            title = { Text(cell.date.format(dateFormatter), style = AppTypography.Header2) },
            text = {
                Column {
                    if (cell.isFuture) {
                        Text("Future date (not yet logged)", style = AppTypography.Body2, color = AppColors.TextSecondary)
                    } else if (cell.caloriesLogged <= 0.0) {
                        Text("No food logged on this day.", style = AppTypography.Body2, color = AppColors.TextSecondary)
                        Spacer(modifier = Modifier.height(AppSpacing.xs))
                        Text("Daily Goal: ${cell.dailyGoalCalories.toInt()} kcal", style = AppTypography.Caption, color = AppColors.TextMuted)
                    } else {
                        Text("Calories Logged: ${cell.caloriesLogged.toInt()} kcal", style = AppTypography.Body1)
                        Text("Daily Target: ${cell.dailyGoalCalories.toInt()} kcal", style = AppTypography.Body2, color = AppColors.TextSecondary)
                        Spacer(modifier = Modifier.height(AppSpacing.xs))
                        Text("Adherence: ${cell.percentageOfGoal}% of Goal", style = AppTypography.Body2, color = CalendarPerformanceConfig.getColorForCategory(cell.category))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedCellForDialog = null }) {
                    Text("Close", color = AppColors.Primary)
                }
            },
            containerColor = AppColors.Surface
        )
    }
}

/**
 * Tab 3: Average Macro Distribution (Actual vs Target Comparison)
 */
@Composable
private fun MacroAveragesTab(
    uiState: StatisticsUiState,
    viewModel: StatisticsViewModel
) {
    val stats = uiState.macroStats

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppSpacing.lg)
            .verticalScroll(rememberScrollState())
    ) {
        // Range Filter Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            MacroInterval.entries.forEach { interval ->
                FilterChip(
                    selected = uiState.macroInterval == interval,
                    onClick = { viewModel.onSelectMacroInterval(interval) },
                    label = { Text(interval.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AppColors.Primary,
                        selectedLabelColor = AppColors.Background,
                        containerColor = AppColors.SurfaceAlt,
                        labelColor = AppColors.TextSecondary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))

        if (stats == null || !stats.hasSufficientData) {
            Card(
                shape = AppShapes.Card,
                colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(AppSpacing.xl),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Log meals in this period to see average macro distribution.",
                        style = AppTypography.Body2,
                        color = AppColors.TextMuted
                    )
                }
            }
        } else {
            // 1. Average Daily Intake Card
            Card(
                shape = AppShapes.Card,
                colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(AppSpacing.lg)) {
                    Text(text = "Daily Intake Averages", style = AppTypography.Header2)
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                    Text(
                        text = "Based on ${stats.loggedDaysCount} logged days",
                        style = AppTypography.Caption,
                        color = AppColors.TextSecondary
                    )

                    Spacer(modifier = Modifier.height(AppSpacing.md))
                    HorizontalDivider(color = AppColors.Divider)
                    Spacer(modifier = Modifier.height(AppSpacing.md))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            Text(text = "${stats.averageCalories.toInt()}", style = AppTypography.Header2, color = AppColors.CalorieText)
                            Text(text = "kcal / day", style = AppTypography.Caption, color = AppColors.TextSecondary)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            Text(text = "${String.format("%.1f", stats.averageProteinGrams)}g", style = AppTypography.Header2, color = AppColors.MacroProtein)
                            Text(text = "Protein", style = AppTypography.Caption, color = AppColors.TextSecondary)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            Text(text = "${String.format("%.1f", stats.averageCarbsGrams)}g", style = AppTypography.Header2, color = AppColors.MacroCarbs)
                            Text(text = "Carbs", style = AppTypography.Caption, color = AppColors.TextSecondary)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            Text(text = "${String.format("%.1f", stats.averageFatGrams)}g", style = AppTypography.Header2, color = AppColors.MacroFat)
                            Text(text = "Fat", style = AppTypography.Caption, color = AppColors.TextSecondary)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.lg))

            // 2. Target vs Actual Macro Distribution Comparison Card
            Card(
                shape = AppShapes.Card,
                colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(AppSpacing.lg)) {
                    Text(text = "Macro Distribution (Actual vs Target)", style = AppTypography.Header2)
                    Spacer(modifier = Modifier.height(AppSpacing.md))

                    // Progress Split Bars
                    // Actual Split
                    Text(text = "Actual Consumed Ratio", style = AppTypography.Body2)
                    Spacer(modifier = Modifier.height(AppSpacing.xs))
                    MacroSplitBar(
                        proteinPercent = stats.actualProteinPercent,
                        carbsPercent = stats.actualCarbsPercent,
                        fatPercent = stats.actualFatPercent
                    )

                    Spacer(modifier = Modifier.height(AppSpacing.md))

                    // Target Split
                    Text(text = "Target Goal Ratio", style = AppTypography.Body2)
                    Spacer(modifier = Modifier.height(AppSpacing.xs))
                    MacroSplitBar(
                        proteinPercent = stats.targetProteinPercent,
                        carbsPercent = stats.targetCarbsPercent,
                        fatPercent = stats.targetFatPercent
                    )

                    Spacer(modifier = Modifier.height(AppSpacing.lg))

                    // Breakdown Rows
                    MacroComparisonRow(
                        name = "Protein",
                        color = AppColors.MacroProtein,
                        actualPercent = stats.actualProteinPercent,
                        targetPercent = stats.targetProteinPercent
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.xs))
                    MacroComparisonRow(
                        name = "Carbohydrates",
                        color = AppColors.MacroCarbs,
                        actualPercent = stats.actualCarbsPercent,
                        targetPercent = stats.targetCarbsPercent
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.xs))
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
            .height(14.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(AppColors.SurfaceAlt)
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
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(modifier = Modifier.width(AppSpacing.sm))
            Text(text = name, style = AppTypography.Body2)
        }
        Text(
            text = "${String.format("%.1f", actualPercent)}% (Target: ${String.format("%.0f", targetPercent)}%)",
            style = AppTypography.Caption,
            color = AppColors.TextSecondary
        )
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
