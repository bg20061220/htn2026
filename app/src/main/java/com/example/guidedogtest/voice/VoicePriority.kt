package com.example.guidedogtest.voice

enum class VoicePriority(val level: Int) {
    NORMAL(1), NEARBY_OBJECT(2), AVOIDANCE(3), UNSAFE_PATH(4), EMERGENCY_STOP(5)
}
