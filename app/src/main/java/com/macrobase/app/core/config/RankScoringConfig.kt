package com.macrobase.app.core.config

import com.macrobase.app.domain.model.FitnessGoal
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.rank.RankDayNutrients
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The rank rules (docs/RANKING_SYSTEM.md). Each logged day gets a day score, 0-100:
 *
 *     day score = calorie credit (0-1) x food score (0-100)
 *     food score = 40% protein + 45% food quality + 15% carb and fat balance
 *
 * The calorie credit comes from the strategy and maintenance in force that day, never from the
 * calorie goal, which can be edited on the day itself. Eating within 5% of maintenance while
 * cutting or bulking, or more than 10% away from it while maintaining, gives no credit and so a
 * day score of 0.
 *
 * RR = (day score - the score the current rating expects) x 0.8, between -15 and +20. A missed day
 * is -20, more than any logged day can lose, so an honest bad day always beats skipping it. Moving
 * up into the top two ranks needs a clean week, where a missed day and a day outside the calorie
 * zone count alike, so skipping a bad day does not help there either.
 *
 * Protein and the carb and fat balance follow the targets saved for the day, as the dashboard does
 * (BUG-024), so editing them late in a day changes that day's scores.
 */
object RankScoringConfig {
    /** Raised whenever the rules change, so the rank screen can say the rank was recalculated. */
    const val RULES_VERSION = 4

    // Rolling windows and evaluation
    const val ROLLING_WINDOW_DAYS = 30
    const val PROVISIONAL_DAYS_THRESHOLD = 7

    // Rating limits
    const val MIN_RATING = 0
    const val MAX_RATING = 2399
    const val RR_PER_TIER = 100
    const val TIERS_PER_RANK = 3
    const val RR_PER_RANK = 300
    const val TOTAL_RANKS = 8

    // RR per day
    const val RR_PER_SCORE_POINT = 0.8
    const val MAX_RR_GAIN_PER_DAY = 20
    const val MAX_RR_LOSS_PER_DAY = 15
    const val MISSED_DAY_RR = -20

    // Food score shares
    const val PROTEIN_SHARE = 0.40
    const val QUALITY_SHARE = 0.45
    const val BALANCE_SHARE = 0.15

    // Food quality shares
    const val FIBER_SHARE = 0.40
    const val SATURATED_FAT_SHARE = 0.25
    const val SODIUM_SHARE = 0.20
    const val SUGAR_SHARE = 0.15

    /** The score a measure gets when the day's foods do not state it. */
    const val UNKNOWN_SCORE = 50.0

    /**
     * The day score each rating expects, rising smoothly through these points (rating to score),
     * so a steady day score settles inside a tier instead of flipping between two each day.
     */
    private val EXPECTED_SCORE_POINTS = listOf(
        0 to 40.0, 300 to 50.0, 600 to 58.0, 900 to 65.0, 1200 to 72.0,
        1500 to 79.0, 1800 to 85.0, 2100 to 90.0, 2399 to 94.0
    )

    /**
     * Moving up into these ratings (Fart Monster, GigaChad) needs a clean week ending within the
     * last [CLEAN_WEEK_LOOKBACK_DAYS] days.
     */
    val CLEAN_WEEK_GATES = listOf(1800, 2100)
    const val CLEAN_WEEK_LOOKBACK_DAYS = 14
    const val CLEAN_WEEK_DAYS = 7

    /** Days of a clean week that may be missed or below half the calorie credit, together. */
    const val CLEAN_WEEK_OFF_DAYS_ALLOWED = 1
    const val CLEAN_DAY_MIN_CALORIE_CREDIT = 0.5

    /** Straight line from (x0, y0) to (x1, y1), flat outside it. */
    private fun ramp(x: Double, x0: Double, x1: Double, y0: Double, y1: Double): Double = when {
        x <= x0 -> y0
        x >= x1 -> y1
        else -> y0 + (x - x0) / (x1 - x0) * (y1 - y0)
    }

    /**
     * How much of the food score a day keeps for its calories, 0-1, against [maintenance] for
     * [strategy]:
     * - cutting: full credit 10-25% below maintenance, none within 5% of it, above it, or more
     *   than 35% below;
     * - bulking: full credit 10-20% above, none within 5% of it, below it, or more than 30% above;
     * - maintaining: full credit within 5%, none beyond 10%.
     * Between these the credit changes in a straight line, so a few kcal never jump the RR.
     */
    fun calorieCredit(strategy: FitnessGoal, calories: Double, maintenance: Double): Double {
        if (maintenance <= 0.0 || calories <= 0.0) return 0.0
        val change = (calories - maintenance) / maintenance
        return when (strategy) {
            FitnessGoal.CUTTING -> {
                val deficit = -change
                if (deficit < 0.25) ramp(deficit, 0.05, 0.10, 0.0, 1.0) else ramp(deficit, 0.25, 0.35, 1.0, 0.0)
            }
            FitnessGoal.BULKING ->
                if (change < 0.20) ramp(change, 0.05, 0.10, 0.0, 1.0) else ramp(change, 0.20, 0.30, 1.0, 0.0)
            FitnessGoal.MAINTAINING -> ramp(abs(change), 0.05, 0.10, 1.0, 0.0)
        }
    }

    /** The full-credit calorie zone for [strategy], in kcal. */
    fun calorieZone(strategy: FitnessGoal, maintenance: Double): ClosedFloatingPointRange<Double> = when (strategy) {
        FitnessGoal.CUTTING -> maintenance * 0.75..maintenance * 0.90
        FitnessGoal.BULKING -> maintenance * 1.10..maintenance * 1.20
        FitnessGoal.MAINTAINING -> maintenance * 0.95..maintenance * 1.05
    }

    /**
     * Protein against the goal in grams: none at half the goal or less, full marks from the goal
     * up to 1.5 times it, easing to 80 at twice the goal. More protein than planned is not punished.
     */
    fun proteinScore(actualGrams: Double, targetGrams: Double): Double {
        if (targetGrams <= 0.0) return UNKNOWN_SCORE
        val ratio = actualGrams / targetGrams
        return if (ratio <= 1.5) ramp(ratio, 0.5, 1.0, 0.0, 100.0) else ramp(ratio, 1.5, 2.0, 100.0, 80.0)
    }

    /** Carbs and fat each against their goal: full marks within 20%, none at 50% off. */
    fun balanceScore(carbsGrams: Double, carbTargetGrams: Double, fatGrams: Double, fatTargetGrams: Double): Double {
        fun side(actual: Double, target: Double) =
            if (target <= 0.0) UNKNOWN_SCORE else ramp(abs(actual / target - 1.0), 0.2, 0.5, 100.0, 0.0)
        return (side(carbsGrams, carbTargetGrams) + side(fatGrams, fatTargetGrams)) / 2.0
    }

    /**
     * Food quality, 0-100, from the Dietary Guidelines for Americans (fiber per 1,000 kcal from
     * the 2010 edition, the saturated fat and sodium limits from 2020-2025):
     * - fiber 14 g or more per 1,000 kcal is full marks, 4 g or less none;
     * - saturated fat 10% of calories or less is full marks, 15% scores 40, 20% or more none;
     * - sodium 2,300 mg a day or less is full marks, 4,000 mg or more none;
     * - total sugar 20% of calories or less is full marks, 35% or more none. The diary stores total
     *   sugar, fruit and milk included, not the added sugar the guideline limits, hence the leeway.
     * Each measure is judged on the foods that state it; the share of the day's calories from
     * foods that do not counts as [UNKNOWN_SCORE].
     */
    fun qualityScore(day: RankDayNutrients): Double {
        if (day.calories <= 0.0) return UNKNOWN_SCORE
        val fiber = measure(day, day.fiber) { known -> ramp(known.amount / known.calories * 1000.0, 4.0, 14.0, 0.0, 100.0) }
        val saturatedFat = measure(day, day.saturatedFat) { known ->
            val percent = known.amount * 9.0 / known.calories * 100.0
            if (percent <= 15.0) ramp(percent, 10.0, 15.0, 100.0, 40.0) else ramp(percent, 15.0, 20.0, 40.0, 0.0)
        }
        // Sodium has a daily limit, so the known foods' sodium is scaled up to the whole day
        val sodium = measure(day, day.sodium) { known -> ramp(known.amount / known.calories * day.calories, 2300.0, 4000.0, 100.0, 0.0) }
        val sugar = measure(day, day.sugar) { known -> ramp(known.amount * 4.0 / known.calories * 100.0, 20.0, 35.0, 100.0, 0.0) }
        return fiber * FIBER_SHARE + saturatedFat * SATURATED_FAT_SHARE + sodium * SODIUM_SHARE + sugar * SUGAR_SHARE
    }

    private inline fun measure(
        day: RankDayNutrients,
        known: RankDayNutrients.KnownAmount,
        score: (RankDayNutrients.KnownAmount) -> Double
    ): Double {
        // No calories behind the stated values (only 0-kcal foods state it): nothing to judge by
        if (known.calories <= 0.0) return UNKNOWN_SCORE
        val coverage = (known.calories / day.calories).coerceIn(0.0, 1.0)
        return score(known) * coverage + UNKNOWN_SCORE * (1.0 - coverage)
    }

    fun foodScore(protein: Double, quality: Double, balance: Double): Double =
        protein * PROTEIN_SHARE + quality * QUALITY_SHARE + balance * BALANCE_SHARE

    /** Every score of one logged day, against [goal], the goal in force that day. */
    data class DayScores(
        val calorieCredit: Double,
        val protein: Double,
        val quality: Double,
        val balance: Double,
        val dayScore: Double
    )

    fun scoreDay(day: RankDayNutrients, goal: Goal): DayScores {
        // A non-finite total (an overflowing sum) must not reach roundToInt, which throws on NaN
        fun Double.orZero() = if (isFinite()) this else 0.0
        val credit = calorieCredit(goal.fitnessGoal, day.calories, goal.maintenanceCalories).orZero()
        val protein = proteinScore(day.proteinGrams, goal.proteinGrams).orZero()
        val quality = qualityScore(day).orZero()
        val balance = balanceScore(day.carbsGrams, goal.carbGrams, day.fatGrams, goal.fatGrams).orZero()
        return DayScores(credit, protein, quality, balance, (credit * foodScore(protein, quality, balance)).orZero())
    }

    /** The day score [rating] expects: 40 at the bottom of the ladder, 94 at the top. */
    fun expectedScore(rating: Int): Double {
        val clamped = rating.coerceIn(MIN_RATING, MAX_RATING)
        val upper = EXPECTED_SCORE_POINTS.indexOfFirst { it.first >= clamped }.coerceAtLeast(1)
        val (r0, s0) = EXPECTED_SCORE_POINTS[upper - 1]
        val (r1, s1) = EXPECTED_SCORE_POINTS[upper]
        return ramp(clamped.toDouble(), r0.toDouble(), r1.toDouble(), s0, s1)
    }

    /** The RR a logged day with [dayScore] gives at [rating]. */
    fun rrForDay(dayScore: Double, rating: Int): Int =
        ((dayScore - expectedScore(rating)) * RR_PER_SCORE_POINT).roundToInt()
            .coerceIn(-MAX_RR_LOSS_PER_DAY, MAX_RR_GAIN_PER_DAY)

    /**
     * The rating after [rrDelta], held below a clean-week gate it would cross upward without a
     * clean week in the lookback.
     */
    fun applyDelta(rating: Int, rrDelta: Int, hasRecentCleanWeek: Boolean): Int {
        var next = (rating + rrDelta).coerceIn(MIN_RATING, MAX_RATING)
        if (!hasRecentCleanWeek) {
            for (gate in CLEAN_WEEK_GATES) {
                if (rating < gate && next >= gate) next = gate - 1
            }
        }
        return next
    }
}
