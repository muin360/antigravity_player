package com.tensorix.antigravityplayer.player

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import androidx.core.content.edit
import com.tensorix.antigravityplayer.audio.Audiophile64BitDspProcessor
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Multi-band Parametric Equalizer engine wrapping Android native AudioEffect APIs.
 * Double-hardened for OEM ROM compatibility (MIUI, Samsung OneUI, OxygenOS, ColorOS, EMUI).
 * Features a pure Bit-Perfect DSP Bypass switch for bit-perfect audiophile passthrough.
 */
@UnstableApi
class EqualizerEngine(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("antigravity_eq_prefs", Context.MODE_PRIVATE)

    private var dspProcessor: Audiophile64BitDspProcessor? = null

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    private var currentAudioSessionId: Int = 0

    private val _isEnabled = MutableStateFlow(prefs.getBoolean("eq_enabled", true))
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _isBitPerfectBypass = MutableStateFlow(prefs.getBoolean("bit_perfect_bypass", false))
    val isBitPerfectBypass: StateFlow<Boolean> = _isBitPerfectBypass.asStateFlow()

    private val _bandCount = MutableStateFlow(10) // Always 10 for Audiophile DSP
    val bandCount: StateFlow<Int> = _bandCount.asStateFlow()

    private val _bandFrequencies = MutableStateFlow<List<Int>>(listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000))
    val bandFrequencies: StateFlow<List<Int>> = _bandFrequencies.asStateFlow()

    private val _bandLevels = MutableStateFlow<List<Short>>(
        List(10) { i -> prefs.getInt("band_$i", 0).toShort() }
    )
    val bandLevels: StateFlow<List<Short>> = _bandLevels.asStateFlow()

    private val _minBandLevel = MutableStateFlow<Short>(-1500) // -15 dB
    val minBandLevel: StateFlow<Short> = _minBandLevel.asStateFlow()

    private val _maxBandLevel = MutableStateFlow<Short>(1500) // +15 dB
    val maxBandLevel: StateFlow<Short> = _maxBandLevel.asStateFlow()

    private val _bassBoostStrength = MutableStateFlow<Short>(prefs.getInt("bass_boost", 0).toShort())
    val bassBoostStrength: StateFlow<Short> = _bassBoostStrength.asStateFlow()

    private val _trebleStrength = MutableStateFlow<Short>(prefs.getInt("treble_strength", 0).toShort())
    val trebleStrength: StateFlow<Short> = _trebleStrength.asStateFlow()

    private val _replayGainEnabled = MutableStateFlow(prefs.getBoolean("replay_gain_enabled", true))
    val replayGainEnabled: StateFlow<Boolean> = _replayGainEnabled.asStateFlow()

    private val _preAmpGainDb = MutableStateFlow<Float>(prefs.getFloat("pre_amp_db", 0.0f))
    val preAmpGainDb: StateFlow<Float> = _preAmpGainDb.asStateFlow()

    private val _isTurboSharpness = MutableStateFlow(prefs.getBoolean("turbo_sharpness", true))
    val isTurboSharpness: StateFlow<Boolean> = _isTurboSharpness.asStateFlow()

    private val _stereoExpansion = MutableStateFlow<Float>(prefs.getFloat("stereo_expansion", 1.0f))
    val stereoExpansion: StateFlow<Float> = _stereoExpansion.asStateFlow()

    private val _limiterThreshold = MutableStateFlow<Float>(prefs.getFloat("limiter_threshold", 0.0f))
    val limiterThreshold: StateFlow<Float> = _limiterThreshold.asStateFlow()

    // Neutral defaults for fresh installs (existing users keep saved values):
    // a "Flat" configuration must not colour the signal (Phase 15.4).
    private val _clarityGain = MutableStateFlow<Float>(prefs.getFloat("clarity_gain", 0.0f))
    val clarityGain: StateFlow<Float> = _clarityGain.asStateFlow()

    private val _warmSaturation = MutableStateFlow<Float>(prefs.getFloat("warm_saturation", 0.0f))
    val warmSaturation: StateFlow<Float> = _warmSaturation.asStateFlow()

    private val _airPresence = MutableStateFlow<Float>(prefs.getFloat("air_presence", 0.0f))
    val airPresence: StateFlow<Float> = _airPresence.asStateFlow()

    private val _crossfeedLevel = MutableStateFlow<Float>(prefs.getFloat("crossfeed_level", 0.0f))
    val crossfeedLevel: StateFlow<Float> = _crossfeedLevel.asStateFlow()

    private val _channelBalance = MutableStateFlow<Float>(prefs.getFloat("channel_balance", 0.0f))
    val channelBalance: StateFlow<Float> = _channelBalance.asStateFlow()

    private val _invertPhase = MutableStateFlow<Boolean>(prefs.getBoolean("invert_phase", false))
    val invertPhase: StateFlow<Boolean> = _invertPhase.asStateFlow()

    private val _subBassMono = MutableStateFlow(prefs.getBoolean("sub_bass_mono", false))
    val subBassMono: StateFlow<Boolean> = _subBassMono.asStateFlow()

    private val _hrtfSpatialEnabled = MutableStateFlow(prefs.getBoolean("hrtf_spatial_enabled", false))
    val hrtfSpatialEnabled: StateFlow<Boolean> = _hrtfSpatialEnabled.asStateFlow()

    private val _hrtfRoomSize = MutableStateFlow<Float>(prefs.getFloat("hrtf_room_size", 0.5f))
    val hrtfRoomSize: StateFlow<Float> = _hrtfRoomSize.asStateFlow()

    private val _currentPresetName = MutableStateFlow(prefs.getString("current_preset", "Flat") ?: "Flat")
    val currentPresetName: StateFlow<String> = _currentPresetName.asStateFlow()

    // Fresh installs start in REFERENCE (transparent) mode. AUDIOPHILE/DYNAMIC
    // are deliberate sound signatures a user opts into; auto-applying one at
    // startup used to silently defeat the neutral-defaults contract.
    private val _listeningMode = MutableStateFlow(
        com.tensorix.antigravityplayer.audio.ListeningMode.valueOf(
            prefs.getString("listening_mode", com.tensorix.antigravityplayer.audio.ListeningMode.REFERENCE.name)
                ?: com.tensorix.antigravityplayer.audio.ListeningMode.REFERENCE.name
        )
    )
    val listeningMode: StateFlow<com.tensorix.antigravityplayer.audio.ListeningMode> = _listeningMode.asStateFlow()

    fun setListeningMode(mode: com.tensorix.antigravityplayer.audio.ListeningMode) {
        _listeningMode.value = mode
        prefs.edit { putString("listening_mode", mode.name) }

        when (mode) {
            com.tensorix.antigravityplayer.audio.ListeningMode.REFERENCE -> {
                setPreAmpGain(0.0f)
                setClarityGain(0.0f)
                setAirPresence(0.0f)
                setWarmSaturation(0.0f)
                setCrossfeedLevel(0.0f)
                setStereoExpansion(1.0f)
            }
            com.tensorix.antigravityplayer.audio.ListeningMode.AUDIOPHILE -> {
                setPreAmpGain(3.5f)
                setClarityGain(3.5f)
                setAirPresence(2.0f)
                setWarmSaturation(0.08f)
                setCrossfeedLevel(0.35f)
                setStereoExpansion(1.1f)
            }
            com.tensorix.antigravityplayer.audio.ListeningMode.DYNAMIC -> {
                setPreAmpGain(4.0f)
                setClarityGain(4.5f)
                setAirPresence(3.0f)
                setWarmSaturation(0.12f)
                setCrossfeedLevel(0.20f)
                setStereoExpansion(1.25f)
            }
        }
        syncWithDsp()
    }

    val builtInPresets = listOf(
        EqPreset("Flat", List(10) { 0 }),
        EqPreset("Bass Boost", listOf(800, 600, 400, 200, 0, 0, 0, 0, 400, 600)),
        EqPreset("Rock", listOf(600, 400, 200, -100, -200, 0, 200, 400, 600, 800)),
        EqPreset("Pop", listOf(-200, -100, 200, 400, 600, 600, 400, 200, -100, -200)),
        EqPreset("Jazz", listOf(400, 300, 100, 200, -200, -200, 100, 200, 400, 500)),
        EqPreset("Vocal", listOf(-400, -200, 200, 500, 800, 800, 500, 200, -200, -400)),
        EqPreset("Heavy Metal", listOf(500, 400, 100, 0, -100, 0, 200, 500, 800, 900)),
        EqPreset("Classical", listOf(500, 400, 300, 200, 0, 0, 0, 200, 400, 500)),
        EqPreset("Harman Target 2020", listOf(450, 400, 250, 100, 0, 0, -200, 0, 150, 200)),
        EqPreset("Audiophile Reference", listOf(150, 100, 50, 0, 0, 0, 0, 50, 100, 150))
    )

    fun setTriodeWarmth(level: Float) {
        _warmSaturation.value = level // Using warmSaturation as proxy for now
        dspProcessor?.triodeWarmthLevel = level.toDouble()
        dspProcessor?.updateAllFiltersLive()
        prefs.edit { putFloat("triode_warmth", level) }
        syncWithDsp()
    }

    fun setPentodeTape(level: Float) {
        dspProcessor?.pentodeTapeLevel = level.toDouble()
        dspProcessor?.updateAllFiltersLive()
        prefs.edit { putFloat("pentode_tape", level) }
        syncWithDsp()
    }

    fun attachToAudioSession(audioSessionId: Int) {
        if (audioSessionId == 0 || audioSessionId == currentAudioSessionId) return
        syncWithDsp()
        release() // Detach previous session effects
        currentAudioSessionId = audioSessionId

        // Framework AudioEffect path is PERMANENTLY DETACHED: the 64-bit DSP
        // (native or JVM fallback) always owns processing, so no Equalizer/
        // BassBoost/Virtualizer/Loudness object is ever created here. This
        // method remains for session bookkeeping only.
    }

    fun setDspProcessor(processor: Audiophile64BitDspProcessor) {
        this.dspProcessor = processor
        syncWithDsp()
    }

    private fun syncWithNativeDsp() {
        val handle = com.tensorix.antigravityplayer.audio.OboeAudioSink.currentActiveHandle
        if (handle != 0L && com.tensorix.antigravityplayer.audio.OboeBridge.isAvailable) {
            try {
                com.tensorix.antigravityplayer.audio.OboeBridge.setDspEnabled(handle, _isEnabled.value)
                com.tensorix.antigravityplayer.audio.OboeBridge.setBitPerfectBypass(handle, _isBitPerfectBypass.value)
                com.tensorix.antigravityplayer.audio.OboeBridge.setPreAmpGainDb(handle, _preAmpGainDb.value.toDouble())
                com.tensorix.antigravityplayer.audio.OboeBridge.setBassBoostGainDb(handle, (_bassBoostStrength.value.toDouble() / 1000.0) * 15.0)
                com.tensorix.antigravityplayer.audio.OboeBridge.setTrebleGainDb(handle, (_trebleStrength.value.toDouble() / 1500.0) * 15.0)
                com.tensorix.antigravityplayer.audio.OboeBridge.setHarmonicExciterLevel(handle, if (_isTurboSharpness.value) 0.25 else 0.0)
                com.tensorix.antigravityplayer.audio.OboeBridge.setClarityEnhancerGain(handle, _clarityGain.value.toDouble())
                com.tensorix.antigravityplayer.audio.OboeBridge.setStereoExpansionMultiplier(handle, _stereoExpansion.value.toDouble())
                com.tensorix.antigravityplayer.audio.OboeBridge.setWarmSaturationLevel(handle, _warmSaturation.value.toDouble())
                // Full parity with OboeAudioSink.syncDspParameters: these were
                // previously missing from the live path (only applied at next
                // stream open).
                com.tensorix.antigravityplayer.audio.OboeBridge.setTriodeWarmthLevel(handle, _warmSaturation.value.toDouble())
                com.tensorix.antigravityplayer.audio.OboeBridge.setPentodeTapeLevel(handle, dspProcessor?.pentodeTapeLevel ?: 0.0)
                com.tensorix.antigravityplayer.audio.OboeBridge.setDitherStrength(handle, if (_isBitPerfectBypass.value) 0.0 else dspProcessor?.ditherStrength ?: 0.0)
                com.tensorix.antigravityplayer.audio.OboeBridge.setOutputBitDepth(handle, dspProcessor?.outputBitDepth ?: 24)
                com.tensorix.antigravityplayer.audio.OboeBridge.setAirPresenceGainDb(handle, _airPresence.value.toDouble())
                com.tensorix.antigravityplayer.audio.OboeBridge.setCrossfeedLevel(handle, _crossfeedLevel.value.toDouble())
                com.tensorix.antigravityplayer.audio.OboeBridge.setLimiterEnabled(handle, _isTurboSharpness.value)
                com.tensorix.antigravityplayer.audio.OboeBridge.setLimiterThresholdDb(handle, _limiterThreshold.value.toDouble())
                com.tensorix.antigravityplayer.audio.OboeBridge.setSubBassMonoEnabled(handle, _subBassMono.value)
                com.tensorix.antigravityplayer.audio.OboeBridge.setChannelBalance(handle, _channelBalance.value.toDouble())
                com.tensorix.antigravityplayer.audio.OboeBridge.setInvertPhase(handle, _invertPhase.value)
                com.tensorix.antigravityplayer.audio.OboeBridge.setHrtfSpatialEnabled(handle, _hrtfSpatialEnabled.value)
                com.tensorix.antigravityplayer.audio.OboeBridge.setHrtfRoomSize(handle, _hrtfRoomSize.value.toDouble())

                _bandLevels.value.forEachIndexed { index, level ->
                    com.tensorix.antigravityplayer.audio.OboeBridge.setBandGain(handle, index, level.toDouble() / 100.0)
                }
            } catch (e: Exception) {
                Log.w("EqualizerEngine", "Native DSP sync notice: ${e.message}")
            }
        }
    }

    private fun syncWithDsp() {
        val dsp = dspProcessor
        if (dsp != null) {
            dsp.isEnabled = _isEnabled.value
            dsp.isBitPerfectBypass = _isBitPerfectBypass.value
            dsp.isTurboMode = _isTurboSharpness.value
            dsp.harmonicExciterLevel = if (_isTurboSharpness.value) 0.25 else 0.0
            dsp.stereoExpansionMultiplier = _stereoExpansion.value.toDouble()
            dsp.limiterThresholdDb = _limiterThreshold.value.toDouble()
            dsp.clarityEnhancerGain = _clarityGain.value.toDouble()
            dsp.warmSaturationLevel = _warmSaturation.value.toDouble()
            dsp.airPresenceGainDb = _airPresence.value.toDouble()
            dsp.crossfeedLevel = _crossfeedLevel.value.toDouble()
            dsp.channelBalance = _channelBalance.value.toDouble()
            dsp.invertPhase = _invertPhase.value
            dsp.preAmpGainDb = _preAmpGainDb.value.toDouble()
            dsp.bassBoostGainDb = (_bassBoostStrength.value.toDouble() / 1000.0) * 15.0 // Map 0-1000 to 0-15dB
            dsp.trebleGainDb = (_trebleStrength.value.toDouble() / 1500.0) * 15.0 // Map 0-1500 to 0-15dB
            
            _bandLevels.value.forEachIndexed { index, level ->
                dsp.setBandGain(index, level.toDouble() / 100.0) // mB to dB
            }
        }
        syncWithNativeDsp()
    }

    fun setBitPerfectBypass(bypass: Boolean) {
        _isBitPerfectBypass.value = bypass
        dspProcessor?.isBitPerfectBypass = bypass
        prefs.edit().putBoolean("bit_perfect_bypass", bypass).apply()
        if (bypass) {
            release()
        } else if (currentAudioSessionId != 0) {
            val session = currentAudioSessionId
            currentAudioSessionId = 0
            attachToAudioSession(session)
        }
    }

    fun setBandLevel(band: Short, level: Short) {
        // No drop-on-throttle: every update lands. The heavy work below is
        // cheap post-hardening (atomic param stores + queued coefficient
        // rebuilds), so slider-rate storms are harmless.
        dspProcessor?.setBandGain(band.toInt(), level.toDouble() / 100.0)

        val currentLevels = _bandLevels.value.toMutableList()
        if (band.toInt() in currentLevels.indices) {
            currentLevels[band.toInt()] = level
            _bandLevels.value = currentLevels
        }

        // Set to custom if manual change
        if (_currentPresetName.value != "Custom") {
            _currentPresetName.value = "Custom"
            prefs.edit().putString("current_preset", "Custom").apply()
        }

        prefs.edit().putInt("band_$band", level.toInt()).apply()
        syncWithDsp()
    }

    fun setBassBoost(strength: Short) {
        val safeStrength = strength.coerceIn(0, 1000)
        _bassBoostStrength.value = safeStrength
        dspProcessor?.bassBoostGainDb = (safeStrength.toDouble() / 1000.0) * 15.0
        syncWithDsp()
        try {
            bassBoost?.let {
                if (runCatching { it.strengthSupported }.getOrDefault(false)) {
                    it.setStrength(safeStrength)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("Antigravity", "Failure in " + javaClass.simpleName, e)
        }
        prefs.edit().putInt("bass_boost", safeStrength.toInt()).apply()
    }

    /** INERT: framework Virtualizer never attaches while the DSP owns the chain.
    *  Kept as a no-op for API compatibility. */
    fun setVirtualizer(strength: Short) { /* intentionally inert */ }

    /** INERT: framework LoudnessEnhancer never attaches while the DSP owns
    *  the chain. Kept as a no-op for API compatibility. */
    fun setLoudnessGain(gainmB: Int) { /* intentionally inert */ }

    fun setPreAmpGain(gainDb: Float) {
        _preAmpGainDb.value = gainDb
        dspProcessor?.preAmpGainDb = gainDb.toDouble()
        prefs.edit().putFloat("pre_amp_db", gainDb).apply()
        syncWithDsp()
    }

    fun setClarityGain(gainDb: Float) {
        _clarityGain.value = gainDb
        dspProcessor?.clarityEnhancerGain = gainDb.toDouble()
        dspProcessor?.updateAllFiltersLive()
        prefs.edit().putFloat("clarity_gain", gainDb).apply()
        syncWithDsp()
    }

    fun setWarmSaturation(level: Float) {
        _warmSaturation.value = level
        dspProcessor?.warmSaturationLevel = level.toDouble()
        prefs.edit().putFloat("warm_saturation", level).apply()
        syncWithDsp()
    }

    fun setAirPresence(gainDb: Float) {
        _airPresence.value = gainDb
        dspProcessor?.setAirPresenceGain(gainDb.toDouble())
        dspProcessor?.updateAllFiltersLive()
        prefs.edit().putFloat("air_presence", gainDb).apply()
        syncWithDsp()
    }

    fun setCrossfeedLevel(level: Float) {
        _crossfeedLevel.value = level
        dspProcessor?.crossfeedLevel = level.toDouble()
        dspProcessor?.updateAllFiltersLive()
        prefs.edit().putFloat("crossfeed_level", level).apply()
        syncWithDsp()
    }

    fun setStereoExpansion(multiplier: Float) {
        _stereoExpansion.value = multiplier
        dspProcessor?.stereoExpansionMultiplier = multiplier.toDouble()
        prefs.edit().putFloat("stereo_expansion", multiplier).apply()
        syncWithDsp()
    }

    fun setLimiterThreshold(db: Float) {
        _limiterThreshold.value = db
        dspProcessor?.limiterThresholdDb = db.toDouble()
        prefs.edit().putFloat("limiter_threshold", db).apply()
        syncWithDsp()
    }

    fun setChannelBalance(balance: Float) {
        _channelBalance.value = balance
        dspProcessor?.channelBalance = balance.toDouble()
        prefs.edit().putFloat("channel_balance", balance).apply()
        syncWithDsp()
    }

    fun setInvertPhase(invert: Boolean) {
        _invertPhase.value = invert
        dspProcessor?.invertPhase = invert
        prefs.edit().putBoolean("invert_phase", invert).apply()
        syncWithDsp()
    }

    fun setTurboSharpness(enabled: Boolean) {
        _isTurboSharpness.value = enabled
        val dsp = dspProcessor
        if (dsp != null) {
            dsp.isTurboMode = enabled
            dsp.harmonicExciterLevel = if (enabled) 0.25 else 0.0
            dsp.limiterEnabled = enabled
        }
        prefs.edit().putBoolean("turbo_sharpness", enabled).apply()
        syncWithDsp()
    }

    fun setTrebleStrength(strength: Short) {
        _trebleStrength.value = strength
        dspProcessor?.trebleGainDb = (strength.toDouble() / 1500.0) * 15.0
        syncWithDsp()
        prefs.edit().putInt("treble_strength", strength.toInt()).apply()
    }

    /** INERT: PresetReverb was never instantiated. No-op for API compat. */
    fun setReverbPreset(preset: Short) { /* intentionally inert */ }

    fun applyPreset(preset: EqPreset) {
        _currentPresetName.value = preset.name
        prefs.edit().putString("current_preset", preset.name).apply()
        
        val newLevels = _bandLevels.value.toMutableList()
        preset.bandLevels.forEachIndexed { index, level ->
            if (index < newLevels.size) {
                newLevels[index] = level.toShort()
                dspProcessor?.setBandGain(index, level.toDouble() / 100.0)
                prefs.edit().putInt("band_$index", level.toInt()).apply()
            }
        }
        dspProcessor?.updateAllFiltersLive()
        _bandLevels.value = newLevels
        syncWithDsp()
    }

    fun setReplayGainEnabled(enabled: Boolean) {
        _replayGainEnabled.value = enabled
        prefs.edit().putBoolean("replay_gain_enabled", enabled).apply()
    }

    fun setSubBassMono(enabled: Boolean) {
        _subBassMono.value = enabled
        dspProcessor?.subBassMonoEnabled = enabled
        dspProcessor?.updateAllFiltersLive()
        prefs.edit().putBoolean("sub_bass_mono", enabled).apply()
        syncWithDsp()
    }

    fun setHrtfSpatialEnabled(enabled: Boolean) {
        _hrtfSpatialEnabled.value = enabled
        prefs.edit().putBoolean("hrtf_spatial_enabled", enabled).apply()
        syncWithDsp()
    }

    fun setHrtfRoomSize(roomSize: Float) {
        val safeSize = roomSize.coerceIn(0.0f, 1.0f)
        _hrtfRoomSize.value = safeSize
        prefs.edit().putFloat("hrtf_room_size", safeSize).apply()
        syncWithDsp()
    }

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        dspProcessor?.isEnabled = enabled

        if (enabled && dspProcessor != null) {
            // JVM/native DSP owns the EQ: framework effects stay detached.
            release()
        } else if (!enabled) {
            // Disabling the equalizer must NOT silently fall through to the
            // framework Equalizer with the same bands applied (previous bug):
            // off means off.
            release()
        }

        dspProcessor?.updateAllFiltersLive()
        prefs.edit().putBoolean("eq_enabled", enabled).apply()
        syncWithDsp()
    }

    fun applyHiFiProfile(profile: com.tensorix.antigravityplayer.audio.HiFiProfile) {
        _currentPresetName.value = profile.name

        // Phase 13 contamination fix: this used to perform 10 separate
        // prefs.apply() disk writes + framework-effect writes on EVERY route
        // evaluation. Now: compute once, skip when identical, persist via a
        // SINGLE editor transaction.
        val newLevels = _bandLevels.value.toMutableList()
        var levelsChanged = false
        profile.eqGainsDb.forEachIndexed { index, gain ->
            if (index < newLevels.size) {
                val level = (gain * 100).toInt().toShort()
                if (newLevels[index] != level) levelsChanged = true
                newLevels[index] = level
                dspProcessor?.setBandGain(index, gain)
            }
        }

        val targetBass = (profile.bassBoostDb * 100).toInt()
        val targetTreble = (profile.trebleGainDb * 100).toInt()
        val targetCrossfeed = if (profile.crossfeedEnabled) 0.5f else 0.0f

        val unchanged = !levelsChanged &&
            _bassBoostStrength.value.toInt() == targetBass &&
            _trebleStrength.value.toInt() == targetTreble &&
            _crossfeedLevel.value == targetCrossfeed

        if (unchanged) return

        prefs.edit().apply {
            newLevels.forEachIndexed { index, level ->
                putInt("band_$index", level.toInt())
            }
            putInt("bass_boost", targetBass)
            putInt("treble_strength", targetTreble)
        }.apply()

        _bandLevels.value = newLevels
        setCrossfeedLevel(targetCrossfeed)
        setReplayGainEnabled(profile.replayGainEnabled)
        dspProcessor?.updateAllFiltersLive()
    }

    fun release() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.release() }
        runCatching { loudnessEnhancer?.release() }
        equalizer = null
        bassBoost = null
        virtualizer = null
        loudnessEnhancer = null
    }
}