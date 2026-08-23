package com.macrobase.app.feature.scanner

import android.graphics.Bitmap
import kotlin.math.sqrt

/**
 * On-device image quality evaluation to detect blur, extreme darkness, glare, or low resolution
 * before passing the bitmap into OCR and parser engines.
 */
class ImageQualityChecker {

    sealed class QualityCheckResult {
        data object Pass : QualityCheckResult()
        data class Fail(
            val title: String,
            val message: String,
            val recommendation: String
        ) : QualityCheckResult()
    }

    /**
     * Inspects bitmap quality and returns a Pass or Fail with actionable user guidance.
     */
    fun evaluate(bitmap: Bitmap): QualityCheckResult {
        if (bitmap.isRecycled) {
            return QualityCheckResult.Fail(
                title = "Image Processing Error",
                message = "The image is no longer available in memory.",
                recommendation = "Please retake the photo."
            )
        }

        val width = bitmap.width
        val height = bitmap.height

        // 1. Resolution Check
        if (width < 320 || height < 320) {
            return QualityCheckResult.Fail(
                title = "Image quality is too low",
                message = "Resolution is too low for text recognition.",
                recommendation = "Move closer to the nutrition label."
            )
        }

        val stepX = (width / 120).coerceAtLeast(1)
        val stepY = (height / 120).coerceAtLeast(1)
        val sampleCols = (width / stepX).coerceAtLeast(2)
        val sampleRows = (height / stepY).coerceAtLeast(2)

        var totalLuminance = 0.0
        val lumGrid = Array(sampleRows) { DoubleArray(sampleCols) }

        for (r in 0 until sampleRows) {
            val y = (r * stepY).coerceAtMost(height - 1)
            for (c in 0 until sampleCols) {
                val x = (c * stepX).coerceAtMost(width - 1)
                val p = bitmap.getPixel(x, y)
                val red = (p shr 16) and 0xFF
                val green = (p shr 8) and 0xFF
                val blue = p and 0xFF
                val lum = 0.299 * red + 0.587 * green + 0.114 * blue
                lumGrid[r][c] = lum
                totalLuminance += lum
            }
        }

        val totalPixels = sampleRows * sampleCols
        val avgLuminance = if (totalPixels > 0) totalLuminance / totalPixels else 128.0

        // 2. Severe Darkness Check
        if (avgLuminance < 28.0) {
            return QualityCheckResult.Fail(
                title = "Image is too dark",
                message = "The nutrition label is poorly illuminated.",
                recommendation = "Turn on the flash or move to a well-lit area."
            )
        }

        // 3. Severe Overexposure / Glare Check
        if (avgLuminance > 240.0) {
            return QualityCheckResult.Fail(
                title = "Image is washed out",
                message = "Severe glare or overexposure detected on the label.",
                recommendation = "Tilt the camera slightly to avoid direct light reflection."
            )
        }

        // 4. Blur / Sharpness Check via gradient differences
        var gradientSum = 0.0
        var count = 0

        for (r in 0 until sampleRows - 1) {
            for (c in 0 until sampleCols - 1) {
                val current = lumGrid[r][c]
                val right = lumGrid[r][c + 1]
                val down = lumGrid[r + 1][c]

                val dx = right - current
                val dy = down - current
                val gradMag = sqrt(dx * dx + dy * dy)
                gradientSum += gradMag
                count++
            }
        }

        val avgGradient = if (count > 0) gradientSum / count else 0.0

        if (avgGradient < 2.5) {
            return QualityCheckResult.Fail(
                title = "Image is blurry",
                message = "The camera was out of focus or moved during capture.",
                recommendation = "Hold the phone steady and tap the screen to focus before capturing."
            )
        }

        return QualityCheckResult.Pass
    }
}
