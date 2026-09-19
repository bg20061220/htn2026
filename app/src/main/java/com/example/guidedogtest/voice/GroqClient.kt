package com.example.guidedogtest.voice

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "GroqClient"

private const val SYSTEM_PROMPT = """
You are Goose, a calm, warm guide dog assistant helping a person get around safely.
Stay in character as a guide dog at all times — never mention being an AI, a model,
or software. Reference nearby hazards naturally when they are relevant (e.g. "there's
a curb coming up on your left") based on the sensor_state you are given, but keep it
conversational, not robotic.

If the user asks you to go, walk, navigate, or take them somewhere, set command to
"navigate" and destination to exactly the place they named (e.g. "the library", "tim
hortons"), with no filler words added. Do this even if the place sounds far away or
unclear — let the app resolve and route it; you are not responsible for judging
distance or feasibility. Your "speech" for a navigate command will not be spoken
(the app replaces it with its own confirmation prompt), so it can be brief.

Respond with ONLY a JSON object, no other text, in exactly this shape:
{"speech": "<1-2 short sentences to speak aloud>", "command": "none|stop|go|turn_left|turn_right|navigate", "destination": "<only present if command is navigate>"}
"""

class GroqClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    private val fallback = ConverseResponse("Sorry, I didn't catch that.", RobotCommand.None)

    suspend fun getReply(
        transcript: String,
        sensor: SensorSnapshot,
        history: List<ChatTurn>
    ): ConverseResponse = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(buildRequestBody(transcript, sensor, history))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.d(TAG, "Groq HTTP error: ${response.code} ${response.body?.string()}")
                    return@withContext fallback
                }
                val body = response.body?.string() ?: return@withContext fallback
                val content = JSONObject(body)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                Log.d(TAG, "Groq content: $content")
                parseReply(content)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Groq call failed: ${e.javaClass.simpleName}: ${e.message}")
            fallback
        }
    }

    private fun buildRequestBody(
        transcript: String,
        sensor: SensorSnapshot,
        history: List<ChatTurn>
    ): okhttp3.RequestBody {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        messages.put(
            JSONObject().put("role", "system").put(
                "content",
                "sensor_state: " + JSONObject()
                    .put("frontDistanceMm", sensor.frontDistanceMm)
                    .put("obstacleLeft", sensor.obstacleLeft)
                    .put("obstacleRight", sensor.obstacleRight)
                    .put("dropoffDetected", sensor.dropoffDetected)
                    .put("isMoving", sensor.isMoving)
                    .toString()
            )
        )
        // Only the last ~8 turns (16 entries) — keeps the prompt small and cheap.
        history.takeLast(16).forEach { turn ->
            messages.put(JSONObject().put("role", turn.role).put("content", turn.content))
        }
        messages.put(JSONObject().put("role", "user").put("content", transcript))

        // openai/gpt-oss-20b: fast + available on this account. Check
        // https://api.groq.com/openai/v1/models against your key if this
        // model gets deprecated too.
        val json = JSONObject()
            .put("model", "openai/gpt-oss-20b")
            .put("messages", messages)
            .put("response_format", JSONObject().put("type", "json_object"))

        return json.toString().toRequestBody("application/json".toMediaType())
    }

    private fun parseReply(content: String): ConverseResponse {
        return try {
            val json = JSONObject(content)
            val speech = json.optString("speech", fallback.speech)
            val command = when (json.optString("command", "none")) {
                "stop" -> RobotCommand.Stop
                "go" -> RobotCommand.Go
                "turn_left" -> RobotCommand.Turn("left")
                "turn_right" -> RobotCommand.Turn("right")
                "navigate" -> RobotCommand.Navigate(json.optString("destination", ""))
                else -> RobotCommand.None
            }
            ConverseResponse(speech, command)
        } catch (e: Exception) {
            fallback
        }
    }
}
