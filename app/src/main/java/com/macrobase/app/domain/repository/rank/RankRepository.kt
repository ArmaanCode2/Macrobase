package com.macrobase.app.domain.repository.rank

import com.macrobase.app.domain.model.rank.LifestyleScore
import com.macrobase.app.domain.model.rank.RankProfile
import com.macrobase.app.domain.repository.LoggedFoodNutrients
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface RankRepository {
    fun observeRankProfile(): Flow<RankProfile>
    suspend fun getRankProfile(): RankProfile
    suspend fun calculateLifestyleScoreForPeriod(startDate: LocalDate, endDate: LocalDate): LifestyleScore

    /** Remembers that the user has seen the current rank rules, so the notice does not return. */
    suspend fun dismissRulesNotice() {}

    /** Remembers that the user has seen the reset that started on [seasonStart], so the notice does not return. */
    suspend fun dismissSeasonNotice(seasonStart: LocalDate) {}

    /**
     * [foods] with the nutrients the score uses: an unstated one filled from the built-in food,
     * as the score fills it. Repositories without a catalog give [foods] as they are.
     */
    suspend fun withScoredNutrients(foods: List<LoggedFoodNutrients>): List<LoggedFoodNutrients> = foods
}
