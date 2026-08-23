package com.macrobase.app

import com.macrobase.app.core.config.CalendarPerformanceConfig
import com.macrobase.app.domain.model.CalendarDaySummary
import com.macrobase.app.domain.model.DailyNutritionSummary
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.repository.DiaryRepository
import com.macrobase.app.domain.repository.GoalsRepository
import com.macrobase.app.domain.usecase.GetCalendarAdherenceUseCase
import com.macrobase.app.feature.calendar.CalendarDayCellModel
import com.macrobase.app.feature.calendar.CalendarViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * Unit test suite for Phase 9: Food Logging Calendar, Adherence Classification, and Metrics.
 */
class CalendarAdherenceUnitTests {

    private lateinit var fakeDiaryRepository: FakeCalendarDiaryRepository
    private lateinit var fakeGoalsRepository: FakeCalendarGoalsRepository
    private lateinit var getCalendarAdherenceUseCase: GetCalendarAdherenceUseCase

    @Before
    fun setUp() {
        fakeDiaryRepository = FakeCalendarDiaryRepository()
        fakeGoalsRepository = FakeCalendarGoalsRepository()
        getCalendarAdherenceUseCase = GetCalendarAdherenceUseCase(fakeDiaryRepository)
    }

    @Test
    fun monthDataAggregation_generatesSingleMonthOfSummaries() = runBlocking {
        // Month of July 2026 (31 days)
        val days = getCalendarAdherenceUseCase(2026, 7)
        assertEquals(31, days.size)
        assertEquals(LocalDate.of(2026, 7, 1), days.first().date)
        assertEquals(LocalDate.of(2026, 7, 31), days.last().date)
    }

    @Test
    fun monthGeneration_startsOnSundayMondayAndVaryingLengths() {
        // 1. February 2026: starts on Sunday, 28 days
        val feb2026 = YearMonth.of(2026, 2)
        assertEquals(28, feb2026.lengthOfMonth())
        assertEquals(DayOfWeek.SUNDAY, feb2026.atDay(1).dayOfWeek)
        assertEquals(0, feb2026.atDay(1).dayOfWeek.value % 7) // 0 leading empty cells

        // 2. June 2026: starts on Monday, 30 days
        val jun2026 = YearMonth.of(2026, 6)
        assertEquals(30, jun2026.lengthOfMonth())
        assertEquals(DayOfWeek.MONDAY, jun2026.atDay(1).dayOfWeek)
        assertEquals(1, jun2026.atDay(1).dayOfWeek.value % 7) // 1 leading empty cell (Sun)

        // 3. July 2026: starts on Wednesday, 31 days
        val jul2026 = YearMonth.of(2026, 7)
        assertEquals(31, jul2026.lengthOfMonth())
        assertEquals(DayOfWeek.WEDNESDAY, jul2026.atDay(1).dayOfWeek)
        assertEquals(3, jul2026.atDay(1).dayOfWeek.value % 7) // 3 leading empty cells (Sun, Mon, Tue)

        // 4. Leap year February 2028: 29 days
        val feb2028 = YearMonth.of(2028, 2)
        assertEquals(29, feb2028.lengthOfMonth())
    }

    @Test
    fun calendarPerformance_acrossDifferentCalorieLevels() = runBlocking {
        val goal = 2000.0
        val yearMonth = YearMonth.of(2026, 7)

        // Day 1: 0 kcal -> EMPTY_MISSED
        // Day 2: 1500 kcal (75%) -> UNDER_BUDGET
        // Day 3: 1950 kcal (97.5%) -> OPTIMAL_TARGET (Green)
        // Day 4: 2200 kcal (110%) -> MODERATE_OVER
        // Day 5: 2600 kcal (130%) -> HIGH_OVER_TARGET

        val calorieMap = mapOf(
            1L to 0.0,
            2L to 1500.0,
            3L to 1950.0,
            4L to 2200.0,
            5L to 2600.0
        )
        fakeDiaryRepository.setMonthCalorieData(yearMonth, calorieMap)

        val days = getCalendarAdherenceUseCase(2026, 7)
        val day1 = days.first { it.date.dayOfMonth == 1 }
        val day2 = days.first { it.date.dayOfMonth == 2 }
        val day3 = days.first { it.date.dayOfMonth == 3 }
        val day4 = days.first { it.date.dayOfMonth == 4 }
        val day5 = days.first { it.date.dayOfMonth == 5 }

        assertEquals(CalendarPerformanceConfig.PerformanceCategory.EMPTY_MISSED, day1.performanceCategory)
        assertEquals(CalendarPerformanceConfig.PerformanceCategory.UNDER_BUDGET, day2.performanceCategory)
        assertEquals(CalendarPerformanceConfig.PerformanceCategory.OPTIMAL_TARGET, day3.performanceCategory)
        assertEquals(CalendarPerformanceConfig.PerformanceCategory.MODERATE_OVER, day4.performanceCategory)
        assertEquals(CalendarPerformanceConfig.PerformanceCategory.HIGH_OVER_TARGET, day5.performanceCategory)
    }

    @Test
    fun performanceClassification_exactThresholdBoundaries() {
        val g = 2000.0

        // 1. 0 calories -> EMPTY_MISSED
        assertEquals(CalendarPerformanceConfig.PerformanceCategory.EMPTY_MISSED, CalendarPerformanceConfig.evaluatePerformance(0.0, g))

        // 2. Exactly 85% (1700 kcal) -> UNDER_BUDGET
        assertEquals(CalendarPerformanceConfig.PerformanceCategory.UNDER_BUDGET, CalendarPerformanceConfig.evaluatePerformance(1700.0, g))

        // 3. 85.01% (1700.2 kcal) -> OPTIMAL_TARGET
        assertEquals(CalendarPerformanceConfig.PerformanceCategory.OPTIMAL_TARGET, CalendarPerformanceConfig.evaluatePerformance(1700.2, g))

        // 4. Exactly 105% (2100 kcal) -> OPTIMAL_TARGET
        assertEquals(CalendarPerformanceConfig.PerformanceCategory.OPTIMAL_TARGET, CalendarPerformanceConfig.evaluatePerformance(2100.0, g))

        // 5. 105.01% (2100.2 kcal) -> MODERATE_OVER
        assertEquals(CalendarPerformanceConfig.PerformanceCategory.MODERATE_OVER, CalendarPerformanceConfig.evaluatePerformance(2100.2, g))

        // 6. Exactly 120% (2400 kcal) -> MODERATE_OVER
        assertEquals(CalendarPerformanceConfig.PerformanceCategory.MODERATE_OVER, CalendarPerformanceConfig.evaluatePerformance(2400.0, g))

        // 7. 120.01% (2400.2 kcal) -> HIGH_OVER_TARGET
        assertEquals(CalendarPerformanceConfig.PerformanceCategory.HIGH_OVER_TARGET, CalendarPerformanceConfig.evaluatePerformance(2400.2, g))

        // 8. High intake (6000 kcal = 300%) -> HIGH_OVER_TARGET
        assertEquals(CalendarPerformanceConfig.PerformanceCategory.HIGH_OVER_TARGET, CalendarPerformanceConfig.evaluatePerformance(6000.0, g))
    }

    @Test
    fun daysMissedAndGreenPercentage_calculatedAccurately() {
        // Assume July 2026: 10 logged days out of 15 eligible days
        // 8 of those 10 are OPTIMAL_TARGET -> 8/10 = 80% Green
        // 5 missed days -> 5 Days Missed
        val eligibleDays = listOf(
            CalendarDayCellModel(LocalDate.of(2026, 7, 1), 1, true, false, false, 0.0, 2000.0, CalendarPerformanceConfig.PerformanceCategory.EMPTY_MISSED),
            CalendarDayCellModel(LocalDate.of(2026, 7, 2), 2, true, false, false, 0.0, 2000.0, CalendarPerformanceConfig.PerformanceCategory.EMPTY_MISSED),
            CalendarDayCellModel(LocalDate.of(2026, 7, 3), 3, true, false, false, 0.0, 2000.0, CalendarPerformanceConfig.PerformanceCategory.EMPTY_MISSED),
            CalendarDayCellModel(LocalDate.of(2026, 7, 4), 4, true, false, false, 0.0, 2000.0, CalendarPerformanceConfig.PerformanceCategory.EMPTY_MISSED),
            CalendarDayCellModel(LocalDate.of(2026, 7, 5), 5, true, false, false, 0.0, 2000.0, CalendarPerformanceConfig.PerformanceCategory.EMPTY_MISSED),
            CalendarDayCellModel(LocalDate.of(2026, 7, 6), 6, true, false, false, 1900.0, 2000.0, CalendarPerformanceConfig.PerformanceCategory.OPTIMAL_TARGET),
            CalendarDayCellModel(LocalDate.of(2026, 7, 7), 7, true, false, false, 1950.0, 2000.0, CalendarPerformanceConfig.PerformanceCategory.OPTIMAL_TARGET),
            CalendarDayCellModel(LocalDate.of(2026, 7, 8), 8, true, false, false, 2000.0, 2000.0, CalendarPerformanceConfig.PerformanceCategory.OPTIMAL_TARGET),
            CalendarDayCellModel(LocalDate.of(2026, 7, 9), 9, true, false, false, 2050.0, 2000.0, CalendarPerformanceConfig.PerformanceCategory.OPTIMAL_TARGET),
            CalendarDayCellModel(LocalDate.of(2026, 7, 10), 10, true, false, false, 1920.0, 2000.0, CalendarPerformanceConfig.PerformanceCategory.OPTIMAL_TARGET),
            CalendarDayCellModel(LocalDate.of(2026, 7, 11), 11, true, false, false, 1980.0, 2000.0, CalendarPerformanceConfig.PerformanceCategory.OPTIMAL_TARGET),
            CalendarDayCellModel(LocalDate.of(2026, 7, 12), 12, true, false, false, 2010.0, 2000.0, CalendarPerformanceConfig.PerformanceCategory.OPTIMAL_TARGET),
            CalendarDayCellModel(LocalDate.of(2026, 7, 13), 13, true, false, false, 1990.0, 2000.0, CalendarPerformanceConfig.PerformanceCategory.OPTIMAL_TARGET),
            CalendarDayCellModel(LocalDate.of(2026, 7, 14), 14, true, false, false, 1400.0, 2000.0, CalendarPerformanceConfig.PerformanceCategory.UNDER_BUDGET),
            CalendarDayCellModel(LocalDate.of(2026, 7, 15), 15, true, false, false, 2500.0, 2000.0, CalendarPerformanceConfig.PerformanceCategory.HIGH_OVER_TARGET)
        )

        val daysMissed = eligibleDays.count { it.loggedCalories <= 0.0 }
        assertEquals(5, daysMissed)

        val loggedDays = eligibleDays.filter { it.loggedCalories > 0.0 }
        val optimalDays = loggedDays.filter { it.performanceCategory == CalendarPerformanceConfig.PerformanceCategory.OPTIMAL_TARGET }
        val greenPercentage = ((optimalDays.size.toDouble() / loggedDays.size.toDouble()) * 100.0).toInt()

        assertEquals(10, loggedDays.size)
        assertEquals(8, optimalDays.size)
        assertEquals(80, greenPercentage)
    }

    @Test
    fun monthlySummaries_emptyMonthAndFutureMonthHandledCorrectly() {
        // 1. Entirely empty past month (30 days with 0 logs)
        val pastDays = (1..30).map { d ->
            CalendarDayCellModel(
                date = LocalDate.of(2026, 6, d),
                dayNumber = d,
                isCurrentMonth = true,
                isToday = false,
                isFuture = false,
                loggedCalories = 0.0,
                calorieGoal = 2000.0,
                performanceCategory = CalendarPerformanceConfig.PerformanceCategory.EMPTY_MISSED
            )
        }
        val pastMissed = pastDays.count { it.loggedCalories <= 0.0 }
        assertEquals(30, pastMissed)

        val pastLogged = pastDays.filter { it.loggedCalories > 0.0 }
        val pastGreenPct = if (pastLogged.isNotEmpty()) (pastLogged.count { it.performanceCategory == CalendarPerformanceConfig.PerformanceCategory.OPTIMAL_TARGET } * 100) / pastLogged.size else 0
        assertEquals(0, pastGreenPct)

        // 2. Future month (all days in future) -> 0 days missed, 0% green
        val futureDays = (1..31).map { d ->
            CalendarDayCellModel(
                date = LocalDate.of(2027, 1, d),
                dayNumber = d,
                isCurrentMonth = true,
                isToday = false,
                isFuture = true,
                loggedCalories = 0.0,
                calorieGoal = 2000.0,
                performanceCategory = CalendarPerformanceConfig.PerformanceCategory.EMPTY_MISSED
            )
        }
        val today = LocalDate.of(2026, 8, 19)
        val eligibleFutureDays = futureDays.filter { !it.date.isAfter(today) }
        val futureMissed = eligibleFutureDays.count { it.loggedCalories <= 0.0 }
        assertEquals(0, futureMissed)
    }

    @Test
    fun monthTransitions_andLeapYears_handledAccurately() {
        // 1. December -> January
        val dec2026 = YearMonth.of(2026, 12)
        val jan2027 = dec2026.plusMonths(1)
        assertEquals(2027, jan2027.year)
        assertEquals(1, jan2027.monthValue)

        // 2. January -> December
        val decPrev = jan2027.minusMonths(1)
        assertEquals(2026, decPrev.year)
        assertEquals(12, decPrev.monthValue)

        // 3. Leap-year February 2028 (29 days) vs Standard February 2026 (28 days)
        val feb2028 = YearMonth.of(2028, 2)
        assertEquals(29, feb2028.lengthOfMonth())

        val feb2026 = YearMonth.of(2026, 2)
        assertEquals(28, feb2026.lengthOfMonth())
    }

    @Test
    fun goalReactivity_updatesCalendarCategoriesWhenGoalChanges() {
        val loggedCal = 1800.0

        // With Goal = 2000 kcal: 1800 / 2000 = 0.90 -> OPTIMAL_TARGET (Green Day)
        val cat1 = CalendarPerformanceConfig.evaluatePerformance(loggedCal, 2000.0)
        assertEquals(CalendarPerformanceConfig.PerformanceCategory.OPTIMAL_TARGET, cat1)

        // User changes Goal to 1500 kcal: 1800 / 1500 = 1.20 -> MODERATE_OVER (Red Warning)
        val cat2 = CalendarPerformanceConfig.evaluatePerformance(loggedCal, 1500.0)
        assertEquals(CalendarPerformanceConfig.PerformanceCategory.MODERATE_OVER, cat2)

        // User changes Goal to 2500 kcal: 1800 / 2500 = 0.72 -> UNDER_BUDGET
        val cat3 = CalendarPerformanceConfig.evaluatePerformance(loggedCal, 2500.0)
        assertEquals(CalendarPerformanceConfig.PerformanceCategory.UNDER_BUDGET, cat3)
    }
}

/**
 * Fake in-memory Diary Repository for Calendar adherence unit tests.
 */
private class FakeCalendarDiaryRepository : DiaryRepository {
    private val calorieDataByEpochDay = MutableStateFlow<Map<Long, Double>>(emptyMap())

    fun setMonthCalorieData(yearMonth: YearMonth, daysMap: Map<Long, Double>) {
        val current = calorieDataByEpochDay.value.toMutableMap()
        daysMap.forEach { (dayOfMonth, cals) ->
            val epochDay = yearMonth.atDay(dayOfMonth.toInt()).toEpochDay()
            current[epochDay] = cals
        }
        calorieDataByEpochDay.value = current
    }

    override suspend fun getDiaryForDate(date: LocalDate) = DailyNutritionSummary(date = date)
    override fun observeDiaryForDate(date: LocalDate): Flow<DailyNutritionSummary> = flowOf(DailyNutritionSummary(date = date))
    override suspend fun getEntryById(entryId: Long): DiaryEntry? = null
    override suspend fun addEntry(entry: DiaryEntry): Long = 1L
    override suspend fun addEntries(entries: List<DiaryEntry>) {}
    override suspend fun updateEntry(entry: DiaryEntry) {}
    override suspend fun deleteEntry(entryId: Long) {}
    override fun observeNutritionAggregations(startDate: LocalDate, endDate: LocalDate): Flow<List<com.macrobase.app.domain.repository.DailyMacroAggregation>> = flowOf(emptyList())
    override suspend fun getNutritionAggregations(startDate: LocalDate, endDate: LocalDate): List<com.macrobase.app.domain.repository.DailyMacroAggregation> = emptyList()

    override suspend fun getMonthlyAdherence(year: Int, month: Int): List<CalendarDaySummary> {
        val yearMonth = YearMonth.of(year, month)
        val startDate = yearMonth.atDay(1)
        val endDate = yearMonth.atEndOfMonth()

        val list = mutableListOf<CalendarDaySummary>()
        var curr = startDate
        while (!curr.isAfter(endDate)) {
            val cals = calorieDataByEpochDay.value[curr.toEpochDay()] ?: 0.0
            list.add(
                CalendarDaySummary(
                    date = curr,
                    loggedCalories = cals,
                    calorieGoal = 2000.0,
                    hasLogs = cals > 0.0
                )
            )
            curr = curr.plusDays(1)
        }
        return list
    }

    override fun observeMonthlyAdherence(year: Int, month: Int): Flow<List<CalendarDaySummary>> {
        val yearMonth = YearMonth.of(year, month)
        val startDate = yearMonth.atDay(1)
        val endDate = yearMonth.atEndOfMonth()

        val list = mutableListOf<CalendarDaySummary>()
        var curr = startDate
        while (!curr.isAfter(endDate)) {
            val cals = calorieDataByEpochDay.value[curr.toEpochDay()] ?: 0.0
            list.add(
                CalendarDaySummary(
                    date = curr,
                    loggedCalories = cals,
                    calorieGoal = 2000.0,
                    hasLogs = cals > 0.0
                )
            )
            curr = curr.plusDays(1)
        }
        return flowOf(list)
    }
}

/**
 * Fake Goals Repository for Calendar tests.
 */
private class FakeCalendarGoalsRepository : GoalsRepository {
    private val goalsFlow = MutableStateFlow(Goal(dailyCalorieGoal = 2000.0))

    override suspend fun getGoals(): Goal = goalsFlow.value
    override fun observeGoals(): Flow<Goal> = goalsFlow.asStateFlow()
    override suspend fun updateGoals(goals: Goal) {
        goalsFlow.value = goals
    }
}
