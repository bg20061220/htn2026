package com.example.guidedogtest

import com.example.guidedogtest.ocr.*
import com.example.guidedogtest.voice.VoicePriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AvoidanceSpeechManagerTest {
    private var now = 1_000L
    private val spoken = mutableListOf<Pair<String, VoicePriority>>()
    private val manager = AvoidanceSpeechManager({ now }) { text, priority ->
        spoken += text to priority
        true
    }

    @Test fun `turn is spoken only on meaningful action changes`() {
        val right = AvoidanceDecision(AvoidanceState.TURN_RIGHT, WheelSpeeds(140, -140), "TURN RIGHT")
        val sideScene = SceneAwarenessResult(centerState = SceneZoneState.CAUTION)
        assertEquals("Obstacle on the left. Moving right.", manager.consider(right, sideScene)?.text)
        assertNull(manager.consider(right, sideScene))
        now += AvoidanceSpeechManager.AVOIDANCE_COOLDOWN_MS
        val left = AvoidanceDecision(AvoidanceState.TURN_LEFT, WheelSpeeds(-140, 140), "TURN LEFT")
        assertEquals("Obstacle on the right. Moving left.", manager.consider(left, sideScene)?.text)
        assertEquals(2, spoken.size)
    }

    @Test fun `blocked center names selected free corridor`() {
        val scene = SceneAwarenessResult(centerState = SceneZoneState.BLOCKED)
        val right = AvoidanceDecision(AvoidanceState.TURN_RIGHT, WheelSpeeds(140, -140), "TURN RIGHT")
        assertEquals("Obstacle ahead. Moving right.", manager.consider(right, scene)?.text)
    }

    @Test fun `drop and no corridor are unsafe path speech`() {
        val drop = AvoidanceDecision(AvoidanceState.STOPPED, WheelSpeeds(0, 0), "STOP: DROP")
        val blocked = AvoidanceDecision(AvoidanceState.STOPPED, WheelSpeeds(0, 0), "STOP: NO SAFE CORRIDOR")
        assertEquals("Drop ahead. Stopping.", manager.consider(drop, SceneAwarenessResult(dropDetected = true))?.text)
        now += AvoidanceSpeechManager.UNSAFE_COOLDOWN_MS
        assertEquals("Path blocked. Stopping.", manager.consider(blocked, SceneAwarenessResult())?.text)
        assertEquals(VoicePriority.UNSAFE_PATH, spoken.last().second)
    }
}
