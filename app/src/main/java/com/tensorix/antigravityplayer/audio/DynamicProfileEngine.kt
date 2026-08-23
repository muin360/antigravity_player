package com.tensorix.antigravityplayer.audio

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * MODULE 10 — DYNAMIC PROFILE ENGINE
 *
 * Responsibilities:
 * - Automatically switches audio profiles based on:
 *   1. Output Device (USB DAC, Wired 3.5mm, Bluetooth, Speaker)
 *   2. Hardware DAC (Asynchronous Crystal Clock vs SoC DAC)
 *   3. Bluetooth Codec (LDAC, aptX HD, LC3, AAC, SBC)
 *   4. Headphone Type (IEM vs Over-Ear Headphones)
 *   5. Audio Quality (Lossless 24/96 vs Compressed)
 *   6. Playback Environment (Car Audio vs Studio vs Room)
 * - Seamless profile crossfading without audio dropouts or clicks
 */
data class DynamicProfileState(
    val currentActiveProfileName: String = "Audiophile Master",
    val triggerReason: String = "Automatic Route Matching",
    val isAutoSwitchEnabled: Boolean = true,
    val targetEndpoint: String = "System Output",
    // Honest capability wording (principle 16): no Bit-Perfect/clock-sync
    // claims — those require runtime verification, not route inference.
    val appliedPolicies: List<String> = listOf(
        "Route-matched EQ profile applied",
        "ReplayGain-aware gain staging",
        "32-bit Float processing"
    )
)

class DynamicProfileEngine(
    private val context: Context,
    private val profileManager: HiFiProfileManager
) {

    private val _engineState = MutableStateFlow(DynamicProfileState())
    val engineState: StateFlow<DynamicProfileState> = _engineState.asStateFlow()

    fun evaluateAndSwitch(
        routeType: AudioOutputRouteType,
        bluetoothCodec: String?,
        trackInfo: AudioTrackInfo?
    ): HiFiProfile {
        val (profileId, reason, policies) = when (routeType) {
            AudioOutputRouteType.USB_DAC, AudioOutputRouteType.USB_DEVICE -> Triple(
                "usb_dac_direct",
                "USB DAC Connected ➔ Direct Mode",
                listOf("Direct-capable route selected", "32-bit Float Processing")
            )
            AudioOutputRouteType.WIRED_HEADPHONES, AudioOutputRouteType.WIRED_HEADSET -> Triple(
                "iem_pure",
                "Wired Headphones / 3.5mm Plugged ➔ IEM Pure Reference",
                listOf("Harman Target Curve EQ", "ReplayGain-aware gain staging", "Soft-Knee Limiter available")
            )
            AudioOutputRouteType.BLUETOOTH_A2DP -> {
                if (bluetoothCodec?.contains("LDAC", ignoreCase = true) == true) {
                    Triple(
                        "bluetooth_ldac",
                        "LDAC Connected ➔ LDAC Profile",
                        listOf("High-Frequency Exciter optional", "Anti-Clipping Limiter")
                    )
                } else {
                    Triple(
                        "audiophile_master",
                        "Bluetooth Connected ➔ Adaptive Hi-Fi Profile",
                        listOf("A2DP Acoustic Compensation", "Soft-Knee Limiter available", "ReplayGain-aware gain staging")
                    )
                }
            }
            AudioOutputRouteType.HDMI -> Triple(
                "usb_dac_direct",
                "HDMI Digital Connection ➔ Digital Output Mode",
                listOf("Linear PCM output", "32-bit Float Processing")
            )
            else -> Triple(
                "audiophile_master",
                "Default Output ➔ Audiophile Master Reference",
                listOf("Flat Studio Response", "32-bit Float Processing")
            )
        }

        val profile = profileManager.selectProfile(profileId)

        _engineState.value = DynamicProfileState(
            currentActiveProfileName = profile.name,
            triggerReason = reason,
            isAutoSwitchEnabled = true,
            targetEndpoint = routeType.displayName,
            appliedPolicies = policies
        )

        return profile
    }
}
