package com.macrobase.app.feature.scanner

import com.macrobase.app.domain.model.ServingUnit
import com.macrobase.app.domain.model.scanner.ColumnBoundaryDebug
import com.macrobase.app.domain.model.scanner.ComparisonOperator
import com.macrobase.app.domain.model.scanner.NutritionBasis
import com.macrobase.app.domain.model.scanner.RowBoundaryDebug
import com.macrobase.app.domain.model.scanner.ScannerDebugData
import com.macrobase.app.domain.model.scanner.TokenDebugItem
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Two-stage physical table geometry and 2D grid reconstructor.
 * Locks header column anchors horizontally and couples numeric tokens strictly to nutrient
 * rows via vertical Y coordinates.
 */
class SpatialTableDetector {

    data class PhysicalColumn(
        val index: Int,
        val type: ColumnType,
        val basis: NutritionBasis,
        val minX: Float,
        val maxX: Float,
        val centerX: Float,
        val headerText: String
    )

    data class PhysicalRow(
        val nutrientKey: String,
        val labelText: String,
        val labelRight: Float,
        val minY: Float,
        val maxY: Float,
        val centerY: Float,
        val height: Float
    )

    data class TableCell(
        val nutrientKey: String,
        val columnType: ColumnType,
        val rawToken: NutritionNumericParser.ParsedNumericToken,
        val bounds: NutritionLabelParser.SpatialBox?,
        val confidence: Float
    )

    data class SpatialTableResult(
        val tableBounds: NutritionLabelParser.SpatialBox?,
        val columns: List<PhysicalColumn>,
        val rows: List<PhysicalRow>,
        val cells: List<TableCell>,
        val cellsByNutrient: Map<String, Map<ColumnType, TableCell>>,
        val servingMassG: Double?,
        val servingSize: Double?,
        val servingUnit: ServingUnit?,
        val servingDescription: String?,
        val debugData: ScannerDebugData,
        val warnings: List<String>
    )

    companion object {
        private val NUTRIENT_ROW_DEFINITIONS = listOf(
            Pair("calories", listOf("energy", "energie", "calories", "calorie", "kcal", "cal")),
            Pair("protein", listOf("protein", "proteins", "proteine", "proteines", "total protein")),
            Pair("carbs", listOf("carbohydrates", "carbohydrate", "carbohydrat es", "carbohyd rates", "carbohydra tes", "total carbohydrate", "total carbohydrates", "total carbs", "carbs")),
            Pair("fat", listOf("total fat", "total fats", "fat", "fats", "lipids")),
            Pair("saturatedFat", listOf("saturated fatty acids", "saturated fatty acid", "saturated fat", "saturated fats", "saturates", "sat fat", "sat. fat")),
            Pair("transFat", listOf("trans fatty acids", "trans fatty acid", "trans fat", "trans fats", "trans-fat")),
            Pair("cholesterol", listOf("cholesterol", "cholest.", "cholest")),
            Pair("fiber", listOf("dietary fibre", "dietary fiber", "dietaryfibre", "fiber", "fibre", "fibres")),
            Pair("sugar", listOf("total sugars", "total sugar", "sugars", "sugar")),
            Pair("addedSugar", listOf("added sugars", "added sugar", "added sugar (sucrose)", "sucrose")),
            Pair("sodium", listOf("sodium")),
            Pair("salt", listOf("salt", "salz"))
        )
    }

    fun detectAndReconstruct(ocrResult: OcrResult): SpatialTableResult {
        val warnings = mutableListOf<String>()
        val logLines = mutableListOf<String>()

        // Flatten all elements with bounding boxes
        val allElements = mutableListOf<OcrElement>()
        for (line in ocrResult.lines) {
            if (line.elements.isNotEmpty()) {
                allElements.addAll(line.elements)
            } else {
                val sBox = line.spatialBounds ?: NutritionLabelParser.SpatialBox.fromAndroidRect(line.boundingBox)
                allElements.add(OcrElement(text = line.text, boundingBox = line.boundingBox, spatialBounds = sBox))
            }
        }

        // 1. Serving Size Extraction (Cross-check table context)
        val servingInfo = extractServingMetadata(ocrResult.lines, ocrResult.fullText)
        logLines.add("Serving Detected: grams=${servingInfo.grams}, size=${servingInfo.size}, unit=${servingInfo.unit}")

        // 2. Multi-line Header Analysis & Column Discovery
        val detectedColumns = detectTableColumns(ocrResult.lines, allElements, servingInfo.grams, logLines)

        if (detectedColumns.isEmpty()) {
            return SpatialTableResult(
                tableBounds = null,
                columns = emptyList(),
                rows = emptyList(),
                cells = emptyList(),
                cellsByNutrient = emptyMap(),
                servingMassG = servingInfo.grams,
                servingSize = servingInfo.size,
                servingUnit = servingInfo.unit,
                servingDescription = servingInfo.description,
                debugData = ScannerDebugData(servingSizeGrams = servingInfo.grams, servingSizeText = servingInfo.description, rawLogLines = logLines),
                warnings = warnings
            )
        }

        // 3. Row Label Discovery (Y-span mapping)
        val detectedRows = detectNutrientRows(ocrResult.lines, allElements, detectedColumns, logLines)

        // 4. Grid Cell Token Assignment (X in Column, Y in Row)
        val cells = assignTokensToCells(allElements, detectedColumns, detectedRows, logLines)

        // 5. Cross-Column Decimal Recovery & Relationship Validation
        val adjustedCells = reconcileCells(cells, servingInfo.grams, warnings, logLines)

        // 6. Build cellsByNutrient map
        val cellsByNutrient = mutableMapOf<String, MutableMap<ColumnType, TableCell>>()
        for (cell in adjustedCells) {
            val nutrientMap = cellsByNutrient.getOrPut(cell.nutrientKey) { mutableMapOf() }
            nutrientMap[cell.columnType] = cell
        }

        // Build Debug Data
        val tableBounds = calculateTableBounds(detectedColumns, detectedRows, allElements)
        val debugData = ScannerDebugData(
            tableRectLeft = tableBounds?.left?.toFloat() ?: 0f,
            tableRectTop = tableBounds?.top?.toFloat() ?: 0f,
            tableRectRight = tableBounds?.right?.toFloat() ?: 0f,
            tableRectBottom = tableBounds?.bottom?.toFloat() ?: 0f,
            columns = detectedColumns.map { col ->
                ColumnBoundaryDebug(
                    name = col.headerText,
                    basis = col.basis,
                    minX = col.minX,
                    maxX = col.maxX,
                    centerX = col.centerX
                )
            },
            rows = detectedRows.map { row ->
                RowBoundaryDebug(
                    nutrientKey = row.nutrientKey,
                    labelText = row.labelText,
                    minY = row.minY,
                    maxY = row.maxY,
                    centerY = row.centerY
                )
            },
            tokens = adjustedCells.map { cell ->
                TokenDebugItem(
                    rawText = cell.rawToken.rawText,
                    numericValue = cell.rawToken.numericValue,
                    operator = cell.rawToken.operator,
                    unit = cell.rawToken.unit,
                    left = cell.bounds?.left?.toFloat() ?: 0f,
                    top = cell.bounds?.top?.toFloat() ?: 0f,
                    right = cell.bounds?.right?.toFloat() ?: 0f,
                    bottom = cell.bounds?.bottom?.toFloat() ?: 0f,
                    assignedRow = cell.nutrientKey,
                    assignedColumn = cell.columnType.name,
                    confidence = cell.confidence
                )
            },
            servingSizeGrams = servingInfo.grams,
            servingSizeText = servingInfo.description,
            rawLogLines = logLines
        )

        return SpatialTableResult(
            tableBounds = tableBounds,
            columns = detectedColumns,
            rows = detectedRows,
            cells = adjustedCells,
            cellsByNutrient = cellsByNutrient,
            servingMassG = servingInfo.grams,
            servingSize = servingInfo.size,
            servingUnit = servingInfo.unit,
            servingDescription = servingInfo.description,
            debugData = debugData,
            warnings = warnings
        )
    }

    private data class ServingMetadata(
        val size: Double?,
        val unit: ServingUnit?,
        val grams: Double?,
        val description: String?
    )

    private fun extractServingMetadata(lines: List<OcrLine>, fullText: String): ServingMetadata {
        var size: Double? = null
        var unit: ServingUnit? = null
        var grams: Double? = null
        var desc: String? = null

        // 1. Check for standard "Serving Size : 30g" or "Serving Size : 1 Scoop (36g)"
        val pattern = Pattern.compile(
            """(?:serving\s*size|per\s*serve|per\s*portion|serve\s*size)[:\s]*([0-9]+(?:\.[0-9]+)?|[0-9]+\/[0-9]+)?\s*([a-zA-Z]+)?(?:\s*\(([0-9]+(?:\.[0-9]+)?)\s*(g|gm|gms|grams|ml)\))?""",
            Pattern.CASE_INSENSITIVE
        )

        for (line in lines) {
            val text = line.text
            val lower = text.lowercase(Locale.ROOT)
            if (lower.contains("total servings") && !lower.contains("serving size")) {
                continue // Ignore container serving count (e.g. Total Servings: 16)
            }

            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                val q1Str = matcher.group(1)?.trim()
                val q1 = q1Str?.toDoubleOrNull()
                val u1 = matcher.group(2)?.trim()?.lowercase(Locale.ROOT)
                val q2 = matcher.group(3)?.toDoubleOrNull()
                val q2Unit = matcher.group(4)?.lowercase(Locale.ROOT)

                if (q2 != null && q2Unit == "ml") {
                    // "Serving size 1 cup (250 mL)": a volume is not a weight, so there is no gram
                    // mass to convert with; the values stay per serving (BUG-020, BUG-021)
                    size = q1 ?: 1.0
                    desc = text.trim()
                    break
                } else if (q2 != null) {
                    size = q1 ?: 1.0
                    grams = q2
                    unit = ServingUnit.GRAMS
                    desc = text.trim()
                    break
                } else if (q1 != null && (u1 == "g" || u1 == "gm" || u1 == "gms" || u1 == "grams")) {
                    size = q1
                    grams = q1
                    unit = ServingUnit.GRAMS
                    desc = text.trim()
                    break
                }
            }
        }

        // 2. Cross-check against header text like "Per Serving (30g)"
        val headerServingPattern = Pattern.compile("""per\s*(?:serving|serve)\s*\(([0-9]+(?:\.[0-9]+)?)\s*(?:g|gm|gms|grams)\)""", Pattern.CASE_INSENSITIVE)
        for (line in lines.take(12)) {
            val m = headerServingPattern.matcher(line.text)
            if (m.find()) {
                val headerGrams = m.group(1)?.toDoubleOrNull()
                if (headerGrams != null && headerGrams > 0.0) {
                    if (grams == null) {
                        grams = headerGrams
                        size = headerGrams
                        unit = ServingUnit.GRAMS
                        desc = line.text.trim()
                    }
                    break
                }
            }
        }

        return ServingMetadata(size, unit, grams, desc)
    }

    private fun detectTableColumns(
        lines: List<OcrLine>,
        elements: List<OcrElement>,
        servingGrams: Double?,
        logLines: MutableList<String>
    ): List<PhysicalColumn> {
        val detected = mutableListOf<PhysicalColumn>()

        // Search top 15 lines for table header elements
        val headerElements = elements.filter { elem ->
            val text = elem.text.lowercase(Locale.ROOT)
            val sBox = elem.spatialBounds ?: NutritionLabelParser.SpatialBox.fromAndroidRect(elem.boundingBox)
            sBox != null && (
                text.contains("100g") || text.contains("100 g") || text.contains("100gm") ||
                text.contains("per") || text.contains("serve") || text.contains("serving") ||
                text.contains("rda") || text.contains("%") || text.contains("qty") || text.contains("30g")
            )
        }

        // Classify header elements spatially
        var elem100: OcrElement? = null
        var elemServ: OcrElement? = null
        var elemRda: OcrElement? = null

        for (elem in headerElements) {
            val text = elem.text.lowercase(Locale.ROOT)
            val sBox = elem.spatialBounds ?: NutritionLabelParser.SpatialBox.fromAndroidRect(elem.boundingBox) ?: continue

            // Ignore "Serving Size : 30g" line in column headers
            if (text.contains("size") || text.contains("total")) continue

            if (text.contains("rda") || text.contains("%")) {
                if (elemRda == null || sBox.centerX > (elemRda.spatialBounds?.centerX ?: 0)) {
                    elemRda = elem
                }
            } else if (text.contains("100g") || text.contains("100 g") || text.contains("100gm")) {
                elem100 = elem
            } else if (text.contains("serving") || text.contains("serve") || (servingGrams != null && text.contains("${servingGrams.toInt()}g"))) {
                elemServ = elem
            }
        }

        // If elements found, define physical column centers
        val x100 = elem100?.let { (it.spatialBounds ?: NutritionLabelParser.SpatialBox.fromAndroidRect(it.boundingBox))?.centerX?.toFloat() }
        val xServ = elemServ?.let { (it.spatialBounds ?: NutritionLabelParser.SpatialBox.fromAndroidRect(it.boundingBox))?.centerX?.toFloat() }
        val xRda = elemRda?.let { (it.spatialBounds ?: NutritionLabelParser.SpatialBox.fromAndroidRect(it.boundingBox))?.centerX?.toFloat() }

        logLines.add("Header Column Anchors: 100g=$x100, serv=$xServ, rda=$xRda")

        if (x100 != null && xServ != null) {
            val leftX = min(x100, xServ)
            val rightX = max(x100, xServ)
            val div1 = (leftX + rightX) / 2f
            val div2 = if (xRda != null) (rightX + xRda) / 2f else rightX + (rightX - div1)
            val rdaRight = if (xRda != null) xRda + 80f else div2 + 80f

            if (x100 < xServ) {
                detected.add(
                    PhysicalColumn(
                        index = 0,
                        type = ColumnType.PER_100G,
                        basis = NutritionBasis.PER_100_G,
                        minX = leftX - 80f,
                        maxX = div1,
                        centerX = x100,
                        headerText = "Per 100g"
                    )
                )
                detected.add(
                    PhysicalColumn(
                        index = 1,
                        type = ColumnType.PER_SERVING,
                        basis = NutritionBasis.PER_SERVING,
                        minX = div1,
                        maxX = div2,
                        centerX = xServ,
                        headerText = "Per Serving"
                    )
                )
            } else {
                detected.add(
                    PhysicalColumn(
                        index = 0,
                        type = ColumnType.PER_SERVING,
                        basis = NutritionBasis.PER_SERVING,
                        minX = leftX - 80f,
                        maxX = div1,
                        centerX = xServ,
                        headerText = "Per Serving"
                    )
                )
                detected.add(
                    PhysicalColumn(
                        index = 1,
                        type = ColumnType.PER_100G,
                        basis = NutritionBasis.PER_100_G,
                        minX = div1,
                        maxX = div2,
                        centerX = x100,
                        headerText = "Per 100g"
                    )
                )
            }

            if (xRda != null) {
                detected.add(
                    PhysicalColumn(
                        index = 2,
                        type = ColumnType.RDA,
                        basis = NutritionBasis.UNKNOWN,
                        minX = div2,
                        maxX = rdaRight,
                        centerX = xRda,
                        headerText = "%RDA"
                    )
                )
            }
        }

        return detected
    }

    private fun detectNutrientRows(
        lines: List<OcrLine>,
        elements: List<OcrElement>,
        columns: List<PhysicalColumn>,
        logLines: MutableList<String>
    ): List<PhysicalRow> {
        val rows = mutableListOf<PhysicalRow>()
        val minDataX = columns.minOfOrNull { it.minX } ?: 300f

        val headerBottomY = columns.mapNotNull { it.centerX }.let {
            elements.filter { it.text.contains("100") || it.text.contains("serv", ignoreCase = true) }
                .mapNotNull { it.spatialBounds ?: NutritionLabelParser.SpatialBox.fromAndroidRect(it.boundingBox) }
                .map { it.bottom }
                .maxOrNull() ?: 0
        }

        for ((key, synonyms) in NUTRIENT_ROW_DEFINITIONS) {
            var matchedLine: OcrLine? = null
            var matchedSynonym: String = ""

            for (line in lines) {
                val sBox = line.spatialBounds ?: NutritionLabelParser.SpatialBox.fromAndroidRect(line.boundingBox) ?: continue
                if (sBox.centerY <= headerBottomY) continue

                val textLower = line.text.lowercase(Locale.ROOT)
                if (textLower.contains("ingredient") || textLower.contains("blend")) continue

                for (syn in synonyms) {
                    val regex = Regex("""\b${Regex.escape(syn)}\b""", RegexOption.IGNORE_CASE)
                    if (regex.containsMatchIn(textLower) || textLower.startsWith(syn) || textLower.contains(" $syn")) {
                        matchedLine = line
                        matchedSynonym = syn
                        break
                    }
                }
                if (matchedLine != null) break
            }

            if (matchedLine != null) {
                val lineBox = matchedLine.spatialBounds ?: NutritionLabelParser.SpatialBox.fromAndroidRect(matchedLine.boundingBox)
                if (lineBox != null) {
                    val lineElems = matchedLine.elements.ifEmpty {
                        elements.filter { elem ->
                            val eb = elem.spatialBounds ?: NutritionLabelParser.SpatialBox.fromAndroidRect(elem.boundingBox)
                            eb != null && abs(eb.centerY - lineBox.centerY) <= (lineBox.height * 0.8)
                        }
                    }

                    val firstDataElem = lineElems.firstOrNull { elem ->
                        NutritionNumericParser.extractTokens(elem.text).isNotEmpty()
                    }

                    val lRight = if (firstDataElem != null) {
                        val fb = firstDataElem.spatialBounds ?: NutritionLabelParser.SpatialBox.fromAndroidRect(firstDataElem.boundingBox)
                        (fb?.left ?: (minDataX - 20f)).toFloat()
                    } else {
                        val labelElems = lineElems.filter { elem ->
                            val eb = elem.spatialBounds ?: NutritionLabelParser.SpatialBox.fromAndroidRect(elem.boundingBox)
                            eb != null && eb.centerX < minDataX
                        }
                        (labelElems.mapNotNull { it.spatialBounds?.right ?: it.boundingBox?.right }.maxOrNull() ?: (minDataX - 50f)).toFloat()
                    }

                    val rowHeight = max(18f, lineBox.height.toFloat() * 1.3f)
                    val halfH = rowHeight / 2f
                    val centerY = lineBox.centerY.toFloat()

                    rows.add(
                        PhysicalRow(
                            nutrientKey = key,
                            labelText = matchedLine.text.trim(),
                            labelRight = lRight,
                            minY = centerY - halfH,
                            maxY = centerY + halfH,
                            centerY = centerY,
                            height = rowHeight
                        )
                    )
                    logLines.add("Row Detected: $key ('$matchedSynonym') at Y=$centerY, labelRight=$lRight")
                }
            }
        }

        return rows.sortedBy { it.centerY }
    }

    private fun assignTokensToCells(
        elements: List<OcrElement>,
        columns: List<PhysicalColumn>,
        rows: List<PhysicalRow>,
        logLines: MutableList<String>
    ): List<TableCell> {
        val cells = mutableListOf<TableCell>()

        // Group elements by row
        val elementsByRow = mutableMapOf<PhysicalRow, MutableList<Pair<OcrElement, List<NutritionNumericParser.ParsedNumericToken>>>>()

        for (elem in elements) {
            val sBox = elem.spatialBounds ?: NutritionLabelParser.SpatialBox.fromAndroidRect(elem.boundingBox) ?: continue
            val tokens = NutritionNumericParser.extractTokens(elem.text)
            if (tokens.isEmpty()) continue

            val matchedRow = rows.minByOrNull { row ->
                abs(sBox.centerY.toFloat() - row.centerY)
            }?.takeIf { row ->
                abs(sBox.centerY.toFloat() - row.centerY) <= (row.height * 0.95f) &&
                sBox.centerX > (row.labelRight - 15f)
            } ?: continue

            elementsByRow.getOrPut(matchedRow) { mutableListOf() }.add(Pair(elem, tokens))
        }

        val valueCols = columns.filter { it.type != ColumnType.RDA }.sortedBy { it.centerX }
        val rdaCol = columns.find { it.type == ColumnType.RDA }

        for ((row, elemPairs) in elementsByRow) {
            // Flatten all tokens in this row with their spatial boxes
            val rowTokensWithBox = mutableListOf<Pair<NutritionNumericParser.ParsedNumericToken, NutritionLabelParser.SpatialBox>>()
            for ((elem, tokens) in elemPairs) {
                val sBox = elem.spatialBounds ?: NutritionLabelParser.SpatialBox.fromAndroidRect(elem.boundingBox) ?: continue
                for (token in tokens) {
                    rowTokensWithBox.add(Pair(token, sBox))
                }
            }

            // Sort row tokens from left to right by spatial X
            val sortedTokens = rowTokensWithBox.sortedBy { it.second.centerX }

            val rdaTokens = sortedTokens.filter { it.first.isPercent }
            val valueTokens = sortedTokens.filter { !it.first.isPercent }.let { tokens ->
                if (row.nutrientKey == "calories") kcalTokens(row, tokens, elements, logLines) else tokens
            }

            // Assign RDA token
            if (rdaCol != null && rdaTokens.isNotEmpty()) {
                val rdaTok = rdaTokens.first()
                cells.add(
                    TableCell(
                        nutrientKey = row.nutrientKey,
                        columnType = ColumnType.RDA,
                        rawToken = rdaTok.first,
                        bounds = rdaTok.second,
                        confidence = 0.95f
                    )
                )
                logLines.add("Token Assigned: '${rdaTok.first.rawText}' -> [${row.nutrientKey}, RDA]")
            }

            // Assign value tokens to value columns
            if (valueCols.size >= 2 && valueTokens.size >= 2) {
                val tok0 = valueTokens[0]
                val tok1 = valueTokens[1]
                cells.add(
                    TableCell(
                        nutrientKey = row.nutrientKey,
                        columnType = valueCols[0].type,
                        rawToken = tok0.first,
                        bounds = tok0.second,
                        confidence = 0.95f
                    )
                )
                cells.add(
                    TableCell(
                        nutrientKey = row.nutrientKey,
                        columnType = valueCols[1].type,
                        rawToken = tok1.first,
                        bounds = tok1.second,
                        confidence = 0.95f
                    )
                )
                logLines.add("Tokens Assigned: '${tok0.first.rawText}' -> [${row.nutrientKey}, ${valueCols[0].type}], '${tok1.first.rawText}' -> [${row.nutrientKey}, ${valueCols[1].type}]")
            } else if (valueTokens.isNotEmpty() && valueCols.isNotEmpty()) {
                val tok = valueTokens[0]
                val closestCol = valueCols.minByOrNull { abs(tok.second.centerX - it.centerX) } ?: valueCols.first()
                cells.add(
                    TableCell(
                        nutrientKey = row.nutrientKey,
                        columnType = closestCol.type,
                        rawToken = tok.first,
                        bounds = tok.second,
                        confidence = 0.95f
                    )
                )
                logLines.add("Token Assigned: '${tok.first.rawText}' -> [${row.nutrientKey}, ${closestCol.type}]")
            }
        }

        return cells
    }

    /**
     * The calorie columns take kcal only. kJ figures printed beside them ("1046 kJ / 250 kcal")
     * are dropped; a row printed only in kJ is converted to kcal (BUG-019).
     */
    private fun kcalTokens(
        row: PhysicalRow,
        allTokens: List<Pair<NutritionNumericParser.ParsedNumericToken, NutritionLabelParser.SpatialBox>>,
        elements: List<OcrElement>,
        logLines: MutableList<String>
    ): List<Pair<NutritionNumericParser.ParsedNumericToken, NutritionLabelParser.SpatialBox>> {
        // Energy is never in grams: a "100g" here is the column header just above the row
        val tokens = allTokens.filterNot { NutritionNumericParser.isMassOrVolume(it.first.unit) }
        if (tokens.isEmpty()) return tokens
        // Every word on this row, left to right, to find the unit printed after each number
        val rowWords = elements.mapNotNull { elem ->
            val box = elem.spatialBounds ?: NutritionLabelParser.SpatialBox.fromAndroidRect(elem.boundingBox) ?: return@mapNotNull null
            if (abs(box.centerY.toFloat() - row.centerY) <= row.height * 0.95f) elem to box else null
        }.sortedBy { it.second.centerX }

        val wordTexts = rowWords.map { it.first.text }
        val numbers = tokens.map { (token, box) ->
            val wordIndex = rowWords.indexOfFirst { it.second == box }
            val word = rowWords.getOrNull(wordIndex)?.first
            val inWord = word?.let { NutritionNumericParser.extractTokens(it.text) } ?: listOf(token)
            val indexInWord = inWord.indexOfFirst { it.rawText == token.rawText }.coerceAtLeast(0)
            NutritionNumericParser.EnergyNumber(
                unit = token.unit,
                // The unit word only belongs to the last number of a word ("1046 kJ")
                nextText = if (indexInWord == inWord.lastIndex) NutritionNumericParser.unitTextAfter(wordTexts, wordIndex) else null,
                indexInWord = indexInWord,
                numbersInWord = inWord.size,
                value = token.numericValue
            )
        }
        val units = NutritionNumericParser.energyUnits(row.labelText, numbers)
        if (units.none { it == "kj" }) return tokens

        val notKj = tokens.filterIndexed { i, _ -> units[i] != "kj" }
        if (notKj.isNotEmpty()) {
            logLines.add("Energy row: ignored kJ values, kept ${notKj.map { it.first.rawText }}")
            return notKj
        }
        // kJ only (Australian / New Zealand labels): convert each column to kcal
        logLines.add("Energy row: converted kJ values ${tokens.map { it.first.rawText }} to kcal")
        return tokens.map { (token, box) ->
            val kcal = Math.round(token.numericValue / NutritionLabelParser.KJ_TO_KCAL_FACTOR * 10.0) / 10.0
            token.copy(numericValue = kcal, unit = "kcal", convertedFromKj = token.numericValue) to box
        }
    }

    private fun reconcileCells(
        cells: List<TableCell>,
        servingGrams: Double?,
        warnings: MutableList<String>,
        logLines: MutableList<String>
    ): List<TableCell> {
        val adjusted = cells.toMutableList()
        if (servingGrams == null || servingGrams <= 0.0) return adjusted

        // 1. Check for Table-Wide Column Inversion / Swap
        var matchesOrientationA = 0
        var matchesOrientationB = 0
        val byNutrientInitial = adjusted.groupBy { it.nutrientKey }
        for ((_, nCells) in byNutrientInitial) {
            val c100 = nCells.find { it.columnType == ColumnType.PER_100G }
            val cServ = nCells.find { it.columnType == ColumnType.PER_SERVING }
            if (c100 != null && cServ != null) {
                val v100 = c100.rawToken.numericValue
                val vServ = cServ.rawToken.numericValue
                if (v100 > 0.0 && vServ > 0.0) {
                    val expA = v100 * servingGrams / 100.0
                    val diffA = abs(expA - vServ)
                    val tolA = max(2.0, vServ * 0.25)
                    if (diffA <= tolA) matchesOrientationA++

                    val expB = vServ * servingGrams / 100.0
                    val diffB = abs(expB - v100)
                    val tolB = max(2.0, v100 * 0.25)
                    if (diffB <= tolB) matchesOrientationB++
                }
            }
        }

        if (matchesOrientationB > matchesOrientationA && matchesOrientationB >= 2) {
            val sDesc = if (servingGrams % 1.0 == 0.0) "${servingGrams.toInt()}g" else "${servingGrams}g"
            warnings.add("Table columns swapped based on serving relationship validation ($sDesc)")
            logLines.add("Table columns swapped based on serving relationship validation ($sDesc)")
            for (i in adjusted.indices) {
                val cell = adjusted[i]
                if (cell.columnType == ColumnType.PER_100G) {
                    adjusted[i] = cell.copy(columnType = ColumnType.PER_SERVING)
                } else if (cell.columnType == ColumnType.PER_SERVING) {
                    adjusted[i] = cell.copy(columnType = ColumnType.PER_100G)
                }
            }
        }

        // 2. Group by nutrient and check for decimal loss
        val byNutrient = adjusted.groupBy { it.nutrientKey }

        for ((nutrientKey, nCells) in byNutrient) {
            val c100 = nCells.find { it.columnType == ColumnType.PER_100G }
            val cServ = nCells.find { it.columnType == ColumnType.PER_SERVING }

            if (c100 != null && cServ != null) {
                val v100 = c100.rawToken.numericValue
                val vServ = cServ.rawToken.numericValue

                // Check for serving decimal loss (e.g. 221 -> 22.1, 23 -> 2.3)
                val recoveredServ = NutritionNumericParser.recoverServingDecimalLoss(v100, vServ, servingGrams)
                if (recoveredServ != null) {
                    warnings.add("Recovered decimal point in $nutrientKey: $vServ -> $recoveredServ")
                    logLines.add("DECIMAL RECOVERED: $nutrientKey serving $vServ -> $recoveredServ")
                    val idx = adjusted.indexOf(cServ)
                    if (idx >= 0) {
                        adjusted[idx] = cServ.copy(
                            rawToken = cServ.rawToken.copy(
                                numericValue = recoveredServ,
                                wasDecimalRecovered = true
                            )
                        )
                    }
                }

                // Check for 100g decimal loss (e.g. 77 -> 7.7)
                val recovered100 = NutritionNumericParser.recover100gDecimalLoss(v100, vServ, servingGrams)
                if (recovered100 != null) {
                    warnings.add("Recovered decimal point in $nutrientKey: $v100 -> $recovered100")
                    logLines.add("DECIMAL RECOVERED: $nutrientKey 100g $v100 -> $recovered100")
                    val idx = adjusted.indexOf(c100)
                    if (idx >= 0) {
                        adjusted[idx] = c100.copy(
                            rawToken = c100.rawToken.copy(
                                numericValue = recovered100,
                                wasDecimalRecovered = true
                            )
                        )
                    }
                }
            }
        }

        return adjusted
    }

    private fun calculateTableBounds(
        columns: List<PhysicalColumn>,
        rows: List<PhysicalRow>,
        allElements: List<OcrElement>
    ): NutritionLabelParser.SpatialBox? {
        if (rows.isEmpty() || columns.isEmpty()) return null
        val minLeft = (rows.map { it.minY }.minOrNull() ?: 0f).toInt()
        val minTop = (rows.map { it.minY }.minOrNull() ?: 0f).toInt()
        val maxRight = (columns.map { it.maxX }.maxOrNull() ?: 500f).toInt()
        val maxBottom = (rows.map { it.maxY }.maxOrNull() ?: 500f).toInt()
        return NutritionLabelParser.SpatialBox(0, minTop - 20, maxRight + 20, maxBottom + 20)
    }
}
