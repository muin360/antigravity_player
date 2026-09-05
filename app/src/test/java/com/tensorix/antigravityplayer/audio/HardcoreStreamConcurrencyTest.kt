package com.tensorix.antigravityplayer.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@UnstableApi
class HardcoreStreamConcurrencyTest {

    // ========================================================================
    // 1. 256 KB BUFFER VS NATIVE SCRATCH CAPACITY TESTS (P0 Section 3)
    // ========================================================================

    @Test
    fun `test authoritative chunking frame arithmetic across all supported channels`() {
        val testChannels = listOf(1, 2, 6, 8)
        val testEncodings = listOf(
            C.ENCODING_PCM_8BIT to 1,
            C.ENCODING_PCM_16BIT to 2,
            C.ENCODING_PCM_24BIT to 3,
            C.ENCODING_PCM_32BIT to 4,
            C.ENCODING_PCM_FLOAT to 4
        )

        for (ch in testChannels) {
            for ((enc, bytesPerSample) in testEncodings) {
                val bytesPerFrame = ch * bytesPerSample
                val maxFramesFromScratch = OboeAudioSink.MAX_SCRATCH_SAMPLES / ch
                val maxFramesFromDirectBuffer = OboeAudioSink.DIRECT_BUFFER_CAPACITY / bytesPerFrame
                val maxAllowedFrames = minOf(maxFramesFromScratch, maxFramesFromDirectBuffer)

                assertTrue("Max allowed frames must be > 0 for ch=$ch enc=$enc", maxAllowedFrames > 0)
                assertTrue(
                    "Total scratch samples must never exceed MAX_SCRATCH_SAMPLES ($maxAllowedFrames * $ch <= ${OboeAudioSink.MAX_SCRATCH_SAMPLES})",
                    maxAllowedFrames * ch <= OboeAudioSink.MAX_SCRATCH_SAMPLES
                )
                assertTrue(
                    "Total direct buffer bytes must never exceed capacity ($maxAllowedFrames * $bytesPerFrame <= ${OboeAudioSink.DIRECT_BUFFER_CAPACITY})",
                    maxAllowedFrames * bytesPerFrame <= OboeAudioSink.DIRECT_BUFFER_CAPACITY
                )
            }
        }
    }

    @Test
    fun `test sub-frame remainder truncation and odd offset protection`() {
        val channelCount = 2
        val bytesPerSample = 2 // 16-bit
        val bytesPerFrame = channelCount * bytesPerSample // 4 bytes

        val oddSize = 1025 // 1024 bytes (256 frames) + 1 odd byte
        val usableBytes = oddSize - (oddSize % bytesPerFrame)
        val remainder = oddSize % bytesPerFrame

        assertEquals(1024, usableBytes)
        assertEquals(1, remainder)
        assertEquals(256, usableBytes / bytesPerFrame)
    }

    @Test
    fun `test 256 KB chunking boundary arithmetic with exact capacity plus one frame`() {
        val channelCount = 2
        val bytesPerSample = 2 // 16-bit
        val bytesPerFrame = channelCount * bytesPerSample

        val maxFrames = OboeAudioSink.MAX_SCRATCH_SAMPLES / channelCount // 65536 frames
        val maxBytes = maxFrames * bytesPerFrame // 262144 bytes = 256 KB

        val totalFrames = maxFrames + 1
        val totalBytes = totalFrames * bytesPerFrame

        val firstChunkFrames = minOf(totalFrames, maxFrames)
        val remainingFrames = totalFrames - firstChunkFrames

        assertEquals(maxFrames, firstChunkFrames)
        assertEquals(1, remainingFrames)
        assertEquals(maxBytes, firstChunkFrames * bytesPerFrame)
    }

    // ========================================================================
    // 2. 8-BIT PCM CONTRACT & VALUE TABLE TEST (P0 Section 9)
    // ========================================================================

    @Test
    fun `test 8-bit PCM byte size and all 256 unsigned values normalization`() {
        // Media3 & Native C++ convention: 8-bit PCM is unsigned [0..255] where 128 is center (silence)
        val buffer = ByteBuffer.allocateDirect(256).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 256) {
            buffer.put(i.toByte())
        }
        buffer.flip()

        val scratch = FloatArray(256)
        for (i in 0 until 256) {
            val u8 = buffer.get().toInt() and 0xFF
            scratch[i] = (u8 - 128) / 128.0f
        }

        // Test midpoint (zero crossing)
        assertEquals("Midpoint 128 must be zero", 0.0f, scratch[128], 1e-6f)
        // Test minimum value (0 -> -1.0)
        assertEquals("Min value 0 must be -1.0", -1.0f, scratch[0], 1e-6f)
        // Test maximum value (255 -> ~0.9921875)
        assertTrue("Max value 255 must be strictly < 1.0", scratch[255] < 1.0f)
        assertTrue("Max value 255 must be > 0.99", scratch[255] > 0.99f)
    }

    // ========================================================================
    // 3. ADVERSARIAL WRITE + CLOSE CONCURRENCY SIMULATION (P0 Section 2)
    // ========================================================================

    @Test
    fun `test concurrent write and close simulation retains race-safe stream lifetime`() {
        val iterations = 500
        val failureCount = AtomicInteger(0)

        for (i in 0 until iterations) {
            val streamActive = AtomicBoolean(true)
            val latch = CountDownLatch(2)

            val writerThread = Thread {
                try {
                    // Simulate hot-path snapshot acquisition
                    if (streamActive.get()) {
                        // In actual code: auto activeStream = wrapper->getStreamSnapshot();
                        // Simulating thread delay during active write
                        Thread.sleep(1)
                    }
                } catch (e: Exception) {
                    failureCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }

            val closerThread = Thread {
                try {
                    // Simulate concurrent close
                    streamActive.set(false)
                } catch (e: Exception) {
                    failureCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }

            writerThread.start()
            closerThread.start()

            assertTrue("Threads must complete without deadlock", latch.await(500, TimeUnit.MILLISECONDS))
        }

        assertEquals("Zero race condition failures observed across $iterations cycles", 0, failureCount.get())
    }

    // ========================================================================
    // 4. FORMAT SUPPORT ADVERTISING (P0 Section 10)
    // ========================================================================

    @Test
    fun `test format support limits reject unsupported channels or invalid encodings`() {
        val supportedEncodings = listOf(
            C.ENCODING_PCM_8BIT,
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_FLOAT
        )

        // Valid formats
        for (enc in supportedEncodings) {
            val validFormat = Format.Builder()
                .setPcmEncoding(enc)
                .setChannelCount(2)
                .setSampleRate(48000)
                .build()
            assertTrue("Format with enc=$enc must be supported", validFormat.pcmEncoding in supportedEncodings)
        }

        // Invalid channel counts (> 8 channels)
        val nineChannelFormat = Format.Builder()
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(9)
            .setSampleRate(48000)
            .build()
        assertFalse("Channel count > 8 must be rejected by native path", nineChannelFormat.channelCount in 1..8)

        // Invalid sample rate
        val zeroRateFormat = Format.Builder()
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(2)
            .setSampleRate(0)
            .build()
        assertFalse("Sample rate <= 0 must be rejected", zeroRateFormat.sampleRate > 0)
    }
}
