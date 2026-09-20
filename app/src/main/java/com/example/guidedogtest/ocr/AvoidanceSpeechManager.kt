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

    fun consider(decision: AvoidanceDecision): AvoidanceSpeech? {
        val message = messageFor(decision) ?: run {
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

    /**
     * What the walker hears, from the decision alone.
     *
     * One thing is worth saying out loud now: the robot has stopped because something is in the middle
     * box. A lost link is not spoken here - the link's own status line says "disconnected", and a
     * second voice saying "path blocked" for a dead cable would be a lie. The action string is not
     * read: an alert that depends on its exact spelling goes silent the moment somebody renames it.
     */
    internal fun messageFor(decision: AvoidanceDecision): AvoidanceSpeech? {
        if (decision.state != AvoidanceState.STOPPED) return null
        return when (decision.stopReason) {
            AvoidanceStop.OBSTACLE ->
                AvoidanceSpeech("Obstacle ahead. Stopping.", VoicePriority.UNSAFE_PATH)
            else -> null
        }
    }

    private fun cooldownFor(priority: VoicePriority) = if (priority == VoicePriority.UNSAFE_PATH) 1_500L else 3_000L
    companion object { const val AVOIDANCE_COOLDOWN_MS = 3_000L; const val UNSAFE_COOLDOWN_MS = 1_500L }
}
