package com.example.guidedogtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The follower is deliberately free of Android, network and timing dependencies: it takes a Fix and
 * a time delta and returns a Command. That is what these tests exercise.
 */
class RouteFollowerTest {

    private val origin = GeoPoint(43.4723, -80.5449)

    // --- geo maths -------------------------------------------------------------------------

    @Test
    fun distancesAreSane() {
        assertEquals(0.0, Geo.distanceMeters(origin, origin), 0.5)
        // 0.001 degrees of latitude is about 111 m anywhere.
        assertEquals(
            111.0,
            Geo.distanceMeters(origin, GeoPoint(origin.lat + 0.001, origin.lng)),
            2.0,
        )
        // 0.001 degrees of longitude shrinks with latitude (cos 43.47 -> about 81 m).
        assertEquals(
            81.0,
            Geo.distanceMeters(origin, GeoPoint(origin.lat, origin.lng + 0.001)),
            2.0,
        )
    }

    @Test
    fun bearingsPointTheRightWay() {
        assertEquals(0.0, Geo.bearingDegrees(origin, GeoPoint(origin.lat + 0.01, origin.lng)), 0.5)
        assertEquals(90.0, Geo.bearingDegrees(origin, GeoPoint(origin.lat, origin.lng + 0.01)), 0.5)
    }

    @Test
    fun anglesWrapAroundZero() {
        assertEquals(10.0, Geo.normalizeDegrees(370.0), 0.001)
        assertEquals(10.0, Geo.normalizeDegrees(-350.0), 0.001)
        assertEquals(-170.0, Geo.normalizeDegrees(190.0), 0.001)
    }

    @Test
    fun headingsGetSpokenNames() {
        assertEquals("N", Geo.compassPoint(0.0))
        assertEquals("N", Geo.compassPoint(350.0))
        assertEquals("NE", Geo.compassPoint(51.0))
        assertEquals("E", Geo.compassPoint(90.0))
        assertEquals("S", Geo.compassPoint(180.0))
        assertEquals("W", Geo.compassPoint(270.0))
    }

    // --- follower --------------------------------------------------------------------------

    private fun step(meters: Int, lat: Double, lng: Double, text: String = "go") =
        RouteStep(text, meters, lat, lng)

    private fun fix(lat: Double, lng: Double, accuracy: Float = 5f, heading: Double? = null) =
        Fix(lat, lng, accuracy, heading)

    /** A step that ends due east of the origin, far enough away to stay out of the arrival radius. */
    private fun eastStep() = step(200, origin.lat, origin.lng + 0.002)

    @Test
    fun refusesToDriveOnABadFix() {
        val follower = RouteFollower(listOf(step(100, 43.48, -80.5449)))
        val command = follower.update(fix(origin.lat, origin.lng, accuracy = 60f), 1.0)
        assertTrue("expected a hold, got $command", command is Command.Hold)
    }

    @Test
    fun drivesStraightWithTheChassisTrimWhenThereIsNoHeading() {
        val follower = RouteFollower(listOf(step(100, 43.48, -80.5449)))
        assertEquals(
            Command.Drive(Drive.SPEED, Drive.SPEED - Drive.RIGHT_TRIM),
            follower.update(fix(origin.lat, origin.lng), 1.0),
        )
    }

    @Test
    fun turnsTowardsTheTargetWhenItIsFarOff() {
        // Target due east, car facing north: it has to turn right before it can drive.
        val follower = RouteFollower(listOf(eastStep()))
        val command = follower.update(fix(origin.lat, origin.lng, heading = 0.0), 1.0)

        assertTrue("expected a pivot, got $command", command is Command.Pivot)
        command as Command.Pivot
        assertTrue("expected a clockwise turn, got ${command.degrees}", command.degrees > 0)
        assertTrue("expected a right-hand pivot, got $command", command.left > 0 && command.right < 0)
        assertTrue(follower.aligning)
    }

    @Test
    fun drivesOnceTheHeadingIsInsideTheTolerance() {
        // 5 degrees off a due-east target: close enough to steer with the drive command.
        val follower = RouteFollower(listOf(eastStep()))
        val command = follower.update(fix(origin.lat, origin.lng, heading = 85.0), 1.0)

        assertTrue("expected a drive, got $command", command is Command.Drive)
        command as Command.Drive
        assertTrue("expected a right-hand steer, got $command", command.left > command.right)
    }

    @Test
    fun keepsTurningUntilTheErrorIsSmall() {
        val follower = RouteFollower(listOf(eastStep()))

        // Far off: it starts turning.
        assertTrue(follower.update(fix(origin.lat, origin.lng, heading = 0.0), 1.0) is Command.Pivot)

        // Still 20 degrees off - inside the start threshold, but a rotation already begun has to
        // finish inside the tighter one, otherwise it chatters between driving and turning.
        assertTrue(follower.update(fix(origin.lat, origin.lng, heading = 70.0), 1.0) is Command.Pivot)

        // Inside the stop threshold: drive.
        assertTrue(follower.update(fix(origin.lat, origin.lng, heading = 85.0), 1.0) is Command.Drive)
        assertTrue(!follower.aligning)
    }

    @Test
    fun aSmallErrorAloneDoesNotStopTheCarToTurn() {
        // 20 degrees off, but never started a rotation: steer while driving instead of stopping.
        val follower = RouteFollower(listOf(eastStep()))
        val command = follower.update(fix(origin.lat, origin.lng, heading = 70.0), 1.0)

        assertTrue("expected a drive, got $command", command is Command.Drive)
    }

    @Test
    fun turnSpeedUsesTheTunedPairAndEasesOffNearTheBearing() {
        val follower = RouteFollower(listOf(eastStep()))

        // Far off (90 degrees): the full pair tuned for a right turn.
        val wide = follower.update(fix(origin.lat, origin.lng, heading = 0.0), 1.0) as Command.Pivot
        assertEquals(MotorSettings().right.left, wide.left)
        assertEquals(MotorSettings().right.right, wide.right)

        // Closing on the bearing (26 degrees off, just past the start threshold): still full effort.
        val stillWide =
            follower.update(fix(origin.lat, origin.lng, heading = 64.0), 1.0) as Command.Pivot
        assertEquals(wide.left, stillWide.left)

        // Nearly there (14 degrees off): a fraction of the same pair, same asymmetry.
        val creep = follower.update(fix(origin.lat, origin.lng, heading = 76.0), 1.0) as Command.Pivot
        assertTrue("expected a creep, got $creep", abs(creep.left) < abs(wide.left))
        assertTrue(creep.left > 0 && creep.right < 0)
    }

    @Test
    fun turnsLeftForATargetToTheLeft() {
        // Target due west of the car facing north: counter-clockwise, so the left wheel reverses.
        val follower = RouteFollower(listOf(step(200, origin.lat, origin.lng - 0.002)))
        val command = follower.update(fix(origin.lat, origin.lng, heading = 0.0), 1.0) as Command.Pivot

        assertTrue("expected a left turn, got ${command.degrees}", command.degrees < 0)
        assertEquals(MotorSettings().left.left, command.left)
        assertEquals(MotorSettings().left.right, command.right)
    }

    @Test
    fun pivotMagnitudesNeverLeaveThePwmRange() {
        val follower = RouteFollower(listOf(eastStep()))
        val command = follower.update(fix(origin.lat, origin.lng, heading = 0.0), 1.0) as Command.Pivot

        assertTrue(abs(command.left) <= Drive.MAX_PWM)
        assertTrue(abs(command.right) <= Drive.MAX_PWM)
    }

    // --- the Configure Robot page drives routes -------------------------------------------------

    /** What the page is for: values tuned there are the values a route drives with. */
    private val tunedAway = MotorSettings(
        forward = WheelSpeeds(160, 145),
        left = WheelSpeeds(-150, 140),
        right = WheelSpeeds(155, -145),
    )

    @Test
    fun straightDrivingUsesTheTunedForwardPair() {
        val follower = RouteFollower(
            listOf(step(100, 43.48, -80.5449)),
            tuning = { tunedAway },
        )

        assertEquals(Command.Drive(160, 145), follower.update(fix(origin.lat, origin.lng), 1.0))
    }

    @Test
    fun turningUsesTheTunedPairsForEachDirection() {
        val follower = RouteFollower(listOf(eastStep()), tuning = { tunedAway })

        val right = follower.update(fix(origin.lat, origin.lng, heading = 0.0), 1.0) as Command.Pivot
        assertEquals(155, right.left)
        assertEquals(-145, right.right)

        val west = RouteFollower(listOf(step(200, origin.lat, origin.lng - 0.002)), tuning = { tunedAway })
        val left = west.update(fix(origin.lat, origin.lng, heading = 0.0), 1.0) as Command.Pivot
        assertEquals(-150, left.left)
        assertEquals(140, left.right)
    }

    @Test
    fun aValueChangedMidRouteAppliesOnTheNextTick() {
        var tuning = MotorSettings()
        val follower = RouteFollower(listOf(step(100, 43.48, -80.5449)), tuning = { tuning })

        assertEquals(
            Command.Drive(Drive.SPEED, Drive.SPEED - Drive.RIGHT_TRIM),
            follower.update(fix(origin.lat, origin.lng), 1.0),
        )

        tuning = MotorSettings(forward = WheelSpeeds(140, 120))
        assertEquals(Command.Drive(140, 120), follower.update(fix(origin.lat, origin.lng), 1.0))
    }

    @Test
    fun arrivesWhenItReachesTheLastStep() {
        val follower = RouteFollower(listOf(step(20, origin.lat, origin.lng)))
        assertEquals(Command.Arrived, follower.update(fix(origin.lat, origin.lng), 1.0))
        assertEquals(Command.Arrived, follower.update(fix(origin.lat, origin.lng), 1.0))
    }

    @Test
    fun walksThroughStepsInOrder() {
        val follower = RouteFollower(
            listOf(
                step(10, origin.lat, origin.lng),
                step(40, origin.lat + 0.0005, origin.lng + 0.0005),
            )
        )
        // Standing on step 1's end: it advances to step 2 and points itself at its end.
        val command = follower.update(fix(origin.lat, origin.lng, heading = 0.0), 1.0)
        assertTrue("expected a pivot onto the new bearing, got $command", command is Command.Pivot)
        // Index is 0-based, so the second step is index 1.
        assertEquals(1, follower.index)
        assertEquals("step 2 of 2", follower.progressLabel())
    }

    @Test
    fun givesUpWhenItStopsMakingProgress() {
        val follower = RouteFollower(listOf(step(500, 43.60, -80.5449)))
        var command: Command = follower.update(fix(origin.lat, origin.lng), 1.0)
        repeat(20) { command = follower.update(fix(origin.lat, origin.lng), 1.0) }
        assertTrue("expected a hold, got $command", command is Command.Hold)
    }
}
