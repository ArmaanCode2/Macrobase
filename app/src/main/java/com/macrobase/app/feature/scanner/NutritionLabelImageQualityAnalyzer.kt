package com.macrobase.app.feature.scanner

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Measurable image quality evaluation result.
 */
data class ImageQualityResult(
    val acceptable: Boolean,
    val overallScore: Double,
    val sharpnessScore: Double,
    val contrastScore: Double,
    val resolutionScore: Double,
    val glareScore: Double,
    val luminance: Double,
    val width: Int,
    val height: Int,
    val warnings: List<String> = emptyList(),
    val rejectionReason: String? = null
)

/**
 * On-device image quality analyzer for nutrition label captures.
 * Computes objective metrics and generates informative warnings rather than aggressively
 * rejecting imperfect photos.
 */
class NutritionLabelImageQualityAnalyzer {

    fun analyze(bitmap: Bitmap): ImageQualityResult {
        if (bitmap.isRecycled) {
            return ImageQualityResult(
                acceptable = false,
                overallScore = 0.0,
                sharpnessScore = 0.0,
                contrastScore = 0.0,
                resolutionScore = 0.0,
                glareScore = 0.0,
                luminance = 0.0,
                width = 0,
                height = 0,
                rejectionReason = "Image is not available in memory."
            )
        }

        val w = bitmap.width
        val h = bitmap.height

        // 1. Resolution Score
        val minDim = min(w, h)
        val resolutionScore = when {
            minDim >= 1080 -> 100.0
            minDim >= 720 -> 80.0 + (minDim - 720) * 20.0 / 360.0
            minDim >= 480 -> 50.0 + (minDim - 480) * 30.0 / 240.0
            minDim >= 300 -> 20.0 + (minDim - 300) * 30.0 / 180.0
            else -> max(0.0, minDim * 20.0 / 300.0)
        }

        if (w < 180 || h < 180) {
            return ImageQualityResult(
                acceptable = false,
                overallScore = 10.0,
                sharpnessScore = 0.0,
                contrastScore = 0.0,
                resolutionScore = resolutionScore,
                glareScore = 100.0,
                luminance = 128.0,
                width = w,
                height = h,
                rejectionReason = "Image resolution is too low ($w x $h) to read nutrition facts."
            )
        }

        // Sample up to 64x64 grid to keep memory and CPU usage negligible (<3ms)
        val stepX = max(1, w / 64)
        val stepY = max(1, h / 64)
        val sampleCols = max(2, w / stepX)
        val sampleRows = max(2, h / stepY)

        val lumGrid = Array(sampleRows) { DoubleArray(sampleCols) }
        var totalLum = 0.0
        var totalPixels = 0
        var glarePixels = 0

        for (r in 0 until sampleRows) {
            val y = (r * stepY).coerceAtMost(h - 1)
            for (c in 0 until sampleCols) {
                val x = (c * stepX).coerceAtMost(w - 1)
                val p = bitmap.getPixel(x, y)
                val red = (p shr 16) and 0xFF
                val green = (p shr 8) and 0xFF
                val blue = p and 0xFF

                // Standard perceptual luminance
                val lum = 0.299 * red + 0.587 * green + 0.114 * blue
                lumGrid[r][c] = lum
                totalLum += lum
                totalPixels++

                if (red >= 248 && green >= 248 && blue >= 248) {
                    glarePixels++
                }
            }
        }

        val avgLuminance = if (totalPixels > 0) totalLum / totalPixels else 128.0

        // 2. Contrast Score (Standard deviation of luminance)
        var sumSqDiff = 0.0
        for (r in 0 until sampleRows) {
            for (c in 0 until sampleCols) {
                val diff = lumGrid[r][c] - avgLuminance
                sumSqDiff += diff * diff
            }
        }
        val stdDev = if (totalPixels > 0) sqrt(sumSqDiff / totalPixels) else 0.0
        val contrastScore = (stdDev * 100.0 / 64.0).coerceIn(0.0, 100.0)

        // 3. Glare Score (Inverted: 100 = no glare, 0 = severe glare)
        val glareRatio = if (totalPixels > 0) glarePixels.toDouble() / totalPixels else 0.0
        val glareScore = ((1.0 - (glareRatio * 2.5)) * 100.0).coerceIn(0.0, 100.0)

        // 4. Sharpness / Focus Score (Gradient magnitude differences)
        var gradientSum = 0.0
        var edgeCount = 0
        var strongEdges = 0

        for (r in 0 until sampleRows - 1) {
            for (c in 0 until sampleCols - 1) {
                val current = lumGrid[r][c]
                val right = lumGrid[r][c + 1]
                val down = lumGrid[r + 1][c]

                val dx = right - current
                val dy = down - current
                val gradMag = sqrt(dx * dx + dy * dy)
                gradientSum += gradMag
                edgeCount++
                if (gradMag > 15.0) {
                    strongEdges++
                }
            }
        }

        val avgGradient = if (edgeCount > 0) gradientSum / edgeCount else 0.0
        val sharpnessScore = (avgGradient * 100.0 / 25.0).coerceIn(0.0, 100.0)

        // Overall Weighted Score
        val overallScore = (sharpnessScore * 0.35 + contrastScore * 0.25 + resolutionScore * 0.25 + glareScore * 0.15)
            .coerceIn(0.0, 100.0)

        val warnings = mutableListOf<String>()

        // Non-blocking warnings that guide preprocessing variants
        if (avgLuminance < 40.0) {
            warnings.add("Image is dark — applying brightness recovery.")
        } else if (avgLuminance > 225.0) {
            warnings.add("Image is bright/washed out — enhancing contrast.")
        }

        if (sharpnessScore < 30.0) {
            warnings.add("Image is slightly blurry — trying enhanced sharpening.")
        }

        if (contrastScore < 30.0) {
            warnings.add("Low contrast detected — applying adaptive thresholding.")
        }

        if (glareRatio > 0.15) {
            warnings.add("Glare detected — mitigating reflections.")
        }

        // Severe Rejections (Only when image is genuinely impossible to read)
        val isExtremelyDark = avgLuminance < 12.0
        val isExtremelyWashedOut = avgLuminance > 250.0 && contrastScore < 5.0
        val isTotallyFeatureless = avgGradient < 0.6 && contrastScore < 5.0

        val acceptable = !(isExtremelyDark || isExtremelyWashedOut || isTotallyFeatureless)
        val rejectionReason = when {
            isExtremelyDark -> "Image is too dark to detect text. Please turn on flash or move to better lighting."
            isExtremelyWashedOut -> "Image is completely washed out by glare. Please tilt camera slightly."
            isTotallyFeatureless -> "No edges or text detected in photo. Please point camera at the nutrition table."
            else -> null
        }

        return ImageQualityResult(
            acceptable = acceptable,
            overallScore = overallScore,
            sharpnessScore = sharpnessScore,
            contrastScore = contrastScore,
            resolutionScore = resolutionScore,
            glareScore = glareScore,
            luminance = avgLuminance,
            width = w,
            height = h,
            warnings = warnings,
            rejectionReason = rejectionReason
        )
    }
}
