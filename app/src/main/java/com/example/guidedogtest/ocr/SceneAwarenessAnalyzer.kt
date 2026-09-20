package com.example.guidedogtest.ocr

import kotlin.math.abs
import kotlin.math.roundToInt

/** How a zone reads: clear to walk, something close, something in the way, or no usable reading. */
enum class SceneZoneState { CLEAR, CAUTION, BLOCKED, UNKNOWN }

enum class RecommendedDirection { FORWARD, LEFT, RIGHT, STOP, UNKNOWN }

/** The three boxes the robot looks through. Left and right are what a hallway centring is measured on. */
enum class SceneZone { LEFT, CENTER, RIGHT }

data class SceneAwarenessThresholds(val blockedBelowMeters: Float = 0.8f, val clearAboveMeters: Float = 1.5f) {
    init { require(blockedBelowMeters > 0f && clearAboveMeters > blockedBelowMeters) }
}

/**
 * One depth frame, reduced to what the three boxes saw.
 *
 * [leftDistanceMeters] and friends are the nearest thing *standing above the floor* in that box, or
 * null when the box held nothing but floor: a flat floor is not an obstacle and is never reported as
 * one. A wall inside the box does count, which is what makes a hallway measurable at all.
 *
 * [recommendedDirection] points at the side with more room, or STOP when a box found a drop (a hole
 * in the floor, or the edge of a step down).
 */
data class SceneAwarenessResult(
    val leftDistanceMeters: Float? = null,
    val centerDistanceMeters: Float? = null,
    val rightDistanceMeters: Float? = null,
    val leftState: SceneZoneState = SceneZoneState.UNKNOWN,
    val centerState: SceneZoneState = SceneZoneState.UNKNOWN,
    val rightState: SceneZoneState = SceneZoneState.UNKNOWN,
    val recommendedDirection: RecommendedDirection = RecommendedDirection.UNKNOWN,
    val depthTimestampNanos: Long? = null,
    val supportPlaneDetected: Boolean = false,
    /** Any box found a hole. A *centre* drop is the one that stops the car; a side one closes a side. */
    val dropDetected: Boolean = false,
    val leftDrop: Boolean = false,
    val centerDrop: Boolean = false,
    val rightDrop: Boolean = false,
    /**
     * Where the floor ends, as a fraction of the image height, or null when there is no ground plane.
     *
     * Rows below it are floor and support - the part of a box that is deliberately *not* detection -
     * and rows above it are what the robot has to look at. It is drawn, so nobody has to guess which
     * half of a box is doing the work.
     */
    val floorLineFraction: Float? = null,
    /**
     * True when the depth frame was empty: ARCore handed back a frame with no measurement in it at
     * all. That is a different fault from "the floor is not visible"), and it is worth telling apart
     * on the bench - one is the camera's aim, the other is the depth module.
     */
    val depthEmpty: Boolean = false,
) {
    fun stateOf(zone: SceneZone): SceneZoneState = when (zone) {
        SceneZone.LEFT -> leftState
        SceneZone.CENTER -> centerState
        SceneZone.RIGHT -> rightState
    }

    fun distanceOf(zone: SceneZone): Float? = when (zone) {
        SceneZone.LEFT -> leftDistanceMeters
        SceneZone.CENTER -> centerDistanceMeters
        SceneZone.RIGHT -> rightDistanceMeters
    }

    fun hasDrop(zone: SceneZone): Boolean = when (zone) {
        SceneZone.LEFT -> leftDrop
        SceneZone.CENTER -> centerDrop
        SceneZone.RIGHT -> rightDrop
    }

    /** True when no box has a usable reading: nothing in the frame can be judged. */
    val seesNothing: Boolean
        get() = leftState == SceneZoneState.UNKNOWN &&
            centerState == SceneZoneState.UNKNOWN &&
            rightState == SceneZoneState.UNKNOWN

    /**
     * How far off the middle of the two side boxes the robot is, -1 (hard left) to +1 (hard right),
     * or null when a side has no reading. Used for steer corrections and shown on the camera view.
     */
    val offMiddle: Float?
        get() {
            val left = leftDistanceMeters ?: return null
            val right = rightDistanceMeters ?: return null
            val span = left + right
            return if (span <= 0f) null else (right - left) / span
        }
}

/** A box is somewhere to go while it is not blocked and not unreadable. */
internal fun SceneZoneState.canTurnInto(): Boolean =
    this == SceneZoneState.CLEAR || this == SceneZoneState.CAUTION

/**
 * How much room a box has, as a number to compare two boxes with: its measured distance, infinite
 * when the box held nothing but floor - an open side has more room than any wall - and negative
 * infinity when the box cannot be used at all (blocked, or unreadable).
 */
internal fun zoneRoom(state: SceneZoneState, distance: Float?): Float = when {
    !state.canTurnInto() -> Float.NEGATIVE_INFINITY
    distance == null -> Float.POSITIVE_INFINITY
    else -> distance
}

/**
 * True when [room] has clearly more room than [other], by [SIDE_SWITCH_MARGIN_METERS].
 *
 * Two open sides count as a tie: infinity compared with a margin is still infinity, so the plain
 * comparison would call every empty scene a reason to steer left.
 */
internal fun clearlyMoreRoom(room: Float, other: Float): Boolean = when {
    room == Float.POSITIVE_INFINITY && other == Float.POSITIVE_INFINITY -> false
    else -> room >= other + SIDE_SWITCH_MARGIN_METERS
}

/**
 * Which way has room, for three zone readings: the side with more of it, or STOP when a box found a
 * hole. Shared by the analyzer and the stabilized display, so what the panel says and what the
 * controller was told cannot drift apart.
 */
internal fun recommendDirection(
    leftState: SceneZoneState,
    centerState: SceneZoneState,
    rightState: SceneZoneState,
    left: Float?,
    right: Float?,
    centerDrop: Boolean,
    leftDrop: Boolean = false,
    rightDrop: Boolean = false,
): RecommendedDirection {
    // A hole directly in front stops the car. A hole beside it only closes that side: stepping round
    // a hole is the same problem as stepping round a wall, and stopping for one the robot is not
    // about to walk into is how a clear corridor becomes a standstill.
    if (centerDrop) return RecommendedDirection.STOP
    val roomLeft = if (leftDrop) Float.NEGATIVE_INFINITY else zoneRoom(leftState, left)
    val roomRight = if (rightDrop) Float.NEGATIVE_INFINITY else zoneRoom(rightState, right)
    val noRoomLeft = roomLeft == Float.NEGATIVE_INFINITY
    val noRoomRight = roomRight == Float.NEGATIVE_INFINITY
    if (centerState == SceneZoneState.UNKNOWN) return RecommendedDirection.UNKNOWN
    if (centerState == SceneZoneState.BLOCKED) {
        return when {
            noRoomLeft && noRoomRight -> RecommendedDirection.STOP
            noRoomRight || (!noRoomLeft && clearlyMoreRoom(roomLeft, roomRight)) ->
                RecommendedDirection.LEFT
            else -> RecommendedDirection.RIGHT
        }
    }
    // Even with the middle open, a wall hard against one box is a reason to lean the other way - the
    // same direction the controller nudges in when it is hugging one.
    if (leftState == SceneZoneState.BLOCKED && rightState != SceneZoneState.BLOCKED) {
        return RecommendedDirection.RIGHT
    }
    if (rightState == SceneZoneState.BLOCKED && leftState != SceneZoneState.BLOCKED) {
        return RecommendedDirection.LEFT
    }
    return when {
        clearlyMoreRoom(roomLeft, roomRight) -> RecommendedDirection.LEFT
        clearlyMoreRoom(roomRight, roomLeft) -> RecommendedDirection.RIGHT
        else -> RecommendedDirection.FORWARD
    }
}

/** How much more room one side needs before it is worth steering towards it at all. */
internal const val SIDE_SWITCH_MARGIN_METERS = 0.25f

/**
 * Turns one ARCore depth frame into three boxes: left, centre, right.
 *
 * The floor is the whole reason this class exists. A depth frame is mostly floor, and the floor
 * gets *nearer* as it goes down the image, so a naive box reads "obstacle at arm's length" from a
 * flat carpet. So the floor is modelled first - the bottom rows of the boxes are fitted with a
 * ground plane (inverse depth against image row, which is what a plane looks like in a perspective
 * projection) - and every sample is then classified against it:
 *
 *  - within [GROUND_PLANE_TOLERANCE_METERS] of the plane: **floor** - support, never an obstacle;
 *  - nearer than the plane: **something standing on the floor**, or a wall: an obstacle;
 *  - farther than the plane by [DROP_DEPTH_DELTA_METERS]: a hole or a step down: a drop.
 *
 * Nothing else runs: no occupancy grid, no corridor search. Each box answers two questions - is
 * anything in me that is not floor, and how far away is the nearest of it - and the controller
 * decides from those three answers.
 */
class SceneAwarenessAnalyzer(private val thresholds: SceneAwarenessThresholds = SceneAwarenessThresholds()) {
    private var lastAnalyzedTimestampNanos = Long.MIN_VALUE

    /** Why the last floor model fitted or did not, for the bench log. */
    var lastFitReport: String = "no frame yet"
        private set

    /** How many samples the frame yielded, for the bench log when every box is grey. */
    var lastSampleReport: String = "no frame yet"
        private set

    /** What the depth frame itself held, for the bench log when every box is grey. */
    var lastDepthReport: String = "no frame yet"
        private set

    private var lastDepthWasEmpty = false

    fun analyzeIfDue(depth: DepthFrame): SceneAwarenessResult? {
        if (lastAnalyzedTimestampNanos != Long.MIN_VALUE &&
            depth.timestampNanos - lastAnalyzedTimestampNanos < ANALYSIS_INTERVAL_NANOS
        ) {
            return null
        }
        lastAnalyzedTimestampNanos = depth.timestampNanos
        // What the frame itself contains, before any of our arithmetic: if the depth module is handing
        // back an empty picture, nothing downstream can be fixed by tuning thresholds.
        val nonZero = depth.millimeters.count { it != 0 }
        // "Non-zero" is not the same as "usable": ARCore hands back a saturated far value for a surface
        // it could not triangulate, so a frame can be 100% non-zero and still have nothing the sampler
        // will accept. Counting what is actually inside the sampler's window is the number that answers
        // "why is there no floor" - 14,400 valid pixels and 0 in range is a view problem, not a bug.
        val inRange = depth.millimeters.count { it in MIN_DEPTH_MM..MAX_DEPTH_MM }
        lastDepthWasEmpty = inRange == 0
        val centrePoint = screenToTexture(0.5f, 0.7f, depth.displayTextureCorners)
        // The whole point of this line: where the floor actually is inside the depth image. A column
        // down the middle, top to bottom, in metres - the near floor shows up as a sharp step, and the
        // rows on either side of it are what the boxes have to sample.
        val columnProfile = (0 until 10).joinToString(",") { step ->
            val row = (step * (depth.height - 1) / 10f).toInt()
            val millimetres = depth.millimeters[row * depth.width + depth.width / 2]
            if (millimetres == 0) "--" else "%.1f".format(millimetres / 1000f)
        }
        lastDepthReport = "depth %dx%d nonzero %d/%d (in range %d), screen(0.5,0.7)->texture(%.2f,%.2f), corners [%s], column %s"
            .format(
                depth.width, depth.height, nonZero, depth.millimeters.size, inRange,
                centrePoint.first, centrePoint.second,
                depth.displayTextureCorners.joinToString(",") { "%.2f".format(it) },
                columnProfile,
            )
        return analyze(depth.timestampNanos) { x, y ->
            val texture = screenToTexture(x, y, depth.displayTextureCorners)
            sampleDepth(depth, texture.first, texture.second)?.div(1000f)
        }
    }

    internal fun analyzeSamplesForTest(
        timestampNanos: Long = 1L,
        sample: (Float, Float) -> Float?,
    ): SceneAwarenessResult = analyze(timestampNanos, sample)

    private fun analyze(timestamp: Long, sample: (Float, Float) -> Float?): SceneAwarenessResult {
        val depths = Array(ZONE_COUNT) { FloatArray(SAMPLES_PER_ZONE) { Float.NaN } }
        var attempted = 0
        var returned = 0
        for (zone in SceneZone.entries) {
            val zoneDepths = depths[zone.ordinal]
            for (row in 0 until SAMPLE_ROWS) {
                for (column in 0 until SAMPLE_COLUMNS) {
                    val x = zoneLeft(zone) +
                        (column + 0.5f) / SAMPLE_COLUMNS * (zoneRight(zone) - zoneLeft(zone))
                    val y = ZONE_TOP + SAMPLE_ROW_FRACTIONS[row] * (ZONE_BOTTOM - ZONE_TOP)
                    if (isSelfMasked(x, y)) continue
                    attempted++
                    val raw = sample(x, y) ?: continue
                    returned++
                    val depth = raw.takeIf { it in MIN_DEPTH_METERS..MAX_DEPTH_METERS } ?: continue
                    zoneDepths[row * SAMPLE_COLUMNS + column] = depth
                }
            }
        }
        lastSampleReport = "sampled %d, depth returned %d".format(attempted, returned)

        // The ground plane, from the lowest rows that actually returned depth.
        //
        // "Lowest that returned depth" rather than "the bottom three": ARCore reports nothing for the
        // parts of the frame its depth module does not cover, and a plane fitted from three empty rows
        // is no plane at all - which is every box grey, forever.
        val supportRows = ArrayList<Pair<Float, Float>>(SUPPORT_ROWS)
        val supportRowIndices = ArrayList<Int>(SUPPORT_ROWS)
        for (row in SAMPLE_ROWS - 1 downTo 0) {
            if (supportRows.size >= SUPPORT_ROWS) break
            val values = SceneZone.entries
                .flatMap { zoneDepthsOf(depths, it, row) }
                .sorted()
            if (values.size < MIN_SUPPORT_ROW_SAMPLES) continue
            val y = ZONE_TOP + SAMPLE_ROW_FRACTIONS[row] * (ZONE_BOTTOM - ZONE_TOP)
            val depth = values[((values.size - 1) * SUPPORT_DEPTH_PERCENTILE).roundToInt()]
            supportRows += y to (1f / depth)
            supportRowIndices += row
        }
        val fitSlope = if (supportRows.size < 2) {
            0f
        } else {
            val meanY = supportRows.map { it.first }.average().toFloat()
            val meanInv = supportRows.map { it.second }.average().toFloat()
            val denominator = supportRows.sumOf { ((it.first - meanY) * (it.first - meanY)).toDouble() }.toFloat()
            if (denominator <= 0f) 0f else supportRows.sumOf {
                ((it.first - meanY) * (it.second - meanInv)).toDouble()
            }.toFloat() / denominator
        }
        val plane = fitSupportPlane(supportRows)
        lastFitReport = "support rows %d of %d (need %d) from rows %s, depths %s, fit %s".format(
            supportRows.size, SUPPORT_ROWS, MIN_SUPPORT_MODEL_ROWS,
            supportRowIndices.joinToString(",") { it.toString() }.ifEmpty { "none" },
            supportRows.joinToString(",") { "%.1f".format(1f / it.second) },
            if (plane == null) {
                "FAILED (slope ${"%.2f".format(fitSlope)})"
            } else {
                "ok (slope ${"%.2f".format(plane.first)})"
            },
        )
        if (plane == null) {
            // No floor model, so nothing can be *judged* - the zones keep their UNKNOWN state and the
            // controller will refuse to drive, which is the safe answer. But the distances are still
            // reported: that is the difference between "the camera sees nothing" and "the camera sees
            // perfectly well and the floor is what is missing", and it is the first thing worth
            // knowing when the boxes sit grey on the screen.
            return SceneAwarenessResult(
                leftDistanceMeters = rawNearest(depths, SceneZone.LEFT),
                centerDistanceMeters = rawNearest(depths, SceneZone.CENTER),
                rightDistanceMeters = rawNearest(depths, SceneZone.RIGHT),
                depthTimestampNanos = timestamp,
                supportPlaneDetected = false,
                depthEmpty = lastDepthWasEmpty,
            )
        }

        val distances = arrayOfNulls<Float>(ZONE_COUNT)
        val states = Array(ZONE_COUNT) { SceneZoneState.UNKNOWN }
        val drops = BooleanArray(ZONE_COUNT)
        val rowSamples = IntArray(SAMPLE_ROWS)
        val rowFloor = IntArray(SAMPLE_ROWS)
        for (zone in SceneZone.entries) {
            val zoneDepths = depths[zone.ordinal]
            val obstacles = ArrayList<Float>(SAMPLES_PER_ZONE)
            var valid = 0
            var dropRowsValid = 0
            var dropped = 0
            for (row in 0 until SAMPLE_ROWS) {
                val y = ZONE_TOP + SAMPLE_ROW_FRACTIONS[row] * (ZONE_BOTTOM - ZONE_TOP)
                val expected = 1f / (plane.first * y + plane.second)
                val dropRow = row >= SAMPLE_ROWS - DROP_CHECK_ROWS
                for (column in 0 until SAMPLE_COLUMNS) {
                    val depth = zoneDepths[row * SAMPLE_COLUMNS + column]
                    if (depth.isNaN()) continue
                    valid++
                    rowSamples[row]++
                    if (dropRow) dropRowsValid++
                    when {
                        // A hole is a hole in the floor, and the floor in front of the robot is the
                        // bottom of the box: counting the whole box would dilute a step edge - which
                        // only ever fills the near rows - until it never crossed the fraction.
                        depth > expected + DROP_DEPTH_DELTA_METERS -> if (dropRow) dropped++
                        depth < expected - GROUND_PLANE_TOLERANCE_METERS -> obstacles += depth
                        else -> rowFloor[row]++
                    }
                }
            }
            drops[zone.ordinal] = dropRowsValid >= MIN_VALID_ZONE_SAMPLES &&
                dropped.toFloat() / dropRowsValid >= DROP_SAMPLE_FRACTION
            if (valid < MIN_VALID_ZONE_SAMPLES) continue
            val nearest = obstacles.sorted()
                .takeIf { it.isNotEmpty() }
                ?.let { it[((it.size - 1) * ZONE_DISTANCE_PERCENTILE).roundToInt()] }
            distances[zone.ordinal] = nearest
            states[zone.ordinal] = when {
                nearest == null -> SceneZoneState.CLEAR          // floor and far readings only
                nearest <= thresholds.blockedBelowMeters -> SceneZoneState.BLOCKED
                nearest <= thresholds.clearAboveMeters -> SceneZoneState.CAUTION
                else -> SceneZoneState.CLEAR
            }
        }

        // The floor line: the top of the highest row that is still mostly floor, counted from the
        // bottom up. A row that is mostly something else ends the floor - that is where the wall,
        // the furniture or the step begins.
        var floorLine: Float? = null
        for (row in SAMPLE_ROWS - 1 downTo 0) {
            if (rowSamples[row] == 0 || rowFloor[row] * 2 < rowSamples[row]) break
            floorLine = ZONE_TOP + SAMPLE_ROW_FRACTIONS[row] * (ZONE_BOTTOM - ZONE_TOP)
        }

        val drop = drops.any { it }
        return SceneAwarenessResult(
            leftDistanceMeters = distances[SceneZone.LEFT.ordinal],
            centerDistanceMeters = distances[SceneZone.CENTER.ordinal],
            rightDistanceMeters = distances[SceneZone.RIGHT.ordinal],
            leftState = states[SceneZone.LEFT.ordinal],
            centerState = states[SceneZone.CENTER.ordinal],
            rightState = states[SceneZone.RIGHT.ordinal],
            recommendedDirection = recommendDirection(
                leftState = states[SceneZone.LEFT.ordinal],
                centerState = states[SceneZone.CENTER.ordinal],
                rightState = states[SceneZone.RIGHT.ordinal],
                left = distances[SceneZone.LEFT.ordinal],
                right = distances[SceneZone.RIGHT.ordinal],
                centerDrop = drops[SceneZone.CENTER.ordinal],
                leftDrop = drops[SceneZone.LEFT.ordinal],
                rightDrop = drops[SceneZone.RIGHT.ordinal],
            ),
            depthTimestampNanos = timestamp,
            supportPlaneDetected = true,
            dropDetected = drop,
            leftDrop = drops[SceneZone.LEFT.ordinal],
            centerDrop = drops[SceneZone.CENTER.ordinal],
            rightDrop = drops[SceneZone.RIGHT.ordinal],
            floorLineFraction = floorLine,
        )
    }

    /**
     * The nearest thing a box saw at all, with no floor model to judge it against: the low percentile
     * of every reading in the box, valid readings only.
     */
    private fun rawNearest(depths: Array<FloatArray>, zone: SceneZone): Float? {
        val values = depths[zone.ordinal].filter { !it.isNaN() }.sorted()
        if (values.size < MIN_VALID_ZONE_SAMPLES) return null
        val index = ((values.size - 1) * ZONE_DISTANCE_PERCENTILE).roundToInt()
        return values[index.coerceIn(0, values.size - 1)]
    }

    private fun zoneDepthsOf(depths: Array<FloatArray>, zone: SceneZone, row: Int): List<Float> {
        val zoneDepths = depths[zone.ordinal]
        val values = ArrayList<Float>(SAMPLE_COLUMNS)
        for (column in 0 until SAMPLE_COLUMNS) {
            val depth = zoneDepths[row * SAMPLE_COLUMNS + column]
            if (!depth.isNaN()) values += depth
        }
        return values
    }

    private fun fitSupportPlane(rows: List<Pair<Float, Float>>): Pair<Float, Float>? {
        if (rows.size < MIN_SUPPORT_MODEL_ROWS) return null
        val meanY = rows.map { it.first }.average().toFloat()
        val meanInv = rows.map { it.second }.average().toFloat()
        val denominator = rows.sumOf { ((it.first - meanY) * (it.first - meanY)).toDouble() }.toFloat()
        if (denominator <= 0f) return null
        val slope = rows.sumOf { ((it.first - meanY) * (it.second - meanInv)).toDouble() }.toFloat() / denominator
        if (slope < MIN_SUPPORT_INVERSE_DEPTH_SLOPE) return null
        return slope to (meanInv - slope * meanY)
    }

    private fun isSelfMasked(x: Float, y: Float) = y >= SELF_MASK_TOP && x in SELF_MASK_LEFT..SELF_MASK_RIGHT

    private fun screenToTexture(x: Float, y: Float, c: FloatArray) =
        (c[0] + x * (c[2] - c[0]) + y * (c[4] - c[0])) to (c[1] + x * (c[3] - c[1]) + y * (c[5] - c[1]))

    private fun sampleDepth(depth: DepthFrame, u: Float, v: Float): Int? {
        if (!u.isFinite() || !v.isFinite() || u !in 0f..1f || v !in 0f..1f) return null
        val x = (u * (depth.width - 1)).roundToInt()
        val y = (v * (depth.height - 1)).roundToInt()
        return depth.millimeters[y * depth.width + x].takeIf { it in MIN_DEPTH_MM..MAX_DEPTH_MM }
    }

    companion object {
        /** Left, centre, right: three boxes, each a third of the region the camera is trusted over. */
        const val REGION_LEFT = 0.05f
        const val REGION_RIGHT = 0.95f
        const val ZONE_TOP = 0.30f

        /**
         * The bottom of the box, at the bottom of the preview.
         *
         * Not 0.92: the preview is much wider than the camera image, so its vertical extent only
         * covers the middle of the depth texture (measured on the S21: display y 0..1 maps to texture
         * v 0.13..0.87). The floor in front of the robot is *below* that, so a box that stopped at 0.92
         * sampled nothing but the far end of the corridor - 14,400 valid depth pixels a frame and every
         * single sample rejected as out of range.
         */
        const val ZONE_BOTTOM = 1.0f

        val ZONES: List<SceneZone> = SceneZone.entries.toList()

        /** The left edge of a box, as a fraction of the image width. */
        fun zoneLeft(zone: SceneZone): Float = REGION_LEFT +
            zone.ordinal * (REGION_RIGHT - REGION_LEFT) / SceneZone.entries.size

        /** The right edge of a box, as a fraction of the image width. */
        fun zoneRight(zone: SceneZone): Float = zoneLeft(zone) +
            (REGION_RIGHT - REGION_LEFT) / SceneZone.entries.size

        /** The sampler's window, in millimetres: nearer is noise on the lens, further is not a wall. */
        const val MIN_DEPTH_MM = 150
        const val MAX_DEPTH_MM = 8_000

        const val GROUND_PLANE_TOLERANCE_METERS = 0.14f
        const val SELF_MASK_LEFT = 0.43f
        const val SELF_MASK_RIGHT = 0.57f
        const val SELF_MASK_TOP = 0.88f

        private val ZONE_COUNT = SceneZone.entries.size
        /**
         * How finely each box is sampled. ARCore's depth is sparse - it returns "no measurement" for
         * whole patches of a flat surface - so the floor model needs more points to fit against than
         * the obstacle test does. 49 points a box costs nothing measurable (the whole analysis runs
         * in ~0.06 ms) and it is what decides whether the robot can see the floor at all.
         */
        private const val SAMPLE_COLUMNS = 7

        /**
         * Where the sample rows sit in the box, top to bottom.
         *
         * Bunched towards the bottom on purpose: that is where the floor, the thing the robot is about
         * to walk on, and every step-down live. Even rows would spend most of their samples on the far
         * half of the corridor.
         */
        private val SAMPLE_ROW_FRACTIONS =
            floatArrayOf(0.05f, 0.18f, 0.32f, 0.46f, 0.60f, 0.72f, 0.82f, 0.90f, 0.96f, 1.0f)
        private const val SAMPLE_ROWS = 10
        private const val SAMPLES_PER_ZONE = SAMPLE_COLUMNS * SAMPLE_ROWS
        private const val SUPPORT_ROWS = 3

        /**
         * How many readings a support row needs before it may vote on the floor model: 4 of the 21 a
         * row holds. Deliberately low - a row that is half furniture, or half "no measurement" from
         * ARCore, still has floor in it, and a model that refuses to fit is a robot that refuses to
         * move at all.
         */
        private const val MIN_SUPPORT_ROW_SAMPLES = 4
        private const val MIN_SUPPORT_MODEL_ROWS = 2
        /**
         * Which value of a support row stands for the floor: the median.
         *
         * The row's samples are floor, walls and whatever is standing up in the boxes, and the floor
         * is the surface the robot needs the model of. A median tolerates a third of the row being
         * something else - a wall box, a hole - where a high percentile would fit the model to the
         * hole and then call the whole floor an obstacle.
         */
        private const val SUPPORT_DEPTH_PERCENTILE = 0.50f
        private const val MIN_SUPPORT_INVERSE_DEPTH_SLOPE = 0.08f
        private const val MIN_DEPTH_METERS = 0.15f
        /**
         * The furthest reading kept. 8 m threw away the far half of a corridor as "no measurement",
         * which then read as "nothing there" - a wall at 10 m is a perfectly good reading.
         */
        private const val MAX_DEPTH_METERS = 12f
        private const val DROP_DEPTH_DELTA_METERS = 0.45f
        private const val DROP_SAMPLE_FRACTION = 0.55f

        /** The rows a hole can appear in: the floor immediately in front of the robot. */
        private const val DROP_CHECK_ROWS = 2
        private const val MIN_VALID_ZONE_SAMPLES = 6
        private const val ZONE_DISTANCE_PERCENTILE = 0.25f
        /** Below the depth frame interval, so the boxes are read from every frame offered. */
        private const val ANALYSIS_INTERVAL_NANOS = 80_000_000L
    }
}
