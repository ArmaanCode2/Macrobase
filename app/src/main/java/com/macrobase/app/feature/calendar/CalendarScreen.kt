package com.macrobase.app.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macrobase.app.core.config.CalendarPerformanceConfig
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppShapes
import com.macrobase.app.core.designsystem.AppSpacing
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.Ph
import com.macrobase.app.core.designsystem.components.MbIconButton
import com.macrobase.app.core.designsystem.components.MbPanel
import com.macrobase.app.core.designsystem.components.rememberIsScrolled
import com.macrobase.app.core.designsystem.components.mbClickable
import com.macrobase.app.core.navigation.LocalDockInset
import com.macrobase.app.core.navigation.ReportContentScrolled
import com.macrobase.app.domain.model.CalendarDaySummary
import com.macrobase.app.domain.usecase.GetCalendarAdherenceUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.macrobase.app.core.util.DeviceClock
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

data class CalendarDayCellModel(
    val date: LocalDate,
    val dayNumber: Int,
    val isCurrentMonth: Boolean,
    val isToday: Boolean,
    val isFuture: Boolean,
    val loggedCalories: Double,
    val calorieGoal: Double,
    val performanceCategory: CalendarPerformanceConfig.PerformanceCategory
)

data class CalendarUiState(
    val displayedYearMonth: YearMonth = YearMonth.now(),
    val dayCells: List<CalendarDayCellModel> = emptyList(),
    val daysMissed: Int = 0,
    val greenPercentage: Int = 0,
    val isLoading: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel(
    private val getCalendarAdherenceUseCase: GetCalendarAdherenceUseCase,
    private val clock: Clock = DeviceClock
) : ViewModel() {

    private val _displayedYearMonth = MutableStateFlow(YearMonth.now(clock))
    val displayedYearMonth: StateFlow<YearMonth> = _displayedYearMonth.asStateFlow()

    // Each summary carries the goal in force on its day, so a goal change never recolours history (BUG-049)
    val uiState: StateFlow<CalendarUiState> = _displayedYearMonth.flatMapLatest { ym ->
        getCalendarAdherenceUseCase.observe(ym.year, ym.monthValue).map { summaries -> calculateMonthState(ym, summaries) }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CalendarUiState()
    )

    fun onPreviousMonth() {
        showMonth(_displayedYearMonth.value.minusMonths(1))
    }

    fun onNextMonth() {
        showMonth(_displayedYearMonth.value.plusMonths(1))
    }

    fun onSelectMonth(yearMonth: YearMonth) {
        showMonth(yearMonth)
    }

    // Whether the calendar shows the current month, so it moves on with it when a new month starts
    private var followsCurrentMonth = true

    private fun showMonth(yearMonth: YearMonth) {
        _displayedYearMonth.value = yearMonth
        followsCurrentMonth = yearMonth == YearMonth.now(clock)
    }

    /**
     * The screen is shown again. A kept calendar that showed the current month moves on to the
     * month it is now; one the user moved to another month stays there.
     */
    fun onShown() {
        if (followsCurrentMonth) _displayedYearMonth.value = YearMonth.now(clock)
    }

    private fun calculateMonthState(
        yearMonth: YearMonth,
        summaries: List<CalendarDaySummary>
    ): CalendarUiState {
        val summariesMap = summaries.associateBy { it.date.dayOfMonth }
        // Days outside the month are padding and are never judged; they show the nearest day's goal
        val firstGoal = summaries.firstOrNull()?.calorieGoal ?: 0.0
        val lastGoal = summaries.lastOrNull()?.calorieGoal ?: 0.0

        val today = LocalDate.now(clock)
        val totalDays = yearMonth.lengthOfMonth()
        val firstDayOfMonth = yearMonth.atDay(1)

        // Sunday-Saturday alignment: Sunday = 0, Monday = 1, ..., Saturday = 6
        // DayOfWeek.SUNDAY.value % 7 = 0
        val leadingEmptyCount = firstDayOfMonth.dayOfWeek.value % 7

        val cellModels = mutableListOf<CalendarDayCellModel>()

        // 1. Leading padding days from previous month
        val prevMonth = yearMonth.minusMonths(1)
        val prevMonthDays = prevMonth.lengthOfMonth()
        for (i in (leadingEmptyCount - 1) downTo 0) {
            val d = prevMonthDays - i
            val date = prevMonth.atDay(d)
            cellModels.add(
                CalendarDayCellModel(
                    date = date,
                    dayNumber = d,
                    isCurrentMonth = false,
                    isToday = date == today,
                    isFuture = date.isAfter(today),
                    loggedCalories = 0.0,
                    calorieGoal = firstGoal,
                    performanceCategory = CalendarPerformanceConfig.PerformanceCategory.EMPTY_MISSED
                )
            )
        }

        // 2. Current month days
        for (d in 1..totalDays) {
            val date = yearMonth.atDay(d)
            val isToday = date == today
            val isFuture = date.isAfter(today)
            val summary = summariesMap[d]
            val loggedCal = summary?.loggedCalories ?: 0.0
            val dayGoal = summary?.calorieGoal ?: 0.0

            val category = if (isFuture && loggedCal <= 0.0) {
                CalendarPerformanceConfig.PerformanceCategory.EMPTY_MISSED
            } else {
                CalendarPerformanceConfig.evaluatePerformance(loggedCal, dayGoal)
            }

            cellModels.add(
                CalendarDayCellModel(
                    date = date,
                    dayNumber = d,
                    isCurrentMonth = true,
                    isToday = isToday,
                    isFuture = isFuture,
                    loggedCalories = loggedCal,
                    calorieGoal = dayGoal,
                    performanceCategory = category
                )
            )
        }

        // 3. Trailing padding days to fill 7-column rows
        val remainder = cellModels.size % 7
        val trailingEmptyCount = if (remainder != 0) 7 - remainder else 0
        val nextMonth = yearMonth.plusMonths(1)
        for (d in 1..trailingEmptyCount) {
            val date = nextMonth.atDay(d)
            cellModels.add(
                CalendarDayCellModel(
                    date = date,
                    dayNumber = d,
                    isCurrentMonth = false,
                    isToday = date == today,
                    isFuture = date.isAfter(today),
                    loggedCalories = 0.0,
                    calorieGoal = lastGoal,
                    performanceCategory = CalendarPerformanceConfig.PerformanceCategory.EMPTY_MISSED
                )
            )
        }

        // Metrics Calculation:
        // Eligible days: past days of the displayed month, and today once something is logged.
        // Until then today is not a missed day, as in the rank and the heatmap (BUG-032, D7).
        val currentMonthCells = cellModels.filter { it.isCurrentMonth }
        val eligiblePastOrTodayDays = currentMonthCells.filter { it.date.isBefore(today) || (it.isToday && it.loggedCalories > 0.0) }

        val daysMissed = eligiblePastOrTodayDays.count { it.loggedCalories <= 0.0 }
        val loggedDays = eligiblePastOrTodayDays.filter { it.loggedCalories > 0.0 }
        val optimalDays = loggedDays.filter { it.performanceCategory == CalendarPerformanceConfig.PerformanceCategory.OPTIMAL_TARGET }

        val greenPercentage = if (loggedDays.isNotEmpty()) {
            ((optimalDays.size.toDouble() / loggedDays.size.toDouble()) * 100.0).roundToInt()
        } else 0

        return CalendarUiState(
            displayedYearMonth = yearMonth,
            dayCells = cellModels,
            daysMissed = daysMissed,
            greenPercentage = greenPercentage,
            isLoading = false
        )
    }
}


@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel,
    onDateClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    FoodLoggingCalendar(
        uiState = uiState,
        onPreviousMonth = { viewModel.onPreviousMonth() },
        onNextMonth = { viewModel.onNextMonth() },
        onDateClick = onDateClick,
        modifier = modifier,
        // The pinned header already names this screen "Food logging calendar"
        showTitle = false
    )
}

/** The weekday row over the grid, Sunday first like the grid. */
private val WeekDayLabels = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

/** The gap between calendar cells, across and down. */
private val CalendarCellGap = 6.dp

/**
 * The food logging calendar, shown by Statistics' Calendar view and by the Calendar screen: the
 * month with its arrows, the days coloured by how each went against its goal, the legend, the
 * month's summary and the hint. A sideways swipe on the days changes the month, and tapping a day
 * of the month opens it. The content scrolls under the pinned header and ends clear of the dock.
 * [showTitle] shows the "Food logging calendar" heading, for a screen whose header names it otherwise.
 */
@Composable
fun FoodLoggingCalendar(
    uiState: CalendarUiState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onDateClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true
) {
    val scrollState = rememberScrollState()
    ReportContentScrolled(scrollState.rememberIsScrolled())

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = AppSpacing.screenMargin)
    ) {
        Spacer(modifier = Modifier.height(AppSpacing.sm))
        if (showTitle) {
            Text(
                text = "Food logging calendar",
                style = AppTypography.Header2,
                color = AppColors.TextPrimary,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(modifier = Modifier.height(AppSpacing.md))
        }

        // 1. The month and its arrows (not part of the swipe area)
        CalendarMonthHeader(
            yearMonth = uiState.displayedYearMonth,
            onPreviousMonth = onPreviousMonth,
            onNextMonth = onNextMonth
        )

        Spacer(modifier = Modifier.height(AppSpacing.sm))

        // 2. The weekday row and the days: only this area handles sideways swipes
        var dragOffsetX by remember { mutableFloatStateOf(0f) }
        val density = LocalDensity.current
        val swipeThresholdPx = remember(density) { with(density) { 48.dp.toPx() } }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(uiState.displayedYearMonth) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragOffsetX = 0f },
                        onDragCancel = { dragOffsetX = 0f },
                        onDragEnd = {
                            if (dragOffsetX < -swipeThresholdPx) {
                                // Swiped left -> advance to Next Month
                                onNextMonth()
                            } else if (dragOffsetX > swipeThresholdPx) {
                                // Swiped right -> go back to Previous Month
                                onPreviousMonth()
                            }
                            dragOffsetX = 0f
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            dragOffsetX += dragAmount
                            // Consume pointer change once horizontal intent is confirmed
                            if (kotlin.math.abs(dragOffsetX) > 10f) {
                                change.consume()
                            }
                        }
                    )
                },
            verticalArrangement = Arrangement.spacedBy(CalendarCellGap)
        ) {
            CalendarWeekHeader()

            // 7-column grid of day cells
            uiState.dayCells.chunked(7).forEach { rowCells ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(CalendarCellGap)
                ) {
                    rowCells.forEach { cell ->
                        CalendarDayCell(
                            cell = cell,
                            onClick = { onDateClick(cell.date) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 3. What each colour means (not part of the swipe area)
        CalendarLegend()

        Spacer(modifier = Modifier.height(14.dp))

        // 4. The month's summary
        CalendarPerformanceSummary(
            daysMissed = uiState.daysMissed,
            greenPercentage = uiState.greenPercentage
        )

        // 5. The hint
        Text(
            text = "Tap on any date on the calendar to review or add foods.",
            style = AppTypography.Body2.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = 10.dp)
        )

        Spacer(modifier = Modifier.height(LocalDockInset.current + 16.dp))
    }
}

/**
 * The month and year between the previous and next month arrows.
 */
@Composable
fun CalendarMonthHeader(
    yearMonth: YearMonth,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    modifier: Modifier = Modifier
) {
    val monthFormatter = remember { DateTimeFormatter.ofPattern("MMMM, yyyy") }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MbIconButton(
            icon = Ph.CaretLeft.light,
            contentDescription = "Previous month",
            onClick = onPreviousMonth,
            iconSize = 20.dp
        )
        Text(
            text = yearMonth.format(monthFormatter),
            style = AppTypography.Header2,
            color = AppColors.TextPrimary
        )
        MbIconButton(
            icon = Ph.CaretRight.light,
            contentDescription = "Next month",
            onClick = onNextMonth,
            iconSize = 20.dp
        )
    }
}

/**
 * The weekday names over the grid's seven columns, Sunday first.
 */
@Composable
fun CalendarWeekHeader(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CalendarCellGap)
    ) {
        WeekDayLabels.forEach { wd ->
            Text(
                text = wd,
                style = AppTypography.Caption.copy(fontSize = 11.sp),
                color = AppColors.TextMuted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * A day's fill and number colour (design.txt section 2, calendar cells). Days of the next or
 * previous month are padding and show their number faintly with no fill; an upcoming day with
 * nothing logged has no fill yet; every other day takes its performance colour.
 */
internal fun calendarCellColors(cell: CalendarDayCellModel): Pair<Color, Color> = when {
    !cell.isCurrentMonth -> Color.Transparent to AppColors.TextMuted.copy(alpha = 0.35f)
    cell.isFuture && cell.performanceCategory == CalendarPerformanceConfig.PerformanceCategory.EMPTY_MISSED ->
        Color.Transparent to AppColors.TextMuted
    else -> CalendarPerformanceConfig.getColorForCategory(cell.performanceCategory) to
        CalendarPerformanceConfig.getTextColorForCategory(cell.performanceCategory)
}

/**
 * One square day of the grid, radius 10, in its performance colour. Today has a light ring. Only
 * days of the displayed month can be tapped.
 */
@Composable
fun CalendarDayCell(
    cell: CalendarDayCellModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (fill, ink) = calendarCellColors(cell)
    val shape = AppShapes.CalendarCell

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .mbClickable(enabled = cell.isCurrentMonth, pressedScale = 0.92f, onClick = onClick)
            .clip(shape)
            .background(fill)
            .then(
                if (cell.isToday && cell.isCurrentMonth) Modifier.border(2.dp, AppColors.TextPrimary, shape) else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "${cell.dayNumber}",
            style = AppTypography.Body1.copy(fontSize = 14.sp, lineHeight = 18.sp),
            color = ink
        )
    }
}

/**
 * What each of the five cell colours means, from a missed day to a day well over the goal.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalendarLegend(modifier: Modifier = Modifier) {
    val items = listOf(
        "Missed" to AppColors.CalendarEmpty,
        "Under" to AppColors.CalendarGreenSubtle,
        "On target" to AppColors.CalendarGreenOptimal,
        "Over" to AppColors.CalendarRedWarning,
        "Well over" to AppColors.CalendarRedAlert
    )

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach { (label, color) ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(color)
                )
                Text(text = label, style = AppTypography.Caption, color = AppColors.TextSecondary)
            }
        }
    }
}

/**
 * The month's summary: days missed and the share of logged days on target, as two tiles.
 */
@Composable
fun CalendarPerformanceSummary(
    daysMissed: Int,
    greenPercentage: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
    ) {
        CalendarSummaryTile(
            label = "Days missed",
            value = "$daysMissed days",
            valueColor = if (daysMissed > 0) AppColors.TextPrimary else AppColors.AccentStrong,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
        CalendarSummaryTile(
            label = "% days of green",
            value = "$greenPercentage%",
            valueColor = AppColors.AccentStrong,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
    }
}

@Composable
private fun CalendarSummaryTile(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    MbPanel(
        modifier = modifier,
        shape = AppShapes.CardSmall,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(text = label, style = AppTypography.Caption, color = AppColors.TextSecondary)
        Text(text = value, style = AppTypography.Header1, color = valueColor)
    }
}
