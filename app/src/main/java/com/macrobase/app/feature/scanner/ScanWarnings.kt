package com.macrobase.app.feature.scanner

import com.macrobase.app.domain.model.scanner.ConfidenceLevel
import com.macrobase.app.domain.model.scanner.NutritionBasis
import com.macrobase.app.domain.model.scanner.NutritionLabelDraft
import com.macrobase.app.domain.model.scanner.ParsedNutrientValue

/**
 * A draft's final warnings, built once every step has run (BUG-042).
 *
 * Critical warnings go to [NutritionLabelDraft.criticalWarnings]. They cover a value no food can
 * have and calories that do not match the macros, and the screens always show them in full. The
 * range check runs on every scan, whatever the confidence, and marks the field for review.
 *
 * The parser's notes, then the image-quality hints, stay in [NutritionLabelDraft.warnings].
 */
fun assembleScanWarnings(
    draft: NutritionLabelDraft,
    qualityWarnings: List<String>,
    validator: NutritionValidator = NutritionValidator()
): NutritionLabelDraft {
    val (parserCritical, parserNotes) = draft.warnings.partition { warning ->
        CRITICAL_PARSER_MARKERS.any { warning.contains(it, ignoreCase = true) }
    }
    val checked = withRangeWarnings(draft.copy(criticalWarnings = draft.criticalWarnings + parserCritical), validator)

    // The one calorie-and-macro check; the parser and the old adjudicator each added their own
    val macroWarning = validator.validateMacroConsistency(
        calories = draft.calories?.value,
        protein = draft.protein?.value,
        carbs = draft.carbs?.value,
        fat = draft.fat?.value
    )

    return checked.copy(
        criticalWarnings = (checked.criticalWarnings + listOfNotNull(macroWarning)).distinct(),
        warnings = (parserNotes + qualityWarnings).distinct()
    )
}

/**
 * [draft] with a critical warning for every value no food can have, judged per 100 g whatever
 * the basis: per 100 ml as per 100 g, per 1 g times 100, per serving through its gram weight.
 * A value of unknown basis is judged once the user says what it is per, by calling this again
 * on [NutritionLabelDraft.withOutputBasis]. A failing value is marked for review.
 */
fun withRangeWarnings(draft: NutritionLabelDraft, validator: NutritionValidator = NutritionValidator()): NutritionLabelDraft {
    val warnings = mutableListOf<String>()
    // The parser already flags impossible calories per 100 g; one warning about it is enough
    val caloriesFlagged = draft.criticalWarnings.any { it.contains("kcal per 100 g", ignoreCase = true) }

    fun check(name: String, value: ParsedNutrientValue?): ParsedNutrientValue? {
        if (value == null) return null
        val per100 = per100Value(draft, value.value) ?: return value
        val warning = validator.checkRangeSanity(name, per100, value.unit, NutritionBasis.PER_100_G) ?: return value
        if (!(name == "calories" && caloriesFlagged)) warnings.add(warning)
        return value.copy(needsReview = true, confidence = ConfidenceLevel.LOW)
    }

    val checked = draft.copy(
        calories = check("calories", draft.calories),
        protein = check("protein", draft.protein),
        carbs = check("carbohydrates", draft.carbs),
        fat = check("fat", draft.fat),
        saturatedFat = check("saturated fat", draft.saturatedFat),
        sugar = check("sugar", draft.sugar),
        sodium = check("sodium", draft.sodium)
    )
    // Saturated fat is part of total fat, so more of it is a misread (1.5 g read as 15 g).
    // Both values share the draft's basis, so they compare directly.
    val saturatedFat = checked.saturatedFat
    val fat = checked.fat
    val withFatCheck = if (saturatedFat != null && fat != null && saturatedFat.value > fat.value) {
        warnings.add(SATURATED_FAT_OVER_TOTAL_WARNING)
        checked.copy(saturatedFat = saturatedFat.copy(needsReview = true, confidence = ConfidenceLevel.LOW))
    } else {
        checked
    }
    return withFatCheck.copy(criticalWarnings = (draft.criticalWarnings + warnings).distinct())
}

/** No numbers, so the warning stays one line when the user switches the basis. */
const val SATURATED_FAT_OVER_TOTAL_WARNING = "Saturated fat is more than total fat. Check both values on the label."

/** [value] per 100 g (or ml) for the draft's basis, or null when the basis cannot tell. */
private fun per100Value(draft: NutritionLabelDraft, value: Double): Double? = when (draft.detectedBasis) {
    NutritionBasis.PER_100_G, NutritionBasis.PER_100_ML -> value
    NutritionBasis.PER_1_G -> value * 100.0
    NutritionBasis.PER_SERVING, NutritionBasis.PER_PACKAGE ->
        draft.servingGrams?.takeIf { it > 0.0 }?.let { value * 100.0 / it }
    else -> null
}

/** Parser warnings that mean a value cannot be right as read. */
private val CRITICAL_PARSER_MARKERS = listOf("more than any food contains")
