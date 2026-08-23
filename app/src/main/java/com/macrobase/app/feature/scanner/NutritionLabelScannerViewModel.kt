package com.macrobase.app.feature.scanner

import android.app.Application
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macrobase.app.domain.model.scanner.ConfidenceLevel
import com.macrobase.app.domain.model.scanner.NutritionLabelDraft
import com.macrobase.app.domain.model.scanner.ScanMetadata
import com.macrobase.app.domain.model.scanner.ScannerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

/**
 * UI State for the Nutrition Label Scanner Screen.
 */
data class ScannerUiState(
    val state: ScannerState = ScannerState.Idle,
    val isTorchOn: Boolean = false,
    val qualityError: ImageQualityChecker.QualityCheckResult.Fail? = null,
    val metadata: ScanMetadata = ScanMetadata(),
    val currentProcessingStep: String? = null,
    val errorMessage: String? = null
) {
    val isAnalyzing: Boolean
        get() = state is ScannerState.Capturing ||
                state is ScannerState.ProcessingImage ||
                state is ScannerState.RunningOCR ||
                state is ScannerState.ParsingNutrition

    val draft: NutritionLabelDraft?
        get() = when (state) {
            is ScannerState.Success -> state.draft
            is ScannerState.LowConfidence -> state.draft
            else -> null
        }
}

/**
 * ViewModel orchestrating camera capture, quality checks, multi-pass OCR processing,
 * and deterministic nutrition label parsing.
 */

enum class OcrProvider {
    ML_KIT,
    PADDLE
}

class NutritionLabelScannerViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val qualityChecker: ImageQualityChecker = ImageQualityChecker()
    private val preprocessor: ImagePreprocessor = ImagePreprocessor()
    private val parser: NutritionLabelParser = NutritionLabelParser()
    
    // Debug Switch
    val ocrProvider = OcrProvider.PADDLE
    
    private val ocrEngine: NutritionLabelOcrEngine = when (ocrProvider) {
        OcrProvider.ML_KIT -> MlKitOcrEngine(preprocessor)
        OcrProvider.PADDLE -> PaddleOcrEngine(application, preprocessor)
    }

    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private var processingJob: kotlinx.coroutines.Job? = null

    fun onCameraReady() {
        if (_uiState.value.state == ScannerState.Idle) {
            _uiState.value = _uiState.value.copy(state = ScannerState.CameraReady)
        }
    }

    fun onPermissionDenied() {
        _uiState.value = _uiState.value.copy(state = ScannerState.CameraPermissionDenied)
    }

    fun onCameraCaptureError(message: String) {
        _uiState.value = _uiState.value.copy(
            state = ScannerState.CameraError(message),
            errorMessage = "Camera capture failed: $message",
            currentProcessingStep = null
        )
    }

    fun toggleTorch() {
        _uiState.value = _uiState.value.copy(isTorchOn = !_uiState.value.isTorchOn)
    }

    fun dismissQualityError() {
        _uiState.value = _uiState.value.copy(
            qualityError = null,
            state = ScannerState.CameraReady
        )
    }

    fun dismissNoTextDetected() {
        _uiState.value = _uiState.value.copy(state = ScannerState.CameraReady)
    }

    fun dismissParseFailed() {
        _uiState.value = _uiState.value.copy(state = ScannerState.CameraReady)
    }

    fun dismissErrorMessage() {
        _uiState.value = _uiState.value.copy(
            errorMessage = null,
            state = ScannerState.CameraReady
        )
    }

    fun retryCapture() {
        processingJob?.cancel()
        _uiState.value = _uiState.value.copy(
            state = ScannerState.CameraReady,
            qualityError = null,
            errorMessage = null,
            currentProcessingStep = null
        )
    }

    fun acceptDraft(draft: NutritionLabelDraft) {
        _uiState.value = _uiState.value.copy(state = ScannerState.Success(draft))
    }

    fun resetState() {
        processingJob?.cancel()
        _uiState.value = ScannerUiState(isTorchOn = _uiState.value.isTorchOn)
    }



    fun processCapturedImage(bitmap: Bitmap, guideRectRatio: RectF = RectF(0.08f, 0.18f, 0.92f, 0.72f), isTestImage: Boolean = false) {
        processingJob?.cancel()
        processingJob = viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            _uiState.value = _uiState.value.copy(
                state = ScannerState.Capturing,
                qualityError = null,
                errorMessage = null,
                currentProcessingStep = "Preparing image..."
            )

            try {
                // 1. Evaluate Image Quality (skip strict checks for test images if needed)
                if (!isTestImage) {
                    val qualityResult = qualityChecker.evaluate(bitmap)
                    if (qualityResult is ImageQualityChecker.QualityCheckResult.Fail) {
                        _uiState.value = _uiState.value.copy(
                            state = ScannerState.CameraReady,
                            qualityError = qualityResult,
                            currentProcessingStep = null
                        )
                        return@launch
                    }
                }

                // 2. Crop to framing guide (only for real camera frames with guide)
                _uiState.value = _uiState.value.copy(
                    state = ScannerState.ProcessingImage,
                    currentProcessingStep = "Optimizing image framing..."
                )
                
                // Allow a small safety margin around the framing rectangle (5% extra on all sides)
                val marginRect = RectF(
                    kotlin.math.max(0f, guideRectRatio.left - 0.05f),
                    kotlin.math.max(0f, guideRectRatio.top - 0.05f),
                    kotlin.math.min(1f, guideRectRatio.right + 0.05f),
                    kotlin.math.min(1f, guideRectRatio.bottom + 0.05f)
                )
                
                val cropped = if (isTestImage) bitmap else preprocessor.cropToGuide(bitmap, marginRect)
                
                // 3. Downscale the cropped image if it is too large for ML Kit
                // We use maxDimension 1920 to preserve text detail on large labels while maintaining safety
                val safeBitmap = preprocessor.downscaleIfNeeded(cropped, maxDimension = 1920)

                // 4. Multi-pass on-device OCR
                _uiState.value = _uiState.value.copy(
                    state = ScannerState.RunningOCR,
                    currentProcessingStep = "Running local on-device OCR..."
                )
                val ocrStartTime = System.currentTimeMillis()
                val candidatePasses = ocrEngine.recognizeAllPasses(safeBitmap)
                if (!isActive) return@launch
                val ocrDuration = System.currentTimeMillis() - ocrStartTime

                val bestOcr = candidatePasses.maxByOrNull { it.fullText.length } ?: candidatePasses.first()
                if (bestOcr.fullText.isBlank() || bestOcr.lines.isEmpty()) {
                    val metadata = ScanMetadata(
                        capturedWidth = bitmap.width,
                        capturedHeight = bitmap.height,
                        cropWidth = safeBitmap.width,
                        cropHeight = safeBitmap.height,
                        ocrBlockCount = 0,
                        ocrLineCount = 0,
                        ocrElementCount = 0,
                        recognizedCharCount = 0,
                        parsedFieldCount = 0,
                        confidenceCategory = ConfidenceLevel.LOW,
                        ocrDurationMs = ocrDuration
                    )
                    logScanMetadata(metadata)
                    _uiState.value = _uiState.value.copy(
                        state = ScannerState.NoTextDetected,
                        metadata = metadata,
                        currentProcessingStep = null
                    )
                    return@launch
                }

                // 5. Deterministic multi-pass parsing and scoring
                _uiState.value = _uiState.value.copy(
                    state = ScannerState.ParsingNutrition,
                    currentProcessingStep = "Parsing nutrition facts..."
                )

                val parseStartTime = System.currentTimeMillis()
                val parsedCandidates = candidatePasses.map { pass ->
                    parser.parse(pass)
                }
                val parseDuration = System.currentTimeMillis() - parseStartTime

                // Score candidate drafts to select the highest quality result
                val bestDraft = parsedCandidates.maxByOrNull { draft ->
                    var score = draft.recognizedFieldCount * 10
                    if (draft.calories != null) score += 20
                    if (draft.protein != null) score += 15
                    if (draft.carbs != null) score += 15
                    if (draft.fat != null) score += 15
                    if (draft.overallConfidence == ConfidenceLevel.HIGH) score += 10
                    score
                } ?: parsedCandidates.first()

                val metadata = ScanMetadata(
                    capturedWidth = bitmap.width,
                    capturedHeight = bitmap.height,
                    cropWidth = safeBitmap.width,
                    cropHeight = safeBitmap.height,
                    ocrBlockCount = bestOcr.blocks.size,
                    ocrLineCount = bestOcr.lines.size,
                    ocrElementCount = bestOcr.lines.sumOf { it.elements.size },
                    recognizedCharCount = bestOcr.fullText.length,
                    parsedFieldCount = bestDraft.recognizedFieldCount,
                    confidenceCategory = bestDraft.overallConfidence,
                    detectedBasis = bestDraft.detectedBasis,
                    servingSizeDetected = bestDraft.servingGrams != null || bestDraft.servingSize != null,
                    ocrDurationMs = ocrDuration,
                    parserDurationMs = parseDuration
                )
                logScanMetadata(metadata)

                if (!bestDraft.hasNutrientData) {
                    _uiState.value = _uiState.value.copy(
                        state = ScannerState.ParseFailed,
                        metadata = metadata,
                        currentProcessingStep = null
                    )
                } else if (bestDraft.recognizedFieldCount <= 2 || bestDraft.overallConfidence == ConfidenceLevel.LOW) {
                    // Partial success: Only 1 or 2 fields detected (e.g. Sodium only)
                    _uiState.value = _uiState.value.copy(
                        state = ScannerState.PartialSuccess(bestDraft),
                        metadata = metadata,
                        currentProcessingStep = null
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        state = ScannerState.Success(bestDraft),
                        metadata = metadata,
                        currentProcessingStep = null
                    )
                }
            } catch (t: Throwable) {
                _uiState.value = _uiState.value.copy(
                    state = ScannerState.CameraError(t.localizedMessage ?: "Scanning failed"),
                    errorMessage = "Couldn't read this image. Please hold steady and try again.",
                    currentProcessingStep = null
                )
            }
        }
    }

    private fun logScanMetadata(metadata: ScanMetadata) {
        // Log safe diagnostic metadata only - no private nutrition text or image contents logged
        Log.d(
            "MacroBaseScanner",
            "Scan Diagnostic: blocks=${metadata.ocrBlockCount}, chars=${metadata.recognizedCharCount}, " +
                    "fields=${metadata.parsedFieldCount}, confidence=${metadata.confidenceCategory}, " +
                    "basis=${metadata.detectedBasis}, servingDetected=${metadata.servingSizeDetected}"
        )
    }

    fun clearDraft() {
        _uiState.value = _uiState.value.copy(state = ScannerState.CameraReady)
    }

    override fun onCleared() {
        super.onCleared()
        processingJob?.cancel()
        ocrEngine.shutdown()
    }
}
