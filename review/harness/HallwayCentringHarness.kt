package com.example.guidedogtest.review

import com.example.guidedogtest.MotorSettings
import com.example.guidedogtest.MotorTuning
import com.example.guidedogtest.WheelSpeeds
import com.example.guidedogtest.ocr.DesiredTravelDirection
import com.example.guidedogtest.ocr.ObstacleAvoidanceController
import com.example.guidedogtest.ocr.SceneAwarenessResult
import com.example.guidedogtest.ocr.SceneZoneState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * TEMPORARY HARNESS — not a permanent test. Deleted after the run; the source is kept in
 * review/harness/HallwayCentringHarness.kt.
 *
 * REPRODUCE (from the repo root):
 *   mkdir -p app/src/test/java/com/example/guidedogtest/review
 *   cp review/harness/HallwayCentringHarness.kt app/src/test/java/com/example/guidedogtest/review/
 *   ./gradlew.bat :app:testDebugUnitTest --tests "com.example.guidedogtest.review.HallwayCentringHarness"
 *   rm -rf app/src/test/java/com/example/guidedogtest/review
 * The log is written by this harness to review/logs/hallway-centring.log.
 *
 * What it answers: standing in a corridor with the walls a little uneven, does the robot hold the
 * middle, or does it chase the wider side? It sweeps the two wall distances across the real
 * ObstacleAvoidanceController and prints the decision each one produces.
 */
class HallwayCentringHarness {

    private val log = StringBuilder()
    private var failures = 0
    private var now = 1_000L

    @Test
    fun sweep() {
        heading("Hallway centring — does a slightly uneven wall move the wheels?")
        line("ObstacleAvoidanceController.kt, MotorTuning.steered and the tuned pair are the real code.")
        line("Each row is one depth frame: left/right = the two wall distances the side boxes report.")
        line("")
        line("  %-9s %-9s %-8s %-7s %-15s %s".format("left(m)", "right(m)", "diff(m)", "bias", "action", "wheels L/R"))

        // A hallway that the grid calls off-centre while the walls say the robot is essentially
        // centred: this is the case the change is about.
        sweep("flat wall, noise only", 1.40f, 1.52f, gridOffset = 0.22f, expectBias = 0, expectAction = "FORWARD")
        sweep("flat wall, more noise", 1.20f, 1.38f, gridOffset = 0.22f, expectBias = 0, expectAction = "FORWARD")
        sweep("hugging the left wall", 0.60f, 1.70f, gridOffset = 0.22f, expectBiasSign = 1, expectAction = "CENTER RIGHT")
        sweep("hugging the right wall", 1.70f, 0.60f, gridOffset = -0.22f, expectBiasSign = -1, expectAction = "CENTER LEFT")
        sweep("half a metre off centre", 1.00f, 1.55f, gridOffset = 0.22f, expectBiasSign = 1, expectAction = "CENTER RIGHT")
        sweep("dead centre in a corridor", 1.10f, 1.10f, gridOffset = 0.22f, expectBias = 0, expectAction = "FORWARD")

        heading("Outside a corridor: the walls stop being a centre line")
        sweep("open room (both sides far)", 4.00f, 4.60f, gridOffset = 0.0f, expectBias = 0, expectAction = "FORWARD")
        sweep("one wall missing", null, 1.20f, gridOffset = 0.0f, expectBias = 0, expectAction = "FORWARD")
        // A wall hard against one side and open space on the other is not a corridor to centre in, but
        // it is still a wall to keep off: the minimum nudge away from it, and no more.
        sweep("wall on the left, doorway on the right", 0.70f, 3.20f, gridOffset = 0.0f, expectBiasSign = 1, expectAction = "CENTER RIGHT")

        heading("The correction is bounded, and it does not fight the route")
        val mild = update(left = 0.95f, right = 1.60f, offset = 0f)
        val strong = update(left = 0.55f, right = 1.95f, offset = 0f)
        line("  bias at 0.65 m off centre = ${mild.steeringBias}   at 1.40 m off centre = ${strong.steeringBias}")
        check("the correction grows with the offset", strong.steeringBias > mild.steeringBias)
        check("the correction never exceeds the smoothing maximum", strong.steeringBias <= 45)
        val routed = ObstacleAvoidanceController(clockMillis = { now }, tuning = { MotorSettings() })
            .update(scene(1.20f, 1.75f, 0.22f), true, true, true, true, DesiredTravelDirection.RIGHT)
        line("  with a route asking for a right turn: bias=${routed.steeringBias} action='${routed.action}'")
        check("a route's turn intent is not overridden by hallway centring", routed.action != "CENTER RIGHT")

        line("")
        line("=".repeat(100))
        line("RESULT: $failures assertion(s) failed")
        line("=".repeat(100))
        write()
        assertEquals("harness assertions failed", 0, failures)
    }

    private fun sweep(
        label: String,
        left: Float?,
        right: Float?,
        gridOffset: Float,
        expectBias: Int? = null,
        expectBiasSign: Int = 0,
        expectAction: String? = null,
    ) {
        val decision = update(left, right, gridOffset)
        val diff = if (left != null && right != null) "%.2f".format(kotlin.math.abs(right - left)) else "--"
        line("  %-9s %-9s %-8s %-7s %-15s %d/%d   (%s)".format(
            left?.let { "%.2f".format(it) } ?: "--",
            right?.let { "%.2f".format(it) } ?: "--",
            diff,
            decision.steeringBias,
            decision.action,
            decision.wheelSpeeds.left,
            decision.wheelSpeeds.right,
            label,
        ))
        if (expectBias != null) check("$label: no correction", decision.steeringBias == expectBias)
        if (expectBiasSign != 0) check(
            "$label: steers ${if (expectBiasSign > 0) "right" else "left"}",
            if (expectBiasSign > 0) decision.steeringBias > 0 else decision.steeringBias < 0,
        )
        if (expectAction != null) check("$label: action is $expectAction", decision.action == expectAction)
    }

    private fun update(left: Float?, right: Float?, offset: Float) =
        ObstacleAvoidanceController(clockMillis = { now }, tuning = { MotorSettings() })
            .update(scene(left, right, offset), true, true, true, true)

    /** A depth frame as the three boxes report it: the two walls, and an open middle. */
    private fun scene(left: Float?, right: Float?, offset: Float): SceneAwarenessResult = SceneAwarenessResult(
        leftDistanceMeters = left,
        centerDistanceMeters = null,
        rightDistanceMeters = right,
        leftState = zoneStateOf(left),
        centerState = SceneZoneState.CLEAR,
        rightState = zoneStateOf(right),
        depthTimestampNanos = now * 1_000_000,
        supportPlaneDetected = true,
    )

    /** The thresholds the analyzer uses, applied to a wall distance. */
    private fun zoneStateOf(distance: Float?): SceneZoneState = when {
        distance == null -> SceneZoneState.UNKNOWN
        distance <= 0.8f -> SceneZoneState.BLOCKED
        distance <= 1.5f -> SceneZoneState.CAUTION
        else -> SceneZoneState.CLEAR
    }

    private fun heading(t: String) {
        line("")
        line("=".repeat(100))
        line(t)
        line("=".repeat(100))
    }

    private fun line(t: String) {
        log.append(t).append('\n')
    }

    private fun check(what: String, ok: Boolean) {
        line("  CHECK ${if (ok) "PASS" else "FAIL"}: $what")
        if (!ok) failures++
    }

    private fun write() {
        val out = resolveLogFile("hallway-centring.log")
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
