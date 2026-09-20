package com.example.guidedogtest.ocr

import android.os.SystemClock
import com.example.guidedogtest.WheelSpeeds

enum class AvoidanceState { IDLE, FORWARD, SLOW, TURN_LEFT, TURN_RIGHT, STOPPED }

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

    fun update(
        scene: SceneAwarenessResult?,
        enabled: Boolean,
        cameraAvailable: Boolean,
        depthAvailable: Boolean,
        robotConnected: Boolean,
    ): AvoidanceDecision {
        if (!enabled) return stop(AvoidanceState.IDLE, "AUTO OFF")
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
                AvoidanceDecision(state, WheelSpeeds(AUTO_FORWARD_SPEED, AUTO_FORWARD_SPEED - AUTO_RIGHT_TRIM), "FORWARD")
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
