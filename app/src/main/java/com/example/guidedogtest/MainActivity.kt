package com.example.guidedogtest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.guidedogtest.ocr.OcrScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.guidedogtest.ui.theme.GuideDogTestTheme
import com.example.guidedogtest.voice.ConversationManager
import com.example.guidedogtest.voice.RobotCommand
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.model.RectangularBounds
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
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
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Places.isInitialized()) {
            // One key source for the whole app: BuildConfig, out of the gitignored local.properties.
            val mapsApiKey = BuildConfig.MAPS_API_KEY
            if (mapsApiKey.isNotBlank()) {
                Places.initializeWithNewPlacesApiEnabled(applicationContext, mapsApiKey)
            }
        }

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
    val coroutineScope = rememberCoroutineScope()

    val fusedLocationClient =
        remember { LocationServices.getFusedLocationProviderClient(context) }
    val placesClient = remember { Places.createClient(context) }

    // Saved, so a typed destination survives the screen being reclaimed - and rotation, which the
    // manifest now keeps the activity alive through.
    var destination by rememberSaveable { mutableStateOf("") }
    var destinationName by remember { mutableStateOf<String?>(null) }
    var destinationLocation by remember { mutableStateOf<LatLng?>(null) }
    var placeSuggestions by remember { mutableStateOf<List<AutocompletePrediction>>(emptyList()) }
    var autocompleteToken by remember { mutableStateOf<AutocompleteSessionToken?>(null) }
    var destinationSearchError by remember { mutableStateOf<String?>(null) }
    var routePoints by remember { mutableStateOf<List<LatLng>>(emptyList()) }
    var routeDistance by remember { mutableStateOf<String?>(null) }
    var routeDuration by remember { mutableStateOf<String?>(null) }
    var routeInstructions by remember { mutableStateOf<List<String>>(emptyList()) }
    var routeMessage by remember { mutableStateOf<String?>(null) }
    var routeLoading by remember { mutableStateOf(false) }
    var command by remember { mutableStateOf("STOP") }

    // Something the UI needs said but cannot speak where it decides it: the voice assistant's
    // onCommand lambda is constructed *by* the call that creates the manager, so it cannot call it.
    // Corrections are queued here and spoken by the effect that drains it.
    var pendingAnnouncement by remember { mutableStateOf<String?>(null) }
    var showOcrMode by remember { mutableStateOf(false) }

    // Live position, so the follower never works from a stale fix.
    var lastLocation by remember { mutableStateOf<Location?>(null) }

    // The same fix as text, for the map and voice code that reads it that way. Kept in step with
    // every live update, so it is never the stale one-shot value it used to be.
    var latitude by remember { mutableStateOf("Unknown") }
    var longitude by remember { mutableStateOf("Unknown") }

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

    /**
     * Everything that stops the robot goes through here: the STOP button, the assistant's stop
     * command, and the stop word the always-listening loop picks up. One path, so a stop cannot work
     * from one place and be forgotten in another.
     */
    fun halt(sayIt: Boolean = true) {
        stopFollowing()
        command = "STOP"
        autonomousFrame = Drive.STOP_FRAME
        routeStatus = "stopped"
        if (sayIt) pendingAnnouncement = "Stopping."
    }

    /**
     * Adopts one route for both consumers: the map and voice read the plan, the follower drives its
     * steps. Typed destinations, picked places and spoken ones all land here, so there is a single
     * place where a route becomes the robot's behaviour.
     */
    fun adoptRoute(plan: RoutePlan, label: String) {
        routePoints = plan.points
        routeDistance = formatDistance(plan.distanceMeters)
        routeDuration = formatDuration(plan.durationSeconds)
        routeInstructions = plan.instructions
        routeMessage = null
        routeSteps = plan.steps
        follower = RouteFollower(plan.steps, tuning = { motorSettings })
        routeStatus = "${plan.steps.size} steps, ${formatDistance(plan.distanceMeters)} to $label"
    }

    var arCoreStatus by remember { mutableStateOf("Not checked") }
    var depthStatus by remember { mutableStateOf("Not checked") }

    var micPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    // MIC PERMISSION
    val micPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            micPermissionGranted = granted
        }

    // The robot link is created before the voice assistant: its commands reach the motors through the
    // same path as the manual buttons, so it has to exist first.
    val link = remember { RobotLink(context) }

    val conversationManager: ConversationManager = viewModel(
        factory = viewModelFactory {
            initializer {
                ConversationManager(
                    context = context.applicationContext,
                    groqApiKey = BuildConfig.GROQ_API_KEY,
                    elevenLabsApiKey = BuildConfig.ELEVENLABS_API_KEY,
                    locationProvider = {
                        val lat = latitude.toDoubleOrNull()
                        val lng = longitude.toDoubleOrNull()
                        if (lat != null && lng != null) LatLng(lat, lng) else null
                    },
                    onCommand = { robotCommand ->
                        // Voice commands go through exactly the same path as the manual buttons:
                        // they take the motors back from the follower and set the command the
                        // transmit loop sends. Nothing about voice reaches the car another way.
                        when (robotCommand) {
                            RobotCommand.Stop -> halt()

                            RobotCommand.Go -> {
                                // "Go" means start following the route that is already loaded - the
                                // voice has just talked the walker through it and they have said yes.
                                if (follower != null && link.connected) {
                                    command = "STOP"
                                    autonomousFrame = Drive.STOP_FRAME
                                    following = true
                                } else {
                                    // The assistant has already said "Okay, going." by now, so an
                                    // audible correction matters more than the status line: a guide
                                    // dog that says it is going and then stands still is worse than
                                    // one that never claimed to move.
                                    routeStatus = if (follower == null) {
                                        "say a destination first"
                                    } else {
                                        "connect the robot first"
                                    }
                                    pendingAnnouncement = if (follower == null) {
                                        "I need a destination first. Where would you like to go?"
                                    } else {
                                        "I'm not connected to the robot, so I can't walk there."
                                    }
                                }
                            }

                            is RobotCommand.Turn -> {
                                stopFollowing()
                                command = if (robotCommand.direction.lowercase().contains("left")) {
                                    "LEFT"
                                } else {
                                    "RIGHT"
                                }
                            }

                            // Straight ahead, on the tuned FORWARD pair. No destination needed, so
                            // this is the one that works on the bench with no route loaded.
                            RobotCommand.Forward -> {
                                stopFollowing()
                                command = "FORWARD"
                            }

                            // The route for a spoken destination arrives on voiceRoute; the
                            // effect below turns it into the follower's route.
                            is RobotCommand.Navigate -> Unit

                            RobotCommand.None -> Unit
                        }
                    },
                    // Heard by the always-listening loop, with no wake word and no round trip: the
                    // person saying "stop" means now, and this is the one path that fires while the
                    // robot is walking and nothing else is going on.
                    onStopWord = { halt() }
                )
            }
        }
    )

    val conversationState by conversationManager.state.collectAsState()
    val lastSpoken by conversationManager.lastSpoken.collectAsState()
    val voiceRoute by conversationManager.voiceRoute.collectAsState()

    // A destination spoken to the robot becomes the route the robot drives, so "go" afterwards
    // leads along exactly the walk the voice just described. One effect: the map state and the
    // follower come from the same plan.
    LaunchedEffect(voiceRoute) {
        voiceRoute?.let { spoken ->
            destination = spoken.destinationName
            destinationName = spoken.destinationName
            destinationLocation = spoken.destinationLocation
            placeSuggestions = emptyList()
            adoptRoute(spoken.plan, spoken.destinationName)
        }
    }

    LaunchedEffect(pendingAnnouncement) {
        pendingAnnouncement?.let {
            conversationManager.announce(it)
            pendingAnnouncement = null
        }
    }

    LaunchedEffect(micPermissionGranted) {
        if (micPermissionGranted) {
            conversationManager.start()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            conversationManager.stop()
        }
    }

    val currentLocation = remember(latitude, longitude) {
        val lat = latitude.toDoubleOrNull()
        val lng = longitude.toDoubleOrNull()
        if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
            LatLng(lat, lng)
        } else {
            null
        }
    }
    val fallbackLocation = remember { LatLng(0.0, 0.0) }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(fallbackLocation, 1f)
    }

    // While the robot is driving itself, the map keeps it in view. Otherwise the camera is left to
    // the route-bounds effect below, so a loaded route is still shown end to end.
    LaunchedEffect(following, currentLocation) {
        if (following) {
            currentLocation?.let {
                cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(it, 17f))
            }
        }
    }

    LaunchedEffect(currentLocation, destinationLocation, routePoints) {
        val selectedDestination = destinationLocation
        when {
            routePoints.isNotEmpty() -> {
                val boundsBuilder = LatLngBounds.builder()
                routePoints.forEach(boundsBuilder::include)
                currentLocation?.let(boundsBuilder::include)
                selectedDestination?.let(boundsBuilder::include)
                try {
                    cameraPositionState.animate(
                        CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 100)
                    )
                } catch (_: IllegalStateException) {
                    cameraPositionState.animate(
                        CameraUpdateFactory.newLatLngZoom(routePoints.first(), 14f)
                    )
                }
            }
            currentLocation != null && selectedDestination != null -> {
                val bounds = LatLngBounds.builder()
                    .include(currentLocation)
                    .include(selectedDestination)
                    .build()
                try {
                    cameraPositionState.animate(
                        CameraUpdateFactory.newLatLngBounds(bounds, 100)
                    )
                } catch (_: IllegalStateException) {
                    cameraPositionState.animate(
                        CameraUpdateFactory.newLatLngZoom(selectedDestination, 15f)
                    )
                }
            }
            selectedDestination != null -> cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(selectedDestination, 15f)
            )
            currentLocation != null -> cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(currentLocation, 17f)
            )
        }
    }

    LaunchedEffect(destination, currentLocation, destinationName) {
        val query = destination.trim()
        if (query.length < 2 || query == destinationName) {
            placeSuggestions = emptyList()
            destinationSearchError = null
            return@LaunchedEffect
        }

        delay(300)
        val token = autocompleteToken ?: AutocompleteSessionToken.newInstance().also {
            autocompleteToken = it
        }
        val requestBuilder = FindAutocompletePredictionsRequest.builder()
            .setQuery(query)
            .setSessionToken(token)

        currentLocation?.let { location ->
            val latitudeDelta = 0.25
            val longitudeDelta = 0.25
            requestBuilder.setLocationBias(
                RectangularBounds.newInstance(
                    LatLng(max(-90.0, location.latitude - latitudeDelta), max(-180.0, location.longitude - longitudeDelta)),
                    LatLng(min(90.0, location.latitude + latitudeDelta), min(180.0, location.longitude + longitudeDelta))
                )
            )
            requestBuilder.setOrigin(location)
        }

        placesClient.findAutocompletePredictions(requestBuilder.build())
            .addOnSuccessListener { response ->
                if (destination.trim() == query) {
                    placeSuggestions = response.autocompletePredictions
                    destinationSearchError = null
                }
            }
            .addOnFailureListener {
                if (destination.trim() == query) {
                    placeSuggestions = emptyList()
                    destinationSearchError = "Unable to load place suggestions"
                }
            }
    }

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
                    latitude = it.latitude.toString()
                    longitude = it.longitude.toString()
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

        // The step a cue has been spoken for, so a turn is announced once when it becomes current
        // rather than every tick the loop runs.
        var announcedStep = -1

        while (following && link.connected) {

            if (active.index != announcedStep) {
                announcedStep = active.index
                active.currentStep?.let { step ->
                    val cue = if (step.distanceMeters > 0) {
                        "${step.instruction}, ${formatDistance(step.distanceMeters)}"
                    } else {
                        step.instruction
                    }
                    conversationManager.announce(cue)
                }
            }

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

        val routePolylineColor = MaterialTheme.colorScheme.primary

        Text(
            text = "MyPetGoose",
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Voice Assistant",
            style = MaterialTheme.typography.titleMedium
        )

        if (micPermissionGranted) {
            Text("State: $conversationState")
            Text("Last spoken: $lastSpoken")
        } else {
            Text("Mic permission not granted")
            Button(
                onClick = {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            ) {
                Text("ENABLE VOICE ASSISTANT")
            }
        }

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

        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = { showOcrMode = true }) {
            Text("OPEN OCR CAMERA")
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

        Spacer(modifier = Modifier.height(16.dp))

        // One map for the whole app: the route line and the chosen places, plus the robot itself -
        // an arrow rotated to its heading and a ring showing how good the fix is.
        var robotArrow by remember { mutableStateOf<BitmapDescriptor?>(null) }

        GoogleMap(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp),
            cameraPositionState = cameraPositionState
        ) {
            // The SDK is only ready once the map exists, and BitmapDescriptorFactory throws before
            // that - which is why the arrow is built here rather than during composition.
            MapEffect(Unit) { robotArrow = headingArrowIcon(context) }

            currentLocation?.let { location ->
                Marker(
                    state = rememberUpdatedMarkerState(position = location),
                    title = "Current Location",
                    icon = robotArrow ?: BitmapDescriptorFactory.defaultMarker(),
                    rotation = (headingSource.headingDegrees ?: 0.0).toFloat(),
                    flat = true,
                    anchor = Offset(0.5f, 0.5f),
                )
                lastLocation?.let { fix ->
                    Circle(
                        center = location,
                        radius = fix.accuracy.toDouble(),
                        fillColor = Color(0x221B5E20),
                        strokeColor = Color(0x661B5E20),
                        strokeWidth = 2f,
                    )
                }
            }
            destinationLocation?.let { location ->
                Marker(
                    state = rememberUpdatedMarkerState(position = location),
                    title = destinationName ?: "Destination"
                )
            }
            if (routePoints.isNotEmpty()) {
                Polyline(
                    points = routePoints,
                    color = routePolylineColor,
                    width = 12f
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        OutlinedTextField(
            value = destination,
            onValueChange = { value ->
                destination = value
                if (value != destinationName) {
                    destinationName = null
                    destinationLocation = null
                    routePoints = emptyList()
                    routeDistance = null
                    routeDuration = null
                    routeInstructions = emptyList()
                    routeMessage = null
                }
            },
            label = { Text("Destination") },
            placeholder = { Text("Search for a destination") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                disabledContainerColor = MaterialTheme.colorScheme.surface,
                focusedLabelColor = MaterialTheme.colorScheme.primary,
                unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledPlaceholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.38f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focus ->
                    destinationFocused = focus.isFocused
                    if (focus.isFocused) revealBottom()
                },
        )

        placeSuggestions.forEach { prediction ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val token = autocompleteToken
                        val request = FetchPlaceRequest.builder(
                            prediction.placeId,
                            listOf(Place.Field.DISPLAY_NAME, Place.Field.LOCATION)
                        ).apply {
                            token?.let { setSessionToken(it) }
                        }.build()

                        placesClient.fetchPlace(request)
                            .addOnSuccessListener { response ->
                                val place = response.place
                                val location = place.location
                                if (location != null) {
                                    val name = place.displayName
                                        ?: prediction.getPrimaryText(null).toString()
                                    destination = name
                                    destinationName = name
                                    destinationLocation = location
                                    routePoints = emptyList()
                                    routeDistance = null
                                    routeDuration = null
                                    routeInstructions = emptyList()
                                    routeMessage = null
                                    placeSuggestions = emptyList()
                                    destinationSearchError = null
                                    autocompleteToken = null
                                }
                            }
                            .addOnFailureListener {
                                destinationSearchError = "Unable to load the selected place"
                            }
                    },
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                tonalElevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = prediction.getPrimaryText(null).toString(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val secondaryText = prediction.getSecondaryText(null).toString()
                    if (secondaryText.isNotBlank()) {
                        Text(
                            text = secondaryText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        destinationSearchError?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                val key = BuildConfig.MAPS_API_KEY
                if (key.isBlank()) {
                    routeMessage = "Add MAPS_API_KEY to local.properties, then rebuild."
                    return@Button
                }

                val origin = currentLocation
                if (origin == null) {
                    routeMessage = "Current GPS location is unavailable. Tap GET CURRENT LOCATION first."
                    return@Button
                }

                // A picked or spoken place beats the typed text: it is a point, not an address to
                // look up again.
                val typed = destination.trim()
                val picked = destinationLocation
                if (picked == null && typed.isBlank()) {
                    routeMessage = "Type a destination, or pick one from the suggestions."
                    return@Button
                }

                val target = picked
                    ?.let { RouteDestination.Point(it.latitude, it.longitude) }
                    ?: RouteDestination.Address(typed)
                val label = destinationName ?: typed

                routeLoading = true
                routeMessage = null
                routeStatus = "requesting a walking route to \"$label\"..."

                coroutineScope.launch {
                    try {
                        val plan = withContext(Dispatchers.IO) {
                            RoutesApi.fetchRoute(
                                apiKey = key,
                                origin = GeoPoint(origin.latitude, origin.longitude),
                                destination = target,
                            )
                        }
                        adoptRoute(plan, label)
                    } catch (error: Exception) {
                        routePoints = emptyList()
                        routeInstructions = emptyList()
                        routeDistance = null
                        routeDuration = null
                        routeSteps = emptyList()
                        follower = null
                        routeMessage = error.message ?: "Unable to calculate the walking route."
                        routeStatus = error.message ?: "route request failed"
                    } finally {
                        routeLoading = false
                    }
                }
            },
            enabled = !routeLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (routeLoading) "LOADING ROUTE..." else "GET ROUTE")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Walking directions may not always reflect safe or accessible pedestrian paths. " +
                "Use caution and follow local conditions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        routeMessage?.let { message ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        if (routeDistance != null || routeDuration != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Walking Route",
                style = MaterialTheme.typography.titleMedium
            )
            routeDistance?.let { Text("Distance: $it") }
            routeDuration?.let { Text("Estimated duration: $it") }
        }

        if (routeInstructions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Turn-by-turn directions",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.fillMaxWidth()
            )
            routeInstructions.forEachIndexed { index, instruction ->
                Text(
                    text = "${index + 1}. $instruction",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                )
            }
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

    if (showOcrMode) {
        Dialog(
            onDismissRequest = { showOcrMode = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            OcrScreen(
                onClose = { showOcrMode = false },
                speakObstacleAlert = conversationManager::speakObstacleAlert
            )
        }
    }
}

/** One decision per tick for the route follower. */
private const val FOLLOW_TICK_MS = 100L

/** The screens: the controls, the live map, and the manual drive tuning page. */
private enum class Screen { Controls, ConfigureRobot }

/** GPS course over ground, only trustworthy while actually moving - standing still it is noise. */
private fun gpsCourse(location: Location): Double? =
    if (location.hasBearing() && location.speed > 0.3f) location.bearing.toDouble() else null

/** "NE 51°", or a dash until the compass has a value. */
internal fun headingText(heading: Double?): String =
    heading?.let { "${Geo.compassPoint(it)} ${it.toInt()}°" } ?: "unknown"

/**
 * The "you are here" arrow: a triangle pointing up the bitmap, which on a north-up map means north.
 * The marker's rotation carries the robot's heading.
 *
 * Built on demand rather than during composition: BitmapDescriptorFactory throws until the Maps SDK
 * has been initialised by the map itself.
 */
private fun headingArrowIcon(context: Context): BitmapDescriptor {
    val size = (26 * context.resources.displayMetrics.density).toInt().coerceAtLeast(26)
    val extent = size.toFloat()

    val path = Path().apply {
        moveTo(extent / 2f, extent * 0.04f)
        lineTo(extent * 0.94f, extent * 0.86f)
        lineTo(extent / 2f, extent * 0.62f)
        lineTo(extent * 0.06f, extent * 0.86f)
        close()
    }

    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).drawPath(
        path,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1B5E20.toInt()
            style = Paint.Style.FILL
        },
    )

    return BitmapDescriptorFactory.fromBitmap(bitmap)
}
