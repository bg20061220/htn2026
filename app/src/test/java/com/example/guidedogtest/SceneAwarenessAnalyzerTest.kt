package com.example.guidedogtest

import com.example.guidedogtest.ocr.*
import org.junit.Assert.*
import org.junit.Test

/**
 * What each of the three boxes sees.
 *
 * The contract under test: a box reports what is *standing up* in it. The floor is modelled first
 * (the bottom rows of the boxes fit a ground plane) and anything matching that plane is support, not
 * an obstacle - which is the whole difference between a robot that walks down a corridor and one
 * that reports an obstacle at arm's length from a flat carpet.
 */
class SceneAwarenessAnalyzerTest {

    /** A floor that gets nearer down the image, as a real one does in a perspective projection. */
    private fun floorDepth(y: Float) = 1f / (0.25f + 1.2f * y)

    /**
     * What a depth camera returns for a surface at [surfaceMeters]: the nearest thing along that ray.
     * A wall or an object therefore reads as itself where it stands in front of the floor, and as the
     * floor below it - which is what makes these scenes worth testing against.
     */
    private fun nearest(y: Float, surfaceMeters: Float) = minOf(floorDepth(y), surfaceMeters)

    private fun analyze(sample: (Float, Float) -> Float?) = SceneAwarenessAnalyzer().analyzeSamplesForTest(sample = sample)

    @Test fun `a flat floor fills no box`() {
        val result = analyze { _, y -> floorDepth(y) }

        assertTrue("the floor has to be modelled at all", result.supportPlaneDetected)
        assertEquals(SceneZoneState.CLEAR, result.leftState)
        assertEquals(SceneZoneState.CLEAR, result.centerState)
        assertEquals(SceneZoneState.CLEAR, result.rightState)
        // Nothing is standing up in any box, so no box has a distance to report: the floor is support.
        assertNull(result.leftDistanceMeters)
        assertNull(result.centerDistanceMeters)
        assertNull(result.rightDistanceMeters)
        assertFalse(result.dropDetected)
        assertEquals(RecommendedDirection.FORWARD, result.recommendedDirection)
    }

    @Test fun `a floor that ends is a drop, not a clear path`() {
        // The bottom of the centre box reads far past the plane: the floor is not there any more.
        val result = analyze { x, y ->
            if (y > 0.7f && x in SceneAwarenessAnalyzer.zoneLeft(SceneZone.CENTER)..SceneAwarenessAnalyzer.zoneRight(SceneZone.CENTER)) {
                floorDepth(y) + 1.2f
            } else {
                floorDepth(y)
            }
        }

        assertTrue(result.centerDrop)
        assertTrue(result.dropDetected)
        assertEquals(RecommendedDirection.STOP, result.recommendedDirection)
    }

    @Test fun `something standing on the floor blocks its box and only its box`() {
        // A 1 m object in the middle third, nearer than the floor line behind it.
        val result = analyze { x, y ->
            if (x in 0.40f..0.60f) nearest(y, 0.5f) else floorDepth(y)
        }

        assertEquals(SceneZoneState.BLOCKED, result.centerState)
        assertNotNull(result.centerDistanceMeters)
        assertTrue("the object reads at about its own distance", result.centerDistanceMeters!! <= 0.6f)
        assertEquals(SceneZoneState.CLEAR, result.leftState)
        assertEquals(SceneZoneState.CLEAR, result.rightState)
    }

    @Test fun `a wall beside the robot is a side reading and not a front obstacle`() {
        // Left third near (a wall), everything else floor: this is a hallway, and its wall is what a
        // hallway centring is measured on.
        val result = analyze { x, y ->
            if (x < 0.35f) nearest(y, 0.9f) else floorDepth(y)
        }

        assertNotNull(result.leftDistanceMeters)
        assertEquals(0.9f, result.leftDistanceMeters!!, 0.05f)
        assertEquals(SceneZoneState.CLEAR, result.centerState)
        assertEquals(SceneZoneState.CLEAR, result.rightState)
        assertNull("the floor on the right is not a reading", result.rightDistanceMeters)
    }

    @Test fun `the nearer of two walls is the one that is reported`() {
        val result = analyze { x, y -> if (x < 0.35f) nearest(y, 0.6f) else floorDepth(y) }

        assertEquals(SceneZoneState.BLOCKED, result.leftState)
        assertEquals(RecommendedDirection.RIGHT, result.recommendedDirection)
    }

    @Test fun `no floor model means nothing can be judged`() {
        // Every reading is missing: no support rows, so no plane, and the boxes stay unknown.
        val result = analyze { _, _ -> null }

        assertFalse(result.supportPlaneDetected)
        assertTrue(result.seesNothing)
        assertNull(result.leftDistanceMeters)
        assertEquals(RecommendedDirection.UNKNOWN, result.recommendedDirection)
    }

    @Test fun `without a floor model the distances are still reported, unjudged`() {
        // A wall 1.2 m ahead and floor everywhere else, but the floor is only in the top rows - so no
        // support row fits a plane. Nothing may be judged from that, and the states stay UNKNOWN (the
        // controller refuses to drive), but "the camera sees 1.2 m" is exactly what you want to see
        // when the boxes sit grey.
        // A wall 1.2 m away filling the bottom rows of every box: the support rows all read the same
        // depth, which is a wall and not a floor (a floor gets nearer as the frame goes down), so no
        // plane fits and everything stays UNKNOWN.
        val result = analyze { _, y -> if (y > 0.6f) 1.2f else floorDepth(y) }

        assertFalse(result.supportPlaneDetected)
        assertEquals(SceneZoneState.UNKNOWN, result.centerState)
        assertNotNull(result.centerDistanceMeters)
        assertTrue(result.centerDistanceMeters!! <= 1.3f)
    }

    @Test fun `the side with more room is the recommendation`() {
        val result = analyze { x, y ->
            when {
                x < 0.35f -> nearest(y, 1.2f)      // left wall close
                x > 0.65f -> nearest(y, 2.4f)      // right wall far
                else -> floorDepth(y)
            }
        }

        // The left box has a wall in it; the right box held nothing but floor, which is the more open
        // side and reads as no distance at all rather than as a very far obstacle.
        assertEquals(RecommendedDirection.RIGHT, result.recommendedDirection)
        assertNotNull(result.leftDistanceMeters)
        assertNull(result.rightDistanceMeters)
    }

    @Test fun `the boxes are read from every frame the camera offers, not one in three`() {
        val analyzer = SceneAwarenessAnalyzer()
        val depth = DepthFrame(
            width = 2,
            height = 2,
            millimeters = IntArray(4) { 1_000 },
            timestampNanos = 1_000_000_000L,
            textureCorners = FloatArray(6),
            displayTextureCorners = FloatArray(6),
        )
        assertNotNull("the first frame is always read", analyzer.analyzeIfDue(depth))
        // 125 ms later, the rate a depth frame actually arrives at: this has to be analysed too, or
        // the robot is reacting to a picture three frames old.
        val later = depth.copy(timestampNanos = depth.timestampNanos + 125_000_000L)
        assertNotNull(analyzer.analyzeIfDue(later))
        // ...but a burst of frames at the same instant is not re-analysed.
        assertNull(analyzer.analyzeIfDue(later.copy(timestampNanos = later.timestampNanos + 1_000_000L)))
    }

    @Test fun `the floor line marks where the floor stops and the world begins`() {
        // Nothing but floor in view: the whole box is support, so the line sits at the highest sample
        // row - which is a little below the top edge, because the rows are centres, not edges.
        val flat = analyze { _, y -> floorDepth(y) }
        assertTrue("all floor means the line is at the top", flat.floorLineFraction!! <= SceneAwarenessAnalyzer.ZONE_TOP + 0.05f)
        assertTrue("and never above the box", flat.floorLineFraction!! >= SceneAwarenessAnalyzer.ZONE_TOP)

        // A wall 0.9 m out, standing in the upper rows of every box (nearer than the floor line up
        // there, farther than it near the robot's feet): the floor stops partway up the frame, and
        // that is where the drawn box has to end.
        val room = analyze { _, y -> nearest(y, 0.9f) }
        val line = room.floorLineFraction
        assertNotNull(line)
        assertTrue("the floor ends below the top of the box", line!! > SceneAwarenessAnalyzer.ZONE_TOP)
        assertTrue("and above its bottom", line < SceneAwarenessAnalyzer.ZONE_BOTTOM)
    }

    @Test fun `a hole in one box is not a hole in the middle`() {
        val leftOnly = analyze { x, y ->
            if (x < SceneAwarenessAnalyzer.zoneRight(SceneZone.LEFT)) {
                nearest(y, 1.6f) + if (y > 0.7f) 1.5f else 0f
            } else {
                nearest(y, 1.6f)
            }
        }
        assertTrue(leftOnly.leftDrop)
        assertFalse("a hole beside the robot is not ahead of it", leftOnly.centerDrop)
        assertNotEquals(RecommendedDirection.STOP, leftOnly.recommendedDirection)
    }

    @Test fun `the boxes are the three thirds of the region, and they do not overlap`() {
        val zones = SceneAwarenessAnalyzer.ZONES
        assertEquals(3, zones.size)
        assertEquals(SceneAwarenessAnalyzer.REGION_LEFT, SceneAwarenessAnalyzer.zoneLeft(zones.first()), 0.0001f)
        assertEquals(SceneAwarenessAnalyzer.REGION_RIGHT, SceneAwarenessAnalyzer.zoneRight(zones.last()), 0.0001f)
        zones.zipWithNext { left, right ->
            assertEquals(SceneAwarenessAnalyzer.zoneRight(left), SceneAwarenessAnalyzer.zoneLeft(right), 0.0001f)
        }
    }
}
