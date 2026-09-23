package com.tensorix.antigravityplayer.audio

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Persists and manages audiophile-grade output configurations per device category.
 * Mimics Poweramp/UAPP per-output settings.
 */
class AudioOutputConfigManager private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("antigravity_output_configs", Context.MODE_PRIVATE)

    private val _configUpdates = MutableSharedFlow<AudioOutputRouteType>(extraBufferCapacity = 10)
    val configUpdates: SharedFlow<AudioOutputRouteType> = _configUpdates.asSharedFlow()

    companion object {
        @Volatile
        private var instance: AudioOutputConfigManager? = null

        fun getInstance(context: Context): AudioOutputConfigManager {
            return instance ?: synchronized(this) {
                instance ?: AudioOutputConfigManager(context.applicationContext).also { instance = it }
            }
        }
    }

    fun getConfigForDevice(routeType: AudioOutputRouteType): OutputDeviceConfig {
        val prefix = routeType.name.lowercase()
        val defaultApiStr = getDefaultApi(routeType)
        val savedApiStr = prefs.getString("${prefix}_api", defaultApiStr) ?: defaultApiStr
        val resolvedApi = runCatching { AudioOutputApi.valueOf(savedApiStr) }.getOrDefault(AudioOutputApi.AAUDIO)
        return OutputDeviceConfig(
            api = resolvedApi,
            sampleRate = prefs.getInt("${prefix}_sample_rate", 48000),
            bitDepth = prefs.getInt("${prefix}_bit_depth", 24),
            bufferSizeMultiplier = prefs.getInt("${prefix}_buffer_mult", 2),
            exclusiveMode = prefs.getBoolean("${prefix}_exclusive", false),
            ditherEnabled = prefs.getBoolean("${prefix}_dither", true)
        )
    }

    fun saveConfigForDevice(routeType: AudioOutputRouteType, config: OutputDeviceConfig) {
        val prefix = routeType.name.lowercase()
        prefs.edit().putString("${prefix}_api", config.api.name)
            .putInt("${prefix}_sample_rate", config.sampleRate)
            .putInt("${prefix}_bit_depth", config.bitDepth)
            .putInt("${prefix}_buffer_mult", config.bufferSizeMultiplier)
            .putBoolean("${prefix}_exclusive", config.exclusiveMode)
            .putBoolean("${prefix}_dither", config.ditherEnabled)
            .apply()
        
        _configUpdates.tryEmit(routeType)
    }

    private fun getDefaultApi(routeType: AudioOutputRouteType): String {
        return when (routeType) {
            AudioOutputRouteType.USB_DAC -> AudioOutputApi.AAUDIO.name
            AudioOutputRouteType.WIRED_HEADPHONES, AudioOutputRouteType.WIRED_HEADSET -> AudioOutputApi.AAUDIO.name
            else -> AudioOutputApi.AUDIOTRACK.name
        }
    }
}
