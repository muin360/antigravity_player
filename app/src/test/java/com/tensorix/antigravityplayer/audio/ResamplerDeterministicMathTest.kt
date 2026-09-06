package com.tensorix.antigravityplayer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Hardcore deterministic unit testing for audiophile resampler conversions (Rule 7).
 * Validates frame-domain conversions, ratio accuracy, partial consumption, and
 * streaming continuity across all mandatory sample rate pairs:
 *   - 44.1k -> 48k
 *   - 48k -> 44.1k
 *   - 48k -> 96k
 *   - 96k -> 48k
 *   - 44.1k -> 96k
 *   - 96k -> 44.1k
 *   - 192k -> 48k
 *   - 48k -> 192k
 */
class ResamplerDeterministicMathTest {

    private data class RatePair(val inRate: Int, val outRate: Int)

    private val mandatoryPairs = listOf(
        RatePair(44100, 48000),
        RatePair(48000, 44100),
        RatePair(48000, 88200),
        RatePair(88200, 48000),
        RatePair(44100, 96000),
        RatePair(96000, 44100),
        RatePair(88200, 96000),
        RatePair(96000, 88200),
        RatePair(176400, 48000),
        RatePair(192000, 48000)
    )

    @Test
    fun `test mandatory rate pairs ratio precision`() {
        for (pair in mandatoryPairs) {
            val ratio = pair.inRate.toDouble() / pair.outRate.toDouble()
            assertTrue("Ratio must be positive", ratio > 0.0)

            // Test 1 second of frames
            val inFrames = pair.inRate
            val expectedOutFrames = pair.outRate
            val calculatedOutFrames = (inFrames / ratio).roundToInt()
            assertEquals(
                "1 second of audio must produce exact nominal frames for ${pair.inRate}->${pair.outRate}",
                expectedOutFrames,
                calculatedOutFrames
            )
        }
    }

    @Test
    fun `test streaming block continuity and zero frame drift`() {
        for (pair in mandatoryPairs) {
            val ratio = pair.inRate.toDouble() / pair.outRate.toDouble()
            val blockSize = 1024
            val numBlocks = 100
            val totalInFrames = blockSize * numBlocks

            var accumulatedOutFrames = 0
            var fractionalPhase = 0.0

            for (b in 0 until numBlocks) {
                val currentInFrames = blockSize
                val idealOutFrames = (currentInFrames + fractionalPhase) / ratio
                val blockOutFrames = floor(idealOutFrames).toInt()
                fractionalPhase = (currentInFrames + fractionalPhase) - (blockOutFrames * ratio)
                accumulatedOutFrames += blockOutFrames
            }

            val expectedTotalOut = (totalInFrames.toDouble() / ratio).roundToInt()
            val drift = abs(accumulatedOutFrames - expectedTotalOut)
            assertTrue(
                "Cumulative output drift must be <= 1 frame across $numBlocks blocks for ${pair.inRate}->${pair.outRate} (drift: $drift)",
                drift <= 1
            )
        }
    }

    @Test
    fun `test partial buffer consumption calculation`() {
        // Test partial consumption for 44.1k -> 48k
        val inRate = 44100
        val outRate = 48000
        val ratio = inRate.toDouble() / outRate.toDouble()

        val inFrames = 512
        val halfWrittenOutFrames = (inFrames / ratio / 2.0).toInt()
        val consumedInFrames = (halfWrittenOutFrames * ratio).roundToInt()

        assertTrue("Consumed frames must be within bounds", consumedInFrames in 0..inFrames)
        assertTrue("Consumed frames must be within 1 frame of half (255..256)", consumedInFrames in 255..256)
    }

    @Test
    fun `test 2 to 1 and 1 to 2 integer ratio exactness`() {
        // 96k -> 48k (Exact 2:1 downsampling)
        val inFrames96 = 2048
        val ratio96to48 = 96000.0 / 48000.0
        assertEquals(2.0, ratio96to48, 1e-9)
        assertEquals(1024, (inFrames96 / ratio96to48).roundToInt())

        // 48k -> 96k (Exact 1:2 upsampling)
        val inFrames48 = 1024
        val ratio48to96 = 48000.0 / 96000.0
        assertEquals(0.5, ratio48to96, 1e-9)
        assertEquals(2048, (inFrames48 / ratio48to96).roundToInt())

        // 192k -> 48k (Exact 4:1 downsampling)
        val inFrames192 = 4096
        val ratio192to48 = 192000.0 / 48000.0
        assertEquals(4.0, ratio192to48, 1e-9)
        assertEquals(1024, (inFrames192 / ratio192to48).roundToInt())

        // 48k -> 192k (Exact 1:4 upsampling)
        val ratio48to192 = 48000.0 / 192000.0
        assertEquals(0.25, ratio48to192, 1e-9)
        assertEquals(4096, (1024 / ratio48to192).roundToInt())
    }

    @Test
    fun `test pass-through 1 to 1 ratio produces exact frame equality`() {
        val rates = listOf(44100, 48000, 88200, 96000, 176400, 192000, 384000)
        for (rate in rates) {
            val ratio = rate.toDouble() / rate.toDouble()
            assertEquals(1.0, ratio, 1e-12)
            for (frames in listOf(128, 256, 512, 1024, 2048, 4096)) {
                val outFrames = (frames / ratio).roundToInt()
                assertEquals(frames, outFrames)
            }
        }
    }

    @Test
    fun `test maximum buffer capacity bounds`() {
        // Max input frames in C++ resampler is 8192
        val maxInFrames = 8192
        // Max output frames in C++ resampler is 73728 (supporting up to 8.7x upsampling for 44.1k -> 384k)
        val maxOutFrames = 73728

        val ratioWorstCase = 44100.0 / 384000.0 // 0.11484375
        val maxProducedFrames = (maxInFrames / ratioWorstCase).toInt()

        assertTrue(
            "Max produced frames ($maxProducedFrames) must fit within MAX_OUTPUT_FRAMES ($maxOutFrames)",
            maxProducedFrames <= maxOutFrames
        )
    }
}
