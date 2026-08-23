package com.tensorix.antigravityplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Golden transparency tests for the fallback-path JVM DSP.
 *
 * Contract under test (mirrors the native engine):
 *  - Neutral configuration => output equals input within the DC-blocker
 *    transient envelope (no colouration, NO dither noise).
 *  - Explicit requantization dither changes the signal only when enabled AND
 *    an integer depth below 32 bits is selected.
 */
class AudiophileDspTransparencyTest {

    private lateinit var processor: Audiophile64BitDspProcessor

    @Before
    fun setUp() {
        processor = Audiophile64BitDspProcessor()
        val format = AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT)
        val configured = processor.configure(format)
        processor.flush()
        System.err.println("DBG2 configured=$configured rate=${processor.currentSampleRate}")
        assertEquals(C.ENCODING_PCM_FLOAT, configured.encoding)
    }

    private fun frameBuffer(frames: Int, l: Float, r: Float): java.nio.ByteBuffer {
        val buf = java.nio.ByteBuffer.allocateDirect(frames * 8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        repeat(frames) {
            buf.putFloat(l)
            buf.putFloat(r)
        }
        buf.flip()
        return buf
    }

    @Test
    fun `neutral chain is transparent within DC-blocker transient`() {
        val frames = 512
        // Deterministic 1 kHz sine pair (zero-mean AC): the always-on DC
        // blockers must pass this essentially untouched.
        val input = java.nio.ByteBuffer.allocateDirect(frames * 8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val expected = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val v = (0.25 * kotlin.math.sin(2.0 * Math.PI * 1000.0 * i / 48000.0)).toFloat()
            expected[i * 2] = v
            expected[i * 2 + 1] = -v
            input.putFloat(v)
            input.putFloat(-v)
        }
        input.flip()

        processor.queueInput(input)

        val out = processor.output
        assertEquals(frames * 8, out.remaining())
        var maxDeviation = 0.0f
        val head = StringBuilder()
        for (i in 0 until frames * 2) {
            val got = out.float
            if (i < 8) head.append(got).append(',')
            maxDeviation = maxOf(maxDeviation, kotlin.math.abs(got - expected[i]))
        }
        // Two cascaded low-frequency HPFs add sub-ppm gain error at 1 kHz
        // plus a small (~0.2%) turn-on transient while their states settle.
        // Genuine colouration (pre-amp, exciter, dither, shelves) exceeds
        // this bound by orders of magnitude; the exact-silence test below is
        // the sharp tripwire for injected noise.
        assertTrue("head=$head max deviation $maxDeviation exceeded bound", maxDeviation < 5e-3f)
    }

    @Test
    fun `bit-perfect bypass is exact passthrough`() {
        processor.isBitPerfectBypass = true
        val frames = 256
        // Include values that would trip clamps/dither if processing ran.
        val buf = java.nio.ByteBuffer.allocateDirect(frames * 8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val inputs = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val v = ((i % 16) / 16f - 0.5f) * 1.8f   // swings beyond ±1.0
            inputs[i * 2] = v
            inputs[i * 2 + 1] = -v
            buf.putFloat(v); buf.putFloat(-v)
        }
        buf.flip()

        processor.queueInput(buf)

        val out = processor.output
        assertEquals(frames * 8, out.remaining())
        for (i in 0 until frames * 2) {
            assertEquals("sample $i", inputs[i], out.float, 0.0f)
        }
    }

    @Test
    fun `silence stays bit-exact silent with neutral settings`() {
        // With ditherStrength defaulting to 0 and all stages neutral, digital
        // silence must remain EXACTLY zero - this fails if any unconditional
        // dither/noise injection ever regresses.
        val frames = 2048
        val outBuf = run {
            val input = frameBuffer(frames, 0.0f, 0.0f)
            System.err.println(
                "DBG currentSampleRate=" + processor.currentSampleRate +
                    " remaining=" + input.remaining()
            )
            processor.queueInput(input)
            processor.output
        }
        var peak = 0.0f
        while (outBuf.hasRemaining()) {
            peak = maxOf(peak, kotlin.math.abs(outBuf.float))
        }
        assertEquals("silence must stay silent", 0.0f, peak, 0.0f)
    }
}
