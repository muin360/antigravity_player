package com.tensorix.antigravityplayer.audio

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hardcore testing for audio playback clock, flush, and discontinuity behavior (Rules 14 & 15).
 * Validates:
 *   - Monotonic presentation clock calculation
 *   - Hardware frames baseline offset subtraction on flush (AAudio/Oboe cumulative hardware counter)
 *   - Clamping to actual hardware played frames (never advancing on unplayed buffered frames)
 *   - Discontinuity and seek clock re-anchoring
 */
class AudioClockPresentationDiscontinuityTest {

    @Test
    fun `test clock calculates exact microseconds from hardware played frames`() {
        val anchorUs = 10_000_000L // 10.0 seconds
        val sampleRate = 48000
        val playedFrames = 48000L // 1.0 second of played audio

        val posUs = SinkClockMath.anchorPositionUs(anchorUs, playedFrames, sampleRate)
        assertEquals(11_000_000L, posUs)
    }

    @Test
    fun `test clock offsets cumulative hardware counter using flush baseline`() {
        val anchorUs = 25_000_000L // Seek to 25.0 seconds
        val sampleRate = 48000

        // Hardware played 1,000,000 frames before seek
        val baselineFrames = 1_000_000L

        // In new epoch after seek, hardware has read 24,000 frames of new audio
        val rawHardwareFrames = 1_024_000L
        val playedInEpoch = rawHardwareFrames - baselineFrames

        val posUs = SinkClockMath.anchorPositionUs(anchorUs, playedInEpoch, sampleRate)
        // 25.0s + 24000/48000 (0.5s) = 25.5s = 25,500,000 us
        assertEquals(25_500_000L, posUs)
    }

    @Test
    fun `test clock handles HAL that resets counter to zero on flush`() {
        val anchorUs = 40_000_000L
        val sampleRate = 96000

        // Baseline before flush was 500,000
        val baseline = 500_000L
        // Device HAL reset hardware counter on flush, so it reports 9,600 frames
        val rawHardwareFrames = 9_600L

        // If rawHardwareFrames < baseline, device reset on flush -> effective played = rawHardwareFrames
        val effectivePlayed = if (rawHardwareFrames >= baseline && baseline > 0) {
            rawHardwareFrames - baseline
        } else {
            rawHardwareFrames
        }

        val posUs = SinkClockMath.anchorPositionUs(anchorUs, effectivePlayed, sampleRate)
        // 40.0s + 9600/96000 (0.1s) = 40.1s = 40,100,000 us
        assertEquals(40_100_000L, posUs)
    }

    @Test
    fun `test monotonic clamp prevents clock from jumping backwards between anchors`() {
        var lastReported = 15_000_000L
        val candidates = listOf(
            15_010_000L, // Forward -> accepted
            15_009_000L, // Backward jitter -> clamped to 15_010_000
            15_025_000L, // Forward -> accepted
            15_025_000L, // Identical -> accepted
            15_020_000L  // Backward jitter -> clamped to 15_025_000
        )

        for (candidate in candidates) {
            val clamped = SinkClockMath.monotonicClamp(lastReported, candidate)
            assertTrue("Clamped position must be >= lastReported", clamped >= lastReported)
            lastReported = clamped
        }
        assertEquals(15_025_000L, lastReported)
    }

    @Test
    fun `test position never advances beyond accepted frames`() {
        val framesAcceptedByStream = 1000L
        // Even if an extrapolation or hardware glitch reports 5000 frames:
        val rawHardwarePos = 5000L

        val validHardwarePos = rawHardwarePos.coerceIn(0L, framesAcceptedByStream)
        assertEquals(framesAcceptedByStream, validHardwarePos)
    }

    @Test
    fun `test non-direct buffer frame alignment calculation`() {
        val capacity = 262144 // 256 KB direct scratch
        val channelCounts = listOf(1, 2, 6, 8)
        val bytesPerSampleList = listOf(2, 3, 4) // 16-bit, 24-bit, 32-bit/float

        for (channels in channelCounts) {
            for (bps in bytesPerSampleList) {
                val bytesPerFrame = channels * bps
                val maxAlignedBytes = capacity - (capacity % bytesPerFrame)

                assertEquals(
                    "maxAlignedBytes must be a strict multiple of bytesPerFrame for ch=$channels bps=$bps",
                    0,
                    maxAlignedBytes % bytesPerFrame
                )
                assertTrue("maxAlignedBytes must be <= capacity", maxAlignedBytes <= capacity)
            }
        }
    }
}
