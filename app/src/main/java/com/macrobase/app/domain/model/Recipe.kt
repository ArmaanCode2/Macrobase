package com.macrobase.app.domain.model

import java.time.Instant

/**
 * Domain model representing a single ingredient item within a composite recipe.
 */
data class RecipeIngredient(
    val id: Long = 0,
    val food: Food,
    val serving: Serving,
    val quantity: Double,
    /**
     * Nutrition of one unit of [quantity], saved with the recipe so later edits to the
     * source food never change a saved recipe.
     */
    val snapshotPerUnit: Nutrition? = null
) {
    val nutrition: Nutrition
        get() = snapshotPerUnit?.scale(quantity) ?: food.nutritionFor(serving, quantity)
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
    val createdAt: Instant = Instant.now(),
    /**
     * Per-serving nutrition stored with the recipe. Used only when the ingredient list is
     * unavailable: recipes saved before ingredients were persisted kept just these values.
     */
    val savedNutritionPerServing: Nutrition? = null
) {
    private val servingsDivisor: Double
        get() = if (servingsProduced > 0) servingsProduced.toDouble() else 1.0

    /**
     * Total composite nutritional facts across all ingredients.
     */
    val totalNutrition: Nutrition
        get() {
            if (ingredients.isEmpty() && savedNutritionPerServing != null) {
                return savedNutritionPerServing.scale(servingsDivisor)
            }
            return ingredients.fold(Nutrition.ZERO) { acc, ing -> acc + ing.nutrition }
        }

    /**
     * Nutrition per single serving produced by this recipe.
     */
    val nutritionPerServing: Nutrition
        get() {
            if (ingredients.isEmpty() && savedNutritionPerServing != null) return savedNutritionPerServing
            return totalNutrition.scale(1.0 / servingsDivisor)
        }

    /**
     * Converts Recipe into standard Food model for diary logging.
     */
    fun toFood(): Food {
        val serving = Serving(
            id = 1,
            // Same label as diary entries for recipes, so a logged serving matches this one
            description = "Serving",
            unit = ServingUnit.SERVING,
            quantity = 1.0,
            gramWeight = 100.0,
            isDefault = true
        )

        return Food(
            id = FOOD_ID_OFFSET + id,
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

    companion object {
        /**
         * Logged recipes get food ids in their own range (AGENTS.md section 4.2), so a diary
         * entry for recipe #1 can never be mistaken for custom food #1. Entries logged by
         * earlier versions used the raw recipe id.
         */
        const val FOOD_ID_OFFSET = 200_000_000L
        const val FOOD_ID_RANGE_END = FOOD_ID_OFFSET + 100_000_000L

        /** Food id for a restored recipe entry whose recipe could not be identified. */
        const val UNLINKED_FOOD_ID = FOOD_ID_OFFSET

        /** Recipe row id for a food id in the recipe range, or null for any other food id. */
        fun rowIdOrNull(foodId: Long): Long? =
            if (foodId > FOOD_ID_OFFSET && foodId < FOOD_ID_RANGE_END) foodId - FOOD_ID_OFFSET else null
    }
}
