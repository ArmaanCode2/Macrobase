package com.macrobase.app.feature.scanner

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CancellationException
import java.io.InputStream

/**
 * Decodes scanner images at a bounded size, so a 50 MP photo never has to fit in memory at full
 * resolution (BUG-044). Camera frames and gallery images share it.
 */
object ScannerBitmapDecoder {

    /** Longest side kept for OCR: small label text stays readable, memory stays bounded. */
    const val MAX_DIMENSION = 2560

    /** The power-of-two sample size that brings both sides to [maxDim] or less. */
    fun calculateInSampleSize(width: Int, height: Int, maxDim: Int = MAX_DIMENSION): Int {
        var sampleSize = 1
        while (width / sampleSize > maxDim || height / sampleSize > maxDim) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /** Encoded image bytes (a camera JPEG), sampled down and turned upright by [rotationDegrees]. */
    fun decode(bytes: ByteArray, rotationDegrees: Int, maxDim: Int = MAX_DIMENSION): Bitmap? =
        decodeSampled({ bytes.inputStream() }, maxDim)?.let { transform(it, rotationMatrix(rotationDegrees)) }

    /**
     * A gallery image, sampled down and turned upright as its EXIF orientation says. Null when
     * the file is not an image. An [OutOfMemoryError] is left to the caller to report.
     */
    fun decodeSampled(resolver: ContentResolver, uri: Uri, maxDim: Int = MAX_DIMENSION): Bitmap? {
        // The orientation first: a small read, so a failure here never costs a decoded image
        val orientation = try {
            resolver.openInputStream(uri)?.use { exifOrientation(it) } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No readable EXIF: show the image as stored
            ExifInterface.ORIENTATION_NORMAL
        }
        val bitmap = decodeSampled({ resolver.openInputStream(uri) }, maxDim) ?: return null
        return transform(bitmap, orientationMatrix(orientation))
    }

    /** The EXIF orientation tag of an image, or [ExifInterface.ORIENTATION_NORMAL]. */
    fun exifOrientation(stream: InputStream): Int =
        ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

    /**
     * The transform that shows an image the way its EXIF [orientation] says it was taken,
     * mirrored orientations included. Null when the image is stored upright.
     */
    fun orientationMatrix(orientation: Int): Matrix? = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { setScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply { setRotate(180f) }
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply { setRotate(180f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply { setRotate(90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply { setRotate(90f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply { setRotate(-90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply { setRotate(-90f) }
        else -> null
    }

    private fun rotationMatrix(degrees: Int): Matrix? =
        if (degrees % 360 == 0) null else Matrix().apply { setRotate(degrees.toFloat()) }

    /** Reads the size first, then decodes only as many pixels as [maxDim] needs. */
    private fun decodeSampled(open: () -> InputStream?, maxDim: Int): Bitmap? {
        // A bounds-only decode returns no bitmap on Android; only the options are filled in
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = open() ?: return null
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDim)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return open()?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun transform(bitmap: Bitmap, matrix: Matrix?): Bitmap {
        if (matrix == null) return bitmap
        val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (transformed !== bitmap) bitmap.recycle()
        return transformed
    }
}

/** An image ready to scan, or why it cannot be (BUG-044). */
sealed interface ScannerImage {
    data class Loaded(val bitmap: Bitmap) : ScannerImage
    data class Failed(val message: String) : ScannerImage
}

/**
 * Runs [decode], turning every failure into a message for the user: out of memory, a broken
 * file, or any other error from the image codec (BUG-044).
 */
fun loadScannerImage(
    unreadableMessage: String = GALLERY_NOT_AN_IMAGE,
    tooLargeMessage: String = GALLERY_TOO_LARGE,
    failedMessage: String = GALLERY_UNREADABLE,
    decode: () -> Bitmap?
): ScannerImage = try {
    decode()?.let { ScannerImage.Loaded(it) } ?: ScannerImage.Failed(unreadableMessage)
} catch (e: CancellationException) {
    throw e
} catch (_: OutOfMemoryError) {
    ScannerImage.Failed(tooLargeMessage)
} catch (_: Throwable) {
    ScannerImage.Failed(failedMessage)
}

const val GALLERY_TOO_LARGE = "This image is too large. Try a smaller photo."
const val GALLERY_NOT_AN_IMAGE = "This file is not an image the scanner can read."
const val GALLERY_UNREADABLE = "Couldn't open this image. Try another photo."
