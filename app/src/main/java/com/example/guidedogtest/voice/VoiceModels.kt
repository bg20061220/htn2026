package com.example.guidedogtest.voice

enum class ConversationState {
    LISTENING_FOR_WAKE_WORD,
    ACK_PLAYING,
    LISTENING,
    THINKING,
    SPEAKING
}

data class SensorSnapshot(
    /** Millimetres to the nearest thing straight ahead, or [NO_HAZARD_MM] when nothing is in range. */
    val frontDistanceMm: Int = NO_HAZARD_MM,
    val obstacleLeft: Boolean = false,
    val obstacleRight: Boolean = false,
    val dropoffDetected: Boolean = false,
    val isMoving: Boolean = false,
    /**
     * False when the robot has no hazard picture at all: no depth session, camera shut, or nothing
     * in front of the lens. Everything else in here means "unknown" then, and the model is told that
     * rather than being handed a "nothing in the way" it would repeat to someone on a leash.
     */
    val hazardsKnown: Boolean = false,
) {
    companion object {
        /** Further than any hazard the robot acts on: what "nothing in front" is worth in millimetres. */
        const val NO_HAZARD_MM = 9999
    }
}

sealed class RobotCommand {
    object None : RobotCommand()
    object Stop : RobotCommand()
    object Go : RobotCommand()
    /** Drive straight ahead, latched, at the tuned FORWARD wheel pair. */
    object Forward : RobotCommand()
    /** Reverse. The depth camera only measures the forward corridor, so the app refuses this. */
    object Backward : RobotCommand()
    data class Turn(val direction: String) : RobotCommand()
    data class Navigate(val destination: String) : RobotCommand()
}

/**
 * The phrases that bypass the model, matched on a whole transcript.
 *
 * These are the ones where a round trip through Groq is the wrong trade: someone saying "stop" or
 * "go forward" needs the motors to answer now, and needs them to answer when the phone has no data.
 * Everything else goes to the model, which is what makes "take me to the library" work.
 *
 * Returns null when the phrase is not one of these, so the caller can send it to Groq.
 */
fun localCommandFor(transcript: String): RobotCommand? =
    // Punctuation at either end is the recognizer's, not the speaker's: "Stop." arrives as "stop",
    // but "Stop!" and "go forward?" do too, and a phrase that misses here takes a network round trip
    // to reach the same decision.
    when (transcript.trim().lowercase().trim('.', '!', '?', ',', ';', ':')) {
        "stop", "halt" -> RobotCommand.Stop
        "go", "start", "start following", "continue", "continue route", "keep going" -> RobotCommand.Go
        "forward", "go forward", "move forward", "walk forward", "straight", "straight ahead" ->
            RobotCommand.Forward
        "backward", "go backward", "move backward", "back", "reverse" -> RobotCommand.Backward
        "turn left", "left" -> RobotCommand.Turn("left")
        "turn right", "right" -> RobotCommand.Turn("right")
        else -> null
    }

/**
 * Is the wake word in this transcript?
 *
 * Matched as a substring, not a word: this runs on partial results too, where the word may still be
 * half-formed, and being early matters more than being tidy.
 */
fun containsWakeWord(transcript: String): Boolean =
    transcript.lowercase().contains(WAKE_WORD)

/**
 * Whatever was said after the wake word in the same breath, e.g. "goose, stop" -> "stop".
 *
 * Empty when the wake word was said on its own, which is the case that gets the acknowledgement
 * prompt. Leading punctuation is dropped because a recognizer will happily hand back "Goose, take me
 * to the library" with the comma attached.
 */
fun commandAfterWakeWord(transcript: String): String {
    val lower = transcript.lowercase()
    val index = lower.indexOf(WAKE_WORD)
    if (index < 0) return ""
    return transcript.substring(index + WAKE_WORD.length)
        .trim()
        .trimStart(',', '.', ':', ';', '-', '!', '?')
        .trim()
}

/**
 * A destination said in one of the fixed phrasings, or null.
 *
 * This is the fast path for "take me to X": it saves the model round trip for the phrasings people
 * actually use, and it works with no network at all. Places still resolves the text - this only
 * decides that a destination was asked for.
 */
fun destinationRequestFor(transcript: String): String? {
    val cleaned = transcript.trim().trimEnd('.', '?', '!')
    val prefixes = listOf("take me to ", "navigate to ", "go to ", "bring me to ", "walk to ")
    val prefix = prefixes.firstOrNull { cleaned.startsWith(it, ignoreCase = true) } ?: return null
    return cleaned.substring(prefix.length).trim().takeIf { it.isNotEmpty() }
}

private const val WAKE_WORD = "goose"

data class ChatTurn(val role: String, val content: String)

data class ConverseResponse(val speech: String, val command: RobotCommand)
