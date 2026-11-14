package com.macrobase.app

import androidx.room.Room
import com.macrobase.app.core.config.PatternsConfig
import com.macrobase.app.data.database.UserDatabase
import com.macrobase.app.data.database.entity.DiaryEntryEntity
import com.macrobase.app.data.repository.DiaryRepositoryImpl
import com.macrobase.app.data.repository.StatisticsRepositoryImpl
import com.macrobase.app.domain.model.CalendarDaySummary
import com.macrobase.app.domain.model.DailyNutritionSummary
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.PatternFinding
import com.macrobase.app.domain.model.PatternsReport
import com.macrobase.app.domain.model.WeightEntry
import com.macrobase.app.domain.model.findPatterns
import com.macrobase.app.domain.repository.DailyMacroAggregation
import com.macrobase.app.domain.repository.DiaryRepository
import com.macrobase.app.domain.repository.GoalsRepository
import com.macrobase.app.domain.repository.LoggedMealNutrients
import com.macrobase.app.domain.repository.WeightRepository
import com.macrobase.app.domain.usecase.GetStatisticsUseCase
import com.macrobase.app.feature.statistics.StatisticsViewModel
import com.macrobase.app.feature.statistics.patternText
import com.macrobase.app.feature.statistics.patternsNeedMoreText
import com.macrobase.app.testutil.MutableTestClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate

/** Stats idea 8: plain-language patterns from the 28 days that ended. */
private val PATTERNS_TODAY: LocalDate = LocalDate.of(2026, 11, 14) // a Saturday

/** The window: the 28 days up to yesterday, Saturday 17 Oct to Friday 13 Nov, four of each weekday. */
private val WINDOW_START: LocalDate = PATTERNS_TODAY.minusDays(28)
private val WINDOW_DAYS: List<LocalDate> = (0L until 28L).map { WINDOW_START.plusDays(it) }

private fun daysOn(weekday: DayOfWeek) = WINDOW_DAYS.filter { it.dayOfWeek == weekday }
private fun daysNotOn(weekday: DayOfWeek) = WINDOW_DAYS.filter { it.dayOfWeek != weekday }

private fun food(
    date: LocalDate,
    meal: MealType = MealType.LUNCH,
    kcal: Double = 2000.0,
    protein: Double = 100.0,
    sugar: Double? = null,
    sodium: Double? = null
) = LoggedMealNutrients(date, meal, kcal, protein, sugar, sodium)

private fun patternsSource(path: String): String =
    listOf(File("src/main/java/com/macrobase/app/$path"), File("app/src/main/java/com/macrobase/app/$path"))
        .first { it.isFile }.readText().replace("\r\n", "\n")

private fun PatternsReport.only(): PatternFinding {
    assertEquals(findings.toString(), 1, findings.size)
    return findings.single()
}

/** The thresholds are named in one place, with the plan's values. */
class PatternsConfigTests {

    @Test
    fun theThresholdsAreThePlans() {
        assertEquals(28L, PatternsConfig.WINDOW_DAYS)
        assertEquals(10, PatternsConfig.MIN_COUNTED_DAYS)
        assertEquals(4, PatternsConfig.MAX_FINDINGS)
        assertEquals(5, PatternsConfig.MIN_MEAL_DAYS)
        assertEquals(0.5, PatternsConfig.LOW_PROTEIN_MEAL_SHARE, 0.0)
        assertEquals(2, PatternsConfig.MIN_WEEKDAY_DAYS)
        assertEquals(0.15, PatternsConfig.HIGH_CALORIE_WEEKDAY_EXCESS, 0.0)
        assertEquals(0.60, PatternsConfig.MIN_STATED_COVERAGE, 0.0)
        assertEquals(0.25, PatternsConfig.NUTRIENT_PEAK_EXCESS, 0.0)
    }
}

/** At least 10 counted days, or the user is told how many more to log. */
class PatternsMinimumDataTests {

    @Test
    fun fewerThanTenDaysAskForTheRest() {
        val nine = findPatterns(WINDOW_DAYS.take(9).map { food(it) }, finishedDays = null)
        val three = findPatterns(WINDOW_DAYS.take(3).map { food(it) }, finishedDays = null)

        assertEquals(9, nine.countedDays)
        assertEquals(1, nine.daysNeeded)
        assertTrue(nine.findings.isEmpty())
        assertEquals("Log 1 more day to see your patterns.", patternsNeedMoreText(nine.daysNeeded, finishedDaysOnly = false))
        assertEquals("Log 7 more days to see your patterns.", patternsNeedMoreText(three.daysNeeded, finishedDaysOnly = false))
        assertEquals(0, findPatterns(WINDOW_DAYS.take(10).map { food(it) }, finishedDays = null).daysNeeded)
    }

    // A day of 0 kcal entries only (black coffee) has no food, as for the averages
    @Test
    fun aDayWithoutCaloriesDoesNotCount() {
        val entries = WINDOW_DAYS.take(9).map { food(it) } + food(WINDOW_DAYS[9], kcal = 0.0, protein = 0.0)

        assertEquals(9, findPatterns(entries, finishedDays = null).countedDays)
    }

    @Test
    fun finishedModeCountsTheFinishedDaysOnly() {
        val entries = WINDOW_DAYS.take(12).map { food(it) }

        val report = findPatterns(entries, finishedDays = WINDOW_DAYS.take(9).toSet() + PATTERNS_TODAY)

        assertEquals(9, report.countedDays)
        assertEquals(
            "Mark 1 more day as done on Home to see your patterns.",
            patternsNeedMoreText(report.daysNeeded, finishedDaysOnly = true)
        )
        assertEquals(
            "Mark 4 more days as done on Home to see your patterns.",
            patternsNeedMoreText(4, finishedDaysOnly = true)
        )
    }

    // Unfinished days are left out of the findings too, not only the count
    @Test
    fun finishedModeLeavesUnfinishedDaysOutOfTheFindings() {
        val finished = WINDOW_DAYS.take(10)
        val unfinished = WINDOW_DAYS.drop(10).take(5)
        val entries = finished.flatMap {
            listOf(food(it, MealType.BREAKFAST, kcal = 500.0, protein = 9.0), food(it, MealType.LUNCH, kcal = 1500.0, protein = 24.0))
        } + unfinished.flatMap {
            listOf(food(it, MealType.BREAKFAST, kcal = 500.0, protein = 30.0), food(it, MealType.LUNCH, kcal = 1500.0, protein = 24.0))
        }

        val finishedOnly = findPatterns(entries, finishedDays = finished.toSet())
        val logged = findPatterns(entries, finishedDays = null)

        assertEquals(PatternFinding.LowProteinMeal(MealType.BREAKFAST, 9.0, MealType.LUNCH, 24.0), finishedOnly.only())
        // Over all 15 days breakfast averages 16 g, not under half of lunch's 24 g
        assertTrue(logged.findings.isEmpty())
    }
}

/** Finding 1: the meal with the least protein, when it is under half the highest. */
class PatternsProteinTests {

    /** Every day 2,000 kcal; breakfast on [breakfastDays] of them. */
    private fun days(breakfastProtein: Double, breakfastDays: Int, lunchProtein: Double = 24.0) =
        WINDOW_DAYS.take(10).flatMapIndexed { index, date ->
            if (index < breakfastDays) {
                listOf(food(date, MealType.BREAKFAST, kcal = 500.0, protein = breakfastProtein), food(date, MealType.LUNCH, kcal = 1500.0, protein = lunchProtein))
            } else {
                listOf(food(date, MealType.LUNCH, kcal = 2000.0, protein = lunchProtein))
            }
        }

    @Test
    fun aMealUnderHalfTheHighestIsShown() {
        val finding = findPatterns(days(breakfastProtein = 9.0, breakfastDays = 5), finishedDays = null).only()

        assertEquals(PatternFinding.LowProteinMeal(MealType.BREAKFAST, 9.0, MealType.LUNCH, 24.0), finding)
        assertEquals("Breakfast gives you the least protein: 9 g on average (lunch 24 g).", patternText(finding))
    }

    @Test
    fun exactlyHalfIsNotShown() {
        assertTrue(findPatterns(days(breakfastProtein = 12.0, breakfastDays = 5), finishedDays = null).findings.isEmpty())
        // Just under half is
        assertEquals(1, findPatterns(days(breakfastProtein = 11.9, breakfastDays = 5), finishedDays = null).findings.size)
    }

    @Test
    fun aMealLoggedOnFewerThanFiveDaysIsLeftOut() {
        assertTrue(findPatterns(days(breakfastProtein = 2.0, breakfastDays = 4), finishedDays = null).findings.isEmpty())
    }

    // A meal's protein is its foods' total that day, averaged over the days it was logged
    @Test
    fun aMealsFoodsAddUpForTheDay() {
        val entries = WINDOW_DAYS.take(10).flatMapIndexed { index, date ->
            val lunch = food(date, MealType.LUNCH, kcal = 1500.0, protein = 30.0)
            if (index < 5) {
                listOf(food(date, MealType.BREAKFAST, kcal = 250.0, protein = 4.0), food(date, MealType.BREAKFAST, kcal = 250.0, protein = 5.0), lunch)
            } else {
                listOf(lunch, food(date, MealType.LUNCH, kcal = 500.0, protein = 0.0))
            }
        }

        val finding = findPatterns(entries, finishedDays = null).only() as PatternFinding.LowProteinMeal

        assertEquals(9.0, finding.averageGrams, 1e-9)
        assertEquals(30.0, finding.highestGrams, 1e-9)
    }

    // "AM snack" keeps its capitals in the middle of a sentence
    @Test
    fun theSecondMealReadsLowercaseUnlessItIsCapitalised() {
        assertEquals(
            "Late snack gives you the least protein: 3 g on average (AM snack 21 g).",
            patternText(PatternFinding.LowProteinMeal(MealType.SNACK, 3.2, MealType.AM_SNACK, 20.6))
        )
    }
}

/** Finding 2: the weekday that runs at least 15% above the daily average. */
class PatternsCalorieWeekdayTests {

    /** Two Saturdays at [saturday] kcal and eight other days making the average 2,000. */
    private fun days(saturday: Double): List<LoggedMealNutrients> {
        val others = (20_000.0 - 2 * saturday) / 8
        return daysOn(DayOfWeek.SATURDAY).take(2).map { food(it, kcal = saturday) } +
            daysNotOn(DayOfWeek.SATURDAY).take(8).map { food(it, kcal = others) }
    }

    @Test
    fun fifteenPercentAboveIsShown() {
        val finding = findPatterns(days(saturday = 2300.0), finishedDays = null).only()

        assertEquals(PatternFinding.HighCalorieWeekday(DayOfWeek.SATURDAY, 2300.0, 2000.0), finding)
        assertEquals("Saturdays run 15% above your average: 2,300 vs 2,000 kcal.", patternText(finding))
    }

    @Test
    fun justUnderFifteenPercentIsNotShown() {
        assertTrue(findPatterns(days(saturday = 2290.0), finishedDays = null).findings.isEmpty())
    }

    @Test
    fun aWeekdayWithOneCountedDayIsLeftOut() {
        val entries = listOf(food(daysOn(DayOfWeek.SATURDAY).first(), kcal = 5000.0)) +
            daysNotOn(DayOfWeek.SATURDAY).take(9).map { food(it, kcal = 2000.0) }

        assertTrue(findPatterns(entries, finishedDays = null).findings.isEmpty())
    }

    @Test
    fun theTextMatchesThePlansExample() {
        assertEquals(
            "Saturdays run 22% above your average: 2,450 vs 2,010 kcal.",
            patternText(PatternFinding.HighCalorieWeekday(DayOfWeek.SATURDAY, 2450.0, 2010.0))
        )
    }
}

/** Finding 3: the weekday whose known sugar per 1,000 kcal is at least 25% above the overall rate. */
class PatternsSugarWeekdayTests {

    /** Two Sundays with [sunday] and eight other days with 30 g of sugar, all 2,000 kcal. */
    private fun days(sunday: List<(LocalDate) -> LoggedMealNutrients>) =
        daysOn(DayOfWeek.SUNDAY).take(2).flatMap { date -> sunday.map { it(date) } } +
            daysNotOn(DayOfWeek.SUNDAY).take(8).map { food(it, sugar = 30.0) }

    @Test
    fun twentyFivePercentAboveIsShown() {
        // Sunday 20 g per 1,000 kcal against 16 g overall
        val finding = findPatterns(days(listOf { food(it, sugar = 40.0) }), finishedDays = null).only()

        assertEquals(PatternFinding.SugarPeakWeekday(DayOfWeek.SUNDAY, 20.0, 16.0), finding)
        assertEquals("Sugar peaks on Sundays.", patternText(finding))
    }

    @Test
    fun justUnderTwentyFivePercentIsNotShown() {
        assertTrue(findPatterns(days(listOf { food(it, sugar = 39.0) }), finishedDays = null).findings.isEmpty())
    }

    // Stated foods must cover at least 60% of the weekday's calories
    @Test
    fun lowCoverageIsSkippedRatherThanShownWrong() {
        val under = days(listOf({ food(it, kcal = 1100.0, sugar = 40.0) }, { food(it, kcal = 900.0, sugar = null) }))
        val atLimit = days(listOf({ food(it, kcal = 1200.0, sugar = 40.0) }, { food(it, kcal = 800.0, sugar = null) }))

        assertTrue(findPatterns(under, finishedDays = null).findings.isEmpty())
        assertTrue(findPatterns(atLimit, finishedDays = null).only() is PatternFinding.SugarPeakWeekday)
    }

    // A food that does not state sugar is left out, never counted as 0 g
    @Test
    fun anUnstatedSugarIsNotZero() {
        val entries = daysOn(DayOfWeek.SUNDAY).take(2).flatMap { listOf(food(it, kcal = 1300.0, sugar = 26.0), food(it, kcal = 700.0, sugar = null)) } +
            daysNotOn(DayOfWeek.SUNDAY).take(8).map { food(it, sugar = 30.0) }

        val finding = findPatterns(entries, finishedDays = null).only() as PatternFinding.SugarPeakWeekday

        assertEquals(20.0, finding.sugarPer1000Kcal, 1e-9)
        assertEquals(292.0 / 18_600.0 * 1000.0, finding.overallPer1000Kcal, 1e-9)
    }
}

/** Finding 4: the meal whose known sodium per 1,000 kcal is at least 25% above the overall rate. */
class PatternsSodiumMealTests {

    /** Ten days of lunch (1,600 kcal, 1,200 mg) and dinner (400 kcal, [dinnerSodium]) on [dinnerDays] of them. */
    private fun days(dinnerSodium: Double?, dinnerDays: Int = 10, dinnerCoverage: Double = 1.0) =
        WINDOW_DAYS.take(10).flatMapIndexed { index, date ->
            val lunch = food(date, MealType.LUNCH, kcal = 1600.0, protein = 20.0, sodium = 1200.0)
            if (index >= dinnerDays) {
                listOf(lunch.copy(calories = 2000.0, sodiumMg = 1500.0))
            } else {
                val stated = 400.0 * dinnerCoverage
                listOfNotNull(
                    lunch,
                    food(date, MealType.DINNER, kcal = stated, protein = 20.0 * dinnerCoverage, sodium = dinnerSodium?.times(dinnerCoverage)),
                    if (dinnerCoverage < 1.0) food(date, MealType.DINNER, kcal = 400.0 - stated, protein = 20.0 * (1.0 - dinnerCoverage), sodium = null) else null
                )
            }
        }

    @Test
    fun twentyFivePercentAboveIsShown() {
        // Dinner 1,000 mg per 1,000 kcal against 800 mg overall
        val finding = findPatterns(days(dinnerSodium = 400.0), finishedDays = null).only()

        assertEquals(PatternFinding.SodiumPeakMeal(MealType.DINNER, 1000.0, 800.0), finding)
        assertEquals("Sodium peaks at dinner.", patternText(finding))
    }

    @Test
    fun justUnderTwentyFivePercentIsNotShown() {
        assertTrue(findPatterns(days(dinnerSodium = 396.0), finishedDays = null).findings.isEmpty())
    }

    @Test
    fun lowCoverageIsSkipped() {
        // Half of dinner's calories state sodium, at a high rate
        assertTrue(findPatterns(days(dinnerSodium = 2000.0, dinnerCoverage = 0.5), finishedDays = null).findings.isEmpty())
    }

    @Test
    fun aMealLoggedOnFewerThanFiveDaysIsLeftOut() {
        assertTrue(findPatterns(days(dinnerSodium = 4000.0, dinnerDays = 4), finishedDays = null).findings.isEmpty())
    }
}

/** Up to four findings, the one that stands out most first. */
class PatternsOrderTests {

    @Test
    fun allFourShowLargestEffectFirst() {
        val days = WINDOW_DAYS.take(14)
        val entries = days.flatMap { date ->
            val sundayLunchSugar = if (date.dayOfWeek == DayOfWeek.SUNDAY) 56.0 else 16.0
            listOfNotNull(
                food(date, MealType.BREAKFAST, kcal = 400.0, protein = 5.0, sugar = 8.0, sodium = 200.0),
                food(date, MealType.LUNCH, kcal = 800.0, protein = 40.0, sugar = sundayLunchSugar, sodium = 600.0),
                food(date, MealType.DINNER, kcal = 800.0, protein = 40.0, sugar = 16.0, sodium = 1600.0),
                if (date.dayOfWeek == DayOfWeek.SATURDAY) food(date, MealType.PM_SNACK, kcal = 800.0, protein = 10.0, sugar = 16.0, sodium = 400.0) else null
            )
        }

        val findings = findPatterns(entries, finishedDays = null).findings

        assertEquals(
            listOf(PatternFinding.LowProteinMeal::class, PatternFinding.SugarPeakWeekday::class, PatternFinding.SodiumPeakMeal::class, PatternFinding.HighCalorieWeekday::class),
            findings.map { it::class }
        )
        assertEquals(findings.map { it.effect }.sortedDescending(), findings.map { it.effect })
        assertEquals(DayOfWeek.SATURDAY, (findings.last() as PatternFinding.HighCalorieWeekday).weekday)
        assertEquals(DayOfWeek.SUNDAY, (findings[1] as PatternFinding.SugarPeakWeekday).weekday)
        assertEquals(MealType.DINNER, (findings[2] as PatternFinding.SodiumPeakMeal).meal)
    }
}

/** Meal nutrients for the patterns, read from the diary as logged. */
private class MealsDiary(entries: List<LoggedMealNutrients>) : DiaryRepository {
    val entries = MutableStateFlow(entries)
    override fun observeMealNutrients(startDate: LocalDate, endDate: LocalDate): Flow<List<LoggedMealNutrients>> =
        entries.map { list -> list.filter { !it.date.isBefore(startDate) && !it.date.isAfter(endDate) } }
    override fun observeNutritionAggregations(startDate: LocalDate, endDate: LocalDate): Flow<List<DailyMacroAggregation>> = flowOf(emptyList())
    override suspend fun getNutritionAggregations(startDate: LocalDate, endDate: LocalDate): List<DailyMacroAggregation> = emptyList()
    override suspend fun getDiaryForDate(date: LocalDate) = DailyNutritionSummary(date = date)
    override fun observeDiaryForDate(date: LocalDate): Flow<DailyNutritionSummary> = flowOf(DailyNutritionSummary(date = date))
    override suspend fun getEntryById(entryId: Long): DiaryEntry? = null
    override suspend fun addEntry(entry: DiaryEntry): Long = 0L
    override suspend fun addEntries(entries: List<DiaryEntry>) {}
    override suspend fun updateEntry(entry: DiaryEntry) {}
    override suspend fun deleteEntry(entryId: Long) {}
    override suspend fun getMonthlyAdherence(year: Int, month: Int): List<CalendarDaySummary> = emptyList()
    override fun observeMonthlyAdherence(year: Int, month: Int): Flow<List<CalendarDaySummary>> = flowOf(emptyList())
}

private class PatternsGoals : GoalsRepository {
    private val goal = MutableStateFlow(Goal())
    override suspend fun getGoals() = goal.value
    override fun observeGoals(): Flow<Goal> = goal
    override suspend fun updateGoals(goals: Goal) { goal.value = goals }
}

private object PatternsNoWeights : WeightRepository {
    override suspend fun addWeightEntry(entry: WeightEntry) = 0L
    override suspend fun updateWeightEntry(entry: WeightEntry) {}
    override suspend fun deleteWeightEntry(entryId: Long) {}
    override suspend fun deleteWeightEntryForDate(date: LocalDate) {}
    override fun observeWeightForDate(date: LocalDate): Flow<WeightEntry?> = flowOf(null)
    override suspend fun getWeightForDate(date: LocalDate): WeightEntry? = null
    override fun observeWeightHistory(): Flow<List<WeightEntry>> = flowOf(emptyList())
    override suspend fun getWeightHistory(): List<WeightEntry> = emptyList()
    override fun observeWeightRange(startDate: LocalDate, endDate: LocalDate): Flow<List<WeightEntry>> = flowOf(emptyList())
    override suspend fun getWeightRange(startDate: LocalDate, endDate: LocalDate): List<WeightEntry> = emptyList()
}

/** The view model reads the 28 days up to yesterday and follows the Finished days switch. */
@OptIn(ExperimentalCoroutinesApi::class)
class PatternsViewModelTests {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(entries: List<LoggedMealNutrients>, prefs: FinishedDaysPrefs) = StatisticsViewModel(
        GetStatisticsUseCase(StatisticsRepositoryImpl(MealsDiary(entries), PatternsGoals(), PatternsNoWeights)),
        prefs,
        MutableTestClock(PATTERNS_TODAY)
    )

    @Test
    fun todayAndDaysBeforeTheWindowDoNotCount() = runTest {
        val entries = WINDOW_DAYS.takeLast(10).map { food(it) } +
            food(PATTERNS_TODAY) + food(WINDOW_START.minusDays(1)) + food(WINDOW_START.minusDays(2))
        val vm = viewModel(entries, FinishedDaysPrefs())

        val state = withTimeout(5_000) { vm.patterns.first { !it.isLoading } }

        assertEquals(10, state.report!!.countedDays)
        assertEquals(false, state.finishedDaysOnly)
    }

    @Test
    fun finishedDaysOnlyCountsTheMarkedDays() = runTest {
        val entries = WINDOW_DAYS.take(12).map { food(it) }
        val prefs = FinishedDaysPrefs(days = WINDOW_DAYS.take(9).toSet(), finishedOnly = true)
        val vm = viewModel(entries, prefs)

        val state = withTimeout(5_000) { vm.patterns.first { !it.isLoading } }
        assertTrue(state.finishedDaysOnly)
        assertEquals(9, state.report!!.countedDays)

        prefs.setDayFinished(WINDOW_DAYS[9], true)
        val more = withTimeout(5_000) { vm.patterns.first { it.report?.countedDays == 10 } }
        assertEquals(0, more.report!!.daysNeeded)
    }
}

/** The DAO reads each entry's meal and keeps unstated sugar and sodium as null. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PatternsDiaryQueryTests {

    private lateinit var db: UserDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), UserDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entity(uuid: String, date: LocalDate, meal: MealType, sugar: Double?, sodium: Double?) = DiaryEntryEntity(
        uuid = uuid, dateEpochDay = date.toEpochDay(), mealType = meal.name, foodId = 1L, foodName = uuid,
        userQuantity = 1.0, servingDescription = "100 g", gramWeight = 100.0,
        loggedCalories = 250.0, loggedProtein = 12.0, loggedCarbs = 30.0, loggedFat = 8.0,
        loggedSugar = sugar, loggedSodium = sodium
    )

    @Test
    fun mealNutrientsKeepTheMealAndTheNulls() = runBlocking {
        val day = LocalDate.of(2026, 11, 10)
        db.diaryDao().insertEntries(
            listOf(
                entity("a", day, MealType.BREAKFAST, sugar = 6.0, sodium = null),
                entity("b", day, MealType.PM_SNACK, sugar = null, sodium = 0.0),
                entity("c", day.plusDays(5), MealType.DINNER, sugar = 1.0, sodium = 1.0)
            )
        )
        val diary = DiaryRepositoryImpl(db, PatternsGoals())

        val rows = diary.observeMealNutrients(day, day.plusDays(1)).first().sortedBy { it.mealType.order }

        assertEquals(listOf(MealType.BREAKFAST, MealType.PM_SNACK), rows.map { it.mealType })
        assertEquals(LoggedMealNutrients(day, MealType.BREAKFAST, 250.0, 12.0, 6.0, null), rows[0])
        assertNull(rows[1].sugarGrams)
        assertEquals(0.0, rows[1].sodiumMg!!, 0.0)
    }
}

/** Patterns sits after Averages in the dock, with its own route. */
class PatternsSourceTests {

    @Test
    fun theViewIsRegisteredBeforeRankHistory() {
        val stats = patternsSource("feature/statistics/StatisticsScreen.kt")

        assertTrue(stats.contains("4 -> PatternsTabRoute(viewModel = viewModel)"))
        assertTrue(stats.contains("5 -> RankHistoryTabRoute(viewModel = viewModel)"))
    }

    @Test
    fun thePatternsViewShowsTheSwitchAndTheMessages() {
        val tab = patternsSource("feature/statistics/PatternsTab.kt")

        assertTrue(tab.contains("\"Logged days\""))
        assertTrue(tab.contains("\"Finished days\""))
        assertTrue(tab.contains("onSetFinishedDaysOnly"))
        assertTrue(tab.contains("patternsNeedMoreText(report.daysNeeded, state.finishedDaysOnly)"))
        assertTrue(tab.contains("patternText(finding)"))
    }
}
