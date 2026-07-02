// SPDX-FileCopyrightText: 2026 BrainLift contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.brainlift

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Parity tests: these mirror the desktop Python tests
 * (`pylib/tests/test_brainlift_calibration.py`, `test_brainlift_fatigue.py`) and
 * must produce the SAME numbers, proving the two engines cannot diverge.
 */
class BrainLiftParityTest {
    private val eps = 1e-9

    // --- Feature 1: calibration ---------------------------------------------
    @Test
    fun confidenceScaleMatchesDesktop() {
        assertEquals(1.0, BrainLiftCalibration.confidenceValue("Highly confident"), eps)
        assertEquals(0.85, BrainLiftCalibration.confidenceValue("Confident"), eps)
        assertEquals(0.6, BrainLiftCalibration.confidenceValue("Kind of confident"), eps)
        assertEquals(0.3, BrainLiftCalibration.confidenceValue("Unsure"), eps)
        assertEquals(0.0, BrainLiftCalibration.confidenceValue("Guessing"), eps)
        assertEquals(15, BrainLiftCalibration.CALIBRATION_TEST_SIZE)
        assertEquals(50, BrainLiftCalibration.CALIBRATION_PRODUCTION_SIZE)
    }

    @Test
    fun deviationAndAccuracy() {
        assertEquals(1.0, BrainLiftCalibration.calibrationAccuracy(listOf(1.0, 0.0, 1.0, 0.0), listOf(1, 0, 1, 0)), eps)
        assertEquals(0.0, BrainLiftCalibration.calibrationAccuracy(listOf(1.0, 1.0, 1.0), listOf(0, 0, 0)), eps)
        assertEquals(0.7, BrainLiftCalibration.calibrationAccuracy(listOf(1.0, 0.6, 0.3), listOf(1, 0, 0)), eps)
    }

    @Test
    fun gamma() {
        assertEquals(1.0, BrainLiftCalibration.goodmanKruskalGamma(listOf(1.0, 0.85, 0.3, 0.0), listOf(1, 1, 0, 0))!!, eps)
        assertEquals(-1.0, BrainLiftCalibration.goodmanKruskalGamma(listOf(1.0, 0.85, 0.3, 0.0), listOf(0, 0, 1, 1))!!, eps)
        assertNull(BrainLiftCalibration.goodmanKruskalGamma(listOf(1.0, 0.5), listOf(1, 1)))
    }

    @Test
    fun authorityMultiplierAndMasteryGap() {
        assertEquals(1.0, BrainLiftCalibration.authorityMultiplier(1.0), eps)
        assertEquals(0.25, BrainLiftCalibration.authorityMultiplier(0.5), eps)
        assertEquals(0.25, BrainLiftCalibration.authorityMultiplier(0.0), eps)
        assertEquals(0.625, BrainLiftCalibration.authorityMultiplier(0.75), eps)
        assertEquals(0.0, BrainLiftCalibration.effectiveMasteryGap(1.0, 1.0), eps)
        assertEquals(0.75, BrainLiftCalibration.effectiveMasteryGap(1.0, 0.25), eps)
        assertEquals(1.0, BrainLiftCalibration.effectiveMasteryGap(0.0, 1.0), eps)
    }

    @Test
    fun deterministicAnalogHasNamedSourceAndValidMcq() {
        val client = BrainLiftAi.DeterministicAnalogClient()
        val a = client.generateAnalog("A fair coin is flipped 3 times. How many outcomes?", "8", 42)
        assertTrue(a.ok)
        assertTrue(a.choices.size >= 2)
        assertTrue(a.correctIndex in a.choices.indices)
        assertEquals(42L, a.sourceCardId)
        assertTrue(a.sourceText.isNotEmpty())
    }

    // --- Feature 2: fatigue --------------------------------------------------
    private fun steady(
        n: Int,
        rt: Double,
        correct: Boolean,
        state: BrainLiftFatigue.FatigueState,
        topic: String = "U",
    ): BrainLiftFatigue.FatigueState {
        var s = state
        repeat(n) { s = BrainLiftFatigue.updateState(s, rt, correct, topic) }
        return s
    }

    @Test
    fun steadyPerformanceNoDrain() {
        var s = BrainLiftFatigue.newSession(0)
        s = steady(15, 3.0, true, s)
        val d = BrainLiftFatigue.decide(s, testMode = true, now = 60)
        assertTrue(d.drain < BrainLiftFatigue.DRAIN_INTERVENE)
        assertTrue(!d.intervene)
    }

    @Test
    fun degradationTriggersInterventionInTestMode() {
        var s = BrainLiftFatigue.newSession(0)
        s = steady(8, 2.0, true, s)
        s = steady(8, 9.0, false, s)
        val d = BrainLiftFatigue.decide(s, testMode = true, now = 120)
        assertTrue(d.drain >= BrainLiftFatigue.DRAIN_INTERVENE)
        assertTrue(d.intervene)
    }

    @Test
    fun prodTimingGateBlocksModerateEarlyDrain() {
        var s = BrainLiftFatigue.newSession(0)
        s = steady(8, 2.0, true, s)
        s = steady(8, 4.0, false, s)
        assertTrue(s.smoothedDrain >= BrainLiftFatigue.DRAIN_INTERVENE && s.smoothedDrain < BrainLiftFatigue.SEVERE_DRAIN)
        assertTrue(!BrainLiftFatigue.decide(s, testMode = false, now = 120).intervene)
        assertTrue(BrainLiftFatigue.decide(s, testMode = true, now = 120).intervene)
    }

    @Test
    fun interleaveOnLongSameTopicStreak() {
        var s = BrainLiftFatigue.newSession(0)
        s = steady(8, 2.0, true, s, "U")
        s = steady(13, 8.0, false, s, "U")
        val d = BrainLiftFatigue.decide(s, testMode = true, now = 120)
        assertTrue(s.sameTopicStreak >= BrainLiftFatigue.SAME_TOPIC_STREAK_LIMIT)
        assertTrue(d.intervene)
        assertEquals(BrainLiftFatigue.TYPE_INTERLEAVE, d.type)
    }
}
