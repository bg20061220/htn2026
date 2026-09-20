package com.example.guidedogtest

import android.util.Log
import com.google.android.gms.maps.model.LatLng
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/** Where a route ends: a typed address, or a point that came from places or the voice. */
sealed interface RouteDestination {

    data class Address(val text: String) : RouteDestination

    data class Point(val lat: Double, val lng: Double) : RouteDestination
}

/**
 * A whole walking route.
 *
 * It carries both halves of what the app needs, from one call: [steps] are what the robot drives
 * (each ends somewhere, so the follower can steer to it), and [points] are the line the map draws.
 * The voice and the on-screen list read [instructions], derived from the same steps, so the spoken
 * cue and the driven manoeuvre can never disagree.
 */
data class RoutePlan(
    val steps: List<RouteStep>,
    val distanceMeters: Int,
    val durationSeconds: Double,
    val points: List<LatLng>,
) {

    /** "Turn right onto Ring Road (40 m)" per step, for the list on screen and the spoken cue. */
    val instructions: List<String>
        get() = steps.map { step ->
            if (step.distanceMeters > 0) {
                "${step.instruction} (${formatDistance(step.distanceMeters)})"
            } else {
                step.instruction
            }
        }
}

/**
 * Google Routes API client - the only route source in the app.
 *
 * One `computeRoutes` call in, a [RoutePlan] out. Runs on a background thread - call it from a
 * coroutine on Dispatchers.IO.
 *
 * WALK is the travel mode because this robot follows footpaths, and the field mask is explicit
 * because the API requires one and it also keeps the response small.
 */
object RoutesApi {

    private const val TAG = "RoutesApi"
    private const val ENDPOINT = "https://routes.googleapis.com/directions/v2:computeRoutes"
    private const val FIELD_MASK =
        "routes.legs.steps.navigationInstruction," +
            "routes.legs.steps.distanceMeters," +
            "routes.legs.steps.endLocation," +
            "routes.legs.endLocation," +
            "routes.distanceMeters," +
            "routes.duration," +
            "routes.polyline.encodedPolyline"

    fun fetchRoute(
        apiKey: String,
        origin: GeoPoint,
        destination: RouteDestination,
    ): RoutePlan {
        val body = JSONObject()
            .put("origin", JSONObject().put("location", latLng(origin)))
            .put("destination", destinationBody(destination))
            .put("travelMode", "WALK")
            .put("computeAlternativeRoutes", false)
            .put("languageCode", "en-US")
            .put("units", "METRIC")
            .toString()

        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Goog-Api-Key", apiKey)
            setRequestProperty("X-Goog-FieldMask", FIELD_MASK)
        }

        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

        val code = connection.responseCode
        val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()
            ?.use(BufferedReader::readText)
            .orEmpty()
        connection.disconnect()

        if (code !in 200..299) {
            Log.i(TAG, "route request failed: $code $text")
            error("Routes API $code: ${text.take(180)}")
        }

        return parse(text, destination)
    }

    /** Visible for tests: turns a computeRoutes response into the plan the app uses. */
    fun parse(response: String, destination: RouteDestination? = null): RoutePlan {
        val routes = JSONObject(response).optJSONArray("routes") ?: error(noRoute(destination))
        if (routes.length() == 0) error(noRoute(destination))

        val route = routes.getJSONObject(0)
        val steps = mutableListOf<RouteStep>()
        val legs = route.optJSONArray("legs") ?: return planOf(route, steps)

        for (i in 0 until legs.length()) {
            val leg = legs.getJSONObject(i)
            val legSteps = leg.optJSONArray("steps") ?: continue
            val legEnd = leg.optJSONObject("endLocation")?.optJSONObject("latLng")

            for (j in 0 until legSteps.length()) {
                val step = legSteps.getJSONObject(j)
                val end = step.optJSONObject("endLocation")?.optJSONObject("latLng") ?: legEnd
                if (end == null) continue

                val navigation = step.optJSONObject("navigationInstruction")
                steps += RouteStep(
                    instruction = navigation?.optString("instructions").orEmpty().ifBlank { "continue" },
                    distanceMeters = step.optInt("distanceMeters", 0),
                    endLat = end.optDouble("latitude"),
                    endLng = end.optDouble("longitude"),
                )
            }
        }

        if (steps.isEmpty()) error(noRoute(destination))
        return planOf(route, steps)
    }

    private fun planOf(route: JSONObject, steps: List<RouteStep>) = RoutePlan(
        steps = steps,
        distanceMeters = route.optInt("distanceMeters", 0),
        durationSeconds = route.optString("duration").removeSuffix("s").toDoubleOrNull() ?: 0.0,
        points = decodePolyline(
            route.optJSONObject("polyline")?.optString("encodedPolyline").orEmpty()
        ),
    )

    private fun noRoute(destination: RouteDestination?): String = when (destination) {
        is RouteDestination.Address -> "Routes API returned no route to \"${destination.text}\""
        is RouteDestination.Point -> "Routes API returned no route to that place"
        null -> "Routes API returned no route"
    }

    private fun destinationBody(destination: RouteDestination): JSONObject = when (destination) {
        is RouteDestination.Address ->
            JSONObject().put("address", destination.text)

        is RouteDestination.Point ->
            JSONObject().put("location", latLng(GeoPoint(destination.lat, destination.lng)))
    }

    private fun latLng(point: GeoPoint) = JSONObject()
        .put("latLng", JSONObject().put("latitude", point.lat).put("longitude", point.lng))

    /**
     * Google's encoded polyline back into points. A route with no drawable line is not an error -
     * the robot drives the steps either way - so a malformed or missing path just draws nothing.
     */
    internal fun decodePolyline(encoded: String): List<LatLng> {
        val points = mutableListOf<LatLng>()
        var index = 0
        var latitude = 0
        var longitude = 0

        while (index < encoded.length) {
            var result = 0
            var shift = 0
            var value: Int
            do {
                if (index >= encoded.length) return points
                value = encoded[index++].code - 63
                result = result or ((value and 0x1f) shl shift)
                shift += 5
            } while (value >= 0x20)
            latitude += if ((result and 1) != 0) (result shr 1).inv() else result shr 1

            result = 0
            shift = 0
            do {
                if (index >= encoded.length) return points
                value = encoded[index++].code - 63
                result = result or ((value and 0x1f) shl shift)
                shift += 5
            } while (value >= 0x20)
            longitude += if ((result and 1) != 0) (result shr 1).inv() else result shr 1

            points += LatLng(latitude / 1E5, longitude / 1E5)
        }

        return points
    }
}

/** "40 m" or "1.2 km", for the route summary, the step list and the spoken cues. */
fun formatDistance(distanceMeters: Int): String =
    if (distanceMeters < 1_000) {
        "$distanceMeters m"
    } else {
        String.format(Locale.getDefault(), "%.1f km", distanceMeters / 1_000.0)
    }

/** "12 min" or "1 hr 5 min". */
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
