package com.macrobase.app.feature.scanner

import android.app.Application
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macrobase.app.domain.model.scanner.ConfidenceLevel
import com.macrobase.app.domain.model.scanner.NutritionLabelDraft
import com.macrobase.app.domain.model.scanner.ScanMetadata
import com.macrobase.app.domain.model.scanner.ScannerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

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
class NutritionLabelScannerViewModel(
    application: Application,
    /** The OCR engine; tests pass a fake, the app uses ML Kit. */
    createOcrEngine: () -> NutritionLabelOcrEngine = { MlKitOcrEngine() },
    /** Decodes a picked gallery image; tests replace it to simulate a failure. */
    private val decodeGalleryImage: (ContentResolver, Uri) -> Bitmap? = { resolver, uri -> ScannerBitmapDecoder.decodeSampled(resolver, uri) }
) : AndroidViewModel(application) {

    private val preprocessor: ImagePreprocessor = ImagePreprocessor()
    val ocrEngine: NutritionLabelOcrEngine = createOcrEngine()
    private val orchestrator: NutritionLabelScanOrchestrator = NutritionLabelScanOrchestrator(
        qualityAnalyzer = NutritionLabelImageQualityAnalyzer(),
        preprocessor = preprocessor,
        regionDetector = NutritionRegionDetector(),
        ocrEngine = ocrEngine,
        ensembleScorer = OcrEnsembleScorer(),
        parser = NutritionLabelParser(),
        validator = NutritionValidator()
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

    /**
     * A photo from the gallery: decoded at a bounded size, upright, off the main thread. A file
     * too large to decode shows a message instead of crashing the app (BUG-044).
     */
    fun processGalleryImage(uri: Uri) {
        // One scan at a time: the analysing overlay's Cancel stops a running one (BUG-056)
        if (_uiState.value.isAnalyzing) return
        val resolver = getApplication<Application>().contentResolver
        launchScan(ImageSource.GALLERY, guideRect()) {
            withContext(Dispatchers.IO) { loadScannerImage { decodeGalleryImage(resolver, uri) } }
        }
    }

    /** A camera frame's JPEG bytes, decoded off the main thread (BUG-044). */
    fun processCameraFrame(jpeg: ByteArray, rotationDegrees: Int, guideRectRatio: RectF = guideRect()) {
        if (_uiState.value.isAnalyzing) return
        launchScan(ImageSource.CAMERA, guideRectRatio) {
            withContext(Dispatchers.Default) {
                loadScannerImage(
                    unreadableMessage = CAMERA_FRAME_UNREADABLE,
                    tooLargeMessage = CAMERA_OUT_OF_MEMORY,
                    failedMessage = CAMERA_FRAME_UNREADABLE
                ) { ScannerBitmapDecoder.decode(jpeg, rotationDegrees) }
            }
        }
    }

    private fun launchScan(source: ImageSource, guideRectRatio: RectF, load: suspend () -> ScannerImage) {
        processingJob?.cancel()
        // Handed over once, so the camera's JPEG bytes are freed as soon as they are decoded
        // instead of staying in memory for the whole scan
        val loader = AtomicReference(load)
        processingJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                state = ScannerState.Capturing,
                qualityError = null,
                errorMessage = null,
                currentProcessingStep = "Preparing image..."
            )

            val bitmap = when (val image = loader.getAndSet(null)?.invoke() ?: return@launch) {
                is ScannerImage.Loaded -> image.bitmap
                is ScannerImage.Failed -> {
                    _uiState.value = _uiState.value.copy(
                        state = ScannerState.CameraError(image.message),
                        errorMessage = image.message,
                        currentProcessingStep = null
                    )
                    return@launch
                }
            }
            if (!isActive) return@launch

            try {
                val result = orchestrator.processImage(
                    bitmap = bitmap,
                    guideRectRatio = guideRectRatio,
                    source = source,
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
                            recommendation = if (source == ImageSource.GALLERY) {
                                "Pick a sharp, well-lit photo of the nutrition label."
                            } else {
                                "Hold steady and ensure good lighting on the nutrition label."
                            }
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                // A newer scan replaced this one: leave its state alone
                throw e
            } catch (e: OutOfMemoryError) {
                val message = if (source == ImageSource.GALLERY) GALLERY_TOO_LARGE else CAMERA_OUT_OF_MEMORY
                _uiState.value = _uiState.value.copy(
                    state = ScannerState.CameraError(message),
                    errorMessage = message,
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
        // A cancelled scan finishes the OCR pass in flight before it stops, and an earlier scan cut
        // short by Cancel may still be finishing one. viewModelScope is already cancelled here, so
        // its job completes only once every scan has stopped; ML Kit is closed then (BUG-056).
        val scope = viewModelScope.coroutineContext[Job]
        if (scope == null) ocrEngine.shutdown() else scope.invokeOnCompletion { ocrEngine.shutdown() }
    }

    private companion object {
        /** The on-screen framing guide, as fractions of the camera frame; a new one each time. */
        fun guideRect() = RectF(0.08f, 0.18f, 0.92f, 0.72f)
        const val CAMERA_FRAME_UNREADABLE = "Unable to decode captured frame. Please try again."
        const val CAMERA_OUT_OF_MEMORY = "Not enough memory to read this frame. Close other apps and try again."
    }
}
