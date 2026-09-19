package com.example.guidedogtest

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Drive values for the ESP32 firmware (OpenBot `DIY_ESP32`).
 *
 * Values are raw PWM in -255..255 for the two sides: `left` drives the two left motors,
 * `right` the two right motors.
 *
 * What lives here is what the *automatic* driving needs. The manual commands are not here any more:
 * their wheel speeds are editable on the Configure Robot page ([MotorSettings]), because finding the
 * numbers this chassis wants is a job for the floor, not for a source file.
 */
object Drive {

    /** The largest magnitude the firmware accepts on either side. */
    const val MAX_PWM = 255

    /** Left-wheel magnitude for straight driving. */
    const val SPEED = 180

    /** How much less the right side is commanded to track straight. Re-calibrate here. */
    const val RIGHT_TRIM = 52

    /**
     * The follower's in-place rotation: deliberately slower than the manual pivots on the Configure
     * Robot page, because a compass loop that turns slowly stops on the bearing instead of sailing
     * past it. [TURN_MIN] is the creep used in the last few degrees, [TURN_MAX] the ceiling for a
     * wide error. Raise [TURN_MIN] if the car stalls instead of turning on the floor.
     */
    const val TURN_MIN = 100
    const val TURN_MAX = 130

    /** The frame that releases the motors. */
    const val STOP_FRAME = "c0,0\n"

    /** Frame for an explicit wheel pair, e.g. "c150,120\n" - raw, exactly as given. */
    fun frame(left: Int, right: Int): String = "c$left,$right\n"
}

/** A way of talking to the ESP32: USB serial over the C-to-C cable, or BLE as a fallback. */
interface Transport {

    /** Name shown in the UI. */
    val label: String

    /** Is a device for this transport attached right now? */
    fun devicePresent(): Boolean

    fun connect()

    fun send(frame: String): Boolean

    fun close()
}

/**
 * Turns a firmware telemetry frame into something worth showing on screen.
 * Frames: v<volts>, s<cm>, w<rpmL>,<rpmR>, b<id>, r (boot).
 */
internal fun describeTelemetry(frame: String): String? {
    if (frame.isEmpty()) return null
    val body = frame.substring(1)
    return when (frame[0]) {
        'v' -> "battery $body V"
        's' -> "obstacle $body cm"
        'w' -> "wheels $body rpm"
        'b' -> "bump $body"
        'r' -> "robot ready"
        else -> null
    }
}

/**
 * The link to the robot.
 *
 * The phone is mounted on the car and wired USB-C to the ESP32, so that cable carries both
 * power and data and USB serial is the primary link. BLE stays as a fallback for when the
 * cable is unplugged or the port misbehaves - either way the ESP32 must keep receiving a
 * heartbeat, or it stops the motors on its own.
 */
class RobotLink(context: Context) {

    private val appContext = context.applicationContext

    /** Compose state, written only from the main thread. */
    var status by mutableStateOf("not connected")
        private set
    var telemetry by mutableStateOf("no telemetry yet")
        private set
    var connected by mutableStateOf(false)
        private set

    private val usb = UsbTransport(
        appContext,
        onStatus = ::postStatus,
        onTelemetry = ::postTelemetry,
        onConnected = ::postConnected,
    )

    private val ble = BleTransport(
        appContext,
        onStatus = ::postStatus,
        onTelemetry = ::postTelemetry,
        onConnected = ::postConnected,
    )

    private var active: Transport? = null

    /** True when the ESP32 is plugged into the phone right now. */
    fun usbAttached(): Boolean = usb.devicePresent()

    /** USB first (it is already plugged in), BLE only if nothing is attached. */
    fun connect() {
        close()

        val chosen: Transport = if (usb.devicePresent()) usb else ble
        active = chosen
        chosen.connect()
    }

    /** Sends one ASCII frame, e.g. "c128,128\n". Returns false when no link is open. */
    fun send(frame: String): Boolean = active?.send(frame) ?: false

    /** Stops the motors, then drops whichever link is open. Safe when not connected. */
    fun stopAndDisconnect() {
        send(Drive.STOP_FRAME)
        close()
    }

    fun close() {
        usb.close()
        ble.close()
        active = null
    }

    private fun postStatus(message: String) {
        status = message
    }

    private fun postTelemetry(message: String) {
        telemetry = message
    }

    private fun postConnected(value: Boolean) {
        connected = value
        if (!value && active != null) {
            status = active?.label?.plus(" disconnected") ?: "disconnected"
        }
    }

    companion object {

        /** Runtime permissions needed to scan for and talk to the ESP32 over BLE. */
        fun requiredPermissions(): List<String> =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                listOf(
                    android.Manifest.permission.BLUETOOTH_SCAN,
                    android.Manifest.permission.BLUETOOTH_CONNECT,
                )
            } else {
                listOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
            }
    }
}
