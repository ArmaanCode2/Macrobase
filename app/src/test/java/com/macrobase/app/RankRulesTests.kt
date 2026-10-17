package com.macrobase.app

import com.macrobase.app.core.config.RankScoringConfig
import com.macrobase.app.data.repository.rank.RankRepositoryImpl
import com.macrobase.app.domain.model.CalendarDaySummary
import com.macrobase.app.domain.model.CustomFood
import com.macrobase.app.domain.model.DailyNutritionSummary
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.FitnessGoal
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.UserPreferences
import com.macrobase.app.domain.model.rank.RankDayNutrients
import com.macrobase.app.domain.model.rank.RankDayNutrients.KnownAmount
import com.macrobase.app.domain.model.rank.RankHistoryEntry
import com.macrobase.app.domain.model.rank.Rank
import com.macrobase.app.domain.model.rank.RankTier
import com.macrobase.app.domain.model.rank.Tier
import com.macrobase.app.domain.repository.DailyMacroAggregation
import com.macrobase.app.domain.repository.DiaryRepository
import com.macrobase.app.domain.repository.FoodRepository
import com.macrobase.app.domain.repository.GoalsRepository
import com.macrobase.app.domain.repository.LoggedFoodNutrients
import com.macrobase.app.domain.repository.PreferencesRepository
import com.macrobase.app.feature.preferences.calorieTargetWarning
import com.macrobase.app.feature.preferences.strategyRankHint
import com.macrobase.app.feature.preferences.todayStrategyWarning
import com.macrobase.app.feature.rank.historyPageRange
import com.macrobase.app.feature.rank.historyReasonLine
import com.macrobase.app.testutil.MutableTestClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** The rank rules of docs/RANKING_SYSTEM.md: calorie credit x food score against the rank's expectation. */
class RankRulesTests {

    private val today = LocalDate.of(2026, 10, 13)

    // Cutting: maintenance 2,000 kcal, a 1,600 kcal goal at 25/50/25 (100 g protein, 200 g carbs, 44 g fat)
    private val cutting = Goal(
        dailyCalorieGoal = 1600.0, carbPercentage = 50.0, proteinPercentage = 25.0, fatPercentage = 25.0,
        fitnessGoal = FitnessGoal.CUTTING, maintenanceCalories = 2000.0
    )

    // ---------------------------------------------------------------------------------------
    // Calorie credit
    // ---------------------------------------------------------------------------------------

    // The owner's example: cutting at 2,000 kcal maintenance, eating near 2,000 must earn nothing
    @Test
    fun cuttingNearMaintenanceEarnsNoCalorieCredit() {
        val credit = { kcal: Double -> RankScoringConfig.calorieCredit(FitnessGoal.CUTTING, kcal, 2000.0) }
        assertEquals(0.0, credit(2300.0), 0.0)
        assertEquals(0.0, credit(2000.0), 0.0)
        assertEquals(0.0, credit(1950.0), 0.0)
        assertEquals(0.0, credit(1900.0), 1e-9)
        assertEquals(0.5, credit(1850.0), 1e-9)
        assertEquals(1.0, credit(1800.0), 1e-9)
        assertEquals(1.0, credit(1500.0), 1e-9)
        assertEquals(0.5, credit(1400.0), 1e-9)
        assertEquals(0.0, credit(1300.0), 1e-9)
        assertEquals(0.0, credit(900.0), 0.0)
    }

    @Test
    fun bulkingNeedsARealSurplus() {
        val credit = { kcal: Double -> RankScoringConfig.calorieCredit(FitnessGoal.BULKING, kcal, 2500.0) }
        assertEquals(0.0, credit(2400.0), 0.0)
        assertEquals(0.0, credit(2500.0), 0.0)
        assertEquals(0.0, credit(2560.0), 0.0)
        assertEquals(0.0, credit(2625.0), 1e-9)
        assertEquals(1.0, credit(2750.0), 1e-9)
        assertEquals(1.0, credit(3000.0), 1e-9)
        assertEquals(0.5, credit(3125.0), 1e-9)
        assertEquals(0.0, credit(3250.0), 1e-9)
    }

    @Test
    fun maintainingStaysNearMaintenance() {
        val credit = { kcal: Double -> RankScoringConfig.calorieCredit(FitnessGoal.MAINTAINING, kcal, 2200.0) }
        assertEquals(1.0, credit(2200.0), 0.0)
        assertEquals(1.0, credit(2310.0), 1e-9)
        assertEquals(0.5, credit(2365.0), 1e-9)
        assertEquals(0.0, credit(2420.0), 1e-9)
        assertEquals(0.0, credit(1900.0), 0.0)
    }

    @Test
    fun noMaintenanceOrNoFoodGivesNoCredit() {
        assertEquals(0.0, RankScoringConfig.calorieCredit(FitnessGoal.MAINTAINING, 2000.0, 0.0), 0.0)
        assertEquals(0.0, RankScoringConfig.calorieCredit(FitnessGoal.CUTTING, 0.0, 2000.0), 0.0)
    }

    @Test
    fun theZoneIsTheFullCreditBand() {
        assertEquals(1500.0..1800.0, RankScoringConfig.calorieZone(FitnessGoal.CUTTING, 2000.0))
        assertEquals(2750.0..3000.0, RankScoringConfig.calorieZone(FitnessGoal.BULKING, 2500.0))
        assertEquals(2090.0..2310.0, RankScoringConfig.calorieZone(FitnessGoal.MAINTAINING, 2200.0))
        for (strategy in FitnessGoal.entries) {
            val zone = RankScoringConfig.calorieZone(strategy, 2000.0)
            assertEquals(1.0, RankScoringConfig.calorieCredit(strategy, zone.start, 2000.0), 1e-9)
            assertEquals(1.0, RankScoringConfig.calorieCredit(strategy, zone.endInclusive, 2000.0), 1e-9)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Food score
    // ---------------------------------------------------------------------------------------

    @Test
    fun extraProteinIsNotPunishedButTooLittleIs() {
        assertEquals(0.0, RankScoringConfig.proteinScore(50.0, 100.0), 0.0)
        assertEquals(50.0, RankScoringConfig.proteinScore(75.0, 100.0), 1e-9)
        assertEquals(100.0, RankScoringConfig.proteinScore(100.0, 100.0), 0.0)
        assertEquals(100.0, RankScoringConfig.proteinScore(150.0, 100.0), 0.0)
        assertEquals(80.0, RankScoringConfig.proteinScore(250.0, 100.0), 0.0)
        // A 0% protein goal cannot be judged, and must not divide by zero
        assertEquals(RankScoringConfig.UNKNOWN_SCORE, RankScoringConfig.proteinScore(80.0, 0.0), 0.0)
    }

    @Test
    fun carbAndFatBalanceAllowsTwentyPercent() {
        assertEquals(100.0, RankScoringConfig.balanceScore(240.0, 200.0, 36.0, 44.0), 1e-9)
        assertEquals(0.0, RankScoringConfig.balanceScore(300.0, 200.0, 0.0, 44.0), 1e-9)
        assertEquals(RankScoringConfig.UNKNOWN_SCORE, RankScoringConfig.balanceScore(10.0, 0.0, 10.0, 0.0), 0.0)
    }

    @Test
    fun goodFoodScoresFullQualityAndJunkLittle() {
        assertEquals(100.0, RankScoringConfig.qualityScore(nutrients(1600.0, Diet.GOOD)), 1e-9)
        val junk = RankScoringConfig.qualityScore(nutrients(1600.0, Diet.JUNK))
        assertTrue("junk $junk", junk < 30.0)
        val typical = RankScoringConfig.qualityScore(nutrients(1600.0, Diet.TYPICAL))
        assertTrue("typical $typical", typical > junk && typical < 80.0)
    }

    @Test
    fun foodsThatDoNotStateANutrientCountAsUnknown() {
        val unknown = RankDayNutrients(1600.0, 100.0, 200.0, 44.0)
        assertEquals(RankScoringConfig.UNKNOWN_SCORE, RankScoringConfig.qualityScore(unknown), 1e-9)

        // Fiber stated for half the calories, at full marks: half known (100), half unknown (50)
        val halfFiber = unknown.copy(fiber = KnownAmount(14.0, 800.0))
        val expected = (100.0 * 0.5 + 50.0 * 0.5) * RankScoringConfig.FIBER_SHARE + 50.0 * (1 - RankScoringConfig.FIBER_SHARE)
        assertEquals(expected, RankScoringConfig.qualityScore(halfFiber), 1e-9)
    }

    // Only 0-kcal foods (salt, black coffee) state it: nothing to divide by, so unknown, never NaN
    @Test
    fun aNutrientStatedOnlyByZeroCalorieFoodsIsUnknown() {
        val day = RankDayNutrients(1600.0, 100.0, 200.0, 44.0, sodium = KnownAmount(1950.0, 0.0))
        assertEquals(RankScoringConfig.UNKNOWN_SCORE, RankScoringConfig.qualityScore(day), 1e-9)
        assertEquals(RankScoringConfig.UNKNOWN_SCORE, RankScoringConfig.qualityScore(RankDayNutrients(0.0, 0.0, 0.0, 0.0)), 0.0)
    }

    // ---------------------------------------------------------------------------------------
    // RR
    // ---------------------------------------------------------------------------------------

    @Test
    fun higherRatingsExpectBetterDays() {
        assertEquals(40.0, RankScoringConfig.expectedScore(0), 0.0)
        assertEquals(72.0, RankScoringConfig.expectedScore(1200), 0.0)
        assertEquals(94.0, RankScoringConfig.expectedScore(2399), 0.0)
        assertEquals(46.0, RankScoringConfig.expectedScore(180), 1e-9)
        for (rating in 1..2399) {
            assertTrue(RankScoringConfig.expectedScore(rating) >= RankScoringConfig.expectedScore(rating - 1))
        }
    }

    @Test
    fun aDayMovesBetweenMinus15AndPlus20() {
        assertEquals(20, RankScoringConfig.rrForDay(100.0, 0))
        assertEquals(-15, RankScoringConfig.rrForDay(0.0, 2399))
        assertEquals(0, RankScoringConfig.rrForDay(72.0, 1200))
        assertEquals(8, RankScoringConfig.rrForDay(82.0, 1200))
        // A missed day costs more than the worst logged day
        assertTrue(RankScoringConfig.MISSED_DAY_RR < -RankScoringConfig.MAX_RR_LOSS_PER_DAY)
    }

    @Test
    fun theTopTwoRanksNeedACleanWeek() {
        assertEquals(1799, RankScoringConfig.applyDelta(1790, 20, hasRecentCleanWeek = false))
        assertEquals(1810, RankScoringConfig.applyDelta(1790, 20, hasRecentCleanWeek = true))
        assertEquals(2099, RankScoringConfig.applyDelta(2090, 20, hasRecentCleanWeek = false))
        assertEquals(1500, RankScoringConfig.applyDelta(1490, 10, hasRecentCleanWeek = false))
        assertEquals(2399, RankScoringConfig.applyDelta(2390, 20, hasRecentCleanWeek = true))
        assertEquals(0, RankScoringConfig.applyDelta(10, -20, hasRecentCleanWeek = false))
    }

    // ---------------------------------------------------------------------------------------
    // The replay
    // ---------------------------------------------------------------------------------------

    // The owner's example through the replay: every day at maintenance while cutting loses RR
    @Test
    fun cuttingAtMaintenanceLosesRREveryDay() = runBlocking {
        val good = days(60) { _ -> entry(1600.0, Diet.GOOD) }
        val atMaintenance = (19 downTo 1).map { daysAgo -> entry(2000.0, Diet.GOOD, today.minusDays(daysAgo.toLong()), protein = 1.2) }
        val profile = rank(good.filter { it.date.isBefore(today.minusDays(19)) } + atMaintenance).getRankProfile()

        val maintenanceDays = profile.recentHistory.filter { !it.isInProgress && it.date.isAfter(today.minusDays(20)) }
        assertEquals(19, maintenanceDays.size)
        assertTrue(maintenanceDays.joinToString { "${it.date}=${it.rrDelta}" }, maintenanceDays.all { it.rrDelta == -15 && it.calorieScore == 0.0 })
    }

    @Test
    fun goodFoodClimbsFasterThanJunkAtTheSameCalories() = runBlocking {
        val good = rank(days(60) { _ -> entry(1600.0, Diet.GOOD) }).getRankProfile()
        val junk = rank(days(60) { _ -> entry(1600.0, Diet.JUNK, protein = 0.6) }).getRankProfile()

        assertTrue("${good.currentRating} vs ${junk.currentRating}", good.currentRating > junk.currentRating + 400)
        assertTrue(good.lifestyleScore.qualityScore > junk.lifestyleScore.qualityScore)
    }

    @Test
    fun aMissedDayIsMinus20() = runBlocking {
        val logged = days(30) { _ -> entry(1600.0, Diet.GOOD) }.filter { it.date != today.minusDays(5) }
        val gap = rank(logged).getRankProfile().recentHistory.single { it.date == today.minusDays(5) }

        assertFalse(gap.isLogged)
        assertEquals(-20, gap.rrDelta)
        assertEquals("Missed day", historyReasonLine(gap))
    }

    // A day before the first saved goal would be scored against a guessed goal
    @Test
    fun daysBeforeTheFirstSavedGoalAreLeftOut() = runBlocking {
        val logged = days(60) { _ -> entry(1600.0, Diet.GOOD) }
        val start = today.minusDays(20)
        val profile = rank(logged, goalsFrom = start).getRankProfile()

        assertEquals(start, profile.recentHistory.last().date)
        assertEquals(20, profile.loggedDaysCount)
        assertEquals(start, profile.rankCountsFrom)
        // The same as a diary that began that day
        val fresh = rank(logged.filter { !it.date.isBefore(start) }).getRankProfile()
        assertEquals(fresh.currentRating, profile.currentRating)
        assertEquals(fresh.lifestyleScore, profile.lifestyleScore)
        assertNull(fresh.rankCountsFrom)
    }

    @Test
    fun aGoalSavedBeforeTheDiaryBeganLeavesNothingOut() = runBlocking {
        val profile = rank(days(30) { _ -> entry(1600.0, Diet.GOOD) }, goalsFrom = today.minusDays(90)).getRankProfile()

        // Every day since the first log, today included, and no note under the list
        assertEquals(31, profile.recentHistory.size)
        assertNull(profile.rankCountsFrom)
    }

    @Test
    fun theScoreCardAveragesTheDaysThatEnded() = runBlocking {
        val logged = days(30) { _ -> entry(1600.0, Diet.GOOD) }.filter { it.date != today.minusDays(3) }
        val card = rank(logged).getRankProfile().lifestyleScore

        assertEquals(29, card.loggedDays)
        assertEquals(30, card.eligibleDays)
        assertEquals(100.0, card.calorieScore, 1e-9)
        assertEquals(100.0, card.qualityScore, 1e-9)
        // The headline counts the missed day as 0
        assertEquals(100.0 * 29 / 30, card.overallScore, 1e-9)
    }

    // ---------------------------------------------------------------------------------------
    // Entries from before the diary kept secondary nutrients
    // ---------------------------------------------------------------------------------------

    @Test
    fun anOldBuiltInEntryBorrowsItsFoodsNutrients() = runBlocking {
        // Built-in oats: 389 kcal, 10.6 g fiber, 1 g sugar, 1.2 g saturated fat, 2 mg sodium per 100 g
        val oats = Food(id = OATS_ID, uuid = "oats", name = "Oats", nutrition = Nutrition(389.0, 16.9, 66.3, 6.9, fiberGrams = 10.6, sugarGrams = 1.0, saturatedFatGrams = 1.2, sodiumMg = 2.0))
        val old = LoggedFoodNutrients(today.minusDays(1), OATS_ID, "Oats", 1600.0, 100.0, 200.0, 44.0)

        val withLookup = rank(listOf(old), FoodLookup(oats)).getRankProfile().recentHistory.single { it.date == today.minusDays(1) }
        val withoutLookup = rank(listOf(old)).getRankProfile().recentHistory.single { it.date == today.minusDays(1) }

        // 1,600 kcal of oats: 43.6 g fiber, 4.9 g saturated fat, 8 mg sodium, 4.1 g sugar, all full marks
        assertEquals(100.0, withLookup.qualityScore, 1e-9)
        assertEquals(RankScoringConfig.UNKNOWN_SCORE, withoutLookup.qualityScore, 1e-9)
    }

    // Ids in the custom food and recipe ranges are the user's own foods, never the catalog's
    @Test
    fun customFoodsAndRecipesAreNotLookedUp() = runBlocking {
        for (id in listOf(100_000_007L, 200_000_003L)) {
            val custom = Food(id = id, uuid = "c$id", name = "Oats", nutrition = Nutrition(389.0, 16.9, 66.3, 6.9, fiberGrams = 10.6, sugarGrams = 1.0, saturatedFatGrams = 1.2, sodiumMg = 2.0))
            val entry = LoggedFoodNutrients(today.minusDays(1), id, "Oats", 1600.0, 100.0, 200.0, 44.0)
            val day = rank(listOf(entry), FoodLookup(custom)).getRankProfile().recentHistory.single { it.date == today.minusDays(1) }
            assertEquals(RankScoringConfig.UNKNOWN_SCORE, day.qualityScore, 1e-9)
        }
    }

    // A lookup that fails is not remembered, so the next replay can still borrow the values
    @Test
    fun aFailedLookupIsTriedAgain() = runBlocking {
        val oats = Food(id = OATS_ID, uuid = "oats", name = "Oats", nutrition = Nutrition(389.0, 16.9, 66.3, 6.9, fiberGrams = 10.6, sugarGrams = 1.0, saturatedFatGrams = 1.2, sodiumMg = 2.0))
        val lookup = FoodLookup(oats, failuresFirst = 1)
        val repo = rank(listOf(LoggedFoodNutrients(today.minusDays(1), OATS_ID, "Oats", 1600.0, 100.0, 200.0, 44.0)), lookup)

        assertEquals(RankScoringConfig.UNKNOWN_SCORE, repo.getRankProfile().recentHistory.first { it.isLogged }.qualityScore, 1e-9)
        assertEquals(100.0, repo.getRankProfile().recentHistory.first { it.isLogged }.qualityScore, 1e-9)
        assertEquals(2, lookup.lookups)
        // Found once, then remembered
        repo.getRankProfile()
        assertEquals(2, lookup.lookups)
    }

    @Test
    fun theLookupSkipsADifferentFoodAndKeepsARealZero() = runBlocking {
        val oats = Food(id = OATS_ID, uuid = "oats", name = "Oats", nutrition = Nutrition(389.0, 16.9, 66.3, 6.9, fiberGrams = 10.6, sugarGrams = 1.0, saturatedFatGrams = 1.2, sodiumMg = 2.0))
        // An id from an older catalog that now points at oats
        val renamed = LoggedFoodNutrients(today.minusDays(2), OATS_ID, "White bread", 1600.0, 100.0, 200.0, 44.0)
        // Logged with a real 0 g fiber
        val zeroFiber = LoggedFoodNutrients(today.minusDays(1), OATS_ID, "Oats", 1600.0, 100.0, 200.0, 44.0, fiberGrams = 0.0)

        val history = rank(listOf(renamed, zeroFiber), FoodLookup(oats)).getRankProfile().recentHistory
        assertEquals(RankScoringConfig.UNKNOWN_SCORE, history.single { it.date == today.minusDays(2) }.qualityScore, 1e-9)
        // Fiber stays 0 (no marks, 40% of quality); the rest comes from the catalog at full marks
        assertEquals(100.0 * (1 - RankScoringConfig.FIBER_SHARE), history.single { it.date == today.minusDays(1) }.qualityScore, 1e-9)
    }

    // ---------------------------------------------------------------------------------------
    // Eating patterns over a year (the owner's simulation table)
    // ---------------------------------------------------------------------------------------

    @Test
    fun eatingPatternsLandWhereTheyShould() = runBlocking {
        suspend fun rating(entries: List<LoggedFoodNutrients>, goal: Goal = cutting) = rank(entries, goal = goal).getRankProfile().currentRating

        val perfect = rating(days(180) { _ -> entry(1600.0, Diet.GOOD) })
        assertTrue("perfect cutter $perfect", perfect >= 2100)

        val typical = rating(days(360) { _ -> entry(1600.0, Diet.TYPICAL) })
        assertTrue("typical food $typical", typical in 1200 until 1800)

        val junk = rating(days(360) { _ -> entry(1600.0, Diet.JUNK, protein = 0.6) })
        assertTrue("junk food $junk", junk < 300)

        val atMaintenance = rating(days(360) { _ -> entry(2000.0, Diet.GOOD, protein = 1.2) })
        assertEquals(0, atMaintenance)

        // Perfect weekdays, 2,800 kcal of junk at weekends: never into the top two ranks
        val binger = rating(days(360) { date ->
            if (date.dayOfWeek.value >= 6) entry(2800.0, Diet.JUNK, date) else entry(1600.0, Diet.GOOD, date)
        })
        assertTrue("weekend binger $binger", binger in 1200 until 1800)

        val bulking = Goal(2875.0, 50.0, 25.0, 25.0, FitnessGoal.BULKING, 2500.0)
        assertEquals(0, rating(days(360) { _ -> entry(2500.0, Diet.GOOD, goal = bulking) }, bulking))
        assertTrue(rating(days(180) { _ -> entry(2875.0, Diet.GOOD, goal = bulking) }, bulking) >= 2100)
    }

    // One off day a week, missed or eaten outside the zone, still makes clean weeks
    @Test
    fun oneOffDayAWeekStillReachesTheTopRanks() = runBlocking {
        val missedSunday = rank(days(360) { date -> entry(1600.0, Diet.GOOD, date) }.filter { it.date.dayOfWeek.value != 7 }).getRankProfile()
        assertTrue("one missed day a week ${missedSunday.currentRating}", missedSunday.currentRating >= 1800)

        val bingeSunday = rank(days(360) { date ->
            if (date.dayOfWeek.value == 7) entry(2800.0, Diet.JUNK, date) else entry(1600.0, Diet.GOOD, date)
        }).getRankProfile()
        assertTrue("one binge day a week ${bingeSunday.currentRating}", bingeSunday.currentRating >= 1800)
        // Logging the bad day is never worse than skipping it
        assertTrue(bingeSunday.currentRating >= missedSunday.currentRating)
    }

    // Two off days a week: held below Fart Monster, and the screen is told why
    @Test
    fun aHeldRatingSaysItNeedsACleanWeek() = runBlocking {
        val profile = rank(days(360) { date ->
            if (date.dayOfWeek.value >= 6) entry(2800.0, Diet.JUNK, date) else entry(1600.0, Diet.GOOD, date)
        }).getRankProfile()

        assertTrue(profile.currentRating < 1800)
        assertEquals(1800, profile.cleanWeekGate)
        assertTrue(profile.recentHistory.any { it.heldByCleanWeek })
        assertEquals(
            "To reach Fart Monster: a clean week, 7 days in a row in your calorie zone (one day off or missed is allowed)",
            com.macrobase.app.feature.rank.cleanWeekHint(1800)
        )
    }

    // ---------------------------------------------------------------------------------------
    // The "rank recalculated" notice
    // ---------------------------------------------------------------------------------------

    @Test
    fun theNoticeShowsOnceForSomeoneWithHistory() = runBlocking {
        val prefs = NoticePrefs(seen = 0)
        val repo = rank(days(10) { _ -> entry(1600.0, Diet.GOOD) }, preferences = prefs)

        assertTrue(repo.getRankProfile().showRulesNotice)
        repo.dismissRulesNotice()
        assertEquals(RankScoringConfig.RULES_VERSION, prefs.seen.value)
        assertFalse(repo.getRankProfile().showRulesNotice)
    }

    @Test
    fun theNoticeIsNotShownWithoutAnEndedLoggedDay() = runBlocking {
        val repo = rank(listOf(entry(1600.0, Diet.GOOD, today)), preferences = NoticePrefs(seen = 0))
        assertFalse(repo.getRankProfile().showRulesNotice)
    }

    // ---------------------------------------------------------------------------------------
    // Screen text
    // ---------------------------------------------------------------------------------------

    @Test
    fun preferencesShowTheZoneAndWarnAboutATargetOutsideIt() {
        assertEquals(
            "Rank calorie zone: 1,500 \u2013 1,800 kcal (10\u201325% below maintenance). Eating within 5% of maintenance, or above it, loses RR.",
            strategyRankHint(FitnessGoal.CUTTING, 2000.0)
        )
        assertEquals(
            "Rank calorie zone: 10\u201320% above maintenance. Eating within 5% of maintenance, or below it, loses RR.",
            strategyRankHint(FitnessGoal.BULKING, null)
        )
        assertNull(calorieTargetWarning(FitnessGoal.CUTTING, 2000.0, 1600.0))
        assertEquals(
            "Your daily calorie target (2,000 kcal) is not in this zone: days eaten to it lose RR.",
            calorieTargetWarning(FitnessGoal.CUTTING, 2000.0, 2000.0)
        )
        assertEquals(
            "Your daily calorie target (1,850 kcal) is at the edge of this zone: days eaten to it earn less RR.",
            calorieTargetWarning(FitnessGoal.CUTTING, 2000.0, 1850.0)
        )
        // The zone's edges are shown in whole kcal: typing a shown edge is not "at the edge"
        for (maintenance in 1201..4000) {
            val zone = RankScoringConfig.calorieZone(FitnessGoal.CUTTING, maintenance.toDouble())
            for (edge in listOf(zone.start, zone.endInclusive)) {
                assertNull("$maintenance $edge", calorieTargetWarning(FitnessGoal.CUTTING, maintenance.toDouble(), kotlin.math.round(edge)))
            }
        }
    }

    // A new strategy starts tomorrow, but a new calorie target applies today
    @Test
    fun switchingStrategyWarnsThatTodayKeepsTheOldOne() {
        assertEquals(
            "Today is still rated as Maintaining (zone 1,900 \u2013 2,100 kcal), so eating 1,600 kcal today loses RR. The change starts tomorrow.",
            todayStrategyWarning(FitnessGoal.MAINTAINING, 2000.0, FitnessGoal.CUTTING, 2000.0, 1600.0)
        )
        // No change of strategy or maintenance, or a target that suits today too: nothing to say
        assertNull(todayStrategyWarning(FitnessGoal.CUTTING, 2000.0, FitnessGoal.CUTTING, 2000.0, 1600.0))
        assertNull(todayStrategyWarning(FitnessGoal.MAINTAINING, 2000.0, FitnessGoal.CUTTING, 2000.0, 2050.0))
    }

    /** Yesterday at Gym Cat I, which expects a day of about 72: a day of 84 gains RR. */
    private val gainingDay = RankHistoryEntry(
        date = today.minusDays(1), rankTier = RankTier(Rank.GYM_CAT, Tier.I), tierRR = 10, overallRating = 1210, rrDelta = 8,
        overallScore = 84.0, ratingBefore = 1202, calorieScore = 100.0, consistencyScore = 100.0,
        proteinScore = 100.0, qualityScore = 70.0, balanceScore = 80.0
    )

    @Test
    fun theHistoryLineSaysWhyADayLostRR_andNothingWhenItGained() {
        assertNull(historyReasonLine(gainingDay))
        assertEquals("Missed day", historyReasonLine(gainingDay.copy(isLogged = false, overallScore = 0.0, calorieScore = 0.0, rrDelta = -20)))
        assertEquals("Calories outside your zone", historyReasonLine(gainingDay.copy(calorieScore = 0.0, overallScore = 0.0, rrDelta = -15)))
        // Otherwise the part that cost the most points
        assertEquals("Protein off target", historyReasonLine(gainingDay.copy(proteinScore = 20.0, overallScore = 50.0, rrDelta = -15)))
        assertEquals("Food quality low", historyReasonLine(gainingDay.copy(qualityScore = 30.0, overallScore = 60.0, rrDelta = -10)))
        assertEquals("Calories off target", historyReasonLine(gainingDay.copy(calorieScore = 50.0, overallScore = 45.0, rrDelta = -15)))
        assertEquals(
            "Carbs and fat off target",
            historyReasonLine(gainingDay.copy(qualityScore = 100.0, balanceScore = 0.0, overallScore = 60.0, rrDelta = -10))
        )
        // A gain held below a rank that needs a clean week says so
        assertEquals("Next rank needs a clean week", historyReasonLine(gainingDay.copy(heldByCleanWeek = true, rrDelta = 0)))
    }

    @Test
    fun aLosingDayAtTheBottomStillSaysWhy() {
        // The rank cannot fall below 0, so the change shows 0 RR, but the day was still a loss
        val bottom = gainingDay.copy(
            rankTier = RankTier(Rank.FAT_BEAR, Tier.I), tierRR = 0, overallRating = 0, ratingBefore = 0, rrDelta = 0,
            calorieScore = 0.0, overallScore = 0.0
        )
        assertEquals("Calories outside your zone", historyReasonLine(bottom))
        assertEquals("Missed day", historyReasonLine(bottom.copy(isLogged = false)))
    }

    @Test
    fun todayGetsAReasonOnlyOnceItsPreviewIsALoss() {
        val inProgress = gainingDay.copy(date = today, isInProgress = true, calorieScore = 0.0, overallScore = 0.0, rrDelta = -15)
        // Below the zone the day is still being eaten, and nothing logged has nothing to explain
        assertNull(historyReasonLine(inProgress, stillEating = true))
        assertNull(historyReasonLine(inProgress.copy(isLogged = false)))
        assertEquals("Calories outside your zone", historyReasonLine(inProgress))
        // The preview decides: no loss shown, no reason, even when the day itself is weak
        assertNull(historyReasonLine(inProgress.copy(calorieScore = 100.0, overallScore = 84.0, rrDelta = 8)))
        assertNull(historyReasonLine(inProgress.copy(rrDelta = 0)))
        assertNull(historyReasonLine(inProgress.copy(rrDelta = 100)))
    }

    @Test
    fun aHistoryPageIsNamedByItsOldestAndNewestDay() {
        val format = DateTimeFormatter.ofPattern("MMM dd")
        val page = (0L until 30L).map { gainingDay.copy(date = today.minusDays(it)) }
        assertEquals("${today.minusDays(29).format(format)} \u2013 ${today.format(format)}", historyPageRange(page))
        assertEquals(today.format(format), historyPageRange(page.take(1)))
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /** Food profiles: fiber g per 1,000 kcal, saturated fat share of kcal, sodium mg a day, sugar share of kcal. */
    private enum class Diet(val fiberPer1000: Double, val saturatedFatShare: Double, val sodiumMg: Double, val sugarShare: Double) {
        GOOD(17.0, 0.08, 1800.0, 0.12),
        TYPICAL(8.0, 0.12, 3400.0, 0.20),
        JUNK(4.0, 0.15, 4200.0, 0.25)
    }

    private fun nutrients(kcal: Double, diet: Diet) = RankDayNutrients(
        calories = kcal, proteinGrams = 100.0, carbsGrams = 200.0, fatGrams = 44.0,
        fiber = KnownAmount(kcal / 1000.0 * diet.fiberPer1000, kcal),
        sugar = KnownAmount(kcal * diet.sugarShare / 4.0, kcal),
        saturatedFat = KnownAmount(kcal * diet.saturatedFatShare / 9.0, kcal),
        sodium = KnownAmount(diet.sodiumMg, kcal)
    )

    /** One day's food as a single entry: macros scaled from [goal]'s targets by the calories eaten. */
    private fun entry(
        kcal: Double,
        diet: Diet,
        date: LocalDate = today.minusDays(1),
        protein: Double = 1.1,
        goal: Goal = cutting
    ): LoggedFoodNutrients {
        val scale = kcal / goal.dailyCalorieGoal
        val junk = diet == Diet.JUNK
        return LoggedFoodNutrients(
            date = date,
            foodId = 0L,
            foodName = "Day",
            calories = kcal,
            proteinGrams = goal.proteinGrams * protein,
            carbsGrams = goal.carbGrams * scale * (if (junk) 1.1 else 1.0),
            fatGrams = goal.fatGrams * scale * (if (junk) 1.2 else 1.0),
            fiberGrams = kcal / 1000.0 * diet.fiberPer1000,
            sugarGrams = kcal * diet.sugarShare / 4.0,
            saturatedFatGrams = kcal * diet.saturatedFatShare / 9.0,
            sodiumMg = diet.sodiumMg
        )
    }

    /** One entry for each of the [count] days ending yesterday; under 365 they stay in the first season. */
    private fun days(count: Int, eaten: (LocalDate) -> LoggedFoodNutrients): List<LoggedFoodNutrients> =
        (count downTo 1).map { daysAgo -> today.minusDays(daysAgo.toLong()) }.map { date -> eaten(date).copy(date = date) }

    private fun rank(
        entries: List<LoggedFoodNutrients>,
        foods: FoodRepository? = null,
        preferences: PreferencesRepository? = null,
        goal: Goal = cutting,
        goalsFrom: LocalDate? = null
    ) = RankRepositoryImpl(
        goalsRepository = FixedGoal(goal, goalsFrom),
        diaryRepository = Entries(entries),
        foodRepository = foods,
        preferencesRepository = preferences,
        clock = MutableTestClock(today)
    )

    private class FixedGoal(private val goal: Goal, private val savedFrom: LocalDate? = null) : GoalsRepository {
        override suspend fun getGoals() = goal
        override fun observeGoals(): Flow<Goal> = flowOf(goal)
        override suspend fun updateGoals(goals: Goal) {}
        override suspend fun getGoalHistoryStart() = savedFrom
    }

    private class Entries(private val entries: List<LoggedFoodNutrients>) : DiaryRepository {
        override suspend fun getDiaryForDate(date: LocalDate) = DailyNutritionSummary(date = date)
        override fun observeDiaryForDate(date: LocalDate) = flowOf(DailyNutritionSummary(date = date))
        override suspend fun getEntryById(entryId: Long): DiaryEntry? = null
        override suspend fun addEntry(entry: DiaryEntry): Long = 1L
        override suspend fun addEntries(entries: List<DiaryEntry>) {}
        override suspend fun updateEntry(entry: DiaryEntry) {}
        override suspend fun deleteEntry(entryId: Long) {}
        override suspend fun getMonthlyAdherence(year: Int, month: Int) = emptyList<CalendarDaySummary>()
        override fun observeMonthlyAdherence(year: Int, month: Int) = flowOf(emptyList<CalendarDaySummary>())
        override fun observeNutritionAggregations(startDate: LocalDate, endDate: LocalDate) = flowOf(emptyList<DailyMacroAggregation>())
        override suspend fun getNutritionAggregations(startDate: LocalDate, endDate: LocalDate) = emptyList<DailyMacroAggregation>()
        override fun observeLoggedFoodNutrients(startDate: LocalDate, endDate: LocalDate): Flow<List<LoggedFoodNutrients>> =
            flowOf(entries.filter { !it.date.isBefore(startDate) && !it.date.isAfter(endDate) })
    }

    /** A catalog holding [food]. It counts lookups, and fails the first [failuresFirst] of them. */
    private class FoodLookup(private val food: com.macrobase.app.domain.model.Food, private var failuresFirst: Int = 0) : FoodRepository {
        var lookups = 0
        override suspend fun searchFoods(query: String, limit: Int) = emptyList<com.macrobase.app.domain.model.Food>()
        override suspend fun getFoodById(id: Long): com.macrobase.app.domain.model.Food? {
            lookups++
            if (failuresFirst > 0) {
                failuresFirst--
                throw IllegalStateException("catalog not ready")
            }
            return food.takeIf { it.id == id }
        }
        override suspend fun getFoodByBarcode(barcode: String): com.macrobase.app.domain.model.Food? = null
        override suspend fun getFoodServings(foodId: Long) = emptyList<Serving>()
        override suspend fun getRecentFoods(limit: Int) = emptyList<com.macrobase.app.domain.model.Food>()
        override suspend fun getFavoriteFoods() = emptyList<com.macrobase.app.domain.model.Food>()
        override fun observeCustomFoods(): Flow<List<com.macrobase.app.domain.model.Food>> = flowOf(emptyList())
        override suspend fun getCustomFoods() = emptyList<com.macrobase.app.domain.model.Food>()
        override suspend fun saveCustomFood(food: CustomFood): Long = 0L
        override suspend fun deleteCustomFood(id: Long) {}
    }

    private companion object {
        /** A real catalog id: built-in foods have large hash ids. */
        const val OATS_ID = 1_896_880_878_330_341L
    }

    private class NoticePrefs(seen: Int) : PreferencesRepository {
        val seen = MutableStateFlow(seen)
        override suspend fun getPreferences() = UserPreferences()
        override fun observePreferences(): Flow<UserPreferences> = flowOf(UserPreferences())
        override suspend fun updatePreferences(preferences: UserPreferences) {}
        override fun observeSeenRankRulesVersion(): Flow<Int> = seen
        override suspend fun setSeenRankRulesVersion(version: Int) {
            seen.value = version
        }
    }
}
