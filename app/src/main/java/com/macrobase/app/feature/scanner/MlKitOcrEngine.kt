package com.macrobase.app.feature.scanner

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Structured OCR models decoupling raw OCR layout from nutrition business logic.
 */
data class OcrElement(
    val text: String,
    val boundingBox: Rect? = null,
    val confidence: Float = 0f,
    val spatialBounds: NutritionLabelParser.SpatialBox? = NutritionLabelParser.SpatialBox.fromAndroidRect(boundingBox)
)

data class OcrLine(
    val text: String,
    val boundingBox: Rect? = null,
    val confidence: Float = 0f,
    val elements: List<OcrElement> = emptyList(),
    val spatialBounds: NutritionLabelParser.SpatialBox? = NutritionLabelParser.SpatialBox.fromAndroidRect(boundingBox)
)

data class OcrBlock(
    val text: String,
    val boundingBox: Rect? = null,
    val confidence: Float = 0f,
    val lines: List<OcrLine> = emptyList(),
    val spatialBounds: NutritionLabelParser.SpatialBox? = NutritionLabelParser.SpatialBox.fromAndroidRect(boundingBox)
)

data class OcrResult(
    val fullText: String,
    val blocks: List<OcrBlock>,
    val lines: List<OcrLine>,
    val passIndex: Int = 1
)

/**
 * Multi-pass on-device text recognition engine using Google ML Kit.
 */
class MlKitOcrEngine(
    private val preprocessor: ImagePreprocessor = ImagePreprocessor()
) : NutritionLabelOcrEngine {
    // Singleton recognizer per instance to avoid expensive native re-initializations
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Executes multi-pass on-device OCR returning all candidate passes for scoring.
     */
    override suspend fun recognizeAllPasses(bitmap: Bitmap): List<OcrResult> = withContext(Dispatchers.Default) {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return@withContext listOf(OcrResult(fullText = "", blocks = emptyList(), lines = emptyList(), passIndex = 1))
        }

        val passes = mutableListOf<OcrResult>()

        try {
            // Pass 1: Original preprocessed crop
            val pass1 = processImagePass(bitmap, passIndex = 1)
            passes.add(pass1)

            // Early exit if Pass 1 already detected extensive text (e.g. 10+ lines and 120+ chars)
            if (pass1.lines.size >= 10 && pass1.fullText.length >= 120) {
                return@withContext passes
            }

            // Pass 2: Grayscale & Contrast-enhanced variant
            val enhancedBitmap = preprocessor.enhanceContrast(bitmap)
            try {
                val pass2 = processImagePass(enhancedBitmap, passIndex = 2)
                passes.add(pass2)
            } finally {
                if (enhancedBitmap != bitmap && !enhancedBitmap.isRecycled) {
                    enhancedBitmap.recycle()
                }
            }

            // Pass 3: Adaptive high-contrast binarized variant (only if still sparse)
            if (passes.maxOfOrNull { it.lines.size } ?: 0 < 6) {
                val binarized = preprocessor.createBinarizedVariant(bitmap)
                try {
                    val pass3 = processImagePass(binarized, passIndex = 3)
                    passes.add(pass3)
                } finally {
                    if (binarized != bitmap && !binarized.isRecycled) {
                        binarized.recycle()
                    }
                }
            }

            return@withContext passes
        } catch (t: Throwable) {
            if (passes.isNotEmpty()) return@withContext passes
            return@withContext listOf(OcrResult(fullText = "", blocks = emptyList(), lines = emptyList(), passIndex = 1))
        }
    }

    /**
     * Executes single/multi-pass on-device OCR on the captured bitmap.
     */
    override suspend fun recognizeText(bitmap: Bitmap): OcrResult = withContext(Dispatchers.Default) {
        val passes = recognizeAllPasses(bitmap)
        return@withContext passes.maxByOrNull { it.lines.size } ?: passes.first()
    }

    /**
     * Shutdown the ML Kit text recognizer to free native memory.
     */
    override fun shutdown() {
        try {
            recognizer.close()
        } catch (ignored: Throwable) {}
    }

    private suspend fun processImagePass(
        bitmap: Bitmap,
        passIndex: Int
    ): OcrResult = suspendCancellableCoroutine { continuation ->
        if (bitmap.isRecycled) {
            continuation.resume(OcrResult(fullText = "", blocks = emptyList(), lines = emptyList(), passIndex = passIndex))
            return@suspendCancellableCoroutine
        }

        try {
            android.util.Log.d("MacroBaseScanner", "STAGE: OCR_STARTED (pass=$passIndex)")
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val ocrResult = mapVisionTextToOcrResult(visionText, passIndex)
                    if (continuation.isActive) continuation.resume(ocrResult)
                }
                .addOnFailureListener { ex ->
                    android.util.Log.e("MacroBaseScanner", "STAGE: OCR_CALLBACK_FAILURE (error=${ex.message})")
                    if (continuation.isActive) continuation.resume(OcrResult(fullText = "", blocks = emptyList(), lines = emptyList(), passIndex = passIndex))
                }
                .addOnCompleteListener {
                    // Task complete, ensuring any cleanup happens if needed
                }
        } catch (t: Throwable) {
            android.util.Log.e("MacroBaseScanner", "STAGE: OCR_EXCEPTION (error=${t.message})")
            if (continuation.isActive) continuation.resume(OcrResult(fullText = "", blocks = emptyList(), lines = emptyList(), passIndex = passIndex))
        }
    }

    private fun mapVisionTextToOcrResult(visionText: Text, passIndex: Int): OcrResult {
        val blocks = mutableListOf<OcrBlock>()
        val allLines = mutableListOf<OcrLine>()

        for (b in visionText.textBlocks) {
            val lines = mutableListOf<OcrLine>()
            for (l in b.lines) {
                val elements = l.elements.map { e ->
                    OcrElement(
                        text = e.text, 
                        boundingBox = e.boundingBox,
                        confidence = e.confidence ?: 0f
                    )
                }
                val ocrLine = OcrLine(
                    text = l.text,
                    boundingBox = l.boundingBox,
                    confidence = l.confidence ?: 0f,
                    elements = elements
                )
                lines.add(ocrLine)
                allLines.add(ocrLine)
            }
            blocks.add(
                OcrBlock(
                    text = b.text,
                    boundingBox = b.boundingBox,
                    confidence = if (lines.isNotEmpty()) lines.map { it.confidence }.average().toFloat() else 0f,
                    lines = lines
                )
            )
        }

        return OcrResult(
            fullText = visionText.text,
            blocks = blocks,
            lines = allLines,
            passIndex = passIndex
        )
    }
}
