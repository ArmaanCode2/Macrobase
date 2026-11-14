package com.macrobase.app.domain.model.rank

import com.macrobase.app.core.config.RankScoringConfig
import com.macrobase.app.core.config.WeeklyReviewConfig
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.rank.RankDayNutrients.KnownAmount
import com.macrobase.app.domain.repository.LoggedFoodNutrients
import java.time.LocalDate
import kotlin.math.abs

/**
 * One week of ended days as the rank scored them. The averages are over the logged days, and null
 * when none was logged.
 */
data class WeekScores(
    /** The RR the week's days gave, missed days included. */
    val rrChange: Int,
    val loggedDays: Int,
    /** The days of the week the rank counted: 7, or fewer when the season or the goal history began during it. */
    val countedDays: Int,
    val dayScore: Double?,
    val calorieScore: Double?,
    val proteinScore: Double?,
    val qualityScore: Double?,
    val balanceScore: Double?
)

/** The food quality measures of the rank, in its order. */
enum class QualityMeasure { FIBER, SATURATED_FAT, SODIUM, SUGAR }

/** The two sides of the carb and fat balance. */
enum class BalanceMacro { CARBS, FAT }

/** A tip for a low part of the week, with the user's own number. */
sealed interface WeeklyTip {
    /**
     * [daysOutside] of [loggedDays] logged days missed full calorie credit. [zone] is the zone in
     * kcal, or null when it changed during the week, since each day is judged against its own.
     */
    data class Calories(val daysOutside: Int, val loggedDays: Int, val zone: ClosedFloatingPointRange<Double>?) : WeeklyTip

    /** Protein per logged day against the goal, in grams. */
    data class Protein(val averageGrams: Double, val goalGrams: Double) : WeeklyTip

    /**
     * A quality measure and its value: grams of fiber per 1,000 kcal, saturated fat or sugar as a
     * percentage of calories, or sodium in mg a day. Null when foods stating it cover too little of
     * the week's calories to judge it.
     */
    data class Quality(val measure: QualityMeasure, val value: Double?) : WeeklyTip

    /** The macro further from its goal, per logged day, in grams. */
    data class Balance(val macro: BalanceMacro, val averageGrams: Double, val goalGrams: Double) : WeeklyTip

    /** Days of the week the rank counted that were not logged. */
    data class Logging(val missedDays: Int) : WeeklyTip
}

/** This week against last week, and up to two tips. */
data class WeeklyReview(
    val thisWeek: WeekScores,
    /** Null early in a season, when last week has too few logged days to compare with. */
    val lastWeek: WeekScores?,
    val tips: List<WeeklyTip>
)

/**
 * The weekly review: the 7 days up to yesterday in [history] (the rank history of the current
 * season) against the 7 before, and tips for this week's lowest parts. [foods] and [goals] are
 * this week's foods, with the nutrients the score used, and each day's goal, for the numbers the
 * tips quote. Null when this week has no ended day the rank counts. It reads the rules and changes
 * nothing in them.
 */
fun weeklyReview(
    history: List<RankHistoryEntry>,
    today: LocalDate,
    foods: List<LoggedFoodNutrients>,
    goals: Map<LocalDate, Goal>
): WeeklyReview? {
    val week = WeeklyReviewConfig.WEEK_DAYS.toLong()
    // Ended days only: today is a preview until it ends
    val ended = history.filter { !it.isInProgress && it.date.isBefore(today) }
    fun weekEnding(last: LocalDate) = ended.filter { !it.date.isBefore(last.minusDays(week - 1)) && !it.date.isAfter(last) }
    val thisWeek = weekEnding(today.minusDays(1))
    if (thisWeek.isEmpty()) return null
    val thisScores = weekScores(thisWeek)
    val lastScores = weekScores(weekEnding(today.minusDays(1 + week)))
        .takeIf { it.loggedDays >= WeeklyReviewConfig.MIN_COMPARABLE_LOGGED_DAYS }
    return WeeklyReview(thisScores, lastScores, weeklyTips(thisWeek, thisScores, foods, goals))
}

private fun weekScores(days: List<RankHistoryEntry>): WeekScores {
    val logged = days.filter { it.isLogged }
    fun average(score: (RankHistoryEntry) -> Double) = if (logged.isEmpty()) null else logged.sumOf(score) / logged.size
    return WeekScores(
        rrChange = days.sumOf { it.rrDelta },
        loggedDays = logged.size,
        countedDays = days.size,
        dayScore = average { it.overallScore },
        calorieScore = average { it.calorieScore },
        proteinScore = average { it.proteinScore },
        qualityScore = average { it.qualityScore },
        balanceScore = average { it.balanceScore }
    )
}

/** The parts of the week a tip can be about, in the order a tie is broken. */
private enum class WeekPart { CALORIES, PROTEIN, QUALITY, BALANCE, LOGGING }

/**
 * Tips for the lowest parts of the week under [WeeklyReviewConfig.TIP_SCORE_BELOW], at most
 * [WeeklyReviewConfig.MAX_TIPS]. Logging scores the share of the week's counted days that were
 * logged, so days before the season started are not called missed.
 */
private fun weeklyTips(
    days: List<RankHistoryEntry>,
    scores: WeekScores,
    foods: List<LoggedFoodNutrients>,
    goals: Map<LocalDate, Goal>
): List<WeeklyTip> {
    val logged = days.filter { it.isLogged }
    val loggedDates = logged.map { it.date }.toSet()
    val week = WeekNutrients(foods.filter { it.date in loggedDates }, logged.mapNotNull { goals[it.date] }, logged.size)
    // Each day was judged against the zone of its own goal; one range is quoted only when they agree
    val zone = logged
        .map { day -> goals[day.date]?.let { RankScoringConfig.calorieZone(it.fitnessGoal, it.maintenanceCalories) } }
        .distinct()
        .singleOrNull()
    val parts = listOf(
        WeekPart.CALORIES to scores.calorieScore,
        WeekPart.PROTEIN to scores.proteinScore,
        WeekPart.QUALITY to scores.qualityScore,
        WeekPart.BALANCE to scores.balanceScore,
        WeekPart.LOGGING to logged.size * 100.0 / days.size
    )
    return parts
        .mapNotNull { (part, score) -> score?.takeIf { it < WeeklyReviewConfig.TIP_SCORE_BELOW }?.let { part to it } }
        .sortedBy { it.second }
        .mapNotNull { (part, _) ->
            when (part) {
                WeekPart.CALORIES -> WeeklyTip.Calories(logged.count { it.calorieScore < 100.0 }, logged.size, zone)
                WeekPart.PROTEIN -> week.proteinTip()
                WeekPart.QUALITY -> week.qualityTip()
                WeekPart.BALANCE -> week.balanceTip()
                WeekPart.LOGGING -> WeeklyTip.Logging(days.size - logged.size)
            }
        }
        .take(WeeklyReviewConfig.MAX_TIPS)
}

/** This week's logged days: their foods, their goals and how many days there were. */
private class WeekNutrients(
    private val foods: List<LoggedFoodNutrients>,
    private val goals: List<Goal>,
    private val days: Int
) {
    private val calories = foods.sumOf { it.calories }

    private fun perDay(value: (LoggedFoodNutrients) -> Double) = foods.sumOf(value) / days
    private fun goalPerDay(value: (Goal) -> Double) = if (goals.isEmpty()) 0.0 else goals.sumOf(value) / goals.size

    // The parts are scored day by day, so a week can score low while its average meets the target.
    // A tip that would say so is left out, and the next part gets the place.

    fun proteinTip(): WeeklyTip.Protein? {
        val goal = goalPerDay { it.proteinGrams }
        if (foods.isEmpty() || goal <= 0.0) return null
        return WeeklyTip.Protein(perDay { it.proteinGrams }, goal).takeIf { it.averageGrams < goal }
    }

    /** The macro further from its goal; one without a goal is left out. */
    fun balanceTip(): WeeklyTip.Balance? {
        if (foods.isEmpty()) return null
        fun offBy(tip: WeeklyTip.Balance) = abs(tip.averageGrams / tip.goalGrams - 1.0)
        return listOf(
            WeeklyTip.Balance(BalanceMacro.CARBS, perDay { it.carbsGrams }, goalPerDay { it.carbGrams }),
            WeeklyTip.Balance(BalanceMacro.FAT, perDay { it.fatGrams }, goalPerDay { it.fatGrams })
        ).filter { it.goalGrams > 0.0 }
            .maxByOrNull(::offBy)
            ?.takeIf { offBy(it) > RankScoringConfig.BALANCE_FULL_MARKS_WITHIN }
    }

    /**
     * A measure is judged when the foods stating it cover enough of the week's calories. The tip
     * names the judged measure that lost the most quality points on those foods, scored as the
     * rank scores a day. Only when no judged measure misses its target does it name an unjudged
     * one, the one the missing data cost most, saying there is too little data to judge it.
     */
    fun qualityTip(): WeeklyTip.Quality? {
        if (calories <= 0.0) return null
        fun known(value: (LoggedFoodNutrients) -> Double?) =
            foods.fold(KnownAmount.NONE) { sum, food -> sum + KnownAmount.of(value(food), food.calories) }
        val measures = listOf(
            Measure(
                QualityMeasure.FIBER, known { it.fiberGrams }, RankScoringConfig.FIBER_SHARE,
                { it.amount / it.calories * 1000.0 }, RankScoringConfig::fiberScore
            ),
            Measure(
                QualityMeasure.SATURATED_FAT, known { it.saturatedFatGrams }, RankScoringConfig.SATURATED_FAT_SHARE,
                { it.amount * 9.0 / it.calories * 100.0 }, RankScoringConfig::saturatedFatScore
            ),
            // Sodium has a daily limit: the stated foods' rate over a whole logged day
            Measure(
                QualityMeasure.SODIUM, known { it.sodiumMg }, RankScoringConfig.SODIUM_SHARE,
                { it.amount / it.calories * (calories / days) }, RankScoringConfig::sodiumScore
            ),
            Measure(
                QualityMeasure.SUGAR, known { it.sugarGrams }, RankScoringConfig.SUGAR_SHARE,
                { it.amount * 4.0 / it.calories * 100.0 }, RankScoringConfig::sugarScore
            )
        )
        val (judged, unjudged) = measures.partition { it.coverage(calories) >= WeeklyReviewConfig.MIN_QUALITY_COVERAGE }
        val weakest = judged.maxByOrNull { it.statedPointsLost(calories) }
        if (weakest != null && weakest.statedPointsLost(calories) > 0.0) {
            return WeeklyTip.Quality(weakest.measure, weakest.value(weakest.known))
        }
        return unjudged.maxByOrNull { it.missingPointsLost(calories) }?.let { WeeklyTip.Quality(it.measure, null) }
    }

    /** One quality measure of the week: what the foods stating it add up to, and how it scores. */
    private class Measure(
        val measure: QualityMeasure,
        val known: KnownAmount,
        private val share: Double,
        val value: (KnownAmount) -> Double,
        private val score: (Double) -> Double
    ) {
        fun coverage(weekCalories: Double): Double = (known.calories / weekCalories).coerceIn(0.0, 1.0)

        /** The quality points it lost on the foods that state it. */
        fun statedPointsLost(weekCalories: Double): Double =
            if (known.calories <= 0.0) 0.0 else share * coverage(weekCalories) * (100.0 - score(value(known)))

        /** The quality points the calories of foods not stating it cost, at the unknown score. */
        fun missingPointsLost(weekCalories: Double): Double =
            share * (1.0 - coverage(weekCalories)) * (100.0 - RankScoringConfig.UNKNOWN_SCORE)
    }
}
