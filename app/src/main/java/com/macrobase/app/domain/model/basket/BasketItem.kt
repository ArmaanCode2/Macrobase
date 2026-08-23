package com.macrobase.app.domain.model.basket

import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Serving
import java.time.Instant
import java.time.LocalDate

/**
 * Represents a pending food item in the centralized basket, before it is committed to the diary.
 * Snapshots the required nutrition information to prevent silent historical changes if the
 * underlying food is edited before submission.
 */
data class BasketItem(
    val id: String, // Stable basket item ID (UUID)
    val food: Food,
    val foodNameSnapshot: String,
    val brandSnapshot: String?,
    val quantity: Double,
    val serving: Serving,
    val calculatedNutrition: Nutrition,
    val date: LocalDate,
    val mealType: MealType,
    val createdAt: Instant
) {
    // Convenience helper to construct from domain models
    companion object {
        fun create(
            food: Food,
            serving: Serving,
            quantity: Double,
            calculatedNutrition: Nutrition,
            date: LocalDate,
            mealType: MealType
        ): BasketItem {
            return BasketItem(
                id = java.util.UUID.randomUUID().toString(),
                food = food,
                foodNameSnapshot = food.name,
                brandSnapshot = food.brand,
                quantity = quantity,
                serving = serving,
                calculatedNutrition = calculatedNutrition,
                date = date,
                mealType = mealType,
                createdAt = Instant.now()
            )
        }
    }
}
