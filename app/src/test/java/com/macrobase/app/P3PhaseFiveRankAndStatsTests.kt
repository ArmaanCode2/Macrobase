package com.macrobase.app

import com.macrobase.app.data.repository.StatisticsRepositoryImpl
import com.macrobase.app.data.repository.rank.RankRepositoryImpl
import com.macrobase.app.domain.model.FitnessGoal
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.rank.RankProfile
import com.macrobase.app.domain.model.windowStart
import com.macrobase.app.domain.repository.DailyMacroAggregation
import com.macrobase.app.domain.repository.GoalsRepository
import com.macrobase.app.domain.usecase.GetStatisticsUseCase
import com.macrobase.app.feature.statistics.ConsistencyInterval
import com.macrobase.app.feature.statistics.StatisticsViewModel
import com.macrobase.app.feature.weight.WeightTimeInterval
import com.macrobase.app.feature.weight.dateRange
import com.macrobase.app.testutil.MutableTestClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/**
 * P3 phase 5: scores count finished days only, and every "N days" window holds exactly N days
 * (BUG-032, BUG-033).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class P3PhaseFiveRankAndStatsTests {

    private val today = LocalDate.of(2026, 10, 9)
    private val clock = MutableTestClock(today, LocalTime.of(9, 0))

    private val maintaining = Goal(
        dailyCalorieGoal = 2000.0,
        carbPercentage = 50.0,
        proteinPercentage = 25.0,
        fatPercentage = 25.0,
        fitnessGoal = FitnessGoal.MAINTAINING,
        maintenanceCalories = 2000.0
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---------------------------------------------------------------------------------------
    // BUG-033: windows hold exactly N days; the score rounds
    // ---------------------------------------------------------------------------------------

    @Test
    fun aThirtyDayWindowIsTodayAndThe29DaysBefore() {
        assertEquals(today.minusDays(29), windowStart(today, 30))
        assertEquals(today, windowStart(today, 1))
        assertThrows(IllegalArgumentException::class.java) { windowStart(today, 0) }
    }

    // The Weight and Statistics screens share these, so "Last 30 days" means the same on both
    @Test
    fun weightRangesHoldNoExtraDay() {
        assertEquals(today.minusDays(29) to today, WeightTimeInterval.LAST_30_DAYS.dateRange(today, today, today))
        assertEquals(LocalDate.of(2026, 7, 10) to today, WeightTimeInterval.LAST_3_MONTHS.dateRange(today, today, today))
        assertEquals(LocalDate.of(2025, 10, 10) to today, WeightTimeInterval.ONE_YEAR.dateRange(today, today, today))
        val from = today.minusDays(5)
        assertEquals(from to today, WeightTimeInterval.CUSTOM.dateRange(today, from, today))
    }

    // Rounding must not show a missed day as 100% or a logged one as 0%
    @Test
    fun theScoreIs100OnlyWhenEveryDayWasLogged() = runBlocking {
        val year = windowStart(today, 365)
        // 365 days, today included, with one day missed
        val allButOne = statistics((0L..364L).filter { it != 100L }.map { perfectDay(today.minusDays(it)) })
            .getNutritionConsistency(year, today)
        val justOne = statistics(listOf(perfectDay(today.minusDays(1)))).getNutritionConsistency(year, today)

        assertEquals(364, allButOne.loggedDaysCount)
        assertEquals(99, allButOne.consistencyScorePercentage)
        assertEquals(1, justOne.consistencyScorePercentage)
    }

    @Test
    fun thirtyLoggedDaysOutOfThirtyIs100Percent() = runBlocking {
        val stats = statistics((0L..29L).map { perfectDay(today.minusDays(it)) })
            .getNutritionConsistency(windowStart(today, 30), today)

        assertEquals(30, stats.allCells.size)
        assertEquals(30, stats.totalEligibleDays)
        assertEquals(30, stats.loggedDaysCount)
        assertEquals(100, stats.consistencyScorePercentage)
    }

    // 29 / 30 = 96.67%, which truncation showed as 96
    @Test
    fun twentyNineOfThirtyRoundsTo97() = runBlocking {
        val logged = (0L..29L).filter { it != 10L }.map { perfectDay(today.minusDays(it)) }
        val stats = statistics(logged).getNutritionConsistency(windowStart(today, 30), today)

        assertEquals(29, stats.loggedDaysCount)
        assertEquals(97, stats.consistencyScorePercentage)
    }

    @Test
    fun anUnloggedTodayIsNotAMissedDay() = runBlocking {
        val stats = statistics((1L..29L).map { perfectDay(today.minusDays(it)) })
            .getNutritionConsistency(windowStart(today, 30), today)

        assertEquals(29, stats.totalEligibleDays)
        assertEquals(100, stats.consistencyScorePercentage)
        val todayCell = stats.allCells.single { it.date == today }
        assertFalse(todayCell.isEligible)
        assertFalse(todayCell.isFuture)
    }

    @Test
    fun aLoggedTodayCounts() = runBlocking {
        val stats = statistics(listOf(perfectDay(today))).getNutritionConsistency(windowStart(today, 7), today)

        assertEquals(7, stats.totalEligibleDays)
        assertEquals(1, stats.loggedDaysCount)
    }

    // An eighth day just outside the window would pull the average up if it were counted
    @Test
    fun theSevenDayMacroAverageCoversSevenDays() = runBlocking {
        val days = (0L..6L).map { perfectDay(today.minusDays(it)) } + perfectDay(today.minusDays(7)).copy(calories = 9000.0)
        val stats = statistics(days).getMacroAverages(windowStart(today, 7), today)

        assertEquals(7, stats.loggedDaysCount)
        assertEquals(2000.0, stats.averageCalories, 1e-9)
    }

    @Test
    fun theStatisticsScreenAsksForExactWindows() = runTest {
        val vm = StatisticsViewModel(
            GetStatisticsUseCase(statistics(emptyList())),
            P2PhaseFourRankReplayTests.Prefs(),
            clock
        )

        val state = withTimeout(5_000) { vm.uiState.first { it.consistencyStats != null && it.macroStats != null } }

        assertEquals(today.minusDays(29), state.consistencyStats!!.startDate)
        assertEquals(today, state.consistencyStats!!.endDate)
        // Averages: the 30 days that have ended, so today's partial day is left out
        assertEquals(today.minusDays(30), state.macroStats!!.startDate)
        assertEquals(today.minusDays(1), state.macroStats!!.endDate)
        assertEquals(today.minusDays(29), state.weightFromDate)
        assertEquals(today, state.weightToDate)
    }

    @Test
    fun theRankHistoryTabShowsTheRatingForTheChosenSpan() = runTest {
        val days = daysEndingYesterday(60)
        val vm = StatisticsViewModel(
            GetStatisticsUseCase(statistics(days)),
            P2PhaseFourRankReplayTests.Prefs(),
            clock,
            com.macrobase.app.domain.usecase.rank.GetRankProfileUseCase(rank(days, dispatcher = Dispatchers.Unconfined))
        )

        val month = withTimeout(5_000) { vm.rankHistory.first { !it.isLoading } }
        assertEquals(com.macrobase.app.feature.statistics.RankHistoryInterval.DAYS_30, month.interval)
        assertTrue(month.hasRank)
        assertEquals(30, month.points.size)
        assertEquals(today.minusDays(1), month.points.last().date)

        vm.onSelectRankInterval(com.macrobase.app.feature.statistics.RankHistoryInterval.ALL)
        val all = withTimeout(5_000) {
            vm.rankHistory.first { it.interval == com.macrobase.app.feature.statistics.RankHistoryInterval.ALL && !it.isLoading }
        }
        assertEquals(60, all.points.size)
    }

    // Without the rank, as in older tests, the tab is empty rather than broken
    @Test
    fun theRankHistoryTabIsEmptyWithoutARank() = runTest {
        val vm = StatisticsViewModel(GetStatisticsUseCase(statistics(emptyList())), P2PhaseFourRankReplayTests.Prefs(), clock)

        val state = withTimeout(5_000) { vm.rankHistory.first { !it.isLoading } }
        assertFalse(state.hasRank)
        assertTrue(state.points.isEmpty())
    }

    // Breakfast must not lower the 7-day average calories every morning
    @Test
    fun macroAveragesLeaveOutTodaysPartialDay() = runTest {
        val days = (1L..7L).map { perfectDay(today.minusDays(it)) } +
            DailyMacroAggregation(date = today, calories = 450.0, proteinGrams = 25.0, carbsGrams = 60.0, fatGrams = 12.0)
        val vm = StatisticsViewModel(GetStatisticsUseCase(statistics(days)), P2PhaseFourRankReplayTests.Prefs(), clock)
        vm.onSelectMacroInterval(com.macrobase.app.feature.statistics.MacroInterval.DAYS_7)

        val state = withTimeout(5_000) {
            vm.uiState.first { it.macroInterval == com.macrobase.app.feature.statistics.MacroInterval.DAYS_7 && it.macroStats != null }
        }

        assertEquals(7, state.macroStats!!.loggedDaysCount)
        assertEquals(2000.0, state.macroStats!!.averageCalories, 1e-9)
    }

    // "6 Months" and "1 Year" mean the same span on the consistency and weight tabs
    @Test
    fun monthAndYearWindowsMatchTheWeightTab() {
        assertEquals(WeightTimeInterval.LAST_6_MONTHS.dateRange(today, today, today).first, ConsistencyInterval.MONTHS_6.startDate(today))
        assertEquals(WeightTimeInterval.ONE_YEAR.dateRange(today, today, today).first, ConsistencyInterval.YEAR_1.startDate(today))
        assertEquals(today.minusDays(29), ConsistencyInterval.DAYS_30.startDate(today))
    }

    // ---------------------------------------------------------------------------------------
    // BUG-032: today is in progress and does not move the rating
    // ---------------------------------------------------------------------------------------

    @Test
    fun anEmptyTodayCostsNothingAndIsShownInProgress() = runBlocking {
        val profile = rank(daysEndingYesterday(29)).getRankProfile()

        val todayEntry = profile.recentHistory.first()
        assertEquals(today, todayEntry.date)
        assertTrue(todayEntry.isInProgress)
        assertEquals(0, todayEntry.rrDelta)
        assertEquals(todayEntry.ratingBefore, todayEntry.overallRating)
        val yesterday = profile.recentHistory.single { it.date == today.minusDays(1) }
        assertFalse(yesterday.isInProgress)
        assertEquals(yesterday.overallRating, profile.currentRating)
        assertEquals(29, profile.loggedDaysCount)
    }

    @Test
    fun loggingBreakfastLeavesTheRatingUnchanged() = runBlocking {
        val before = rank(daysEndingYesterday(29)).getRankProfile()
        val breakfast = DailyMacroAggregation(date = today, calories = 450.0, proteinGrams = 25.0, carbsGrams = 60.0, fatGrams = 12.0)
        val after = rank(daysEndingYesterday(29) + breakfast).getRankProfile()

        assertEquals(before.currentRating, after.currentRating)
        assertEquals(before.loggedDaysCount, after.loggedDaysCount)
        assertTrue(after.recentHistory.first().isInProgress)
    }

    // The preview is what the day gives once it ends
    @Test
    fun theDayCountsOnceItEndsByItsPreview() = runBlocking {
        val days = daysEndingYesterday(29) + perfectDay(today)
        val repo = rank(days)
        val tonight = repo.getRankProfile()
        val preview = tonight.recentHistory.first().rrDelta
        assertTrue(preview > 0)

        clock.advanceBy(Duration.ofDays(1))
        val tomorrow = repo.getRankProfile()

        val ended = tomorrow.recentHistory.single { it.date == today }
        assertFalse(ended.isInProgress)
        assertEquals(preview, ended.rrDelta)
        assertEquals(tonight.currentRating + preview, tomorrow.currentRating)
        assertEquals(30, tomorrow.loggedDaysCount)
    }

    @Test
    fun theSeventhDayEstablishesTheRatingOnlyOnceItEnds() = runBlocking {
        val repo = rank(daysEndingYesterday(6) + perfectDay(today))

        val tonight = repo.getRankProfile()
        assertTrue(tonight.isProvisional)
        assertEquals(6, tonight.loggedDaysCount)

        clock.advanceBy(Duration.ofDays(1))
        assertFalse(repo.getRankProfile().isProvisional)
    }

    // Ending the 7th or 14th logged day re-scores the earlier days too; the preview includes that
    @Test
    fun thePreviewIsTheWholeChangeWhenTheRatingIsEstablished() = runBlocking {
        for (pastDays in listOf(6, 13)) {
            val dayClock = MutableTestClock(today, LocalTime.of(9, 0))
            val repo = rank(daysEndingYesterday(pastDays) + perfectDay(today), dayClock)
            val tonight = repo.getRankProfile()

            dayClock.advanceBy(Duration.ofDays(1))
            val tomorrow = repo.getRankProfile()

            assertEquals("after $pastDays days", tomorrow.currentRating - tonight.currentRating, tonight.recentHistory.first().rrDelta)
        }
    }

    // The card shows ended days only, so breakfast does not lower it
    @Test
    fun theScoreCardIgnoresAHalfLoggedToday() = runBlocking {
        val before = rank(daysEndingYesterday(30)).getRankProfile()
        val breakfast = DailyMacroAggregation(date = today, calories = 450.0, proteinGrams = 25.0, carbsGrams = 60.0, fatGrams = 12.0)
        val after = rank(daysEndingYesterday(30) + breakfast).getRankProfile()

        assertEquals(before.lifestyleScore, after.lifestyleScore)
        assertEquals(100.0, after.lifestyleScore.consistencyScore, 0.0)
    }

    // A screen left open overnight: the same collection moves to the new day at midnight
    @Test
    fun aProfileKeptOpenPastMidnightCountsTheDayThatEnded() = runTest {
        clock.advanceBy(Duration.ofHours(14).plusMinutes(30)) // 23:30
        val repo = rank(daysEndingYesterday(29) + perfectDay(today), dispatcher = StandardTestDispatcher(testScheduler))
        val profiles = mutableListOf<RankProfile>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.observeRankProfile().toList(profiles) }
        runCurrent()
        assertEquals(29, profiles.last().loggedDaysCount)

        clock.advanceBy(Duration.ofMinutes(31))
        advanceTimeBy(Duration.ofMinutes(31).toMillis())
        runCurrent()

        assertEquals(30, profiles.last().loggedDaysCount)
        assertFalse(profiles.last().recentHistory.single { it.date == today }.isInProgress)
        assertTrue(profiles.last().recentHistory.first().isInProgress)
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private fun perfectDay(date: LocalDate) = DailyMacroAggregation(
        date = date,
        calories = maintaining.dailyCalorieGoal,
        proteinGrams = maintaining.proteinGrams,
        carbsGrams = maintaining.carbGrams,
        fatGrams = maintaining.fatGrams
    )

    private fun daysEndingYesterday(count: Int) = (count downTo 1).map { perfectDay(today.minusDays(it.toLong())) }

    private fun statistics(aggregations: List<DailyMacroAggregation>, statsClock: MutableTestClock = clock) = StatisticsRepositoryImpl(
        diaryRepository = P2PhaseFourRankReplayTests.Diary(aggregations),
        goalsRepository = FixedGoals(maintaining),
        weightRepository = P2PhaseFourRankReplayTests.Weights(emptyList()),
        clock = statsClock
    )

    private fun rank(
        aggregations: List<DailyMacroAggregation>,
        rankClock: MutableTestClock = clock,
        dispatcher: CoroutineDispatcher = Dispatchers.Default
    ) = RankRepositoryImpl(
        goalsRepository = FixedGoals(maintaining),
        diaryRepository = P2PhaseFourRankReplayTests.Diary(aggregations),
        clock = rankClock,
        dispatcher = dispatcher
    )

    private class FixedGoals(private val goal: Goal) : GoalsRepository {
        override suspend fun getGoals() = goal
        override fun observeGoals(): Flow<Goal> = flowOf(goal)
        override suspend fun updateGoals(goals: Goal) {}
    }
}
