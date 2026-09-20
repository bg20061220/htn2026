package com.example.guidedogtest

import com.example.guidedogtest.voice.EmergencyStopMatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyStopMatcherTest {
    @Test fun `accepts clearly intended stop phrases`() {
        listOf("Stop", "stop robot", "ROBOT, STOP!", "goose stop", "please stop")
            .forEach { assertTrue(it, EmergencyStopMatcher.matches(it)) }
    }

    @Test fun `rejects stop embedded in ordinary speech`() {
        listOf("next stop is Main Street", "do not stop navigation", "unstoppable", "stop by the shop")
            .forEach { assertFalse(it, EmergencyStopMatcher.matches(it)) }
    }
}
