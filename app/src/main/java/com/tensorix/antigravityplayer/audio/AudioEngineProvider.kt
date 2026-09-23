package com.tensorix.antigravityplayer.audio

import kotlinx.coroutines.flow.MutableStateFlow
import com.tensorix.antigravityplayer.player.EqualizerEngine
import com.tensorix.antigravityplayer.player.PlaybackService

/**
 * Global provider for core audio engine components.
 * Decouples the UI and peripheral engines from the PlaybackService lifecycle.
 */
@android.annotation.SuppressLint("StaticFieldLeak")
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object AudioEngineProvider {
    var audioOutputManager: AudioOutputManager? = null
    var equalizerEngine: EqualizerEngine? = null
    var autoEqEngine: AutoEqEngine? = null
    var dspProcessor: Audiophile64BitDspProcessor? = null
    var hifiProfileManager: HiFiProfileManager? = null
    var dynamicProfileEngine: DynamicProfileEngine? = null

    val hiFiEnabled = MutableStateFlow(true)
    val bitPerfectMode = MutableStateFlow(false)
    val sampleRateMatching = MutableStateFlow(true)
    val audioAuxEnabled = MutableStateFlow(true)
    val autoProfileSwitch = MutableStateFlow(true)
    val oboeMode = MutableStateFlow("UNAVAILABLE")
    
    // Command delegation (Service observes these or UI triggers them)
    var commandHandler: ((String, Any?) -> Unit)? = null
    
    fun reloadAudioPipeline() {
        commandHandler?.invoke("RELOAD_PIPELINE", null)
    }
    
    fun updateCurrentTrackInfo(track: com.tensorix.antigravityplayer.data.Song?) {
        commandHandler?.invoke("UPDATE_TRACK", track)
    }
}
