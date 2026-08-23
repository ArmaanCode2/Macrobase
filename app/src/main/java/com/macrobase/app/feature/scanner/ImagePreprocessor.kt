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
     * Converts bitmap to grayscale and enhances contrast (Pass 2 variant).
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
     * Creates an adaptive high-contrast binarized bitmap (Pass 3 variant).
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
}
