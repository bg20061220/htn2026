package com.example.guidedogtest.review

import com.example.guidedogtest.MotorSettings
import com.example.guidedogtest.ocr.AvoidanceState
import com.example.guidedogtest.ocr.AvoidanceStop
import com.example.guidedogtest.ocr.ObstacleAvoidanceController
import com.example.guidedogtest.ocr.SceneAwarenessResult
import com.example.guidedogtest.ocr.SceneZoneState
import org.junit.Test
import java.io.File

/**
 * TEMPORARY HARNESS — not a permanent test. Deleted after the run; the source is kept in
 * review/harness/MiddleBoxStopHarness.kt.
 *
 * REPRODUCE (from the repo root):
 *   mkdir -p app/src/test/java/com/example/guidedogtest/review
 *   cp review/harness/MiddleBoxStopHarness.kt app/src/test/java/com/example/guidedogtest/review/
 *   ./gradlew.bat :app:testDebugUnitTest --tests "com.example.guidedogtest.review.MiddleBoxStopHarness"
 *   rm -rf app/src/test/java/com/example/guidedogtest/review
 * The log is written by this harness to review/logs/middle-box-stop.log.
 *
 * What it answers: with the whole obstacle strategy reduced to "something in the middle box stops the
 * car", what does the controller hand the arbiter, frame by frame, through a stop and the answer to it?
 * It steps the real controller through a walk: nothing in front, an obstacle arriving, the frames after
 * that flickering clear, the frames after that going unreadable because the robot is standing still,
 * and then the walker answering the stop.
 */
class MiddleBoxStopHarness {
    private var now = 10_000L
    private var tick = 0L
    private val controller = ObstacleAvoidanceController(clockMillis = { now })
    private val log = StringBuilder()

    @Test fun `trace the middle-box stop through a walk`() {
        line("middle-box stop: what the controller hands the arbiter, frame by frame")
        line("rule: anything in the middle box -> stop; empty middle -> drive on the calibrated pair")
        line("      nothing readable -> do what was asked; the stop is held until a new command")
        line("")

        step("walking, nothing in front") {
            controller.update(frame(SceneZoneState.CLEAR, centreMeters = null), true, true, true, true)
        }
        step("an obstacle arrives in the middle box") {
            controller.update(frame(SceneZoneState.BLOCKED, centreMeters = 0.6f), true, true, true, true)
        }
        step("the next frame reads clear again (a flicker)") {
            controller.update(frame(SceneZoneState.CLEAR, centreMeters = null), true, true, true, true)
        }
        step("two more clear frames") {
            controller.update(frame(SceneZoneState.CLEAR, centreMeters = null), true, true, true, true)
        }
        step("the robot is still, so its depth goes stale") {
            controller.update(frame(SceneZoneState.CLEAR, centreMeters = null, plane = false), true, true, true, true)
        }
        step("no frame at all") {
            controller.update(null, true, true, true, true)
        }
        step("the walker answers the stop: \"go on\"") {
            controller.releaseObstacleStop()
            controller.update(frame(SceneZoneState.CLEAR, centreMeters = null, plane = false), true, true, true, true)
        }
        step("the obstacle is still there after the answer") {
            controller.update(frame(SceneZoneState.BLOCKED, centreMeters = 0.6f), true, true, true, true)
        }
        step("it is gone, and the walker answers again") {
            controller.releaseObstacleStop()
            controller.update(frame(SceneZoneState.CLEAR, centreMeters = null), true, true, true, true)
        }
        line("")
        step("a side box goes red in a clear hallway (no steering, no turn)") {
            controller.update(
                frame(SceneZoneState.CLEAR, left = SceneZoneState.BLOCKED, leftMeters = 0.4f, rightMeters = 2.6f),
                true, true, true, true,
            )
        }
        step("no link to the car") {
            controller.update(frame(SceneZoneState.CLEAR), true, true, true, false)
        }

        val file = resolveLogFile("middle-box-stop.log")
        file.parentFile?.mkdirs()
        file.writeText(log.toString())
        println(log)
    }

    private fun step(what: String, decide: () -> com.example.guidedogtest.ocr.AvoidanceDecision) {
        val decision = decide()
        val reason = if (decision.stopReason == AvoidanceStop.NONE) "" else "  (${decision.stopReason})"
        line(
            "%-64s -> %-10s %-26s L=%4d R=%4d%s".format(
                what,
                decision.state,
                decision.action,
                decision.wheelSpeeds.left,
                decision.wheelSpeeds.right,
                reason,
            ),
        )
    }

    private fun line(text: String) {
        log.append(text).append('\n')
    }

    /** Walk up from the test's working directory to the repo root (the dir holding settings.gradle.kts). */
    private fun resolveLogFile(name: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return File(dir, "review/logs/$name")
            dir = dir.parentFile
        }
        return File("review/logs/$name")
    }

    private fun frame(
        centre: SceneZoneState,
        centreMeters: Float? = null,
        left: SceneZoneState = SceneZoneState.CLEAR,
        leftMeters: Float? = null,
        rightMeters: Float? = null,
        plane: Boolean = true,
    ) = SceneAwarenessResult(
        leftDistanceMeters = leftMeters,
        centerDistanceMeters = centreMeters,
        rightDistanceMeters = rightMeters,
        leftState = left,
        centerState = centre,
        rightState = SceneZoneState.CLEAR,
        depthTimestampNanos = ++tick,
        supportPlaneDetected = plane,
    )

    @Suppress("unused")
    private fun forwardPair() = MotorSettings().forward
}
