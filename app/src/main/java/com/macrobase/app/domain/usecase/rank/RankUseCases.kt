package com.macrobase.app.domain.usecase.rank

import com.macrobase.app.domain.model.rank.LifestyleScore
import com.macrobase.app.domain.model.rank.RankProfile
import com.macrobase.app.domain.repository.rank.RankRepository
import kotlinx.coroutines.flow.Flow
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

class CalculateLifestyleScoreUseCase(
    private val rankRepository: RankRepository
) {
    suspend operator fun invoke(startDate: LocalDate, endDate: LocalDate): LifestyleScore {
        return rankRepository.calculateLifestyleScoreForPeriod(startDate, endDate)
    }
}
