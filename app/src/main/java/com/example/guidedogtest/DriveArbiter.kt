package com.example.guidedogtest

import com.example.guidedogtest.ocr.AvoidanceDecision
import com.example.guidedogtest.ocr.AvoidanceState
import com.example.guidedogtest.ocr.AvoidanceStop

/**
 * Which wheel speeds go on the wire, and who gets to decide them.
 *
 * Two things can drive this car and they answer different questions: the route follower - or the
 * human on the manual buttons - knows *where* to go, the depth controller knows what is *in the way*.
 * Only one of them can own the wheel differential on a given tick, so the split is:
 *
 *  - the controller stops the car outright when it says the way is unsafe: a drop, no corridor, no link;
 *  - it stops the car outright when it cannot see at all, too: an obstacle sensor that has gone quiet
 *    is not a clear road, and the walker is told about that stop rather than walked into something
 *    the robot stopped being able to detect;
 *  - it takes the wheel when it needs to pivot around something, because nothing else knows how;
 *  - it steers, with a bounded bias, when there is something to go around but a way past it;
 *  - otherwise the driving pair goes out untouched.
 *
 * When nobody is driving at all - no route, no manual command - the controller drives on its own,
 * which is what the camera view's AUTO switch is for.
 *
 * Pure logic: no transport, no clock, no Android, so the precedence is testable without a robot.
 */
object DriveArbiter {

    /**
     * @param route the follower's frame while a route is being followed, null otherwise.
     * @param manual the human's latched command, [WheelSpeeds] `0,0` when the command is STOP.
     * @param avoidance the depth controller's latest decision, or null when avoidance is off.
     */
    fun resolve(route: WheelSpeeds?, manual: WheelSpeeds, avoidance: AvoidanceDecision?): WheelSpeeds =
        when {
            avoidance == null -> route ?: manual
            avoidance.stopReason != AvoidanceStop.NONE -> WheelSpeeds(0, 0)
            avoidance.state == AvoidanceState.TURN_LEFT || avoidance.state == AvoidanceState.TURN_RIGHT ->
                avoidance.wheelSpeeds
            route != null -> MotorTuning.steered(route, avoidance.steeringBias)
            else -> avoidance.wheelSpeeds
        }
}
