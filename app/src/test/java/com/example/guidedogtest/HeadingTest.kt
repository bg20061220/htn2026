package com.example.guidedogtest

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The compass maths: the raw phone heading, and the single mounting offset that turns it into the
 * robot's forward direction.
 *
 * A wrong matrix index rotates the heading by a multiple of 90 degrees and a wrong offset flips it by
 * 180 - both make the robot turn the wrong way - so both halves are pinned here. The matrix layout is
 * Android's: row-major, device -> world (east, north, up), with device `+Y` (the phone's portrait top
 * edge) in column 1.
 */
class HeadingTest {

    /**
     * A phone lying flat, screen up, with its **portrait top edge** pointing at [topEdgeDegrees].
     *
     * The robot's mount is landscape, so this is not the robot's forward direction - it is what the
     * compass reports before [ROBOT_HEADING_OFFSET_DEGREES] is applied.
     */
    private fun flat(topEdgeDegrees: Double): FloatArray {
        val h = Math.toRadians(topEdgeDegrees)
        return floatArrayOf(
            cos(h).toFloat(), sin(h).toFloat(), 0f, // world east  = (cos, sin, 0)
            (-sin(h)).toFloat(), cos(h).toFloat(), 0f, // world north = (-sin, cos, 0)
            0f, 0f, 1f, // world up    = (0, 0, 1)
        )
    }

    @Test
    fun rawHeadingReadsThePhonesTopEdge() {
        assertEquals(0.0, rawPhoneHeading(flat(0.0), 0.0), 0.5)
        assertEquals(45.0, rawPhoneHeading(flat(45.0), 0.0), 0.5)
        assertEquals(90.0, rawPhoneHeading(flat(90.0), 0.0), 0.5)
        assertEquals(180.0, rawPhoneHeading(flat(180.0), 0.0), 0.5)
        assertEquals(270.0, rawPhoneHeading(flat(270.0), 0.0), 0.5)
    }

    @Test
    fun rawHeadingIsCorrectedForDeclination() {
        // Waterloo sits about 9.5 degrees west, so true heading = magnetic - 9.5.
        assertEquals(350.5, rawPhoneHeading(flat(0.0), -9.5), 0.001)
        assertEquals(80.5, rawPhoneHeading(flat(90.0), -9.5), 0.001)
    }

    @Test
    fun theMountingOffsetTurnsThePhoneIntoTheRobot() {
        // Landscape mount: the phone's top edge points sideways, so "forward" is 90 degrees away.
        assertEquals(120.0, robotHeading(30.0, 90.0), 0.001)
        assertEquals(300.0, robotHeading(30.0, -90.0), 0.001)

        // Which is the whole of the +/-90 choice: the two candidates are 180 degrees apart.
        assertEquals(180.0, robotHeading(120.0, 90.0) - robotHeading(120.0, -90.0), 0.001)
    }

    @Test
    fun theDefaultOffsetIsTheConstant() {
        assertEquals(ROBOT_HEADING_OFFSET_DEGREES, robotHeading(0.0), 0.001)

        // The mounted case: the phone's top edge points west, so with a +90 mount the robot's forward
        // direction - its right edge - points north.
        val raw = rawPhoneHeading(flat(270.0), 0.0)
        assertEquals(0.0, robotHeading(raw), 0.001)
    }

    @Test
    fun theOffsetIsAppliedExactlyOnce() {
        // Top edge south, +90 mount: the robot points west. Applying the offset twice would say east.
        val raw = rawPhoneHeading(flat(180.0), 0.0)
        assertEquals(270.0, robotHeading(raw, 90.0), 0.001)
    }

    @Test
    fun headingsStayInZeroToThreeSixty() {
        assertEquals(30.0, robotHeading(300.0, 90.0), 0.001)
        assertEquals(280.0, robotHeading(10.0, -90.0), 0.001)
        assertEquals(0.0, robotHeading(0.0, 360.0), 0.001)
        assertEquals(350.0, rawPhoneHeading(flat(5.0), -15.0), 0.001)
    }
}
