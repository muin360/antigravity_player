package com.tensorix.antigravityplayer.audio

import android.util.Log

object OboeBridge {
    private const val TAG = "OboeBridge"
    
    var isAvailable: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("antigravity_oboe")
            isAvailable = true
            runCatching { Log.i(TAG, "✦ Antigravity Native C++ Oboe 64-bit Engine Loaded Successfully ✦") }
        } catch (e: Throwable) {
            runCatching { Log.e(TAG, "Failed to load Oboe native library: ${e.message}") }
            isAvailable = false
        }
    }

    // Core Stream Lifecycle
    external fun openStream(sampleRate: Int, channelCount: Int, bitPerfectMode: Boolean, deviceId: Int = 0): Long
    external fun getStreamGeneration(handle: Long): Long
    external fun writeDirect(
        handle: Long,
        generation: Long,
        directBuffer: java.nio.ByteBuffer,
        offsetBytes: Int,
        numBytes: Int,
        numFrames: Int,
        pcmEncoding: Int,
        isBitPerfect: Boolean
    ): Int
    external fun write(handle: Long, audioData: FloatArray, numFrames: Int): Int
    external fun flushStream(handle: Long)
    external fun pauseStream(handle: Long)
    external fun startStream(handle: Long)
    external fun closeStream(handle: Long)
    external fun getPlaybackPositionFrames(handle: Long): Long
    external fun getPlaybackTimestampUs(handle: Long): Long
    external fun getSampleRate(handle: Long): Int
    external fun isExclusive(handle: Long): Boolean

    // 64-bit Native C++ DSP Parameter Mutators
    external fun setDspEnabled(handle: Long, enabled: Boolean)
    external fun setBitPerfectBypass(handle: Long, bypass: Boolean)
    external fun setPreAmpGainDb(handle: Long, gainDb: Double)
    external fun setBandGain(handle: Long, bandIndex: Int, gainDb: Double)
    external fun setBassBoostGainDb(handle: Long, gainDb: Double)
    external fun setTrebleGainDb(handle: Long, gainDb: Double)
    external fun setHarmonicExciterLevel(handle: Long, level: Double)
    external fun setClarityEnhancerGain(handle: Long, gainDb: Double)
    external fun setStereoExpansionMultiplier(handle: Long, multiplier: Double)
    external fun setDvcVolume(handle: Long, volume: Double)
    external fun setDitherStrength(handle: Long, strength: Double)
    external fun setOutputBitDepth(handle: Long, bitDepth: Int)
    external fun setWarmSaturationLevel(handle: Long, level: Double)
    external fun setTriodeWarmthLevel(handle: Long, level: Double)
    external fun setPentodeTapeLevel(handle: Long, level: Double)
    external fun setCrossfeedLevel(handle: Long, level: Double)
    external fun setLimiterEnabled(handle: Long, enabled: Boolean)
    external fun setLimiterThresholdDb(handle: Long, thresholdDb: Double)
    external fun setSubBassMonoEnabled(handle: Long, enabled: Boolean)
    external fun setChannelBalance(handle: Long, balance: Double)
    external fun setInvertPhase(handle: Long, invert: Boolean)
    external fun setAirPresenceGainDb(handle: Long, gainDb: Double)
    external fun setHrtfSpatialEnabled(handle: Long, enabled: Boolean)
    external fun setHrtfRoomSize(handle: Long, roomSize: Double)

    // DSD Engine
    external fun setDsdMode(handle: Long, mode: Int, dsdRate: Int)

    // Parametric EQ (PEQ)
    external fun clearPeqBands(handle: Long)
    external fun addPeqBand(handle: Long, type: Int, frequency: Double, q: Double, gainDb: Double)
    external fun updatePeqBand(handle: Long, index: Int, type: Int, frequency: Double, q: Double, gainDb: Double)
    external fun setResamplerQuality(handle: Long, quality: Int)

    // Real-Time Telemetry
    external fun getPeakL(handle: Long): Double
    external fun getPeakR(handle: Long): Double
    external fun getPhaseCorrelation(handle: Long): Float

    /**
     * Frame-domain telemetry in RESAMPLED-OUTPUT frames (P0-2):
     * [0] outputFramesProduced, [1] hardwareFramesWritten, [2] stagedPending.
     * Null when the handle is stale/closed.
     */
    external fun getStreamFrameTelemetry(handle: Long): LongArray?

    data class NativeStreamInfo(
        val api: String,
        val sharingMode: String,
        val performanceMode: String,
        val sampleRate: Int,
        val channelCount: Int,
        val format: String,
        val bufferSize: Int,
        val deviceId: Int,
        val state: String = "Open",
        val isStarted: Boolean = true,
        val framesWritten: Long = 0L,
        val underrunCount: Int = 0
    )

    external fun getNativeStreamInfo(handle: Long): NativeStreamInfo?
}
