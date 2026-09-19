package com.example.guidedogtest

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Which way the car is pointing, from the phone's rotation-vector sensor.
 *
 * GPS course cannot do this job: a receiver that is standing still reports no course at all, and the
 * robot turns on the spot. The rotation vector fuses accelerometer, gyroscope and magnetometer and
 * holds a heading at rest, which is what the follower aligns to.
 *
 * The phone is mounted flat on the robot with its camera end forward, so the heading is the bearing
 * of the phone's top edge - see [headingFromRotation] for the mount and how to change it.
 *
 * Azimuth arrives as a magnetic bearing; [setLocation] adds the local declination so the result is a
 * true bearing, comparable with [Geo.bearingDegrees].
 */
class HeadingSource(context: Context) : SensorEventListener {

    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVector = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /** True heading in degrees, 0 = north, 90 = east. Null until the sensor reports a value. */
    var headingDegrees by mutableStateOf<Double?>(null)
        private set

    /** Magnetic declination at the current fix; 0 until [setLocation] has been called. */
    private var declination = 0.0

    private val rotation = FloatArray(9)

    /** True north = magnetic north + declination, so a fix has to be known to get a true heading. */
    fun setLocation(lat: Double, lng: Double) {
        declination = GeomagneticField(
            lat.toFloat(),
            lng.toFloat(),
            0f,
            System.currentTimeMillis(),
        ).declination.toDouble()
    }

    fun start() {
        rotationVector?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensors.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        val heading = headingFromRotation(rotation, declination)

        // The sensor fires 16 times a second and every write here recomposes both screens, so a
        // stationary car's noise is dropped: only a real change is worth publishing.
        val published = headingDegrees
        if (published == null || abs(Geo.normalizeDegrees(heading - published)) >= HEADING_STEP_DEGREES) {
            headingDegrees = heading
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        /** How far the heading has to move before the screens hear about it. */
        const val HEADING_STEP_DEGREES = 1.0
    }
}

/**
 * Which way the phone's forward axis points, from a rotation matrix in Android's layout: the matrix
 * is row-major and takes device coordinates to world (east, north, up), so row 0 is the world's east
 * axis in device coordinates, row 1 is north, row 2 is up - and the *columns* are the device's axes
 * in the world. Reading the wrong column silently negates the heading, and a robot steering on a
 * negated heading turns the wrong way.
 *
 * The build decides which axis is forward, not the sensors: **the phone lies flat on the robot with
 * the camera end (its top edge, device `+Y`) pointing down the road**, so the heading is that edge's
 * bearing. Nothing here guesses, because guessing cannot work - a phone standing up in landscape has
 * two equally horizontal axes and picking the wrong one is a silent 90 degrees.
 *
 * Mounted upright instead, with the back camera looking forward? Swap the two pairs below:
 * `-rotation[2]` for east and `-rotation[5]` for north (device `-Z` is the camera).
 *
 * [declination] is the local magnetic declination: magnetic north is not true north.
 */
internal fun headingFromRotation(rotation: FloatArray, declination: Double): Double {
    val east = rotation[1].toDouble()
    val north = rotation[4].toDouble()

    val magnetic = Math.toDegrees(atan2(east, north))

    return (magnetic + declination + 360.0) % 360.0
}
