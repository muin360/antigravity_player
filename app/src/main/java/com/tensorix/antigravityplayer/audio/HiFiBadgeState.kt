package com.tensorix.antigravityplayer.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Badge truth rules (principle 13/16):
 *  - "BIT-PERFECT" only from VERIFIED evidence.
 *  - "DIRECT" only when an exclusive path is actually active-unverified.
 *  - Everything else is a descriptive format/route label, never a hardware
 *    capability claim. UNKNOWN stays UNKNOWN.
 */
object HiFiBadgeState {
    private val _isHiFiActive = MutableStateFlow(false)
    val isHiFiActive: StateFlow<Boolean> = _isHiFiActive.asStateFlow()

    private val _hifiLabel = MutableStateFlow("HI-FI")
    val hifiLabel: StateFlow<String> = _hifiLabel.asStateFlow()

    private val _hifiDetail = MutableStateFlow("")
    val hifiDetail: StateFlow<String> = _hifiDetail.asStateFlow()

    fun updateFromSnapshot(snapshot: CanonicalAudioRuntimeSnapshot) {
        val state = snapshot.bitPerfect.state
        _isHiFiActive.value = state == BitPerfectState.VERIFIED ||
                state == BitPerfectState.ACTIVE_UNVERIFIED

        _hifiLabel.value = when (state) {
            BitPerfectState.VERIFIED -> "BIT-PERFECT"
            BitPerfectState.ACTIVE_UNVERIFIED -> "DIRECT"
            BitPerfectState.ELIGIBLE -> "HI-RES"
            else -> if (snapshot.actualOutput.sampleRate.value >= 88200) "HD" else "HI-FI"
        }

        _hifiDetail.value = when (state) {
            BitPerfectState.VERIFIED -> "Verified Direct Path"
            BitPerfectState.ACTIVE_UNVERIFIED -> "Exclusive Mode (unverified)"
            BitPerfectState.ELIGIBLE -> "Direct-capable route"
            else -> snapshot.activeRoute.value.displayName
        }
    }
}
