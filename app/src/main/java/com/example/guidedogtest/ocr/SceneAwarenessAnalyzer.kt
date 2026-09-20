package com.example.guidedogtest.ocr

import kotlin.math.roundToInt

enum class SceneZoneState { CLEAR, CAUTION, BLOCKED, UNKNOWN }

enum class RecommendedDirection { FORWARD, LEFT, RIGHT, STOP, UNKNOWN }

data class SceneAwarenessThresholds(
    val blockedBelowMeters: Float = 0.8f,
    val clearAboveMeters: Float = 1.5f
) {
    init {
        require(blockedBelowMeters > 0f && clearAboveMeters > blockedBelowMeters)
    }
}

data class SceneAwarenessResult(
    val leftDistanceMeters: Float? = null,
    val centerDistanceMeters: Float? = null,
    val rightDistanceMeters: Float? = null,
    val leftState: SceneZoneState = SceneZoneState.UNKNOWN,
    val centerState: SceneZoneState = SceneZoneState.UNKNOWN,
    val rightState: SceneZoneState = SceneZoneState.UNKNOWN,
    val recommendedDirection: RecommendedDirection = RecommendedDirection.UNKNOWN,
    val depthTimestampNanos: Long? = null
)

/** Performs depth-only free-space analysis. It does not use detector labels or boxes. */
class SceneAwarenessAnalyzer(
    private val thresholds: SceneAwarenessThresholds = SceneAwarenessThresholds()
) {
    private var lastAnalyzedTimestampNanos = Long.MIN_VALUE

    fun analyzeIfDue(depth: DepthFrame): SceneAwarenessResult? {
        if (lastAnalyzedTimestampNanos != Long.MIN_VALUE &&
            depth.timestampNanos - lastAnalyzedTimestampNanos < ANALYSIS_INTERVAL_NANOS
        ) return null
        lastAnalyzedTimestampNanos = depth.timestampNanos

        val samples = Array(3) { ArrayList<Int>(SAMPLE_COLUMNS_PER_ZONE * SAMPLE_ROWS) }
        for (row in 0 until SAMPLE_ROWS) {
            val screenY = REGION_TOP + (row + 0.5f) / SAMPLE_ROWS * (REGION_BOTTOM - REGION_TOP)
            for (column in 0 until SAMPLE_COLUMNS) {
                val screenX = REGION_LEFT + (column + 0.5f) / SAMPLE_COLUMNS * (REGION_RIGHT - REGION_LEFT)
                val texture = screenToTexture(screenX, screenY, depth.displayTextureCorners)
                val millimeters = sampleDepth(depth, texture.first, texture.second) ?: continue
                samples[(column * 3 / SAMPLE_COLUMNS).coerceIn(0, 2)].add(millimeters)
            }
        }

        val distances = samples.map(::representativeDistanceMeters)
        val states = distances.map(::stateFor)
        return SceneAwarenessResult(
            leftDistanceMeters = distances[0],
            centerDistanceMeters = distances[1],
            rightDistanceMeters = distances[2],
            leftState = states[0],
            centerState = states[1],
            rightState = states[2],
            recommendedDirection = recommend(distances, states),
            depthTimestampNanos = depth.timestampNanos
        )
    }

    private fun representativeDistanceMeters(values: ArrayList<Int>): Float? {
        if (values.size < MIN_VALID_SAMPLES) return null
        values.sort()
        // Trim the most extreme 5% on both ends, then use the 25th percentile.
        // This remains sensitive to a real nearby obstacle without trusting one noisy pixel.
        val trim = (values.size * OUTLIER_TRIM_FRACTION).toInt()
        val from = trim.coerceAtMost(values.lastIndex)
        val toExclusive = (values.size - trim).coerceAtLeast(from + 1)
        val index = from + ((toExclusive - from - 1) * LOW_PERCENTILE).roundToInt()
        return values[index] / 1000f
    }

    private fun stateFor(distance: Float?): SceneZoneState = when {
        distance == null -> SceneZoneState.UNKNOWN
        distance < thresholds.blockedBelowMeters -> SceneZoneState.BLOCKED
        distance <= thresholds.clearAboveMeters -> SceneZoneState.CAUTION
        else -> SceneZoneState.CLEAR
    }

    private fun recommend(
        distances: List<Float?>,
        states: List<SceneZoneState>
    ): RecommendedDirection {
        if (states[1] == SceneZoneState.CLEAR) return RecommendedDirection.FORWARD
        if (states[1] == SceneZoneState.UNKNOWN) return RecommendedDirection.UNKNOWN
        if (states[0] == SceneZoneState.UNKNOWN || states[2] == SceneZoneState.UNKNOWN) {
            return RecommendedDirection.UNKNOWN
        }
        if (states[0] == SceneZoneState.BLOCKED && states[2] == SceneZoneState.BLOCKED) {
            return RecommendedDirection.STOP
        }
        val left = distances[0] ?: return RecommendedDirection.UNKNOWN
        val right = distances[2] ?: return RecommendedDirection.UNKNOWN
        return if (left >= right) RecommendedDirection.LEFT else RecommendedDirection.RIGHT
    }

    private fun screenToTexture(x: Float, y: Float, corners: FloatArray): Pair<Float, Float> {
        val u = corners[0] + x * (corners[2] - corners[0]) + y * (corners[4] - corners[0])
        val v = corners[1] + x * (corners[3] - corners[1]) + y * (corners[5] - corners[1])
        return u to v
    }

    private fun sampleDepth(depth: DepthFrame, u: Float, v: Float): Int? {
        if (!u.isFinite() || !v.isFinite() || u !in 0f..1f || v !in 0f..1f) return null
        val x = (u * (depth.width - 1)).roundToInt()
        val y = (v * (depth.height - 1)).roundToInt()
        return depth.millimeters[y * depth.width + x]
            .takeIf { it in MIN_DEPTH_MILLIMETERS..MAX_DEPTH_MILLIMETERS }
    }

    companion object {
        const val REGION_LEFT = 0.08f
        const val REGION_RIGHT = 0.92f
        const val REGION_TOP = 0.45f
        const val REGION_BOTTOM = 0.80f

        private const val SAMPLE_COLUMNS = 36
        private const val SAMPLE_COLUMNS_PER_ZONE = SAMPLE_COLUMNS / 3
        private const val SAMPLE_ROWS = 15
        private const val MIN_VALID_SAMPLES = 24
        private const val MIN_DEPTH_MILLIMETERS = 150
        private const val MAX_DEPTH_MILLIMETERS = 8_000
        private const val OUTLIER_TRIM_FRACTION = 0.05f
        private const val LOW_PERCENTILE = 0.25f
        private const val ANALYSIS_INTERVAL_NANOS = 400_000_000L
    }
}
