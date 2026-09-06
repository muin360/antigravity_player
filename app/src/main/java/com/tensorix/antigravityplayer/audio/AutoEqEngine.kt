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
     * Applies an AutoEQ profile directly to native C++ 64-bit PEQ filters and EqualizerEngine.
     */
    fun applyProfile(profile: AutoEqProfile, equalizerEngine: EqualizerEngine?) {
        _activeProfile.value = profile
        _isAutoEqEnabled.value = true
        prefs.edit()
            .putString(PREFS_KEY_ACTIVE_PROFILE_ID, profile.id)
            .putBoolean("auto_eq_enabled", true)
            .apply()

        Log.i(TAG, "✦ [AutoEQ CALIBRATION ENGAGED] ✦ Model: '${profile.displayName}', Target: '${profile.targetCurve}', Bands: ${profile.bands.size}")

        // 1. Sync with Native C++ PEQ Bands
        val handle = OboeAudioSink.currentActiveHandle
        if (handle != 0L && OboeBridge.isAvailable) {
            try {
                OboeBridge.clearPeqBands(handle)
                profile.bands.forEach { band ->
                    OboeBridge.addPeqBand(
                        handle = handle,
                        type = band.filterType,
                        frequency = band.frequencyHz,
                        q = band.qFactor,
                        gainDb = band.gainDb
                    )
                }
                Log.i(TAG, "✓ Applied ${profile.bands.size} PEQ bands to Native C++ DSP")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply PEQ bands to Oboe", e)
            }
        }

        // 2. Adjust pre-amp to prevent clipping from positive EQ gains
        equalizerEngine?.let { eq ->
            eq.setPreAmpGain((profile.preampDb).toFloat().coerceIn(-12.0f, 0.0f))
        }
    }

    /**
     * Disables AutoEQ calibration and clears native PEQ bands.
     */
    fun disableAutoEq(equalizerEngine: EqualizerEngine?) {
        _isAutoEqEnabled.value = false
        prefs.edit().putBoolean("auto_eq_enabled", false).apply()

        val handle = OboeAudioSink.currentActiveHandle
        if (handle != 0L && OboeBridge.isAvailable) {
            try {
                OboeBridge.clearPeqBands(handle)
                Log.i(TAG, "✓ Cleared Native C++ PEQ bands")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear PEQ bands", e)
            }
        }
        equalizerEngine?.setPreAmpGain(0.0f)
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

        disableAutoEq(equalizerEngine)
    }
}
