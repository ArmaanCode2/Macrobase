package com.macrobase.app.domain.model

/**
 * Origin source classification of a food entry.
 */
enum class FoodSource(val identifier: String) {
    BUILT_IN("BUILT_IN"),
    CUSTOM_USER("CUSTOM_USER"),
    OPEN_FOOD_FACTS("OPEN_FOOD_FACTS"),
    RECIPE("RECIPE"),
    THIRD_PARTY("THIRD_PARTY");

    companion object {
        fun fromIdentifier(id: String?): FoodSource {
            return entries.firstOrNull { it.identifier.equals(id, ignoreCase = true) } ?: BUILT_IN
        }
    }
}

/**
 * Functional category / type of food.
 */
enum class FoodType {
    GENERIC,
    BRANDED,
    CUSTOM,
    RECIPE_COMPOSITE
}

/**
 * Clean, provider-agnostic domain model representing a food.
 *
 * Fully decoupled from SQLite, Room, and USDA JSON structures.
 */
data class Food(
    val id: Long,
    val uuid: String,
    val source: FoodSource = FoodSource.BUILT_IN,
    val sourceId: String? = null,
    val name: String,
    val normalizedName: String = name.lowercase().trim(),
    val brand: String? = null,
    val category: String? = null,
    val foodType: FoodType = FoodType.GENERIC,
    val barcode: String? = null,
    val isUserOwned: Boolean = false,
    val servingBasis: String = "100g",
    val nutrition: Nutrition,
    val servings: List<Serving> = emptyList()
) {
    val defaultServing: Serving?
        get() = servings.firstOrNull { it.isDefault } ?: servings.firstOrNull()
}
