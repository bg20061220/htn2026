package com.example.guidedogtest.voice

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.guidedogtest.maps.ResolvedPlace
import com.example.guidedogtest.maps.computeWalkingRoute
import com.example.guidedogtest.maps.formatDistance
import com.example.guidedogtest.maps.formatDuration
import com.example.guidedogtest.maps.getMapsApiKey
import com.example.guidedogtest.maps.resolveBestPlace
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.net.PlacesClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val TAG = "ConversationManager"

// After Goose finishes speaking, how long to keep listening for a
// follow-up before giving up and going back to wake-word listening.
private const val FOLLOW_UP_LISTEN_TIMEOUT_MS = 10000L

private val CONFIRM_WORDS = listOf("yes", "yeah", "yep", "yup", "correct", "right", "sure", "please", "confirm")
private val DENY_WORDS = listOf("no", "nope", "not", "wrong", "cancel", "nevermind", "never mind")

data class VoiceRoute(
    val destinationName: String,
    val destinationLocation: LatLng,
    val distanceMeters: Int,
    val durationSeconds: Double,
    val points: List<LatLng>,
    val instructions: List<String>
)

class ConversationManager(
    context: Context,
    groqApiKey: String,
    elevenLabsApiKey: String,
    private val sensorProvider: () -> SensorSnapshot = { SensorSnapshot() },
    private val locationProvider: () -> LatLng? = { null },
    private val onCommand: (RobotCommand) -> Unit = {}
) : ViewModel() {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(ConversationState.LISTENING_FOR_WAKE_WORD)
    val state: StateFlow<ConversationState> = _state

    private val _lastSpoken = MutableStateFlow("")
    val lastSpoken: StateFlow<String> = _lastSpoken

    private val _voiceRoute = MutableStateFlow<VoiceRoute?>(null)
    val voiceRoute: StateFlow<VoiceRoute?> = _voiceRoute

    private val groqClient = GroqClient(groqApiKey)
    private val elevenLabsClient = ElevenLabsClient(elevenLabsApiKey, context)
    private val speechCapture = SpeechCapture(context)
    private val placesClient: PlacesClient = Places.createClient(appContext)
    private val history = mutableListOf<ChatTurn>()

    private val wakeWordDetector = WakeWordDetector(context) { onWakeWordDetected() }

    private var followUpTimeoutJob: Job? = null

    // Set while waiting for the user to say yes/no to a proposed
    // destination. Non-null means the next transcript is treated as a
    // confirmation answer instead of a fresh request.
    private var pendingDestination: ResolvedPlace? = null

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

    /**
     * Speaks a short safety alert only when no conversation turn is active.
     * Pausing wake-word recognition prevents Goose from hearing its own alert.
     */
    fun speakObstacleAlert(text: String): Boolean {
        if (_state.value != ConversationState.LISTENING_FOR_WAKE_WORD || text.isBlank()) return false
        wakeWordDetector.pause()
        _state.value = ConversationState.SPEAKING
        speak(text) { returnToWakeWordListening() }
        return true
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

        val pending = pendingDestination
        if (pending != null) {
            pendingDestination = null
            viewModelScope.launch { handleNavigationConfirmation(pending, transcript) }
            return
        }

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

            val command = reply.command
            if (command is RobotCommand.Navigate) {
                handleNavigateRequest(command.destination)
            } else {
                // TODO: wire this into BLE once the ESP32 link exists — for
                // now just log it, same as the FORWARD/LEFT/STOP/RIGHT buttons.
                if (command !is RobotCommand.None) {
                    Log.d(TAG, "Robot command: $command")
                    onCommand(command)
                }
                _state.value = ConversationState.SPEAKING
                speak(reply.speech) { listenForCommand(withTimeout = true) }
            }
        }
    }

    /** Resolves the spoken destination to a real place, then asks for confirmation. */
    private suspend fun handleNavigateRequest(query: String) {
        val origin = locationProvider()
        val place = resolveBestPlace(placesClient, query, origin)

        if (place == null) {
            _state.value = ConversationState.SPEAKING
            speak("I couldn't find $query. Where would you like to go?") { listenForCommand(withTimeout = true) }
            return
        }

        pendingDestination = place
        _state.value = ConversationState.SPEAKING
        val addressPart = if (place.address.isNotBlank()) ", at ${place.address}" else ""
        speak("Did you mean ${place.name}$addressPart? Say yes or no.") { listenForCommand(withTimeout = true) }
    }

    /** Handles a yes/no answer to a previously proposed destination. */
    private suspend fun handleNavigationConfirmation(place: ResolvedPlace, transcript: String) {
        val answer = transcript.trim().lowercase()
        val confirmed = CONFIRM_WORDS.any { answer.contains(it) }
        val denied = DENY_WORDS.any { answer.contains(it) }

        when {
            confirmed -> {
                val origin = locationProvider()
                if (origin == null) {
                    _state.value = ConversationState.SPEAKING
                    speak("I don't have a location fix yet, so I can't build a route.") {
                        listenForCommand(withTimeout = true)
                    }
                    return
                }

                _state.value = ConversationState.THINKING
                try {
                    val apiKey = getMapsApiKey(appContext)
                    val route = computeWalkingRoute(appContext, apiKey, origin, place.location)
                    _voiceRoute.value = VoiceRoute(
                        destinationName = place.name,
                        destinationLocation = place.location,
                        distanceMeters = route.distanceMeters,
                        durationSeconds = route.durationSeconds,
                        points = route.points,
                        instructions = route.instructions
                    )
                    onCommand(RobotCommand.Navigate(place.name))
                    _state.value = ConversationState.SPEAKING
                    speak(
                        "Okay, heading to ${place.name}. That's about ${formatDistance(route.distanceMeters)}, " +
                            "roughly ${formatDuration(route.durationSeconds)} on foot."
                    ) { listenForCommand(withTimeout = true) }
                } catch (e: Exception) {
                    Log.d(TAG, "Route computation failed: ${e.message}")
                    _state.value = ConversationState.SPEAKING
                    speak("Sorry, I couldn't calculate the route.") { listenForCommand(withTimeout = true) }
                }
            }
            denied -> {
                _state.value = ConversationState.SPEAKING
                speak("Okay, cancelled. Where would you like to go?") { listenForCommand(withTimeout = true) }
            }
            else -> {
                // Unclear answer — keep waiting on the same destination.
                pendingDestination = place
                _state.value = ConversationState.SPEAKING
                speak("Sorry, was that a yes or a no?") { listenForCommand(withTimeout = true) }
            }
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
