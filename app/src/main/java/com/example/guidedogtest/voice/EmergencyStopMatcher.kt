package com.example.guidedogtest.voice

/** Strict local matcher for safety commands that may bypass the wake phrase. */
object EmergencyStopMatcher {
    private val accepted = setOf(
        "stop",
        "stop robot",
        "robot stop",
        "goose stop",
        "stop goose",
        "please stop",
        "stop please",
        "halt",
        "cancel",
        "cancel that",
    )

    fun matches(transcript: String): Boolean {
        val normalized = transcript
            .lowercase()
            .replace(Regex("[^a-z]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
        return normalized in accepted
    }
}
