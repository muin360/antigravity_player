package com.tensorix.antigravityplayer.audio

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * MODULE 9 — HIFI PROFILE SYSTEM
 *
 * Responsibilities:
 * - Manages unlimited built-in and user-defined audiophile profiles
 * - Stores 10-band EQ, 64-bit DSP gains, ReplayGain 2.0, Crossfeed, Crossfade, Limiter,
 *   Volume Normalization, Sample Rate policy, Bit Depth policy, and Output Preferences
 */
data class HiFiProfile(
    val id: String,
    val name: String,
    val description: String,
    val eqGainsDb: List<Double>,
    val bassBoostDb: Double,
    val trebleGainDb: Double,
    val replayGainEnabled: Boolean,
    val crossfeedEnabled: Boolean,
    val crossfadeDurationMs: Int,
    val limiterEnabled: Boolean,
    val volumeNormalizationEnabled: Boolean,
    val sampleRatePolicy: String,
    val bitDepthPolicy: String,
    val outputPreference: String,
    val isCustom: Boolean = false
)

class HiFiProfileManager(context: Context? = null) {

    val defaultProfiles: List<HiFiProfile> = listOf(
        HiFiProfile(
            id = "audiophile_master",
            name = "Audiophile Master",
            description = "Bit-Perfect reference reproduction with zero phase shift",
            eqGainsDb = listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            bassBoostDb = 0.0,
            trebleGainDb = 0.0,
            replayGainEnabled = true,
            crossfeedEnabled = false,
            crossfadeDurationMs = 0,
            limiterEnabled = true,
            volumeNormalizationEnabled = true,
            sampleRatePolicy = "Source Rate Matching",
            bitDepthPolicy = "32-bit Float Native AudioSink",
            outputPreference = "USB DAC / 3.5mm Headphone Jack"
        ),
        HiFiProfile(
            id = "iem_pure",
            name = "IEM Pure Reference",
            description = "Harmon target curve tailored for high-sensitivity In-Ear Monitors",
            eqGainsDb = listOf(1.5, 1.0, 0.5, 0.0, 0.0, 0.5, 1.0, 1.5, 2.0, 2.5),
            bassBoostDb = 1.0,
            trebleGainDb = 1.5,
            replayGainEnabled = true,
            crossfeedEnabled = true,
            crossfadeDurationMs = 800,
            limiterEnabled = true,
            volumeNormalizationEnabled = true,
            sampleRatePolicy = "Source Rate Matching",
            bitDepthPolicy = "32-bit Float AudioSink",
            outputPreference = "Wired Headphones (3.5mm / 4.4mm)"
        ),
        HiFiProfile(
            id = "usb_dac_direct",
            name = "USB DAC Direct",
            description = "Asynchronous USB Audio Class direct hardware profile",
            eqGainsDb = listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            bassBoostDb = 0.0,
            trebleGainDb = 0.0,
            replayGainEnabled = false,
            crossfeedEnabled = false,
            crossfadeDurationMs = 0,
            limiterEnabled = false,
            volumeNormalizationEnabled = false,
            sampleRatePolicy = "USB Audio Class Direct Passthrough",
            bitDepthPolicy = "32-bit Float Passthrough",
            outputPreference = "External USB Audio DAC"
        ),
        HiFiProfile(
            id = "bluetooth_ldac",
            name = "Bluetooth LDAC Hi-Res",
            description = "990 kbps high-bitrate wireless streaming with high-frequency exciter",
            eqGainsDb = listOf(1.0, 0.5, 0.0, 0.0, 0.5, 0.5, 1.0, 1.5, 2.0, 1.5),
            bassBoostDb = 2.0,
            trebleGainDb = 1.0,
            replayGainEnabled = true,
            crossfeedEnabled = false,
            crossfadeDurationMs = 1200,
            limiterEnabled = true,
            volumeNormalizationEnabled = true,
            sampleRatePolicy = "96.0 kHz Fixed Wireless Master",
            bitDepthPolicy = "24-bit PCM",
            outputPreference = "Bluetooth A2DP (LDAC)"
        ),
        HiFiProfile(
            id = "bass_boost_sub",
            name = "Dynamic Sub-Bass",
            description = "Acoustic low-frequency elevation with anti-clipping limiter",
            eqGainsDb = listOf(6.0, 4.5, 3.0, 1.5, 0.0, 0.0, 0.5, 1.0, 2.0, 2.5),
            bassBoostDb = 6.0,
            trebleGainDb = 1.0,
            replayGainEnabled = true,
            crossfeedEnabled = false,
            crossfadeDurationMs = 1500,
            limiterEnabled = true,
            volumeNormalizationEnabled = true,
            sampleRatePolicy = "Source Match",
            bitDepthPolicy = "32-bit Float AudioSink",
            outputPreference = "Built-in Speaker / Subwoofer"
        ),
        HiFiProfile(
            id = "studio_monitor",
            name = "Studio Monitor Flat",
            description = "Uncolored ruler-flat frequency response for audio mastering",
            eqGainsDb = listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            bassBoostDb = 0.0,
            trebleGainDb = 0.0,
            replayGainEnabled = true,
            crossfeedEnabled = false,
            crossfadeDurationMs = 0,
            limiterEnabled = true,
            volumeNormalizationEnabled = false,
            sampleRatePolicy = "Source Rate Match",
            bitDepthPolicy = "32-bit Float",
            outputPreference = "Studio Reference Monitors"
        ),
        HiFiProfile(
            id = "car_audio",
            name = "Car Audio Acoustics",
            description = "Road-noise compensation with enhanced midrange and punch",
            eqGainsDb = listOf(4.0, 3.0, 1.0, 0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 3.5),
            bassBoostDb = 4.0,
            trebleGainDb = 2.0,
            replayGainEnabled = true,
            crossfeedEnabled = false,
            crossfadeDurationMs = 2000,
            limiterEnabled = true,
            volumeNormalizationEnabled = true,
            sampleRatePolicy = "48.0 kHz Adaptive",
            bitDepthPolicy = "24-bit PCM",
            outputPreference = "Car Bluetooth / AUX Line Out"
        )
    )

    private val _allProfiles = MutableStateFlow(defaultProfiles)
    val allProfiles: StateFlow<List<HiFiProfile>> = _allProfiles.asStateFlow()

    private val _activeProfile = MutableStateFlow(defaultProfiles[0])
    val activeProfile: StateFlow<HiFiProfile> = _activeProfile.asStateFlow()

    fun selectProfile(id: String): HiFiProfile {
        val profile = _allProfiles.value.firstOrNull { it.id == id } ?: defaultProfiles[0]
        _activeProfile.value = profile
        return profile
    }

    fun addCustomProfile(profile: HiFiProfile) {
        val updated = _allProfiles.value.toMutableList()
        updated.removeAll { it.id == profile.id }
        updated.add(profile)
        _allProfiles.value = updated
        _activeProfile.value = profile
    }
}
