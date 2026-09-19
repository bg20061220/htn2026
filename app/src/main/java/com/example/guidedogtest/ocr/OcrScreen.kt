package com.example.guidedogtest.ocr

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

@Composable
fun OcrScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> cameraPermissionGranted = granted }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "OCR / Computer Vision",
                    style = MaterialTheme.typography.titleLarge
                )
                Button(onClick = onClose) { Text("CLOSE") }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (cameraPermissionGranted) {
                OcrCameraContent(modifier = Modifier.weight(1f))
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Camera permission is required to recognize signs and labels.",
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("ALLOW CAMERA")
                    }
                }
            }
        }
    }
}

@Composable
private fun OcrCameraContent(modifier: Modifier = Modifier) {
    var frameResult by remember { mutableStateOf(OcrFrameResult()) }
    var objectDetections by remember { mutableStateOf<List<VisionObjectDetection>>(emptyList()) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        OcrCameraPreview(
            frameResult = frameResult,
            objectDetections = objectDetections,
            onResult = {
                frameResult = it
                cameraError = null
            },
            onObjectDetections = {
                objectDetections = it
                cameraError = null
            },
            onError = { cameraError = it },
            modifier = Modifier
                .fillMaxWidth()
                .weight(3f)
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = if (objectDetections.isEmpty()) {
                "Objects: none detected"
            } else {
                "Objects: " + objectDetections.joinToString { detection ->
                    "${detection.label} ${(detection.confidence * 100).toInt()}%"
                }
            },
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            text = "Recognized text",
            style = MaterialTheme.typography.titleMedium
        )
        cameraError?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text(
            text = frameResult.fullText.ifBlank {
                "Point the rear camera at an EXIT sign, room number, door label, or printed sign."
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(2f)
                .verticalScroll(rememberScrollState())
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun OcrCameraPreview(
    frameResult: OcrFrameResult,
    objectDetections: List<VisionObjectDetection>,
    onResult: (OcrFrameResult) -> Unit,
    onObjectDetections: (List<VisionObjectDetection>) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(lifecycleOwner, previewView) {
        val disposed = AtomicBoolean(false)
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val analyzer = OcrTextAnalyzer(
            context = context,
            onResult = { result ->
                ContextCompat.getMainExecutor(context).execute {
                    if (!disposed.get()) onResult(result)
                }
            },
            onObjectDetections = { detections ->
                ContextCompat.getMainExecutor(context).execute {
                    if (!disposed.get()) onObjectDetections(detections)
                }
            },
            onError = { error ->
                ContextCompat.getMainExecutor(context).execute {
                    if (!disposed.get()) onError(error)
                }
            }
        )
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        var cameraProvider: ProcessCameraProvider? = null
        var imageAnalysis: ImageAnalysis? = null

        cameraProviderFuture.addListener(
            {
                if (disposed.get()) return@addListener
                try {
                    cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { analysis -> analysis.setAnalyzer(analysisExecutor, analyzer) }

                    cameraProvider?.unbindAll()
                    cameraProvider?.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                } catch (error: Exception) {
                    onError(error.message ?: "Unable to start the rear camera.")
                }
            },
            ContextCompat.getMainExecutor(context)
        )

        onDispose {
            disposed.set(true)
            imageAnalysis?.clearAnalyzer()
            cameraProvider?.unbindAll()
            analyzer.close()
            analysisExecutor.shutdown()
        }
    }

    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )
        VisionBoundingBoxOverlay(
            frameResult = frameResult,
            objectDetections = objectDetections,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun VisionBoundingBoxOverlay(
    frameResult: OcrFrameResult,
    objectDetections: List<VisionObjectDetection>,
    modifier: Modifier = Modifier
) {
    val boxColor = MaterialTheme.colorScheme.primary
    val objectBoxColor = MaterialTheme.colorScheme.tertiary
    val objectLabelBackground = MaterialTheme.colorScheme.tertiaryContainer
    val objectLabelText = MaterialTheme.colorScheme.onTertiaryContainer
    Canvas(modifier = modifier) {
        if (frameResult.imageWidth <= 0 || frameResult.imageHeight <= 0) return@Canvas
        val scale = min(
            size.width / frameResult.imageWidth,
            size.height / frameResult.imageHeight
        )
        val horizontalOffset = (size.width - frameResult.imageWidth * scale) / 2f
        val verticalOffset = (size.height - frameResult.imageHeight * scale) / 2f

        frameResult.blocks.forEach { block ->
            val bounds = block.boundingBox
            drawRect(
                color = boxColor,
                topLeft = Offset(
                    horizontalOffset + bounds.left * scale,
                    verticalOffset + bounds.top * scale
                ),
                size = Size(bounds.width() * scale, bounds.height() * scale),
                style = Stroke(width = 3.dp.toPx())
            )
        }

        val textPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = objectLabelText.toArgb()
            textSize = 14.dp.toPx()
        }
        val backgroundPaint = android.graphics.Paint().apply {
            color = objectLabelBackground.toArgb()
            style = android.graphics.Paint.Style.FILL
        }

        objectDetections.forEach { detection ->
            val bounds = detection.boundingBox
            val left = horizontalOffset + bounds.left * scale
            val top = verticalOffset + bounds.top * scale
            val width = bounds.width() * scale
            val height = bounds.height() * scale
            drawRect(
                color = objectBoxColor,
                topLeft = Offset(left, top),
                size = Size(width, height),
                style = Stroke(width = 4.dp.toPx())
            )

            val label = "${detection.label} ${(detection.confidence * 100).toInt()}%"
            val labelWidth = textPaint.measureText(label) + 12.dp.toPx()
            val labelHeight = 22.dp.toPx()
            val labelTop = (top - labelHeight).coerceAtLeast(0f)
            drawContext.canvas.nativeCanvas.drawRect(
                left,
                labelTop,
                (left + labelWidth).coerceAtMost(size.width),
                labelTop + labelHeight,
                backgroundPaint
            )
            drawContext.canvas.nativeCanvas.drawText(
                label,
                left + 6.dp.toPx(),
                labelTop + 16.dp.toPx(),
                textPaint
            )
        }
    }
}
