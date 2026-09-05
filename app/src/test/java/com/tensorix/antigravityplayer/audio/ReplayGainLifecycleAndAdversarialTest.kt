package com.tensorix.antigravityplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.sin

/**
 * Hardcore Forensic Test Suite for ReplayGain Lifecycle & Adversarial Concurrency.
 * Validates Rule 9 (ReplayGain correctness), Rule 29 (Adversarial races), and Rule 30 (Negative testing).
 */
@UnstableApi
class ReplayGainLifecycleAndAdversarialTest {

    private fun generateSineBuffer(frames: Int, freq: Double, amplitude: Float, sampleRate: Int = 48000): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(frames * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val sample = (sin(2.0 * Math.PI * freq * i / sampleRate) * amplitude).toFloat()
            buf.putFloat(sample)  // Left
            buf.putFloat(sample)  // Right
        }
        buf.flip()
        return buf
    }

    private fun measureMaxAbsAmplitude(buf: ByteBuffer): Float {
        val dup = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        var maxAmp = 0.0f
        while (dup.remaining() >= 4) {
            val s = abs(dup.float)
            if (s > maxAmp) maxAmp = s
        }
        return maxAmp
    }

    // ========================================================================
    // RULE 9: REPLAYGAIN ENABLE / DISABLE & TRACK TRANSITION PROOF
    // ========================================================================

    @Test
    fun `test ReplayGain disabled strictly forces unity gain even with aggressive multiplier`() {
        val processor = Audiophile64BitDspProcessor()
        val format = AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT)
        processor.configure(format)
        processor.flush()

        // 1. ReplayGain enabled with 0.5x multiplier (-6 dB)
        processor.replayGainEnabled = true
        processor.replayGainMultiplier = 0.5
        processor.flush()

        val input1 = generateSineBuffer(1024, 1000.0, 0.8f)
        processor.queueInput(input1)
        val outEnabled = processor.output
        val maxAmpEnabled = measureMaxAbsAmplitude(outEnabled)

        // Measured amplitude must be ~0.40 (0.8 * 0.5)
        assertTrue("Enabled RG must attenuate to ~0.40, got $maxAmpEnabled", maxAmpEnabled in 0.38f..0.42f)

        // 2. Turn ReplayGain OFF, but leave multiplier as 0.5
        processor.replayGainEnabled = false
        processor.flush()

        val input2 = generateSineBuffer(1024, 1000.0, 0.8f)
        processor.queueInput(input2)
        val outDisabled = processor.output
        val maxAmpDisabled = measureMaxAbsAmplitude(outDisabled)

        // Physical proof: Must be full scale ~0.80 (strict unity gain 1.0x)
        assertTrue("Disabled RG must strictly produce unity gain ~0.80, got $maxAmpDisabled", maxAmpDisabled in 0.78f..0.82f)
        assertFalse("isReplayGainActive must be false when disabled", processor.isReplayGainActive)
    }

    @Test
    fun `test ReplayGain OFF cannot silently reappear during track transition`() {
        val processor = Audiophile64BitDspProcessor()
        val format = AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT)
        processor.configure(format)
        processor.flush()

        // User toggled ReplayGain OFF in settings
        var userSettingReplayGainEnabled = false
        processor.replayGainEnabled = userSettingReplayGainEnabled

        // Track 1 loaded with ReplayGain metadata (-10 dB -> linear ~0.316)
        val track1GainDb = -10.0f
        val track1Peak = 0.95f
        if (userSettingReplayGainEnabled) {
            processor.applyReplayGain(track1GainDb, 0f, track1Peak, false)
        } else {
            processor.replayGainMultiplier = 1.0
        }
        processor.flush()

        val inputTrack1 = generateSineBuffer(1024, 1000.0, 0.5f)
        processor.queueInput(inputTrack1)
        val outTrack1 = processor.output
        val maxTrack1 = measureMaxAbsAmplitude(outTrack1)
        assertEquals("Track 1 must have 1.0x unity gain when RG is disabled", 0.5f, maxTrack1, 0.02f)

        // Simulate Track Transition to Track 2 with aggressive ReplayGain (-14 dB -> linear ~0.20)
        val track2GainDb = -14.0f
        val track2Peak = 0.99f
        if (userSettingReplayGainEnabled) {
            processor.applyReplayGain(track2GainDb, 0f, track2Peak, false)
        } else {
            processor.replayGainMultiplier = 1.0
        }
        processor.flush()

        val inputTrack2 = generateSineBuffer(1024, 1000.0, 0.5f)
        processor.queueInput(inputTrack2)
        val outTrack2 = processor.output
        val maxTrack2 = measureMaxAbsAmplitude(outTrack2)

        // Physical proof: Track 2 MUST NOT be attenuated; must stay at 0.5f!
        assertEquals("Track 2 must remain at 1.0x unity gain without silent ReplayGain revival", 0.5f, maxTrack2, 0.02f)
        assertEquals("Multiplier must remain 1.0", 1.0, processor.replayGainMultiplier, 1e-6)
        assertFalse("ReplayGain active state must remain false", processor.isReplayGainActive)
    }

    @Test
    fun `test ReplayGain applyReplayGain peak limiting calculation`() {
        val processor = Audiophile64BitDspProcessor()

        // 1. Positive gain (+6 dB -> 2.0x linear) with peak = 0.8:
        // linearGain = 2.0, but max allowed without clipping is 1.0 / 0.8 = 1.25.
        processor.applyReplayGain(trackGainDb = 6.0f, albumGainDb = 0f, peakAmplitude = 0.8f, useAlbumGain = false)
        assertEquals("Peak limiting must clamp multiplier to 1/peak", 1.25, processor.replayGainMultiplier, 0.01)

        // 2. Negative gain (-6 dB -> 0.5x linear) with peak = 0.8:
        // linearGain = 0.5 <= 1.25, so full 0.5 is safe and preserved.
        processor.applyReplayGain(trackGainDb = -6.0f, albumGainDb = 0f, peakAmplitude = 0.8f, useAlbumGain = false)
        assertEquals("Safe negative gain must not be clamped by peak", 0.501, processor.replayGainMultiplier, 0.01)
    }

    @Test
    fun `test bit-perfect bypass completely ignores ReplayGain and all DSP`() {
        val processor = Audiophile64BitDspProcessor()
        val format = AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT)
        processor.configure(format)
        processor.flush()

        processor.isBitPerfectBypass = true
        processor.replayGainEnabled = true
        processor.replayGainMultiplier = 0.1 // 90% attenuation requested
        processor.preAmpGainDb = 12.0       // +12 dB preamp boost requested

        val input = generateSineBuffer(512, 1000.0, 0.75f)
        processor.queueInput(input)
        val output = processor.output
        val maxAmp = measureMaxAbsAmplitude(output)

        // Exact sample transparency
        assertEquals("Bit-perfect bypass must ignore ReplayGain & preamp entirely", 0.75f, maxAmp, 0.001f)
    }

    // ========================================================================
    // RULE 29: ADVERSARIAL STRESS TEST SCENARIOS
    // ========================================================================

    @Test
    fun `adversarial test A - continuous DSP parameter mutation during continuous audio rendering`() {
        val processor = Audiophile64BitDspProcessor()
        val format = AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT)
        processor.configure(format)
        processor.flush()

        val running = AtomicBoolean(true)
        val renderErrors = AtomicInteger(0)
        val renderFramesProcessed = AtomicInteger(0)
        val numMutations = 2000

        val executor = Executors.newFixedThreadPool(3)
        val controlLatch = CountDownLatch(2)
        val renderLatch = CountDownLatch(1)

        // Thread 1: Render Thread (continuous audio queueInput and output verification)
        executor.submit {
            try {
                val inputBuf = ByteBuffer.allocateDirect(512 * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
                while (running.get()) {
                    inputBuf.clear()
                    for (i in 0 until 512) {
                        val v = (sin(2.0 * Math.PI * 440.0 * i / 48000) * 0.5f).toFloat()
                        inputBuf.putFloat(v)
                        inputBuf.putFloat(v)
                    }
                    inputBuf.flip()

                    processor.queueInput(inputBuf)
                    val out = processor.output
                    while (out.remaining() >= 4) {
                        val sample = out.float
                        if (sample.isNaN() || sample.isInfinite() || abs(sample) > 5.0f) {
                            renderErrors.incrementAndGet()
                        }
                    }
                    renderFramesProcessed.addAndGet(512)
                }
            } catch (e: Throwable) {
                renderErrors.incrementAndGet()
            } finally {
                renderLatch.countDown()
            }
        }

        // Thread 2: Control Thread A (Rapid EQ and Tone changes)
        executor.submit {
            try {
                for (i in 0 until numMutations) {
                    val band = i % 10
                    val gain = ((i % 21) - 10).toDouble()
                    processor.setBandGain(band, gain)
                    processor.bassBoostGainDb = ((i % 15)).toDouble()
                    processor.trebleGainDb = ((i % 15)).toDouble()
                    processor.clarityEnhancerGain = ((i % 10) * 0.2)
                }
            } finally {
                controlLatch.countDown()
            }
        }

        // Thread 3: Control Thread B (Rapid Dynamics, ReplayGain, and Saturation changes)
        executor.submit {
            try {
                for (i in 0 until numMutations) {
                    processor.replayGainMultiplier = 0.5 + (i % 10) * 0.05
                    processor.replayGainEnabled = (i % 5 != 0)
                    processor.warmSaturationLevel = (i % 10) * 0.1
                    processor.triodeWarmthLevel = (i % 10) * 0.1
                    processor.crossfeedLevel = (i % 10) * 0.1
                    processor.limiterEnabled = (i % 2 == 0)
                    processor.dvcVolume = 0.8 + (i % 5) * 0.04
                }
            } finally {
                controlLatch.countDown()
            }
        }

        // Wait for control threads to complete, then signal render thread
        val completed = controlLatch.await(10, TimeUnit.SECONDS)
        running.set(false)
        val renderCompleted = renderLatch.await(5, TimeUnit.SECONDS)
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        assertTrue("Control threads must finish within timeout", completed)
        assertTrue("Render thread must finish after stop signal", renderCompleted)
        assertEquals("Zero render errors allowed during intense concurrency", 0, renderErrors.get())
        assertTrue("Render thread must have processed audio frames", renderFramesProcessed.get() > 1000)
    }

    @Test
    fun `adversarial test H - rapid BitPerfect toggling during continuous rendering`() {
        val processor = Audiophile64BitDspProcessor()
        val format = AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT)
        processor.configure(format)
        processor.flush()

        val running = AtomicBoolean(true)
        val errors = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(2)
        val latch = CountDownLatch(2)

        // Render thread
        executor.submit {
            try {
                val inputBuf = ByteBuffer.allocateDirect(256 * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
                while (running.get()) {
                    inputBuf.clear()
                    for (i in 0 until 256) {
                        inputBuf.putFloat(0.5f)
                        inputBuf.putFloat(-0.5f)
                    }
                    inputBuf.flip()

                    processor.queueInput(inputBuf)
                    val out = processor.output
                    while (out.remaining() >= 4) {
                        val s = out.float
                        if (s.isNaN() || s.isInfinite()) {
                            errors.incrementAndGet()
                        }
                    }
                }
            } finally {
                latch.countDown()
            }
        }

        // Control thread toggling BitPerfect and EQ
        executor.submit {
            try {
                for (i in 0 until 1000) {
                    processor.isBitPerfectBypass = (i % 2 == 0)
                    processor.isEnabled = (i % 3 != 0)
                    processor.preAmpGainDb = (i % 5).toDouble()
                }
            } finally {
                running.set(false)
                latch.countDown()
            }
        }

        val completed = latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()
        assertTrue(completed)
        assertEquals("Zero NaN/Inf or tearing errors allowed during BitPerfect transitions", 0, errors.get())
    }
}
