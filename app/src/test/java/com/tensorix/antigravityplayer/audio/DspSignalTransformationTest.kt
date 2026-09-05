package com.tensorix.antigravityplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin

/**
 * Deterministic Signal Transformation & Verification Tests (Phase 7, 18, 27, 28)
 *
 * Verifies that DSP features physically alter the audio signal as advertised:
 *  1. ReplayGain multiplier actually scales sample amplitudes numerically.
 *  2. Triode Warmth introduces genuine asymmetric nonlinear curvature.
 *  3. Bit-Perfect Bypass guarantees exact sample-level transparency.
 *  4. Preamp boost/attenuation matches linear decibel calculations.
 */
class DspSignalTransformationTest {

    private lateinit var processor: Audiophile64BitDspProcessor

    @Before
    fun setUp() {
        processor = Audiophile64BitDspProcessor()
        val format = AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT)
        processor.configure(format)
        processor.flush()
    }

    private fun generateSineWave(frames: Int, freqHz: Double, amplitude: Float): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(frames * 8).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val sample = (amplitude * sin(2.0 * Math.PI * freqHz * i / 48000.0)).toFloat()
            buf.putFloat(sample)
            buf.putFloat(sample)
        }
        buf.flip()
        return buf
    }

    @Test
    fun `replayGain multiplier 0_5 physically halves signal amplitude`() {
        val frames = 1024
        val inputAmp = 0.5f

        // 1. Run at Unity Gain (1.0)
        processor.replayGainMultiplier = 1.0
        processor.flush()
        val inputUnity = generateSineWave(frames, 1000.0, inputAmp)
        processor.queueInput(inputUnity)
        val outUnity = processor.output
        var maxAmpUnity = 0.0f
        for (i in 0 until frames * 2) {
            val s = abs(outUnity.float)
            if (s > maxAmpUnity) maxAmpUnity = s
        }

        // 2. Run at 0.5 ReplayGain (-6.02 dB)
        processor.replayGainMultiplier = 0.5
        processor.flush()
        val inputHalf = generateSineWave(frames, 1000.0, inputAmp)
        processor.queueInput(inputHalf)
        val outHalf = processor.output
        var maxAmpHalf = 0.0f
        for (i in 0 until frames * 2) {
            val s = abs(outHalf.float)
            if (s > maxAmpHalf) maxAmpHalf = s
        }

        // Physical proof: Half amplitude should be approximately 50% of Unity amplitude
        val ratio = maxAmpHalf / maxAmpUnity
        assertTrue("Expected amplitude ratio ~0.5 but got $ratio", ratio in 0.48f..0.52f)
        assertTrue("Max amplitude at 0.5 ReplayGain must be ~0.25 but got $maxAmpHalf", maxAmpHalf in 0.23f..0.27f)
    }

    @Test
    fun `bit-perfect bypass ignores replayGain and preserves raw samples exactly`() {
        processor.isBitPerfectBypass = true
        processor.replayGainMultiplier = 0.25 // Extreme attenuation configured
        processor.preAmpGainDb = -12.0

        val frames = 256
        val input = ByteBuffer.allocateDirect(frames * 8).order(ByteOrder.LITTLE_ENDIAN)
        val expected = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val v = (i / 256.0f) * 0.8f
            expected[i * 2] = v
            expected[i * 2 + 1] = -v
            input.putFloat(v)
            input.putFloat(-v)
        }
        input.flip()

        processor.queueInput(input)
        val out = processor.output
        assertEquals(frames * 8, out.remaining())

        for (i in 0 until frames * 2) {
            val sample = out.float
            assertEquals("Sample at index $i must be untouched under bit-perfect bypass", expected[i], sample, 0.0f)
        }
    }

    @Test
    fun `triode warmth produces asymmetric nonlinear distortion`() {
        processor.triodeWarmthLevel = 0.8
        processor.flush()

        val frames = 512
        val inputAmp = 0.6f
        val input = generateSineWave(frames, 1000.0, inputAmp)

        processor.queueInput(input)
        val out = processor.output

        var maxPos = 0.0f
        var maxNeg = 0.0f
        for (i in 0 until frames * 2) {
            val s = out.float
            if (s > maxPos) maxPos = s
            if (-s > maxNeg) maxNeg = -s
        }

        // Triode model creates asymmetric 2nd-order distortion: positive peak differs from negative peak
        val diff = abs(maxPos - maxNeg)
        assertTrue("Triode warmth must produce measurable positive/negative asymmetry (diff=$diff)", diff > 0.005f)
    }

    @Test
    fun `preAmp gain of +6dB doubles amplitude and -6dB halves amplitude`() {
        val frames = 1024
        val inputAmp = 0.2f

        // +6.02 dB => ~2.0x amplitude
        processor.preAmpGainDb = 6.0206
        processor.flush()
        val inputPlus6 = generateSineWave(frames, 1000.0, inputAmp)
        processor.queueInput(inputPlus6)
        val outPlus6 = processor.output
        var maxAmpPlus6 = 0.0f
        for (i in 0 until frames * 2) {
            val s = abs(outPlus6.float)
            if (s > maxAmpPlus6) maxAmpPlus6 = s
        }
        assertTrue("Expected +6dB amplitude ~0.4 but got $maxAmpPlus6", maxAmpPlus6 in 0.38f..0.42f)

        // -6.02 dB => ~0.5x amplitude
        processor.preAmpGainDb = -6.0206
        processor.flush()
        val inputMinus6 = generateSineWave(frames, 1000.0, inputAmp)
        processor.queueInput(inputMinus6)
        val outMinus6 = processor.output
        var maxAmpMinus6 = 0.0f
        for (i in 0 until frames * 2) {
            val s = abs(outMinus6.float)
            if (s > maxAmpMinus6) maxAmpMinus6 = s
        }
        assertTrue("Expected -6dB amplitude ~0.1 but got $maxAmpMinus6", maxAmpMinus6 in 0.09f..0.11f)
    }
}
