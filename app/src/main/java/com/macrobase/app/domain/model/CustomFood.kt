package com.macrobase.app.domain.model

import java.time.Instant

/**
 * Domain model representing a user-created custom food.
 */
data class CustomFood(
    val id: Long = 0,
    val uuid: String,
    val name: String,
    val brand: String? = null,
    val servingSize: Double,
    val servingUnit: ServingUnit = ServingUnit.SERVING,
    val customUnitName: String? = null,
    val nutritionPerServing: Nutrition,
    val createdAt: Instant = Instant.now()
) {
    /**
     * Converts custom food into standard Food domain representation.
     */
    fun toFood(): Food {
        val unitLabel = if (servingUnit == ServingUnit.CUSTOM && !customUnitName.isNullOrBlank()) {
            customUnitName
        } else {
            servingUnit.displayName
        }

        val serving = Serving(
            id = 1,
            description = "$servingSize $unitLabel",
            unit = servingUnit,
            customUnitName = customUnitName,
            quantity = servingSize,
            gramWeight = when (servingUnit) {
                ServingUnit.GRAMS -> servingSize
                ServingUnit.KILOGRAMS -> servingSize * 1000.0
                else -> 0.0
            },
            isDefault = true
        )

        return Food(
            id = id,
            uuid = uuid,
            source = FoodSource.CUSTOM_USER,
            name = name,
            brand = brand,
            category = "Custom Foods",
            foodType = FoodType.CUSTOM,
            isUserOwned = true,
            nutrition = nutritionPerServing,
            servings = listOf(serving)
        )
    }
}
