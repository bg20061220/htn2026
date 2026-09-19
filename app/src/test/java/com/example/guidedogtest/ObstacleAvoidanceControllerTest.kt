package com.example.guidedogtest

import com.example.guidedogtest.ocr.AvoidanceAction
import com.example.guidedogtest.ocr.ObstacleAvoidanceConfig
import com.example.guidedogtest.ocr.ObstacleAvoidanceController
import com.example.guidedogtest.ocr.SceneAwarenessResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObstacleAvoidanceControllerTest {
    private var now = 0L
    // No smoothing so each test reads a single frame's decision.
    private fun controller(smoothing: Float = 1f) =
        ObstacleAvoidanceController(ObstacleAvoidanceConfig(smoothing = smoothing)) { now }

    private fun scene(left: Float?, center: Float?, right: Float?) =
        SceneAwarenessResult(
            leftDistanceMeters = left,
            centerDistanceMeters = center,
            rightDistanceMeters = right
        )

    @Test
    fun openSpace_goesStraight() {
        val command = controller().update(scene(4f, 4f, 4f))
        assertEquals(AvoidanceAction.FORWARD, command.action)
        assertEquals(0f, command.steering, 0.001f)
        assertEquals(1f, command.speedFactor, 0.001f)
    }

    @Test
    fun leftCloserThanRight_veersRight() {
        val command = controller().update(scene(left = 1.2f, center = 4f, right = 3.5f))
        assertEquals(AvoidanceAction.RIGHT, command.action)
        assertTrue(command.steering > 0f)
    }

    @Test
    fun rightCloserThanLeft_veersLeft() {
        val command = controller().update(scene(left = 3.5f, center = 4f, right = 1.2f))
        assertEquals(AvoidanceAction.LEFT, command.action)
        assertTrue(command.steering < 0f)
    }

    @Test
    fun closerObstacleSteersHarder() {
        val far = controller().update(scene(left = 2.0f, center = 4f, right = 4f))
        val near = controller().update(scene(left = 1.0f, center = 4f, right = 4f))
        assertTrue(near.steering > far.steering)
    }

    @Test
    fun obstacleAhead_escapesTowardMoreOpenSide() {
        val command = controller().update(scene(left = 2.0f, center = 1.0f, right = 4f))
        assertEquals(AvoidanceAction.RIGHT, command.action)
        assertTrue(command.speedFactor < 1f)
    }

    @Test
    fun blockedAheadAndBothSidesBlocked_stops() {
        val command = controller().update(scene(left = 0.5f, center = 0.5f, right = 0.6f))
        assertEquals(AvoidanceAction.STOP, command.action)
        assertEquals(0f, command.speedFactor, 0f)
    }

    @Test
    fun blockedAheadWithOneOpenSide_turnsToIt() {
        val command = controller().update(scene(left = 0.5f, center = 0.5f, right = 3f))
        assertEquals(AvoidanceAction.RIGHT, command.action)
    }

    @Test
    fun unknownCenter_stops() {
        assertEquals(AvoidanceAction.STOP, controller().update(scene(3f, null, 3f)).action)
        assertEquals(AvoidanceAction.STOP, controller().update(scene(null, null, null)).action)
    }

    @Test
    fun unknownSide_isNeverSteeredInto() {
        val command = controller().update(scene(left = null, center = 4f, right = 4f))
        assertEquals(AvoidanceAction.RIGHT, command.action)
    }

    @Test
    fun nearlyEqualSides_doNotFlipFlopWhenBlockedAhead() {
        val controller = controller()
        val first = controller.update(scene(left = 2.0f, center = 1.0f, right = 2.4f))
        val second = controller.update(scene(left = 2.2f, center = 1.0f, right = 2.1f))
        assertEquals(first.action, second.action)
    }

    @Test
    fun smoothing_easesIntoTheSteering() {
        val controller = controller(smoothing = 0.5f)
        val first = controller.update(scene(left = 1.0f, center = 4f, right = 4f))
        val second = controller.update(scene(left = 1.0f, center = 4f, right = 4f))
        assertTrue(second.steering > first.steering)
    }

    @Test
    fun staleDepth_stopsOnce() {
        val controller = controller()
        controller.update(scene(4f, 4f, 4f))
        now += 500
        assertNull(controller.checkStale())
        now += 2_000
        assertEquals(AvoidanceAction.STOP, controller.checkStale()?.action)
        assertNull(controller.checkStale())
        controller.update(scene(4f, 4f, 4f))
        now += 2_000
        assertNotNull(controller.checkStale())
    }
}
