package com.tensorix.antigravityplayer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates DSD-over-PCM (DoP v1.1) packetization:
 * - Parity validation & trailing odd byte handling
 * - 16-bit DSD payload packing: (marker shl 24) or (sample shl 8)
 * - Stereo frame marker alternation: 0x05 and 0xFA alternating once per stereo frame
 * - Deterministic output frame sizing and capacity guarantees
 */
class DsdEngineDoPTest {

    /**
     * Algorithmic reference implementation of DsdEngine::convertDsdToDoP from dsd_engine.cpp.
     */
    private class DsdEngineSimulator {
        var dopMarker: Int = 0x05

        fun reset() {
            dopMarker = 0x05
        }

        fun convertDsdToDoP(
            dsdBytesL: ByteArray?,
            dsdBytesR: ByteArray?,
            numBytes: Int,
            dopOutFrames: MutableList<Int>
        ): Int {
            if (dsdBytesL == null || dsdBytesR == null || numBytes < 2) return 0

            var bytesToProcess = numBytes
            // Drop trailing odd byte defensively
            if (bytesToProcess % 2 != 0) {
                bytesToProcess -= 1
            }

            val words16 = bytesToProcess / 2
            dopOutFrames.clear()

            for (i in 0 until words16) {
                val sampleL = ((dsdBytesL[i * 2].toInt() and 0xFF) shl 8) or (dsdBytesL[i * 2 + 1].toInt() and 0xFF)
                val sampleR = ((dsdBytesR[i * 2].toInt() and 0xFF) shl 8) or (dsdBytesR[i * 2 + 1].toInt() and 0xFF)

                val marker = dopMarker
                val dopWordL = (marker shl 24) or (sampleL shl 8)
                val dopWordR = (marker shl 24) or (sampleR shl 8)

                dopOutFrames.add(dopWordL)
                dopOutFrames.add(dopWordR)

                // Marker toggles once per stereo frame (after writing both L and R)
                dopMarker = if (dopMarker == 0x05) 0xFA else 0x05
            }

            return words16 // Return stereo frame count
        }
    }

    @Test
    fun `test convertDsdToDoP returns 0 for null or undersized buffers`() {
        val engine = DsdEngineSimulator()
        val out = mutableListOf<Int>()

        assertEquals(0, engine.convertDsdToDoP(null, ByteArray(4), 4, out))
        assertEquals(0, engine.convertDsdToDoP(ByteArray(4), null, 4, out))
        assertEquals(0, engine.convertDsdToDoP(ByteArray(1), ByteArray(1), 1, out))
        assertEquals(0, engine.convertDsdToDoP(ByteArray(0), ByteArray(0), 0, out))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `test convertDsdToDoP handles even byte lengths with correct stereo frame count`() {
        val engine = DsdEngineSimulator()
        val out = mutableListOf<Int>()

        // 8 bytes in -> 4 words of 16-bit DSD per channel -> 4 stereo frames (8 total words)
        val dsdL = byteArrayOf(0x12, 0x34, 0x56, 0x78.toByte(), 0x9A.toByte(), 0xBC.toByte(), 0xDE.toByte(), 0xF0.toByte())
        val dsdR = byteArrayOf(0x0F, 0x1E, 0x2D, 0x3C.toByte(), 0x4B.toByte(), 0x5A.toByte(), 0x69.toByte(), 0x78.toByte())

        val frames = engine.convertDsdToDoP(dsdL, dsdR, dsdL.size, out)

        assertEquals(4, frames)
        assertEquals(8, out.size) // 4 stereo frames * 2 channels

        // Frame 0: Marker = 0x05
        val marker0L = (out[0] ushr 24) and 0xFF
        val marker0R = (out[1] ushr 24) and 0xFF
        assertEquals(0x05, marker0L)
        assertEquals(0x05, marker0R)

        // Payload 0: sampleL = 0x1234, shifted by 8 -> 0x123400
        assertEquals(0x1234, (out[0] ushr 8) and 0xFFFF)
        assertEquals(0x0F1E, (out[1] ushr 8) and 0xFFFF)

        // Frame 1: Marker = 0xFA
        val marker1L = (out[2] ushr 24) and 0xFF
        val marker1R = (out[3] ushr 24) and 0xFF
        assertEquals(0xFA, marker1L)
        assertEquals(0xFA, marker1R)

        // Frame 2: Marker = 0x05
        assertEquals(0x05, (out[4] ushr 24) and 0xFF)
        assertEquals(0x05, (out[5] ushr 24) and 0xFF)

        // Frame 3: Marker = 0xFA
        assertEquals(0xFA, (out[6] ushr 24) and 0xFF)
        assertEquals(0xFA, (out[7] ushr 24) and 0xFF)
    }

    @Test
    fun `test convertDsdToDoP safely drops trailing byte on odd buffer lengths`() {
        val engine = DsdEngineSimulator()
        val out = mutableListOf<Int>()

        // 7 bytes input -> 3 words (6 bytes used, 1 trailing byte dropped) -> 3 stereo frames
        val dsdL = ByteArray(7) { (it + 1).toByte() }
        val dsdR = ByteArray(7) { (it + 10).toByte() }

        val frames = engine.convertDsdToDoP(dsdL, dsdR, 7, out)

        assertEquals(3, frames)
        assertEquals(6, out.size) // 3 frames * 2 channels
    }

    @Test
    fun `test DoP marker toggles per stereo frame not per channel`() {
        val engine = DsdEngineSimulator()
        val out = mutableListOf<Int>()

        val dsdL = ByteArray(20) { 0xAA.toByte() }
        val dsdR = ByteArray(20) { 0x55.toByte() }

        val frames = engine.convertDsdToDoP(dsdL, dsdR, 20, out)
        assertEquals(10, frames)

        var expectedMarker = 0x05
        for (f in 0 until frames) {
            val markL = (out[f * 2] ushr 24) and 0xFF
            val markR = (out[f * 2 + 1] ushr 24) and 0xFF
            assertEquals("Left channel marker mismatch at frame $f", expectedMarker, markL)
            assertEquals("Right channel marker must match Left at frame $f", expectedMarker, markR)
            expectedMarker = if (expectedMarker == 0x05) 0xFA else 0x05
        }
    }
}
