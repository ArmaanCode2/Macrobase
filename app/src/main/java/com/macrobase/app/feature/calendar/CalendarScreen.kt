package com.macrobase.app.feature.calendar

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.macrobase.app.core.designsystem.Dimensions
import com.macrobase.app.domain.model.CalendarDaySummary
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.repository.GoalsRepository
import com.macrobase.app.domain.usecase.GetCalendarAdherenceUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
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
    private val goalsRepository: GoalsRepository
) : ViewModel() {

    private val _displayedYearMonth = MutableStateFlow(YearMonth.now())
    val displayedYearMonth: StateFlow<YearMonth> = _displayedYearMonth.asStateFlow()

    val uiState: StateFlow<CalendarUiState> = _displayedYearMonth.flatMapLatest { ym ->
        combine(
            getCalendarAdherenceUseCase.observe(ym.year, ym.monthValue),
            goalsRepository.observeGoals()
        ) { summaries, goals ->
            calculateMonthState(ym, summaries, goals)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CalendarUiState()
    )

    fun onPreviousMonth() {
        _displayedYearMonth.value = _displayedYearMonth.value.minusMonths(1)
    }

    fun onNextMonth() {
        _displayedYearMonth.value = _displayedYearMonth.value.plusMonths(1)
    }

    fun onSelectMonth(yearMonth: YearMonth) {
        _displayedYearMonth.value = yearMonth
    }

    private fun calculateMonthState(
        yearMonth: YearMonth,
        summaries: List<CalendarDaySummary>,
        goals: Goal
    ): CalendarUiState {
        val summariesMap = summaries.associateBy { it.date.dayOfMonth }

        val today = LocalDate.now()
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
                    calorieGoal = goals.dailyCalorieGoal,
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

            val category = if (isFuture && loggedCal <= 0.0) {
                CalendarPerformanceConfig.PerformanceCategory.EMPTY_MISSED
            } else {
                CalendarPerformanceConfig.evaluatePerformance(loggedCal, goals.dailyCalorieGoal)
            }

            cellModels.add(
                CalendarDayCellModel(
                    date = date,
                    dayNumber = d,
                    isCurrentMonth = true,
                    isToday = isToday,
                    isFuture = isFuture,
                    loggedCalories = loggedCal,
                    calorieGoal = goals.dailyCalorieGoal,
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
                    calorieGoal = goals.dailyCalorieGoal,
                    performanceCategory = CalendarPerformanceConfig.PerformanceCategory.EMPTY_MISSED
                )
            )
        }

        // Metrics Calculation:
        // Eligible days: past or today dates within the currently displayed month
        val currentMonthCells = cellModels.filter { it.isCurrentMonth }
        val eligiblePastOrTodayDays = currentMonthCells.filter { !it.date.isAfter(today) }

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
        modifier = modifier
    )
}

/**
 * Main Food Logging Calendar Screen Component matching UI_UX_SPECIFICATION.md
 */
@Composable
fun FoodLoggingCalendar(
    uiState: CalendarUiState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onDateClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Background)
            .padding(AppSpacing.lg)
            .verticalScroll(rememberScrollState())
    ) {
        Text(text = "Food Logging Calendar", style = AppTypography.Header1)
        Spacer(modifier = Modifier.height(AppSpacing.md))

        // Main Calendar Container Card (Surface #1E1E1E, Radius 8dp, Padding 16dp)
        Card(
            shape = AppShapes.Card,
            colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(AppSpacing.md)) {
                // 1. Month Navigation Header
                CalendarMonthHeader(
                    yearMonth = uiState.displayedYearMonth,
                    onPreviousMonth = onPreviousMonth,
                    onNextMonth = onNextMonth
                )

                Spacer(modifier = Modifier.height(AppSpacing.sm))

                // 2. Sunday–Saturday Weekday Headers
                CalendarWeekHeader()

                Spacer(modifier = Modifier.height(AppSpacing.sm))

                // 3. 7-Column Calendar Grid
                val rows = uiState.dayCells.chunked(7)
                rows.forEach { rowCells ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Dimensions.CalendarCellSpacing / 2),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        rowCells.forEach { cell ->
                            CalendarDayCell(
                                cell = cell,
                                onClick = { onDateClick(cell.date) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(AppSpacing.md))

                // 4. Centered 5-Step Performance Legend
                CalendarLegend(modifier = Modifier.align(Alignment.CenterHorizontally))

                Spacer(modifier = Modifier.height(AppSpacing.md))
                HorizontalDivider(color = AppColors.Divider)
                Spacer(modifier = Modifier.height(AppSpacing.md))

                // 5. Performance Summary Metrics Strip
                CalendarPerformanceSummary(
                    daysMissed = uiState.daysMissed,
                    greenPercentage = uiState.greenPercentage
                )

                Spacer(modifier = Modifier.height(AppSpacing.md))

                // 6. Helper Note Text
                Text(
                    text = "Tap on any date on the calendar to review or add foods.",
                    style = AppTypography.Caption,
                    color = AppColors.TextMuted,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}

/**
 * Month Navigation Bar with Sky Blue chevrons and Month/Year title.
 */
@Composable
fun CalendarMonthHeader(
    yearMonth: YearMonth,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    modifier: Modifier = Modifier
) {
    val monthFormatter = DateTimeFormatter.ofPattern("MMMM, yyyy")

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPreviousMonth) {
            Icon(
                imageVector = Icons.Default.ChevronLeft,
                contentDescription = "Previous Month",
                tint = AppColors.WaterCyan
            )
        }
        Text(
            text = yearMonth.format(monthFormatter),
            style = AppTypography.Header2
        )
        IconButton(onClick = onNextMonth) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Next Month",
                tint = AppColors.WaterCyan
            )
        }
    }
}

/**
 * Sunday–Saturday Weekday Headers.
 */
@Composable
fun CalendarWeekHeader(modifier: Modifier = Modifier) {
    val weekDays = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        weekDays.forEach { wd ->
            Text(
                text = wd,
                style = AppTypography.Caption,
                color = AppColors.TextSecondary,
                modifier = Modifier.width(Dimensions.CalendarCellSize),
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Responsive 40dp Calendar Day Cell with 4dp corner radius.
 */
@Composable
fun CalendarDayCell(
    cell: CalendarDayCellModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cellColor = if (!cell.isCurrentMonth) {
        AppColors.Background.copy(alpha = 0.3f)
    } else {
        CalendarPerformanceConfig.getColorForCategory(cell.performanceCategory)
    }

    val textColor = if (!cell.isCurrentMonth) {
        AppColors.TextMuted.copy(alpha = 0.3f)
    } else {
        AppColors.TextPrimary
    }

    Box(
        modifier = modifier
            .size(Dimensions.CalendarCellSize)
            .clip(RoundedCornerShape(4.dp))
            .background(cellColor)
            .clickable(enabled = cell.isCurrentMonth, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "${cell.dayNumber}",
            style = AppTypography.Header3,
            color = textColor,
            fontSize = 14.sp
        )
    }
}

/**
 * 5-Step Performance Legend centered with 8dp mini-squares.
 */
@Composable
fun CalendarLegend(modifier: Modifier = Modifier) {
    val colors = listOf(
        AppColors.CalendarEmpty,
        AppColors.CalendarGreenSubtle,
        AppColors.CalendarGreenOptimal,
        AppColors.CalendarRedWarning,
        AppColors.CalendarRedAlert
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        colors.forEach { color ->
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}

/**
 * Two-Column Performance Summary Metrics Strip.
 */
@Composable
fun CalendarPerformanceSummary(
    daysMissed: Int,
    greenPercentage: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "Days Missed", style = AppTypography.Caption, color = AppColors.TextSecondary)
            Spacer(modifier = Modifier.height(AppSpacing.xs))
            Text(
                text = "$daysMissed Days",
                style = AppTypography.Header1,
                color = if (daysMissed > 0) AppColors.TextPrimary else AppColors.CalorieText
            )
        }

        Box(
            modifier = Modifier
                .width(1.dp)
                .height(40.dp)
                .background(AppColors.Divider)
        )

        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "% Days of Green", style = AppTypography.Caption, color = AppColors.TextSecondary)
            Spacer(modifier = Modifier.height(AppSpacing.xs))
            Text(
                text = "$greenPercentage%",
                style = AppTypography.Header1,
                color = AppColors.CalorieText
            )
        }
    }
}
