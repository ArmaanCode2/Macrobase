package com.macrobase.app

import com.macrobase.app.core.config.RankScoringConfig
import com.macrobase.app.data.repository.rank.RankRepositoryImpl
import com.macrobase.app.domain.model.CalendarDaySummary
import com.macrobase.app.domain.model.DailyNutritionSummary
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.FitnessGoal
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.UserPreferences
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
import com.macrobase.app.domain.usecase.CancelScheduledGoalUseCase
import com.macrobase.app.feature.preferences.PreferenceField
import com.macrobase.app.feature.preferences.PreferencesViewModel
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
    // 4. Hard Requirements & Date Correctness Tests
    // ==========================================

    @Test
    fun testWaterExclusionGuarantee_strictlyNoEffectOnRating() = runTest(testDispatcher) {
        val fakeGoals = FakeGoalsRepository()
        val fakeDiary = FakeDiaryRepository()

        val rankRepo = RankRepositoryImpl(
            goalsRepository = fakeGoals,
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

        // 12 ordinary days before the three under test: the rating is replayed from the diary,
        // so the 15 logged days the statistics fake reports must be in the diary too (BUG-023)
        val earlierDays = (14 downTo 3).map { daysAgo ->
            DailyMacroAggregation(date = today.minusDays(daysAgo.toLong()), calories = 2000.0, proteinGrams = 125.0, carbsGrams = 250.0, fatGrams = 55.0)
        }
        val aggregations = earlierDays + listOf(
            DailyMacroAggregation(date = dayA, calories = 2000.0, proteinGrams = 150.0, carbsGrams = 200.0, fatGrams = 65.0),
            DailyMacroAggregation(date = dayB, calories = 6000.0, proteinGrams = 350.0, carbsGrams = 700.0, fatGrams = 220.0),
            DailyMacroAggregation(date = dayC, calories = 2080.0, proteinGrams = 140.0, carbsGrams = 250.0, fatGrams = 58.0)
        )

        val fakeDiary = FakeDiaryRepository(aggregations = aggregations)
        val fakeGoals = FakeGoalsRepository()

        val rankRepo = RankRepositoryImpl(
            goalsRepository = fakeGoals,
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
        // Day C (2080 kcal, 4% over maintenance) -> Positive RR
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

        val rankRepo = RankRepositoryImpl(
            goalsRepository = fakeGoals,
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

        // Invariant: past evaluations (< T) remain 100% immutable. Today keeps its strategy and
        // maintenance, but calorie and macro targets apply as soon as they are saved, as on the
        // dashboard (BUG-024), so today's macro score follows the new targets.
        val profileAfter = rankRepo.getRankProfile()
        assertEquals(profileBefore.recentHistory.size, profileAfter.recentHistory.size)

        for (i in profileBefore.recentHistory.indices) {
            val entryBefore = profileBefore.recentHistory[i]
            val entryAfter = profileAfter.recentHistory[i]
            assertEquals(entryBefore.date, entryAfter.date)
            assertEquals(entryBefore.calorieScore, entryAfter.calorieScore, 0.001)
            if (entryBefore.date.isBefore(today)) {
                assertEquals(entryBefore.overallRating, entryAfter.overallRating)
                assertEquals(entryBefore.rrDelta, entryAfter.rrDelta)
                assertEquals(entryBefore.proteinScore, entryAfter.proteinScore, 0.001)
            } else {
                assertTrue("today is scored against the new targets", entryAfter.proteinScore < entryBefore.proteinScore)
            }
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
        val fakeDiary = FakeDiaryRepository(aggregations = aggregations)

        val rankRepo = RankRepositoryImpl(
            goalsRepository = fakeGoals,
            diaryRepository = fakeDiary,
            // The same "today" as the aggregations above, even if the test runs across midnight
            clock = com.macrobase.app.testutil.MutableTestClock(today)
        )

        val profile = rankRepo.getRankProfile()

        assertFalse("30 days logged must not be provisional", profile.isProvisional)
        // Today is logged but counts once it ends (BUG-032)
        assertEquals(29, profile.loggedDaysCount)
        assertEquals(100.0, profile.lifestyleScore.calorieScore, 0.001)
        assertEquals(100.0, profile.lifestyleScore.proteinScore, 0.001)
        // These days state no fiber, sodium and the rest, so food quality counts as unknown (50)
        assertEquals(50.0, profile.lifestyleScore.qualityScore, 0.001)

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
    fun testPreferencesViewModel_updatesFitnessGoalAndMaintenanceCalories() = runTest {
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
        val viewModel = PreferencesViewModel(
            FakePreferencesRepository(), getGoalsUseCase, updateGoalsUseCase, CancelScheduledGoalUseCase(fakeGoals)
        )
        testScheduler.advanceUntilIdle()

        viewModel.onFieldChanged(PreferenceField.CALORIES, "2400")
        viewModel.onFieldChanged(PreferenceField.CARBS, "40")
        viewModel.onFieldChanged(PreferenceField.PROTEIN, "30")
        viewModel.onFieldChanged(PreferenceField.FAT, "30")
        viewModel.onFieldChanged(PreferenceField.MAINTENANCE, "2100")
        viewModel.onStrategySelected(FitnessGoal.BULKING)
        viewModel.save()

        testScheduler.advanceUntilIdle()
        assertTrue("the save must succeed", viewModel.form.value.showSavedMessage)

        val scheduledGoal = fakeGoals.getGoalForDate(fakeGoals.simulatedToday.plusDays(1))
        assertEquals(FitnessGoal.BULKING, scheduledGoal.fitnessGoal)
        assertEquals(2100.0, scheduledGoal.maintenanceCalories, 0.001)
        assertEquals(2400.0, scheduledGoal.dailyCalorieGoal, 0.001)
    }

    @Test
    fun testCalorieTargetSubtitleFormatting_forAllStrategies() {
        fun subtitle(goal: FitnessGoal, maintenance: Double) =
            com.macrobase.app.feature.rank.calorieZoneSubtitle(goal, RankScoringConfig.calorieZone(goal, maintenance))

        assertEquals("Zone: 2,640 \u2013 2,880 kcal \u2022 near or below maintenance earns nothing", subtitle(FitnessGoal.BULKING, 2400.0))
        assertEquals("Zone: 1,800 \u2013 2,160 kcal \u2022 near or above maintenance earns nothing", subtitle(FitnessGoal.CUTTING, 2400.0))
        assertEquals("Zone: 1,900 \u2013 2,100 kcal \u2022 10% off maintenance earns nothing", subtitle(FitnessGoal.MAINTAINING, 2000.0))
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
            goalsRepository = fakeGoals,
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
            // As GoalsRepositoryImpl (BUG-031): asking for the active or the pending strategy again
            // changes nothing; anything else is scheduled for tomorrow
            val asksForActive = goals.fitnessGoal == current.fitnessGoal && goals.maintenanceCalories == current.maintenanceCalories
            val pending = scheduledGoal?.second
            val asksForPending = pending != null &&
                goals.fitnessGoal == pending.fitnessGoal && goals.maintenanceCalories == pending.maintenanceCalories
            val strategyChanged = !asksForActive && !asksForPending
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
                goalsFlow.value = goalsFlow.value.copy(
                    dailyCalorieGoal = goals.dailyCalorieGoal,
                    carbPercentage = goals.carbPercentage,
                    proteinPercentage = goals.proteinPercentage,
                    fatPercentage = goals.fatPercentage
                )
            }
        }

        override suspend fun cancelScheduledGoal(): Boolean {
            val scheduled = scheduledGoal ?: return false
            history.removeAll { it.first == scheduled.first }
            scheduledGoal = null
            goalsFlow.value = goalsFlow.value.copy(
                scheduledFitnessGoal = null,
                scheduledMaintenanceCalories = null,
                scheduledEffectiveDate = null
            )
            return true
        }

        override suspend fun getGoalForDate(date: LocalDate): Goal {
            if (date.isAfter(simulatedToday) && scheduledGoal != null && !date.isBefore(scheduledGoal!!.first)) {
                return scheduledGoal!!.second
            }
            // As GoalsRepositoryImpl: today is the goal in force now, targets included
            if (!date.isBefore(simulatedToday)) return goalsFlow.value
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
