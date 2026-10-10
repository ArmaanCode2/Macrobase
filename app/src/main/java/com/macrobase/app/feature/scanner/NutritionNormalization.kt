package com.macrobase.app.feature.scanner

import com.macrobase.app.domain.model.scanner.ComparisonOperator
import com.macrobase.app.domain.model.scanner.ConfidenceLevel
import com.macrobase.app.domain.model.scanner.NutritionBasis
import com.macrobase.app.domain.model.scanner.ParsedNutrientValue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Pure, deterministic mathematical engine for normalizing packaging nutrition values
 * to canonical PER_100_G and deriving PER_1_G representations.
 *
 * Implements:
 * - Direct Per 100g passthrough (no recalculation)
 * - Per-serving to per-100g normalization based strictly on verified gram mass
 * - Per-1g derivation from canonical per-100g (value / 100.0)
 * - Strict double-conversion protection (never converts if already PER_100_G)
 * - Mass-only normalization (rejects volume-to-mass assumption 1ml != 1g)
 * - Rejection of unknown serving mass (never invents gram weight)
 * - Cross-check validation between dual printed columns
 */
object NutritionNormalization {

    /**
     * Normalizes a nutrient value to canonical PER_100_G.
     */
    fun normalizeToPer100g(
        sourceValue: Double,
        sourceUnit: String,
        sourceBasis: NutritionBasis,
        servingMassG: Double?,
        sourceText: String? = null,
        sourceColumn: String? = null,
        confidence: ConfidenceLevel = ConfidenceLevel.HIGH,
        needsReview: Boolean = false,
        extractionMethod: String? = null,
        operator: ComparisonOperator = ComparisonOperator.EXACT
    ): ParsedNutrientValue {
        val isOpEstimated = operator != ComparisonOperator.EXACT
        when (sourceBasis) {
            NutritionBasis.PER_100_G -> {
                // Rule: If already Per 100g, DO NOT recalculate. Values are already canonical.
                val per1g = round4(sourceValue / 100.0)
                return ParsedNutrientValue(
                    value = sourceValue,
                    unit = sourceUnit,
                    basis = NutritionBasis.PER_100_G,
                    confidence = confidence,
                    isEstimated = isOpEstimated,
                    operator = operator,
                    rawMatch = sourceText,
                    sourceText = sourceText,
                    sourceBBox = null,
                    needsReview = needsReview,
                    extractionMethod = extractionMethod ?: "EXPLICIT_PER_100G",
                    sourceValue = sourceValue,
                    sourceUnit = sourceUnit,
                    sourceBasis = NutritionBasis.PER_100_G,
                    sourceColumn = sourceColumn,
                    normalizedPer100g = sourceValue,
                    normalizedPer1g = per1g,
                    isDerived = false
                )
            }

            NutritionBasis.PER_SERVING, NutritionBasis.PER_PACKAGE -> {
                // Rule: Normalization requires verified mass in grams.
                if (servingMassG != null && servingMassG > 0.0) {
                    val factor = 100.0 / servingMassG
                    val normalized100g = round2(sourceValue * factor)
                    val normalized1g = round4(normalized100g / 100.0)

                    return ParsedNutrientValue(
                        value = normalized100g,
                        unit = sourceUnit,
                        basis = NutritionBasis.PER_100_G,
                        confidence = confidence,
                        isEstimated = true,
                        operator = operator,
                        rawMatch = sourceText,
                        sourceText = sourceText,
                        sourceBBox = null,
                        needsReview = needsReview,
                        extractionMethod = extractionMethod ?: "NORMALIZED_FROM_SERVING_${servingMassG}G",
                        sourceValue = sourceValue,
                        sourceUnit = sourceUnit,
                        sourceBasis = sourceBasis,
                        sourceColumn = sourceColumn,
                        normalizedPer100g = normalized100g,
                        normalizedPer1g = normalized1g,
                        isDerived = true
                    )
                } else {
                    // Rule: Serving size without gram mass (e.g. "1 bar", "1 package" with no grams).
                    // Do NOT invent a mass. Leave normalizedPer100g null and flag needsReview.
                    return ParsedNutrientValue(
                        value = sourceValue,
                        unit = sourceUnit,
                        basis = sourceBasis,
                        confidence = ConfidenceLevel.LOW,
                        isEstimated = isOpEstimated,
                        operator = operator,
                        rawMatch = sourceText,
                        sourceText = sourceText,
                        sourceBBox = null,
                        needsReview = true,
                        extractionMethod = extractionMethod ?: "UNNORMALIZED_NO_GRAM_MASS",
                        sourceValue = sourceValue,
                        sourceUnit = sourceUnit,
                        sourceBasis = sourceBasis,
                        sourceColumn = sourceColumn,
                        normalizedPer100g = null,
                        normalizedPer1g = null,
                        isDerived = false
                    )
                }
            }

            NutritionBasis.PER_100_ML -> {
                // Rule: Volume-based label. Do not assume 1ml = 1g without explicit density.
                val per1ml = round4(sourceValue / 100.0)
                return ParsedNutrientValue(
                    value = sourceValue,
                    unit = sourceUnit,
                    basis = NutritionBasis.PER_100_ML,
                    confidence = confidence,
                    isEstimated = isOpEstimated,
                    operator = operator,
                    rawMatch = sourceText,
                    sourceText = sourceText,
                    sourceBBox = null,
                    needsReview = needsReview,
                    extractionMethod = extractionMethod ?: "EXPLICIT_PER_100ML",
                    sourceValue = sourceValue,
                    sourceUnit = sourceUnit,
                    sourceBasis = NutritionBasis.PER_100_ML,
                    sourceColumn = sourceColumn,
                    normalizedPer100g = null,
                    normalizedPer1g = per1ml,
                    isDerived = false
                )
            }

            NutritionBasis.PER_1_G -> {
                // Already per 1g -> canonical per 100g is sourceValue * 100
                val norm100g = round2(sourceValue * 100.0)
                return ParsedNutrientValue(
                    value = norm100g,
                    unit = sourceUnit,
                    basis = NutritionBasis.PER_100_G,
                    confidence = confidence,
                    isEstimated = true,
                    operator = operator,
                    rawMatch = sourceText,
                    sourceText = sourceText,
                    sourceBBox = null,
                    needsReview = needsReview,
                    extractionMethod = extractionMethod ?: "DERIVED_FROM_1G",
                    sourceValue = sourceValue,
                    sourceUnit = sourceUnit,
                    sourceBasis = NutritionBasis.PER_1_G,
                    sourceColumn = sourceColumn,
                    normalizedPer100g = norm100g,
                    normalizedPer1g = sourceValue,
                    isDerived = true
                )
            }

            NutritionBasis.UNKNOWN -> {
                return ParsedNutrientValue(
                    value = sourceValue,
                    unit = sourceUnit,
                    basis = NutritionBasis.UNKNOWN,
                    confidence = ConfidenceLevel.LOW,
                    isEstimated = isOpEstimated,
                    operator = operator,
                    rawMatch = sourceText,
                    sourceText = sourceText,
                    sourceBBox = null,
                    needsReview = true,
                    extractionMethod = extractionMethod ?: "UNKNOWN_BASIS",
                    sourceValue = sourceValue,
                    sourceUnit = sourceUnit,
                    sourceBasis = NutritionBasis.UNKNOWN,
                    sourceColumn = sourceColumn,
                    normalizedPer100g = null,
                    normalizedPer1g = null,
                    isDerived = false
                )
            }
        }
    }

    /**
     * Derives a PER_1_G representation strictly from a canonical PER_100_G value.
     */
    fun derivePer1g(per100g: ParsedNutrientValue): ParsedNutrientValue {
        val canonical100g = per100g.normalizedPer100g ?: per100g.value
        val derived1g = round4(canonical100g / 100.0)

        return per100g.copy(
            value = derived1g,
            basis = NutritionBasis.PER_1_G,
            normalizedPer100g = canonical100g,
            normalizedPer1g = derived1g,
            operator = per100g.operator,
            isDerived = true
        )
    }

    /**
     * Cross-checks a printed per-100g value against a printed per-serving value.
     * Returns true if the mathematical relationship holds within a sensible tolerance.
     */
    fun crossCheckServingVs100g(per100g: Double, perServing: Double, servingMassG: Double): Boolean {
        if (servingMassG <= 0.0) return false
        val expectedServing = per100g * servingMassG / 100.0
        val diff = abs(expectedServing - perServing)
        val tolerance = max(2.0, perServing * 0.20) // 20% or 2 units tolerance for label rounding
        return diff <= tolerance
    }

    /**
     * Prevents double conversion on a value.
     * If the value is already marked PER_100_G, it returns the value directly.
     */
    fun safeScaleToPer100g(
        current: ParsedNutrientValue,
        servingMassG: Double?
    ): ParsedNutrientValue {
        if (current.basis == NutritionBasis.PER_100_G || current.sourceBasis == NutritionBasis.PER_100_G) {
            // Already canonical per-100-g. Do NOT convert again!
            return current
        }
        return normalizeToPer100g(
            sourceValue = current.sourceValue ?: current.value,
            sourceUnit = current.sourceUnit ?: current.unit,
            sourceBasis = current.sourceBasis.takeIf { it != NutritionBasis.UNKNOWN } ?: current.basis,
            servingMassG = servingMassG,
            sourceText = current.sourceText,
            sourceColumn = current.sourceColumn,
            confidence = current.confidence,
            needsReview = current.needsReview,
            extractionMethod = current.extractionMethod,
            operator = current.operator
        )
    }

    private fun round2(v: Double): Double = (v * 100.0).roundToLong() / 100.0
    private fun round4(v: Double): Double = (v * 10000.0).roundToLong() / 10000.0
}
