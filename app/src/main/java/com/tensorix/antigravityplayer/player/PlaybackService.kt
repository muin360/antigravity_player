package com.tensorix.antigravityplayer.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import com.tensorix.antigravityplayer.audio.HiFiBadgeState
import com.tensorix.antigravityplayer.audio.HardwareHiFiVerifier
import com.tensorix.antigravityplayer.audio.OboeBridge
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.tensorix.antigravityplayer.audio.AudioEngine
import com.tensorix.antigravityplayer.audio.AudioOutputApi
import com.tensorix.antigravityplayer.audio.AudioOutputConfigManager
import com.tensorix.antigravityplayer.audio.AudioOutputManager
import com.tensorix.antigravityplayer.audio.AudioOutputRouteType
import com.tensorix.antigravityplayer.audio.AudioTrackInfo
import com.tensorix.antigravityplayer.audio.AudiophilePlaybackSnapshot
import com.tensorix.antigravityplayer.audio.CanonicalAudioRuntimeSnapshot
import com.tensorix.antigravityplayer.audio.OutputDeviceConfig
import com.tensorix.antigravityplayer.audio.VendorDacManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Audiophile-Grade Core Playback Service with True Hi-Res Audio Architecture:
 *  - 24-bit / 32-bit Float Output direct passthrough (FLAC, WAV, ALAC, and decoded PCM streams)
 *  - Dynamic Hardware Sample Rate Matching (44.1kHz, 48kHz, 88.2kHz, 96kHz, 176.4kHz, 192kHz)
 *  - Bit-Perfect DSP Bypass switch for studio-master audio clarity
 *  - Authoritative AudioEngine owning stream lifecycle and non-destructive route changes
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private lateinit var audioManager: AudioManager
    var equalizerEngine: EqualizerEngine? = null
        private set
    var audioOutputManager: AudioOutputManager? = null
        private set
    var outputConfigManager: AudioOutputConfigManager? = null
        private set
    var hifiProfileManager: com.tensorix.antigravityplayer.audio.HiFiProfileManager? = null
        private set
    var dynamicProfileEngine: com.tensorix.antigravityplayer.audio.DynamicProfileEngine? = null
        private set
    var autoEqEngine: com.tensorix.antigravityplayer.audio.AutoEqEngine? = null
        private set

    companion object {
        private val _instanceFlow = MutableStateFlow<PlaybackService?>(null)
        val instanceFlow: StateFlow<PlaybackService?> = _instanceFlow.asStateFlow()

        var instance: PlaybackService?
            get() = _instanceFlow.value
            private set(value) { _instanceFlow.value = value }

        private val _hardwareHiFiCapable = MutableStateFlow(false)
        val hardwareHiFiCapable: StateFlow<Boolean> = _hardwareHiFiCapable.asStateFlow()
        val hiFiSupportedState: StateFlow<Boolean> = _hardwareHiFiCapable.asStateFlow()

        fun isHiFiSupported(): Boolean {
            return _hardwareHiFiCapable.value
        }

        fun updateHiFiSupported(supported: Boolean) {
            _hardwareHiFiCapable.value = supported
        }
    }

    private val audioPrefs by lazy {
        getSharedPreferences("antigravity_audio_prefs", Context.MODE_PRIVATE)
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var volumeReceiver: BroadcastReceiver? = null
    private var bitPerfectReceiver: BroadcastReceiver? = null
    private var becomingNoisyReceiver: BroadcastReceiver? = null

    private val _hiFiEnabled = MutableStateFlow(true)
    val hiFiEnabled: StateFlow<Boolean> = _hiFiEnabled.asStateFlow()

    private val _bitPerfectMode = MutableStateFlow(false)
    val bitPerfectMode: StateFlow<Boolean> = _bitPerfectMode.asStateFlow()

    private val _sampleRateMatching = MutableStateFlow(true)
    val sampleRateMatching: StateFlow<Boolean> = _sampleRateMatching.asStateFlow()

    private val _audioAuxEnabled = MutableStateFlow(true)
    val audioAuxEnabled: StateFlow<Boolean> = _audioAuxEnabled.asStateFlow()

    private val _oboeMode = MutableStateFlow("UNAVAILABLE")
    val oboeMode: StateFlow<String> = _oboeMode.asStateFlow()

    private val _autoProfileSwitch = MutableStateFlow(true)
    val autoProfileSwitch: StateFlow<Boolean> = _autoProfileSwitch.asStateFlow()

    private val _currentTrackInfo = MutableStateFlow(AudioTrackInfo())
    val currentTrackInfo: StateFlow<AudioTrackInfo> = _currentTrackInfo.asStateFlow()

    private val _audiophileSnapshot = MutableStateFlow(AudiophilePlaybackSnapshot())
    val audiophileSnapshot: StateFlow<AudiophilePlaybackSnapshot> = _audiophileSnapshot.asStateFlow()

    private val _isRouteAvailable = MutableStateFlow(false)
    val isRouteAvailable: StateFlow<Boolean> = _isRouteAvailable.asStateFlow()

    private val _isDirectPathActive = MutableStateFlow(false)
    val isDirectPathActive: StateFlow<Boolean> = _isDirectPathActive.asStateFlow()

    private val _isDirectOutputVerified = MutableStateFlow(false)
    val isDirectOutputVerified: StateFlow<Boolean> = _isDirectOutputVerified.asStateFlow()

    var activeOboeAudioSink: com.tensorix.antigravityplayer.audio.OboeAudioSink? = null
        private set

    val activeAudioSessionId: Int
        get() = player?.audioSessionId ?: 0

    val dspProcessor = com.tensorix.antigravityplayer.audio.Audiophile64BitDspProcessor()

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        Log.i("HiFiPlayer", "PlaybackService onCreate: Engaging Authoritative AudioEngine")
        audioOutputManager = AudioOutputManager(applicationContext)
        outputConfigManager = AudioOutputConfigManager.getInstance(applicationContext)
        hifiProfileManager = com.tensorix.antigravityplayer.audio.HiFiProfileManager(applicationContext)
        val profileManager = hifiProfileManager
        if (profileManager != null) {
            dynamicProfileEngine = com.tensorix.antigravityplayer.audio.DynamicProfileEngine(applicationContext, profileManager)
        }
        equalizerEngine = EqualizerEngine(applicationContext)
        equalizerEngine?.setDspProcessor(dspProcessor)
        dspProcessor.replayGainEnabled = equalizerEngine?.replayGainEnabled?.value ?: true
        autoEqEngine = com.tensorix.antigravityplayer.audio.AutoEqEngine(applicationContext)

        val hifiSupported = OboeBridge.isAvailable && HardwareHiFiVerifier.isHiFiCapable(applicationContext)
        _hardwareHiFiCapable.value = hifiSupported

        // Generate persistent audio session ID to notify Android AudioPolicy / OEM Hi-Fi service
        val generatedSessionId = audioManager.generateAudioSessionId()
        if (generatedSessionId != 0) {
            VendorDacManager.onAudioSessionOpened(applicationContext, generatedSessionId)
        }

        // Retain settings from preferences
        _hiFiEnabled.value = audioPrefs.getBoolean("hi_fi_enabled", true)
        _bitPerfectMode.value = audioPrefs.getBoolean("bit_perfect_mode", false)
        _sampleRateMatching.value = audioPrefs.getBoolean("sample_rate_matching", true)
        _audioAuxEnabled.value = audioPrefs.getBoolean("audio_aux_enabled", true)
        _autoProfileSwitch.value = audioPrefs.getBoolean("auto_profile_switch", true)

        dspProcessor.isBitPerfectBypass = _bitPerfectMode.value
        dspProcessor.isEnabled = !_bitPerfectMode.value
        dspProcessor.isTurboMode = _hiFiEnabled.value

        equalizerEngine?.setBitPerfectBypass(_bitPerfectMode.value)
        refreshAudiophileState()

        val bitPerfectFilter = IntentFilter("com.tensorix.antigravityplayer.SET_BIT_PERFECT")
        bitPerfectReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val enabled = intent?.getBooleanExtra("enabled", false) ?: false
                Log.i("AntigravityAudioAudit", "[CMD] Received SET_BIT_PERFECT broadcast: enabled=$enabled")
                setBitPerfectMode(enabled)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bitPerfectReceiver, bitPerfectFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(bitPerfectReceiver, bitPerfectFilter)
        }

        // Becoming Noisy receiver for immediate pause on unplug
        becomingNoisyReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    player?.pause()
                }
            }
        }
        val noisyFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(becomingNoisyReceiver, noisyFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(becomingNoisyReceiver, noisyFilter)
        }

        // Listen to volume changes for DVC using BroadcastReceiver.
        // Sole owner of the software DVC mirror (see OboeAudioSink.setVolume).
        // Disabled entirely during BitPerfect: no software gain may touch the
        // signal in bypass mode.
        volumeReceiver = object : BroadcastReceiver() {
            private var lastSentDvc = -1.0
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                    if (_bitPerfectMode.value) return
                    val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val dvcVol = (currentVolume.toDouble() / maxVolume.toDouble().coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
                    if (equalizerEngine != null) {
                        equalizerEngine?.setDvcVolume(dvcVol)
                    } else {
                        dspProcessor?.dvcVolume = dvcVol
                        if (Math.abs(dvcVol - lastSentDvc) > 0.001) {
                            lastSentDvc = dvcVol
                            val handle = com.tensorix.antigravityplayer.audio.OboeAudioSink.currentActiveHandle
                            if (handle != 0L && com.tensorix.antigravityplayer.audio.OboeBridge.isAvailable) {
                                serviceScope.launch(Dispatchers.IO) {
                                    com.tensorix.antigravityplayer.audio.OboeBridge.setDvcVolume(handle, dvcVol)
                                }
                            }
                        }
                    }
                }
            }
        }
        val volFilter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(volumeReceiver, volFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(volumeReceiver, volFilter)
        }
        
        // Initial volume sync
        val initDvc = (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toDouble() / 
                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toDouble().coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
        equalizerEngine?.setDvcVolume(initDvc)



        buildAndAttachPlayer()
        refreshAudiophileState()
    }

    internal fun reloadAudioPipeline() {
        serviceScope.launch {
            val currentPlayer = player ?: return@launch
            val currentMediaItems = buildList {
                val count = currentPlayer.mediaItemCount
                for (i in 0 until count) {
                    add(currentPlayer.getMediaItemAt(i))
                }
            }
            val currentIndex = currentPlayer.currentMediaItemIndex
            val currentPosition = currentPlayer.currentPosition
            val playWhenReady = currentPlayer.playWhenReady

            currentPlayer.stop()
            val newPlayer = createExoPlayerInstance()
            mediaSession?.setPlayer(newPlayer)
            currentPlayer.release()
            player = newPlayer
            if (currentMediaItems.isNotEmpty()) {
                newPlayer.setMediaItems(currentMediaItems, currentIndex.coerceAtLeast(0), currentPosition)
                newPlayer.prepare()
                newPlayer.playWhenReady = playWhenReady
            }
            refreshAudiophileState()
        }
    }

    private fun createExoPlayerInstance(): ExoPlayer {
        val activeRouteType = audioOutputManager?.scanOutputState()?.activeRoute?.routeType ?: AudioOutputRouteType.SPEAKER
        val config = outputConfigManager?.getConfigForDevice(activeRouteType) ?: OutputDeviceConfig()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .apply {
                if (_hiFiEnabled.value) {
                    @Suppress("WrongConstant")
                    setFlags(0x100)
                }
            }
            .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_NONE)
            .build()

        // Poweramp-Grade 32-bit Float AudioSink with 64-bit Double DSP Processing
        val renderersFactory = object : DefaultRenderersFactory(this@PlaybackService) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return try {
                    val currentConfig = outputConfigManager?.getConfigForDevice(activeRouteType) ?: OutputDeviceConfig()
                    val isBitPerfect = _bitPerfectMode.value

                    Log.d("AntigravityAudioAudit", "Building Sink: activeRoute=$activeRouteType, bitPerfect=$isBitPerfect, hiFiEnabled=${_hiFiEnabled.value}")

                    if (_hiFiEnabled.value && com.tensorix.antigravityplayer.audio.OboeBridge.isAvailable) {
                        try {
                            Log.i("AntigravityAudioAudit", "Using OboeAudioSink for High-Performance path (Bit-Perfect: $isBitPerfect)")
                            val sink = com.tensorix.antigravityplayer.audio.OboeAudioSink(
                                context = context,
                                dspProcessor = dspProcessor,
                                equalizerEngine = equalizerEngine,
                                bitPerfectMode = isBitPerfect,
                                sampleRateMatchingInitial = _sampleRateMatching.value
                            )
                            activeOboeAudioSink = sink
                            return sink
                        } catch (e: Exception) {
                            Log.e("AntigravityAudioAudit", "OboeAudioSink initialization failed, falling back to Default", e)
                        }
                    } else {
                        activeOboeAudioSink = null
                    }

                    dspProcessor.isTurboMode = _hiFiEnabled.value
                    
                    if (isBitPerfect) {
                        dspProcessor.ditherStrength = 0.0
                        dspProcessor.dvcVolume = 1.0
                    } else {
                        dspProcessor.ditherStrength = if (currentConfig.ditherEnabled) 1.0 else 0.0
                    }
                    
                    dspProcessor.outputBitDepth = currentConfig.bitDepth

                    val builder = DefaultAudioSink.Builder(context)
                        .setAudioProcessors(if (isBitPerfect) emptyArray() else arrayOf(dspProcessor))
                    
                    if (!isBitPerfect && _hiFiEnabled.value && isHiFiSupported()) {
                        builder.setEnableFloatOutput(true)
                    } else {
                        builder.setEnableFloatOutput(false)
                    }

                    val bufferProvider = object : DefaultAudioSink.AudioTrackBufferSizeProvider {
                        override fun getBufferSizeInBytes(
                            minBufferSizeInBytes: Int,
                            encoding: Int,
                            outputMode: Int,
                            pcmFrameSize: Int,
                            sampleRate: Int,
                            bitrate: Int,
                            maxSpeedsMultiplier: Double
                        ): Int {
                            val framesPerBuffer = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 192
                            val hardwareAlignedMin = framesPerBuffer * pcmFrameSize * 2
                            
                            val multiplier = currentConfig.bufferSizeMultiplier.coerceAtLeast(1)
                            val baseSize = maxOf(minBufferSizeInBytes, hardwareAlignedMin) * multiplier
                            
                            val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
                            val dynamicChannels = if (pcmFrameSize > 0) (pcmFrameSize / bytesPerSample).coerceAtLeast(1) else 2
                            val directBuffer = VendorDacManager.getDirectBufferSize(sampleRate, dynamicChannels, bytesPerSample)
                            return maxOf(baseSize, minBufferSizeInBytes, directBuffer)
                        }
                    }
                    builder.setAudioTrackBufferSizeProvider(bufferProvider)

                    val sink = builder
                        .setEnableAudioTrackPlaybackParams(true)
                        .build()
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        sink.setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED)
                    }
                    return sink
                } catch (e: Exception) {
                    Log.e("AntigravityAudioAudit", "Hi-Fi Sink Build Failed, falling back to standard", e)
                    DefaultAudioSink.Builder(context).build()
                }
            }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
         .setEnableDecoderFallback(true)

        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 5_000, 
                /* maxBufferMs = */ if (_audioAuxEnabled.value) 15_000 else 30_000, 
                /* bufferForPlaybackMs = */ 250, 
                /* bufferForPlaybackAfterRebufferMs = */ 500
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setLoadControl(loadControl)
            .setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
            .build()

        // P0-11: vendor activation is NEVER part of playback construction.
        // BitPerfect exclusive open works without OEM settings writes; the
        // verifier reports truth. VendorDacManager stays reserved for the
        // future explicit Hi-Fi phase (P1).

        val currentSessionId = exoPlayer.audioSessionId
        if (currentSessionId != 0) {
            VendorDacManager.onAudioSessionOpened(applicationContext, currentSessionId)
            if (!_bitPerfectMode.value) {
                equalizerEngine?.attachToAudioSession(currentSessionId)
            } else {
                equalizerEngine?.release()
            }
            logRuntimeAudioDiagnostics(currentSessionId, audioAttributes)
        }

        exoPlayer.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (audioSessionId != 0) {
                    VendorDacManager.onAudioSessionOpened(applicationContext, audioSessionId)
                    if (!_bitPerfectMode.value) {
                        equalizerEngine?.attachToAudioSession(audioSessionId)
                    } else {
                        equalizerEngine?.release()
                    }
                    logRuntimeAudioDiagnostics(audioSessionId, audioAttributes)
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                refreshAudiophileState()
            }
        })
        
        return exoPlayer
    }

    private fun logRuntimeAudioDiagnostics(sessionId: Int, attributes: AudioAttributes) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val currentTrack = _currentTrackInfo.value
        val trackSampleRate = currentTrack.sampleRateHz.takeIf { it > 0 } ?: 48000
        val trackBitDepth = currentTrack.bitDepth.takeIf { it > 0 } ?: 16
        val trackChannels = currentTrack.channels.takeIf { it > 0 } ?: 2

        val actualSampleRate = audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: trackSampleRate
        // Truthful labelling: these describe the FALLBACK DefaultAudioSink
        // configuration policy. The active native Oboe path always opens
        // Float streams and its real format comes from stream telemetry.
        val fallbackEncoding = if (!_bitPerfectMode.value && isHiFiSupported()) "ENCODING_PCM_FLOAT (4)" else "ENCODING_PCM_16BIT (2)"
        val actualChannels = if (trackChannels == 1) "MONO (1)" else "STEREO (2)"

        val verifiedReport = HardwareHiFiVerifier.probeHardwareState(
            context = applicationContext,
            trackSampleRate = trackSampleRate,
            trackBitDepth = trackBitDepth,
            isDspBypassed = _bitPerfectMode.value
        )

        val isBitPerfect = _bitPerfectMode.value
        val processorsCount = if (isBitPerfect) 0 else 1
        Log.i("AntigravityAudioAudit", "==================== AUDIO RUNTIME DIAGNOSTICS ====================")
        Log.i("AntigravityAudioAudit", "1.  BitPerfect Mode Toggle:        ${if (isBitPerfect) "ENABLED (True)" else "DISABLED (False)"}")
        Log.i("AntigravityAudioAudit", "2.  FallbackSink Config Policy:    $fallbackEncoding, Processors=$processorsCount")
        Log.i("AntigravityAudioAudit", "3.  Audio Session ID:              $sessionId")
        Log.i("AntigravityAudioAudit", "4.  Platform Output Rate (inferred): $actualSampleRate Hz")
        Log.i("AntigravityAudioAudit", "5.  Track Channels (inferred):     $actualChannels")
        Log.i("AntigravityAudioAudit", "6.  Path Model (inferred):         ${verifiedReport.audioThreadType.displayName}")
        Log.i("AntigravityAudioAudit", "====================================================================")
    }

    private fun buildAndAttachPlayer() {
        val exoPlayer = createExoPlayerInstance()
        player = exoPlayer
        mediaSession = MediaSession.Builder(this, exoPlayer).build()
        refreshAudiophileState()
    }

    fun updateCurrentTrackInfo(
        title: String,
        artist: String,
        codec: String,
        bitrateKbps: Int,
        bitDepth: Int,
        sampleRateHz: Int,
        channels: Int = 2,
        trackReplayGainDb: Float = 0f,
        albumReplayGainDb: Float = 0f,
        peakAmplitude: Float = 0f,
        useAlbumGain: Boolean = false
    ) {
        val resolvedSampleRate = if (sampleRateHz > 0) sampleRateHz else 0
        val resolvedBitDepth = if (bitDepth > 0) bitDepth else 0
        val isHiResSource = (resolvedBitDepth >= 24) || (resolvedSampleRate >= 88200)
        val cleanCodec = codec.ifBlank { "Unknown Codec" }
        val info = AudioTrackInfo(
            title = title,
            artist = artist,
            codec = cleanCodec,
            bitrateKbps = bitrateKbps,
            bitDepth = resolvedBitDepth,
            sampleRateHz = resolvedSampleRate,
            channels = channels,
            isHiResSource = isHiResSource,
            isHiRes = isHiResSource
        )
        
        _currentTrackInfo.value = info
        val rgEnabled = equalizerEngine?.replayGainEnabled?.value ?: true
        if (rgEnabled && (trackReplayGainDb != 0f || albumReplayGainDb != 0f || peakAmplitude > 0f)) {
            val gainDb = if (useAlbumGain) albumReplayGainDb else trackReplayGainDb
            val linearGain = Math.min(
                Math.pow(10.0, (gainDb / 20.0).toDouble()),
                if (peakAmplitude > 0f) (1.0 / peakAmplitude).toDouble() else 10.0
            )
            equalizerEngine?.setReplayGainMultiplier(linearGain)
        } else {
            equalizerEngine?.setReplayGainMultiplier(1.0)
        }
        refreshAudiophileState(info)
    }

    fun refreshAudiophileState(trackInfo: AudioTrackInfo = _currentTrackInfo.value) {
        val outManager = audioOutputManager ?: return
        val isDspActive = !_bitPerfectMode.value && (equalizerEngine?.isEnabled?.value == true)
        val snapshot = outManager.currentSnapshot(trackInfo, isDspActive)
        _audiophileSnapshot.value = snapshot
        val activeRoute = snapshot.output.activeRoute
        _isRouteAvailable.value = (activeRoute != null)
        _isDirectPathActive.value = (snapshot.output.canonicalSnapshot?.directPathActive?.value == true)
        _isDirectOutputVerified.value = (snapshot.output.bitPerfectState == com.tensorix.antigravityplayer.audio.BitPerfectState.VERIFIED)
        _hardwareHiFiCapable.value = OboeBridge.isAvailable && HardwareHiFiVerifier.isHiFiCapable(applicationContext)

        snapshot.output.canonicalSnapshot?.let { canon ->
            HiFiBadgeState.updateFromSnapshot(canon)
            AudioEngine.updateSnapshot(applicationContext, trackInfo, isDspActive)
        }

        val streamInfo = com.tensorix.antigravityplayer.audio.OboeAudioSink.currentStreamInfo
        _oboeMode.value = if (activeOboeAudioSink != null && streamInfo != null) streamInfo.sharingMode else if (OboeBridge.isAvailable) "SHARED" else "UNAVAILABLE"
        
        // Auto-switch profile and Listening Mode based on dynamic route engine.
        // Phase 13 dedupe: apply ONLY when the effective route type actually
        // changed — playback-state transitions (buffering/ready/ended) must not
        // rewrite EQ prefs or re-drive the DSP sync pipeline.
        if (_autoProfileSwitch.value) {
            val activeRoute = snapshot.output.activeRoute?.routeType ?: AudioOutputRouteType.SPEAKER
            if (activeRoute != lastAutoAppliedRoute) {
                lastAutoAppliedRoute = activeRoute
                val profile = dynamicProfileEngine?.evaluateAndSwitch(activeRoute, null, trackInfo)
                if (profile != null) {
                    equalizerEngine?.applyHiFiProfile(profile)

                    when (activeRoute) {
                        AudioOutputRouteType.USB_DAC, AudioOutputRouteType.USB_DEVICE -> {
                            equalizerEngine?.setListeningMode(com.tensorix.antigravityplayer.audio.ListeningMode.REFERENCE)
                        }
                        AudioOutputRouteType.WIRED_HEADPHONES, AudioOutputRouteType.WIRED_HEADSET -> {
                            equalizerEngine?.setListeningMode(com.tensorix.antigravityplayer.audio.ListeningMode.AUDIOPHILE)
                        }
                        AudioOutputRouteType.BLUETOOTH_A2DP -> {
                            equalizerEngine?.setListeningMode(com.tensorix.antigravityplayer.audio.ListeningMode.DYNAMIC)
                        }
                        else -> {
                            equalizerEngine?.setListeningMode(com.tensorix.antigravityplayer.audio.ListeningMode.REFERENCE)
                        }
                    }
                }
            }
        }

        logRouteProof()
    }

    @Volatile
    private var lastAutoAppliedRoute: AudioOutputRouteType? = null

    /**
     * P0-8: PROOF of the actual output device. Correlates the live native
     * stream's deviceId against the OS output-device enumeration. Availability
     * of a route is never reported as an active one.
     */
    private fun logRouteProof() {
        val info = com.tensorix.antigravityplayer.audio.OboeAudioSink.currentStreamInfo ?: return
        if (info.deviceId <= 0) return
        runCatching {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val match = devices.firstOrNull { it.id == info.deviceId }
            val typeName = match?.let { deviceTypeName(it.type) } ?: "UNKNOWN"
            Log.i(
                "ROUTE_PROOF",
                "nativeDev=${info.deviceId} found=${match != null} type=$typeName " +
                    "name=${match?.productName} rate=${info.sampleRate} ch=${info.channelCount} " +
                    "mode=${info.sharingMode} perf=${info.performanceMode} state=${info.state}"
            )
        }
    }

    private fun deviceTypeName(type: Int): String = when (type) {
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED"
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
        android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
        android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
        android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
        else -> "TYPE_$type"
    }

    fun setHiFiEnabled(enabled: Boolean) {
        if (_hiFiEnabled.value == enabled) return
        _hiFiEnabled.value = enabled
        audioPrefs.edit { putBoolean("hi_fi_enabled", enabled) }
        dspProcessor.isTurboMode = enabled
        AudioEngine.invalidate()
        reloadAudioPipeline()
        refreshAudiophileState()
    }

    fun setBitPerfectMode(enabled: Boolean) {
        _bitPerfectMode.value = enabled
        audioPrefs.edit().putBoolean("bit_perfect_mode", enabled).apply()
        Log.i("HiFiPlayer", "Bit-Perfect Mode changed: $enabled")
        equalizerEngine?.setBitPerfectBypass(enabled)
        activeOboeAudioSink?.setBitPerfectMode(enabled)
        AudioEngine.setBitPerfectMode(enabled)
        refreshAudiophileState()
    }

    fun setSampleRateMatching(enabled: Boolean) {
        _sampleRateMatching.value = enabled
        activeOboeAudioSink?.setSampleRateMatching(enabled)
        audioPrefs.edit().putBoolean("sample_rate_matching", enabled).apply()
        Log.i("HiFiPlayer", "Sample Rate Matching changed: $enabled")
        AudioEngine.invalidate()
        refreshAudiophileState()
    }

    fun setAudioAuxEnabled(enabled: Boolean) {
        _audioAuxEnabled.value = enabled
        audioPrefs.edit().putBoolean("audio_aux_enabled", enabled).apply()
        AudioEngine.invalidate()
        refreshAudiophileState()
    }

    fun setAutoProfileSwitch(enabled: Boolean) {
        _autoProfileSwitch.value = enabled
        audioPrefs.edit().putBoolean("auto_profile_switch", enabled).apply()
        refreshAudiophileState()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    fun isPlayerReady(): Boolean = player != null && mediaSession != null

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player != null && !player.playWhenReady) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        becomingNoisyReceiver?.let { runCatching { unregisterReceiver(it) } }
        serviceScope.coroutineContext[Job]?.cancel()

        bitPerfectReceiver?.let { runCatching { unregisterReceiver(it) } }
        
        VendorDacManager.deactivate(applicationContext)

        
        volumeReceiver?.let {
            runCatching { unregisterReceiver(it) }
        }
        volumeReceiver = null
        
        equalizerEngine?.release()
        equalizerEngine = null
        audioOutputManager?.release()
        audioOutputManager = null
        instance = null

        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        player = null
        AudioEngine.invalidate()
        super.onDestroy()
    }
}

