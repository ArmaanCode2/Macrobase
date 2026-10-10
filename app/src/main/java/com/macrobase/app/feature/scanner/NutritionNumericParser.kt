package com.macrobase.app.feature.scanner

import com.macrobase.app.domain.model.scanner.ComparisonOperator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * High-fidelity numeric token parser tailored specifically for nutrition facts panels.
 * Preserves decimal points, comparison operators (<, >), percentage signs, and units without
 * destructive normalization or string stripping.
 */
object NutritionNumericParser {

    data class ParsedNumericToken(
        val rawText: String,
        val numericValue: Double,
        val operator: ComparisonOperator = ComparisonOperator.EXACT,
        val unit: String? = null,
        val isPercent: Boolean = false,
        val wasDecimalRecovered: Boolean = false,
        /** The printed kilojoules when [numericValue] was converted from kJ to kcal (BUG-019). */
        val convertedFromKj: Double? = null
    )

    /** "1,580" (grouped thousands, optionally with decimals) or a plain "12", "8.5", "8,5". */
    const val NUMBER_PATTERN = """[0-9]{1,3}(?:,[0-9]{3})+(?:\.[0-9]+)?|[0-9]+(?:[.,][0-9]+)?"""

    // Micro sign and Greek mu written as escapes so the source stays ASCII (AGENTS.md 3.2)
    private val NUMERIC_REGEX = Regex(
        """([<>]?)\s*($NUMBER_PATTERN)\s*(%|kcal|kj|cal|mg|mcg|\u00B5g|\u03BCg|gms|gm|g|ml)?""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Extracts all valid nutrition numeric tokens present in a raw text string.
     * Preserves exact raw substrings and boundaries.
     *
     * [unitHint] is the unit the row expects; it decides "1,580" when no unit is printed next
     * to the number (see [NutritionDigitCorrector.parseDecimal]).
     */
    fun extractTokens(text: String, unitHint: String? = null): List<ParsedNumericToken> {
        val matches = NUMERIC_REGEX.findAll(text)
        val tokens = mutableListOf<ParsedNumericToken>()

        for (match in matches) {
            val opStr = match.groupValues[1].trim()
            val numStr = match.groupValues[2].trim()
            val unitStr = match.groupValues[3].trim().lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }

            // Thousands separators are not decimal points: "1,580 mg" is 1580 mg (BUG-022)
            val num = NutritionDigitCorrector.parseDecimal(numStr, unitStr ?: unitHint) ?: continue
            val op = when (opStr) {
                "<" -> ComparisonOperator.LESS_THAN
                ">" -> ComparisonOperator.GREATER_THAN
                else -> ComparisonOperator.EXACT
            }
            val isPercent = unitStr == "%" || match.value.contains("%")

            tokens.add(
                ParsedNumericToken(
                    rawText = match.value.trim(),
                    numericValue = num,
                    operator = op,
                    unit = unitStr,
                    isPercent = isPercent
                )
            )
        }

        return tokens
    }

    /** A number on an energy row, with what is printed around it. */
    data class EnergyNumber(
        /** Unit attached to the number itself ("680kJ"), if any. */
        val unit: String?,
        /** The next word to its right ("kJ" in "1046 kJ"), if any. */
        val nextText: String?,
        /** Position of the number inside its OCR word, and how many numbers that word holds ("1046/250"). */
        val indexInWord: Int = 0,
        val numbersInWord: Int = 1,
        /** The number itself, so a kJ figure printed without its unit can be told apart by size. */
        val value: Double? = null
    )

    private val MASS_OR_VOLUME_UNITS = setOf("g", "gm", "gms", "mg", "mcg", "\u00B5g", "\u03BCg", "ml")

    /**
     * True for a number printed in grams, milligrams or millilitres. An energy row never holds
     * one, so such a number there belongs to something else, such as a "Per 100g" header just
     * above the row.
     */
    fun isMassOrVolume(unit: String?): Boolean = unit?.lowercase(Locale.ROOT) in MASS_OR_VOLUME_UNITS

    private val KJ_WORD = Regex("""\b(kj|kilojoules?)\b""", RegexOption.IGNORE_CASE)
    private val KCAL_WORD = Regex("""\b(kcal|cal|calories?)\b""", RegexOption.IGNORE_CASE)

    /**
     * The energy unit of each number on an energy row: "kj", "kcal", or null when the label does
     * not say. Labels print kJ and kcal side by side ("1046 kJ / 250 kcal"), only kJ (Australia,
     * New Zealand), or the units once in the row label ("Energy kJ/kcal 1046/250"); a kJ figure
     * must never be read as kcal (BUG-019).
     */
    fun energyUnits(rowText: String, numbers: List<EnergyNumber>): List<String?> {
        fun unitWord(text: String?): String? {
            // "k J": OCR sometimes splits the unit; callers pass the following word too
            val word = text?.trim()?.trimStart('(', '/', '|')?.replace(" ", "")?.lowercase(Locale.ROOT) ?: return null
            return when {
                word.startsWith("kj") || word.startsWith("kilojoule") -> "kj"
                word.startsWith("kcal") || word.startsWith("cal") -> "kcal"
                else -> null
            }
        }

        val explicit = numbers.map { unitWord(it.unit) ?: unitWord(it.nextText) }
        val units: MutableList<String?> = if (explicit.any { it != null }) {
            // Units printed next to the numbers win
            numbers.mapIndexed { i, n -> explicit[i] ?: pairedUnit(rowText, n) }.toMutableList()
        } else {
            // Units only in the row label: "Energy (kJ) 1046 523" or "Energy kJ/kcal 1046/250 523/125"
            val hasKj = KJ_WORD.containsMatchIn(rowText)
            val hasKcal = KCAL_WORD.containsMatchIn(rowText)
            numbers.map { n -> pairedUnit(rowText, n) ?: if (hasKj && !hasKcal) "kj" else null }.toMutableList()
        }
        return withKjKcalPairs(units, numbers, rowMentionsKj = KJ_WORD.containsMatchIn(rowText))
    }

    /**
     * Numbers still without a unit (units only in the column header, or OCR lost them) are told
     * apart by size: next to each other, kJ is about 4.18 times kcal ("1046 250").
     *
     * A per-100 g / per-serving kcal pair can have the same ratio (420 and 100 for a 24 g
     * serving), so this applies only when the row mentions kJ, or when two such pairs line up
     * ("1046 250 523 125").
     */
    private fun withKjKcalPairs(units: MutableList<String?>, numbers: List<EnergyNumber>, rowMentionsKj: Boolean): List<String?> {
        val pairs = mutableListOf<Pair<Int, Boolean>>() // start index, kJ first
        var i = 0
        while (i < numbers.size - 1) {
            val a = numbers[i].value
            val b = numbers[i + 1].value
            if (a != null && b != null && a > 0.0 && b > 0.0) {
                val kjThenKcal = units[i] == null && units[i + 1] != "kj" && a / b in KJ_PER_KCAL_RANGE
                val kcalThenKj = units[i] != "kj" && units[i + 1] == null && b / a in KJ_PER_KCAL_RANGE
                if (kjThenKcal || kcalThenKj) {
                    pairs.add(i to kjThenKcal)
                    i += 2
                    continue
                }
            }
            i++
        }
        if (rowMentionsKj || pairs.size >= 2) {
            for ((start, kjFirst) in pairs) {
                units[start] = if (kjFirst) "kj" else "kcal"
                units[start + 1] = if (kjFirst) "kcal" else "kj"
            }
        }
        return units
    }

    /** 4.184 kJ per kcal, with room for label rounding. */
    private val KJ_PER_KCAL_RANGE = 4.0..4.3

    /** "1046/250" under a "kJ/kcal" label: the label's order gives each number its unit. */
    private fun pairedUnit(rowText: String, n: EnergyNumber): String? {
        if (n.numbersInWord != 2) return null
        val kjAt = KJ_WORD.find(rowText)?.range?.first ?: return null
        val kcalAt = KCAL_WORD.find(rowText)?.range?.first ?: return null
        val kjFirst = kjAt < kcalAt
        return if ((n.indexInWord == 0) == kjFirst) "kj" else "kcal"
    }

    /**
     * The text after a number on a row, as [EnergyNumber.nextText]: the next word, joined with
     * the one after it when OCR split the unit ("k J", "k cal").
     */
    fun unitTextAfter(words: List<String>, index: Int): String? {
        val next = words.getOrNull(index + 1) ?: return null
        return if (next.equals("k", ignoreCase = true)) next + (words.getOrNull(index + 2) ?: "") else next
    }

    /**
     * Parses a single numeric token. Returns null if no valid number is present.
     */
    fun parseSingleToken(text: String): ParsedNumericToken? {
        val tokens = extractTokens(text)
        return tokens.firstOrNull()
    }

    /**
     * Checks if a serving value likely suffered a dropped decimal point
     * (e.g. "221" instead of "22.1" when 100g is "73.7" and serving size is 30g).
     */
    fun recoverServingDecimalLoss(
        per100gVal: Double,
        perServingVal: Double,
        servingGrams: Double
    ): Double? {
        if (perServingVal < 10.0 || per100gVal <= 0.0 || servingGrams <= 0.0) return null

        val expectedServing = per100gVal * servingGrams / 100.0
        val rawDiff = abs(expectedServing - perServingVal)
        val tol = max(1.5, expectedServing * 0.20)

        // If raw difference is huge, check if dividing by 10 restores mathematical consistency
        if (rawDiff > tol) {
            val candidateRecovered = (perServingVal / 10.0 * 100.0).roundToLong() / 100.0
            val candidateDiff = abs(expectedServing - candidateRecovered)
            val candidateTol = max(0.5, expectedServing * 0.12)

            if (candidateDiff <= candidateTol && rawDiff > 3.0 * candidateDiff) {
                return candidateRecovered
            }
        }

        return null
    }

    /**
     * Checks if a 100g value likely suffered a dropped decimal point
     * (e.g. "77" instead of "7.7" when serving is "2.3" and serving size is 30g).
     */
    fun recover100gDecimalLoss(
        per100gVal: Double,
        perServingVal: Double,
        servingGrams: Double
    ): Double? {
        if (per100gVal < 10.0 || perServingVal <= 0.0 || servingGrams <= 0.0) return null

        val expectedServing = per100gVal * servingGrams / 100.0
        val rawDiff = abs(expectedServing - perServingVal)
        val tol = max(1.5, perServingVal * 0.20)

        if (rawDiff > tol) {
            val candidateRecovered = (per100gVal / 10.0 * 100.0).roundToLong() / 100.0
            val candidateExpectedServing = candidateRecovered * servingGrams / 100.0
            val candidateDiff = abs(candidateExpectedServing - perServingVal)
            val candidateTol = max(0.5, perServingVal * 0.12)

            if (candidateDiff <= candidateTol && rawDiff > 3.0 * candidateDiff) {
                return candidateRecovered
            }
        }

        return null
    }
}
