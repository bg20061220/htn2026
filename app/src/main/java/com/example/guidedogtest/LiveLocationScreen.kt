package com.example.guidedogtest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.location.Location
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions

/** Close enough to read street names and see the next turn coming. */
private const val MAP_ZOOM = 18f

/** The car's colour, used for the arrow and the accuracy ring. */
private const val CAR_COLOR = 0xFF1B5E20.toInt()

/**
 * The live position screen: a map that follows the phone, with the numbers behind the dot.
 *
 * It is deliberately bare. The phone is strapped to the robot and nobody reads it while the robot
 * walks, so this is the bring-up and pitch view - one marker, one ring, one line of text. A drag
 * hands the camera to whoever is holding the phone; a tap gives it back to the car.
 */
@Composable
fun LiveLocationScreen(
    location: Location?,
    headingDegrees: Double?,
    onRequestPermission: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    val granted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    var map by remember { mutableStateOf<GoogleMap?>(null) }
    var marker by remember { mutableStateOf<Marker?>(null) }
    var accuracyRing by remember { mutableStateOf<Circle?>(null) }

    /**
     * The car's arrow. Built only once the map exists: `BitmapDescriptorFactory` is not usable
     * before the Maps SDK has been initialised, which happens when the MapView is created.
     */
    var arrow by remember { mutableStateOf<BitmapDescriptor?>(null) }

    /** False while the operator is dragging the map around; a tap puts the camera back on the car. */
    var following by remember { mutableStateOf(true) }

    val mapView = remember { MapView(context).apply { onCreate(null) } }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDestroy()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        AndroidView(
            factory = {
                mapView.apply {
                    getMapAsync { googleMap ->
                        googleMap.mapType = GoogleMap.MAP_TYPE_NORMAL
                        googleMap.uiSettings.apply {
                            isCompassEnabled = true
                            isMyLocationButtonEnabled = false
                            isMapToolbarEnabled = false
                        }
                        googleMap.setOnCameraMoveStartedListener { reason ->
                            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                                following = false
                            }
                        }
                        googleMap.setOnMapClickListener { following = true }
                        arrow = headingArrowIcon(context)
                        map = googleMap
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        Text(
            text = readout(location, headingDegrees),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .background(Color(0xCCFFFFFF))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )

        TextButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .background(Color(0xCCFFFFFF)),
        ) {
            Text("BACK")
        }

        if (!granted) {
            Button(
                onClick = onRequestPermission,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            ) {
                Text("ALLOW LOCATION")
            }
        }
    }

    // One marker for the car, one ring for how good the fix is, and the camera on the car unless
    // somebody has taken it.
    LaunchedEffect(map, arrow, location, headingDegrees, following) {
        val googleMap = map ?: return@LaunchedEffect
        val icon = arrow ?: return@LaunchedEffect
        val here = location ?: return@LaunchedEffect
        val point = LatLng(here.latitude, here.longitude)

        val pin = marker
        val ring = accuracyRing

        if (pin == null || ring == null) {
            marker = googleMap.addMarker(
                MarkerOptions()
                    .position(point)
                    .icon(arrow)
                    .anchor(0.5f, 0.5f)
                    .flat(true),
            )
            accuracyRing = googleMap.addCircle(
                CircleOptions()
                    .center(point)
                    .radius(here.accuracy.toDouble())
                    .fillColor(0x22_1B_5E_20)
                    .strokeColor(0x66_1B_5E_20)
                    .strokeWidth(2f),
            )
        } else {
            pin.position = point
            pin.rotation = (headingDegrees ?: 0.0).toFloat()
            ring.center = point
            ring.radius = here.accuracy.toDouble()
        }

        if (following) {
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(point, MAP_ZOOM))
        }
    }
}

/** One line under the map's title: where we are, how well we know it, which way we point. */
private fun readout(location: Location?, headingDegrees: Double?): String {
    if (location == null) return "Waiting for a GPS fix"

    val speed = (location.speed * 3.6f).toInt()
    return "${location.latitude}, ${location.longitude}\n" +
        "±${location.accuracy.toInt()} m · ${headingText(headingDegrees)} · $speed km/h"
}

/** "NE 51°", or a dash until the compass has a value. */
internal fun headingText(heading: Double?): String =
    heading?.let { "${Geo.compassPoint(it)} ${it.toInt()}°" } ?: "unknown"

/**
 * The "you are here" arrow: a triangle pointing up the bitmap, which on a north-up map means north.
 * The marker's rotation carries the heading.
 */
private fun headingArrowIcon(context: Context): BitmapDescriptor {
    val size = (32 * context.resources.displayMetrics.density).toInt().coerceAtLeast(32)
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
            color = CAR_COLOR
            style = Paint.Style.FILL
        },
    )

    return BitmapDescriptorFactory.fromBitmap(bitmap)
}
