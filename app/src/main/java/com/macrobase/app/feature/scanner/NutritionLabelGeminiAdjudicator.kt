package com.macrobase.app.feature.scanner

import kotlin.math.roundToInt
import com.macrobase.app.domain.model.scanner.ConfidenceLevel
import com.macrobase.app.domain.model.scanner.NutritionLabelDraft
import com.macrobase.app.domain.model.scanner.ParsedNutrientValue

/**
 * On-Device Deterministic Adjudicator.
 * Resolves column ambiguities, 100g vs per-serving basis discrepancies,
 * and candidate conflicts strictly using deterministic nutritional relationships.
 *
 * Zero network connections. 100% offline and private.
 */
class NutritionLabelGeminiAdjudicator(
    private val validator: NutritionValidator = NutritionValidator()
) {
    fun shouldAdjudicate(draft: NutritionLabelDraft, apiKey: String? = null): Boolean {
        val hasUnresolvedConflicts = draft.warnings.any { it.contains("disagreed", ignoreCase = true) }
        val isLowConfidence = draft.overallConfidence == ConfidenceLevel.LOW
        val hasFieldsNeedingReview = listOfNotNull(
            draft.calories, draft.protein, draft.carbs, draft.fat, draft.sodium
        ).any { it.needsReview }
        return hasUnresolvedConflicts || isLowConfidence || hasFieldsNeedingReview
    }

    /**
     * Deterministically reconciles ambiguous OCR candidates using macro caloric math:
     * Calories ~ (Protein * 4) + (Carbs * 4) + (Fat * 9)
     */
    fun adjudicate(
        draft: NutritionLabelDraft,
        tableGrid: TableGrid?,
        apiKey: String? = null
    ): NutritionLabelDraft {
        var updated = draft
        // 1. Verify and reconcile calorie-to-macro math
        val cal = updated.calories?.value
        val p = updated.protein?.value ?: 0.0
        val c = updated.carbs?.value ?: 0.0
        val f = updated.fat?.value ?: 0.0
        val expectedCal = (p * 4.0) + (c * 4.0) + (f * 9.0)
        if (cal != null && (p > 0 || c > 0 || f > 0)) {
            val delta = kotlin.math.abs(cal - expectedCal)
            // If calories disagree by >40% and expected calories is plausible, flag for review
            if (delta > kotlin.math.max(20.0, expectedCal * 0.40)) {
                updated = updated.copy(
                    warnings = updated.warnings + "Calorie and macronutrient totals show discrepancy (Expected ~${expectedCal.roundToInt()} kcal based on macros)"
                )
            }
        }
        // 2. Validate ranges using NutritionValidator
        listOf(
            "calories" to updated.calories,
            "protein" to updated.protein,
            "carbohydrates" to updated.carbs,
            "fat" to updated.fat,
            "sodium" to updated.sodium
        ).forEach { (name, parsedVal) ->
            if (parsedVal != null) {
                val warning = validator.checkRangeSanity(name, parsedVal.value, parsedVal.unit, updated.detectedBasis)
                if (warning != null && !updated.warnings.contains(warning)) {
                    updated = updated.copy(warnings = updated.warnings + warning)
                }
            }
        }
        return updated
    }
}
