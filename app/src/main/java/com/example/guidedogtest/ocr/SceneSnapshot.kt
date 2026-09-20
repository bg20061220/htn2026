package com.example.guidedogtest.ocr

import com.example.guidedogtest.voice.SensorSnapshot
import kotlin.math.roundToInt

/**
 * The depth scene, reduced to the handful of facts the conversation is allowed to state.
 *
 * Distances cross into millimetres here and nowhere else: the depth frame is metres and
 * [SensorSnapshot.frontDistanceMm] is millimetres, so this is the single conversion, in a function
 * that can be checked without a camera.
 *
 * The zone states are already the analyzer's answer to "how close is too close" (its thresholds),
 * so they are what decides the left and right hazard flags rather than a second set of numbers
 * invented here. Only the centre zone is reported as a front distance: something a metre off to the
 * left is a left hazard, not "a metre ahead".
 */
fun SceneAwarenessResult.toSensorSnapshot(isMoving: Boolean): SensorSnapshot {
    // The boxes can be read only when the ground plane fitted - without it, floor and obstacle are
    // the same reading - and when at least one box has something to say.
    val depthLive = depthTimestampNanos != null && supportPlaneDetected && !seesNothing
    return SensorSnapshot(
        frontDistanceMm = centerDistanceMeters?.let { (it * 1000f).roundToInt() }
            ?: SensorSnapshot.NO_HAZARD_MM,
        obstacleLeft = leftState == SceneZoneState.BLOCKED,
        obstacleRight = rightState == SceneZoneState.BLOCKED,
        // Not reported: the drop signal false-fires on this floor (a glossy surface reads past the
        // ground plane), so nothing acts on it and nothing claims it - the plumbing stays for the ToF
        // sensor when it is fitted.
        dropoffDetected = false,
        isMoving = isMoving,
        hazardsKnown = depthLive,
    )
}

/** True when this controller state means the wheels are turning. */
fun AvoidanceState.isDriving(): Boolean =
    this == AvoidanceState.FORWARD || this == AvoidanceState.SLOW ||
        this == AvoidanceState.TURN_LEFT || this == AvoidanceState.TURN_RIGHT
