package com.macrobase.app.feature.scanner

import java.util.Locale

/**
 * Standard unit parser and normalizer for nutrition labels.
 */
object NutritionUnitParser {

    const val KJ_TO_KCAL_FACTOR = 4.184
    const val SALT_TO_SODIUM_FACTOR = 2.54 // 1g Salt ≈ 393.4mg Sodium

    /**
     * Normalizes raw unit string into standard canonical unit representation.
     */
    fun normalizeUnit(rawUnit: String?): String? {
        if (rawUnit.isNullOrBlank()) return null
        val lower = rawUnit.trim().lowercase(Locale.ROOT)

        return when {
            lower == "g" || lower == "gm" || lower == "gms" || lower == "gram" || lower == "grams" -> "g"
            lower == "mg" || lower == "rng" || lower == "mgs" || lower == "milligram" || lower == "milligrams" -> "mg"
            lower == "mcg" || lower == "µg" || lower == "μg" || lower == "microgram" || lower == "micrograms" -> "mcg"
            lower == "kcal" || lower == "cal" || lower == "calories" || lower == "calorie" -> "kcal"
            lower == "kj" || lower == "kilojoules" || lower == "kilojoule" -> "kJ"
            lower == "ml" || lower == "milliliter" || lower == "milliliters" -> "ml"
            lower == "%" || lower == "%rda" || lower == "%dv" || lower == "% daily value" -> "%"
            else -> lower
        }
    }

    /**
     * Converts value from one unit to another if compatible.
     */
    fun convertUnit(value: Double, fromUnit: String, toUnit: String): Double? {
        val normFrom = normalizeUnit(fromUnit) ?: return null
        val normTo = normalizeUnit(toUnit) ?: return null

        if (normFrom == normTo) return value

        return when {
            normFrom == "g" && normTo == "mg" -> value * 1000.0
            normFrom == "mg" && normTo == "g" -> value / 1000.0
            normFrom == "mg" && normTo == "mcg" -> value * 1000.0
            normFrom == "mcg" && normTo == "mg" -> value / 1000.0
            normFrom == "kJ" && normTo == "kcal" -> value / KJ_TO_KCAL_FACTOR
            normFrom == "kcal" && normTo == "kJ" -> value * KJ_TO_KCAL_FACTOR
            else -> null
        }
    }
}
