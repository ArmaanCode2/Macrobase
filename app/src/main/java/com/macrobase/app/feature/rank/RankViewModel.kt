package com.macrobase.app.feature.rank

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macrobase.app.domain.model.rank.LifestyleScore
import com.macrobase.app.domain.model.rank.Rank
import com.macrobase.app.domain.model.rank.RankProfile
import com.macrobase.app.domain.model.rank.RankTier
import com.macrobase.app.domain.model.rank.Tier
import com.macrobase.app.domain.model.rank.WeeklyReview
import com.macrobase.app.domain.usecase.rank.DismissRankRulesNoticeUseCase
import com.macrobase.app.domain.usecase.rank.DismissRankSeasonNoticeUseCase
import com.macrobase.app.domain.usecase.rank.GetRankProfileUseCase
import com.macrobase.app.domain.usecase.rank.GetWeeklyReviewUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    ),
    val weeklyReview: WeeklyReview? = null
)

class RankViewModel(
    getRankProfileUseCase: GetRankProfileUseCase,
    private val dismissRankRulesNoticeUseCase: DismissRankRulesNoticeUseCase,
    private val dismissRankSeasonNoticeUseCase: DismissRankSeasonNoticeUseCase,
    /** Null leaves out the weekly review card. */
    private val getWeeklyReviewUseCase: GetWeeklyReviewUseCase? = null
) : ViewModel() {

    /** The profile, with this week against last week once the week's diary is read. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<RankUiState> = getRankProfileUseCase()
        .flatMapLatest { profile ->
            // A review that fails (a diary read error, say) leaves out the card, not the screen
            val reviews = getWeeklyReviewUseCase?.invoke(profile)?.catch { emit(null) } ?: flowOf(null)
            reviews.map { review ->
                RankUiState(
                    isLoading = false,
                    rankProfile = profile,
                    weeklyReview = review
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = RankUiState(isLoading = true)
        )

    fun dismissRulesNotice() {
        viewModelScope.launch { dismissRankRulesNoticeUseCase() }
    }

    fun dismissSeasonNotice() {
        val seasonStart = uiState.value.rankProfile.season?.start ?: return
        viewModelScope.launch { dismissRankSeasonNoticeUseCase(seasonStart) }
    }
}
