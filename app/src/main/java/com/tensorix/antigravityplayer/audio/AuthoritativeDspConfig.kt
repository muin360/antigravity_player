package com.tensorix.antigravityplayer.audio

import androidx.media3.common.util.UnstableApi
import java.util.Collections

/**
 * Parametric EQ Band definition for Authoritative DSP Configuration.
 */
@UnstableApi
data class AuthoritativePeqBand(
    val filterType: Int = 0, // 0: PEAKING_EQ, 1: LOW_SHELF, 2: HIGH_SHELF, etc.
    val frequencyHz: Double = 1000.0,
    val qFactor: Double = 1.414,
    val gainDb: Double = 0.0,
    val isEnabled: Boolean = true
) {
    fun validated(): AuthoritativePeqBand {
        return copy(
            frequencyHz = frequencyHz.coerceIn(10.0, 48000.0),
            qFactor = qFactor.coerceIn(0.1, 30.0),
            gainDb = gainDb.coerceIn(-24.0, 24.0)
        )
    }
}

/**
 * Authoritative, immutable, deeply-validated single source of truth for all DSP configuration.
 *
 * Guaranteed invariants:
 * - Immutable value object
 * - Normalization and range clamping on all parameters
 * - Consistent feature activation flag calculation
 * - Exact 10-band ISO graphic equalizer mapping
 * - Atomic single-shot serialization for both Native C++ Oboe DSP and JVM Fallback DSP
 */
@UnstableApi
data class AuthoritativeDspConfig(
    val isEnabled: Boolean = true,
    val isBitPerfectBypass: Boolean = false,
    val preAmpGainDb: Double = 0.0,
    val bassBoostGainDb: Double = 0.0,
    val trebleGainDb: Double = 0.0,
    val clarityEnhancerGainDb: Double = 0.0,
    val harmonicExciterLevel: Double = 0.0,
    val warmSaturationLevel: Double = 0.0,
    val triodeWarmthLevel: Double = 0.0,
    val pentodeTapeLevel: Double = 0.0,
    val crossfeedLevel: Double = 0.0,
    val stereoExpansionMultiplier: Double = 1.0,
    val channelBalance: Double = 0.0,
    val invertPhase: Boolean = false,
    val airPresenceGainDb: Double = 0.0,
    val subBassMonoEnabled: Boolean = false,
    val hrtfSpatialEnabled: Boolean = false,
    val hrtfRoomSize: Double = 0.5,
    val limiterEnabled: Boolean = false,
    val limiterThresholdDb: Double = 0.0,
    val ditherStrength: Double = 0.0,
    val outputBitDepth: Int = 24,
    val replayGainEnabled: Boolean = true,
    val replayGainMultiplier: Double = 1.0,
    val dvcVolume: Double = 1.0,
    val bandGainsDb: List<Double> = List(10) { 0.0 },
    val peqBands: List<AuthoritativePeqBand> = emptyList(),
    val isAutoEqEnabled: Boolean = false
) {
    init {
        require(bandGainsDb.size == 10) { "bandGainsDb must have exactly 10 bands" }
    }

    /**
     * Produces a fully validated, range-clamped instance.
     */
    fun validated(): AuthoritativeDspConfig {
        val validatedBands = bandGainsDb.map { it.coerceIn(-15.0, 15.0) }
        val validatedPeq = peqBands.take(32).map { it.validated() }
        val effectiveRgMultiplier = if (replayGainEnabled && !isBitPerfectBypass) {
            replayGainMultiplier.coerceIn(0.01, 10.0)
        } else {
            1.0
        }

        return copy(
            preAmpGainDb = preAmpGainDb.coerceIn(-20.0, 20.0),
            bassBoostGainDb = bassBoostGainDb.coerceIn(0.0, 15.0),
            trebleGainDb = trebleGainDb.coerceIn(0.0, 15.0),
            clarityEnhancerGainDb = clarityEnhancerGainDb.coerceIn(0.0, 15.0),
            harmonicExciterLevel = harmonicExciterLevel.coerceIn(0.0, 1.0),
            warmSaturationLevel = warmSaturationLevel.coerceIn(0.0, 1.0),
            triodeWarmthLevel = triodeWarmthLevel.coerceIn(0.0, 1.0),
            pentodeTapeLevel = pentodeTapeLevel.coerceIn(0.0, 1.0),
            crossfeedLevel = crossfeedLevel.coerceIn(0.0, 1.0),
            stereoExpansionMultiplier = stereoExpansionMultiplier.coerceIn(0.0, 2.0),
            channelBalance = channelBalance.coerceIn(-1.0, 1.0),
            airPresenceGainDb = airPresenceGainDb.coerceIn(0.0, 15.0),
            hrtfRoomSize = hrtfRoomSize.coerceIn(0.0, 1.0),
            limiterThresholdDb = limiterThresholdDb.coerceIn(-20.0, 0.0),
            ditherStrength = ditherStrength.coerceIn(0.0, 1.0),
            outputBitDepth = when (outputBitDepth) {
                16, 24, 32 -> outputBitDepth
                else -> 24
            },
            replayGainMultiplier = effectiveRgMultiplier,
            dvcVolume = dvcVolume.coerceIn(0.0, 1.0),
            bandGainsDb = Collections.unmodifiableList(validatedBands),
            peqBands = Collections.unmodifiableList(validatedPeq)
        )
    }

    /**
     * Computes the 17 active feature flags for C++ AudiophileDsp batch publication.
     */
    fun computeActiveFlags(): BooleanArray {
        if (isBitPerfectBypass || !isEnabled) {
            return BooleanArray(17) { false }
        }
        val eqActive = bandGainsDb.any { it < -0.01 || it > 0.01 }
        val peqActive = isAutoEqEnabled && peqBands.any { it.isEnabled }
        val saturationActive = warmSaturationLevel > 0.001 || triodeWarmthLevel > 0.001 || pentodeTapeLevel > 0.001

        return booleanArrayOf(
            eqActive,                                                                      // 0: eqActive
            isAutoEqEnabled,                                                               // 1: autoEqActive
            peqActive,                                                                     // 2: peqActive
            limiterEnabled,                                                                // 3: limiterActive
            ditherStrength > 0.0001,                                                       // 4: ditherActive
            replayGainEnabled && (replayGainMultiplier < 0.999 || replayGainMultiplier > 1.001), // 5: replayGainActive
            crossfeedLevel > 0.001,                                                        // 6: crossfeedActive
            channelBalance < -0.01 || channelBalance > 0.01,                               // 7: balanceActive
            hrtfSpatialEnabled,                                                            // 8: spatialActive
            bassBoostGainDb > 0.01,                                                        // 9: bassBoostActive
            trebleGainDb > 0.01,                                                           // 10: trebleActive
            clarityEnhancerGainDb > 0.01,                                                  // 11: clarityActive
            harmonicExciterLevel > 0.001,                                                  // 12: harmonicExciterActive
            saturationActive,                                                              // 13: saturationActive
            stereoExpansionMultiplier < 0.99 || stereoExpansionMultiplier > 1.01,          // 14: stereoExpansionActive
            subBassMonoEnabled,                                                            // 15: subBassMonoActive
            false                                                                          // 16: channelTransformActive
        )
    }

    /**
     * Builds the 27 double parameters array for C++ AudiophileDsp batch publication.
     */
    fun toNativeDoubleParams(): DoubleArray {
        val p = DoubleArray(27) { 0.0 }
        if (isBitPerfectBypass) {
            p[5] = 1.0 // stereoExpansionMultiplier
            p[6] = 1.0 // dvcVolume
            p[7] = 1.0 // replayGainMultiplier
            p[16] = 0.5 // hrtfRoomSize
            return p
        }

        p[0] = preAmpGainDb
        p[1] = bassBoostGainDb
        p[2] = trebleGainDb
        p[3] = harmonicExciterLevel
        p[4] = clarityEnhancerGainDb
        p[5] = stereoExpansionMultiplier
        p[6] = dvcVolume
        p[7] = if (replayGainEnabled) replayGainMultiplier else 1.0
        p[8] = ditherStrength
        p[9] = warmSaturationLevel
        p[10] = triodeWarmthLevel
        p[11] = pentodeTapeLevel
        p[12] = crossfeedLevel
        p[13] = limiterThresholdDb
        p[14] = channelBalance
        p[15] = airPresenceGainDb
        p[16] = hrtfRoomSize
        for (i in 0 until 10) {
            p[17 + i] = bandGainsDb.getOrElse(i) { 0.0 }
        }
        return p
    }

    /**
     * Converts to FallbackDspConfiguration for JVM Fallback DSP processing.
     */
    fun toFallbackDspConfiguration(): FallbackDspConfiguration {
        return FallbackDspConfiguration(
            isEnabled = isEnabled,
            isBitPerfectBypass = isBitPerfectBypass,
            isTurboMode = harmonicExciterLevel > 0.001,
            preAmpGainDb = preAmpGainDb,
            bassBoostGainDb = bassBoostGainDb,
            trebleGainDb = trebleGainDb,
            harmonicExciterLevel = harmonicExciterLevel,
            clarityEnhancerGain = clarityEnhancerGainDb,
            stereoExpansionMultiplier = stereoExpansionMultiplier,
            dvcVolume = dvcVolume,
            replayGainEnabled = replayGainEnabled,
            replayGainMultiplier = if (replayGainEnabled) replayGainMultiplier else 1.0,
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
            bandGainsDb = bandGainsDb
        )
    }

    companion object {
        val DEFAULT = AuthoritativeDspConfig().validated()
    }
}
