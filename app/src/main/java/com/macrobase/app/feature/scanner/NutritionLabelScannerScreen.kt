package com.macrobase.app.feature.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppShapes
import com.macrobase.app.core.designsystem.AppSpacing
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.Dimensions
import com.macrobase.app.domain.model.scanner.NutritionLabelDraft


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
        if (uri != null) {
            try {
                val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
                if (bitmap != null) {
                    viewModel.processCapturedImage(bitmap, isTestImage = true)
                }
            } catch (e: Exception) {
                viewModel.onCameraCaptureError("Failed to load test image: ${e.message}")
            }
        }
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var isDebugMode by remember { mutableStateOf(false) }

    // Scanned draft will be reviewed by user in the interactive review modal before applying


    if (!hasCameraPermission) {
        CameraPermissionDeniedContent(
            onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onNavigateBack = onNavigateBack,
            modifier = modifier
        )
        return
    }

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
                        imageCaptureUseCase = imageCapture

                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                        cameraProvider.unbindAll()
                        cameraInstance = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageCapture
                        )
                    } catch (e: Throwable) {
                        e.printStackTrace()
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
                // Developer test image picker
                IconButton(
                    onClick = { galleryLauncher.launch("image/*") },
                    modifier = Modifier
                        .padding(end = AppSpacing.xs)
                        .size(44.dp)
                        .background(Color(0x66000000), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = "Test Image",
                        tint = Color.White
                    )
                }

                // Debug Mode Toggle
                IconButton(
                    onClick = { isDebugMode = !isDebugMode },
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
                shape = RoundedCornerShape(8.dp)
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
            val isShutterEnabled = !uiState.isAnalyzing && !isCaptureInProgress
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
                                            // Step 1: Extract JPEG bytes from the ImageProxy buffer
                                            val bitmap = safeDecodeImageProxy(image)
                                            // Step 2: ImageProxy is closed inside safeDecodeImageProxy
                                            
                                            if (bitmap != null) {
                                                android.util.Log.d("MacroBaseScanner", "STAGE: BITMAP_DECODED (${bitmap.width}x${bitmap.height})")
                                                // Route through existing processCapturedImage which handles
                                                // quality check, multi-pass OCR, parsing, and state updates
                                                viewModel.processCapturedImage(bitmap, isTestImage = false)
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
                }
            }
        }

        // 7. Image Quality Check Warning Modal
        uiState.qualityError?.let { error ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
                    .padding(AppSpacing.lg),
                contentAlignment = Alignment.Center
            ) {
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
                        Button(
                            onClick = { viewModel.dismissQualityError() },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Primary),
                            modifier = Modifier.fillMaxWidth().height(Dimensions.ButtonHeight)
                        ) {
                            Text("Retake Photo", style = AppTypography.Button)
                        }
                    }
                }
            }
        }

        // 8. No Text Detected Modal
        if (uiState.state is com.macrobase.app.domain.model.scanner.ScannerState.NoTextDetected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
                    .padding(AppSpacing.lg),
                contentAlignment = Alignment.Center
            ) {
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
                        Button(
                            onClick = { viewModel.retryCapture() },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Primary),
                            modifier = Modifier.fillMaxWidth().height(Dimensions.ButtonHeight)
                        ) {
                            Text("Retake Photo", style = AppTypography.Button)
                        }
                        Spacer(modifier = Modifier.height(AppSpacing.sm))
                        Button(
                            onClick = onNavigateBack,
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.SurfaceAlt),
                            modifier = Modifier.fillMaxWidth().height(Dimensions.ButtonHeight)
                        ) {
                            Text("Return to Manual Entry", style = AppTypography.Button, color = AppColors.TextPrimary)
                        }
                    }
                }
            }
        }

        // 9. Parse Failed (No Nutrition Information Identified) Modal
        if (uiState.state is com.macrobase.app.domain.model.scanner.ScannerState.ParseFailed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
                    .padding(AppSpacing.lg),
                contentAlignment = Alignment.Center
            ) {
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
                        Button(
                            onClick = { viewModel.retryCapture() },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Primary),
                            modifier = Modifier.fillMaxWidth().height(Dimensions.ButtonHeight)
                        ) {
                            Text("Retake Photo", style = AppTypography.Button)
                        }
                        Spacer(modifier = Modifier.height(AppSpacing.sm))
                        Button(
                            onClick = onNavigateBack,
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.SurfaceAlt),
                            modifier = Modifier.fillMaxWidth().height(Dimensions.ButtonHeight)
                        ) {
                            Text("Return to Manual Entry", style = AppTypography.Button, color = AppColors.TextPrimary)
                        }
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
                    .padding(AppSpacing.lg),
                contentAlignment = Alignment.Center
            ) {
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
                        Button(
                            onClick = { viewModel.retryCapture() },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Primary),
                            modifier = Modifier.fillMaxWidth().height(Dimensions.ButtonHeight)
                        ) {
                            Text("Try Again", style = AppTypography.Button)
                        }
                        Spacer(modifier = Modifier.height(AppSpacing.sm))
                        Button(
                            onClick = onNavigateBack,
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.SurfaceAlt),
                            modifier = Modifier.fillMaxWidth().height(Dimensions.ButtonHeight)
                        ) {
                            Text("Return to Manual Entry", style = AppTypography.Button, color = AppColors.TextPrimary)
                        }
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
        val brandColor = Color(0xFF00C853)

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

@Composable
fun CameraPermissionDeniedContent(
    onRequestPermission: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
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
                text = "To scan nutrition labels locally on your device, MacroBase needs camera access. Images are processed 100% on-device and never uploaded.",
                style = AppTypography.Body1,
                color = AppColors.TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(AppSpacing.sm))

            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Primary),
                modifier = Modifier.fillMaxWidth().height(Dimensions.ButtonHeight)
            ) {
                Text("Grant Camera Permission", style = AppTypography.Button)
            }

            Button(
                onClick = onNavigateBack,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.SurfaceAlt),
                modifier = Modifier.fillMaxWidth().height(Dimensions.ButtonHeight)
            ) {
                Text("Return to Manual Form", style = AppTypography.Button, color = AppColors.TextPrimary)
            }
        }
    }
}

/**
 * Safely decodes an ImageProxy into a downscaled, rotation-corrected Bitmap.
 * Closes the ImageProxy immediately after extracting bytes (before returning).
 *
 * Key design decisions:
 * 1. CameraX ImageCapture always delivers JPEG format. Extract bytes, close proxy immediately.
 * 2. Decode with inSampleSize to keep max dimension ≤ 1280px (prevents 48MP OOM).
 * 3. Apply rotation correction from EXIF/ImageInfo.
 * 4. Return plain Bitmap (not InputImage) — ViewModel handles ML Kit conversion.
 * 5. On ANY failure, return null and close proxy. Never crash.
 */
private fun safeDecodeImageProxy(image: ImageProxy): Bitmap? {
    val bytes: ByteArray
    val rotationDegrees = image.imageInfo.rotationDegrees
    try {
        val buffer = image.planes[0].buffer
        buffer.rewind()
        bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
    } catch (t: Throwable) {
        android.util.Log.e("MacroBaseScanner", "STAGE: IMAGE_BUFFER_EXTRACTION_FAILED: ${t.message}", t)
        return null
    } finally {
        // Close ImageProxy IMMEDIATELY — bytes are now an independent copy
        try { image.close() } catch (_: Throwable) {}
        android.util.Log.d("MacroBaseScanner", "STAGE: IMAGE_PROXY_CLOSED")
    }

    return try {
        // Step 1: Probe dimensions without allocating pixel memory
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
        val origWidth = boundsOpts.outWidth
        val origHeight = boundsOpts.outHeight

        if (origWidth <= 0 || origHeight <= 0) {
            android.util.Log.e("MacroBaseScanner", "STAGE: JPEG_HEADER_INVALID (${origWidth}x${origHeight})")
            return null
        }

        // Step 2: Compute inSampleSize so max dimension is <= 2560px (for higher density OCR)
        var sampleSize = 1
        while ((origWidth / sampleSize) > 2560 || (origHeight / sampleSize) > 2560) {
            sampleSize *= 2
        }
        android.util.Log.d("MacroBaseScanner", "STAGE: DECODE_PARAMS (orig=${origWidth}x${origHeight}, inSampleSize=$sampleSize, rotation=$rotationDegrees)")

        // Step 3: Decode downscaled bitmap
        val decodeOpts = BitmapFactory.Options().apply {
            this.inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return null

        // Step 4: Apply rotation if needed
        if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
            if (rotated !== rawBitmap && !rawBitmap.isRecycled) {
                rawBitmap.recycle()
            }
            rotated
        } else {
            rawBitmap
        }
    } catch (t: Throwable) {
        android.util.Log.e("MacroBaseScanner", "safeDecodeImageProxy failed: ${t.message}", t)
        // Ensure proxy is closed even on exception
        try { image.close() } catch (_: Throwable) {}
        null
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
    val shown = selectedBasis?.let { draft.withOutputBasis(it) } ?: draft

    val nutrientItems = listOf(
        Triple("Calories", shown.calories, "calories"),
        Triple("Protein", shown.protein, "protein"),
        Triple("Carbohydrates", shown.carbs, "carbs"),
        Triple("Total Fat", shown.fat, "fat"),
        Triple("Sodium", shown.sodium, "sodium"),
        Triple("Sugars", shown.sugar, "sugar"),
        Triple("Fiber", shown.fiber, "fiber")
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .padding(AppSpacing.md),
        contentAlignment = Alignment.Center
    ) {
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
                        color = Color(0x1A00C853),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = Color(0xFF00C853),
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
                            shape = RoundedCornerShape(8.dp),
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

                                        if (parsed != null) {
                                            if (parsed.needsReview) {
                                                Surface(
                                                    color = Color(0x33FFB300),
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Warning,
                                                            contentDescription = null,
                                                            tint = Color(0xFFFFB300),
                                                            modifier = Modifier.size(12.dp)
                                                        )
                                                        Spacer(modifier = Modifier.size(4.dp))
                                                        Text(
                                                            text = "Check",
                                                            style = AppTypography.Caption,
                                                            color = Color(0xFFFFB300)
                                                        )
                                                    }
                                                }
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Default.CheckCircle,
                                                    contentDescription = null,
                                                    tint = Color(0xFF00C853),
                                                    modifier = Modifier.size(16.dp)
                                                )
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
                                        shape = RoundedCornerShape(6.dp),
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

                if (draft.warnings.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                    Surface(
                        color = Color(0x22FF9800),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(AppSpacing.sm)) {
                            for (warn in draft.warnings.take(3)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = Color(0xFFFF9800),
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
                        }
                    }
                }

                Spacer(modifier = Modifier.height(AppSpacing.md))

                Button(
                    onClick = {
                        val basis = selectedBasis ?: return@Button
                        onApply(draft.withOutputBasis(basis))
                    },
                    enabled = selectedBasis != null,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Primary),
                    modifier = Modifier.fillMaxWidth().height(Dimensions.ButtonHeight)
                ) {
                    Text("Apply to Custom Food", style = AppTypography.Button)
                }

                Spacer(modifier = Modifier.height(AppSpacing.xs))

                Button(
                    onClick = onRetake,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.SurfaceAlt),
                    modifier = Modifier.fillMaxWidth().height(Dimensions.ButtonHeight)
                ) {
                    Text("Retake Photo", style = AppTypography.Button, color = AppColors.TextPrimary)
                }
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
            shape = RoundedCornerShape(8.dp),
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

