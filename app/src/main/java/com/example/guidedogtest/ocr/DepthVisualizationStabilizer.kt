package com.example.guidedogtest.ocr

import android.os.SystemClock

enum class DepthVisualState { ACTIVE, UPDATING, STALE, UNSUPPORTED }

data class DepthVisualization(
    val scene: SceneAwarenessResult,
    val state: DepthVisualState,
)

/** UI-only persistence. Raw scenes still go directly to the safety controller. */
class DepthVisualizationStabilizer(
    private val clockMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    private data class PendingRecovery(val state: OccupancyState, val samples: Int)

    private var lastValidScene: SceneAwarenessResult? = null
    private var lastValidAtMillis = Long.MIN_VALUE
    private var latestSceneInputValid = false
    private val pendingRecoveries = mutableMapOf<Pair<Int, Int>, PendingRecovery>()

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
        val previousByPosition = lastValidScene?.cells.orEmpty().associateBy { it.column to it.row }
        val cells = incoming.cells.map { cell ->
            val key = cell.column to cell.row
            val previous = previousByPosition[key] ?: return@map cell
            when {
                cell.state == previous.state -> {
                    pendingRecoveries.remove(key)
                    cell
                }
                isMoreDangerous(cell.state, previous.state) -> {
                    pendingRecoveries.remove(key)
                    cell // Hazards appear on the first sample.
                }
                else -> {
                    val pending = pendingRecoveries[key]
                    val samples = if (pending?.state == cell.state) pending.samples + 1 else 1
                    if (samples >= CLEAR_CONFIRMATION_SAMPLES) {
                        pendingRecoveries.remove(key)
                        cell
                    } else {
                        pendingRecoveries[key] = PendingRecovery(cell.state, samples)
                        previous.copy(distanceMeters = cell.distanceMeters ?: previous.distanceMeters)
                    }
                }
            }
        }
        return rebuildVisualScene(incoming, cells)
    }

    private fun rebuildVisualScene(source: SceneAwarenessResult, cells: List<OccupancyCell>): SceneAwarenessResult {
        val drop = cells.any { it.state == OccupancyState.DROP }
        val corridors = if (drop) emptyList() else FreeSpaceCorridorSelector.selectAll(cells, source.gridColumns, source.gridRows)
        val corridor = corridors.minByOrNull { kotlin.math.abs(it.centerOffset) }
        val direction = when {
            drop || corridor == null -> RecommendedDirection.STOP
            corridor.centerOffset < -0.12f -> RecommendedDirection.LEFT
            corridor.centerOffset > 0.12f -> RecommendedDirection.RIGHT
            else -> RecommendedDirection.FORWARD
        }
        return source.copy(cells = cells, freeCorridor = corridor, freeCorridors = corridors,
            dropDetected = drop, recommendedDirection = direction)
    }

    private fun isMoreDangerous(next: OccupancyState, previous: OccupancyState): Boolean =
        dangerRank(next) > dangerRank(previous)

    private fun dangerRank(state: OccupancyState): Int = when (state) {
        OccupancyState.FREE -> 0
        OccupancyState.UNKNOWN -> 1
        OccupancyState.CAUTION -> 2
        OccupancyState.OCCUPIED -> 3
        OccupancyState.DROP -> 4
    }

    private fun isValid(scene: SceneAwarenessResult) =
        scene.depthTimestampNanos != null && scene.supportPlaneDetected && scene.cells.isNotEmpty()

    companion object {
        const val DEPTH_VISUAL_HOLD_MS = 1_500L
        const val DEPTH_ACTIVE_WINDOW_MS = 500L
        const val CLEAR_CONFIRMATION_SAMPLES = 2

        fun unknownScene(columns: Int = SceneAwarenessAnalyzer.GRID_COLUMNS, rows: Int = SceneAwarenessAnalyzer.GRID_ROWS) =
            SceneAwarenessResult(
                gridColumns = columns,
                gridRows = rows,
                cells = (0 until rows).flatMap { row ->
                    (0 until columns).map { column -> OccupancyCell(column, row, OccupancyState.UNKNOWN) }
                },
            )
    }
}
