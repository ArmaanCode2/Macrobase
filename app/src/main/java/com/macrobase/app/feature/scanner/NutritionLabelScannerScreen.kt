package com.macrobase.app.feature.scanner

import com.macrobase.app.core.designsystem.components.MbButtonKind
import com.macrobase.app.core.designsystem.components.MbButton
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.content.Intent
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.LifecycleResumeEffect
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.BoxScope
import androidx.activity.compose.BackHandler
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppShapes
import com.macrobase.app.core.designsystem.AppSpacing
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.Dimensions
import com.macrobase.app.domain.model.scanner.NutritionLabelDraft


/**
 * Whether the shutter can take a picture now: only once the camera is bound (BUG-038), and never
 * behind a modal (BUG-037).
 */
internal fun shutterEnabled(state: ScannerUiState, cameraBound: Boolean, captureInProgress: Boolean): Boolean =
    cameraBound && !state.cameraUnavailable && !state.isAnalyzing && !captureInProgress && !state.hasOpenModal

internal enum class PermissionButton { REQUEST, OPEN_SETTINGS }

/**
 * What the denied screen's button does. Once Android has said no and shows no rationale, it will
 * not ask again ("Don't ask again", or a second denial), so the button opens Settings (BUG-039).
 */
internal fun permissionButton(deniedOnce: Boolean, showRationale: Boolean): PermissionButton =
    if (deniedOnce && !showRationale) PermissionButton.OPEN_SETTINGS else PermissionButton.REQUEST

@Composable
fun NutritionLabelScannerScreen(
    viewModel: NutritionLabelScannerViewModel,
    onNavigateBack: () -> Unit,
    onDraftExtracted: (NutritionLabelDraft) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        // Decoded at a bounded size off the main thread, with the normal quality checks (BUG-044)
        if (uri != null) viewModel.processGalleryImage(uri)
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    // Set once Android has said no, to tell "not asked yet" from "will not ask again" (BUG-039)
    var cameraPermissionDenied by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (!isGranted) cameraPermissionDenied = true
    }

    // Granted in Settings while away: the camera opens on return (BUG-039)
    LifecycleResumeEffect(Unit) {
        hasCameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        onPauseOrDispose { }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var isDebugMode by remember { mutableStateOf(false) }

    // Scanned draft will be reviewed by user in the interactive review modal before applying


    var cameraInstance by remember { mutableStateOf<Camera?>(null) }
    var imageCaptureUseCase by remember { mutableStateOf<ImageCapture?>(null) }
    var isCaptureInProgress by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            try {
                val cameraProvider = ProcessCameraProvider.getInstance(context).get()
                cameraProvider.unbindAll()
            } catch (ignored: Throwable) {}
        }
    }

    // Sync torch state
    LaunchedEffect(uiState.isTorchOn) {
        cameraInstance?.cameraControl?.enableTorch(uiState.isTorchOn)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (hasCameraPermission) {
            // 1. CameraX PreviewView
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }

                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()

                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }

                            val resolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    androidx.camera.core.resolutionselector.ResolutionStrategy(
                                        android.util.Size(1920, 1080),
                                        androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                    )
                                )
                                .build()
                            val imageCapture = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                .setTargetRotation(previewView.display?.rotation ?: 0)
                                .setResolutionSelector(resolutionSelector)
                                .build()
                            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                            cameraProvider.unbindAll()
                            cameraInstance = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                imageCapture
                            )
                            // Only a bound camera can take the picture, so the shutter waits for this (BUG-038)
                            imageCaptureUseCase = imageCapture
                            viewModel.onCameraBound()
                        } catch (e: Throwable) {
                            // No back camera, or CameraX could not start: say so and offer a photo (BUG-038)
                            viewModel.onCameraBindFailed(e.localizedMessage ?: e.javaClass.simpleName)
                        }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            // 2. Framing Guide Overlay (with cutout and glowing green brackets)
            ScannerFramingOverlay(
                modifier = Modifier.fillMaxSize()
            )

            // The camera could not start: a photo of the label still works (BUG-038)
            if (uiState.cameraUnavailable) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(AppSpacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
                ) {
                    Text(
                        text = "The camera could not start.",
                        style = AppTypography.Header2,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    MbButton(
                        text = "Choose a photo",
                        onClick = { galleryLauncher.launch("image/*") },
                        enabled = !uiState.isAnalyzing && !uiState.hasOpenModal
                    )
                }
            }

            // Debug Spatial Grid & Token Overlay
            if (isDebugMode) {
                ScannerDebugOverlay(
                    debugData = uiState.draft?.debugData,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 3. Top Action Bar (Back button, Flash toggle, Title)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AppSpacing.xl, start = AppSpacing.md, end = AppSpacing.md),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onNavigateBack,
                    enabled = !uiState.hasOpenModal,
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color(0x66000000), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }

                Text(
                    text = "Nutrition Scanner",
                    style = AppTypography.Header2,
                    color = Color.White
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Photo from the gallery
                    IconButton(
                        onClick = { galleryLauncher.launch("image/*") },
                        // Also off while a camera capture is being handed over, before the overlay shows
                        enabled = !uiState.isAnalyzing && !isCaptureInProgress && !uiState.hasOpenModal,
                        modifier = Modifier
                            .padding(end = AppSpacing.xs)
                            .size(44.dp)
                            .background(Color(0x66000000), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = "Choose a photo",
                            tint = Color.White
                        )
                    }

                    // Debug Mode Toggle
                    IconButton(
                        onClick = { isDebugMode = !isDebugMode },
                        enabled = !uiState.isAnalyzing && !uiState.hasOpenModal,
                        modifier = Modifier
                            .padding(end = AppSpacing.xs)
                            .size(44.dp)
                            .background(if (isDebugMode) Color(0xCC00E676) else Color(0x66000000), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Debug Overlay",
                            tint = if (isDebugMode) Color.Black else Color.White
                        )
                    }

                    IconButton(
                        onClick = { viewModel.toggleTorch() },
                        enabled = !uiState.isAnalyzing && !uiState.hasOpenModal,
                        modifier = Modifier
                            .size(44.dp)
                            .background(Color(0x66000000), CircleShape)
                    ) {
                        Icon(
                            imageVector = if (uiState.isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = "Flash",
                            tint = if (uiState.isTorchOn) AppColors.MacroCarbs else Color.White
                        )
                    }
                }
            }

            // 4. Instructions Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 80.dp, start = AppSpacing.lg, end = AppSpacing.lg),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = Color(0x99000000),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = "Fill the box with the Nutrition Facts panel.",
                        style = AppTypography.Body2,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = AppSpacing.xs)
                    )
                }
            }

            // 5. Bottom Capture Button
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = AppSpacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Hold steady and tap to scan",
                    style = AppTypography.Caption,
                    color = Color.White.copy(alpha = 0.8f)
                )
                Spacer(modifier = Modifier.height(AppSpacing.md))

                // Shutter Button
                val isShutterEnabled = shutterEnabled(uiState, cameraBound = imageCaptureUseCase != null, captureInProgress = isCaptureInProgress)
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(if (isShutterEnabled) AppColors.Primary else AppColors.TextDisabled)
                        .clickable(enabled = isShutterEnabled) {
                            val capture = imageCaptureUseCase
                            if (capture != null && !isCaptureInProgress) {
                                isCaptureInProgress = true
                                android.util.Log.d("MacroBaseScanner", "STAGE: CAPTURE_BUTTON_PRESSED")
                                capture.takePicture(
                                    ContextCompat.getMainExecutor(context),
                                    object : ImageCapture.OnImageCapturedCallback() {
                                        override fun onCaptureSuccess(image: ImageProxy) {
                                            android.util.Log.d("MacroBaseScanner", "STAGE: CAPTURE_CALLBACK_STARTED (format=${image.format}, ${image.width}x${image.height})")
                                            try {
                                                // Copy the JPEG bytes and close the frame now; decoding runs off
                                                // the main thread in the ViewModel (BUG-044)
                                                val frame = jpegFrame(image)
                                                if (frame != null) {
                                                    viewModel.processCameraFrame(frame.bytes, frame.rotationDegrees)
                                                } else {
                                                    android.util.Log.e("MacroBaseScanner", "STAGE: DECODE_FAILED")
                                                    viewModel.onCameraCaptureError("Unable to decode captured frame. Please try again.")
                                                }
                                            } catch (t: Throwable) {
                                                android.util.Log.e("MacroBaseScanner", "STAGE: CAPTURE_EXCEPTION: ${t.message}", t)
                                                // Ensure ImageProxy is closed even on unexpected exceptions
                                                try { image.close() } catch (_: Throwable) {}
                                                viewModel.onCameraCaptureError(t.localizedMessage ?: "Processing error")
                                            } finally {
                                                isCaptureInProgress = false
                                            }
                                        }

                                        override fun onError(exception: ImageCaptureException) {
                                            android.util.Log.e("MacroBaseScanner", "STAGE: CAPTURE_ERROR: ${exception.message}", exception)
                                            isCaptureInProgress = false
                                            viewModel.onCameraCaptureError(exception.localizedMessage ?: "Capture error")
                                        }
                                    }
                                )
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "Capture Label",
                            tint = AppColors.PrimaryDark,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        } else {
            // The modals and the gallery result below serve this screen too: a photo needs no camera (BUG-039)
            val activity = context as? android.app.Activity
            val button = permissionButton(
                deniedOnce = cameraPermissionDenied,
                showRationale = activity != null &&
                    ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
            )
            CameraPermissionDeniedContent(
                opensSettings = button == PermissionButton.OPEN_SETTINGS,
                onRequestPermission = {
                    if (button == PermissionButton.OPEN_SETTINGS) {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            )
                        }
                    } else {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                onChoosePhoto = { galleryLauncher.launch("image/*") },
                onNavigateBack = onNavigateBack,
                // Under an open modal its buttons are off, as the camera's are (BUG-037)
                enabled = !uiState.hasOpenModal && !uiState.isAnalyzing,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Back while analysing is its Cancel: the scanner stays open (BUG-037)
        BackHandler(enabled = uiState.isAnalyzing) { viewModel.retryCapture() }

        // 6. Analyzing Loading Overlay
        AnimatedVisibility(
            visible = uiState.isAnalyzing,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC000000)),
                contentAlignment = Alignment.Center
            ) {
                // Taps anywhere but Cancel stop here, so nothing underneath starts a second scan or
                // leaves (BUG-056). This layer sits behind the overlay's content as a sibling, so
                // Cancel gets its own taps first, finger movement included.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
                ) {
                    CircularProgressIndicator(
                        color = AppColors.Primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "Analyzing Nutrition Label",
                        style = AppTypography.Header2,
                        color = Color.White
                    )
                    Text(
                        text = uiState.currentProcessingStep ?: "Running on-device recognition...",
                        style = AppTypography.Body2,
                        color = AppColors.TextSecondary
                    )
                    TextButton(onClick = { viewModel.retryCapture() }) {
                        Text("Cancel", color = Color.White)
                    }
                }
            }
        }

        // 7. Image Quality Check Warning Modal
        uiState.qualityError?.let { error ->
            ScannerModal(onBack = { viewModel.dismissQualityError() }) {
                Surface(
                    color = AppColors.Surface,
                    shape = AppShapes.Card,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(AppSpacing.lg),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = AppColors.MacroCarbs,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.sm))
                        Text(
                            text = error.title,
                            style = AppTypography.Header2,
                            color = AppColors.TextPrimary
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.xs))
                        Text(
                            text = error.message,
                            style = AppTypography.Body2,
                            color = AppColors.TextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.xs))
                        Text(
                            text = "Tip: ${error.recommendation}",
                            style = AppTypography.Body1,
                            color = AppColors.CalorieText,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.lg))
                        MbButton(
                            text = "Retake Photo",
                            onClick = { viewModel.dismissQualityError() },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // 8. No Text Detected Modal
        if (uiState.state is com.macrobase.app.domain.model.scanner.ScannerState.NoTextDetected) {
            ScannerModal(onBack = { viewModel.retryCapture() }) {
                Surface(
                    color = AppColors.Surface,
                    shape = AppShapes.Card,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(AppSpacing.lg),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = AppColors.MacroCarbs,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.sm))
                        Text(
                            text = "No Readable Text Detected",
                            style = AppTypography.Header2,
                            color = AppColors.TextPrimary
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.xs))
                        Text(
                            text = "No readable text was detected.",
                            style = AppTypography.Body1,
                            color = AppColors.TextPrimary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.xs))
                        Text(
                            text = "Tip: Ensure the camera is pointed at the nutrition label in good lighting, avoid glare, and hold steady.",
                            style = AppTypography.Body2,
                            color = AppColors.CalorieText,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.lg))
                        MbButton(
                            text = "Retake Photo",
                            onClick = { viewModel.retryCapture() },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.sm))
                        MbButton(
                            text = "Return to Manual Entry",
                            onClick = onNavigateBack,
                            kind = MbButtonKind.Secondary,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // 9. Parse Failed (No Nutrition Information Identified) Modal
        if (uiState.state is com.macrobase.app.domain.model.scanner.ScannerState.ParseFailed) {
            ScannerModal(onBack = { viewModel.retryCapture() }) {
                Surface(
                    color = AppColors.Surface,
                    shape = AppShapes.Card,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(AppSpacing.lg),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = AppColors.MacroCarbs,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.sm))
                        Text(
                            text = "Nutrition Info Not Found",
                            style = AppTypography.Header2,
                            color = AppColors.TextPrimary
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.xs))
                        Text(
                            text = "Nutrition information could not be identified.",
                            style = AppTypography.Body1,
                            color = AppColors.TextPrimary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.xs))
                        Text(
                            text = "Tip: The scanner detected text, but could not identify a valid Nutrition Facts table. Align the label inside the green frame and avoid cropping columns.",
                            style = AppTypography.Body2,
                            color = AppColors.CalorieText,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.lg))
                        MbButton(
                            text = "Retake Photo",
                            onClick = { viewModel.retryCapture() },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.sm))
                        MbButton(
                            text = "Return to Manual Entry",
                            onClick = onNavigateBack,
                            kind = MbButtonKind.Secondary,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // 10. Interactive Nutrition Review Modal (Success, PartialSuccess, LowConfidence)
        val activeDraft = uiState.draft
        if (activeDraft != null && !uiState.isAnalyzing) {
            NutritionLabelReviewModal(
                draft = activeDraft,
                onApply = { chosenDraft ->
                    onDraftExtracted(chosenDraft)
                },
                onRetake = {
                    viewModel.retryCapture()
                }
            )
        }

        // 11. Recognition Error Modal
        uiState.errorMessage?.let { errorMsg ->
            ScannerModal(onBack = { viewModel.retryCapture() }) {
                Surface(
                    color = AppColors.Surface,
                    shape = AppShapes.Card,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(AppSpacing.lg),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = AppColors.ProgressOver,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.sm))
                        Text(
                            text = "Recognition Error",
                            style = AppTypography.Header2,
                            color = AppColors.TextPrimary
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.xs))
                        Text(
                            text = errorMsg,
                            style = AppTypography.Body2,
                            color = AppColors.TextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.lg))
                        MbButton(
                            text = "Try Again",
                            onClick = { viewModel.retryCapture() },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.sm))
                        MbButton(
                            text = "Return to Manual Entry",
                            onClick = onNavigateBack,
                            kind = MbButtonKind.Secondary,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

/**
 * Custom Canvas drawing framing guide and darkened mask outside the nutrition label bounding box.
 */
@Composable
fun ScannerFramingOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokeWidth = 3.dp.toPx()
        val cornerLength = 32.dp.toPx()
        val cornerRadius = 16.dp.toPx()

        val guideWidth = size.width * 0.84f
        val guideHeight = size.height * 0.54f
        val guideLeft = (size.width - guideWidth) / 2f
        val guideTop = size.height * 0.18f
        val guideRight = guideLeft + guideWidth
        val guideBottom = guideTop + guideHeight

        // Dark scrim around cutout
        drawRect(
            color = Color(0x66000000),
            topLeft = Offset.Zero,
            size = size
        )

        // Clear cutout window
        drawRoundRect(
            color = Color.Transparent,
            topLeft = Offset(guideLeft, guideTop),
            size = Size(guideWidth, guideHeight),
            cornerRadius = CornerRadius(cornerRadius, cornerRadius),
            blendMode = BlendMode.Clear
        )

        // Subtle bounding guide stroke
        drawRoundRect(
            color = Color.White.copy(alpha = 0.3f),
            topLeft = Offset(guideLeft, guideTop),
            size = Size(guideWidth, guideHeight),
            cornerRadius = CornerRadius(cornerRadius, cornerRadius),
            style = Stroke(width = 1.dp.toPx())
        )

        // Emerald corner brackets
        val brandColor = AppColors.Primary

        // Top Left
        drawLine(brandColor, Offset(guideLeft, guideTop + cornerLength), Offset(guideLeft, guideTop), strokeWidth)
        drawLine(brandColor, Offset(guideLeft, guideTop), Offset(guideLeft + cornerLength, guideTop), strokeWidth)

        // Top Right
        drawLine(brandColor, Offset(guideRight - cornerLength, guideTop), Offset(guideRight, guideTop), strokeWidth)
        drawLine(brandColor, Offset(guideRight, guideTop), Offset(guideRight, guideTop + cornerLength), strokeWidth)

        // Bottom Left
        drawLine(brandColor, Offset(guideLeft, guideBottom - cornerLength), Offset(guideLeft, guideBottom), strokeWidth)
        drawLine(brandColor, Offset(guideLeft, guideBottom), Offset(guideLeft + cornerLength, guideBottom), strokeWidth)

        // Bottom Right
        drawLine(brandColor, Offset(guideRight - cornerLength, guideBottom), Offset(guideRight, guideBottom), strokeWidth)
        drawLine(brandColor, Offset(guideRight, guideBottom), Offset(guideRight, guideBottom - cornerLength), strokeWidth)
    }
}

/**
 * A scanner modal. Its scrim takes every touch, so nothing beneath starts a scan or leaves, and
 * system Back does what the modal's own Retake or dismiss button does (BUG-037).
 */
@Composable
private fun ScannerModal(onBack: () -> Unit, padding: Dp = AppSpacing.lg, content: @Composable BoxScope.() -> Unit) {
    BackHandler(onBack = onBack)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
    ) {
        // Behind the content as a sibling, so the modal's own buttons and scrolling get their touches first
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
            content = content
        )
    }
}

@Composable
fun CameraPermissionDeniedContent(
    onRequestPermission: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Android no longer asks, so the button opens the app's settings (BUG-039). */
    opensSettings: Boolean = false,
    /** A photo of the label needs no camera (BUG-039). */
    onChoosePhoto: (() -> Unit)? = null,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Background)
            .padding(AppSpacing.lg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
            modifier = Modifier.padding(AppSpacing.md)
        ) {
            Icon(
                imageVector = Icons.Default.PhotoCamera,
                contentDescription = null,
                tint = AppColors.TextSecondary,
                modifier = Modifier.size(64.dp)
            )

            Text(
                text = "Camera Permission Required",
                style = AppTypography.Header1,
                color = AppColors.TextPrimary
            )

            Text(
                text = if (opensSettings) {
                    "Camera access is off for MacroBase. Turn it on in Settings, or scan a photo of the label you already have."
                } else {
                    "To scan nutrition labels locally on your device, MacroBase needs camera access. Images are processed 100% on-device and never uploaded."
                },
                style = AppTypography.Body1,
                color = AppColors.TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(AppSpacing.sm))

            MbButton(
                text = if (opensSettings) "Open Settings" else "Grant Camera Permission",
                onClick = onRequestPermission,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )

            if (onChoosePhoto != null) {
                MbButton(
                    text = "Choose a photo",
                    onClick = onChoosePhoto,
                    enabled = enabled,
                    kind = MbButtonKind.Secondary,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            MbButton(
                text = "Return to Manual Form",
                onClick = onNavigateBack,
                enabled = enabled,
                kind = MbButtonKind.Secondary,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** A captured frame's JPEG bytes and how far to turn them upright. */
private class JpegFrame(val bytes: ByteArray, val rotationDegrees: Int)

/**
 * Copies a captured frame's JPEG bytes and closes the frame straight away, so the camera can
 * deliver the next one. Decoding happens later, off the main thread (BUG-044).
 */
private fun jpegFrame(image: ImageProxy): JpegFrame? {
    val rotationDegrees = image.imageInfo.rotationDegrees
    return try {
        val buffer = image.planes[0].buffer
        buffer.rewind()
        JpegFrame(ByteArray(buffer.remaining()).also { buffer.get(it) }, rotationDegrees)
    } catch (t: Throwable) {
        android.util.Log.e("MacroBaseScanner", "STAGE: IMAGE_BUFFER_EXTRACTION_FAILED: ${t.message}", t)
        null
    } finally {
        try { image.close() } catch (_: Throwable) {}
    }
}

/**
 * Interactive Review Modal presenting extracted nutrition values.
 * Allows inspecting uncertain fields, toggling dual-basis columns (Per 100g vs Per Serving),
 * viewing OCR source snippets, and confirming before populating the Custom Food form.
 */
@Composable
fun NutritionLabelReviewModal(
    draft: com.macrobase.app.domain.model.scanner.NutritionLabelDraft,
    onApply: (com.macrobase.app.domain.model.scanner.NutritionLabelDraft) -> Unit,
    onRetake: () -> Unit
) {
    // Null until the user picks, when the label does not say what its values are per (BUG-020)
    var selectedBasis by remember(draft) { mutableStateOf(draft.defaultOutputBasis) }
    var expandedFieldKey by remember { mutableStateOf<String?>(null) }

    // Exactly what Apply will hand to the Custom Food form
    // Range checks for the basis chosen, so a value of unknown basis is judged once it has one (BUG-042)
    val shown = withRangeWarnings(selectedBasis?.let { draft.withOutputBasis(it) } ?: draft)

    val nutrientItems = listOf(
        Triple("Calories", shown.calories, "calories"),
        Triple("Protein", shown.protein, "protein"),
        Triple("Carbohydrates", shown.carbs, "carbs"),
        Triple("Total Fat", shown.fat, "fat"),
        Triple("Saturated Fat", shown.saturatedFat, "saturatedFat"),
        Triple("Sodium", shown.sodium, "sodium"),
        Triple("Sugars", shown.sugar, "sugar"),
        Triple("Fiber", shown.fiber, "fiber")
    )

    // Back is Retake here, as the modal's own button, so it never leaves the scanner (BUG-037)
    ScannerModal(onBack = onRetake, padding = AppSpacing.md) {
        Surface(
            color = AppColors.Surface,
            shape = AppShapes.Card,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = AppSpacing.sm)
        ) {
            Column(
                modifier = Modifier
                    .padding(AppSpacing.md)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Nutrition Facts Review",
                    style = AppTypography.Header2,
                    color = AppColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                Text(
                    text = "Verify detected values. Tap any item with \u26A0 Check to inspect OCR source.",
                    style = AppTypography.Body2,
                    color = AppColors.TextSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(AppSpacing.sm))
                Row(
                    modifier = Modifier
                        .background(AppColors.SurfaceAlt, RoundedCornerShape(20.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    for (option in draft.outputBasisOptions) {
                        val isSelected = selectedBasis == option
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) AppColors.Primary else Color.Transparent,
                            modifier = Modifier.clickable { selectedBasis = option }
                        ) {
                            Text(
                                text = when (option) {
                                    com.macrobase.app.domain.model.scanner.NutritionBasis.PER_1_G -> "Per 1 g"
                                    com.macrobase.app.domain.model.scanner.NutritionBasis.PER_SERVING -> "Per serving"
                                    com.macrobase.app.domain.model.scanner.NutritionBasis.PER_100_ML -> "Per 100 ml"
                                    else -> "Per 100 g"
                                },
                                style = AppTypography.Caption,
                                color = if (isSelected) Color.White else AppColors.TextSecondary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                val basisNote = when {
                    selectedBasis == null ->
                        "The label doesn't say whether these values are per 100 g or per serving. Choose the one printed on the pack."
                    draft.detectedBasis == com.macrobase.app.domain.model.scanner.NutritionBasis.PER_100_ML ->
                        "These values are per 100 ml and are saved for 100 ml."
                    !draft.canConvertToPer100g -> {
                        val size = shown.servingSize ?: 1.0
                        val sizeText = if (size % 1.0 == 0.0) size.roundToInt().toString() else size.toString()
                        val unitName = shown.servingUnit?.displayName ?: "serving"
                        "The label gives no serving weight in grams, so these values are saved for $sizeText $unitName."
                    }
                    else -> null
                }
                if (basisNote != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = basisNote,
                        style = AppTypography.Caption,
                        color = AppColors.TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }

                if (!draft.normalizationNote.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        color = AppColors.Success.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = AppColors.Success,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(
                                text = draft.normalizationNote,
                                style = AppTypography.Caption,
                                color = AppColors.TextPrimary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(AppSpacing.sm))

                // Field Cards
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for ((name, parsed, key) in nutrientItems) {
                        val isExpanded = expandedFieldKey == key
                        Surface(
                            color = AppColors.SurfaceAlt,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expandedFieldKey = if (isExpanded) null else key
                                }
                        ) {
                            Column(modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = 8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = name,
                                        style = AppTypography.Body2,
                                        color = AppColors.TextPrimary
                                    )

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = if (parsed != null) "${parsed.value} ${parsed.unit}" else "Not detected",
                                            style = AppTypography.Body1,
                                            color = if (parsed != null) AppColors.TextPrimary else AppColors.TextDisabled
                                        )

                                        // A clean row shows nothing: no check compares the OCR passes,
                                        // so a tick would claim one (BUG-041)
                                        if (parsed != null) {
                                            if (parsed.needsReview) {
                                                Surface(
                                                    color = AppColors.Warning.copy(alpha = 0.2f),
                                                    shape = RoundedCornerShape(10.dp)
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Warning,
                                                            contentDescription = null,
                                                            tint = AppColors.Warning,
                                                            modifier = Modifier.size(12.dp)
                                                        )
                                                        Spacer(modifier = Modifier.size(4.dp))
                                                        Text(
                                                            text = "Check",
                                                            style = AppTypography.Caption,
                                                            color = AppColors.Warning
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        Icon(
                                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                            contentDescription = null,
                                            tint = AppColors.TextDisabled,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                if (isExpanded && parsed != null) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Surface(
                                        color = AppColors.Surface,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            if (parsed.sourceValue != null) {
                                                Text(
                                                    text = "Source on label: ${parsed.sourceValue} ${parsed.sourceUnit ?: parsed.unit} (${parsed.sourceBasis.displayName})",
                                                    style = AppTypography.Caption,
                                                    color = AppColors.TextSecondary
                                                )
                                            }
                                            if (!parsed.sourceText.isNullOrBlank()) {
                                                Text(
                                                    text = "Raw OCR text: \"${parsed.sourceText}\"",
                                                    style = AppTypography.Caption,
                                                    color = AppColors.TextSecondary
                                                )
                                            }
                                            // Only values with a known per-100 g form show one (BUG-021)
                                            val norm100g = parsed.normalizedPer100g
                                                ?: parsed.value.takeIf { parsed.basis == com.macrobase.app.domain.model.scanner.NutritionBasis.PER_100_G }
                                            if (norm100g != null) {
                                                val norm1g = parsed.normalizedPer1g ?: (norm100g / 100.0)
                                                Text(
                                                    text = "Canonical Per 100g: $norm100g ${parsed.unit}",
                                                    style = AppTypography.Caption,
                                                    color = AppColors.TextPrimary
                                                )
                                                Text(
                                                    text = "Derived Per 1g: $norm1g ${parsed.unit}",
                                                    style = AppTypography.Caption,
                                                    color = AppColors.TextPrimary
                                                )
                                            }
                                            Text(
                                                text = "Confidence: ${parsed.confidence.name} | Method: ${parsed.extractionMethod}",
                                                style = AppTypography.Caption,
                                                color = AppColors.TextDisabled
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Critical warnings first, every one of them, never cut off (BUG-042)
                if (shown.criticalWarnings.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                    Surface(
                        color = AppColors.Error.copy(alpha = 0.13f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(AppSpacing.sm)) {
                            for (warn in shown.criticalWarnings) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Error,
                                        contentDescription = "Check this value",
                                        tint = AppColors.Error,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.size(6.dp))
                                    Text(
                                        text = warn,
                                        style = AppTypography.Caption,
                                        color = AppColors.TextPrimary
                                    )
                                }
                            }
                        }
                    }
                }

                if (draft.warnings.isNotEmpty()) {
                    var showAllWarnings by remember(draft) { mutableStateOf(false) }
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                    Surface(
                        color = AppColors.Warning.copy(alpha = 0.13f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(AppSpacing.sm)) {
                            val shownWarnings = if (showAllWarnings) draft.warnings else draft.warnings.take(3)
                            for (warn in shownWarnings) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = AppColors.Warning,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.size(6.dp))
                                    Text(
                                        text = warn,
                                        style = AppTypography.Caption,
                                        color = AppColors.TextSecondary
                                    )
                                }
                            }
                            if (draft.warnings.size > 3) {
                                TextButton(onClick = { showAllWarnings = !showAllWarnings }) {
                                    Text(
                                        text = if (showAllWarnings) "Show fewer" else "Show all (${draft.warnings.size})",
                                        style = AppTypography.Caption,
                                        color = AppColors.Primary
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(AppSpacing.md))

                MbButton(
                    text = "Apply to Custom Food",
                    onClick = {
                        val basis = selectedBasis ?: return@MbButton
                        onApply(withRangeWarnings(draft.withOutputBasis(basis)))
                    },
                    enabled = selectedBasis != null,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(AppSpacing.xs))

                MbButton(
                    text = "Retake Photo",
                    onClick = onRetake,
                    kind = MbButtonKind.Secondary,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun ScannerDebugOverlay(
    debugData: com.macrobase.app.domain.model.scanner.ScannerDebugData?,
    modifier: Modifier = Modifier
) {
    if (debugData == null) return

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasW = size.width
            val canvasH = size.height

            val refW = kotlin.math.max(1f, debugData.tableRectRight)
            val refH = kotlin.math.max(1f, debugData.tableRectBottom)
            val scaleX = canvasW / refW
            val scaleY = canvasH / refH

            // 1. Column Dividers (Vibrant Green)
            for (col in debugData.columns) {
                val cx = col.centerX * scaleX
                drawLine(
                    color = Color(0xFF00E676),
                    start = Offset(cx, 0f),
                    end = Offset(cx, canvasH),
                    strokeWidth = 3f
                )
            }

            // 2. Row Dividers (Cyan)
            for (row in debugData.rows) {
                val cy = row.centerY * scaleY
                drawLine(
                    color = Color(0xFF00E5FF),
                    start = Offset(0f, cy),
                    end = Offset(canvasW, cy),
                    strokeWidth = 2f
                )
            }

            // 3. Token Bounding Boxes (Yellow)
            for (tok in debugData.tokens) {
                val l = tok.left * scaleX
                val t = tok.top * scaleY
                val w = (tok.right - tok.left) * scaleX
                val h = (tok.bottom - tok.top) * scaleY

                drawRect(
                    color = Color(0xFFFFD600),
                    topLeft = Offset(l, t),
                    size = Size(w, h),
                    style = Stroke(width = 2f)
                )
            }
        }

        // 4. Floating Diagnostic Summary Card
        Surface(
            color = Color(0xDD1E1E1E),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .padding(top = 90.dp, start = 16.dp, end = 16.dp)
                .fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = "SPATIAL GRID DEBUG OVERLAY",
                    style = AppTypography.Caption,
                    color = Color(0xFF00E676)
                )
                Text(
                    text = "Columns (${debugData.columns.size}): " + debugData.columns.joinToString(", ") { it.name },
                    style = AppTypography.Caption,
                    color = Color.White
                )
                Text(
                    text = "Rows (${debugData.rows.size}): " + debugData.rows.take(6).joinToString(", ") { it.nutrientKey },
                    style = AppTypography.Caption,
                    color = Color(0xFF00E5FF)
                )
                if (debugData.servingSizeGrams != null) {
                    Text(
                        text = "Serving Size: ${debugData.servingSizeGrams}g (${debugData.servingSizeText ?: ""})",
                        style = AppTypography.Caption,
                        color = Color(0xFFFFD600)
                    )
                }
            }
        }
    }
}

