package com.example.guidedogtest

import com.example.guidedogtest.ocr.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthVisualizationStabilizerTest {
    private var now = 1_000L
    private val stabilizer = DepthVisualizationStabilizer { now }

    @Test fun `last valid grid is held then replaced by complete unknown grid`() {
        stabilizer.accept(scene(OccupancyState.FREE))
        now += DepthVisualizationStabilizer.DEPTH_VISUAL_HOLD_MS
        val held = stabilizer.current(true, true, false)
        assertEquals(DepthVisualState.UPDATING, held.state)
        assertTrue(held.scene.cells.all { it.state == OccupancyState.FREE })

        now++
        val stale = stabilizer.current(true, true, false)
        assertEquals(DepthVisualState.STALE, stale.state)
        assertEquals(36, stale.scene.cells.size)
        assertTrue(stale.scene.cells.all { it.state == OccupancyState.UNKNOWN })
    }

    @Test fun `hazard appears immediately but clear needs two samples`() {
        stabilizer.accept(scene(OccupancyState.FREE))
        assertTrue(stabilizer.accept(scene(OccupancyState.OCCUPIED, 2L)).scene.cells.all {
            it.state == OccupancyState.OCCUPIED
        })
        assertTrue(stabilizer.accept(scene(OccupancyState.FREE, 3L)).scene.cells.all {
            it.state == OccupancyState.OCCUPIED
        })
        assertTrue(stabilizer.accept(scene(OccupancyState.FREE, 4L)).scene.cells.all {
            it.state == OccupancyState.FREE
        })
    }

    @Test fun `unsupported always draws unknown structure`() {
        stabilizer.accept(scene(OccupancyState.FREE))
        val unsupported = stabilizer.current(false, true, false)
        assertEquals(DepthVisualState.UNSUPPORTED, unsupported.state)
        assertTrue(unsupported.scene.cells.all { it.state == OccupancyState.UNKNOWN })
    }

    private fun scene(state: OccupancyState, timestamp: Long = 1L) = SceneAwarenessResult(
        depthTimestampNanos = timestamp,
        cells = (0 until 4).flatMap { row ->
            (0 until 9).map { column -> OccupancyCell(column, row, state, 1.2f) }
        },
        supportPlaneDetected = true,
    )
}
