package com.tensorix.antigravityplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Audiophile 64-bit Double-Precision DSP Engine (fallback-path processor).
 *
 * Contract mirrors the native C++ DSP (see docs/DSPOwnership.md):
 *  - Neutral-by-default chain: colouration stages are opt-in so a Flat/default
 *    configuration stays transparent apart from DC blocking.
 *  - Dither is REAL requantization dither: gated behind an explicit strength
 *    AND an integer target depth below 32 bits; never unconditional noise.
 *  - Anti-aliasing guard engages only while nonlinear stages are active.
 *  - Zero per-sample heap allocation on the render thread.
 */
@UnstableApi
class Audiophile64BitDspProcessor : BaseAudioProcessor() {

    @Volatile
    var isEnabled: Boolean = true

    @Volatile
    var isBitPerfectBypass: Boolean = false

    @Volatile
    var isTurboMode: Boolean = true // High CPU, ultra-high precision

    @Volatile
    var preAmpGainDb: Double = 0.0 // Unity gain baseline

    @Volatile
    var bassBoostGainDb: Double = 0.0

    @Volatile
    var trebleGainDb: Double = 0.0

    @Volatile
    var harmonicExciterLevel: Double = 0.0

    @Volatile
    var clarityEnhancerGain: Double = 0.0

    @Volatile
    var stereoExpansionMultiplier: Double = 1.0 // 1.0 = neutral, >1.0 = wider

    @Volatile
    var dvcVolume: Double = 1.0 // Direct Volume Control (software gain stage)

    @Volatile
    var ditherStrength: Double = 0.0

    @Volatile
    var outputBitDepth: Int = 24 // Target depth for requantization dither

    @Volatile
    var warmSaturationLevel: Double = 0.0

    @Volatile
    var triodeWarmthLevel: Double = 0.0

    @Volatile
    var pentodeTapeLevel: Double = 0.0

    @Volatile
    var dynamicLoudnessEnabled: Boolean = false // Fletcher-Munson compensation (reserved)

    @Volatile
    var crossfeedLevel: Double = 0.0

    @Volatile
    var limiterThresholdDb: Double = 0.0

    @Volatile
    var limiterEnabled: Boolean = false // Optional soft-knee safety stage

    @Volatile
    var replayGainMultiplier: Double = 1.0

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
    var channelBalance: Double = 0.0 // -1.0 to 1.0

    @Volatile
    var phaseCorrelation: Float = 1.0f

    private var ditherErrorL = 0.0
    private var ditherErrorR = 0.0

    @Volatile
    var invertPhase: Boolean = false

    @Volatile
    var airPresenceGainDb: Double = 0.0

    val currentSampleRate: Int
        get() = if (inputAudioFormat != AudioProcessor.AudioFormat.NOT_SET) inputAudioFormat.sampleRate else 0

    // 10-Band EQ Gains in dB (-15.0 to +15.0 dB)
    private val bandGainsDb = DoubleArray(10)
    private val bandCenterFreqs = doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)

    // Biquad filter state per channel (Left & Right)
    private var biquadsL = Array(10) { BiquadFilter() }
    private var biquadsR = Array(10) { BiquadFilter() }
    private var bassShelfL = BiquadFilter()
    private var bassShelfR = BiquadFilter()
    private var trebleShelfL = BiquadFilter()
    private var trebleShelfR = BiquadFilter()

    // Detail filters
    private var detailHPFL = BiquadFilter()
    private var detailHPFR = BiquadFilter()

    private var clarityFilterL = BiquadFilter()
    private var clarityFilterR = BiquadFilter()

    private var crossfeedLPFL = BiquadFilter()
    private var crossfeedLPFR = BiquadFilter()

    private var airFilterL = BiquadFilter()
    private var airFilterR = BiquadFilter()

    private var dcRemovalL = BiquadFilter()
    private var dcRemovalR = BiquadFilter()

    private var aaFilterL = BiquadFilter()
    private var aaFilterR = BiquadFilter()

    private var dcBlockerL = BiquadFilter()
    private var dcBlockerR = BiquadFilter()

    @Volatile
    var subBassMonoEnabled: Boolean = false

    private var subBassFilterL = BiquadFilter()
    private var subBassFilterR = BiquadFilter()

    // Waveshaping interpolation history
    private var osSamplesL = DoubleArray(4) { 0.0 }
    private var osSamplesR = DoubleArray(4) { 0.0 }

    private var rngState: Long = System.nanoTime()

    private fun nextRandomDouble(): Double {
        rngState = rngState xor (rngState ushr 12)
        rngState = rngState xor (rngState shl 25)
        rngState = rngState xor (rngState ushr 27)
        val v = (rngState * 0x2545F4914F6CDD1DL)
        return (v ushr 1).toDouble() / Long.MAX_VALUE.toDouble()
    }

    // Render-thread scratch: zero allocation in the hot loop.
    private val frameSamples = DoubleArray(8)
    private val upsampledPair = DoubleArray(2)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_24BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_32BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioFormat.NOT_SET
        }

        val sampleRate = inputAudioFormat.sampleRate.toDouble()
        updateFilterCoefficients(sampleRate)

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
            val fs = if (inputAudioFormat.sampleRate > 0) inputAudioFormat.sampleRate.toDouble() else 44100.0
            updateFilterCoefficients(fs)
        }
    }

    fun updateAllFiltersLive() {
        val fs = if (inputAudioFormat.sampleRate > 0) inputAudioFormat.sampleRate.toDouble() else 44100.0
        updateFilterCoefficients(fs)
    }

    private fun updateFilterCoefficients(sampleRate: Double) {
        if (sampleRate <= 0.0) return

        for (i in bandCenterFreqs.indices) {
            val f0 = bandCenterFreqs[i]
            val gain = bandGainsDb[i]
            biquadsL[i].setPeakingEq(f0, 1.414, gain, sampleRate)
            biquadsR[i].setPeakingEq(f0, 1.414, gain, sampleRate)
        }

        bassShelfL.setLowShelf(80.0, 0.707, bassBoostGainDb, sampleRate)
        bassShelfR.setLowShelf(80.0, 0.707, bassBoostGainDb, sampleRate)

        trebleShelfL.setHighShelf(10000.0, 0.707, trebleGainDb, sampleRate)
        trebleShelfR.setHighShelf(10000.0, 0.707, trebleGainDb, sampleRate)

        // Exciter HPF drives two interpolated sub-samples per frame (2x rate):
        // design at 2*fs to preserve the nominal 7.5 kHz corner.
        detailHPFL.setHighPass(7500.0, 0.707, sampleRate * 2.0)
        detailHPFR.setHighPass(7500.0, 0.707, sampleRate * 2.0)

        clarityFilterL.setPeakingEq(3200.0, 1.0, clarityEnhancerGain, sampleRate)
        clarityFilterR.setPeakingEq(3200.0, 1.0, clarityEnhancerGain, sampleRate)

        crossfeedLPFL.setLowPass(700.0, 0.5, sampleRate)
        crossfeedLPFR.setLowPass(700.0, 0.5, sampleRate)

        dcRemovalL.setHighPass(2.0, 0.707, sampleRate)
        dcRemovalR.setHighPass(2.0, 0.707, sampleRate)

        dcBlockerL.setHighPass(1.0, 0.707, sampleRate)
        dcBlockerR.setHighPass(1.0, 0.707, sampleRate)

        // AA guard tracks Nyquist; engaged only while nonlinear stages run.
        val aaCorner = minOf(20000.0, sampleRate * 0.45)
        aaFilterL.setLowPass(aaCorner, 0.707, sampleRate)
        aaFilterR.setLowPass(aaCorner, 0.707, sampleRate)

        subBassFilterL.setLowPass(80.0, 0.707, sampleRate)
        subBassFilterR.setLowPass(80.0, 0.707, sampleRate)

        airFilterL.setHighShelf(16000.0, 0.5, airPresenceGainDb, sampleRate)
        airFilterR.setHighShelf(16000.0, 0.5, airPresenceGainDb, sampleRate)
    }

    fun setAirPresenceGain(gainDb: Double) {
        airPresenceGainDb = gainDb
        val sampleRate = if (inputAudioFormat.sampleRate > 0) inputAudioFormat.sampleRate.toDouble() else 44100.0
        airFilterL.setHighShelf(16000.0, 0.5, gainDb, sampleRate)
        airFilterR.setHighShelf(16000.0, 0.5, gainDb, sampleRate)
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

        val bypass = isBitPerfectBypass || !isEnabled

        // Per-buffer control snapshot: coherent values for this whole block,
        // no torn mid-buffer mixes.
        val preAmpMultiplier = 10.0.pow(preAmpGainDb / 20.0)
        val rgGain = replayGainMultiplier
        val dvc = dvcVolume
        val warmSat = warmSaturationLevel
        val triode = triodeWarmthLevel
        val pentode = pentodeTapeLevel
        val exciterLevel = harmonicExciterLevel
        val clarityGainLocal = clarityEnhancerGain
        val crossfeedLocal = crossfeedLevel
        val stereoExpLocal = stereoExpansionMultiplier
        val subMonoLocal = subBassMonoEnabled
        val balanceLocal = channelBalance
        val invPhaseLocal = invertPhase
        val airGainLocal = airPresenceGainDb
        val limiterOn = limiterEnabled
        val limThresh = 10.0.pow(limiterThresholdDb / 20.0)
        val bassLocal = bassBoostGainDb
        val trebleLocal = trebleGainDb

        // Honest requantization dither (mirrors native DSP): TPDF noise plus an
        // ACTUAL quantization step onto the selected target-depth grid. Inert
        // unless explicitly enabled with a depth below 32 bits - never
        // unconditional noise into a float transport.
        val ditherOn = ditherStrength > 0.0 && outputBitDepth in 1..31
        val lsb = if (ditherOn) 1.0 / (2.0.pow(outputBitDepth.toDouble() - 1.0)) else 0.0

        // Nonlinear stages engaged -> their harmonics need band-limiting.
        val nonlinearEngaged =
            (warmSat + triode + pentode) > 0.0 || exciterLevel > 0.0

        while (inputBuffer.remaining() >= bytesPerFrameIn) {
            // Decode one frame
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
                // Pre-amp
                for (ch in 0 until channelCount) {
                    frameSamples[ch] *= preAmpMultiplier
                }

                // Interpolated waveshaping stage (saturation/exciter). Two
                // interpolated sub-samples per frame are shaped and averaged;
                // NOT a full anti-aliased oversampler. Skipped entirely while
                // disengaged so neutral settings stay transparent.
                if (nonlinearEngaged) {
                    for (ch in 0 until channelCount) {
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

                            if (warmSat > 0 || triode > 0) {
                                val warmFactor = warmSat + triode
                                sm += warmFactor * (sm.pow(3.0) - sm)
                                if (triode > 0) {
                                    sm += triode * 0.15 * (sm * sm * (if (sm > 0) 1.0 else -1.0))
                                }
                            }

                            if (pentode > 0) {
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

                // ReplayGain
                for (ch in 0 until channelCount) {
                    frameSamples[ch] *= rgGain
                }

                // DC removal/blocking (always-on safety, transparent for music)
                for (ch in 0 until channelCount) {
                    var s = frameSamples[ch]
                    s = if (ch == 0) dcRemovalL.process(s) else dcRemovalR.process(s)
                    s = if (ch == 0) dcBlockerL.process(s) else dcBlockerR.process(s)
                    frameSamples[ch] = s
                }

                if (clarityGainLocal != 0.0) {
                    for (ch in 0 until channelCount) {
                        frameSamples[ch] = if (ch == 0) clarityFilterL.process(frameSamples[ch]) else clarityFilterR.process(frameSamples[ch])
                    }
                }

                if (bassLocal != 0.0) {
                    for (ch in 0 until channelCount) {
                        frameSamples[ch] = if (ch == 0) bassShelfL.process(frameSamples[ch]) else bassShelfR.process(frameSamples[ch])
                    }
                }

                for (i in bandGainsDb.indices) {
                    if (bandGainsDb[i] != 0.0) {
                        for (ch in 0 until channelCount) {
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

                for (ch in 0 until channelCount) {
                    if (trebleLocal != 0.0) {
                        frameSamples[ch] = if (ch == 0) trebleShelfL.process(frameSamples[ch]) else trebleShelfR.process(frameSamples[ch])
                    }

                    if (airGainLocal != 0.0) {
                        frameSamples[ch] = if (ch == 0) airFilterL.process(frameSamples[ch]) else airFilterR.process(frameSamples[ch])
                    }

                    if (nonlinearEngaged) {
                        frameSamples[ch] = if (ch == 0) aaFilterL.process(frameSamples[ch]) else aaFilterR.process(frameSamples[ch])
                    }

                    if (limiterOn) {
                        val absVal = kotlin.math.abs(frameSamples[ch])
                        if (absVal > limThresh) {
                            val over = absVal - limThresh
                            val compressed = limThresh + limThresh * tanh(over / limThresh)
                            frameSamples[ch] = if (frameSamples[ch] > 0) compressed else -compressed
                        }
                    } else if (frameSamples[ch] > 1.0 || frameSamples[ch] < -1.0) {
                        frameSamples[ch] = frameSamples[ch].coerceIn(-1.0, 1.0)
                    }

                    frameSamples[ch] *= dvc

                    if (ditherOn) {
                        val r1 = nextRandomDouble() - 0.5
                        val r2 = nextRandomDouble() - 0.5
                        val rawDither = (r1 + r2) * lsb * ditherStrength
                        val prevError = if (ch == 0) ditherErrorL else ditherErrorR
                        val shapedDither = rawDither - 0.5 * prevError
                        if (ch == 0) ditherErrorL = rawDither else ditherErrorR = rawDither
                        frameSamples[ch] = round((frameSamples[ch] + shapedDither) / lsb) * lsb
                    }
                }
            }

            // Output (both bypass and processed paths)
            for (ch in 0 until channelCount) {
                outputBuffer.putFloat(frameSamples[ch].toFloat().coerceIn(-1.0f, 1.0f))
            }

            // Sample-peak telemetry with decay (NOT an oversampled true-peak
            // measurement - see truth audit).
            val pkL = kotlin.math.abs(frameSamples[0])
            val pkR = if (channelCount > 1) kotlin.math.abs(frameSamples[1]) else pkL
            peakL = (peakL * 0.92).coerceAtLeast(pkL)
            peakR = (peakR * 0.92).coerceAtLeast(pkR)

            if (channelCount > 1) {
                val lVal = frameSamples[0]
                val rVal = frameSamples[1]
                val corrDen = sqrt((lVal * lVal + 1e-12) * (rVal * rVal + 1e-12))
                if (corrDen > 1e-12) {
                    val currentCorr = ((lVal * rVal) / corrDen).toFloat().coerceIn(-1.0f, 1.0f)
                    phaseCorrelation = (phaseCorrelation * 0.95f) + (currentCorr * 0.05f)
                }
            }
        }

        outputBuffer.flip()
    }

    override fun onReset() {
        for (bq in biquadsL) bq.reset()
        for (bq in biquadsR) bq.reset()
        bassShelfL.reset(); bassShelfR.reset()
        trebleShelfL.reset(); trebleShelfR.reset()
        detailHPFL.reset(); detailHPFR.reset()
        clarityFilterL.reset(); clarityFilterR.reset()
        crossfeedLPFL.reset(); crossfeedLPFR.reset()
        airFilterL.reset(); airFilterR.reset()
        dcRemovalL.reset(); dcRemovalR.reset()
        dcBlockerL.reset(); dcBlockerR.reset()
        aaFilterL.reset(); aaFilterR.reset()
        subBassFilterL.reset(); subBassFilterR.reset()

        osSamplesL.fill(0.0)
        osSamplesR.fill(0.0)
        ditherErrorL = 0.0
        ditherErrorR = 0.0
        peakL = 0.0
        peakR = 0.0
    }
}
