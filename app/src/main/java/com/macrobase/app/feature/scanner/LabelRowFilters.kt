package com.macrobase.app.feature.scanner

import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale

/**
 * Row clean-up shared by the parser and the spatial table detector, for US labels (BUG-041,
 * BUG-045).
 */
object LabelRowFilters {

    /**
     * "Calories from Fat 72" (or "Fat72"), with an optional unit, inside a longer line or on its
     * own. It never reaches past the end of a line.
     */
    private val CALORIES_FROM_FAT = Regex(
        """\b(?:calories|cal)\.?[ \t]+from[ \t]+fat(?![a-z])[ \t]*:?[ \t]*(?:[0-9]+(?:[.,][0-9]+)?[ \t]*(?:k?cal\b)?)?""",
        RegexOption.IGNORE_CASE
    )

    /**
     * The same OCR result without "Calories from Fat" segments. That figure is neither the
     * calories nor the fat, and OCR often merges it onto the "Calories 230" row, so only the
     * segment goes and the real calories stay (BUG-045).
     */
    fun withoutCaloriesFromFat(ocr: OcrResult): OcrResult {
        if (ocr.lines.none { CALORIES_FROM_FAT.containsMatchIn(it.text) } && !CALORIES_FROM_FAT.containsMatchIn(ocr.fullText)) {
            return ocr
        }
        val lines = ocr.lines.mapNotNull { line ->
            if (line.elements.isEmpty()) {
                val text = CALORIES_FROM_FAT.replace(line.text, " ").trim()
                if (text.isEmpty()) null else line.copy(text = text)
            } else {
                val kept = withoutCaloriesFromFat(line.elements)
                when {
                    kept.size == line.elements.size -> line
                    kept.isEmpty() -> null
                    else -> line.copy(text = kept.joinToString(" ") { it.text }, elements = kept)
                }
            }
        }
        return ocr.copy(fullText = CALORIES_FROM_FAT.replace(ocr.fullText, " "), lines = lines)
    }

    /** Drops each "Calories" "from" "Fat" word run with the number (and unit) after it. */
    private fun withoutCaloriesFromFat(elements: List<OcrElement>): List<OcrElement> {
        val kept = elements.toMutableList()
        var i = 0
        while (i + 2 < kept.size) {
            val first = word(kept[i]).trimEnd('.', ':')
            val third = word(kept[i + 2])
            if ((first == "calories" || first == "cal") && word(kept[i + 1]) == "from" && third.startsWith("fat")) {
                var end = i + 3
                // "Fat72" carries its number; otherwise the next word holds it
                if (third.none { it.isDigit() } && end < kept.size && kept[end].text.any { it.isDigit() }) end++
                if (end < kept.size && word(kept[end]).let { it == "kcal" || it == "cal" }) end++
                repeat(end - i) { kept.removeAt(i) }
            } else {
                i++
            }
        }
        return kept
    }

    private fun word(element: OcrElement) = element.text.trim().lowercase(Locale.ROOT)

    /**
     * A line of the "% Daily Value" footnote ("Calories: 2,000 2,500", "based on a 2,000 calorie
     * diet", "2,000 calories a day is used..."), whose figures are never the label's own values
     * (BUG-045). The "% Daily Value" column header is not part of it.
     */
    fun isDailyValueFootnote(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        val bothReferenceDiets = REFERENCE_2000.containsMatchIn(lower) && REFERENCE_2500.containsMatchIn(lower)
        return bothReferenceDiets || FOOTNOTE_WORDS.any { lower.contains(it) }
    }

    private val REFERENCE_2000 = Regex("""(?<![0-9.,])2,?000(?![0-9])""")
    private val REFERENCE_2500 = Regex("""(?<![0-9.,])2,?500(?![0-9])""")
    private val FOOTNOTE_WORDS = listOf(
        "daily values are based", "percent daily values", "calorie diet", "calories a day", "daily value (dv) tells"
    )

    /** "Energy from fat", "Calories from saturated fat": a "from" in the label, before any number. */
    fun hasFromLabel(text: String): Boolean =
        FROM_WORD.containsMatchIn(text.lowercase(Locale.ROOT).split(FIRST_DIGIT, limit = 2)[0])

    private val FIRST_DIGIT = Regex("[0-9]")
    private val FROM_WORD = Regex("""\bfrom\b""")

    /**
     * The words of "Serving Size: 30g", "Total Servings : 16", "Servings per pack" and "Serving
     * suggestion", which describe the pack and are never a column header. ML Kit reports one word
     * per element, so "Serving" alone looks like a column (BUG-015). A phrase ends at the next
     * column header's word or at the wide gap before it. Matched by identity, like
     * [numbersBeforePercentSign].
     */
    fun servingPhraseWords(elements: List<OcrElement>): Set<OcrElement> {
        val phrase = Collections.newSetFromMap(IdentityHashMap<OcrElement, Boolean>())
        var i = 0
        while (i < elements.size) {
            val current = bareWord(elements[i])
            val next = elements.getOrNull(i + 1)?.let(::bareWord).orEmpty()
            val starts = current == "servings" || (current == "serving" && (next == "size" || next == "suggestion")) ||
                (current == "total" && next.startsWith("serving"))
            if (!starts) {
                i++
                continue
            }
            phrase.add(elements[i])
            i++
            while (i < elements.size && !isColumnWord(bareWord(elements[i])) && !isColumnGap(elements[i - 1], elements[i])) {
                phrase.add(elements[i])
                i++
            }
        }
        return phrase
    }

    private fun bareWord(element: OcrElement) = word(element).trim(':', '.', ',', '(', ')', '*')

    private fun isColumnWord(word: String) = word == "per" || word.startsWith("100") || word.contains("rda") || word.contains("%")

    private fun isColumnGap(left: OcrElement, right: OcrElement): Boolean {
        val first = left.spatialBounds ?: return false
        val second = right.spatialBounds ?: return false
        return second.left - first.right > 2 * first.height
    }

    /**
     * [elements] in reading order, with an amount and the unit after it read as one word: ML Kit
     * reports "100 ml" as "100" and "ml", which no test of a single word can recognise (BUG-014).
     */
    fun withSplitAmountsJoined(elements: List<OcrElement>): List<OcrElement> {
        val joined = mutableListOf<OcrElement>()
        var i = 0
        while (i < elements.size) {
            val current = elements[i]
            val next = elements.getOrNull(i + 1)
            if (next != null && isSplitAmount(current, next)) {
                joined.add(current.copy(text = current.text.trim() + next.text.trim(), spatialBounds = boundsOf(current, next)))
                i += 2
            } else {
                joined.add(current)
                i++
            }
        }
        return joined
    }

    private fun isSplitAmount(amount: OcrElement, unit: OcrElement): Boolean {
        if (!AMOUNT_WORD.matches(amount.text.trim()) || !AMOUNT_UNIT_WORD.matches(unit.text.trim().lowercase(Locale.ROOT))) return false
        val first = amount.spatialBounds ?: return true
        val second = unit.spatialBounds ?: return true
        // Reading order runs on to the next line, whose first word starts back at the left
        val sameLine = kotlin.math.abs(first.centerY - second.centerY) < first.height / 2
        return sameLine && second.left - first.right <= first.height
    }

    private fun boundsOf(first: OcrElement, second: OcrElement): NutritionLabelParser.SpatialBox? {
        val a = first.spatialBounds ?: return second.spatialBounds
        val b = second.spatialBounds ?: return a
        return NutritionLabelParser.SpatialBox(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
    }

    private val AMOUNT_WORD = Regex("""\(?[0-9]+(?:\.[0-9]+)?""")
    private val AMOUNT_UNIT_WORD = Regex("""(?:g|gm|gms|ml)\)?:?""")

    /**
     * Words followed by a separate "%" word ("7" "%"): those numbers are %DV or %RI figures,
     * never values (BUG-041). Matched by identity, since two words can read the same.
     */
    fun numbersBeforePercentSign(lines: List<OcrLine>): Set<OcrElement> {
        val result = Collections.newSetFromMap(IdentityHashMap<OcrElement, Boolean>())
        for (line in lines) {
            line.elements.zipWithNext { current, next ->
                if (next.text.trim().startsWith("%")) result.add(current)
            }
        }
        return result
    }
}
