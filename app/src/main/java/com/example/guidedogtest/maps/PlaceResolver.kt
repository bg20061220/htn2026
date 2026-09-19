package com.example.guidedogtest.maps

import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.tasks.Task
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.model.RectangularBounds
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

data class ResolvedPlace(
    val name: String,
    val address: String,
    val location: LatLng
)

/**
 * Resolves a spoken destination phrase (e.g. "the library", "tim hortons")
 * to a single best-match place, biased toward [origin] when available.
 * Deliberately takes only the top autocomplete result — good enough for a
 * voice flow where the user confirms the result out loud before we act on
 * it, rather than reading out a list of candidates.
 */
suspend fun resolveBestPlace(
    placesClient: PlacesClient,
    query: String,
    origin: LatLng?
): ResolvedPlace? {
    return try {
        val token = AutocompleteSessionToken.newInstance()
        val requestBuilder = FindAutocompletePredictionsRequest.builder()
            .setQuery(query)
            .setSessionToken(token)

        if (origin != null) {
            requestBuilder.setOrigin(origin)
            val delta = 0.5
            requestBuilder.setLocationBias(
                RectangularBounds.newInstance(
                    LatLng(max(-90.0, origin.latitude - delta), max(-180.0, origin.longitude - delta)),
                    LatLng(min(90.0, origin.latitude + delta), min(180.0, origin.longitude + delta))
                )
            )
        }

        val predictions = placesClient.findAutocompletePredictions(requestBuilder.build())
            .await()
            .autocompletePredictions
        val top = predictions.firstOrNull() ?: return null

        val fetchRequest = FetchPlaceRequest.builder(
            top.placeId,
            listOf(Place.Field.DISPLAY_NAME, Place.Field.LOCATION, Place.Field.FORMATTED_ADDRESS)
        ).setSessionToken(token).build()

        val place = placesClient.fetchPlace(fetchRequest).await().place
        val location = place.location ?: return null

        ResolvedPlace(
            name = place.displayName ?: top.getPrimaryText(null).toString(),
            address = place.formattedAddress.orEmpty(),
            location = location
        )
    } catch (e: Exception) {
        null
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}
