package com.macrobase.app.feature.scanner

import android.content.Context
import android.graphics.Bitmap
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * On-device OCR backend using PaddleOCR (ONNX + OpenCV).
 */
class PaddleOcrEngine(
    private val context: Context,
    private val preprocessor: ImagePreprocessor = ImagePreprocessor()
) : NutritionLabelOcrEngine {
    
    private var ocr: PaddleOCR? = null

    suspend fun init() {
        if (ocr == null) {
            com.paddle.ocr.util.OpenCVUtils.init(context)
            ocr = PaddleOCR.create(
                context = context,
                config = PaddleOCRConfig(
                    detThresh = 0.3f,
                    detBoxThresh = 0.6f,
                    recScoreThresh = 0.0f
                ),
                engineConfig = EngineConfig(numThreads = 4),
                detModelAssetPath = "models/det/inference.onnx",
                recModelAssetPath = "models/rec/inference.onnx",
                recConfigAssetPath = "models/rec/inference.yml"
            )
        }
    }

    override suspend fun recognizeText(bitmap: Bitmap): OcrResult = withContext(Dispatchers.Default) {
        init()
        val paddle = ocr ?: return@withContext emptyResult()

        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return@withContext emptyResult()
        }

        try {
            val runResult = paddle.recognize(bitmap)
            return@withContext mapPaddleResultToOcrResult(runResult, 1, bitmap.width, bitmap.height)
        } catch (e: Exception) {
            android.util.Log.e("PaddleOcrEngine", "Failed to recognize text", e)
            return@withContext emptyResult()
        }
    }

    override suspend fun recognizeAllPasses(bitmap: Bitmap): List<OcrResult> = withContext(Dispatchers.Default) {
        // For Paddle, we might not need all passes since the model might be robust enough, 
        // but for now we'll just run a single pass and return it in a list to satisfy the interface.
        listOf(recognizeText(bitmap))
    }

    override fun shutdown() {
        val currentOcr = ocr
        ocr = null
        if (currentOcr != null) {
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                currentOcr.release()
            }
        }
    }

    private fun emptyResult() = OcrResult(
        fullText = "",
        blocks = emptyList(),
        lines = emptyList(),
        passIndex = 1
    )

    private fun mapPaddleResultToOcrResult(
        runResult: com.paddle.ocr.model.OCRRunResult,
        passIndex: Int,
        imgWidth: Int,
        imgHeight: Int
    ): OcrResult {
        // Map Paddle's OCRResult list to our lines. Paddle returns results as individual detected text boxes.
        // We'll treat each detection as a line since DB detection usually groups text lines.
        val lines = mutableListOf<OcrLine>()
        val fullTextBuilder = java.lang.StringBuilder()

        for (res in runResult.results) {
            // Calculate a bounding box from the polygon points (4 points usually for 'quad')
            val pts = res.box.points
            if (pts.isEmpty()) continue
            
            var left = Float.MAX_VALUE
            var top = Float.MAX_VALUE
            var right = Float.MIN_VALUE
            var bottom = Float.MIN_VALUE
            
            for (pt in pts) {
                if (pt.x < left) left = pt.x
                if (pt.y < top) top = pt.y
                if (pt.x > right) right = pt.x
                if (pt.y > bottom) bottom = pt.y
            }
            
            val rect = android.graphics.Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
            
            val element = OcrElement(
                text = res.text,
                boundingBox = rect,
                confidence = res.confidence
            )
            
            val ocrLine = OcrLine(
                text = res.text,
                boundingBox = rect,
                confidence = res.confidence,
                elements = listOf(element)
            )
            
            lines.add(ocrLine)
            fullTextBuilder.append(res.text).append(" ")
        }
        
        return OcrResult(
            fullText = fullTextBuilder.toString().trim(),
            blocks = emptyList(), // Paddle doesn't group into blocks out-of-the-box in this integration
            lines = lines,
            passIndex = passIndex
        )
    }
}
