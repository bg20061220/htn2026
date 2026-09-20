package com.example.guidedogtest.ocr

import android.os.SystemClock
import java.util.Locale
import kotlin.math.abs

enum class ObstacleDirection(val speech: String) {
    LEFT("slightly left"),
    AHEAD("ahead"),
    RIGHT("slightly right")
}

data class ObstacleAlert(
    val label: String,
    val direction: ObstacleDirection,
    /**
     * How far away it is, or null when the depth frame had nothing to say about it.
     *
     * A detection with no depth behind it is still worth announcing - EfficientDet saw something and
     * the walker cannot - so the distance is left out of the sentence rather than the alert being
     * dropped for lack of a number.
     */
    val distanceMeters: Float?
) {
    val speech: String
        get() = buildString {
            append("${label.replaceFirstChar { it.titlecase(Locale.getDefault()) }} ${direction.speech}")
            distanceMeters?.let { append(String.format(Locale.getDefault(), ", %.1f metres", it)) }
            append('.')
        }
}

/** Selects at most one useful alert; it does not own audio or camera resources. */
class ObstacleAlertManager(
    private val clockMillis: () -> Long = SystemClock::elapsedRealtime,
    private val speak: (String) -> Boolean
) {
    private data class SpokenAlert(val timeMillis: Long, val distanceMeters: Float?)

    private val spokenByKey = mutableMapOf<String, SpokenAlert>()
    private var lastAnyAlertMillis = Long.MIN_VALUE

    fun consider(
        detections: List<VisionObjectDetection>,
        frameWidth: Int,
        warningDistanceMeters: Float
    ): ObstacleAlert? {
        if (frameWidth <= 0 || warningDistanceMeters <= 0f) return null
        val now = clockMillis()
        if (lastAnyAlertMillis != Long.MIN_VALUE && now - lastAnyAlertMillis < GLOBAL_COOLDOWN_MS) return null

        val candidates = detections.mapNotNull { detection ->
            val distance = detection.distanceMeters?.takeIf { it.isFinite() && it > 0f }
            // With a distance, the range gate is what decides. Without one, the detector's own
            // confidence has to carry the decision, and only a reasonably sure label is worth
            // interrupting someone to say.
            if (distance != null && distance > warningDistanceMeters) return@mapNotNull null
            if (distance == null && detection.confidence < MIN_DISTANCELESS_CONFIDENCE) return@mapNotNull null
            val direction = directionFor(detection.boundingBox.centerX(), frameWidth)
            val alert = ObstacleAlert(detection.label, direction, distance)
            val previous = spokenByKey[key(alert)]
            val repeatAllowed = previous == null ||
                now - previous.timeMillis >= SAME_OBJECT_COOLDOWN_MS ||
                (previous.distanceMeters != null && distance != null &&
                    now - previous.timeMillis >= SIGNIFICANT_CHANGE_MIN_DELAY_MS &&
                    abs(previous.distanceMeters - distance) >= SIGNIFICANT_DISTANCE_CHANGE_METERS)
            alert.takeIf { repeatAllowed }
        }

        // Forward-path objects get a modest priority bonus without hiding a much closer side hazard.
        // A detection with no distance ranks as if it were at the warning limit, so it is heard but
        // never outranks something measured closer.
        val selected = candidates.minByOrNull {
            (it.distanceMeters ?: warningDistanceMeters) +
                if (it.direction == ObstacleDirection.AHEAD) 0f else SIDE_PRIORITY_PENALTY_METERS
        } ?: return null

        if (!speak(selected.speech)) return null
        lastAnyAlertMillis = now
        spokenByKey[key(selected)] = SpokenAlert(now, selected.distanceMeters)
        removeOldEntries(now)
        return selected
    }

    fun reset() {
        spokenByKey.clear()
        lastAnyAlertMillis = Long.MIN_VALUE
    }

    private fun directionFor(centerX: Float, frameWidth: Int): ObstacleDirection = when {
        centerX < frameWidth / 3f -> ObstacleDirection.LEFT
        centerX < frameWidth * 2f / 3f -> ObstacleDirection.AHEAD
        else -> ObstacleDirection.RIGHT
    }

    private fun key(alert: ObstacleAlert) = "${alert.label.lowercase(Locale.ROOT)}:${alert.direction.name}"

    private fun removeOldEntries(now: Long) {
        spokenByKey.entries.removeAll { now - it.value.timeMillis > HISTORY_RETENTION_MS }
    }

    private companion object {
        const val GLOBAL_COOLDOWN_MS = 3_000L
        const val SAME_OBJECT_COOLDOWN_MS = 8_000L
        const val SIGNIFICANT_CHANGE_MIN_DELAY_MS = 3_000L
        const val SIGNIFICANT_DISTANCE_CHANGE_METERS = 0.5f
        const val SIDE_PRIORITY_PENALTY_METERS = 0.4f
        /** How sure the detector has to be before a distance-less label is spoken. */
        const val MIN_DISTANCELESS_CONFIDENCE = 0.65f
        const val HISTORY_RETENTION_MS = 60_000L
    }
}
