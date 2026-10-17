package com.macrobase.app.feature.scanner

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor

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
    val passIndex: Int = 1,
    /** The engine gave up waiting and may still read the bitmap, so it must not be recycled (BUG-056). */
    val abandoned: Boolean = false
)

/**
 * The same result with every box moved by ([dx], [dy]): OCR run on a crop reports boxes from the
 * crop's corner, and this puts them back where the crop sits in the full image (BUG-043).
 */
fun OcrResult.offsetBy(dx: Int, dy: Int): OcrResult {
    if (dx == 0 && dy == 0) return this
    fun Rect?.moved(): Rect? = this?.let { Rect(it).apply { offset(dx, dy) } }
    fun NutritionLabelParser.SpatialBox?.moved(): NutritionLabelParser.SpatialBox? =
        this?.let { NutritionLabelParser.SpatialBox(it.left + dx, it.top + dy, it.right + dx, it.bottom + dy) }
    fun OcrElement.moved() = copy(boundingBox = boundingBox.moved(), spatialBounds = spatialBounds.moved())
    fun OcrLine.moved() = copy(
        boundingBox = boundingBox.moved(),
        spatialBounds = spatialBounds.moved(),
        elements = elements.map { it.moved() }
    )
    return copy(
        blocks = blocks.map { block ->
            block.copy(
                boundingBox = block.boundingBox.moved(),
                spatialBounds = block.spatialBounds.moved(),
                lines = block.lines.map { it.moved() }
            )
        },
        lines = lines.map { it.moved() }
    )
}

/**
 * On-device text recognition with Google ML Kit.
 */
class MlKitOcrEngine internal constructor(
    /** Starts recognition of one bitmap; tests pass a fake so no ML Kit client is created. */
    private val process: ((Bitmap) -> Task<Text>)?,
    private val passTimeoutMillis: Long = PASS_TIMEOUT_MILLIS
) : NutritionLabelOcrEngine {

    constructor() : this(process = null)

    // One recognizer per engine, created on first use, to avoid repeated native set-up
    private val recognizerHolder = lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val recognizer by recognizerHolder

    override suspend fun recognizeText(bitmap: Bitmap): OcrResult = withContext(Dispatchers.Default) {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return@withContext emptyResult(passIndex = 1)
        }
        return@withContext processImagePass(bitmap, passIndex = 1)
    }

    /**
     * Shutdown the ML Kit text recognizer to free native memory.
     */
    override fun shutdown() {
        if (!recognizerHolder.isInitialized()) return
        try {
            recognizer.close()
        } catch (ignored: Throwable) {}
    }

    private suspend fun processImagePass(bitmap: Bitmap, passIndex: Int): OcrResult {
        if (bitmap.isRecycled) return emptyResult(passIndex)
        android.util.Log.d("MacroBaseScanner", "STAGE: OCR_STARTED (pass=$passIndex)")
        val task = try {
            process?.invoke(bitmap) ?: recognizer.process(InputImage.fromBitmap(bitmap, 0))
        } catch (t: Throwable) {
            android.util.Log.e("MacroBaseScanner", "STAGE: OCR_EXCEPTION (error=${t.message})")
            return emptyResult(passIndex)
        }
        // ML Kit reads the bitmap until its task completes, and the caller recycles the bitmap as
        // soon as this returns. So a cancelled scan still waits for the pass in flight. A task that
        // has not finished after the time limit is abandoned, and its bitmap is left for the garbage
        // collector instead of being recycled (BUG-056).
        val done = task.awaitCompletion(passTimeoutMillis)
        if (done == null) {
            android.util.Log.e("MacroBaseScanner", "STAGE: OCR_TIMEOUT (pass=$passIndex)")
            return emptyResult(passIndex).copy(abandoned = true)
        }
        currentCoroutineContext().ensureActive()
        if (!done.isSuccessful) {
            android.util.Log.e("MacroBaseScanner", "STAGE: OCR_CALLBACK_FAILURE (error=${done.exception?.message})")
            return emptyResult(passIndex)
        }
        return mapVisionTextToOcrResult(done.result, passIndex)
    }

    private fun emptyResult(passIndex: Int) = OcrResult(fullText = "", blocks = emptyList(), lines = emptyList(), passIndex = passIndex)

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

/** How long one OCR pass may take before the scan stops waiting for it. */
internal const val PASS_TIMEOUT_MILLIS = 15_000L

/**
 * Waits until the task has finished, whether it succeeded, failed or was cancelled, or null once
 * [timeoutMillis] has passed. Cancelling the caller does not cut the wait short; check for
 * cancellation after it returns (BUG-056).
 */
internal suspend fun <T> Task<T>.awaitCompletion(timeoutMillis: Long): Task<T>? {
    if (isComplete) return this
    val done = CompletableDeferred<Task<T>>()
    addOnCompleteListener(Executor { it.run() }) { done.complete(it) }
    return withContext(NonCancellable) { withTimeoutOrNull(timeoutMillis) { done.await() } }
}
