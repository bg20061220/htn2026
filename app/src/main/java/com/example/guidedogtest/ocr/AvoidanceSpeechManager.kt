package com.example.guidedogtest.ocr

import android.os.SystemClock
import com.example.guidedogtest.voice.VoicePriority

data class AvoidanceSpeech(val text: String, val priority: VoicePriority)

/** Converts real controller state transitions into concise, rate-limited spoken guidance. */
class AvoidanceSpeechManager(
    private val clockMillis: () -> Long = SystemClock::elapsedRealtime,
    private val speak: (String, VoicePriority) -> Boolean,
) {
    private var lastSignature: String? = null
    private var lastSpokenMillis = Long.MIN_VALUE

    fun consider(decision: AvoidanceDecision, scene: SceneAwarenessResult?): AvoidanceSpeech? {
        val message = messageFor(decision, scene) ?: run {
            if (decision.state == AvoidanceState.FORWARD) lastSignature = "FORWARD"
            return null
        }
        val signature = "${decision.state}:${message.text}"
        val now = clockMillis()
        if (signature == lastSignature) return null
        if (lastSpokenMillis != Long.MIN_VALUE && now - lastSpokenMillis < cooldownFor(message.priority)) return null
        if (!speak(message.text, message.priority)) return null
        lastSignature = signature
        lastSpokenMillis = now
        return message
    }

    fun reset() { lastSignature = null; lastSpokenMillis = Long.MIN_VALUE }

    internal fun messageFor(decision: AvoidanceDecision, scene: SceneAwarenessResult?): AvoidanceSpeech? {
        if (decision.state == AvoidanceState.STOPPED) return when {
            decision.action == "EMERGENCY STOP" -> null
            decision.action == "STOP: DROP" || scene?.dropDetected == true ->
                AvoidanceSpeech("Drop ahead. Stopping.", VoicePriority.UNSAFE_PATH)
            decision.action == "STOP: NO SAFE CORRIDOR" ->
                AvoidanceSpeech("Path blocked. Stopping.", VoicePriority.UNSAFE_PATH)
            else -> null
        }
        return when (decision.state) {
            AvoidanceState.TURN_LEFT -> AvoidanceSpeech(
                if (scene?.centerState == SceneZoneState.BLOCKED) "Obstacle ahead. Moving left."
                else "Obstacle on the right. Moving left.", VoicePriority.AVOIDANCE)
            AvoidanceState.TURN_RIGHT -> AvoidanceSpeech(
                if (scene?.centerState == SceneZoneState.BLOCKED) "Obstacle ahead. Moving right."
                else "Obstacle on the left. Moving right.", VoicePriority.AVOIDANCE)
            else -> null
        }
    }

    private fun cooldownFor(priority: VoicePriority) = if (priority == VoicePriority.UNSAFE_PATH) 1_500L else 3_000L
    companion object { const val AVOIDANCE_COOLDOWN_MS = 3_000L; const val UNSAFE_COOLDOWN_MS = 1_500L }
}
