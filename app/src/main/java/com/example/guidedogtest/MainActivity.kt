package com.example.guidedogtest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.guidedogtest.ui.theme.GuideDogTestTheme
import com.example.guidedogtest.voice.ConversationManager
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
import com.google.ar.core.Session
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Places.isInitialized()) {
            val mapsApiKey = getMapsApiKey(this)
            if (mapsApiKey.isNotBlank()) {
                Places.initializeWithNewPlacesApiEnabled(applicationContext, mapsApiKey)
            }
        }

        setContent {
            GuideDogTestTheme {
                NavigationScreen()
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

    var destination by remember { mutableStateOf("") }
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

    var latitude by remember { mutableStateOf("Unknown") }
    var longitude by remember { mutableStateOf("Unknown") }

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

    val conversationManager: ConversationManager = viewModel(
        factory = viewModelFactory {
            initializer {
                ConversationManager(
                    context = context.applicationContext,
                    groqApiKey = BuildConfig.GROQ_API_KEY,
                    elevenLabsApiKey = BuildConfig.ELEVENLABS_API_KEY,
                    onCommand = { robotCommand ->
                        // TODO: wire into BLE once the ESP32 link exists —
                        // same as the FORWARD/LEFT/STOP/RIGHT buttons below.
                        Log.d("MainActivity", "Voice robot command: $robotCommand")
                    }
                )
            }
        }
    )

    val conversationState by conversationManager.state.collectAsState()
    val lastSpoken by conversationManager.lastSpoken.collectAsState()

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
                            latitude = location.latitude.toString()
                            longitude = location.longitude.toString()
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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

        Text("Latitude: $latitude")
        Text("Longitude: $longitude")

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
                            latitude = location.latitude.toString()
                            longitude = location.longitude.toString()
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

        GoogleMap(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp),
            cameraPositionState = cameraPositionState
        ) {
            currentLocation?.let { location ->
                Marker(
                    state = rememberUpdatedMarkerState(position = location),
                    title = "Current Location"
                )
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
            modifier = Modifier.fillMaxWidth()
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
                val origin = currentLocation
                val selectedDestination = destinationLocation
                when {
                    origin == null -> {
                        routeMessage = "Current GPS location is unavailable. Tap GET CURRENT LOCATION first."
                    }
                    selectedDestination == null -> {
                        routeMessage = "Select a destination from the place suggestions first."
                    }
                    else -> coroutineScope.launch {
                        routeLoading = true
                        routeMessage = null
                        routePoints = emptyList()
                        routeDistance = null
                        routeDuration = null
                        routeInstructions = emptyList()

                        try {
                            val apiKey = getMapsApiKey(context)
                            if (apiKey.isBlank()) {
                                throw IOException("The Maps API key is unavailable.")
                            }
                            val route = computeWalkingRoute(
                                context = context,
                                apiKey = apiKey,
                                origin = origin,
                                destination = selectedDestination
                            )
                            routePoints = route.points
                            routeDistance = formatDistance(route.distanceMeters)
                            routeDuration = formatDuration(route.durationSeconds)
                            routeInstructions = route.instructions
                            routeMessage = if (route.instructions.isEmpty()) {
                                "Route found, but no turn-by-turn instructions were returned."
                            } else {
                                null
                            }
                        } catch (error: Exception) {
                            routeMessage = error.message
                                ?: "Unable to calculate the walking route. Check your connection and try again."
                        } finally {
                            routeLoading = false
                        }
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

        Spacer(modifier = Modifier.height(20.dp))

        Text("Robot Command: $command")

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                command = "FORWARD"
            }
        ) {
            Text("FORWARD")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {

            Button(
                onClick = {
                    command = "LEFT"
                }
            ) {
                Text("LEFT")
            }

            Button(
                onClick = {
                    command = "STOP"
                }
            ) {
                Text("STOP")
            }

            Button(
                onClick = {
                    command = "RIGHT"
                }
            ) {
                Text("RIGHT")
            }
        }
    }
}

private data class WalkingRoute(
    val distanceMeters: Int,
    val durationSeconds: Double,
    val points: List<LatLng>,
    val instructions: List<String>
)

private fun getMapsApiKey(context: Context): String {
    val applicationInfo = context.packageManager.getApplicationInfo(
        context.packageName,
        PackageManager.GET_META_DATA
    )
    return applicationInfo.metaData
        ?.getString("com.google.android.geo.API_KEY")
        .orEmpty()
}

private suspend fun computeWalkingRoute(
    context: Context,
    apiKey: String,
    origin: LatLng,
    destination: LatLng
): WalkingRoute = withContext(Dispatchers.IO) {
    val requestBody = JSONObject()
        .put("origin", routeWaypoint(origin))
        .put("destination", routeWaypoint(destination))
        .put("travelMode", "WALK")
        .put("computeAlternativeRoutes", false)
        .put("languageCode", Locale.getDefault().toLanguageTag())
        .put("units", "METRIC")

    val connection = (URL(ROUTES_API_URL).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 15_000
        readTimeout = 20_000
        doOutput = true
        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        setRequestProperty("X-Goog-Api-Key", apiKey)
        setRequestProperty("X-Goog-FieldMask", ROUTES_FIELD_MASK)
        setRequestProperty("X-Android-Package", context.packageName)
        getSigningCertificateSha1(context)?.let {
            setRequestProperty("X-Android-Cert", it)
        }
    }

    try {
        connection.outputStream.bufferedWriter(Charsets.UTF_8).use {
            it.write(requestBody.toString())
        }

        val responseCode = connection.responseCode
        val responseText = (if (responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        })?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

        if (responseCode !in 200..299) {
            val apiMessage = runCatching {
                JSONObject(responseText).getJSONObject("error").getString("message")
            }.getOrNull()
            throw IOException(
                apiMessage ?: "Routes API request failed (HTTP $responseCode)."
            )
        }

        parseWalkingRoute(JSONObject(responseText))
    } finally {
        connection.disconnect()
    }
}

private fun routeWaypoint(location: LatLng): JSONObject = JSONObject()
    .put(
        "location",
        JSONObject().put(
            "latLng",
            JSONObject()
                .put("latitude", location.latitude)
                .put("longitude", location.longitude)
        )
    )

private fun parseWalkingRoute(response: JSONObject): WalkingRoute {
    val routes = response.optJSONArray("routes")
    if (routes == null || routes.length() == 0) {
        throw IOException("No walking route was found for this destination.")
    }

    val route = routes.getJSONObject(0)
    val encodedPolyline = route
        .optJSONObject("polyline")
        ?.optString("encodedPolyline")
        .orEmpty()
    if (encodedPolyline.isBlank()) {
        throw IOException("The route did not include a drawable path.")
    }

    val instructions = buildList {
        val legs = route.optJSONArray("legs") ?: return@buildList
        for (legIndex in 0 until legs.length()) {
            val steps = legs.getJSONObject(legIndex).optJSONArray("steps") ?: continue
            for (stepIndex in 0 until steps.length()) {
                val instruction = steps.getJSONObject(stepIndex)
                    .optJSONObject("navigationInstruction")
                    ?.optString("instructions")
                    .orEmpty()
                val stepDistance = steps.getJSONObject(stepIndex).optInt("distanceMeters", -1)
                if (instruction.isNotBlank()) {
                    add(
                        if (stepDistance >= 0) {
                            "$instruction (${formatDistance(stepDistance)})"
                        } else {
                            instruction
                        }
                    )
                }
            }
        }
    }

    return WalkingRoute(
        distanceMeters = route.optInt("distanceMeters", 0),
        durationSeconds = route.optString("duration")
            .removeSuffix("s")
            .toDoubleOrNull()
            ?: 0.0,
        points = decodePolyline(encodedPolyline),
        instructions = instructions
    )
}

private fun decodePolyline(encoded: String): List<LatLng> {
    val points = mutableListOf<LatLng>()
    var index = 0
    var latitude = 0
    var longitude = 0

    while (index < encoded.length) {
        var result = 0
        var shift = 0
        var value: Int
        do {
            if (index >= encoded.length) throw IOException("The route path was invalid.")
            value = encoded[index++].code - 63
            result = result or ((value and 0x1f) shl shift)
            shift += 5
        } while (value >= 0x20)
        latitude += if ((result and 1) != 0) (result shr 1).inv() else result shr 1

        result = 0
        shift = 0
        do {
            if (index >= encoded.length) throw IOException("The route path was invalid.")
            value = encoded[index++].code - 63
            result = result or ((value and 0x1f) shl shift)
            shift += 5
        } while (value >= 0x20)
        longitude += if ((result and 1) != 0) (result shr 1).inv() else result shr 1

        points += LatLng(latitude / 1E5, longitude / 1E5)
    }

    return points
}

private fun formatDistance(distanceMeters: Int): String =
    if (distanceMeters < 1_000) {
        "$distanceMeters m"
    } else {
        String.format(Locale.getDefault(), "%.1f km", distanceMeters / 1_000.0)
    }

private fun formatDuration(durationSeconds: Double): String {
    val totalMinutes = ceil(durationSeconds / 60.0).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "$hours hr $minutes min"
        hours > 0 -> "$hours hr"
        else -> "${max(1, minutes)} min"
    }
}

private fun getSigningCertificateSha1(context: Context): String? {
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES
        )
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNATURES
        )
    }

    val certificate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()
    } else {
        @Suppress("DEPRECATION")
        packageInfo.signatures?.firstOrNull()
    } ?: return null

    return MessageDigest.getInstance("SHA-1")
        .digest(certificate.toByteArray())
        .joinToString(separator = "") { byte -> "%02X".format(byte.toInt() and 0xff) }
}

private const val ROUTES_API_URL =
    "https://routes.googleapis.com/directions/v2:computeRoutes"
private const val ROUTES_FIELD_MASK =
    "routes.distanceMeters,routes.duration,routes.polyline.encodedPolyline," +
        "routes.legs.steps.navigationInstruction,routes.legs.steps.distanceMeters"
