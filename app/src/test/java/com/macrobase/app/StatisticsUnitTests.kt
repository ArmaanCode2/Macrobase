package com.macrobase.app

import com.macrobase.app.core.config.CalendarPerformanceConfig
import com.macrobase.app.domain.model.CalendarDaySummary
import com.macrobase.app.domain.model.ConsistencyStatistics
import com.macrobase.app.domain.model.DailyNutritionSummary
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.MacroAveragesStatistics
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.ServingUnit
import com.macrobase.app.domain.model.UserPreferences
import com.macrobase.app.domain.model.WeightEntry
import com.macrobase.app.domain.model.WeightStatistics
import com.macrobase.app.domain.repository.DailyMacroAggregation
import com.macrobase.app.domain.repository.DiaryRepository
import com.macrobase.app.domain.repository.GoalsRepository
import com.macrobase.app.domain.repository.PreferencesRepository
import com.macrobase.app.domain.repository.StatisticsRepository
import com.macrobase.app.domain.repository.WeightRepository
import com.macrobase.app.data.repository.StatisticsRepositoryImpl
import com.macrobase.app.domain.usecase.GetStatisticsUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Comprehensive Unit Test Suite for Phase 12: Statistics & Food Logging Heatmap.
 */
class StatisticsUnitTests {

    private lateinit var fakeDiaryRepository: FakeStatisticsDiaryRepository
    private lateinit var fakeGoalsRepository: FakeStatisticsGoalsRepository
    private lateinit var fakeWeightRepository: FakeStatisticsWeightRepository
    private lateinit var statisticsRepository: StatisticsRepository
    private lateinit var getStatisticsUseCase: GetStatisticsUseCase

    @Before
    fun setUp() {
        fakeDiaryRepository = FakeStatisticsDiaryRepository()
        fakeGoalsRepository = FakeStatisticsGoalsRepository()
        fakeWeightRepository = FakeStatisticsWeightRepository()

        statisticsRepository = StatisticsRepositoryImpl(
            diaryRepository = fakeDiaryRepository,
            goalsRepository = fakeGoalsRepository,
            weightRepository = fakeWeightRepository
        )

        getStatisticsUseCase = GetStatisticsUseCase(statisticsRepository)
    }

    @Test
    fun consistencyScore_calculatesCorrectlyAcrossScenarios() = runBlocking {
        val today = LocalDate.of(2026, 8, 19)
        val startDate = today.minusDays(29) // 30 days total

        // 1. 0 logged / 30 eligible -> 0%
        val stats0 = getStatisticsUseCase.getNutritionConsistency(startDate, today)
        assertEquals(30, stats0.totalEligibleDays)
        assertEquals(0, stats0.loggedDaysCount)
        assertEquals(0, stats0.consistencyScorePercentage)

        // 2. 15 logged / 30 eligible -> 50%
        for (i in 0 until 15) {
            val d = startDate.plusDays(i.toLong())
            fakeDiaryRepository.addMacroLog(d, calories = 2000.0, protein = 120.0, carbs = 220.0, fat = 60.0)
        }
        val stats15 = getStatisticsUseCase.getNutritionConsistency(startDate, today)
        assertEquals(30, stats15.totalEligibleDays)
        assertEquals(15, stats15.loggedDaysCount)
        assertEquals(50, stats15.consistencyScorePercentage)

        // 3. 28 logged / 30 eligible -> 93% (or 94% rounded)
        for (i in 15 until 28) {
            val d = startDate.plusDays(i.toLong())
            fakeDiaryRepository.addMacroLog(d, calories = 1950.0, protein = 130.0, carbs = 200.0, fat = 55.0)
        }
        val stats28 = getStatisticsUseCase.getNutritionConsistency(startDate, today)
        assertEquals(30, stats28.totalEligibleDays)
        assertEquals(28, stats28.loggedDaysCount)
        assertEquals(93, stats28.consistencyScorePercentage)
    }

    @Test
    fun consistencyHeatmap_excludesFutureDaysFromEligible() = runBlocking {
        val today = LocalDate.now()
        val fromDate = today.minusDays(10)
        val toDate = today.plusDays(10) // 10 days into the future

        val stats = getStatisticsUseCase.getNutritionConsistency(fromDate, toDate)

        // Eligible days should only be fromDate to today (11 days: today - 10 to today)
        assertEquals(11, stats.totalEligibleDays)

        // Verify future cells are flagged
        val futureCells = stats.allCells.filter { it.isFuture }
        assertEquals(10, futureCells.size)
        futureCells.forEach {
            assertFalse(it.isEligible)
            assertEquals(CalendarPerformanceConfig.PerformanceCategory.EMPTY_MISSED, it.category)
        }
    }

    @Test
    fun heatmapPerformanceCategory_evaluatesAdherenceCorrectly() {
        val goal = 2000.0

        // 0 cal -> EMPTY_MISSED
        assertEquals(
            CalendarPerformanceConfig.PerformanceCategory.EMPTY_MISSED,
            CalendarPerformanceConfig.evaluatePerformance(0.0, goal)
        )

        // 1500 cal (75%) -> UNDER_BUDGET (<= 85%)
        assertEquals(
            CalendarPerformanceConfig.PerformanceCategory.UNDER_BUDGET,
            CalendarPerformanceConfig.evaluatePerformance(1500.0, goal)
        )

        // 1900 cal (95%) -> OPTIMAL_TARGET (85% .. 105%)
        assertEquals(
            CalendarPerformanceConfig.PerformanceCategory.OPTIMAL_TARGET,
            CalendarPerformanceConfig.evaluatePerformance(1900.0, goal)
        )

        // 2300 cal (115%) -> MODERATE_OVER (105% .. 120%)
        assertEquals(
            CalendarPerformanceConfig.PerformanceCategory.MODERATE_OVER,
            CalendarPerformanceConfig.evaluatePerformance(2300.0, goal)
        )

        // 2600 cal (130%) -> HIGH_OVER_TARGET (> 120%)
        assertEquals(
            CalendarPerformanceConfig.PerformanceCategory.HIGH_OVER_TARGET,
            CalendarPerformanceConfig.evaluatePerformance(2600.0, goal)
        )
    }

    @Test
    fun macroAverages_calculatesAveragesAndActualDistribution() = runBlocking {
        // Deterministic Prompt Test:
        // Protein = 100g -> 400 kcal
        // Carbs = 200g -> 800 kcal
        // Fat = 50g -> 450 kcal
        // Total = 1650 kcal
        val d1 = LocalDate.of(2026, 8, 1)
        val d2 = LocalDate.of(2026, 8, 2)

        fakeDiaryRepository.addMacroLog(d1, calories = 1650.0, protein = 100.0, carbs = 200.0, fat = 50.0)
        fakeDiaryRepository.addMacroLog(d2, calories = 1650.0, protein = 100.0, carbs = 200.0, fat = 50.0)

        val stats = getStatisticsUseCase.getMacroAverages(d1, d2)

        assertEquals(2, stats.loggedDaysCount)
        assertEquals(1650.0, stats.averageCalories, 0.001)
        assertEquals(100.0, stats.averageProteinGrams, 0.001)
        assertEquals(200.0, stats.averageCarbsGrams, 0.001)
        assertEquals(50.0, stats.averageFatGrams, 0.001)

        // Actual distribution percentages:
        // P = (400 / 1650) * 100 = 24.24%
        // C = (800 / 1650) * 100 = 48.48%
        // F = (450 / 1650) * 100 = 27.27%
        assertEquals(24.24, stats.actualProteinPercent, 0.01)
        assertEquals(48.48, stats.actualCarbsPercent, 0.01)
        assertEquals(27.27, stats.actualFatPercent, 0.01)

        // Target distribution from Goal (Default 50% Carbs, 25% Protein, 25% Fat, 2000 kcal)
        assertEquals(25.0, stats.targetProteinPercent, 0.001)
        assertEquals(50.0, stats.targetCarbsPercent, 0.001)
        assertEquals(25.0, stats.targetFatPercent, 0.001)
    }

    @Test
    fun targetComparison_reactsToGoalChanges() = runBlocking {
        val d = LocalDate.of(2026, 8, 10)
        fakeDiaryRepository.addMacroLog(d, calories = 1800.0, protein = 120.0, carbs = 180.0, fat = 60.0)

        // Update target to 40% Carbs, 35% Protein, 25% Fat, 1800 kcal
        fakeGoalsRepository.updateGoals(
            Goal(
                dailyCalorieGoal = 1800.0,
                carbPercentage = 40.0,
                proteinPercentage = 35.0,
                fatPercentage = 25.0
            )
        )

        val stats = getStatisticsUseCase.getMacroAverages(d, d)
        assertEquals(35.0, stats.targetProteinPercent, 0.001)
        assertEquals(40.0, stats.targetCarbsPercent, 0.001)
        assertEquals(25.0, stats.targetFatPercent, 0.001)
        assertEquals(1800.0, stats.targetCalories, 0.001)
    }

    @Test
    fun weightStatistics_matchesWeightTrackerValues() = runBlocking {
        // Dataset from Phase 11:
        // Day 1: 83.6 kg
        // Day 2: 82.9 kg
        // Day 3: 82.2 kg
        // Day 4: 81.7 kg
        // Day 5: 80.9 kg
        val day1 = LocalDate.of(2026, 8, 1)
        val day2 = LocalDate.of(2026, 8, 2)
        val day3 = LocalDate.of(2026, 8, 3)
        val day4 = LocalDate.of(2026, 8, 4)
        val day5 = LocalDate.of(2026, 8, 5)

        fakeWeightRepository.addWeightEntry(WeightEntry(date = day1, weightKg = 83.6))
        fakeWeightRepository.addWeightEntry(WeightEntry(date = day2, weightKg = 82.9))
        fakeWeightRepository.addWeightEntry(WeightEntry(date = day3, weightKg = 82.2))
        fakeWeightRepository.addWeightEntry(WeightEntry(date = day4, weightKg = 81.7))
        fakeWeightRepository.addWeightEntry(WeightEntry(date = day5, weightKg = 80.9))

        val stats = getStatisticsUseCase.getWeightStatistics(day1, day5)

        assertEquals(83.6, stats.initialWeightKg!!, 0.001)
        assertEquals(80.9, stats.latestWeightKg!!, 0.001)
        assertEquals(-2.7, stats.deltaWeightKg!!, 0.001)
        assertEquals(-3.23, stats.percentageChange!!, 0.01)
        assertEquals(5, stats.entries.size)
        assertTrue(stats.hasSufficientData)
    }

    @Test
    fun emptyMacroPeriod_handlesZeroDataGracefully() = runBlocking {
        val d1 = LocalDate.of(2026, 1, 1)
        val d2 = LocalDate.of(2026, 1, 7)

        val stats = getStatisticsUseCase.getMacroAverages(d1, d2)
        assertEquals(0, stats.loggedDaysCount)
        assertEquals(0.0, stats.averageCalories, 0.001)
        assertEquals(0.0, stats.actualProteinPercent, 0.001)
        assertFalse(stats.hasSufficientData)
    }
}

/**
 * In-memory fake DiaryRepository for statistics testing.
 */
private class FakeStatisticsDiaryRepository : DiaryRepository {
    private val aggregationsFlow = MutableStateFlow<Map<LocalDate, DailyMacroAggregation>>(emptyMap())

    fun addMacroLog(date: LocalDate, calories: Double, protein: Double, carbs: Double, fat: Double) {
        aggregationsFlow.update {
            it + (date to DailyMacroAggregation(date, calories, protein, carbs, fat))
        }
    }

    override fun observeNutritionAggregations(startDate: LocalDate, endDate: LocalDate): Flow<List<DailyMacroAggregation>> {
        return aggregationsFlow.asStateFlow().map { map ->
            map.values.filter { !it.date.isBefore(startDate) && !it.date.isAfter(endDate) }.sortedBy { it.date }
        }
    }

    override suspend fun getNutritionAggregations(startDate: LocalDate, endDate: LocalDate): List<DailyMacroAggregation> {
        return observeNutritionAggregations(startDate, endDate).first()
    }

    override suspend fun getDiaryForDate(date: LocalDate): DailyNutritionSummary = DailyNutritionSummary(date = date)
    override fun observeDiaryForDate(date: LocalDate): Flow<DailyNutritionSummary> = MutableStateFlow(DailyNutritionSummary(date = date))
    override suspend fun getEntryById(entryId: Long): DiaryEntry? = null
    override suspend fun addEntry(entry: DiaryEntry): Long = 1L
    override suspend fun addEntries(entries: List<DiaryEntry>) {}
    override suspend fun updateEntry(entry: DiaryEntry) {}
    override suspend fun deleteEntry(entryId: Long) {}
    override suspend fun getMonthlyAdherence(year: Int, month: Int): List<CalendarDaySummary> = emptyList()
    override fun observeMonthlyAdherence(year: Int, month: Int): Flow<List<CalendarDaySummary>> = MutableStateFlow(emptyList())
}

/**
 * In-memory fake GoalsRepository for statistics testing.
 */
private class FakeStatisticsGoalsRepository : GoalsRepository {
    private val goalsFlow = MutableStateFlow(Goal(dailyCalorieGoal = 2000.0, carbPercentage = 50.0, proteinPercentage = 25.0, fatPercentage = 25.0))

    override suspend fun getGoals(): Goal = goalsFlow.value
    override fun observeGoals(): Flow<Goal> = goalsFlow.asStateFlow()
    override suspend fun updateGoals(goals: Goal) {
        goalsFlow.value = goals
    }
}

/**
 * In-memory fake WeightRepository for statistics testing.
 */
private class FakeStatisticsWeightRepository : WeightRepository {
    private val weightsFlow = MutableStateFlow<Map<LocalDate, WeightEntry>>(emptyMap())

    override suspend fun addWeightEntry(entry: WeightEntry): Long {
        weightsFlow.update { it + (entry.date to entry) }
        return 1L
    }

    override suspend fun updateWeightEntry(entry: WeightEntry) {
        addWeightEntry(entry)
    }

    override suspend fun deleteWeightEntry(entryId: Long) {}
    override suspend fun deleteWeightEntryForDate(date: LocalDate) {
        weightsFlow.update { it - date }
    }

    override fun observeWeightForDate(date: LocalDate): Flow<WeightEntry?> = weightsFlow.map { it[date] }
    override suspend fun getWeightForDate(date: LocalDate): WeightEntry? = weightsFlow.value[date]
    override fun observeWeightHistory(): Flow<List<WeightEntry>> = weightsFlow.map { it.values.toList() }
    override suspend fun getWeightHistory(): List<WeightEntry> = weightsFlow.value.values.toList()

    override fun observeWeightRange(startDate: LocalDate, endDate: LocalDate): Flow<List<WeightEntry>> {
        return weightsFlow.asStateFlow().map { map ->
            map.values.filter { !it.date.isBefore(startDate) && !it.date.isAfter(endDate) }.sortedBy { it.date }
        }
    }

    override suspend fun getWeightRange(startDate: LocalDate, endDate: LocalDate): List<WeightEntry> {
        return observeWeightRange(startDate, endDate).first()
    }
}
