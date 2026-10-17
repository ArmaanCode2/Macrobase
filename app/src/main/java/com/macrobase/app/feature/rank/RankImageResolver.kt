package com.macrobase.app.feature.rank

import androidx.annotation.DrawableRes
import com.macrobase.app.R
import com.macrobase.app.domain.model.rank.Rank
import com.macrobase.app.domain.model.rank.RankTier

/**
 * Centralized resolver mapping domain Rank / RankTier models to their corresponding drawable assets.
 */
object RankImageResolver {

    @DrawableRes
    fun getRankDrawableRes(rankTier: RankTier): Int {
        return getRankDrawableRes(rankTier.rank)
    }

    @DrawableRes
    fun getRankDrawableRes(rank: Rank): Int {
        return when (rank) {
            Rank.FAT_BEAR -> R.drawable.rank_fat_bear
            Rank.AVERAGE -> R.drawable.rank_average
            Rank.CARB_MERCHANT -> R.drawable.rank_carb_merchant
            Rank.PROTEIN_ALCHEMIST -> R.drawable.rank_protein_alchemist
            Rank.GYM_CAT -> R.drawable.rank_gym_cat
            Rank.MUSCLE_BUSTER -> R.drawable.rank_muscle_buster
            Rank.FART_MONSTER -> R.drawable.rank_fart_monster
            Rank.GIGACHAD -> R.drawable.rank_gigachad
        }
    }

    /** The small picture for a row of the rank ladder: decoding it costs about a seventh of the full one. */
    @DrawableRes
    fun getRankThumbnailRes(rank: Rank): Int {
        return when (rank) {
            Rank.FAT_BEAR -> R.drawable.rank_fat_bear_thumb
            Rank.AVERAGE -> R.drawable.rank_average_thumb
            Rank.CARB_MERCHANT -> R.drawable.rank_carb_merchant_thumb
            Rank.PROTEIN_ALCHEMIST -> R.drawable.rank_protein_alchemist_thumb
            Rank.GYM_CAT -> R.drawable.rank_gym_cat_thumb
            Rank.MUSCLE_BUSTER -> R.drawable.rank_muscle_buster_thumb
            Rank.FART_MONSTER -> R.drawable.rank_fart_monster_thumb
            Rank.GIGACHAD -> R.drawable.rank_gigachad_thumb
        }
    }
}
