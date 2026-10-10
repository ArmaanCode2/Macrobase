package com.macrobase.app.feature.rank

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macrobase.app.domain.model.rank.LifestyleScore
import com.macrobase.app.domain.model.rank.Rank
import com.macrobase.app.domain.model.rank.RankProfile
import com.macrobase.app.domain.model.rank.RankTier
import com.macrobase.app.domain.model.rank.Tier
import com.macrobase.app.domain.usecase.rank.GetRankProfileUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class RankUiState(
    val isLoading: Boolean = false,
    val rankProfile: RankProfile = RankProfile(
        currentRating = 0,
        rankTier = RankTier(Rank.FAT_BEAR, Tier.I),
        tierRR = 0,
        isProvisional = true,
        loggedDaysCount = 0,
        lifestyleScore = LifestyleScore.ZERO,
        recentHistory = emptyList()
    )
)

class RankViewModel(
    getRankProfileUseCase: GetRankProfileUseCase
) : ViewModel() {

    val uiState: StateFlow<RankUiState> = getRankProfileUseCase()
        .map { profile ->
            RankUiState(
                isLoading = false,
                rankProfile = profile
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = RankUiState(isLoading = true)
        )
}
