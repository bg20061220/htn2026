package com.example.guidedogtest

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The frame is the contract with the ESP32 firmware - `c<left>,<right>` with raw PWM values - so it
 * is worth pinning down. What the numbers *are* is no longer decided here: the manual commands come
 * from [MotorSettings], and the follower ramps its own.
 */
class DriveTest {

    @Test
    fun framesCarryTheWheelPairVerbatim() {
        assertEquals("c180,128\n", Drive.frame(180, 128))
        assertEquals("c-190,170\n", Drive.frame(-190, 170))
        // The follower's ramped pivot: a trimmed spin would be an arc, not a rotation.
        assertEquals("c100,-100\n", Drive.frame(100, -100))
    }

    @Test
    fun theStopFrameIsBothWheelsReleased() {
        assertEquals("c0,0\n", Drive.STOP_FRAME)
        assertEquals(Drive.STOP_FRAME, Drive.frame(0, 0))
    }
}
