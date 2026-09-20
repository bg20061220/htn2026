package com.example.guidedogtest

import com.example.guidedogtest.ocr.AvoidanceDecision
import com.example.guidedogtest.ocr.AvoidanceStop

/**
 * Which wheel speeds go on the wire, and who gets to decide them.
 *
 * Two things can drive this car and they answer different questions: the human (or the route follower)
 * knows *where* to go, the depth controller knows what is *in the way*. The split is one-way on
 * purpose - **the controller may only ever subtract**:
 *
 *  1. **its stop wins over everything.** Something in the middle box, no link, and the wheels are 0,0
 *     whatever the human or the route asked for. This is the whole safety contract, and it is the one
 *     rule that must not depend on which mode the app thinks it is in;
 *  2. **a route drives**, with the controller's bounded steering bias laid on it - the follower owns
 *     the differential while it is following;
 *  3. **a live command drives** when the controller has nothing to say. Not the *absence* of a stop -
 *     the absence of an opinion. A controller that is switched off, or still waking up, returns
 *     `IDLE` at zero, and that must never be read as "stop": it used to be, which is why the app
 *     could say "moving forward" out loud while the wheels stayed still;
 *  4. **with nobody driving at all, the controller drives** - that is what the camera view's AUTO
 *     switch is for.
 *
 * Pure logic: no transport, no clock, no Android, so the precedence is testable without a robot.
 */
object DriveArbiter {

    /**
     * @param route the follower's frame while a route is being followed, null otherwise.
     * @param manual the human's latched command, [WheelSpeeds] `0,0` when the command is STOP.
     * @param avoidance the depth controller's latest decision, or null when avoidance is off.
     */
    fun resolve(route: WheelSpeeds?, manual: WheelSpeeds, avoidance: AvoidanceDecision?): WheelSpeeds {
        if (avoidance != null && avoidance.stopReason != AvoidanceStop.NONE) return WheelSpeeds(0, 0)
        route?.let { return MotorTuning.steered(it, avoidance?.steeringBias ?: 0) }
        if (manual != WheelSpeeds(0, 0)) return manual
        return avoidance?.wheelSpeeds ?: manual
    }
}
