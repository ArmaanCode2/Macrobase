package com.macrobase.app

import com.macrobase.app.data.repository.rank.RankRepositoryImpl
import com.macrobase.app.domain.model.FitnessGoal
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.rank.BestRank
import com.macrobase.app.domain.model.rank.Rank
import com.macrobase.app.domain.model.rank.RankSeason
import com.macrobase.app.domain.model.rank.RankTier
import com.macrobase.app.domain.model.rank.Tier
import com.macrobase.app.domain.repository.DailyMacroAggregation
import com.macrobase.app.domain.repository.GoalsRepository
import com.macrobase.app.domain.repository.PreferencesRepository
import com.macrobase.app.feature.rank.bestRankLine
import com.macrobase.app.feature.rank.newSeasonNoticeText
import com.macrobase.app.feature.rank.rankResetText
import com.macrobase.app.testutil.MutableTestClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The rank starts again from Fat Bear on each anniversary of the first logged day. */
class RankSeasonTests {

    private val firstLogged = LocalDate.of(2026, 8, 29)
    private val secondSeason = LocalDate.of(2027, 8, 29)

    private val maintaining = Goal(
        dailyCalorieGoal = 2000.0, carbPercentage = 50.0, proteinPercentage = 25.0, fatPercentage = 25.0,
        fitnessGoal = FitnessGoal.MAINTAINING, maintenanceCalories = 2000.0
    )

    // ---------------------------------------------------------------------------------------
    // Season dates
    // ---------------------------------------------------------------------------------------

    @Test
    fun aSeasonRunsFromOneAnniversaryOfTheFirstLogToTheNext() {
        assertEquals(RankSeason(1, firstLogged, secondSeason), RankSeason.containing(firstLogged, firstLogged))
        assertEquals(RankSeason(1, firstLogged, secondSeason), RankSeason.containing(firstLogged, LocalDate.of(2026, 10, 14)))
        assertEquals(RankSeason(1, firstLogged, secondSeason), RankSeason.containing(firstLogged, secondSeason.minusDays(1)))
        assertEquals(RankSeason(2, secondSeason, LocalDate.of(2028, 8, 29)), RankSeason.containing(firstLogged, secondSeason))
        assertEquals(3, RankSeason.containing(firstLogged, LocalDate.of(2029, 1, 1)).number)
    }

    @Test
    fun aFirstLogOnFebruary29ResetsOnFebruary28InOtherYears() {
        val leapDay = LocalDate.of(2028, 2, 29)
        assertEquals(RankSeason(1, leapDay, LocalDate.of(2029, 2, 28)), RankSeason.containing(leapDay, LocalDate.of(2029, 2, 27)))
        assertEquals(RankSeason(2, LocalDate.of(2029, 2, 28), LocalDate.of(2030, 2, 28)), RankSeason.containing(leapDay, LocalDate.of(2029, 2, 28)))
        assertEquals(RankSeason(5, leapDay.plusYears(4), LocalDate.of(2033, 2, 28)), RankSeason.containing(leapDay, LocalDate.of(2032, 2, 29)))
    }

    // ---------------------------------------------------------------------------------------
    // The rank across a reset
    // ---------------------------------------------------------------------------------------

    @Test
    fun theFirstSeasonHasNoResetYet() = runBlocking {
        val today = LocalDate.of(2026, 10, 14)
        val profile = rank(goodDays(firstLogged, today), today).getRankProfile()

        assertEquals(RankSeason(1, firstLogged, secondSeason), profile.season)
        assertFalse(profile.showNewSeasonNotice)
        assertNull(profile.lastSeasonBest)
        assertEquals(firstLogged, profile.recentHistory.last().date)
    }

    @Test
    fun aNewSeasonCountsOnlyItsOwnDays_fromFatBear() = runBlocking {
        val today = secondSeason.plusDays(12)
        val diary = goodDays(firstLogged, today)
        val profile = rank(diary, today).getRankProfile()

        assertEquals(2, profile.season?.number)
        // The rank screen lists this season only, starting at the bottom: no drop of last year's rating
        assertEquals(secondSeason, profile.recentHistory.last().date)
        assertEquals(0, profile.recentHistory.last().ratingBefore)
        assertEquals(12, profile.loggedDaysCount)
        // The same as a diary that began on the reset day
        val fresh = rank(diary.filter { !it.date.isBefore(secondSeason) }, today).getRankProfile()
        assertEquals(fresh.currentRating, profile.currentRating)
        assertEquals(fresh.recentHistory, profile.recentHistory)
        // Last season climbed for a year, so it went higher than 12 days can
        val lastBest = profile.lastSeasonBest
        assertNotNull(lastBest)
        assertTrue("${lastBest!!.rating} vs ${profile.currentRating}", lastBest.rating > profile.currentRating)
        assertTrue(lastBest.date.isBefore(secondSeason))
    }

    // The score card judges the food of the last 30 days, not the rank, so a reset leaves it whole
    @Test
    fun theScoreCardKeepsTheLast30DaysAcrossAReset() = runBlocking {
        val today = secondSeason.plusDays(12)
        val card = rank(goodDays(firstLogged, today), today).getRankProfile().lifestyleScore

        assertEquals(30, card.eligibleDays)
        assertEquals(30, card.loggedDays)
    }

    @Test
    fun onTheResetDayTheRankStartsAgainAndSaysSo() = runBlocking {
        val prefs = SeenSeason()
        val repo = rank(goodDays(firstLogged, secondSeason), secondSeason, prefs)

        val profile = repo.getRankProfile()
        assertEquals(0, profile.currentRating)
        assertEquals(RankTier(Rank.FAT_BEAR, Tier.I), profile.rankTier)
        assertTrue(profile.isProvisional)
        assertTrue(profile.recentHistory.isEmpty())
        assertNull(profile.bestThisSeason)
        assertTrue(profile.showNewSeasonNotice)
        assertNotNull(profile.lastSeasonBest)

        // Seen once, gone until the next reset
        repo.dismissSeasonNotice(secondSeason)
        assertFalse(repo.getRankProfile().showNewSeasonNotice)
        val nextYear = secondSeason.plusYears(1)
        assertTrue(rank(goodDays(firstLogged, nextYear), nextYear, prefs).getRankProfile().showNewSeasonNotice)
    }

    @Test
    fun theBestRankIsTheHighestSoFar_evenAfterAFall() = runBlocking {
        val today = firstLogged.plusDays(30)
        // 21 good days of +20 RR, then 10 days without logging: 400 RR (Average II) on day 20, 420 on day 21
        val peakDay = firstLogged.plusDays(20)
        val profile = rank(goodDays(firstLogged, peakDay.plusDays(1)), today).getRankProfile()

        val best = profile.bestThisSeason
        assertNotNull(best)
        val peak = profile.recentHistory.first { it.date == peakDay }
        assertEquals(peak.overallRating, best!!.rating)
        assertEquals(peak.rankTier, best.rankTier)
        assertTrue("${best.rating} vs ${profile.currentRating}", best.rating > profile.currentRating)
        // Dated by the first day that rank and tier were reached, not the day of the most RR
        val firstInTier = profile.recentHistory.reversed().first { it.rankTier == best.rankTier }
        assertEquals(firstInTier.date, best.date)
        assertTrue(best.date.isBefore(peakDay))
    }

    @Test
    fun theSeasonStartsAtItsFirstLog_whenTheResetDayIsNotLogged() = runBlocking {
        val today = secondSeason.plusDays(10)
        val diary = goodDays(firstLogged, secondSeason) + goodDays(secondSeason.plusDays(3), today)
        val profile = rank(diary, today).getRankProfile()

        assertEquals(secondSeason.plusDays(3), profile.recentHistory.last().date)
        assertEquals(7, profile.loggedDaysCount)
        assertFalse(profile.isProvisional)
    }

    @Test
    fun aYearWithNothingLoggedHasNoBestToName() = runBlocking {
        val thirdSeason = secondSeason.plusYears(1)
        val today = thirdSeason.plusDays(5)
        val diary = goodDays(firstLogged, firstLogged.plusDays(30)) + goodDays(thirdSeason, today)
        val profile = rank(diary, today).getRankProfile()

        assertEquals(3, profile.season?.number)
        assertNull(profile.lastSeasonBest)
        assertTrue(profile.showNewSeasonNotice)
    }

    // A goal first saved inside this season still leaves out the days before it
    @Test
    fun theRankCountsFromTheLaterOfTheSeasonAndTheFirstSavedGoal() = runBlocking {
        val today = secondSeason.plusDays(20)
        val diary = goodDays(firstLogged, today)

        val savedLastSeason = rank(diary, today, goalsFrom = firstLogged.plusDays(100)).getRankProfile()
        assertEquals(secondSeason, savedLastSeason.recentHistory.last().date)
        assertNull(savedLastSeason.rankCountsFrom)

        val savedThisSeason = rank(diary, today, goalsFrom = secondSeason.plusDays(5)).getRankProfile()
        assertEquals(secondSeason.plusDays(5), savedThisSeason.recentHistory.last().date)
        assertEquals(secondSeason.plusDays(5), savedThisSeason.rankCountsFrom)
    }

    // Someone who first logs today has no rank to recalculate yet
    @Test
    fun theRulesNoticeWaitsForAnEndedLoggedDay() = runBlocking {
        val today = LocalDate.of(2026, 10, 14)
        val notSeen = object : PreferencesRepository by P2PhaseFourRankReplayTests.Prefs() {
            override fun observeSeenRankRulesVersion() = flowOf(0)
        }
        assertFalse(rank(goodDays(today, today.plusDays(1)), today, notSeen).getRankProfile().showRulesNotice)
        assertTrue(rank(goodDays(today.minusDays(1), today), today, notSeen).getRankProfile().showRulesNotice)
    }

    // The reset date follows the diary: food logged on a wrong old date moves it until it is deleted
    @Test
    fun anEntryOnAnOlderDateMovesTheResetUntilItIsDeleted() = runBlocking {
        val today = LocalDate.of(2026, 10, 14)
        val diary = goodDays(firstLogged, today)
        val yearTooEarly = goodDays(LocalDate.of(2025, 10, 13), LocalDate.of(2025, 10, 14))

        val moved = rank(yearTooEarly + diary, today).getRankProfile()
        assertEquals(LocalDate.of(2026, 10, 13), moved.season?.start)

        val deleted = rank(diary, today).getRankProfile()
        assertEquals(RankSeason(1, firstLogged, secondSeason), deleted.season)
        assertTrue(deleted.currentRating > moved.currentRating)
    }

    // ---------------------------------------------------------------------------------------
    // Text
    // ---------------------------------------------------------------------------------------

    @Test
    fun theLadderSaysWhenTheRankResets() {
        assertEquals(
            "Resets on Aug 29, 2027 \u2022 319 days left. Your rank starts again from Fat Bear every year on the date you first logged food.",
            rankResetText(secondSeason, LocalDate.of(2026, 10, 14))
        )
        assertTrue(rankResetText(secondSeason, secondSeason.minusDays(1)).contains("\u2022 1 day left."))
        // A profile from yesterday still on screen on the reset day
        assertTrue(rankResetText(secondSeason, secondSeason).startsWith("Resets today."))
        assertEquals(
            "Your rank starts again from Fat Bear one year after the first day you log food, and every year after that.",
            rankResetText(null, LocalDate.of(2026, 10, 14))
        )
    }

    @Test
    fun theBestRankAndNewSeasonLinesNameTheRankAndDay() {
        val best = BestRank(RankTier(Rank.GYM_CAT, Tier.II), 1320, LocalDate.of(2027, 9, 3))
        assertEquals("Best this season: Gym Cat II \u2022 Sep 3", bestRankLine(best))
        val season = RankSeason(2, secondSeason, LocalDate.of(2028, 8, 29))
        assertEquals(
            "A new season started on Aug 29, so your rank starts again from Fat Bear. Last season's best: Gym Cat II.",
            newSeasonNoticeText(season, best)
        )
        assertEquals(
            "A new season started on Aug 29, so your rank starts again from Fat Bear.",
            newSeasonNoticeText(season, null)
        )
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /** A day eaten exactly to plan on every day from [from] to the day before [until]. */
    private fun goodDays(from: LocalDate, until: LocalDate): List<DailyMacroAggregation> =
        generateSequence(from) { it.plusDays(1) }.takeWhile { it.isBefore(until) }.map { date ->
            DailyMacroAggregation(
                date = date,
                calories = maintaining.dailyCalorieGoal,
                proteinGrams = maintaining.proteinGrams,
                carbsGrams = maintaining.carbGrams,
                fatGrams = maintaining.fatGrams
            )
        }.toList()

    private fun rank(
        diary: List<DailyMacroAggregation>,
        today: LocalDate,
        prefs: PreferencesRepository = SeenSeason(),
        goalsFrom: LocalDate? = null
    ) =
        RankRepositoryImpl(
            goalsRepository = Goals(maintaining, goalsFrom),
            diaryRepository = P2PhaseFourRankReplayTests.Diary(diary),
            preferencesRepository = prefs,
            clock = MutableTestClock(today)
        )

    private class Goals(private val goal: Goal, private val savedFrom: LocalDate? = null) : GoalsRepository {
        override suspend fun getGoals() = goal
        override fun observeGoals(): Flow<Goal> = flowOf(goal)
        override suspend fun updateGoals(goals: Goal) {}
        override suspend fun getGoalHistoryStart() = savedFrom
    }

    /** Remembers the season whose new-season notice was dismissed; none at first. */
    private class SeenSeason : PreferencesRepository by P2PhaseFourRankReplayTests.Prefs() {
        private val seen = MutableStateFlow(Long.MIN_VALUE)
        override fun observeSeenRankSeason(): Flow<Long> = seen
        override suspend fun setSeenRankSeason(seasonStartEpochDay: Long) {
            seen.value = seasonStartEpochDay
        }
    }
}
