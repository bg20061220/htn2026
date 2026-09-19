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
    data class Turn(val direction: String) : RobotCommand()
    data class Navigate(val destination: String) : RobotCommand()
}

data class ChatTurn(val role: String, val content: String)

data class ConverseResponse(val speech: String, val command: RobotCommand)
