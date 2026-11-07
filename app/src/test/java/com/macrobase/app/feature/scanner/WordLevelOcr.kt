package com.macrobase.app.feature.scanner

/**
 * OCR output shaped the way ML Kit reports it: every word is its own element with its own box, so
 * "Per 100 ml" is three elements and "Serving Size: 30g" is three more. Fixtures that put a whole
 * cell in one element hide what the parser really has to deal with (BUG-014, BUG-015).
 *
 * A row is written as cells at x positions; a cell's words get [CHAR_WIDTH] pixels per character and
 * one space between them. Rows are [LINE_HEIGHT] tall, [PITCH] apart unless a top is given.
 */
internal object WordLevelOcr {
    const val CHAR_WIDTH = 14
    const val LINE_HEIGHT = 30
    const val PITCH = 40

    /** A column in a table fixture: where its cells start. */
    data class Cell(val text: String, val x: Int)

    infix fun String.at(x: Int) = Cell(this, x)

    /** One OCR line at [top] holding [cells]: every word of every cell is one element. */
    fun line(top: Int, vararg cells: Cell, height: Int = LINE_HEIGHT): OcrLine {
        val words = cells.flatMap { cell ->
            var x = cell.x
            cell.text.trim().split(Regex("\\s+")).map { word ->
                val box = NutritionLabelParser.SpatialBox(x, top, x + word.length * CHAR_WIDTH, top + height)
                x += (word.length + 1) * CHAR_WIDTH
                OcrElement(text = word, spatialBounds = box, confidence = 0.95f)
            }
        }
        return OcrLine(
            text = words.joinToString(" ") { it.text },
            elements = words,
            confidence = 0.95f,
            spatialBounds = NutritionLabelParser.SpatialBox(
                words.minOf { it.spatialBounds!!.left },
                top,
                words.maxOf { it.spatialBounds!!.right },
                top + height
            )
        )
    }

    /** One OCR line per cell, as ML Kit gives when a table's columns are far apart. */
    fun linesPerCell(top: Int, vararg cells: Cell, height: Int = LINE_HEIGHT): List<OcrLine> =
        cells.map { line(top, it, height = height) }

    fun ocr(lines: List<OcrLine>) = OcrResult(lines.joinToString("\n") { it.text }, emptyList(), lines)

    fun ocr(vararg lines: OcrLine) = ocr(lines.toList())

    /** A plain label: each string is one printed line, its words left to right, one [PITCH] apart. */
    fun lines(vararg printed: String): OcrResult =
        ocr(printed.mapIndexed { i, text -> line(40 + i * PITCH, text at 20) })

    /** [printed] as a table: each row is its cells at [columns], one [PITCH] apart from [firstTop]. */
    fun table(columns: List<Int>, printed: List<List<String>>, firstTop: Int = 40): List<OcrLine> =
        printed.mapIndexed { i, row ->
            val cells = row.mapIndexedNotNull { c, text -> text.takeIf { it.isNotEmpty() }?.let { it at columns[c] } }
            line(firstTop + i * PITCH, *cells.toTypedArray())
        }
}
