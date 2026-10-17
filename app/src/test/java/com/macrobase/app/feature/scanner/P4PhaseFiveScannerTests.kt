package com.macrobase.app.feature.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.mlkit.vision.text.Text
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

/** P4 phase 5: BUG-056 (scans and recycling) and BUG-057 (header detection). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class P4PhaseFiveScannerTests {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---------------------------------------------------------------------------------------
    // BUG-056: ML Kit finishes before a bitmap is let go
    // ---------------------------------------------------------------------------------------

    // A cancelled scan still waits for the OCR task, which reads the bitmap until it completes
    @Test
    fun cancellingDoesNotEndTheWaitBeforeTheTaskCompletes() = runBlocking {
        val source = TaskCompletionSource<String>()
        val waiter = CoroutineScope(Dispatchers.Default).launch { source.task.awaitCompletion(10_000) }
        delay(100)

        waiter.cancel()
        delay(200)
        assertFalse(waiter.isCompleted)

        source.setResult("text")
        withTimeout(5_000) { waiter.join() }
        assertTrue(waiter.isCancelled)
    }

    @Test
    fun aFinishedTaskIsReturnedAtOnce() = runBlocking {
        val task = Tasks.forResult("text")

        assertEquals("text", task.awaitCompletion(10_000)!!.result)
    }

    // A task that never finishes would hold the scan forever; past the limit it is given up on
    @Test
    fun aTaskThatNeverFinishesIsGivenUpOnAfterTheLimit() = runBlocking {
        val never = TaskCompletionSource<String>()

        assertNull(never.task.awaitCompletion(100))
    }

    // The ML Kit engine itself waits for its task, even when the scan is cancelled
    @Test
    fun theEngineWaitsForMlKitWhenTheScanIsCancelled() = runBlocking {
        val source = TaskCompletionSource<Text>()
        val engine = MlKitOcrEngine(process = { source.task }, passTimeoutMillis = 10_000)
        val scan = CoroutineScope(Dispatchers.Default).launch { engine.recognizeText(checkerboard()) }
        delay(100)

        scan.cancel()
        delay(200)
        assertFalse(scan.isCompleted)

        source.setException(IllegalStateException("closed"))
        withTimeout(5_000) { scan.join() }
        assertTrue(scan.isCancelled)
    }

    @Test
    fun theEngineGivesUpOnAStuckTaskAndSaysSo() = runBlocking {
        val engine = MlKitOcrEngine(process = { TaskCompletionSource<Text>().task }, passTimeoutMillis = 100)

        val result = engine.recognizeText(checkerboard())

        assertTrue(result.abandoned)
        assertTrue(result.lines.isEmpty())
    }

    /** Records each bitmap it is given; gives up on the first generated variant when [stuck]. */
    private class RecordingOcrEngine(private val source: Bitmap, private val stuck: Boolean) : NutritionLabelOcrEngine {
        val seen = mutableListOf<Bitmap>()
        var abandonedBitmap: Bitmap? = null
        override suspend fun recognizeText(bitmap: Bitmap): OcrResult {
            seen.add(bitmap)
            if (stuck && abandonedBitmap == null && bitmap !== source) {
                abandonedBitmap = bitmap
                return OcrResult("", emptyList(), emptyList(), abandoned = true)
            }
            return OcrResult("", emptyList(), emptyList())
        }
        override fun shutdown() {}
    }

    // A bitmap the engine may still be reading is left for the garbage collector, never recycled
    @Test
    fun aBitmapFromAnAbandonedPassIsNotRecycled() = runBlocking {
        val image = checkerboard()
        val engine = RecordingOcrEngine(image, stuck = true)

        NutritionLabelScanOrchestrator(ocrEngine = engine).processImage(image, source = ImageSource.GALLERY)

        val kept = engine.abandonedBitmap
        assertNotNull(kept)
        assertFalse(kept!!.isRecycled)
        // No further pass was started once the engine was stuck
        assertEquals(kept, engine.seen.last())
    }

    // Without an abandoned pass the same variant is recycled as before, so the test above can fail
    @Test
    fun variantsAreRecycledWhenEveryPassFinishes() = runBlocking {
        val image = checkerboard()
        val engine = RecordingOcrEngine(image, stuck = false)

        NutritionLabelScanOrchestrator(ocrEngine = engine).processImage(image, source = ImageSource.GALLERY)

        assertTrue(engine.seen.any { it !== image && it.isRecycled })
    }

    /**
     * OCR whose first pass holds the bitmap until [release] opens, as ML Kit holds it until its task
     * ends. Later passes finish at once.
     */
    private class HoldingOcrEngine : NutritionLabelOcrEngine {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val shutdowns = AtomicInteger()
        private val calls = AtomicInteger()
        override suspend fun recognizeText(bitmap: Bitmap): OcrResult = withContext(NonCancellable) {
            if (calls.getAndIncrement() == 0) {
                entered.complete(Unit)
                release.await()
            }
            OcrResult("", emptyList(), emptyList())
        }
        override fun shutdown() {
            shutdowns.incrementAndGet()
        }
    }

    private fun scanner(engine: NutritionLabelOcrEngine, decodes: AtomicInteger) = NutritionLabelScannerViewModel(
        RuntimeEnvironment.getApplication(),
        createOcrEngine = { engine },
        decodeGalleryImage = { _, _ -> decodes.incrementAndGet(); checkerboard() }
    )

    // While a scan runs, another request is ignored; the overlay's Cancel stops a scan
    @Test
    fun aSecondScanRequestWhileOneRunsIsIgnored() = runBlocking<Unit> {
        val engine = HoldingOcrEngine()
        val decodes = AtomicInteger()
        val vm = scanner(engine, decodes)

        vm.processGalleryImage(Uri.parse("content://photos/1"))
        withTimeout(10_000) { engine.entered.await() }
        vm.processGalleryImage(Uri.parse("content://photos/2"))

        assertEquals(1, decodes.get())
        assertTrue(vm.uiState.value.isAnalyzing)
        engine.release.complete(Unit)
    }

    // Leaving the screen mid-scan closes ML Kit only after the pass in flight has finished
    @Test
    fun mlKitIsClosedOnlyAfterTheRunningPassEnds() = runBlocking {
        val engine = HoldingOcrEngine()
        val vm = scanner(engine, AtomicInteger())
        val store = ViewModelStore()
        ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
        })[NutritionLabelScannerViewModel::class.java]

        vm.processGalleryImage(Uri.parse("content://photos/1"))
        withTimeout(10_000) { engine.entered.await() }
        store.clear()
        delay(200)
        assertEquals(0, engine.shutdowns.get())

        engine.release.complete(Unit)
        withTimeout(10_000) { while (engine.shutdowns.get() == 0) delay(20) }
        assertEquals(1, engine.shutdowns.get())
    }

    // Cancel, then a second scan that finishes at once, then Back: the first scan's pass is still
    // running, so ML Kit must stay open until it ends
    @Test
    fun anEarlierCancelledScanStillKeepsMlKitOpen() = runBlocking {
        val engine = HoldingOcrEngine()
        val vm = scanner(engine, AtomicInteger())
        val store = ViewModelStore()
        ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
        })[NutritionLabelScannerViewModel::class.java]

        vm.processGalleryImage(Uri.parse("content://photos/1"))
        withTimeout(10_000) { engine.entered.await() }
        vm.retryCapture()
        vm.processGalleryImage(Uri.parse("content://photos/2"))
        withTimeout(10_000) { while (vm.uiState.value.isAnalyzing) delay(20) }

        store.clear()
        delay(200)
        assertEquals(0, engine.shutdowns.get())

        engine.release.complete(Unit)
        withTimeout(10_000) { while (engine.shutdowns.get() == 0) delay(20) }
        assertEquals(1, engine.shutdowns.get())
    }

    @Test
    fun withNoScanRunningMlKitIsClosedAtOnce() {
        val engine = HoldingOcrEngine()
        val vm = scanner(engine, AtomicInteger())
        val store = ViewModelStore()
        ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
        })[NutritionLabelScannerViewModel::class.java]

        store.clear()

        assertEquals(1, engine.shutdowns.get())
    }

    // ---------------------------------------------------------------------------------------
    // BUG-057: only real column headers end the header row
    // ---------------------------------------------------------------------------------------

    /** One OCR line at [top] whose words start at the given x positions, 14 px per character. */
    private fun row(top: Int, vararg words: Pair<String, Int>): OcrLine {
        val elements = words.map { (text, x) ->
            OcrElement(text = text, spatialBounds = NutritionLabelParser.SpatialBox(x, top, x + text.length * 14, top + 30))
        }
        val text = words.joinToString(" ") { it.first }
        return OcrLine(
            text = text,
            elements = elements,
            spatialBounds = NutritionLabelParser.SpatialBox(elements.minOf { it.spatialBounds!!.left }, top, elements.maxOf { it.spatialBounds!!.right }, top + 30)
        )
    }

    private fun detect(vararg lines: OcrLine) =
        SpatialTableDetector().detectAndReconstruct(OcrResult(lines.joinToString("\n") { it.text }, emptyList(), lines.toList()))

    // "1004kJ" and "100mg" below the header used to push the header down past every row
    @Test
    fun valuesContaining100AreNotHeaders() {
        val result = detect(
            row(40, "Nutrition" to 20, "Information" to 160),
            row(80, "Per" to 300, "100g" to 350, "Per" to 500, "serving" to 550),
            row(120, "Energy" to 20, "1004kJ" to 350, "301kJ" to 550),
            row(160, "Protein" to 20, "5.0g" to 350, "1.5g" to 550),
            row(200, "Fat" to 20, "10g" to 350, "3g" to 550),
            row(240, "Carbohydrate" to 20, "60g" to 350, "18g" to 550),
            row(280, "Sodium" to 20, "100mg" to 350, "30mg" to 550)
        )

        assertEquals(setOf("calories", "protein", "fat", "carbs", "sodium"), result.rows.map { it.nutrientKey }.toSet())
        val protein = result.cellsByNutrient["protein"]?.get(ColumnType.PER_100G)
        assertNotNull(protein)
        assertEquals(5.0, protein!!.rawToken.numericValue, 0.0)
        assertEquals(110, result.columns.first().headerBottomY)
    }

    // With a 30 g serving, "0.30g" in a row matches the serving header test, and so does "Serving"
    // in a line under the table; neither may end the header row
    @Test
    fun aLowerMatchForTheServingHeaderDoesNotHideTheRows() {
        val result = detect(
            row(40, "Nutrition" to 20, "Information" to 160),
            row(70, "Serving" to 20, "size" to 130, "30g" to 200),
            row(110, "Per" to 300, "100g" to 350, "Per" to 500, "serving" to 550),
            row(150, "Energy" to 20, "1004kJ" to 350, "301kJ" to 550),
            row(190, "Protein" to 20, "5.0g" to 350, "1.5g" to 550),
            row(230, "Fat" to 20, "10g" to 350, "3g" to 550),
            row(270, "Salt" to 20, "1.0g" to 350, "0.30g" to 550),
            row(330, "Serving" to 20, "suggestion:" to 130, "with" to 300, "milk" to 370)
        )

        assertEquals(setOf("calories", "protein", "fat", "salt"), result.rows.map { it.nutrientKey }.toSet())
        assertEquals(140, result.columns.first().headerBottomY)
    }

    // %DV values with no "%DV" header are values, not the header row
    @Test
    fun percentValuesWithoutADailyValueHeaderAreNotHeaders() {
        val result = detect(
            row(40, "Per" to 300, "100g" to 350, "Per" to 500, "serving" to 550),
            row(80, "Energy" to 20, "1004kJ" to 350, "301kJ" to 550, "12%" to 700),
            row(120, "Protein" to 20, "5.0g" to 350, "1.5g" to 550, "3%" to 700),
            row(160, "Fat" to 20, "10g" to 350, "3g" to 550, "4%" to 700)
        )

        assertEquals(setOf("calories", "protein", "fat"), result.rows.map { it.nutrientKey }.toSet())
    }

    // "Serving" in "Serving size: 40g" above the table is not the per-serving column (golden test06)
    @Test
    fun theColumnsSitUnderTheHeaderRowNotTheServingSizeLine() {
        val result = detect(
            row(30, "NUTRITION" to 20, "INFORMATION" to 170),
            row(60, "Servings" to 20, "per" to 140, "package:" to 190, "5" to 310),
            row(90, "Serving" to 20, "size:" to 130, "40g" to 210),
            row(120, "Avg" to 250, "Qty" to 300, "Per" to 350, "Serving" to 400, "Avg" to 520, "Qty" to 570, "Per" to 620, "100g" to 670),
            row(160, "Energy" to 20, "680kJ" to 400, "1700kJ" to 670),
            row(200, "Protein" to 20, "3.2g" to 400, "8.0g" to 670)
        )

        // The header row's "Serving" spans 400 to 498 and its "100g" 670 to 726
        assertEquals(449f, result.columns.single { it.type == ColumnType.PER_SERVING }.centerX, 0.5f)
        assertEquals(698f, result.columns.single { it.type == ColumnType.PER_100G }.centerX, 0.5f)
        assertEquals(3.2, result.cellsByNutrient["protein"]?.get(ColumnType.PER_SERVING)?.rawToken?.numericValue ?: -1.0, 0.0)
    }

    /** A sharp, high-contrast image the quality check accepts. */
    private fun checkerboard(): Bitmap {
        val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val black = Paint().apply { color = Color.BLACK }
        for (r in 0 until 30) for (c in 0 until 30) {
            if ((r + c) % 2 == 0) canvas.drawRect(c * 20f, r * 20f, c * 20f + 20f, r * 20f + 20f, black)
        }
        return bitmap
    }
}
