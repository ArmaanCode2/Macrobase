package com.macrobase.app

import com.macrobase.app.data.repository.rank.RankRepositoryImpl
import com.macrobase.app.domain.model.FitnessGoal
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.rank.BestRank
import com.macrobase.app.domain.model.rank.PastSeason
import com.macrobase.app.domain.model.rank.Rank
import com.macrobase.app.domain.model.rank.RankSeason
import com.macrobase.app.domain.model.rank.RankTier
import com.macrobase.app.domain.model.rank.RatingPoint
import com.macrobase.app.domain.model.rank.Tier
import com.macrobase.app.domain.repository.DailyMacroAggregation
import com.macrobase.app.domain.repository.GoalsRepository
import com.macrobase.app.feature.statistics.RankHistoryInterval
import com.macrobase.app.feature.statistics.pastSeasonLine
import com.macrobase.app.feature.statistics.rankAxisRange
import com.macrobase.app.feature.statistics.rankHistoryPoints
import com.macrobase.app.feature.statistics.rankHistoryState
import com.macrobase.app.feature.statistics.rankPointLabel
import com.macrobase.app.testutil.MutableTestClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The Rank History tab in Statistics: every day's rating across seasons, and each season's best. */
class RankHistoryTests {

    private val firstLogged = LocalDate.of(2026, 8, 29)
    private val secondSeason = LocalDate.of(2027, 8, 29)
    private val today = secondSeason.plusDays(12)

    private val maintaining = Goal(
        dailyCalorieGoal = 2000.0, carbPercentage = 50.0, proteinPercentage = 25.0, fatPercentage = 25.0,
        fitnessGoal = FitnessGoal.MAINTAINING, maintenanceCalories = 2000.0
    )

    @Test
    fun theHistoryHoldsEveryEndedDayOfEverySeason() = runBlocking {
        val profile = rank(goodDays(firstLogged, today), today).getRankProfile()
        val history = profile.ratingHistory

        // A year of season 1, then the 12 days of season 2 that have ended
        assertEquals(365 + 12, history.size)
        assertEquals(firstLogged, history.first().date)
        assertEquals(today.minusDays(1), history.last().date)
        assertEquals(history.sortedBy { it.date }, history)
        assertEquals(setOf(1), history.filter { it.date.isBefore(secondSeason) }.map { it.seasonNumber }.toSet())
        assertEquals(setOf(2), history.filter { !it.date.isBefore(secondSeason) }.map { it.seasonNumber }.toSet())
        // The new season starts from the bottom, and its days match the rank screen's rows
        assertTrue(history.single { it.date == secondSeason }.rating <= 20)
        assertEquals(
            profile.recentHistory.filter { !it.isInProgress }.map { it.date to it.overallRating }.reversed(),
            history.filter { it.seasonNumber == 2 }.map { it.date to it.rating }
        )
    }

    @Test
    fun eachPastSeasonKeepsItsBestRank() = runBlocking {
        val profile = rank(goodDays(firstLogged, today), today).getRankProfile()
        val past = profile.pastSeasons.single()

        assertEquals(RankSeason(1, firstLogged, secondSeason), past.season)
        val seasonOne = profile.ratingHistory.filter { it.seasonNumber == 1 }
        assertEquals(seasonOne.maxOf { it.rating }, past.best!!.rating)
        assertEquals(past.best, profile.lastSeasonBest)
    }

    @Test
    fun theChipsPickTheDaysThatEnded() = runBlocking {
        val profile = rank(goodDays(firstLogged, today), today).getRankProfile()

        val month = rankHistoryPoints(profile, RankHistoryInterval.DAYS_30, today)
        assertEquals(30, month.size)
        assertEquals(today.minusDays(30), month.first().date)
        assertEquals(today.minusDays(1), month.last().date)
        assertEquals(90, rankHistoryPoints(profile, RankHistoryInterval.DAYS_90, today).size)
        assertEquals(12, rankHistoryPoints(profile, RankHistoryInterval.SEASON, today).size)
        assertEquals(profile.ratingHistory, rankHistoryPoints(profile, RankHistoryInterval.ALL, today))
    }

    @Test
    fun untilTheFirstRankThereIsNothingToDraw() = runBlocking {
        val newcomer = rank(goodDays(today.minusDays(3), today), today).getRankProfile()
        assertFalse(rankHistoryState(newcomer, RankHistoryInterval.DAYS_30, today).hasRank)

        val ranked = rank(goodDays(firstLogged, today), today).getRankProfile()
        assertTrue(rankHistoryState(ranked, RankHistoryInterval.DAYS_30, today).hasRank)
    }

    // The first logged days of a new season are not a rank yet: no line at 0 RR for them
    @Test
    fun aNewSeasonIsNotDrawnUntilItHasARank() = runBlocking {
        val threeDaysIn = secondSeason.plusDays(3)
        val profile = rank(goodDays(firstLogged, threeDaysIn), threeDaysIn).getRankProfile()

        assertEquals(setOf(1), profile.ratingHistory.map { it.seasonNumber }.toSet())
        val season = rankHistoryState(profile, RankHistoryInterval.SEASON, threeDaysIn)
        assertTrue(season.points.isEmpty())
        assertTrue(season.seasonIsProvisional)
        // Last season's line still shows under "All"
        assertTrue(season.hasRank)
        assertEquals(365, rankHistoryState(profile, RankHistoryInterval.ALL, threeDaysIn).points.size)
    }

    // The dashed line marks the reset day, not the day logging started again
    @Test
    fun theResetLineIsOnTheDayTheSeasonStarted() = runBlocking {
        val back = secondSeason.plusDays(30)
        val now = back.plusDays(15)
        val profile = rank(goodDays(firstLogged, secondSeason) + goodDays(back, now), now).getRankProfile()
        val all = rankHistoryState(profile, RankHistoryInterval.ALL, now)

        assertEquals(listOf(secondSeason), all.resetDates)
        assertEquals(back, all.points.first { it.seasonNumber == 2 }.date)
    }

    // A year with nothing logged is not listed, and the reset notice names no best for it
    @Test
    fun aYearWithNothingLoggedIsNotAPastSeason() = runBlocking {
        val thirdSeason = secondSeason.plusYears(1)
        val now = thirdSeason.plusDays(10)
        val profile = rank(goodDays(firstLogged, firstLogged.plusDays(30)) + goodDays(thirdSeason, now), now).getRankProfile()

        assertEquals(listOf(1), profile.pastSeasons.map { it.season.number })
        assertTrue(profile.pastSeasons.single().best != null)
        assertEquals(null, profile.lastSeasonBest)
    }

    @Test
    fun theGraphCoversOnlyTheRanksTheLineReaches() {
        fun point(rating: Int) = RatingPoint(today, rating, 1)

        assertEquals(0..600, rankAxisRange(listOf(point(50), point(420))))
        assertEquals(1200..1500, rankAxisRange(listOf(point(1250), point(1290))))
        assertEquals(2100..2400, rankAxisRange(listOf(point(2399))))
        assertEquals(0..300, rankAxisRange(emptyList()))
    }

    @Test
    fun aTappedDayAndAPastSeasonReadLikeTheRankScreen() {
        assertEquals("Oct 12 \u2022 Fat Bear I \u2022 83 RR", rankPointLabel(RatingPoint(LocalDate.of(2026, 10, 12), 83, 1), 2026))
        assertEquals("Oct 13 \u2022 Fat Bear II \u2022 3 RR", rankPointLabel(RatingPoint(LocalDate.of(2026, 10, 13), 103, 1), 2026))
        // A day from another year names it, so "All" across seasons stays readable
        assertEquals("Oct 12, 2026 \u2022 Fat Bear I \u2022 83 RR", rankPointLabel(RatingPoint(LocalDate.of(2026, 10, 12), 83, 1), 2027))

        val season = RankSeason(1, firstLogged, secondSeason)
        val best = BestRank(RankTier(Rank.GYM_CAT, Tier.II), 1320, LocalDate.of(2027, 3, 1))
        assertEquals("Aug 29, 2026 \u2013 Aug 28, 2027 \u2022 best Gym Cat II", pastSeasonLine(PastSeason(season, best)))
        assertEquals("Aug 29, 2026 \u2013 Aug 28, 2027 \u2022 no rank", pastSeasonLine(PastSeason(season, null)))
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

    private fun rank(diary: List<DailyMacroAggregation>, today: LocalDate) = RankRepositoryImpl(
        goalsRepository = Goals(maintaining),
        diaryRepository = P2PhaseFourRankReplayTests.Diary(diary),
        clock = MutableTestClock(today)
    )

    private class Goals(private val goal: Goal) : GoalsRepository {
        override suspend fun getGoals() = goal
        override fun observeGoals(): Flow<Goal> = flowOf(goal)
        override suspend fun updateGoals(goals: Goal) {}
    }
}
