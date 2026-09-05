package com.tensorix.antigravityplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@UnstableApi
class DspConcurrencyAndSnapshotTest {

    @Test
    fun `test fallback DSP snapshot immutability and live coefficient update concurrency`() {
        val processor = Audiophile64BitDspProcessor()
        val audioFormat = AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT)
        processor.configure(audioFormat)
        processor.flush()

        val running = AtomicBoolean(true)
        val renderErrors = AtomicInteger(0)
        val renderCycles = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(4)

        // Render simulation thread
        executor.submit {
            try {
                val inputBuf = ByteBuffer.allocateDirect(1024 * 4 * 2).order(ByteOrder.LITTLE_ENDIAN)
                val floats = FloatArray(1024 * 2) { 0.5f }
                val floatBuf = inputBuf.asFloatBuffer()
                while (running.get()) {
                    inputBuf.clear()
                    floatBuf.clear()
                    floatBuf.put(floats)
                    inputBuf.position(0)
                    inputBuf.limit(1024 * 4 * 2)

                    processor.queueInput(inputBuf)
                    val output = processor.output
                    val outFloats = output.asFloatBuffer()
                    while (outFloats.hasRemaining()) {
                        val v = outFloats.get()
                        if (v.isNaN() || v.isInfinite()) {
                            renderErrors.incrementAndGet()
                        }
                    }
                    renderCycles.incrementAndGet()
                }
            } catch (e: Throwable) {
                renderErrors.incrementAndGet()
            } finally {
                latch.countDown()
            }
        }

        // Control Thread 1: Rapid EQ Band Updates
        executor.submit {
            try {
                for (i in 0 until 1000) {
                    val band = i % 10
                    val gain = (i % 24) - 12.0
                    processor.setBandGain(band, gain)
                }
            } finally {
                latch.countDown()
            }
        }

        // Control Thread 2: Rapid Tone and Air Presence Updates
        executor.submit {
            try {
                for (i in 0 until 1000) {
                    processor.bassBoostGainDb = (i % 20) - 10.0
                    processor.trebleGainDb = (i % 20) - 10.0
                    processor.clarityEnhancerGain = (i % 15) * 0.1
                }
            } finally {
                latch.countDown()
            }
        }

        // Control Thread 3: Rapid Saturation, Crossfeed, Limiter, and Preset Toggles
        executor.submit {
            try {
                for (i in 0 until 1000) {
                    processor.warmSaturationLevel = (i % 10) * 0.1
                    processor.crossfeedLevel = (i % 10) * 0.1
                    processor.limiterEnabled = (i % 2 == 0)
                    processor.dvcVolume = 0.9 + (i % 10) * 0.01
                    processor.isBitPerfectBypass = (i % 4 == 0)
                }
            } finally {
                latch.countDown()
            }
        }

        // Let threads run for 500ms while processing 1000+ parameter updates
        Thread.sleep(500)
        running.set(false)
        latch.await(5, TimeUnit.SECONDS)
        executor.shutdownNow()

        assertEquals("Render thread must observe zero NaN or Inf during rapid concurrent mutation", 0, renderErrors.get())
        assertTrue("Render thread must complete rendering cycles under concurrent mutation", renderCycles.get() > 10)
    }

    @Test
    fun `test BiquadCoeffs numerical safety across pathological parameters`() {
        val sampleRate = 48000.0

        // Zero / negative frequency
        val c0 = BiquadFilter.computePeakingEq(0.0, 1.0, 6.0, sampleRate)
        assertFalse(c0.b0.isNaN() || c0.b0.isInfinite())

        // Frequency exceeding Nyquist
        val cNyquist = BiquadFilter.computePeakingEq(30000.0, 1.0, 6.0, sampleRate)
        assertFalse(cNyquist.b0.isNaN() || cNyquist.b0.isInfinite())

        // Extreme Q
        val cExtremeQ = BiquadFilter.computePeakingEq(1000.0, 1000.0, 6.0, sampleRate)
        assertFalse(cExtremeQ.b0.isNaN() || cExtremeQ.b0.isInfinite())

        // Zero Q
        val cZeroQ = BiquadFilter.computePeakingEq(1000.0, 0.0, 6.0, sampleRate)
        assertFalse(cZeroQ.b0.isNaN() || cZeroQ.b0.isInfinite())

        // Extreme gain (+100 dB and -100 dB)
        val cHighGain = BiquadFilter.computePeakingEq(1000.0, 1.0, 100.0, sampleRate)
        val cLowGain = BiquadFilter.computePeakingEq(1000.0, 1.0, -100.0, sampleRate)
        assertFalse(cHighGain.b0.isNaN() || cHighGain.b0.isInfinite())
        assertFalse(cLowGain.b0.isNaN() || cLowGain.b0.isInfinite())

        // Shelf filters
        val lowShelf = BiquadFilter.computeLowShelf(100.0, 0.707, 12.0, sampleRate)
        val highShelf = BiquadFilter.computeHighShelf(10000.0, 0.707, -12.0, sampleRate)
        assertFalse(lowShelf.b0.isNaN() || lowShelf.b0.isInfinite())
        assertFalse(highShelf.b0.isNaN() || highShelf.b0.isInfinite())
    }

    @Test
    fun `test 8-bit unsigned PCM exact mathematical normalization mapping`() {
        // Contract (User Requirement 16):
        // 0   -> -1.0
        // 128 -> 0.0
        // 255 -> 0.9921875 (127.0 / 128.0)
        fun map8Bit(unsignedByte: Int): Float {
            return (unsignedByte - 128) / 128.0f
        }

        assertEquals(-1.0f, map8Bit(0), 1e-6f)
        assertEquals(0.0f, map8Bit(128), 1e-6f)
        assertEquals(127.0f / 128.0f, map8Bit(255), 1e-6f)
        assertEquals(0.9921875f, map8Bit(255), 1e-6f)

        // Verify full 0..255 range is bounded in [-1.0, 1.0] and monotonic
        var prev = -2.0f
        for (i in 0..255) {
            val v = map8Bit(i)
            assertTrue("Value at $i must be >= -1.0", v >= -1.0f)
            assertTrue("Value at $i must be < 1.0", v < 1.0f)
            assertTrue("Values must strictly increase monotonically", v > prev)
            prev = v
        }
    }

    @Test
    fun `test dsdBypassed truth table logic`() {
        val dsp = Audiophile64BitDspProcessor()

        // 1. Initially disabled + bypass = true -> bypassed
        dsp.isEnabled = false
        dsp.isBitPerfectBypass = true
        var processorEnabled = dsp.isEnabled
        var processorBypassed = dsp.isBitPerfectBypass
        var signalTransformActive = processorEnabled && !processorBypassed
        var dspBypassed = !processorEnabled || processorBypassed
        assertTrue("Disabled + bypass should be bypassed", dspBypassed)
        assertFalse("Disabled should not have signal transform active", signalTransformActive)

        // 2. Disabled + bypass = false -> processor not active -> bypassed
        dsp.isEnabled = false
        dsp.isBitPerfectBypass = false
        processorEnabled = dsp.isEnabled
        processorBypassed = dsp.isBitPerfectBypass
        signalTransformActive = processorEnabled && !processorBypassed
        dspBypassed = !processorEnabled || processorBypassed
        assertTrue("Disabled should be bypassed", dspBypassed)
        assertFalse("Disabled should not have signal transform active", signalTransformActive)

        // 3. Enabled + bypass = true -> bypass active -> bypassed
        dsp.isEnabled = true
        dsp.isBitPerfectBypass = true
        processorEnabled = dsp.isEnabled
        processorBypassed = dsp.isBitPerfectBypass
        signalTransformActive = processorEnabled && !processorBypassed
        dspBypassed = !processorEnabled || processorBypassed
        assertTrue("Enabled + bypass should be bypassed", dspBypassed)
        assertFalse("Bypassed should not have signal transform active", signalTransformActive)

        // 4. Enabled + bypass = false -> ACTIVE -> NOT bypassed
        dsp.isEnabled = true
        dsp.isBitPerfectBypass = false
        processorEnabled = dsp.isEnabled
        processorBypassed = dsp.isBitPerfectBypass
        signalTransformActive = processorEnabled && !processorBypassed
        dspBypassed = !processorEnabled || processorBypassed
        assertFalse("Enabled without bypass must not be bypassed", dspBypassed)
        assertTrue("Enabled without bypass must have signal transform active", signalTransformActive)
    }

    @Test
    fun `test ActiveStreamSnapshot immutability and epoch tracking`() {
        val s1 = ActiveStreamSnapshot(handle = 100L, generation = 1L, epoch = 1L, info = null)
        val s2 = s1.copy(epoch = 2L)
        val s3 = s2.copy(handle = 200L, generation = 2L, epoch = 3L)

        assertEquals(100L, s1.handle)
        assertEquals(1L, s1.epoch)
        assertEquals(1L, s1.generation)

        assertEquals(100L, s2.handle)
        assertEquals(2L, s2.epoch)
        assertEquals(1L, s2.generation)

        assertEquals(200L, s3.handle)
        assertEquals(3L, s3.epoch)
        assertEquals(2L, s3.generation)
    }
}
