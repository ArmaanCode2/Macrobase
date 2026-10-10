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
            is ScannerState.PartialSuccess -> state.draft
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

    private val preprocessor: ImagePreprocessor = ImagePreprocessor()
    val ocrEngine: NutritionLabelOcrEngine = MlKitOcrEngine(preprocessor)
    private val orchestrator: NutritionLabelScanOrchestrator = NutritionLabelScanOrchestrator(
        qualityAnalyzer = NutritionLabelImageQualityAnalyzer(),
        preprocessor = preprocessor,
        regionDetector = NutritionRegionDetector(),
        ocrEngine = ocrEngine,
        ensembleScorer = OcrEnsembleScorer(),
        tableReconstructor = TableLayoutReconstructor(),
        parser = NutritionLabelParser(),
        validator = NutritionValidator(),
        adjudicator = NutritionLabelGeminiAdjudicator()
    )

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
            _uiState.value = _uiState.value.copy(
                state = ScannerState.Capturing,
                qualityError = null,
                errorMessage = null,
                currentProcessingStep = "Preparing image..."
            )

            try {
                val result = orchestrator.processImage(
                    bitmap = bitmap,
                    guideRectRatio = guideRectRatio,
                    isTestImage = isTestImage,
                    onProgressUpdate = { step ->
                        _uiState.value = _uiState.value.copy(currentProcessingStep = step)
                    }
                )

                if (!isActive) return@launch

                logScanMetadata(result.metadata)

                if (result.rejectionReason != null) {
                    _uiState.value = _uiState.value.copy(
                        state = ScannerState.CameraReady,
                        qualityError = ImageQualityChecker.QualityCheckResult.Fail(
                            title = "Image Unusable",
                            message = result.rejectionReason,
                            recommendation = "Hold steady and ensure good lighting on the nutrition label."
                        ),
                        currentProcessingStep = null
                    )
                    return@launch
                }

                _uiState.value = _uiState.value.copy(
                    state = result.state,
                    metadata = result.metadata,
                    currentProcessingStep = null
                )
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
