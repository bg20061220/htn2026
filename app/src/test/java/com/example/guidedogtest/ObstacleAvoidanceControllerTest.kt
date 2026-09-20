package com.example.guidedogtest

import com.example.guidedogtest.ocr.*
import org.junit.Assert.*
import org.junit.Test

/**
 * What the robot does about the three boxes.
 *
 * The decision table, in order: a stop request holds, a drop stops the car, a box that cannot be read
 * is not trusted, a blocked middle turns towards whichever side has room, a caution slows down, and a
 * clear middle drives - steering off the middle of the two side boxes, with a deadband so an uneven
 * wall does not move the wheels.
 */
class ObstacleAvoidanceControllerTest {
    private var now = 1_000L
    private var tick = 0L
    private val controller = ObstacleAvoidanceController(clockMillis = { now })

    @Test fun `a clear path drives forward on the tuned pair`() {
        val decision = update()
        assertEquals(AvoidanceState.FORWARD, decision.state)
        assertEquals("FORWARD", decision.action)
        assertEquals(MotorSettings().forward.frame(), decision.wheelSpeeds.frame())
    }

    @Test fun `a caution box slows down and leaves the steering alone when the sides are even`() {
        val decision = update(centre = SceneZoneState.CAUTION, centreMeters = 1.2f, leftMeters = 1.0f, rightMeters = 1.0f)
        assertEquals(AvoidanceState.SLOW, decision.state)
        assertEquals("SLOW FORWARD", decision.action)
        assertEquals(0, decision.steeringBias)
        assertTrue("slower than the cruise pair", decision.wheelSpeeds.left < MotorSettings().forward.left)
    }

    @Test fun `a blocked middle turns towards the side with room`() {
        val decision = update(centre = SceneZoneState.BLOCKED, centreMeters = 0.5f, leftMeters = 1.6f, rightMeters = 0.5f)
        assertEquals(AvoidanceState.TURN_LEFT, decision.state)
        assertEquals("PIVOT LEFT", decision.action)
        assertEquals(MotorTuning.pivotPair(MotorSettings().left).frame(), decision.wheelSpeeds.frame())
    }

    @Test fun `the chosen side is held while the middle stays blocked`() {
        update(centre = SceneZoneState.BLOCKED, centreMeters = 0.5f, leftMeters = 1.6f, rightMeters = 0.5f)
        // The other side edges ahead, but only just: a near-tie must not rock the robot left and right.
        val decision = update(centre = SceneZoneState.BLOCKED, centreMeters = 0.5f, leftMeters = 1.62f, rightMeters = 1.58f)
        assertEquals(AvoidanceState.TURN_LEFT, decision.state)
    }

    @Test fun `a blocked middle with nowhere to turn stops`() {
        val decision = update(
            left = SceneZoneState.BLOCKED,
            centre = SceneZoneState.BLOCKED,
            right = SceneZoneState.BLOCKED,
            centreMeters = 0.5f,
            leftMeters = 0.4f,
            rightMeters = 0.4f,
        )
        assertEquals(AvoidanceState.STOPPED, decision.state)
        assertEquals("STOP: NO SAFE PATH", decision.action)
        assertEquals(WheelSpeeds(0, 0), decision.wheelSpeeds)
    }

    @Test fun `green middle with a red side still drives, leaning off the wall`() {
        val decision = update(left = SceneZoneState.BLOCKED, leftMeters = 0.5f, centreMeters = null, rightMeters = 2.6f)
        assertEquals(AvoidanceState.FORWARD, decision.state)
        assertTrue("away from the blocked side", decision.steeringBias > 0)
    }

    @Test fun `a red middle turns into a yellow side, not just a green one`() {
        val decision = update(
            left = SceneZoneState.CAUTION,
            leftMeters = 1.2f,
            centre = SceneZoneState.BLOCKED,
            centreMeters = 0.5f,
            right = SceneZoneState.BLOCKED,
            rightMeters = 0.4f,
        )
        assertEquals(AvoidanceState.TURN_LEFT, decision.state)
    }

    @Test fun `a yellow middle with both sides red creeps forward instead of stopping`() {
        val decision = update(
            left = SceneZoneState.BLOCKED,
            leftMeters = 0.5f,
            centre = SceneZoneState.CAUTION,
            centreMeters = 1.2f,
            right = SceneZoneState.BLOCKED,
            rightMeters = 0.5f,
        )
        assertEquals(AvoidanceState.SLOW, decision.state)
        assertTrue("slower than the cruise pair", decision.wheelSpeeds.left < MotorSettings().forward.left)
    }

    @Test fun `all red stops and asks for the alert`() {
        val decision = update(
            left = SceneZoneState.BLOCKED,
            centre = SceneZoneState.BLOCKED,
            right = SceneZoneState.BLOCKED,
            leftMeters = 0.5f,
            centreMeters = 0.5f,
            rightMeters = 0.5f,
        )
        assertEquals(AvoidanceState.STOPPED, decision.state)
        assertEquals(AvoidanceStop.UNSAFE, decision.stopReason)
        assertEquals(WheelSpeeds(0, 0), decision.wheelSpeeds)
    }

    @Test fun `a hole beside the robot closes that side and nothing else`() {
        // The bug from the floor: a drop in the left box used to stop the whole car, so a drain cover
        // on one side ended the walk. The middle is green, so the walk continues.
        val decision = update(leftDrop = true, leftMeters = 1.6f, rightMeters = 1.6f)
        assertEquals(AvoidanceState.FORWARD, decision.state)
        assertTrue("and it leans away from the hole", decision.steeringBias > 0)
    }

    @Test fun `a hole beside the robot is not somewhere to turn into`() {
        val decision = update(
            leftDrop = true,
            leftMeters = 2.0f,
            centre = SceneZoneState.BLOCKED,
            centreMeters = 0.5f,
            rightMeters = 1.0f,
        )
        assertEquals(AvoidanceState.TURN_RIGHT, decision.state)
    }

    @Test fun `without a floor model the robot swivels to give the depth module motion`() {
        // Depth-from-motion needs the camera to move, so standing still forever is not an option and
        // driving blind is not either: it turns in place, which sweeps the camera and advances nothing.
        val decision = update(plane = false, leftMeters = null, rightMeters = null)
        assertEquals(AvoidanceState.TURN_LEFT, decision.state)
        assertEquals("WARM UP TURN LEFT", decision.action)
        assertTrue("the swivel is deliberate motion, not a refusal", decision.stopReason == AvoidanceStop.NONE)
        assertEquals(
            "the swivel turns on the same gentled pair as every other pivot",
            MotorTuning.pivotPair(MotorSettings().left).left,
            decision.wheelSpeeds.left,
        )
    }

    @Test fun `the swivel keeps away from a side that reads close`() {
        val decision = update(plane = false, leftMeters = 0.3f, rightMeters = null)
        assertEquals(AvoidanceState.TURN_RIGHT, decision.state)
    }

    @Test fun `no swivel when there is nothing to swivel into but no room either`() {
        val decision = update(plane = false, leftMeters = 0.2f, rightMeters = 0.2f)
        assertEquals(AvoidanceState.STOPPED, decision.state)
        assertEquals(AvoidanceStop.SENSING_UNAVAILABLE, decision.stopReason)
    }

    @Test fun `the swivel is bounded and then the robot says it cannot see`() {
        val controller = ObstacleAvoidanceController(clockMillis = { now })
        val blind = scene(plane = false)
        var sawStop = false
        repeat(40) {
            now += 200
            val decision = controller.update(blind.copy(depthTimestampNanos = ++tick), true, true, true, true)
            if (decision.state == AvoidanceState.STOPPED) sawStop = true
        }
        assertTrue("the swivel budget runs out instead of turning forever", sawStop)
    }

    @Test fun `a flickering side box does not take the wheel away from a drive`() {
        // The complaint this comes from: "it barely gets to move". One frame where a side box reads
        // red used to start a turn, the turn changed the view, the next frame drove again - so the
        // robot crossed the room a hand's width at a time.
        val controller = ObstacleAvoidanceController(clockMillis = { now })
        assertEquals(AvoidanceState.FORWARD, controller.update(scene(leftMeters = 1.5f, rightMeters = 1.5f), true, true, true, true).state)
        now += 125
        val flicker = controller.update(
            scene(left = SceneZoneState.BLOCKED, leftMeters = 0.5f, rightMeters = 1.5f),
            true, true, true, true,
        )
        assertEquals("a red side for one frame does not turn the robot", AvoidanceState.FORWARD, flicker.state)
    }

    @Test fun `a red middle takes the wheel at once, whatever was decided`() {
        val controller = ObstacleAvoidanceController(clockMillis = { now })
        controller.update(scene(leftMeters = 1.5f, rightMeters = 1.5f), true, true, true, true)
        now += 125
        val blocked = controller.update(
            scene(leftMeters = 1.6f, centre = SceneZoneState.BLOCKED, centreMeters = 0.5f, rightMeters = 0.5f, right = SceneZoneState.BLOCKED),
            true, true, true, true,
        )
        assertEquals(AvoidanceState.TURN_LEFT, blocked.state)
    }

    @Test fun `a stop is never held back`() {
        val controller = ObstacleAvoidanceController(clockMillis = { now })
        controller.update(scene(leftMeters = 1.5f, rightMeters = 1.5f), true, true, true, true)
        now += 125
        val stopped = controller.update(scene(drop = true, dropZone = SceneZone.CENTER), true, true, true, true)
        assertEquals(AvoidanceState.STOPPED, stopped.state)
        assertEquals("STOP: DROP", stopped.action)
    }

    @Test fun `one unjudgeable frame does not interrupt a drive, but losing sight does`() {
        val controller = ObstacleAvoidanceController(clockMillis = { now })
        controller.update(scene(leftMeters = 1.5f, rightMeters = 1.5f), true, true, true, true)

        now += 125
        val oneBadFrame = controller.update(scene(plane = false), true, true, true, true)
        assertEquals("one frame without a floor model keeps the drive", AvoidanceState.FORWARD, oneBadFrame.state)

        now += 500
        val persistent = controller.update(scene(plane = false), true, true, true, true)
        assertTrue("but losing it for good does not", persistent.state != AvoidanceState.FORWARD)
    }

    @Test fun `a hole straight ahead stops the car`() {
        val decision = update(drop = true, dropZone = SceneZone.CENTER)
        assertEquals(AvoidanceState.STOPPED, decision.state)
        assertEquals("STOP: DROP", decision.action)
        assertEquals(WheelSpeeds(0, 0), decision.wheelSpeeds)
    }

    @Test fun `a scene with nothing readable is never driven on`() {
        val controller = ObstacleAvoidanceController(clockMillis = { now })
        val blank = scene(
            left = SceneZoneState.UNKNOWN,
            centre = SceneZoneState.UNKNOWN,
            right = SceneZoneState.UNKNOWN,
        )
        // It may look around to get the depth module going, but it must never drive on a scene nothing
        // can be judged from - and when the looking-around is spent, it stops and says so.
        var stopped = false
        repeat(40) {
            now += 200
            val decision = controller.update(blank.copy(depthTimestampNanos = ++tick), true, true, true, true)
            assertTrue(
                "never a drive without a floor model, got ${decision.state}",
                decision.state != AvoidanceState.FORWARD && decision.state != AvoidanceState.SLOW,
            )
            if (decision.state == AvoidanceState.STOPPED) {
                assertEquals("STOP: SCENE UNKNOWN", decision.action)
                stopped = true
            }
        }
        assertTrue("and it does stop in the end", stopped)
    }

    @Test fun `without a ground plane the boxes are not trusted`() {
        val controller = ObstacleAvoidanceController(clockMillis = { now })
        val unmodelled = scene(plane = false, leftMeters = 1.5f, rightMeters = 1.5f, centreMeters = 5f)
        repeat(30) {
            now += 200
            val decision = controller.update(unmodelled.copy(depthTimestampNanos = ++tick), true, true, true, true)
            // Distances without a floor model are raw readings, not judged ones: whatever they say,
            // the robot does not drive on them.
            assertTrue(decision.state != AvoidanceState.FORWARD && decision.state != AvoidanceState.SLOW)
        }
    }

    @Test fun `a route asking for a turn eases that way`() {
        val right = controller.update(scene(), true, true, true, true, DesiredTravelDirection.RIGHT)
        assertEquals(AvoidanceState.SLOW, right.state)
        assertEquals("STEER RIGHT", right.action)
        assertTrue(right.steeringBias > 0)

        val left = controller.update(scene(), true, true, true, true, DesiredTravelDirection.LEFT)
        assertEquals("STEER LEFT", left.action)
        assertTrue(left.steeringBias < 0)
    }

    @Test fun `a requested pivot turns that way`() {
        val decision = controller.update(scene(leftMeters = 1.5f, rightMeters = 1.5f), true, true, true, true, DesiredTravelDirection.PIVOT_RIGHT)
        assertEquals(AvoidanceState.TURN_RIGHT, decision.state)
        assertEquals("PIVOT RIGHT", decision.action)
        assertEquals(MotorTuning.pivotPair(MotorSettings().right).frame(), decision.wheelSpeeds.frame())
    }

    @Test fun `a requested pivot into a blocked box turns the other way`() {
        val decision = controller.update(
            scene(left = SceneZoneState.CAUTION, leftMeters = 1.2f, right = SceneZoneState.BLOCKED, rightMeters = 0.4f),
            true,
            true,
            true,
            true,
            DesiredTravelDirection.PIVOT_RIGHT,
        )
        assertEquals(AvoidanceState.TURN_LEFT, decision.state)
    }

    @Test fun `a stop request holds`() {
        val decision = controller.update(scene(), true, true, true, true, DesiredTravelDirection.STOP)
        assertEquals(AvoidanceState.STOPPED, decision.state)
        assertEquals("STOP: ROUTE HOLD", decision.action)
        assertEquals(WheelSpeeds(0, 0), decision.wheelSpeeds)
    }

    // --- the middle of a hallway, measured on the two side boxes ----------------------------------

    @Test fun `a slightly uneven hallway wall does not bend the path`() {
        val decision = update(leftMeters = 1.40f, rightMeters = 1.52f)
        assertEquals(AvoidanceState.FORWARD, decision.state)
        assertEquals("FORWARD", decision.action)
        assertEquals(0, decision.steeringBias)
        assertEquals(MotorSettings().forward.frame(), decision.wheelSpeeds.frame())
    }

    @Test fun `a hallway walked off its middle is corrected towards it`() {
        val right = update(leftMeters = 0.60f, rightMeters = 1.70f)
        assertEquals(AvoidanceState.FORWARD, right.state)
        assertTrue("more room on the right steers right", right.steeringBias > 0)
        assertEquals("CENTER RIGHT", right.action)
        assertTrue(right.wheelSpeeds.left > right.wheelSpeeds.right)

        val left = ObstacleAvoidanceController(clockMillis = { now })
            .update(scene(leftMeters = 1.70f, rightMeters = 0.60f, left = SceneZoneState.CLEAR, right = SceneZoneState.CLEAR), true, true, true, true)
        assertTrue("more room on the left steers left", left.steeringBias < 0)
        assertEquals("CENTER LEFT", left.action)
    }

    @Test fun `the corridor correction grows with the offset and stays bounded`() {
        val slight = update(leftMeters = 0.90f, rightMeters = 1.50f).steeringBias
        val strong = update(leftMeters = 0.55f, rightMeters = 1.95f).steeringBias
        assertTrue("a bigger offset has to ask for more", strong > slight)
        assertTrue("it stays a nudge", strong <= 45)
    }

    @Test fun `a room is not a hallway`() {
        assertEquals(0, update(leftMeters = 4.0f, rightMeters = 4.6f).steeringBias)
    }

    @Test fun `one wall is not a centre line either`() {
        assertEquals(0, update(leftMeters = null, rightMeters = 1.2f).steeringBias)
        assertEquals(0, update(leftMeters = 1.2f, rightMeters = null).steeringBias)
    }

    @Test fun `hugging a blocked side nudges away from it`() {
        val huggingLeft = update(left = SceneZoneState.BLOCKED, leftMeters = 0.5f, rightMeters = null)
        assertTrue("away from the left wall is right", huggingLeft.steeringBias > 0)

        val huggingRight = ObstacleAvoidanceController(clockMillis = { now })
            .update(scene(right = SceneZoneState.BLOCKED, rightMeters = 0.5f), true, true, true, true)
        assertTrue("away from the right wall is left", huggingRight.steeringBias < 0)
    }

    @Test fun `blocked on both sides and clear ahead is not a nudge in either direction`() {
        val squeezed = update(
            left = SceneZoneState.BLOCKED,
            right = SceneZoneState.BLOCKED,
            leftMeters = 0.5f,
            rightMeters = 0.5f,
        )
        assertEquals(0, squeezed.steeringBias)
    }

    @Test fun `every command that moves clears the motor floor`() {
        listOf(
            update(leftMeters = 0.6f, rightMeters = 1.7f),
            update(centre = SceneZoneState.CAUTION, centreMeters = 1.1f),
            update(centre = SceneZoneState.BLOCKED, centreMeters = 0.5f, leftMeters = 1.6f, rightMeters = 0.5f),
            update(left = SceneZoneState.BLOCKED, leftMeters = 0.5f, rightMeters = 1.4f),
        ).flatMap { listOf(it.wheelSpeeds.left, it.wheelSpeeds.right) }
            .filter { it != 0 }
            .forEach { assertTrue(kotlin.math.abs(it) >= ObstacleAvoidanceController.MIN_EFFECTIVE_MOTOR_SPEED) }
    }

    private fun update(
        left: SceneZoneState = SceneZoneState.CLEAR,
        centre: SceneZoneState = SceneZoneState.CLEAR,
        right: SceneZoneState = SceneZoneState.CLEAR,
        leftMeters: Float? = null,
        centreMeters: Float? = null,
        rightMeters: Float? = null,
        drop: Boolean = false,
        dropZone: SceneZone? = null,
        leftDrop: Boolean = false,
        plane: Boolean = true,
    ) = controller.update(
        scene(left, centre, right, leftMeters, centreMeters, rightMeters, drop, dropZone, plane, leftDrop),
        true,
        true,
        true,
        true,
    )

    private fun scene(
        left: SceneZoneState = SceneZoneState.CLEAR,
        centre: SceneZoneState = SceneZoneState.CLEAR,
        right: SceneZoneState = SceneZoneState.CLEAR,
        leftMeters: Float? = null,
        centreMeters: Float? = null,
        rightMeters: Float? = null,
        drop: Boolean = false,
        dropZone: SceneZone? = null,
        plane: Boolean = true,
        leftDrop: Boolean = false,
    ) = SceneAwarenessResult(
        leftDistanceMeters = leftMeters,
        centerDistanceMeters = centreMeters,
        rightDistanceMeters = rightMeters,
        leftState = left,
        centerState = centre,
        rightState = right,
        depthTimestampNanos = ++tick,
        supportPlaneDetected = plane,
        dropDetected = drop,
        leftDrop = leftDrop || dropZone == SceneZone.LEFT,
        centerDrop = dropZone == SceneZone.CENTER,
        rightDrop = dropZone == SceneZone.RIGHT,
    )
}
