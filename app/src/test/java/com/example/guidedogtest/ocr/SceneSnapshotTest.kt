package com.example.guidedogtest.ocr

import com.example.guidedogtest.voice.SensorSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the conversation is told about the boxes.
 *
 * Two things have to hold or the assistant lies to someone on a leash: the depth box is metres while
 * the model is told millimetres, and a robot that cannot see must say so rather than report a clear
 * path it has no evidence for. Note also what a *floor* produces - nothing. The nearest thing in the
 * middle box is only reported when it stands above the ground plane, so an empty hallway is not
 * "something 1.4 metres ahead".
 */
class SceneSnapshotTest {

    private val liveFrame = 1L

    @Test
    fun distancesReachTheModelInMillimetres() {
        val scene = SceneAwarenessResult(
            centerDistanceMeters = 1.25f,
            centerState = SceneZoneState.CAUTION,
            depthTimestampNanos = liveFrame,
            supportPlaneDetected = true,
        )
        assertEquals(1250, scene.toSensorSnapshot(isMoving = true).frontDistanceMm)
        assertTrue(scene.toSensorSnapshot(isMoving = true).hazardsKnown)
    }

    @Test
    fun anEmptyCorridorReportsNoFrontDistanceAtAll() {
        // All three boxes clear means the boxes hold nothing but floor, which is not a hazard.
        val scene = SceneAwarenessResult(
            leftState = SceneZoneState.CLEAR,
            centerState = SceneZoneState.CLEAR,
            rightState = SceneZoneState.CLEAR,
            depthTimestampNanos = liveFrame,
            supportPlaneDetected = true,
        )
        val snapshot = scene.toSensorSnapshot(isMoving = true)
        assertEquals(SensorSnapshot.NO_HAZARD_MM, snapshot.frontDistanceMm)
        assertTrue(snapshot.hazardsKnown)
    }

    @Test
    fun withoutAFrameOrAGroundPlaneTheModelIsToldItCannotSee() {
        assertFalse(SceneAwarenessResult().toSensorSnapshot(isMoving = false).hazardsKnown)
        assertFalse(
            SceneAwarenessResult(
                centerState = SceneZoneState.CLEAR,
                depthTimestampNanos = liveFrame,
                supportPlaneDetected = false,
            ).toSensorSnapshot(isMoving = false).hazardsKnown
        )
        // A frame with a plane but not one readable box is the same answer: nothing can be judged.
        assertFalse(
            SceneAwarenessResult(
                depthTimestampNanos = liveFrame,
                supportPlaneDetected = true,
            ).toSensorSnapshot(isMoving = false).hazardsKnown
        )
    }

    @Test
    fun aBlockedSideIsAHazardAndAClearCentreIsNotAFrontDistance() {
        val scene = SceneAwarenessResult(
            leftState = SceneZoneState.BLOCKED,
            leftDistanceMeters = 0.6f,
            centerState = SceneZoneState.CLEAR,
            rightState = SceneZoneState.CLEAR,
            depthTimestampNanos = liveFrame,
            supportPlaneDetected = true,
        )
        val snapshot = scene.toSensorSnapshot(isMoving = false)
        assertTrue(snapshot.obstacleLeft)
        assertFalse(snapshot.obstacleRight)
        assertEquals(SensorSnapshot.NO_HAZARD_MM, snapshot.frontDistanceMm)
    }
}
