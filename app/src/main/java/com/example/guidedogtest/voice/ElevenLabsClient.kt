package com.example.guidedogtest.voice

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class ElevenLabsClient(private val apiKey: String, private val context: Context) {

    // TODO: swap for whichever voice you want. "Hope" (uYXf8XasLslADfZ2MB4u)
    // is a Voice Library voice, which the free ElevenLabs plan can't call via
    // API (402 payment_required) — this account's other voices are all
    // "premade" and work on the free tier, so using one of those for now.
    private val voiceId = "EXAVITQu4vr4xnSDxMaL" // "Sarah"

    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var mediaPlayer: MediaPlayer? = null

    /**
     * Downloads and plays [text] as speech, calling [onDone] once playback
     * actually finishes (or immediately, on any failure, so the caller's
     * state machine never gets stuck waiting).
     *
     * Tradeoff: we download the whole MP3 to a temp file before starting
     * MediaPlayer, rather than feeding MediaPlayer a live pipe of arriving
     * bytes. True incremental playback would shave off the download latency
     * (a second or so for these short replies), but piping partial reads
     * into MediaPlayer's data source is flaky across Android versions and
     * not worth debugging in a hackathon timeframe. Buffer-then-play is
     * slower but reliably works everywhere.
     */
    fun speak(text: String, onDone: () -> Unit) {
        scope.launch {
            try {
                val file = streamSpeech(text)
                withContext(Dispatchers.Main) { playFile(file, onDone) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onDone() }
            }
        }
    }

    suspend fun streamSpeech(text: String): File = withContext(Dispatchers.IO) {
        val json = JSONObject()
            .put("text", text)
            .put("model_id", "eleven_turbo_v2_5")
        val body = json.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId/stream?output_format=mp3_44100_128")
            .addHeader("xi-api-key", apiKey)
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("ElevenLabs error: ${response.code}")
            val file = File.createTempFile("goose_tts", ".mp3", context.cacheDir)
            response.body?.byteStream()?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            file
        }
    }

    private fun playFile(file: File, onDone: () -> Unit) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    it.release()
                    mediaPlayer = null
                    onDone()
                }
                setOnErrorListener { mp, _, _ ->
                    mp.release()
                    mediaPlayer = null
                    onDone()
                    true
                }
                prepare()
                start()
            } catch (e: IOException) {
                release()
                mediaPlayer = null
                onDone()
            }
        }
    }

    fun release() {
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
