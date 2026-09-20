package com.example.guidedogtest.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

private const val TAG = "WakeWordDetector"

/**
 * Wake-word detection via substring matching, NOT a dedicated wake-word
 * engine. We wanted to use Picovoice or similar, but signup was blocked
 * during the hackathon, so instead this repeatedly restarts Android's
 * on-device SpeechRecognizer and scans every partial/final transcript for
 * "goose". Tradeoffs vs a real wake-word SDK: this is far less
 * battery-efficient (a full STT recognizer cycles continuously instead of
 * a tiny always-on keyword model) and less accurate (it needs a full word
 * to transcribe correctly, not a tuned acoustic match), but it needs zero
 * extra dependency or account signup and is good enough for a demo.
 */
class WakeWordDetector(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit,
    /**
     * Called the moment a stop word is heard, whatever else is going on and without the wake word:
     * the loop is listening to everything anyway, so a stop does not have to be asked for twice.
     */
    private val onStopWordDetected: () -> Unit = {}
) {
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isActive = false
    private var consecutiveErrors = 0

    /** True once a stop has been fired for the utterance being listened to, so partials don't repeat it. */
    private var stopSent = false

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            consecutiveErrors++
            restartWithBackoff()
        }

        override fun onResults(results: Bundle?) {
            if (heardEmergencyStop(results)) {
                consecutiveErrors = 0
                restartWithBackoff()
                return
            }
            if (containsWakeWord(transcriptOf(results))) {
                fireWakeWord()
            } else {
                consecutiveErrors = 0
                restartWithBackoff()
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (heardEmergencyStop(partialResults)) return
            if (containsWakeWord(transcriptOf(partialResults))) {
                fireWakeWord()
            }
        }
    }

    private fun transcriptOf(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.joinToString(" ").orEmpty()

    /**
     * Acts on a stop word if one was heard, once per utterance.
     *
     * It does not stop listening: the loop has to keep running so the next thing said is heard too.
     */
    private fun heardEmergencyStop(bundle: Bundle?): Boolean {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (stopSent) return true
        if (matches?.any(EmergencyStopMatcher::matches) != true) return false
        stopSent = true
        Log.d(TAG, "emergency stop phrase heard")
        onStopWordDetected()
        return true
    }

    private fun fireWakeWord() {
        isActive = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.stopListening()
        onWakeWordDetected()
    }

    private fun restartWithBackoff() {
        if (!isActive) return
        // Back off after repeated errors so a broken mic/recognizer doesn't
        // spin the CPU in a tight restart loop.
        val delay = if (consecutiveErrors > 3) 1000L else 250L
        handler.postDelayed({ startListeningInternal() }, delay)
    }

    private fun startListeningInternal() {
        if (!isActive) return
        recognizer?.destroy()
        stopSent = false
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(listener)
            startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                }
            )
        }
    }

    fun start() {
        if (isActive) return
        isActive = true
        consecutiveErrors = 0
        // Logged once per start, not per cycle: this loop restarts several times a second and the
        // log would otherwise be nothing but this line.
        Log.d(TAG, "wake word listening")
        startListeningInternal()
    }

    fun pause() {
        isActive = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.stopListening()
    }

    fun resume() {
        start()
    }

    fun stop() {
        pause()
        recognizer?.destroy()
        recognizer = null
    }

    fun destroy() {
        stop()
    }
}
