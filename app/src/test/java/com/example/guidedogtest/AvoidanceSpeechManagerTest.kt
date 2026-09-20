package com.example.guidedogtest

import com.example.guidedogtest.ocr.*
import com.example.guidedogtest.voice.VoicePriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the walker hears from the obstacle layer.
 *
 * One thing is worth saying out loud: the robot has stopped because something is in the middle box.
 * Everything else it does - driving on, leaning with a route, pivoting because it was asked to - is
 * either silent or already covered by the voice layer that asked for it.
 */
class AvoidanceSpeechManagerTest {
    private var now = 1_000L
    private val spoken = mutableListOf<Pair<String, VoicePriority>>()
    private val manager = AvoidanceSpeechManager({ now }) { text, priority ->
        spoken += text to priority
        true
    }

    private fun obstacleStop() = AvoidanceDecision(
        state = AvoidanceState.STOPPED,
        wheelSpeeds = WheelSpeeds(0, 0),
        action = "STOP: OBSTACLE AHEAD",
        stopReason = AvoidanceStop.OBSTACLE,
    )

    @Test fun `an obstacle stop is spoken once, and not again while it holds`() {
        val stop = obstacleStop()
        val first = manager.consider(stop)
        assertEquals("Obstacle ahead. Stopping.", first?.text)
        assertEquals(VoicePriority.UNSAFE_PATH, first?.priority)

        // The stop is held frame after frame, so the same words must not repeat frame after frame.
        assertNull(manager.consider(stop))
        now += AvoidanceSpeechManager.UNSAFE_COOLDOWN_MS
        assertNull("the same stop is not re-announced at all", manager.consider(stop))
        assertEquals(1, spoken.size)
    }

    @Test fun `driving on is not announced, and clears the way for the next stop`() {
        assertEquals("Obstacle ahead. Stopping.", manager.consider(obstacleStop())?.text)
        assertNull(manager.consider(AvoidanceDecision(AvoidanceState.FORWARD, WheelSpeeds(180, 128), "FORWARD")))
        now += AvoidanceSpeechManager.UNSAFE_COOLDOWN_MS
        assertEquals("Obstacle ahead. Stopping.", manager.consider(obstacleStop())?.text)
        assertEquals(2, spoken.size)
    }

    @Test fun `a turn that was asked for is not spoken here`() {
        // The voice layer that asked for it has already acknowledged, and the route speaks its own cues.
        assertNull(manager.consider(AvoidanceDecision(AvoidanceState.TURN_LEFT, WheelSpeeds(-140, 140), "PIVOT LEFT")))
        assertNull(manager.consider(AvoidanceDecision(AvoidanceState.SLOW, WheelSpeeds(158, 113), "STEER RIGHT")))
    }

    @Test fun `a dead link is not called a blocked path`() {
        // The link's own status line says "disconnected"; a second voice saying "path blocked" for a
        // dead cable would be a lie.
        val unlinked = AvoidanceDecision(
            state = AvoidanceState.STOPPED,
            wheelSpeeds = WheelSpeeds(0, 0),
            action = "STOP: ROBOT DISCONNECTED",
            stopReason = AvoidanceStop.UNSAFE,
        )
        assertNull(manager.consider(unlinked))
    }

    @Test fun `a stop with no reason is not spoken as one`() {
        val idle = AvoidanceDecision(AvoidanceState.IDLE, WheelSpeeds(0, 0), "AUTO OFF")
        assertNull(manager.consider(idle))
    }
}
