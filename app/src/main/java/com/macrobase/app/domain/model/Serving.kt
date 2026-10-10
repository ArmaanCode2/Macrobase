package com.macrobase.app.domain.model

import java.util.Locale
import kotlin.math.roundToInt

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
     * True when this portion has a real gram weight, so it can be scaled
     * against per-100g nutrition.
     */
    val hasKnownWeight: Boolean
        get() = gramWeight > 0.0

    /**
     * Picker label that shows what a household portion weighs, e.g. "1 plate (208 g)".
     * Gram servings and descriptions that already state their grams are left unchanged.
     * Only meaningful for catalog servings; UI uses [Food.servingLabel].
     */
    val labelWithWeight: String
        get() {
            if (!hasKnownWeight || unit == ServingUnit.GRAMS || unit == ServingUnit.KILOGRAMS) return description
            if (GRAMS_IN_DESCRIPTION.containsMatchIn(description)) return description
            val formatted = if (gramWeight.roundToInt() < 10) String.format(Locale.US, "%.1f", gramWeight) else gramWeight.roundToInt().toString()
            return "$description ($formatted g)"
        }

    /**
     * Catalog portions logged before they had gram weights were calculated as one
     * 100 g unit each (every such portion had quantity 1). Restores that basis so a
     * legacy diary snapshot keeps scaling exactly as it was logged.
     */
    fun withLegacyCatalogWeight(): Serving =
        if (hasKnownWeight) this else copy(gramWeight = LEGACY_CATALOG_PORTION_GRAMS)

    /**
     * Calculates the scaling multiplier relative to the nutritional baseline
     * for a given user-entered portion quantity.
     *
     * The quantity-only fallback for weightless servings serves custom and snapshot-backed
     * foods; catalog foods never reach it (see CalculateNutritionForServingUseCase).
     */
    fun calculateGramMultiplier(userQuantity: Double): Double {
        if (userQuantity <= 0.0) return 0.0
        val effectiveQuantity = if (quantity == 100.0 && gramWeight == 100.0) 1.0 else quantity
        if (gramWeight > 0.0 && effectiveQuantity > 0.0) {
            val totalGrams = (gramWeight / effectiveQuantity) * userQuantity
            return totalGrams / 100.0
        }
        if (effectiveQuantity > 0.0) {
            return userQuantity / effectiveQuantity
        }
        return 1.0
    }

    /**
     * Returns total weight in grams for a given user portion quantity if known.
     */
    fun calculateTotalGrams(userQuantity: Double): Double {
        if (gramWeight <= 0.0) return 0.0
        val effectiveQuantity = if (quantity == 100.0 && gramWeight == 100.0) 1.0 else quantity
        if (effectiveQuantity <= 0.0) return gramWeight * userQuantity
        return (gramWeight / effectiveQuantity) * userQuantity
    }

    companion object {
        const val LEGACY_CATALOG_PORTION_GRAMS = 100.0
        private val GRAMS_IN_DESCRIPTION = Regex("""\d\s*g\b""", RegexOption.IGNORE_CASE)
    }
}
