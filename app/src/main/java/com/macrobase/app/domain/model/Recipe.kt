package com.macrobase.app.domain.model

import java.time.Instant

/**
 * Domain model representing a single ingredient item within a composite recipe.
 */
data class RecipeIngredient(
    val id: Long = 0,
    val food: Food,
    val serving: Serving,
    val quantity: Double
) {
    val nutrition: Nutrition
        get() {
            val multiplier = serving.calculateGramMultiplier(quantity)
            return food.nutrition.scale(multiplier)
        }
}

/**
 * Domain model representing a composite recipe.
 *
 * Implements automatic per-serving nutrition calculation across all ingredients.
 */
data class Recipe(
    val id: Long = 0,
    val uuid: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val servingsProduced: Int = 1,
    val ingredients: List<RecipeIngredient> = emptyList(),
    val instructions: String? = null,
    val createdAt: Instant = Instant.now()
) {
    /**
     * Total composite nutritional facts across all ingredients.
     */
    val totalNutrition: Nutrition
        get() = ingredients.fold(Nutrition.ZERO) { acc, ing -> acc + ing.nutrition }

    /**
     * Nutrition per single serving produced by this recipe.
     */
    val nutritionPerServing: Nutrition
        get() {
            val divisor = if (servingsProduced > 0) servingsProduced.toDouble() else 1.0
            return totalNutrition.scale(1.0 / divisor)
        }

    /**
     * Converts Recipe into standard Food model for diary logging.
     */
    fun toFood(): Food {
        val serving = Serving(
            id = 1,
            description = "1 of $servingsProduced servings",
            unit = ServingUnit.SERVING,
            quantity = 1.0,
            gramWeight = 100.0,
            isDefault = true
        )

        return Food(
            id = id,
            uuid = uuid,
            source = FoodSource.RECIPE,
            name = name,
            category = "My Recipes",
            foodType = FoodType.RECIPE_COMPOSITE,
            isUserOwned = true,
            nutrition = nutritionPerServing,
            servings = listOf(serving)
        )
    }
}
