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
import com.tensorix.antigravityplayer.audio.AuthoritativeDspConfig
import com.tensorix.antigravityplayer.audio.AuthoritativePeqBand
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

    private val _triodeWarmth = MutableStateFlow<Float>(prefs.getFloat("triode_warmth", 0.0f))
    val triodeWarmth: StateFlow<Float> = _triodeWarmth.asStateFlow()

    private val _pentodeTape = MutableStateFlow<Float>(prefs.getFloat("pentode_tape", 0.0f))
    val pentodeTape: StateFlow<Float> = _pentodeTape.asStateFlow()

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

    private val _dvcVolume = MutableStateFlow<Double>(1.0)
    val dvcVolume: StateFlow<Double> = _dvcVolume.asStateFlow()

    private val _userVolume = MutableStateFlow<Double>(1.0)
    val userVolume: StateFlow<Double> = _userVolume.asStateFlow()

    private val _replayGainMultiplier = MutableStateFlow<Double>(1.0)
    val replayGainMultiplier: StateFlow<Double> = _replayGainMultiplier.asStateFlow()

    private val _autoEqProfile = MutableStateFlow<com.tensorix.antigravityplayer.audio.AutoEqProfile?>(null)
    val autoEqProfile: StateFlow<com.tensorix.antigravityplayer.audio.AutoEqProfile?> = _autoEqProfile.asStateFlow()

    private val _isAutoEqEnabled = MutableStateFlow<Boolean>(false)
    val isAutoEqEnabled: StateFlow<Boolean> = _isAutoEqEnabled.asStateFlow()

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
        when (mode) {
            com.tensorix.antigravityplayer.audio.ListeningMode.REFERENCE -> {
                _preAmpGainDb.value = 0.0f
                _clarityGain.value = 0.0f
                _airPresence.value = 0.0f
                _warmSaturation.value = 0.0f
                _crossfeedLevel.value = 0.0f
                _stereoExpansion.value = 1.0f
            }
            com.tensorix.antigravityplayer.audio.ListeningMode.AUDIOPHILE -> {
                _preAmpGainDb.value = 3.5f
                _clarityGain.value = 3.5f
                _airPresence.value = 2.0f
                _warmSaturation.value = 0.08f
                _crossfeedLevel.value = 0.35f
                _stereoExpansion.value = 1.1f
            }
            com.tensorix.antigravityplayer.audio.ListeningMode.DYNAMIC -> {
                _preAmpGainDb.value = 4.0f
                _clarityGain.value = 4.5f
                _airPresence.value = 3.0f
                _warmSaturation.value = 0.12f
                _crossfeedLevel.value = 0.20f
                _stereoExpansion.value = 1.25f
            }
        }
        prefs.edit {
            putString("listening_mode", mode.name)
            putFloat("pre_amp_db", _preAmpGainDb.value)
            putFloat("clarity_gain", _clarityGain.value)
            putFloat("air_presence", _airPresence.value)
            putFloat("warm_saturation", _warmSaturation.value)
            putFloat("crossfeed_level", _crossfeedLevel.value)
            putFloat("stereo_expansion", _stereoExpansion.value)
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
        _triodeWarmth.value = level
        prefs.edit { putFloat("triode_warmth", level) }
        syncWithDsp()
    }

    fun setPentodeTape(level: Float) {
        _pentodeTape.value = level
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

    fun buildAuthoritativeConfig(): AuthoritativeDspConfig {
        val isBypass = _isBitPerfectBypass.value
        val isEnabled = _isEnabled.value
        val isRgEnabled = _replayGainEnabled.value
        val dsp = dspProcessor
        val eqBands = List(10) { idx ->
            if (isBypass) 0.0 else (_bandLevels.value.getOrNull(idx)?.toDouble() ?: 0.0) / 100.0
        }

        val autoEqEngine = PlaybackService.instance?.autoEqEngine
        val isAutoEq = (_isAutoEqEnabled.value || autoEqEngine?.isAutoEqEnabled?.value == true) && !isBypass && isEnabled
        val activeProfile = _autoEqProfile.value ?: autoEqEngine?.activeProfile?.value
        val peqList = if (isAutoEq) {
            activeProfile?.bands?.map { band ->
                AuthoritativePeqBand(
                    filterType = band.filterType,
                    frequencyHz = band.frequencyHz,
                    qFactor = band.qFactor,
                    gainDb = band.gainDb,
                    isEnabled = true
                )
            } ?: emptyList()
        } else {
            emptyList()
        }

        val effVolume = if (isBypass) 1.0 else (_dvcVolume.value * _userVolume.value).coerceIn(0.0, 1.0)
        val rgMult = if (isBypass || !isRgEnabled) 1.0 else _replayGainMultiplier.value

        return AuthoritativeDspConfig(
            isEnabled = isEnabled,
            isBitPerfectBypass = isBypass,
            preAmpGainDb = if (isBypass) 0.0 else _preAmpGainDb.value.toDouble(),
            bassBoostGainDb = if (isBypass) 0.0 else (_bassBoostStrength.value.toDouble() / 1000.0) * 15.0,
            trebleGainDb = if (isBypass) 0.0 else (_trebleStrength.value.toDouble() / 1500.0) * 15.0,
            clarityEnhancerGainDb = if (isBypass) 0.0 else _clarityGain.value.toDouble(),
            harmonicExciterLevel = if (isBypass) 0.0 else if (_isTurboSharpness.value) 0.25 else 0.0,
            warmSaturationLevel = if (isBypass) 0.0 else _warmSaturation.value.toDouble(),
            triodeWarmthLevel = if (isBypass) 0.0 else _triodeWarmth.value.toDouble(),
            pentodeTapeLevel = if (isBypass) 0.0 else _pentodeTape.value.toDouble(),
            crossfeedLevel = if (isBypass) 0.0 else _crossfeedLevel.value.toDouble(),
            stereoExpansionMultiplier = if (isBypass) 1.0 else _stereoExpansion.value.toDouble(),
            channelBalance = if (isBypass) 0.0 else _channelBalance.value.toDouble(),
            invertPhase = !isBypass && _invertPhase.value,
            airPresenceGainDb = if (isBypass) 0.0 else _airPresence.value.toDouble(),
            subBassMonoEnabled = !isBypass && _subBassMono.value,
            hrtfSpatialEnabled = !isBypass && _hrtfSpatialEnabled.value,
            hrtfRoomSize = if (isBypass) 0.5 else _hrtfRoomSize.value.toDouble(),
            limiterEnabled = !isBypass && isEnabled && (dsp?.isLimiterActive ?: true),
            limiterThresholdDb = if (isBypass) 0.0 else _limiterThreshold.value.toDouble(),
            ditherStrength = if (isBypass) 0.0 else (dsp?.ditherStrength ?: 0.0),
            outputBitDepth = dsp?.outputBitDepth ?: 24,
            replayGainEnabled = isRgEnabled,
            replayGainMultiplier = rgMult,
            dvcVolume = effVolume,
            bandGainsDb = eqBands,
            peqBands = peqList,
            isAutoEqEnabled = isAutoEq
        ).validated()
    }

    internal fun syncWithNativeDsp(targetHandle: Long = 0L, config: AuthoritativeDspConfig = buildAuthoritativeConfig()) {
        val handle = if (targetHandle != 0L) targetHandle else com.tensorix.antigravityplayer.audio.OboeAudioSink.currentActiveHandle
        if (handle != 0L && com.tensorix.antigravityplayer.audio.OboeBridge.isAvailable) {
            try {
                val count = if (config.isAutoEqEnabled) config.peqBands.size else 0
                val types = if (count > 0) IntArray(count) { config.peqBands[it].filterType } else IntArray(0)
                val freqs = if (count > 0) DoubleArray(count) { config.peqBands[it].frequencyHz } else DoubleArray(0)
                val qs = if (count > 0) DoubleArray(count) { config.peqBands[it].qFactor } else DoubleArray(0)
                val gains = if (count > 0) DoubleArray(count) { config.peqBands[it].gainDb } else DoubleArray(0)
                val enableds = if (count > 0) BooleanArray(count) { config.peqBands[it].isEnabled } else BooleanArray(0)

                com.tensorix.antigravityplayer.audio.OboeBridge.setDspUnifiedConfig(
                    handle = handle,
                    enabled = !config.isBitPerfectBypass && config.isEnabled,
                    bitPerfectBypass = config.isBitPerfectBypass,
                    activeFlags = config.computeActiveFlags(),
                    params = config.toNativeDoubleParams(),
                    outputBitDepth = config.outputBitDepth,
                    invertPhase = config.invertPhase,
                    peqTypes = types,
                    peqFrequencies = freqs,
                    peqQs = qs,
                    peqGainsDb = gains,
                    peqEnableds = enableds
                )
            } catch (e: Exception) {
                Log.w("EqualizerEngine", "Native DSP sync notice", e)
            }
        }
    }

    internal fun buildNativeDspDoubleParameters(
        isBypass: Boolean,
        isRgEnabled: Boolean,
        dsp: Audiophile64BitDspProcessor?,
        eqBands: DoubleArray
    ): DoubleArray {
        val doubleParams = DoubleArray(NATIVE_DSP_PARAM_COUNT) { 0.0 }
        doubleParams[0] = if (isBypass) 0.0 else _preAmpGainDb.value.toDouble()
        doubleParams[1] = if (isBypass) 0.0 else (_bassBoostStrength.value.toDouble() / 1000.0) * 15.0
        doubleParams[2] = if (isBypass) 0.0 else (_trebleStrength.value.toDouble() / 1500.0) * 15.0
        doubleParams[3] = if (isBypass) 0.0 else if (_isTurboSharpness.value) 0.25 else 0.0
        doubleParams[4] = if (isBypass) 0.0 else _clarityGain.value.toDouble()
        doubleParams[5] = if (isBypass) 1.0 else _stereoExpansion.value.toDouble()
        doubleParams[6] = if (isBypass) 1.0 else (dsp?.dvcVolume ?: 1.0)
        doubleParams[7] = if (isBypass || !isRgEnabled) 1.0 else (dsp?.replayGainMultiplier ?: 1.0)
        doubleParams[8] = if (isBypass) 0.0 else (dsp?.ditherStrength ?: 0.0)
        doubleParams[9] = if (isBypass) 0.0 else _warmSaturation.value.toDouble()
        doubleParams[10] = if (isBypass) 0.0 else _triodeWarmth.value.toDouble()
        doubleParams[11] = if (isBypass) 0.0 else _pentodeTape.value.toDouble()
        doubleParams[12] = if (isBypass) 0.0 else _crossfeedLevel.value.toDouble()
        doubleParams[13] = if (isBypass) 0.0 else _limiterThreshold.value.toDouble()
        doubleParams[14] = if (isBypass) 0.0 else _channelBalance.value.toDouble()
        doubleParams[15] = if (isBypass) 0.0 else _airPresence.value.toDouble()
        doubleParams[16] = if (isBypass) 0.5 else _hrtfRoomSize.value.toDouble()
        for (i in 0 until 10) {
            doubleParams[17 + i] = if (isBypass) 0.0 else (eqBands.getOrNull(i) ?: 0.0)
        }
        return doubleParams
    }

    companion object {
        const val NATIVE_DSP_PARAM_COUNT = 27
    }

    private fun syncWithDsp() {
        val config = buildAuthoritativeConfig()
        dspProcessor?.applyConfiguration(config.toFallbackDspConfiguration())
        syncWithNativeDsp(0L, config)
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
        syncWithDsp()
    }

    fun setBandLevel(band: Short, level: Short) {
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
        prefs.edit().putInt("bass_boost", safeStrength.toInt()).apply()
        syncWithDsp()
    }

    /** INERT: framework Virtualizer never attaches while the DSP owns the chain.
    *  Kept as a no-op for API compatibility. */
    fun setVirtualizer(strength: Short) { /* intentionally inert */ }

    /** INERT: framework LoudnessEnhancer never attaches while the DSP owns
    *  the chain. Kept as a no-op for API compatibility. */
    fun setLoudnessGain(gainmB: Int) { /* intentionally inert */ }

    fun setPreAmpGain(gainDb: Float) {
        _preAmpGainDb.value = gainDb
        prefs.edit().putFloat("pre_amp_db", gainDb).apply()
        syncWithDsp()
    }

    fun setClarityGain(gainDb: Float) {
        _clarityGain.value = gainDb
        prefs.edit().putFloat("clarity_gain", gainDb).apply()
        syncWithDsp()
    }

    fun setWarmSaturation(level: Float) {
        _warmSaturation.value = level
        prefs.edit().putFloat("warm_saturation", level).apply()
        syncWithDsp()
    }

    fun setAirPresence(gainDb: Float) {
        _airPresence.value = gainDb
        prefs.edit().putFloat("air_presence", gainDb).apply()
        syncWithDsp()
    }

    fun setCrossfeedLevel(level: Float) {
        _crossfeedLevel.value = level
        prefs.edit().putFloat("crossfeed_level", level).apply()
        syncWithDsp()
    }

    fun setStereoExpansion(multiplier: Float) {
        _stereoExpansion.value = multiplier
        prefs.edit().putFloat("stereo_expansion", multiplier).apply()
        syncWithDsp()
    }

    fun setLimiterThreshold(db: Float) {
        _limiterThreshold.value = db
        prefs.edit().putFloat("limiter_threshold", db).apply()
        syncWithDsp()
    }

    fun setChannelBalance(balance: Float) {
        _channelBalance.value = balance
        prefs.edit().putFloat("channel_balance", balance).apply()
        syncWithDsp()
    }

    fun setInvertPhase(invert: Boolean) {
        _invertPhase.value = invert
        prefs.edit().putBoolean("invert_phase", invert).apply()
        syncWithDsp()
    }

    fun setTurboSharpness(enabled: Boolean) {
        _isTurboSharpness.value = enabled
        prefs.edit().putBoolean("turbo_sharpness", enabled).apply()
        syncWithDsp()
    }

    fun setTrebleStrength(strength: Short) {
        _trebleStrength.value = strength
        prefs.edit().putInt("treble_strength", strength.toInt()).apply()
        syncWithDsp()
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
                prefs.edit().putInt("band_$index", level.toInt()).apply()
            }
        }
        _bandLevels.value = newLevels
        syncWithDsp()
    }

    fun setReplayGainEnabled(enabled: Boolean) {
        _replayGainEnabled.value = enabled
        prefs.edit().putBoolean("replay_gain_enabled", enabled).apply()
        syncWithDsp()
    }

    fun setSubBassMono(enabled: Boolean) {
        _subBassMono.value = enabled
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
        if (enabled && dspProcessor != null) {
            release()
        } else if (!enabled) {
            release()
        }
        prefs.edit().putBoolean("eq_enabled", enabled).apply()
        syncWithDsp()
    }

    fun applyHiFiProfile(profile: com.tensorix.antigravityplayer.audio.HiFiProfile) {
        _currentPresetName.value = profile.name

        val newLevels = _bandLevels.value.toMutableList()
        var levelsChanged = false
        profile.eqGainsDb.forEachIndexed { index, gain ->
            if (index < newLevels.size) {
                val level = (gain * 100).toInt().toShort()
                if (newLevels[index] != level) levelsChanged = true
                newLevels[index] = level
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
            putFloat("crossfeed_level", targetCrossfeed)
            putBoolean("replay_gain_enabled", profile.replayGainEnabled)
        }.apply()

        _bandLevels.value = newLevels
        _bassBoostStrength.value = targetBass.toShort()
        _trebleStrength.value = targetTreble.toShort()
        _crossfeedLevel.value = targetCrossfeed
        _replayGainEnabled.value = profile.replayGainEnabled
        syncWithDsp()
    }

    fun setDvcVolume(volume: Double) {
        val safeVol = volume.coerceIn(0.0, 1.0)
        _dvcVolume.value = safeVol
        dspProcessor?.dvcVolume = safeVol
        syncWithDsp()
    }

    fun setUserVolume(volume: Double) {
        val safeVol = volume.coerceIn(0.0, 1.0)
        _userVolume.value = safeVol
        syncWithDsp()
    }

    fun setReplayGainMultiplier(multiplier: Double) {
        val safeMult = multiplier.coerceIn(0.0, 10.0)
        _replayGainMultiplier.value = safeMult
        dspProcessor?.replayGainMultiplier = safeMult
        syncWithDsp()
    }

    fun applyAutoEqProfile(profile: com.tensorix.antigravityplayer.audio.AutoEqProfile) {
        _autoEqProfile.value = profile
        _isAutoEqEnabled.value = true
        _preAmpGainDb.value = profile.preampDb.toFloat().coerceIn(-12.0f, 0.0f)
        prefs.edit {
            putFloat("pre_amp_db", _preAmpGainDb.value)
        }
        syncWithDsp()
    }

    fun disableAutoEq() {
        _isAutoEqEnabled.value = false
        _preAmpGainDb.value = 0.0f
        prefs.edit {
            putFloat("pre_amp_db", 0.0f)
        }
        syncWithDsp()
    }

    fun clearAutoEq() {
        _autoEqProfile.value = null
        _isAutoEqEnabled.value = false
        _preAmpGainDb.value = 0.0f
        prefs.edit {
            putFloat("pre_amp_db", 0.0f)
        }
        syncWithDsp()
    }

    fun release() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        equalizer = null
        bassBoost = null
    }
}