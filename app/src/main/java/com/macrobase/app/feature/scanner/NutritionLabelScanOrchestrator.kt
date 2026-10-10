package com.macrobase.app.feature.scanner

import android.graphics.Bitmap
import android.graphics.RectF
import com.macrobase.app.domain.model.scanner.ConfidenceLevel
import com.macrobase.app.domain.model.scanner.NutritionBasis
import com.macrobase.app.domain.model.scanner.NutritionLabelDraft
import com.macrobase.app.domain.model.scanner.ScanMetadata
import com.macrobase.app.domain.model.scanner.ScannerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Result produced by the master NutritionLabelScanOrchestrator.
 */
data class OrchestratorResult(
    val state: ScannerState,
    val draft: NutritionLabelDraft?,
    val metadata: ScanMetadata,
    val warnings: List<String> = emptyList(),
    val rejectionReason: String? = null
)

/**
 * Master coordinator orchestrating the full end-to-end nutrition label scanning pipeline:
 *
 * Image
 *   ↓
 * Quality Analyzer
 *   ↓
 * Multi-Pass Preprocessor (Variants A-F)
 *   ↓
 * Region Detection & Table Crop
 *   ↓
 * ML Kit OCR Ensemble & Regional Scoring
 *   ↓
 * 2D Table Layout & Column Reconstruction
 *   ↓
 * Deterministic Multi-Column Parsing
 *   ↓
 * Nutrition Sanity & Macro Validation
 *   ↓
 * Optional Contract-Bound Gemini Adjudication
 *   ↓
 * Form-Ready Draft
 */
class NutritionLabelScanOrchestrator(
    private val qualityAnalyzer: NutritionLabelImageQualityAnalyzer = NutritionLabelImageQualityAnalyzer(),
    private val preprocessor: ImagePreprocessor = ImagePreprocessor(),
    private val regionDetector: NutritionRegionDetector = NutritionRegionDetector(),
    private val ocrEngine: NutritionLabelOcrEngine,
    private val ensembleScorer: OcrEnsembleScorer = OcrEnsembleScorer(),
    private val tableReconstructor: TableLayoutReconstructor = TableLayoutReconstructor(),
    private val parser: NutritionLabelParser = NutritionLabelParser(),
    private val validator: NutritionValidator = NutritionValidator(),
    private val adjudicator: NutritionLabelGeminiAdjudicator = NutritionLabelGeminiAdjudicator(validator)
) {

    suspend fun processImage(
        bitmap: Bitmap,
        guideRectRatio: RectF = RectF(0.08f, 0.18f, 0.92f, 0.72f),
        isTestImage: Boolean = false,
        onProgressUpdate: (String) -> Unit = {}
    ): OrchestratorResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()

        // 1. Measurable Image Quality Analysis
        onProgressUpdate("Analyzing image quality...")
        val quality = qualityAnalyzer.analyze(bitmap)
        if (!quality.acceptable && !isTestImage) {
            return@withContext OrchestratorResult(
                state = ScannerState.ParseFailed,
                draft = null,
                metadata = ScanMetadata(capturedWidth = bitmap.width, capturedHeight = bitmap.height),
                rejectionReason = quality.rejectionReason
            )
        }

        if (!coroutineContext.isActive) return@withContext emptyCancellationResult(bitmap)

        // 2. Framing Guide Cropping (with safety margin)
        onProgressUpdate("Optimizing framing...")
        val cropped = if (isTestImage) {
            bitmap
        } else {
            val marginRect = RectF(
                kotlin.math.max(0f, guideRectRatio.left - 0.05f),
                kotlin.math.max(0f, guideRectRatio.top - 0.05f),
                kotlin.math.min(1f, guideRectRatio.right + 0.05f),
                kotlin.math.min(1f, guideRectRatio.bottom + 0.05f)
            )
            preprocessor.cropToGuide(bitmap, marginRect)
        }

        // Bounded working resolution: up to 1920px keeps small fonts crisp without memory hazard
        val safeBitmap = preprocessor.downscaleIfNeeded(cropped, maxDimension = 1920)

        // 3. Multi-Pass Preprocessing Variants (Variants A - E)
        onProgressUpdate("Generating preprocessing variants...")
        val variants = preprocessor.generateVariants(safeBitmap, quality)

        // 4. Multi-Pass OCR Ensemble
        onProgressUpdate("Running multi-pass OCR ensemble...")
        val ocrStartTime = System.currentTimeMillis()
        val scoredPasses = mutableListOf<OcrPassResult>()

        try {
            for ((idx, variant) in variants.withIndex()) {
                if (!coroutineContext.isActive) break
                val passIndex = idx + 1
                val ocrResult = ocrEngine.recognizeText(variant.bitmap)

                if (ocrResult.lines.isNotEmpty()) {
                    val scoredPass = ensembleScorer.scorePass(
                        passIndex = passIndex,
                        variantId = variant.id,
                        variantName = variant.name,
                        ocrResult = ocrResult
                    )
                    scoredPasses.add(scoredPass)
                }
            }
        } finally {
            // Clean up temporary variant bitmaps immediately
            for (v in variants) {
                if (v.bitmap != safeBitmap && v.bitmap != bitmap) {
                    v.recycleIfNeeded()
                }
            }
        }

        if (scoredPasses.isEmpty()) {
            val ocrDuration = System.currentTimeMillis() - ocrStartTime
            return@withContext OrchestratorResult(
                state = ScannerState.NoTextDetected,
                draft = null,
                metadata = ScanMetadata(
                    capturedWidth = bitmap.width,
                    capturedHeight = bitmap.height,
                    cropWidth = safeBitmap.width,
                    cropHeight = safeBitmap.height,
                    ocrDurationMs = ocrDuration
                )
            )
        }

        // 5. Table Region Detection & Targeted High-Res Crop
        val initialEnsemble = ensembleScorer.buildEnsemble(scoredPasses)
        val bestInitialPass = initialEnsemble.bestOverallPass

        val regionResult = regionDetector.detectRegion(
            ocrResult = OcrResult(bestInitialPass.fullText, bestInitialPass.blocks, bestInitialPass.lines),
            imageWidth = safeBitmap.width,
            imageHeight = safeBitmap.height
        )

        if (regionResult.found && regionResult.boundingBox != null) {
            onProgressUpdate("Focusing on nutrition table region...")
            val tableCrop = regionDetector.cropTable(safeBitmap, regionResult)
            if (tableCrop != null) {
                try {
                    val cropOcr = ocrEngine.recognizeText(tableCrop)
                    if (cropOcr.lines.isNotEmpty()) {
                        val scoredCropPass = ensembleScorer.scorePass(
                            passIndex = scoredPasses.size + 1,
                            variantId = "TableCrop",
                            variantName = "TargetedTableCrop",
                            ocrResult = cropOcr
                        )
                        scoredPasses.add(scoredCropPass)
                    }
                } finally {
                    if (tableCrop != safeBitmap && !tableCrop.isRecycled) {
                        tableCrop.recycle()
                    }
                }
            }
        }

        val ocrDuration = System.currentTimeMillis() - ocrStartTime

        // 6. Build final consolidated ensemble
        val finalEnsemble = ensembleScorer.buildEnsemble(scoredPasses)
        val bestPass = finalEnsemble.bestOverallPass

        // 7. 2D Table Layout & Column Reconstruction
        onProgressUpdate("Reconstructing table geometry...")
        val unifiedOcr = OcrResult(
            fullText = bestPass.fullText,
            blocks = bestPass.blocks,
            lines = finalEnsemble.unifiedLines
        )
        val tableGrid = tableReconstructor.reconstructTable(unifiedOcr)

        // 8. Deterministic Multi-Column Parsing
        onProgressUpdate("Extracting nutrients...")
        val parseStartTime = System.currentTimeMillis()
        var draft = parser.parse(unifiedOcr)
        val parseDuration = System.currentTimeMillis() - parseStartTime

        // 9. Macro Consistency & Range Sanity Validation
        val macroWarning = validator.validateMacroConsistency(
            calories = draft.calories?.value,
            protein = draft.protein?.value,
            carbs = draft.carbs?.value,
            fat = draft.fat?.value
        )
        if (macroWarning != null) {
            draft = draft.copy(warnings = draft.warnings + macroWarning)
        }

        // Incorporate image quality warnings
        val combinedWarnings = quality.warnings + draft.warnings

        // 10. On-Device Conflict & Consistency Adjudication
        if (adjudicator.shouldAdjudicate(draft)) {
            onProgressUpdate("Resolving ambiguous fields...")
            draft = adjudicator.adjudicate(draft, tableGrid)
        }

        val metadata = ScanMetadata(
            capturedWidth = bitmap.width,
            capturedHeight = bitmap.height,
            cropWidth = safeBitmap.width,
            cropHeight = safeBitmap.height,
            ocrBlockCount = bestPass.blocks.size,
            ocrLineCount = finalEnsemble.unifiedLines.size,
            ocrElementCount = finalEnsemble.unifiedLines.sumOf { it.elements.size },
            recognizedCharCount = bestPass.fullText.length,
            parsedFieldCount = draft.recognizedFieldCount,
            confidenceCategory = draft.overallConfidence,
            detectedBasis = draft.detectedBasis,
            servingSizeDetected = draft.servingGrams != null || draft.servingSize != null,
            ocrDurationMs = ocrDuration,
            parserDurationMs = parseDuration
        )

        val finalState = when {
            !draft.hasNutrientData -> ScannerState.ParseFailed
            draft.recognizedFieldCount <= 2 || draft.overallConfidence == ConfidenceLevel.LOW ->
                ScannerState.PartialSuccess(draft.copy(warnings = combinedWarnings))
            else -> ScannerState.Success(draft.copy(warnings = combinedWarnings))
        }

        return@withContext OrchestratorResult(
            state = finalState,
            draft = draft.copy(warnings = combinedWarnings),
            metadata = metadata,
            warnings = combinedWarnings
        )
    }

    private fun emptyCancellationResult(bitmap: Bitmap) = OrchestratorResult(
        state = ScannerState.Idle,
        draft = null,
        metadata = ScanMetadata(capturedWidth = bitmap.width, capturedHeight = bitmap.height)
    )
}
