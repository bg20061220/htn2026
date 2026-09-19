package com.example.guidedogtest.ocr

import android.os.SystemClock
import java.util.Locale
import kotlin.math.abs

enum class AvoidanceAction { FORWARD, LEFT, RIGHT, STOP }

data class AvoidanceCommand(
    val action: AvoidanceAction,
    /** -1 (hard left) .. +1 (hard right); 0 is straight ahead. */
    val steering: Float = 0f,
    /** 1 = full speed, 0 = stopped. */
    val speedFactor: Float = 1f,
    val reason: String = ""
) {
    val summary: String
        get() = "$action steer ${String.format(Locale.US, "%+.2f", steering)} " +
            "speed ${(speedFactor * 100).toInt()}% ($reason)"
}

data class ObstacleAvoidanceConfig(
    /** At or inside this distance a zone pushes with full strength and counts as blocked. */
    val stopMeters: Float = 0.8f,
    /** Beyond this distance a zone exerts no push at all. */
    val influenceMeters: Float = 2.5f,
    /** How strongly the left/right imbalance steers. Kept below 1 so the default is a gentle veer. */
    val lateralGain: Float = 0.7f,
    /** Steering magnitudes below this are treated as straight. */
    val deadband: Float = 0.12f,
    /** Weight of the newest reading in the steering low-pass filter (1 = no smoothing). */
    val smoothing: Float = 0.6f,
    /** Left/right gap that must be exceeded before an obstacle-ahead escape switches sides. */
    val escapeSwitchMeters: Float = 0.3f,
    val minSpeedFactor: Float = 0.25f,
    val staleAfterMillis: Long = 1_500L
) {
    init {
        require(stopMeters > 0f && influenceMeters > stopMeters)
        require(smoothing in 0.05f..1f)
    }
}

/**
 * Turns the ARCore depth scene analysis (left/center/right distances) into a steering command.
 *
 * Steering always points away from the closer side: the nearer the obstacle, the harder the veer.
 * When something is straight ahead it escapes toward the more open side, and it stops when there
 * is no safe way to go or when it cannot see.
 */
class ObstacleAvoidanceController(
    private val config: ObstacleAvoidanceConfig = ObstacleAvoidanceConfig(),
    private val clockMillis: () -> Long = SystemClock::elapsedRealtime
) {
    private var steering = 0f
    private var escapeSign = 0
    private var lastUpdateMillis = clockMillis()
    private var staleReported = false

    fun update(scene: SceneAwarenessResult): AvoidanceCommand {
        lastUpdateMillis = clockMillis()
        staleReported = false

        val left = scene.leftDistanceMeters
        val center = scene.centerDistanceMeters
        val right = scene.rightDistanceMeters
        if (center == null) return stop(if (left == null && right == null) "no depth" else "path ahead unknown")

        val centerProximity = proximity(center)
        // A side we cannot measure is not a safe place to steer into, so lean slightly away from it.
        val leftProximity = left?.let(::proximity) ?: UNKNOWN_SIDE_PROXIMITY
        val rightProximity = right?.let(::proximity) ?: UNKNOWN_SIDE_PROXIMITY

        val escape = escapeDirection(left, right)
        // Left closer than right gives a positive value, which steers right (away from the closer side).
        val lateralPush = (leftProximity - rightProximity) * config.lateralGain
        val aheadPush = escape * centerProximity
        val target = (lateralPush + aheadPush).coerceIn(-1f, 1f)

        if (center < config.stopMeters) {
            val escapeDistance = if (escape > 0) right else if (escape < 0) left else null
            if (escapeDistance == null || escapeDistance < config.stopMeters) return stop("blocked ahead")
        }

        steering += config.smoothing * (target - steering)
        val speed = (1f - centerProximity * (1f - config.minSpeedFactor))
            .coerceIn(config.minSpeedFactor, 1f)
        return when {
            abs(steering) < config.deadband -> AvoidanceCommand(AvoidanceAction.FORWARD, steering, speed, "clear")
            steering < 0f -> AvoidanceCommand(AvoidanceAction.LEFT, steering, speed, "veer left")
            else -> AvoidanceCommand(AvoidanceAction.RIGHT, steering, speed, "veer right")
        }
    }

    /**
     * Call periodically. Returns a STOP once when depth results stop arriving, so the robot does not
     * keep following an old steering command. Returns null while data is fresh or STOP was already sent.
     */
    fun checkStale(): AvoidanceCommand? {
        if (staleReported || clockMillis() - lastUpdateMillis < config.staleAfterMillis) return null
        staleReported = true
        return stop("no recent depth")
    }

    fun reset() {
        steering = 0f
        escapeSign = 0
        lastUpdateMillis = clockMillis()
        staleReported = false
    }

    /** 0 when the zone is at or beyond the influence range, 1 when at or inside the stop distance. */
    private fun proximity(distanceMeters: Float): Float =
        ((config.influenceMeters - distanceMeters) / (config.influenceMeters - config.stopMeters))
            .coerceIn(0f, 1f)

    /** +1 to escape right, -1 to escape left, 0 when neither side is usable. */
    private fun escapeDirection(left: Float?, right: Float?): Int {
        val sign = when {
            left == null && right == null -> 0
            left == null -> if (right!! >= config.stopMeters) 1 else 0
            right == null -> if (left >= config.stopMeters) -1 else 0
            else -> {
                val gap = right - left
                when {
                    abs(gap) >= config.escapeSwitchMeters -> if (gap > 0f) 1 else -1
                    // Nearly equal: keep the previous side so it does not flip-flop between frames.
                    escapeSign != 0 -> escapeSign
                    else -> if (gap >= 0f) 1 else -1
                }
            }
        }
        escapeSign = sign
        return sign
    }

    private fun stop(reason: String): AvoidanceCommand {
        steering = 0f
        return AvoidanceCommand(AvoidanceAction.STOP, 0f, 0f, reason)
    }

    private companion object {
        const val UNKNOWN_SIDE_PROXIMITY = 0.5f
    }
}
