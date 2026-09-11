package com.macrobase.app

import com.macrobase.app.core.config.RankScoringConfig
import com.macrobase.app.data.repository.rank.RankRepositoryImpl
import com.macrobase.app.domain.model.CalendarDaySummary
import com.macrobase.app.domain.model.ConsistencyStatistics
import com.macrobase.app.domain.model.DailyNutritionSummary
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.FitnessGoal
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.MacroAveragesStatistics
import com.macrobase.app.domain.model.UserPreferences
import com.macrobase.app.domain.model.WeightEntry
import com.macrobase.app.domain.model.WeightStatistics
import com.macrobase.app.domain.model.rank.Rank
import com.macrobase.app.domain.model.rank.RankTier
import com.macrobase.app.domain.model.rank.Tier
import com.macrobase.app.domain.model.rank.rankTierAndRRToScore
import com.macrobase.app.domain.model.rank.ratingToRankTier
import com.macrobase.app.domain.model.rank.scoreToRankBreakdown
import com.macrobase.app.domain.repository.DailyMacroAggregation
import com.macrobase.app.domain.repository.DiaryRepository
import com.macrobase.app.domain.repository.GoalsRepository
import com.macrobase.app.domain.repository.PreferencesRepository
import com.macrobase.app.domain.repository.StatisticsRepository
import com.macrobase.app.domain.repository.WeightRepository
import com.macrobase.app.domain.usecase.rank.CalculateLifestyleScoreUseCase
import com.macrobase.app.domain.usecase.rank.GetRankProfileUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.macrobase.app.domain.usecase.GetGoalsUseCase
import com.macrobase.app.domain.usecase.UpdateGoalsUseCase
import com.macrobase.app.feature.goals.DailyGoalsViewModel
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalCoroutinesApi::class)
class LifestyleRankingUnitTests {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ==========================================
    // 1. Pure Domain Mapping Tests
    // ==========================================

    @Test
    fun test1_initialStartingRank_isFatBearI_zeroRR() {
        val breakdown = scoreToRankBreakdown(0)
        assertEquals(Rank.FAT_BEAR, breakdown.rankTier.rank)
        assertEquals(Tier.I, breakdown.rankTier.tier)
        assertEquals(0, breakdown.tierRR)
        assertEquals(0, breakdown.overallRating)
        assertEquals("Fat Bear I", breakdown.rankTier.displayName)
    }

    @Test
    fun test2_fatBearI_progression() {
        val breakdown = scoreToRankBreakdown(75)
        assertEquals(Rank.FAT_BEAR, breakdown.rankTier.rank)
        assertEquals(Tier.I, breakdown.rankTier.tier)
        assertEquals(75, breakdown.tierRR)
    }

    @Test
    fun test3_fatBearII_progression() {
        val breakdown = scoreToRankBreakdown(150)
        assertEquals(Rank.FAT_BEAR, breakdown.rankTier.rank)
        assertEquals(Tier.II, breakdown.rankTier.tier)
        assertEquals(50, breakdown.tierRR)
        assertEquals("Fat Bear II", breakdown.rankTier.displayName)
    }

    @Test
    fun test4_fatBearIII_to_averageI_promotion() {
        val before = scoreToRankBreakdown(299)
        assertEquals(Rank.FAT_BEAR, before.rankTier.rank)
        assertEquals(Tier.III, before.rankTier.tier)
        assertEquals(99, before.tierRR)

        val after = scoreToRankBreakdown(300)
        assertEquals(Rank.AVERAGE, after.rankTier.rank)
        assertEquals(Tier.I, after.rankTier.tier)
        assertEquals(0, after.tierRR)
        assertEquals("Average I", after.rankTier.displayName)
    }

    @Test
    fun test5_allEightRanks_progressionLadder() {
        val expectedRanks = listOf(
            0 to Rank.FAT_BEAR,
            300 to Rank.AVERAGE,
            600 to Rank.CARB_MERCHANT,
            900 to Rank.PROTEIN_ALCHEMIST,
            1200 to Rank.GYM_CAT,
            1500 to Rank.MUSCLE_BUSTER,
            1800 to Rank.FART_MONSTER,
            2100 to Rank.GIGACHAD
        )

        for ((score, expectedRank) in expectedRanks) {
            val tier1 = ratingToRankTier(score)
            assertEquals(expectedRank, tier1.rank)
            assertEquals(Tier.I, tier1.tier)

            val tier2 = ratingToRankTier(score + 100)
            assertEquals(expectedRank, tier2.rank)
            assertEquals(Tier.II, tier2.tier)

            val tier3 = ratingToRankTier(score + 200)
            assertEquals(expectedRank, tier3.rank)
            assertEquals(Tier.III, tier3.tier)
        }
    }

    @Test
    fun test6_maximumCeiling_isGigaChadIII_99RR() {
        val maxBreakdown = scoreToRankBreakdown(2399)
        assertEquals(Rank.GIGACHAD, maxBreakdown.rankTier.rank)
        assertEquals(Tier.III, maxBreakdown.rankTier.tier)
        assertEquals(99, maxBreakdown.tierRR)
        assertEquals("GigaChad III", maxBreakdown.rankTier.displayName)

        // Clamping above max
        val clampedBreakdown = scoreToRankBreakdown(5000)
        assertEquals(Rank.GIGACHAD, clampedBreakdown.rankTier.rank)
        assertEquals(Tier.III, clampedBreakdown.rankTier.tier)
        assertEquals(99, clampedBreakdown.tierRR)
        assertEquals(2399, clampedBreakdown.overallRating)
    }

    @Test
    fun test7_minimumFloor_clampedAtZero() {
        val clamped = scoreToRankBreakdown(-50)
        assertEquals(Rank.FAT_BEAR, clamped.rankTier.rank)
        assertEquals(Tier.I, clamped.rankTier.tier)
        assertEquals(0, clamped.tierRR)
        assertEquals(0, clamped.overallRating)
    }

    @Test
    fun test8_rankTierAndRRToScore_roundtrip() {
        for (score in 0..2399) {
            val breakdown = scoreToRankBreakdown(score)
            val convertedBack = rankTierAndRRToScore(
                rank = breakdown.rankTier.rank,
                tier = breakdown.rankTier.tier,
                tierRR = breakdown.tierRR
            )
            assertEquals(score, convertedBack)
        }
    }

    // ==========================================
    // 2. Component Scoring & Calibration Tests
    // ==========================================

    @Test
    fun test1_2000goal_1950intake_isExcellent() {
        val score = RankScoringConfig.evaluateCalorieScore(1950.0, 2000.0)
        assertEquals(100.0, score, 0.001)
    }

    @Test
    fun test2_2000goal_2000intake_isExcellent() {
        val score = RankScoringConfig.evaluateCalorieScore(2000.0, 2000.0)
        assertEquals(100.0, score, 0.001)
    }

    @Test
    fun test3_2000goal_2200intake_isGoodModerate_meaningfullyBelow2000() {
        val score2000 = RankScoringConfig.evaluateCalorieScore(2000.0, 2000.0)
        val score2200 = RankScoringConfig.evaluateCalorieScore(2200.0, 2000.0)
        assertEquals(86.667, score2200, 0.001)
        assertTrue(score2200 < score2000)
    }

    @Test
    fun test4_2000goal_2400intake_isModeratePoor() {
        val score = RankScoringConfig.evaluateCalorieScore(2400.0, 2000.0)
        assertEquals(55.0, score, 0.001)
    }

    @Test
    fun test5_2000goal_3000intake_isPoor() {
        val score = RankScoringConfig.evaluateCalorieScore(3000.0, 2000.0)
        assertEquals(0.0, score, 0.001)
    }

    @Test
    fun test6_2000goal_4000intake_isVeryPoor() {
        val score = RankScoringConfig.evaluateCalorieScore(4000.0, 2000.0)
        assertEquals(0.0, score, 0.001)
    }

    @Test
    fun test7_2000goal_6000intake_isZeroScore() {
        val score = RankScoringConfig.evaluateCalorieScore(6000.0, 2000.0)
        assertEquals(0.0, score, 0.001)
    }

    @Test
    fun test8_extremeUndereating_isPenalized() {
        val score1000 = RankScoringConfig.evaluateCalorieScore(1000.0, 2000.0)
        val score500 = RankScoringConfig.evaluateCalorieScore(500.0, 2000.0)
        val score1950 = RankScoringConfig.evaluateCalorieScore(1950.0, 2000.0)

        assertEquals(0.0, score1000, 0.001)
        assertEquals(0.0, score500, 0.001)
        assertEquals(100.0, score1950, 0.001)
        assertTrue(score1000 < score1950)
    }

    @Test
    fun test9_macroBalance_goodDay() {
        val targetP = 150.0
        val targetC = 200.0
        val targetF = 65.0

        val perfectScore = RankScoringConfig.evaluateMacroBalanceScore(
            actualP = 150.0, targetP = targetP,
            actualC = 200.0, targetC = targetC,
            actualF = 65.0, targetF = targetF
        )
        assertEquals(100.0, perfectScore, 0.001)
    }

    @Test
    fun test10_macroBalance_poorDay_and_excessScaleDown() {
        val targetP = 150.0
        val targetC = 200.0
        val targetF = 65.0

        // 3x over-target macros (e.g. 6000 kcal binge day)
        val extremeExcessScore = RankScoringConfig.evaluateMacroBalanceScore(
            actualP = 400.0, targetP = targetP,
            actualC = 600.0, targetC = targetC,
            actualF = 200.0, targetF = targetF
        )
        assertEquals(0.0, extremeExcessScore, 0.001)
    }

    @Test
    fun test11_consistency_strongVsPoor() {
        val strongLifestyle = RankScoringConfig.calculateLifestyleScore(
            consistencyScore = 100.0,
            calorieScore = 100.0,
            macroScore = 100.0,
            weightTrendScore = 50.0
        )
        val poorLifestyle = RankScoringConfig.calculateLifestyleScore(
            consistencyScore = 20.0,
            calorieScore = 100.0,
            macroScore = 100.0,
            weightTrendScore = 50.0
        )
        assertTrue(strongLifestyle > poorLifestyle)
        assertEquals(28.0, strongLifestyle - poorLifestyle, 0.001) // 80% * 0.35 = 28.0
    }

    @Test
    fun test12_weightTrend_positiveNegativeAndMissing() {
        // Cutting target, healthy progressive loss -> 100.0
        assertEquals(100.0, RankScoringConfig.evaluateWeightTrendScore(-1.0, 5, FitnessGoal.CUTTING), 0.001)
        // Cutting target, active weight gain -> 20.0
        assertEquals(20.0, RankScoringConfig.evaluateWeightTrendScore(1.0, 5, FitnessGoal.CUTTING), 0.001)
        // Missing data or insufficient weigh-ins -> 50.0
        assertEquals(50.0, RankScoringConfig.evaluateWeightTrendScore(null, 0, FitnessGoal.CUTTING), 0.001)
        assertEquals(50.0, RankScoringConfig.evaluateWeightTrendScore(-1.0, 1, FitnessGoal.CUTTING), 0.001)
    }

    @Test
    fun test13_rrDelta_positiveNegativeZero() {
        // 90 score -> +18
        assertEquals(18, RankScoringConfig.calculateRRDelta(90.0, 30))
        // 75 score -> +9
        assertEquals(9, RankScoringConfig.calculateRRDelta(75.0, 30))
        // 60 score (neutral baseline) -> 0
        assertEquals(0, RankScoringConfig.calculateRRDelta(60.0, 30))
        // 50 score -> -6
        assertEquals(-6, RankScoringConfig.calculateRRDelta(50.0, 30))
        // 40 score -> -12
        assertEquals(-12, RankScoringConfig.calculateRRDelta(40.0, 30))
        // 20 score -> clamped to -20 (MAX_RR_LOSS_PER_EVAL = 20)
        assertEquals(-20, RankScoringConfig.calculateRRDelta(20.0, 30))
    }

    @Test
    fun test14_rrCaps_cannotExceedMaxGainOrLoss() {
        // 100 score -> max gain strictly capped at +20
        assertEquals(20, RankScoringConfig.calculateRRDelta(100.0, 30))
        // 0 score -> max loss strictly capped at -20
        assertEquals(-20, RankScoringConfig.calculateRRDelta(0.0, 30))
    }

    @Test
    fun test15_provisionalPeriod_and_day8Progression() {
        // Day 1 to 6 (<7) -> strictly 0 RR
        assertEquals(0, RankScoringConfig.calculateRRDelta(100.0, 1))
        assertEquals(0, RankScoringConfig.calculateRRDelta(100.0, 6))

        // Day 8 (7..13) -> 50% dampening: clamped 20 * 0.5 = +10 RR
        assertEquals(10, RankScoringConfig.calculateRRDelta(100.0, 8))

        // Day 14+ -> Full (+20 RR)
        assertEquals(20, RankScoringConfig.calculateRRDelta(100.0, 14))
    }

    // ==========================================
    // 3. Mandatory Real-World Scenarios
    // ==========================================

    @Test
    fun scenarioA_2000goal_1800intake_producesPositiveRR() {
        val calScore = RankScoringConfig.evaluateCalorieScore(1800.0, 2000.0)
        val macroScore = RankScoringConfig.evaluateMacroBalanceScore(150.0, 150.0, 180.0, 200.0, 60.0, 65.0)
        val consistency = 100.0
        val weight = 50.0

        val lifestyleScore = RankScoringConfig.calculateLifestyleScore(consistency, calScore, macroScore, weight)
        val delta = RankScoringConfig.calculateRRDelta(lifestyleScore, 30)

        // Calorie = 86.667, Macro = 100, Consistency = 100, Weight = 50 -> LifestyleScore = 92.0
        // Delta = (92.0 - 60) * 0.6 = +19.2 -> +19 RR
        assertTrue("Lifestyle score must be high", lifestyleScore >= 85.0)
        assertTrue("RR delta must be positive", delta > 0)
        assertEquals(19, delta)
    }

    @Test
    fun scenarioB_2000goal_2200intake_isModeratelyPositive_belowScenarioA() {
        val calScore = RankScoringConfig.evaluateCalorieScore(2200.0, 2000.0)
        val macroScore = RankScoringConfig.evaluateMacroBalanceScore(140.0, 150.0, 230.0, 200.0, 75.0, 65.0)
        val consistency = 100.0
        val weight = 50.0

        val lifestyleScore = RankScoringConfig.calculateLifestyleScore(consistency, calScore, macroScore, weight)
        val delta = RankScoringConfig.calculateRRDelta(lifestyleScore, 30)

        // Calorie = 86.667, Macro = 92.5, Consistency = 100, Weight = 50 -> LifestyleScore = 89.975
        // Delta = (89.975 - 60) * 0.6 = 17.985 -> +18 RR
        assertEquals(86.667, calScore, 0.001)
        assertTrue("Lifestyle score should be lower than scenario A", lifestyleScore < 92.0)
        assertEquals(18, delta)
        assertTrue("Delta should be less than Scenario A (19)", delta < 19)
    }

    @Test
    fun scenarioC_2000goal_6000intake_strictlyProducesNegativeRR() {
        val calScore = RankScoringConfig.evaluateCalorieScore(6000.0, 2000.0)
        val macroScore = RankScoringConfig.evaluateMacroBalanceScore(350.0, 150.0, 700.0, 200.0, 220.0, 65.0)
        val consistency = 100.0
        val weight = 50.0

        val lifestyleScore = RankScoringConfig.calculateLifestyleScore(consistency, calScore, macroScore, weight)
        val delta = RankScoringConfig.calculateRRDelta(lifestyleScore, 30)

        // Calorie = 0.0, Macro = 0.0, Consistency = 100.0 (35.0), Weight = 50.0 (4.0) -> LifestyleScore = 39.0
        // Delta = (39.0 - 60.0) * 0.6 = -12.6 -> -13 RR
        assertEquals(0.0, calScore, 0.001)
        assertEquals(0.0, macroScore, 0.001)
        assertEquals(39.0, lifestyleScore, 0.001)
        assertTrue("Lifestyle score must be well below 60.0 neutral point", lifestyleScore < 50.0)
        assertTrue("RR delta MUST be negative on a 6000 kcal day", delta < 0)
        assertEquals(-13, delta)
    }

    @Test
    fun scenarioD_2000goal_1000intake_penalizedAndCannotOutperformScenarioA() {
        val calScore1000 = RankScoringConfig.evaluateCalorieScore(1000.0, 2000.0)
        val calScore1800 = RankScoringConfig.evaluateCalorieScore(1800.0, 2000.0)

        assertEquals(0.0, calScore1000, 0.001)
        assertEquals(86.667, calScore1800, 0.001)
        assertTrue(calScore1000 < calScore1800)
    }

    // ==========================================
    // 4. Hard Requirements & Date Correctness Tests
    // ==========================================

    @Test
    fun testWaterExclusionGuarantee_strictlyNoEffectOnRating() = runTest(testDispatcher) {
        val fakeStats = FakeStatisticsRepository()
        val fakeGoals = FakeGoalsRepository()
        val fakeWeight = FakeWeightRepository()
        val fakePrefs = FakePreferencesRepository()
        val fakeDiary = FakeDiaryRepository()

        val rankRepo = RankRepositoryImpl(
            statisticsRepository = fakeStats,
            goalsRepository = fakeGoals,
            weightRepository = fakeWeight,
            preferencesRepository = fakePrefs,
            diaryRepository = fakeDiary
        )

        val getProfileUseCase = GetRankProfileUseCase(rankRepo)

        val profileA = getProfileUseCase.get()
        val profileB = getProfileUseCase.get()

        assertEquals(profileA.currentRating, profileB.currentRating)
        assertEquals(profileA.rankTier, profileB.rankTier)
        assertEquals(profileA.tierRR, profileB.tierRR)
        assertEquals(profileA.lifestyleScore.overallScore, profileB.lifestyleScore.overallScore, 0.001)
    }

    @Test
    fun testDateCorrectness_eachDayEvaluatedFromItsOwnDiary() = runTest(testDispatcher) {
        val today = LocalDate.now()
        val dayA = today.minusDays(2)
        val dayB = today.minusDays(1)
        val dayC = today

        val aggregations = listOf(
            DailyMacroAggregation(date = dayA, calories = 2000.0, proteinGrams = 150.0, carbsGrams = 200.0, fatGrams = 65.0),
            DailyMacroAggregation(date = dayB, calories = 6000.0, proteinGrams = 350.0, carbsGrams = 700.0, fatGrams = 220.0),
            DailyMacroAggregation(date = dayC, calories = 2200.0, proteinGrams = 140.0, carbsGrams = 230.0, fatGrams = 75.0)
        )

        val fakeDiary = FakeDiaryRepository(aggregations = aggregations)
        val fakeStats = FakeStatisticsRepository(loggedDaysCount = 15)
        val fakeGoals = FakeGoalsRepository()
        val fakeWeight = FakeWeightRepository()
        val fakePrefs = FakePreferencesRepository()

        val rankRepo = RankRepositoryImpl(
            statisticsRepository = fakeStats,
            goalsRepository = fakeGoals,
            weightRepository = fakeWeight,
            preferencesRepository = fakePrefs,
            diaryRepository = fakeDiary
        )

        val profile = rankRepo.getRankProfile()
        val history = profile.recentHistory

        val entryA = history.first { it.date == dayA }
        val entryB = history.first { it.date == dayB }
        val entryC = history.first { it.date == dayC }

        // Day A (2000 kcal) -> Positive RR
        assertTrue("Day A must have positive delta", entryA.rrDelta > 0)
        // Day B (6000 kcal) -> Negative RR
        assertTrue("Day B must have negative delta", entryB.rrDelta < 0)
        // Day C (2200 kcal) -> Positive RR
        assertTrue("Day C must have positive delta", entryC.rrDelta > 0)
    }

    // ==========================================
    // 5. Rank Image Resolver Tests
    // ==========================================

    @Test
    fun testRankImageResolver_allRanksResolveToNonZeroDrawables() {
        for (rank in Rank.entries) {
            val res = com.macrobase.app.feature.rank.RankImageResolver.getRankDrawableRes(rank)
            assertTrue("Drawable for $rank must be non-zero", res != 0)
        }
    }

    @Test
    fun testRankImageResolver_allRankTiersResolveCorrectly() {
        for (rank in Rank.entries) {
            for (tier in Tier.entries) {
                val rankTier = RankTier(rank, tier)
                val res = com.macrobase.app.feature.rank.RankImageResolver.getRankDrawableRes(rankTier)
                val expected = com.macrobase.app.feature.rank.RankImageResolver.getRankDrawableRes(rank)
                assertEquals(expected, res)
            }
        }
    }

    @Test
    fun testRankImageResolver_specificRankMappings() {
        assertEquals(R.drawable.rank_fat_bear, com.macrobase.app.feature.rank.RankImageResolver.getRankDrawableRes(Rank.FAT_BEAR))
        assertEquals(R.drawable.rank_average, com.macrobase.app.feature.rank.RankImageResolver.getRankDrawableRes(Rank.AVERAGE))
        assertEquals(R.drawable.rank_carb_merchant, com.macrobase.app.feature.rank.RankImageResolver.getRankDrawableRes(Rank.CARB_MERCHANT))
        assertEquals(R.drawable.rank_protein_alchemist, com.macrobase.app.feature.rank.RankImageResolver.getRankDrawableRes(Rank.PROTEIN_ALCHEMIST))
        assertEquals(R.drawable.rank_gym_cat, com.macrobase.app.feature.rank.RankImageResolver.getRankDrawableRes(Rank.GYM_CAT))
        assertEquals(R.drawable.rank_muscle_buster, com.macrobase.app.feature.rank.RankImageResolver.getRankDrawableRes(Rank.MUSCLE_BUSTER))
        assertEquals(R.drawable.rank_fart_monster, com.macrobase.app.feature.rank.RankImageResolver.getRankDrawableRes(Rank.FART_MONSTER))
        assertEquals(R.drawable.rank_gigachad, com.macrobase.app.feature.rank.RankImageResolver.getRankDrawableRes(Rank.GIGACHAD))
    }

    // ==========================================
    // 6. Rank Ladder Tests
    // ==========================================

    @Test
    fun testRankLadder_hasExactly24TiersInExactOrder() {
        val allTiers = Rank.entries.flatMap { rank ->
            Tier.entries.map { tier ->
                RankTier(rank, tier)
            }
        }

        assertEquals(24, allTiers.size)

        // Starting rank is Fat Bear I
        assertEquals("Fat Bear I", allTiers.first().displayName)
        assertEquals(0, allTiers.first().tierGlobalIndex)

        // Max rank is GigaChad III
        assertEquals("GigaChad III", allTiers.last().displayName)
        assertEquals(23, allTiers.last().tierGlobalIndex)

        // Verify exact order
        val expectedNames = listOf(
            "Fat Bear I", "Fat Bear II", "Fat Bear III",
            "Average I", "Average II", "Average III",
            "Carb Merchant I", "Carb Merchant II", "Carb Merchant III",
            "Protein Alchemist I", "Protein Alchemist II", "Protein Alchemist III",
            "Gym Cat I", "Gym Cat II", "Gym Cat III",
            "Muscle Buster I", "Muscle Buster II", "Muscle Buster III",
            "Fart Monster I", "Fart Monster II", "Fart Monster III",
            "GigaChad I", "GigaChad II", "GigaChad III"
        )

        val actualNames = allTiers.map { it.displayName }
        assertEquals(expectedNames, actualNames)

        // Verify every tier has a valid non-zero image
        for (tier in allTiers) {
            val res = com.macrobase.app.feature.rank.RankImageResolver.getRankDrawableRes(tier)
            assertTrue("Every tier in ladder must have valid drawable: ${tier.displayName}", res != 0)
        }
    }

    // ==========================================
    // 7. Pure Mathematical Scoring Engine Tests
    // ==========================================

    @Test
    fun testBulkingCalorieCurve_exactMathematicalContracts() {
        val maintenance = 2500.0

        // At maintenance (2500 kcal, delta = 0): calorie score = 60.0
        val scoreMaintenance = RankScoringConfig.evaluateCalorieScore(
            actualCalories = 2500.0,
            maintenanceCalories = maintenance,
            goal = FitnessGoal.BULKING
        )
        assertEquals(60.0, scoreMaintenance, 0.001)

        // At +100 kcal surplus (2600 kcal): calorie score = 70.0.
        // When macro score is 80+, RR delta is +6 RR (in the +5 to +7 range).
        val scoreSurplus100 = RankScoringConfig.evaluateCalorieScore(
            actualCalories = 2600.0,
            maintenanceCalories = maintenance,
            goal = FitnessGoal.BULKING
        )
        assertEquals(70.0, scoreSurplus100, 0.001)

        val lifestyleScoreSurplus100 = RankScoringConfig.calculateLifestyleScore(
            consistencyScore = 70.0,
            calorieScore = scoreSurplus100,
            macroScore = 80.0,
            weightTrendScore = 50.0
        )
        val deltaSurplus100 = RankScoringConfig.calculateRRDelta(
            lifestyleScore = lifestyleScoreSurplus100,
            loggedDaysCount = 30,
            macroScore = 80.0
        )
        assertTrue("RR delta for +100 kcal surplus with macro >= 80 must be in [+5, +7]", deltaSurplus100 in 5..7)

        // Direct mapping check: score 70.0 -> (70 - 60) * 0.6 = +6 RR
        val directRRDelta = RankScoringConfig.calculateRRDelta(
            lifestyleScore = 70.0,
            loggedDaysCount = 30,
            macroScore = 80.0
        )
        assertEquals(6, directRRDelta)

        // At +350 kcal surplus (2850 kcal): calorie score = 100.0. High RR delta (+18 to +20).
        val scoreSurplus350 = RankScoringConfig.evaluateCalorieScore(
            actualCalories = 2850.0,
            maintenanceCalories = maintenance,
            goal = FitnessGoal.BULKING
        )
        assertEquals(100.0, scoreSurplus350, 0.001)

        val highLifestyleScore = RankScoringConfig.calculateLifestyleScore(
            consistencyScore = 100.0,
            calorieScore = scoreSurplus350,
            macroScore = 95.0,
            weightTrendScore = 100.0
        )
        val deltaSurplus350 = RankScoringConfig.calculateRRDelta(
            lifestyleScore = highLifestyleScore,
            loggedDaysCount = 30,
            macroScore = 95.0
        )
        assertTrue("High RR delta must be in [+18, +20]", deltaSurplus350 in 18..20)

        // At -200 kcal deficit (2300 kcal): calorie score = 35.0. Negative RR delta.
        val scoreDeficit200 = RankScoringConfig.evaluateCalorieScore(
            actualCalories = 2300.0,
            maintenanceCalories = maintenance,
            goal = FitnessGoal.BULKING
        )
        assertEquals(35.0, scoreDeficit200, 0.001)

        val deficitLifestyle = RankScoringConfig.calculateLifestyleScore(
            consistencyScore = 50.0,
            calorieScore = scoreDeficit200,
            macroScore = 50.0,
            weightTrendScore = 50.0
        )
        val deltaDeficit200 = RankScoringConfig.calculateRRDelta(
            lifestyleScore = deficitLifestyle,
            loggedDaysCount = 30,
            macroScore = 50.0
        )
        assertTrue("Deficit while bulking must produce negative RR delta", deltaDeficit200 < 0)

        // At +800 kcal surplus (3300 kcal): calorie score = 0.0. Negative RR delta.
        val scoreSurplus800 = RankScoringConfig.evaluateCalorieScore(
            actualCalories = 3300.0,
            maintenanceCalories = maintenance,
            goal = FitnessGoal.BULKING
        )
        assertEquals(0.0, scoreSurplus800, 0.001)

        val overageLifestyle = RankScoringConfig.calculateLifestyleScore(
            consistencyScore = 50.0,
            calorieScore = scoreSurplus800,
            macroScore = 50.0,
            weightTrendScore = 50.0
        )
        val deltaSurplus800 = RankScoringConfig.calculateRRDelta(
            lifestyleScore = overageLifestyle,
            loggedDaysCount = 30,
            macroScore = 50.0
        )
        assertTrue("Massive surplus must produce negative RR delta", deltaSurplus800 < 0)
    }

    @Test
    fun testCuttingCalorieCurve_exactMathematicalContracts() {
        val maintenance = 2500.0

        // At -100 kcal deficit (2400 kcal): calorie score = 70.0. RR delta is +6 RR.
        val scoreDeficit100 = RankScoringConfig.evaluateCalorieScore(
            actualCalories = 2400.0,
            maintenanceCalories = maintenance,
            goal = FitnessGoal.CUTTING
        )
        assertEquals(70.0, scoreDeficit100, 0.001)

        val deltaDeficit100 = RankScoringConfig.calculateRRDelta(
            lifestyleScore = 70.0,
            loggedDaysCount = 30,
            macroScore = 80.0
        )
        assertEquals(6, deltaDeficit100)

        // At -350 kcal deficit (2150 kcal): calorie score = 100.0. High RR delta (+18 to +20).
        val scoreDeficit350 = RankScoringConfig.evaluateCalorieScore(
            actualCalories = 2150.0,
            maintenanceCalories = maintenance,
            goal = FitnessGoal.CUTTING
        )
        assertEquals(100.0, scoreDeficit350, 0.001)

        val highCuttingLifestyle = RankScoringConfig.calculateLifestyleScore(
            consistencyScore = 100.0,
            calorieScore = scoreDeficit350,
            macroScore = 95.0,
            weightTrendScore = 100.0
        )
        val deltaDeficit350 = RankScoringConfig.calculateRRDelta(
            lifestyleScore = highCuttingLifestyle,
            loggedDaysCount = 30,
            macroScore = 95.0
        )
        assertTrue("High cutting RR delta must be in [+18, +20]", deltaDeficit350 in 18..20)

        // At +200 kcal surplus (2700 kcal): calorie score = 35.0. Negative RR delta.
        val scoreSurplus200 = RankScoringConfig.evaluateCalorieScore(
            actualCalories = 2700.0,
            maintenanceCalories = maintenance,
            goal = FitnessGoal.CUTTING
        )
        assertEquals(35.0, scoreSurplus200, 0.001)

        val surplusLifestyle = RankScoringConfig.calculateLifestyleScore(
            consistencyScore = 50.0,
            calorieScore = scoreSurplus200,
            macroScore = 50.0,
            weightTrendScore = 50.0
        )
        val deltaSurplus200 = RankScoringConfig.calculateRRDelta(
            lifestyleScore = surplusLifestyle,
            loggedDaysCount = 30,
            macroScore = 50.0
        )
        assertTrue("Surplus while cutting must produce negative RR delta", deltaSurplus200 < 0)

        // At -800 kcal crash deficit (1700 kcal): calorie score = 0.0. Negative RR delta.
        val scoreCrash800 = RankScoringConfig.evaluateCalorieScore(
            actualCalories = 1700.0,
            maintenanceCalories = maintenance,
            goal = FitnessGoal.CUTTING
        )
        assertEquals(0.0, scoreCrash800, 0.001)

        val crashLifestyle = RankScoringConfig.calculateLifestyleScore(
            consistencyScore = 50.0,
            calorieScore = scoreCrash800,
            macroScore = 50.0,
            weightTrendScore = 50.0
        )
        val deltaCrash800 = RankScoringConfig.calculateRRDelta(
            lifestyleScore = crashLifestyle,
            loggedDaysCount = 30,
            macroScore = 50.0
        )
        assertTrue("Starvation crash deficit must produce negative RR delta", deltaCrash800 < 0)
    }

    @Test
    fun testMacroQualityGate_blocksPositiveRRWhenMacroScoreBelow60() {
        // When calories are in optimal sweet spot, but macroScore = 45.0 (< 60.0):
        // calculateRRDelta(lifestyleScore = 85.0, loggedDaysCount = 30, macroScore = 45.0) must return 0
        val blockedDelta = RankScoringConfig.calculateRRDelta(
            lifestyleScore = 85.0,
            loggedDaysCount = 30,
            macroScore = 45.0
        )
        assertEquals(0, blockedDelta)

        // When macro score is >= 60.0 (e.g. 60.0), positive RR is unlocked:
        // (85.0 - 60.0) * 0.6 = 15.0 -> +15 RR
        val unlockedDelta = RankScoringConfig.calculateRRDelta(
            lifestyleScore = 85.0,
            loggedDaysCount = 30,
            macroScore = 60.0
        )
        assertEquals(15, unlockedDelta)
    }

    @Test
    fun testRRDeltaClamping_strictlyClampedToMinus20AndPlus20() {
        // Worst possible score (0.0): clamped to -20
        assertEquals(-20, RankScoringConfig.calculateRRDelta(0.0, 30))

        // Perfect score (100.0): clamped to +20
        assertEquals(20, RankScoringConfig.calculateRRDelta(100.0, 30))

        // Extreme out-of-bounds scores
        assertEquals(-20, RankScoringConfig.calculateRRDelta(-100.0, 30))
        assertEquals(20, RankScoringConfig.calculateRRDelta(200.0, 30))
    }

    @Test
    fun testEightPercentWeightTrendWeightage_andGoalSpecificWeightCurves() {
        // Verify component weights sum exactly to 1.0
        val totalWeight = RankScoringConfig.CONSISTENCY_WEIGHT +
            RankScoringConfig.CALORIE_WEIGHT +
            RankScoringConfig.MACRO_WEIGHT +
            RankScoringConfig.WEIGHT_TREND_WEIGHT +
            RankScoringConfig.WATER_WEIGHT
        assertEquals(1.0, totalWeight, 0.000001)
        assertEquals(0.08, RankScoringConfig.WEIGHT_TREND_WEIGHT, 0.000001)

        // Bulking: +0.8 kg delta gives score 100.0, while loss (-1.0 kg) gives score 20.0
        val bulkingGood = RankScoringConfig.evaluateWeightTrendScore(
            deltaWeightKg = 0.8,
            entryCount = 5,
            goal = FitnessGoal.BULKING
        )
        assertEquals(100.0, bulkingGood, 0.001)

        val bulkingLoss = RankScoringConfig.evaluateWeightTrendScore(
            deltaWeightKg = -1.0,
            entryCount = 5,
            goal = FitnessGoal.BULKING
        )
        assertEquals(20.0, bulkingLoss, 0.001)

        // Cutting: -1.2 kg delta gives score 100.0, while gain (+1.0 kg) gives score 20.0
        val cuttingGood = RankScoringConfig.evaluateWeightTrendScore(
            deltaWeightKg = -1.2,
            entryCount = 5,
            goal = FitnessGoal.CUTTING
        )
        assertEquals(100.0, cuttingGood, 0.001)

        val cuttingGain = RankScoringConfig.evaluateWeightTrendScore(
            deltaWeightKg = 1.0,
            entryCount = 5,
            goal = FitnessGoal.CUTTING
        )
        assertEquals(20.0, cuttingGain, 0.001)
    }

    // ==========================================
    // 8. Next-Day Goal Scheduling & Immutability Tests
    // ==========================================

    @Test
    fun testNextDayScheduling_and_historyImmutability() = runTest(testDispatcher) {
        val today = LocalDate.now()
        val cuttingGoal = Goal(
            dailyCalorieGoal = 1800.0,
            carbPercentage = 40.0,
            proteinPercentage = 35.0,
            fatPercentage = 25.0,
            fitnessGoal = FitnessGoal.CUTTING,
            maintenanceCalories = 2200.0
        )
        val bulkingGoal = Goal(
            dailyCalorieGoal = 2800.0,
            carbPercentage = 50.0,
            proteinPercentage = 25.0,
            fatPercentage = 25.0,
            fitnessGoal = FitnessGoal.BULKING,
            maintenanceCalories = 2500.0
        )

        val fakeGoals = FakeGoalsRepository(initialGoal = cuttingGoal, simulatedToday = today)

        // Generate 15 days of logged entries
        val aggregations = (14 downTo 0).map { daysAgo ->
            val d = today.minusDays(daysAgo.toLong())
            DailyMacroAggregation(
                date = d,
                calories = 1800.0,
                proteinGrams = cuttingGoal.proteinGrams,
                carbsGrams = cuttingGoal.carbGrams,
                fatGrams = cuttingGoal.fatGrams
            )
        }

        val fakeDiary = FakeDiaryRepository(aggregations = aggregations)
        val fakeStats = FakeStatisticsRepository(loggedDaysCount = 15, avgCalories = 1800.0)
        val fakeWeight = FakeWeightRepository()
        val fakePrefs = FakePreferencesRepository()

        val rankRepo = RankRepositoryImpl(
            statisticsRepository = fakeStats,
            goalsRepository = fakeGoals,
            weightRepository = fakeWeight,
            preferencesRepository = fakePrefs,
            diaryRepository = fakeDiary
        )

        // Baseline profile with Cutting active
        val profileBefore = rankRepo.getRankProfile()

        // Update goal to BULKING on date today (T)
        fakeGoals.updateGoals(bulkingGoal)

        // Invariant: getGoalForDate(T) returns CUTTING
        val goalToday = fakeGoals.getGoalForDate(today)
        assertEquals(FitnessGoal.CUTTING, goalToday.fitnessGoal)
        assertEquals(2200.0, goalToday.maintenanceCalories, 0.001)

        // Invariant: getGoalForDate(T + 1) returns BULKING
        val goalTomorrow = fakeGoals.getGoalForDate(today.plusDays(1))
        assertEquals(FitnessGoal.BULKING, goalTomorrow.fitnessGoal)
        assertEquals(2500.0, goalTomorrow.maintenanceCalories, 0.001)

        // Invariant: Past evaluations and today (<= T) MUST remain 100% immutable
        val profileAfter = rankRepo.getRankProfile()
        assertEquals(profileBefore.currentRating, profileAfter.currentRating)
        assertEquals(profileBefore.rankTier, profileAfter.rankTier)
        assertEquals(profileBefore.tierRR, profileAfter.tierRR)
        assertEquals(profileBefore.recentHistory.size, profileAfter.recentHistory.size)

        for (i in profileBefore.recentHistory.indices) {
            val entryBefore = profileBefore.recentHistory[i]
            val entryAfter = profileAfter.recentHistory[i]
            assertEquals(entryBefore.date, entryAfter.date)
            assertEquals(entryBefore.overallRating, entryAfter.overallRating)
            assertEquals(entryBefore.rrDelta, entryAfter.rrDelta)
            assertEquals(entryBefore.calorieScore, entryAfter.calorieScore, 0.001)
            assertEquals(entryBefore.macroScore, entryAfter.macroScore, 0.001)
        }
    }

    @Test
    fun testRepositoryRollingEvaluation_bulking30DaysAggregation_producesPositiveCappedDeltas() = runTest(testDispatcher) {
        val today = LocalDate.now()
        val bulkingGoal = Goal(
            dailyCalorieGoal = 2850.0,
            carbPercentage = 45.0,
            proteinPercentage = 25.0,
            fatPercentage = 30.0,
            fitnessGoal = FitnessGoal.BULKING,
            maintenanceCalories = 2500.0
        )

        val fakeGoals = FakeGoalsRepository(initialGoal = bulkingGoal, simulatedToday = today)

        // Simulate 30-day aggregation: 2850 kcal (+350 kcal surplus in sweet spot), good macros
        val aggregations = (29 downTo 0).map { daysAgo ->
            val d = today.minusDays(daysAgo.toLong())
            DailyMacroAggregation(
                date = d,
                calories = 2850.0,
                proteinGrams = bulkingGoal.proteinGrams,
                carbsGrams = bulkingGoal.carbGrams,
                fatGrams = bulkingGoal.fatGrams
            )
        }

        val fakeStats = FakeStatisticsRepository(
            consistencyScore = 100,
            loggedDaysCount = 30,
            avgCalories = 2850.0,
            avgProtein = bulkingGoal.proteinGrams,
            avgCarbs = bulkingGoal.carbGrams,
            avgFat = bulkingGoal.fatGrams
        )

        val fakeWeight = FakeWeightRepository()
        val fakePrefs = FakePreferencesRepository()
        val fakeDiary = FakeDiaryRepository(aggregations = aggregations)

        val rankRepo = RankRepositoryImpl(
            statisticsRepository = fakeStats,
            goalsRepository = fakeGoals,
            weightRepository = fakeWeight,
            preferencesRepository = fakePrefs,
            diaryRepository = fakeDiary
        )

        val profile = rankRepo.getRankProfile()

        assertFalse("30 days logged must not be provisional", profile.isProvisional)
        assertEquals(30, profile.loggedDaysCount)
        assertTrue("Lifestyle score must be excellent", profile.lifestyleScore.overallScore >= 85.0)

        // Assert RankProfile generates positive daily RR deltas capped at <= 20
        for (historyEntry in profile.recentHistory) {
            assertTrue("Daily RR delta for optimal bulking must be positive (got ${historyEntry.rrDelta})", historyEntry.rrDelta > 0)
            assertTrue("Daily RR delta must be strictly capped at <= 20 (got ${historyEntry.rrDelta})", historyEntry.rrDelta <= 20)
        }

        assertTrue("Final rating must have progressed significantly from MIN_RATING (0)", profile.currentRating > 200)
    }

    // ==========================================
    // 9. UI & Controls Integration Tests (Part 3)
    // ==========================================

    @Test
    fun testDailyGoalsViewModel_updatesFitnessGoalAndMaintenanceCalories() = runTest {
        val fakeGoals = FakeGoalsRepository(
            initialGoal = Goal(
                dailyCalorieGoal = 2000.0,
                carbPercentage = 50.0,
                proteinPercentage = 25.0,
                fatPercentage = 25.0,
                fitnessGoal = FitnessGoal.MAINTAINING,
                maintenanceCalories = 2000.0
            )
        )
        val getGoalsUseCase = GetGoalsUseCase(fakeGoals)
        val updateGoalsUseCase = UpdateGoalsUseCase(fakeGoals)
        val viewModel = DailyGoalsViewModel(getGoalsUseCase, updateGoalsUseCase)

        var successCalled = false
        viewModel.updateGoals(
            calories = 2400.0,
            carbsPct = 40.0,
            proteinPct = 30.0,
            fatPct = 30.0,
            fitnessGoal = FitnessGoal.BULKING,
            maintenanceCalories = 2100.0,
            onSuccess = { successCalled = true }
        )

        testScheduler.advanceUntilIdle()
        assertTrue("onSuccess callback must be executed", successCalled)

        val scheduledGoal = fakeGoals.getGoalForDate(fakeGoals.simulatedToday.plusDays(1))
        assertEquals(FitnessGoal.BULKING, scheduledGoal.fitnessGoal)
        assertEquals(2100.0, scheduledGoal.maintenanceCalories, 0.001)
        assertEquals(2400.0, scheduledGoal.dailyCalorieGoal, 0.001)
    }

    @Test
    fun testCalorieTargetSubtitleFormatting_forAllStrategies() {
        fun computeCalorieSubtitle(goal: FitnessGoal, maintenanceCalories: Double): String {
            val maint = maintenanceCalories.roundToInt()
            return when (goal) {
                FitnessGoal.BULKING -> {
                    val minStr = String.format(Locale.US, "%,d", maint)
                    val maxStr = String.format(Locale.US, "%,d", maint + 500)
                    "Target: $minStr \u2013 $maxStr kcal (Surplus)"
                }
                FitnessGoal.CUTTING -> {
                    val minStr = String.format(Locale.US, "%,d", (maint - 500).coerceAtLeast(0))
                    val maxStr = String.format(Locale.US, "%,d", maint)
                    "Target: $minStr \u2013 $maxStr kcal (Deficit)"
                }
                FitnessGoal.MAINTAINING -> {
                    val minStr = String.format(Locale.US, "%,d", (maint - 100).coerceAtLeast(0))
                    val maxStr = String.format(Locale.US, "%,d", maint + 100)
                    "Target: $minStr \u2013 $maxStr kcal (\u00b1100)"
                }
            }
        }

        val bulkingSubtitle = computeCalorieSubtitle(FitnessGoal.BULKING, 2400.0)
        assertEquals("Target: 2,400 \u2013 2,900 kcal (Surplus)", bulkingSubtitle)

        val cuttingSubtitle = computeCalorieSubtitle(FitnessGoal.CUTTING, 2400.0)
        assertEquals("Target: 1,900 \u2013 2,400 kcal (Deficit)", cuttingSubtitle)

        val maintainingSubtitle = computeCalorieSubtitle(FitnessGoal.MAINTAINING, 2000.0)
        assertEquals("Target: 1,900 \u2013 2,100 kcal (\u00b1100)", maintainingSubtitle)
    }

    @Test
    fun testStrategyBadgeFormatting_andPendingNotice() {
        fun formatBadge(goal: FitnessGoal, maintenanceCalories: Double): String {
            val formattedMaint = String.format(Locale.US, "%,d", maintenanceCalories.roundToInt())
            return "${goal.displayName.uppercase()} \u2022 $formattedMaint kcal Maintenance"
        }

        assertEquals("BULKING \u2022 2,400 kcal Maintenance", formatBadge(FitnessGoal.BULKING, 2400.0))
        assertEquals("CUTTING \u2022 1,850 kcal Maintenance", formatBadge(FitnessGoal.CUTTING, 1850.0))
        assertEquals("MAINTAINING \u2022 2,000 kcal Maintenance", formatBadge(FitnessGoal.MAINTAINING, 2000.0))
    }

    @Test
    fun testRankProfile_propagatesStrategyAndPendingChange() = runTest {
        val today = LocalDate.of(2026, 6, 1)
        val cuttingGoal = Goal(
            dailyCalorieGoal = 1800.0,
            carbPercentage = 40.0,
            proteinPercentage = 35.0,
            fatPercentage = 25.0,
            fitnessGoal = FitnessGoal.CUTTING,
            maintenanceCalories = 2200.0
        )
        val fakeGoals = FakeGoalsRepository(initialGoal = cuttingGoal, simulatedToday = today)
        val rankRepo = RankRepositoryImpl(
            statisticsRepository = FakeStatisticsRepository(loggedDaysCount = 14),
            goalsRepository = fakeGoals,
            weightRepository = FakeWeightRepository(),
            preferencesRepository = FakePreferencesRepository(),
            diaryRepository = FakeDiaryRepository()
        )

        val profileBefore = rankRepo.getRankProfile()
        assertEquals(FitnessGoal.CUTTING, profileBefore.fitnessGoal)
        assertEquals(2200.0, profileBefore.maintenanceCalories, 0.001)
        assertNull(profileBefore.pendingStrategyChange)

        // Schedule switch to Bulking
        val bulkingGoal = Goal(
            dailyCalorieGoal = 2700.0,
            carbPercentage = 50.0,
            proteinPercentage = 25.0,
            fatPercentage = 25.0,
            fitnessGoal = FitnessGoal.BULKING,
            maintenanceCalories = 2400.0
        )
        fakeGoals.updateGoals(bulkingGoal)

        val profileAfter = rankRepo.getRankProfile()
        assertEquals(FitnessGoal.CUTTING, profileAfter.fitnessGoal)
        assertEquals(2200.0, profileAfter.maintenanceCalories, 0.001)
        assertEquals("Bulking (2,400 kcal)", profileAfter.pendingStrategyChange)
    }

    // ==========================================
    // Test Fakes
    // ==========================================

    private class FakeStatisticsRepository(
        var consistencyScore: Int = 90,
        var loggedDaysCount: Int = 25,
        var avgCalories: Double = 2000.0,
        var avgProtein: Double = 125.0,
        var avgCarbs: Double = 250.0,
        var avgFat: Double = 55.0
    ) : StatisticsRepository {
        override fun observeNutritionConsistency(startDate: LocalDate, endDate: LocalDate): Flow<ConsistencyStatistics> {
            return flowOf(
                ConsistencyStatistics(
                    startDate = startDate,
                    endDate = endDate,
                    totalEligibleDays = 30,
                    loggedDaysCount = loggedDaysCount,
                    consistencyScorePercentage = consistencyScore
                )
            )
        }

        override suspend fun getNutritionConsistency(startDate: LocalDate, endDate: LocalDate): ConsistencyStatistics {
            return ConsistencyStatistics(
                startDate = startDate,
                endDate = endDate,
                totalEligibleDays = 30,
                loggedDaysCount = loggedDaysCount,
                consistencyScorePercentage = consistencyScore
            )
        }

        override fun observeMacroAverages(startDate: LocalDate, endDate: LocalDate): Flow<MacroAveragesStatistics> {
            return flowOf(
                MacroAveragesStatistics(
                    startDate = startDate,
                    endDate = endDate,
                    loggedDaysCount = loggedDaysCount,
                    averageCalories = avgCalories,
                    averageProteinGrams = avgProtein,
                    averageCarbsGrams = avgCarbs,
                    averageFatGrams = avgFat,
                    actualCarbsPercent = 50.0,
                    actualProteinPercent = 25.0,
                    actualFatPercent = 25.0,
                    targetCarbsPercent = 50.0,
                    targetProteinPercent = 25.0,
                    targetFatPercent = 25.0,
                    targetCalories = 2000.0
                )
            )
        }

        override suspend fun getMacroAverages(startDate: LocalDate, endDate: LocalDate): MacroAveragesStatistics {
            return MacroAveragesStatistics(
                startDate = startDate,
                endDate = endDate,
                loggedDaysCount = loggedDaysCount,
                averageCalories = avgCalories,
                averageProteinGrams = avgProtein,
                averageCarbsGrams = avgCarbs,
                averageFatGrams = avgFat,
                actualCarbsPercent = 50.0,
                actualProteinPercent = 25.0,
                actualFatPercent = 25.0,
                targetCarbsPercent = 50.0,
                targetProteinPercent = 25.0,
                targetFatPercent = 25.0,
                targetCalories = 2000.0
            )
        }

        override fun observeWeightStatistics(startDate: LocalDate, endDate: LocalDate): Flow<WeightStatistics> {
            return flowOf(
                WeightStatistics(
                    startDate = startDate,
                    endDate = endDate,
                    initialWeightKg = 80.0,
                    latestWeightKg = 79.5,
                    deltaWeightKg = -0.5,
                    entries = listOf(
                        WeightEntry(id = 1, date = startDate, weightKg = 80.0),
                        WeightEntry(id = 2, date = endDate, weightKg = 79.5)
                    )
                )
            )
        }

        override suspend fun getWeightStatistics(startDate: LocalDate, endDate: LocalDate): WeightStatistics {
            return WeightStatistics(
                startDate = startDate,
                endDate = endDate,
                initialWeightKg = 80.0,
                latestWeightKg = 79.5,
                deltaWeightKg = -0.5,
                entries = listOf(
                    WeightEntry(id = 1, date = startDate, weightKg = 80.0),
                    WeightEntry(id = 2, date = endDate, weightKg = 79.5)
                )
            )
        }
    }

    private class FakeGoalsRepository(
        initialGoal: Goal = Goal(
            dailyCalorieGoal = 2000.0,
            carbPercentage = 50.0,
            proteinPercentage = 25.0,
            fatPercentage = 25.0,
            fitnessGoal = FitnessGoal.MAINTAINING,
            maintenanceCalories = 2000.0
        ),
        var simulatedToday: LocalDate = LocalDate.now()
    ) : GoalsRepository {
        private val goalsFlow = MutableStateFlow(initialGoal)
        private val history = mutableListOf<Pair<LocalDate, Goal>>()
        private var scheduledGoal: Pair<LocalDate, Goal>? = null

        init {
            history.add(LocalDate.MIN to initialGoal)
        }

        override suspend fun getGoals(): Goal = goalsFlow.value
        override fun observeGoals(): Flow<Goal> = goalsFlow
        override suspend fun updateGoals(goals: Goal) {
            val current = goalsFlow.value
            val strategyChanged = (goals.fitnessGoal != current.fitnessGoal) || (goals.maintenanceCalories != current.maintenanceCalories)
            if (strategyChanged) {
                val effectiveDate = simulatedToday.plusDays(1)
                scheduledGoal = effectiveDate to goals
                history.removeAll { it.first == effectiveDate }
                history.add(effectiveDate to goals)
                goalsFlow.value = current.copy(
                    dailyCalorieGoal = goals.dailyCalorieGoal,
                    carbPercentage = goals.carbPercentage,
                    proteinPercentage = goals.proteinPercentage,
                    fatPercentage = goals.fatPercentage,
                    scheduledFitnessGoal = goals.fitnessGoal,
                    scheduledMaintenanceCalories = goals.maintenanceCalories,
                    scheduledEffectiveDate = effectiveDate
                )
            } else {
                goalsFlow.value = goals
            }
        }

        override suspend fun getGoalForDate(date: LocalDate): Goal {
            if (date.isAfter(simulatedToday) && scheduledGoal != null && !date.isBefore(scheduledGoal!!.first)) {
                return scheduledGoal!!.second
            }
            val match = history.filter { !it.first.isAfter(date) }.maxByOrNull { it.first }
            return match?.second ?: goalsFlow.value
        }
    }

    private class FakePreferencesRepository : PreferencesRepository {
        private val prefsFlow = MutableStateFlow(
            UserPreferences(
                currentWeightKg = 80.0,
                targetWeightKg = 75.0
            )
        )

        override suspend fun getPreferences(): UserPreferences = prefsFlow.value
        override fun observePreferences(): Flow<UserPreferences> = prefsFlow
        override suspend fun updatePreferences(preferences: UserPreferences) {
            prefsFlow.value = preferences
        }
    }

    private class FakeWeightRepository : WeightRepository {
        override suspend fun addWeightEntry(entry: WeightEntry): Long = 1L
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

    private class FakeDiaryRepository(
        private val aggregations: List<DailyMacroAggregation> = emptyList()
    ) : DiaryRepository {
        override suspend fun getDiaryForDate(date: LocalDate) = DailyNutritionSummary(date = date)
        override fun observeDiaryForDate(date: LocalDate) = flowOf(DailyNutritionSummary(date = date))
        override suspend fun getEntryById(entryId: Long): DiaryEntry? = null
        override suspend fun addEntry(entry: DiaryEntry): Long = 1L
        override suspend fun addEntries(entries: List<DiaryEntry>) {}
        override suspend fun updateEntry(entry: DiaryEntry) {}
        override suspend fun deleteEntry(entryId: Long) {}
        override suspend fun getMonthlyAdherence(year: Int, month: Int) = emptyList<CalendarDaySummary>()
        override fun observeMonthlyAdherence(year: Int, month: Int) = flowOf(emptyList<CalendarDaySummary>())
        override fun observeNutritionAggregations(startDate: LocalDate, endDate: LocalDate): Flow<List<DailyMacroAggregation>> = flowOf(aggregations)
        override suspend fun getNutritionAggregations(startDate: LocalDate, endDate: LocalDate): List<DailyMacroAggregation> = aggregations
    }
}
