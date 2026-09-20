package com.example.guidedogtest

import com.example.guidedogtest.ocr.AvoidanceDecision
import com.example.guidedogtest.ocr.AvoidanceState
import com.example.guidedogtest.ocr.AvoidanceStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who drives the car.
 *
 * What this pins: the route follower computes a tuned pair every tick, and for a while every branch
 * of the autonomous loop threw those pairs away, so a route could be planned, announced, confirmed -
 * and never driven, because only the depth controller's own frame ever reached the wire. The
 * precedence here is the contract, and it is exactly the sort of thing a refactor of the transmit
 * loop can quietly invert.
 */
class DriveArbiterTest {

    private val route = WheelSpeeds(180, 128)
    private val manual = WheelSpeeds(-205, 190)
    private val stopped = WheelSpeeds(0, 0)

    private fun avoidance(
        state: AvoidanceState,
        stopReason: AvoidanceStop = AvoidanceStop.NONE,
        bias: Int = 0,
        speeds: WheelSpeeds = stopped,
    ) = AvoidanceDecision(state, speeds, "action", steeringBias = bias, stopReason = stopReason)

    @Test
    fun theRouteDrivesWhenThePathIsClear() {
        assertEquals(route, DriveArbiter.resolve(route, stopped, avoidance(AvoidanceState.FORWARD)))
    }

    @Test
    fun anUnsafeStopBeatsTheRoute() {
        val blocked = avoidance(AvoidanceState.STOPPED, AvoidanceStop.UNSAFE)
        assertEquals(stopped, DriveArbiter.resolve(route, manual, blocked))
    }

    @Test
    fun notBeingAbleToSeeStopsTheCar() {
        val blind = avoidance(AvoidanceState.STOPPED, AvoidanceStop.SENSING_UNAVAILABLE)
        // A depth feed that has gone quiet is not a clear road: the route does not get to keep
        // driving on the strength of a sensor that stopped answering.
        assertEquals(stopped, DriveArbiter.resolve(route, manual, blind))
        // With nobody driving either it is a stop, not a guess.
        assertEquals(stopped, DriveArbiter.resolve(null, stopped, blind))
    }

    @Test
    fun theControllersSteeringIsLaidOnTheDrivingPair() {
        val slow = avoidance(AvoidanceState.SLOW, bias = 45, speeds = WheelSpeeds(160, 160))
        // 128 - 45 = 83 is under the right motor's floor, so it is raised to it: a wheel commanded
        // below the deadband does not turn at all, and then the arc is not the one asked for.
        assertEquals(WheelSpeeds(225, MotorTuning.enforceMinimumRight(83)), DriveArbiter.resolve(route, stopped, slow))
    }

    @Test
    fun anObstacleWorthPivotingAroundTakesTheWheel() {
        val pivot = avoidance(AvoidanceState.TURN_LEFT, speeds = WheelSpeeds(-205, 190))
        assertEquals(WheelSpeeds(-205, 190), DriveArbiter.resolve(route, stopped, pivot))
    }

    @Test
    fun withNobodyDrivingTheControllerDrivesItself() {
        val cruising = avoidance(AvoidanceState.FORWARD, speeds = WheelSpeeds(160, 160))
        assertEquals(WheelSpeeds(160, 160), DriveArbiter.resolve(null, stopped, cruising))
    }

    @Test
    fun withAvoidanceOffTheRouteOrTheManualCommandGoesOut() {
        assertEquals(route, DriveArbiter.resolve(route, manual, null))
        assertEquals(manual, DriveArbiter.resolve(null, manual, null))
    }
}
