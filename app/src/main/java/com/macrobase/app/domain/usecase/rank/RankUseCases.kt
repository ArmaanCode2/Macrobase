package com.macrobase.app.domain.usecase.rank

import com.macrobase.app.core.config.WeeklyReviewConfig
import com.macrobase.app.core.util.DeviceClock
import com.macrobase.app.domain.model.rank.LifestyleScore
import com.macrobase.app.domain.model.rank.RankProfile
import com.macrobase.app.domain.model.rank.WeeklyReview
import com.macrobase.app.domain.model.rank.weeklyReview
import com.macrobase.app.domain.repository.DiaryRepository
import com.macrobase.app.domain.repository.GoalsRepository
import com.macrobase.app.domain.repository.rank.RankRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import java.time.Clock
import java.time.LocalDate

class GetRankProfileUseCase(
    private val rankRepository: RankRepository
) {
    operator fun invoke(): Flow<RankProfile> = rankRepository.observeRankProfile()
    suspend fun get(): RankProfile = rankRepository.getRankProfile()
}

class DismissRankRulesNoticeUseCase(
    private val rankRepository: RankRepository
) {
    suspend operator fun invoke() = rankRepository.dismissRulesNotice()
}

class DismissRankSeasonNoticeUseCase(
    private val rankRepository: RankRepository
) {
    suspend operator fun invoke(seasonStart: LocalDate) = rankRepository.dismissSeasonNotice(seasonStart)
}

/**
 * The Rank screen's weekly review of a profile (domain/model/rank/WeeklyReview.kt), with this
 * week's foods, their nutrients filled as the score fills them, and each day's goal for the
 * tips. It reads the rank rules and changes nothing in them.
 */
class GetWeeklyReviewUseCase(
    private val diaryRepository: DiaryRepository,
    private val goalsRepository: GoalsRepository,
    private val rankRepository: RankRepository,
    private val clock: Clock = DeviceClock
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(profile: RankProfile): Flow<WeeklyReview?> {
        // The profile's own day: just after midnight it can still be yesterday's
        val today = profile.recentHistory.firstOrNull { it.isInProgress }?.date ?: LocalDate.now(clock)
        val from = today.minusDays(WeeklyReviewConfig.WEEK_DAYS.toLong())
        val to = today.minusDays(1)
        val dates = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
        return combine(
            diaryRepository.observeLoggedFoodNutrients(from, to).mapLatest { rankRepository.withScoredNutrients(it) },
            goalsRepository.observeGoalsForDates(dates)
        ) { foods, goals ->
            weeklyReview(profile.recentHistory, today, foods, dates.zip(goals).toMap())
        }
    }
}

class CalculateLifestyleScoreUseCase(
    private val rankRepository: RankRepository
) {
    suspend operator fun invoke(startDate: LocalDate, endDate: LocalDate): LifestyleScore {
        return rankRepository.calculateLifestyleScoreForPeriod(startDate, endDate)
    }
}
