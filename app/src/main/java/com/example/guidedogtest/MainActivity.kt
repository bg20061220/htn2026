package com.example.guidedogtest

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.guidedogtest.ui.theme.GuideDogTestTheme
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.ar.core.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            // The window theme is light, so the colour scheme must be too: with the phone in dark
            // mode the dynamic dark scheme made everything scheme-coloured come out light on white -
            // the destination field's text was white on white and looked empty.
            GuideDogTestTheme(darkTheme = false) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    NavigationScreen()
                }
            }
        }
    }
}

@Composable
fun NavigationScreen() {

    val context = LocalContext.current

    val fusedLocationClient =
        remember { LocationServices.getFusedLocationProviderClient(context) }

    // Saved, so a typed destination survives the screen being reclaimed - and rotation, which the
    // manifest now keeps the activity alive through.
    var destination by rememberSaveable { mutableStateOf("") }
    var command by remember { mutableStateOf("STOP") }

    // Live position, so the follower never works from a stale fix.
    var lastLocation by remember { mutableStateOf<Location?>(null) }

    // Which screen is up. The map is a view of the same session, not a second session.
    var screen by remember { mutableStateOf(Screen.Controls) }

    // The phone's compass: the follower aligns to this, because GPS course says nothing at rest.
    // The landscape mount is applied inside it, by ROBOT_HEADING_OFFSET_DEGREES.
    val headingSource = remember { HeadingSource(context) }

    // The manual drive values, edited on the Configure Robot page and kept across launches.
    var motorSettings by remember { mutableStateOf(MotorSettingsStore.load(context)) }

    // Route state.
    var routeSteps by remember { mutableStateOf<List<RouteStep>>(emptyList()) }
    var routeStatus by remember { mutableStateOf("no route loaded") }
    var follower by remember { mutableStateOf<RouteFollower?>(null) }
    var following by remember { mutableStateOf(false) }

    // When this is set it wins over the manual command: that is what makes the robot autonomous.
    var autonomousFrame by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    val scrollState = rememberScrollState()

    /** True while the destination field has the keyboard, so a resize can bring it back into view. */
    var destinationFocused by remember { mutableStateOf(false) }

    /** Brings the destination field and the buttons under it above the keyboard. */
    fun revealBottom() {
        scope.launch { scrollState.scrollTo(scrollState.maxValue) }
    }

    fun stopFollowing() {
        following = false
        autonomousFrame = null
    }

    var arCoreStatus by remember { mutableStateOf("Not checked") }
    var depthStatus by remember { mutableStateOf("Not checked") }

    // LOCATION PERMISSION
    val locationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {

                if (
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                ) {

                    fusedLocationClient.getCurrentLocation(
                        Priority.PRIORITY_HIGH_ACCURACY,
                        CancellationTokenSource().token
                    ).addOnSuccessListener { location ->

                        if (location != null) {
                            lastLocation = location
                        }
                    }
                }
            }
        }

    // CAMERA PERMISSION
    val cameraPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {
                arCoreStatus = "Camera permission granted"
                depthStatus = "Tap CHECK ARCORE again"
            } else {
                arCoreStatus = "Camera permission denied"
                depthStatus = "Unavailable"
            }
        }

    val link = remember { RobotLink(context) }

    val bluetoothPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { granted ->

            if (granted.values.all { it }) {
                link.connect()
            }
        }

    /**
     * Opens the link: USB when the board is attached to the phone (Android handles that permission
     * itself), otherwise BLE, asking for the permissions that needs.
     */
    fun connectRobot() {
        if (link.usbAttached()) {
            link.connect()
            return
        }

        val missing = RobotLink.requiredPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            link.connect()
        } else {
            bluetoothPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    // Keeps the ESP32 fed: the frame currently in force at 20 Hz, plus the heartbeat the
    // firmware needs to keep the motors turning. If this loop stops - app killed, link
    // dropped, screen closed - the firmware stops the car on its own after 500 ms.
    //
    // The frame is whatever is in force: the route follower's when it is driving, otherwise the
    // manual command. One writer, one path to the motors.
    LaunchedEffect(link.connected) {

        var tick = 0

        while (link.connected) {

            link.send(autonomousFrame ?: motorSettings.speedsFor(command).frame())

            if (tick % 4 == 0) {
                link.send("h500\n")
            }

            tick++
            delay(50)
        }
    }

    // A live fix while the screen is up (1 Hz, faster if the receiver has one ready).
    val locationCallback = remember {
        object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let {
                    lastLocation = it
                    // Magnetic north is not true north; the compass needs a fix to correct itself.
                    headingSource.setLocation(it.latitude, it.longitude)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val request =
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                    .setMinUpdateIntervalMillis(500L)
                    .build()
            fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback,
                Looper.getMainLooper()
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose { fusedLocationClient.removeLocationUpdates(locationCallback) }
    }

    // The compass runs while the screen is up: the follower needs it every tick it is driving.
    DisposableEffect(Unit) {
        headingSource.start()
        onDispose { headingSource.stop() }
    }

    // The autonomous loop: one decision per tick, handed to the transmit loop above.
    LaunchedEffect(following, link.connected) {

        val active = follower
        if (!following || !link.connected || active == null) {
            autonomousFrame = null
            return@LaunchedEffect
        }

        while (following && link.connected) {

            val location = lastLocation

            if (location == null) {
                autonomousFrame = Drive.STOP_FRAME
                routeStatus = "waiting for a GPS fix"
            } else {
                val fix =
                    Fix(
                        lat = location.latitude,
                        lng = location.longitude,
                        accuracyMeters = location.accuracy,
                        // The compass knows which way the car points even standing still, which is
                        // the whole point of turning in place; the GPS course is the fallback.
                        headingDegrees = headingSource.headingDegrees ?: gpsCourse(location),
                    )

                when (val decision = active.update(fix, FOLLOW_TICK_MS / 1000.0)) {

                    is Command.Pivot -> {
                        // Closed loop, so the frame is re-sent every tick until the error closes.
                        autonomousFrame = Drive.frame(decision.left, decision.right)
                        routeStatus =
                            "turning ${if (decision.degrees < 0) "left" else "right"} " +
                                "${abs(decision.degrees).toInt()}°"
                    }

                    is Command.Drive -> {
                        autonomousFrame = Drive.frame(decision.left, decision.right)
                        routeStatus =
                            "${active.progressLabel()}: ${active.currentStep?.instruction ?: ""}"
                    }

                    is Command.Hold -> {
                        autonomousFrame = Drive.STOP_FRAME
                        routeStatus = decision.reason
                    }

                    Command.Arrived -> {
                        autonomousFrame = Drive.STOP_FRAME
                        routeStatus = "arrived"
                        following = false
                    }
                }
            }

            delay(FOLLOW_TICK_MS)
        }

        autonomousFrame = Drive.STOP_FRAME
    }

    // Leaving the screen must never leave the car rolling.
    DisposableEffect(Unit) {
        onDispose { link.stopAndDisconnect() }
    }

    if (screen == Screen.ConfigureRobot) {
        ConfigureRobotScreen(
            settings = motorSettings,
            onSettingsChange = {
                motorSettings = it
                MotorSettingsStore.save(context, it)
            },
            command = command,
            onCommand = {
                stopFollowing()
                command = it
            },
            rawHeading = headingSource.rawHeadingDegrees,
            robotHeading = headingSource.headingDegrees,
            offsetDegrees = headingSource.offsetDegrees,
            connected = link.connected,
            linkStatus = link.status,
            onConnect = { connectRobot() },
            onBack = { screen = Screen.Controls },
        )
        return
    }

    if (screen == Screen.LiveMap) {
        LiveLocationScreen(
            location = lastLocation,
            headingDegrees = headingSource.headingDegrees,
            onRequestPermission = {
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            },
            onBack = { screen = Screen.Controls },
        )
        return
    }

    // Scrollable: the route status and the manual buttons below it overflow an S21 screen once a
    // route is loaded, and a STOP that cannot be reached is worse than useless. The size callback is
    // what lifts the destination field above the keyboard: the window shrinks when the keyboard
    // opens, and that resize is the reliable moment to scroll, not the focus event.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .onSizeChanged { if (destinationFocused) revealBottom() }
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Text(
            text = "MyPetGoose",
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text("ARCore: $arCoreStatus")
        Text("Depth API: $depthStatus")

        Spacer(modifier = Modifier.height(12.dp))

        Text("ESP32: ${link.status}")
        Text("Robot: ${link.telemetry}")

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { connectRobot() }
        ) {
            Text("CONNECT ROBOT")
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {

                // First check camera permission
                if (
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA
                    ) != PackageManager.PERMISSION_GRANTED
                ) {

                    cameraPermissionLauncher.launch(
                        Manifest.permission.CAMERA
                    )

                    return@Button
                }

                // Then check ARCore
                val availability =
                    ArCoreApk.getInstance().checkAvailability(context)

                if (availability.isSupported) {

                    arCoreStatus = "SUPPORTED"

                    try {

                        val session = Session(context)

                        val depthSupported =
                            session.isDepthModeSupported(
                                Config.DepthMode.AUTOMATIC
                            )

                        depthStatus =
                            if (depthSupported) {
                                "SUPPORTED"
                            } else {
                                "NOT SUPPORTED"
                            }

                        session.close()

                    } catch (e: Exception) {

                        depthStatus =
                            "ERROR: ${e.message}"
                    }

                } else {

                    arCoreStatus = "NOT SUPPORTED"
                    depthStatus = "NOT AVAILABLE"
                }
            }
        ) {
            Text("CHECK ARCORE")
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Current Location",
            style = MaterialTheme.typography.titleMedium
        )

        Text("Latitude: ${lastLocation?.latitude ?: "unknown"}")
        Text("Longitude: ${lastLocation?.longitude ?: "unknown"}")
        Text("Heading: ${headingText(headingSource.headingDegrees)}")

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { screen = Screen.LiveMap },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("LIVE MAP")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {

                if (
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                ) {

                    fusedLocationClient.getCurrentLocation(
                        Priority.PRIORITY_HIGH_ACCURACY,
                        CancellationTokenSource().token
                    ).addOnSuccessListener { location ->

                        if (location != null) {
                            lastLocation = location
                        }
                    }

                } else {

                    locationPermissionLauncher.launch(
                        Manifest.permission.ACCESS_FINE_LOCATION
                    )
                }
            }
        ) {
            Text("GET CURRENT LOCATION")
        }

        Spacer(modifier = Modifier.height(20.dp))

        OutlinedTextField(
            value = destination,
            onValueChange = { destination = it },
            label = { Text("Destination") },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focus ->
                    destinationFocused = focus.isFocused
                    if (focus.isFocused) revealBottom()
                },
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {

                val key = BuildConfig.MAPS_API_KEY
                if (key.isBlank()) {
                    routeStatus = "add MAPS_API_KEY to local.properties, then rebuild"
                    return@Button
                }

                val here = lastLocation
                if (here == null) {
                    routeStatus = "waiting for a GPS fix"
                    return@Button
                }

                if (destination.isBlank()) {
                    routeStatus = "type a destination first"
                    return@Button
                }

                routeStatus = "requesting a walking route to \"$destination\"..."

                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            RoutesApi.fetchRoute(
                                apiKey = key,
                                origin = GeoPoint(here.latitude, here.longitude),
                                destination = destination,
                            )
                        }
                    }

                    result
                        .onSuccess { steps ->
                            routeSteps = steps
                            // The follower reads the tuned wheel values every tick, so what is set
                            // on the Configure Robot page is what a route drives.
                            follower = RouteFollower(steps, tuning = { motorSettings })
                            routeStatus =
                                "${steps.size} steps, ${steps.sumOf { it.distanceMeters }} m"
                        }
                        .onFailure { error ->
                            routeSteps = emptyList()
                            follower = null
                            routeStatus = error.message ?: "route request failed"
                        }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("GET ROUTE")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text("Route: $routeStatus")

        follower?.currentStep?.let { step ->
            Text("Now: ${step.instruction} (${step.distanceMeters} m)")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {

                if (following) {
                    stopFollowing()
                    command = "STOP"
                    routeStatus = "stopped"
                    return@Button
                }

                if (follower == null) {
                    routeStatus = "get a route first"
                    return@Button
                }

                if (!link.connected) {
                    routeStatus = "connect the robot first"
                    return@Button
                }

                // Hand the motors over to the follower; the manual command goes neutral.
                command = "STOP"
                autonomousFrame = Drive.STOP_FRAME
                following = true
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (following) "STOP FOLLOWING" else "START FOLLOWING")
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text("Robot Command: $command")

        Spacer(modifier = Modifier.height(16.dp))

        // The manual drive buttons and their wheel values live on their own page: they are the
        // floor-testing rig, not part of the demo flow.
        Button(
            onClick = { screen = Screen.ConfigureRobot },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("CONFIGURE ROBOT")
        }
    }
}

/** One decision per tick for the route follower. */
private const val FOLLOW_TICK_MS = 100L

/** The screens: the controls, the live map, and the manual drive tuning page. */
private enum class Screen { Controls, LiveMap, ConfigureRobot }

/** GPS course over ground, only trustworthy while actually moving - standing still it is noise. */
private fun gpsCourse(location: Location): Double? =
    if (location.hasBearing() && location.speed > 0.3f) location.bearing.toDouble() else null