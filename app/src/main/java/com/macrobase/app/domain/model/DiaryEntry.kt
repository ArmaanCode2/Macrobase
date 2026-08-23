package com.macrobase.app.domain.model

import java.time.Instant
import java.time.LocalDate

/**
 * Domain model representing an individual food item logged to a user meal diary.
 */
data class DiaryEntry(
    val id: Long = 0,
    val uuid: String,
    val date: LocalDate,
    val mealType: MealType,
    val food: Food,
    val serving: Serving,
    val quantity: Double,
    val calculatedNutrition: Nutrition,
    val loggedAt: Instant = Instant.now()
) {
    val loggedCalories: Double
        get() = calculatedNutrition.calories

    val loggedProtein: Double
        get() = calculatedNutrition.proteinGrams

    val loggedCarbs: Double
        get() = calculatedNutrition.carbsGrams

    val loggedFat: Double
        get() = calculatedNutrition.fatGrams
}
