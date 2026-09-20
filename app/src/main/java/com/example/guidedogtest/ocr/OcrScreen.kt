package com.example.guidedogtest.ocr

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.guidedogtest.Drive
import com.example.guidedogtest.voice.VoicePriority
import kotlinx.coroutines.delay

@Composable
fun OcrScreen(
    onClose: () -> Unit,
    speakObstacleAlert: (String) -> Boolean = { false },
    speakAvoidanceAlert: (String, VoicePriority) -> Boolean = { _, _ -> false },
    robotConnected: Boolean = false,
    initialAutonomousEnabled: Boolean = false,
    routeNavigationActive: Boolean = false,
    desiredRouteDirection: DesiredTravelDirection = DesiredTravelDirection.FORWARD,
    emergencyStopSignal: Long = 0L,
    onAutonomousAvoidanceEnabled: (Boolean) -> Unit = {},
    onAutonomousFrame: (String?) -> Unit = {},
    onAvoidanceDecision: (AvoidanceDecision) -> Unit = {},
) {
    val context = LocalContext.current
    DisposableEffect(context) {
        val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            onAutonomousAvoidanceEnabled(false)
            onAutonomousFrame(Drive.STOP_FRAME)
            if (previous != null) activity.requestedOrientation = previous
        }
    }
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
                    text = "ROBOT CAMERA",
                    style = MaterialTheme.typography.titleLarge
                )
                Button(onClick = onClose) { Text("CLOSE") }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (cameraPermissionGranted) {
                OcrCameraContent(
                    speakObstacleAlert = speakObstacleAlert,
                    speakAvoidanceAlert = speakAvoidanceAlert,
                    robotConnected = robotConnected,
                    initialAutonomousEnabled = initialAutonomousEnabled,
                    routeNavigationActive = routeNavigationActive,
                    desiredRouteDirection = desiredRouteDirection,
                    emergencyStopSignal = emergencyStopSignal,
                    onAutonomousAvoidanceEnabled = onAutonomousAvoidanceEnabled,
                    onAutonomousFrame = onAutonomousFrame,
                    onAvoidanceDecision = onAvoidanceDecision,
                    modifier = Modifier.weight(1f)
                )
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
private fun OcrCameraContent(
    speakObstacleAlert: (String) -> Boolean,
    speakAvoidanceAlert: (String, VoicePriority) -> Boolean,
    robotConnected: Boolean,
    initialAutonomousEnabled: Boolean,
    routeNavigationActive: Boolean,
    desiredRouteDirection: DesiredTravelDirection,
    emergencyStopSignal: Long,
    onAutonomousAvoidanceEnabled: (Boolean) -> Unit,
    onAutonomousFrame: (String?) -> Unit,
    onAvoidanceDecision: (AvoidanceDecision) -> Unit,
    modifier: Modifier = Modifier
) {
    var frameResult by remember { mutableStateOf(OcrFrameResult()) }
    var objectDetections by remember { mutableStateOf<List<VisionObjectDetection>>(emptyList()) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var depthStatus by remember { mutableStateOf(ArDepthStatus()) }
    var autonomousEnabled by remember { mutableStateOf(initialAutonomousEnabled) }
    var avoidanceDecision by remember { mutableStateOf(AvoidanceDecision(AvoidanceState.IDLE, com.example.guidedogtest.WheelSpeeds(0, 0), "AUTO OFF")) }
    var obstacleAlertsEnabled by remember { mutableStateOf(true) }
    var warningDistanceMeters by remember { mutableStateOf(2f) }
    var lastObstacleAlert by remember { mutableStateOf<String?>(null) }
    var rawSceneAwareness by remember { mutableStateOf<SceneAwarenessResult?>(null) }
    val visualizationStabilizer = remember { DepthVisualizationStabilizer() }
    var depthVisualization by remember {
        mutableStateOf(DepthVisualization(DepthVisualizationStabilizer.unknownScene(), DepthVisualState.STALE))
    }
    val obstacleAlertManager = remember {
        ObstacleAlertManager(speak = speakObstacleAlert)
    }
    val avoidanceController = remember { ObstacleAvoidanceController() }
    val avoidanceSpeechManager = remember { AvoidanceSpeechManager(speak = speakAvoidanceAlert) }

    LaunchedEffect(emergencyStopSignal) {
        if (emergencyStopSignal > 0L) {
            autonomousEnabled = false
            avoidanceDecision = AvoidanceDecision(
                AvoidanceState.STOPPED,
                com.example.guidedogtest.WheelSpeeds(0, 0),
                "EMERGENCY STOP"
            )
            onAutonomousAvoidanceEnabled(false)
            onAutonomousFrame(Drive.STOP_FRAME)
        }
    }

    fun applyAvoidance(scene: SceneAwarenessResult? = rawSceneAwareness) {
        avoidanceDecision = avoidanceController.update(
            scene = scene,
            enabled = autonomousEnabled,
            cameraAvailable = depthStatus.sessionActive,
            depthAvailable = depthStatus.depthActive,
            robotConnected = robotConnected,
            desiredDirection = desiredRouteDirection,
        )
        onAutonomousFrame(if (autonomousEnabled) avoidanceDecision.wheelSpeeds.frame() else null)
        onAvoidanceDecision(avoidanceDecision)
        if (autonomousEnabled) avoidanceSpeechManager.consider(avoidanceDecision, scene)
    }

    LaunchedEffect(initialAutonomousEnabled, routeNavigationActive) {
        if (initialAutonomousEnabled || routeNavigationActive) {
            autonomousEnabled = true
            onAutonomousAvoidanceEnabled(true)
            applyAvoidance()
        }
    }

    LaunchedEffect(desiredRouteDirection) {
        if (autonomousEnabled) applyAvoidance()
    }

    LaunchedEffect(autonomousEnabled, robotConnected, depthStatus.sessionActive, depthStatus.depthActive) {
        while (autonomousEnabled) {
            avoidanceController.watchdog(
                enabled = true,
                cameraAvailable = depthStatus.sessionActive,
                depthAvailable = depthStatus.depthActive,
                robotConnected = robotConnected,
            )?.let {
                avoidanceDecision = it
                onAutonomousFrame(it.wheelSpeeds.frame())
                onAvoidanceDecision(it)
                avoidanceSpeechManager.consider(it, rawSceneAwareness)
            }
            delay(100L)
        }
    }

    // UI persistence is separate from the raw scene used by the safety controller above.
    LaunchedEffect(Unit) {
        while (true) {
            depthVisualization = visualizationStabilizer.current(
                depthSupported = depthStatus.message?.contains("not supported", ignoreCase = true) != true,
                sessionActive = depthStatus.sessionActive,
                freshDepthAvailable = depthStatus.depthActive,
            )
            delay(100L)
        }
    }

    LaunchedEffect(objectDetections, frameResult.imageWidth, obstacleAlertsEnabled, warningDistanceMeters) {
        if (obstacleAlertsEnabled) {
            obstacleAlertManager.consider(
                detections = objectDetections,
                frameWidth = frameResult.imageWidth,
                warningDistanceMeters = warningDistanceMeters
            )?.let { lastObstacleAlert = it.speech }
        } else {
            obstacleAlertManager.reset()
            lastObstacleAlert = null
        }
    }

    Row(modifier = modifier.fillMaxSize()) {
        ArCoreDepthPreview(
                frameResult = frameResult,
                objectDetections = objectDetections,
                sceneAwareness = depthVisualization.scene,
                onResult = { frameResult = it; cameraError = null },
                onObjectDetections = { objectDetections = it; cameraError = null },
                onSceneAwareness = {
                    rawSceneAwareness = it
                    depthVisualization = visualizationStabilizer.accept(it)
                    applyAvoidance(it)
                },
                onStatus = {
                    depthStatus = it
                    depthVisualization = visualizationStabilizer.current(
                        depthSupported = it.message?.contains("not supported", ignoreCase = true) != true,
                        sessionActive = it.sessionActive,
                        freshDepthAvailable = it.depthActive,
                    )
                    if (autonomousEnabled && (!it.sessionActive || !it.depthActive)) {
                        applyAvoidance()
                    }
                },
                onError = { cameraError = it },
                modifier = Modifier.fillMaxHeight().weight(3f)
            )

        Spacer(modifier = Modifier.width(10.dp))
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Obstacle Voice Alerts", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Warn within ${String.format(java.util.Locale.US, "%.1f", warningDistanceMeters)} m",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = obstacleAlertsEnabled,
                onCheckedChange = { obstacleAlertsEnabled = it }
            )
        }
        Slider(
            value = warningDistanceMeters,
            onValueChange = { warningDistanceMeters = it },
            valueRange = 0.5f..4f,
            steps = 6,
            enabled = obstacleAlertsEnabled,
            modifier = Modifier.fillMaxWidth()
        )
        lastObstacleAlert?.let {
            Text(
                text = "Last alert: $it",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Text(
            text = "ARCore session: ${if (depthStatus.sessionActive) "active" else "inactive"} • " +
                "Depth API: ${if (depthStatus.depthActive) "active" else "inactive"} • " +
                "latest depth: ${depthStatus.latestDepthTimestampNanos?.let { "$it ns" } ?: "none"}",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = "Depth: ${depthVisualization.state}",
            color = if (depthVisualization.state == DepthVisualState.ACTIVE) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall
        )
        depthStatus.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        SceneAwarenessPanel(depthVisualization.scene)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("AUTONOMOUS AVOIDANCE: ${if (autonomousEnabled) "ON" else "OFF"}")
            Switch(
                checked = autonomousEnabled,
                enabled = !routeNavigationActive,
                onCheckedChange = { enabled ->
                    autonomousEnabled = enabled
                    onAutonomousAvoidanceEnabled(enabled)
                    if (!enabled) {
                        avoidanceSpeechManager.reset()
                        avoidanceDecision = AvoidanceDecision(AvoidanceState.IDLE, com.example.guidedogtest.WheelSpeeds(0, 0), "AUTO OFF")
                        onAutonomousFrame(null)
                    } else {
                        applyAvoidance()
                    }
                }
            )
        }
        Text("AUTO: ${if (autonomousEnabled) "ON" else "OFF"} • Action: ${avoidanceDecision.action}")
        Text("NAVIGATION: ${if (routeNavigationActive) "ACTIVE" else "INACTIVE"}", style = MaterialTheme.typography.labelSmall)
        Text("Desired route direction: $desiredRouteDirection", style = MaterialTheme.typography.labelSmall)
        Text("Local action: ${avoidanceDecision.action}", style = MaterialTheme.typography.labelSmall)
        Text("Target corridor: ${avoidanceDecision.targetCorridorOffset?.let { String.format(java.util.Locale.US, "%+.2f", it) } ?: "--"}", style = MaterialTheme.typography.labelSmall)
        Text("Motor output: L: ${avoidanceDecision.wheelSpeeds.left}  R: ${avoidanceDecision.wheelSpeeds.right}", style = MaterialTheme.typography.labelSmall)
        Button(onClick = {
            autonomousEnabled = false
            onAutonomousAvoidanceEnabled(false)
            avoidanceDecision = AvoidanceDecision(
                AvoidanceState.STOPPED,
                com.example.guidedogtest.WheelSpeeds(0, 0),
                "MANUAL STOP"
            )
            onAutonomousFrame(Drive.STOP_FRAME)
        }) {
            Text("STOP")
        }

        Text(
            text = if (objectDetections.isEmpty()) {
                "Objects: none detected"
            } else {
                "Objects: " + objectDetections.joinToString { detection ->
                    detection.displayLabel()
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
                .height(96.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge
        )
        }
    }
}

@Composable
private fun ArCoreDepthPreview(
    frameResult: OcrFrameResult,
    objectDetections: List<VisionObjectDetection>,
    sceneAwareness: SceneAwarenessResult?,
    onResult: (OcrFrameResult) -> Unit,
    onObjectDetections: (List<VisionObjectDetection>) -> Unit,
    onSceneAwareness: (SceneAwarenessResult) -> Unit,
    onStatus: (ArDepthStatus) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val currentOnResult by androidx.compose.runtime.rememberUpdatedState(onResult)
    val currentOnObjects by androidx.compose.runtime.rememberUpdatedState(onObjectDetections)
    val currentOnScene by androidx.compose.runtime.rememberUpdatedState(onSceneAwareness)
    val currentOnStatus by androidx.compose.runtime.rememberUpdatedState(onStatus)
    val currentOnError by androidx.compose.runtime.rememberUpdatedState(onError)
    val view = remember {
        ArCoreDepthCameraView(
            context,
            onText = { result -> mainExecutor.execute { currentOnResult(result) } },
            onObjects = { objects -> mainExecutor.execute { currentOnObjects(objects) } },
            onSceneAwareness = { scene -> mainExecutor.execute { currentOnScene(scene) } },
            onStatus = { status -> mainExecutor.execute { currentOnStatus(status) } },
            onError = { error -> mainExecutor.execute { currentOnError(error) } }
        )
    }
    DisposableEffect(lifecycleOwner, view) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view.resumeSession()
                Lifecycle.Event.ON_PAUSE -> view.pauseSession()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.resumeSession()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            view.release()
        }
    }
    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        VisionBoundingBoxOverlay(frameResult, objectDetections, Modifier.fillMaxSize())
        SceneZoneOverlay(sceneAwareness = sceneAwareness, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun SceneAwarenessPanel(result: SceneAwarenessResult?) {
    val value = result ?: SceneAwarenessResult()
    Text(
        text = buildString {
            appendLine("Support: ${if (value.supportPlaneDetected) "TRACKED" else "UNKNOWN"}")
            appendLine("Drop: ${if (value.dropDetected) "DETECTED" else "none"}")
            val corridor = value.freeCorridor
            appendLine(if (corridor == null) "Free corridor: NONE" else {
                val side = when { corridor.centerOffset < -0.12f -> "LEFT"; corridor.centerOffset > 0.12f -> "RIGHT"; else -> "CENTER" }
                "Free corridor: $side (${corridor.widthColumns} columns)"
            })
            append("Recommendation: ${value.recommendedDirection}")
        },
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun SceneZoneOverlay(
    sceneAwareness: SceneAwarenessResult?,
    modifier: Modifier = Modifier
) {
    val clearColor = Color(0xFF2E7D32)
    val cautionColor = Color(0xFFF9A825)
    val blockedColor = Color(0xFFC62828)
    val unknownColor = Color(0xFF607D8B)
    Canvas(modifier = modifier) {
        val left = size.width * SceneAwarenessAnalyzer.REGION_LEFT
        val top = size.height * SceneAwarenessAnalyzer.REGION_TOP
        val regionWidth = size.width *
            (SceneAwarenessAnalyzer.REGION_RIGHT - SceneAwarenessAnalyzer.REGION_LEFT)
        val columns = sceneAwareness?.gridColumns ?: SceneAwarenessAnalyzer.GRID_COLUMNS
        val rows = sceneAwareness?.gridRows ?: SceneAwarenessAnalyzer.GRID_ROWS
        val cellWidth = regionWidth / columns
        val regionHeight = size.height *
            (SceneAwarenessAnalyzer.REGION_BOTTOM - SceneAwarenessAnalyzer.REGION_TOP)
        val cellHeight = regionHeight / rows
        val cells = sceneAwareness?.cells.orEmpty()
        cells.forEach { cell ->
            val color = when (cell.state) {
                OccupancyState.FREE -> clearColor
                OccupancyState.CAUTION -> cautionColor
                OccupancyState.OCCUPIED, OccupancyState.DROP -> blockedColor
                OccupancyState.UNKNOWN -> unknownColor
            }
            drawRect(
                color = color.copy(alpha = 0.12f),
                topLeft = Offset(left + cell.column * cellWidth, top + cell.row * cellHeight),
                size = Size(cellWidth, cellHeight)
            )
            drawRect(
                color = color.copy(alpha = 0.75f),
                topLeft = Offset(left + cell.column * cellWidth, top + cell.row * cellHeight),
                size = Size(cellWidth, cellHeight),
                style = Stroke(width = 1.dp.toPx())
            )
        }
        sceneAwareness?.freeCorridor?.let { corridor ->
            drawRect(
                color = clearColor.copy(alpha = 0.9f),
                topLeft = Offset(left + corridor.startColumn * cellWidth, top),
                size = Size(corridor.widthColumns * cellWidth, 4.dp.toPx()),
            )
        }
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
        val scaleX = size.width / frameResult.imageWidth
        val scaleY = size.height / frameResult.imageHeight

        frameResult.blocks.forEach { block ->
            val bounds = block.boundingBox
            drawRect(
                color = boxColor,
                topLeft = Offset(
                    bounds.left * scaleX,
                    bounds.top * scaleY
                ),
                size = Size(bounds.width() * scaleX, bounds.height() * scaleY),
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
            val left = bounds.left * scaleX
            val top = bounds.top * scaleY
            val width = bounds.width() * scaleX
            val height = bounds.height() * scaleY
            drawRect(
                color = objectBoxColor,
                topLeft = Offset(left, top),
                size = Size(width, height),
                style = Stroke(width = 4.dp.toPx())
            )

            val label = detection.displayLabel()
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

private fun VisionObjectDetection.displayLabel(): String =
    "$label ${(confidence * 100).toInt()}% • " +
        (distanceMeters?.let { String.format(java.util.Locale.US, "%.1f m", it) } ?: "distance unavailable")
