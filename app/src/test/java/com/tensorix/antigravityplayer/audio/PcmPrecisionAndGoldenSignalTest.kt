package com.tensorix.antigravityplayer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Hardcore Golden Signal and Numerical Precision Test Suite.
 * Validates Rules 9, 10, 49, and 50:
 * - 16-bit and 24-bit exact integer round-trip in IEEE 754 32-bit Float
 * - 32-bit integer precision loss proof in 32-bit Float
 * - Golden signals: silence, unit impulse, full-scale sine, Nyquist tone, phase correlation
 */
class PcmPrecisionAndGoldenSignalTest {

    // ========================================================================
    // RULE 10: FLOAT PIPELINE VS INTEGER BIT-PERFECT PRECISION PROOF
    // ========================================================================

    @Test
    fun `test 16-bit PCM integer exact round-trip identity in 32-bit float`() {
        // IEEE 754 float has 24 bits significand >= 16 bits.
        // Every single 16-bit integer must reconstruct identically without single-bit error.
        var errorCount = 0
        for (sample in -32768..32767) {
            val floatVal = sample.toFloat() * (1.0f / 32768.0f)
            val recovered = round(floatVal * 32768.0f).toInt()
            if (recovered != sample) {
                errorCount++
            }
        }
        assertEquals("16-bit PCM must have exactly zero bit error in 32-bit Float", 0, errorCount)
    }

    @Test
    fun `test 24-bit PCM integer exact round-trip identity in 32-bit float`() {
        // IEEE 754 float has 24 bits significand (23 explicit + 1 implicit).
        // 24-bit signed values [-8388608, 8388607] fit losslessly into float24 significand.
        val testValues = intArrayOf(
            0, 1, -1, 2, -2,
            8388607, -8388608,      // Max/Min 24-bit
            4194304, -4194304,
            0x123456, -0x123456,
            0x7F0000, -0x7F0000,
            0x000001, -0x000001,
            0x555555, -0x555555,
            0x2AAAAA, -0x2AAAAA
        )

        for (sample in testValues) {
            val floatVal = sample.toFloat() * (1.0f / 8388608.0f)
            val recovered = round(floatVal * 8388608.0f).toInt()
            assertEquals("24-bit sample $sample must be preserved losslessly in Float", sample, recovered)
        }

        // Pseudo-random dense sample across 24-bit range
        val rng = java.util.Random(1337)
        for (i in 0 until 100_000) {
            val sample = rng.nextInt(16777216) - 8388608
            val floatVal = sample.toFloat() * (1.0f / 8388608.0f)
            val recovered = round(floatVal * 8388608.0f).toInt()
            assertEquals("Random 24-bit sample $sample must have exact identity", sample, recovered)
        }
    }

    @Test
    fun `test 32-bit integer PCM loses lower 8 bits in 32-bit float (Rule 10 Proof)`() {
        // A 32-bit integer requires 31 bits of significand.
        // IEEE 754 single-precision float only has 24 bits of significand.
        // Therefore, integers with non-zero lower 7-8 bits CANNOT be reconstructed!
        val sample32 = 0x7FFFFF55 // 2147483477
        val floatVal = (sample32.toDouble() * (1.0 / 2147483648.0)).toFloat()
        val recovered = (floatVal.toDouble() * 2147483648.0).toLong().toInt()

        // Mathematically prove that the lower bits are truncated/corrupted
        assertNotEquals(
            "32-bit integer PCM through 32-bit float MUST lose precision on lower bits (cannot be bit-perfect)",
            sample32,
            recovered
        )
        val bitDifference = abs(sample32 - recovered)
        assertTrue("Lower bit difference must be non-zero", bitDifference > 0)
    }

    // ========================================================================
    // RULES 49 & 50: GOLDEN SIGNAL TEST FIXTURES
    // ========================================================================

    @Test
    fun `test golden silence fixture has zero RMS, zero DC and no NaN`() {
        val buffer = FloatArray(1024) { 0.0f }
        var sumSquares = 0.0
        var sumDc = 0.0
        var nanCount = 0
        var infCount = 0

        for (sample in buffer) {
            if (sample.isNaN()) nanCount++
            if (sample.isInfinite()) infCount++
            sumSquares += sample * sample
            sumDc += sample
        }

        val rms = sqrt(sumSquares / buffer.size)
        val dcOffset = sumDc / buffer.size

        assertEquals(0, nanCount)
        assertEquals(0, infCount)
        assertEquals(0.0, rms, 1e-12)
        assertEquals(0.0, dcOffset, 1e-12)
    }

    @Test
    fun `test golden full-scale 0 dBFS sine wave RMS and peak integrity`() {
        val sampleRate = 48000
        val frequency = 1000.0 // 1 kHz
        val numSamples = sampleRate // 1 second
        val buffer = FloatArray(numSamples) { i ->
            sin(2.0 * PI * frequency * i / sampleRate).toFloat()
        }

        var peak = 0.0f
        var sumSquares = 0.0
        for (sample in buffer) {
            val a = abs(sample)
            if (a > peak) peak = a
            sumSquares += sample * sample
        }
        val rms = sqrt(sumSquares / numSamples)

        // Peak must be 1.0 (0 dBFS)
        assertEquals(1.0f, peak, 1e-4f)
        // Theoretical RMS of sine is 1 / sqrt(2) ≈ 0.70710678
        assertEquals(1.0 / sqrt(2.0), rms, 1e-3)
    }

    @Test
    fun `test golden unit impulse response delta has unit energy`() {
        val buffer = FloatArray(512) { 0.0f }
        buffer[0] = 1.0f // delta[n]

        var energy = 0.0
        var peak = 0.0f
        for (sample in buffer) {
            val a = abs(sample)
            if (a > peak) peak = a
            energy += sample * sample
        }

        assertEquals(1.0f, peak, 1e-6f)
        assertEquals(1.0, energy, 1e-6)
    }

    @Test
    fun `test golden stereo phase correlation fixture math`() {
        val n = 1024

        // 1. In-phase stereo (L == R) -> correlation must be +1.0
        val lInPhase = FloatArray(n) { i -> sin(2.0 * PI * 440.0 * i / 48000.0).toFloat() }
        val rInPhase = lInPhase.clone()
        val corrInPhase = computePhaseCorrelation(lInPhase, rInPhase)
        assertEquals(1.0f, corrInPhase, 1e-4f)

        // 2. Anti-phase stereo (L == -R) -> correlation must be -1.0
        val rAntiPhase = FloatArray(n) { i -> -lInPhase[i] }
        val corrAntiPhase = computePhaseCorrelation(lInPhase, rAntiPhase)
        assertEquals(-1.0f, corrAntiPhase, 1e-4f)

        // 3. Orthogonal stereo (sine and cosine) -> correlation must be ~0.0
        val rOrthogonal = FloatArray(n) { i -> kotlin.math.cos(2.0 * PI * 440.0 * i / 48000.0).toFloat() }
        val corrOrthogonal = computePhaseCorrelation(lInPhase, rOrthogonal)
        assertEquals(0.0f, corrOrthogonal, 0.05f)
    }

    @Test
    fun `test golden Nyquist tone stability has zero DC and bounded amplitude`() {
        // Nyquist frequency tone: alternates +1.0, -1.0, +1.0, -1.0
        val n = 512
        val buffer = FloatArray(n) { i -> if (i % 2 == 0) 1.0f else -1.0f }

        var dcSum = 0.0
        var peak = 0.0f
        for (sample in buffer) {
            val a = abs(sample)
            if (a > peak) peak = a
            dcSum += sample
        }

        assertEquals(1.0f, peak, 1e-6f)
        assertEquals(0.0, dcSum / n, 1e-6)
    }

    private fun computePhaseCorrelation(left: FloatArray, right: FloatArray): Float {
        var dot = 0.0
        var sumSqL = 0.0
        var sumSqR = 0.0
        for (i in left.indices) {
            dot += left[i] * right[i]
            sumSqL += left[i] * left[i]
            sumSqR += right[i] * right[i]
        }
        val denom = sqrt(sumSqL * sumSqR)
        return if (denom > 1e-9) (dot / denom).toFloat() else 0.0f
    }
}
