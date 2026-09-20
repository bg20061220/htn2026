package com.example.guidedogtest.ocr

import android.os.SystemClock

enum class DepthVisualState { ACTIVE, UPDATING, STALE, UNSUPPORTED }

data class DepthVisualization(
    val scene: SceneAwarenessResult,
    val state: DepthVisualState,
)

/**
 * UI-only persistence for the three boxes.
 *
 * A box that reads UNKNOWN for a frame or two - a blank patch of wall, a hand over the lens - would
 * otherwise flicker on the panel and on the overlay, so its last known reading is held briefly. A
 * box that reads *safer* than it did has to be confirmed twice before the display follows it, while
 * a box that reads more dangerous (or finds a drop) is shown at once.
 *
 * Nothing here reaches the safety controller: that gets the raw scene, so a held-over display can
 * never look like a clear path to whoever is walking.
 */
class DepthVisualizationStabilizer(
    private val clockMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    private data class PendingRecovery(val state: SceneZoneState, val samples: Int)

    private var lastValidScene: SceneAwarenessResult? = null
    private var lastValidAtMillis = Long.MIN_VALUE
    private var latestSceneInputValid = false
    private val pendingRecoveries = mutableMapOf<SceneZone, PendingRecovery>()

    fun accept(scene: SceneAwarenessResult): DepthVisualization {
        if (isValid(scene)) {
            latestSceneInputValid = true
            val stabilized = stabilize(scene)
            lastValidScene = stabilized
            lastValidAtMillis = clockMillis()
            return DepthVisualization(stabilized, DepthVisualState.ACTIVE)
        }
        latestSceneInputValid = false
        return current(depthSupported = true, sessionActive = true, freshDepthAvailable = false)
    }

    fun current(
        depthSupported: Boolean,
        sessionActive: Boolean,
        freshDepthAvailable: Boolean,
    ): DepthVisualization {
        if (!depthSupported) return DepthVisualization(unknownScene(), DepthVisualState.UNSUPPORTED)
        val previous = lastValidScene
        val age = if (lastValidAtMillis == Long.MIN_VALUE) Long.MAX_VALUE else clockMillis() - lastValidAtMillis
        return when {
            freshDepthAvailable && latestSceneInputValid && previous != null && age <= DEPTH_ACTIVE_WINDOW_MS ->
                DepthVisualization(previous, DepthVisualState.ACTIVE)
            sessionActive && previous != null && age <= DEPTH_VISUAL_HOLD_MS ->
                DepthVisualization(previous, DepthVisualState.UPDATING)
            else -> DepthVisualization(unknownScene(), DepthVisualState.STALE)
        }
    }

    private fun stabilize(incoming: SceneAwarenessResult): SceneAwarenessResult {
        val previous = lastValidScene ?: return incoming
        var result = incoming
        for (zone in SceneZone.entries) {
            if (rank(incoming, zone) >= rank(previous, zone)) {
                pendingRecoveries.remove(zone)          // a hazard, or the same reading: show it now
                continue
            }
            val pending = pendingRecoveries[zone]
            val state = incoming.stateOf(zone)
            val samples = if (pending?.state == state) pending.samples + 1 else 1
            if (samples >= CLEAR_CONFIRMATION_SAMPLES) {
                pendingRecoveries.remove(zone)
                continue
            }
            pendingRecoveries[zone] = PendingRecovery(state, samples)
            result = result.withZone(
                zone = zone,
                state = previous.stateOf(zone),
                distance = incoming.distanceOf(zone) ?: previous.distanceOf(zone),
                drop = incoming.hasDrop(zone) || previous.hasDrop(zone),
            )
        }
        // The recommendation is re-derived from the boxes as they are being shown, so the panel cannot
        // say "forward" while a box it just held back says otherwise.
        val drop = result.leftDrop || result.centerDrop || result.rightDrop
        return result.copy(
            dropDetected = drop,
            recommendedDirection = recommendDirection(
                leftState = result.leftState,
                centerState = result.centerState,
                rightState = result.rightState,
                left = result.leftDistanceMeters,
                right = result.rightDistanceMeters,
                centerDrop = result.centerDrop,
                leftDrop = result.leftDrop,
                rightDrop = result.rightDrop,
            ),
        )
    }

    /** How much a box has to say for itself: a hole beats a wall, which beats a clear view. */
    private fun rank(scene: SceneAwarenessResult, zone: SceneZone): Int = when {
        scene.hasDrop(zone) -> 4
        scene.stateOf(zone) == SceneZoneState.BLOCKED -> 3
        scene.stateOf(zone) == SceneZoneState.CAUTION -> 2
        scene.stateOf(zone) == SceneZoneState.UNKNOWN -> 1
        else -> 0
    }

    private fun SceneAwarenessResult.withZone(
        zone: SceneZone,
        state: SceneZoneState,
        distance: Float?,
        drop: Boolean,
    ): SceneAwarenessResult = when (zone) {
        SceneZone.LEFT -> copy(leftState = state, leftDistanceMeters = distance, leftDrop = drop)
        SceneZone.CENTER -> copy(centerState = state, centerDistanceMeters = distance, centerDrop = drop)
        SceneZone.RIGHT -> copy(rightState = state, rightDistanceMeters = distance, rightDrop = drop)
    }

    private fun isValid(scene: SceneAwarenessResult) =
        scene.depthTimestampNanos != null && scene.supportPlaneDetected

    companion object {
        const val DEPTH_VISUAL_HOLD_MS = 1_500L
        const val DEPTH_ACTIVE_WINDOW_MS = 500L
        const val CLEAR_CONFIRMATION_SAMPLES = 2

        /** The display with nothing behind it: three unknown boxes and no ground plane. */
        fun unknownScene() = SceneAwarenessResult()
    }
}
