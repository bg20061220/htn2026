package com.example.guidedogtest.maps

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

data class WalkingRoute(
    val distanceMeters: Int,
    val durationSeconds: Double,
    val points: List<LatLng>,
    val instructions: List<String>
)

fun getMapsApiKey(context: Context): String {
    val applicationInfo = context.packageManager.getApplicationInfo(
        context.packageName,
        PackageManager.GET_META_DATA
    )
    return applicationInfo.metaData
        ?.getString("com.google.android.geo.API_KEY")
        .orEmpty()
}

suspend fun computeWalkingRoute(
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

fun formatDistance(distanceMeters: Int): String =
    if (distanceMeters < 1_000) {
        "$distanceMeters m"
    } else {
        String.format(Locale.getDefault(), "%.1f km", distanceMeters / 1_000.0)
    }

fun formatDuration(durationSeconds: Double): String {
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
