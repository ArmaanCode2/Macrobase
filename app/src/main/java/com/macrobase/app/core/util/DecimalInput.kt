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

/**
 * Formats a number for an editable text field. Always uses "." (AGENTS.md section 3.1), so the
 * text reads back through [parseDecimalInput] in every locale.
 */
fun formatDecimalInput(value: Double, decimals: Int = 1): String =
    String.format(Locale.US, "%.${decimals}f", value)
