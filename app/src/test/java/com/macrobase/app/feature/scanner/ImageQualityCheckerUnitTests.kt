package com.macrobase.app.feature.scanner

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageQualityCheckerUnitTests {

    private lateinit var checker: ImageQualityChecker

    @Before
    fun setUp() {
        checker = ImageQualityChecker()
    }

    @Test
    fun testLowResolutionImage() {
        val smallBitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val result = checker.evaluate(smallBitmap)

        assertTrue(result is ImageQualityChecker.QualityCheckResult.Fail)
        val fail = result as ImageQualityChecker.QualityCheckResult.Fail
        assertEquals("Image quality is too low", fail.title)
    }

    @Test
    fun testTooDarkImage() {
        // Solid black image
        val darkBitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        darkBitmap.eraseColor(Color.BLACK)

        val result = checker.evaluate(darkBitmap)
        assertTrue(result is ImageQualityChecker.QualityCheckResult.Fail)
        val fail = result as ImageQualityChecker.QualityCheckResult.Fail
        assertEquals("Image is too dark", fail.title)
    }

    @Test
    fun testOverexposedWashedOutImage() {
        // Solid white image
        val brightBitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        brightBitmap.eraseColor(Color.WHITE)

        val result = checker.evaluate(brightBitmap)
        assertTrue(result is ImageQualityChecker.QualityCheckResult.Fail)
        val fail = result as ImageQualityChecker.QualityCheckResult.Fail
        assertEquals("Image is washed out", fail.title)
    }

    @Test
    fun testBlurryFeaturelessImage() {
        // Flat gray with no edge contrast
        val grayBitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        grayBitmap.eraseColor(Color.rgb(120, 120, 120))

        val result = checker.evaluate(grayBitmap)
        assertTrue(result is ImageQualityChecker.QualityCheckResult.Fail)
        val fail = result as ImageQualityChecker.QualityCheckResult.Fail
        assertEquals("Image is blurry", fail.title)
    }

    @Test
    fun testHighContrastTextPatternPasses() {
        val sharpBitmap = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)
        sharpBitmap.eraseColor(Color.rgb(200, 200, 200)) // Light gray background

        // Draw multiple high-frequency lines representing text
        for (y in 50..450 step 20) {
            for (x in 50..450) {
                sharpBitmap.setPixel(x, y, Color.BLACK)
                if (y + 1 < 500) sharpBitmap.setPixel(x, y + 1, Color.BLACK)
                if (y + 2 < 500) sharpBitmap.setPixel(x, y + 2, Color.BLACK)
            }
        }

        val result = checker.evaluate(sharpBitmap)
        assertTrue(result is ImageQualityChecker.QualityCheckResult.Pass)
    }

    @Test
    fun testPreprocessor_downscaleIfNeeded_oversizedBitmap() {
        val preprocessor = ImagePreprocessor()
        val largeBitmap = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)
        val scaled = preprocessor.downscaleIfNeeded(largeBitmap, maxDimension = 1280)

        assertEquals(1280, scaled.width)
        assertEquals(960, scaled.height)
    }

    @Test
    fun testPreprocessor_downscaleIfNeeded_smallBitmapUnchanged() {
        val preprocessor = ImagePreprocessor()
        val normalBitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val scaled = preprocessor.downscaleIfNeeded(normalBitmap, maxDimension = 1280)

        assertEquals(800, scaled.width)
        assertEquals(600, scaled.height)
    }

    @Test
    fun testPreprocessor_createBinarizedVariant_generatesValidBinaryOutput() {
        val preprocessor = ImagePreprocessor()
        val sampleBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        sampleBitmap.eraseColor(Color.GRAY)
        for (x in 0 until 50) {
            for (y in 0 until 100) {
                sampleBitmap.setPixel(x, y, Color.WHITE)
            }
        }
        val binarized = preprocessor.createBinarizedVariant(sampleBitmap)

        assertEquals(100, binarized.width)
        assertEquals(100, binarized.height)
        assertTrue(binarized.getPixel(10, 10) == Color.WHITE || binarized.getPixel(10, 10) == Color.BLACK)
    }
}
