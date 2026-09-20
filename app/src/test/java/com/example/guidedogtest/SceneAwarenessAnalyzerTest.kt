package com.example.guidedogtest

import com.example.guidedogtest.ocr.*
import org.junit.Assert.*
import org.junit.Test

class SceneAwarenessAnalyzerTest {
    private fun floorDepth(y: Float) = 1f / (0.25f + 1.2f * y)

    @Test fun `flat support floor is free and produces forward corridor`() {
        val result = SceneAwarenessAnalyzer().analyzeSamplesForTest { _, y -> floorDepth(y) }
        assertTrue(result.supportPlaneDetected); assertFalse(result.dropDetected); assertNotNull(result.freeCorridor)
        assertEquals(RecommendedDirection.FORWARD, result.recommendedDirection)
        assertFalse(result.cells.any { it.state == OccupancyState.OCCUPIED })
    }

    @Test fun `object protruding from floor occupies geometry without a label`() {
        val result = SceneAwarenessAnalyzer().analyzeSamplesForTest { x, y ->
            if (x in .40f..0.60f) (floorDepth(y) - .35f).coerceAtLeast(.25f) else floorDepth(y)
        }
        assertTrue(result.cells.any { it.column in 3..5 && it.state == OccupancyState.OCCUPIED })
        assertNotEquals(RecommendedDirection.FORWARD, result.recommendedDirection)
    }

    @Test fun `disappearing support is a drop and has no corridor`() {
        val result = SceneAwarenessAnalyzer().analyzeSamplesForTest { x, y -> if (y > .77f && x in .35f..0.65f) null else floorDepth(y) }
        assertTrue(result.dropDetected); assertNull(result.freeCorridor); assertEquals(RecommendedDirection.STOP, result.recommendedDirection)
    }

    @Test fun `corridor must be wide enough for robot plus margin`() {
        assertNull(FreeSpaceCorridorSelector.select(grid(setOf(4, 5)), 9, 4))
        assertNotNull(FreeSpaceCorridorSelector.select(grid(setOf(3, 4, 5, 6)), 9, 4))
    }

    @Test fun `left and right wide corridors are selected`() {
        assertTrue(FreeSpaceCorridorSelector.select(grid(setOf(0, 1, 2, 3)), 9, 4)!!.centerOffset < 0f)
        assertTrue(FreeSpaceCorridorSelector.select(grid(setOf(5, 6, 7, 8)), 9, 4)!!.centerOffset > 0f)
    }

    private fun grid(free: Set<Int>) = (0 until 4).flatMap { row ->
        (0 until 9).map { col -> OccupancyCell(col, row, if (col in free) OccupancyState.FREE else OccupancyState.OCCUPIED, 1.2f) }
    }
}
