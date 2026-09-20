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
import com.example.guidedogtest.MotorSettings
import com.example.guidedogtest.WheelSpeeds
import com.example.guidedogtest.voice.SensorSnapshot
import com.example.guidedogtest.voice.VoicePriority
import kotlinx.coroutines.delay

/** Bench log for the three boxes: their states, the decision, and how fast it runs. */
private const val WATCHDOG_INTERVAL_MS = 50L
const val DECISION_LOG_TAG = "Avoidance"

@Composable
fun OcrScreen(
    onClose: () -> Unit,
    speakObstacleAlert: (String) -> Boolean = { false },
    speakAvoidanceAlert: (String, VoicePriority) -> Boolean = { _, _ -> false },
    robotConnected: Boolean = false,
    motorSettings: MotorSettings = MotorSettings(),
    initialAutonomousEnabled: Boolean = false,
    routeNavigationActive: Boolean = false,
    desiredRouteDirection: DesiredTravelDirection = DesiredTravelDirection.FORWARD,
    emergencyStopSignal: Long = 0L,
    /**
     * Bumped by the owner every time it issues a fresh drive command (a spoken "go forward", a page
     * button, the switch coming on). It is how the controller is told that the walker has answered an
     * obstacle stop, so the next frames are judged afresh and the robot can start again.
     */
    driveCommandSeq: Int = 0,
    /** The camera page's own "go forward": the host arms the layer and drives. See its handler. */
    onGoForward: () -> Unit = {},
    onAutonomousAvoidanceEnabled: (Boolean) -> Unit = {},
    onAvoidanceDecision: (AvoidanceDecision) -> Unit = {},
    /** The scene, reduced to what the conversation may state. Fired with every decision. */
    onSceneSnapshot: (SensorSnapshot) -> Unit = {},
) {
    val context = LocalContext.current
    DisposableEffect(context) {
        val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            onAutonomousAvoidanceEnabled(false)
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

    // Camera refused while the host expects the depth layer to be driving: nothing can see the path,
    // and the car must stand still and say so instead of leaving the host holding a stale decision
    // that looks like "nothing wrong". This is the one path into the depth layer that never reaches
    // the controller at all - the camera content is not composed without permission.
    LaunchedEffect(cameraPermissionGranted, initialAutonomousEnabled) {
        if (!cameraPermissionGranted && initialAutonomousEnabled) {
            onAvoidanceDecision(
                AvoidanceDecision(
                    AvoidanceState.STOPPED,
                    WheelSpeeds(0, 0),
                    "STOP: CAMERA PERMISSION",
                    stopReason = AvoidanceStop.SENSING_UNAVAILABLE,
                )
            )
        }
    }

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
                    motorSettings = motorSettings,
                    initialAutonomousEnabled = initialAutonomousEnabled,
                    routeNavigationActive = routeNavigationActive,
                    desiredRouteDirection = desiredRouteDirection,
                    emergencyStopSignal = emergencyStopSignal,
                    driveCommandSeq = driveCommandSeq,
                    onGoForward = onGoForward,
                    onAutonomousAvoidanceEnabled = onAutonomousAvoidanceEnabled,
                    onAvoidanceDecision = onAvoidanceDecision,
                    onSceneSnapshot = onSceneSnapshot,
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
    motorSettings: MotorSettings,
    initialAutonomousEnabled: Boolean,
    routeNavigationActive: Boolean,
    desiredRouteDirection: DesiredTravelDirection,
    emergencyStopSignal: Long,
    driveCommandSeq: Int,
    onGoForward: () -> Unit,
    onAutonomousAvoidanceEnabled: (Boolean) -> Unit,
    onAvoidanceDecision: (AvoidanceDecision) -> Unit,
    onSceneSnapshot: (SensorSnapshot) -> Unit,
    modifier: Modifier = Modifier
) {
    var frameResult by remember { mutableStateOf(OcrFrameResult()) }
    var objectDetections by remember { mutableStateOf<List<VisionObjectDetection>>(emptyList()) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var depthStatus by remember { mutableStateOf(ArDepthStatus()) }
    var autonomousEnabled by remember { mutableStateOf(initialAutonomousEnabled) }
    var avoidanceDecision by remember { mutableStateOf(AvoidanceDecision(AvoidanceState.IDLE, WheelSpeeds(0, 0), "AUTO OFF")) }
    // On by default: a guide dog that only warns when someone remembers to switch warnings on is not
    // a guide dog. The switch and the distance slider stay for the bench.
    var obstacleAlertsEnabled by remember { mutableStateOf(true) }
    var decisionCount = 0
    var decisionLogMillis = System.currentTimeMillis()
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
    // The controller reads the page's values on every decision instead of capturing them when the
    // dialog opened, so a number changed on the Configure Robot page applies to the next frame.
    val currentMotorSettings by androidx.compose.runtime.rememberUpdatedState(motorSettings)
    val avoidanceController = remember { ObstacleAvoidanceController(tuning = { currentMotorSettings }) }
    val avoidanceSpeechManager = remember { AvoidanceSpeechManager(speak = speakAvoidanceAlert) }

    // The host is the authority on whether the layer is armed: a spoken "go forward" sets it while
    // this page is already open, and the page has to follow, or the layer would sit switched off
    // while the app believed it was watching the path.
    LaunchedEffect(initialAutonomousEnabled) {
        autonomousEnabled = initialAutonomousEnabled
    }

    // A fresh command - a spoken "go forward", a page button, the switch coming on - is the walker
    // answering the stop the robot latched: the next frames are judged afresh.
    LaunchedEffect(driveCommandSeq) {
        if (driveCommandSeq > 0) avoidanceController.releaseObstacleStop()
    }

    LaunchedEffect(emergencyStopSignal) {
        if (emergencyStopSignal > 0L) {
            autonomousEnabled = false
            avoidanceDecision = AvoidanceDecision(
                AvoidanceState.STOPPED,
                WheelSpeeds(0, 0),
                "EMERGENCY STOP"
            )
            onAutonomousAvoidanceEnabled(false)
        }
    }

    // What the depth layer should steer towards. A route owns the direction while it is running; a
    // spoken turn or "go forward" carries its own intent in the same field, and stand-alone autonomy
    // (the AUTO switch, no route, no command) walks forward. A STOP here means "nobody has asked for
    // a direction", which is why it only counts as a hold while a route is following.
    val effectiveDirection = when {
        routeNavigationActive -> desiredRouteDirection
        desiredRouteDirection == DesiredTravelDirection.STOP -> DesiredTravelDirection.FORWARD
        else -> desiredRouteDirection
    }

    fun applyAvoidance(scene: SceneAwarenessResult? = rawSceneAwareness) {
        val updateStartedNanos = System.nanoTime()
        avoidanceDecision = avoidanceController.update(
            scene = scene,
            enabled = autonomousEnabled,
            cameraAvailable = depthStatus.sessionActive,
            depthAvailable = depthStatus.depthActive,
            robotConnected = robotConnected,
            desiredDirection = effectiveDirection,
        )
        // Bench log: the decision cost and the rate the boxes are being read at, rate-limited to one
        // line a second so measuring it is not most of the cost.
        decisionCount++
        val nowMillis = System.currentTimeMillis()
        if (nowMillis - decisionLogMillis >= 1_000L) {
            val seconds = (nowMillis - decisionLogMillis) / 1000.0
            android.util.Log.d(
                DECISION_LOG_TAG,
                "boxes L=%s(%s) C=%s(%s) R=%s(%s) -> %s  %.1f/s  decide %.2f ms".format(
                    scene?.leftState, scene?.leftDistanceMeters?.let { "%.1f".format(it) } ?: "--",
                    scene?.centerState, scene?.centerDistanceMeters?.let { "%.1f".format(it) } ?: "--",
                    scene?.rightState, scene?.rightDistanceMeters?.let { "%.1f".format(it) } ?: "--",
                    avoidanceDecision.action, decisionCount / seconds,
                    (System.nanoTime() - updateStartedNanos) / 1_000_000.0,
                ),
            )
            decisionCount = 0
            decisionLogMillis = nowMillis
        }
        onAvoidanceDecision(avoidanceDecision)
        // What the conversation is told, from the same scene the safety controller just decided on:
        // the assistant and the motors cannot disagree about what is in front of the robot.
        onSceneSnapshot(
            (scene ?: SceneAwarenessResult()).toSensorSnapshot(
                isMoving = autonomousEnabled && avoidanceDecision.state.isDriving()
            )
        )
        if (autonomousEnabled) avoidanceSpeechManager.consider(avoidanceDecision)
    }

    // The dialog's copy of the autonomy flag follows the screen that owns it, in both directions.
    // A spoken command or a manual button can take the motors back outside this dialog, and this
    // mirror is what stops the panel still claiming AUTO: ON with a motor readout of 0/0 while the
    // manual command is what the car is actually driving.
    LaunchedEffect(initialAutonomousEnabled, routeNavigationActive) {
        val wanted = initialAutonomousEnabled || routeNavigationActive
        when {
            wanted && !autonomousEnabled -> {
                autonomousEnabled = true
                onAutonomousAvoidanceEnabled(true)
                applyAvoidance()
            }
            !wanted && autonomousEnabled -> {
                autonomousEnabled = false
                avoidanceSpeechManager.reset()
                avoidanceDecision = AvoidanceDecision(
                    AvoidanceState.IDLE,
                    WheelSpeeds(0, 0),
                    "AUTO OFF"
                )
                onAutonomousAvoidanceEnabled(false)
            }
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
                onAvoidanceDecision(it)
                avoidanceSpeechManager.consider(it)
            }
            delay(WATCHDOG_INTERVAL_MS)
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
                    val visualization = visualizationStabilizer.accept(it)
                    depthVisualization = visualization
                    android.util.Log.d(
                        DECISION_LOG_TAG,
                        "scene -> ui: plane=%s empty=%s timestamp=%s states L=%s C=%s R=%s, display=%s".format(
                            it.supportPlaneDetected, it.depthEmpty, it.depthTimestampNanos != null,
                            it.leftState, it.centerState, it.rightState, visualization.state,
                        ),
                    )
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

        // First thing in the panel, and the only two controls a demo needs: go, and stop. The
        // diagnostics below are for the bench, and in landscape they are below the fold - a fallback
        // button you have to scroll to find in front of an audience is not a fallback.
        Text("AUTO: ${if (autonomousEnabled) "ON" else "OFF"} • Action: ${avoidanceDecision.action}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The demo's fallback: if the microphone misses, this arms the layer and drives in one
            // press, and it is also the answer to a latched obstacle stop.
            Button(
                onClick = {
                    autonomousEnabled = true
                    onGoForward()
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("GO FORWARD")
            }
            Button(
                onClick = {
                    autonomousEnabled = false
                    avoidanceDecision = AvoidanceDecision(
                        AvoidanceState.STOPPED,
                        WheelSpeeds(0, 0),
                        "MANUAL STOP"
                    )
                    onAutonomousAvoidanceEnabled(false)
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("STOP")
            }
        }


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
                        avoidanceDecision = AvoidanceDecision(AvoidanceState.IDLE, WheelSpeeds(0, 0), "AUTO OFF")
                        onAutonomousAvoidanceEnabled(false)
                    } else {
                        applyAvoidance()
                    }
                }
            )
        }
        Text("NAVIGATION: ${if (routeNavigationActive) "ACTIVE" else "INACTIVE"}", style = MaterialTheme.typography.labelSmall)
        Text("Target direction: $effectiveDirection", style = MaterialTheme.typography.labelSmall)
        Text("Local action: ${avoidanceDecision.action}", style = MaterialTheme.typography.labelSmall)
        Text("Off middle: ${avoidanceDecision.offMiddle?.let { String.format(java.util.Locale.US, "%+.2f", it) } ?: "--"}", style = MaterialTheme.typography.labelSmall)
        Text("Motor output: L: ${avoidanceDecision.wheelSpeeds.left}  R: ${avoidanceDecision.wheelSpeeds.right}", style = MaterialTheme.typography.labelSmall)
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
            appendLine(
                when {
                    value.supportPlaneDetected ->
                        "Support: TRACKED   Drop: ${if (value.dropDetected) "DETECTED" else "none"}"
                    // Two faults, two answers: an empty depth frame is ARCore not measuring at all,
                    // a full one with no floor in it is where the camera is pointed.
                    // Either way the robot is driving on the command alone, so say that rather than
                    // pretending it is watching the path.
                    value.depthEmpty ->
                        "Support: NO DEPTH from the camera (ARCore is returning empty frames) - " +
                            "driving on the command alone, obstacle detection OFF."
                    else ->
                        "Support: NO FLOOR IN THE DEPTH IMAGE (ARCore's depth is the middle of the " +
                            "camera picture, so a floor along the bottom edge is not measured) - " +
                            "driving on the command alone, obstacle detection OFF."
                }
            )
            SceneAwarenessAnalyzer.ZONES.forEach { zone ->
                val distance = value.distanceOf(zone)
                appendLine(
                    "${zone.name.take(1)}: ${value.stateOf(zone)}" +
                        (distance?.let { String.format(java.util.Locale.US, " %.2f m", it) } ?: "") +
                        (if (value.hasDrop(zone)) " DROP" else "")
                )
            }
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
        val top = size.height * SceneAwarenessAnalyzer.ZONE_TOP
        val height = size.height * (SceneAwarenessAnalyzer.ZONE_BOTTOM - SceneAwarenessAnalyzer.ZONE_TOP)
        val labelPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            textSize = 34f
            setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
        }
        SceneAwarenessAnalyzer.ZONES.forEach { zone ->
            val left = size.width * SceneAwarenessAnalyzer.zoneLeft(zone)
            val width = size.width *
                (SceneAwarenessAnalyzer.zoneRight(zone) - SceneAwarenessAnalyzer.zoneLeft(zone))
            val state = sceneAwareness?.stateOf(zone) ?: SceneZoneState.UNKNOWN
            val dropped = sceneAwareness?.hasDrop(zone) == true
            val color = when {
                dropped -> blockedColor
                state == SceneZoneState.CLEAR -> clearColor
                state == SceneZoneState.CAUTION -> cautionColor
                state == SceneZoneState.BLOCKED -> blockedColor
                else -> unknownColor
            }
            // Above the floor line is where an obstacle has to be to matter, so that is the part
            // drawn as an active box. Below it is floor - support, deliberately not detection - and it
            // is drawn as a thin, dim strip rather than three big boxes that are mostly pavement.
            val floorY = sceneAwareness?.floorLineFraction?.let { size.height * it } ?: (top + height)
            val activeHeight = (floorY - top).coerceAtLeast(1f)
            drawRect(
                color = color.copy(alpha = 0.12f),
                topLeft = Offset(left, top),
                size = Size(width, activeHeight),
            )
            drawRect(
                color = color.copy(alpha = 0.85f),
                topLeft = Offset(left, top),
                size = Size(width, activeHeight),
                style = Stroke(width = 3.dp.toPx()),
            )
            if (floorY < top + height) {
                drawRect(
                    color = color.copy(alpha = 0.30f),
                    topLeft = Offset(left, floorY),
                    size = Size(width, top + height - floorY),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            val distance = sceneAwareness?.distanceOf(zone)
            val label = buildString {
                append(zone.name.first())
                append(' ')
                append(distance?.let { String.format(java.util.Locale.US, "%.1f m", it) } ?: "--")
                if (dropped) append(" DROP")
            }
            drawContext.canvas.nativeCanvas.drawText(
                label,
                left + 12.dp.toPx(),
                top + 40.dp.toPx(),
                labelPaint,
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
