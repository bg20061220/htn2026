package com.example.guidedogtest.voice

enum class ConversationState {
    LISTENING_FOR_WAKE_WORD,
    ACK_PLAYING,
    LISTENING,
    THINKING,
    SPEAKING
}

data class SensorSnapshot(
    val frontDistanceMm: Int = 9999,
    val obstacleLeft: Boolean = false,
    val obstacleRight: Boolean = false,
    val dropoffDetected: Boolean = false,
    val isMoving: Boolean = false
)

sealed class RobotCommand {
    object None : RobotCommand()
    object Stop : RobotCommand()
    object Go : RobotCommand()
    /** Drive straight ahead, latched, at the tuned FORWARD wheel pair. */
    object Forward : RobotCommand()
    data class Turn(val direction: String) : RobotCommand()
    data class Navigate(val destination: String) : RobotCommand()
}

/**
 * The commands that work with no network at all, matched on the transcript itself.
 *
 * These are the ones where a round trip through Groq is the wrong trade: someone saying "stop" or
 * "go forward" needs the motors to answer now, and needs them to answer when the phone has no data.
 * Everything else goes to the model, which is what makes "take me to the library" work.
 *
 * Returns null when the phrase is not one of these, so the caller can send it to Groq.
 */
fun localCommandFor(transcript: String): RobotCommand? =
    when (transcript.trim().lowercase().trim('.')) {
        "stop", "halt" -> RobotCommand.Stop
        "go", "start", "start following" -> RobotCommand.Go
        "forward", "go forward", "walk forward", "straight", "straight ahead" -> RobotCommand.Forward
        "turn left", "left" -> RobotCommand.Turn("left")
        "turn right", "right" -> RobotCommand.Turn("right")
        else -> null
    }

data class ChatTurn(val role: String, val content: String)

data class ConverseResponse(val speech: String, val command: RobotCommand)
