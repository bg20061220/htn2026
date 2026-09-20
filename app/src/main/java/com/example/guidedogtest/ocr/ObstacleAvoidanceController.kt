package com.example.guidedogtest.ocr

import android.os.SystemClock
import com.example.guidedogtest.WheelSpeeds

enum class AvoidanceState { IDLE, CREEP, FORWARD, SLOW, TURN_LEFT, TURN_RIGHT, STOPPED }

data class AvoidanceDecision(
    val state: AvoidanceState,
    val wheelSpeeds: WheelSpeeds,
    val action: String,
)

/** Depth-driven local navigation. It produces wheel targets but never talks to a transport. */
class ObstacleAvoidanceController(
    private val clockMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    private var state = AvoidanceState.IDLE
    private var lastDepthTimestampNanos: Long? = null
    private var lastDepthArrivalMillis = Long.MIN_VALUE
    private var armed = false
    private var creepPending = false

    /**
     * End of the opening creep, or null while it has not begun. The clock starts on the first frame
     * the robot is actually allowed to move, not when avoidance is armed: depth takes a moment to
     * come up, and a creep that expires while the robot is still held stopped never happens at all.
     */
    private var creepUntilMillis: Long? = null

    fun update(
        scene: SceneAwarenessResult?,
        enabled: Boolean,
        cameraAvailable: Boolean,
        depthAvailable: Boolean,
        robotConnected: Boolean,
    ): AvoidanceDecision {
        if (!enabled) {
            armed = false
            return stop(AvoidanceState.IDLE, "AUTO OFF")
        }
        if (!armed) {
            armed = true
            creepPending = true
            creepUntilMillis = null
            state = AvoidanceState.CREEP
        }
        if (!robotConnected) return stop(reason = "STOP: ROBOT DISCONNECTED")
        if (!cameraAvailable) return stop(reason = "STOP: CAMERA UNAVAILABLE")
        if (!depthAvailable || scene?.depthTimestampNanos == null) {
            return stop(reason = "STOP: DEPTH UNAVAILABLE")
        }

        val now = clockMillis()
        if (scene.depthTimestampNanos != lastDepthTimestampNanos) {
            lastDepthTimestampNanos = scene.depthTimestampNanos
            lastDepthArrivalMillis = now
        }
        if (lastDepthArrivalMillis == Long.MIN_VALUE || now - lastDepthArrivalMillis > SCENE_STALE_TIMEOUT_MS) {
            return stop(reason = "STOP: DEPTH STALE")
        }
        if (scene.leftState == SceneZoneState.UNKNOWN ||
            scene.centerState == SceneZoneState.UNKNOWN ||
            scene.rightState == SceneZoneState.UNKNOWN
        ) return stop(reason = "STOP: SCENE UNKNOWN")

        return try {
            decide(scene)
        } catch (_: Exception) {
            stop(reason = "STOP: CONTROLLER ERROR")
        }
    }

    fun watchdog(
        enabled: Boolean,
        cameraAvailable: Boolean,
        depthAvailable: Boolean,
        robotConnected: Boolean,
    ): AvoidanceDecision? {
        if (!enabled) return null
        val now = clockMillis()
        return when {
            !robotConnected -> stop(reason = "STOP: ROBOT DISCONNECTED")
            !cameraAvailable -> stop(reason = "STOP: CAMERA UNAVAILABLE")
            !depthAvailable -> stop(reason = "STOP: DEPTH UNAVAILABLE")
            lastDepthArrivalMillis == Long.MIN_VALUE || now - lastDepthArrivalMillis > SCENE_STALE_TIMEOUT_MS ->
                stop(reason = "STOP: DEPTH STALE")
            else -> null
        }
    }

    private fun decide(scene: SceneAwarenessResult): AvoidanceDecision {
        val center = scene.centerDistanceMeters ?: return stop(reason = "STOP: SCENE UNKNOWN")
        val left = scene.leftDistanceMeters ?: return stop(reason = "STOP: SCENE UNKNOWN")
        val right = scene.rightDistanceMeters ?: return stop(reason = "STOP: SCENE UNKNOWN")

        // Opening move: ease forward in a straight line for a moment so the robot visibly sets off
        // before it starts hunting for the open side. Anything blocking the path cancels it outright
        // - the creep is a nicety, and it never outranks the corridor being closed.
        //
        // This is a flag rather than a check on [state] because every safety gate above runs through
        // stop(), which overwrites the state: a robot that armed before depth came up would arrive
        // here as STOPPED and skip the creep it never got to take.
        if (creepPending) {
            val until = creepUntilMillis ?: (clockMillis() + CREEP_DURATION_MS).also { creepUntilMillis = it }
            if (scene.centerState == SceneZoneState.BLOCKED || clockMillis() >= until) {
                creepPending = false
            } else {
                state = AvoidanceState.CREEP
                return AvoidanceDecision(
                    state,
                    WheelSpeeds(AUTO_CREEP_SPEED, AUTO_CREEP_SPEED - AUTO_CREEP_RIGHT_TRIM),
                    "CREEP FORWARD",
                )
            }
        }

        // Hold the selected side until the forward corridor is genuinely clear. Switch only when
        // that side becomes blocked and the opposite side is usable, preventing left/right chatter.
        if (state == AvoidanceState.TURN_LEFT || state == AvoidanceState.TURN_RIGHT) {
            if (center > CLEAR_RESUME_DISTANCE_METERS && scene.centerState == SceneZoneState.CLEAR) {
                state = AvoidanceState.SLOW
                return slowToward(left, right)
            }
            if (state == AvoidanceState.TURN_LEFT && scene.leftState != SceneZoneState.BLOCKED) return turnLeft()
            if (state == AvoidanceState.TURN_RIGHT && scene.rightState != SceneZoneState.BLOCKED) return turnRight()
            if (state == AvoidanceState.TURN_LEFT && scene.rightState != SceneZoneState.BLOCKED) return turnRight()
            if (state == AvoidanceState.TURN_RIGHT && scene.leftState != SceneZoneState.BLOCKED) return turnLeft()
            return stop(reason = "STOP: BOTH SIDES BLOCKED")
        }

        return when (scene.centerState) {
            SceneZoneState.CLEAR -> {
                state = AvoidanceState.FORWARD
                forwardToward(left, right)
            }
            SceneZoneState.CAUTION -> {
                state = AvoidanceState.SLOW
                slowToward(left, right)
            }
            SceneZoneState.BLOCKED -> chooseTurn(scene, left, right)
            SceneZoneState.UNKNOWN -> stop(reason = "STOP: SCENE UNKNOWN")
        }
    }

    private fun chooseTurn(scene: SceneAwarenessResult, left: Float, right: Float): AvoidanceDecision {
        if (scene.leftState == SceneZoneState.BLOCKED && scene.rightState == SceneZoneState.BLOCKED) {
            return stop(reason = "STOP: BOTH SIDES BLOCKED")
        }
        return if (left >= right) turnLeft() else turnRight()
    }

    /**
     * Driving with the corridor open: hold speed, but lean toward whichever side has more room.
     *
     * The lean only engages once something is actually near enough to matter. In an open space the
     * two sides differ by metres of pure depth noise, and veering at every frame toward the larger
     * number would curve the robot around in circles instead of driving it down the middle.
     */
    private fun forwardToward(left: Float, right: Float): AvoidanceDecision {
        val straight = WheelSpeeds(AUTO_FORWARD_SPEED, AUTO_FORWARD_SPEED - AUTO_RIGHT_TRIM)
        if (minOf(left, right) > STEER_INFLUENCE_METERS) {
            return AvoidanceDecision(state, straight, "FORWARD")
        }
        return if (left > right + SIDE_SWITCH_HYSTERESIS_METERS) {
            AvoidanceDecision(
                state,
                WheelSpeeds(AUTO_FORWARD_SPEED - AUTO_STEER_BIAS, AUTO_FORWARD_SPEED - AUTO_RIGHT_TRIM),
                "FORWARD / VEER LEFT",
            )
        } else if (right > left + SIDE_SWITCH_HYSTERESIS_METERS) {
            AvoidanceDecision(
                state,
                WheelSpeeds(AUTO_FORWARD_SPEED, AUTO_FORWARD_SPEED - AUTO_RIGHT_TRIM - AUTO_STEER_BIAS),
                "FORWARD / VEER RIGHT",
            )
        } else {
            AvoidanceDecision(state, straight, "FORWARD")
        }
    }

    private fun slowToward(left: Float, right: Float): AvoidanceDecision {
        val bias = AUTO_STEER_BIAS
        return if (left > right + SIDE_SWITCH_HYSTERESIS_METERS) {
            AvoidanceDecision(state, WheelSpeeds(AUTO_SLOW_SPEED - bias, AUTO_SLOW_SPEED), "SLOW / STEER LEFT")
        } else if (right > left + SIDE_SWITCH_HYSTERESIS_METERS) {
            AvoidanceDecision(state, WheelSpeeds(AUTO_SLOW_SPEED, AUTO_SLOW_SPEED - bias), "SLOW / STEER RIGHT")
        } else {
            AvoidanceDecision(state, WheelSpeeds(AUTO_SLOW_SPEED, AUTO_SLOW_SPEED - AUTO_SLOW_RIGHT_TRIM), "SLOW FORWARD")
        }
    }

    private fun turnLeft(): AvoidanceDecision {
        state = AvoidanceState.TURN_LEFT
        return AvoidanceDecision(state, WheelSpeeds(-AUTO_TURN_SPEED, AUTO_TURN_SPEED), "TURN LEFT")
    }

    private fun turnRight(): AvoidanceDecision {
        state = AvoidanceState.TURN_RIGHT
        return AvoidanceDecision(state, WheelSpeeds(AUTO_TURN_SPEED, -AUTO_TURN_SPEED), "TURN RIGHT")
    }

    private fun stop(newState: AvoidanceState = AvoidanceState.STOPPED, reason: String): AvoidanceDecision {
        state = newState
        return AvoidanceDecision(state, WheelSpeeds(0, 0), reason)
    }

    companion object {
        const val AUTO_FORWARD_SPEED = 110
        const val AUTO_SLOW_SPEED = 75
        const val AUTO_TURN_SPEED = 85

        /**
         * The opening creep. Deliberately the same PWM as [AUTO_SLOW_SPEED], which is the slowest
         * pair this chassis is known to actually roll at - drop it much further and the motors sit
         * under their stall threshold and buzz without turning a wheel.
         */
        const val AUTO_CREEP_SPEED = 75
        const val CREEP_DURATION_MS = 1_200L
        private const val AUTO_CREEP_RIGHT_TRIM = 12

        /** Something must be at least this near before the lean engages; past it, drive straight. */
        private const val STEER_INFLUENCE_METERS = 1.5f
        const val BLOCKED_DISTANCE_METERS = 0.8f
        const val CAUTION_DISTANCE_METERS = 1.5f
        const val SCENE_STALE_TIMEOUT_MS = 1_000L
        private const val CLEAR_RESUME_DISTANCE_METERS = 1.65f
        private const val SIDE_SWITCH_HYSTERESIS_METERS = 0.20f
        private const val AUTO_RIGHT_TRIM = 20
        private const val AUTO_SLOW_RIGHT_TRIM = 12
        private const val AUTO_STEER_BIAS = 20
    }
}
