package com.macrobase.app.feature.scanner

import com.macrobase.app.domain.model.scanner.ConfidenceLevel
import com.macrobase.app.domain.model.scanner.NutritionBasis
import com.macrobase.app.domain.model.scanner.NutritionLabelDraft
import com.macrobase.app.domain.model.scanner.ParsedNutrientValue
import kotlin.math.abs
import kotlin.math.max

/**
 * Extracted candidate value from an individual OCR pass or spatial parsing attempt.
 */
data class CandidateValue(
    val value: Double,
    val unit: String,
    val sourcePassIndex: Int,
    val sourceVariantName: String,
    val sourceText: String,
    val boundingBox: NutritionLabelParser.SpatialBox?,
    val confidence: ConfidenceLevel = ConfidenceLevel.HIGH,
    val isEstimated: Boolean = false,
    val basis: NutritionBasis = NutritionBasis.UNKNOWN
)

/**
 * Result of validating and resolving candidate extractions for a specific nutritional field.
 */
data class FieldValidationResult(
    val resolvedValue: ParsedNutrientValue?,
    val warnings: List<String> = emptyList(),
    val needsReview: Boolean = false
)

/**
 * Comprehensive nutrition validation and conflict resolution engine.
 */
class NutritionValidator {

    /**
     * Resolves multiple candidate values extracted across different OCR passes for the same nutrient.
     */
    fun resolveCandidates(
        nutrientName: String,
        candidates: List<CandidateValue>,
        expectedUnit: String,
        basis: NutritionBasis
    ): FieldValidationResult {
        if (candidates.isEmpty()) {
            return FieldValidationResult(resolvedValue = null)
        }

        val warnings = mutableListOf<String>()

        // 1. If only 1 candidate exists
        if (candidates.size == 1) {
            val cand = candidates.first()
            val rangeWarning = checkRangeSanity(nutrientName, cand.value, cand.unit, basis)
            if (rangeWarning != null) warnings.add(rangeWarning)

            val parsed = ParsedNutrientValue(
                value = cand.value,
                unit = cand.unit,
                basis = cand.basis.takeIf { it != NutritionBasis.UNKNOWN } ?: basis,
                confidence = if (rangeWarning != null) ConfidenceLevel.LOW else cand.confidence,
                isEstimated = cand.isEstimated,
                rawMatch = cand.sourceText,
                sourceText = cand.sourceText,
                sourceBBox = cand.boundingBox?.let { "l=${it.left},t=${it.top},r=${it.right},b=${it.bottom}" },
                needsReview = rangeWarning != null || cand.confidence == ConfidenceLevel.LOW,
                extractionMethod = "ensemble_pass_${cand.sourcePassIndex}_${cand.sourceVariantName}"
            )
            return FieldValidationResult(parsed, warnings, parsed.needsReview)
        }

        // 2. Multiple candidates: check agreement
        // Group candidates by approximately matching values (within 5% or 0.2 difference)
        val clusters = mutableListOf<MutableList<CandidateValue>>()
        for (cand in candidates) {
            val matchedCluster = clusters.find { cl ->
                cl.any { abs(it.value - cand.value) <= max(0.2, cand.value * 0.05) && it.unit.equals(cand.unit, ignoreCase = true) }
            }
            if (matchedCluster != null) {
                matchedCluster.add(cand)
            } else {
                clusters.add(mutableListOf(cand))
            }
        }

        // Select the cluster with the highest agreement count
        val bestCluster = clusters.maxByOrNull { it.size } ?: clusters.first()
        val agreementCount = bestCluster.size
        val representative = bestCluster.first()

        val hasDisagreement = clusters.size > 1
        if (hasDisagreement) {
            warnings.add("Multiple OCR passes disagreed on $nutrientName (${candidates.map { "${it.value}${it.unit}" }.distinct().joinToString(", ")})")
        }

        val rangeWarning = checkRangeSanity(nutrientName, representative.value, representative.unit, basis)
        if (rangeWarning != null) warnings.add(rangeWarning)

        val confidence = when {
            rangeWarning != null -> ConfidenceLevel.LOW
            agreementCount >= 2 && !hasDisagreement -> ConfidenceLevel.HIGH
            agreementCount >= 2 -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW
        }

        val needsReview = hasDisagreement || rangeWarning != null || confidence == ConfidenceLevel.LOW

        val parsed = ParsedNutrientValue(
            value = representative.value,
            unit = representative.unit,
            basis = representative.basis.takeIf { it != NutritionBasis.UNKNOWN } ?: basis,
            confidence = confidence,
            isEstimated = representative.isEstimated,
            rawMatch = representative.sourceText,
            sourceText = representative.sourceText,
            sourceBBox = representative.boundingBox?.let { "l=${it.left},t=${it.top},r=${it.right},b=${it.bottom}" },
            needsReview = needsReview,
            extractionMethod = if (agreementCount > 1) "ensemble_agreement_$agreementCount" else "best_pass_${representative.sourceVariantName}"
        )

        return FieldValidationResult(parsed, warnings, needsReview)
    }

    /**
     * Checks macronutrient consistency (Atwater factors: 4 P + 4 C + 9 F ≈ Calories).
     * Used strictly as an extraction validation signal, never overwriting numbers.
     */
    fun validateMacroConsistency(
        calories: Double?,
        protein: Double?,
        carbs: Double?,
        fat: Double?
    ): String? {
        if (calories == null || protein == null || carbs == null || fat == null) return null
        if (calories < 15.0) return null

        val expectedCalories = protein * 4.0 + carbs * 4.0 + fat * 9.0
        val diff = abs(expectedCalories - calories)
        val tolerance = max(20.0, calories * 0.40)

        return if (diff > tolerance) {
            "Calculated macro calories (${expectedCalories.toInt()} kcal) differ notably from label calories (${calories.toInt()} kcal)"
        } else {
            null
        }
    }

    /**
     * Inspects value sanity ranges on common packaging units.
     */
    fun checkRangeSanity(nutrientName: String, value: Double, unit: String, basis: NutritionBasis): String? {
        if (value < 0.0) {
            return "$nutrientName cannot be negative ($value$unit)"
        }

        val nameLower = nutrientName.lowercase()
        val isPer100g = basis == NutritionBasis.PER_100_G

        if (isPer100g && unit.equals("g", ignoreCase = true)) {
            if (nameLower.contains("protein") && value > 100.0) {
                return "Protein exceeds 100g per 100g ($value g)"
            }
            if (nameLower.contains("carbohydrate") && value > 100.0) {
                return "Carbohydrates exceed 100g per 100g ($value g)"
            }
            if (nameLower.contains("fat") && !nameLower.contains("calories") && value > 100.0) {
                return "Fat exceeds 100g per 100g ($value g)"
            }
            if (nameLower.contains("sugar") && value > 100.0) {
                return "Sugars exceed 100g per 100g ($value g)"
            }
        }

        if (nameLower.contains("calories") || nameLower.contains("energy")) {
            if (unit.equals("kcal", ignoreCase = true) && isPer100g && value > 950.0) {
                return "Calories exceed 950 kcal per 100g ($value kcal)"
            }
        }

        if (nameLower.contains("sodium") && unit.equals("mg", ignoreCase = true)) {
            if (isPer100g && value > 40000.0) {
                return "Sodium unusually high ($value mg per 100g)"
            }
        }

        return null
    }
}
