package com.macrobase.app.core.util

import java.util.Locale

/** Digits with at most one "." or "," separator; no exponents, hex or type suffixes such as "5d". */
private val DECIMAL_INPUT = Regex("""[+-]?(\d+([.,]\d*)?|[.,]\d+)""")

/**
 * Reads a decimal number typed by the user. Accepts "," as the decimal separator, as keyboards in
 * comma locales insert it ("72,5"). Blank, malformed, NaN and infinite input returns null.
 */
fun parseDecimalInput(text: String): Double? {
    val trimmed = text.trim()
    if (!DECIMAL_INPUT.matches(trimmed)) return null
    return trimmed.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
}

/** "2,000" or "2.000": digits in groups of three, which people type as a thousands separator. */
private val THOUSANDS_GROUPED = Regex("""\d{1,3}([.,]\d{3})+""")

/**
 * True when [text] looks like a whole number written with a thousands separator ("2,000"). For
 * a large whole amount it means 2000, not 2, so fields of that kind refuse it rather than save 2.
 */
fun hasThousandsSeparator(text: String): Boolean = THOUSANDS_GROUPED.matches(text.trim())

/**
 * Formats a number for an editable text field. Always uses "." (AGENTS.md section 3.1), so the
 * text reads back through [parseDecimalInput] in every locale.
 */
fun formatDecimalInput(value: Double, decimals: Int = 1): String =
    String.format(Locale.US, "%.${decimals}f", value)
