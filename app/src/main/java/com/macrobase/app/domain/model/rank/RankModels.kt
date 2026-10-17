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

/**
 * The score card: each day's scores averaged over the logged days among the last 30 that have
 * ended, so it agrees with the RR those days gave. Scores are 0-100.
 */
data class LifestyleScore(
    /** Share of those days that were logged, counted from the first logged day. */
    val consistencyScore: Double,
    /** Calorie credit: 100 inside the strategy's calorie zone, 0 on the wrong side of maintenance. */
    val calorieScore: Double,
    val proteinScore: Double,
    val qualityScore: Double,
    val balanceScore: Double,
    /** The day score: calorie credit x food score. */
    val overallScore: Double,
    val loggedDays: Int = 0,
    val eligibleDays: Int = 0
) {
    companion object {
        val ZERO = LifestyleScore(
            consistencyScore = 0.0,
            calorieScore = 0.0,
            proteinScore = 0.0,
            qualityScore = 0.0,
            balanceScore = 0.0,
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
    /** The day score, 0-100; 0 for a day not logged. */
    val overallScore: Double,
    val ratingBefore: Int = 0,
    val rankTierBefore: RankTier = RankTier(Rank.FAT_BEAR, Tier.I),
    /** Calorie credit x 100. */
    val calorieScore: Double = 0.0,
    val consistencyScore: Double = 0.0,
    val proteinScore: Double = 0.0,
    val qualityScore: Double = 0.0,
    val balanceScore: Double = 0.0,
    /** The day score the rank held before this day expected. */
    val expectedScore: Double = 0.0,
    /** Calories logged that day. */
    val calories: Double = 0.0,
    /** Whether anything was logged that day. */
    val isLogged: Boolean = consistencyScore > 0.0,
    /**
     * Today, still in progress: [rrDelta] is a preview of what the day would give if it ended now,
     * and is not in the rating ([overallRating] equals [ratingBefore]) until the day ends (BUG-032).
     */
    val isInProgress: Boolean = false,
    /** The day would have moved the rating into a rank that needs a clean week, and was held below it. */
    val heldByCleanWeek: Boolean = false
)

data class RankProfile(
    val currentRating: Int,
    val rankTier: RankTier,
    val tierRR: Int,
    val isProvisional: Boolean,
    /** Logged days that have ended; today counts once it is over (BUG-032). */
    val loggedDaysCount: Int,
    val lifestyleScore: LifestyleScore,
    /** Every day the rank counts, the newest first: today in progress, then each ended day. */
    val recentHistory: List<RankHistoryEntry>,
    val fitnessGoal: FitnessGoal = FitnessGoal.MAINTAINING,
    val maintenanceCalories: Double = 2000.0,
    val pendingStrategyChange: String? = null,
    /** Today's full-credit calorie zone, in kcal. */
    val calorieZone: ClosedFloatingPointRange<Double> = 0.0..0.0,
    /** The rank rules changed since the user last looked, so their rank was recalculated. */
    val showRulesNotice: Boolean = false,
    /** The year the rank counts now; null before anything is logged. */
    val season: RankSeason? = null,
    /** The highest rank of [season] so far, once the rating is established and above 0. */
    val bestThisSeason: BestRank? = null,
    /** The highest rank of the season before [season], for the new-season notice. */
    val lastSeasonBest: BestRank? = null,
    /** [season] started after the one whose reset the user last saw. */
    val showNewSeasonNotice: Boolean = false,
    /** The rating at the end of every counted day that has ended, all seasons, the oldest first. */
    val ratingHistory: List<RatingPoint> = emptyList(),
    /** The seasons before [season], the oldest first. */
    val pastSeasons: List<PastSeason> = emptyList(),
    /**
     * The rating that starts the next rank, when the rating is in the tier just below it and that
     * rank needs a clean week the user has not had lately; null otherwise.
     */
    val cleanWeekGate: Int? = null,
    /**
     * The first day with a saved goal, when logged days before it were left out because their
     * goal is only a guess; null when no logged day was left out.
     */
    val rankCountsFrom: LocalDate? = null
)

/**
 * One year of rank. The first season starts on the first logged day and each later one on its
 * anniversary, when the rank starts again from the bottom; a first log on Feb 29 resets on Feb 28
 * in other years. [number] counts from 1.
 */
data class RankSeason(val number: Int, val start: LocalDate, val nextReset: LocalDate) {
    companion object {
        /** The season [date] falls in, for a diary first logged on [firstLogged]. */
        fun containing(firstLogged: LocalDate, date: LocalDate): RankSeason {
            var years = maxOf(0, date.year - firstLogged.year)
            if (years > 0 && firstLogged.plusYears(years.toLong()).isAfter(date)) years--
            return RankSeason(years + 1, firstLogged.plusYears(years.toLong()), firstLogged.plusYears(years + 1L))
        }
    }
}

/** The highest rating of a season, its rank and tier, and the first day that rank and tier were reached. */
data class BestRank(val rankTier: RankTier, val rating: Int, val date: LocalDate)

/** The rating once [date] ended, in season [seasonNumber]. */
data class RatingPoint(val date: LocalDate, val rating: Int, val seasonNumber: Int)

/** A season that has ended and its best rank; [best] is null when it never had a rank above 0. */
data class PastSeason(val season: RankSeason, val best: BestRank?)

/**
 * A day's logged nutrition as the rank scores it. For each secondary nutrient it keeps the sum
 * over the entries that state it and the calories those entries cover, so a day where only some
 * foods state fiber is judged on those foods and the rest counts as unknown.
 */
data class RankDayNutrients(
    val calories: Double,
    val proteinGrams: Double,
    val carbsGrams: Double,
    val fatGrams: Double,
    val fiber: KnownAmount = KnownAmount.NONE,
    val sugar: KnownAmount = KnownAmount.NONE,
    val saturatedFat: KnownAmount = KnownAmount.NONE,
    val sodium: KnownAmount = KnownAmount.NONE
) {
    /** [amount] summed over entries that state the nutrient, which cover [calories] kcal. */
    data class KnownAmount(val amount: Double, val calories: Double) {
        operator fun plus(other: KnownAmount) = KnownAmount(amount + other.amount, calories + other.calories)

        companion object {
            val NONE = KnownAmount(0.0, 0.0)

            /** One entry's share: nothing when it does not state the nutrient. */
            fun of(value: Double?, entryCalories: Double) =
                if (value == null) NONE else KnownAmount(value, entryCalories.coerceAtLeast(0.0))
        }
    }
}

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
