package com.tensorix.antigravityplayer.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import androidx.media3.common.util.UnstableApi
import com.tensorix.antigravityplayer.player.PlaybackService
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dedicated Audiophile Audio Output & USB DAC Route Manager
 * - Scans connected USB Audio Class devices via UsbManager
 * - Monitors hotplug events via AudioDeviceCallback and USB BroadcastReceivers
 * - Calculates device capability matrix across 16/24/32-bit & 44.1-192kHz sample rates
 * - Strictly correlates active playback endpoint with real runtime stream state
 */
@UnstableApi
class AudioOutputManager(
    private val context: Context,
    /**
     * ONLY the service-owned instance may register system listeners. The
     * ViewModel-side poll-only instance must never double-register device
     * callbacks / route broadcasts (duplicate AudioEngine.reconfigureRoute
     * storms were traced here).
     */
    private val registerSystemListeners: Boolean = true
) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    private var cachedRoutes: List<AudioRouteCapability> = emptyList()
    private var cachedUsbDacs: List<UsbDacInfo> = emptyList()

    private val _outputState = MutableStateFlow(scanOutputStateInternal())
    val outputState: StateFlow<AudioOutputState> = _outputState.asStateFlow()

    private val managerScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())
    private var debounceJob: kotlinx.coroutines.Job? = null

    // Memo cache for the expensive canonical-snapshot build (AudioManager +
    // vendor probes + verification). Playback state changes used to rebuild it
    // on every transition; identical inputs now reuse the last result.
    private var memoKey: List<Any?>? = null
    private var memoState: AudioOutputState? = null

    private fun onRouteEvent() {
        debounceJob?.cancel()
        debounceJob = managerScope.launch {
            kotlinx.coroutines.delay(50)
            updateCache()
            val newState = scanOutputStateInternal()
            _outputState.value = newState

            val activeDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }
                val targetType = newState.activeRoute?.routeType
                outputDevices.firstOrNull { it.toRouteType() == targetType }
            } else null

            AudioEngine.reconfigureRoute(context, newState.activeRoute, activeDevice)
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            onRouteEvent()
        }
    }

    private val deviceCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                onRouteEvent()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                onRouteEvent()
            }
        }
    } else null

    init {
        updateCache()
        if (registerSystemListeners && deviceCallback != null) {
            audioManager.registerAudioDeviceCallback(deviceCallback, null)
        }

        if (registerSystemListeners) {
            // NOTE: ACTION_AUDIO_BECOMING_NOISY is deliberately NOT observed
            // here. Unplug-pause belongs to PlaybackService's receiver; a
            // route reconfiguration during that pause was contamination.
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(Intent.ACTION_HEADSET_PLUG)
                addAction(AudioManager.ACTION_HEADSET_PLUG)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(usbReceiver, filter)
                }
            } catch (e: Exception) {
                android.util.Log.w("Antigravity", "Failure in " + javaClass.simpleName, e)
            }
        }
    }

    private fun updateCache() {
        cachedRoutes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.isSink }
                .map { device -> device.toCapability() }
                .sortedBy { it.routeType.ordinal }
        } else {
            emptyList()
        }
        cachedUsbDacs = scanConnectedUsbDacs()
    }

    fun forceRefresh() {
        updateCache()
        _outputState.value = scanOutputStateInternal()
    }

    fun refresh() {
        forceRefresh()
    }

    fun release() {
        debounceJob?.cancel()
        managerScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        if (registerSystemListeners) runCatching {
            context.unregisterReceiver(usbReceiver)
        }
        if (registerSystemListeners && deviceCallback != null) {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
        }
    }

    /**
     * Formats supported by the Antigravity Player Engine (not a representation of hardware DAC limits).
     */
    fun engineSupportedSampleRates(): List<Int> = listOf(
        44100, 48000, 88200, 96000, 176400, 192000
    )

    fun supportedSampleRates(): List<Int> = engineSupportedSampleRates()

    fun engineSupportedBitDepths(): List<Int> = listOf(16, 24, 32)

    fun supportedBitDepths(): List<Int> = engineSupportedBitDepths()

    /**
     * Real per-format probing for Direct Playback capability (API 26-34+).
     */
    /**
     * Real per-format probing for Direct Playback capability (API 29-35+).
     * Rule 26: Direct SDK calls, zero reflection.
     */
    fun checkDirectPlaybackSupport(context: Context, audioAttributes: AudioAttributes, audioFormat: AudioFormat): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                val support = AudioManager.getDirectPlaybackSupport(audioFormat, audioAttributes)
                support != AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                runCatching {
                    AudioTrack.isDirectPlaybackSupported(audioFormat, audioAttributes)
                }.getOrDefault(false)
            }
            else -> false
        }
    }

    fun scanConnectedUsbDacs(): List<UsbDacInfo> {
        val usbList = mutableListOf<UsbDacInfo>()
        val manager = usbManager ?: return usbList

        try {
            val deviceList = manager.deviceList
            for ((_, device) in deviceList) {
                val isAudio = isUsbAudioDevice(device)
                if (isAudio) {
                    val mfgName = runCatching { device.manufacturerName }.getOrNull()
                    val prodName = runCatching { device.productName }.getOrNull()
                    
                    val interfaceCount = device.interfaceCount
                    val deviceClass = device.deviceClass
                    val deviceSubclass = device.deviceSubclass

                    val audioDevices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    } else emptyArray()

                    val deviceInfo = audioDevices.find { 
                        (it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET) &&
                        it.productName?.toString() == (prodName ?: "")
                    }

                    usbList.add(
                        UsbDacInfo(
                            deviceName = device.deviceName,
                            manufacturerName = mfgName,
                            productName = prodName ?: "USB Audio DAC",
                            vendorId = device.vendorId,
                            productId = device.productId,
                            deviceClass = deviceClass,
                            deviceSubclass = deviceSubclass,
                            interfaceCount = interfaceCount,
                            isAudioClassCompliant = true,
                            supportedSampleRates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                deviceInfo?.sampleRates?.filter { it > 0 }?.sorted() ?: emptyList()
                            } else emptyList(),
                            supportedBitDepths = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                deviceInfo?.encodings?.map { enc ->
                                    when (enc) {
                                        AudioFormat.ENCODING_PCM_16BIT -> 16
                                        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                                        AudioFormat.ENCODING_PCM_32BIT -> 32
                                        AudioFormat.ENCODING_PCM_FLOAT -> 32
                                        else -> 0
                                    }
                                }?.filter { it > 0 }?.distinct()?.sorted() ?: emptyList()
                            } else emptyList()
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("Antigravity", "Failure in " + javaClass.simpleName, e)
        }
        return usbList
    }

    private fun isUsbAudioDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_AUDIO) return true
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                return true
            }
        }
        return false
    }

    fun scanOutputStateInternal(trackInfo: AudioTrackInfo? = null, isDspActive: Boolean = true): AudioOutputState {
        // Memo gate (P0 Rule 17 / P1 Requirement 24): comprehensive invalidation key containing all
        // runtime parameters capable of altering the canonical verification state.
        val activeSnapshot = OboeAudioSink.activeStreamSnapshot
        val nativeInfoNow = activeSnapshot?.info
        val activeHandle = activeSnapshot?.handle ?: 0L
        val streamGeneration = activeSnapshot?.generation ?: 0L
        val audioEpoch = activeSnapshot?.epoch ?: 0L
        val service = PlaybackService.instance
        val dsp = service?.dspProcessor
        val bitPerfectRequested = service?.bitPerfectMode?.value ?: false
        val dspBypass = dsp?.isBitPerfectBypass ?: false
        val dspGen = dsp?.activeSnapshot?.generation ?: 0L
        val dspVol = dsp?.dvcVolume ?: 1.0f
        val dspRg = dsp?.replayGainMultiplier ?: 1.0f
        val dspLimiter = dsp?.isLimiterActive ?: false
        val dspDither = dsp?.isDitherActive ?: false
        val autoEqEnabled = service?.autoEqEngine?.isAutoEqEnabled?.value ?: false
        val autoEqProfileId = service?.autoEqEngine?.activeProfile?.value?.id ?: ""
        val key = listOf(
            trackInfo?.title, trackInfo?.artist, trackInfo?.sampleRateHz,
            trackInfo?.bitDepth, trackInfo?.codec, isDspActive,
            activeHandle, streamGeneration, audioEpoch,
            nativeInfoNow?.deviceId, nativeInfoNow?.sharingMode,
            nativeInfoNow?.api,
            nativeInfoNow?.sampleRate,
            nativeInfoNow?.channelCount,
            nativeInfoNow?.formatId,
            nativeInfoNow?.sharingModeId,
            nativeInfoNow?.performanceModeId,
            nativeInfoNow?.stateId,
            nativeInfoNow?.underrunCount,
            bitPerfectRequested,
            dspBypass,
            dspGen,
            dspVol,
            dspRg,
            dspLimiter,
            dspDither,
            autoEqEnabled,
            autoEqProfileId,
            cachedRoutes.map { "${it.routeType}_${it.deviceName}" }
        )
        if (key == memoKey) return memoState ?: scanOutputStateUncached(trackInfo, isDspActive)
        val state = scanOutputStateUncached(trackInfo, isDspActive)
        memoKey = key
        memoState = state
        return state
    }

    private fun scanOutputStateUncached(trackInfo: AudioTrackInfo?, isDspActive: Boolean): AudioOutputState {
        if (cachedRoutes.isEmpty()) updateCache()
        
        val routes = cachedRoutes
        val usbDacs = cachedUsbDacs

        // 1. Identify ACTUALLY ACTIVE route from correlated runtime evidence.
        // P0-8 truth rules: an unmatched native deviceId stays UNKNOWN —
        // opaque AAudio handles are NOT type bitmasks, and a lone non-speaker
        // sink is NOT proof of the active route (principles 13/16).
        val nativeInfo = OboeAudioSink.currentStreamInfo
        val activeDevice: AudioDeviceInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }
            if (nativeInfo != null && nativeInfo.deviceId > 0) {
                outputDevices.firstOrNull { it.id == nativeInfo.deviceId }
            } else {
                null // No live native device id -> correlation impossible -> UNKNOWN
            }
        } else null

        // Correlate with cached capabilities
        val activeRoute = activeDevice?.let { dev ->
            val devRouteType = dev.toRouteType()
            routes.find { it.routeType == devRouteType }
                ?: routes.find { it.deviceName == (dev.productName?.toString() ?: it.routeType.displayName) }
        }

        // 2. Build Canonical Runtime Snapshot
        val currentTrack = trackInfo ?: AudioTrackInfo()
        val canonicalSnapshot = AudioVerificationEngine.buildCanonicalSnapshot(
            context = context,
            trackInfo = currentTrack,
            isDspActive = isDspActive,
            activeRoute = activeRoute,
            dspProcessor = PlaybackService.instance?.dspProcessor
        )

        val sampleRate = canonicalSnapshot.actualOutput.sampleRate.value
        val bitDepth = canonicalSnapshot.actualOutput.bitDepth.value
        val limitations = canonicalSnapshot.limitations
        
        // 3. Map Snapshot back to existing UI model for compatibility
        val bitPerfectState = canonicalSnapshot.bitPerfect.state
        val bitPerfectPossible = canonicalSnapshot.directPathActive.value && bitPerfectState != BitPerfectState.UNAVAILABLE

        val playbackPath = buildPlaybackPath(activeRoute, bitPerfectState)
        
        val signalStages = buildSignalPathStages(currentTrack, activeRoute, bitPerfectState, canonicalSnapshot)

        return AudioOutputState(
            activeRoute = activeRoute,
            availableRoutes = routes,
            connectedUsbDacs = usbDacs,
            currentPlaybackSampleRate = sampleRate,
            currentPlaybackBitDepth = bitDepth,
            playbackPath = playbackPath,
            bitPerfectState = bitPerfectState,
            bitPerfectPossible = bitPerfectPossible,
            resamplingRequired = canonicalSnapshot.resamplerState.value == "ACTIVE",
            signalPathStages = signalStages,
            deviceLimitations = limitations,
            latencyMs = 0,
            canonicalSnapshot = canonicalSnapshot
        )
    }

    fun scanOutputState(trackInfo: AudioTrackInfo? = null, isDspActive: Boolean = true): AudioOutputState {
        return scanOutputStateInternal(trackInfo, isDspActive)
    }

    fun currentSnapshot(trackInfo: AudioTrackInfo? = null, isDspActive: Boolean = true): AudiophilePlaybackSnapshot {
        val outputState = scanOutputStateInternal(trackInfo, isDspActive)
        val info = trackInfo ?: AudioTrackInfo()
        val quality = AudioQualityState.evaluate(
            sourceCodec = info.codec,
            sourceSampleRate = info.sampleRateHz,
            sourceBitDepth = info.bitDepth,
            actualOutputSampleRate = outputState.currentPlaybackSampleRate,
            actualOutputBitDepth = outputState.currentPlaybackBitDepth,
            bitPerfectState = outputState.bitPerfectState
        )
        return AudiophilePlaybackSnapshot(
            track = info.copy(quality = quality),
            output = outputState,
            quality = quality
        )
    }

    private fun buildPlaybackPath(route: AudioRouteCapability?, state: BitPerfectState): String {
        val routeName = route?.productName ?: route?.deviceName ?: "System Audio Output"
        return when (state) {
            BitPerfectState.VERIFIED -> "Bit-Perfect Path ➔ $routeName (Verified)"
            BitPerfectState.ACTIVE_UNVERIFIED -> "Direct Path ➔ $routeName (Unverified)"
            BitPerfectState.ELIGIBLE -> "Direct Path Capable ➔ $routeName"
            BitPerfectState.REQUESTED, BitPerfectState.NEGOTIATING -> "Direct Path Requested ➔ $routeName"
            BitPerfectState.DISABLED -> "Standard Audio Path ➔ $routeName (Bit-Perfect Off)"
            BitPerfectState.UNAVAILABLE -> "Standard Audio Path ➔ $routeName (Bit-Perfect Unavailable)"
            BitPerfectState.FAILED, BitPerfectState.BROKEN -> "Standard Audio Path ➔ $routeName (Engine Error)"
            else -> "Standard Android Audio Path ➔ $routeName"
        }
    }

    private fun buildSignalPathStages(
        trackInfo: AudioTrackInfo?,
        route: AudioRouteCapability?,
        bitPerfectState: BitPerfectState,
        snapshot: CanonicalAudioRuntimeSnapshot
    ): List<SignalPathStage> {
        val stages = mutableListOf<SignalPathStage>()

        // Stage 1: Source File
        val codec = trackInfo?.codec ?: "Lossless PCM"
        val sampleRateValue = trackInfo?.sampleRateHz ?: 0
        val sampleRateStr = if (sampleRateValue > 0) "${sampleRateValue / 1000.0} kHz" else "Unknown kHz"
        val bitDepthValue = trackInfo?.bitDepth ?: 0
        val bitDepthStr = if (bitDepthValue > 0) "$bitDepthValue-bit" else "Unknown-bit"
        stages.add(
            SignalPathStage(
                stageName = "1. Source Track",
                title = "$codec ($bitDepthStr / $sampleRateStr)",
                description = "Direct lossless decoding from storage container",
                isBitPerfect = true,
                badge = if (sampleRateValue >= 88200 || bitDepthValue >= 24) "HI-RES" else "HI-FI"
            )
        )

        // Stage 2: Media3 Decoder
        stages.add(
            SignalPathStage(
                stageName = "2. Lossless Decoder",
                title = "Media3 Audio Decoder",
                description = "Decodes compressed stream to 32-bit floating point PCM without 16-bit truncation",
                isBitPerfect = true,
                badge = "32-bit Float"
            )
        )

        // Stage 3: DSP / Equalizer Engine
        val dsp = PlaybackService.instance?.dspProcessor
        val dspEnabled = dsp != null && dsp.isEnabled && !dsp.isBitPerfectBypass
        if (dspEnabled) {
            stages.add(
                SignalPathStage(
                    stageName = "3. DSP & Audio Effects",
                    title = "Parametric Equalizer + 64-bit DSP",
                    description = "AudioEffect chain active. Bitstream modified for acoustic shaping",
                    isBitPerfect = false,
                    badge = "DSP ACTIVE"
                )
            )
        } else {
            stages.add(
                SignalPathStage(
                    stageName = "3. Bit-Perfect DSP Bypass",
                    title = "Pure Bit-Perfect Direct Stream",
                    description = "DSP engine completely bypassed to preserve exact studio master bitstream",
                    isBitPerfect = true,
                    badge = "BYPASSED"
                )
            )
        }

        // Stage 4: AudioSink / AudioTrack Pipeline
        val actualRate = snapshot.actualOutput.sampleRate.value
        val actualRateStr = if (actualRate > 0) "${actualRate / 1000.0} kHz" else "UNKNOWN"
        val sinkBadge = when (bitPerfectState) {
            BitPerfectState.VERIFIED -> "VERIFIED"
            BitPerfectState.ACTIVE_UNVERIFIED -> "ACTIVE"
            BitPerfectState.UNKNOWN -> "UNKNOWN"
            else -> "UNAVAILABLE"
        }
        
        stages.add(
            SignalPathStage(
                stageName = "4. AudioSink Output",
                title = "Float AudioSink ($actualRateStr)",
                description = if (bitPerfectState == BitPerfectState.VERIFIED) "Verified direct native stream with exact sample-rate matching"
                else "AudioTrack initialized; bit-perfect status unverified",
                isBitPerfect = bitPerfectState == BitPerfectState.VERIFIED,
                badge = sinkBadge
            )
        )

        // Stage 5: Hardware DAC / Endpoint
        val routeName = route?.productName ?: route?.deviceName ?: "Hardware Audio DAC"
        val dacBadge = when (bitPerfectState) {
            BitPerfectState.VERIFIED -> "VERIFIED"
            BitPerfectState.ACTIVE_UNVERIFIED -> "ACTIVE"
            BitPerfectState.UNKNOWN -> "UNKNOWN"
            else -> route?.routeType?.displayName ?: "DAC"
        }
        stages.add(
            SignalPathStage(
                stageName = "5. Hardware DAC / Endpoint",
                title = routeName,
                description = if (bitPerfectState == BitPerfectState.VERIFIED) "Verified Bit-for-Bit exact studio master output established"
                else if (bitPerfectState == BitPerfectState.ACTIVE_UNVERIFIED) "Direct path active; bit-integrity not yet verified"
                else "Mixed and resampled through Android AudioFlinger audio HAL",
                isBitPerfect = bitPerfectState == BitPerfectState.VERIFIED,
                badge = dacBadge
            )
        )

        return stages
    }

    private fun AudioDeviceInfo.toCapability(): AudioRouteCapability {
        val encodings = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) this.encodings.toList() else emptyList()
        val sampleRates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) this.sampleRates.toList() else emptyList()
        val channelCounts = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) this.channelCounts.toList() else emptyList()

        val routeType = toRouteType()
        val directSupport = when (routeType) {
            AudioOutputRouteType.USB_DAC,
            AudioOutputRouteType.USB_DEVICE,
            AudioOutputRouteType.WIRED_HEADSET,
            AudioOutputRouteType.WIRED_HEADPHONES,
            AudioOutputRouteType.LINE_OUT -> true
            else -> false
        }

        val name = when {
            !productName.isNullOrBlank() -> productName.toString()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !address.isNullOrBlank() -> address.toString()
            else -> routeType.displayName
        }

        return AudioRouteCapability(
            routeType = routeType,
            deviceName = name,
            productName = productName?.toString(),
            sampleRates = sampleRates.filter { it > 0 }.sorted(),
            encodings = encodings.filter { it > 0 }.sorted(),
            channelCounts = channelCounts.filter { it > 0 }.sorted(),
            isDirectPlaybackCapable = directSupport,
            canBeExclusive = directSupport && routeType != AudioOutputRouteType.BLUETOOTH_A2DP
        )
    }
}

