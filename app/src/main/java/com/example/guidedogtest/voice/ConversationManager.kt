package com.example.guidedogtest.voice

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val TAG = "ConversationManager"

// After Goose finishes speaking, how long to keep listening for a
// follow-up before giving up and going back to wake-word listening.
private const val FOLLOW_UP_LISTEN_TIMEOUT_MS = 10000L

class ConversationManager(
    context: Context,
    groqApiKey: String,
    elevenLabsApiKey: String,
    private val sensorProvider: () -> SensorSnapshot = { SensorSnapshot() },
    private val onCommand: (RobotCommand) -> Unit = {}
) : ViewModel() {

    private val _state = MutableStateFlow(ConversationState.LISTENING_FOR_WAKE_WORD)
    val state: StateFlow<ConversationState> = _state

    private val _lastSpoken = MutableStateFlow("")
    val lastSpoken: StateFlow<String> = _lastSpoken

    private val groqClient = GroqClient(groqApiKey)
    private val elevenLabsClient = ElevenLabsClient(elevenLabsApiKey, context)
    private val speechCapture = SpeechCapture(context)
    private val history = mutableListOf<ChatTurn>()

    private val wakeWordDetector = WakeWordDetector(context) { onWakeWordDetected() }

    private var followUpTimeoutJob: Job? = null

    fun start() {
        _state.value = ConversationState.LISTENING_FOR_WAKE_WORD
        wakeWordDetector.start()
    }

    fun stop() {
        followUpTimeoutJob?.cancel()
        wakeWordDetector.stop()
        speechCapture.destroy()
        elevenLabsClient.release()
    }

    private fun onWakeWordDetected() {
        Log.d(TAG, "Wake word detected")
        wakeWordDetector.pause()
        _state.value = ConversationState.ACK_PLAYING
        speak("Hi, how can I help?") {
            Log.d(TAG, "Ack finished, starting command capture")
            listenForCommand(withTimeout = false)
        }
    }

    /**
     * Starts one round of command capture. After the first turn (right after
     * the wake word), we wait indefinitely for the user to speak. After that,
     * each follow-up turn only waits [FOLLOW_UP_LISTEN_TIMEOUT_MS] before
     * giving up and dropping back to wake-word listening, so the mic doesn't
     * stay hot forever once the conversation is actually over.
     */
    private fun listenForCommand(withTimeout: Boolean) {
        _state.value = ConversationState.LISTENING

        followUpTimeoutJob?.cancel()
        if (withTimeout) {
            followUpTimeoutJob = viewModelScope.launch {
                delay(FOLLOW_UP_LISTEN_TIMEOUT_MS)
                Log.d(TAG, "Follow-up listening timed out, returning to wake word")
                speechCapture.stopListening()
                returnToWakeWordListening()
            }
        }

        speechCapture.startListening(
            onResult = { transcript ->
                followUpTimeoutJob?.cancel()
                onTranscript(transcript)
            },
            onError = {
                followUpTimeoutJob?.cancel()
                Log.d(TAG, "SpeechCapture error, returning to wake word listening")
                returnToWakeWordListening()
            }
        )
    }

    private fun onTranscript(transcript: String) {
        Log.d(TAG, "Transcript: $transcript")
        // Local safety-phrase bypass: these must work with zero network
        // dependency, so we short-circuit before ever calling Groq.
        val safetyCommand = when (transcript.trim().lowercase()) {
            "stop", "halt" -> RobotCommand.Stop
            "go" -> RobotCommand.Go
            else -> null
        }

        if (safetyCommand != null) {
            onCommand(safetyCommand)
            _state.value = ConversationState.SPEAKING
            val ack = if (safetyCommand == RobotCommand.Stop) "Stopping now." else "Okay, going."
            speak(ack) { listenForCommand(withTimeout = true) }
            return
        }

        _state.value = ConversationState.THINKING
        viewModelScope.launch {
            val reply = groqClient.getReply(transcript, sensorProvider(), history)
            Log.d(TAG, "Groq reply: speech=${reply.speech} command=${reply.command}")

            history.add(ChatTurn("user", transcript))
            history.add(ChatTurn("assistant", reply.speech))
            while (history.size > 16) history.removeAt(0)

            // TODO: wire this into BLE once the ESP32 link exists — for now
            // just log it, same as the FORWARD/LEFT/STOP/RIGHT buttons.
            if (reply.command !is RobotCommand.None) {
                Log.d(TAG, "Robot command: ${reply.command}")
                onCommand(reply.command)
            }

            _state.value = ConversationState.SPEAKING
            speak(reply.speech) { listenForCommand(withTimeout = true) }
        }
    }

    private fun speak(text: String, onDone: () -> Unit) {
        Log.d(TAG, "Speaking: $text")
        _lastSpoken.value = text
        elevenLabsClient.speak(text) {
            Log.d(TAG, "Speak done: $text")
            onDone()
        }
    }

    private fun returnToWakeWordListening() {
        followUpTimeoutJob?.cancel()
        _state.value = ConversationState.LISTENING_FOR_WAKE_WORD
        wakeWordDetector.resume()
    }

    override fun onCleared() {
        super.onCleared()
        stop()
    }
}
