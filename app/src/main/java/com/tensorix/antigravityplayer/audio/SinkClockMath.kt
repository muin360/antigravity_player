package com.tensorix.antigravityplayer.audio

/**
 * Pure math for the P0 playback clock and frame-domain accounting.
 *
 * Isolated here so the position contract (P0-1) and pending-data contract
 * (P0-2) can be golden-tested on the JVM without Android/Media3 machinery.
 *
 * Domains:
 *  - [playedFrames] is HARDWARE PLAYBACK FRAMES in the RESAMPLED-OUTPUT
 *    domain (what the native throttled position model reports).
 *  - All time values are microseconds of SINK MEDIA TIME.
 */
object SinkClockMath {

    /** anchor + frames->us conversion; never negative. */
    fun anchorPositionUs(anchorMediaTimeUs: Long, playedFrames: Long, outputSampleRate: Int): Long {
        if (anchorMediaTimeUs == Long.MIN_VALUE || outputSampleRate <= 0) return Long.MIN_VALUE
        val f = if (playedFrames < 0L) 0L else playedFrames
        return anchorMediaTimeUs + (f * 1_000_000L) / outputSampleRate.toLong()
    }

    /**
     * Monotonic clamp within one anchor epoch. TIME sentinel is
     * [Long.MIN_VALUE] ("unset"): an unset last value accepts anything.
     */
    fun monotonicClamp(lastReportedUs: Long, candidateUs: Long): Long {
        if (lastReportedUs == Long.MIN_VALUE) return candidateUs
        return if (candidateUs < lastReportedUs) lastReportedUs else candidateUs
    }

    /**
     * Audible/pending output frames (P0-2): produced - hardwarePlayed.
     * Both arguments are OUTPUT-domain frames. Never negative.
     */
    fun pendingOutputFrames(producedFrames: Long, hardwarePlayedFrames: Long): Long {
        val p = if (producedFrames < 0L) 0L else producedFrames
        val h = if (hardwarePlayedFrames < 0L) 0L else hardwarePlayedFrames
        return (p - h).coerceAtLeast(0L)
    }

    /** Sentinel used for "unset" media-time values in this model. */
    const val TIME_UNSET: Long = Long.MIN_VALUE
}
