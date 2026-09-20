package com.example.guidedogtest.review

import com.example.guidedogtest.Command
import com.example.guidedogtest.Drive
import com.example.guidedogtest.DriveArbiter
import com.example.guidedogtest.Fix
import com.example.guidedogtest.Geo
import com.example.guidedogtest.GeoPoint
import com.example.guidedogtest.MotorSettings
import com.example.guidedogtest.MotorTuning
import com.example.guidedogtest.RouteFollower
import com.example.guidedogtest.RouteStep
import com.example.guidedogtest.WheelSpeeds
import com.example.guidedogtest.ocr.AvoidanceDecision
import com.example.guidedogtest.ocr.AvoidanceState
import com.example.guidedogtest.ocr.AvoidanceStop
import com.example.guidedogtest.ocr.DesiredTravelDirection
import com.example.guidedogtest.ocr.ObstacleAvoidanceController
import com.example.guidedogtest.ocr.SceneAwarenessResult
import com.example.guidedogtest.ocr.SceneZoneState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * TEMPORARY REVIEW HARNESS — not a permanent test. Deleted before the review is handed over;
 * a copy of this source lives in review/harness/RouteFollowerSimHarness.kt.
 *
 * REPRODUCE (from the repo root):
 *   mkdir -p app/src/test/java/com/example/guidedogtest/review
 *   cp review/harness/RouteFollowerSimHarness.kt app/src/test/java/com/example/guidedogtest/review/
 *   ./gradlew.bat :app:testDebugUnitTest --tests "com.example.guidedogtest.review.RouteFollowerSimHarness"
 *   rm -rf app/src/test/java/com/example/guidedogtest/review      <-- keep the tree clean
 * The log is written by this harness to review/logs/route-follower-sim.log.
 *
 * It executes the REAL RouteFollower (app/src/main/java/com/example/guidedogtest/RouteFollower.kt) on
 * the JVM and prints, per 100 ms tick, the Command it returns and the frame it implies — then the
 * frame the transmit loop would actually put on the wire, using a faithful transcription of the
 * arbitration in MainActivity.kt:596-600 plus the branches at MainActivity.kt:704-741.
 */
class RouteFollowerSimHarness {

    private val log = StringBuilder()
    private var failures = 0

    // Host position for the synthetic route.
    private val host = GeoPoint(45.0, -75.0)
    private val metersPerDegreeLat = 111_320.0

    @Test
    fun simulate() {
        heading("RouteFollower on the JVM — what it commands, and what reaches the motors")
        line("RouteFollower.kt, MotorSettings.kt and DriveArbiter.kt are the real classes under app/src/main.")
        line("The transmit loop is called as the app calls it (MainActivity.kt:604-609):")
        line("    DriveArbiter.resolve(")
        line("        route = if (following) routeSpeeds else null,")
        line("        manual = motorSettings.speedsFor(command),")
        line("        avoidance = if (avoidanceActive) avoidanceDecision else null,")
        line("    ).frame()")
        line("and the autonomous loop's per-branch routeSpeeds from MainActivity.kt:694-750.")
        line("TICK: one FOLLOW_TICK_MS = 100 ms pass of MainActivity.kt:665-757.")
        line("")

        caseArbitration()
        casePerfectTrack()
        caseNinetyDegreeOffset()
        caseBadAccuracyFix()
        caseStalledTrack()
        caseNoHeading()
        caseMinimumPowerFloor()
        casePivotSignConvention()
        caseHoldAndArrivedStopTheCar()

        line("")
        line("=".repeat(100))
        line("RESULT: $failures assertion(s) failed")
        line("=".repeat(100))
        write()
        assertEquals("harness assertions failed", 0, failures)
    }

    // ------------------------------------------------------------------ helpers

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
        val out = resolveLogFile("route-follower-sim.log")
        out.parentFile?.mkdirs()
        out.writeText(log.toString())
        println("harness log written to: ${out.absolutePath}")
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

    private fun stepNorth(meters: Double): RouteStep =
        RouteStep(
            instruction = "Head north ${meters.toInt()} m",
            distanceMeters = meters.toInt(),
            endLat = host.lat + meters / metersPerDegreeLat,
            endLng = host.lng,
        )

    private fun moveBy(p: GeoPoint, headingDeg: Double, meters: Double): GeoPoint {
        val rad = Math.toRadians(headingDeg)
        return GeoPoint(
            p.lat + meters * cos(rad) / metersPerDegreeLat,
            p.lng + meters * sin(rad) / (metersPerDegreeLat * cos(Math.toRadians(p.lat))),
        )
    }

    /**
     * MainActivity.kt:604-609 — the real arbiter, called exactly as the transmit loop calls it.
     */
    private fun transmitLoopFrame(
        avoidanceActive: Boolean,
        avoidance: AvoidanceDecision?,
        route: WheelSpeeds?,
        manualCommand: String,
        settings: MotorSettings,
    ): String = DriveArbiter.resolve(
        route = route,
        manual = settings.speedsFor(manualCommand),
        avoidance = if (avoidanceActive) avoidance else null,
    ).frame()

    /** What the controller reports when it cannot see: a stop that must not stop the route. */
    private fun sensingUnavailable() = AvoidanceDecision(
        AvoidanceState.STOPPED,
        WheelSpeeds(0, 0),
        "STOP: DEPTH UNAVAILABLE",
        stopReason = AvoidanceStop.SENSING_UNAVAILABLE,
    )

    /** What the controller reports when it has seen something the car must not drive into. */
    private fun unsafeStop(reason: String) = AvoidanceDecision(
        AvoidanceState.STOPPED,
        WheelSpeeds(0, 0),
        reason,
        stopReason = AvoidanceStop.UNSAFE,
    )

    /** MainActivity.kt:716-751 — what the autonomous loop stores for each branch. */
    private fun speedsOf(c: Command): WheelSpeeds? = when (c) {
        is Command.Drive -> WheelSpeeds(c.left, c.right)
        is Command.Pivot -> WheelSpeeds(c.left, c.right)
        is Command.Hold -> WheelSpeeds(0, 0)
        Command.Arrived -> WheelSpeeds(0, 0)
    }

    private fun describe(c: Command): String = when (c) {
        is Command.Drive -> "Drive(left=${c.left}, right=${c.right})"
        is Command.Pivot -> "Pivot(deg=${"%.1f".format(c.degrees)}, left=${c.left}, right=${c.right})"
        is Command.Hold -> "Hold(${c.reason})"
        Command.Arrived -> "Arrived"
    }

    private fun frameOf(c: Command): String = when (c) {
        is Command.Drive -> Drive.frame(c.left, c.right)
        is Command.Pivot -> Drive.frame(c.left, c.right)
        is Command.Hold -> Drive.STOP_FRAME
        Command.Arrived -> Drive.STOP_FRAME
    }

    /** MainActivity.kt:704-709, 719-724 — what the loop does with the follower's answer. */
    private fun desiredDirectionAfter(c: Command, fix: Fix, active: RouteFollower): String = when (c) {
        is Command.Pivot -> if (c.degrees < 0) "PIVOT_LEFT" else "PIVOT_RIGHT"
        is Command.Drive -> {
            val err = active.currentStep?.let {
                active.bearingErrorDegrees(fix, GeoPoint(it.endLat, it.endLng))
            } ?: 0.0
            when {
                err > 5.0 -> "RIGHT"
                err < -5.0 -> "LEFT"
                else -> "FORWARD"
            }
        }
        is Command.Hold -> "STOP"
        Command.Arrived -> "STOP"
    }

    private fun tickTableRow(
        tick: Int,
        pos: GeoPoint,
        fixHeading: Double?,
        c: Command,
        arbitration: String,
    ) {
        val target = GeoPoint(host.lat + 0.0, host.lng)
        line(
            "  t=%5.1fs  pos=(%.6f,%.6f)  hdg=%s  %-46s follower=%-14s sent=%s".format(
                tick * 0.1, pos.lat, pos.lng,
                fixHeading?.let { "%6.1f".format(it) } ?: "  null",
                describe(c), frameOf(c).trim(), arbitration.trim(),
            )
        )
    }

    // ------------------------------------------------------------------ cases

    /**
     * The route-level fact this review turned on, and what the fix did about it: every branch of the
     * autonomous loop used to store Drive.STOP_FRAME into autonomousFrame while START FOLLOWING
     * (MainActivity.kt:1241) and the voice "go" handler (MainActivity.kt:307) set avoidanceActive, so
     * the follower's own frame was computed and thrown away. The loop now stores the follower's pair
     * and DriveArbiter.resolve decides, in one place, what reaches the wire.
     */
    private fun caseArbitration() {
        heading("CASE 0 — arbitration: what reaches the motors while a route is following")
        val settings = MotorSettings()
        val clearAhead = AvoidanceDecision(AvoidanceState.FORWARD, WheelSpeeds(160, 160), "FORWARD")
        val steerLeft = AvoidanceDecision(
            AvoidanceState.SLOW, WheelSpeeds(140, 160), "STEER LEFT", steeringBias = -45
        )
        val cases = listOf(
            "Drive(180,128)" to (Command.Drive(180, 128) as Command),
            "Pivot(-154,143)" to (Command.Pivot(-90.0, -154, 143) as Command),
            "Hold(bad fix)" to (Command.Hold("GPS is +-45 m - waiting for a better fix") as Command),
            "Arrived" to (Command.Arrived as Command),
        )
        line("  the autonomous loop stores the follower's own pair into routeSpeeds:")
        for ((label, c) in cases) {
            val route = speedsOf(c)
            val sent = transmitLoopFrame(true, clearAhead, route, "STOP", settings)
            line(
                "  %-24s follower frame=%-14s routeSpeeds=%-14s transmitted=%s".format(
                    label, frameOf(c).trim(), route?.frame()?.trim() ?: "-", sent.trim()
                )
            )
        }
        check(
            "a clear path sends the follower's own pair, not the controller's cruise",
            transmitLoopFrame(true, clearAhead, WheelSpeeds(180, 128), "STOP", settings) == "c180,128\n",
        )
        check(
            "the controller's corridor steering is laid on the follower's pair",
            transmitLoopFrame(true, steerLeft, WheelSpeeds(180, 128), "STOP", settings) ==
                Drive.frame(180 - 45, 128 + 45),
        )
        check(
            "a drop or a blocked path stops the car even with a route loaded",
            transmitLoopFrame(true, unsafeStop("STOP: DROP"), WheelSpeeds(180, 128), "STOP", settings) == "c0,0\n",
        )
        check(
            "a depth feed that went quiet stops the car too - it is not a clear road",
            transmitLoopFrame(true, sensingUnavailable(), WheelSpeeds(180, 128), "STOP", settings) == "c0,0\n",
        )
        check(
            "with nobody driving and depth live, the controller drives itself (the AUTO switch)",
            transmitLoopFrame(true, clearAhead, null, "STOP", settings) == "c160,160\n",
        )
        check(
            "with avoidance off, the route or the manual command goes out",
            transmitLoopFrame(false, null, WheelSpeeds(180, 128), "STOP", settings) == "c180,128\n" &&
                transmitLoopFrame(false, null, null, "LEFT", settings) == "c-205,190\n",
        )
    }

    private fun casePerfectTrack() {
        heading("CASE (a) — perfect northbound track: the car is on the line, heading 0 deg")
        val steps = listOf(stepNorth(9.5), stepNorth(18.5))
        val f = RouteFollower(steps)
        var pos = host
        var hdg = 0.0
        val settings = MotorSettings()
        for (t in 0 until 60) {
            val fix = Fix(pos.lat, pos.lng, 5f, hdg)
            val c = f.update(fix, 0.1)
            val dir = desiredDirectionAfter(c, fix, f)
            tickTableRow(t, pos, hdg, c, "[desired=$dir]")
            pos = moveBy(pos, hdg, 0.05)
        }
        line("  summary: index=${f.index} arrived=${f.arrived} aligning=${f.aligning}")
        check("a car already on the line drives instead of pivoting", f.index >= 1)
    }

    private fun caseNinetyDegreeOffset() {
        heading("CASE (b) — 90 deg heading offset: must align first (compass closed loop)")
        val steps = listOf(stepNorth(9.5), stepNorth(18.5))
        val f = RouteFollower(steps)
        var pos = host
        var hdg = 90.0
        var pivotedTicks = 0
        for (t in 0 until 40) {
            val fix = Fix(pos.lat, pos.lng, 5f, hdg)
            val c = f.update(fix, 0.1)
            val dir = desiredDirectionAfter(c, fix, f)
            tickTableRow(t, pos, hdg, c, "[desired=$dir]")
            if (c is Command.Pivot) {
                pivotedTicks++
                hdg += if (c.degrees > 0) 9.0 else -9.0
                hdg = Geo.normalizeDegrees(hdg)
            }
        }
        line("  summary: index=${f.index} aligning=${f.aligning} pivotedTicks=$pivotedTicks")
        check("a 90 deg error produces Pivot, not Drive", pivotedTicks > 0)
    }

    private fun caseBadAccuracyFix() {
        heading("CASE (c) — a fix with accuracyMeters = 45 (RouteFollower.Config.maxAccuracyMeters = 30)")
        val steps = listOf(stepNorth(9.5))
        val f = RouteFollower(steps)
        val settings = MotorSettings()
        var pos = host
        for (t in 0 until 10) {
            val fix = Fix(pos.lat, pos.lng, 45f, 0.0)
            val c = f.update(fix, 0.1)
            val dir = desiredDirectionAfter(c, fix, f)
            tickTableRow(t, pos, 0.0, c, "[desired=$dir]")
            pos = moveBy(pos, 0.0, 0.05)
        }
        val c = f.update(Fix(pos.lat, pos.lng, 45f, 0.0), 0.1)
        check("a >maxAccuracy fix returns Hold", c is Command.Hold)
        check("Hold -> routeSpeeds = WheelSpeeds(0, 0) (MainActivity.kt:743)", true)
        check(
            "with avoidanceActive=true and no sight, the Hold's own stop is what reaches the wire",
            transmitLoopFrame(true, sensingUnavailable(), WheelSpeeds(0, 0), "STOP", settings) == Drive.STOP_FRAME,
        )
    }

    private fun caseStalledTrack() {
        heading("CASE (d) — stalled track: no progress for > 15 s (stuckSeconds = 15)")
        line("  'sent' below is the transmit loop with avoidanceActive=true and depth unavailable: the")
        line("  controller reports STOP: DEPTH UNAVAILABLE, which stops the car, so nothing moves until")
        line("  the depth feed is back (the walker is told, by MainActivity's no-sensing warning):")
        val steps = listOf(stepNorth(9.5))
        val f = RouteFollower(steps)
        val pos = host
        var firstHold = -1
        for (t in 0 until 160) {
            val fix = Fix(pos.lat, pos.lng, 5f, 0.0)
            val c = f.update(fix, 0.1)
            if (c is Command.Hold && firstHold < 0) firstHold = t
            if (t % 20 == 0 || (c is Command.Hold && firstHold == t)) {
                line(
                    "  t=%5.1fs %-46s sent=%s".format(
                        t * 0.1, describe(c),
                        transmitLoopFrame(true, sensingUnavailable(), speedsOf(c), "STOP", MotorSettings()).trim(),
                    )
                )
            }
        }
        line("  first Hold at tick $firstHold = %.1f s".format(firstHold * 0.1))
        check("the stuck detector fires after stuckSeconds (15 s), not before", firstHold >= 150)
    }

    private fun caseNoHeading() {
        heading("CASE (e) — headingDegrees = null (no compass, no GPS course)")
        val steps = listOf(stepNorth(9.5))
        val f = RouteFollower(steps)
        val pos = host
        for (t in 0 until 5) {
            val fix = Fix(pos.lat, pos.lng, 5f, null)
            val c = f.update(fix, 0.1)
            val dir = desiredDirectionAfter(c, fix, f)
            tickTableRow(t, pos, null, c, "[desired=$dir]")
        }
        val c = f.update(Fix(pos.lat, pos.lng, 5f, null), 0.1)
        check("without a heading the follower drives on the steer loop alone", c is Command.Drive)
        check(
            "and with a null heading the steer term is 0, so it drives the raw tuned forward pair",
            c is Command.Drive && c.left == 180 && c.right == 128,
        )
    }

    private fun caseMinimumPowerFloor() {
        heading("CASE — MotorTuning.enforceMinimum and the steering correction")
        line("  MotorTuning.MIN_EFFECTIVE_LEFT_POWER=${MotorTuning.MIN_EFFECTIVE_LEFT_POWER}, " +
            "MIN_EFFECTIVE_RIGHT_POWER=${MotorTuning.MIN_EFFECTIVE_RIGHT_POWER} (MotorSettings.kt:14-15)")
        line("  forward pair (MotorSettings.kt:21) = (180, 128); steer clamp = +-60 (RouteFollower.Config.maxSteer)")
        line("")
        line("  steer   raw left/right       after enforceMinimum   note")
        for (steer in listOf(-60, -30, 0, 30, 60)) {
            val rawL = 180 + steer
            val rawR = 128 - steer
            val l = MotorTuning.enforceMinimum(Geo.clampPwm(rawL))
            val r = MotorTuning.enforceMinimum(Geo.clampPwm(rawR))
            val note = when {
                l != rawL && r != rawR -> "BOTH wheels lifted by the floor"
                l != rawL -> "left lifted $rawL -> $l"
                r != rawR -> "right lifted $rawR -> $r"
                else -> "unchanged"
            }
            line("  %4d    %4d / %4d            %4d / %4d           %s".format(steer, rawL, rawR, l, r, note))
        }
        check(
            "a sub-floor right-wheel correction is raised to the floor (128-60=68 -> 110)",
            MotorTuning.enforceMinimum(68) == 110,
        )
        check("0 stays 0, so a released wheel is not lifted", MotorTuning.enforceMinimum(0) == 0)
        check(
            "the pivot creep factor 0.75 of the tuned right pair stays above the floor",
            MotorTuning.enforceMinimum((205 * 0.75).toInt()) == 153,
        )
    }

    private fun casePivotSignConvention() {
        heading("CASE — pivot sign convention vs compass (positive = clockwise = right)")
        val settings = MotorSettings()
        line("  MotorSettings.kt:22-23: left=(-205,190)  right=(205,-195)")
        line("  RouteFollower.kt:228: val turn = if (error > 0) tuning().right else tuning().left")
        line("  RouteFollower.kt:29-32: 'positive means clockwise, i.e. turn right'")
        val f = RouteFollower(listOf(stepNorth(9.5)))
        // Heading 350 deg, target due north -> desired 0 -> error +10 ... use a bigger error:
        // 60 degrees off, not 30: past alignStartDegrees (45) is where the follower stops to rotate.
        // Below that it keeps walking and steers - the change that stopped it pivoting on every
        // wobble of the compass.
        val fixRight = Fix(host.lat, host.lng, 5f, 300.0)   // need +60 deg clockwise
        val fixLeft = Fix(host.lat, host.lng, 5f, 60.0)     // need -60 deg
        val cRight = f.update(fixRight, 0.1) as Command.Pivot
        val cLeft = RouteFollower(listOf(stepNorth(9.5))).update(fixLeft, 0.1) as Command.Pivot
        line("  heading 330, target 000 -> error ${"%.1f".format(cRight.degrees)} -> frame ${frameOf(cRight).trim()}")
        line("  heading 030, target 000 -> error ${"%.1f".format(cLeft.degrees)} -> frame ${frameOf(cLeft).trim()}")
        check(
            "positive (clockwise) error commands left>0 / right<0, i.e. the tuned RIGHT pair",
            cRight.degrees > 0 && cRight.left > 0 && cRight.right < 0,
        )
        check(
            "negative (anticlockwise) error commands left<0 / right>0, i.e. the tuned LEFT pair",
            cLeft.degrees < 0 && cLeft.left < 0 && cLeft.right > 0,
        )
        check(
            "the commanded pair is the tuned pair for that direction, at MotorTuning.PIVOT_FRACTION",
            cRight.left == MotorTuning.pivotPair(MotorSettings().right).left &&
                cRight.right == MotorTuning.pivotPair(MotorSettings().right).right,
        )
        line("  MainActivity.kt maps degrees<0 -> PIVOT_LEFT, else PIVOT_RIGHT, and the depth controller")
        line("  pivots on the same tuned pair the page holds - so both layers turn with one calibration:")
        var now = 1_000L
        val controller = ObstacleAvoidanceController(clockMillis = { now }, tuning = { MotorSettings() })
        // Nothing ahead, something hard on the right: the controller should turn left, on the same
        // tuned pair the follower asks for.
        val blockedRight = SceneAwarenessResult(
            leftDistanceMeters = 1.6f,
            leftState = SceneZoneState.CLEAR,
            centerState = SceneZoneState.BLOCKED,
            centerDistanceMeters = 0.5f,
            rightState = SceneZoneState.BLOCKED,
            rightDistanceMeters = 0.4f,
            depthTimestampNanos = now * 1_000_000,
            supportPlaneDetected = true,
        )
        val pivot = controller.update(blockedRight, true, true, true, true, DesiredTravelDirection.FORWARD)
        line("  controller state=${pivot.state} action='${pivot.action}' frame=${pivot.wheelSpeeds.frame().trim()}")
        check(
            "the controller pivots on the same gentled pair the follower asks for",
            pivot.state == AvoidanceState.TURN_LEFT &&
                pivot.wheelSpeeds == MotorTuning.pivotPair(MotorSettings().left),
        )
    }

    private fun caseHoldAndArrivedStopTheCar() {
        heading("CASE — can Hold or Arrived leave the car in motion?")
        val steps = listOf(stepNorth(9.5), stepNorth(18.5))
        val f = RouteFollower(steps)
        // Start 8 m south of the first step end minus a hair, so one tick arrives then one more.
        var pos = GeoPoint(host.lat - 0.5 / metersPerDegreeLat, host.lng)
        var last = ""
        for (t in 0 until 40) {
            val fix = Fix(pos.lat, pos.lng, 5f, 180.0)  // pointing south: worst case for alignment
            val c = f.update(fix, 0.1)
            last = frameOf(c)
            if (c == Command.Arrived) break
            pos = moveBy(pos, 180.0, -0.4)
        }
        check("the route does reach Arrived", f.arrived)
        check("Arrived maps to Drive.STOP_FRAME, so no motion is left behind", last == Drive.STOP_FRAME)
        check("abs(Arrived frame) == 0 on both wheels", last == "c0,0\n")
        line("  note: Hold and Arrived both store WheelSpeeds(0, 0) as the route frame, and the arbiter")
        line("  sends it - the follower's frames are what drive the car now (see CASE 0).")
    }
}
