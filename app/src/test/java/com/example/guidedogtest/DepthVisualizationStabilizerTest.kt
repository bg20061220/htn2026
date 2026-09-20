package com.example.guidedogtest

import com.example.guidedogtest.ocr.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The display's own persistence, and only the display's.
 *
 * A box that suddenly reads safer has to be confirmed twice before the screen believes it, while a
 * box that reads more dangerous - or finds a hole - is shown at once. The safety controller never
 * sees any of this: it is fed the raw scene, so a held-over display can never look like a clear path.
 */
class DepthVisualizationStabilizerTest {
    private var now = 1_000L
    private val stabilizer = DepthVisualizationStabilizer { now }

    @Test fun `the last reading is held, then replaced by an unknown one`() {
        stabilizer.accept(scene(SceneZoneState.CLEAR))
        now += DepthVisualizationStabilizer.DEPTH_VISUAL_HOLD_MS
        val held = stabilizer.current(true, true, false)
        assertEquals(DepthVisualState.UPDATING, held.state)
        assertEquals(SceneZoneState.CLEAR, held.scene.centerState)

        now++
        val stale = stabilizer.current(true, true, false)
        assertEquals(DepthVisualState.STALE, stale.state)
        assertEquals(SceneZoneState.UNKNOWN, stale.scene.centerState)
    }

    @Test fun `a hazard appears at once but a clear reading has to be confirmed`() {
        stabilizer.accept(scene(SceneZoneState.CLEAR))
        assertEquals(SceneZoneState.BLOCKED, stabilizer.accept(scene(SceneZoneState.BLOCKED, 2L)).scene.centerState)
        assertEquals(SceneZoneState.BLOCKED, stabilizer.accept(scene(SceneZoneState.CLEAR, 3L)).scene.centerState)
        assertEquals(SceneZoneState.CLEAR, stabilizer.accept(scene(SceneZoneState.CLEAR, 4L)).scene.centerState)
    }

    @Test fun `a drop is shown immediately and clears with the same confirmation`() {
        stabilizer.accept(scene(SceneZoneState.CLEAR))
        val dropped = stabilizer.accept(scene(SceneZoneState.CLEAR, 2L).copy(centerDrop = true, dropDetected = true))
        assertTrue(dropped.scene.centerDrop)
        assertEquals(RecommendedDirection.STOP, dropped.scene.recommendedDirection)

        val recovered = stabilizer.accept(scene(SceneZoneState.CLEAR, 3L))
        assertTrue("a drop that has just vanished is not believed yet", recovered.scene.centerDrop)
        assertTrue(!stabilizer.accept(scene(SceneZoneState.CLEAR, 4L)).scene.centerDrop)
    }

    @Test fun `without depth support the display is unknown`() {
        stabilizer.accept(scene(SceneZoneState.CLEAR))
        val unsupported = stabilizer.current(false, true, false)
        assertEquals(DepthVisualState.UNSUPPORTED, unsupported.state)
        assertEquals(SceneZoneState.UNKNOWN, unsupported.scene.centerState)
    }

    private fun scene(state: SceneZoneState, timestamp: Long = 1L) = SceneAwarenessResult(
        leftState = state,
        centerState = state,
        rightState = state,
        leftDistanceMeters = 1.0f,
        centerDistanceMeters = 1.0f,
        rightDistanceMeters = 1.0f,
        depthTimestampNanos = timestamp,
        supportPlaneDetected = true,
    )
}
