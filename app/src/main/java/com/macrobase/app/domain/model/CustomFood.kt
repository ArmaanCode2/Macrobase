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
            servings = buildServings()
        )
    }

    /**
     * The entered serving plus a single-unit serving ("1 g" / "1 ml") for metric units.
     *
     * Sub-portions scale by gramWeight against the base serving, so metric bases carry
     * their size in base units: grams for g/kg, millilitres for ml/L (1 ml counts as 1 unit;
     * only the ratio between servings is used).
     */
    fun buildServings(): List<Serving> {
        val unitLabel = if (servingUnit == ServingUnit.CUSTOM && !customUnitName.isNullOrBlank()) {
            customUnitName
        } else {
            servingUnit.displayName
        }
        val baseUnitAmount = when (servingUnit) {
            ServingUnit.GRAMS, ServingUnit.MILLILITERS -> servingSize
            ServingUnit.KILOGRAMS, ServingUnit.LITERS -> servingSize * 1000.0
            else -> 0.0
        }
        val baseServing = Serving(
            id = 1,
            description = if (servingSize % 1.0 == 0.0) "${servingSize.toInt()} $unitLabel" else "$servingSize $unitLabel",
            unit = servingUnit,
            customUnitName = customUnitName,
            quantity = 1.0,
            gramWeight = baseUnitAmount,
            isDefault = true
        )
        val singleUnit = when (servingUnit) {
            ServingUnit.GRAMS, ServingUnit.KILOGRAMS -> ServingUnit.GRAMS
            ServingUnit.MILLILITERS, ServingUnit.LITERS -> ServingUnit.MILLILITERS
            else -> null
        }
        if (singleUnit == null || baseUnitAmount <= 1.0) return listOf(baseServing)
        return listOf(
            baseServing,
            Serving(
                id = 2,
                description = "1 ${singleUnit.displayName}",
                unit = singleUnit,
                customUnitName = customUnitName,
                quantity = 1.0,
                gramWeight = 1.0,
                isDefault = false
            )
        )
    }
}
