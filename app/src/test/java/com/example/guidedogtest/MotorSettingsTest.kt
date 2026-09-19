package com.example.guidedogtest

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The manual drive values: what the Configure Robot page edits and what the transmit loop sends.
 *
 * The defaults matter as much as the mapping - they are the numbers calibrated on the floor - and
 * the fallback matters most of all, because anything unrecognised has to release the motors.
 */
class MotorSettingsTest {

    private val defaults = MotorSettings()

    @Test
    fun defaultsAreTheCalibratedOnes() {
        // This chassis drives crooked at equal PWM: forward is a pair, not a number.
        assertEquals(WheelSpeeds(Drive.SPEED, Drive.SPEED - Drive.RIGHT_TRIM), defaults.forward)
        assertEquals("c180,128\n", defaults.forward.frame())

        // Pivots are asymmetric for the same reason - the right pair is weaker.
        assertEquals(WheelSpeeds(-190, 170), defaults.left)
        assertEquals(WheelSpeeds(190, -180), defaults.right)
    }

    @Test
    fun everyManualCommandHasItsOwnPair() {
        val settings = defaults.copy(
            forward = WheelSpeeds(200, 190),
            left = WheelSpeeds(-90, 90),
            right = WheelSpeeds(90, -90),
        )

        assertEquals("c200,190\n", settings.speedsFor("FORWARD").frame())
        assertEquals("c-90,90\n", settings.speedsFor("LEFT").frame())
        assertEquals("c90,-90\n", settings.speedsFor("RIGHT").frame())
    }

    @Test
    fun anythingElseReleasesTheMotors() {
        // STOP, an empty command, a stale route message - all of them stop the car.
        assertEquals(WheelSpeeds(0, 0), defaults.speedsFor("STOP"))
        assertEquals(WheelSpeeds(0, 0), defaults.speedsFor(""))
        assertEquals(WheelSpeeds(0, 0), defaults.speedsFor("Route requested: Union Station"))
    }

    @Test
    fun editingOneCommandLeavesTheOthersAlone() {
        val edited = defaults.withSpeeds("LEFT", WheelSpeeds(-140, 140))

        assertEquals(WheelSpeeds(-140, 140), edited.left)
        assertEquals(defaults.forward, edited.forward)
        assertEquals(defaults.right, edited.right)
    }
}
