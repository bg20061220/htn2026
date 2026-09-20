package com.example.guidedogtest

import android.content.Context

/** One command's wheel speeds: raw PWM per side, -255..255. */
data class WheelSpeeds(val left: Int, val right: Int) {

    /** The frame the firmware understands, e.g. "c150,120\n". */
    fun frame(): String = Drive.frame(left, right)
}

/** One place for chassis-specific PWM tuning. Values are raw signed firmware PWM. */
object MotorTuning {
    const val MIN_EFFECTIVE_LEFT_POWER = 110
    const val MIN_EFFECTIVE_RIGHT_POWER = 110
    const val MIN_EFFECTIVE_MOTOR_SPEED = MIN_EFFECTIVE_LEFT_POWER
    const val MANUAL_FORWARD_LEFT = 180
    const val MANUAL_FORWARD_RIGHT = 128
    const val MANUAL_TURN_OUTER = 205
    const val MANUAL_TURN_INNER_LEFT = 190
    const val MANUAL_TURN_INNER_RIGHT = 195

    fun enforceMinimum(value: Int): Int = enforceMinimumFor(value, MIN_EFFECTIVE_MOTOR_SPEED)
    fun enforceMinimumLeft(value: Int): Int = enforceMinimumFor(value, MIN_EFFECTIVE_LEFT_POWER)
    fun enforceMinimumRight(value: Int): Int = enforceMinimumFor(value, MIN_EFFECTIVE_RIGHT_POWER)

    private fun enforceMinimumFor(value: Int, minimum: Int): Int = when {
        value == 0 -> 0
        value > 0 -> value.coerceAtLeast(minimum).coerceAtMost(Drive.MAX_PWM)
        else -> value.coerceAtMost(-minimum).coerceAtLeast(-Drive.MAX_PWM)
    }
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
 *  - left `-205 / 190` and right `205 / -195` - stronger asymmetric pivots from robot testing.
 */
data class MotorSettings(
    val forward: WheelSpeeds = WheelSpeeds(MotorTuning.MANUAL_FORWARD_LEFT, MotorTuning.MANUAL_FORWARD_RIGHT),
    val left: WheelSpeeds = WheelSpeeds(-MotorTuning.MANUAL_TURN_OUTER, MotorTuning.MANUAL_TURN_INNER_LEFT),
    val right: WheelSpeeds = WheelSpeeds(MotorTuning.MANUAL_TURN_OUTER, -MotorTuning.MANUAL_TURN_INNER_RIGHT),
) {

    /** The wheel pair a manual command drives with. Anything else - including STOP - releases. */
    fun speedsFor(command: String): WheelSpeeds = when (command) {
        "FORWARD" -> forward
        "LEFT" -> left
        "RIGHT" -> right
        "BACK" -> WheelSpeeds(-forward.left, -forward.right)
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

        val stored = MotorSettings(
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
        // Upgrade the old shipped turn defaults after the physical robot showed insufficient pivot
        // torque. Custom values above the effective minimum are preserved.
        val migratedLeft = if (stored.left == WheelSpeeds(-190, 170)) defaults.left else stored.left
        val migratedRight = if (stored.right == WheelSpeeds(190, -180)) defaults.right else stored.right
        return stored.copy(
            left = migratedLeft.withMinimumPower(),
            right = migratedRight.withMinimumPower(),
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

private fun WheelSpeeds.withMinimumPower() = WheelSpeeds(
    MotorTuning.enforceMinimum(left),
    MotorTuning.enforceMinimum(right),
)
