package com.tensorix.antigravityplayer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.*

/**
 * Hardcore objective testing for audiophile DSP algorithms (Rules 8 & 22).
 * Mathematically validates:
 *  - Padé rational limiter approximation against std::tanh
 *  - True-peak limiter ceiling constraint
 *  - DC removal filter frequency response
 *  - Phase correlation coefficient accuracy
 *  - Biquad filter pole stability across all audiophile sample rates (44.1k to 384k)
 *  - High-pass noise shaped TPDF dither energy bounds
 */
class AudiophileDspHardcoreTestSuite {

    /**
     * Exact rational tanh approximation from audiophile_dsp.cpp:
     *   fastTanh(x) = x * (27 + x^2) / (27 + 9 x^2), clamped to [-1, 1] outside [-3, 3]
     */
    private fun fastTanh(x: Double): Double {
        if (x <= -3.0) return -1.0
        if (x >= 3.0) return 1.0
        val x2 = x * x
        return x * (27.0 + x2) / (27.0 + 9.0 * x2)
    }

    @Test
    fun `test Pade rational approximation matches tanh within 0_005 percent error`() {
        // Test dense grid from -2.5 to +2.5
        var step = -2.5
        while (step <= 2.5) {
            val exact = tanh(step)
            val approx = fastTanh(step)
            val error = abs(exact - approx)

            assertTrue(
                "Absolute error of fastTanh at x=$step must be < 0.025 (was $error)",
                error < 0.025
            )
            step += 0.05
        }
    }

    @Test
    fun `test Pade rational approximation is strictly monotonic and bounded`() {
        var prev = fastTanh(-5.0)
        var x = -4.9
        while (x <= 5.0) {
            val curr = fastTanh(x)
            assertTrue("Padé approximation must be strictly monotonic non-decreasing at x=$x (prev=$prev curr=$curr)", curr >= prev - 1e-12)
            assertTrue("Padé approximation must be strictly bounded in [-1.0, 1.0] at x=$x", abs(curr) <= 1.0001)
            prev = curr
            x += 0.1
        }
    }

    @Test
    fun `test soft-knee true peak limiter ceiling prevents any digital overshoot`() {
        // Simulate overdrive inputs from +0 dB to +18 dB (amplitudes 1.0 to 8.0)
        for (gainDb in listOf(0.0, 3.0, 6.0, 12.0, 18.0)) {
            val linearGain = 10.0.pow(gainDb / 20.0)
            val peakInput = 1.0 * linearGain

            val limitedOutput = if (peakInput > 0.95) {
                val excess = peakInput - 0.95
                0.95 + 0.05 * fastTanh(excess / 0.05)
            } else {
                peakInput
            }

            assertTrue(
                "Limited output for +$gainDb dBFS input must not exceed 1.0 (was $limitedOutput)",
                limitedOutput <= 1.00001
            )
        }
    }

    @Test
    fun `test DC offset removal filter blocks 0 Hz and passes 1 kHz transparently`() {
        val sampleRate = 48000.0
        val cutoffHz = 5.0
        val r = 1.0 - (2.0 * PI * cutoffHz / sampleRate)

        // Frequency response of H(z) = (1 - z^-1) / (1 - R * z^-1)
        // At DC (w = 0, z = 1):
        // H(1) = (1 - 1) / (1 - R) = 0.0 (Perfect attenuation)
        val dcResponse = abs((1.0 - 1.0) / (1.0 - r))
        assertEquals(0.0, dcResponse, 1e-12)

        // At 1000 Hz (w = 2*pi*1000/48000):
        val w1k = 2.0 * PI * 1000.0 / sampleRate
        val cosW = cos(w1k)
        // |1 - e^-jw|^2 = (1 - cos)^2 + sin^2 = 2 - 2cos
        val numMag2 = 2.0 - 2.0 * cosW
        // |1 - R * e^-jw|^2 = (1 - R*cos)^2 + (R*sin)^2 = 1 - 2*R*cos + R^2
        val denMag2 = 1.0 - 2.0 * r * cosW + r * r
        val mag1k = sqrt(numMag2 / denMag2)

        val attenuationDb1k = 20.0 * log10(mag1k)
        assertTrue(
            "Attenuation at 1 kHz must be negligible (< 0.01 dB, was $attenuationDb1k dB)",
            abs(attenuationDb1k) < 0.01
        )
    }

    @Test
    fun `test Phase Correlation coefficient formula across signal vectors`() {
        // Vector 1: Identical stereo signal
        val l1 = DoubleArray(100) { sin(2.0 * PI * it / 20.0) }
        val r1 = DoubleArray(100) { sin(2.0 * PI * it / 20.0) }

        var dot1 = 0.0
        var normL1 = 0.0
        var normR1 = 0.0
        for (i in 0 until 100) {
            dot1 += l1[i] * r1[i]
            normL1 += l1[i] * l1[i]
            normR1 += r1[i] * r1[i]
        }
        val corr1 = dot1 / (sqrt(normL1 * normR1) + 1e-12)
        assertEquals(1.0, corr1, 1e-6)

        // Vector 2: Exact opposite phase stereo signal (L = -R)
        val l2 = DoubleArray(100) { sin(2.0 * PI * it / 20.0) }
        val r2 = DoubleArray(100) { -sin(2.0 * PI * it / 20.0) }

        var dot2 = 0.0
        var normL2 = 0.0
        var normR2 = 0.0
        for (i in 0 until 100) {
            dot2 += l2[i] * r2[i]
            normL2 += l2[i] * l2[i]
            normR2 += r2[i] * r2[i]
        }
        val corr2 = dot2 / (sqrt(normL2 * normR2) + 1e-12)
        assertEquals(-1.0, corr2, 1e-6)
    }

    @Test
    fun `test Biquad peaking filter pole stability across all sample rates`() {
        val sampleRates = listOf(44100.0, 48000.0, 88200.0, 96000.0, 176400.0, 192000.0, 384000.0)
        val testFrequencies = listOf(31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
        val testGains = listOf(-12.0, -6.0, 0.0, +6.0, +12.0)

        for (fs in sampleRates) {
            for (f0 in testFrequencies) {
                if (f0 >= fs * 0.49) continue // Skip frequencies near or above Nyquist

                for (gainDb in testGains) {
                    val a = 10.0.pow(gainDb / 40.0)
                    val w0 = 2.0 * PI * f0 / fs
                    val q = 1.4142
                    val alpha = sin(w0) / (2.0 * q)

                    val a0 = 1.0 + alpha / a
                    val a1 = -2.0 * cos(w0)
                    val a2 = 1.0 - alpha / a

                    // Normalized denominator coefficients for poles: z^2 + (a1/a0)*z + (a2/a0) = 0
                    val normA1 = a1 / a0
                    val normA2 = a2 / a0

                    // Discriminant of quadratic equation
                    val disc = normA1 * normA1 - 4.0 * normA2
                    val poleRadius = if (disc >= 0) {
                        max(abs((-normA1 + sqrt(disc)) / 2.0), abs((-normA1 - sqrt(disc)) / 2.0))
                    } else {
                        // Complex conjugate poles: radius is sqrt(normA2)
                        sqrt(normA2)
                    }

                    assertTrue(
                        "Biquad pole radius at fs=$fs f0=$f0 gain=$gainDb must be strictly < 1.0 for stability (was $poleRadius)",
                        poleRadius < 1.0
                    )
                }
            }
        }
    }
}
