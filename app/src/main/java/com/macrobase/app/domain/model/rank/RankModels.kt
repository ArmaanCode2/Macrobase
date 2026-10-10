package com.macrobase.app.domain.model.rank

import com.macrobase.app.domain.model.FitnessGoal
import java.time.LocalDate

enum class Rank(val displayName: String, val order: Int) {
    FAT_BEAR("Fat Bear", 0),
    AVERAGE("Average", 1),
    CARB_MERCHANT("Carb Merchant", 2),
    PROTEIN_ALCHEMIST("Protein Alchemist", 3),
    GYM_CAT("Gym Cat", 4),
    MUSCLE_BUSTER("Muscle Buster", 5),
    FART_MONSTER("Fart Monster", 6),
    GIGACHAD("GigaChad", 7);

    companion object {
        fun fromOrder(order: Int): Rank {
            return entries.firstOrNull { it.order == order } ?: FAT_BEAR
        }
    }
}

enum class Tier(val displayName: String, val level: Int) {
    I("I", 1),
    II("II", 2),
    III("III", 3);

    companion object {
        fun fromLevel(level: Int): Tier {
            return entries.firstOrNull { it.level == level } ?: I
        }
    }
}

data class RankTier(
    val rank: Rank,
    val tier: Tier
) {
    val displayName: String
        get() = "${rank.displayName} ${tier.displayName}"

    val tierGlobalIndex: Int
        get() = rank.order * 3 + (tier.level - 1)
}

data class RankBreakdown(
    val rankTier: RankTier,
    val tierRR: Int,
    val overallRating: Int
)

data class LifestyleScore(
    val consistencyScore: Double,
    val calorieScore: Double,
    val macroScore: Double,
    val weightTrendScore: Double,
    val overallScore: Double
) {
    companion object {
        val ZERO = LifestyleScore(
            consistencyScore = 0.0,
            calorieScore = 0.0,
            macroScore = 0.0,
            weightTrendScore = 50.0,
            overallScore = 0.0
        )
    }
}

data class RankHistoryEntry(
    val date: LocalDate,
    val rankTier: RankTier,
    val tierRR: Int,
    val overallRating: Int,
    val rrDelta: Int,
    val overallScore: Double,
    val ratingBefore: Int = 0,
    val rankTierBefore: RankTier = RankTier(Rank.FAT_BEAR, Tier.I),
    val calorieScore: Double = 0.0,
    val consistencyScore: Double = 0.0,
    val macroScore: Double = 0.0,
    val weightTrendScore: Double = 0.0
)

data class RankProfile(
    val currentRating: Int,
    val rankTier: RankTier,
    val tierRR: Int,
    val isProvisional: Boolean,
    val loggedDaysCount: Int,
    val lifestyleScore: LifestyleScore,
    val recentHistory: List<RankHistoryEntry>,
    val fitnessGoal: FitnessGoal = FitnessGoal.MAINTAINING,
    val maintenanceCalories: Double = 2000.0,
    val pendingStrategyChange: String? = null
)

/**
 * Pure domain function mapping a 0-2399 rating score into RankTier.
 */
fun ratingToRankTier(score: Int): RankTier {
    val clamped = score.coerceIn(0, 2399)
    val globalTierIndex = clamped / 100
    val rankIndex = (globalTierIndex / 3).coerceIn(0, Rank.entries.size - 1)
    val tierIndex = (globalTierIndex % 3).coerceIn(0, Tier.entries.size - 1)
    return RankTier(Rank.entries[rankIndex], Tier.entries[tierIndex])
}

/**
 * Pure domain function mapping a 0-2399 rating score into a complete breakdown.
 */
fun scoreToRankBreakdown(score: Int): RankBreakdown {
    val clamped = score.coerceIn(0, 2399)
    val rankTier = ratingToRankTier(clamped)
    val tierRR = clamped % 100
    return RankBreakdown(
        rankTier = rankTier,
        tierRR = tierRR,
        overallRating = clamped
    )
}

/**
 * Pure domain function converting a Rank + Tier + RR into a 0-2399 rating score.
 */
fun rankTierAndRRToScore(rank: Rank, tier: Tier, tierRR: Int): Int {
    val base = (rank.order * 3 + (tier.level - 1)) * 100
    return (base + tierRR.coerceIn(0, 99)).coerceIn(0, 2399)
}
