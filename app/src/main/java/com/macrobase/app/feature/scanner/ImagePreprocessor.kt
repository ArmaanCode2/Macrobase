package com.macrobase.app.feature.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * Image preprocessing pipeline preparing camera captures for ML Kit OCR passes.
 */
class ImagePreprocessor {

    /**
     * Rotates bitmap by specified degrees.
     */
    fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Crops bitmap to the relative normalized framing rectangle [0.0..1.0].
     */
    fun cropToGuide(bitmap: Bitmap, guideRectRatio: RectF): Bitmap {
        val left = (guideRectRatio.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val top = (guideRectRatio.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val right = (guideRectRatio.right * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (guideRectRatio.bottom * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)

        val cropWidth = right - left
        val cropHeight = bottom - top

        if (cropWidth <= 10 || cropHeight <= 10) return bitmap
        return Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
    }

    /**
     * Downscales bitmap if either dimension exceeds maxDimension, preserving aspect ratio.
     * Prevents OutOfMemoryError when handling full-resolution camera captures (e.g. 4000x3000).
     */
    fun downscaleIfNeeded(bitmap: Bitmap, maxDimension: Int = 1280): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDimension && height <= maxDimension) return bitmap

        val ratio = width.toFloat() / height.toFloat()
        val targetWidth: Int
        val targetHeight: Int
        if (width > height) {
            targetWidth = maxDimension
            targetHeight = (maxDimension / ratio).toInt().coerceAtLeast(1)
        } else {
            targetHeight = maxDimension
            targetWidth = (maxDimension * ratio).toInt().coerceAtLeast(1)
        }
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    /**
     * Converts bitmap to grayscale and enhances contrast (Variant B).
     */
    fun enhanceContrast(bitmap: Bitmap, contrast: Float = 1.4f, brightness: Float = 5.0f): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // ColorMatrix for grayscale + contrast enhancement
        val cm = ColorMatrix()
        cm.setSaturation(0f)

        val scale = contrast
        val translate = (-0.5f * scale + 0.5f) * 255f + brightness
        val contrastMatrix = floatArrayOf(
            scale, 0f, 0f, 0f, translate,
            0f, scale, 0f, 0f, translate,
            0f, 0f, scale, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        )
        cm.postConcat(ColorMatrix(contrastMatrix))

        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return output
    }

    /**
     * Creates an adaptive high-contrast binarized bitmap (Pass 3 / Variant C).
     * Uses in-place single-array luminance computation with bitwise operators.
     */
    fun createBinarizedVariant(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var totalLum = 0L
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val lum = (299 * r + 587 * g + 114 * b) / 1000
            totalLum += lum
        }

        val threshold = if (pixels.isNotEmpty()) {
            (totalLum / pixels.size).toInt().coerceIn(60, 190)
        } else {
            128
        }

        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val lum = (299 * r + 587 * g + 114 * b) / 1000
            pixels[i] = if (lum > threshold) Color.WHITE else Color.BLACK
        }

        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * Creates an adaptive windowed threshold variant (Variant C+).
     * Divides the image into grid blocks to calculate local thresholding.
     * Prevents text loss on colored packaging or non-uniform lighting.
     */
    fun createAdaptiveThresholdVariant(bitmap: Bitmap, blockSize: Int = 32): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val lumArray = IntArray(width * height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            lumArray[i] = (299 * r + 587 * g + 114 * b) / 1000
        }

        val numBlocksX = max(1, (width + blockSize - 1) / blockSize)
        val numBlocksY = max(1, (height + blockSize - 1) / blockSize)
        val blockMeans = IntArray(numBlocksX * numBlocksY)

        for (by in 0 until numBlocksY) {
            val startY = by * blockSize
            val endY = min(height, startY + blockSize)
            for (bx in 0 until numBlocksX) {
                val startX = bx * blockSize
                val endX = min(width, startX + blockSize)

                var sum = 0L
                var count = 0
                for (y in startY until endY) {
                    val rowOffset = y * width
                    for (x in startX until endX) {
                        sum += lumArray[rowOffset + x]
                        count++
                    }
                }
                blockMeans[by * numBlocksX + bx] = if (count > 0) (sum / count).toInt() else 128
            }
        }

        for (y in 0 until height) {
            val by = (y / blockSize).coerceAtMost(numBlocksY - 1)
            val rowOffset = y * width
            for (x in 0 until width) {
                val bx = (x / blockSize).coerceAtMost(numBlocksX - 1)
                val localThreshold = (blockMeans[by * numBlocksX + bx] - 10).coerceIn(40, 210)
                pixels[rowOffset + x] = if (lumArray[rowOffset + x] > localThreshold) Color.WHITE else Color.BLACK
            }
        }

        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * Creates a conservatively sharpened variant (Variant D).
     * Enhances fine letter edges without ringing or character deformation.
     */
    fun createSharpenedVariant(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val srcPixels = IntArray(width * height)
        val dstPixels = IntArray(width * height)
        bitmap.getPixels(srcPixels, 0, width, 0, 0, width, height)

        // 3x3 subtle unsharp kernel: center=5, cross=-1
        for (y in 1 until height - 1) {
            val row = y * width
            val rowAbove = (y - 1) * width
            val rowBelow = (y + 1) * width
            for (x in 1 until width - 1) {
                val c = srcPixels[row + x]
                val up = srcPixels[rowAbove + x]
                val down = srcPixels[rowBelow + x]
                val left = srcPixels[row + x - 1]
                val right = srcPixels[row + x + 1]

                val r = (5 * ((c shr 16) and 0xFF) - ((up shr 16) and 0xFF) - ((down shr 16) and 0xFF) - ((left shr 16) and 0xFF) - ((right shr 16) and 0xFF)).coerceIn(0, 255)
                val g = (5 * ((c shr 8) and 0xFF) - ((up shr 8) and 0xFF) - ((down shr 8) and 0xFF) - ((left shr 8) and 0xFF) - ((right shr 8) and 0xFF)).coerceIn(0, 255)
                val b = (5 * (c and 0xFF) - (up and 0xFF) - (down and 0xFF) - (left and 0xFF) - (right and 0xFF)).coerceIn(0, 255)

                dstPixels[row + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        output.setPixels(dstPixels, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * Recovers brightness and shadow details for underexposed labels (Variant E).
     */
    fun createBrightnessRecoveryVariant(bitmap: Bitmap, brightnessBoost: Float = 40f, contrastScale: Float = 1.25f): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val cm = ColorMatrix()
        cm.setSaturation(0.2f) // Mostly desaturate to focus on contrast

        val scale = contrastScale
        val translate = (-0.5f * scale + 0.5f) * 255f + brightnessBoost
        val matrix = floatArrayOf(
            scale, 0f, 0f, 0f, translate,
            0f, scale, 0f, 0f, translate,
            0f, 0f, scale, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        )
        cm.postConcat(ColorMatrix(matrix))

        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return output
    }

    /**
     * Generates an ensemble of preprocessing variants tailored to the input quality.
     * Never mutates or destroys the original image.
     */
    fun generateVariants(
        originalBitmap: Bitmap,
        quality: ImageQualityResult? = null
    ): List<PreprocessedVariant> {
        val variants = mutableListOf<PreprocessedVariant>()

        // Variant A: Original
        variants.add(PreprocessedVariant("A", "Original", originalBitmap, isOriginal = true))

        // Variant B: Grayscale & Contrast Enhanced
        variants.add(PreprocessedVariant("B", "ContrastEnhanced", enhanceContrast(originalBitmap)))

        // Variant C: Adaptive local threshold (especially effective for colored or shaded packaging)
        variants.add(PreprocessedVariant("C", "AdaptiveThreshold", createAdaptiveThresholdVariant(originalBitmap)))

        // Variant D: Sharpened (for small text or slightly out-of-focus labels)
        if (quality == null || quality.sharpnessScore < 60.0) {
            variants.add(PreprocessedVariant("D", "Sharpened", createSharpenedVariant(originalBitmap)))
        }

        // Variant E: Brightness recovery (for dark or underexposed labels)
        if (quality != null && quality.luminance < 75.0) {
            variants.add(PreprocessedVariant("E", "BrightnessRecovery", createBrightnessRecoveryVariant(originalBitmap)))
        }

        return variants
    }
}

/**
 * Preprocessed image variant ready for OCR ensemble processing.
 */
data class PreprocessedVariant(
    val id: String,
    val name: String,
    val bitmap: Bitmap,
    val isOriginal: Boolean = false
) {
    fun recycleIfNeeded() {
        if (!isOriginal && !bitmap.isRecycled) {
            bitmap.recycle()
        }
    }
}
