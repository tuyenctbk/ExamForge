package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FlashAuto
import androidx.camera.core.ImageCapture.FLASH_MODE_AUTO
import androidx.camera.core.ImageCapture.FLASH_MODE_OFF
import androidx.camera.core.ImageCapture.FLASH_MODE_ON
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import java.util.concurrent.Executors

private const val TAG = "CameraCaptureScreen"

@Composable
fun CameraCaptureScreen(
    onBack: () -> Unit,
    onUsePhotoForForge: (Bitmap) -> Unit,
    onAnalyzePhotoNow: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val hasCamera = com.example.ui.util.rememberHasCameraHardware()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isCapturing by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var flashMode by remember { mutableIntStateOf(FLASH_MODE_AUTO) }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var torchEnabled by remember { mutableStateOf(false) }

    val captureExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose { captureExecutor.shutdown() }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val decoded = decodePickerBitmap(context, uri)
            if (decoded != null) {
                capturedBitmap = decoded
                cameraError = null
            } else {
                cameraError = context.getString(R.string.camera_err_load_image)
            }
        }
    }

    BackHandler {
        if (capturedBitmap != null) {
            capturedBitmap = null
        } else {
            onBack()
        }
    }

    // Request camera permission automatically on first entry if camera exists and not yet granted
    LaunchedEffect(hasCamera) {
        if (hasCamera && !hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (capturedBitmap != null) {
        CapturedNoteReviewOverlay(
            bitmap = capturedBitmap!!,
            onRetake = { capturedBitmap = null },
            onAttachToForge = { onUsePhotoForForge(capturedBitmap!!) },
            onAnalyzeImmediately = { onAnalyzePhotoNow(capturedBitmap!!) }
        )
        return
    }

    if (!hasCamera || !hasCameraPermission) {
        CameraPermissionFallback(
            hasCameraHardware = hasCamera,
            onGrantClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onPickGallery = {
                photoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onBack = onBack
        )
        return
    }

    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var imageCaptureUseCase by remember { mutableStateOf<ImageCapture?>(null) }
    val previewView = remember(context) {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    // Bind CameraX Preview + ImageCapture whenever lens or flash changes
    DisposableEffect(lifecycleOwner, lensFacing) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        var cameraProvider: ProcessCameraProvider? = null

        val listener = Runnable {
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setFlashMode(flashMode)
                    .build()

                val selector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()

                provider.unbindAll()
                val camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    capture
                )
                boundCamera = camera
                imageCaptureUseCase = capture
                torchEnabled = false
                cameraError = null

                // Tap-to-focus on PreviewView
                previewView.setOnTouchListener { view, event ->
                    if (event.action == MotionEvent.ACTION_UP) {
                        val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                        val action = FocusMeteringAction.Builder(point).build()
                        camera.cameraControl.startFocusAndMetering(action)
                        view.performClick()
                    }
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "CameraX bind failed", e)
                cameraError = e.localizedMessage ?: context.getString(R.string.camera_err_preview_unavailable)
            }
        }

        providerFuture.addListener(listener, mainExecutor)

        onDispose {
            try {
                cameraProvider?.unbindAll()
            } catch (_: Exception) {
            }
            boundCamera = null
            imageCaptureUseCase = null
        }
    }

    // Sync flash mode and torch without rebinding camera
    LaunchedEffect(flashMode, imageCaptureUseCase) {
        imageCaptureUseCase?.flashMode = flashMode
    }
    LaunchedEffect(torchEnabled, boundCamera) {
        val cam = boundCamera
        if (cam != null && cam.cameraInfo.hasFlashUnit()) {
            cam.cameraControl.enableTorch(torchEnabled)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0D12))
    ) {
        // Live CameraX PreviewView
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                .testTag("camerax_preview_view")
        )

        // Note-framing corner brackets overlay
        NoteScannerReticleOverlay(
            accentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxSize()
        )

        // Top translucent control bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = Color(0xAA0E1117),
                shape = CircleShape,
                modifier = Modifier.size(48.dp)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("camera_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(id = R.string.cd_camera_close),
                        tint = Color.White
                    )
                }
            }

            Surface(
                color = Color(0xBB0E1117),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color(0x44FFFFFF))
            ) {
                Text(
                    text = stringResource(id = R.string.camera_capture_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    color = Color(0xAA0E1117),
                    shape = CircleShape,
                    modifier = Modifier.size(48.dp)
                ) {
                    IconButton(
                        onClick = {
                            flashMode = when (flashMode) {
                                FLASH_MODE_AUTO -> FLASH_MODE_ON
                                FLASH_MODE_ON -> FLASH_MODE_OFF
                                else -> FLASH_MODE_AUTO
                            }
                        },
                        modifier = Modifier.testTag("camera_flash_button")
                    ) {
                        Icon(
                            imageVector = when (flashMode) {
                                FLASH_MODE_ON -> Icons.Default.FlashOn
                                FLASH_MODE_OFF -> Icons.Default.FlashOff
                                else -> Icons.Default.FlashAuto
                            },
                            contentDescription = stringResource(id = R.string.cd_camera_flash),
                            tint = if (flashMode == FLASH_MODE_ON) MaterialTheme.colorScheme.primary else Color.White
                        )
                    }
                }

                Surface(
                    color = Color(0xAA0E1117),
                    shape = CircleShape,
                    modifier = Modifier.size(48.dp)
                ) {
                    IconButton(
                        onClick = {
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                CameraSelector.LENS_FACING_FRONT
                            } else {
                                CameraSelector.LENS_FACING_BACK
                            }
                        },
                        modifier = Modifier.testTag("camera_switch_lens_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = stringResource(id = R.string.cd_camera_switch),
                            tint = Color.White
                        )
                    }
                }
            }
        }

        // Bottom guidance pill + shutter dock
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            if (cameraError != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Text(
                        text = cameraError ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else {
                Surface(
                    color = Color(0xB80E1117),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(bottom = 18.dp)
                ) {
                    Text(
                        text = stringResource(id = R.string.camera_frame_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFE5EAF2),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Gallery shortcut
                Surface(
                    color = Color(0xBB161B24),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, Color(0x44FFFFFF)),
                    modifier = Modifier.size(54.dp)
                ) {
                    IconButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.testTag("camera_gallery_shortcut")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = stringResource(id = R.string.cd_pick_gallery),
                            tint = Color.White
                        )
                    }
                }

                // Primary Shutter button
                Surface(
                    onClick = {
                        val capture = imageCaptureUseCase
                        if (capture == null || isCapturing) return@Surface
                        isCapturing = true
                        cameraError = null
                        capture.takePicture(
                            captureExecutor,
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    val bitmap = imageProxyToRotatedBitmap(image)
                                    ContextCompat.getMainExecutor(context).execute {
                                        isCapturing = false
                                        if (bitmap != null) {
                                            capturedBitmap = bitmap
                                        } else {
                                            cameraError = context.getString(R.string.camera_err_decode_photo)
                                        }
                                    }
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    Log.e(TAG, "Photo capture failed", exception)
                                    ContextCompat.getMainExecutor(context).execute {
                                        isCapturing = false
                                        cameraError = exception.localizedMessage ?: context.getString(R.string.camera_err_capture_failed)
                                    }
                                }
                            }
                        )
                    },
                    shape = CircleShape,
                    color = Color.Transparent,
                    border = BorderStroke(4.dp, Color.White),
                    modifier = Modifier
                        .size(78.dp)
                        .testTag("camera_shutter_button")
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(6.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    ) {
                        if (isCapturing) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(28.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = stringResource(id = R.string.cd_camera_shutter),
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                }

                // Torch / Low-light desk lamp toggle
                val hasFlash = boundCamera?.cameraInfo?.hasFlashUnit() == true
                Surface(
                    color = if (torchEnabled) MaterialTheme.colorScheme.primary else Color(0xBB161B24),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, Color(0x44FFFFFF)),
                    modifier = Modifier.size(54.dp)
                ) {
                    IconButton(
                        onClick = { if (hasFlash) torchEnabled = !torchEnabled },
                        enabled = hasFlash,
                        modifier = Modifier.testTag("camera_torch_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Highlight,
                            contentDescription = stringResource(id = R.string.cd_camera_torch),
                            tint = if (torchEnabled) {
                                MaterialTheme.colorScheme.onPrimary
                            } else if (hasFlash) {
                                Color.White
                            } else {
                                Color(0x66FFFFFF)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteScannerReticleOverlay(
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val marginX = size.width * 0.08f
        val topY = size.height * 0.18f
        val bottomY = size.height * 0.72f
        val cornerLen = 36.dp.toPx()
        val strokeWidth = 3.dp.toPx()

        // Top-left bracket
        drawLine(accentColor, Offset(marginX, topY), Offset(marginX + cornerLen, topY), strokeWidth, StrokeCap.Round)
        drawLine(accentColor, Offset(marginX, topY), Offset(marginX, topY + cornerLen), strokeWidth, StrokeCap.Round)

        // Top-right bracket
        drawLine(accentColor, Offset(size.width - marginX, topY), Offset(size.width - marginX - cornerLen, topY), strokeWidth, StrokeCap.Round)
        drawLine(accentColor, Offset(size.width - marginX, topY), Offset(size.width - marginX, topY + cornerLen), strokeWidth, StrokeCap.Round)

        // Bottom-left bracket
        drawLine(accentColor, Offset(marginX, bottomY), Offset(marginX + cornerLen, bottomY), strokeWidth, StrokeCap.Round)
        drawLine(accentColor, Offset(marginX, bottomY), Offset(marginX, bottomY - cornerLen), strokeWidth, StrokeCap.Round)

        // Bottom-right bracket
        drawLine(accentColor, Offset(size.width - marginX, bottomY), Offset(size.width - marginX - cornerLen, bottomY), strokeWidth, StrokeCap.Round)
        drawLine(accentColor, Offset(size.width - marginX, bottomY), Offset(size.width - marginX, bottomY - cornerLen), strokeWidth, StrokeCap.Round)
    }
}

@Composable
private fun CapturedNoteReviewOverlay(
    bitmap: Bitmap,
    onRetake: () -> Unit,
    onAttachToForge: () -> Unit,
    onAnalyzeImmediately: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0D12))
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(id = R.string.camera_cd_captured_preview),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 170.dp)
        )

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.camera_snapshot_ready_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(id = R.string.camera_snapshot_ready_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                Button(
                    onClick = onAnalyzeImmediately,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("camera_analyze_now_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(id = R.string.camera_analyze_now),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = onRetake,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("camera_retake_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(id = R.string.camera_retake))
                    }

                    OutlinedButton(
                        onClick = onAttachToForge,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("camera_attach_notes_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.EditNote,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(id = R.string.camera_add_notes))
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPermissionFallback(
    hasCameraHardware: Boolean = true,
    onGrantClick: () -> Unit,
    onPickGallery: () -> Unit,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape,
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (hasCameraHardware) {
                        stringResource(id = R.string.camera_permission_title)
                    } else {
                        stringResource(id = R.string.camera_no_hardware_title)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (hasCameraHardware) {
                        stringResource(id = R.string.camera_permission_rationale)
                    } else {
                        stringResource(id = R.string.camera_no_hardware_body)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))
                if (hasCameraHardware) {
                    Button(
                        onClick = onGrantClick,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("camera_grant_permission_button")
                    ) {
                        Text(stringResource(id = R.string.camera_grant_permission), fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }
                OutlinedButton(
                    onClick = onPickGallery,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text(stringResource(id = R.string.camera_pick_gallery_instead))
                }
                Spacer(modifier = Modifier.height(6.dp))
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.textButtonColors(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(id = R.string.common_back))
                }
            }
        }
    }
}

private fun imageProxyToRotatedBitmap(image: ImageProxy): Bitmap? {
    return try {
        val rawBitmap = image.toBitmap()
        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) {
            rawBitmap
        } else {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed converting ImageProxy to Bitmap", e)
        null
    } finally {
        image.close()
    }
}

private fun decodePickerBitmap(context: Context, uri: Uri): Bitmap? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
    } catch (_: Exception) {
        try {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }
    }
}
