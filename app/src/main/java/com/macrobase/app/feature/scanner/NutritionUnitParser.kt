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
            lower == "ug" -> "mcg"
            lower == "g" || lower == "gm" || lower == "gms" || lower == "gram" || lower == "grams" -> "g"
            lower == "mg" || lower == "rng" || lower == "mgs" || lower == "milligram" || lower == "milligrams" -> "mg"
            lower == "mcg" || lower == "\u00b5g" || lower == "\u03bcg" || lower == "microgram" || lower == "micrograms" -> "mcg"
            lower == "kcal" || lower == "cal" || lower == "calories" || lower == "calorie" -> "kcal"
            lower == "kj" || lower == "kilojoules" || lower == "kilojoule" -> "kJ"
            lower == "ml" || lower == "milliliter" || lower == "milliliters" -> "ml"
            lower == "%" || lower == "%rda" || lower == "%dv" || lower == "% daily value" -> "%"
            else -> lower
        }
    }

    /** No food has this many grams of a nutrient labelled in mg: OCR lost the "m" of "mg". */
    private const val MAX_GRAMS_OF_A_MILLIGRAM_NUTRIENT = 40.0

    /**
     * A number from a label in the unit its row expects, or null when the two cannot be converted,
     * such as millilitres of protein. [printed] is the number's own unit, or the row label's; with
     * none the number is taken to be in [expected] already (BUG-017).
     */
    fun toExpectedUnit(value: Double, printed: String?, expected: String): Double? {
        val from = normalizeUnit(printed) ?: return value
        if (from == "g" && normalizeUnit(expected) == "mg" && value > MAX_GRAMS_OF_A_MILLIGRAM_NUTRIENT) return value
        return convertUnit(value, from, expected)
    }

    /**
     * A word that is only a mass unit, such as "g", "(g)" or "mg,": the number before it is in
     * that unit, for labels that print a space between them.
     */
    fun massUnitOf(word: String?): String? {
        val text = word?.trim()?.trim('(', ')', ',', ';', ':', '.')?.lowercase(Locale.ROOT) ?: return null
        return text.takeIf { it in MASS_UNIT_WORDS }
    }

    private val MASS_UNIT_WORDS = setOf("g", "gm", "gms", "mg", "mcg", "\u00b5g", "\u03bcg", "ug")

    /** "(g)" or "(mg)" in a row's label, before its first number: the unit of that row's numbers. */
    fun labelUnitOf(rowText: String): String? =
        LABEL_UNIT.find(rowText.lowercase(Locale.ROOT).takeWhile { !it.isDigit() })?.groupValues?.get(1)

    private val LABEL_UNIT = Regex("""\(\s*(g|gm|gms|mg|mcg|\u00b5g|\u03bcg|ug)\s*\)""")

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
