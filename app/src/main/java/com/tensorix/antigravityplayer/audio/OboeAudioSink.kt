package com.tensorix.antigravityplayer.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.annotation.WorkerThread
import com.tensorix.antigravityplayer.player.PlaybackService
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Audiophile Oboe AudioSink for Media3 / ExoPlayer.
 *
 * LOCK MODEL (P0-3 / P0-5):
 *  - [lifecycleLock] guards ONLY lifecycle operations (open/close/reconfigure/
 *    recover/reset/release/format-change). It is never held across a device
 *    write.
 *  - The PCM write path is LOCK-FREE: it takes an atomic snapshot of
 *    (handle, generation) and lets the native registry validate it. A stale
 *    snapshot fails safely with RET_ERROR_STALE_OR_WRITE and Media3 retries
 *    or recovery engages.
 *  - Lazy-open inside handleBuffer uses a CAS guard instead of a blocking
 *    monitor: a concurrent lifecycle transition makes the write fail safely
 *    rather than making audio wait for route work.
 *
 * CLOCK MODEL (P0-1 / P0-6):
 *  - mediaAnchorUs  : presentation time of the FIRST frame written after a
 *                     discontinuity (the seek target). Authoritative.
 *  - Position       : anchor + hardwarePlayedFrames converted at the ACTUAL
 *                     output sample rate. Monotonically clamped between
 *                     anchors; invalidated on flush/discontinuity.
 *  - Pause freezes the hardware clock natively; resume continues it.
 *
 * FRAME DOMAINS (P0-2):
 *  - All pending-data accounting happens in RESAMPLED-OUTPUT frames using
 *    native telemetry; input frames are never mixed into output clocks.
 */
data class ActiveStreamSnapshot(
    val handle: Long = 0L,
    val generation: Long = 0L,
    val epoch: Long = 0L,
    val info: OboeBridge.NativeStreamInfo? = null
)

@UnstableApi
class OboeAudioSink(
    private val context: Context,
    private val dspProcessor: Audiophile64BitDspProcessor? = null,
    private var bitPerfectMode: Boolean = false,
    sampleRateMatchingInitial: Boolean = true,
    private val onExclusiveModeChanged: (Boolean) -> Unit = {}
) : AudioSink {

    companion object {
        private const val TAG_LOG = "OboeAudioSink"

        // Native writeDirect/write return contract (see oboe_bridge.cpp):
        //  >0 : input frames consumed
        //   0 : transient, retry later
        //  -1 : stale/closed stream or write error -> AudioEngine recovery
        //  -2 : unsupported encoding or scratch overflow
        //  -3 : bad arguments / unsupported format -> fallback sink
        private const val RET_STALE_OR_ERROR = -1
        private const val RET_ERROR_UNSUPPORTED_ENCODING = -2
        private const val RET_ERROR_SCRATCH_OVERFLOW = -2
        private const val RET_ERROR_BAD_ARGUMENTS = -3

        // Authoritative maximum scratch samples matching native kMaxScratchSamples (oboe_bridge.cpp)
        const val MAX_SCRATCH_SAMPLES = 131072
        const val DIRECT_BUFFER_CAPACITY = 262144

        @Volatile
        @JvmStatic
        var activeStreamSnapshot: ActiveStreamSnapshot? = null
            internal set

        val currentActiveHandle: Long
            @JvmStatic get() = activeStreamSnapshot?.handle ?: 0L

        val currentStreamInfo: OboeBridge.NativeStreamInfo?
            @JvmStatic get() = activeStreamSnapshot?.info
    }

    enum class SeekState {
        STABLE,
        REQUESTED,
        FLUSHING,
        EPOCH_INVALIDATED,
        HARDWARE_FLUSHED,
        REANCHORED,
        WRITING
    }

    // ------------------------------------------------------------------
    // Lifecycle state (guarded by lifecycleLock)
    // ------------------------------------------------------------------
    private val lifecycleLock = Any()
    private val opening = AtomicBoolean(false)

    @Volatile
    private var seekState: SeekState = SeekState.STABLE

    // Data-path snapshot: written under lifecycleLock, read lock-free by the
    // write path. Native validates generation, so a racing close is safe.
    @Volatile
    private var streamHandle: Long = 0L

    @Volatile
    private var streamGeneration: Long = 0L

    @Volatile
    private var audioEpoch: Long = 0L

    private var volume = 1.0f
    private var sampleRate = 48000
    private var channelCount = 2
    private var pcmEncoding = C.ENCODING_PCM_FLOAT
    private var playbackParameters = PlaybackParameters.DEFAULT
    private var listener: AudioSink.Listener? = null
    private var audioAttributes = AudioAttributes.DEFAULT
    private var isPlaying = false

    private var preferredDevice: AudioDeviceInfo? = null

    private var playerId: PlayerId? = null
    private var clock: Clock? = null
    private var skipSilenceEnabled: Boolean = false
    private var lastInputFormat: Format? = null
    private var lastSpecifiedBufferSize: Int = 0
    private var lastOutputChannels: IntArray? = null

    // Set permanently when native cannot support the current format; all
    // subsequent traffic routes to DefaultAudioSink until reset().
    @Volatile
    private var nativeUnsupported: Boolean = false

    /**
     * P0 dead-control fix: this flag is now LIVE. ON = open the device stream
     * at the SOURCE (track) sample rate. OFF = open at a fixed 48 kHz and let
     * the resampler own all conversion. Persisted + toggled from Settings and
     * the Audiophile screen; changing it re-opens a live stream.
     */
    @Volatile
    private var sampleRateMatchingEnabled: Boolean = sampleRateMatchingInitial

    // Fallback sink ONLY used if native Oboe library is missing or the device
    // fails the native open.
    private var fallbackSink: DefaultAudioSink? = null

    // ------------------------------------------------------------------
    // Clock state (P0-1/P0-6)
    // ------------------------------------------------------------------

    /** Presentation time of first frame written after the last discontinuity. */
    @Volatile
    private var mediaAnchorUs: Long = C.TIME_UNSET

    /** Monotonic clamp between anchors. */
    private var lastReportedPositionUs: Long = C.TIME_UNSET

    /** Actual output rate of the live native stream (differs when resampling). */
    @Volatile
    private var actualOutputSampleRate: Int = 0

    private fun getOrCreateFallbackSink(): DefaultAudioSink? {
        if (fallbackSink == null) {
            runCatching {
                Log.w(TAG_LOG, "STREAM_OPEN: initializing DefaultAudioSink fallback pipeline")
                val sink = DefaultAudioSink.Builder(context)
                    .setAudioProcessors(if (dspProcessor != null && !bitPerfectMode) arrayOf(dspProcessor) else emptyArray())
                    .setEnableFloatOutput(!bitPerfectMode)
                    .build()

                listener?.let { sink.setListener(it) }
                playerId?.let { sink.setPlayerId(it) }
                clock?.let { sink.setClock(it) }
                sink.setAudioAttributes(audioAttributes)
                sink.setPreferredDevice(preferredDevice)
                sink.setVolume(volume)
                sink.setPlaybackParameters(playbackParameters)
                sink.setSkipSilenceEnabled(skipSilenceEnabled)

                lastInputFormat?.let { fmt ->
                    sink.configure(fmt, lastSpecifiedBufferSize, lastOutputChannels)
                }
                if (isPlaying) {
                    sink.play()
                }
                fallbackSink = sink
            }
        }
        return fallbackSink
    }

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        fallbackSink?.setListener(listener)
    }

    override fun setPlayerId(playerId: PlayerId?) {
        this.playerId = playerId
        fallbackSink?.setPlayerId(playerId)
    }

    override fun setClock(clock: Clock) {
        this.clock = clock
        fallbackSink?.setClock(clock)
    }

    private val supportedPcmEncodings = setOf(
        C.ENCODING_PCM_8BIT,
        C.ENCODING_PCM_16BIT,
        C.ENCODING_PCM_24BIT,
        C.ENCODING_PCM_32BIT,
        C.ENCODING_PCM_FLOAT
    )

    override fun supportsFormat(format: Format): Boolean {
        val fb = fallbackSink
        if (fb != null) return fb.supportsFormat(format)
        return format.pcmEncoding in supportedPcmEncodings &&
               format.channelCount in 1..8 &&
               format.sampleRate > 0
    }

    override fun getFormatSupport(format: Format): Int {
        val fb = fallbackSink
        if (fb != null) return fb.getFormatSupport(format)
        if (!supportsFormat(format)) return AudioSink.SINK_FORMAT_UNSUPPORTED
        val isDirectCandidate = (format.pcmEncoding == C.ENCODING_PCM_FLOAT || format.pcmEncoding == C.ENCODING_PCM_16BIT) &&
                format.channelCount == channelCount &&
                (actualOutputSampleRate == 0 || actualOutputSampleRate == format.sampleRate) &&
                !nativeUnsupported
        return if (isDirectCandidate) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
    }

    /**
     * OUTPUT PRESENTATION POSITION (P0-1):
     *   mediaAnchorUs + hardwarePlayedFrames -> microseconds at the actual
     *   output rate. Monotonic between anchors. No allocations, no logging,
     *   no control-plane work (P0-12).
     */
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val fb = fallbackSink
        if (fb != null) return fb.getCurrentPositionUs(sourceEnded)

        val handle = streamHandle
        if (handle == 0L || mediaAnchorUs == C.TIME_UNSET) {
            // Before any post-discontinuity data exists there is no meaningful
            // sink position; Media3 falls back to its source clock.
            return C.TIME_UNSET
        }

        val candidate = SinkClockMath.anchorPositionUs(
            anchorMediaTimeUs = mediaAnchorUs,
            playedFrames = OboeBridge.getPlaybackPositionFrames(handle),
            outputSampleRate = actualOutputSampleRate
        )
        if (candidate == SinkClockMath.TIME_UNSET) return C.TIME_UNSET

        // Monotonic clamp within one anchor epoch.
        val result = SinkClockMath.monotonicClamp(lastReportedPositionUs, candidate)
        lastReportedPositionUs = result
        return result
    }

    // ------------------------------------------------------------------

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        synchronized(lifecycleLock) {
            lastInputFormat = inputFormat
            lastSpecifiedBufferSize = specifiedBufferSize
            lastOutputChannels = outputChannels

            val existingFallback = fallbackSink
            if (existingFallback != null) {
                existingFallback.configure(inputFormat, specifiedBufferSize, outputChannels)
                return
            }

            val newSampleRate = inputFormat.sampleRate
            val newChannelCount = inputFormat.channelCount

            if (streamHandle != 0L && (sampleRate != newSampleRate || channelCount != newChannelCount)) {
                closeOboeStreamLocked()
            }

            sampleRate = newSampleRate
            channelCount = newChannelCount.coerceAtLeast(1)
            pcmEncoding = inputFormat.pcmEncoding

            // A format change clears any previous unsupported-format verdict.
            nativeUnsupported = false

            if (streamHandle == 0L && OboeBridge.isAvailable && !nativeUnsupported) {
                openOboeStreamLocked(preferredDevice?.id ?: 0)
            }

            if (streamHandle == 0L) {
                // Native open failed during configure; pre-configure the
                // fallback sink immediately so audio never disappears.
                val fallback = getOrCreateFallbackSink()
                fallback?.configure(inputFormat, specifiedBufferSize, outputChannels)
            }

            runCatching { Log.i(TAG_LOG, "STREAM_OPEN(config): $sampleRate Hz, $channelCount ch, enc=$pcmEncoding") }
        }
    }

    override fun play() {
        isPlaying = true
        synchronized(lifecycleLock) {
            val handle = streamHandle
            if (handle != 0L) {
                OboeBridge.startStream(handle)
            }
        }
        fallbackSink?.play()
    }

    override fun pause() {
        isPlaying = false
        synchronized(lifecycleLock) {
            val handle = streamHandle
            if (handle != 0L) {
                OboeBridge.pauseStream(handle)
            }
        }
        fallbackSink?.pause()
    }

    private var isDraining = false

    /**
     * Seek/discontinuity entry point (P0-6):
     * invalidate the clock anchor, drop ALL staged/pre-seek audio (native ring
     * discard + resampler reset are requested inside native flush), flush the
     * hardware queue, and let the next handleBuffer establish the new anchor.
     */
    override fun handleDiscontinuity() {
        seekState = SeekState.REQUESTED
        mediaAnchorUs = C.TIME_UNSET
        lastReportedPositionUs = C.TIME_UNSET
        isDraining = false
        val seekStartMs = android.os.SystemClock.elapsedRealtime()
        runCatching { Log.i("SEEK", "SEEK_START state=REQUESTED") }
        synchronized(lifecycleLock) {
            seekState = SeekState.FLUSHING
            val handle = streamHandle
            if (handle != 0L) {
                // Native flush leaves the stream legally PAUSED; restore the
                // running state ourselves because Media3 will NOT re-issue
                // play() for a seek during playback (P0-6 regression guard).
                OboeBridge.flushStream(handle)
                audioEpoch++
                val cur = activeStreamSnapshot
                if (cur != null && cur.handle == handle) {
                    activeStreamSnapshot = cur.copy(epoch = audioEpoch)
                }
                seekState = SeekState.EPOCH_INVALIDATED
                if (isPlaying) {
                    OboeBridge.startStream(handle)
                }
                seekState = SeekState.HARDWARE_FLUSHED
            }
            partialFrameBuffer.clear()
        }
        runCatching { Log.i("SEEK", "SEEK_END durationMs=${android.os.SystemClock.elapsedRealtime() - seekStartMs}") }
        fallbackSink?.handleDiscontinuity()
    }

    override fun flush() {
        seekState = SeekState.REQUESTED
        mediaAnchorUs = C.TIME_UNSET
        lastReportedPositionUs = C.TIME_UNSET
        isDraining = false
        runCatching { Log.i("SEEK", "flush: clock invalidated, state=REQUESTED") }
        synchronized(lifecycleLock) {
            seekState = SeekState.FLUSHING
            val handle = streamHandle
            if (handle != 0L) {
                OboeBridge.flushStream(handle)
                audioEpoch++
                val cur = activeStreamSnapshot
                if (cur != null && cur.handle == handle) {
                    activeStreamSnapshot = cur.copy(epoch = audioEpoch)
                }
                seekState = SeekState.EPOCH_INVALIDATED
                if (isPlaying) {
                    // See handleDiscontinuity(): native flush ends PAUSED.
                    OboeBridge.startStream(handle)
                }
                seekState = SeekState.HARDWARE_FLUSHED
            }
            partialFrameBuffer.clear()
        }
        fallbackSink?.flush()
    }

    override fun reset() {
        synchronized(lifecycleLock) {
            isPlaying = false
            isDraining = false
            seekState = SeekState.STABLE
            mediaAnchorUs = C.TIME_UNSET
            lastReportedPositionUs = C.TIME_UNSET
            nativeUnsupported = false
            closeOboeStreamLocked()
            activeStreamSnapshot = null
            partialFrameBuffer.clear()
            fallbackSink?.reset()
        }
    }

    override fun release() {
        synchronized(lifecycleLock) {
            seekState = SeekState.STABLE
            closeOboeStreamLocked()
            activeStreamSnapshot = null
            partialFrameBuffer.clear()
            fallbackSink?.release()
        }
    }

    // Preallocated 256 KB direct scratch (Rule 3: zero allocation in audio write path)
    private val directByteBuffer: ByteBuffer = ByteBuffer.allocateDirect(262144).order(ByteOrder.LITTLE_ENDIAN)
    // Staging buffer for sub-frame remainder (< 1 frame) to preserve partial frames across handleBuffer calls
    private val partialFrameBuffer: ByteBuffer = ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN)

    @WorkerThread
    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        if (fallbackSink != null) {
            val fb = fallbackSink ?: return false
            return fb.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }

        // ---- Lazy open WITHOUT holding the lifecycle lock (P0-3) ----
        if (nativeUnsupported || !OboeBridge.isAvailable) {
            return getOrCreateFallbackSink()
                ?.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount) ?: false
        }
        if (streamHandle == 0L) {
            if (!opening.compareAndSet(false, true)) {
                // Another thread is mid-open: retry this buffer later rather
                // than blocking the audio path behind lifecycle work.
                return false
            }
            try {
                synchronized(lifecycleLock) {
                    if (streamHandle == 0L) {
                        openOboeStreamLocked(preferredDevice?.id ?: 0)
                    }
                }
            } finally {
                opening.set(false)
            }
        }

        // Atomic data-path snapshot; native registry validates the pair and
        // fails safely if a concurrent lifecycle transition invalidated it.
        val handleSnapshot = streamHandle
        val generationSnapshot = streamGeneration
        if (handleSnapshot == 0L) {
            return getOrCreateFallbackSink()
                ?.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount) ?: false
        }

        // ---- Clock anchoring & seek state machine (P0-6) ----
        if (seekState == SeekState.REQUESTED || seekState == SeekState.FLUSHING ||
            seekState == SeekState.EPOCH_INVALIDATED || seekState == SeekState.HARDWARE_FLUSHED ||
            mediaAnchorUs == C.TIME_UNSET) {
            mediaAnchorUs = presentationTimeUs
            lastReportedPositionUs = presentationTimeUs
            seekState = SeekState.REANCHORED
            runCatching { Log.i("SEEK", "handleBuffer: re-anchored mediaAnchorUs=$presentationTimeUs state=REANCHORED") }
        }

        val remaining = buffer.remaining()
        if (remaining == 0 && partialFrameBuffer.position() == 0) return true

        val bytesPerSample = when (pcmEncoding) {
            C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> 4
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT -> 2
            else -> 2
        }

        val bytesPerFrame = channelCount * bytesPerSample
        if (bytesPerFrame <= 0) return true

        // Complete any pending partial frame from previous calls
        if (partialFrameBuffer.position() > 0) {
            val pendingBytes = partialFrameBuffer.position()
            val needed = bytesPerFrame - pendingBytes
            if (buffer.remaining() < needed) {
                // Not enough bytes to complete 1 frame; append to staging and return
                if (partialFrameBuffer.remaining() >= buffer.remaining()) {
                    partialFrameBuffer.put(buffer)
                } else {
                    partialFrameBuffer.clear()
                }
                return true
            }
            // Pull exact bytes needed to finish the frame
            val savedLimit = buffer.limit()
            buffer.limit(buffer.position() + needed)
            if (partialFrameBuffer.remaining() >= needed) {
                partialFrameBuffer.put(buffer)
            } else {
                partialFrameBuffer.clear()
            }
            buffer.limit(savedLimit)

            partialFrameBuffer.flip()
            val stitchedResult = OboeBridge.writeDirect(
                handle = handleSnapshot,
                generation = generationSnapshot,
                directBuffer = partialFrameBuffer,
                offsetBytes = 0,
                numBytes = bytesPerFrame,
                numFrames = 1,
                pcmEncoding = pcmEncoding,
                isBitPerfect = bitPerfectMode
            )
            if (stitchedResult > 0) {
                partialFrameBuffer.clear()
            } else if (stitchedResult == 0) {
                // Cannot write yet; restore buffer and retry
                buffer.position(buffer.position() - needed)
                partialFrameBuffer.position(pendingBytes)
                partialFrameBuffer.limit(partialFrameBuffer.capacity())
                return false
            } else {
                partialFrameBuffer.clear()
                if (stitchedResult == RET_ERROR_UNSUPPORTED_ENCODING || stitchedResult == RET_ERROR_BAD_ARGUMENTS) {
                    Log.w(TAG_LOG, "FALLBACK: format unsupported natively (enc=$pcmEncoding ch=$channelCount)")
                    synchronized(lifecycleLock) { closeOboeStreamLocked() }
                    nativeUnsupported = true
                    val fallback = getOrCreateFallbackSink()
                    lastInputFormat?.let { fmt ->
                        fallback?.configure(fmt, lastSpecifiedBufferSize, lastOutputChannels)
                    }
                    return fallback?.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount) ?: false
                } else {
                    runCatching { Log.w(TAG_LOG, "RECOVERY_REQUEST: stitched write error $stitchedResult") }
                    synchronized(lifecycleLock) { closeOboeStreamLocked() }
                    AudioEngine.handleStreamError(stitchedResult, context)
                    return false
                }
            }
        }

        val curRemaining = buffer.remaining()
        val curPosition = buffer.position()
        val remainder = curRemaining % bytesPerFrame
        val usableBytes = curRemaining - remainder
        if (usableBytes <= 0) {
            if (remainder > 0) {
                if (partialFrameBuffer.remaining() >= buffer.remaining()) {
                    partialFrameBuffer.put(buffer)
                } else {
                    partialFrameBuffer.clear()
                }
            }
            return true
        }

        val numFrames = usableBytes / bytesPerFrame
        val maxFramesFromScratch = MAX_SCRATCH_SAMPLES / channelCount.coerceAtLeast(1)
        val maxFramesFromDirectBuffer = DIRECT_BUFFER_CAPACITY / bytesPerFrame
        val maxAllowedFrames = minOf(maxFramesFromScratch, maxFramesFromDirectBuffer)
        val framesToWrite = minOf(numFrames, maxAllowedFrames)
        val bytesToWrite = framesToWrite * bytesPerFrame

        seekState = SeekState.WRITING

        // ---- Lock-free PCM write (P0-3) bounded to scratch capacity (P0-3) ----
        val framesWrittenResult = if (buffer.isDirect) {
            OboeBridge.writeDirect(
                handle = handleSnapshot,
                generation = generationSnapshot,
                directBuffer = buffer,
                offsetBytes = curPosition,
                numBytes = bytesToWrite,
                numFrames = framesToWrite,
                pcmEncoding = pcmEncoding,
                isBitPerfect = bitPerfectMode
            )
        } else {
            directByteBuffer.clear()
            val slice = buffer.duplicate()
            slice.position(curPosition)
            slice.limit(curPosition + bytesToWrite)
            directByteBuffer.put(slice)
            directByteBuffer.flip()

            OboeBridge.writeDirect(
                handle = handleSnapshot,
                generation = generationSnapshot,
                directBuffer = directByteBuffer,
                offsetBytes = 0,
                numBytes = bytesToWrite,
                numFrames = framesToWrite,
                pcmEncoding = pcmEncoding,
                isBitPerfect = bitPerfectMode
            )
        }

        when {
            framesWrittenResult > 0 -> {
                seekState = SeekState.STABLE
                val bytesConsumed = framesWrittenResult * bytesPerFrame
                buffer.position((curPosition + bytesConsumed).coerceAtMost(buffer.limit()))
                if (buffer.remaining() in 1 until bytesPerFrame) {
                    if (partialFrameBuffer.remaining() >= buffer.remaining()) {
                        partialFrameBuffer.put(buffer)
                    } else {
                        partialFrameBuffer.clear()
                    }
                }
                return !buffer.hasRemaining()
            }
            framesWrittenResult == 0 -> {
                // Nothing consumed this cycle; Media3 retries later.
                return false
            }
            framesWrittenResult == RET_ERROR_UNSUPPORTED_ENCODING ||
                framesWrittenResult == RET_ERROR_BAD_ARGUMENTS -> {
                Log.w(TAG_LOG, "FALLBACK: format unsupported natively(enc=$pcmEncoding ch=$channelCount)")
                synchronized(lifecycleLock) { closeOboeStreamLocked() }
                nativeUnsupported = true
                val fallback = getOrCreateFallbackSink()
                lastInputFormat?.let { fmt ->
                    fallback?.configure(fmt, lastSpecifiedBufferSize, lastOutputChannels)
                }
                return fallback?.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount) ?: false
            }
            else -> {
                // Stale/closed/write error: delegate to the single recovery
                // authority. The stale-generation case self-heals via reopen.
                runCatching { Log.w(TAG_LOG, "RECOVERY_REQUEST: write error $framesWrittenResult") }
                synchronized(lifecycleLock) { closeOboeStreamLocked() }
                AudioEngine.handleStreamError(framesWrittenResult, context)
                return false
            }
        }
    }

    override fun playToEndOfStream() {
        isDraining = true
        fallbackSink?.playToEndOfStream()
    }

    /**
     * EOF chain (P0-2.3): decoder EOF -> drain flag -> all staged/hardware
     * output consumed -> ended. Pause alone NEVER implies ended.
     */
    override fun isEnded(): Boolean {
        val fb = fallbackSink
        if (fb != null) return fb.isEnded
        if (streamHandle == 0L) return isDraining
        return isDraining && !hasPendingData()
    }

    /**
     * TRUE only while audible/pending output remains (P0-2):
     * outputFramesProduced - hardwarePlayedFrames > 0, computed entirely in
     * the resampled-output frame domain. Never uses isPlaying or mere
     * handle existence as a proxy.
     */
    override fun hasPendingData(): Boolean {
        val fb = fallbackSink
        if (fb != null) return fb.hasPendingData()
        val handle = streamHandle
        if (handle == 0L) return false

        // Zero-allocation scalar getters (Rule 4 / Rule 14)
        val produced = OboeBridge.getOutputFramesProduced(handle)
        if (produced <= 0L) return false

        // getPlaybackPositionFrames is already in the OUTPUT frame domain and
        // is clamped natively to hardwareFramesWritten, so this single
        // subtraction covers both staged and device-queued audio.
        val playedFrames = OboeBridge.getPlaybackPositionFrames(handle)
        if (SinkClockMath.pendingOutputFrames(produced, playedFrames) > 0) return true

        // Belt & braces: any frames still sitting in the staging ring count.
        val staged = OboeBridge.getStagedPendingFrames(handle)
        return staged > 0L
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        this.playbackParameters = playbackParameters
        fallbackSink?.playbackParameters = playbackParameters
    }

    override fun getPlaybackParameters(): PlaybackParameters {
        // Native Oboe does not implement sonic time/pitch stretching; returning non-1.0x
        // causes Media3 position calculation drift. When native is active, report DEFAULT.
        return if (fallbackSink != null) {
            fallbackSink?.playbackParameters ?: PlaybackParameters.DEFAULT
        } else {
            PlaybackParameters.DEFAULT
        }
    }

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        this.skipSilenceEnabled = skipSilenceEnabled
        fallbackSink?.setSkipSilenceEnabled(skipSilenceEnabled)
    }

    override fun getSkipSilenceEnabled(): Boolean {
        return fallbackSink?.getSkipSilenceEnabled() ?: skipSilenceEnabled
    }

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        if (this.audioAttributes == audioAttributes) return
        this.audioAttributes = audioAttributes
        fallbackSink?.setAudioAttributes(audioAttributes)
        synchronized(lifecycleLock) {
            if (streamHandle != 0L) {
                closeOboeStreamLocked()
                openOboeStreamLocked(preferredDevice?.id ?: 0)
                if (streamHandle != 0L && isPlaying) {
                    OboeBridge.startStream(streamHandle)
                }
            }
        }
    }

    override fun getAudioAttributes(): AudioAttributes {
        return fallbackSink?.getAudioAttributes() ?: audioAttributes
    }

    override fun setAudioSessionId(audioSessionId: Int) {
        fallbackSink?.setAudioSessionId(audioSessionId)
    }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {
        fallbackSink?.setAuxEffectInfo(auxEffectInfo)
    }

    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) {
        this.preferredDevice = audioDeviceInfo
        fallbackSink?.setPreferredDevice(audioDeviceInfo)
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        fallbackSink?.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun enableTunnelingV21() {
        fallbackSink?.enableTunnelingV21()
    }

    override fun disableTunneling() {
        fallbackSink?.disableTunneling()
    }

    override fun setVolume(volume: Float) {
        val targetVolume = if (bitPerfectMode) 1.0f else volume.coerceIn(0.0f, 1.0f)
        this.volume = targetVolume
        fallbackSink?.setVolume(targetVolume)
        val handle = streamHandle
        if (handle != 0L && OboeBridge.isAvailable) {
            val dspVol = dspProcessor?.dvcVolume ?: 1.0
            val effVol = if (bitPerfectMode) 1.0 else (targetVolume.toDouble() * dspVol).coerceIn(0.0, 1.0)
            OboeBridge.setDvcVolume(handle, effVol)
        }
    }

    /**
     * Route change (P0-5.4): serialized lifecycle transition. Old generation
     * becomes stale the moment the handle is cleared, so an in-flight lock-free
     * write fails fast instead of waiting for this work.
     */
    fun reconfigureRoute(newRoute: AudioRouteCapability? = null, preferredDevice: AudioDeviceInfo? = null): Boolean {
        synchronized(lifecycleLock) {
            val oldHandle = streamHandle
            val oldDeviceId = currentStreamInfo?.deviceId ?: 0
            val targetDeviceId = preferredDevice?.id ?: 0
            runCatching { Log.i("ROUTE_CHANGE", "[OBOE_RECONFIG] oldHandle=$oldHandle oldDevice=$oldDeviceId newRoute=${newRoute?.routeType?.displayName} newDevice=$targetDeviceId") }

            this.preferredDevice = preferredDevice

            // 1. Invalidate old generation FIRST (writes fail fast), then tear down.
            closeOboeStreamLocked()

            // 2. Clear fallback if we're trying to re-engage native on the new route.
            if (fallbackSink != null && !nativeUnsupported) {
                fallbackSink?.pause()
                fallbackSink?.reset()
                fallbackSink = null
            }

            // 3. Open on the new physical device.
            if (OboeBridge.isAvailable && !nativeUnsupported) {
                openOboeStreamLocked(targetDeviceId)
            }

            val openedHandle = streamHandle
            if (openedHandle != 0L) {
                if (isPlaying) {
                    OboeBridge.startStream(openedHandle)
                }
                runCatching { Log.i("ROUTE_CHANGE", "[OBOE_RECONFIG] engaged native (Handle=$openedHandle Device=${currentStreamInfo?.deviceId})") }
                return true
            } else {
                runCatching { Log.w("ROUTE_CHANGE", "[OBOE_RECONFIG] native open failed; engaging fallback") }
                val fallback = getOrCreateFallbackSink()
                if (isPlaying) fallback?.play()
                return false
            }
        }
    }

    fun recoverFromError(errorCode: Int): Boolean {
        synchronized(lifecycleLock) {
            runCatching { Log.w(TAG_LOG, "RECOVERY: controlled recovery for errorCode=$errorCode") }
            closeOboeStreamLocked()

            if (OboeBridge.isAvailable && !nativeUnsupported) {
                openOboeStreamLocked(preferredDevice?.id ?: 0)
            }

            val openedHandle = streamHandle
            if (openedHandle != 0L) {
                if (isPlaying) {
                    OboeBridge.startStream(openedHandle)
                }
                runCatching { Log.i(TAG_LOG, "RECOVERY: reopened native (Handle=$openedHandle)") }
                return true
            } else {
                runCatching { Log.w(TAG_LOG, "RECOVERY: native reopen failed; falling back") }
                val fallback = getOrCreateFallbackSink()
                if (isPlaying) fallback?.play()
                return false
            }
        }
    }


    /**
     * LIVE control (dead-toggle fix): ON opens streams at the track rate;
     * OFF pins the device stream to 48 kHz and lets the resampler convert.
     * Re-open is serialized through the lifecycle lock; an in-flight
     * lock-free write fails safely on the stale generation.
     */
    fun setSampleRateMatching(enabled: Boolean) {
        if (sampleRateMatchingEnabled == enabled) return
        sampleRateMatchingEnabled = enabled
        synchronized(lifecycleLock) {
            if (streamHandle != 0L) {
                closeOboeStreamLocked()
                openOboeStreamLocked(preferredDevice?.id ?: 0)
                if (streamHandle != 0L && isPlaying) {
                    OboeBridge.startStream(streamHandle)
                }
            }
        }
    }
    fun setBitPerfectMode(enabled: Boolean) {
        synchronized(lifecycleLock) {
            if (this.bitPerfectMode != enabled) {
                this.bitPerfectMode = enabled
                if (streamHandle != 0L) {
                    closeOboeStreamLocked()
                    openOboeStreamLocked(preferredDevice?.id ?: 0)
                    if (streamHandle != 0L && isPlaying) {
                        OboeBridge.startStream(streamHandle)
                    }
                }
            }
        }
    }

    // MUST hold lifecycleLock.
    private fun openOboeStreamLocked(deviceId: Int = 0) {
        if (!OboeBridge.isAvailable || streamHandle != 0L) return
        val targetDevice = if (deviceId > 0) deviceId else (preferredDevice?.id ?: 0)
        // Sample-Rate Matching (now LIVE): OFF pins device stream to 48 kHz.
        val openRate = if (sampleRateMatchingEnabled) sampleRate else 48000
        val handle = OboeBridge.openStream(
            sampleRate = openRate,
            channelCount = channelCount,
            bitPerfectMode = bitPerfectMode,
            deviceId = targetDevice,
            usage = audioAttributes.usage,
            contentType = audioAttributes.contentType
        )
        if (handle == 0L) return

        if (OboeBridge.isAvailable) {
            val dspVol = dspProcessor?.dvcVolume ?: 1.0
            val effVol = if (bitPerfectMode) 1.0 else (this.volume.toDouble() * dspVol).coerceIn(0.0, 1.0)
            OboeBridge.setDvcVolume(handle, effVol)
        }

        streamHandle = handle
        streamGeneration = OboeBridge.getStreamGeneration(handle)
        actualOutputSampleRate = OboeBridge.getSampleRate(handle)
        val info = OboeBridge.getNativeStreamInfo(handle)
        activeStreamSnapshot = ActiveStreamSnapshot(handle, streamGeneration, audioEpoch, info)

        val exclusive = OboeBridge.isExclusive(handle)
        val routeConf =
            if (info?.deviceId != null && info.deviceId != 0) "VERIFIED" else "UNKNOWN"
        // Phase 3 rate domains: SOURCE (decoder/format) vs NATIVE (stream).
        // When they differ, OUR resampler is ACTIVE. When they match but the
        // HAL mixer runs at another rate, AAudio's flowgraph performs the
        // conversion inside AudioFlinger (visible as 44100->48000 flowgraph
        // logs) — our resampler stays PASSTHROUGH and BitPerfect remains
        // unavailable because the endpoint rate differs from source.
        val resamplerState =
            if (actualOutputSampleRate > 0 && sampleRate != actualOutputSampleRate) "ACTIVE"
            else "PASSTHROUGH"
        runCatching {
            Log.i(
                TAG_LOG,
                "STREAM_OPENED: handle=$streamHandle gen=$streamGeneration exclusive=$exclusive " +
                    "rate=$actualOutputSampleRate dev=${info?.deviceId} bp=$bitPerfectMode"
            )
            Log.i(
                TAG_LOG,
                "ROUTE_TELEMETRY: requestedDeviceId=$targetDevice " +
                    "nativeDeviceId=${info?.deviceId} " +
                    "matchedAudioDeviceId=${preferredDevice?.id} routeType=${preferredDevice?.type} " +
                    "routeConfidence=$routeConf sharingMode=${info?.sharingMode} " +
                    "sourceSampleRate=$sampleRate nativeSampleRate=$actualOutputSampleRate " +
                    "resampler=$resamplerState channels=$channelCount api=${info?.api}"
            )
        }

        syncDspParameters(handle)
        onExclusiveModeChanged(exclusive)
        AudioEngine.invalidate()
    }

    private fun syncDspParameters(handle: Long) {
        val eq = PlaybackService.instance?.equalizerEngine
        if (eq != null) {
            eq.syncWithNativeDsp(handle)
        } else {
            // EqualizerEngine not yet attached; configure neutral bit-perfect bypass state
            OboeBridge.setBitPerfectBypass(handle, bitPerfectMode)
        }
    }

    // MUST hold lifecycleLock. Clearing the volatile handle first makes every
    // in-flight lock-free write fail safely on the next native validation.
    private fun closeOboeStreamLocked() {
        val handleToClose = streamHandle
        if (handleToClose != 0L) {
            streamHandle = 0L
            streamGeneration = 0L
            actualOutputSampleRate = 0
            if (activeStreamSnapshot?.handle == handleToClose) {
                activeStreamSnapshot = null
            }
            OboeBridge.closeStream(handleToClose)
            AudioEngine.invalidate()
        }
    }
}
