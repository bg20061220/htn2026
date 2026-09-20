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
 * "I can see and it is not safe" and "I cannot see" are different facts and the caller has to tell
 * them apart: the walker is told which one it is, in words, because a stop with no explanation is
 * indistinguishable from a dead robot. Both stop the car - a depth feed that has gone quiet is not a
 * clear road - and [AvoidanceStop.NONE] means the controller has no opinion and whoever is driving
 * goes on driving.
 */
enum class AvoidanceStop { NONE, SENSING_UNAVAILABLE, UNSAFE }

data class AvoidanceDecision(
    val state: AvoidanceState,
    val wheelSpeeds: WheelSpeeds,
    val action: String,
    val offMiddle: Float? = null,
    /** Signed steering to lay on a driving pair while there is a way past: positive turns right. */
    val steeringBias: Int = 0,
    /** [AvoidanceStop.NONE] unless the controller is refusing to let the car drive. */
    val stopReason: AvoidanceStop = AvoidanceStop.NONE,
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
     * The box the robot is currently turning into, held while the middle box stays blocked.
     *
     * Without it two nearly equal side readings would flip the chosen side frame to frame and the
     * robot would rock left and right instead of going around anything.
     */
    private var sideLatch: SceneZone? = null

    /** Swivel budget for a depth module that has not seen anything yet. */
    private var warmUpRemainingMillis = WARM_UP_TOTAL_MS
    private var warmUpSwingRemainingMillis = WARM_UP_SWING_MS
    private var warmUpSide = SceneZone.LEFT
    private var warmUpLastMillis = Long.MIN_VALUE

    /** What the robot is currently doing, and since when, so a flickering box cannot take the wheel. */
    private var committedState = AvoidanceState.IDLE
    private var committedAtMillis = Long.MIN_VALUE

    /** When a floor model was last fitted, so one bad frame is not treated as losing sight. */
    private var planeLastSeenMillis = Long.MIN_VALUE

    fun update(
        scene: SceneAwarenessResult?,
        enabled: Boolean,
        cameraAvailable: Boolean,
        depthAvailable: Boolean,
        robotConnected: Boolean,
        desiredDirection: DesiredTravelDirection = DesiredTravelDirection.FORWARD,
    ): AvoidanceDecision {
        if (!enabled) return stop(AvoidanceState.IDLE, "AUTO OFF")
        if (!robotConnected) return stop(reason = "STOP: ROBOT DISCONNECTED", stopReason = AvoidanceStop.UNSAFE)
        if (!cameraAvailable) return stop(reason = "STOP: CAMERA UNAVAILABLE", stopReason = AvoidanceStop.SENSING_UNAVAILABLE)
        if (!depthAvailable || scene?.depthTimestampNanos == null) {
            return stop(reason = "STOP: DEPTH UNAVAILABLE", stopReason = AvoidanceStop.SENSING_UNAVAILABLE)
        }

        val now = clockMillis()
        if (scene.depthTimestampNanos != lastDepthTimestampNanos) {
            lastDepthTimestampNanos = scene.depthTimestampNanos
            lastDepthArrivalMillis = now
        }
        val staleCheckNow = now
        if (lastDepthArrivalMillis == Long.MIN_VALUE ||
            staleCheckNow - lastDepthArrivalMillis > SCENE_STALE_TIMEOUT_MS
        ) {
            return stop(reason = "STOP: DEPTH STALE", stopReason = AvoidanceStop.SENSING_UNAVAILABLE)
        }
        if (!scene.supportPlaneDetected || scene.seesNothing) {
            // One unjudgeable frame is not losing sight. While the robot is driving and the floor model
            // was there a moment ago, it keeps driving on the decision it already had: depth flickers
            // (a flat wall, a person passing, a frame the module could not fill) and reacting to a
            // single frame is how the robot ends up stuttering forward a few centimetres at a time.
            val graceLeft = now - planeLastSeenMillis <= PLANE_LOSS_GRACE_MS
            if (graceLeft && isDriving(committedState)) {
                return if (committedState == AvoidanceState.FORWARD) drive(scene) else slow(scene, desiredDirection)
            }
            return warmUpSwivel(scene) ?: stop(
                reason = "STOP: SCENE UNKNOWN",
                stopReason = AvoidanceStop.SENSING_UNAVAILABLE,
            )
        }
        planeLastSeenMillis = now
        // A floor model is back: the next time it goes missing the robot gets a fresh swivel budget.
        warmUpRemainingMillis = WARM_UP_TOTAL_MS
        warmUpSwingRemainingMillis = WARM_UP_SWING_MS

        return try {
            decide(scene, desiredDirection)
        } catch (_: Exception) {
            stop(reason = "STOP: CONTROLLER ERROR", stopReason = AvoidanceStop.SENSING_UNAVAILABLE)
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
            !robotConnected -> stop(reason = "STOP: ROBOT DISCONNECTED", stopReason = AvoidanceStop.UNSAFE)
            !cameraAvailable -> stop(reason = "STOP: CAMERA UNAVAILABLE", stopReason = AvoidanceStop.SENSING_UNAVAILABLE)
            !depthAvailable -> stop(reason = "STOP: DEPTH UNAVAILABLE", stopReason = AvoidanceStop.SENSING_UNAVAILABLE)
            lastDepthArrivalMillis == Long.MIN_VALUE || now - lastDepthArrivalMillis > SCENE_STALE_TIMEOUT_MS ->
                stop(reason = "STOP: DEPTH STALE", stopReason = AvoidanceStop.SENSING_UNAVAILABLE)
            else -> null
        }
    }

    /**
     * What to do, from the three boxes - the rule the team asked for, in its own words:
     *
     *  1. the middle box is **green** -> drive on;
     *  2. otherwise, a side box that is **green or yellow** is somewhere to go -> turn into it, and
     *     hold that side while the middle stays red;
     *  3. **all three red** -> stop, and say so.
     *
     * Two cases the rule does not name, and what this does with them:
     *
     *  - middle **yellow**, both sides red: slow down and keep going. Something 1.5 m ahead is not a
     *    collision yet, and stopping at every caution would leave the robot standing still in any
     *    furnished room; the stop in (3) arrives as soon as the middle turns red.
     *  - middle **green** with a wall hard against one side: drive on, leaning away from the wall.
     *
     * A drop overrides all of it: a hole in the floor is never somewhere to go.
     */
    private fun decide(scene: SceneAwarenessResult, desiredDirection: DesiredTravelDirection): AvoidanceDecision =
        commit(decideRaw(scene, desiredDirection), scene, desiredDirection)

    private fun decideRaw(scene: SceneAwarenessResult, desiredDirection: DesiredTravelDirection): AvoidanceDecision {
        if (desiredDirection == DesiredTravelDirection.STOP) return stop(reason = "STOP: ROUTE HOLD")
        // Only a hole directly in front stops the car. One beside it closes that side and nothing
        // else - otherwise a drain cover on the left ends the walk.
        if (scene.centerDrop) return stop(reason = "STOP: DROP", stopReason = AvoidanceStop.UNSAFE)

        val centre = scene.stateOf(SceneZone.CENTER)
        val requested = when (desiredDirection) {
            DesiredTravelDirection.PIVOT_LEFT -> SceneZone.LEFT
            DesiredTravelDirection.PIVOT_RIGHT -> SceneZone.RIGHT
            else -> null
        }

        // 0. a route whose bearing is off, or a spoken "turn left/right": that is not an obstacle
        // decision. Creep and lean, which is what the follower got before the boxes existed.
        if (requested == null && centre != SceneZoneState.BLOCKED &&
            (desiredDirection == DesiredTravelDirection.LEFT || desiredDirection == DesiredTravelDirection.RIGHT)
        ) {
            return slow(scene, desiredDirection)
        }

        // 1. a green middle, and nobody asking for a turn: drive on.
        if (requested == null && centre == SceneZoneState.CLEAR) {
            sideLatch = null
            return drive(scene)
        }

        // 2. somewhere to go: the side box with the most room, if there is one.
        val side = chooseSide(scene, requested)
        if (side != null) {
            sideLatch = side
            // A turn that was asked for is a turn, and a red middle is something to go around; a
            // yellow middle is something to creep past.
            return if (requested != null || centre == SceneZoneState.BLOCKED) {
                pivot(side, scene)
            } else {
                slow(scene, desiredDirection)
            }
        }

        // 3. nothing but red where it matters: stop, and the speech manager says why.
        if (centre == SceneZoneState.BLOCKED) {
            return stop(reason = "STOP: NO SAFE PATH", stopReason = AvoidanceStop.UNSAFE)
        }
        sideLatch = null
        return slow(scene, desiredDirection)
    }

    /**
     * Holds the decision the robot is already carrying for [DECISION_HOLD_MS] before a *different* one
     * can replace it - except a stop, which is never held back, and except a red middle box, which is
     * the one thing that must take the wheel immediately.
     *
     * The boxes flicker: ARCore's depth moves by centimetres frame to frame on a flat wall, and a
     * person walking past changes a box for a single frame. With a decision every 125 ms that flicker
     * *is* the robot's behaviour - it starts forward, a side box reads red for one frame, it turns, the
     * turn changes the view, it starts forward again, and it crosses the room a hand's width at a time.
     * This is the time it gets to keep doing what it decided.
     */
    private fun commit(
        decision: AvoidanceDecision,
        scene: SceneAwarenessResult,
        desiredDirection: DesiredTravelDirection,
    ): AvoidanceDecision {
        val now = clockMillis()
        val driving = isDriving(decision.state)
        val heldLongEnough = now - committedAtMillis >= DECISION_HOLD_MS
        val middleClear = scene.stateOf(SceneZone.CENTER) != SceneZoneState.BLOCKED
        if (!driving && !heldLongEnough && middleClear && decision.stopReason == AvoidanceStop.NONE &&
            isDriving(committedState)
        ) {
            // Keep driving: the current decision is a turn or a slowdown that can wait, and what the
            // robot was doing is still safe - the middle box is clear.
            return if (committedState == AvoidanceState.FORWARD) {
                drive(scene)
            } else {
                slow(scene, desiredDirection)
            }
        }
        committedState = decision.state
        committedAtMillis = now
        return decision
    }

    private fun isDriving(state: AvoidanceState) =
        state == AvoidanceState.FORWARD || state == AvoidanceState.SLOW

    /**
     * Swivelling in place so the depth module has something to work with.
     *
     * ARCore's Depth API is depth-from-motion - on a phone without a ToF sensor it recovers depth from
     * the camera moving - so a robot that will not move until it has a floor model can never get one.
     * A spoken "stop" is the safe answer, but standing still forever is not a working guide dog.
     *
     * A pivot, not a creep: turning in place sweeps the camera along an arc, which is the parallax the
     * depth module needs, and it does not advance the robot into anything it cannot see. It alternates
     * sides so the robot sweeps rather than spins, it uses the same tuned turn pair the obstacle logic
     * uses (scaled down, because this is a nudge and not a manoeuvre), it refuses to swivel towards a
     * side whose raw distance says something is there, and it is strictly bounded - after
     * [WARM_UP_TOTAL_MS] it stops and says it cannot see, exactly as before.
     *
     * @return the swivel, or null when there is no room to swivel or the budget is spent.
     */
    private fun warmUpSwivel(scene: SceneAwarenessResult): AvoidanceDecision? {
        if (warmUpRemainingMillis <= 0L || warmUpSwingRemainingMillis <= 0L) return null

        // Raw distances, because there is no floor model to judge them against: anything measured
        // nearer than the robot's own width is something not to swing into.
        val leftClear = scene.leftDistanceMeters?.let { it >= WARM_UP_SIDE_MIN_METERS } ?: true
        val rightClear = scene.rightDistanceMeters?.let { it >= WARM_UP_SIDE_MIN_METERS } ?: true
        if (!leftClear && !rightClear) return null

        val side = when {
            !rightClear -> SceneZone.LEFT
            !leftClear -> SceneZone.RIGHT
            else -> warmUpSide
        }

        val now = clockMillis()
        val elapsed = if (warmUpLastMillis == Long.MIN_VALUE) 0L else (now - warmUpLastMillis).coerceAtLeast(0L)
        warmUpLastMillis = now
        warmUpRemainingMillis -= elapsed
        warmUpSwingRemainingMillis -= elapsed
        if (warmUpSwingRemainingMillis <= 0L) {
            // Sweep back the other way: parallax comes from the camera moving, and a sweep reads the
            // same scene from two sides.
            warmUpSide = if (side == SceneZone.LEFT) SceneZone.RIGHT else SceneZone.LEFT
            warmUpSwingRemainingMillis = WARM_UP_SWING_MS
            warmUpRemainingMillis -= WARM_UP_SWING_MS
        }

        val turn = if (side == SceneZone.LEFT) tuning().left else tuning().right
        state = if (side == SceneZone.LEFT) AvoidanceState.TURN_LEFT else AvoidanceState.TURN_RIGHT
        val wheels = MotorTuning.pivotPair(turn)
        // stopReason stays NONE on purpose: this is deliberate motion, not a refusal, so the arbiter
        // must let it through - the refusal is what happens when the budget runs out.
        return AvoidanceDecision(
            state = state,
            wheelSpeeds = wheels,
            action = if (side == SceneZone.LEFT) "WARM UP TURN LEFT" else "WARM UP TURN RIGHT",
            offMiddle = scene.offMiddle,
        )
    }

    /** Driving on: the cruise pair with the lateral correction (hallway middle, or off a wall). */
    private fun drive(scene: SceneAwarenessResult): AvoidanceDecision {
        state = AvoidanceState.FORWARD
        val lateral = lateralBias(scene)
        val action = when {
            lateral > 0 -> "CENTER RIGHT"
            lateral < 0 -> "CENTER LEFT"
            else -> "FORWARD"
        }
        // The lateral correction rides on the cruise pair, and the arbiter lays it on whatever pair a
        // route is driving, so a hallway holds its middle on a route as well as on the AUTO switch.
        return AvoidanceDecision(state, MotorTuning.steered(cruisePair(), lateral), action, scene.offMiddle, lateral)
    }

    /** Creeping past something: the cautious pair, the lateral correction, and any route intent. */
    private fun slow(scene: SceneAwarenessResult, desiredDirection: DesiredTravelDirection): AvoidanceDecision {
        state = AvoidanceState.SLOW
        val routeIntent = routeIntent(desiredDirection)
        val bias = (lateralBias(scene) + routeIntent)
            .coerceIn(-MAX_SMOOTH_STEERING_BIAS, MAX_SMOOTH_STEERING_BIAS)
        val action = when {
            desiredDirection == DesiredTravelDirection.LEFT -> "STEER LEFT"
            desiredDirection == DesiredTravelDirection.RIGHT -> "STEER RIGHT"
            bias > 0 -> "STEER RIGHT"
            bias < 0 -> "STEER LEFT"
            else -> "SLOW FORWARD"
        }
        return AvoidanceDecision(state, MotorTuning.steered(slowPair(), bias), action, scene.offMiddle, bias)
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
     * How hard to steer sideways while driving, signed like every other bias (positive is right).
     *
     * Two things ask for it, in this order: the middle of a hallway - the walls, with a deadband so a
     * slightly uneven one moves nothing - and, when there is no hallway to speak of, a side box that
     * has something in it while the other does not, which means the robot is hugging it.
     */
    private fun lateralBias(scene: SceneAwarenessResult): Int {
        val hallway = corridorCentringBias(scene)
        return if (hallway != 0) hallway else hugBias(scene)
    }

    /** One side box blocked (or holed) and the other not: move away from what is beside the robot. */
    private fun hugBias(scene: SceneAwarenessResult): Int {
        val leftBlocked = scene.stateOf(SceneZone.LEFT) == SceneZoneState.BLOCKED || scene.leftDrop
        val rightBlocked = scene.stateOf(SceneZone.RIGHT) == SceneZoneState.BLOCKED || scene.rightDrop
        return when {
            leftBlocked && !rightBlocked -> MIN_SMOOTH_STEERING_BIAS
            rightBlocked && !leftBlocked -> -MIN_SMOOTH_STEERING_BIAS
            else -> 0
        }
    }

    /**
     * Which box to turn into: the one that was asked for when it has room, otherwise the side held
     * from the previous frame while it still beats the other by [SIDE_HOLD_MARGIN_METERS], otherwise
     * whichever side measured more room. A side that is blocked, or that has no reading at all, is
     * never chosen while a measured side exists.
     */
    private fun chooseSide(scene: SceneAwarenessResult, preferred: SceneZone?): SceneZone? {
        preferred?.let { if (hasRoom(scene, it)) return it }
        val left = room(scene, SceneZone.LEFT)
        val right = room(scene, SceneZone.RIGHT)
        sideLatch?.let { latched ->
            val mine = if (latched == SceneZone.LEFT) left else right
            val other = if (latched == SceneZone.LEFT) right else left
            if (mine != Float.NEGATIVE_INFINITY && mine + SIDE_HOLD_MARGIN_METERS >= other) return latched
        }
        return when {
            left == Float.NEGATIVE_INFINITY && right == Float.NEGATIVE_INFINITY -> null
            right == Float.NEGATIVE_INFINITY -> SceneZone.LEFT
            left == Float.NEGATIVE_INFINITY -> SceneZone.RIGHT
            left >= right -> SceneZone.LEFT
            else -> SceneZone.RIGHT
        }
    }

    private fun hasRoom(scene: SceneAwarenessResult, zone: SceneZone): Boolean =
        scene.stateOf(zone).canTurnInto() && !scene.hasDrop(zone)

    /**
     * How much room a side box has, in the same units the recommendation uses: a box that held nothing
     * but floor is open (infinite room, so it wins over any wall) and a box that is blocked or
     * unreadable has none at all.
     */
    private fun room(scene: SceneAwarenessResult, zone: SceneZone): Float =
        if (!hasRoom(scene, zone)) {
            Float.NEGATIVE_INFINITY
        } else {
            zoneRoom(scene.stateOf(zone), scene.distanceOf(zone))
        }

    /** In place, on the pair the page has for that direction: a pivot is not the other one mirrored. */
    private fun pivot(zone: SceneZone, scene: SceneAwarenessResult): AvoidanceDecision {
        val left = zone == SceneZone.LEFT
        val turn = if (left) tuning().left else tuning().right
        state = if (left) AvoidanceState.TURN_LEFT else AvoidanceState.TURN_RIGHT
        return AvoidanceDecision(
            state = state,
            wheelSpeeds = MotorTuning.pivotPair(turn),
            action = if (left) "PIVOT LEFT" else "PIVOT RIGHT",
            offMiddle = scene.offMiddle,
        )
    }

    /** True when both sides are measured and near enough to be the walls of a hallway rather than a room. */
    private fun isCorridor(scene: SceneAwarenessResult): Boolean {
        val left = scene.leftDistanceMeters ?: return false
        val right = scene.rightDistanceMeters ?: return false
        return left <= CORRIDOR_SIDE_MAX_METERS && right <= CORRIDOR_SIDE_MAX_METERS
    }

    /**
     * How hard to steer towards the middle of the hallway, signed like every other bias (positive is
     * right), or 0 when the robot is already close enough to the middle that the difference is noise.
     *
     * The middle of a hallway is where the two wall distances are equal, and that is a continuous
     * measure - unlike the column grid, which quantises the same information into nine buckets and so
     * turns a few centimetres of a flat wall into a whole column of "the other side is wider".
     *
     * The deadband is the point of it: it is a fraction of the hallway's own width, capped, so a
     * corridor can be uneven by a little without the wheels moving at all, while a genuine offset
     * (the robot hugging one wall, or a doorway pulling the grid sideways) still gets a correction.
     * Below [CORRIDOR_DEADBAND_MIN_METERS] nothing is corrected no matter how narrow the corridor,
     * because at that size the two numbers are the walls' own noise.
     */
    private fun corridorCentringBias(scene: SceneAwarenessResult): Int {
        val left = scene.leftDistanceMeters ?: return 0
        val right = scene.rightDistanceMeters ?: return 0
        if (left > CORRIDOR_SIDE_MAX_METERS || right > CORRIDOR_SIDE_MAX_METERS) return 0

        val deadband = ((left + right) * CORRIDOR_DEADBAND_FRACTION)
            .coerceIn(CORRIDOR_DEADBAND_MIN_METERS, CORRIDOR_DEADBAND_MAX_METERS)
        val offCentre = right - left                 // more room on the right: move right
        val beyond = kotlin.math.abs(offCentre) - deadband
        if (beyond <= 0f) return 0

        val magnitude = (beyond * CORRIDOR_CENTERING_PWM_PER_METER).roundToInt()
            .coerceIn(1, MAX_SMOOTH_STEERING_BIAS)
        return if (offCentre > 0f) magnitude else -magnitude
    }

    /** In place, on the pair the page has for that direction: a pivot is not the other one mirrored. */
    private fun stop(
        newState: AvoidanceState = AvoidanceState.STOPPED,
        reason: String,
        stopReason: AvoidanceStop = AvoidanceStop.NONE,
    ): AvoidanceDecision {
        state = newState
        return AvoidanceDecision(state, WheelSpeeds(0, 0), reason, stopReason = stopReason)
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

        /** Both side distances must be at most this for the scene to be a hallway rather than a room. */
        private const val CORRIDOR_SIDE_MAX_METERS = 2.0f

        /** How far a hallway may be uneven, as a share of its own width, before steering to correct. */
        private const val CORRIDOR_DEADBAND_FRACTION = 0.20f
        private const val CORRIDOR_DEADBAND_MIN_METERS = 0.20f
        private const val CORRIDOR_DEADBAND_MAX_METERS = 0.45f

        /** PWM of correction per metre the robot is off the middle of a hallway, past the deadband. */
        private const val CORRIDOR_CENTERING_PWM_PER_METER = 45f

        /** How much more room the other side needs before a held side is given up. */
        private const val SIDE_HOLD_MARGIN_METERS = 0.20f

        /**
         * The warm-up swivel: a couple of seconds of slow turning, in alternating swings, and a side
         * has to read at least this far away before the robot will swing towards it.
         */
        private const val WARM_UP_TOTAL_MS = 2_500L

        /** A slow sweep rather than a twitch: long swings, at [MotorTuning.PIVOT_FRACTION]. */
        private const val WARM_UP_SWING_MS = 700L
        private const val WARM_UP_SIDE_MIN_METERS = 0.45f

        /**
         * How long the robot keeps doing what it decided before a different decision can replace it.
         * Long enough to actually cross a doorway; short enough that a real change is still quick.
         */
        private const val DECISION_HOLD_MS = 900L

        /**
         * How long a missing floor model is tolerated while driving: one or two frames of depth
         * flicker, not a real loss of sight.
         */
        private const val PLANE_LOSS_GRACE_MS = 250L

        private const val MIN_SMOOTH_STEERING_BIAS = 8
        private const val MAX_SMOOTH_STEERING_BIAS = 45
        private const val ROUTE_STEERING_BIAS = 0.22f
    }
}
