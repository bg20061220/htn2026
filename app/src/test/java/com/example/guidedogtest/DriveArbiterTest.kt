package com.example.guidedogtest

import com.example.guidedogtest.ocr.AvoidanceDecision
import com.example.guidedogtest.ocr.AvoidanceState
import com.example.guidedogtest.ocr.AvoidanceStop
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Who owns the wheels, and who may veto whom.
 *
 * The one rule the car's safety rests on: the depth controller can *subtract*, never add. It stops the
 * car whatever else is driving; it never steers it; and its silence is not a stop. Both halves of that
 * have failed on the floor - a robot that drove on with an obstacle in front of it, and a robot that
 * said "moving forward" out loud while the wheels stayed still - so they are pinned here.
 */
class DriveArbiterTest {
    private val forward = WheelSpeeds(180, 128)
    private val stop = WheelSpeeds(0, 0)

    private fun decision(
        state: AvoidanceState = AvoidanceState.FORWARD,
        wheelSpeeds: WheelSpeeds = forward,
        stopReason: AvoidanceStop = AvoidanceStop.NONE,
        steeringBias: Int = 0,
        action: String = "FORWARD",
    ) = AvoidanceDecision(state, wheelSpeeds, action, steeringBias = steeringBias, stopReason = stopReason)

    @Test
    fun `an obstacle stop beats a live command`() {
        // The failure this comes from: the car drove on with something in the middle box, because the
        // human's command was still on the wire and the controller's opinion came second.
        val stopped = decision(
            state = AvoidanceState.STOPPED,
            wheelSpeeds = stop,
            stopReason = AvoidanceStop.OBSTACLE,
            action = "STOP: OBSTACLE AHEAD",
        )
        assertEquals(stop, DriveArbiter.resolve(route = null, manual = forward, avoidance = stopped))
    }

    @Test
    fun `a live command drives while the controller has no opinion`() {
        // The other failure: "moving forward" said out loud, wheels still. A controller that is
        // switched off (or still waking up) returns IDLE at zero, and zero from a controller with no
        // opinion is not an instruction to stop.
        val idle = decision(state = AvoidanceState.IDLE, wheelSpeeds = stop, action = "AUTO OFF")
        assertEquals(forward, DriveArbiter.resolve(route = null, manual = forward, avoidance = idle))
    }

    @Test
    fun `with nobody driving the controller drives`() {
        // The camera view's AUTO switch: no route, no command, the layer owns the wheels.
        assertEquals(forward, DriveArbiter.resolve(route = null, manual = stop, avoidance = decision()))
    }

    @Test
    fun `with nobody driving and nothing armed the car stands still`() {
        val off = decision(state = AvoidanceState.IDLE, wheelSpeeds = stop, action = "AUTO OFF")
        assertEquals(stop, DriveArbiter.resolve(route = null, manual = stop, avoidance = off))
    }

    @Test
    fun `without the layer the command goes out untouched`() {
        assertEquals(forward, DriveArbiter.resolve(route = null, manual = forward, avoidance = null))
    }

    @Test
    fun `a route owns the differential and carries the lean`() {
        val route = WheelSpeeds(200, 160)
        val leaning = decision(state = AvoidanceState.SLOW, wheelSpeeds = WheelSpeeds(158, 113), steeringBias = 40)
        assertEquals(
            MotorTuning.steered(route, 40),
            DriveArbiter.resolve(route = route, manual = forward, avoidance = leaning),
        )
    }

    @Test
    fun `a route cannot drive through the stop either`() {
        val stopped = decision(
            state = AvoidanceState.STOPPED,
            wheelSpeeds = stop,
            stopReason = AvoidanceStop.OBSTACLE,
        )
        assertEquals(stop, DriveArbiter.resolve(route = WheelSpeeds(200, 160), manual = forward, avoidance = stopped))
    }

    @Test
    fun `a dead link stops it whatever else is driving`() {
        val unlinked = decision(
            state = AvoidanceState.STOPPED,
            wheelSpeeds = stop,
            stopReason = AvoidanceStop.UNSAFE,
            action = "STOP: ROBOT DISCONNECTED",
        )
        assertEquals(stop, DriveArbiter.resolve(route = WheelSpeeds(200, 160), manual = forward, avoidance = unlinked))
    }
}
