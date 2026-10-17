package com.macrobase.app.feature.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.macrobase.app.domain.model.scanner.ScannerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Base64

/**
 * P3 phase 6 checks that need Android's Bitmap and Rect (BUG-042, BUG-043, BUG-044). Native
 * graphics, so decoding behaves as on a phone: a bounds-only decode returns no bitmap there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class P3PhaseSixScannerRobolectricTests {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val resolver get() = RuntimeEnvironment.getApplication().contentResolver

    // ---------------------------------------------------------------------------------------
    // BUG-044: decoding
    // ---------------------------------------------------------------------------------------

    @Test
    fun aGalleryPhotoDecodes() {
        val bitmap = ScannerBitmapDecoder.decodeSampled(resolver, Uri.fromFile(jpeg()))

        assertNotNull(bitmap)
        assertEquals(40, bitmap!!.width)
        assertEquals(20, bitmap.height)
    }

    @Test
    fun aCameraFrameDecodes() {
        val bitmap = ScannerBitmapDecoder.decode(Base64.getDecoder().decode(JPEG_40_X_20), rotationDegrees = 90)

        assertNotNull(bitmap)
        assertEquals(20, bitmap!!.width)
        assertEquals(40, bitmap.height)
    }

    @Test
    fun aLargeImageIsSampledDown() {
        val bitmap = ScannerBitmapDecoder.decodeSampled(resolver, Uri.fromFile(jpeg()), maxDim = 16)

        assertNotNull(bitmap)
        assertTrue("${bitmap!!.width} x ${bitmap.height}", bitmap.width <= 16 && bitmap.height <= 16)
    }

    // A phone photo held sideways is stored landscape with an EXIF "rotate 90" tag
    @Test
    fun aGalleryPhotoIsTurnedUprightFromItsExifOrientation() {
        val bitmap = ScannerBitmapDecoder.decodeSampled(resolver, Uri.fromFile(jpeg(ExifInterface.ORIENTATION_ROTATE_90)))

        assertEquals(20, bitmap!!.width)
        assertEquals(40, bitmap.height)
    }

    // A mirrored orientation is both turned and flipped
    @Test
    fun aMirroredOrientationIsUndone() {
        val bitmap = ScannerBitmapDecoder.decodeSampled(resolver, Uri.fromFile(jpeg(ExifInterface.ORIENTATION_TRANSPOSE)))

        assertEquals(20, bitmap!!.width)
        assertEquals(40, bitmap.height)
        assertEquals(ExifInterface.ORIENTATION_ROTATE_270, jpeg(ExifInterface.ORIENTATION_ROTATE_270).inputStream().use { ScannerBitmapDecoder.exifOrientation(it) })
    }

    @Test
    fun aFileThatIsNotAnImageIsReported() {
        val file = tempFolder.newFile("notes.txt").apply { writeText("not an image") }

        assertNull(ScannerBitmapDecoder.decodeSampled(resolver, Uri.fromFile(file)))
    }

    // The scanner screen shows a message; the app keeps running
    @Test
    fun runningOutOfMemoryOnAGalleryImageShowsAMessage() = runBlocking {
        val vm = NutritionLabelScannerViewModel(
            RuntimeEnvironment.getApplication(),
            createOcrEngine = { FakeOcrEngine(OcrResult("", emptyList(), emptyList())) },
            decodeGalleryImage = { _, _ -> throw OutOfMemoryError("Failed to allocate a 192000000 byte allocation") }
        )

        vm.processGalleryImage(Uri.fromFile(jpeg()))
        val state = withTimeout(10_000) { vm.uiState.first { it.errorMessage != null } }

        assertEquals(GALLERY_TOO_LARGE, state.errorMessage)
        assertTrue(state.state is ScannerState.CameraError)
    }

    // ---------------------------------------------------------------------------------------
    // BUG-042: the whole pipeline keeps the range warning on a high-confidence scan
    // ---------------------------------------------------------------------------------------

    @Test
    fun theOrchestratorReportsAnImpossibleValueAsCritical() = runBlocking {
        val label = ocrLines(
            "Nutrition Facts",
            "Per 100g",
            "Energy 400 kcal",
            "Protein 120 g",
            "Carbohydrate 2 g",
            "Fat 1 g"
        )
        val orchestrator = NutritionLabelScanOrchestrator(ocrEngine = FakeOcrEngine(label))

        val result = orchestrator.processImage(checkerboard(), source = ImageSource.GALLERY)

        val draft = result.draft!!
        assertTrue(draft.criticalWarnings.toString(), draft.criticalWarnings.any { it.contains("Protein exceeds 100g") })
        assertTrue(draft.warnings.none { it.contains("Protein exceeds 100g") })
        assertTrue(draft.protein!!.needsReview)
    }

    // ---------------------------------------------------------------------------------------
    // BUG-043: Rect offsets
    // ---------------------------------------------------------------------------------------

    @Test
    fun theCropOffsetMovesAndroidRectsToo() {
        val element = OcrElement(text = "5g", boundingBox = Rect(10, 20, 30, 40))
        val ocr = OcrResult("5g", emptyList(), listOf(OcrLine(text = "5g", boundingBox = Rect(10, 20, 30, 40), elements = listOf(element))))

        val moved = ocr.offsetBy(100, 300)

        assertEquals(Rect(110, 320, 130, 340), moved.lines.single().boundingBox)
        assertEquals(Rect(110, 320, 130, 340), moved.lines.single().elements.single().boundingBox)
        assertEquals(320, moved.lines.single().spatialBounds!!.top)
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /** Returns the same OCR result for every image it is given. */
    private class FakeOcrEngine(private val result: OcrResult) : NutritionLabelOcrEngine {
        override suspend fun recognizeText(bitmap: Bitmap) = result
        override fun shutdown() {}
    }

    /** OCR lines laid out top to bottom, one word per element, as ML Kit reports them. */
    private fun ocrLines(vararg rows: String): OcrResult {
        val lines = rows.mapIndexed { i, text ->
            var x = 20
            val elements = text.split(" ").map { word ->
                val box = NutritionLabelParser.SpatialBox(x, 40 + i * 40, x + word.length * 14, 70 + i * 40)
                x += word.length * 14 + 10
                OcrElement(text = word, spatialBounds = box)
            }
            OcrLine(text = text, elements = elements, spatialBounds = NutritionLabelParser.SpatialBox(20, 40 + i * 40, x, 70 + i * 40))
        }
        return OcrResult(rows.joinToString("\n"), emptyList(), lines)
    }

    /** A sharp, high-contrast image the quality check accepts. */
    private fun checkerboard(): Bitmap {
        val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val black = Paint().apply { color = Color.BLACK }
        for (row in 0 until 30) for (col in 0 until 30) {
            if ((row + col) % 2 == 0) canvas.drawRect(col * 20f, row * 20f, col * 20f + 20f, row * 20f + 20f, black)
        }
        return bitmap
    }

    /** A real 40 x 20 JPEG (made with Pillow), with an optional EXIF orientation. */
    private fun jpeg(orientation: Int? = null): File {
        val file = tempFolder.newFile()
        file.writeBytes(Base64.getDecoder().decode(JPEG_40_X_20))
        if (orientation != null) {
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
        }
        return file
    }

    private companion object {
        const val JPEG_40_X_20 = "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDABALDA4MChAODQ4SERATGCgaGBYWGDEjJR0oOjM9PDkzODdASFxOQERXRTc4UG1RV19iZ2hnPk1xeXBkeFxlZ2P/2wBDARESEhgVGC8aGi9jQjhCY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2P/wAARCAAUACgDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwCSiiivGPWCiiigAooooAKKKKACiiigAooooA//2Q=="
    }
}
