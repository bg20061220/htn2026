package com.example.guidedogtest

import com.example.guidedogtest.ocr.AvoidanceState
import com.example.guidedogtest.ocr.ObstacleAvoidanceController
import com.example.guidedogtest.ocr.SceneAwarenessResult
import com.example.guidedogtest.ocr.SceneZoneState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObstacleAvoidanceControllerTest {
    private var now = 1_000L
    private val controller = ObstacleAvoidanceController { now }

    @Test fun `holds selected turn until center clears`() {
        val blocked = scene(0.7f, 0.6f, 2.0f, SceneZoneState.BLOCKED)
        assertEquals(AvoidanceState.TURN_RIGHT, update(blocked).state)

        // Left becomes marginally better, but the controller must not oscillate.
        assertEquals(AvoidanceState.TURN_RIGHT, update(scene(2.1f, 0.7f, 2.0f, SceneZoneState.BLOCKED)).state)
        assertEquals(AvoidanceState.SLOW, update(scene(2.0f, 1.8f, 1.9f, SceneZoneState.CLEAR)).state)
    }

    @Test fun `stale depth and unknown scene stop`() {
        update(scene(2f, 2f, 2f, SceneZoneState.CLEAR))
        now += ObstacleAvoidanceController.SCENE_STALE_TIMEOUT_MS + 1
        assertEquals(AvoidanceState.STOPPED, controller.watchdog(true, true, true, true)?.state)
        assertEquals(AvoidanceState.STOPPED, update(SceneAwarenessResult()).state)
    }

    @Test fun `arming creeps forward before it starts steering`() {
        val creep = update(open())
        assertEquals(AvoidanceState.CREEP, creep.state)
        assertEquals(ObstacleAvoidanceController.AUTO_CREEP_SPEED, creep.wheelSpeeds.left)

        now += ObstacleAvoidanceController.CREEP_DURATION_MS
        assertEquals(AvoidanceState.FORWARD, update(open()).state)
    }

    @Test fun `the creep clock only starts once the robot may actually move`() {
        // Armed while depth is still coming up: held stopped, and the creep must not burn down.
        assertEquals(
            AvoidanceState.STOPPED,
            controller.update(scene(4f, 4f, 4f, SceneZoneState.CLEAR), true, true, false, true).state
        )
        now += ObstacleAvoidanceController.CREEP_DURATION_MS * 3

        assertEquals(AvoidanceState.CREEP, update(open()).state)
    }

    @Test fun `a blocked path cancels the creep instead of driving into it`() {
        val decision = update(scene(2.0f, 0.6f, 0.5f, SceneZoneState.BLOCKED))
        assertEquals(AvoidanceState.TURN_LEFT, decision.state)
    }

    @Test fun `disarming and re-arming creeps again`() {
        driveOnPastTheCreep()
        assertEquals(AvoidanceState.FORWARD, update(open()).state)

        assertEquals(AvoidanceState.IDLE, controller.update(null, false, true, true, true).state)
        assertEquals(AvoidanceState.CREEP, update(open()).state)
    }

    @Test fun `driving on veers toward whichever side has more room`() {
        driveOnPastTheCreep()
        // The straight pair is asymmetric on this chassis, so a veer is only meaningful measured
        // against it: comparing the two wheels to each other says nothing about which way it curves.
        val straight = update(open()).wheelSpeeds

        // Right is nearer, so the open side is the left one.
        val left = update(scene(3f, 4f, 1.0f, SceneZoneState.CLEAR))
        assertEquals("FORWARD / VEER LEFT", left.action)
        assertTrue(left.wheelSpeeds.left < straight.left)
        assertEquals(straight.right, left.wheelSpeeds.right)

        val right = update(scene(1.0f, 4f, 3f, SceneZoneState.CLEAR))
        assertEquals("FORWARD / VEER RIGHT", right.action)
        assertTrue(right.wheelSpeeds.right < straight.right)
        assertEquals(straight.left, right.wheelSpeeds.left)
    }

    @Test fun `open space drives straight rather than chasing depth noise`() {
        driveOnPastTheCreep()

        // Metres apart, but nothing is near enough to be worth avoiding.
        assertEquals("FORWARD", update(scene(3.0f, 5f, 6.0f, SceneZoneState.CLEAR)).action)
    }

    /**
     * Arms the controller and lets the opening creep expire, leaving it in normal driving.
     *
     * The creep clock only starts on the first frame the robot may move, so the time has to be
     * advanced after that frame, not before it.
     */
    private fun driveOnPastTheCreep() {
        update(open())
        now += ObstacleAvoidanceController.CREEP_DURATION_MS
    }

    /** A fresh wide-open scene. Fresh matters: a re-used one carries a now-stale depth timestamp. */
    private fun open() = scene(4f, 4f, 4f, SceneZoneState.CLEAR)

    private fun update(scene: SceneAwarenessResult) = controller.update(scene, true, true, true, true)

    private fun scene(left: Float, center: Float, right: Float, centerState: SceneZoneState) =
        SceneAwarenessResult(
            left, center, right,
            SceneZoneState.CLEAR, centerState, SceneZoneState.CLEAR,
            depthTimestampNanos = now * 1_000_000
        )
}
