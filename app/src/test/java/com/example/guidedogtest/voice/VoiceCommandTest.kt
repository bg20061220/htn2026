package com.example.guidedogtest.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The commands that bypass the model.
 *
 * These are the ones a person says when they want the robot to answer *now*, and when the phone may
 * have no data at all, so the mapping is pinned here rather than left to a prompt: a phrase that
 * stops matching would still compile, and would still look like it worked, right up until someone
 * said it at a curb.
 */
class VoiceCommandTest {

    @Test
    fun stoppingIsRecognisedHoweverItIsSaid() {
        assertEquals(RobotCommand.Stop, localCommandFor("stop"))
        assertEquals(RobotCommand.Stop, localCommandFor("Stop."))
        assertEquals(RobotCommand.Stop, localCommandFor("  HALT  "))
    }

    @Test
    fun goingAndGoingForwardAreDifferentCommands() {
        // "go" starts following a planned route; "forward" drives without one.
        assertEquals(RobotCommand.Go, localCommandFor("go"))
        assertEquals(RobotCommand.Go, localCommandFor("start following"))
        assertEquals(RobotCommand.Forward, localCommandFor("go forward"))
        assertEquals(RobotCommand.Forward, localCommandFor("forward"))
        assertEquals(RobotCommand.Forward, localCommandFor("straight ahead"))
    }

    @Test
    fun turnsAreRecognisedLocallyToo() {
        assertEquals(RobotCommand.Turn("left"), localCommandFor("turn left"))
        assertEquals(RobotCommand.Turn("left"), localCommandFor("left"))
        assertEquals(RobotCommand.Turn("right"), localCommandFor("Turn Right"))
    }

    @Test
    fun anythingElseGoesToTheModel() {
        assertNull(localCommandFor("take me to the library"))
        assertNull(localCommandFor("where is the nearest coffee shop"))
        assertNull(localCommandFor(""))
    }
}
