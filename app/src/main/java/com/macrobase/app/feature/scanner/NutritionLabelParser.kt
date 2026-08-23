package com.macrobase.app.feature.scanner

import android.graphics.Rect
import com.macrobase.app.domain.model.ServingUnit
import com.macrobase.app.domain.model.scanner.ConfidenceLevel
import com.macrobase.app.domain.model.scanner.NutritionBasis
import com.macrobase.app.domain.model.scanner.NutritionLabelDraft
import com.macrobase.app.domain.model.scanner.ParsedNutrientValue
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

// --- Structural Types ---
enum class ColumnType { PER_100G, PER_SERVING, RDA }

data class NutritionColumn(
    val type: ColumnType,
    val left: Float,
    val right: Float,
    val centerX: Float
)

data class StructuralColumnLayout(
    val columns: List<NutritionColumn>
)

data class NutrientRowExtraction(
    val keywordMatch: String,
    val per100g: Double?,
    val perServing: Double?,
    val rda: Double?,
    val isEstimated: Boolean = false
)
// ------------------------

/**
 * 2D Spatial & Structural On-Device Nutrition Label Parser.
 * Reconstructs visual tabular rows from OCR bounding boxes, handles multi-column layouts,
 * and falls back to structural 1D parsing when spatial coordinates are unavailable.
 */
class NutritionLabelParser {

    companion object {
        const val KJ_TO_KCAL_FACTOR = 4.184
        const val SALT_TO_SODIUM_FACTOR = 2.54 // 1g Salt ≈ 393.4mg Sodium (Salt / 2.54)
    }

    /**
     * Platform-independent bounding box representation for spatial OCR reasoning.
     */
    data class SpatialBox(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val width: Int get() = max(0, right - left)
        val height: Int get() = max(0, bottom - top)
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2

        fun toAndroidRect(): Rect = Rect(left, top, right, bottom)

        companion object {
            fun fromAndroidRect(rect: Rect?): SpatialBox? {
                if (rect == null) return null
                return SpatialBox(rect.left, rect.top, rect.right, rect.bottom)
            }
        }
    }

    /**
     * Reconstructed spatial row containing horizontally aligned text elements.
     */
    data class SpatialRow(
        val text: String,
        val bounds: SpatialBox?,
        val yCenter: Int,
        val elements: List<OcrElement>
    )

    /**
     * Parses raw OCR text and structured bounding lines into a typed NutritionLabelDraft.
     */
    fun parse(ocrResult: OcrResult): NutritionLabelDraft {
        val rawLines = ocrResult.lines
        val fullText = ocrResult.fullText
        val warnings = mutableListOf<String>()

        // 1. 2D Spatial Row Reconstruction
        val spatialRows = reconstructSpatialRows(ocrResult)
        val unifiedLines = if (spatialRows.isNotEmpty()) {
            spatialRows.map { OcrLine(text = it.text, boundingBox = it.bounds?.toAndroidRect(), elements = it.elements) }
        } else {
            rawLines
        }

        // 2. Detect Basis (Per 100g, Per 100ml, Per Serving)
        val basis = detectBasis(unifiedLines, fullText)

        // 3. Detect Serving Size & Serving Mass
        val servingInfo = detectServingInfo(unifiedLines, fullText)

        // 4. Multi-Column Layout Analysis
        val columns = analyzeColumns(spatialRows)

        // Filter sections (Stop at Amino Acid Profile or Ingredients)
        val filteredRows = mutableListOf<SpatialRow>()
        for (row in spatialRows) {
            val lower = row.text.lowercase(Locale.ROOT)
            if (lower.contains("amino acid profile") || lower.contains("ingredients") || lower.contains("allergens")) {
                break
            }
            filteredRows.add(row)
        }

        val normalizedFullText = normalizeOcrMisreads(fullText)

        var calories = extractEnergy(filteredRows, normalizedFullText, columns, basis, servingInfo.servingGrams)
        val caloriesKj = extractKjEnergy(filteredRows, normalizedFullText, columns, basis)
        var protein = extractNutrient(filteredRows, PROTEIN_PATTERNS, "g", columns, basis, servingInfo.servingGrams)
        var carbs = extractNutrient(filteredRows, CARBS_PATTERNS, "g", columns, basis, servingInfo.servingGrams)
        var fat = extractNutrient(filteredRows, FAT_PATTERNS, "g", columns, basis, servingInfo.servingGrams)
        var satFat = extractNutrient(filteredRows, SAT_FAT_PATTERNS, "g", columns, basis, servingInfo.servingGrams)
        var transFat = extractNutrient(filteredRows, TRANS_FAT_PATTERNS, "g", columns, basis, servingInfo.servingGrams)
        var cholesterol = extractNutrient(filteredRows, CHOLESTEROL_PATTERNS, "mg", columns, basis, servingInfo.servingGrams)
        var fiber = extractNutrient(filteredRows, FIBER_PATTERNS, "g", columns, basis, servingInfo.servingGrams)
        var sugar = extractNutrient(filteredRows, SUGAR_PATTERNS, "g", columns, basis, servingInfo.servingGrams)
        var addedSugar = extractNutrient(filteredRows, ADDED_SUGAR_PATTERNS, "g", columns, basis, servingInfo.servingGrams)
        var sodium = extractNutrient(filteredRows, SODIUM_PATTERNS, "mg", columns, basis, servingInfo.servingGrams)
        val salt = extractNutrient(filteredRows, SALT_PATTERNS, "g", columns, basis, servingInfo.servingGrams)
        var potassium = extractNutrient(filteredRows, POTASSIUM_PATTERNS, "mg", columns, basis, servingInfo.servingGrams)
        var calcium = extractNutrient(filteredRows, CALCIUM_PATTERNS, "mg", columns, basis, servingInfo.servingGrams)
        var iron = extractNutrient(filteredRows, IRON_PATTERNS, "mg", columns, basis, servingInfo.servingGrams)

        // Handle Salt -> Sodium estimation if Sodium is missing and Salt is present
        if (sodium == null && salt != null) {
            val estimatedSodiumMg = (salt.value / SALT_TO_SODIUM_FACTOR) * 1000.0
            val roundedMg = (estimatedSodiumMg * 10.0).roundToLong() / 10.0
            sodium = ParsedNutrientValue(
                value = roundedMg,
                unit = "mg",
                basis = salt.basis,
                confidence = ConfidenceLevel.MEDIUM,
                isEstimated = true,
                rawMatch = "Salt ${salt.value}g"
            )
            warnings.add("Sodium estimated from ${salt.value}g Salt (${sodium.value} mg)")
        }

        // Handle kJ-only conversion if Calories (kcal) is missing and kJ is found
        if (calories == null && caloriesKj != null) {
            val convertedKcal = caloriesKj.value / KJ_TO_KCAL_FACTOR
            val roundedKcal = (convertedKcal * 10.0).roundToLong() / 10.0
            calories = ParsedNutrientValue(
                value = roundedKcal,
                unit = "kcal",
                basis = caloriesKj.basis,
                confidence = ConfidenceLevel.MEDIUM,
                isEstimated = true,
                rawMatch = "${caloriesKj.value} kJ"
            )
            warnings.add("Calories converted from ${caloriesKj.value} kJ (${calories.value} kcal)")
        }

        // 6. Per-Serving to Per-100g Normalization (if serving grams known)
        var finalBasis = basis
        val servingGrams = servingInfo.servingGrams
        var normalizedToPer100g = false

        if (basis == NutritionBasis.PER_SERVING && servingGrams != null && servingGrams > 0.0) {
            val multiplier = 100.0 / servingGrams
            calories = calories?.scale(multiplier, NutritionBasis.PER_100_G)
            protein = protein?.scale(multiplier, NutritionBasis.PER_100_G)
            carbs = carbs?.scale(multiplier, NutritionBasis.PER_100_G)
            fat = fat?.scale(multiplier, NutritionBasis.PER_100_G)
            satFat = satFat?.scale(multiplier, NutritionBasis.PER_100_G)
            transFat = transFat?.scale(multiplier, NutritionBasis.PER_100_G)
            cholesterol = cholesterol?.scale(multiplier, NutritionBasis.PER_100_G)
            fiber = fiber?.scale(multiplier, NutritionBasis.PER_100_G)
            sugar = sugar?.scale(multiplier, NutritionBasis.PER_100_G)
            addedSugar = addedSugar?.scale(multiplier, NutritionBasis.PER_100_G)
            sodium = sodium?.scale(multiplier, NutritionBasis.PER_100_G)
            potassium = potassium?.scale(multiplier, NutritionBasis.PER_100_G)
            calcium = calcium?.scale(multiplier, NutritionBasis.PER_100_G)
            iron = iron?.scale(multiplier, NutritionBasis.PER_100_G)

            finalBasis = NutritionBasis.PER_100_G
            normalizedToPer100g = true
            warnings.add("Values normalized to Per 100g based on ${servingGrams}g serving size")
        }

        // 7. Plausibility Sanity Checks & Confidence Rating
        var confidenceScore = 100

        val hasMacros = protein != null || carbs != null || fat != null
        if (calories == null && !hasMacros) {
            confidenceScore -= 60
            warnings.add("No primary macronutrients could be confidently detected")
        }

        // Check caloric plausibility from macros: Cal ≈ P*4 + C*4 + F*9
        if (calories != null && protein != null && carbs != null && fat != null) {
            val expectedCal = protein.value * 4.0 + carbs.value * 4.0 + fat.value * 9.0
            val actualCal = calories.value
            val diff = abs(expectedCal - actualCal)
            if (actualCal > 20.0 && diff > (actualCal * 0.45)) {
                confidenceScore -= 20
                warnings.add("Calculated macro calories (${expectedCal.toInt()}) differ from label calories (${actualCal.toInt()})")
            }
        }

        

        val overallConfidence = when {
            confidenceScore >= 80 -> ConfidenceLevel.HIGH
            confidenceScore >= 50 -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW
        }

        return NutritionLabelDraft(
            foodName = null,
            brand = null,
            detectedBasis = finalBasis,
            servingSize = servingInfo.servingSize ?: if (normalizedToPer100g) 100.0 else 1.0,
            servingUnit = servingInfo.servingUnit ?: if (normalizedToPer100g) ServingUnit.GRAMS else ServingUnit.SERVING,
            servingGrams = servingInfo.servingGrams ?: if (normalizedToPer100g) 100.0 else null,
            servingDescription = servingInfo.servingDescription,
            calories = calories,
            caloriesKj = caloriesKj,
            protein = protein,
            carbs = carbs,
            fat = fat,
            saturatedFat = satFat,
            transFat = transFat,
            cholesterol = cholesterol,
            fiber = fiber,
            sugar = sugar,
            addedSugar = addedSugar,
            sodium = sodium,
            salt = salt,
            potassium = potassium,
            calcium = calcium,
            iron = iron,
            overallConfidence = overallConfidence,
            warnings = warnings,
            rawOcrText = fullText
        )
    }

    /**
     * Convenience method to parse raw text without bounding boxes.
     */
    fun parseText(text: String): NutritionLabelDraft {
        val lines = text.lines().mapIndexed { i, lineText ->
            val elements = Regex("\\S+").findAll(lineText).map { match ->
                val word = match.value
                val startX = match.range.first * 15
                val endX = startX + word.length * 15
                OcrElement(
                    text = word, 
                    boundingBox = null, 
                    spatialBounds = SpatialBox(startX, i * 20, endX, (i + 1) * 20)
                )
            }.toList()
            OcrLine(
                text = lineText, 
                boundingBox = null, 
                elements = elements, 
                spatialBounds = SpatialBox(0, i * 20, lineText.length * 15, (i + 1) * 20)
            )
        }
        val ocrResult = OcrResult(text, emptyList(), lines)
        return parse(ocrResult)
    }

    // =========================================================================
    // 2D SPATIAL RECONSTRUCTION ALGORITHM
    // =========================================================================

    /**
     * Reconstructs 2D tabular rows by grouping horizontally aligned OCR elements/lines.
     * Overcomes the standard OCR limitation where label names (left block) and values (right block)
     * are split into separate vertical blocks.
     */
    private data class InternalSpatialElement(
        val text: String,
        val bounds: SpatialBox,
        val originalElement: OcrElement
    )

    private fun reconstructSpatialRows(ocrResult: OcrResult): List<SpatialRow> {
        val allElements = mutableListOf<InternalSpatialElement>()

        for (line in ocrResult.lines) {
            if (line.elements.isNotEmpty()) {
                for (elem in line.elements) {
                    val sBox = elem.spatialBounds ?: SpatialBox.fromAndroidRect(elem.boundingBox)
                    if (sBox != null) {
                        allElements.add(InternalSpatialElement(elem.text, sBox, elem))
                    }
                }
            } else {
                val sBox = line.spatialBounds ?: SpatialBox.fromAndroidRect(line.boundingBox)
                if (sBox != null) {
                    allElements.add(InternalSpatialElement(line.text, sBox, OcrElement(text = line.text, boundingBox = line.boundingBox, spatialBounds = sBox)))
                }
            }
        }

        if (allElements.isEmpty()) {
            return ocrResult.lines.map {
                val sBox = it.spatialBounds ?: SpatialBox.fromAndroidRect(it.boundingBox)
                SpatialRow(
                    text = it.text,
                    bounds = sBox,
                    yCenter = sBox?.centerY ?: 0,
                    elements = it.elements
                )
            }
        }

        // Sort elements primarily top-to-bottom
        val sorted = allElements.sortedBy { it.bounds.top }
        val rows = mutableListOf<MutableList<InternalSpatialElement>>()

        for (elem in sorted) {
            val elemBounds = elem.bounds
            val elemCenterY = elemBounds.centerY
            val elemHeight = elemBounds.height.coerceAtLeast(1)

            // Find an existing row cluster that has vertical overlap with this element
            var matchedRow: MutableList<InternalSpatialElement>? = null
            for (row in rows) {
                val rowMinTop = row.minOf { it.bounds.top }
                val rowMaxBottom = row.maxOf { it.bounds.bottom }
                val rowCenterY = (rowMinTop + rowMaxBottom) / 2
                val rowAvgHeight = row.map { it.bounds.height }.average().toInt().coerceAtLeast(1)

                val verticalOverlap = max(0, min(rowMaxBottom, elemBounds.bottom) - max(rowMinTop, elemBounds.top))
                val minH = min(rowAvgHeight, elemHeight)

                if (verticalOverlap >= minH * 0.35 || abs(rowCenterY - elemCenterY) <= (minH * 0.6)) {
                    matchedRow = row
                    break
                }
            }

            if (matchedRow != null) {
                matchedRow.add(elem)
            } else {
                rows.add(mutableListOf(elem))
            }
        }

        // For each spatial row, sort elements left-to-right (X-axis) and join text
        return rows.map { rowElements ->
            val sortedLeftToRight = rowElements.sortedBy { it.bounds.left }
            val joinedText = sortedLeftToRight.joinToString(" ") { it.text.trim() }

            val minLeft = sortedLeftToRight.minOf { it.bounds.left }
            val maxRight = sortedLeftToRight.maxOf { it.bounds.right }
            val minTop = sortedLeftToRight.minOf { it.bounds.top }
            val maxBottom = sortedLeftToRight.maxOf { it.bounds.bottom }
            val combinedBounds = SpatialBox(minLeft, minTop, maxRight, maxBottom)

            SpatialRow(
                text = joinedText,
                bounds = combinedBounds,
                yCenter = combinedBounds.centerY,
                elements = sortedLeftToRight.map { it.originalElement }
            )
        }.sortedBy { it.bounds?.top ?: 0 }
    }

    // =========================================================================
    // HELPER EXTRACTION FUNCTIONS
    // =========================================================================

    private fun detectBasis(lines: List<OcrLine>, fullText: String): NutritionBasis {
        val lowerText = fullText.lowercase(Locale.ROOT)

        // Indian / UK Per 100g / 100ml patterns
        if (lowerText.contains("per 100 g") || lowerText.contains("per 100g") ||
            lowerText.contains("per 100 gm") || lowerText.contains("approx. values per 100g") ||
            lowerText.contains("values per 100 g") || lowerText.contains("per 100gm") ||
            lowerText.contains("per 100 ml") || lowerText.contains("per 100ml")
        ) {
            return if (lowerText.contains("100 ml") || lowerText.contains("100ml")) {
                NutritionBasis.PER_100_ML
            } else {
                NutritionBasis.PER_100_G
            }
        }

        // Per Serving / Per Serve / Per Portion patterns
        if (lowerText.contains("per serving") || lowerText.contains("per serve") ||
            lowerText.contains("per portion") || lowerText.contains("amount per serving") ||
            lowerText.contains("serving size")
        ) {
            return NutritionBasis.PER_SERVING
        }

        return NutritionBasis.PER_100_G // Standard default food basis
    }

    private data class ServingInfo(
        val servingSize: Double?,
        val servingUnit: ServingUnit?,
        val servingGrams: Double?,
        val servingDescription: String?
    )

    private fun detectServingInfo(lines: List<OcrLine>, fullText: String): ServingInfo {
        var size: Double? = null
        var unit: ServingUnit? = null
        var grams: Double? = null
        var desc: String? = null

        val pattern = Pattern.compile(
            """(?:serving\s*size|per\s*serve|per\s*portion|approx\.?\s*serving)[:\s]*([0-9]+(?:\.[0-9]+)?)\s*([a-zA-Z]+)?(?:\s*\(([0-9]+(?:\.[0-9]+)?)\s*(g|gm|gms|ml)\))?""",
            Pattern.CASE_INSENSITIVE
        )

        for (line in lines) {
            val matcher = pattern.matcher(line.text)
            if (matcher.find()) {
                val q1 = matcher.group(1)?.toDoubleOrNull()
                val u1 = matcher.group(2)?.trim()?.lowercase(Locale.ROOT)
                val q2 = matcher.group(3)?.toDoubleOrNull()
                val u2 = matcher.group(4)?.trim()?.lowercase(Locale.ROOT)

                desc = line.text.trim()

                if (q2 != null && (u2 == "g" || u2 == "gm" || u2 == "gms" || u2 == "ml")) {
                    size = q1 ?: 1.0
                    unit = parseServingUnit(u1)
                    grams = q2
                } else if (q1 != null) {
                    size = q1
                    unit = parseServingUnit(u1)
                    if (u1 == "g" || u1 == "gm" || u1 == "gms" || u1 == "gram" || u1 == "grams") {
                        grams = q1
                    }
                }
                break
            }
        }

        if (grams == null) {
            val gramPattern = Pattern.compile("""\b([0-9]+(?:\.[0-9]+)?)\s*(?:g|gm|gms|grams)\b""", Pattern.CASE_INSENSITIVE)
            for (line in lines) {
                if (line.text.contains("serving", ignoreCase = true) || line.text.contains("serve", ignoreCase = true)) {
                    val m = gramPattern.matcher(line.text)
                    if (m.find()) {
                        grams = m.group(1)?.toDoubleOrNull()
                        size = size ?: grams
                        unit = unit ?: ServingUnit.GRAMS
                        desc = line.text.trim()
                        break
                    }
                }
            }
        }

        return ServingInfo(size, unit, grams, desc)
    }

    private fun parseServingUnit(raw: String?): ServingUnit {
        if (raw == null) return ServingUnit.SERVING
        val lower = raw.lowercase(Locale.ROOT)
        return when {
            lower.contains("g") || lower.contains("gm") || lower.contains("gram") -> ServingUnit.GRAMS
            lower.contains("ml") -> ServingUnit.MILLILITERS
            lower.contains("cup") -> ServingUnit.CUP
            lower.contains("tbsp") || lower.contains("tablespoon") -> ServingUnit.TABLESPOON
            lower.contains("tsp") || lower.contains("teaspoon") -> ServingUnit.TEASPOON
            lower.contains("piece") || lower.contains("pc") || lower.contains("biscuit") || lower.contains("cookie") -> ServingUnit.PIECE
            lower.contains("slice") -> ServingUnit.SLICE
            lower.contains("bowl") || lower.contains("katori") -> ServingUnit.BOWL
            lower.contains("oz") || lower.contains("ounce") -> ServingUnit.OUNCE
            else -> ServingUnit.SERVING
        }
    }

    

    private fun analyzeColumns(rows: List<SpatialRow>): StructuralColumnLayout {
        val cols = mutableListOf<NutritionColumn>()
        for (row in rows.take(15)) {
            for (element in row.elements) {
                val text = element.text.lowercase(Locale.ROOT)
                val type = when {
                    text.contains("100g") || text.contains("100 g") || text.contains("100ml") -> ColumnType.PER_100G
                    text.contains("serve") || text.contains("serving") || text.contains("portion") -> ColumnType.PER_SERVING
                    text.contains("%") || text.contains("rda") || text.contains("dv") || text.contains("daily value") -> ColumnType.RDA
                    else -> null
                }
                val sBox = element.spatialBounds
                if (type != null && sBox != null) {
                    cols.add(
                        NutritionColumn(
                            type = type,
                            left = sBox.left.toFloat(),
                            right = sBox.right.toFloat(),
                            centerX = sBox.centerX.toFloat()
                        )
                    )
                }
            }
        }
        val deduped = cols.sortedBy { it.centerX }.fold(mutableListOf<NutritionColumn>()) { acc, col ->
            if (acc.none { abs(it.centerX - col.centerX) < 60f }) {
                acc.add(col)
            }
            acc
        }
        return StructuralColumnLayout(deduped)
    }

    private fun extractEnergy(
        rows: List<SpatialRow>,
        fullText: String,
        columns: StructuralColumnLayout,
        basis: NutritionBasis,
        servingGrams: Double?
    ): ParsedNutrientValue? {
        val dualPattern = Pattern.compile(
            "(?:energy|calories|cal)[:\\s]*([0-9]+(?:[.,][0-9]+)?)\\s*(?:kj|kilojoules)?\\s*[/|\\(]\\s*([0-9]+(?:[.,][0-9]+)?)\\s*(?:kcal|cal|calories)?",
            Pattern.CASE_INSENSITIVE
        )

        for (row in rows) {
            val normalized = normalizeOcrMisreads(row.text).replace(',', '.')
            val m = dualPattern.matcher(normalized)
            if (m.find()) {
                val val1 = m.group(1)?.toDoubleOrNull()
                val val2 = m.group(2)?.toDoubleOrNull()
                val kcalVal = val2 ?: val1
                if (kcalVal != null && kcalVal > 0.0) {
                    return ParsedNutrientValue(
                        value = kcalVal,
                        unit = "kcal",
                        basis = basis,
                        confidence = ConfidenceLevel.HIGH,
                        rawMatch = row.text.trim()
                    )
                }
            }
        }

        val kcalRows = rows.filter { row ->
            val text = row.text.lowercase(Locale.ROOT)
            if (text.contains("calories from fat") || text.contains("cal from fat")) false
            else if ((text.contains("kj") || text.contains("kilojoule")) && !text.contains("kcal") && !text.contains("cal")) false
            else true
        }
        return extractNutrient(kcalRows, ENERGY_PATTERNS, "kcal", columns, basis, servingGrams)
    }

    private fun extractKjEnergy(
        rows: List<SpatialRow>,
        fullText: String,
        columns: StructuralColumnLayout,
        basis: NutritionBasis
    ): ParsedNutrientValue? {
        val kjPattern = Pattern.compile(
            "(?:(?:energy|energy\\s*value)[:\\s]*)?([0-9]+(?:[.,][0-9]+)?)\\s*(?:kj|kilojoules)\\b",
            Pattern.CASE_INSENSITIVE
        )

        for (row in rows) {
            val normalized = normalizeOcrMisreads(row.text).replace(',', '.')
            val m = kjPattern.matcher(normalized)
            if (m.find()) {
                val value = m.group(1)?.toDoubleOrNull()
                if (value != null && value > 0.0) {
                    return ParsedNutrientValue(
                        value = value,
                        unit = "kJ",
                        basis = basis,
                        confidence = ConfidenceLevel.HIGH,
                        rawMatch = row.text.trim()
                    )
                }
            }
        }
        return null
    }

    private fun extractNutrient(
        rows: List<SpatialRow>,
        keywordPatterns: List<String>,
        expectedUnit: String,
        columnLayout: StructuralColumnLayout,
        basis: NutritionBasis,
        servingGrams: Double?
    ): ParsedNutrientValue? {
        for (row in rows) {
            val normalizedRowText = normalizeOcrMisreads(row.text)
            val textLower = normalizedRowText.lowercase(Locale.ROOT)
            var matchedKeyword = ""
            for (kw in keywordPatterns) {
                val regex = Regex("""\b$kw\b""")
                val match = regex.find(textLower)
                if (match != null) {
                    matchedKeyword = kw
                    break
                }
            }
            if (matchedKeyword.isEmpty()) continue

            val numericElements = mutableListOf<Pair<OcrElement, Double>>()
            val rowIsEstimated = row.text.contains("<")
            for (element in row.elements) {
                val normalizedText = normalizeOcrMisreads(element.text).replace(',', '.')
                val match = Regex("""([<]?)\s*([0-9]+(?:[.,][0-9]+)?)""").find(normalizedText)
                if (match != null) {
                    val num = match.groupValues[2].toDoubleOrNull()
                    if (num != null) {
                        numericElements.add(Pair(element, num))
                    }
                }
            }

            if (numericElements.isEmpty()) continue
            
            println("ParserDebug - Row text: ${row.text} | Keyword: $matchedKeyword | Numerics: ${numericElements.map { it.second }}")

            var per100g: Double? = null
            var perServing: Double? = null
            var rda: Double? = null
            if (columnLayout.columns.isNotEmpty()) {
                for ((element, num) in numericElements) {
                    val sBox = element.spatialBounds ?: continue
                    val centerX = sBox.centerX.toFloat()
                    val nearestCol = columnLayout.columns.minByOrNull { kotlin.math.abs(it.centerX - centerX) }
                    
                    if (nearestCol != null && kotlin.math.abs(nearestCol.centerX - centerX) < 350f) {
                        when (nearestCol.type) {
                            ColumnType.PER_100G -> per100g = num
                            ColumnType.PER_SERVING -> perServing = num
                            ColumnType.RDA -> rda = num
                        }
                    }
                }
            } else {
                val sorted = numericElements.sortedBy { it.first.spatialBounds?.centerX?.toFloat() ?: 0f }
                if (sorted.isNotEmpty()) {
                    if (basis == NutritionBasis.PER_SERVING) {
                         if (sorted.size > 1 && sorted[1].first.text.contains("%")) {
                             perServing = sorted[0].second
                             rda = sorted[1].second
                         } else if (sorted.size > 1) {
                             per100g = sorted[0].second
                             perServing = sorted[1].second
                         } else {
                             perServing = sorted[0].second
                         }
                    } else {
                         per100g = sorted[0].second
                         if (sorted.size > 1) {
                             if (sorted[1].first.text.contains("%")) rda = sorted[1].second
                             else perServing = sorted[1].second
                         }
                    }
                }
            }
            
            val extracted = NutrientRowExtraction(matchedKeyword, per100g, perServing, rda, rowIsEstimated)
            return reconcileNutrient(extracted, servingGrams, expectedUnit, basis)
        }
        return null
    }

    private fun reconcileNutrient(
        extracted: NutrientRowExtraction,
        servingGrams: Double?,
        expectedUnit: String,
        requestedBasis: NutritionBasis
    ): ParsedNutrientValue? {
        var finalValue: Double? = null
        var finalBasis: NutritionBasis? = null
        var confidence = ConfidenceLevel.LOW

        if (extracted.per100g != null && extracted.perServing != null && servingGrams != null && servingGrams > 0) {
            val expectedServing = extracted.per100g * servingGrams / 100.0
            val diff = abs(expectedServing - extracted.perServing)
            val tolerance = max(2.0, extracted.perServing * 0.15)
            if (diff <= tolerance) {
                finalValue = extracted.per100g
                finalBasis = NutritionBasis.PER_100_G
                confidence = ConfidenceLevel.HIGH
            } else {
                finalValue = extracted.per100g
                finalBasis = NutritionBasis.PER_100_G
                confidence = ConfidenceLevel.MEDIUM
            }
        } else if (extracted.per100g != null) {
            finalValue = extracted.per100g
            finalBasis = NutritionBasis.PER_100_G
            confidence = ConfidenceLevel.MEDIUM
        } else if (extracted.perServing != null) {
            finalValue = extracted.perServing
            finalBasis = NutritionBasis.PER_SERVING
            confidence = ConfidenceLevel.MEDIUM
        }

        if (finalValue != null && finalBasis != null) {
            val v = if (extracted.isEstimated) finalValue / 2.0 else finalValue
            val result = ParsedNutrientValue(
                value = v,
                unit = expectedUnit,
                basis = finalBasis,
                confidence = confidence,
                isEstimated = extracted.isEstimated,
                rawMatch = extracted.keywordMatch
            )
            println("ParserDebug - reconcileNutrient returning: $result")
            return result
        }
        println("ParserDebug - reconcileNutrient returning null")
        return null
    }

    private fun ParsedNutrientValue.scale(multiplier: Double, newBasis: NutritionBasis): ParsedNutrientValue {
        val scaledVal = (this.value * multiplier * 100.0).roundToLong() / 100.0
        return this.copy(
            value = scaledVal,
            basis = newBasis,
            isEstimated = true
        )
    }

    // =========================================================================
    // REGEX KEYWORD PATTERNS
    // =========================================================================

    private val ENERGY_PATTERNS = listOf(
        "calories", "energy \\(kcal\\)", "energy kcal", "energy value", "energy", "cal", "energetic value"
    )

    private val PROTEIN_PATTERNS = listOf(
        "total protein", "protein", "proteins", "proteine"
    )

    private val CARBS_PATTERNS = listOf(
        "total carbohydrate", "total carbohydrates", "carbohydrate", "carbohydrates", "total carbs", "carbs", "glucides", "kohlenhydrate"
    )

    private val FAT_PATTERNS = listOf(
        "total fat", "total fats", "fat", "fats", "lipids", "graisses", "fett"
    )

    private val SAT_FAT_PATTERNS = listOf(
        "saturated fat", "saturated fatty acids", "saturated fats", "saturates", "of which saturates", "sat fat", "sat. fat"
    )

    private val TRANS_FAT_PATTERNS = listOf(
        "trans fat", "trans fatty acids", "trans fats", "trans-fat"
    )

    private val CHOLESTEROL_PATTERNS = listOf(
        "cholesterol", "cholest.", "cholest"
    )

    private val FIBER_PATTERNS = listOf(
        "dietary fiber", "dietary fibre", "fiber", "fibre", "ballaststoffe"
    )

    private val SUGAR_PATTERNS = listOf(
        "total sugars", "total sugar", "sugars", "sugar", "of which sugars", "zucker"
    )

    private val ADDED_SUGAR_PATTERNS = listOf(
        "added sugars", "added sugar", "includes .* added sugars", "added sucres"
    )

    private val SODIUM_PATTERNS = listOf(
        "sodium", "natrium"
    )

    private val SALT_PATTERNS = listOf(
        "salt", "equivalent as salt", "sel\\b", "salz"
    )

    private val POTASSIUM_PATTERNS = listOf(
        "potassium", "potasio"
    )

    private val CALCIUM_PATTERNS = listOf(
        "calcium", "calcio"
    )

    private val IRON_PATTERNS = listOf(
        "iron", "hierro"
    )

    private fun normalizeOcrMisreads(text: String): String {
        var normalized = text
        // Text corrections
        normalized = normalized.replace(Regex("""\bProte1n\b""", RegexOption.IGNORE_CASE), "Protein")
        normalized = normalized.replace(Regex("""\bCarbohydrat\s+e\b""", RegexOption.IGNORE_CASE), "Carbohydrate")
        normalized = normalized.replace(Regex("""\bSodlum\b""", RegexOption.IGNORE_CASE), "Sodium")
        normalized = normalized.replace(Regex("""\bCholestero1\b""", RegexOption.IGNORE_CASE), "Cholesterol")
        
        // Number misreads
        // O instead of 0
        normalized = normalized.replace(Regex("""(?<=\s|^|\b)[Oo]\.([0-9]+)"""), "0.$1") // " O.5" -> " 0.5"
        normalized = normalized.replace(Regex("""([0-9]+)\.[Oo](?=\s|g|mg|kcal|%|$)"""), "$1.0") // "1.O" -> "1.0"
        normalized = normalized.replace(Regex("""\b([0-9]+)[Oo](?=\s|g|mg|kcal|%|$)""")) {
            it.groupValues[1] + "0"
        }
        
        // I instead of 1
        normalized = normalized.replace(Regex("""(?<=\s|^|\b)[Il]\.([0-9]+)"""), "1.$1") // " I.5" -> " 1.5"

        return normalized
    }
}
