package com.example.guidedogtest

import com.example.guidedogtest.ocr.*
import org.junit.Assert.*
import org.junit.Test

/**
 * What the robot does about the middle box - which is now the whole strategy.
 *
 * The depth model is asked one question, and it is a binary one: is something in the middle box? Not
 * "which side has room", not "how far off the middle of a hallway" - those are second questions, and a
 * wrong answer to either of them steers the robot into a wall. So:
 *
 *  1. something in the middle (yellow or red) -> **stop**;
 *  2. an empty middle -> **drive on**, on the calibrated straight pair;
 *  3. nothing readable -> do what was asked, boxes or no boxes.
 *
 * Turning only happens when it is asked for: a pivot from a route or a spoken command, or the
 * follower's lean while it holds a bearing. No box ever turns the robot.
 */
class ObstacleAvoidanceControllerTest {
    private var now = 1_000L
    private var tick = 0L
    private val controller = ObstacleAvoidanceController(clockMillis = { now })

    @Test fun `an empty middle drives on the calibrated straight pair`() {
        val decision = update()
        assertEquals(AvoidanceState.FORWARD, decision.state)
        assertEquals("FORWARD", decision.action)
        assertEquals(MotorSettings().forward.frame(), decision.wheelSpeeds.frame())
        assertEquals(0, decision.steeringBias)
    }

    @Test fun `anything in the middle stops it`() {
        // Yellow counts: something within 1.5 m in the middle box is not something to creep past.
        val caution = update(centre = SceneZoneState.CAUTION, centreMeters = 1.2f)
        assertEquals(AvoidanceState.STOPPED, caution.state)
        assertEquals(AvoidanceStop.OBSTACLE, caution.stopReason)
        assertEquals(WheelSpeeds(0, 0), caution.wheelSpeeds)

        val blocked = ObstacleAvoidanceController(clockMillis = { now }).update(
            scene(centre = SceneZoneState.BLOCKED, centreMeters = 0.5f),
            true, true, true, true,
        )
        assertEquals(AvoidanceState.STOPPED, blocked.state)
        assertEquals(AvoidanceStop.OBSTACLE, blocked.stopReason)
        assertEquals(WheelSpeeds(0, 0), blocked.wheelSpeeds)
    }

    @Test fun `the stop is held until a new command answers it`() {
        // A box flickers - a step past a doorway, a person shifting their weight, ARCore's depth moving
        // by centimetres on a flat wall. Resuming on the next frame that reads clear would stutter the
        // robot across the room, so the stop waits for the walker instead.
        assertEquals(update(centre = SceneZoneState.BLOCKED, centreMeters = 0.5f).state, AvoidanceState.STOPPED)
        assertEquals(AvoidanceState.STOPPED, update().state)
        assertEquals(AvoidanceState.STOPPED, update().state)

        controller.releaseObstacleStop()
        assertEquals(AvoidanceState.FORWARD, update().state)
    }

    @Test fun `a stopped robot does not restart itself on frames it cannot read`() {
        // The trap this closes: depth-from-motion produces nothing while the robot stands still, so a
        // stopped robot's frames go stale - and a stale frame is not a clear road.
        update(centre = SceneZoneState.BLOCKED, centreMeters = 0.5f)
        assertEquals(AvoidanceState.STOPPED, update(plane = false).state)
        assertEquals(AvoidanceState.STOPPED, controller.update(null, true, true, true, true).state)

        controller.releaseObstacleStop()
        assertEquals("answered, it goes on the command alone", AvoidanceState.FORWARD, update(plane = false).state)
    }

    @Test fun `the side boxes do not steer it`() {
        // The explicit pin: a red side box is not an invitation to turn. It used to be, and it is what
        // had the robot crossing a room a hand's width at a time.
        val leftWall = update(left = SceneZoneState.BLOCKED, leftMeters = 0.4f, rightMeters = 2.6f)
        assertEquals(AvoidanceState.FORWARD, leftWall.state)
        assertEquals(0, leftWall.steeringBias)
        assertEquals(MotorSettings().forward.frame(), leftWall.wheelSpeeds.frame())

        val squeezed = update(
            left = SceneZoneState.BLOCKED, leftMeters = 0.4f,
            right = SceneZoneState.BLOCKED, rightMeters = 0.4f,
        )
        assertEquals(AvoidanceState.FORWARD, squeezed.state)
        assertEquals(0, squeezed.steeringBias)
    }

    @Test fun `a route lean is the only steering left`() {
        val right = controller.update(scene(leftMeters = 1.5f, rightMeters = 1.5f), true, true, true, true, DesiredTravelDirection.RIGHT)
        assertEquals(AvoidanceState.SLOW, right.state)
        assertEquals("STEER RIGHT", right.action)
        assertTrue(right.steeringBias > 0)

        val left = ObstacleAvoidanceController(clockMillis = { now })
            .update(scene(leftMeters = 1.5f, rightMeters = 1.5f), true, true, true, true, DesiredTravelDirection.LEFT)
        assertEquals("STEER LEFT", left.action)
        assertTrue(left.steeringBias < 0)
    }

    @Test fun `a lean does not drive into an obstacle`() {
        val leaning = controller.update(
            scene(centre = SceneZoneState.BLOCKED, centreMeters = 0.5f),
            true, true, true, true, DesiredTravelDirection.RIGHT,
        )
        assertEquals(AvoidanceState.STOPPED, leaning.state)
        assertEquals(AvoidanceStop.OBSTACLE, leaning.stopReason)
    }

    @Test fun `a pivot that was asked for is never blocked or redirected`() {
        // A turn in place cannot run into anything, and it is how the robot gets out of a corner - so
        // the obstacle layer has no vote on it, and it does not pick the other side either.
        val decision = controller.update(
            scene(centre = SceneZoneState.BLOCKED, centreMeters = 0.4f, right = SceneZoneState.BLOCKED, rightMeters = 0.3f),
            true, true, true, true, DesiredTravelDirection.PIVOT_RIGHT,
        )
        assertEquals(AvoidanceState.TURN_RIGHT, decision.state)
        assertEquals("PIVOT RIGHT", decision.action)
        assertEquals(MotorTuning.pivotPair(MotorSettings().right).frame(), decision.wheelSpeeds.frame())
    }

    @Test fun `a pivot works with nothing readable in the frame`() {
        val decision = controller.update(
            scene(plane = false), true, true, true, true, DesiredTravelDirection.PIVOT_LEFT,
        )
        assertEquals(AvoidanceState.TURN_LEFT, decision.state)
        assertEquals(MotorSettings().left.frame(), decision.wheelSpeeds.frame())
    }

    @Test fun `a stop request holds`() {
        val decision = controller.update(scene(), true, true, true, true, DesiredTravelDirection.STOP)
        assertEquals(AvoidanceState.STOPPED, decision.state)
        assertEquals("STOP: ROUTE HOLD", decision.action)
        assertEquals(WheelSpeeds(0, 0), decision.wheelSpeeds)
    }

    @Test fun `no link is the one case that must still stop`() {
        val decision = controller.update(scene(leftMeters = 1.5f, rightMeters = 1.5f), true, true, true, false)
        assertEquals(AvoidanceState.STOPPED, decision.state)
        assertEquals(AvoidanceStop.UNSAFE, decision.stopReason)
    }

    @Test fun `with the camera or depth not ready it still does what it was told`() {
        // The regression from the floor: the gates that meant "no picture yet" returned a refusal, so
        // "go forward" did nothing at all until the depth module warmed up.
        val noDepth = controller.update(blankScene(), true, true, false, true)
        assertEquals(AvoidanceState.FORWARD, noDepth.state)
        assertEquals(MotorSettings().forward.frame(), noDepth.wheelSpeeds.frame())
        assertFalse(noDepth.obstacleSensingAvailable)

        assertEquals(AvoidanceState.FORWARD, controller.update(blankScene(), true, false, false, true).state)
        assertEquals(AvoidanceState.FORWARD, controller.update(null, true, true, true, true).state)
    }

    @Test fun `with nothing in the frame it does the action it was asked for`() {
        val forward = update(plane = false)
        assertEquals(AvoidanceState.FORWARD, forward.state)
        assertEquals(MotorSettings().forward.frame(), forward.wheelSpeeds.frame())
        assertFalse("and it says it is not watching the path", forward.obstacleSensingAvailable)
    }

    @Test fun `with nothing in the frame a route lean still steers`() {
        val lean = controller.update(scene(plane = false), true, true, true, true, DesiredTravelDirection.RIGHT)
        assertEquals(AvoidanceState.SLOW, lean.state)
        assertTrue("leaning right while it cannot see", lean.steeringBias > 0)
        assertFalse(lean.obstacleSensingAvailable)
    }

    @Test fun `with a frame that has gone quiet the last reading stands`() {
        // A stale frame is not a clear road: what the robot was doing continues, and a box seen a
        // moment ago is still a box. The watchdog says nothing about sensing - only about the link.
        assertEquals(AvoidanceState.FORWARD, update().state)
        now += ObstacleAvoidanceController.SCENE_STALE_TIMEOUT_MS + 1
        assertEquals(AvoidanceState.FORWARD, controller.update(scene(), true, true, true, true).state)
        assertEquals(
            "the link is all the watchdog refuses for",
            null,
            controller.watchdog(enabled = true, cameraAvailable = false, depthAvailable = false, robotConnected = true),
        )
    }

    @Test fun `a drop is ignored, because it false-fires on this floor`() {
        val decision = update(drop = true, dropZone = SceneZone.CENTER, plane = false)
        assertEquals(AvoidanceState.FORWARD, decision.state)
    }

    @Test fun `every command that moves clears the motor floor`() {
        listOf(
            update(),
            controller.update(scene(), true, true, true, true, DesiredTravelDirection.RIGHT),
            controller.update(scene(), true, true, true, true, DesiredTravelDirection.PIVOT_LEFT),
            controller.update(scene(), true, true, true, true, DesiredTravelDirection.PIVOT_RIGHT),
        ).flatMap { listOf(it.wheelSpeeds.left, it.wheelSpeeds.right) }
            .filter { it != 0 }
            .forEach { assertTrue(kotlin.math.abs(it) >= ObstacleAvoidanceController.MIN_EFFECTIVE_MOTOR_SPEED) }
    }

    private fun blankScene() = SceneAwarenessResult()

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
