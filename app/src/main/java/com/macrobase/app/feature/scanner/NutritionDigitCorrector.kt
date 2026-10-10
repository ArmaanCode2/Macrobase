package com.macrobase.app.feature.scanner

/**
 * Context-aware OCR digit correction and localized decimal parser.
 * Only applies character substitutions within verified numeric/unit contexts,
 * never altering arbitrary food or brand names.
 */
object NutritionDigitCorrector {

    /** "1,580" or "12,500,000": a comma before every group of three digits. */
    private val THOUSANDS_GROUPED = Regex("""[1-9][0-9]{0,2}(?:,[0-9]{3})+""")

    /** Units whose label values reach the thousands, where "1,580" is never 1.58. */
    private val LARGE_VALUE_UNITS = setOf("mg", "mcg", "\u00B5g", "\u03BCg", "kj", "kcal", "cal")

    /** Units where "1,500" is a decimal comma (1.5 g); a thousand grams per serving is not a label value. */
    private val GRAM_UNITS = setOf("g", "gm", "gms", "gram", "grams", "ml")

    /**
     * Parses a localized decimal number with context-aware comma/dot disambiguation.
     * Supports: "8", "8.5", "8,5", "0.5", "0,5", "1,234.5", "1.234,5", "1,580", "< 0.5".
     *
     * [unit] (the unit printed next to the number, or the unit the row expects) settles
     * "1,580": thousands for mg, kJ and kcal (BUG-022), a decimal comma for grams.
     */
    fun parseDecimal(raw: String, unit: String? = null): Double? {
        val trimmed = raw.trim().removePrefix("<").removePrefix(">").trim()
        if (trimmed.isEmpty()) return null

        // Disambiguate commas:
        // 1. If string contains both '.' and ',' (e.g. "1,234.5" vs "1.234,5")
        if (trimmed.contains('.') && trimmed.contains(',')) {
            val lastDot = trimmed.lastIndexOf('.')
            val lastComma = trimmed.lastIndexOf(',')
            return if (lastDot > lastComma) {
                // Standard US/UK: comma is thousands, dot is decimal (1,234.5)
                trimmed.replace(",", "").toDoubleOrNull()
            } else {
                // European: dot is thousands, comma is decimal (1.234,5)
                trimmed.replace(".", "").replace(',', '.').toDoubleOrNull()
            }
        }

        // 2. Only commas: thousands ("1,580") or a decimal comma ("25,7", "0,125")
        if (trimmed.contains(',')) {
            if (THOUSANDS_GROUPED.matches(trimmed)) {
                val asThousands = trimmed.replace(",", "").toDoubleOrNull()
                if (trimmed.count { it == ',' } > 1) return asThousands
                val asDecimal = trimmed.replace(',', '.').toDoubleOrNull()
                val unitKey = unit?.trim()?.lowercase(java.util.Locale.ROOT)
                return when {
                    unitKey in LARGE_VALUE_UNITS -> asThousands
                    unitKey in GRAM_UNITS -> asDecimal
                    // Unknown unit: large readings are thousands, small ones decimals
                    else -> if ((asThousands ?: 0.0) >= 500.0) asThousands else asDecimal
                }
            }
            return trimmed.replace(',', '.').toDoubleOrNull()
        }

        return trimmed.toDoubleOrNull()
    }

    /**
     * Context-aware normalization of OCR misread characters in a nutrient row string.
     */
    fun correctRowText(text: String): String {
        var corrected = text

        // 1. Common unit misreads (rng -> mg)
        corrected = corrected.replace(Regex("""(?i)\brng\b"""), "mg")
        corrected = corrected.replace(Regex("""(?i)\b([0-9]+(?:[.,][0-9]+)?)\s*(?:\u00B5g|\u03BCg)\b"""), "$1 mcg")

        // 2. Misread letter 'O'/'o' for digit '0' in numeric tokens
        // e.g. "40O" -> "400", "3Og" -> "30g", "10O" -> "100", "0.O" -> "0.0", "O.5" -> "0.5"
        corrected = corrected.replace(Regex("""(?<=\s|^|\b)[Oo]\.([0-9]+)"""), "0.$1")
        corrected = corrected.replace(Regex("""([0-9]+)\.[Oo](?=\s|g|mg|kcal|kJ|%|$)"""), "$1.0")
        corrected = corrected.replace(Regex("""(?<=\d)[Oo](?=\s|[a-zA-Z]|%|$)"""), "0")
        corrected = corrected.replace(Regex("""\b([0-9]+)[Oo]([a-zA-Z]*)\b"""), "$10$2")

        // 3. Misread letter 'I' or 'l' for digit '1'
        // e.g. "I.5" -> "1.5", "l.2" -> "1.2", "l90" -> "190"
        corrected = corrected.replace(Regex("""(?<=\s|^|\b)[Il]\.([0-9]+)"""), "1.$1")
        corrected = corrected.replace(Regex("""(?<=\s|^|\b)[Il]([0-9]+)"""), "1$1")

        // 4. Misread letter 'S' for '5' when directly followed by units or digits
        // e.g. "S0 mg" -> "50 mg", "5Og" -> "50g"
        corrected = corrected.replace(Regex("""(?<=\s|^|\b)[Ss]([0-9]+)"""), "5$1")

        // 5. Misread letter 'B' for '8' in numeric tokens
        corrected = corrected.replace(Regex("""(?<=\s|^|\b)[Bb]\.([0-9]+)"""), "8.$1")

        return corrected
    }
}
