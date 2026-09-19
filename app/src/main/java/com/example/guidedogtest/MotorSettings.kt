package com.example.guidedogtest

import android.content.Context

/** One command's wheel speeds: raw PWM per side, -255..255. */
data class WheelSpeeds(val left: Int, val right: Int) {

    /** The frame the firmware understands, e.g. "c150,120\n". */
    fun frame(): String = Drive.frame(left, right)
}

/**
 * The manual drive values, edited on the Configure Robot page and kept across launches.
 *
 * Every command carries both wheels because that is what this chassis needs: at equal PWM it drives
 * crooked, so "forward" is a pair of numbers, not one. These are the values to tune on the floor -
 * change one, press the command, watch the car, and the value is still there after a restart.
 *
 * The defaults are the numbers measured on this chassis, and they are not symmetric:
 *
 *  - forward `180 / 128` - the right pair is weaker, so it gets 52 less.
 *  - left `-190 / 170` and right `190 / -180` - a pivot is asymmetric too, for the same reason.
 */
data class MotorSettings(
    val forward: WheelSpeeds = WheelSpeeds(Drive.SPEED, Drive.SPEED - Drive.RIGHT_TRIM),
    val left: WheelSpeeds = WheelSpeeds(-190, 170),
    val right: WheelSpeeds = WheelSpeeds(190, -180),
) {

    /** The wheel pair a manual command drives with. Anything else - including STOP - releases. */
    fun speedsFor(command: String): WheelSpeeds = when (command) {
        "FORWARD" -> forward
        "LEFT" -> left
        "RIGHT" -> right
        else -> WheelSpeeds(0, 0)
    }

    /** Copy with one command's speeds replaced. */
    fun withSpeeds(command: String, speeds: WheelSpeeds): MotorSettings = when (command) {
        "FORWARD" -> copy(forward = speeds)
        "LEFT" -> copy(left = speeds)
        "RIGHT" -> copy(right = speeds)
        else -> this
    }
}

/**
 * Keeps [MotorSettings] in SharedPreferences.
 *
 * Tuning is the whole point of the Configure Robot page, and tuning that vanishes on the next launch
 * is not tuning. Nothing here throws: a missing or corrupt entry falls back to the default.
 */
object MotorSettingsStore {

    private const val FILE = "motor-settings"

    private const val KEY_FORWARD_LEFT = "forward.left"
    private const val KEY_FORWARD_RIGHT = "forward.right"
    private const val KEY_LEFT_LEFT = "left.left"
    private const val KEY_LEFT_RIGHT = "left.right"
    private const val KEY_RIGHT_LEFT = "right.left"
    private const val KEY_RIGHT_RIGHT = "right.right"

    fun load(context: Context): MotorSettings {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val defaults = MotorSettings()

        return MotorSettings(
            forward = WheelSpeeds(
                prefs.getInt(KEY_FORWARD_LEFT, defaults.forward.left),
                prefs.getInt(KEY_FORWARD_RIGHT, defaults.forward.right),
            ),
            left = WheelSpeeds(
                prefs.getInt(KEY_LEFT_LEFT, defaults.left.left),
                prefs.getInt(KEY_LEFT_RIGHT, defaults.left.right),
            ),
            right = WheelSpeeds(
                prefs.getInt(KEY_RIGHT_LEFT, defaults.right.left),
                prefs.getInt(KEY_RIGHT_RIGHT, defaults.right.right),
            ),
        )
    }

    fun save(context: Context, settings: MotorSettings) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_FORWARD_LEFT, settings.forward.left)
            .putInt(KEY_FORWARD_RIGHT, settings.forward.right)
            .putInt(KEY_LEFT_LEFT, settings.left.left)
            .putInt(KEY_LEFT_RIGHT, settings.left.right)
            .putInt(KEY_RIGHT_LEFT, settings.right.left)
            .putInt(KEY_RIGHT_RIGHT, settings.right.right)
            .apply()
    }
}
