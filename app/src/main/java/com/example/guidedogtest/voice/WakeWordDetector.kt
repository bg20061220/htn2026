package com.example.guidedogtest.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

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
    private val onWakeWordDetected: () -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isActive = false
    private var consecutiveErrors = 0

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
            if (containsWakeWord(results)) {
                fireWakeWord()
            } else {
                consecutiveErrors = 0
                restartWithBackoff()
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (containsWakeWord(partialResults)) {
                fireWakeWord()
            }
        }
    }

    private fun containsWakeWord(bundle: Bundle?): Boolean {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        return matches?.any { it.lowercase().contains("goose") } == true
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
