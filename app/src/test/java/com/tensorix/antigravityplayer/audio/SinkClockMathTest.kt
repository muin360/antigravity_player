package com.tensorix.antigravityplayer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden tests for the P0-1 position contract and P0-2 pending-data
 * accounting (pure math; see SinkClockMath).
 */
class SinkClockMathTest {

    private val rate = 48_000

    // ---------------- P0-1: play / pause / resume / seek / route ----------

    @Test
    fun `play - position increases with played frames`() {
        val anchor = 5_000_000L // 5 s media time
        val p1 = SinkClockMath.anchorPositionUs(anchor, 0, rate)
        val p2 = SinkClockMath.anchorPositionUs(anchor, rate.toLong(), rate)          // +1 s
        val p3 = SinkClockMath.anchorPositionUs(anchor, rate.toLong() * 30L, rate)    // +30 s
        assertEquals(5_000_000L, p1)
        assertEquals(6_000_000L, p2)
        assertEquals(35_000_000L, p3)
        assertTrue(p1 < p2 && p2 < p3)
    }

    @Test
    fun `pause - frozen hardware clock yields identical position`() {
        val anchor = 2_500_000L
        // While paused the native played-frame count stops advancing.
        val a = SinkClockMath.anchorPositionUs(anchor, 12_000L, rate)
        val b = SinkClockMath.anchorPositionUs(anchor, 12_000L, rate)
        assertEquals(a, b)
    }

    @Test
    fun `seek forward - new anchor becomes authoritative immediately`() {
        // User seeks to 30 s: first post-seek buffer anchors there; zero
        // hardware progress at that instant must read exactly 30 s.
        val p = SinkClockMath.anchorPositionUs(anchorMediaTimeUs = 30_000_000L, playedFrames = 0, outputSampleRate = rate)
        assertEquals(30_000_000L, p)
    }

    @Test
    fun `seek backward - new lower anchor is accepted (clamp resets on re-anchor)`() {
        val afterSeekBack = SinkClockMath.anchorPositionUs(5_000_000L, 0, rate)
        assertEquals(5_000_000L, afterSeekBack)
    }

    @Test
    fun `rapid seeks - final anchor wins because each anchor replaces state`() {
        var reported = SinkClockMath.TIME_UNSET
        // Seek to 10s, then 20s, then 5s: each new anchor must be returned.
        for (anchorSec in listOf(10L, 20L, 5L)) {
            reported = SinkClockMath.monotonicClamp(
                lastReportedUs = SinkClockMath.TIME_UNSET, // invalidated by discontinuity
                candidateUs = SinkClockMath.anchorPositionUs(anchorSec * 1_000_000L, 0, rate)
            )
            assertEquals(anchorSec * 1_000_000L, reported)
        }
    }

    @Test
    fun `monotonic clamp - backward hardware jitter cannot move position back`() {
        val last = SinkClockMath.anchorPositionUs(10_000_000L, rate.toLong(), rate) // 11 s
        val jittered = SinkClockMath.anchorPositionUs(10_000_000L, rate - 480L, rate)
        assertEquals(last, SinkClockMath.monotonicClamp(last, jittered))
    }

    @Test
    fun `route change continuity - same anchor math keeps position continuous`() {
        // Route reconfiguration preserves the media anchor and replays from
        // the current frame domain; position must not reset to zero.
        val before = SinkClockMath.anchorPositionUs(7_000_000L, 240_000L, rate) // +5 s
        val after = SinkClockMath.anchorPositionUs(7_000_000L, 244_800L, rate)  // tiny gap
        assertTrue(after >= before && after < before + 200_000L)
    }

    @Test
    fun `unset anchor or invalid rate yields unset sentinel`() {
        assertEquals(
            SinkClockMath.TIME_UNSET,
            SinkClockMath.anchorPositionUs(SinkClockMath.TIME_UNSET, 100, rate)
        )
        assertEquals(
            SinkClockMath.TIME_UNSET,
            SinkClockMath.anchorPositionUs(1_000L, 100, 0)
        )
    }

    // ---------------- P0-2: pending data across scenarios -----------------

    @Test
    fun `pending - normal playback has audible backlog`() {
        assertTrue(SinkClockMath.pendingOutputFrames(producedFrames = 960_000, hardwarePlayedFrames = 480_000) > 0)
    }

    @Test
    fun `pending - drained stream reports zero not negative`() {
        assertEquals(0L, SinkClockMath.pendingOutputFrames(960_000, 960_000))
        assertEquals(0L, SinkClockMath.pendingOutputFrames(960_000, 2_000_000))
    }

    @Test
    fun `pending - resampled staging counts as produced output`() {
        // Resampler consumed input and staged output: produced already includes it.
        val produced = 1_000L   // staged but not yet handed to hardware
        assertEquals(1_000L, SinkClockMath.pendingOutputFrames(produced, 0))
    }

    @Test
    fun `pending - partial write keeps remainder pending`() {
        assertEquals(512L, SinkClockMath.pendingOutputFrames(1024, 512))
    }

    @Test
    fun `pending - flush zeroes both sides`() {
        assertEquals(0L, SinkClockMath.pendingOutputFrames(0, 0))
    }
}
