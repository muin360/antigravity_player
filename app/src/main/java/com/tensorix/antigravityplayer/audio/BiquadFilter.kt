package com.tensorix.antigravityplayer.audio

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class BiquadCoeffs(
    val b0: Double = 1.0,
    val b1: Double = 0.0,
    val b2: Double = 0.0,
    val a1: Double = 0.0,
    val a2: Double = 0.0
)

/**
 * 64-bit Double Precision Direct Form II Transposed Biquad Filter
 * Numerical stability for audiophile parametric equalization without rounding noise.
 */
class BiquadFilter {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    private var z1 = 0.0
    private var z2 = 0.0

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    fun setCoefficients(c: BiquadCoeffs) {
        b0 = c.b0
        b1 = c.b1
        b2 = c.b2
        a1 = c.a1
        a2 = c.a2
    }

    fun getCoefficients(): BiquadCoeffs = BiquadCoeffs(b0, b1, b2, a1, a2)

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        // Audiophile denormal protection against CPU Kryo stalls at ultra-low amplitudes
        if (z1 != 0.0 && abs(z1) < 1.0e-15) z1 = 0.0
        if (z2 != 0.0 && abs(z2) < 1.0e-15) z2 = 0.0
        return y
    }

    fun setPeakingEq(f0: Double, q: Double, gainDb: Double, fs: Double) {
        setCoefficients(computePeakingEq(f0, q, gainDb, fs))
    }

    fun setLowShelf(f0: Double, q: Double, gainDb: Double, fs: Double) {
        setCoefficients(computeLowShelf(f0, q, gainDb, fs))
    }

    fun setHighShelf(f0: Double, q: Double, gainDb: Double, fs: Double) {
        setCoefficients(computeHighShelf(f0, q, gainDb, fs))
    }

    fun setHighPass(f0: Double, q: Double, fs: Double) {
        setCoefficients(computeHighPass(f0, q, fs))
    }

    fun setLowPass(f0: Double, q: Double, fs: Double) {
        setCoefficients(computeLowPass(f0, q, fs))
    }

    fun setAllPass(f0: Double, q: Double, fs: Double) {
        setCoefficients(computeAllPass(f0, q, fs))
    }

    fun setNotch(f0: Double, q: Double, fs: Double) {
        setCoefficients(computeNotch(f0, q, fs))
    }

    companion object {
        private fun safeNormalize(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double): BiquadCoeffs {
            if (!a0.isFinite() || abs(a0) < 1.0e-15) return BiquadCoeffs()
            val invA0 = 1.0 / a0
            val nb0 = b0 * invA0
            val nb1 = b1 * invA0
            val nb2 = b2 * invA0
            val na1 = a1 * invA0
            val na2 = a2 * invA0
            if (!nb0.isFinite() || !nb1.isFinite() || !nb2.isFinite() || !na1.isFinite() || !na2.isFinite()) {
                return BiquadCoeffs()
            }
            return BiquadCoeffs(nb0, nb1, nb2, na1, na2)
        }

        fun computePeakingEq(f0: Double, q: Double, gainDb: Double, fs: Double): BiquadCoeffs {
            if (!fs.isFinite() || fs <= 0.0) return BiquadCoeffs()
            val f = f0.coerceIn(1.0, fs * 0.499)
            val qSafe = q.coerceIn(0.01, 100.0)
            val g = gainDb.coerceIn(-60.0, 60.0)
            if (abs(g) < 1.0e-6) return BiquadCoeffs()

            val a = 10.0.pow(g / 40.0)
            val w0 = 2.0 * Math.PI * f / fs
            val alpha = sin(w0) / (2.0 * qSafe)
            val cosW0 = cos(w0)

            val a0 = 1.0 + alpha / a
            val b0 = 1.0 + alpha * a
            val b1 = -2.0 * cosW0
            val b2 = 1.0 - alpha * a
            val a1 = -2.0 * cosW0
            val a2 = 1.0 - alpha / a
            return safeNormalize(b0, b1, b2, a0, a1, a2)
        }

        fun computeLowShelf(f0: Double, q: Double, gainDb: Double, fs: Double): BiquadCoeffs {
            if (!fs.isFinite() || fs <= 0.0) return BiquadCoeffs()
            val f = f0.coerceIn(1.0, fs * 0.499)
            val qSafe = q.coerceIn(0.01, 100.0)
            val g = gainDb.coerceIn(-60.0, 60.0)
            if (abs(g) < 1.0e-6) return BiquadCoeffs()

            val a = 10.0.pow(g / 40.0)
            val w0 = 2.0 * Math.PI * f / fs
            val alpha = sin(w0) / 2.0 * sqrt((a + 1.0 / a) * (1.0 / qSafe - 1.0) + 2.0)
            val cosW0 = cos(w0)

            val a0 = (a + 1.0) + (a - 1.0) * cosW0 + 2.0 * sqrt(a) * alpha
            val b0 = a * ((a + 1.0) - (a - 1.0) * cosW0 + 2.0 * sqrt(a) * alpha)
            val b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0)
            val b2 = a * ((a + 1.0) - (a - 1.0) * cosW0 - 2.0 * sqrt(a) * alpha)
            val a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW0)
            val a2 = (a + 1.0) + (a - 1.0) * cosW0 - 2.0 * sqrt(a) * alpha
            return safeNormalize(b0, b1, b2, a0, a1, a2)
        }

        fun computeHighShelf(f0: Double, q: Double, gainDb: Double, fs: Double): BiquadCoeffs {
            if (!fs.isFinite() || fs <= 0.0) return BiquadCoeffs()
            val f = f0.coerceIn(1.0, fs * 0.499)
            val qSafe = q.coerceIn(0.01, 100.0)
            val g = gainDb.coerceIn(-60.0, 60.0)
            if (abs(g) < 1.0e-6) return BiquadCoeffs()

            val a = 10.0.pow(g / 40.0)
            val w0 = 2.0 * Math.PI * f / fs
            val alpha = sin(w0) / 2.0 * sqrt((a + 1.0 / a) * (1.0 / qSafe - 1.0) + 2.0)
            val cosW0 = cos(w0)

            val a0 = (a + 1.0) - (a - 1.0) * cosW0 + 2.0 * sqrt(a) * alpha
            val b0 = a * ((a + 1.0) + (a - 1.0) * cosW0 + 2.0 * sqrt(a) * alpha)
            val b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0)
            val b2 = a * ((a + 1.0) - (a - 1.0) * cosW0 - 2.0 * sqrt(a) * alpha)
            val a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW0)
            val a2 = (a + 1.0) - (a - 1.0) * cosW0 - 2.0 * sqrt(a) * alpha
            return safeNormalize(b0, b1, b2, a0, a1, a2)
        }

        fun computeHighPass(f0: Double, q: Double, fs: Double): BiquadCoeffs {
            if (!fs.isFinite() || fs <= 0.0) return BiquadCoeffs()
            val f = f0.coerceIn(1.0, fs * 0.499)
            val qSafe = q.coerceIn(0.01, 100.0)
            val w0 = 2.0 * Math.PI * f / fs
            val alpha = sin(w0) / (2.0 * qSafe)
            val cosW0 = cos(w0)

            val a0 = 1.0 + alpha
            val b0 = (1.0 + cosW0) * 0.5
            val b1 = -(1.0 + cosW0)
            val b2 = (1.0 + cosW0) * 0.5
            val a1 = -2.0 * cosW0
            val a2 = 1.0 - alpha
            return safeNormalize(b0, b1, b2, a0, a1, a2)
        }

        fun computeLowPass(f0: Double, q: Double, fs: Double): BiquadCoeffs {
            if (!fs.isFinite() || fs <= 0.0) return BiquadCoeffs()
            val f = f0.coerceIn(1.0, fs * 0.499)
            val qSafe = q.coerceIn(0.01, 100.0)
            val w0 = 2.0 * Math.PI * f / fs
            val alpha = sin(w0) / (2.0 * qSafe)
            val cosW0 = cos(w0)

            val a0 = 1.0 + alpha
            val b0 = (1.0 - cosW0) * 0.5
            val b1 = 1.0 - cosW0
            val b2 = (1.0 - cosW0) * 0.5
            val a1 = -2.0 * cosW0
            val a2 = 1.0 - alpha
            return safeNormalize(b0, b1, b2, a0, a1, a2)
        }

        fun computeAllPass(f0: Double, q: Double, fs: Double): BiquadCoeffs {
            if (!fs.isFinite() || fs <= 0.0) return BiquadCoeffs()
            val f = f0.coerceIn(1.0, fs * 0.499)
            val qSafe = q.coerceIn(0.01, 100.0)
            val w0 = 2.0 * Math.PI * f / fs
            val alpha = sin(w0) / (2.0 * qSafe)
            val cosW0 = cos(w0)

            val a0 = 1.0 + alpha
            val b0 = 1.0 - alpha
            val b1 = -2.0 * cosW0
            val b2 = 1.0 + alpha
            val a1 = -2.0 * cosW0
            val a2 = 1.0 - alpha
            return safeNormalize(b0, b1, b2, a0, a1, a2)
        }

        fun computeNotch(f0: Double, q: Double, fs: Double): BiquadCoeffs {
            if (!fs.isFinite() || fs <= 0.0) return BiquadCoeffs()
            val f = f0.coerceIn(1.0, fs * 0.499)
            val qSafe = q.coerceIn(0.01, 100.0)
            val w0 = 2.0 * Math.PI * f / fs
            val alpha = sin(w0) / (2.0 * qSafe)
            val cosW0 = cos(w0)

            val a0 = 1.0 + alpha
            val b0 = 1.0
            val b1 = -2.0 * cosW0
            val b2 = 1.0
            val a1 = -2.0 * cosW0
            val a2 = 1.0 - alpha
            return safeNormalize(b0, b1, b2, a0, a1, a2)
        }
    }
}
