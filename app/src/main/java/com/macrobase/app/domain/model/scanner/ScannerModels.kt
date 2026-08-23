package com.macrobase.app.domain.model.scanner

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import com.macrobase.app.domain.model.ServingUnit

/**
 * Standard basis of nutrition measurements extracted from packaging labels.
 */
@Parcelize
enum class NutritionBasis(val displayName: String) : Parcelable {
    PER_100_G("Per 100g"),
    PER_100_ML("Per 100ml"),
    PER_SERVING("Per Serving"),
    UNKNOWN("Unknown Basis")
}

/**
 * Confidence rating of OCR and Parser extraction.
 */
@Parcelize
enum class ConfidenceLevel : Parcelable {
    HIGH,
    MEDIUM,
    LOW
}

/**
 * Extracted nutrient value with metadata and confidence.
 */
@Parcelize
data class ParsedNutrientValue(
    val value: Double,
    val unit: String,
    val basis: NutritionBasis = NutritionBasis.UNKNOWN,
    val confidence: ConfidenceLevel = ConfidenceLevel.HIGH,
    val isEstimated: Boolean = false,
    val rawMatch: String? = null
) : Parcelable

/**
 * Structured nutrition label draft produced by the on-device parser.
 * Serves as an uncommitted draft to pre-fill the Create/Edit Custom Food form.
 */
@Parcelize
data class NutritionLabelDraft(
    val foodName: String? = null,
    val brand: String? = null,
    val detectedBasis: NutritionBasis = NutritionBasis.UNKNOWN,
    val servingSize: Double? = null,
    val servingUnit: ServingUnit? = null,
    val servingGrams: Double? = null,
    val servingDescription: String? = null,
    val calories: ParsedNutrientValue? = null,
    val caloriesKj: ParsedNutrientValue? = null,
    val protein: ParsedNutrientValue? = null,
    val carbs: ParsedNutrientValue? = null,
    val fat: ParsedNutrientValue? = null,
    val saturatedFat: ParsedNutrientValue? = null,
    val transFat: ParsedNutrientValue? = null,
    val cholesterol: ParsedNutrientValue? = null,
    val fiber: ParsedNutrientValue? = null,
    val sugar: ParsedNutrientValue? = null,
    val addedSugar: ParsedNutrientValue? = null,
    val sodium: ParsedNutrientValue? = null,
    val salt: ParsedNutrientValue? = null,
    val potassium: ParsedNutrientValue? = null,
    val calcium: ParsedNutrientValue? = null,
    val iron: ParsedNutrientValue? = null,
    val vitamins: Map<String, ParsedNutrientValue> = emptyMap(),
    val overallConfidence: ConfidenceLevel = ConfidenceLevel.MEDIUM,
    val warnings: List<String> = emptyList(),
    val rawOcrText: String = ""
) : Parcelable {
    /**
     * Total count of recognized nutritional fields (calories, macros, micronutrients).
     */
    val recognizedFieldCount: Int
        get() {
            var count = 0
            if (calories != null || caloriesKj != null) count++
            if (protein != null) count++
            if (carbs != null) count++
            if (fat != null) count++
            if (saturatedFat != null) count++
            if (transFat != null) count++
            if (cholesterol != null) count++
            if (fiber != null) count++
            if (sugar != null) count++
            if (addedSugar != null) count++
            if (sodium != null || salt != null) count++
            if (potassium != null) count++
            if (calcium != null) count++
            if (iron != null) count++
            count += vitamins.size
            return count
        }

    /**
     * Whether the draft contains at least one recognized major nutritional field.
     */
    val hasNutrientData: Boolean
        get() = calories != null || caloriesKj != null || protein != null || carbs != null || fat != null || sodium != null || salt != null
}

/**
 * Explicit state machine for the on-device Nutrition Label Scanner.
 */
sealed interface ScannerState {
    data object Idle : ScannerState
    data object CameraReady : ScannerState
    data object Capturing : ScannerState
    data object ProcessingImage : ScannerState
    data object RunningOCR : ScannerState
    data object ParsingNutrition : ScannerState
    data class Success(val draft: NutritionLabelDraft) : ScannerState
    data class PartialSuccess(val draft: NutritionLabelDraft) : ScannerState
    data class LowConfidence(val draft: NutritionLabelDraft) : ScannerState
    data object NoTextDetected : ScannerState
    data object ParseFailed : ScannerState
    data object CameraPermissionDenied : ScannerState
    data class CameraError(val message: String) : ScannerState
}

/**
 * Diagnostic metadata collected strictly for development metrics and quality checks.
 * Zero user private information or label text is contained in this model.
 */
data class ScanMetadata(
    val capturedWidth: Int = 0,
    val capturedHeight: Int = 0,
    val cropWidth: Int = 0,
    val cropHeight: Int = 0,
    val ocrBlockCount: Int = 0,
    val ocrLineCount: Int = 0,
    val ocrElementCount: Int = 0,
    val recognizedCharCount: Int = 0,
    val parsedFieldCount: Int = 0,
    val confidenceCategory: ConfidenceLevel = ConfidenceLevel.LOW,
    val detectedBasis: NutritionBasis = NutritionBasis.UNKNOWN,
    val servingSizeDetected: Boolean = false,
    val ocrDurationMs: Long = 0L,
    val parserDurationMs: Long = 0L
)
