package com.macrobase.app.feature.scanner

import android.graphics.Bitmap
import android.graphics.Rect
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Result of detecting a nutrition facts table region within an image.
 */
data class TableRegionResult(
    val found: Boolean,
    val boundingBox: Rect?,
    val confidence: Double,
    val anchorMatches: List<String> = emptyList()
)

/**
 * Detects the geometric bounding box of a nutrition information panel within a captured image
 * to enable high-resolution table cropping and targeted re-OCR.
 */
class NutritionRegionDetector {

    companion object {
        private val PRIMARY_TITLE_ANCHORS = listOf(
            "nutrition facts", "nutritional facts", "nutrition information",
            "nutritional information", "valeurs nutritionnelles", "typical values",
            "approx. values", "approx values", "composition nutritionnelle"
        )

        private val SECONDARY_NUTRIENT_ANCHORS = listOf(
            "energy", "calories", "kcal", "protein", "carbohydrate", "total fat",
            "saturated fat", "sodium", "salt", "serving size", "per 100g", "per 100 g",
            "per serving", "% rda", "% dv", "% daily value"
        )
    }

    /**
     * Inspects OCR lines and bounding boxes to find the nutrition panel bounding rectangle.
     */
    fun detectRegion(ocrResult: OcrResult, imageWidth: Int, imageHeight: Int): TableRegionResult {
        if (ocrResult.lines.isEmpty() || imageWidth <= 0 || imageHeight <= 0) {
            return TableRegionResult(found = false, boundingBox = null, confidence = 0.0)
        }

        val matchingLines = mutableListOf<Pair<OcrLine, Int>>() // Pair<Line, weight>
        val matchedAnchorNames = mutableListOf<String>()

        for (line in ocrResult.lines) {
            val textLower = line.text.lowercase(Locale.ROOT)
            var weight = 0

            for (titleAnchor in PRIMARY_TITLE_ANCHORS) {
                if (textLower.contains(titleAnchor)) {
                    weight += 5
                    matchedAnchorNames.add(titleAnchor)
                    break
                }
            }

            for (nutrientAnchor in SECONDARY_NUTRIENT_ANCHORS) {
                if (textLower.contains(nutrientAnchor)) {
                    weight += 2
                    matchedAnchorNames.add(nutrientAnchor)
                    break
                }
            }

            // Numeric + unit combination in the line adds confidence
            if (textLower.contains(Regex("""\d+\s*(g|mg|kcal|kj|%)\b"""))) {
                weight += 1
            }

            if (weight > 0) {
                matchingLines.add(Pair(line, weight))
            }
        }

        if (matchingLines.size < 3) {
            // Insufficient evidence of a distinct table region; fall back to full image
            return TableRegionResult(found = false, boundingBox = null, confidence = 0.0)
        }

        // Collect bounding boxes of all matching lines
        var minLeft = Int.MAX_VALUE
        var minTop = Int.MAX_VALUE
        var maxRight = Int.MIN_VALUE
        var maxBottom = Int.MIN_VALUE
        var validBoxCount = 0

        for ((line, _) in matchingLines) {
            val box = line.spatialBounds?.toAndroidRect() ?: line.boundingBox
            if (box != null && box.width() > 0 && box.height() > 0) {
                minLeft = min(minLeft, box.left)
                minTop = min(minTop, box.top)
                maxRight = max(maxRight, box.right)
                maxBottom = max(maxBottom, box.bottom)
                validBoxCount++
            }
        }

        if (validBoxCount < 3 || minLeft >= maxRight || minTop >= maxBottom) {
            return TableRegionResult(found = false, boundingBox = null, confidence = 0.0)
        }

        // Apply generous safety margin (10% horizontally, 12% vertically)
        // Never crop so aggressively that serving-size header or column headers are removed
        val rawWidth = maxRight - minLeft
        val rawHeight = maxBottom - minTop

        val marginX = (rawWidth * 0.12).toInt().coerceAtLeast(16)
        val marginTop = (rawHeight * 0.15).toInt().coerceAtLeast(20) // Extra margin above for headers
        val marginBottom = (rawHeight * 0.10).toInt().coerceAtLeast(16)

        val cropLeft = max(0, minLeft - marginX)
        val cropTop = max(0, minTop - marginTop)
        val cropRight = min(imageWidth, maxRight + marginX)
        val cropBottom = min(imageHeight, maxBottom + marginBottom)

        val boundingBox = Rect(cropLeft, cropTop, cropRight, cropBottom)
        val confidence = (matchingLines.sumOf { it.second } * 5.0).coerceIn(40.0, 95.0)

        return TableRegionResult(
            found = true,
            boundingBox = boundingBox,
            confidence = confidence,
            anchorMatches = matchedAnchorNames.distinct()
        )
    }

    /**
     * Crops the detected table region from the source bitmap.
     */
    fun cropTable(bitmap: Bitmap, region: TableRegionResult): Bitmap? {
        val box = region.boundingBox ?: return null
        if (bitmap.isRecycled) return null

        val left = box.left.coerceIn(0, bitmap.width - 1)
        val top = box.top.coerceIn(0, bitmap.height - 1)
        val width = box.width().coerceIn(10, bitmap.width - left)
        val height = box.height().coerceIn(10, bitmap.height - top)

        if (width < 50 || height < 50) return null

        return try {
            Bitmap.createBitmap(bitmap, left, top, width, height)
        } catch (t: Throwable) {
            null
        }
    }
}
