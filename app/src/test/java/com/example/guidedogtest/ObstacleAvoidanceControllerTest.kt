package com.example.guidedogtest

import com.example.guidedogtest.ocr.AvoidanceState
import com.example.guidedogtest.ocr.ObstacleAvoidanceController
import com.example.guidedogtest.ocr.SceneAwarenessResult
import com.example.guidedogtest.ocr.SceneZoneState
import org.junit.Assert.assertEquals
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

    private fun update(scene: SceneAwarenessResult) = controller.update(scene, true, true, true, true)

    private fun scene(left: Float, center: Float, right: Float, centerState: SceneZoneState) =
        SceneAwarenessResult(
            left, center, right,
            SceneZoneState.CLEAR, centerState, SceneZoneState.CLEAR,
            depthTimestampNanos = now * 1_000_000
        )
}
