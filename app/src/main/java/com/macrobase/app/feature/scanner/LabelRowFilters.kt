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
