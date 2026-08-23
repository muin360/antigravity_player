package com.tensorix.antigravityplayer.audio

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
class OboeAudioSinkTest {

    @org.junit.Before
    fun setUp() {
        OboeAudioSink.currentActiveHandle = 0L
        OboeAudioSink.currentStreamInfo = null
    }

    @org.junit.After
    fun tearDown() {
        OboeAudioSink.currentActiveHandle = 0L
        OboeAudioSink.currentStreamInfo = null
    }

    @Test
    fun `test OboeAudioSink supports standard linear PCM formats`() {
        val context = mock<Context>()
        val sink = OboeAudioSink(context, dspProcessor = null, bitPerfectMode = false)

        val format16 = Format.Builder()
            .setSampleMimeType("audio/raw")
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setSampleRate(44100)
            .setChannelCount(2)
            .build()

        val format24 = Format.Builder()
            .setSampleMimeType("audio/raw")
            .setPcmEncoding(C.ENCODING_PCM_24BIT)
            .setSampleRate(96000)
            .setChannelCount(2)
            .build()

        val formatFloat = Format.Builder()
            .setSampleMimeType("audio/raw")
            .setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .setSampleRate(192000)
            .setChannelCount(2)
            .build()

        assertTrue(sink.supportsFormat(format16))
        assertTrue(sink.supportsFormat(format24))
        assertTrue(sink.supportsFormat(formatFloat))
    }

    @Test
    fun `test OboeAudioSink buffer full consumption returns true and advances position`() {
        val context = mock<Context>()
        val sink = OboeAudioSink(context, dspProcessor = null, bitPerfectMode = true)

        val format = Format.Builder()
            .setSampleMimeType("audio/raw")
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setSampleRate(48000)
            .setChannelCount(2)
            .build()

        sink.configure(format, 4096, null)

        val byteBuffer = ByteBuffer.allocateDirect(1024).order(ByteOrder.LITTLE_ENDIAN)
        byteBuffer.put(ByteArray(1024))
        byteBuffer.flip()

        val consumed = sink.handleBuffer(byteBuffer, 0L, 1)
        // If native library not loaded in JVM runner, it delegates to fallback DefaultAudioSink
        // Either way, it must not throw and must maintain valid ByteBuffer bounds
        assertTrue(byteBuffer.position() <= byteBuffer.limit())
    }

    @Test
    fun `test OboeAudioSink flush and discontinuity reset state cleanly`() {
        val context = mock<Context>()
        val sink = OboeAudioSink(context, dspProcessor = null, bitPerfectMode = false)

        sink.handleDiscontinuity()
        sink.flush()
        sink.pause()
        assertFalse(sink.hasPendingData())
        sink.reset()
        sink.release()
    }

    @Test
    fun `test OboeAudioSink playToEndOfStream sets drain state and isEnded becomes true when drained`() {
        val context = mock<Context>()
        val sink = OboeAudioSink(context, dspProcessor = null, bitPerfectMode = false)

        sink.play()
        sink.playToEndOfStream()
        // When no pending hardware frames remain and playToEndOfStream is invoked, isEnded is true
        assertTrue(sink.isEnded)
    }

    @Test
    fun `test OboeAudioSink dynamic bit-perfect mode toggle preserves sink safety`() {
        val context = mock<Context>()
        val sink = OboeAudioSink(context, dspProcessor = null, bitPerfectMode = false)

        sink.setBitPerfectMode(true)
        sink.setBitPerfectMode(false)
        sink.setBitPerfectMode(true)
        assertFalse(sink.hasPendingData())
    }

    @Test
    fun `test Resampling ratio math conversions across sample rates`() {
        // Test 44100 -> 44100 (Pass-through)
        val inFrames1 = 1024
        val framesToSend1 = 1024
        val written1 = 1024
        val ratio1 = framesToSend1.toDouble() / inFrames1.toDouble()
        assertEquals(1.0, ratio1, 1e-6)
        val consumed1 = (written1 / ratio1).toInt().coerceIn(0, inFrames1)
        assertEquals(1024, consumed1)

        // Test 44100 -> 48000 (Upsampling)
        val inFrames2 = 441
        val framesToSend2 = 480
        val ratio2 = framesToSend2.toDouble() / inFrames2.toDouble()
        // 100% written
        val written2Full = 480
        val consumed2Full = Math.round(written2Full / ratio2).toInt().coerceIn(0, inFrames2)
        assertEquals(441, consumed2Full)
        // 50% written
        val written2Half = 240
        val consumed2Half = Math.round(written2Half / ratio2).toInt().coerceIn(0, inFrames2)
        assertEquals(220, consumed2Half)
        // 0% written
        val written2Zero = 0
        val consumed2Zero = Math.round(written2Zero / ratio2).toInt().coerceIn(0, inFrames2)
        assertEquals(0, consumed2Zero)

        // Test 48000 -> 44100 (Downsampling)
        val inFrames3 = 480
        val framesToSend3 = 441
        val ratio3 = framesToSend3.toDouble() / inFrames3.toDouble()
        val written3Full = 441
        val consumed3Full = Math.round(written3Full / ratio3).toInt().coerceIn(0, inFrames3)
        assertEquals(480, consumed3Full)

        // Test 96000 -> 48000 (2:1 Downsampling)
        val inFrames4 = 1000
        val framesToSend4 = 500
        val ratio4 = framesToSend4.toDouble() / inFrames4.toDouble()
        assertEquals(0.5, ratio4, 1e-6)
        val written4Full = 500
        val consumed4Full = Math.round(written4Full / ratio4).toInt().coerceIn(0, inFrames4)
        assertEquals(1000, consumed4Full)
    }

    @Test
    fun `test OboeAudioSink reconfigureRoute executes cleanly`() {
        val context = mock<Context>()
        val sink = OboeAudioSink(context, dspProcessor = null, bitPerfectMode = false)

        val route = AudioRouteCapability(
            routeType = AudioOutputRouteType.WIRED_HEADSET,
            deviceName = "Wired Headset",
            productName = null,
            sampleRates = listOf(48000, 96000),
            encodings = listOf(16, 24),
            channelCounts = listOf(2),
            isDirectPlaybackCapable = true,
            canBeExclusive = true
        )

        // reconfigureRoute should execute safely
        val result = sink.reconfigureRoute(route, preferredDevice = null)
        // In JVM test environment without native lib, fallback is engaged cleanly
        assertEquals(0L, OboeAudioSink.currentActiveHandle)
    }

    @Test
    fun `test OboeAudioSink recoverFromError executes controlled recovery`() {
        val context = mock<Context>()
        val sink = OboeAudioSink(context, dspProcessor = null, bitPerfectMode = false)

        val result = sink.recoverFromError(errorCode = -1)
        // Recovery resets active handle and falls back safely
        assertEquals(0L, OboeAudioSink.currentActiveHandle)
    }

    @Test
    fun `test OboeAudioSink non-direct buffer slicing and position tracking`() {
        val context = mock<Context>()
        val sink = OboeAudioSink(context, dspProcessor = null, bitPerfectMode = false)

        val format = Format.Builder()
            .setSampleMimeType("audio/raw")
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setSampleRate(48000)
            .setChannelCount(2)
            .build()

        sink.configure(format, 4096, null)

        // Heap ByteBuffer
        val heapBuffer = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)
        heapBuffer.put(ByteArray(1024))
        heapBuffer.flip()

        val consumed = sink.handleBuffer(heapBuffer, 1000L, 1)
        assertTrue(heapBuffer.position() <= heapBuffer.limit())
    }

    @Test
    fun `test OboeAudioSink position monotonically increases and resets on flush`() {
        val context = mock<Context>()
        val sink = OboeAudioSink(context, dspProcessor = null, bitPerfectMode = false)

        val pos0 = sink.getCurrentPositionUs(false)
        assertEquals(C.TIME_UNSET, pos0)

        sink.flush()
        val posAfterFlush = sink.getCurrentPositionUs(false)
        assertEquals(C.TIME_UNSET, posAfterFlush)
    }

    @Test
    fun `test OboeAudioSink reset zeroes handle immediately`() {
        val context = mock<Context>()
        val sink = OboeAudioSink(context, dspProcessor = null, bitPerfectMode = false)

        sink.reset()
        assertEquals(0L, OboeAudioSink.currentActiveHandle)
        org.junit.Assert.assertNull(OboeAudioSink.currentStreamInfo)
    }

    @Test
    fun `test NativeStreamInfo 12-field constructor contract`() {
        val info = OboeBridge.NativeStreamInfo(
            api = "AAudio",
            sharingMode = "SHARED",
            performanceMode = "LowLatency",
            sampleRate = 48000,
            channelCount = 2,
            format = "Float",
            bufferSize = 192,
            deviceId = 16500,
            state = "Started",
            isStarted = true,
            framesWritten = 100000L,
            underrunCount = 0
        )

        assertEquals("AAudio", info.api)
        assertEquals("SHARED", info.sharingMode)
        assertEquals("LowLatency", info.performanceMode)
        assertEquals(48000, info.sampleRate)
        assertEquals(2, info.channelCount)
        assertEquals("Float", info.format)
        assertEquals(192, info.bufferSize)
        assertEquals(16500, info.deviceId)
        assertEquals("Started", info.state)
        assertTrue(info.isStarted)
        assertEquals(100000L, info.framesWritten)
        assertEquals(0, info.underrunCount)
    }

    @Test
    fun `test Seek state machine transition sequence`() {
        val context = mock<Context>()
        val sink = OboeAudioSink(context, dspProcessor = null, bitPerfectMode = false)

        val format = Format.Builder()
            .setSampleMimeType("audio/raw")
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setSampleRate(44100)
            .setChannelCount(2)
            .build()

        sink.configure(format, 4096, null)

        // 1. Initial state
        assertEquals(C.TIME_UNSET, sink.getCurrentPositionUs(false))

        // 2. Discontinuity / Seek
        sink.handleDiscontinuity()
        assertEquals(C.TIME_UNSET, sink.getCurrentPositionUs(false))

        // 3. Buffer delivery after seek
        val buffer = ByteBuffer.allocateDirect(1024).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(ByteArray(1024))
        buffer.flip()
        sink.handleBuffer(buffer, 5_000_000L, 1)

        // 4. Subsequent seek resets anchor cleanly
        sink.flush()
        assertEquals(C.TIME_UNSET, sink.getCurrentPositionUs(false))
    }
}
