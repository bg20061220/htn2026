package com.example.guidedogtest

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The compass maths, checked against rotation matrices built by hand for the mounted attitude.
 *
 * This is the one place a wrong index silently negates the heading, and a robot steering on a
 * negated heading turns the wrong way - which is exactly the bug these tests were written for.
 * The matrix layout is Android's: row-major, device -> world (east, north, up).
 *
 * The robot's mount is a phone lying flat with its camera end forward, so that is the attitude
 * these cover. See [headingFromRotation] for what to change if it is ever mounted upright.
 */
class HeadingTest {

    /**
     * Rotation matrix for the mounted phone: flat, screen up, top edge (the camera end) pointing at
     * [headingDegrees].
     *
     * Rows are the world axes in device coordinates; the columns come out as the device axes in the
     * world, so column 1 is device +Y - the axis the robot drives on.
     */
    private fun mounted(headingDegrees: Double): FloatArray {
        val h = Math.toRadians(headingDegrees)
        return floatArrayOf(
            cos(h).toFloat(), sin(h).toFloat(), 0f, // world east  = (cos, sin, 0)
            (-sin(h)).toFloat(), cos(h).toFloat(), 0f, // world north = (-sin, cos, 0)
            0f, 0f, 1f, // world up    = (0, 0, 1)
        )
    }

    @Test
    fun readsTheCameraEndOfThePhone() {
        assertEquals(0.0, headingFromRotation(mounted(0.0), 0.0), 0.5)
        assertEquals(45.0, headingFromRotation(mounted(45.0), 0.0), 0.5)
        assertEquals(90.0, headingFromRotation(mounted(90.0), 0.0), 0.5)
        assertEquals(180.0, headingFromRotation(mounted(180.0), 0.0), 0.5)
        assertEquals(270.0, headingFromRotation(mounted(270.0), 0.0), 0.5)
    }

    @Test
    fun declinationIsAddedToMagneticNorth() {
        // Waterloo sits about 9.5 degrees west, so true heading = magnetic - 9.5.
        assertEquals(350.5, headingFromRotation(mounted(0.0), -9.5), 0.001)
        assertEquals(80.5, headingFromRotation(mounted(90.0), -9.5), 0.001)
    }

    @Test
    fun headingWrapsInsteadOfGoingNegative() {
        assertEquals(355.0, headingFromRotation(mounted(5.0), -10.0), 0.001)
    }
}
