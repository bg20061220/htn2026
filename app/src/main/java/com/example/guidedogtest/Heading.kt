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
 * Rotation added to the phone's own heading to get the robot's.
 *
 * The phone sits flat on the robot's top plate in **landscape**, so the edge pointing down the road is
 * one of its long edges - 90 degrees off the portrait top edge that Android's compass reports. This
 * constant is the only place that correction lives: the sensor code stays raw, and everything that
 * steers on a heading (route following, the map arrow, the readouts) sees the corrected value.
 *
 * - `+90` when the phone's **right** edge - the edge on your right looking at the phone in portrait -
 *   points down the road.
 * - `-90` when the **left** edge does.
 * - `0` would mean a portrait mount with the top edge forward.
 *
 * The two landscape candidates are 180 degrees apart, so picking between them is a binary check on
 * the robot: point it at a known heading and read the corrected value on the Configure Robot page. If
 * it reads 180 degrees out, flip the sign. Nothing else about the mount is assumed anywhere else in
 * the code.
 */
internal const val ROBOT_HEADING_OFFSET_DEGREES = 90.0

/**
 * Which way the car is pointing, from the phone's rotation-vector sensor.
 *
 * GPS course cannot do this job: a receiver that is standing still reports no course at all, and the
 * robot turns on the spot. The rotation vector fuses accelerometer, gyroscope and magnetometer and
 * holds a heading at rest, which is what the follower aligns to.
 *
 * [headingDegrees] is the robot's forward direction - the raw phone heading turned by
 * [ROBOT_HEADING_OFFSET_DEGREES]. [rawHeadingDegrees] is kept as well, because calibration needs to
 * see both: the offset is applied in exactly one place, and it is applied before anything compares a
 * heading to a bearing.
 *
 * Azimuth arrives as a magnetic bearing; [setLocation] adds the local declination so the result is a
 * true bearing, comparable with [Geo.bearingDegrees].
 */
class HeadingSource(context: Context) : SensorEventListener {

    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVector = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /** The phone's own heading: its portrait top edge, true north, mount not applied. */
    var rawHeadingDegrees by mutableStateOf<Double?>(null)
        private set

    /** The robot's forward direction in degrees, 0 = north, 90 = east. Null until the sensor reports. */
    var headingDegrees by mutableStateOf<Double?>(null)
        private set

    /** The mounting offset in force, for the debug readout. */
    val offsetDegrees: Double get() = ROBOT_HEADING_OFFSET_DEGREES

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

        val raw = rawPhoneHeading(rotation, declination)
        val robot = robotHeading(raw)

        // The sensor fires 16 times a second and every write here recomposes both screens, so a
        // stationary car's noise is dropped: only a real change is worth publishing.
        val published = rawHeadingDegrees
        if (published == null || abs(Geo.normalizeDegrees(raw - published)) >= HEADING_STEP_DEGREES) {
            rawHeadingDegrees = raw
            headingDegrees = robot
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        /** How far the heading has to move before the screens hear about it. */
        const val HEADING_STEP_DEGREES = 1.0
    }
}

/**
 * The phone's own heading: where its portrait top edge points, in degrees true.
 *
 * The rotation matrix is Android's, row-major, taking device coordinates to world (east, north, up);
 * row 0 is the world's east axis in device coordinates, row 1 is north, row 2 is up, so the *columns*
 * are the device's axes in the world. Device `+Y` - the top edge - is column 1, hence indices 1 and 4.
 * Reading the wrong column is a silent 90 degree error, which is why [HeadingTest] pins this.
 *
 * [declination] turns the magnetic bearing into a true one.
 */
internal fun rawPhoneHeading(rotation: FloatArray, declination: Double): Double {
    val east = rotation[1].toDouble()
    val north = rotation[4].toDouble()
    return normaliseDegrees(Math.toDegrees(atan2(east, north)) + declination)
}

/**
 * The robot's forward direction: the raw phone heading turned by the mounting [offsetDegrees].
 *
 * The single place the mount is applied, and the single place a heading is normalised into 0..360.
 */
internal fun robotHeading(
    rawDegrees: Double,
    offsetDegrees: Double = ROBOT_HEADING_OFFSET_DEGREES,
): Double = normaliseDegrees(rawDegrees + offsetDegrees)

/** Wraps any angle into 0..360. */
private fun normaliseDegrees(degrees: Double): Double = (degrees % 360.0 + 360.0) % 360.0
