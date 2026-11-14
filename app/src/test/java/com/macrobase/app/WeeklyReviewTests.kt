package com.macrobase.app

import com.macrobase.app.core.config.DatabaseConfig
import com.macrobase.app.core.config.RankScoringConfig
import com.macrobase.app.core.config.WeeklyReviewConfig
import com.macrobase.app.core.di.appModules
import com.macrobase.app.data.database.UserDatabase
import com.macrobase.app.domain.model.CalendarDaySummary
import com.macrobase.app.domain.model.DailyNutritionSummary
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.FitnessGoal
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.rank.BalanceMacro
import com.macrobase.app.domain.model.rank.LifestyleScore
import com.macrobase.app.domain.model.rank.QualityMeasure
import com.macrobase.app.domain.model.rank.Rank
import com.macrobase.app.domain.model.rank.RankHistoryEntry
import com.macrobase.app.domain.model.rank.RankProfile
import com.macrobase.app.domain.model.rank.RankTier
import com.macrobase.app.domain.model.rank.Tier
import com.macrobase.app.domain.model.rank.WeekScores
import com.macrobase.app.domain.model.rank.WeeklyTip
import com.macrobase.app.domain.model.rank.weeklyReview
import com.macrobase.app.domain.repository.DailyMacroAggregation
import com.macrobase.app.domain.repository.DiaryRepository
import com.macrobase.app.domain.repository.GoalsRepository
import com.macrobase.app.domain.repository.LoggedFoodNutrients
import com.macrobase.app.domain.repository.rank.RankRepository
import com.macrobase.app.domain.usecase.rank.DismissRankRulesNoticeUseCase
import com.macrobase.app.domain.usecase.rank.DismissRankSeasonNoticeUseCase
import com.macrobase.app.domain.usecase.rank.GetRankProfileUseCase
import com.macrobase.app.domain.usecase.rank.GetWeeklyReviewUseCase
import com.macrobase.app.feature.rank.RankViewModel
import com.macrobase.app.feature.rank.loggedDaysText
import com.macrobase.app.feature.rank.rrChangeText
import com.macrobase.app.feature.rank.weekChange
import com.macrobase.app.feature.rank.weeklyTipText
import com.macrobase.app.testutil.MutableTestClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.koinApplication
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/** Stats idea 7: this week against last week on the Rank screen, with one or two tips. */
private val REVIEW_TODAY: LocalDate = LocalDate.of(2026, 11, 14)

/** The 7 days up to yesterday, newest first, and the 7 before them. */
private val THIS_WEEK: List<LocalDate> = (1L..7L).map { REVIEW_TODAY.minusDays(it) }
private val LAST_WEEK: List<LocalDate> = (8L..14L).map { REVIEW_TODAY.minusDays(it) }

/** The zone of the default goal: maintaining at 2,000 kcal. */
private val ZONE = RankScoringConfig.calorieZone(Goal().fitnessGoal, Goal().maintenanceCalories)

private fun ended(
    date: LocalDate,
    rr: Int = 0,
    logged: Boolean = true,
    dayScore: Double = 70.0,
    calorie: Double = 100.0,
    protein: Double = 80.0,
    quality: Double = 80.0,
    balance: Double = 80.0,
    inProgress: Boolean = false
) = RankHistoryEntry(
    date = date,
    rankTier = RankTier(Rank.FAT_BEAR, Tier.I),
    tierRR = 0,
    overallRating = 0,
    rrDelta = rr,
    overallScore = if (logged) dayScore else 0.0,
    calorieScore = if (logged) calorie else 0.0,
    consistencyScore = if (logged) 100.0 else 0.0,
    proteinScore = if (logged) protein else 0.0,
    qualityScore = if (logged) quality else 0.0,
    balanceScore = if (logged) balance else 0.0,
    isLogged = logged,
    isInProgress = inProgress
)

/** A food that meets every target of the default goal (2,000 kcal, 125 g protein, 250 g carbs, 56 g fat). */
private fun goodFood(
    date: LocalDate,
    kcal: Double = 2000.0,
    protein: Double = 125.0,
    carbs: Double = 250.0,
    fat: Double = 55.0,
    fiber: Double? = 30.0,
    sugar: Double? = 50.0,
    saturatedFat: Double? = 10.0,
    sodium: Double? = 1500.0
) = LoggedFoodNutrients(date, 0L, "", kcal, protein, carbs, fat, fiber, sugar, saturatedFat, sodium)

private val GOALS: Map<LocalDate, Goal> = (THIS_WEEK + LAST_WEEK).associateWith { Goal() }

/** Seven logged days this week with these scores, and a full last week so comparisons show. */
private fun week(
    calorie: Double = 80.0,
    protein: Double = 80.0,
    quality: Double = 80.0,
    balance: Double = 80.0
) = THIS_WEEK.map { ended(it, calorie = calorie, protein = protein, quality = quality, balance = balance) } +
    LAST_WEEK.map { ended(it) }

private fun review(history: List<RankHistoryEntry>, foods: List<LoggedFoodNutrients> = THIS_WEEK.map { goodFood(it) }) =
    weeklyReview(history, REVIEW_TODAY, foods, GOALS)

private fun rankSource(path: String): String =
    listOf(File("src/main/java/com/macrobase/app/$path"), File("app/src/main/java/com/macrobase/app/$path"))
        .first { it.isFile }.readText().replace("\r\n", "\n")

/** The weekly numbers come from the ended days of the rank history; nothing in the rules changes. */
class WeeklyReviewNumbersTests {

    @Test
    fun theWeekAddsUpItsEndedDaysOnly() {
        val history = listOf(ended(REVIEW_TODAY, rr = 15, inProgress = true)) +
            THIS_WEEK.take(5).mapIndexed { i, date ->
                ended(date, rr = 6, dayScore = 50.0 + 10 * i, protein = 60.0 + 10 * i, quality = 75.0, balance = 85.0)
            } +
            THIS_WEEK.drop(5).map { ended(it, rr = -20, logged = false) } +
            LAST_WEEK.map { ended(it, rr = 2, dayScore = 60.0, calorie = 90.0, protein = 70.0, quality = 70.0, balance = 80.0) } +
            ended(REVIEW_TODAY.minusDays(15), rr = 100)

        val result = review(history)!!

        assertEquals(WeekScores(rrChange = -10, loggedDays = 5, countedDays = 7, dayScore = 70.0, calorieScore = 100.0, proteinScore = 80.0, qualityScore = 75.0, balanceScore = 85.0), result.thisWeek)
        assertEquals(WeekScores(rrChange = 14, loggedDays = 7, countedDays = 7, dayScore = 60.0, calorieScore = 90.0, proteinScore = 70.0, qualityScore = 70.0, balanceScore = 80.0), result.lastWeek)
        // 5 of 7 logged is 71, and every part is 70 or more: no tip
        assertTrue(result.tips.isEmpty())
    }

    // recentHistory covers the current season only, so last week can be short
    @Test
    fun earlyInASeasonThereIsNoComparison() {
        val thisWeek = THIS_WEEK.map { ended(it, rr = 5) }
        val twoLast = LAST_WEEK.take(2).map { ended(it) }
        val threeLast = LAST_WEEK.take(3).map { ended(it) }

        assertNull(review(thisWeek + twoLast)!!.lastWeek)
        assertNull(review(thisWeek)!!.lastWeek)
        assertEquals(3, review(thisWeek + threeLast)!!.lastWeek!!.loggedDays)
        assertEquals(7, WeeklyReviewConfig.WEEK_DAYS)
        assertEquals(3, WeeklyReviewConfig.MIN_COMPARABLE_LOGGED_DAYS)
    }

    @Test
    fun aWeekWithNoEndedDayHasNoReview() {
        assertNull(review(emptyList()))
        assertNull(review(listOf(ended(REVIEW_TODAY, inProgress = true))))
    }

    // A week of missed days has no averages to show: "-", never 0
    @Test
    fun aWeekWithNothingLoggedHasNoAverages() {
        val result = review(THIS_WEEK.map { ended(it, rr = -20, logged = false) }, foods = emptyList())!!

        assertEquals(WeekScores(rrChange = -140, loggedDays = 0, countedDays = 7, dayScore = null, calorieScore = null, proteinScore = null, qualityScore = null, balanceScore = null), result.thisWeek)
        assertEquals(listOf(WeeklyTip.Logging(missedDays = 7)), result.tips)
    }

    // A season that began three days ago: three counted days, none of them missed
    @Test
    fun loggedDaysAreOutOfTheDaysTheRankCounted() {
        val result = review(THIS_WEEK.take(3).map { ended(it) }, THIS_WEEK.take(3).map { goodFood(it) })!!

        assertEquals(3, result.thisWeek.countedDays)
        assertEquals("3 of 3 days logged", loggedDaysText(result.thisWeek))
        assertTrue(result.tips.isEmpty())
        assertEquals("5 of 7 days logged", loggedDaysText(WeekScores(0, 5, 7, null, null, null, null, null)))
    }

    @Test
    fun changesAndRrReadAsSignedWholeNumbers() {
        assertEquals("+10", weekChange(70.0, 60.0))
        assertEquals("-5", weekChange(55.0, 60.4))
        assertEquals("0", weekChange(60.2, 60.0))
        assertNull(weekChange(null, 60.0))
        assertNull(weekChange(60.0, null))
        assertEquals("+34 RR", rrChangeText(34))
        assertEquals("-12 RR", rrChangeText(-12))
        assertEquals("0 RR", rrChangeText(0))
    }
}

/** One or two tips, for the lowest parts of the week under 70, quoting the user's numbers and the rule's target. */
class WeeklyReviewTipsTests {

    @Test
    fun theTwoLowestPartsUnder70GetTips() {
        val foods = THIS_WEEK.map { goodFood(it, protein = 90.0, carbs = 330.0, fat = 60.0) }

        val tips = review(week(calorie = 100.0, protein = 55.0, quality = 65.0, balance = 40.0), foods)!!.tips

        assertEquals(2, WeeklyReviewConfig.MAX_TIPS)
        assertEquals(70.0, WeeklyReviewConfig.TIP_SCORE_BELOW, 0.0)
        assertEquals(2, tips.size)
        val balance = tips[0] as WeeklyTip.Balance
        assertEquals(BalanceMacro.CARBS, balance.macro)
        assertEquals(330.0, balance.averageGrams, 1e-9)
        assertEquals(Goal().carbGrams, balance.goalGrams, 1e-9)
        assertEquals(WeeklyTip.Protein(averageGrams = 90.0, goalGrams = Goal().proteinGrams), tips[1])
    }

    // Protein scored low on some days but the week's average meets the goal: no tip saying otherwise
    @Test
    fun aTipThatWouldContradictTheWeeksAverageIsLeftOut() {
        val foods = THIS_WEEK.map { goodFood(it, protein = 130.0) }

        val tips = review(week(protein = 55.0, balance = 60.0), foods)!!.tips

        // The balance tip is left out too: carbs and fat are within 20% of their goals over the week
        assertTrue(tips.isEmpty())
        assertEquals(1, review(week(protein = 55.0, quality = 60.0), THIS_WEEK.map { goodFood(it, protein = 130.0, fiber = 16.0) })!!.tips.size)
    }

    @Test
    fun seventyOrMoreGetsNoTip() {
        assertTrue(review(week(calorie = 70.0, protein = 70.0, quality = 70.0, balance = 70.0))!!.tips.isEmpty())
        assertEquals(1, review(week(protein = 69.0), THIS_WEEK.map { goodFood(it, protein = 100.0) })!!.tips.size)
    }

    @Test
    fun missedDaysGetTheLoggingTip() {
        val history = THIS_WEEK.take(4).map { ended(it) } + THIS_WEEK.drop(4).map { ended(it, rr = -20, logged = false) } + LAST_WEEK.map { ended(it) }

        val tips = review(history, THIS_WEEK.take(4).map { goodFood(it) })!!.tips

        assertEquals(listOf(WeeklyTip.Logging(missedDays = 3)), tips)
        assertEquals(
            "3 days went unlogged this week. A missed day costs 20 RR, more than a logged day can lose, so log even a rough day.",
            weeklyTipText(tips.single())
        )
        assertTrue(weeklyTipText(WeeklyTip.Logging(missedDays = 1)).startsWith("1 day went unlogged this week."))
    }

    @Test
    fun theCalorieTipCountsDaysOutsideTheZone() {
        val scores = listOf(100.0, 100.0, 100.0, 40.0, 30.0, 50.0, 60.0)
        val history = THIS_WEEK.zip(scores) { date, score -> ended(date, calorie = score) } + LAST_WEEK.map { ended(it) }

        val tip = review(history)!!.tips.single()

        assertEquals(WeeklyTip.Calories(daysOutside = 4, loggedDays = 7, zone = ZONE), tip)
        assertEquals(
            "4 of 7 logged days fell outside your calorie zone of 1,900 \u2013 2,100 kcal. A day inside it keeps its full food score.",
            weeklyTipText(tip)
        )
    }

    // A scheduled change to bulking took effect mid-week: the days had different zones
    @Test
    fun aZoneThatChangedIsNotQuoted() {
        val bulking = Goal(fitnessGoal = FitnessGoal.BULKING, maintenanceCalories = 2400.0)
        val goals = GOALS + THIS_WEEK.take(2).associateWith { bulking }
        val scores = listOf(100.0, 100.0, 100.0, 40.0, 30.0, 50.0, 60.0)
        val history = THIS_WEEK.zip(scores) { date, score -> ended(date, calorie = score) } + LAST_WEEK.map { ended(it) }

        val tip = weeklyReview(history, REVIEW_TODAY, THIS_WEEK.map { goodFood(it) }, goals)!!.tips.single()

        assertEquals(WeeklyTip.Calories(daysOutside = 4, loggedDays = 7, zone = null), tip)
        assertEquals(
            "4 of 7 logged days fell outside their calorie zone, which changed during the week. A day inside its zone keeps its full food score.",
            weeklyTipText(tip)
        )
    }

    @Test
    fun theQualityTipNamesTheWeakestMeasure() {
        // 8 g of fiber per 1,000 kcal; the other measures meet their targets
        val lowFiber = review(week(quality = 50.0), THIS_WEEK.map { goodFood(it, fiber = 16.0) })!!.tips.single()
        val highSodium = review(week(quality = 50.0), THIS_WEEK.map { goodFood(it, sodium = 3500.0) })!!.tips.single()

        assertEquals(WeeklyTip.Quality(QualityMeasure.FIBER, 8.0), lowFiber)
        assertEquals("Fiber averaged 8 g per 1,000 kcal. Full marks start at 14 g: add dal, fruit or whole grains.", weeklyTipText(lowFiber))
        assertEquals(WeeklyTip.Quality(QualityMeasure.SODIUM, 3500.0), highSodium)
        assertEquals(
            "Sodium averaged 3,500 mg a day. Full marks are at 2,300 mg or less: go lighter on salt, pickles and packaged snacks.",
            weeklyTipText(highSodium)
        )
    }

    // Under half the calories from foods that state it: say so, rather than judge on too little
    @Test
    fun lowCoverageGivesTheNotEnoughDataTip() {
        val foods = THIS_WEEK.flatMap {
            listOf(
                goodFood(it, kcal = 800.0, fiber = 4.0, sugar = 20.0, saturatedFat = 4.0, sodium = 600.0),
                goodFood(it, kcal = 1200.0, fiber = null, sugar = 30.0, saturatedFat = 6.0, sodium = 900.0)
            )
        }

        val tip = review(week(quality = 50.0), foods)!!.tips.single()

        assertEquals(WeeklyTip.Quality(QualityMeasure.FIBER, null), tip)
        assertEquals(
            "There isn't enough data to judge fiber this week. Under half of your calories came from foods that state it.",
            weeklyTipText(tip)
        )
    }

    // Fiber meets its target on the 52% of calories that state it; saturated fat, fully stated, does not
    @Test
    fun aMeasureAtFullMarksIsNotBlamedForMissingData() {
        val foods = THIS_WEEK.flatMap {
            listOf(
                goodFood(it, kcal = 1040.0, fiber = 16.64, sugar = 26.0, saturatedFat = 15.0, sodium = 780.0),
                goodFood(it, kcal = 960.0, fiber = null, sugar = 24.0, saturatedFat = 14.1, sodium = 720.0)
            )
        }

        val tip = review(week(quality = 60.0), foods)!!.tips.single() as WeeklyTip.Quality

        assertEquals(QualityMeasure.SATURATED_FAT, tip.measure)
        assertEquals(29.1 * 9.0 / 2000.0 * 100.0, tip.value!!, 1e-9)
    }

    // Too little fiber data, but sodium is judged and over its limit: the tip is about sodium
    @Test
    fun aJudgedMeasureOverItsTargetComesBeforeMissingData() {
        val foods = THIS_WEEK.flatMap {
            listOf(
                goodFood(it, kcal = 900.0, fiber = 18.0, sugar = 22.5, saturatedFat = 4.5, sodium = 1350.0),
                goodFood(it, kcal = 1100.0, fiber = null, sugar = 27.5, saturatedFat = 5.5, sodium = 1650.0)
            )
        }

        val tip = review(week(quality = 60.0), foods)!!.tips.single()

        assertEquals(WeeklyTip.Quality(QualityMeasure.SODIUM, 3000.0), tip)
    }

    // A food that does not state fiber is left out of the rate, never counted as 0 g
    @Test
    fun anUnstatedNutrientIsNotZero() {
        val foods = THIS_WEEK.flatMap {
            listOf(
                goodFood(it, kcal = 1200.0, fiber = 12.0, sugar = 30.0, saturatedFat = 6.0, sodium = 900.0),
                goodFood(it, kcal = 800.0, fiber = null, sugar = 20.0, saturatedFat = 4.0, sodium = 600.0)
            )
        }

        val tip = review(week(quality = 50.0), foods)!!.tips.single() as WeeklyTip.Quality

        assertEquals(QualityMeasure.FIBER, tip.measure)
        assertEquals(10.0, tip.value!!, 1e-9)
    }

    @Test
    fun eachTipReadsPlainly() {
        assertEquals(
            "Protein averaged 82 g a day. Full marks start at your goal of 120 g: add dal, paneer, eggs or curd to a meal.",
            weeklyTipText(WeeklyTip.Protein(averageGrams = 82.4, goalGrams = 120.0))
        )
        assertEquals(
            "Fat averaged 33% below your goal, 40 g against 60 g a day. Full marks are within 20% of the goal each day.",
            weeklyTipText(WeeklyTip.Balance(BalanceMacro.FAT, averageGrams = 40.0, goalGrams = 60.0))
        )
        assertEquals(
            "Carbs averaged 32% above your goal, 330 g against 250 g a day. Full marks are within 20% of the goal each day.",
            weeklyTipText(WeeklyTip.Balance(BalanceMacro.CARBS, averageGrams = 330.0, goalGrams = 250.0))
        )
        assertEquals(
            "Saturated fat was 14% of your calories. Full marks are at 10% or less: use less ghee, butter and cream.",
            weeklyTipText(WeeklyTip.Quality(QualityMeasure.SATURATED_FAT, 14.2))
        )
        assertEquals(
            "Sugar was 26% of your calories. Full marks are at 20% or less: cut back on sweets and sweet drinks.",
            weeklyTipText(WeeklyTip.Quality(QualityMeasure.SUGAR, 25.6))
        )
    }
}

/** The tips quote the rule's targets from RankScoringConfig; the rules themselves stay as they were. */
class WeeklyReviewRulesTests {

    @Test
    fun theTargetsAreNamedAndTheRulesUnchanged() {
        assertEquals(4, RankScoringConfig.RULES_VERSION)
        assertEquals(14.0, RankScoringConfig.FIBER_FULL_MARKS_PER_1000_KCAL, 0.0)
        assertEquals(10.0, RankScoringConfig.SATURATED_FAT_FULL_MARKS_PERCENT, 0.0)
        assertEquals(2300.0, RankScoringConfig.SODIUM_FULL_MARKS_MG, 0.0)
        assertEquals(20.0, RankScoringConfig.SUGAR_FULL_MARKS_PERCENT, 0.0)
        assertEquals(0.20, RankScoringConfig.BALANCE_FULL_MARKS_WITHIN, 0.0)
        // The same curves as before: halfway points of each ramp
        assertEquals(50.0, RankScoringConfig.fiberScore(9.0), 1e-9)
        assertEquals(40.0, RankScoringConfig.saturatedFatScore(15.0), 1e-9)
        assertEquals(20.0, RankScoringConfig.saturatedFatScore(17.5), 1e-9)
        assertEquals(50.0, RankScoringConfig.sodiumScore(3150.0), 1e-9)
        assertEquals(50.0, RankScoringConfig.sugarScore(27.5), 1e-9)
    }
}

/** Day totals are not needed here: only the foods of the week, for the tips. */
private class WeekFoodsDiary(foods: List<LoggedFoodNutrients>) : DiaryRepository {
    val foods = MutableStateFlow(foods)
    override fun observeLoggedFoodNutrients(startDate: LocalDate, endDate: LocalDate): Flow<List<LoggedFoodNutrients>> =
        foods.map { list -> list.filter { !it.date.isBefore(startDate) && !it.date.isAfter(endDate) } }
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

private class WeekGoals : GoalsRepository {
    private val goal = MutableStateFlow(Goal())
    override suspend fun getGoals() = goal.value
    override fun observeGoals(): Flow<Goal> = goal
    override suspend fun updateGoals(goals: Goal) { goal.value = goals }
}

private class FixedRank(
    private val profile: RankProfile,
    private val fill: (LoggedFoodNutrients) -> LoggedFoodNutrients = { it }
) : RankRepository {
    override suspend fun withScoredNutrients(foods: List<LoggedFoodNutrients>) = foods.map(fill)
    override fun observeRankProfile(): Flow<RankProfile> = flowOf(profile)
    override suspend fun getRankProfile() = profile
    override suspend fun calculateLifestyleScoreForPeriod(startDate: LocalDate, endDate: LocalDate) = LifestyleScore.ZERO
}

/** The Rank screen's state carries the review, worked out from the profile, the diary and the goals. */
@OptIn(ExperimentalCoroutinesApi::class)
class WeeklyReviewViewModelTests {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun theRankStateCarriesTheReview() = runTest {
        val profile = RankProfile(
            currentRating = 400,
            rankTier = RankTier(Rank.FAT_BEAR, Tier.I),
            tierRR = 0,
            isProvisional = false,
            loggedDaysCount = 14,
            lifestyleScore = LifestyleScore.ZERO,
            recentHistory = week(protein = 50.0),
            calorieZone = ZONE
        )
        val rank = FixedRank(profile)
        val vm = RankViewModel(
            GetRankProfileUseCase(rank),
            DismissRankRulesNoticeUseCase(rank),
            DismissRankSeasonNoticeUseCase(rank),
            GetWeeklyReviewUseCase(WeekFoodsDiary(THIS_WEEK.map { goodFood(it, protein = 60.0) }), WeekGoals(), rank, MutableTestClock(REVIEW_TODAY))
        )

        val state = withTimeout(5_000) { vm.uiState.first { !it.isLoading } }

        val review = state.weeklyReview
        assertNotNull(review)
        assertEquals(7, review!!.thisWeek.loggedDays)
        assertEquals(listOf(WeeklyTip.Protein(averageGrams = 60.0, goalGrams = Goal().proteinGrams)), review.tips)
    }

    private fun profileWith(history: List<RankHistoryEntry>) = RankProfile(
        currentRating = 400,
        rankTier = RankTier(Rank.FAT_BEAR, Tier.I),
        tierRR = 0,
        isProvisional = false,
        loggedDaysCount = 14,
        lifestyleScore = LifestyleScore.ZERO,
        recentHistory = history
    )

    // The quality tip reads the nutrients the score used, catalog values filled in
    @Test
    fun theTipsUseTheNutrientsTheScoreUsed() = runTest {
        val rank = FixedRank(profileWith(week(quality = 50.0))) { food -> food.copy(fiberGrams = 16.0) }
        val useCase = GetWeeklyReviewUseCase(
            WeekFoodsDiary(THIS_WEEK.map { goodFood(it, fiber = null) }), WeekGoals(), rank, MutableTestClock(REVIEW_TODAY)
        )

        val review = useCase(profileWith(week(quality = 50.0))).first()

        assertEquals(listOf(WeeklyTip.Quality(QualityMeasure.FIBER, 8.0)), review!!.tips)
    }

    // Just after midnight the profile can still be yesterday's: its own day is the one used
    @Test
    fun theWeekEndsTheDayBeforeTheProfilesToday() = runTest {
        val history = listOf(ended(REVIEW_TODAY, inProgress = true)) + week()
        val rank = FixedRank(profileWith(history))
        val useCase = GetWeeklyReviewUseCase(
            WeekFoodsDiary(THIS_WEEK.map { goodFood(it) }), WeekGoals(), rank, MutableTestClock(REVIEW_TODAY.plusDays(1))
        )

        assertEquals(7, useCase(profileWith(history)).first()!!.thisWeek.loggedDays)
    }

    // A diary that cannot be read leaves out the card, never the Rank screen
    @Test
    fun aFailingReviewHidesTheCardOnly() = runTest {
        val rank = FixedRank(profileWith(week()))
        val broken = object : DiaryRepository by WeekFoodsDiary(emptyList()) {
            override fun observeLoggedFoodNutrients(startDate: LocalDate, endDate: LocalDate): Flow<List<LoggedFoodNutrients>> =
                flow { throw IllegalStateException("disk I/O error") }
        }
        val vm = RankViewModel(
            GetRankProfileUseCase(rank),
            DismissRankRulesNoticeUseCase(rank),
            DismissRankSeasonNoticeUseCase(rank),
            GetWeeklyReviewUseCase(broken, WeekGoals(), rank, MutableTestClock(REVIEW_TODAY))
        )

        val state = withTimeout(5_000) { vm.uiState.first { !it.isLoading } }

        assertEquals(400, state.rankProfile.currentRating)
        assertNull(state.weeklyReview)
    }
}

/** The app's Koin modules build the Rank screen with the review, so the card shows on the phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeeklyReviewWiringTests {

    @Test
    fun theRankViewModelGetsTheReview() {
        val app = RuntimeEnvironment.getApplication()
        val catalog = listOf(File("src/main/assets/databases/built_in_foods.db"), File("app/src/main/assets/databases/built_in_foods.db"))
            .first { it.isFile }
        val installed = app.getDatabasePath(DatabaseConfig.BUILT_IN_FOODS_DB_NAME)
        installed.parentFile?.mkdirs()
        catalog.copyTo(installed, overwrite = true)
        val koin = koinApplication {
            androidContext(app)
            modules(appModules)
        }.koin
        try {
            val vm = koin.get<RankViewModel>()
            val useCase = RankViewModel::class.java.getDeclaredField("getWeeklyReviewUseCase")
                .apply { isAccessible = true }
                .get(vm)
            assertTrue(useCase is GetWeeklyReviewUseCase)
        } finally {
            koin.get<UserDatabase>().close()
            koin.close()
        }
    }
}

/** The card sits between the score section and the history card. */
class WeeklyReviewSourceTests {

    @Test
    fun theCardIsOnTheRankScreen() {
        val screen = rankSource("feature/rank/RankScreen.kt")
        val score = screen.indexOf("LifestyleScoreSection(profile)\n")
        val card = screen.indexOf("WeeklyReviewCard(")
        val history = screen.indexOf("RecentHistoryCard(\n")

        assertTrue(score in 0 until card)
        assertTrue(card < history)
        assertTrue(screen.substring(score, history).contains("uiState.weeklyReview?.let"))
    }
}
