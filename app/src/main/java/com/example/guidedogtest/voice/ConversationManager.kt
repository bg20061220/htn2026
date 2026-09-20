package com.example.guidedogtest.voice

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.guidedogtest.BuildConfig
import com.example.guidedogtest.RouteDestination
import com.example.guidedogtest.RoutePlan
import com.example.guidedogtest.RouteStep
import com.example.guidedogtest.RoutesApi
import com.example.guidedogtest.formatDistance
import com.example.guidedogtest.formatDuration
import com.example.guidedogtest.GeoPoint
import com.example.guidedogtest.maps.ResolvedPlace
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
    /**
     * Which request produced this route. A `StateFlow` does not re-emit a value it considers equal,
     * and two routes to the same place are equal field for field - so without a per-request id the
     * second "take me to the library" would leave the first route (and its finished follower) in
     * place, and the robot would answer the walker's confirmation by standing still.
     */
    val requestId: Long,
    val destinationName: String,
    val destinationLocation: LatLng,
    val plan: RoutePlan,
) {
    val distanceMeters: Int get() = plan.distanceMeters
    val durationSeconds: Double get() = plan.durationSeconds
    val points: List<LatLng> get() = plan.points
    val instructions: List<String> get() = plan.instructions

    /** The steps the robot drives, so a spoken destination becomes the same route the UI loads. */
    val steps: List<RouteStep> get() = plan.steps
}

class ConversationManager(
    context: Context,
    groqApiKey: String,
    elevenLabsApiKey: String,
    private val sensorProvider: () -> SensorSnapshot = { SensorSnapshot() },
    private val locationProvider: () -> LatLng? = { null },
    private val onCommand: (RobotCommand) -> Unit = {},
    /**
     * A stop word heard by the always-listening loop, outside any conversation turn. The loop owns
     * the mic, so this is the only place a stop can be heard while nothing else is going on - which
     * is exactly when the robot is walking.
     */
    private val onStopWord: () -> Unit = {}
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

    private val wakeWordDetector = WakeWordDetector(
        context = context,
        onWakeWordDetected = { inlineCommand -> onWakeWordDetected(inlineCommand) },
        onStopWordDetected = { onStopWord() },
    )

    private var followUpTimeoutJob: Job? = null
    private var activeRequestJob: Job? = null
    private var activeSpeechPriority: VoicePriority? = null
    private var speechGeneration = 0L

    // Set while waiting for the user to say yes/no to a proposed
    // destination. Non-null means the next transcript is treated as a
    // confirmation answer instead of a fresh request.
    private var pendingDestination: ResolvedPlace? = null

    /** Bumped for every route the voice loads: see [VoiceRoute.requestId]. */
    private var routeRequestId = 0L

    fun start() {
        _state.value = ConversationState.LISTENING_FOR_WAKE_WORD
        wakeWordDetector.start()
    }

    fun stop() {
        followUpTimeoutJob?.cancel()
        activeRequestJob?.cancel()
        wakeWordDetector.stop()
        speechCapture.destroy()
        elevenLabsClient.release()
    }

    /**
     * Speaks a short safety alert only when no conversation turn is active.
     * Pausing wake-word recognition prevents Goose from hearing its own alert.
     */
    fun speakObstacleAlert(text: String): Boolean {
        return requestSpeech(text, VoicePriority.NEARBY_OBJECT)
    }

    fun speakAvoidanceAlert(text: String, priority: VoicePriority): Boolean =
        requestSpeech(text, priority)

    fun announceEmergencyStop() {
        requestSpeech("Stopping now.", VoicePriority.EMERGENCY_STOP)
    }

    /**
     * Speaks a navigation cue - the turn the robot is about to make.
     *
     * Unlike an obstacle alert this interrupts a turn in progress: the person on the leash needs the
     * cue more than the conversation does, and the cue is what they steer by. It still refuses to
     * talk over itself, so a burst of step changes cannot queue up a wall of speech.
     */
    fun announce(text: String): Boolean {
        return requestSpeech(text, VoicePriority.NORMAL)
    }

    private fun requestSpeech(text: String, priority: VoicePriority): Boolean {
        if (text.isBlank()) return false
        val currentPriority = activeSpeechPriority
        val idle = _state.value == ConversationState.LISTENING_FOR_WAKE_WORD
        if (!idle && currentPriority != null && priority.level <= currentPriority.level) return false
        // Conversation owns the microphone unless the path itself is unsafe. Avoidance directions
        // may replace an object alert, but do not cut off an active user turn.
        if (!idle && currentPriority == null && priority.level < VoicePriority.UNSAFE_PATH.level) return false

        speechGeneration++
        elevenLabsClient.interrupt()
        followUpTimeoutJob?.cancel()
        speechCapture.stopListening()
        wakeWordDetector.pause()
        activeSpeechPriority = priority
        _state.value = ConversationState.SPEAKING
        speak(text) {
            activeSpeechPriority = null
            returnToWakeWordListening()
        }
        return true
    }

    private fun onWakeWordDetected(inlineCommand: String) {
        if (_state.value != ConversationState.LISTENING_FOR_WAKE_WORD) {
            // During request processing this recognizer is safety-only; ordinary wake words wait.
            wakeWordDetector.resume()
            return
        }
        Log.d(TAG, "Wake word detected (inline='$inlineCommand')")
        wakeWordDetector.pause()
        // "Goose, stop" is one utterance, not two turns: acting on what followed the wake word
        // skips both the acknowledgement prompt and a second round of listening, which is the
        // difference between a command that lands and one the user has to say twice.
        if (inlineCommand.isNotBlank()) {
            _state.value = ConversationState.THINKING
            onTranscript(inlineCommand)
            return
        }
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
            },
            onEmergencyStop = { handleEmergencyStop() },
        )
    }

    /** No network and no spoken acknowledgement: motor safety wins over the conversation. */
    private fun handleEmergencyStop() {
        Log.d(TAG, "Local emergency stop detected")
        followUpTimeoutJob?.cancel()
        activeRequestJob?.cancel()
        activeRequestJob = null
        pendingDestination = null
        speechCapture.stopListening()
        onCommand(RobotCommand.Stop)
        returnToWakeWordListening()
    }

    private fun onTranscript(transcript: String) {
        Log.d(TAG, "Transcript: $transcript")
        // The one-shot recognizer has finished. Resume the lightweight global safety listener while
        // local/network processing runs so STOP can cancel an in-flight Groq, Places or Routes job.
        speechCapture.destroy()
        wakeWordDetector.resume()

        val pending = pendingDestination
        if (pending != null) {
            pendingDestination = null
            _state.value = ConversationState.THINKING
            activeRequestJob = viewModelScope.launch { handleNavigationConfirmation(pending, transcript) }
            return
        }

        // Local safety-phrase bypass: these must work with zero network
        // dependency, so we short-circuit before ever calling Groq.
        val safetyCommand = if (EmergencyStopMatcher.matches(transcript)) {
            RobotCommand.Stop
        } else {
            localCommandFor(transcript)
        }

        if (safetyCommand != null) {
            onCommand(safetyCommand)
            // Stop and backward are answered by whoever acts on them, never here. halt() fires its
            // own emergency announcement, and the backward refusal is an explanation the robot must
            // not contradict - speaking an ack here too produced two competing "Stopping now."s that
            // interrupted each other, which sounded exactly like Goose failing to speak at all.
            if (safetyCommand == RobotCommand.Stop || safetyCommand == RobotCommand.Backward) return
            _state.value = ConversationState.SPEAKING
            val ack = when (safetyCommand) {
                RobotCommand.Forward -> "Moving forward."
                is RobotCommand.Turn -> "Turning ${safetyCommand.direction}."
                else -> "Okay, going."
            }
            // The command is done, so the microphone goes back to the wake word rather than staying
            // open for a follow-up: a one-shot recognizer left listening costs battery, CPU and the
            // occasional mistaken command, and "goose" is one word to say again.
            speak(ack) { returnToWakeWordListening() }
            return
        }

        // A destination in one of the fixed phrasings is resolved without the model: no round trip,
        // no network, and the confirmation that follows is the same one the model path produces.
        destinationRequestFor(transcript)?.let { destination ->
            _state.value = ConversationState.THINKING
            activeRequestJob = viewModelScope.launch { handleNavigateRequest(destination) }
            return
        }

        _state.value = ConversationState.THINKING
        activeRequestJob = viewModelScope.launch {
            val reply = groqClient.getReply(transcript, sensorProvider(), history)
            Log.d(TAG, "Groq reply: speech=${reply.speech} command=${reply.command}")

            history.add(ChatTurn("user", transcript))
            history.add(ChatTurn("assistant", reply.speech))
            while (history.size > 16) history.removeAt(0)

            val command = reply.command
            if (command is RobotCommand.Navigate) {
                handleNavigateRequest(command.destination)
            } else {
                // Straight through to the motors by the same path the buttons use: onCommand is what
                // hands the command to MainActivity, which is what actually writes a wheel frame.
                if (command !is RobotCommand.None) {
                    Log.d(TAG, "Robot command: $command")
                    onCommand(command)
                }
                _state.value = ConversationState.SPEAKING
                // An answer keeps the exchange open for a follow-up: conversation is the point of the
                // persona, and the walker should not have to say "goose" between two sentences. A
                // *command* is different - it is done when it is done, and that one goes back to the
                // wake word (see the safety-command branch above).
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
        // Denial wins. The question is "Did you mean <place>? Say yes or no", and the natural
        // refusals - "no, that's not right", "not right" - contain the confirmation word "right".
        // Reading one of those as agreement fetches a route to the place the user just refused and
        // announces it back to them as the destination.
        val denied = DENY_WORDS.any { answer.contains(it) }
        val confirmed = !denied && CONFIRM_WORDS.any { answer.contains(it) }

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
                    val apiKey = BuildConfig.MAPS_API_KEY
                    val plan = RoutesApi.fetchRoute(
                        apiKey = apiKey,
                        origin = GeoPoint(origin.latitude, origin.longitude),
                        destination = RouteDestination.Point(
                            lat = place.location.latitude,
                            lng = place.location.longitude,
                        ),
                    )
                    _voiceRoute.value = VoiceRoute(
                        requestId = ++routeRequestId,
                        destinationName = place.name,
                        destinationLocation = place.location,
                        plan = plan,
                    )
                    _state.value = ConversationState.SPEAKING
                    speak(
                        "Okay, heading to ${place.name}. That's about ${formatDistance(plan.distanceMeters)}, " +
                            "roughly ${formatDuration(plan.durationSeconds)} on foot."
                    ) {
                        // The confirmation was the go-ahead, so the walk starts here: the walker said
                        // yes to this destination and hears the plan, and then the robot walks it -
                        // without a second utterance. It is the app's own Go path that starts it, so
                        // the one place that knows how a route begins (and how to refuse out loud
                        // when there is no robot to start) is the one place that does it.
                        //
                        // Back to the wake word *first*: an announcement is dropped while a
                        // conversation turn is still open, and if the start has to be refused the
                        // walker has just been told the robot is heading somewhere - a refusal they
                        // cannot hear is worse than none. Stopping needs no wake word, so nothing is
                        // lost by closing the follow-up window here.
                        returnToWakeWordListening()
                        onCommand(RobotCommand.Go)
                    }
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
        // Never feed ElevenLabs output into the emergency matcher: pause recognition before audio.
        wakeWordDetector.pause()
        _lastSpoken.value = text
        val generation = ++speechGeneration
        elevenLabsClient.speak(text) {
            Log.d(TAG, "Speak done: $text")
            if (generation == speechGeneration) onDone()
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
