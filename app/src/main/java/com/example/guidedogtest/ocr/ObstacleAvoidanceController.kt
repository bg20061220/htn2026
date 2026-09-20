package com.example.guidedogtest.ocr

import android.os.SystemClock
import com.example.guidedogtest.WheelSpeeds
import com.example.guidedogtest.MotorTuning
import kotlin.math.roundToInt

enum class AvoidanceState { IDLE, FORWARD, SLOW, TURN_LEFT, TURN_RIGHT, STOPPED }
enum class DesiredTravelDirection { FORWARD, LEFT, RIGHT, PIVOT_LEFT, PIVOT_RIGHT, STOP }

data class AvoidanceDecision(
    val state: AvoidanceState,
    val wheelSpeeds: WheelSpeeds,
    val action: String,
    val targetCorridorOffset: Float? = null,
)

/** Depth-driven local navigation. It produces wheel targets but never talks to a transport. */
class ObstacleAvoidanceController(
    private val clockMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    private var state = AvoidanceState.IDLE
    private var lastDepthTimestampNanos: Long? = null
    private var lastDepthArrivalMillis = Long.MIN_VALUE
    private var lastCorridorOffset: Float? = null

    fun update(
        scene: SceneAwarenessResult?,
        enabled: Boolean,
        cameraAvailable: Boolean,
        depthAvailable: Boolean,
        robotConnected: Boolean,
        desiredDirection: DesiredTravelDirection = DesiredTravelDirection.FORWARD,
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
        if (!scene.supportPlaneDetected || scene.cells.isEmpty()) return stop(reason = "STOP: SCENE UNKNOWN")

        return try {
            decide(scene, desiredDirection)
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

    private fun decide(scene: SceneAwarenessResult, desiredDirection: DesiredTravelDirection): AvoidanceDecision {
        if (desiredDirection == DesiredTravelDirection.STOP) return stop(reason = "STOP: ROUTE HOLD")
        if (scene.dropDetected || scene.cells.any { it.state == OccupancyState.DROP }) return stop(reason = "STOP: DROP")
        val corridor = chooseCorridor(scene, desiredDirection) ?: return stop(reason = "STOP: NO SAFE CORRIDOR")
        val corridorOffset = corridor.centerOffset.coerceIn(-1f, 1f)
        lastCorridorOffset = corridorOffset
        if (desiredDirection == DesiredTravelDirection.PIVOT_LEFT && scene.centerState != SceneZoneState.BLOCKED) return pivotLeft(corridorOffset)
        if (desiredDirection == DesiredTravelDirection.PIVOT_RIGHT && scene.centerState != SceneZoneState.BLOCKED) return pivotRight(corridorOffset)
        val routeBias = when (desiredDirection) {
            DesiredTravelDirection.LEFT -> -ROUTE_STEERING_BIAS
            DesiredTravelDirection.RIGHT -> ROUTE_STEERING_BIAS
            else -> 0f
        }
        val offset = (corridorOffset + routeBias).coerceIn(-1f, 1f)
        val centered = kotlin.math.abs(offset) <= CENTER_DEADBAND
        if (centered && scene.centerState == SceneZoneState.CLEAR) {
            state = AvoidanceState.FORWARD
            return AvoidanceDecision(state, wheels(AUTO_CRUISE_POWER, AUTO_CRUISE_POWER - AUTO_RIGHT_TRIM), "FORWARD", offset)
        }

        // A large lateral target needs a pivot. The direction remains latched while its corridor
        // remains on that side, preventing frame-to-frame left/right hunting.
        if (kotlin.math.abs(offset) >= PIVOT_OFFSET_THRESHOLD || scene.centerState == SceneZoneState.BLOCKED) {
            if (state == AvoidanceState.TURN_LEFT && offset <= -CENTER_DEADBAND) return pivotLeft(offset)
            if (state == AvoidanceState.TURN_RIGHT && offset >= CENTER_DEADBAND) return pivotRight(offset)
            return if (offset < 0f) pivotLeft(offset) else pivotRight(offset)
        }

        state = AvoidanceState.SLOW
        val bias = (kotlin.math.abs(offset) * MAX_SMOOTH_STEERING_BIAS).roundToInt()
            .coerceIn(MIN_SMOOTH_STEERING_BIAS, MAX_SMOOTH_STEERING_BIAS)
        return when {
            offset < -CENTER_DEADBAND -> AvoidanceDecision(state, wheels(AUTO_CAUTION_POWER - bias, AUTO_CAUTION_POWER), "STEER LEFT", offset)
            offset > CENTER_DEADBAND -> AvoidanceDecision(state, wheels(AUTO_CAUTION_POWER, AUTO_CAUTION_POWER - bias), "STEER RIGHT", offset)
            else -> AvoidanceDecision(state, wheels(AUTO_CAUTION_POWER, AUTO_CAUTION_POWER - AUTO_SLOW_RIGHT_TRIM), "SLOW FORWARD", offset)
        }
    }

    private fun chooseCorridor(scene: SceneAwarenessResult, desired: DesiredTravelDirection): FreeCorridor? {
        val candidates = scene.freeCorridors.ifEmpty { listOfNotNull(scene.freeCorridor) }
        if (candidates.isEmpty()) return null
        val target = when (desired) {
            DesiredTravelDirection.LEFT, DesiredTravelDirection.PIVOT_LEFT -> -ROUTE_TURN_TARGET_OFFSET
            DesiredTravelDirection.RIGHT, DesiredTravelDirection.PIVOT_RIGHT -> ROUTE_TURN_TARGET_OFFSET
            else -> 0f
        }
        fun score(corridor: FreeCorridor): Float =
            kotlin.math.abs(corridor.centerOffset - target) -
                corridor.widthColumns * WIDTH_SCORE_BONUS - corridor.clearanceMeters * CLEARANCE_SCORE_BONUS
        val best = candidates.minByOrNull(::score) ?: return null
        val previous = lastCorridorOffset ?: return best
        val latched = candidates.filter { it.centerOffset * previous > 0f }.minByOrNull(::score)
        return if (latched != null && score(latched) <= score(best) + CORRIDOR_SWITCH_HYSTERESIS) latched else best
    }

    private fun pivotLeft(offset: Float): AvoidanceDecision {
        state = AvoidanceState.TURN_LEFT
        return AvoidanceDecision(state, wheels(-AUTO_PIVOT_POWER, AUTO_PIVOT_POWER), "PIVOT LEFT", offset)
    }

    private fun pivotRight(offset: Float): AvoidanceDecision {
        state = AvoidanceState.TURN_RIGHT
        return AvoidanceDecision(state, wheels(AUTO_PIVOT_POWER, -AUTO_PIVOT_POWER), "PIVOT RIGHT", offset)
    }

    private fun stop(newState: AvoidanceState = AvoidanceState.STOPPED, reason: String): AvoidanceDecision {
        state = newState
        return AvoidanceDecision(state, WheelSpeeds(0, 0), reason)
    }

    private fun wheels(left: Int, right: Int) = WheelSpeeds(
        MotorTuning.enforceMinimumLeft(left),
        MotorTuning.enforceMinimumRight(right),
    )

    companion object {
        const val AUTO_CRUISE_POWER = 145
        const val AUTO_CAUTION_POWER = 130
        const val AUTO_TURN_POWER = 140
        const val AUTO_PIVOT_POWER = 190
        const val AUTO_FORWARD_SPEED = AUTO_CRUISE_POWER
        const val AUTO_SLOW_SPEED = AUTO_CAUTION_POWER
        const val AUTO_TURN_SPEED = AUTO_TURN_POWER
        const val MIN_EFFECTIVE_MOTOR_SPEED = MotorTuning.MIN_EFFECTIVE_MOTOR_SPEED
        const val BLOCKED_DISTANCE_METERS = 0.8f
        const val CAUTION_DISTANCE_METERS = 1.5f
        const val SCENE_STALE_TIMEOUT_MS = 1_000L
        private const val AUTO_RIGHT_TRIM = 25
        private const val AUTO_SLOW_RIGHT_TRIM = 15
        const val STEERING_BIAS = 20
        private const val CENTER_DEADBAND = 0.12f
        private const val PIVOT_OFFSET_THRESHOLD = 0.55f
        private const val MIN_SMOOTH_STEERING_BIAS = 8
        private const val MAX_SMOOTH_STEERING_BIAS = 45
        private const val ROUTE_TURN_TARGET_OFFSET = 0.65f
        private const val ROUTE_STEERING_BIAS = 0.22f
        private const val WIDTH_SCORE_BONUS = 0.025f
        private const val CLEARANCE_SCORE_BONUS = 0.05f
        private const val CORRIDOR_SWITCH_HYSTERESIS = 0.15f
    }
}
