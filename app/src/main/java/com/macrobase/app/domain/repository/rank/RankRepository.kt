package com.macrobase.app.domain.repository.rank

import com.macrobase.app.domain.model.rank.LifestyleScore
import com.macrobase.app.domain.model.rank.RankProfile
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface RankRepository {
    fun observeRankProfile(): Flow<RankProfile>
    suspend fun getRankProfile(): RankProfile
    suspend fun calculateLifestyleScoreForPeriod(startDate: LocalDate, endDate: LocalDate): LifestyleScore
}
