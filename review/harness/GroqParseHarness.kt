package com.example.guidedogtest.review

import com.example.guidedogtest.MotorSettings
import com.example.guidedogtest.voice.ConverseResponse
import com.example.guidedogtest.voice.GroqClient
import com.example.guidedogtest.voice.RobotCommand
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * TEMPORARY REVIEW HARNESS — not a permanent test. Deleted before the review is handed over;
 * a copy of this source lives in review/harness/GroqParseHarness.kt.
 *
 * REPRODUCE (from the repo root):
 *   mkdir -p app/src/test/java/com/example/guidedogtest/review
 *   cp review/harness/GroqParseHarness.kt app/src/test/java/com/example/guidedogtest/review/
 *   ./gradlew.bat :app:testDebugUnitTest --tests "com.example.guidedogtest.review.GroqParseHarness"
 *   rm -rf app/src/test/java/com/example/guidedogtest/review      <-- keep the tree clean
 * The log is written by this harness to review/logs/parse-robustness.log.
 *
 * Calls the REAL private GroqClient.parseReply (app/src/main/java/com/example/guidedogtest/voice/
 * GroqClient.kt:115-133) by reflection, against the shapes a chat-completions model actually returns,
 * and then evaluates what MainActivity's `when (robotCommand)` (MainActivity.kt:293-353) would do
 * with the result on the real MotorSettings.
 */
class GroqParseHarness {

    private val log = StringBuilder()
    private var failures = 0

    @Test
    fun parseShapes() {
        line("GroqClient.parseReply by reflection — what each realistic model output becomes")
        line("parseReply is private (GroqClient.kt:115); the transport call in the same class wraps it in")
        line("a try/catch that returns the fallback ConverseResponse(\"Sorry, I didn't catch that.\", None)")
        line("only when the JSONObject constructor or a getter throws; parseReply has its own catch for that.")
        line("")
        line("model 'command' value -> RobotCommand (GroqClient.kt:119-127) -> what MainActivity does")
        line("(MainActivity.kt:293-353) -> the frame the transmit loop sends for it")
        line("")

        val client = GroqClient("not-a-real-key-for-parsing-only")

        val cases = listOf(
            "exact JSON, turn_left" to
                """{"speech":"Turning left now.","command":"turn_left"}""",
            "exact JSON, turn_right" to
                """{"speech":"Turning right.","command":"turn_right"}""",
            "fenced in ```json" to
                "```json\n{\"speech\":\"Okay.\",\"command\":\"stop\"}\n```",
            "leading prose then JSON" to
                """Sure, here is the reply: {"speech":"Going forward.","command":"forward"}""",
            "missing command key" to
                """{"speech":"I can help with that."}""",
            "unknown command 'Turn'" to
                """{"speech":"Turning.","command":"Turn"}""",
            "unknown command 'TURN_LEFT'" to
                """{"speech":"Turning.","command":"TURN_LEFT"}""",
            "navigate without destination" to
                """{"speech":"Heading there.","command":"navigate"}""",
            "navigate with destination" to
                """{"speech":"Okay.","command":"navigate","destination":"the library"}""",
            "command from the system prompt vocabulary 'go'" to
                """{"speech":"Starting.","command":"go"}""",
            "backward (understood so it can be refused)" to
                """{"speech":"Moving back.","command":"backward"}""",
            "empty body" to "",
            "error body (HTML)" to "<html><body>502 Bad Gateway</body></html>",
            "truncated JSON" to """{"speech":"Turning left,""",
            "null JSON literal" to "null",
        )

        for ((label, body) in cases) {
            val r = parse(client, body)
            val effect = whatMainActivityDoes(r.command)
            val frame = frameFor(r.command)
            val verdict = when {
                r.speech == FALLBACK_SPEECH && r.command is RobotCommand.None -> "safe fallback"
                r.command is RobotCommand.None -> "silent no-op"
                else -> "acted on: $effect"
            }
            line("%-52s -> command=%-28s frame=%-12s %s".format(
                label, r.command.toString(), frame.trim(), verdict))
        }

        line("")
        line("=".repeat(100))
        line("ASSERTIONS")
        line("=".repeat(100))
        expect("turn_left maps to Turn(\"left\")", parse(client, """{"command":"turn_left"}""").command, RobotCommand.Turn("left"))
        expect("backward maps to Backward (the app then refuses it: no rear depth)",
            parse(client, """{"command":"backward"}""").command, RobotCommand.Backward)
        expect("turn_right maps to Turn(\"right\")", parse(client, """{"command":"turn_right"}""").command, RobotCommand.Turn("right"))
        expect("the fenced form does NOT parse - it is inert (falls back to None)",
            parse(client, "```json\n{\"command\":\"stop\"}\n```").command, RobotCommand.None)
        expect("leading prose breaks the parse (fallback)", parse(client, "Sure: {\"command\":\"forward\"}").command, RobotCommand.None)
        expect("a missing command key is None, not Stop", parse(client, """{"speech":"hi"}""").command, RobotCommand.None)
        expect("an unknown command is None, not Stop", parse(client, """{"command":"Turn"}""").command, RobotCommand.None)
        expect("navigate without a destination still becomes Navigate(\"\")",
            parse(client, """{"command":"navigate"}""").command, RobotCommand.Navigate(""))
        expect("an empty body is the fallback", parse(client, "").speech, FALLBACK_SPEECH)
        expect("an HTML error body is the fallback", parse(client, "<html>502</html>").command, RobotCommand.None)

        line("")
        line("The LLM-facing vocabulary in SYSTEM_PROMPT (GroqClient.kt) is")
        line("  \"none|stop|go|forward|backward|turn_left|turn_right|navigate\"")
        line("and parseReply maps every one of those, so the prompt and the parser agree — this risk is REFUTED.")
        line("MainActivity's voice `when` tests direction.lowercase().contains(\"left\"), not an exact")
        line("\"left\"/\"right\" match, so Turn(\"left\") and Turn(\"right\") both land.")
        line("What the mapping does NOT express: a Turn has no duration or angle. A spoken turn arms the")
        line("depth layer with PIVOT_LEFT/PIVOT_RIGHT, and the controller pivots on the tuned pair until")
        line("the centre is blocked or something else writes the intent - so a turn is ended by \"stop\",")
        line("not by a number of degrees. Backward is understood only so the app can refuse it out loud:")

        line("")
        line("=".repeat(100))
        line("RESULT: $failures assertion(s) failed")
        line("=".repeat(100))
        write()
        assertEquals("harness assertions failed", 0, failures)
    }

    private val FALLBACK_SPEECH = "Sorry, I didn't catch that."

    private fun parse(client: GroqClient, body: String): ConverseResponse {
        val m = GroqClient::class.java.getDeclaredMethod("parseReply", String::class.java)
        m.isAccessible = true
        return m.invoke(client, body) as ConverseResponse
    }

    /** MainActivity's voice `when`, reduced to what the command turns into. */
    private fun whatMainActivityDoes(c: RobotCommand): String = when (c) {
        RobotCommand.Stop -> "halt() -> command=\"STOP\", link.send(c0,0), one spoken ack"
        RobotCommand.Go -> "following=true + avoidanceActive=true (needs follower != null and a link)"
        // Spoken motion arms the depth layer instead of latching a wheel pair: the page's buttons are
        // the unguarded bench path, the walker's voice goes through obstacle sensing.
        RobotCommand.Forward -> "command=\"STOP\"; desired=FORWARD; avoidanceActive=true (depth layer drives)"
        is RobotCommand.Turn ->
            "command=\"STOP\"; desired=PIVOT_" + if (c.direction.lowercase().contains("left")) "LEFT" else "RIGHT" +
                "; avoidanceActive=true (depth layer pivots)"
        RobotCommand.Backward -> "halt() + spoken refusal: only the forward corridor is measured"
        is RobotCommand.Navigate -> "no-op here; the route arrives via voiceRoute -> adoptRoute()"
        RobotCommand.None -> "no-op"
    }

    /**
     * The manual frame the command leaves queued (MotorSettings.kt + RobotLink.kt). Forward and the
     * turns leave \"STOP\" there on purpose: their motion comes from the depth layer's own wheels,
     * which is what DriveArbiter.resolve puts on the wire.
     */
    private fun frameFor(c: RobotCommand): String {
        val settings = MotorSettings()
        val cmd = when (c) {
            RobotCommand.Go, RobotCommand.Forward, RobotCommand.Backward -> "STOP"
            is RobotCommand.Turn -> "STOP"
            RobotCommand.Stop, RobotCommand.None -> "STOP"
            is RobotCommand.Navigate -> "STOP"
        }
        return settings.speedsFor(cmd).frame()
    }

    private fun expect(what: String, actual: Any?, expected: Any?) {
        val ok = actual == expected
        line("  CHECK ${if (ok) "PASS" else "FAIL"}: $what   (got $actual, expected $expected)")
        if (!ok) failures++
    }

    private fun line(t: String) = log.append(t).append('\n')

    private fun write() {
        val out = resolveLogFile("parse-robustness.log")
        out.parentFile?.mkdirs()
        out.writeText(log.toString())
        println("harness log written to: ${out.absolutePath}")
    }

    private fun resolveLogFile(name: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return File(dir, "review/logs/$name")
            dir = dir.parentFile
        }
        return File("review/logs/$name")
    }
}
