package com.tensorix.antigravityplayer.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.tensorix.antigravityplayer.util.CrashDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Isolated Vendor-Specific DAC & Hardware HAL Integrator.
 * Gated by strict OEM adapters with zero global parameter injection.
 */
object VendorDacManager {

    private const val TAG = "VendorDacManager"

    @Volatile
    var isLgQuadDacActive: Boolean = false
        internal set

    @Volatile
    var isVivoHiFiActive: Boolean = false
        internal set

    @Volatile
    var isSamsungUhqActive: Boolean = false
        internal set

    @Volatile
    var isSonyHiResActive: Boolean = false
        internal set

    @Volatile
    var isQualcommDirectActive: Boolean = false
        internal set

    @Volatile
    private var detectedDacNameInternal: String = "Internal Audio HAL"
    val activeDacName: String get() = detectedDacNameInternal

    // Adapter Interfaces
    interface VendorAdapter {
        fun activate(context: Context): Boolean
        fun deactivate(context: Context)
    }

    object VivoAdapter : VendorAdapter {
        override fun activate(context: Context): Boolean {
            if (!SafeAudioParameterController.isVendorMatch(SafeAudioParameterController.TargetVendor.VIVO)) return false
            
            SafeAudioParameterController.setParameter(context, SafeAudioParameterController.TargetVendor.VIVO, "hifi_state", "on")
            SafeAudioParameterController.setParameter(context, SafeAudioParameterController.TargetVendor.VIVO, "hifi_dac_enable", "1")
            SafeAudioParameterController.setParameter(context, SafeAudioParameterController.TargetVendor.VIVO, "hifi_mode", "1")
            SafeAudioParameterController.setParameter(context, SafeAudioParameterController.TargetVendor.VIVO, "vivo_hifi", "1")
            SafeAudioParameterController.setParameter(context, SafeAudioParameterController.TargetVendor.VIVO, "vivo_headset_hifi", "1")
            SafeAudioParameterController.setParameter(context, SafeAudioParameterController.TargetVendor.VIVO, "vivo_app_package_name", context.packageName)

            // Safe Settings.System check
            return try {
                if (Settings.System.canWrite(context)) {
                    val cr = context.contentResolver
                    val current = Settings.System.getString(cr, "vivo_hifi_music_app_list") ?: ""
                    if (!current.contains(context.packageName)) {
                        Settings.System.putString(
                            cr,
                            "vivo_hifi_music_app_list",
                            if (current.isEmpty()) context.packageName else "$current,${context.packageName}"
                        )
                    }
                    Settings.System.putInt(cr, "hifi_settings_music", 1)
                    true
                } else false
            } catch (iae: IllegalArgumentException) {
                // ROM redirected to Secure settings or rejected key; expected on standard user builds
                Log.d(TAG, "VivoAdapter: Settings.System key unsupported on this build (${iae.message})")
                false
            } catch (t: Throwable) {
                Log.d(TAG, "VivoAdapter: Settings.System write unavailable (${t.message})")
                false
            }
        }

        override fun deactivate(context: Context) {
            if (SafeAudioParameterController.isVendorMatch(SafeAudioParameterController.TargetVendor.VIVO)) {
                SafeAudioParameterController.setParameter(context, SafeAudioParameterController.TargetVendor.VIVO, "hifi_state", "off")
            }
        }
    }

    object SamsungAdapter : VendorAdapter {
        override fun activate(context: Context): Boolean {
            if (!SafeAudioParameterController.isVendorMatch(SafeAudioParameterController.TargetVendor.SAMSUNG)) return false
            
            SafeAudioParameterController.setParameter(context, SafeAudioParameterController.TargetVendor.SAMSUNG, "hifi_mode", "on")
            SafeAudioParameterController.setParameter(context, SafeAudioParameterController.TargetVendor.SAMSUNG, "udp_on", "1")
            SafeAudioParameterController.setParameter(context, SafeAudioParameterController.TargetVendor.SAMSUNG, "upscaling_mode", "1")

            return try {
                if (Settings.System.canWrite(context)) {
                    Settings.System.putInt(context.contentResolver, "sound_alive_uhq_upscaler", 1)
                    true
                } else false
            } catch (t: Throwable) {
                CrashDiagnostics.record("SAMSUNG_ADAPTER", "Settings.System write", t)
                false
            }
        }

        override fun deactivate(context: Context) {
            if (SafeAudioParameterController.isVendorMatch(SafeAudioParameterController.TargetVendor.SAMSUNG)) {
                SafeAudioParameterController.setParameter(context, SafeAudioParameterController.TargetVendor.SAMSUNG, "upscaling_mode", "0")
            }
        }
    }

    object SonyAdapter : VendorAdapter {
        override fun activate(context: Context): Boolean {
            if (!SafeAudioParameterController.isVendorMatch(SafeAudioParameterController.TargetVendor.SONY)) return false
            
            SafeAudioParameterController.setParameter(context, SafeAudioParameterController.TargetVendor.SONY, "audio_output_format", "hi-res")
            SafeAudioParameterController.setParameter(context, SafeAudioParameterController.TargetVendor.SONY, "hires_mode", "on")

            return try {
                if (Settings.System.canWrite(context)) {
                    Settings.System.putInt(context.contentResolver, "sony_hires_audio_enabled", 1)
                    true
                } else false
            } catch (t: Throwable) {
                CrashDiagnostics.record("SONY_ADAPTER", "Settings.System write", t)
                false
            }
        }

        override fun deactivate(context: Context) {}
    }

    object LGAdapter : VendorAdapter {
        override fun activate(context: Context): Boolean {
            if (!SafeAudioParameterController.isVendorMatch(SafeAudioParameterController.TargetVendor.LG)) return false
            
            SafeAudioParameterController.setParameter(context, SafeAudioParameterController.TargetVendor.LG, "hifi_dac", "on")
            SafeAudioParameterController.setParameter(context, SafeAudioParameterController.TargetVendor.LG, "quadbeat_hifi", "1")

            return try {
                if (Settings.System.canWrite(context)) {
                    Settings.System.putInt(context.contentResolver, "quad_dac_state", 1)
                    true
                } else false
            } catch (t: Throwable) {
                CrashDiagnostics.record("LG_ADAPTER", "Settings.System write", t)
                false
            }
        }

        override fun deactivate(context: Context) {
            if (SafeAudioParameterController.isVendorMatch(SafeAudioParameterController.TargetVendor.LG)) {
                SafeAudioParameterController.setParameter(context, SafeAudioParameterController.TargetVendor.LG, "hifi_dac", "off")
            }
        }
    }

    object QualcommAdapter : VendorAdapter {
        override fun activate(context: Context): Boolean {
            if (!SafeAudioParameterController.isVendorMatch(SafeAudioParameterController.TargetVendor.QUALCOMM)) return false
            
            SafeAudioParameterController.setParameter(context, SafeAudioParameterController.TargetVendor.QUALCOMM, "direct_pcm", "1")
            SafeAudioParameterController.setParameter(context, SafeAudioParameterController.TargetVendor.QUALCOMM, "audio_stream_direct", "true")
            return true
        }

        override fun deactivate(context: Context) {}
    }

    object GenericAdapter : VendorAdapter {
        override fun activate(context: Context): Boolean = false
        override fun deactivate(context: Context) {}
    }

    suspend fun activateHardwareDacAsync(context: Context, forceExclusive: Boolean = false): HiFiActivationResult =
        withContext(Dispatchers.IO) {
            activateHardwareDac(context, forceExclusive)
        }

    fun activateHardwareDac(context: Context, forceExclusive: Boolean = false): HiFiActivationResult {
        val manufacturer = (Build.MANUFACTURER ?: "").lowercase()
        val brand = (Build.BRAND ?: "").lowercase()
        val model = (Build.MODEL ?: "").lowercase()

        Log.i(TAG, "🚀 [ISOLATED DAC PROBE] Manufacturer: $manufacturer, Brand: $brand, Model: $model")

        // Reset all vendor active states before probing
        isVivoHiFiActive = false
        isSamsungUhqActive = false
        isSonyHiResActive = false
        isLgQuadDacActive = false
        isQualcommDirectActive = false

        val isVivoMatch = SafeAudioParameterController.isVendorMatch(SafeAudioParameterController.TargetVendor.VIVO)
        val isSamsungMatch = SafeAudioParameterController.isVendorMatch(SafeAudioParameterController.TargetVendor.SAMSUNG)
        val isSonyMatch = SafeAudioParameterController.isVendorMatch(SafeAudioParameterController.TargetVendor.SONY)
        val isLgMatch = SafeAudioParameterController.isVendorMatch(SafeAudioParameterController.TargetVendor.LG)
        val isQualcommMatch = SafeAudioParameterController.isVendorMatch(SafeAudioParameterController.TargetVendor.QUALCOMM)

        // Activate matched vendor adapter ONLY
        val adapterActivated = when {
            isVivoMatch -> VivoAdapter.activate(context)
            isSamsungMatch -> SamsungAdapter.activate(context)
            isSonyMatch -> SonyAdapter.activate(context)
            isLgMatch -> LGAdapter.activate(context)
            isQualcommMatch -> QualcommAdapter.activate(context)
            else -> GenericAdapter.activate(context)
        }

        // Verification phase
        val verifiedReport = HardwareHiFiVerifier.probeHardwareState(context)
        isVivoHiFiActive = isVivoMatch && verifiedReport.isVendorHiFiActive
        isQualcommDirectActive = isQualcommMatch && verifiedReport.isDirectOutputActive
        isSamsungUhqActive = isSamsungMatch && verifiedReport.isVendorHiFiActive
        isSonyHiResActive = isSonyMatch && verifiedReport.isVendorHiFiActive
        isLgQuadDacActive = isLgMatch && verifiedReport.isVendorHiFiActive
        detectedDacNameInternal = verifiedReport.activeDacName

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val sampleRate = audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0
        val framesPerBuffer = audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 1024
        val isLowLatency = framesPerBuffer <= 256
        val isWired = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.any {
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ||
            it.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE
        } ?: false

        val isExclusiveActive = if (forceExclusive) {
            if (verifiedReport.isDirectOutputSupported) {
                prepareHardwareForDirectPlayback(context, sampleRate)
                true
            } else {
                false
            }
        } else {
            false
        }

        val isHiFiConfirmed = isVivoHiFiActive || isSamsungUhqActive || isSonyHiResActive || isLgQuadDacActive || isQualcommDirectActive
        val confirmedParameter = when {
            isVivoHiFiActive -> "vivo_hifi_active"
            isSamsungUhqActive -> "sound_alive_uhq_upscaler"
            isSonyHiResActive -> "sony_hires_audio_enabled"
            isLgQuadDacActive -> "quad_dac_state"
            isQualcommDirectActive -> "direct_pcm"
            else -> "standard_hal"
        }

        return HiFiActivationResult(
            isHiFiConfirmed = isHiFiConfirmed,
            activeOem = manufacturer.uppercase(),
            confirmedParameter = confirmedParameter,
            outputSampleRate = sampleRate,
            isLowLatencyPath = isLowLatency,
            isWiredConnected = isWired,
            isExclusiveModeActive = isExclusiveActive,
            settingsApplied = adapterActivated,
            isDirectSupported = verifiedReport.isDirectOutputSupported,
            isDirectActive = verifiedReport.isDirectOutputActive,
            isVendorVerified = verifiedReport.isVendorHiFiActive,
            isVivoActive = isVivoHiFiActive,
            isSamsungActive = isSamsungUhqActive,
            isSonyActive = isSonyHiResActive,
            isLgActive = isLgQuadDacActive,
            isQualcommActive = isQualcommDirectActive
        )
    }

    // Vendor HAL setParameters is a blocking binder call that can stall for
    // tens of milliseconds (or hang on broken OEM HALs). Never run it on the
    // caller's (main) thread during player construction.
    private val vendorParamExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "VendorDacParams").apply { isDaemon = true }
    }

    fun prepareHardwareForDirectPlayback(context: Context, sampleRate: Int) {
        val appContext = context.applicationContext
        vendorParamExecutor.execute {
            if (SafeAudioParameterController.isVendorMatch(SafeAudioParameterController.TargetVendor.VIVO)) {
                SafeAudioParameterController.setParameter(appContext, SafeAudioParameterController.TargetVendor.VIVO, "direct_pcm", "1")
                SafeAudioParameterController.setParameter(appContext, SafeAudioParameterController.TargetVendor.VIVO, "vivo_hifi_state", "1")
                SafeAudioParameterController.setParameter(appContext, SafeAudioParameterController.TargetVendor.VIVO, "vivo_headset_hifi", "1")
                SafeAudioParameterController.setParameter(appContext, SafeAudioParameterController.TargetVendor.VIVO, "sampling_rate", "$sampleRate")
                SafeAudioParameterController.setParameter(appContext, SafeAudioParameterController.TargetVendor.VIVO, "audio_stream_direct", "true")
            } else if (SafeAudioParameterController.isVendorMatch(SafeAudioParameterController.TargetVendor.QUALCOMM)) {
                SafeAudioParameterController.setParameter(appContext, SafeAudioParameterController.TargetVendor.QUALCOMM, "direct_pcm", "1")
                SafeAudioParameterController.setParameter(appContext, SafeAudioParameterController.TargetVendor.QUALCOMM, "audio_stream_direct", "true")
            }
        }
    }

    fun deactivate(context: Context) {
        VivoAdapter.deactivate(context)
        SamsungAdapter.deactivate(context)
        LGAdapter.deactivate(context)
        SonyAdapter.deactivate(context)
        QualcommAdapter.deactivate(context)
        isVivoHiFiActive = false
        isSamsungUhqActive = false
        isSonyHiResActive = false
        isLgQuadDacActive = false
        isQualcommDirectActive = false
    }

    fun onAudioSessionOpened(context: Context, sessionId: Int) {
        if (sessionId == 0) return
        val pkg = context.packageName
        
        val standardIntent = android.content.Intent(android.media.audiofx.AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
            putExtra(android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
            putExtra(android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME, pkg)
            putExtra(android.media.audiofx.AudioEffect.EXTRA_CONTENT_TYPE, android.media.audiofx.AudioEffect.CONTENT_TYPE_MUSIC)
        }
        runCatching { context.sendBroadcast(standardIntent) }

        if (SafeAudioParameterController.isVendorMatch(SafeAudioParameterController.TargetVendor.VIVO)) {
            val bbkIntent = android.content.Intent("bbk.media.action.OPEN_AUDIOFX_CONTROL_SESSION").apply {
                putExtra("android.media.extra.AUDIO_SESSION", sessionId)
                putExtra("android.media.extra.PACKAGE_NAME", pkg)
            }
            runCatching { context.sendBroadcast(bbkIntent) }
        }
    }

    fun onAudioSessionClosed(context: Context, sessionId: Int) {
        if (sessionId == 0) return
        val pkg = context.packageName
        val standardIntent = android.content.Intent(android.media.audiofx.AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
            putExtra(android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
            putExtra(android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME, pkg)
        }
        runCatching { context.sendBroadcast(standardIntent) }

        if (SafeAudioParameterController.isVendorMatch(SafeAudioParameterController.TargetVendor.VIVO)) {
            val bbkIntent = android.content.Intent("bbk.media.action.CLOSE_AUDIOFX_CONTROL_SESSION").apply {
                putExtra("android.media.extra.AUDIO_SESSION", sessionId)
                putExtra("android.media.extra.PACKAGE_NAME", pkg)
            }
            runCatching { context.sendBroadcast(bbkIntent) }
        }
    }

    fun getDirectBufferSize(sampleRate: Int, channelCount: Int, bytesPerSample: Int): Int {
        return try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO,
                if (bytesPerSample >= 4) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
            )
            val multiplier = if (sampleRate >= 88200) 4 else 2
            if (minBufferSize > 0) (minBufferSize * multiplier).coerceAtLeast(minBufferSize) else 0
        } catch (t: Throwable) {
            CrashDiagnostics.record("VENDOR_DAC", "getDirectBufferSize", t)
            4096
        }
    }
}

data class HiFiActivationResult(
    val isHiFiConfirmed: Boolean,
    val activeOem: String,
    val confirmedParameter: String,
    val outputSampleRate: Int,
    val isLowLatencyPath: Boolean,
    val isWiredConnected: Boolean,
    var isExclusiveModeActive: Boolean,
    val settingsApplied: Boolean = false,
    val isDirectSupported: Boolean = false,
    val isDirectActive: Boolean = false,
    val isVendorVerified: Boolean = false,
    val isVivoActive: Boolean = false,
    val isSamsungActive: Boolean = false,
    val isSonyActive: Boolean = false,
    val isLgActive: Boolean = false,
    val isQualcommActive: Boolean = false
)
