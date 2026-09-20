package com.example.guidedogtest.ocr

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.tan

enum class SceneZoneState { CLEAR, CAUTION, BLOCKED, UNKNOWN }
enum class RecommendedDirection { FORWARD, LEFT, RIGHT, STOP, UNKNOWN }
enum class OccupancyState { FREE, CAUTION, OCCUPIED, UNKNOWN, DROP }

data class OccupancyCell(val column: Int, val row: Int, val state: OccupancyState, val distanceMeters: Float? = null)
data class FreeCorridor(val startColumn: Int, val endColumn: Int, val centerOffset: Float, val clearanceMeters: Float) {
    val widthColumns: Int get() = endColumn - startColumn + 1
}

data class SceneAwarenessThresholds(val blockedBelowMeters: Float = 0.8f, val clearAboveMeters: Float = 1.5f) {
    init { require(blockedBelowMeters > 0f && clearAboveMeters > blockedBelowMeters) }
}

data class SceneAwarenessResult(
    val leftDistanceMeters: Float? = null,
    val centerDistanceMeters: Float? = null,
    val rightDistanceMeters: Float? = null,
    val leftState: SceneZoneState = SceneZoneState.UNKNOWN,
    val centerState: SceneZoneState = SceneZoneState.UNKNOWN,
    val rightState: SceneZoneState = SceneZoneState.UNKNOWN,
    val recommendedDirection: RecommendedDirection = RecommendedDirection.UNKNOWN,
    val depthTimestampNanos: Long? = null,
    val gridColumns: Int = GRID_COLUMNS,
    val gridRows: Int = GRID_ROWS,
    val cells: List<OccupancyCell> = emptyList(),
    val freeCorridor: FreeCorridor? = null,
    val freeCorridors: List<FreeCorridor> = emptyList(),
    val supportPlaneDetected: Boolean = false,
    val dropDetected: Boolean = false,
) {
    companion object { const val GRID_COLUMNS = 9; const val GRID_ROWS = 4 }
}

/** Depth geometry only: semantic detector labels never participate in drive safety. */
class SceneAwarenessAnalyzer(private val thresholds: SceneAwarenessThresholds = SceneAwarenessThresholds()) {
    private var lastAnalyzedTimestampNanos = Long.MIN_VALUE

    fun analyzeIfDue(depth: DepthFrame): SceneAwarenessResult? {
        if (lastAnalyzedTimestampNanos != Long.MIN_VALUE && depth.timestampNanos - lastAnalyzedTimestampNanos < ANALYSIS_INTERVAL_NANOS) return null
        lastAnalyzedTimestampNanos = depth.timestampNanos
        return analyze(depth.timestampNanos) { x, y ->
            val texture = screenToTexture(x, y, depth.displayTextureCorners)
            sampleDepth(depth, texture.first, texture.second)?.div(1000f)
        }
    }

    internal fun analyzeSamplesForTest(timestampNanos: Long = 1L, sample: (Float, Float) -> Float?): SceneAwarenessResult =
        analyze(timestampNanos, sample)

    private fun analyze(timestamp: Long, sample: (Float, Float) -> Float?): SceneAwarenessResult {
        val samples = Array(GRID_ROWS * GRID_COLUMNS) { ArrayList<Float>(SAMPLES_PER_CELL * SAMPLES_PER_CELL) }
        for (row in 0 until GRID_ROWS) for (column in 0 until GRID_COLUMNS) {
            val values = samples[row * GRID_COLUMNS + column]
            for (sy in 0 until SAMPLES_PER_CELL) for (sx in 0 until SAMPLES_PER_CELL) {
                val x = REGION_LEFT + (column + (sx + .5f) / SAMPLES_PER_CELL) / GRID_COLUMNS * (REGION_RIGHT - REGION_LEFT)
                val y = REGION_TOP + (row + (sy + .5f) / SAMPLES_PER_CELL) / GRID_ROWS * (REGION_BOTTOM - REGION_TOP)
                if (!isSelfMasked(x, y)) sample(x, y)?.takeIf { it in MIN_DEPTH_METERS..MAX_DEPTH_METERS }?.let(values::add)
            }
        }

        val supportRows = ArrayList<Pair<Float, Float>>()
        for (row in SUPPORT_START_ROW until GRID_ROWS) {
            val values = (0 until GRID_COLUMNS).flatMap { samples[row * GRID_COLUMNS + it] }.sorted()
            if (values.size >= MIN_SUPPORT_ROW_SAMPLES) {
                val depth = values[((values.size - 1) * SUPPORT_DEPTH_PERCENTILE).roundToInt()]
                val y = REGION_TOP + (row + .5f) / GRID_ROWS * (REGION_BOTTOM - REGION_TOP)
                supportRows += y to (1f / depth)
            }
        }
        val plane = fitSupportPlane(supportRows) ?: return SceneAwarenessResult(depthTimestampNanos = timestamp)

        val cells = ArrayList<OccupancyCell>(GRID_ROWS * GRID_COLUMNS)
        for (row in 0 until GRID_ROWS) for (column in 0 until GRID_COLUMNS) {
            val yTop = REGION_TOP + row.toFloat() / GRID_ROWS * (REGION_BOTTOM - REGION_TOP)
            val yBottom = REGION_TOP + (row + 1f) / GRID_ROWS * (REGION_BOTTOM - REGION_TOP)
            val expectedFar = 1f / (plane.first * yTop + plane.second)
            val expectedNear = 1f / (plane.first * yBottom + plane.second)
            val values = samples[row * GRID_COLUMNS + column]
            val obstacles = values.count { it < expectedNear - MIN_OBSTACLE_HEIGHT_METERS }
            val support = values.count { it in (expectedNear - GROUND_PLANE_TOLERANCE_METERS)..(expectedFar + GROUND_PLANE_TOLERANCE_METERS) }
            val farther = values.count { it > expectedFar + DROP_DEPTH_DELTA_METERS }
            val slots = SAMPLES_PER_CELL * SAMPLES_PER_CELL
            val obstacleFraction = obstacles.toFloat() / slots
            val supportFraction = support.toFloat() / slots
            val dropFraction = (farther + slots - values.size).toFloat() / slots
            val state = when {
                row >= GRID_ROWS - DROP_CHECK_ROWS && dropFraction >= DROP_CELL_FRACTION && supportFraction < MIN_SUPPORT_FRACTION -> OccupancyState.DROP
                obstacleFraction >= OCCUPIED_FRACTION -> OccupancyState.OCCUPIED
                obstacleFraction >= CAUTION_FRACTION -> OccupancyState.CAUTION
                supportFraction >= MIN_SUPPORT_FRACTION -> OccupancyState.FREE
                values.size < MIN_VALID_CELL_SAMPLES -> OccupancyState.UNKNOWN
                else -> OccupancyState.FREE
            }
            cells += OccupancyCell(column, row, state, robustLowDistance(values))
        }
        val drop = cells.any { it.state == OccupancyState.DROP }
        val corridors = if (drop) emptyList() else FreeSpaceCorridorSelector.selectAll(cells, GRID_COLUMNS, GRID_ROWS)
        val corridor = corridors.minByOrNull { abs(it.centerOffset) }
        val columnStates = (0 until GRID_COLUMNS).map { columnState(cells, it) }
        val thirds = listOf(0 until 3, 3 until 6, 6 until 9)
        val zoneStates = thirds.map { range -> zoneState(range.map { columnStates[it] }) }
        val zoneDistances = thirds.map { range -> cells.filter { it.column in range }.mapNotNull { it.distanceMeters }.minOrNull() }
        val direction = when {
            drop || corridor == null -> RecommendedDirection.STOP
            corridor.centerOffset < -CORRIDOR_CENTER_DEADBAND -> RecommendedDirection.LEFT
            corridor.centerOffset > CORRIDOR_CENTER_DEADBAND -> RecommendedDirection.RIGHT
            else -> RecommendedDirection.FORWARD
        }
        return SceneAwarenessResult(
            zoneDistances[0], zoneDistances[1], zoneDistances[2], zoneStates[0], zoneStates[1], zoneStates[2],
            direction, timestamp, GRID_COLUMNS, GRID_ROWS, cells, corridor, corridors,
            supportPlaneDetected = true, dropDetected = drop,
        )
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

    private fun columnState(cells: List<OccupancyCell>, column: Int): OccupancyState {
        val states = cells.filter { it.column == column }.map { it.state }
        return when {
            OccupancyState.DROP in states -> OccupancyState.DROP
            OccupancyState.OCCUPIED in states -> OccupancyState.OCCUPIED
            OccupancyState.UNKNOWN in states -> OccupancyState.UNKNOWN
            OccupancyState.CAUTION in states -> OccupancyState.CAUTION
            else -> OccupancyState.FREE
        }
    }

    private fun zoneState(states: List<OccupancyState>): SceneZoneState = when {
        states.any { it == OccupancyState.DROP || it == OccupancyState.OCCUPIED } -> SceneZoneState.BLOCKED
        states.any { it == OccupancyState.UNKNOWN } -> SceneZoneState.UNKNOWN
        states.any { it == OccupancyState.CAUTION } -> SceneZoneState.CAUTION
        else -> SceneZoneState.CLEAR
    }

    private fun robustLowDistance(values: List<Float>): Float? = values.sorted().takeIf { it.size >= MIN_VALID_CELL_SAMPLES }
        ?.let { it[((it.size - 1) * LOW_PERCENTILE).roundToInt()] }
    private fun isSelfMasked(x: Float, y: Float) = y >= SELF_MASK_TOP && x in SELF_MASK_LEFT..SELF_MASK_RIGHT
    private fun screenToTexture(x: Float, y: Float, c: FloatArray) =
        (c[0] + x * (c[2] - c[0]) + y * (c[4] - c[0])) to (c[1] + x * (c[3] - c[1]) + y * (c[5] - c[1]))
    private fun sampleDepth(depth: DepthFrame, u: Float, v: Float): Int? {
        if (!u.isFinite() || !v.isFinite() || u !in 0f..1f || v !in 0f..1f) return null
        val x = (u * (depth.width - 1)).roundToInt(); val y = (v * (depth.height - 1)).roundToInt()
        return depth.millimeters[y * depth.width + x].takeIf { it in 150..8_000 }
    }

    companion object {
        const val GRID_COLUMNS = 9; const val GRID_ROWS = 4
        const val REGION_LEFT = 0.05f; const val REGION_RIGHT = 0.95f
        const val REGION_TOP = 0.30f; const val REGION_BOTTOM = 0.92f
        const val GROUND_PLANE_TOLERANCE_METERS = 0.14f
        const val MIN_OBSTACLE_HEIGHT_METERS = 0.20f
        const val ROBOT_WIDTH_METERS = 0.34f; const val SAFETY_MARGIN_METERS = 0.16f
        const val SELF_MASK_LEFT = 0.43f; const val SELF_MASK_RIGHT = 0.57f; const val SELF_MASK_TOP = 0.88f
        private const val SAMPLES_PER_CELL = 5; private const val SUPPORT_START_ROW = 1
        private const val MIN_SUPPORT_ROW_SAMPLES = 18; private const val MIN_SUPPORT_MODEL_ROWS = 3
        private const val SUPPORT_DEPTH_PERCENTILE = 0.70f; private const val MIN_SUPPORT_INVERSE_DEPTH_SLOPE = 0.08f
        private const val MIN_DEPTH_METERS = 0.15f; private const val MAX_DEPTH_METERS = 8f
        private const val DROP_DEPTH_DELTA_METERS = 0.45f; private const val DROP_CELL_FRACTION = 0.55f
        private const val DROP_CHECK_ROWS = 1; private const val MIN_SUPPORT_FRACTION = 0.30f
        private const val OCCUPIED_FRACTION = 0.20f; private const val CAUTION_FRACTION = 0.08f
        private const val MIN_VALID_CELL_SAMPLES = 7; private const val LOW_PERCENTILE = 0.25f
        private const val CORRIDOR_CENTER_DEADBAND = 0.12f; private const val ANALYSIS_INTERVAL_NANOS = 250_000_000L
    }
}

object FreeSpaceCorridorSelector {
    fun select(cells: List<OccupancyCell>, columns: Int, rows: Int): FreeCorridor? =
        selectAll(cells, columns, rows).minByOrNull { abs(it.centerOffset) }

    fun selectAll(cells: List<OccupancyCell>, columns: Int, rows: Int): List<FreeCorridor> {
        if (cells.any { it.state == OccupancyState.DROP }) return emptyList()
        val states = (0 until columns).map { column ->
            val columnCells = cells.filter { it.column == column && it.row in 1 until rows }
            when {
                columnCells.isEmpty() || columnCells.any { it.state == OccupancyState.UNKNOWN } -> OccupancyState.UNKNOWN
                columnCells.any { it.state == OccupancyState.OCCUPIED || it.state == OccupancyState.DROP } -> OccupancyState.OCCUPIED
                columnCells.any { it.state == OccupancyState.CAUTION } -> OccupancyState.CAUTION
                else -> OccupancyState.FREE
            }
        }
        val clearance = cells.mapNotNull { it.distanceMeters }.filter { it.isFinite() }.minOrNull()?.coerceIn(0.8f, 2.5f) ?: 1.2f
        val columnWidth = 2f * clearance * tan(Math.toRadians(HORIZONTAL_FOV_DEGREES / 2.0)).toFloat() / columns
        val needed = ceil((SceneAwarenessAnalyzer.ROBOT_WIDTH_METERS + SceneAwarenessAnalyzer.SAFETY_MARGIN_METERS) / columnWidth).toInt().coerceIn(1, columns)
        val runs = ArrayList<IntRange>(); var start = -1
        for (i in 0..columns) {
            val usable = i < columns && states[i] == OccupancyState.FREE
            if (usable && start < 0) start = i
            if (!usable && start >= 0) { if (i - start >= needed) runs += start until i; start = -1 }
        }
        val center = (columns - 1) / 2f
        return runs.map { run ->
            FreeCorridor(run.first, run.last, ((run.first + run.last) / 2f - center) / center.coerceAtLeast(1f), clearance)
        }
    }
    private const val HORIZONTAL_FOV_DEGREES = 70.0
}
