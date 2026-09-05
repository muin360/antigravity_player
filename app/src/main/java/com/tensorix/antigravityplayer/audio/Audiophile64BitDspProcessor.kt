package com.tensorix.antigravityplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

data class FallbackDspSnapshot(
    val generation: Long = 1L,
    val isEnabled: Boolean = true,
    val isBitPerfectBypass: Boolean = false,
    val preAmpGainDb: Double = 0.0,
    val bassBoostGainDb: Double = 0.0,
    val trebleGainDb: Double = 0.0,
    val harmonicExciterLevel: Double = 0.0,
    val clarityEnhancerGain: Double = 0.0,
    val stereoExpansionMultiplier: Double = 1.0,
    val dvcVolume: Double = 1.0,
    val replayGainMultiplier: Double = 1.0,
    val ditherStrength: Double = 0.0,
    val outputBitDepth: Int = 24,
    val warmSaturationLevel: Double = 0.0,
    val triodeWarmthLevel: Double = 0.0,
    val pentodeTapeLevel: Double = 0.0,
    val crossfeedLevel: Double = 0.0,
    val limiterEnabled: Boolean = false,
    val limiterThresholdDb: Double = 0.0,
    val subBassMonoEnabled: Boolean = false,
    val channelBalance: Double = 0.0,
    val invertPhase: Boolean = false,
    val airPresenceGainDb: Double = 0.0,
    val bandGainsDb: DoubleArray = DoubleArray(10),
    val biquadsCoeffs: Array<BiquadCoeffs> = Array(10) { BiquadCoeffs() },
    val bassShelfCoeff: BiquadCoeffs = BiquadCoeffs(),
    val trebleShelfCoeff: BiquadCoeffs = BiquadCoeffs(),
    val detailHpfCoeff: BiquadCoeffs = BiquadCoeffs(),
    val clarityFilterCoeff: BiquadCoeffs = BiquadCoeffs(),
    val crossfeedLpfCoeff: BiquadCoeffs = BiquadCoeffs(),
    val dcRemovalCoeff: BiquadCoeffs = BiquadCoeffs(),
    val dcBlockerCoeff: BiquadCoeffs = BiquadCoeffs(),
    val aaFilterCoeff: BiquadCoeffs = BiquadCoeffs(),
    val subBassFilterCoeff: BiquadCoeffs = BiquadCoeffs(),
    val airFilterCoeff: BiquadCoeffs = BiquadCoeffs()
)

/**
 * Audiophile 64-bit Double-Precision DSP Engine (fallback-path processor).
 *
 * Concurrency Contract:
 *  - Control thread builds an immutable FallbackDspSnapshot under snapshotLock.
 *  - Audio render thread atomically takes one coherent snapshot per buffer call in queueInput().
 *  - The audio thread updates its local BiquadFilter instances strictly at block boundaries
 *    when the snapshot generation changes, guaranteeing zero torn coefficients.
 *  - Zero per-sample heap allocation on the render thread.
 */
@UnstableApi
class Audiophile64BitDspProcessor : BaseAudioProcessor() {

    private val snapshotLock = Any()
    private var publishedGeneration = 1L
    private var appliedGeneration = 0L

    @Volatile
    var isEnabled: Boolean = true
        set(value) { field = value; rebuildSnapshot() }

    @Volatile
    var isBitPerfectBypass: Boolean = false
        set(value) { field = value; rebuildSnapshot() }

    @Volatile
    var isTurboMode: Boolean = true

    @Volatile
    var preAmpGainDb: Double = 0.0
        set(value) { field = value; rebuildSnapshot() }

    @Volatile
    var bassBoostGainDb: Double = 0.0
        set(value) { field = value; rebuildSnapshot() }

    @Volatile
    var trebleGainDb: Double = 0.0
        set(value) { field = value; rebuildSnapshot() }

    @Volatile
    var harmonicExciterLevel: Double = 0.0
        set(value) { field = value; rebuildSnapshot() }

    @Volatile
    var clarityEnhancerGain: Double = 0.0
        set(value) { field = value; rebuildSnapshot() }

    @Volatile
    var stereoExpansionMultiplier: Double = 1.0
        set(value) { field = value; rebuildSnapshot() }

    @Volatile
    var dvcVolume: Double = 1.0
        set(value) { field = value; rebuildSnapshot() }

    @Volatile
    var ditherStrength: Double = 0.0
        set(value) { field = value; rebuildSnapshot() }

    @Volatile
    var outputBitDepth: Int = 24
        set(value) { field = value; rebuildSnapshot() }

    @Volatile
    var warmSaturationLevel: Double = 0.0
        set(value) { field = value; rebuildSnapshot() }

    @Volatile
    var triodeWarmthLevel: Double = 0.0
        set(value) { field = value; rebuildSnapshot() }

    @Volatile
    var pentodeTapeLevel: Double = 0.0
        set(value) { field = value; rebuildSnapshot() }

    @Volatile
    var crossfeedLevel: Double = 0.0
        set(value) { field = value; rebuildSnapshot() }

    @Volatile
    var limiterThresholdDb: Double = 0.0
        set(value) { field = value; rebuildSnapshot() }

    @Volatile
    var limiterEnabled: Boolean = false
        set(value) { field = value; rebuildSnapshot() }

    @Volatile
    var replayGainMultiplier: Double = 1.0
        set(value) { field = value; rebuildSnapshot() }

    fun applyReplayGain(
        trackGainDb: Float,
        albumGainDb: Float,
        peakAmplitude: Float,
        useAlbumGain: Boolean
    ) {
        val gainDb = if (useAlbumGain) albumGainDb else trackGainDb
        val linearGain = 10f.pow(gainDb / 20f)
        val safeGain = if (peakAmplitude > 0f) {
            min(linearGain, 1f / peakAmplitude)
        } else {
            linearGain
        }
        replayGainMultiplier = safeGain.toDouble()
    }

    @Volatile
    var peakL: Double = 0.0

    @Volatile
    var peakR: Double = 0.0

    @Volatile
    var channelBalance: Double = 0.0
        set(value) { field = value; rebuildSnapshot() }

    @Volatile
    var phaseCorrelation: Float = 1.0f

    private var ditherErrorL = 0.0
    private var ditherErrorR = 0.0

    @Volatile
    var invertPhase: Boolean = false
        set(value) { field = value; rebuildSnapshot() }

    @Volatile
    var airPresenceGainDb: Double = 0.0
        set(value) { field = value; rebuildSnapshot() }

    @Volatile
    var subBassMonoEnabled: Boolean = false
        set(value) { field = value; rebuildSnapshot() }

    val currentSampleRate: Int
        get() = if (inputAudioFormat != AudioProcessor.AudioFormat.NOT_SET) inputAudioFormat.sampleRate else 0

    // Explicit state query model for DSP stages
    val isEqActive: Boolean
        get() = activeSnapshot.let { snap ->
            snap.isEnabled && !snap.isBitPerfectBypass && snap.bandGainsDb.any { it < -0.01 || it > 0.01 }
        }

    val isBassBoostActive: Boolean
        get() = activeSnapshot.let { snap -> snap.isEnabled && !snap.isBitPerfectBypass && snap.bassBoostGainDb > 0.01 }

    val isTrebleActive: Boolean
        get() = activeSnapshot.let { snap -> snap.isEnabled && !snap.isBitPerfectBypass && snap.trebleGainDb > 0.01 }

    val isClarityActive: Boolean
        get() = activeSnapshot.let { snap -> snap.isEnabled && !snap.isBitPerfectBypass && snap.clarityEnhancerGain > 0.01 }

    val isHarmonicActive: Boolean
        get() = activeSnapshot.let { snap -> snap.isEnabled && !snap.isBitPerfectBypass && snap.harmonicExciterLevel > 0.001 }

    val isStereoExpansionActive: Boolean
        get() = activeSnapshot.let { snap ->
            snap.isEnabled && !snap.isBitPerfectBypass && (snap.stereoExpansionMultiplier < 0.99 || snap.stereoExpansionMultiplier > 1.01)
        }

    val isSaturationActive: Boolean
        get() = activeSnapshot.let { snap ->
            snap.isEnabled && !snap.isBitPerfectBypass &&
                (snap.warmSaturationLevel > 0.001 || snap.triodeWarmthLevel > 0.001 || snap.pentodeTapeLevel > 0.001)
        }

    val isCrossfeedActive: Boolean
        get() = activeSnapshot.let { snap -> snap.isEnabled && !snap.isBitPerfectBypass && snap.crossfeedLevel > 0.001 }

    val isSubBassMonoActive: Boolean
        get() = activeSnapshot.let { snap -> snap.isEnabled && !snap.isBitPerfectBypass && snap.subBassMonoEnabled }

    val isLimiterActive: Boolean
        get() = activeSnapshot.let { snap -> snap.isEnabled && !snap.isBitPerfectBypass && snap.limiterEnabled }

    val isDitherActive: Boolean
        get() = activeSnapshot.let { snap -> snap.isEnabled && !snap.isBitPerfectBypass && snap.ditherStrength > 0.0001 }

    val isChannelBalanceActive: Boolean
        get() = activeSnapshot.let { snap ->
            snap.isEnabled && !snap.isBitPerfectBypass && (snap.channelBalance < -0.01 || snap.channelBalance > 0.01)
        }

    val isInvertPhaseActive: Boolean
        get() = activeSnapshot.let { snap -> snap.isEnabled && !snap.isBitPerfectBypass && snap.invertPhase }

    val isAirPresenceActive: Boolean
        get() = activeSnapshot.let { snap -> snap.isEnabled && !snap.isBitPerfectBypass && snap.airPresenceGainDb > 0.01 }

    val isReplayGainActive: Boolean
        get() = activeSnapshot.let { snap ->
            snap.isEnabled && !snap.isBitPerfectBypass &&
                (snap.replayGainMultiplier < 0.999 || snap.replayGainMultiplier > 1.001)
        }

    private val bandGainsDb = DoubleArray(10)
    private val bandCenterFreqs = doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)

    // Render-thread-owned Biquad filter state (Left & Right)
    private val biquadsL = Array(10) { BiquadFilter() }
    private val biquadsR = Array(10) { BiquadFilter() }
    private val bassShelfL = BiquadFilter()
    private val bassShelfR = BiquadFilter()
    private val trebleShelfL = BiquadFilter()
    private val trebleShelfR = BiquadFilter()
    private val detailHPFL = BiquadFilter()
    private val detailHPFR = BiquadFilter()
    private val clarityFilterL = BiquadFilter()
    private val clarityFilterR = BiquadFilter()
    private val crossfeedLPFL = BiquadFilter()
    private val crossfeedLPFR = BiquadFilter()
    private val airFilterL = BiquadFilter()
    private val airFilterR = BiquadFilter()
    private val dcRemovalL = BiquadFilter()
    private val dcRemovalR = BiquadFilter()
    private val aaFilterL = BiquadFilter()
    private val aaFilterR = BiquadFilter()
    private val dcBlockerL = BiquadFilter()
    private val dcBlockerR = BiquadFilter()
    private val subBassFilterL = BiquadFilter()
    private val subBassFilterR = BiquadFilter()

    // Waveshaping interpolation history
    private val osSamplesL = DoubleArray(4) { 0.0 }
    private val osSamplesR = DoubleArray(4) { 0.0 }

    private var rngState: Long = System.nanoTime()

    private fun nextRandomDouble(): Double {
        rngState = rngState xor (rngState ushr 12)
        rngState = rngState xor (rngState shl 25)
        rngState = rngState xor (rngState ushr 27)
        val v = (rngState * 0x2545F4914F6CDD1DL)
        return (v ushr 1).toDouble() / Long.MAX_VALUE.toDouble()
    }

    // Render-thread scratch
    private val frameSamples = DoubleArray(8)
    private val upsampledPair = DoubleArray(2)

    @Volatile
    var activeSnapshot: FallbackDspSnapshot = buildSnapshotInternal(48000.0)
        private set

    private fun rebuildSnapshot() {
        val fs = if (inputAudioFormat.sampleRate > 0) inputAudioFormat.sampleRate.toDouble() else 44100.0
        val snap = buildSnapshotInternal(fs)
        activeSnapshot = snap
    }

    private fun buildSnapshotInternal(sampleRate: Double): FallbackDspSnapshot {
        synchronized(snapshotLock) {
            val gen = ++publishedGeneration
            val gainsCopy = bandGainsDb.copyOf()

            val biquads = Array(10) { i ->
                BiquadFilter.computePeakingEq(bandCenterFreqs[i], 1.414, gainsCopy[i], sampleRate)
            }
            val bass = BiquadFilter.computeLowShelf(80.0, 0.707, bassBoostGainDb, sampleRate)
            val treble = BiquadFilter.computeHighShelf(10000.0, 0.707, trebleGainDb, sampleRate)
            val detail = BiquadFilter.computeHighPass(7500.0, 0.707, sampleRate * 2.0)
            val clarity = BiquadFilter.computePeakingEq(3200.0, 1.0, clarityEnhancerGain, sampleRate)
            val crossfeed = BiquadFilter.computeLowPass(700.0, 0.5, sampleRate)
            val dcRemoval = BiquadFilter.computeHighPass(2.0, 0.707, sampleRate)
            val dcBlocker = BiquadFilter.computeHighPass(1.0, 0.707, sampleRate)
            val aaCorner = minOf(20000.0, sampleRate * 0.45)
            val aa = BiquadFilter.computeLowPass(aaCorner, 0.707, sampleRate)
            val subBass = BiquadFilter.computeLowPass(80.0, 0.707, sampleRate)
            val air = BiquadFilter.computeHighShelf(16000.0, 0.5, airPresenceGainDb, sampleRate)

            return FallbackDspSnapshot(
                generation = gen,
                isEnabled = isEnabled,
                isBitPerfectBypass = isBitPerfectBypass,
                preAmpGainDb = preAmpGainDb,
                bassBoostGainDb = bassBoostGainDb,
                trebleGainDb = trebleGainDb,
                harmonicExciterLevel = harmonicExciterLevel,
                clarityEnhancerGain = clarityEnhancerGain,
                stereoExpansionMultiplier = stereoExpansionMultiplier,
                dvcVolume = dvcVolume,
                replayGainMultiplier = replayGainMultiplier,
                ditherStrength = ditherStrength,
                outputBitDepth = outputBitDepth,
                warmSaturationLevel = warmSaturationLevel,
                triodeWarmthLevel = triodeWarmthLevel,
                pentodeTapeLevel = pentodeTapeLevel,
                crossfeedLevel = crossfeedLevel,
                limiterEnabled = limiterEnabled,
                limiterThresholdDb = limiterThresholdDb,
                subBassMonoEnabled = subBassMonoEnabled,
                channelBalance = channelBalance,
                invertPhase = invertPhase,
                airPresenceGainDb = airPresenceGainDb,
                bandGainsDb = gainsCopy,
                biquadsCoeffs = biquads,
                bassShelfCoeff = bass,
                trebleShelfCoeff = treble,
                detailHpfCoeff = detail,
                clarityFilterCoeff = clarity,
                crossfeedLpfCoeff = crossfeed,
                dcRemovalCoeff = dcRemoval,
                dcBlockerCoeff = dcBlocker,
                aaFilterCoeff = aa,
                subBassFilterCoeff = subBass,
                airFilterCoeff = air
            )
        }
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_24BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_32BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioFormat.NOT_SET
        }

        rebuildSnapshot()

        // Always process/output 32-bit Float PCM for maximum dynamic range.
        val outputFormat = AudioFormat(
            inputAudioFormat.sampleRate,
            inputAudioFormat.channelCount,
            C.ENCODING_PCM_FLOAT
        )

        // Clear history on format change to prevent glitches/noise
        osSamplesL.fill(0.0)
        osSamplesR.fill(0.0)
        ditherErrorL = 0.0
        ditherErrorR = 0.0

        return outputFormat
    }

    fun setBandGain(bandIndex: Int, gainDb: Double) {
        if (bandIndex in bandGainsDb.indices) {
            bandGainsDb[bandIndex] = gainDb
            rebuildSnapshot()
        }
    }

    fun updateAllFiltersLive() {
        rebuildSnapshot()
    }

    fun setAirPresenceGain(gainDb: Double) {
        airPresenceGainDb = gainDb
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (inputBuffer.remaining() == 0) return

        val channelCount = inputAudioFormat.channelCount.coerceIn(1, frameSamples.size)
        val encoding = inputAudioFormat.encoding

        val bytesPerFrameIn = channelCount * when (encoding) {
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_16BIT -> 2
            else -> 4
        }
        val sampleCount = inputBuffer.remaining() /
            (when (encoding) {
                C.ENCODING_PCM_24BIT -> 3
                C.ENCODING_PCM_16BIT -> 2
                else -> 4
            })

        val outputBuffer = replaceOutputBuffer(sampleCount * 4)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        // 1. Take one coherent snapshot for this entire buffer
        val snap = activeSnapshot

        // 2. Synchronize coefficients to render-owned filters if snapshot generation changed
        if (snap.generation != appliedGeneration) {
            for (i in snap.biquadsCoeffs.indices) {
                biquadsL[i].setCoefficients(snap.biquadsCoeffs[i])
                biquadsR[i].setCoefficients(snap.biquadsCoeffs[i])
            }
            bassShelfL.setCoefficients(snap.bassShelfCoeff)
            bassShelfR.setCoefficients(snap.bassShelfCoeff)
            trebleShelfL.setCoefficients(snap.trebleShelfCoeff)
            trebleShelfR.setCoefficients(snap.trebleShelfCoeff)
            detailHPFL.setCoefficients(snap.detailHpfCoeff)
            detailHPFR.setCoefficients(snap.detailHpfCoeff)
            clarityFilterL.setCoefficients(snap.clarityFilterCoeff)
            clarityFilterR.setCoefficients(snap.clarityFilterCoeff)
            crossfeedLPFL.setCoefficients(snap.crossfeedLpfCoeff)
            crossfeedLPFR.setCoefficients(snap.crossfeedLpfCoeff)
            dcRemovalL.setCoefficients(snap.dcRemovalCoeff)
            dcRemovalR.setCoefficients(snap.dcRemovalCoeff)
            dcBlockerL.setCoefficients(snap.dcBlockerCoeff)
            dcBlockerR.setCoefficients(snap.dcBlockerCoeff)
            aaFilterL.setCoefficients(snap.aaFilterCoeff)
            aaFilterR.setCoefficients(snap.aaFilterCoeff)
            subBassFilterL.setCoefficients(snap.subBassFilterCoeff)
            subBassFilterR.setCoefficients(snap.subBassFilterCoeff)
            airFilterL.setCoefficients(snap.airFilterCoeff)
            airFilterR.setCoefficients(snap.airFilterCoeff)
            appliedGeneration = snap.generation
        }

        val bypass = snap.isBitPerfectBypass || !snap.isEnabled

        val preAmpMultiplier = 10.0.pow(snap.preAmpGainDb / 20.0)
        val replayGain = if (snap.replayGainMultiplier > 0.0) snap.replayGainMultiplier else 1.0
        val totalPreGain = preAmpMultiplier * replayGain
        val dvc = snap.dvcVolume
        val warmSat = snap.warmSaturationLevel
        val triode = snap.triodeWarmthLevel
        val pentode = snap.pentodeTapeLevel
        val exciterLevel = snap.harmonicExciterLevel
        val clarityGainLocal = snap.clarityEnhancerGain
        val crossfeedLocal = snap.crossfeedLevel
        val stereoExpLocal = snap.stereoExpansionMultiplier
        val subMonoLocal = snap.subBassMonoEnabled
        val balanceLocal = snap.channelBalance
        val invPhaseLocal = snap.invertPhase
        val airGainLocal = snap.airPresenceGainDb
        val limiterOn = snap.limiterEnabled
        val limThresh = 10.0.pow(snap.limiterThresholdDb / 20.0)
        val bassLocal = snap.bassBoostGainDb
        val trebleLocal = snap.trebleGainDb

        val ditherOn = snap.ditherStrength > 0.0 && snap.outputBitDepth in 1..31
        val lsb = if (ditherOn) 1.0 / (2.0.pow(snap.outputBitDepth.toDouble() - 1.0)) else 0.0

        val nonlinearEngaged = (warmSat + triode + pentode) > 0.0 || exciterLevel > 0.0

        while (inputBuffer.remaining() >= bytesPerFrameIn) {
            for (ch in 0 until channelCount) {
                frameSamples[ch] = when (encoding) {
                    C.ENCODING_PCM_FLOAT -> inputBuffer.float.toDouble()
                    C.ENCODING_PCM_16BIT -> inputBuffer.short.toDouble() / 32768.0
                    C.ENCODING_PCM_24BIT -> {
                        val b0 = inputBuffer.get().toInt() and 0xFF
                        val b1 = inputBuffer.get().toInt() and 0xFF
                        val b2 = inputBuffer.get().toInt() and 0xFF
                        val raw24 = (b2 shl 16) or (b1 shl 8) or b0
                        val s24 = if (raw24 and 0x800000 != 0) raw24 or -0x1000000 else raw24
                        s24.toDouble() / 8388608.0
                    }
                    C.ENCODING_PCM_32BIT -> inputBuffer.int.toDouble() / 2147483648.0
                    else -> 0.0
                }
            }

            if (!bypass) {
                for (ch in 0 until channelCount) {
                    frameSamples[ch] *= totalPreGain
                }

                if (nonlinearEngaged) {
                    for (ch in 0 until minOf(channelCount, 2)) {
                        val history = if (ch == 0) osSamplesL else osSamplesR

                        history[0] = history[1]
                        history[1] = history[2]
                        history[2] = history[3]
                        history[3] = frameSamples[ch]

                        val v0 = history[0]
                        val v1 = history[1]
                        val v2 = history[2]
                        val v3 = history[3]

                        val a = -0.5 * v0 + 1.5 * v1 - 1.5 * v2 + 0.5 * v3
                        val b = v0 - 2.5 * v1 + 2.0 * v2 - 0.5 * v3
                        val c = -0.5 * v0 + 0.5 * v2

                        upsampledPair[0] = a * 0.125 + b * 0.25 + c * 0.5 + v1
                        upsampledPair[1] = v2

                        for (i in 0..1) {
                            var sm = upsampledPair[i]

                            // Symmetric 3rd-harmonic tape saturation
                            if (warmSat > 0.0) {
                                sm += warmSat * (sm.pow(3.0) - sm)
                            }

                            // Dedicated asymmetric 2nd-harmonic triode vacuum tube warmth
                            if (triode > 0.0) {
                                sm += triode * 0.25 * (sm * sm * (if (sm >= 0.0) 1.0 else -0.5) - 0.1 * sm)
                            }

                            if (pentode > 0.0) {
                                sm -= pentode * 0.1 * (sm * sm * sm)
                            }

                            if (exciterLevel > 0) {
                                val detail = if (ch == 0) detailHPFL.process(sm) else detailHPFR.process(sm)
                                sm += (detail.pow(3.0) * 0.5 + detail.pow(2.0) * 0.3) * exciterLevel
                            }

                            upsampledPair[i] = sm
                        }

                        frameSamples[ch] = (upsampledPair[0] + upsampledPair[1]) * 0.5
                    }
                }

                if (clarityGainLocal != 0.0) {
                    for (ch in 0 until minOf(channelCount, 2)) {
                        frameSamples[ch] = if (ch == 0) clarityFilterL.process(frameSamples[ch]) else clarityFilterR.process(frameSamples[ch])
                    }
                }

                if (bassLocal != 0.0) {
                    for (ch in 0 until minOf(channelCount, 2)) {
                        frameSamples[ch] = if (ch == 0) bassShelfL.process(frameSamples[ch]) else bassShelfR.process(frameSamples[ch])
                    }
                }

                for (i in snap.bandGainsDb.indices) {
                    if (snap.bandGainsDb[i] != 0.0) {
                        for (ch in 0 until minOf(channelCount, 2)) {
                            frameSamples[ch] = if (ch == 0) biquadsL[i].process(frameSamples[ch]) else biquadsR[i].process(frameSamples[ch])
                        }
                    }
                }

                if (channelCount >= 2) {
                    if (crossfeedLocal > 0) {
                        val lowL = crossfeedLPFL.process(frameSamples[0])
                        val lowR = crossfeedLPFR.process(frameSamples[1])
                        val amt = crossfeedLocal * 0.3
                        frameSamples[0] = frameSamples[0] - amt * lowL + amt * lowR
                        frameSamples[1] = frameSamples[1] - amt * lowR + amt * lowL
                    }

                    if (stereoExpLocal != 1.0) {
                        val mid = (frameSamples[0] + frameSamples[1]) * 0.5
                        val side = (frameSamples[0] - frameSamples[1]) * 0.5 * stereoExpLocal
                        frameSamples[0] = mid + side
                        frameSamples[1] = mid - side
                    }

                    if (subMonoLocal) {
                        val subL = subBassFilterL.process(frameSamples[0])
                        val subR = subBassFilterR.process(frameSamples[1])
                        val monoSub = (subL + subR) * 0.5
                        frameSamples[0] = (frameSamples[0] - subL) + monoSub
                        frameSamples[1] = (frameSamples[1] - subR) + monoSub
                    }

                    if (invPhaseLocal) frameSamples[1] = -frameSamples[1]

                    if (balanceLocal != 0.0) {
                        val panAngle = (balanceLocal.coerceIn(-1.0, 1.0) + 1.0) * (Math.PI / 4.0)
                        frameSamples[0] *= cos(panAngle) * 1.4142135623730951
                        frameSamples[1] *= sin(panAngle) * 1.4142135623730951
                    }
                }

                if (trebleLocal != 0.0) {
                    for (ch in 0 until minOf(channelCount, 2)) {
                        frameSamples[ch] = if (ch == 0) trebleShelfL.process(frameSamples[ch]) else trebleShelfR.process(frameSamples[ch])
                    }
                }

                if (airGainLocal != 0.0) {
                    for (ch in 0 until minOf(channelCount, 2)) {
                        frameSamples[ch] = if (ch == 0) airFilterL.process(frameSamples[ch]) else airFilterR.process(frameSamples[ch])
                    }
                }

                if (nonlinearEngaged) {
                    for (ch in 0 until minOf(channelCount, 2)) {
                        frameSamples[ch] = if (ch == 0) aaFilterL.process(frameSamples[ch]) else aaFilterR.process(frameSamples[ch])
                    }
                }

                if (limiterOn) {
                    for (ch in 0 until channelCount) {
                        val x = frameSamples[ch]
                        val absX = kotlin.math.abs(x)
                        if (absX > limThresh) {
                            val over = absX - limThresh
                            val compressed = limThresh + limThresh * tanh(over / limThresh)
                            frameSamples[ch] = if (x > 0.0) compressed else -compressed
                        }
                    }
                } else {
                    for (ch in 0 until channelCount) {
                        frameSamples[ch] = frameSamples[ch].coerceIn(-1.0, 1.0)
                    }
                }

                for (ch in 0 until channelCount) {
                    frameSamples[ch] *= dvc
                }

                if (ditherOn) {
                    val rawL = (nextRandomDouble() - 0.5 + nextRandomDouble() - 0.5) * lsb * snap.ditherStrength
                    val shapedL = rawL - 0.5 * ditherErrorL
                    ditherErrorL = rawL
                    frameSamples[0] = kotlin.math.round((frameSamples[0] + shapedL) / lsb) * lsb

                    if (channelCount > 1) {
                        val rawR = (nextRandomDouble() - 0.5 + nextRandomDouble() - 0.5) * lsb * snap.ditherStrength
                        val shapedR = rawR - 0.5 * ditherErrorR
                        ditherErrorR = rawR
                        frameSamples[1] = kotlin.math.round((frameSamples[1] + shapedR) / lsb) * lsb
                    }
                }
            }

            for (ch in 0 until channelCount) {
                outputBuffer.putFloat(frameSamples[ch].toFloat())
            }
        }

        outputBuffer.flip()
    }

    override fun onFlush() {
        osSamplesL.fill(0.0)
        osSamplesR.fill(0.0)
        ditherErrorL = 0.0
        ditherErrorR = 0.0
        biquadsL.forEach { it.reset() }
        biquadsR.forEach { it.reset() }
        bassShelfL.reset(); bassShelfR.reset()
        trebleShelfL.reset(); trebleShelfR.reset()
        detailHPFL.reset(); detailHPFR.reset()
        clarityFilterL.reset(); clarityFilterR.reset()
        crossfeedLPFL.reset(); crossfeedLPFR.reset()
        airFilterL.reset(); airFilterR.reset()
        dcRemovalL.reset(); dcRemovalR.reset()
        aaFilterL.reset(); aaFilterR.reset()
        dcBlockerL.reset(); dcBlockerR.reset()
        subBassFilterL.reset(); subBassFilterR.reset()
        appliedGeneration = 0L
    }

    override fun onReset() {
        onFlush()
    }
}
