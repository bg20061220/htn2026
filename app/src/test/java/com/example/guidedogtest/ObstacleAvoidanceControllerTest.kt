package com.example.guidedogtest

import com.example.guidedogtest.ocr.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObstacleAvoidanceControllerTest {
    private var now = 1_000L
    private val controller = ObstacleAvoidanceController { now }

    @Test fun `center corridor drives forward and lateral corridors steer`() {
        assertEquals(AvoidanceState.FORWARD, update(scene(0f, SceneZoneState.CLEAR)).state)
        assertEquals(AvoidanceState.SLOW, update(scene(-0.35f, SceneZoneState.CAUTION)).state)
        assertEquals(AvoidanceState.SLOW, update(scene(0.35f, SceneZoneState.CAUTION)).state)
        assertEquals(AvoidanceState.TURN_RIGHT, update(scene(0.75f, SceneZoneState.BLOCKED)).state)
    }

    @Test fun `drop no corridor unknown and stale depth stop`() {
        assertEquals(AvoidanceState.STOPPED, update(scene(0f, SceneZoneState.CLEAR, drop = true)).state)
        assertEquals(AvoidanceState.STOPPED, update(scene(null, SceneZoneState.BLOCKED)).state)
        update(scene(0f, SceneZoneState.CLEAR))
        now += ObstacleAvoidanceController.SCENE_STALE_TIMEOUT_MS + 1
        assertEquals(AvoidanceState.STOPPED, controller.watchdog(true, true, true, true)?.state)
        assertEquals(AvoidanceState.STOPPED, update(SceneAwarenessResult()).state)
    }

    @Test fun `route forward clear path moves forward`() {
        assertEquals(AvoidanceState.FORWARD, update(scene(0f, SceneZoneState.CLEAR)).state)
    }

    @Test fun `center obstacle takes available left or right detour`() {
        val left = sceneWithCorridors(listOf(corridor(-.65f)), SceneZoneState.BLOCKED)
        assertEquals(AvoidanceState.TURN_LEFT, update(left).state)

        val freshController = ObstacleAvoidanceController { now }
        val right = sceneWithCorridors(listOf(corridor(.65f)), SceneZoneState.BLOCKED)
        assertEquals(AvoidanceState.TURN_RIGHT,
            freshController.update(right, true, true, true, true, DesiredTravelDirection.FORWARD).state)
    }

    @Test fun `blocked sides unavailable depth and drop stop exactly`() {
        assertEquals(WheelSpeeds(0, 0), update(sceneWithCorridors(emptyList(), SceneZoneState.BLOCKED)).wheelSpeeds)
        assertEquals(WheelSpeeds(0, 0), controller.update(scene(0f, SceneZoneState.CLEAR), true, true, false, true).wheelSpeeds)
        assertEquals(WheelSpeeds(0, 0), update(scene(0f, SceneZoneState.CLEAR, drop = true)).wheelSpeeds)
    }

    @Test fun `controller returns toward route after detour clears`() {
        assertEquals(AvoidanceState.TURN_LEFT,
            update(sceneWithCorridors(listOf(corridor(-.7f)), SceneZoneState.BLOCKED)).state)
        now++
        assertEquals(AvoidanceState.FORWARD, update(scene(0f, SceneZoneState.CLEAR)).state)
    }

    @Test fun `corridor side is latched when alternatives are nearly tied`() {
        val first = sceneWithCorridors(listOf(corridor(-.55f), corridor(.50f)), SceneZoneState.BLOCKED)
        val second = sceneWithCorridors(listOf(corridor(-.48f), corridor(.52f)), SceneZoneState.BLOCKED)
        assertEquals(AvoidanceState.TURN_RIGHT, update(first).state)
        now++
        assertEquals(AvoidanceState.TURN_RIGHT, update(second).state)
    }

    @Test fun `autonomous movement clears the effective motor floor`() {
        listOf(update(scene(0f, SceneZoneState.CLEAR)), update(scene(-.35f, SceneZoneState.CAUTION)), update(scene(.8f, SceneZoneState.BLOCKED)))
            .flatMap { listOf(it.wheelSpeeds.left, it.wheelSpeeds.right) }.filter { it != 0 }
            .forEach { assertTrue(kotlin.math.abs(it) >= ObstacleAvoidanceController.MIN_EFFECTIVE_MOTOR_SPEED) }
    }

    private fun update(scene: SceneAwarenessResult) = controller.update(scene, true, true, true, true)
    private fun scene(offset: Float?, center: SceneZoneState, drop: Boolean = false): SceneAwarenessResult {
        val cells = (0 until 4).flatMap { row -> (0 until 9).map { col -> OccupancyCell(col, row, if (drop && row == 3) OccupancyState.DROP else OccupancyState.FREE, 1.5f) } }
        return SceneAwarenessResult(centerState = center, depthTimestampNanos = now * 1_000_000, cells = cells,
            freeCorridor = offset?.let { FreeCorridor(2, 6, it, 1.5f) }, supportPlaneDetected = true, dropDetected = drop)
    }

    private fun corridor(offset: Float) = FreeCorridor(1, 4, offset, 1.5f)

    private fun sceneWithCorridors(corridors: List<FreeCorridor>, center: SceneZoneState): SceneAwarenessResult {
        val cells = (0 until 4).flatMap { row ->
            (0 until 9).map { col -> OccupancyCell(col, row, OccupancyState.FREE, 1.5f) }
        }
        return SceneAwarenessResult(
            centerState = center,
            depthTimestampNanos = now * 1_000_000,
            cells = cells,
            freeCorridor = corridors.firstOrNull(),
            freeCorridors = corridors,
            supportPlaneDetected = true,
        )
    }
}
