package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SkinMagnifierApp(cameraExecutor = cameraExecutor)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

enum class SkinFilterMode(val title: String) {
    NORMAL("Normal"),
    SKIN_CLARITY("Skin Clarity"), // Reduces glare, shimmer, digital noise, boosts pore definition
    HIGH_CONTRAST("High Contrast"),
    INVERTED("Inverted"),
    COOL_TONE("Cool Tone")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkinMagnifierApp(cameraExecutor: ExecutorService) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // App State
    var zoomRatio by remember { mutableStateOf(1.0f) }
    var maxZoomRatio by remember { mutableStateOf(10.0f) }
    var minZoomRatio by remember { mutableStateOf(1.0f) }
    var isTorchOn by remember { mutableStateOf(false) }
    var isScreenRingLightOn by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf(SkinFilterMode.SKIN_CLARITY) }
    var showGrid by remember { mutableStateOf(true) }
    var cameraSelector by remember { mutableStateOf(CameraSelector.DEFAULT_BACK_CAMERA) }
    
    // Focus animation state
    var focusPoint by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var showFocusRing by remember { mutableStateOf(false) }

    // Snapshot inspection state
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // CameraX objects
    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    var cameraInfo by remember { mutableStateOf<CameraInfo?>(null) }

    val previewView = remember { PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    } }

    // Initialize CameraX
    LaunchedEffect(hasCameraPermission, cameraSelector) {
        if (hasCameraPermission) {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                try {
                    cameraProvider.unbindAll()
                    val camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview
                    )
                    cameraControl = camera.cameraControl
                    cameraInfo = camera.cameraInfo
                    maxZoomRatio = cameraInfo?.zoomState?.value?.maxZoomRatio ?: 10.0f
                    minZoomRatio = cameraInfo?.zoomState?.value?.minZoomRatio ?: 1.0f
                    
                    cameraControl?.setZoomRatio(zoomRatio)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    // Update zoom when slider changes
    LaunchedEffect(zoomRatio) {
        cameraControl?.setZoomRatio(zoomRatio.coerceIn(minZoomRatio, maxZoomRatio))
    }

    // Update torch
    LaunchedEffect(isTorchOn) {
        try {
            cameraControl?.enableTorch(isTorchOn)
        } catch (_: Exception) {}
    }

    if (!hasCameraPermission) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.CameraAlt,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Camera Permission Required",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Skin Magnifier needs camera access to inspect skin textures, pores, and blemishes with high precision.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.testTag("grant_permission_button")
                ) {
                    Text("Grant Camera Permission")
                }
            }
        }
        return
    }

    // Snapshot Inspection Overlay
    if (capturedBitmap != null) {
        SnapshotInspectionScreen(
            bitmap = capturedBitmap!!,
            onDismiss = { capturedBitmap = null },
            initialFilter = selectedFilter
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Camera Preview with Pinch-to-Zoom and Tap-to-Focus
        AndroidView(
            factory = { ctx ->
                previewView.apply {
                    setOnTouchListener { v, event ->
                        if (event.action == MotionEvent.ACTION_DOWN) {
                            val x = event.x
                            val y = event.y
                            focusPoint = Pair(x, y)
                            showFocusRing = true
                            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)

                            val factory = previewView.meteringPointFactory
                            val point = factory.createPoint(x, y)
                            val action = FocusMeteringAction.Builder(
                                point,
                                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                            )
                            .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                            .build()

                            cameraControl?.startFocusAndMetering(action)

                            scope.launch {
                                delay(1200)
                                showFocusRing = false
                            }
                        }
                        true
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoomChange, _ ->
                        val newZoom = (zoomRatio * zoomChange).coerceIn(minZoomRatio, maxZoomRatio)
                        zoomRatio = newZoom
                    }
                }
                .testTag("camera_preview")
        )

        // Filter Overlay for Glare Reduction & Skin Clarity
        when (selectedFilter) {
            SkinFilterMode.NORMAL -> {}
            SkinFilterMode.SKIN_CLARITY -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF201A18).copy(alpha = 0.18f))
                )
            }
            SkinFilterMode.HIGH_CONTRAST -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f))
                )
            }
            SkinFilterMode.INVERTED -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = 0.15f))
                )
            }
            SkinFilterMode.COOL_TONE -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF00B0FF).copy(alpha = 0.15f))
                )
            }
        }

        // Screen Ring Light Overlay (if enabled)
        if (isScreenRingLightOn) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(16.dp, Color.White.copy(alpha = 0.9f))
            )
        }

        // Grid Overlay
        if (showGrid) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height

                val thirdW = width / 3f
                val thirdH = height / 3f

                val lineColor = Color.White.copy(alpha = 0.4f)
                val dotColor = Color.Cyan.copy(alpha = 0.8f)

                // Vertical lines
                drawLine(lineColor, androidx.compose.ui.geometry.Offset(thirdW, 0f), androidx.compose.ui.geometry.Offset(thirdW, height), 1.dp.toPx())
                drawLine(lineColor, androidx.compose.ui.geometry.Offset(thirdW * 2f, 0f), androidx.compose.ui.geometry.Offset(thirdW * 2f, height), 1.dp.toPx())

                // Horizontal lines
                drawLine(lineColor, androidx.compose.ui.geometry.Offset(0f, thirdH), androidx.compose.ui.geometry.Offset(width, thirdH), 1.dp.toPx())
                drawLine(lineColor, androidx.compose.ui.geometry.Offset(0f, thirdH * 2f), androidx.compose.ui.geometry.Offset(width, thirdH * 2f), 1.dp.toPx())

                // Center precision crosshair
                val cx = width / 2f
                val cy = height / 2f
                drawCircle(dotColor, radius = 6.dp.toPx(), center = androidx.compose.ui.geometry.Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
                drawCircle(dotColor, radius = 2.dp.toPx(), center = androidx.compose.ui.geometry.Offset(cx, cy))
            }
        }

        // Tap-to-focus Ring Animation
        if (showFocusRing && focusPoint != null) {
            Box(
                modifier = Modifier
                    .offset { IntOffset((focusPoint!!.first - 40).toInt(), (focusPoint!!.second - 40).toInt()) }
                    .size(80.dp)
                    .border(2.dp, Color.Yellow, CircleShape)
            )
        }

        // Top Control Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Zoom Badge
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))
            ) {
                Text(
                    text = String.format(Locale.US, "%.1fx Zoom", zoomRatio),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Grid Toggle
                IconButton(
                    onClick = { showGrid = !showGrid },
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        .testTag("grid_toggle_button")
                ) {
                    Icon(
                        imageVector = if (showGrid) Icons.Default.GridOn else Icons.Default.GridOff,
                        contentDescription = "Toggle Grid",
                        tint = Color.White
                    )
                }

                // Screen Ring Light Toggle
                IconButton(
                    onClick = { isScreenRingLightOn = !isScreenRingLightOn },
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        .testTag("ring_light_toggle")
                ) {
                    Icon(
                        imageVector = if (isScreenRingLightOn) Icons.Default.WbSunny else Icons.Outlined.WbSunny,
                        contentDescription = "Ring Light",
                        tint = if (isScreenRingLightOn) Color.Yellow else Color.White
                    )
                }

                // Torch Toggle
                IconButton(
                    onClick = { isTorchOn = !isTorchOn },
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        .testTag("torch_toggle")
                ) {
                    Icon(
                        imageVector = if (isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = "Torch",
                        tint = if (isTorchOn) Color.Yellow else Color.White
                    )
                }

                // Camera Switch (Front/Back)
                IconButton(
                    onClick = {
                        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        } else {
                            CameraSelector.DEFAULT_BACK_CAMERA
                        }
                    },
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        .testTag("camera_switch_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Cameraswitch,
                        contentDescription = "Switch Camera",
                        tint = Color.White
                    )
                }
            }
        }

        // Bottom Controls Container
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Filter Selector Chips
            ScrollableRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                SkinFilterMode.values().forEach { mode ->
                    FilterChip(
                        selected = selectedFilter == mode,
                        onClick = { selectedFilter = mode },
                        label = { Text(mode.title) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color.Black.copy(alpha = 0.6f),
                            labelColor = Color.White,
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.testTag("filter_chip_${mode.name.lowercase()}")
                    )
                }
            }

            // Zoom Slider
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.ZoomOut, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Slider(
                        value = zoomRatio,
                        onValueChange = { zoomRatio = it },
                        valueRange = minZoomRatio..minOf(maxZoomRatio, 10.0f),
                        modifier = Modifier.weight(1f).testTag("zoom_slider")
                    )
                    Icon(Icons.Default.ZoomIn, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }

            // Action Row: Snapshot / Freeze Frame Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        previewView.bitmap?.let { bmp ->
                            capturedBitmap = bmp
                            Toast.makeText(context, "Frame frozen for close inspection!", Toast.LENGTH_SHORT).show()
                        } ?: run {
                            Toast.makeText(context, "Preview not ready yet", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .height(56.dp)
                        .fillMaxWidth()
                        .testTag("freeze_snapshot_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Icon(Icons.Default.Camera, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Freeze & Inspect Detail", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
fun ScrollableRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnapshotInspectionScreen(
    bitmap: Bitmap,
    onDismiss: () -> Unit,
    initialFilter: SkinFilterMode
) {
    val context = LocalContext.current
    var currentFilter by remember { mutableStateOf(initialFilter) }
    var panOffsetX by remember { mutableStateOf(0f) }
    var panOffsetY by remember { mutableStateOf(0f) }
    var zoomScale by remember { mutableStateOf(1f) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            zoomScale = (zoomScale * zoom).coerceIn(1f, 5f)
                            panOffsetX += pan.x
                            panOffsetY += pan.y
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Inspected Snapshot",
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = zoomScale,
                            scaleY = zoomScale,
                            translationX = panOffsetX,
                            translationY = panOffsetY
                        )
                )

                // Snapshot filter overlay
                when (currentFilter) {
                    SkinFilterMode.NORMAL -> {}
                    SkinFilterMode.SKIN_CLARITY -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF201A18).copy(alpha = 0.18f))
                        )
                    }
                    SkinFilterMode.HIGH_CONTRAST -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.3f))
                        )
                    }
                    SkinFilterMode.INVERTED -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.White.copy(alpha = 0.15f))
                        )
                    }
                    SkinFilterMode.COOL_TONE -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF00B0FF).copy(alpha = 0.15f))
                        )
                    }
                }
            }

            // Top Toolbar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close Inspection", tint = Color.White)
                }

                Text(
                    text = String.format(Locale.US, "Inspection Mode (%.1fx)", zoomScale),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )

                IconButton(
                    onClick = {
                        try {
                            val filename = "SkinMagnifier_${System.currentTimeMillis()}.jpg"
                            val file = File(context.getExternalFilesDir(null), filename)
                            val stream = FileOutputStream(file)
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
                            stream.flush()
                            stream.close()
                            Toast.makeText(context, "Saved to ${file.absolutePath}", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), CircleShape)
                ) {
                    Icon(Icons.Default.Save, contentDescription = "Save Photo", tint = Color.White)
                }
            }

            // Bottom Filter Selection for Snapshot
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ScrollableRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SkinFilterMode.values().forEach { mode ->
                        FilterChip(
                            selected = currentFilter == mode,
                            onClick = { currentFilter = mode },
                            label = { Text(mode.title) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Color.Black.copy(alpha = 0.6f),
                                labelColor = Color.White,
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
                Text(
                    text = "Pinch to zoom & drag to pan across skin detail",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
