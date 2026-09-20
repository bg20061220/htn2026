package com.example.guidedogtest.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals(RobotCommand.Go, localCommandFor("continue"))
        assertEquals(RobotCommand.Go, localCommandFor("keep going"))
        assertEquals(RobotCommand.Forward, localCommandFor("go forward"))
        assertEquals(RobotCommand.Forward, localCommandFor("forward"))
        assertEquals(RobotCommand.Forward, localCommandFor("move forward"))
        assertEquals(RobotCommand.Forward, localCommandFor("straight ahead"))
    }

    @Test
    fun backwardsIsRecognisedSoItCanBeRefused() {
        // The robot has no rear depth, so this command is answered with a refusal rather than
        // motion - but it has to be understood for the refusal to be possible.
        assertEquals(RobotCommand.Backward, localCommandFor("backward"))
        assertEquals(RobotCommand.Backward, localCommandFor("go backward"))
        assertEquals(RobotCommand.Backward, localCommandFor("back"))
        assertEquals(RobotCommand.Backward, localCommandFor("reverse"))
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

    @Test
    fun aCommandSaidWithTheWakeWordIsExtractedFromIt() {
        assertEquals("stop", commandAfterWakeWord("Hey Goose, stop"))
        assertEquals("take me to the library", commandAfterWakeWord("Goose take me to the library"))
        assertEquals("", commandAfterWakeWord("hey goose"))
        assertEquals("", commandAfterWakeWord("hello there"))
        // Case and trailing punctuation are the recognizer's business, and localCommandFor normalises
        // both - so what matters is that the phrase survives the round trip, not its exact spelling.
        assertEquals("Turn left.", commandAfterWakeWord("Goose. Turn left."))
        assertEquals(RobotCommand.Turn("left"), localCommandFor(commandAfterWakeWord("Goose. Turn left.")))
        assertEquals(RobotCommand.Stop, localCommandFor(commandAfterWakeWord("Goose, stop!")))
    }

    @Test
    fun aSpokenDestinationIsExtractedForTheFastPath() {
        assertEquals("the library", destinationRequestFor("take me to the library"))
        assertEquals("200 University Avenue", destinationRequestFor("go to 200 University Avenue"))
        assertEquals("Room 204", destinationRequestFor("take me to Room 204?"))
        // Only the fixed phrasings; anything else is the model's job.
        assertNull(destinationRequestFor("tell me about Room 204"))
        assertNull(destinationRequestFor("take me to"))
    }

    @Test
    fun theWakeWordIsFoundInTheMiddleOfASentence() {
        // Matched on partial results too, so it has to work before the sentence is finished.
        assertTrue(containsWakeWord("hey goose"))
        assertTrue(containsWakeWord("GOOSE, take me home"))

        // ...but half a wake word is not a wake word: firing on "goo" would fire on anything.
        assertFalse(containsWakeWord("okay goo"))
        assertFalse(containsWakeWord("hello there"))
        assertFalse(containsWakeWord(""))
    }
}
