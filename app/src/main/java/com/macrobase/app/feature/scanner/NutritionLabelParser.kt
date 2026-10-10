package com.macrobase.app.feature.scanner

import kotlin.math.roundToInt
import android.graphics.Rect
import com.macrobase.app.domain.model.ServingUnit
import com.macrobase.app.domain.model.scanner.ComparisonOperator
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
    val isEstimated: Boolean = false,
    val per100gOp: ComparisonOperator = ComparisonOperator.EXACT,
    val perServingOp: ComparisonOperator = ComparisonOperator.EXACT
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
        const val MAX_KCAL_PER_100G = 900.0
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
        val rawBasis = detectBasis(unifiedLines, fullText)

        // 3. Detect Serving Size & Serving Mass
        val servingInfo = detectServingInfo(unifiedLines, fullText)

        // 4. Multi-Column Layout Analysis & 2D Spatial Table Grid Reconstruction
        val spatialDetector = SpatialTableDetector()
        val spatialResult = spatialDetector.detectAndReconstruct(ocrResult)
        warnings.addAll(spatialResult.warnings)

        val columns = analyzeColumns(spatialRows)

        val basis = when {
            spatialResult.columns.any { it.type == ColumnType.PER_100G } -> NutritionBasis.PER_100_G
            // A "100 g" column header without "per" ("Typical values 100 g  Serving 30 g") still
            // says what the first column is; it only decides when the wording did not
            rawBasis == NutritionBasis.UNKNOWN && columns.columns.any { it.type == ColumnType.PER_100G } ->
                if (Regex("""\b100\s*ml\b""").containsMatchIn(fullText.lowercase(Locale.ROOT))) NutritionBasis.PER_100_ML
                else NutritionBasis.PER_100_G
            else -> rawBasis
        }

        // Filter sections (Stop at Amino Acid Profile or Ingredients ONLY if encountered after table header)
        val filteredRows = mutableListOf<SpatialRow>()
        var foundTableOrNutrient = false
        for (row in spatialRows) {
            val lower = row.text.lowercase(Locale.ROOT)
            if (lower.contains("nutrition") || lower.contains("nutrients") || lower.contains("100g") || lower.contains("energy") || lower.contains("protein")) {
                foundTableOrNutrient = true
            }
            if (foundTableOrNutrient && (lower.contains("amino acid profile") || lower.contains("typical amino acid") || lower.contains("ingredients") || lower.contains("allergens"))) {
                break
            }
            filteredRows.add(row)
        }

        val effectiveServingGrams = servingInfo.servingGrams ?: spatialResult.servingMassG
        val effectiveServingSize = servingInfo.servingSize ?: spatialResult.servingSize
        val effectiveServingUnit = servingInfo.servingUnit ?: spatialResult.servingUnit
        val effectiveServingDesc = servingInfo.servingDescription ?: spatialResult.servingDescription

        val normalizedFullText = normalizeOcrMisreads(fullText)

        val per100gMap = mutableMapOf<String, ParsedNutrientValue>()
        val perServingMap = mutableMapOf<String, ParsedNutrientValue>()
        val rdaMap = mutableMapOf<String, Double>()

        // Helper function to extract nutrient using Spatial Grid first, falling back to regex row parser
        fun resolveNutrient(
            key: String,
            patterns: List<String>,
            unit: String
        ): ParsedNutrientValue? {
            if (spatialResult.columns.isNotEmpty()) {
                val cellGroup = spatialResult.cellsByNutrient[key]
                if (cellGroup != null) {
                val cell100 = cellGroup[ColumnType.PER_100G]
                val cellServ = cellGroup[ColumnType.PER_SERVING]
                val cellRda = cellGroup[ColumnType.RDA]

                if (cellRda != null) {
                    rdaMap[key] = cellRda.rawToken.numericValue
                }

                if (cell100 != null) {
                    val isLess = cell100.rawToken.operator == ComparisonOperator.LESS_THAN
                    val effectiveVal = if (isLess) cell100.rawToken.numericValue / 2.0 else cell100.rawToken.numericValue
                    val parsed100 = NutritionNormalization.normalizeToPer100g(
                        sourceValue = effectiveVal,
                        sourceUnit = unit,
                        sourceBasis = NutritionBasis.PER_100_G,
                        servingMassG = effectiveServingGrams,
                        sourceText = cell100.rawToken.rawText,
                        confidence = ConfidenceLevel.HIGH,
                        operator = cell100.rawToken.operator
                    ).copy(
                        sourceValue = cell100.rawToken.numericValue,
                        isEstimated = cell100.rawToken.wasDecimalRecovered || isLess
                    )
                    per100gMap[key] = parsed100

                    if (cellServ != null) {
                        val sLess = cellServ.rawToken.operator == ComparisonOperator.LESS_THAN
                        val sEffective = if (sLess) cellServ.rawToken.numericValue / 2.0 else cellServ.rawToken.numericValue
                        val parsedServ = ParsedNutrientValue(
                            value = sEffective,
                            unit = unit,
                            basis = NutritionBasis.PER_SERVING,
                            confidence = ConfidenceLevel.HIGH,
                            isEstimated = cellServ.rawToken.wasDecimalRecovered || sLess,
                            operator = cellServ.rawToken.operator,
                            rawMatch = cellServ.rawToken.rawText,
                            sourceText = cellServ.rawToken.rawText,
                            sourceValue = cellServ.rawToken.numericValue,
                            sourceUnit = unit,
                            sourceBasis = NutritionBasis.PER_SERVING,
                            normalizedPer100g = if (effectiveServingGrams != null && effectiveServingGrams > 0.0) ((sEffective * 100.0 / effectiveServingGrams) * 100.0).roundToLong() / 100.0 else null,
                            normalizedPer1g = if (effectiveServingGrams != null && effectiveServingGrams > 0.0) ((sEffective / effectiveServingGrams) * 10000.0).roundToLong() / 10000.0 else null
                        )
                        perServingMap[key] = parsedServ
                    }
                    return parsed100
                } else if (cellServ != null) {
                    val sLess = cellServ.rawToken.operator == ComparisonOperator.LESS_THAN
                    val sEffective = if (sLess) cellServ.rawToken.numericValue / 2.0 else cellServ.rawToken.numericValue
                    val parsedServ = NutritionNormalization.normalizeToPer100g(
                        sourceValue = sEffective,
                        sourceUnit = unit,
                        sourceBasis = NutritionBasis.PER_SERVING,
                        servingMassG = effectiveServingGrams,
                        sourceText = cellServ.rawToken.rawText,
                        confidence = ConfidenceLevel.MEDIUM,
                        needsReview = effectiveServingGrams == null,
                        operator = cellServ.rawToken.operator
                    ).copy(
                        sourceValue = cellServ.rawToken.numericValue,
                        isEstimated = cellServ.rawToken.wasDecimalRecovered || sLess
                    )
                    perServingMap[key] = parsedServ
                    return parsedServ
                }
            }
        }

        return extractNutrient(filteredRows, patterns, unit, columns, basis, effectiveServingGrams, key, per100gMap, perServingMap, rdaMap)
    }

        var calories = if (spatialResult.columns.isNotEmpty()) {
            val cellGroup = spatialResult.cellsByNutrient["calories"]
            if (cellGroup != null && cellGroup[ColumnType.PER_100G] != null) {
                val cell100 = cellGroup[ColumnType.PER_100G]!!
                val cellServ = cellGroup[ColumnType.PER_SERVING]
                val cellRda = cellGroup[ColumnType.RDA]
                if (cellRda != null) {
                    rdaMap["calories"] = cellRda.rawToken.numericValue
                }
                val kj100 = cell100.rawToken.convertedFromKj
                val p100 = NutritionNormalization.normalizeToPer100g(
                    sourceValue = cell100.rawToken.numericValue,
                    sourceUnit = "kcal",
                    sourceBasis = NutritionBasis.PER_100_G,
                    servingMassG = effectiveServingGrams,
                    sourceText = cell100.rawToken.rawText,
                    confidence = ConfidenceLevel.HIGH,
                    operator = cell100.rawToken.operator
                ).let { p ->
                    // A kJ-only label: keep what was printed and show that kcal was calculated (BUG-019)
                    if (kj100 != null) p.copy(isEstimated = true, sourceValue = kj100, sourceUnit = "kJ") else p
                }
                if (kj100 != null) {
                    warnings.add("Calories converted from $kj100 kJ (${p100.value} kcal)")
                }
                per100gMap["calories"] = p100
                if (cellServ != null) {
                    val kjServ = cellServ.rawToken.convertedFromKj
                    perServingMap["calories"] = ParsedNutrientValue(
                        value = cellServ.rawToken.numericValue,
                        unit = "kcal",
                        basis = NutritionBasis.PER_SERVING,
                        confidence = ConfidenceLevel.HIGH,
                        isEstimated = kjServ != null,
                        operator = cellServ.rawToken.operator,
                        sourceText = cellServ.rawToken.rawText,
                        sourceValue = kjServ ?: cellServ.rawToken.numericValue,
                        sourceUnit = if (kjServ != null) "kJ" else "kcal",
                        sourceBasis = NutritionBasis.PER_SERVING
                    )
                }
                p100
            } else null
        } else null

        if (calories == null) {
            calories = extractEnergy(filteredRows, normalizedFullText, columns, basis, effectiveServingGrams, per100gMap, perServingMap, rdaMap)
        }
        val caloriesKj = extractKjEnergy(filteredRows, normalizedFullText, columns, basis, per100gMap, perServingMap)
        var protein = resolveNutrient("protein", PROTEIN_PATTERNS, "g")
        var carbs = resolveNutrient("carbs", CARBS_PATTERNS, "g")
        var fat = resolveNutrient("fat", FAT_PATTERNS, "g")
        var satFat = resolveNutrient("saturatedFat", SAT_FAT_PATTERNS, "g")
        var transFat = resolveNutrient("transFat", TRANS_FAT_PATTERNS, "g")
        var cholesterol = resolveNutrient("cholesterol", CHOLESTEROL_PATTERNS, "mg")
        var fiber = resolveNutrient("fiber", FIBER_PATTERNS, "g")
        var sugar = resolveNutrient("sugar", SUGAR_PATTERNS, "g")
        var addedSugar = resolveNutrient("addedSugar", ADDED_SUGAR_PATTERNS, "g")
        var sodium = resolveNutrient("sodium", SODIUM_PATTERNS, "mg")
        var salt = resolveNutrient("salt", SALT_PATTERNS, "g")
        var potassium = resolveNutrient("potassium", POTASSIUM_PATTERNS, "mg")
        var calcium = resolveNutrient("calcium", CALCIUM_PATTERNS, "mg")
        var iron = resolveNutrient("iron", IRON_PATTERNS, "mg")

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
                rawMatch = "Salt ${salt.value}g",
                sourceText = "Salt ${salt.value}g"
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
                rawMatch = "${caloriesKj.value} kJ",
                sourceText = "${caloriesKj.value} kJ"
            )
            warnings.add("Calories converted from ${caloriesKj.value} kJ (${calories.value} kcal)")
        }

        // 5. Table-Wide Serving Relationship Validation & Column Swap Correction
        val servingGrams = effectiveServingGrams
        if (servingGrams != null && servingGrams > 0.0) {
            val sGrams = servingGrams
            val keysToCheck = listOf("calories", "protein", "carbs", "fat", "saturatedFat", "transFat", "fiber", "sugar", "sodium")

            // 5a. Table-Wide Column Swap Scoring & Orientation Validation
            var matchesOrientationA = 0
            var matchesOrientationB = 0
            for (key in keysToCheck) {
                val p100 = per100gMap[key]
                val pServ = perServingMap[key]
                if (p100 != null && pServ != null && pServ.value > 0.0 && p100.value > 0.0) {
                    val expA = p100.value * sGrams / 100.0
                    val diffA = abs(expA - pServ.value)
                    val tolA = max(2.0, pServ.value * 0.25)
                    if (diffA <= tolA) matchesOrientationA++

                    val expB = pServ.value * sGrams / 100.0
                    val diffB = abs(expB - p100.value)
                    val tolB = max(2.0, p100.value * 0.25)
                    if (diffB <= tolB) matchesOrientationB++
                }
            }

            if (matchesOrientationB > matchesOrientationA && matchesOrientationB >= 2) {
                warnings.add("Table columns swapped based on serving relationship validation (${sGrams}g)")
                val temp100 = HashMap(per100gMap)
                val tempServ = HashMap(perServingMap)
                per100gMap.clear()
                perServingMap.clear()

                for ((k, v) in tempServ) {
                    per100gMap[k] = v.copy(
                        basis = NutritionBasis.PER_100_G,
                        normalizedPer100g = v.value,
                        normalizedPer1g = (v.value / 100.0 * 10000.0).roundToLong() / 10000.0
                    )
                }
                for ((k, v) in temp100) {
                    perServingMap[k] = v.copy(
                        basis = NutritionBasis.PER_SERVING
                    )
                }

                calories = per100gMap["calories"] ?: calories
                protein = per100gMap["protein"] ?: protein
                carbs = per100gMap["carbs"] ?: carbs
                fat = per100gMap["fat"] ?: fat
                satFat = per100gMap["saturatedFat"] ?: satFat
                transFat = per100gMap["transFat"] ?: transFat
                cholesterol = per100gMap["cholesterol"] ?: cholesterol
                fiber = per100gMap["fiber"] ?: fiber
                sugar = per100gMap["sugar"] ?: sugar
                addedSugar = per100gMap["addedSugar"] ?: addedSugar
                sodium = per100gMap["sodium"] ?: sodium
                salt = per100gMap["salt"] ?: salt
                potassium = per100gMap["potassium"] ?: potassium
                calcium = per100gMap["calcium"] ?: calcium
                iron = per100gMap["iron"] ?: iron
            }

            // 5b. Contextual Decimal Point Loss Recovery (e.g. 77g vs 7.7g)
            for (key in keysToCheck) {
                val p100 = per100gMap[key]
                val pServ = perServingMap[key]
                if (p100 != null && pServ != null && pServ.value > 0.0) {
                    val expectedServ = p100.value * sGrams / 100.0
                    val diff = abs(expectedServ - pServ.value)
                    val tol = max(1.5, pServ.value * 0.20)

                    if (diff > tol && diff > 3.0 && p100.value >= 10.0) {
                        val correctedVal100 = (p100.value / 10.0 * 100.0).roundToLong() / 100.0
                        val correctedExpectedServ = correctedVal100 * sGrams / 100.0
                        val correctedDiff = abs(correctedExpectedServ - pServ.value)
                        if (correctedDiff <= max(0.5, pServ.value * 0.10) && diff > 3.0 * correctedDiff) {
                            warnings.add("Recovered decimal point in $key (100g): ${p100.value} -> $correctedVal100")
                            val correctedNutrient = p100.copy(
                                value = correctedVal100,
                                sourceValue = p100.sourceValue?.let { (it / 10.0 * 100.0).roundToLong() / 100.0 } ?: correctedVal100,
                                normalizedPer100g = correctedVal100,
                                normalizedPer1g = (correctedVal100 / 100.0 * 10000.0).roundToLong() / 10000.0,
                                isEstimated = true
                            )
                            per100gMap[key] = correctedNutrient
                            when (key) {
                                "calories" -> calories = correctedNutrient
                                "protein" -> protein = correctedNutrient
                                "carbs" -> carbs = correctedNutrient
                                "fat" -> fat = correctedNutrient
                                "saturatedFat" -> satFat = correctedNutrient
                                "transFat" -> transFat = correctedNutrient
                                "fiber" -> fiber = correctedNutrient
                                "sugar" -> sugar = correctedNutrient
                                "sodium" -> sodium = correctedNutrient
                            }
                        }
                    } else if (diff > tol && diff > 3.0 && pServ.value >= 10.0) {
                        val correctedValServ = (pServ.value / 10.0 * 100.0).roundToLong() / 100.0
                        val correctedDiff = abs(expectedServ - correctedValServ)
                        if (correctedDiff <= max(0.5, pServ.value * 0.10) && diff > 3.0 * correctedDiff) {
                            warnings.add("Recovered decimal point in $key (serving): ${pServ.value} -> $correctedValServ")
                            val correctedNutrient = pServ.copy(
                                value = correctedValServ,
                                sourceValue = pServ.sourceValue?.let { (it / 10.0 * 100.0).roundToLong() / 100.0 } ?: correctedValServ,
                                isEstimated = true
                            )
                            perServingMap[key] = correctedNutrient
                        }
                    }
                }
            }
        }

        // 6. Normalization & Canonical Basis Consolidation
        var finalBasis = basis
        var normalizedToPer100g = false
        var normalizationNote: String? = null

        if (basis == NutritionBasis.PER_SERVING || basis == NutritionBasis.PER_PACKAGE) {
            if (servingGrams != null && servingGrams > 0.0) {
                finalBasis = NutritionBasis.PER_100_G
                normalizedToPer100g = true
                val descText = if (servingInfo.servingSize != null) "${servingInfo.servingSize} (${servingGrams}g)" else "${servingGrams}g"
                normalizationNote = "Normalized from $descText serving to per 100 g."
                warnings.add(normalizationNote)
            } else {
                // No field takes a gram weight later, so the values are saved per serving (BUG-021)
                warnings.add("Serving size in grams not found, so values are kept per serving as printed.")
            }
        }

        // The label does not say what its values are per: keep them as printed, unconverted, and
        // let the user pick per 100 g or per serving in the review screen (BUG-020)
        val basisUnknown = finalBasis == NutritionBasis.UNKNOWN
        if (basisUnknown) {
            fun asPrinted(v: ParsedNutrientValue?) = v?.copy(
                basis = NutritionBasis.UNKNOWN,
                sourceBasis = NutritionBasis.UNKNOWN,
                normalizedPer100g = null,
                normalizedPer1g = null,
                needsReview = true,
                confidence = ConfidenceLevel.LOW
            )
            calories = asPrinted(calories)
            protein = asPrinted(protein)
            carbs = asPrinted(carbs)
            fat = asPrinted(fat)
            satFat = asPrinted(satFat)
            transFat = asPrinted(transFat)
            cholesterol = asPrinted(cholesterol)
            fiber = asPrinted(fiber)
            sugar = asPrinted(sugar)
            addedSugar = asPrinted(addedSugar)
            sodium = asPrinted(sodium)
            salt = asPrinted(salt)
            potassium = asPrinted(potassium)
            calcium = asPrinted(calcium)
            iron = asPrinted(iron)
            per100gMap.clear()
            perServingMap.clear()
            warnings.add("The label doesn't say whether these values are per 100 g or per serving. Choose one before applying.")
        }

        // Ensure all primary nutrients are safely scaled to canonical PER_100_G if needed
        if (finalBasis == NutritionBasis.PER_100_G && servingGrams != null && servingGrams > 0.0) {
            calories = calories?.let { NutritionNormalization.safeScaleToPer100g(it, servingGrams) }
            protein = protein?.let { NutritionNormalization.safeScaleToPer100g(it, servingGrams) }
            carbs = carbs?.let { NutritionNormalization.safeScaleToPer100g(it, servingGrams) }
            fat = fat?.let { NutritionNormalization.safeScaleToPer100g(it, servingGrams) }
            satFat = satFat?.let { NutritionNormalization.safeScaleToPer100g(it, servingGrams) }
            transFat = transFat?.let { NutritionNormalization.safeScaleToPer100g(it, servingGrams) }
            cholesterol = cholesterol?.let { NutritionNormalization.safeScaleToPer100g(it, servingGrams) }
            fiber = fiber?.let { NutritionNormalization.safeScaleToPer100g(it, servingGrams) }
            sugar = sugar?.let { NutritionNormalization.safeScaleToPer100g(it, servingGrams) }
            addedSugar = addedSugar?.let { NutritionNormalization.safeScaleToPer100g(it, servingGrams) }
            sodium = sodium?.let { NutritionNormalization.safeScaleToPer100g(it, servingGrams) }
            potassium = potassium?.let { NutritionNormalization.safeScaleToPer100g(it, servingGrams) }
            calcium = calcium?.let { NutritionNormalization.safeScaleToPer100g(it, servingGrams) }
            iron = iron?.let { NutritionNormalization.safeScaleToPer100g(it, servingGrams) }
        }

        // No food has more than 900 kcal per 100 g (pure fat is about 884): a higher reading is
        // almost certainly a kJ figure or an OCR error, so it is flagged for review (BUG-019)
        if (finalBasis == NutritionBasis.PER_100_G) {
            val high = calories?.takeIf { it.value > MAX_KCAL_PER_100G }
            if (high != null) {
                calories = high.copy(needsReview = true, confidence = ConfidenceLevel.LOW)
                warnings.add("${high.value} kcal per 100 g is more than any food contains. Check the energy value.")
            }
        }

        // Build canonical per100g map and derived per1g map
        val canonical100gMap = mutableMapOf<String, ParsedNutrientValue>()
        val per1gMap = mutableMapOf<String, ParsedNutrientValue>()

        fun registerNutrient(key: String, nutrient: ParsedNutrientValue?) {
            // Values of unknown basis have no per 100 g or per 1 g form
            if (nutrient != null && !basisUnknown) {
                canonical100gMap[key] = nutrient
                per1gMap[key] = NutritionNormalization.derivePer1g(nutrient)
            }
        }

        registerNutrient("calories", calories)
        registerNutrient("caloriesKj", caloriesKj)
        registerNutrient("protein", protein)
        registerNutrient("carbs", carbs)
        registerNutrient("fat", fat)
        registerNutrient("saturatedFat", satFat)
        registerNutrient("transFat", transFat)
        registerNutrient("cholesterol", cholesterol)
        registerNutrient("fiber", fiber)
        registerNutrient("sugar", sugar)
        registerNutrient("addedSugar", addedSugar)
        registerNutrient("sodium", sodium)
        registerNutrient("salt", salt)
        registerNutrient("potassium", potassium)
        registerNutrient("calcium", calcium)
        registerNutrient("iron", iron)

        // Merge any additional entries from per100gMap
        for ((k, v) in per100gMap) {
            if (!canonical100gMap.containsKey(k)) {
                canonical100gMap[k] = v
                per1gMap[k] = NutritionNormalization.derivePer1g(v)
            }
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
                warnings.add("Calculated macro calories (${expectedCal.roundToInt()}) differ from label calories (${actualCal.roundToInt()})")
            }
        }

        val overallConfidence = when {
            // A value of unknown basis is never "high confidence": the user must say what it is per
            confidenceScore >= 80 && !basisUnknown -> ConfidenceLevel.HIGH
            confidenceScore >= 50 -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW
        }

        return NutritionLabelDraft(
            foodName = null,
            brand = null,
            detectedBasis = finalBasis,
            sourceBasis = basis,
            normalizationNote = normalizationNote,
            servingSize = if (finalBasis == NutritionBasis.PER_100_G) 100.0 else (effectiveServingSize ?: 1.0),
            servingUnit = if (finalBasis == NutritionBasis.PER_100_G) ServingUnit.GRAMS else (effectiveServingUnit ?: ServingUnit.SERVING),
            servingGrams = effectiveServingGrams ?: if (finalBasis == NutritionBasis.PER_100_G) 100.0 else null,
            servingDescription = effectiveServingDesc,
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
            per100gValues = canonical100gMap,
            per1gValues = per1gMap,
            perServingValues = perServingMap,
            rdaValues = rdaMap,
            overallConfidence = overallConfidence,
            warnings = warnings,
            rawOcrText = fullText,
            debugData = spatialResult.debugData
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

        // "Per 100 g" in the languages packaging uses (per / pour / por / pro / je), "/100g",
        // and the UK "100g contains"
        val per100Units = PER_100_PATTERN.findAll(lowerText).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()
        if (per100Units.isNotEmpty()) {
            return if (per100Units.all { it == "ml" }) NutritionBasis.PER_100_ML else NutritionBasis.PER_100_G
        }

        // Per Serving / Per Serve / Per Portion patterns
        if (lowerText.contains("per serving") || lowerText.contains("per serve") ||
            lowerText.contains("per portion") || lowerText.contains("amount per serving") ||
            lowerText.contains("serving size")
        ) {
            return NutritionBasis.PER_SERVING
        }

        // "Per 1 cup (250 mL)", "Per bar (40g)", "Per biscuit": one unit of the food (BUG-020)
        if (PER_UNIT_WITH_WEIGHT_PATTERN.containsMatchIn(lowerText) || PER_ITEM_PATTERN.containsMatchIn(lowerText)) {
            return NutritionBasis.PER_SERVING
        }

        // The label does not say: never assume per 100 g, the user chooses (BUG-020)
        return NutritionBasis.UNKNOWN
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
            """(?:serving\s*size|per\s*serve|per\s*portion|approx\.?\s*serving)[:\s]*([0-9]+\/[0-9]+|[0-9]+(?:\.[0-9]+)?)?\s*([a-zA-Z]+)?(?:\s*\(([0-9]+(?:\.[0-9]+)?)\s*(g|gm|gms|grams|ml)\))?""",
            Pattern.CASE_INSENSITIVE
        )

        for (line in lines) {
            val matcher = pattern.matcher(line.text)
            if (matcher.find()) {
                val q1Str = matcher.group(1)?.trim()
                val q1 = if (q1Str != null && q1Str.contains("/")) {
                    val parts = q1Str.split("/")
                    val num = parts[0].toDoubleOrNull() ?: 1.0
                    val den = parts[1].toDoubleOrNull() ?: 1.0
                    if (den != 0.0) num / den else null
                } else {
                    q1Str?.toDoubleOrNull()
                }
                val u1 = matcher.group(2)?.trim()?.lowercase(Locale.ROOT)
                val q2 = matcher.group(3)?.toDoubleOrNull()
                val u2 = matcher.group(4)?.trim()?.lowercase(Locale.ROOT)

                desc = line.text.trim()

                if (q2 != null && (u2 == "g" || u2 == "gm" || u2 == "gms" || u2 == "grams" || u2 == "ml")) {
                    size = q1 ?: 1.0
                    unit = parseServingUnit(u1)
                    if (u2 != "ml") {
                        grams = q2
                    }
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

        // Header naming one unit of the food: "Per bar (40g)", "Per 1 cup (250 mL)" (BUG-020)
        if (size == null) {
            val perUnitPattern = Pattern.compile(
                """\bper\s+([0-9]+/[0-9]+|[0-9]+(?:\.[0-9]+)?)?\s*([a-z]+)\s*\(\s*([0-9]+(?:\.[0-9]+)?)\s*(g|gm|gms|grams|ml)\s*\)""",
                Pattern.CASE_INSENSITIVE
            )
            for (line in lines) {
                val matcher = perUnitPattern.matcher(line.text)
                if (!matcher.find()) continue
                val qtyText = matcher.group(1)
                val qty = if (qtyText != null && qtyText.contains("/")) {
                    val parts = qtyText.split("/")
                    val den = parts[1].toDoubleOrNull()
                    if (den != null && den != 0.0) (parts[0].toDoubleOrNull() ?: 1.0) / den else null
                } else {
                    qtyText?.toDoubleOrNull()
                }
                val weight = matcher.group(3)?.toDoubleOrNull()
                val weightUnit = matcher.group(4)?.lowercase(Locale.ROOT)
                size = qty ?: 1.0
                unit = parseServingUnit(matcher.group(2))
                desc = line.text.trim()
                // Only a weight in grams can convert to per 100 g; millilitres stay per serving
                if (weightUnit != "ml") grams = weight
                break
            }
        }

        if (grams == null) {
            val gramPattern = Pattern.compile("""\b([0-9]+(?:\.[0-9]+)?)\s*(?:g|gm|gms|grams)\b""", Pattern.CASE_INSENSITIVE)
            for (line in lines) {
                val t = line.text.lowercase(Locale.ROOT)
                if ((t.contains("serving") || t.contains("serve") || t.contains("portion")) &&
                    !t.contains("servings per") && !t.contains("total servings")
                ) {
                    val m = gramPattern.matcher(line.text)
                    while (m.find()) {
                        val gVal = m.group(1)?.toDoubleOrNull()
                        // Ignore 100g if it appears next to "per 100" or is the 100g column header
                        if (gVal == 100.0 && (t.contains("100g") || t.contains("100 g") || t.contains("100gm") || t.contains("per 100"))) {
                            continue
                        }
                        if (gVal != null && gVal > 0.0) {
                            grams = gVal
                            size = size ?: grams
                            unit = unit ?: ServingUnit.GRAMS
                            desc = line.text.trim()
                            break
                        }
                    }
                    if (grams != null) break
                }
            }
        }

        return ServingInfo(size, unit, grams, desc)
    }

    private fun parseServingUnit(raw: String?): ServingUnit {
        if (raw == null) return ServingUnit.SERVING
        val lower = raw.lowercase(Locale.ROOT)
        return when {
            // Whole words only: "serving", "bag" and "egg" are not grams
            lower in setOf("g", "gm", "gms", "gr", "gram", "grams") -> ServingUnit.GRAMS
            lower in setOf("ml", "milliliter", "milliliters", "millilitre", "millilitres") -> ServingUnit.MILLILITERS
            // "8 fl oz (240mL)": the unit word captured is "fl"
            lower == "fl" || lower.startsWith("floz") -> ServingUnit.FLUID_OUNCE
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
        val detectedCols = mutableListOf<NutritionColumn>()

        for (row in rows.take(15)) {
            val rowTextLower = row.text.lowercase(Locale.ROOT)
            val isHeaderCandidate = rowTextLower.contains("100g") || rowTextLower.contains("100 g") ||
                    rowTextLower.contains("100ml") || rowTextLower.contains("serve") ||
                    rowTextLower.contains("serving") || rowTextLower.contains("rda") ||
                    rowTextLower.contains("%")

            if (!isHeaderCandidate) continue

            // 1. Check individual elements
            for (element in row.elements) {
                val text = element.text.lowercase(Locale.ROOT)
                val sBox = element.spatialBounds ?: SpatialBox.fromAndroidRect(element.boundingBox)
                if (sBox != null) {
                    // Priority 1: RDA / % (must check before "serving" so "Per Serving %RDA" is RDA)
                    val type = when {
                        text.contains("%") || text.contains("rda") || text.contains("dv") || text.contains("daily value") -> ColumnType.RDA
                        text.contains("100g") || text.contains("100 g") || text.contains("100gm") || text.contains("100 gm") ||
                                text.contains("100ml") || text.contains("100 ml") -> ColumnType.PER_100G
                        text.contains("serve") || text.contains("serving") || text.contains("portion") -> ColumnType.PER_SERVING
                        else -> null
                    }
                    if (type != null) {
                        detectedCols.add(
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

            // 2. If row elements were merged into a single element or line
            val sBox = row.bounds
            if (sBox != null && detectedCols.map { it.type }.distinct().size < 2) {
                val text = rowTextLower
                val charW = sBox.width.toFloat() / max(1, text.length)

                val pRda = text.indexOf("%").takeIf { it >= 0 } ?: text.indexOf("rda")
                val p100 = text.indexOf("100g").takeIf { it >= 0 } ?: text.indexOf("100 g")
                val pServ = text.indexOf("serving").takeIf { it >= 0 && (pRda == null || abs(it - pRda) > 6) }
                    ?: text.indexOf("serve").takeIf { it >= 0 && (pRda == null || abs(it - pRda) > 6) }

                if (p100 != null && p100 >= 0) {
                    val cx = sBox.left + (p100 + 2) * charW
                    detectedCols.add(NutritionColumn(ColumnType.PER_100G, cx - 30, cx + 30, cx))
                }
                if (pServ != null && pServ >= 0) {
                    val cx = sBox.left + (pServ + 3) * charW
                    detectedCols.add(NutritionColumn(ColumnType.PER_SERVING, cx - 30, cx + 30, cx))
                }
                if (pRda != null && pRda >= 0) {
                    val cx = sBox.left + (pRda + 1) * charW
                    detectedCols.add(NutritionColumn(ColumnType.RDA, cx - 30, cx + 30, cx))
                }
            }

            if (detectedCols.map { it.type }.distinct().size >= 2) {
                break
            }
        }

        // Deduplicate detected columns by type, averaging their center X
        val finalCols = mutableListOf<NutritionColumn>()
        val byType = detectedCols.groupBy { it.type }
        for ((type, colsOfType) in byType) {
            val avgCenterX = colsOfType.map { it.centerX }.average().toFloat()
            val minLeft = colsOfType.minOf { it.left }
            val maxRight = colsOfType.maxOf { it.right }
            finalCols.add(NutritionColumn(type, minLeft, maxRight, avgCenterX))
        }

        finalCols.sortBy { it.centerX }
        return StructuralColumnLayout(finalCols)
    }

    private fun extractEnergy(
        rows: List<SpatialRow>,
        fullText: String,
        columns: StructuralColumnLayout,
        basis: NutritionBasis,
        servingGrams: Double?,
        per100gCollector: MutableMap<String, ParsedNutrientValue>? = null,
        perServingCollector: MutableMap<String, ParsedNutrientValue>? = null,
        rdaCollector: MutableMap<String, Double>? = null
    ): ParsedNutrientValue? {
        val number = NutritionNumericParser.NUMBER_PATTERN
        // The second figure is the kcal one: never a percentage ("1046 kJ (12%)") or another kJ
        // value ("680 kJ / 1700 kJ"), and never part of a longer number
        val dualPattern = Pattern.compile(
            "(?:energy|energie|calories|cal)[:\\s]*($number)\\s*(?:kj|kilojoules)?\\s*[/|\\(]\\s*($number)(?![0-9.,])(?!\\s*(?:%|kj|kilojoule))\\s*(?:kcal|cal|calories)?",
            Pattern.CASE_INSENSITIVE
        )

        for (row in rows) {
            // Commas stay as printed: "1,046 kJ" is a thousands separator, not 1.046 (BUG-022)
            val normalized = normalizeOcrMisreads(row.text)
            if ((normalized.contains("kj", ignoreCase = true) || normalized.contains("kilojoule", ignoreCase = true)) &&
                (normalized.contains("/") || normalized.contains("("))
            ) {
                val m = dualPattern.matcher(normalized)
                if (m.find()) {
                    val val1 = m.group(1)?.let { NutritionDigitCorrector.parseDecimal(it, "kj") }
                    val val2 = m.group(2)?.let { NutritionDigitCorrector.parseDecimal(it, "kcal") }
                    val kcalVal = val2 ?: val1
                    if (kcalVal != null && kcalVal > 0.0) {
                        val parsed = NutritionNormalization.normalizeToPer100g(
                            sourceValue = kcalVal,
                            sourceUnit = "kcal",
                            sourceBasis = basis,
                            servingMassG = servingGrams,
                            sourceText = row.text.trim(),
                            confidence = ConfidenceLevel.HIGH
                        )
                        if (basis == NutritionBasis.PER_SERVING) {
                            perServingCollector?.put("calories", parsed)
                        } else {
                            per100gCollector?.put("calories", parsed)
                        }
                        return parsed
                    }
                }
            }
        }

        val kcalRows = rows.filter { row ->
            val text = row.text.lowercase(Locale.ROOT)
            if (text.contains("calories from fat") || text.contains("cal from fat")) false
            else if ((text.contains("kj") || text.contains("kilojoule")) && !text.contains("kcal") && !text.contains("cal")) false
            else true
        }
        return extractNutrient(kcalRows, ENERGY_PATTERNS, "kcal", columns, basis, servingGrams, "calories", per100gCollector, perServingCollector, rdaCollector)
    }

    private fun extractKjEnergy(
        rows: List<SpatialRow>,
        fullText: String,
        columns: StructuralColumnLayout,
        basis: NutritionBasis,
        per100gCollector: MutableMap<String, ParsedNutrientValue>? = null,
        perServingCollector: MutableMap<String, ParsedNutrientValue>? = null
    ): ParsedNutrientValue? {
        val kjPattern = Pattern.compile(
            "(?:(?:energy|energy\\s*value)[:\\s]*)?(${NutritionNumericParser.NUMBER_PATTERN})\\s*(?:kj|kilojoules)\\b",
            Pattern.CASE_INSENSITIVE
        )

        for (row in rows) {
            val normalized = normalizeOcrMisreads(row.text)
            val m = kjPattern.matcher(normalized)
            if (m.find()) {
                val value = m.group(1)?.let { NutritionDigitCorrector.parseDecimal(it, "kj") }
                if (value != null && value > 0.0) {
                    val parsed = ParsedNutrientValue(
                        value = value,
                        unit = "kJ",
                        basis = basis,
                        confidence = ConfidenceLevel.HIGH,
                        rawMatch = row.text.trim(),
                        sourceText = row.text.trim()
                    )
                    if (basis == NutritionBasis.PER_SERVING) {
                        perServingCollector?.put("caloriesKj", parsed)
                    } else {
                        per100gCollector?.put("caloriesKj", parsed)
                    }
                    return parsed
                }
            }
        }
        return null
    }

    private data class RowToken(
        val value: Double,
        val operator: ComparisonOperator,
        val isPercent: Boolean,
        val rawText: String,
        val centerX: Float
    )

    private fun extractNutrient(
        rows: List<SpatialRow>,
        keywordPatterns: List<String>,
        expectedUnit: String,
        columnLayout: StructuralColumnLayout,
        basis: NutritionBasis,
        servingGrams: Double?,
        nutrientKey: String? = null,
        per100gCollector: MutableMap<String, ParsedNutrientValue>? = null,
        perServingCollector: MutableMap<String, ParsedNutrientValue>? = null,
        rdaCollector: MutableMap<String, Double>? = null
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

            val rowTokens = mutableListOf<RowToken>()
            val energyNumbers = mutableListOf<NutritionNumericParser.EnergyNumber>()
            val isEnergyRow = nutrientKey == "calories"
            val elementTexts = row.elements.map { it.text }
            var pendingOp = ComparisonOperator.EXACT
            for ((index, element) in row.elements.withIndex()) {
                // Commas stay as printed: "1,580mg" is 1580 mg, not 1.58 (BUG-022)
                val normalizedText = normalizeOcrMisreads(element.text)
                val isPercent = normalizedText.contains("%")
                val isLess = normalizedText.contains("<")
                val isGreater = normalizedText.contains(">")
                if (isLess) pendingOp = ComparisonOperator.LESS_THAN
                if (isGreater) pendingOp = ComparisonOperator.GREATER_THAN

                // An energy word may hold both figures ("1046/250"); other rows read one number per word
                val wordTokens = NutritionNumericParser.extractTokens(normalizedText, unitHint = expectedUnit)
                    .let { if (isEnergyRow) it else it.take(1) }
                if (wordTokens.isNotEmpty()) {
                    val sBox = element.spatialBounds ?: SpatialBox.fromAndroidRect(element.boundingBox)
                    val cX = sBox?.centerX?.toFloat() ?: 0f
                    val op = when {
                        isLess -> ComparisonOperator.LESS_THAN
                        isGreater -> ComparisonOperator.GREATER_THAN
                        else -> pendingOp
                    }
                    for ((tokenIndex, token) in wordTokens.withIndex()) {
                        rowTokens.add(RowToken(token.numericValue, op, isPercent, element.text.trim(), cX))
                        energyNumbers.add(
                            NutritionNumericParser.EnergyNumber(
                                unit = token.unit,
                                // The unit word only belongs to the last number of a word ("1046 kJ")
                                nextText = if (tokenIndex == wordTokens.lastIndex) NutritionNumericParser.unitTextAfter(elementTexts, index) else null,
                                indexInWord = tokenIndex,
                                numbersInWord = wordTokens.size,
                                value = token.numericValue
                            )
                        )
                    }
                    pendingOp = ComparisonOperator.EXACT
                }
            }

            // A calorie row may also print kJ ("Energy 1046 kJ 250 kcal"): only kcal counts (BUG-019).
            // Rows printed only in kJ never reach here; they are converted from the kJ value instead.
            if (isEnergyRow) {
                val units = NutritionNumericParser.energyUnits(normalizedRowText, energyNumbers)
                // Energy is never in grams: "Energy (per 100g) 250 kcal" holds one value, 250
                val kcalOnly = rowTokens.filterIndexed { i, _ ->
                    units[i] != "kj" && !NutritionNumericParser.isMassOrVolume(energyNumbers[i].unit)
                }
                if (kcalOnly.size != rowTokens.size) {
                    if (kcalOnly.none { !it.isPercent }) continue
                    rowTokens.clear()
                    rowTokens.addAll(kcalOnly)
                }
            }

            if (rowTokens.isEmpty()) continue

            var per100g: Double? = null
            var per100gOp = ComparisonOperator.EXACT
            var perServing: Double? = null
            var perServingOp = ComparisonOperator.EXACT
            var rda: Double? = null

            // Assign percent token strictly to %RDA
            val percentToken = rowTokens.find { it.isPercent }
            if (percentToken != null) {
                rda = percentToken.value
            }

            val valueTokens = rowTokens.filter { !it.isPercent }.sortedBy { it.centerX }
            val valueCols = columnLayout.columns.filter { it.type != ColumnType.RDA }

            if (valueCols.size >= 2) {
                val col100 = valueCols.find { it.type == ColumnType.PER_100G } ?: valueCols[0]
                val colServ = valueCols.find { it.type == ColumnType.PER_SERVING } ?: valueCols[1]

                if (valueTokens.size >= 2) {
                    val leftCol = if (col100.centerX < colServ.centerX) col100 else colServ

                    if (leftCol.type == ColumnType.PER_100G) {
                        per100g = valueTokens[0].value
                        per100gOp = valueTokens[0].operator
                        perServing = valueTokens[1].value
                        perServingOp = valueTokens[1].operator
                    } else {
                        perServing = valueTokens[0].value
                        perServingOp = valueTokens[0].operator
                        per100g = valueTokens[1].value
                        per100gOp = valueTokens[1].operator
                    }
                } else if (valueTokens.size == 1) {
                    val tok = valueTokens[0]
                    val dist100 = abs(tok.centerX - col100.centerX)
                    val distServ = abs(tok.centerX - colServ.centerX)

                    if (dist100 <= distServ) {
                        per100g = tok.value
                        per100gOp = tok.operator
                    } else {
                        perServing = tok.value
                        perServingOp = tok.operator
                    }
                }
            } else if (valueCols.size == 1 && valueTokens.isNotEmpty()) {
                val singleCol = valueCols[0]
                if (singleCol.type == ColumnType.PER_100G) {
                    per100g = valueTokens[0].value
                    per100gOp = valueTokens[0].operator
                    if (valueTokens.size >= 2) {
                        perServing = valueTokens[1].value
                        perServingOp = valueTokens[1].operator
                    }
                } else {
                    perServing = valueTokens[0].value
                    perServingOp = valueTokens[0].operator
                    if (valueTokens.size >= 2) {
                        per100g = valueTokens[1].value
                        per100gOp = valueTokens[1].operator
                    }
                }
            } else {
                if (basis == NutritionBasis.PER_SERVING) {
                    if (valueTokens.size >= 2) {
                        per100g = valueTokens[0].value
                        per100gOp = valueTokens[0].operator
                        perServing = valueTokens[1].value
                        perServingOp = valueTokens[1].operator
                    } else if (valueTokens.isNotEmpty()) {
                        perServing = valueTokens[0].value
                        perServingOp = valueTokens[0].operator
                    }
                } else {
                    if (valueTokens.isNotEmpty()) {
                        per100g = valueTokens[0].value
                        per100gOp = valueTokens[0].operator
                        if (valueTokens.size >= 2) {
                            perServing = valueTokens[1].value
                            perServingOp = valueTokens[1].operator
                        }
                    }
                }
            }

            val extracted = NutrientRowExtraction(
                keywordMatch = matchedKeyword,
                per100g = per100g,
                perServing = perServing,
                rda = rda,
                isEstimated = (per100gOp != ComparisonOperator.EXACT || perServingOp != ComparisonOperator.EXACT),
                per100gOp = per100gOp,
                perServingOp = perServingOp
            )

            if (nutrientKey != null) {
                if (extracted.per100g != null && per100gCollector != null) {
                    val isLess = extracted.per100gOp == ComparisonOperator.LESS_THAN
                    val effective100g = if (isLess) extracted.per100g / 2.0 else extracted.per100g
                    val normalized = NutritionNormalization.normalizeToPer100g(
                        sourceValue = effective100g,
                        sourceUnit = expectedUnit,
                        sourceBasis = NutritionBasis.PER_100_G,
                        servingMassG = servingGrams,
                        sourceText = row.text,
                        confidence = ConfidenceLevel.HIGH,
                        operator = extracted.per100gOp
                    )
                    per100gCollector[nutrientKey] = normalized.copy(
                        sourceValue = extracted.per100g,
                        isEstimated = extracted.isEstimated || isLess,
                        rawMatch = extracted.keywordMatch
                    )
                }
                if (extracted.perServing != null && perServingCollector != null) {
                    val isLess = extracted.perServingOp == ComparisonOperator.LESS_THAN
                    val effectiveServing = if (isLess) extracted.perServing / 2.0 else extracted.perServing
                    perServingCollector[nutrientKey] = ParsedNutrientValue(
                        value = effectiveServing,
                        unit = expectedUnit,
                        basis = NutritionBasis.PER_SERVING,
                        confidence = ConfidenceLevel.HIGH,
                        isEstimated = extracted.isEstimated || isLess,
                        operator = extracted.perServingOp,
                        rawMatch = extracted.keywordMatch,
                        sourceText = row.text,
                        sourceValue = extracted.perServing,
                        sourceUnit = expectedUnit,
                        sourceBasis = NutritionBasis.PER_SERVING,
                        normalizedPer100g = if (servingGrams != null && servingGrams > 0.0) ((effectiveServing * 100.0 / servingGrams) * 100.0).roundToLong() / 100.0 else null,
                        normalizedPer1g = if (servingGrams != null && servingGrams > 0.0) ((effectiveServing / servingGrams) * 10000.0).roundToLong() / 10000.0 else null
                    )
                }
                if (extracted.rda != null && rdaCollector != null) {
                    rdaCollector[nutrientKey] = extracted.rda
                }
            }

            return reconcileNutrient(extracted, servingGrams, expectedUnit, basis, row.text)
        }
        return null
    }

    private fun reconcileNutrient(
        extracted: NutrientRowExtraction,
        servingGrams: Double?,
        expectedUnit: String,
        requestedBasis: NutritionBasis,
        rowText: String = ""
    ): ParsedNutrientValue? {
        // PRIORITY 1: Explicit Per 100g value is authoritative
        if (extracted.per100g != null) {
            val isLess = extracted.per100gOp == ComparisonOperator.LESS_THAN
            val effective100g = if (isLess) extracted.per100g / 2.0 else extracted.per100g
            var needsReview = false
            var confidence = ConfidenceLevel.HIGH

            // If Per Serving column also exists, cross-check
            if (extracted.perServing != null && servingGrams != null && servingGrams > 0.0) {
                val effectiveServing = if (extracted.perServingOp == ComparisonOperator.LESS_THAN) extracted.perServing / 2.0 else extracted.perServing
                val matches = NutritionNormalization.crossCheckServingVs100g(effective100g, effectiveServing, servingGrams)
                if (!matches) {
                    needsReview = true
                    confidence = ConfidenceLevel.MEDIUM
                }
            }

            return NutritionNormalization.normalizeToPer100g(
                sourceValue = effective100g,
                sourceUnit = expectedUnit,
                sourceBasis = NutritionBasis.PER_100_G,
                servingMassG = servingGrams,
                sourceText = rowText,
                confidence = confidence,
                needsReview = needsReview,
                operator = extracted.per100gOp
            ).copy(
                sourceValue = extracted.per100g,
                isEstimated = extracted.isEstimated || isLess,
                rawMatch = extracted.keywordMatch
            )
        }

        // PRIORITY 2: Per Serving column -> normalize to PER_100_G if serving mass is known
        if (extracted.perServing != null) {
            val isLess = extracted.perServingOp == ComparisonOperator.LESS_THAN
            val effectiveServing = if (isLess) extracted.perServing / 2.0 else extracted.perServing
            val normalized = NutritionNormalization.normalizeToPer100g(
                sourceValue = effectiveServing,
                sourceUnit = expectedUnit,
                sourceBasis = NutritionBasis.PER_SERVING,
                servingMassG = servingGrams,
                sourceText = rowText,
                confidence = if (servingGrams != null && servingGrams > 0.0) ConfidenceLevel.HIGH else ConfidenceLevel.LOW,
                needsReview = (servingGrams == null || servingGrams <= 0.0),
                operator = extracted.perServingOp
            )
            return normalized.copy(
                sourceValue = extracted.perServing,
                isEstimated = extracted.isEstimated || isLess || normalized.isDerived,
                rawMatch = extracted.keywordMatch
            )
        }

        return null
    }

    // =========================================================================
    // REGEX KEYWORD PATTERNS
    // =========================================================================

    private val PER_100_PATTERN = Regex(
        """(?:\b(?:per|pour|por|pro|je|para)\s*|/\s*)100\s*(g|gm|gms|gr|grams?|ml)\b|\b100\s*(g|ml)\s+contains\b"""
    )

    /** "per 1 cup (250 ml)", "per bar (40g)", "per 2 biscuits (25 g)" */
    private val PER_UNIT_WITH_WEIGHT_PATTERN = Regex(
        """\bper\s+(?:[0-9]+(?:\.[0-9]+)?\s*|[0-9]+/[0-9]+\s*)?[a-z]+\s*\(\s*[0-9]+(?:\.[0-9]+)?\s*(?:g|gm|gms|grams?|ml)\s*\)"""
    )

    private val PER_ITEM_PATTERN = Regex(
        """\bper\s+(?:[0-9]+\s+)?(?:bar|pack|packet|piece|pouch|can|bottle|biscuit|cookie|cracker|slice|sachet|scoop|stick|cup|tbsp|tsp|bowl|container|tablet|capsule|unit|egg|muffin|portion)s?\b"""
    )

    private val ENERGY_PATTERNS = listOf(
        "calories", "energy \\(kcal\\)", "energie \\(kcal\\)", "energy kcal", "energie kcal", "energy value", "valeur energetique", "energy", "energie", "cal", "energetic value", "brennwert"
    )

    private val PROTEIN_PATTERNS = listOf(
        "total protein", "protein", "proteins", "proteine", "proteines", "eiweiss"
    )

    private val CARBS_PATTERNS = listOf(
        "total carbohydrate", "total carbohydrates", "carbohydrate", "carbohydrates", "total carbs", "carbs", "glucides", "kohlenhydrate"
    )

    private val FAT_PATTERNS = listOf(
        "total fat", "total fats", "fat", "fats", "lipids", "lipides", "graisses", "matieres grasses", "matiere grasse", "fett"
    )

    private val SAT_FAT_PATTERNS = listOf(
        "saturated fat", "saturated fatty acids", "saturated fats", "saturates", "of which saturates", "sat fat", "sat. fat", "acides gras satures"
    )

    private val TRANS_FAT_PATTERNS = listOf(
        "trans fat", "trans fatty acids", "trans fats", "trans-fat", "acides gras trans"
    )

    private val CHOLESTEROL_PATTERNS = listOf(
        "cholesterol", "cholest.", "cholest"
    )

    private val FIBER_PATTERNS = listOf(
        "dietary fiber", "dietary fibre", "fiber", "fibre", "fibres", "ballaststoffe"
    )

    private val SUGAR_PATTERNS = listOf(
        "total sugars", "total sugar", "sugars", "sugar", "of which sugars", "dont sucres", "zucker"
    )

    private val ADDED_SUGAR_PATTERNS = listOf(
        "added sugars", "added sugar", "includes .* added sugars", "added sucres"
    )

    private val SODIUM_PATTERNS = listOf(
        "sodium", "natrium"
    )

    private val SALT_PATTERNS = listOf(
        "salt", "equivalent as salt", "sel\\b", "sel", "salz"
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
        var normalized = NutritionDigitCorrector.correctRowText(text)
        // Text corrections
        normalized = normalized.replace(Regex("""\bProte1n\b""", RegexOption.IGNORE_CASE), "Protein")
        normalized = normalized.replace(Regex("""\bCarbohydrat\s+e\b""", RegexOption.IGNORE_CASE), "Carbohydrate")
        normalized = normalized.replace(Regex("""\bSodlum\b""", RegexOption.IGNORE_CASE), "Sodium")
        normalized = normalized.replace(Regex("""\bCholestero1\b""", RegexOption.IGNORE_CASE), "Cholesterol")
        return normalized
    }
}
