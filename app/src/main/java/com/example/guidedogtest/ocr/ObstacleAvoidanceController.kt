package com.example.guidedogtest.ocr

import android.os.SystemClock
import com.example.guidedogtest.MotorSettings
import com.example.guidedogtest.WheelSpeeds
import com.example.guidedogtest.MotorTuning
import kotlin.math.roundToInt

enum class AvoidanceState { IDLE, FORWARD, SLOW, TURN_LEFT, TURN_RIGHT, STOPPED }
enum class DesiredTravelDirection { FORWARD, LEFT, RIGHT, PIVOT_LEFT, PIVOT_RIGHT, STOP }

/**
 * Why the controller is not letting the car drive, when it is not.
 *
 * [OBSTACLE] is the ordinary case and the one the walker hears: something is in the middle box.
 * [UNSAFE] is "I cannot do this at all" - there is no link to the car. [SENSING_UNAVAILABLE] is kept
 * for a caller that has lost its own camera. [NONE] means the controller has no opinion and whoever
 * is driving goes on driving.
 */
enum class AvoidanceStop { NONE, OBSTACLE, SENSING_UNAVAILABLE, UNSAFE }

data class AvoidanceDecision(
    val state: AvoidanceState,
    val wheelSpeeds: WheelSpeeds,
    val action: String,
    val offMiddle: Float? = null,
    /** Signed steering to lay on a driving pair while there is a way past: positive turns right. */
    val steeringBias: Int = 0,
    /** [AvoidanceStop.NONE] unless the controller is refusing to let the car drive. */
    val stopReason: AvoidanceStop = AvoidanceStop.NONE,
    /**
     * False while the robot is driving on the command alone because nothing usable is in the frame.
     * The motors do not care, but the walker and the panel are told.
     */
    val obstacleSensingAvailable: Boolean = true,
)

/**
 * Depth-driven local navigation. It produces wheel targets but never talks to a transport.
 *
 * Every wheel pair is derived from the calibration on the Configure Robot page ([MotorSettings]):
 * this layer has no PWM numbers of its own, so the numbers a route drives and the numbers a detour
 * drives come from the one calibration the team tunes on the floor.
 */
class ObstacleAvoidanceController(
    private val clockMillis: () -> Long = SystemClock::elapsedRealtime,
    private val tuning: () -> MotorSettings = { MotorSettings() },
) {
    private var state = AvoidanceState.IDLE
    private var lastDepthTimestampNanos: Long? = null
    private var lastDepthArrivalMillis = Long.MIN_VALUE

    /**
     * Set while the robot is stopped for something in the middle box, and cleared only by a fresh
     * command ([releaseObstacleStop]) - not by the next frame.
     *
     * It is what makes the stop mean something on the floor. A box flickers: a step past a doorway, a
     * person shifting their weight, ARCore's depth moving by centimetres on a flat wall. Resuming on
     * the next frame that reads clear would stutter the robot forwards and backwards across the room.
     * It also answers the one thing that would otherwise restart the robot by itself: depth-from-motion
     * produces nothing while the robot stands still, so a stationary robot's depth goes stale, and a
     * stale frame must not be read as a clear road.
     */
    private var obstacleStop = false


    /** What the robot is currently doing, and since when, so a flickering box cannot take the wheel. */
    private var committedState = AvoidanceState.IDLE
    private var committedAtMillis = Long.MIN_VALUE



    /**
     * What to do, from the three boxes - the rule the team asked for, in its own words:
     *
     *  1. something in the **middle** box (yellow or red) -> **stop**, and say so;
     *  2. an empty middle box -> **drive on**, on the calibrated straight pair;
     *  3. nothing usable in the frame -> do what was asked, boxes or no boxes.
     *
     * That is the whole strategy. There is no turning in it: the side boxes are not consulted, because
     * "which side has room" is a second question for the depth model to get right and getting it wrong
     * steers the robot into a wall. Obstacle detection now has exactly one vote - stop or go - and it
     * cannot veto starting, cannot steer, and cannot refuse because it is still waking up.
     *
     * A turn is therefore only ever something that was *asked* for: a pivot from a route or a spoken
     * command, or the follower's lean while it holds a bearing. The obstacle layer never turns.
     */
    fun update(
        scene: SceneAwarenessResult?,
        enabled: Boolean,
        cameraAvailable: Boolean,
        depthAvailable: Boolean,
        robotConnected: Boolean,
        desiredDirection: DesiredTravelDirection = DesiredTravelDirection.FORWARD,
    ): AvoidanceDecision {
        if (!enabled) {
            obstacleStop = false
            return stop(AvoidanceState.IDLE, "AUTO OFF")
        }
        if (!robotConnected) return stop(reason = "STOP: ROBOT DISCONNECTED", stopReason = AvoidanceStop.UNSAFE)
        if (desiredDirection == DesiredTravelDirection.STOP) return stop(reason = "STOP: ROUTE HOLD")

        // A turn that was asked for is not an obstacle decision and is never blocked by one: pivoting
        // in place cannot run into anything, and it is how the robot gets out of a corner.
        when (desiredDirection) {
            DesiredTravelDirection.PIVOT_LEFT -> return pivot(SceneZone.LEFT, scene)
            DesiredTravelDirection.PIVOT_RIGHT -> return pivot(SceneZone.RIGHT, scene)
            else -> Unit
        }

        // Held until a new command: see [obstacleStop].
        if (obstacleStop) return stop(reason = "STOP: OBSTACLE AHEAD", stopReason = AvoidanceStop.OBSTACLE)

        val now = clockMillis()
        val sceneUsable = scene != null &&
            scene.depthTimestampNanos != null &&
            scene.supportPlaneDetected &&
            !scene.seesNothing
        // A frame at a new timestamp is what counts as seeing: the arrival clock starts on the first
        // usable one, not when the controller was built.
        if (scene != null && scene.depthTimestampNanos != null && scene.depthTimestampNanos != lastDepthTimestampNanos) {
            lastDepthTimestampNanos = scene.depthTimestampNanos
            lastDepthArrivalMillis = now
        }
        val stale = lastDepthArrivalMillis == Long.MIN_VALUE ||
            now - lastDepthArrivalMillis > SCENE_STALE_TIMEOUT_MS

        // No usable picture - camera not up, depth not warmed up, no frame, a stale frame, no floor
        // model, every box unreadable - is one case with one answer: do what was asked and let the
        // boxes load. Refusing until the depth module was ready was a deadlock (depth-from-motion needs
        // the robot to move, and the robot waited for depth), and a robot that does not move when told
        // to is worse than one that is not watching.
        if (!cameraAvailable || !depthAvailable || !sceneUsable || stale) {
            return openLoop(scene, desiredDirection)
        }

        if (scene.stateOf(SceneZone.CENTER) != SceneZoneState.CLEAR) {
            obstacleStop = true
            return stop(
                reason = "STOP: OBSTACLE AHEAD",
                stopReason = AvoidanceStop.OBSTACLE,
                offMiddle = scene.offMiddle,
            )
        }

        // The one steering left: the follower holding a bearing. It is not the obstacle layer's.
        if (desiredDirection == DesiredTravelDirection.LEFT || desiredDirection == DesiredTravelDirection.RIGHT) {
            return lean(desiredDirection)
        }
        return drive(scene)
    }

    /**
     * Clears a latched stop so the next frame is judged afresh.
     *
     * Every fresh thing the walker does goes through here - a spoken "go forward", the AUTO switch
     * coming on, a page button - so that a stop is answerable: the robot stops, the walker looks, and
     * "go on" is what starts it again.
     */
    fun releaseObstacleStop() {
        obstacleStop = false
        lastDepthArrivalMillis = Long.MIN_VALUE
        lastDepthTimestampNanos = null
    }

    fun watchdog(
        enabled: Boolean,
        cameraAvailable: Boolean,
        depthAvailable: Boolean,
        robotConnected: Boolean,
    ): AvoidanceDecision? {
        if (!enabled) return null
        return when {
            !robotConnected -> stop(reason = "STOP: ROBOT DISCONNECTED", stopReason = AvoidanceStop.UNSAFE)
            obstacleStop -> stop(reason = "STOP: OBSTACLE AHEAD", stopReason = AvoidanceStop.OBSTACLE)
            else -> null
        }
    }

    /**
     * The action that was asked for, with nothing in the frame worth judging it against.
     *
     * Forward on the tuned pair, or a creep-and-steer for a route lean, open loop, ignoring the frames
     * that read nothing. `obstacleSensingAvailable` is false so the walker and the panel are told
     * obstacle detection is not on yet; the moment a box loads, the rule in [update] takes over.
     */
    private fun openLoop(scene: SceneAwarenessResult?, desiredDirection: DesiredTravelDirection): AvoidanceDecision {
        val commanded = drive(scene)
        val steering = when (desiredDirection) {
            DesiredTravelDirection.LEFT -> -ROUTE_STEERING_BIAS_PWM
            DesiredTravelDirection.RIGHT -> ROUTE_STEERING_BIAS_PWM
            else -> 0
        }
        if (steering == 0) return commanded.copy(obstacleSensingAvailable = false)
        state = AvoidanceState.SLOW
        return AvoidanceDecision(
            state = state,
            wheelSpeeds = MotorTuning.steered(slowPair(), steering),
            action = if (steering > 0) "STEER RIGHT (no depth)" else "STEER LEFT (no depth)",
            offMiddle = scene?.offMiddle,
            steeringBias = steering,
            obstacleSensingAvailable = false,
        )
    }

    /** Driving on: the calibrated straight pair, exactly as the bench tuned it. */
    private fun drive(scene: SceneAwarenessResult? = null): AvoidanceDecision {
        state = AvoidanceState.FORWARD
        return AvoidanceDecision(state, cruisePair(), "FORWARD", scene?.offMiddle)
    }

    /**
     * The follower holding a bearing: creep, and lean that way.
     *
     * This is the only steering the depth layer has left, and it is not the depth layer's - the
     * direction comes from the route, never from a box.
     */
    private fun lean(desiredDirection: DesiredTravelDirection): AvoidanceDecision {
        state = AvoidanceState.SLOW
        val bias = routeIntent(desiredDirection)
        val action = if (bias < 0) "STEER LEFT" else "STEER RIGHT"
        return AvoidanceDecision(state, MotorTuning.steered(slowPair(), bias), action, steeringBias = bias)
    }

    /** How hard a route, or a spoken turn, wants the robot to lean: a fixed, visible nudge. */
    private fun routeIntent(desiredDirection: DesiredTravelDirection): Int {
        val magnitude = (ROUTE_STEERING_BIAS * MAX_SMOOTH_STEERING_BIAS).roundToInt()
            .coerceIn(MIN_SMOOTH_STEERING_BIAS, MAX_SMOOTH_STEERING_BIAS)
        return when (desiredDirection) {
            DesiredTravelDirection.LEFT -> -magnitude
            DesiredTravelDirection.RIGHT -> magnitude
            else -> 0
        }
    }

    /**
     * A turn that was asked for, in place, on the pair the page has for that direction: a pivot is not
     * the other one mirrored.
     *
     * At the tuned pair, not a fraction of it: a weaker pivot does not bring this chassis round at all.
     * This is the only turning left in the layer, and nothing about it reads a box.
     */
    private fun pivot(zone: SceneZone, scene: SceneAwarenessResult? = null): AvoidanceDecision {
        val left = zone == SceneZone.LEFT
        val turn = if (left) tuning().left else tuning().right
        state = if (left) AvoidanceState.TURN_LEFT else AvoidanceState.TURN_RIGHT
        return AvoidanceDecision(
            state = state,
            wheelSpeeds = MotorTuning.pivotPair(turn),
            action = if (left) "PIVOT LEFT" else "PIVOT RIGHT",
            offMiddle = scene?.offMiddle,
        )
    }

    /** Full stop. The wheels are commanded 0,0 whatever was being driven. */
    private fun stop(
        newState: AvoidanceState = AvoidanceState.STOPPED,
        reason: String,
        stopReason: AvoidanceStop = AvoidanceStop.NONE,
        offMiddle: Float? = null,
    ): AvoidanceDecision {
        state = newState
        return AvoidanceDecision(state, WheelSpeeds(0, 0), reason, offMiddle = offMiddle, stopReason = stopReason)
    }

    private fun wheels(left: Int, right: Int) = WheelSpeeds(
        MotorTuning.enforceMinimumLeft(left),
        MotorTuning.enforceMinimumRight(right),
    )

    /** The calibrated straight pair: what a route drives, and what avoidance cruises at. */
    private fun cruisePair(): WheelSpeeds = wheels(tuning().forward.left, tuning().forward.right)

    /**
     * The same pair [AUTO_SLOW_FRACTION] slower. Both sides are scaled, so the calibration's
     * left/right ratio survives and slower is still straight. A pair that is already down at the
     * motor floor cannot be scaled without breaking that ratio, so it is used unscaled.
     */
    private fun slowPair(): WheelSpeeds {
        val forward = tuning().forward
        val left = (forward.left * AUTO_SLOW_FRACTION).roundToInt()
        val right = (forward.right * AUTO_SLOW_FRACTION).roundToInt()
        val aboveFloor = left >= MotorTuning.MIN_EFFECTIVE_LEFT_POWER &&
            right >= MotorTuning.MIN_EFFECTIVE_RIGHT_POWER
        return if (aboveFloor) wheels(left, right) else wheels(forward.left, forward.right)
    }

    companion object {
        const val MIN_EFFECTIVE_MOTOR_SPEED = MotorTuning.MIN_EFFECTIVE_MOTOR_SPEED
        const val SCENE_STALE_TIMEOUT_MS = 1_000L

        /** How much of the calibrated forward pair the cautious state commands. */
        private const val AUTO_SLOW_FRACTION = 0.88f

        private const val MIN_SMOOTH_STEERING_BIAS = 8
        private const val MAX_SMOOTH_STEERING_BIAS = 45
        private const val ROUTE_STEERING_BIAS = 0.22f

        /** The same route lean in PWM, for the open-loop case where there is no box to judge. */
        private const val ROUTE_STEERING_BIAS_PWM = 14
    }
}
