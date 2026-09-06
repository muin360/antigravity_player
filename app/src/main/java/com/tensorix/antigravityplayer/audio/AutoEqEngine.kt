package com.tensorix.antigravityplayer.audio

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.tensorix.antigravityplayer.player.EqualizerEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AutoEQ Headphone Calibration Engine
 * Applies studio-calibrated Parametric Equalizer (PEQ) curves to the 64-bit C++ DSP Engine
 * in real-time, achieving exact Harman Target response curves.
 */
@UnstableApi
class AutoEqEngine(private val context: Context) {

    companion object {
        private const val TAG = "AutoEqEngine"
        private const val PREFS_KEY_ACTIVE_PROFILE_ID = "active_auto_eq_profile_id"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("antigravity_autoeq_prefs", Context.MODE_PRIVATE)

    private val _activeProfile = MutableStateFlow<AutoEqProfile?>(null)
    val activeProfile: StateFlow<AutoEqProfile?> = _activeProfile.asStateFlow()

    private val _isAutoEqEnabled = MutableStateFlow(prefs.getBoolean("auto_eq_enabled", false))
    val isAutoEqEnabled: StateFlow<Boolean> = _isAutoEqEnabled.asStateFlow()

    init {
        val savedId = prefs.getString(PREFS_KEY_ACTIVE_PROFILE_ID, null)
        if (!savedId.isNullOrBlank()) {
            _activeProfile.value = AutoEqDatabase.findById(savedId)
        }
    }

    /**
     * Applies an AutoEQ profile atomically to the 64-bit DSP Engine through EqualizerEngine.
     * All PEQ bands and pre-amp attenuation are published as a single transaction.
     */
    fun applyProfile(profile: AutoEqProfile, equalizerEngine: EqualizerEngine?) {
        _activeProfile.value = profile
        _isAutoEqEnabled.value = true
        prefs.edit()
            .putString(PREFS_KEY_ACTIVE_PROFILE_ID, profile.id)
            .putBoolean("auto_eq_enabled", true)
            .apply()

        Log.i(TAG, "✦ [AutoEQ CALIBRATION ENGAGED] ✦ Model: '${profile.displayName}', Target: '${profile.targetCurve}', Bands: ${profile.bands.size}")

        equalizerEngine?.applyAutoEqProfile(profile)
    }

    /**
     * Disables AutoEQ calibration and clears PEQ bands atomically through EqualizerEngine.
     */
    fun disableAutoEq(equalizerEngine: EqualizerEngine?) {
        _isAutoEqEnabled.value = false
        prefs.edit().putBoolean("auto_eq_enabled", false).apply()

        equalizerEngine?.disableAutoEq()
    }

    /**
     * Resets and removes the active AutoEQ profile completely.
     */
    fun clearProfile(equalizerEngine: EqualizerEngine?) {
        _activeProfile.value = null
        _isAutoEqEnabled.value = false
        prefs.edit()
            .remove(PREFS_KEY_ACTIVE_PROFILE_ID)
            .putBoolean("auto_eq_enabled", false)
            .apply()

        equalizerEngine?.clearAutoEq()
    }
}
