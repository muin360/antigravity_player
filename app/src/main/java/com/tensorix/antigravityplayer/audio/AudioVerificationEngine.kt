package com.tensorix.antigravityplayer.audio

import android.content.Context
import android.os.Build
import com.tensorix.antigravityplayer.player.PlaybackService
import androidx.media3.common.util.UnstableApi

@UnstableApi
object AudioVerificationEngine {

    fun buildCanonicalSnapshot(
        context: Context,
        trackInfo: AudioTrackInfo,
        isDspActive: Boolean,
        activeRoute: AudioRouteCapability?,
        dspProcessor: Audiophile64BitDspProcessor? = null
    ): CanonicalAudioRuntimeSnapshot {
        val isBitPerfectRequested = !isDspActive && (dspProcessor?.isBitPerfectBypass == true)
        val hardwareReport = HardwareHiFiVerifier.probeHardwareState(context, trackInfo.sampleRateHz, trackInfo.bitDepth, isBitPerfectRequested)
        val nativeInfo = OboeAudioSink.currentStreamInfo

        // 1. Source Format
        val source = AudioFormatSnapshot(
            sampleRate = AudioEvidence(
                trackInfo.sampleRateHz.takeIf { it > 0 } ?: 0,
                EvidenceSource.SOURCE_METADATA,
                if (trackInfo.sampleRateHz > 0) Confidence.VERIFIED else Confidence.UNKNOWN
            ),
            bitDepth = AudioEvidence(
                trackInfo.bitDepth.takeIf { it > 0 } ?: 0,
                EvidenceSource.SOURCE_METADATA,
                if (trackInfo.bitDepth > 0) Confidence.VERIFIED else Confidence.UNKNOWN
            ),
            channels = AudioEvidence(
                trackInfo.channels.takeIf { it > 0 } ?: 0,
                EvidenceSource.SOURCE_METADATA,
                if (trackInfo.channels > 0) Confidence.VERIFIED else Confidence.UNKNOWN
            ),
            encoding = AudioEvidence(
                trackInfo.codec,
                EvidenceSource.SOURCE_METADATA,
                if (trackInfo.codec.isNotBlank() && !trackInfo.codec.contains("Unknown")) Confidence.VERIFIED else Confidence.INFERRED
            )
        )

        // 2. Decoder Format
        val decoder = AudioFormatSnapshot(
            sampleRate = AudioEvidence(source.sampleRate.value, EvidenceSource.AUDIO_TRACK, source.sampleRate.confidence),
            bitDepth = AudioEvidence(32, EvidenceSource.AUDIO_TRACK, Confidence.HIGH_CONFIDENCE),
            channels = AudioEvidence(source.channels.value, EvidenceSource.AUDIO_TRACK, source.channels.confidence),
            encoding = AudioEvidence("PCM_FLOAT", EvidenceSource.AUDIO_TRACK, Confidence.HIGH_CONFIDENCE)
        )

        // 3. Processing Format (Internal 64-bit double-precision DSP calculations)
        val processing = AudioFormatSnapshot(
            sampleRate = AudioEvidence(source.sampleRate.value, EvidenceSource.DSP_ENGINE, source.sampleRate.confidence),
            bitDepth = AudioEvidence(64, EvidenceSource.DSP_ENGINE, Confidence.VERIFIED),
            channels = AudioEvidence(source.channels.value, EvidenceSource.DSP_ENGINE, source.channels.confidence),
            encoding = AudioEvidence("DSP_DOUBLE_PRECISION", EvidenceSource.DSP_ENGINE, Confidence.VERIFIED)
        )

        // 4. Requested Output Format
        val requested = AudioFormatSnapshot(
            sampleRate = AudioEvidence(source.sampleRate.value, EvidenceSource.AUDIO_TRACK, source.sampleRate.confidence),
            bitDepth = AudioEvidence(source.bitDepth.value, EvidenceSource.AUDIO_TRACK, source.bitDepth.confidence),
            channels = AudioEvidence(source.channels.value, EvidenceSource.AUDIO_TRACK, source.channels.confidence),
            encoding = AudioEvidence("PCM", EvidenceSource.AUDIO_TRACK, Confidence.VERIFIED)
        )

        // 5. Actual Output Telemetry (Rule 11: structured format, no string matching)
        val actual = if (nativeInfo != null) {
            val bitDepth = when {
                nativeInfo.bitDepth > 0 -> nativeInfo.bitDepth
                nativeInfo.formatId == OboeBridge.AudioFormatId.I16 -> 16
                nativeInfo.formatId == OboeBridge.AudioFormatId.I24 -> 24
                nativeInfo.formatId == OboeBridge.AudioFormatId.FLOAT || nativeInfo.formatId == OboeBridge.AudioFormatId.I32 -> 32
                else -> 0
            }
            AudioFormatSnapshot(
                sampleRate = AudioEvidence(nativeInfo.sampleRate, EvidenceSource.OBOE_STREAM, Confidence.VERIFIED),
                bitDepth = AudioEvidence(bitDepth, EvidenceSource.OBOE_STREAM, if (bitDepth > 0) Confidence.VERIFIED else Confidence.UNKNOWN),
                channels = AudioEvidence(nativeInfo.channelCount, EvidenceSource.OBOE_STREAM, Confidence.VERIFIED),
                encoding = AudioEvidence(nativeInfo.format, EvidenceSource.OBOE_STREAM, Confidence.VERIFIED)
            )
        } else if (hardwareReport.isDirectOutputActive) {
            AudioFormatSnapshot(
                sampleRate = AudioEvidence(hardwareReport.actualOutputSampleRate, EvidenceSource.HAL_PARAMETER, Confidence.HIGH_CONFIDENCE),
                bitDepth = AudioEvidence(source.bitDepth.value, EvidenceSource.AUDIO_TRACK, Confidence.INFERRED),
                channels = AudioEvidence(2, EvidenceSource.AUDIO_TRACK, Confidence.INFERRED),
                encoding = AudioEvidence(hardwareReport.actualAudioSinkType, EvidenceSource.HAL_PARAMETER, Confidence.HIGH_CONFIDENCE)
            )
        } else {
            AudioFormatSnapshot(
                sampleRate = AudioEvidence(hardwareReport.actualOutputSampleRate, EvidenceSource.HAL_PARAMETER, Confidence.HIGH_CONFIDENCE),
                bitDepth = AudioEvidence(0, EvidenceSource.UNKNOWN, Confidence.UNKNOWN),
                channels = AudioEvidence(0, EvidenceSource.UNKNOWN, Confidence.UNKNOWN),
                encoding = AudioEvidence("Mixed (AudioFlinger)", EvidenceSource.ANDROID_AUDIO_DEVICE, Confidence.INFERRED)
            )
        }

        // 6. Active Route Evidence
        val routeEvidence = if (activeRoute != null) {
            AudioEvidence(activeRoute.routeType, EvidenceSource.ANDROID_AUDIO_DEVICE, Confidence.VERIFIED)
        } else {
            AudioEvidence(AudioOutputRouteType.OTHER, EvidenceSource.UNKNOWN, Confidence.UNKNOWN)
        }

        val apiEvidence = AudioEvidence(
            if (nativeInfo != null) {
                if (nativeInfo.apiId == OboeBridge.AudioApiId.OPENSLES) AudioOutputApi.OPENSL_ES else AudioOutputApi.AAUDIO
            } else AudioOutputApi.AUDIOTRACK,
            if (nativeInfo != null) EvidenceSource.OBOE_STREAM else EvidenceSource.AUDIO_TRACK,
            if (nativeInfo != null) Confidence.VERIFIED else Confidence.HIGH_CONFIDENCE
        )

        // 7. Direct & Mixer Path State
        val isDirectActive = when {
            nativeInfo != null -> nativeInfo.sharingModeId == OboeBridge.SharingModeId.EXCLUSIVE || nativeInfo.sharingMode == "EXCLUSIVE"
            hardwareReport.isDirectOutputActive -> true
            else -> false
        }
        val directConfidence = when {
            nativeInfo != null -> Confidence.VERIFIED
            hardwareReport.isDirectOutputActive -> Confidence.HIGH_CONFIDENCE
            else -> Confidence.INFERRED
        }
        val directPathState = when {
            isDirectActive -> DirectPathState.DIRECT_ACTIVE
            hardwareReport.isDirectOutputSupported -> DirectPathState.DIRECT_CAPABILITY
            else -> DirectPathState.DIRECT_UNKNOWN
        }

        val isMixerActive = !isDirectActive
        val mixerPathState = when {
            isDirectActive -> MixerPathState.DIRECT_ACTIVE
            hardwareReport.isVendorHiFiActive && hardwareReport.isDirectOutputActive -> MixerPathState.DIRECT_ACTIVE
            else -> MixerPathState.MIXER_ACTIVE
        }

        val resamplerActive = source.sampleRate.value > 0 && actual.sampleRate.value > 0 && source.sampleRate.value != actual.sampleRate.value
        val resamplerStateValue = when {
            resamplerActive -> "ACTIVE"
            nativeInfo != null && nativeInfo.sampleRate == source.sampleRate.value -> "OFF"
            isDirectActive -> "OFF"
            else -> "BYPASS"
        }

        // 8. DAC State
        val detector = UniversalHardwareDetector(context)
        val dacDevice = detector.detectActiveOutputDevice()
        val dacProfile = detector.detectDacHardware(dacDevice)

        val dacState = DacRuntimeState(
            modelName = AudioEvidence(dacProfile.dacModelName, EvidenceSource.HEURISTIC, dacProfile.confidence),
            vendor = AudioEvidence(dacProfile.dacManufacturer, EvidenceSource.HEURISTIC, dacProfile.confidence),
            isActive = AudioEvidence(hardwareReport.isVendorHiFiActive || isDirectActive, EvidenceSource.HAL_PARAMETER, if (isDirectActive) Confidence.VERIFIED else Confidence.HIGH_CONFIDENCE),
            maxSampleRate = AudioEvidence(dacProfile.maxSampleRateHz, EvidenceSource.HEURISTIC, dacProfile.confidence),
            maxBitDepth = AudioEvidence(dacProfile.maxBitDepth, EvidenceSource.HEURISTIC, dacProfile.confidence),
            confidence = dacProfile.confidence
        )

        val nativeStreamSnapshot = nativeInfo?.let {
            NativeStreamSnapshot(
                handle = OboeAudioSink.currentActiveHandle,
                state = it.state,
                isStarted = it.isStarted,
                sampleRate = it.sampleRate,
                channelCount = it.channelCount,
                nativeFormat = it.format,
                sharingMode = it.sharingMode,
                performanceMode = it.performanceMode,
                audioApi = it.api,
                deviceId = it.deviceId,
                framesWritten = it.framesWritten,
                underrunCount = it.underrunCount,
                bufferSizeInFrames = it.bufferSize,
                confidence = Confidence.VERIFIED,
                bitDepth = it.bitDepth,
                channelMask = it.channelMask,
                streamGeneration = it.streamGeneration,
                apiId = it.apiId,
                sharingModeId = it.sharingModeId,
                formatId = it.formatId
            )
        }

        val pipelineSnapshot = SignalProcessingPipelineSnapshot(
            sourcePcm = source,
            decoderConversion = "32-bit Float",
            dspConversion = "64-bit Double",
            isDspBypassed = !isDspActive,
            resamplerState = resamplerStateValue,
            channelRemapActive = false,
            softwareGainActive = dspProcessor != null && dspProcessor.dvcVolume < 0.999 && !isDspActive,
            ditherActive = dspProcessor != null && dspProcessor.ditherStrength > 0.001 && isDspActive,
            outputConversion = actual.encoding.value,
            dacEndpoint = dacState.modelName.value
        )

        // 9. Construct Canonical Snapshot
        val preliminarySnapshot = CanonicalAudioRuntimeSnapshot(
            source = source,
            decoder = decoder,
            processing = processing,
            requestedOutput = requested,
            actualOutput = actual,
            activeRoute = routeEvidence,
            audioApi = apiEvidence,
            sharingMode = AudioEvidence(
                nativeInfo?.sharingMode ?: if (isDirectActive) "EXCLUSIVE" else "SHARED",
                if (nativeInfo != null) EvidenceSource.OBOE_STREAM else EvidenceSource.AUDIO_TRACK,
                if (nativeInfo != null) Confidence.VERIFIED else Confidence.HIGH_CONFIDENCE
            ),
            performanceMode = AudioEvidence(
                nativeInfo?.performanceMode ?: "LOW_LATENCY",
                if (nativeInfo != null) EvidenceSource.OBOE_STREAM else EvidenceSource.HAL_PARAMETER,
                if (nativeInfo != null) Confidence.VERIFIED else Confidence.HIGH_CONFIDENCE
            ),
            directPathActive = AudioEvidence(isDirectActive, if (nativeInfo != null) EvidenceSource.OBOE_STREAM else EvidenceSource.HAL_PARAMETER, directConfidence),
            directPathState = AudioEvidence(directPathState, if (nativeInfo != null) EvidenceSource.OBOE_STREAM else EvidenceSource.HAL_PARAMETER, directConfidence),
            mixerPathActive = AudioEvidence(isMixerActive, EvidenceSource.HAL_PARAMETER, directConfidence),
            mixerPathState = AudioEvidence(mixerPathState, EvidenceSource.HAL_PARAMETER, directConfidence),
            resamplerState = AudioEvidence(resamplerStateValue, if (nativeInfo != null) EvidenceSource.OBOE_STREAM else EvidenceSource.HAL_PARAMETER, Confidence.HIGH_CONFIDENCE),
            dspState = AudioEvidence(if (isDspActive) "ACTIVE" else "OFF", EvidenceSource.OBOE_STREAM, Confidence.VERIFIED),
            dac = dacState,
            bitPerfect = BitPerfectRuntimeState(BitPerfectState.UNKNOWN, BitPerfectVerifier.isEligible(
                CanonicalAudioRuntimeSnapshot(
                    source = source, decoder = decoder, processing = processing, requestedOutput = requested,
                    actualOutput = actual, activeRoute = routeEvidence, audioApi = apiEvidence,
                    sharingMode = AudioEvidence("SHARED", EvidenceSource.UNKNOWN, Confidence.UNKNOWN),
                    performanceMode = AudioEvidence("NONE", EvidenceSource.UNKNOWN, Confidence.UNKNOWN),
                    directPathActive = AudioEvidence(false, EvidenceSource.UNKNOWN, Confidence.UNKNOWN),
                    mixerPathActive = AudioEvidence(true, EvidenceSource.UNKNOWN, Confidence.UNKNOWN),
                    resamplerState = AudioEvidence("OFF", EvidenceSource.UNKNOWN, Confidence.UNKNOWN),
                    dspState = AudioEvidence("OFF", EvidenceSource.UNKNOWN, Confidence.UNKNOWN),
                    dac = dacState,
                    bitPerfect = BitPerfectRuntimeState(BitPerfectState.UNKNOWN, false, null, Confidence.UNKNOWN),
                    confidence = Confidence.UNKNOWN,
                    limitations = hardwareReport.limitations
                )
            ), null, Confidence.UNKNOWN),
            nativeStream = nativeStreamSnapshot,
            pipeline = pipelineSnapshot,
            confidence = Confidence.UNKNOWN,
            limitations = hardwareReport.limitations
        )

        val bitPerfectSettingRequested = PlaybackService.instance?.bitPerfectMode?.value ?: AudioEngine.bitPerfectRequested.value
        val hrtfEnabled = PlaybackService.instance?.equalizerEngine?.hrtfSpatialEnabled?.value ?: false
        val verificationResult = BitPerfectVerifier.verify(
            snapshot = preliminarySnapshot,
            dspProcessor = dspProcessor,
            isHrtfEnabled = hrtfEnabled,
            isBitPerfectRequested = bitPerfectSettingRequested
        )

        val modeStr = if (bitPerfectSettingRequested) "BIT_PERFECT_ON" else "NORMAL_OFF"
        val requestedPathStr = if (bitPerfectSettingRequested) "BITPERFECT_DIRECT" else if (isDirectActive) "NORMAL_DIRECT" else "NORMAL_SHARED"
        val actualPathStr = nativeInfo?.sharingMode ?: if (isDirectActive) "DIRECT" else "SHARED"
        val routeStr = activeRoute?.routeType?.displayName ?: "UNKNOWN"
        val dspStr = if (isDspActive) "ACTIVE" else "OFF"
        val resamplerStr = resamplerStateValue
        val directStr = if (isDirectActive) "ACTIVE" else "INACTIVE"
        val mixerStr = if (isMixerActive) "ACTIVE" else "INACTIVE"
        val verifStr = verificationResult.state.name

        runCatching {
            android.util.Log.i("AudioEngine", "Snapshot: MODE=$modeStr | REQUESTED_PATH=$requestedPathStr | ACTUAL_PATH=$actualPathStr | ROUTE=$routeStr | DSP=$dspStr | RESAMPLER=$resamplerStr | DIRECT=$directStr | MIXER=$mixerStr | VERIFICATION=$verifStr")
        }

        return preliminarySnapshot.copy(
            bitPerfect = BitPerfectRuntimeState(
                state = verificationResult.state,
                eligibility = BitPerfectVerifier.isEligible(preliminarySnapshot),
                verificationResult = verificationResult,
                confidence = verificationResult.confidence
            ),
            confidence = verificationResult.confidence
        )
    }
}
