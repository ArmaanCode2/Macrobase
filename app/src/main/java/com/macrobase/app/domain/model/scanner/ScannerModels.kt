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
    PER_1_G("Per 1g"),
    PER_SERVING("Per Serving"),
    PER_100_ML("Per 100ml"),
    PER_PACKAGE("Per Package"),
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
 * Comparison operator for nutrient measurements (e.g. "<1 mg", "<0.3 mg").
 */
@Parcelize
enum class ComparisonOperator : Parcelable {
    EXACT,
    LESS_THAN,
    GREATER_THAN
}

/**
 * Extracted nutrient value with metadata, source preservation, and normalized representations.
 */
@Parcelize
data class ParsedNutrientValue(
    val value: Double,
    val unit: String,
    val basis: NutritionBasis = NutritionBasis.UNKNOWN,
    val confidence: ConfidenceLevel = ConfidenceLevel.HIGH,
    val isEstimated: Boolean = false,
    val operator: ComparisonOperator = ComparisonOperator.EXACT,
    val rawMatch: String? = null,
    val sourceText: String? = null,
    val sourceBBox: String? = null,
    val needsReview: Boolean = false,
    val extractionMethod: String? = null,
    // Source preservation & Normalization tracking
    val sourceValue: Double? = null,
    val sourceUnit: String? = null,
    val sourceBasis: NutritionBasis = NutritionBasis.UNKNOWN,
    val sourceColumn: String? = null,
    val normalizedPer100g: Double? = null,
    val normalizedPer1g: Double? = null,
    val isDerived: Boolean = false
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
    val sourceBasis: NutritionBasis = NutritionBasis.UNKNOWN,
    val normalizationNote: String? = null,
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
    // Multi-Basis maps (canonical per-100g, derived per-1g, and raw per-serving)
    val per100gValues: Map<String, ParsedNutrientValue> = emptyMap(),
    val per1gValues: Map<String, ParsedNutrientValue> = emptyMap(),
    val perServingValues: Map<String, ParsedNutrientValue> = emptyMap(),
    val rdaValues: Map<String, Double> = emptyMap(),
    val overallConfidence: ConfidenceLevel = ConfidenceLevel.MEDIUM,
    val warnings: List<String> = emptyList(),
    val rawOcrText: String = "",
    val tableRegionBBox: String? = null,
    val debugData: ScannerDebugData? = null
) : Parcelable {

    /**
     * False when the values were printed per serving with no gram weight: they cannot become
     * per 100 g or per 1 g without inventing a weight (BUG-021).
     */
    val canConvertToPer100g: Boolean
        get() = detectedBasis != NutritionBasis.PER_SERVING && detectedBasis != NutritionBasis.PER_PACKAGE &&
            detectedBasis != NutritionBasis.PER_100_ML

    /** The bases the review screen offers for this draft. */
    val outputBasisOptions: List<NutritionBasis>
        get() = when {
            // A drink's values per 100 ml are not per 100 g (1 ml is not 1 g)
            detectedBasis == NutritionBasis.PER_100_ML -> listOf(NutritionBasis.PER_100_ML)
            !canConvertToPer100g -> listOf(NutritionBasis.PER_SERVING)
            // The label does not say: the user decides what the printed values are per (BUG-020)
            detectedBasis == NutritionBasis.UNKNOWN -> listOf(NutritionBasis.PER_100_G, NutritionBasis.PER_SERVING)
            else -> listOf(NutritionBasis.PER_100_G, NutritionBasis.PER_1_G)
        }

    /** The basis selected when the review screen opens; null when the user has to choose one. */
    val defaultOutputBasis: NutritionBasis?
        get() = if (detectedBasis == NutritionBasis.UNKNOWN) null else outputBasisOptions.first()

    /**
     * Converts or switches the primary exposed fields of the draft to a specific display basis:
     * PER_100_G (serving size 100 g), PER_1_G (serving size 1 g) or PER_SERVING (one serving
     * as printed on the label).
     *
     * Values printed per serving without a gram weight are always returned per serving: they
     * are never relabelled as per 100 g or per 1 g (BUG-021). For a draft of unknown basis, the
     * caller's choice states what the printed values are per.
     */
    fun withOutputBasis(targetBasis: NutritionBasis): NutritionLabelDraft {
        if (detectedBasis == NutritionBasis.PER_100_ML) {
            // Values per 100 ml are saved for 100 ml: never relabelled as grams
            return per100mlOutput()
        }
        if (targetBasis == NutritionBasis.PER_SERVING || !canConvertToPer100g) {
            return perServingOutput()
        }
        if (targetBasis == NutritionBasis.PER_1_G) {
            fun to1g(key: String, primary: ParsedNutrientValue?): ParsedNutrientValue? {
                val fromMap = per1gValues[key]
                if (fromMap != null) return fromMap
                if (primary == null) return null
                val derived1g = primary.normalizedPer1g ?: (primary.value / 100.0)
                val rounded1g = (derived1g * 1000.0).let { Math.round(it) / 1000.0 }
                return primary.copy(
                    value = rounded1g,
                    basis = NutritionBasis.PER_1_G,
                    isDerived = true
                )
            }

            return this.copy(
                detectedBasis = NutritionBasis.PER_1_G,
                servingSize = 1.0,
                servingUnit = ServingUnit.GRAMS,
                calories = to1g("calories", calories),
                protein = to1g("protein", protein),
                carbs = to1g("carbs", carbs),
                fat = to1g("fat", fat),
                saturatedFat = to1g("saturatedFat", saturatedFat),
                transFat = to1g("transFat", transFat),
                cholesterol = to1g("cholesterol", cholesterol),
                fiber = to1g("fiber", fiber),
                sugar = to1g("sugar", sugar),
                addedSugar = to1g("addedSugar", addedSugar),
                sodium = to1g("sodium", sodium),
                salt = to1g("salt", salt),
                potassium = to1g("potassium", potassium),
                calcium = to1g("calcium", calcium),
                iron = to1g("iron", iron)
            )
        } else if (targetBasis == NutritionBasis.PER_100_G) {
            fun to100g(key: String, primary: ParsedNutrientValue?): ParsedNutrientValue? {
                val fromMap = per100gValues[key]
                if (fromMap != null) return fromMap
                if (primary == null) return null
                val val100g = primary.normalizedPer100g ?: primary.value
                return primary.copy(
                    value = val100g,
                    basis = NutritionBasis.PER_100_G
                )
            }

            return this.copy(
                detectedBasis = NutritionBasis.PER_100_G,
                servingSize = 100.0,
                servingUnit = ServingUnit.GRAMS,
                calories = to100g("calories", calories),
                protein = to100g("protein", protein),
                carbs = to100g("carbs", carbs),
                fat = to100g("fat", fat),
                saturatedFat = to100g("saturatedFat", saturatedFat),
                transFat = to100g("transFat", transFat),
                cholesterol = to100g("cholesterol", cholesterol),
                fiber = to100g("fiber", fiber),
                sugar = to100g("sugar", sugar),
                addedSugar = to100g("addedSugar", addedSugar),
                sodium = to100g("sodium", sodium),
                salt = to100g("salt", salt),
                potassium = to100g("potassium", potassium),
                calcium = to100g("calcium", calcium),
                iron = to100g("iron", iron)
            )
        }
        return this
    }

    /** 100 ml as printed on a drink's label. */
    private fun per100mlOutput(): NutritionLabelDraft {
        fun as100ml(v: ParsedNutrientValue?) = v?.copy(basis = NutritionBasis.PER_100_ML)
        return this.copy(
            detectedBasis = NutritionBasis.PER_100_ML,
            servingSize = 100.0,
            servingUnit = ServingUnit.MILLILITERS,
            calories = as100ml(calories),
            protein = as100ml(protein),
            carbs = as100ml(carbs),
            fat = as100ml(fat),
            saturatedFat = as100ml(saturatedFat),
            transFat = as100ml(transFat),
            cholesterol = as100ml(cholesterol),
            fiber = as100ml(fiber),
            sugar = as100ml(sugar),
            addedSugar = as100ml(addedSugar),
            sodium = as100ml(sodium),
            salt = as100ml(salt),
            potassium = as100ml(potassium),
            calcium = as100ml(calcium),
            iron = as100ml(iron)
        )
    }

    /** One serving as printed: the label's serving size and its per-serving values. */
    private fun perServingOutput(): NutritionLabelDraft {
        val normalized = detectedBasis == NutritionBasis.PER_100_G
        val grams = servingGrams
        fun toServing(key: String, primary: ParsedNutrientValue?): ParsedNutrientValue? {
            perServingValues[key]?.let { return it.copy(basis = NutritionBasis.PER_SERVING) }
            if (primary == null) return null
            if (!normalized) return primary.copy(basis = NutritionBasis.PER_SERVING)
            // Per 100 g values with a known serving weight scale down; without one there is no serving value
            if (grams == null || grams <= 0.0) return null
            val per100g = primary.normalizedPer100g ?: primary.value
            return primary.copy(
                value = Math.round(per100g * grams / 100.0 * 100.0) / 100.0,
                basis = NutritionBasis.PER_SERVING,
                isDerived = true
            )
        }

        return this.copy(
            detectedBasis = NutritionBasis.PER_SERVING,
            servingSize = if (normalized) servingGrams ?: 100.0 else servingSize ?: 1.0,
            servingUnit = if (normalized) ServingUnit.GRAMS else servingUnit ?: ServingUnit.SERVING,
            calories = toServing("calories", calories),
            protein = toServing("protein", protein),
            carbs = toServing("carbs", carbs),
            fat = toServing("fat", fat),
            saturatedFat = toServing("saturatedFat", saturatedFat),
            transFat = toServing("transFat", transFat),
            cholesterol = toServing("cholesterol", cholesterol),
            fiber = toServing("fiber", fiber),
            sugar = toServing("sugar", sugar),
            addedSugar = toServing("addedSugar", addedSugar),
            sodium = toServing("sodium", sodium),
            salt = toServing("salt", salt),
            potassium = toServing("potassium", potassium),
            calcium = toServing("calcium", calcium),
            iron = toServing("iron", iron)
        )
    }
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
    data object TableNotReconstructed : ScannerState
    data object OcrFailed : ScannerState
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

@Parcelize
data class ColumnBoundaryDebug(
    val name: String,
    val basis: NutritionBasis,
    val minX: Float,
    val maxX: Float,
    val centerX: Float
) : Parcelable

@Parcelize
data class RowBoundaryDebug(
    val nutrientKey: String,
    val labelText: String,
    val minY: Float,
    val maxY: Float,
    val centerY: Float
) : Parcelable

@Parcelize
data class TokenDebugItem(
    val rawText: String,
    val numericValue: Double?,
    val operator: ComparisonOperator,
    val unit: String?,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val assignedRow: String?,
    val assignedColumn: String?,
    val confidence: Float
) : Parcelable

@Parcelize
data class ScannerDebugData(
    val tableRectLeft: Float = 0f,
    val tableRectTop: Float = 0f,
    val tableRectRight: Float = 0f,
    val tableRectBottom: Float = 0f,
    val columns: List<ColumnBoundaryDebug> = emptyList(),
    val rows: List<RowBoundaryDebug> = emptyList(),
    val tokens: List<TokenDebugItem> = emptyList(),
    val servingSizeGrams: Double? = null,
    val servingSizeText: String? = null,
    val rawLogLines: List<String> = emptyList()
) : Parcelable

