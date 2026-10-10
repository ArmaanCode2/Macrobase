package com.macrobase.app.feature.scanner

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Semantic basis of a detected table column.
 */
enum class ColumnBasis {
    PER_100_G,
    PER_100_ML,
    PER_SERVING,
    DAILY_VALUE,
    UNKNOWN
}

/**
 * Geometric and semantic representation of a column in a nutrition table.
 */
data class TableColumn(
    val index: Int,
    val minX: Int,
    val maxX: Int,
    val centerX: Int,
    val headerText: String,
    val basis: ColumnBasis
)

/**
 * Extracted value cell in a reconstructed table row.
 */
data class TableRowCell(
    val text: String,
    val rawNumber: Double?,
    val unit: String?,
    val isDailyValue: Boolean,
    val bounds: NutritionLabelParser.SpatialBox?,
    val columnIndex: Int? = null,
    val sourceElement: OcrElement? = null
)

/**
 * Horizontally unified row containing a nutrient label and one or more value cells.
 */
data class TableGridRow(
    val fullRowText: String,
    val labelText: String,
    val bounds: NutritionLabelParser.SpatialBox?,
    val yCenter: Int,
    val cells: List<TableRowCell>,
    val cellsByColumn: Map<Int, TableRowCell> = emptyMap()
)

/**
 * Complete 2D tabular representation of the nutrition facts panel.
 */
data class TableGrid(
    val columns: List<TableColumn>,
    val rows: List<TableGridRow>
)

/**
 * 2D Bounding-Box Geometry & Table Reconstruction Engine.
 * Reconstructs visual table rows from OCR bounding boxes, discovers columns via X-coordinate
 * clustering and header analysis, and maps numbers strictly to their corresponding columns.
 */
class TableLayoutReconstructor {

    companion object {
        private val PER_100G_HEADERS = listOf("per 100g", "per 100 g", "per 100 gm", "per 100ml", "per 100 ml", "qty/100g", "100g", "100 g")
        private val PER_SERVING_HEADERS = listOf("per serving", "per serve", "per 1 serving", "per portion", "per package", "qty/serving", "per pack")
        private val RDA_HEADERS = listOf("%rda", "% rda", "%dv", "% dv", "% daily value", "% daily values", "%")
        private val VALUE_PATTERN = Regex("""([<]?)\s*([0-9]+(?:[.,][0-9]+)?)\s*(g|gm|gms|mg|mcg|\u00B5g|\u03BCg|kcal|cal|kj|ml|%)?""", RegexOption.IGNORE_CASE)
    }

    /**
     * Reconstructs a complete 2D TableGrid from OCR results using spatial coordinates.
     */
    fun reconstructTable(ocrResult: OcrResult): TableGrid {
        // Step 1: Group OCR elements into spatial rows
        val rawSpatialRows = groupElementsIntoRows(ocrResult)

        // Step 2: Identify table columns from headers and numeric X-clustering
        val detectedColumns = detectColumns(rawSpatialRows)

        // Step 3: Parse row cells and map them to columns
        val gridRows = mutableListOf<TableGridRow>()

        for (row in rawSpatialRows) {
            val cells = extractRowCells(row, detectedColumns)
            val labelText = extractRowLabel(row, cells)

            val cellsByCol = mutableMapOf<Int, TableRowCell>()
            for (cell in cells) {
                if (cell.columnIndex != null) {
                    cellsByCol[cell.columnIndex] = cell
                }
            }

            gridRows.add(
                TableGridRow(
                    fullRowText = row.text,
                    labelText = labelText,
                    bounds = row.bounds,
                    yCenter = row.yCenter,
                    cells = cells,
                    cellsByColumn = cellsByCol
                )
            )
        }

        return TableGrid(columns = detectedColumns, rows = gridRows)
    }

    private fun groupElementsIntoRows(ocrResult: OcrResult): List<NutritionLabelParser.SpatialRow> {
        val allElements = mutableListOf<OcrElement>()

        for (line in ocrResult.lines) {
            if (line.elements.isNotEmpty()) {
                allElements.addAll(line.elements)
            } else {
                val sBox = line.spatialBounds ?: NutritionLabelParser.SpatialBox.fromAndroidRect(line.boundingBox)
                allElements.add(OcrElement(text = line.text, boundingBox = line.boundingBox, spatialBounds = sBox))
            }
        }

        if (allElements.isEmpty()) {
            return emptyList()
        }

        val sorted = allElements.sortedBy { (it.spatialBounds ?: NutritionLabelParser.SpatialBox.fromAndroidRect(it.boundingBox))?.top ?: 0 }
        val rowClusters = mutableListOf<MutableList<OcrElement>>()

        for (elem in sorted) {
            val box = elem.spatialBounds ?: NutritionLabelParser.SpatialBox.fromAndroidRect(elem.boundingBox)
            if (box == null) continue

            val elemCenterY = box.centerY
            val elemHeight = box.height.coerceAtLeast(8)

            var matchedCluster: MutableList<OcrElement>? = null
            for (cluster in rowClusters) {
                val clusterBoxes = cluster.mapNotNull { it.spatialBounds ?: NutritionLabelParser.SpatialBox.fromAndroidRect(it.boundingBox) }
                if (clusterBoxes.isEmpty()) continue

                val minTop = clusterBoxes.minOf { it.top }
                val maxBottom = clusterBoxes.maxOf { it.bottom }
                val centerY = (minTop + maxBottom) / 2
                val avgH = clusterBoxes.map { it.height }.average().toInt().coerceAtLeast(8)

                val overlap = max(0, min(maxBottom, box.bottom) - max(minTop, box.top))
                val minH = min(avgH, elemHeight)

                if (overlap >= minH * 0.35 || abs(centerY - elemCenterY) <= (minH * 0.55)) {
                    matchedCluster = cluster
                    break
                }
            }

            if (matchedCluster != null) {
                matchedCluster.add(elem)
            } else {
                rowClusters.add(mutableListOf(elem))
            }
        }

        return rowClusters.map { cluster ->
            val sortedLeft = cluster.sortedBy { (it.spatialBounds ?: NutritionLabelParser.SpatialBox.fromAndroidRect(it.boundingBox))?.left ?: 0 }
            val joinedText = sortedLeft.joinToString(" ") { it.text.trim() }

            val boxes = sortedLeft.mapNotNull { it.spatialBounds ?: NutritionLabelParser.SpatialBox.fromAndroidRect(it.boundingBox) }
            val combinedBounds = if (boxes.isNotEmpty()) {
                NutritionLabelParser.SpatialBox(
                    left = boxes.minOf { it.left },
                    top = boxes.minOf { it.top },
                    right = boxes.maxOf { it.right },
                    bottom = boxes.maxOf { it.bottom }
                )
            } else null

            NutritionLabelParser.SpatialRow(
                text = joinedText,
                bounds = combinedBounds,
                yCenter = combinedBounds?.centerY ?: 0,
                elements = sortedLeft
            )
        }.sortedBy { it.bounds?.top ?: 0 }
    }

    private fun detectColumns(rows: List<NutritionLabelParser.SpatialRow>): List<TableColumn> {
        val detected = mutableListOf<TableColumn>()

        // 1. First search header rows for explicit column labels
        val headerRows = rows.take(8)
        for (row in headerRows) {
            val textLower = row.text.lowercase(Locale.ROOT)
            for (elem in row.elements) {
                val eText = elem.text.lowercase(Locale.ROOT)
                val sBox = elem.spatialBounds ?: NutritionLabelParser.SpatialBox.fromAndroidRect(elem.boundingBox)
                if (sBox == null) continue

                val basis = when {
                    PER_100G_HEADERS.any { eText.contains(it) || textLower.contains(it) && abs(sBox.centerX - (sBox.centerX)) < 50 } -> ColumnBasis.PER_100_G
                    PER_SERVING_HEADERS.any { eText.contains(it) || textLower.contains(it) && abs(sBox.centerX - (sBox.centerX)) < 50 } -> ColumnBasis.PER_SERVING
                    RDA_HEADERS.any { eText.contains(it) } -> ColumnBasis.DAILY_VALUE
                    else -> null
                }

                if (basis != null && detected.none { abs(it.centerX - sBox.centerX) < 80 }) {
                    detected.add(
                        TableColumn(
                            index = detected.size,
                            minX = sBox.left,
                            maxX = sBox.right,
                            centerX = sBox.centerX,
                            headerText = elem.text.trim(),
                            basis = basis
                        )
                    )
                }
            }
        }

        if (detected.isNotEmpty()) {
            return detected.sortedBy { it.centerX }
        }

        // 2. If no explicit headers found, cluster numeric values horizontally across rows
        val numericXCoords = mutableListOf<Int>()
        for (row in rows) {
            for (elem in row.elements) {
                val sBox = elem.spatialBounds ?: NutritionLabelParser.SpatialBox.fromAndroidRect(elem.boundingBox)
                if (sBox != null && VALUE_PATTERN.containsMatchIn(elem.text)) {
                    numericXCoords.add(sBox.centerX)
                }
            }
        }

        if (numericXCoords.size >= 6) {
            val clusters = mutableListOf<MutableList<Int>>()
            for (x in numericXCoords.sorted()) {
                val existing = clusters.find { cl -> cl.any { abs(it - x) < 70 } }
                if (existing != null) {
                    existing.add(x)
                } else {
                    clusters.add(mutableListOf(x))
                }
            }

            // Filter clusters with at least 3 occurrences (representing a solid column)
            val validClusters = clusters.filter { it.size >= 3 }.sortedBy { it.average() }

            if (validClusters.size in 1..4) {
                return validClusters.mapIndexed { idx, cl ->
                    val avgX = cl.average().toInt()
                    val basis = when {
                        validClusters.size == 1 -> ColumnBasis.PER_100_G
                        validClusters.size == 2 && idx == 0 -> ColumnBasis.PER_100_G
                        validClusters.size == 2 && idx == 1 -> ColumnBasis.PER_SERVING
                        validClusters.size >= 3 && idx == validClusters.size - 1 -> ColumnBasis.DAILY_VALUE
                        validClusters.size >= 3 && idx == 0 -> ColumnBasis.PER_100_G
                        else -> ColumnBasis.PER_SERVING
                    }
                    TableColumn(
                        index = idx,
                        minX = cl.minOrNull() ?: avgX,
                        maxX = cl.maxOrNull() ?: avgX,
                        centerX = avgX,
                        headerText = "Column ${idx + 1}",
                        basis = basis
                    )
                }
            }
        }

        return emptyList()
    }

    private fun extractRowCells(
        row: NutritionLabelParser.SpatialRow,
        columns: List<TableColumn>
    ): List<TableRowCell> {
        val cells = mutableListOf<TableRowCell>()

        for (elem in row.elements) {
            val sBox = elem.spatialBounds ?: NutritionLabelParser.SpatialBox.fromAndroidRect(elem.boundingBox)
            val match = VALUE_PATTERN.find(elem.text)

            if (match != null) {
                val unitStr = match.groupValues[3].ifEmpty { null }
                // Same reading as the parser: "1,580 mg" is 1580, "1,5 g" is 1.5 (BUG-022)
                val numVal = NutritionDigitCorrector.parseDecimal(match.groupValues[2], unitStr)
                val isPercent = elem.text.contains("%") || unitStr == "%"

                var matchedColIndex: Int? = null
                if (columns.isNotEmpty() && sBox != null) {
                    val nearestCol = columns.minByOrNull { abs(it.centerX - sBox.centerX) }
                    if (nearestCol != null && abs(nearestCol.centerX - sBox.centerX) < 140) {
                        matchedColIndex = nearestCol.index
                    }
                }

                cells.add(
                    TableRowCell(
                        text = elem.text,
                        rawNumber = numVal,
                        unit = unitStr,
                        isDailyValue = isPercent,
                        bounds = sBox,
                        columnIndex = matchedColIndex,
                        sourceElement = elem
                    )
                )
            }
        }

        return cells
    }

    private fun extractRowLabel(
        row: NutritionLabelParser.SpatialRow,
        cells: List<TableRowCell>
    ): String {
        val cellElements = cells.mapNotNull { it.sourceElement }
        val labelElements = row.elements.filter { it !in cellElements }
        return labelElements.joinToString(" ") { it.text.trim() }
    }
}
