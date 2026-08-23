package com.macrobase.app.domain.usecase

import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.repository.FoodRepository

/**
 * Use case to search foods across all registered data providers and custom user foods.
 */
class SearchFoodsUseCase(
    private val foodRepository: FoodRepository
) {
    suspend operator fun invoke(query: String, limit: Int = 30): List<Food> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            return emptyList()
        }
        return foodRepository.searchFoods(trimmed, limit)
    }
}

/**
 * Use case to retrieve detailed information for a food item by its internal ID.
 */
class GetFoodDetailsUseCase(
    private val foodRepository: FoodRepository
) {
    suspend operator fun invoke(foodId: Long): Food? {
        return foodRepository.getFoodById(foodId)
    }
}

/**
 * Use case to retrieve all available portion/serving options for a specific food.
 */
class GetFoodServingsUseCase(
    private val foodRepository: FoodRepository
) {
    suspend operator fun invoke(foodId: Long): List<Serving> {
        return foodRepository.getFoodServings(foodId)
    }
}

/**
 * Pure business logic use case to calculate scaled nutritional values for a selected portion.
 * Does not depend on UI or database.
 */
class CalculateNutritionForServingUseCase {
    operator fun invoke(food: Food, serving: Serving, userQuantity: Double): Nutrition {
        if (userQuantity <= 0.0) return Nutrition.ZERO
        val multiplier = if (food.isUserOwned || food.source == com.macrobase.app.domain.model.FoodSource.CUSTOM_USER) {
            if (serving.quantity > 0.0) userQuantity / serving.quantity else userQuantity
        } else {
            serving.calculateGramMultiplier(userQuantity)
        }
        return food.nutrition.scale(multiplier)
    }
}
