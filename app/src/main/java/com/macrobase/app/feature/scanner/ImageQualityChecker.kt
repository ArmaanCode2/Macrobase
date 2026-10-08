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
    private val analyzer = NutritionLabelImageQualityAnalyzer()

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

        val analysis = analyzer.analyze(bitmap)

        // 2. Severe Darkness Check
        if (analysis.luminance < 28.0) {
            return QualityCheckResult.Fail(
                title = "Image is too dark",
                message = "The nutrition label is poorly illuminated.",
                recommendation = "Turn on the flash or move to a well-lit area."
            )
        }

        // 3. Severe Overexposure / Glare Check
        if (analysis.luminance > 240.0) {
            return QualityCheckResult.Fail(
                title = "Image is washed out",
                message = "Severe glare or overexposure detected on the label.",
                recommendation = "Tilt the camera slightly to avoid direct light reflection."
            )
        }

        // 4. Featureless / completely blurry check
        if (analysis.sharpnessScore < 5.0 && analysis.contrastScore < 10.0) {
            return QualityCheckResult.Fail(
                title = "Image is blurry",
                message = "The camera was out of focus or moved during capture.",
                recommendation = "Hold the phone steady and tap the screen to focus before capturing."
            )
        }

        return QualityCheckResult.Pass
    }
}
