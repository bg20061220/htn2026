package com.example.guidedogtest

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A point on the earth. */
data class GeoPoint(val lat: Double, val lng: Double)

/** One GPS reading, reduced to what the follower needs. */
data class Fix(
    val lat: Double,
    val lng: Double,
    val accuracyMeters: Float,
    /** Which way the car points, 0 = north, 90 = east, or null while there is no heading. */
    val headingDegrees: Double?,
)

/** What the follower wants the robot to do next. */
sealed interface Command {

    data class Drive(val left: Int, val right: Int) : Command

    /**
     * Rotate in place at [left]/[right] until the heading error closes. [degrees] is how far off the
     * target bearing the car is right now - positive means clockwise, i.e. turn right.
     */
    data class Pivot(val degrees: Double, val left: Int, val right: Int) : Command

    data class Hold(val reason: String) : Command

    data object Arrived : Command
}

/** One step of a Google Routes itinerary: an instruction, and a place it ends. */
data class RouteStep(
    val instruction: String,
    val distanceMeters: Int,
    val endLat: Double,
    val endLng: Double,
)

object Geo {

    private const val EARTH_RADIUS_M = 6371000.0
    private const val MAX_PWM = Drive.MAX_PWM

    private val COMPASS_POINTS =
        listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val sinLat = sin(dLat / 2)
        val sinLng = sin(dLng / 2)
        val h = sinLat * sinLat + cos(lat1) * cos(lat2) * sinLng * sinLng
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Initial bearing from one point to another, 0 = north, 90 = east. */
    fun bearingDegrees(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLng = Math.toRadians(to.lng - from.lng)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Wraps an angle into -180..180 so differences are comparable. */
    fun normalizeDegrees(degrees: Double): Double {
        var value = (degrees + 180.0) % 360.0
        if (value < 0) value += 360.0
        return value - 180.0
    }

    /** Eight-point compass name for a heading, e.g. 51 -> "NE". */
    fun compassPoint(degrees: Double): String {
        val wrapped = (degrees % 360.0 + 360.0) % 360.0
        return COMPASS_POINTS[(((wrapped + 22.5) / 45.0).toInt()) % 8]
    }

    internal fun clampPwm(value: Int): Int = value.coerceIn(-MAX_PWM, MAX_PWM)
}

/**
 * Walks a Google Routes itinerary one step at a time.
 *
 * Each step is "go this way for this far", and "this way" is a bearing, not the maneuver word
 * Google attaches to it: the robot points itself at the step's `endLocation` and drives there.
 * Pointing is a closed loop on the compass - rotate slowly while the error is wide, stop as soon
 * as it is inside the tolerance - so a 45 degree turn cannot overshoot into a 60 degree one the
 * way a timed spin does.
 *
 * All of this is pure logic - it is fed a [Fix] and a time delta and returns a [Command], which is
 * what makes it testable without a robot, a phone or a network.
 */
class RouteFollower(
    private val steps: List<RouteStep>,
    private val config: Config = Config(),
) {

    data class Config(
        /** Close enough to a step's end to call it reached. GPS is coarse, so this is generous. */
        val arriveRadiusMeters: Double = 8.0,
        /** Refuse to drive on a fix worse than this - a car is not a car if it does not know where it is. */
        val maxAccuracyMeters: Double = 30.0,
        /** Straight-line command, already carrying the chassis trim. */
        val baseLeft: Int = Drive.SPEED,
        val baseRight: Int = Drive.SPEED - Drive.RIGHT_TRIM,
        val steerGain: Double = 1.2,
        val maxSteer: Int = 60,
        /**
         * Heading error at which the car stops and rotates in place.
         *
         * Two thresholds rather than one: a single one would have it chattering between driving and
         * pivoting on the boundary, and every chatter is a correction the chassis has to absorb.
         */
        val alignStartDegrees: Double = 25.0,
        /** ...and the tighter one that ends the rotation, so it never hunts past the target. */
        val alignStopDegrees: Double = 10.0,
        /** Rotation PWM ramps from [Drive.TURN_MIN] at the stop threshold to [Drive.TURN_MAX]. */
        val turnPwmPerDegree: Double = 0.4,
        /** Give up if the distance to the current target has not shrunk in this long. */
        val stuckSeconds: Double = 15.0,
        val minProgressMeters: Double = 3.0,
    )

    var index: Int = 0
        private set
    var arrived: Boolean = false
        private set

    /** True while the car is rotating instead of driving. */
    var aligning: Boolean = false
        private set

    private var bestDistance = Double.MAX_VALUE
    private var secondsWithoutProgress = 0.0

    val currentStep: RouteStep? get() = steps.getOrNull(index)

    fun progressLabel(): String =
        if (steps.isEmpty()) "no route loaded" else "step ${index + 1} of ${steps.size}"

    fun update(fix: Fix, dtSeconds: Double): Command {
        if (arrived) return Command.Arrived
        val step = currentStep ?: return Command.Hold("no route loaded")

        if (fix.accuracyMeters > config.maxAccuracyMeters) {
            return Command.Hold("GPS is ±${fix.accuracyMeters.toInt()} m - waiting for a better fix")
        }

        val here = GeoPoint(fix.lat, fix.lng)
        val target = GeoPoint(step.endLat, step.endLng)
        val distance = Geo.distanceMeters(here, target)

        if (distance < bestDistance - config.minProgressMeters) {
            bestDistance = distance
            secondsWithoutProgress = 0.0
        } else {
            secondsWithoutProgress += dtSeconds
            if (secondsWithoutProgress > config.stuckSeconds) {
                return Command.Hold("not making progress toward \"${step.instruction}\"")
            }
        }

        if (distance <= config.arriveRadiusMeters) {
            index++
            bestDistance = Double.MAX_VALUE
            secondsWithoutProgress = 0.0
            aligning = false
            if (index >= steps.size) {
                arrived = true
                return Command.Arrived
            }
            return update(fix, 0.0)
        }

        // Point at where this step ends before driving to it. Without a heading there is nothing to
        // align to, so the car drives on the steer loop alone, which is what it did before.
        val error = bearingErrorDegrees(fix, target)
        if (error != null) {
            val off = abs(error)
            if (aligning) {
                if (off > config.alignStopDegrees) return pivot(error)
                aligning = false
            } else if (off >= config.alignStartDegrees) {
                aligning = true
                return pivot(error)
            }
        }

        return Command.Drive(left = steeredLeft(fix, target), right = steeredRight(fix, target))
    }

    /** Which way we would have to turn to face the current target, or null without a heading. */
    fun bearingErrorDegrees(fix: Fix, target: GeoPoint): Double? {
        val heading = fix.headingDegrees ?: return null
        val desired = Geo.bearingDegrees(GeoPoint(fix.lat, fix.lng), target)
        return Geo.normalizeDegrees(desired - heading)
    }

    /**
     * A slow in-place rotation toward the target bearing. The speed ramps with the error - quick-ish
     * while it is far off, barely moving over the last few degrees - and the wheels stay symmetric,
     * because a trimmed spin is an arc, not a rotation.
     */
    private fun pivot(error: Double): Command {
        val pwm = (Drive.TURN_MIN + (abs(error) - config.alignStopDegrees) * config.turnPwmPerDegree)
            .toInt()
            .coerceIn(Drive.TURN_MIN, Drive.TURN_MAX)

        return if (error > 0) {
            Command.Pivot(error, left = pwm, right = -pwm)
        } else {
            Command.Pivot(error, left = -pwm, right = pwm)
        }
    }

    private fun steer(fix: Fix, target: GeoPoint): Int {
        val error = bearingErrorDegrees(fix, target) ?: return 0
        return (error * config.steerGain)
            .coerceIn(-config.maxSteer.toDouble(), config.maxSteer.toDouble())
            .toInt()
    }

    private fun steeredLeft(fix: Fix, target: GeoPoint) = Geo.clampPwm(config.baseLeft + steer(fix, target))

    private fun steeredRight(fix: Fix, target: GeoPoint) = Geo.clampPwm(config.baseRight - steer(fix, target))
}
