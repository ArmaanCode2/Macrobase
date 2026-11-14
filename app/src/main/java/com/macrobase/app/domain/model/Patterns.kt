package com.macrobase.app.domain.model

import com.macrobase.app.core.config.PatternsConfig
import com.macrobase.app.domain.repository.LoggedMealNutrients
import java.time.DayOfWeek
import java.time.LocalDate

/** One pattern the Stats Patterns view can show. */
sealed interface PatternFinding {
    /** How far the pattern stands out, as a share; the largest shows first. */
    val effect: Double

    /** [meal] gives the least protein, under half of [highestMeal]'s, in grams per day logged. */
    data class LowProteinMeal(
        val meal: MealType,
        val averageGrams: Double,
        val highestMeal: MealType,
        val highestGrams: Double
    ) : PatternFinding {
        override val effect: Double get() = 1.0 - averageGrams / highestGrams
    }

    /** [weekday]s average [averageCalories], well above the daily average [overallCalories]. */
    data class HighCalorieWeekday(
        val weekday: DayOfWeek,
        val averageCalories: Double,
        val overallCalories: Double
    ) : PatternFinding {
        override val effect: Double get() = averageCalories / overallCalories - 1.0
    }

    /** [weekday]s have the most known sugar per 1,000 kcal, well above the overall rate. */
    data class SugarPeakWeekday(
        val weekday: DayOfWeek,
        val sugarPer1000Kcal: Double,
        val overallPer1000Kcal: Double
    ) : PatternFinding {
        override val effect: Double get() = sugarPer1000Kcal / overallPer1000Kcal - 1.0
    }

    /** [meal] has the most known sodium per 1,000 kcal, well above the overall rate. */
    data class SodiumPeakMeal(
        val meal: MealType,
        val sodiumPer1000Kcal: Double,
        val overallPer1000Kcal: Double
    ) : PatternFinding {
        override val effect: Double get() = sodiumPer1000Kcal / overallPer1000Kcal - 1.0
    }
}

/** The patterns of a window: how many days counted, and what stands out, largest first. */
data class PatternsReport(
    val countedDays: Int,
    val findings: List<PatternFinding>
) {
    /** Counted days still missing before any finding shows. */
    val daysNeeded: Int get() = (PatternsConfig.MIN_COUNTED_DAYS - countedDays).coerceAtLeast(0)
}

/** A value on a threshold counts as reaching it, whatever the last bits of the division. */
private const val EDGE_TOLERANCE = 1e-9

private fun reaches(value: Double, threshold: Double) = value >= threshold - EDGE_TOLERANCE

/**
 * The patterns in [entries], the foods of a window. A day counts when it has food and, when
 * [finishedDays] is given, is one of them; foods of other days are left out entirely. Below
 * [PatternsConfig.MIN_COUNTED_DAYS] counted days there are no findings.
 */
fun findPatterns(entries: List<LoggedMealNutrients>, finishedDays: Set<LocalDate>?): PatternsReport {
    val days = entries.groupBy { it.date }
        .filter { (date, foods) -> foods.sumOf { it.calories } > 0.0 && (finishedDays == null || date in finishedDays) }
    if (days.size < PatternsConfig.MIN_COUNTED_DAYS) return PatternsReport(days.size, emptyList())

    val foods = days.values.flatten()
    val findings = listOfNotNull(
        lowestProteinMeal(foods),
        highestCalorieWeekday(days),
        sugarPeakWeekday(foods),
        sodiumPeakMeal(foods)
    ).sortedByDescending { it.effect }.take(PatternsConfig.MAX_FINDINGS)
    return PatternsReport(days.size, findings)
}

/** Each meal's protein per day it was logged, averaged, over meals logged on enough days. */
private fun lowestProteinMeal(foods: List<LoggedMealNutrients>): PatternFinding.LowProteinMeal? {
    val byMeal = foods.groupBy { it.mealType }
        .mapValues { (_, mealFoods) -> mealFoods.groupBy { it.date }.values.map { day -> day.sumOf { it.proteinGrams } } }
        .filterValues { perDay -> perDay.size >= PatternsConfig.MIN_MEAL_DAYS }
        .mapValues { (_, perDay) -> perDay.average() }
        .entries.sortedBy { it.key.order }
    if (byMeal.size < 2) return null
    val lowest = byMeal.minBy { it.value }
    val highest = byMeal.maxBy { it.value }
    // Under half: exactly half does not show
    if (highest.value <= 0.0 || lowest.value >= highest.value * PatternsConfig.LOW_PROTEIN_MEAL_SHARE) return null
    return PatternFinding.LowProteinMeal(lowest.key, lowest.value, highest.key, highest.value)
}

/** The weekday whose days average the most calories, against the window's daily average. */
private fun highestCalorieWeekday(days: Map<LocalDate, List<LoggedMealNutrients>>): PatternFinding.HighCalorieWeekday? {
    val dayCalories = days.mapValues { (_, foods) -> foods.sumOf { it.calories } }
    val overall = dayCalories.values.average()
    val top = dayCalories.entries.groupBy { it.key.dayOfWeek }
        .filterValues { weekdayDays -> weekdayDays.size >= PatternsConfig.MIN_WEEKDAY_DAYS }
        .mapValues { (_, weekdayDays) -> weekdayDays.map { it.value }.average() }
        .entries.sortedBy { it.key.value }
        .maxByOrNull { it.value } ?: return null
    if (overall <= 0.0 || !reaches(top.value / overall - 1.0, PatternsConfig.HIGH_CALORIE_WEEKDAY_EXCESS)) return null
    return PatternFinding.HighCalorieWeekday(top.key, top.value, overall)
}

private fun sugarPeakWeekday(foods: List<LoggedMealNutrients>): PatternFinding.SugarPeakWeekday? =
    nutrientPeak(foods, { it.sugarGrams }, { it.date.dayOfWeek }, PatternsConfig.MIN_WEEKDAY_DAYS, { it.value })
        ?.let { (weekday, rate, overall) -> PatternFinding.SugarPeakWeekday(weekday, rate, overall) }

private fun sodiumPeakMeal(foods: List<LoggedMealNutrients>): PatternFinding.SodiumPeakMeal? =
    nutrientPeak(foods, { it.sodiumMg }, { it.mealType }, PatternsConfig.MIN_MEAL_DAYS, { it.order })
        ?.let { (meal, rate, overall) -> PatternFinding.SodiumPeakMeal(meal, rate, overall) }

/**
 * The group of [foods] (by [groupOf]) with the highest known [amount] per 1,000 kcal, its rate and
 * the overall rate, when it is far enough above. A group needs [minDays] counted days, and the
 * group and the whole window each need the foods stating [amount] to cover enough calories.
 */
private fun <K> nutrientPeak(
    foods: List<LoggedMealNutrients>,
    amount: (LoggedMealNutrients) -> Double?,
    groupOf: (LoggedMealNutrients) -> K,
    minDays: Int,
    order: (K) -> Int
): Triple<K, Double, Double>? {
    val overall = statedRate(foods, amount) ?: return null
    val peak = foods.groupBy(groupOf)
        .filterValues { groupFoods -> groupFoods.map { it.date }.distinct().size >= minDays }
        .mapNotNull { (key, groupFoods) -> statedRate(groupFoods, amount)?.let { key to it } }
        .sortedBy { order(it.first) }
        .maxByOrNull { it.second } ?: return null
    if (overall <= 0.0 || !reaches(peak.second / overall - 1.0, PatternsConfig.NUTRIENT_PEAK_EXCESS)) return null
    return Triple(peak.first, peak.second, overall)
}

/**
 * [amount] per 1,000 kcal over the foods that state it, or null when those cover under
 * [PatternsConfig.MIN_STATED_COVERAGE] of [foods]' calories. A food that does not state it is
 * left out, never counted as 0.
 */
private fun statedRate(foods: List<LoggedMealNutrients>, amount: (LoggedMealNutrients) -> Double?): Double? {
    val calories = foods.sumOf { it.calories }
    val stated = foods.mapNotNull { food -> amount(food)?.let { food.calories to it } }
    val statedCalories = stated.sumOf { it.first }
    if (calories <= 0.0 || statedCalories <= 0.0 || !reaches(statedCalories / calories, PatternsConfig.MIN_STATED_COVERAGE)) return null
    return stated.sumOf { it.second } * 1000.0 / statedCalories
}
