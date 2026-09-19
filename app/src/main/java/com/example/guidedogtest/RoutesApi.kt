package com.example.guidedogtest

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Google Routes API client.
 *
 * One `computeRoutes` call in, a list of [RouteStep] out. Runs on a background thread - call it
 * from a coroutine on Dispatchers.IO.
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
            "routes.distanceMeters"

    fun fetchRoute(
        apiKey: String,
        origin: GeoPoint,
        destination: String,
    ): List<RouteStep> {
        val body = JSONObject()
            .put("origin", JSONObject().put("location", latLng(origin)))
            .put("destination", JSONObject().put("address", destination))
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

        val steps = parse(text)
        if (steps.isEmpty()) error("Routes API returned no route to \"$destination\"")
        return steps
    }

    /** Visible for tests: turns a computeRoutes response into a step list. */
    fun parse(response: String): List<RouteStep> {
        val routes = JSONObject(response).optJSONArray("routes") ?: return emptyList()
        if (routes.length() == 0) return emptyList()

        val steps = mutableListOf<RouteStep>()
        val legs = routes.getJSONObject(0).optJSONArray("legs") ?: return emptyList()

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
        return steps
    }

    private fun latLng(point: GeoPoint) = JSONObject()
        .put("latLng", JSONObject().put("latitude", point.lat).put("longitude", point.lng))
}
