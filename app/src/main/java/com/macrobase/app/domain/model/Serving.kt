package com.macrobase.app.domain.model

/**
 * Domain portion/serving model.
 *
 * Encapsulates the gram weight representation and calculation of scaling factors
 * relative to the standard 100g base.
 */
data class Serving(
    val id: Long = 0,
    val description: String,
    val unit: ServingUnit = ServingUnit.SERVING,
    val customUnitName: String? = null,
    val quantity: Double = 1.0,
    val gramWeight: Double = 0.0,
    val isDefault: Boolean = false,
    val sequence: Int = 0
) {
    val displayUnitName: String
        get() = if (unit == ServingUnit.CUSTOM && !customUnitName.isNullOrBlank()) {
            customUnitName
        } else {
            unit.displayName
        }

    /**
     * Calculates the scaling multiplier relative to the nutritional baseline
     * for a given user-entered portion quantity.
     */
    fun calculateGramMultiplier(userQuantity: Double): Double {
        if (userQuantity <= 0.0) return 0.0
        if (gramWeight > 0.0 && quantity > 0.0) {
            val totalGrams = (gramWeight / quantity) * userQuantity
            return totalGrams / 100.0
        }
        if (quantity > 0.0) {
            return userQuantity / quantity
        }
        return 1.0
    }

    /**
     * Returns total weight in grams for a given user portion quantity if known.
     */
    fun calculateTotalGrams(userQuantity: Double): Double {
        if (gramWeight <= 0.0) return 0.0
        if (quantity <= 0.0) return gramWeight * userQuantity
        return (gramWeight / quantity) * userQuantity
    }
}
