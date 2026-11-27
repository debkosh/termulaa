package com.debkosh.termulaa.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopGestureTest {
    private val g = 9.81f

    private fun feed(d: StopGesture.Detector, x: Float, y: Float, z: Float, from: Long, to: Long): Boolean {
        var hit = false
        var t = from
        while (t <= to) {
            hit = d.feed(x, y, z, t) || hit
            t += 20
        }
        return hit
    }

    @Test fun turningFaceDownStops() {
        val d = StopGesture.Detector()
        assertFalse(feed(d, 0f, 0f, g, 0, 500)) // face up on the table
        assertFalse(feed(d, 0f, 0f, -g, 520, 700)) // turned, not held long enough
        assertTrue(feed(d, 0f, 0f, -g, 720, 1200))
    }

    @Test fun alreadyFaceDownDoesNot() {
        val d = StopGesture.Detector()
        assertFalse(feed(d, 0f, 0f, -g, 0, 3000))
    }

    @Test fun inAPocketDoesNot() {
        val d = StopGesture.Detector()
        // Upright and walking: gravity along y, small bumps.
        var hit = false
        for (i in 0..200) hit = d.feed(0.5f, g + (i % 5) * 0.8f, 1f, i * 20L) || hit
        assertFalse(hit)
    }

    @Test fun threeJoltsInASecondStop() {
        val d = StopGesture.Detector()
        assertFalse(d.feed(0f, 30f, 0f, 0))
        assertFalse(d.feed(0f, 0f, g, 100)) // calm between
        assertFalse(d.feed(0f, -30f, 0f, 300))
        assertTrue(d.feed(0f, 30f, 0f, 600))
    }

    @Test fun joltsSpreadOutDoNot() {
        val d = StopGesture.Detector()
        assertFalse(d.feed(0f, 30f, 0f, 0))
        assertFalse(d.feed(0f, 30f, 0f, 1500))
        assertFalse(d.feed(0f, 30f, 0f, 3000))
    }
}
