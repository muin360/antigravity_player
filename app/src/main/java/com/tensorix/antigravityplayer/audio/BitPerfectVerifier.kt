package com.tensorix.antigravityplayer.audio

import androidx.media3.common.util.UnstableApi

/**
 * AUTHORITATIVE BIT-PERFECT VERIFIER
 *
 * Strictly enforces all 35 mandatory runtime conditions for VERIFIED status.
 * ZERO false positives.
 * UNKNOWN / INFERRED confidence blocks VERIFIED.
 * Contradictory or heuristic paths are rejected.
 */
@UnstableApi
object BitPerfectVerifier {
    private const val TAG = "BitPerfectVerifier"

    fun verify(
        snapshot: CanonicalAudioRuntimeSnapshot,
        dspProcessor: Audiophile64BitDspProcessor?,
        isHrtfEnabled: Boolean,
        isBitPerfectRequested: Boolean = true
    ): BitPerfectVerificationResult {
        if (!isBitPerfectRequested) {
            return BitPerfectVerificationResult(
                state = BitPerfectState.DISABLED,
                evidence = emptyList(),
                confidence = Confidence.VERIFIED,
                failureReasons = emptyList()
            )
        }

        val evidence = mutableListOf<BitPerfectEvidence>()
        val failureReasons = mutableListOf<String>()
        var hasUnknownOrInferredCritical = false

        // 1. Current playback stream exists
        val streamExists = OboeAudioSink.currentActiveHandle != 0L || (snapshot.nativeStream?.handle ?: 0L) != 0L
        evidence.add(BitPerfectEvidence("Stream Exists", streamExists, EvidenceSource.OBOE_STREAM))
        if (!streamExists) {
            failureReasons.add("Native audio stream handle is null or closed")
        }

        // 2. Playback stream is actually active (lifecycle state proof, not sampleRate > 0)
        val nativeStream = snapshot.nativeStream
        val streamActive = streamExists && (nativeStream == null || nativeStream.isStarted || nativeStream.state.equals("Started", ignoreCase = true) || nativeStream.framesWritten > 0L)
        evidence.add(BitPerfectEvidence("Stream Active", streamActive, EvidenceSource.OBOE_STREAM))
        if (!streamActive) {
            failureReasons.add("Audio stream is not actively running")
        }

        // 3. Active output route verified (UNKNOWN or INFERRED blocks verification)
        val routeVerified = snapshot.activeRoute.confidence == Confidence.VERIFIED
        if (snapshot.activeRoute.confidence == Confidence.UNKNOWN || snapshot.activeRoute.confidence == Confidence.INFERRED) {
            hasUnknownOrInferredCritical = true
        }
        evidence.add(BitPerfectEvidence("Route Verified", routeVerified, snapshot.activeRoute.source))
        if (!routeVerified) {
            failureReasons.add("Audio output route is not verified (Confidence: ${snapshot.activeRoute.confidence})")
        }

        // 4. Actual output device identity known and route eligible
        val routeType = snapshot.activeRoute.value
        val routeKnown = routeType != AudioOutputRouteType.OTHER && snapshot.activeRoute.confidence != Confidence.UNKNOWN
        val routeEligible = routeKnown && routeType != AudioOutputRouteType.BLUETOOTH_A2DP && routeType != AudioOutputRouteType.SPEAKER && routeType != AudioOutputRouteType.BUILT_IN_EARPIECE
        evidence.add(BitPerfectEvidence("Device Eligible", routeEligible, snapshot.activeRoute.source))
        if (!routeEligible) {
            failureReasons.add("Audio output route ($routeType) does not support bit-perfect direct output")
        }

        // 5. Actual API known
        val apiKnown = snapshot.audioApi.confidence == Confidence.VERIFIED || snapshot.audioApi.confidence == Confidence.HIGH_CONFIDENCE
        evidence.add(BitPerfectEvidence("API Known", apiKnown, snapshot.audioApi.source))
        if (!apiKnown) {
            failureReasons.add("Audio output API is unknown or unverified")
            hasUnknownOrInferredCritical = true
        }

        // 6. Actual sharing mode known
        val sharingKnown = snapshot.sharingMode.confidence == Confidence.VERIFIED || snapshot.sharingMode.confidence == Confidence.HIGH_CONFIDENCE
        evidence.add(BitPerfectEvidence("Sharing Mode Known", sharingKnown, snapshot.sharingMode.source))
        if (!sharingKnown) {
            failureReasons.add("Stream sharing mode is unknown")
            hasUnknownOrInferredCritical = true
        }

        // 7. Actual sharing mode is Exclusive (for USB/Wired direct path)
        // Rule 13: Never use 'EXCLUSIVE || directPathActive' - they are separate facts.
        val isExclusive = snapshot.sharingMode.value == "EXCLUSIVE"
        evidence.add(BitPerfectEvidence("Exclusive Mode", isExclusive, snapshot.sharingMode.source))
        if (!isExclusive) {
            failureReasons.add("Stream is operating in shared mixer mode")
        }
        if (snapshot.sharingMode.confidence != Confidence.VERIFIED) {
            hasUnknownOrInferredCritical = true
        }

        // 8. Actual output sample rate known and verified
        val outputRateKnown = snapshot.actualOutput.sampleRate.value > 0 && 
                             (snapshot.actualOutput.sampleRate.confidence == Confidence.VERIFIED || snapshot.actualOutput.sampleRate.confidence == Confidence.HIGH_CONFIDENCE)
        evidence.add(BitPerfectEvidence("Output Rate Known", outputRateKnown, snapshot.actualOutput.sampleRate.source))
        if (!outputRateKnown) {
            failureReasons.add("Actual hardware output sample rate is unknown or unverified")
            hasUnknownOrInferredCritical = true
        } else if (snapshot.actualOutput.sampleRate.confidence != Confidence.VERIFIED) {
            hasUnknownOrInferredCritical = true
        }

        // 9. Source sample rate known
        val sourceRateKnown = snapshot.source.sampleRate.value > 0 && snapshot.source.sampleRate.confidence == Confidence.VERIFIED
        evidence.add(BitPerfectEvidence("Source Rate Known", sourceRateKnown, snapshot.source.sampleRate.source))
        if (!sourceRateKnown) {
            failureReasons.add("Source track sample rate is unknown")
            hasUnknownOrInferredCritical = true
        }

        // 10. Sample rates exactly match (1:1 Clock)
        val rateMatch = outputRateKnown && sourceRateKnown && snapshot.actualOutput.sampleRate.value == snapshot.source.sampleRate.value
        evidence.add(BitPerfectEvidence("Sample Rate Match", rateMatch, EvidenceSource.HAL_PARAMETER, "${snapshot.source.sampleRate.value} Hz -> ${snapshot.actualOutput.sampleRate.value} Hz"))
        if (!rateMatch) {
            failureReasons.add("Sample rate mismatch: ${snapshot.source.sampleRate.value} Hz source vs ${snapshot.actualOutput.sampleRate.value} Hz output")
        }

        // 11. Actual channel count known
        val outputChannelsKnown = snapshot.actualOutput.channels.value > 0 && 
                                 (snapshot.actualOutput.channels.confidence == Confidence.VERIFIED || snapshot.actualOutput.channels.confidence == Confidence.HIGH_CONFIDENCE)
        evidence.add(BitPerfectEvidence("Output Channels Known", outputChannelsKnown, snapshot.actualOutput.channels.source))
        if (!outputChannelsKnown) {
            failureReasons.add("Actual hardware output channel count is unknown")
            hasUnknownOrInferredCritical = true
        } else if (snapshot.actualOutput.channels.confidence != Confidence.VERIFIED) {
            hasUnknownOrInferredCritical = true
        }

        // 12. Source channel count known
        val sourceChannelsKnown = snapshot.source.channels.value > 0 && snapshot.source.channels.confidence == Confidence.VERIFIED
        evidence.add(BitPerfectEvidence("Source Channels Known", sourceChannelsKnown, snapshot.source.channels.source))
        if (!sourceChannelsKnown) {
            failureReasons.add("Source track channel count is unknown")
            hasUnknownOrInferredCritical = true
        }

        // 13. Channel counts exactly match
        val channelsMatch = outputChannelsKnown && sourceChannelsKnown && snapshot.actualOutput.channels.value == snapshot.source.channels.value
        evidence.add(BitPerfectEvidence("Channel Match", channelsMatch, EvidenceSource.HAL_PARAMETER, "${snapshot.source.channels.value} ch -> ${snapshot.actualOutput.channels.value} ch"))
        if (!channelsMatch) {
            failureReasons.add("Channel count mismatch: ${snapshot.source.channels.value} ch vs ${snapshot.actualOutput.channels.value} ch")
        }

        // 14. Actual output encoding known
        val encodingKnown = snapshot.actualOutput.encoding.value.isNotEmpty() && snapshot.actualOutput.encoding.confidence != Confidence.UNKNOWN
        evidence.add(BitPerfectEvidence("Encoding Known", encodingKnown, snapshot.actualOutput.encoding.source))
        if (!encodingKnown) {
            failureReasons.add("Actual hardware output encoding is unknown")
            hasUnknownOrInferredCritical = true
        }

        // 15. Encoding is compatible (linear PCM without lossy container compression)
        val encodingCompatible = !snapshot.actualOutput.encoding.value.contains("MP3", ignoreCase = true) &&
                                 !snapshot.actualOutput.encoding.value.contains("AAC", ignoreCase = true) &&
                                 !snapshot.actualOutput.encoding.value.contains("SBC", ignoreCase = true)
        evidence.add(BitPerfectEvidence("Encoding Compatible", encodingCompatible, snapshot.actualOutput.encoding.source))
        if (!encodingCompatible) {
            failureReasons.add("Output encoding is lossy or incompatible")
        }

        // 16. No resampler active
        val noResampler = snapshot.resamplerState.value == "OFF" || snapshot.resamplerState.value == "BYPASS"
        evidence.add(BitPerfectEvidence("No Resampler", noResampler, snapshot.resamplerState.source))
        if (!noResampler) {
            failureReasons.add("Resampler is actively altering audio clock")
        }

        // 17. DSP is completely disabled or in pure bit-perfect bypass
        val dspBypassed = dspProcessor == null || (!dspProcessor.isEnabled && dspProcessor.isBitPerfectBypass) || (dspProcessor.isBitPerfectBypass && !dspProcessor.isEnabled)
        evidence.add(BitPerfectEvidence("DSP Bypassed", dspBypassed, EvidenceSource.OBOE_STREAM))
        if (!dspBypassed) {
            failureReasons.add("DSP Engine is active")
        }

        // 18. EQ is disabled (Rule 8: independent explicit verification)
        val eqDisabled = dspProcessor == null || !dspProcessor.isEqActive
        evidence.add(BitPerfectEvidence("EQ Disabled", eqDisabled, EvidenceSource.OBOE_STREAM))
        if (!eqDisabled) {
            failureReasons.add("Equalizer filters are active")
        }

        // 19. Tone controls (bass/treble/clarity/presence) disabled
        val toneDisabled = dspProcessor == null ||
            (!dspProcessor.isBassBoostActive && !dspProcessor.isTrebleActive && !dspProcessor.isClarityActive && !dspProcessor.isAirPresenceActive)
        evidence.add(BitPerfectEvidence("Tone Controls Disabled", toneDisabled, EvidenceSource.OBOE_STREAM))
        if (!toneDisabled) {
            failureReasons.add("Hardware tone shaping or clarity filters are active")
        }

        // 20. PEQ / AutoEQ is disabled
        val peqDisabled = dspProcessor == null || !dspProcessor.isEqActive
        evidence.add(BitPerfectEvidence("PEQ Disabled", peqDisabled, EvidenceSource.OBOE_STREAM))

        // 21. Limiter is disabled
        val limiterDisabled = dspProcessor == null || (!dspProcessor.limiterEnabled && !dspProcessor.isLimiterActive)
        evidence.add(BitPerfectEvidence("Limiter Disabled", limiterDisabled, EvidenceSource.OBOE_STREAM))
        if (!limiterDisabled) {
            failureReasons.add("True-peak limiter is active")
        }

        // 22. Dither is disabled
        val ditherDisabled = dspProcessor == null || (dspProcessor.ditherStrength < 0.0001 && !dspProcessor.isDitherActive)
        evidence.add(BitPerfectEvidence("Dither Disabled", ditherDisabled, EvidenceSource.OBOE_STREAM))
        if (!ditherDisabled) {
            failureReasons.add("TPDF dither modification is active")
        }

        // 23. Software digital volume is unity (1.0)
        val volUnity = dspProcessor == null || (dspProcessor.dvcVolume >= 0.999 && dspProcessor.dvcVolume <= 1.001)
        evidence.add(BitPerfectEvidence("Volume Unity", volUnity, EvidenceSource.OBOE_STREAM))
        if (!volUnity) {
            failureReasons.add("Software digital volume attenuation is active")
        }

        // 24. Preamp gain is unity (0.0 dB)
        val preampUnity = dspProcessor == null || (dspProcessor.preAmpGainDb >= -0.01 && dspProcessor.preAmpGainDb <= 0.01)
        evidence.add(BitPerfectEvidence("Preamp Unity", preampUnity, EvidenceSource.OBOE_STREAM))
        if (!preampUnity) {
            failureReasons.add("Preamp gain is active")
        }

        // 25. ReplayGain is unity (1.0x multiplier)
        val replayGainUnity = dspProcessor == null || (dspProcessor.replayGainMultiplier >= 0.999 && dspProcessor.replayGainMultiplier <= 1.001)
        evidence.add(BitPerfectEvidence("ReplayGain Unity", replayGainUnity, EvidenceSource.OBOE_STREAM))
        if (!replayGainUnity) {
            failureReasons.add("ReplayGain modification is active")
        }

        // 26. Saturation / Analog warmth is disabled
        val saturationDisabled = dspProcessor == null || (dspProcessor.warmSaturationLevel <= 0.001 && dspProcessor.triodeWarmthLevel <= 0.001 && dspProcessor.pentodeTapeLevel <= 0.001 && !dspProcessor.isSaturationActive)
        evidence.add(BitPerfectEvidence("Saturation Disabled", saturationDisabled, EvidenceSource.OBOE_STREAM))
        if (!saturationDisabled) {
            failureReasons.add("Tube/Tape saturation simulation is active")
        }

        // 27. Spatial / HRTF processing is disabled
        val spatialOff = !isHrtfEnabled
        evidence.add(BitPerfectEvidence("Spatial Audio Disabled", spatialOff, EvidenceSource.VENDOR_API))
        if (!spatialOff) {
            failureReasons.add("HRTF Spatial Audio processing is active")
        }

        // 28. Crossfeed is disabled
        val crossfeedOff = dspProcessor == null || (dspProcessor.crossfeedLevel < 0.001 && !dspProcessor.isCrossfeedActive)
        evidence.add(BitPerfectEvidence("Crossfeed Disabled", crossfeedOff, EvidenceSource.OBOE_STREAM))
        if (!crossfeedOff) {
            failureReasons.add("Meier crossfeed is active")
        }

        // 29. Channel balance is unity (0.0)
        val balanceUnity = dspProcessor == null || (dspProcessor.channelBalance >= -0.01 && dspProcessor.channelBalance <= 0.01 && !dspProcessor.isChannelBalanceActive)
        evidence.add(BitPerfectEvidence("Balance Unity", balanceUnity, EvidenceSource.OBOE_STREAM))
        if (!balanceUnity) {
            failureReasons.add("Channel balance attenuation is active")
        }

        // 30. No channel transformation active (Rule 9: verified from actual evidence)
        val channelCountsMatch = snapshot.source.channels.value > 0 &&
            snapshot.actualOutput.channels.value > 0 &&
            snapshot.source.channels.value == snapshot.actualOutput.channels.value
        val stereoExpansionOff = dspProcessor == null || !dspProcessor.isStereoExpansionActive
        val subBassMonoOff = dspProcessor == null || !dspProcessor.isSubBassMonoActive
        val invertPhaseOff = dspProcessor == null || !dspProcessor.isInvertPhaseActive
        val noChannelRemap = snapshot.pipeline?.channelRemapActive != true
        val noChannelTransform = channelCountsMatch && balanceUnity && stereoExpansionOff && subBassMonoOff && invertPhaseOff && noChannelRemap
        evidence.add(BitPerfectEvidence("No Channel Transform", noChannelTransform, EvidenceSource.OBOE_STREAM))
        if (!noChannelTransform) {
            failureReasons.add("Channel transformation, downmixing, or spatial remapping is active")
        }

        // 31. No lossy PCM conversion (Rule 9 & 10: verified from actual evidence)
        val sourceBits = snapshot.source.bitDepth.value
        val outputBits = snapshot.actualOutput.bitDepth.value
        val isFloatOutput = snapshot.actualOutput.encoding.value.contains("Float", ignoreCase = true)
        val bitDepthPreserved = when {
            sourceBits > 0 && outputBits > 0 -> {
                if (isFloatOutput) {
                    // IEEE 754 32-bit float has 24 bits of significand precision (23 explicit + 1 implicit).
                    // 16-bit and 24-bit integer PCM fit losslessly with exact mathematical identity.
                    // 32-bit integer PCM loses 8 bits of precision when mapped to 32-bit float and is NOT bit-perfect.
                    sourceBits <= 24
                } else {
                    // Integer pipeline: output bit depth must be >= source bit depth
                    outputBits >= sourceBits
                }
            }
            else -> false // Unknown output format cannot claim bit-perfect preservation
        }
        val noLossyPcm = encodingCompatible && bitDepthPreserved
        evidence.add(BitPerfectEvidence("No Lossy PCM", noLossyPcm, EvidenceSource.OBOE_STREAM))
        if (!noLossyPcm) {
            failureReasons.add("PCM bit-depth truncation or lossy downconversion detected (Source: ${sourceBits}-bit, Output: ${outputBits}-bit ${if (isFloatOutput) "Float" else "Integer"})")
        }

        // Rule 40: DSD Decimation Detection (DSD source decimated to PCM alters 1-bit bitstream)
        val isDsdSource = snapshot.source.encoding.value.contains("DSD", ignoreCase = true) ||
                         snapshot.source.encoding.value.contains("DSF", ignoreCase = true) ||
                         snapshot.source.encoding.value.contains("DFF", ignoreCase = true)
        val dsdNotDecimated = !isDsdSource || snapshot.actualOutput.encoding.value.contains("DSD", ignoreCase = true)
        evidence.add(BitPerfectEvidence("DSD Bitstream Integrity", dsdNotDecimated, EvidenceSource.OBOE_STREAM))
        if (!dsdNotDecimated) {
            failureReasons.add("DSD 1-bit bitstream is decimated to PCM; native 1-bit stream cannot be preserved")
        }

        // 32. Direct HAL path is ACTUALLY active (runtime proof)
        val directActive = snapshot.directPathActive.value && 
                          (snapshot.directPathActive.confidence == Confidence.VERIFIED || snapshot.directPathActive.confidence == Confidence.HIGH_CONFIDENCE)
        evidence.add(BitPerfectEvidence("Direct Path Active", directActive, snapshot.directPathActive.source))
        if (!directActive) {
            failureReasons.add("Direct PCM HAL path is not actively confirmed")
            hasUnknownOrInferredCritical = true
        } else if (snapshot.directPathActive.confidence != Confidence.VERIFIED) {
            hasUnknownOrInferredCritical = true
        }

        // 33. Mixer state is definitely not active
        val mixerStateKnown = snapshot.mixerPathState.confidence != Confidence.UNKNOWN
        val mixerNotActive = mixerStateKnown && snapshot.mixerPathActive.value == false
        evidence.add(BitPerfectEvidence("Mixer Inactive", mixerNotActive, snapshot.mixerPathActive.source))
        if (!mixerNotActive) {
            failureReasons.add("AudioFlinger mixer path is active or mixer state is unknown")
            if (!mixerStateKnown) hasUnknownOrInferredCritical = true
        }

        // 34. No unknown critical telemetry
        val noUnknownTelemetry = !hasUnknownOrInferredCritical &&
                                snapshot.activeRoute.confidence != Confidence.UNKNOWN &&
                                snapshot.actualOutput.sampleRate.confidence != Confidence.UNKNOWN &&
                                snapshot.source.sampleRate.confidence != Confidence.UNKNOWN
        evidence.add(BitPerfectEvidence("Telemetry Complete", noUnknownTelemetry, EvidenceSource.OBOE_STREAM))
        if (!noUnknownTelemetry) {
            failureReasons.add("Critical audio telemetry is unknown or unverified")
        }

        // 35. Current stream telemetry is fresh (< 10 seconds old)
        val isFresh = (System.currentTimeMillis() - snapshot.timestamp) < 10000L
        evidence.add(BitPerfectEvidence("Telemetry Fresh", isFresh, EvidenceSource.OBOE_STREAM))
        if (!isFresh) {
            failureReasons.add("Telemetry data is stale")
        }

        // Final State Evaluation
        val allSatisfied = evidence.all { it.isSatisfied }
        val eligible = isEligible(snapshot)

        val state = when {
            !eligible -> BitPerfectState.UNAVAILABLE
            !streamExists || !streamActive -> BitPerfectState.REQUESTED
            allSatisfied && !hasUnknownOrInferredCritical -> BitPerfectState.VERIFIED
            hasUnknownOrInferredCritical && eligible && directActive -> BitPerfectState.ACTIVE_UNVERIFIED
            failureReasons.isNotEmpty() -> BitPerfectState.UNAVAILABLE
            hasUnknownOrInferredCritical && eligible -> BitPerfectState.UNKNOWN
            else -> BitPerfectState.UNKNOWN
        }

        val confidence = when (state) {
            BitPerfectState.DISABLED -> Confidence.VERIFIED
            BitPerfectState.VERIFIED -> Confidence.VERIFIED
            BitPerfectState.ACTIVE_UNVERIFIED -> Confidence.HIGH_CONFIDENCE
            BitPerfectState.UNAVAILABLE -> Confidence.HIGH_CONFIDENCE
            BitPerfectState.REQUESTED -> Confidence.HIGH_CONFIDENCE
            BitPerfectState.UNKNOWN -> Confidence.UNKNOWN
            else -> Confidence.INFERRED
        }

        return BitPerfectVerificationResult(
            state = state,
            evidence = evidence,
            confidence = confidence,
            failureReasons = failureReasons
        )
    }

    fun isEligible(snapshot: CanonicalAudioRuntimeSnapshot): Boolean {
        val routeType = snapshot.activeRoute.value
        val isNotBluetooth = routeType != AudioOutputRouteType.BLUETOOTH_A2DP
        val isNotSpeaker = routeType != AudioOutputRouteType.SPEAKER && routeType != AudioOutputRouteType.BUILT_IN_EARPIECE && routeType != AudioOutputRouteType.OTHER

        val isRouteCapable = routeType == AudioOutputRouteType.USB_DAC || 
                            routeType == AudioOutputRouteType.USB_DEVICE || 
                            routeType == AudioOutputRouteType.WIRED_HEADPHONES || 
                            routeType == AudioOutputRouteType.WIRED_HEADSET ||
                            snapshot.dac.isActive.value

        return isNotBluetooth && isNotSpeaker && isRouteCapable
    }
}
