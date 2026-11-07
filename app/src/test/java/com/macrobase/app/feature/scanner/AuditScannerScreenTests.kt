package com.macrobase.app.feature.scanner

import android.graphics.Bitmap
import com.macrobase.app.domain.model.scanner.NutritionBasis
import com.macrobase.app.domain.model.scanner.NutritionLabelDraft
import com.macrobase.app.domain.model.scanner.ScannerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

private fun scannerScreen(): String {
    val path = "src/main/java/com/macrobase/app/feature/scanner/NutritionLabelScannerScreen.kt"
    return listOf(File(path), File("app/$path")).first { it.isFile }.readText()
}

/**
 * BUG-037: the scanner's modals were plain overlays, so a tap on the scrim reached the shutter
 * beneath and system Back left the scanner with the reviewed scan.
 */
class ScannerModalTests {

    private val draft = NutritionLabelDraft(foodName = null, detectedBasis = NutritionBasis.PER_100_G)
    private val ready = ScannerUiState(state = ScannerState.CameraReady)

    @Test
    fun everyModalCountsAsOpen() {
        assertFalse(ready.hasOpenModal)
        assertFalse(ScannerUiState(state = ScannerState.RunningOCR).hasOpenModal)
        assertTrue(ScannerUiState(state = ScannerState.Success(draft)).hasOpenModal)
        assertTrue(ScannerUiState(state = ScannerState.LowConfidence(draft)).hasOpenModal)
        assertTrue(ScannerUiState(state = ScannerState.NoTextDetected).hasOpenModal)
        assertTrue(ScannerUiState(state = ScannerState.ParseFailed).hasOpenModal)
        assertTrue(ready.copy(errorMessage = "Camera capture failed").hasOpenModal)
        assertTrue(ready.copy(qualityError = ImageQualityChecker.QualityCheckResult.Fail("Too dark", "m", "r")).hasOpenModal)
    }

    @Test
    fun theShutterIsOffBehindAModal() {
        assertTrue(shutterEnabled(ready, cameraBound = true, captureInProgress = false))
        assertFalse(shutterEnabled(ScannerUiState(state = ScannerState.Success(draft)), cameraBound = true, captureInProgress = false))
        assertFalse(shutterEnabled(ready.copy(errorMessage = "x"), cameraBound = true, captureInProgress = false))
        assertFalse(shutterEnabled(ready, cameraBound = true, captureInProgress = true))
    }

    // The denied screen sits under the same modals: its buttons are off while one is open
    @Test
    fun theDeniedScreenIsInertBehindAModal() {
        assertTrue(scannerScreen().contains("enabled = !uiState.hasOpenModal && !uiState.isAnalyzing"))
    }

    @Test
    fun backDuringAnalysisCancelsItAndStaysInTheScanner() {
        assertTrue(scannerScreen().contains("BackHandler(enabled = uiState.isAnalyzing) { viewModel.retryCapture() }"))
    }

    @Test
    fun everyModalBlocksTouchesAndMapsBackToItsOwnAction() {
        val screen = scannerScreen()

        // The wrapper, and the review, quality, no-text, parse-failed and error modals that use it
        assertEquals(6, Regex("""\bScannerModal\(""").findAll(screen).count())
        assertTrue(screen.contains("BackHandler(onBack = onBack)"))
    }
}

/** BUG-038: a camera that could not start left a black preview and a shutter that did nothing. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraBindFailureTests {

    private class NoOcr : NutritionLabelOcrEngine {
        override suspend fun recognizeText(bitmap: Bitmap) = OcrResult("", emptyList(), emptyList())
        override fun shutdown() {}
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aCameraThatCannotStartIsReportedAndStaysReported() {
        val vm = NutritionLabelScannerViewModel(RuntimeEnvironment.getApplication(), createOcrEngine = { NoOcr() })

        vm.onCameraBindFailed("No back camera")
        assertTrue(vm.uiState.value.cameraUnavailable)

        vm.retryCapture()
        assertTrue("a retake does not bring the camera back", vm.uiState.value.cameraUnavailable)
    }

    // Rotation binds the camera again; if that works, the message must go
    @Test
    fun aCameraThatBindsLaterClearsTheMessage() {
        val vm = NutritionLabelScannerViewModel(RuntimeEnvironment.getApplication(), createOcrEngine = { NoOcr() })
        vm.onCameraBindFailed("Camera in use")

        vm.onCameraBound()

        assertFalse(vm.uiState.value.cameraUnavailable)
        assertTrue(scannerScreen().contains("viewModel.onCameraBound()"))
    }

    @Test
    fun theShutterIsOffUntilTheCameraIsBound() {
        val ready = ScannerUiState(state = ScannerState.CameraReady)

        assertFalse(shutterEnabled(ready, cameraBound = false, captureInProgress = false))
        assertFalse(shutterEnabled(ready.copy(cameraUnavailable = true), cameraBound = true, captureInProgress = false))
    }

    @Test
    fun aBindFailureReachesTheViewModelAndOnlyABoundCameraIsUsed() {
        val screen = scannerScreen()

        assertTrue(screen.contains("viewModel.onCameraBindFailed("))
        assertFalse(screen.contains("e.printStackTrace()"))
        assertTrue(Regex("""bindToLifecycle\([\s\S]{0,400}?imageCaptureUseCase = imageCapture""").containsMatchIn(screen))
    }
}

/**
 * BUG-039: after "Don't ask again" the permission button did nothing, the gallery could not be
 * reached, and a permission granted in Settings was not noticed on return.
 */
class CameraPermissionTests {

    @Test
    fun onceAndroidStopsAskingTheButtonOpensSettings() {
        assertEquals(PermissionButton.REQUEST, permissionButton(deniedOnce = false, showRationale = false))
        assertEquals(PermissionButton.REQUEST, permissionButton(deniedOnce = true, showRationale = true))
        assertEquals(PermissionButton.OPEN_SETTINGS, permissionButton(deniedOnce = true, showRationale = false))
    }

    @Test
    fun theDeniedScreenOffersAPhotoAndTheScreenRechecksOnReturn() {
        val screen = scannerScreen()

        // No early return: the modals and the gallery result sit below the denied screen too
        assertFalse(Regex("""\breturn\s+}\s+var cameraInstance""").containsMatchIn(screen))
        assertTrue(screen.contains("onChoosePhoto = { galleryLauncher.launch(\"image/*\") }"))
        assertTrue(screen.contains("Settings.ACTION_APPLICATION_DETAILS_SETTINGS"))
        // A phone without a settings screen for apps must not crash on the button
        assertTrue(Regex("""runCatching \{\s*context\.startActivity\(""").containsMatchIn(screen))
        assertTrue(screen.contains("LifecycleResumeEffect("))
    }
}
