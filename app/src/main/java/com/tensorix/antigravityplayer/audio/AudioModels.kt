package com.tensorix.antigravityplayer.audio

import android.media.AudioDeviceInfo

enum class AudioOutputRouteType(val displayName: String) {
    SPEAKER("Built-in Speaker"),
    WIRED_HEADPHONES("Wired Headphones (3.5mm)"),
    WIRED_HEADSET("Wired Headset (3.5mm w/ Mic)"),
    USB_DAC("USB Audio DAC"),
    USB_DEVICE("USB Audio Device"),
    BLUETOOTH_A2DP("Bluetooth Audio (A2DP)"),
    HDMI("HDMI Audio Output"),
    LINE_OUT("Analog / Digital Line Out"),
    BUILT_IN_EARPIECE("Built-in Earpiece"),
    OTHER("Other Audio Device")
}

enum class ListeningMode(val displayName: String, val badge: String, val description: String) {
    REFERENCE("Reference Mode", "STUDIO", "Pure neutral uncolored studio mastering baseline (0.0 dB gain, 0.0 dBFS true-peak)"),
    AUDIOPHILE("Audiophile Mode", "64-BIT DSP", "64-bit double precision biquads, triode tube warmth, Meier crossfeed, and +3.5 dB dynamic headroom"),
    DYNAMIC("Dynamic Mode", "PUNCH & AIR", "Fletcher-Munson dynamic loudness, +3.5 dB clarity enhancement, and +2.0 dB high-end air presence")
}

enum class BitPerfectState(val label: String) {
    DISABLED("Bit-Perfect Off"),
    UNAVAILABLE("Not Supported by Hardware/Path"),
    ELIGIBLE("Direct Path Capable"),
    REQUESTED("Requested (Awaiting Verification)"),
    NEGOTIATING("Negotiating Direct Path"),
    ACTIVE_UNVERIFIED("Direct Path Active (Unverified)"),
    VERIFIED("Bit-Perfect Verified"),
    FAILED("Engine Failure"),
    BROKEN("Broken (Internal Error)"),
    UNKNOWN("Unknown State")
}

enum class DirectPathState(val displayName: String) {
    DIRECT_CAPABILITY("Direct Output Capable"),
    DIRECT_REQUESTED("Direct Output Requested"),
    DIRECT_NEGOTIATING("Direct Output Negotiating"),
    DIRECT_ACTIVE("Direct Output Active"),
    DIRECT_FAILED("Direct Output Failed"),
    DIRECT_UNKNOWN("Direct Output State Unknown")
}

enum class MixerPathState(val displayName: String) {
    MIXER_ACTIVE("AudioFlinger Mixer Active"),
    DIRECT_ACTIVE("Direct HAL Path Active (Mixer Bypassed)"),
    OFFLOAD_ACTIVE("Hardware Offload Active"),
    UNKNOWN("Mixer State Unknown")
}

enum class EvidenceSource {
    SOURCE_METADATA,
    ANDROID_AUDIO_DEVICE,
    AUDIO_TRACK,
    OBOE_STREAM,
    USB_DESCRIPTOR,
    HAL_PARAMETER,
    VENDOR_API,
    HEURISTIC,
    UNKNOWN
}

enum class Confidence {
    VERIFIED,
    HIGH_CONFIDENCE,
    INFERRED,
    UNKNOWN,
    UNAVAILABLE
}

data class AudioEvidence<T>(
    val value: T,
    val source: EvidenceSource,
    val confidence: Confidence,
    val timestamp: Long = System.currentTimeMillis()
)

data class AudioFormatSnapshot(
    val sampleRate: AudioEvidence<Int>,
    val bitDepth: AudioEvidence<Int>,
    val channels: AudioEvidence<Int>,
    val encoding: AudioEvidence<String>
)

data class NativeStreamSnapshot(
    val handle: Long = 0L,
    val state: String = "UNKNOWN", // Started, Paused, Stopped, Open, Closed, Unknown
    val isStarted: Boolean = false,
    val sampleRate: Int = 0,
    val channelCount: Int = 0,
    val nativeFormat: String = "UNKNOWN", // Float, I16, I24, I32
    val sharingMode: String = "UNKNOWN", // EXCLUSIVE / SHARED
    val performanceMode: String = "UNKNOWN", // LOW_LATENCY, NONE, POWER_SAVING
    val audioApi: String = "UNKNOWN", // AAudio, OpenSLES
    val deviceId: Int = 0,
    val framesWritten: Long = 0L,
    val underrunCount: Int = 0,
    val bufferSizeInFrames: Int = 0,
    val confidence: Confidence = Confidence.UNKNOWN,
    val bitDepth: Int = 0,
    val channelMask: Int = 0,
    val streamGeneration: Long = 0L,
    val apiId: Int = 0,
    val sharingModeId: Int = 0,
    val formatId: Int = 0
)

data class SignalProcessingPipelineSnapshot(
    val sourcePcm: AudioFormatSnapshot,
    val decoderConversion: String = "32-bit Float",
    val dspConversion: String = "64-bit Double",
    val isDspBypassed: Boolean = false,
    val resamplerState: String = "OFF", // OFF / ACTIVE / BYPASS
    val channelRemapActive: Boolean = false,
    val softwareGainActive: Boolean = false,
    val ditherActive: Boolean = false,
    val outputConversion: String = "Integer PCM / Float",
    val dacEndpoint: String = "Hardware Endpoint"
)

data class AudioRuntimeSnapshot(
    val sourceFormat: AudioFormatSnapshot,
    val decodedFormat: AudioFormatSnapshot,
    val processingFormat: AudioFormatSnapshot,
    val requestedOutputFormat: AudioFormatSnapshot,
    val actualOutputFormat: AudioFormatSnapshot,

    val activeRoute: AudioEvidence<AudioOutputRouteType>,
    val audioApi: AudioEvidence<AudioOutputApi>,
    val sharingMode: AudioEvidence<String>, // EXCLUSIVE / SHARED
    val performanceMode: AudioEvidence<String>,

    val directPlaybackActive: AudioEvidence<Boolean>,
    val resamplerState: AudioEvidence<String>, // OFF / ACTIVE / BYPASS
    val dspState: AudioEvidence<String>, // OFF / ACTIVE / BYPASS

    val bitPerfectState: BitPerfectState,
    val bitPerfectEvidence: String = ""
)

enum class AudioOutputApi(val label: String) {
    AAUDIO("AAudio (High Performance)"),
    AUDIOTRACK("Java AudioTrack (Stable)"),
    OPENSL_ES("OpenSL ES (Legacy High Performance)"),
    OFFLOAD("Hardware Offload (Direct)")
}

data class OutputDeviceConfig(
    val api: AudioOutputApi = AudioOutputApi.AAUDIO,
    val sampleRate: Int = 48000,
    val bitDepth: Int = 24,
    val bufferSizeMultiplier: Int = 2, // 1 = small, 2 = normal, 4 = large
    val exclusiveMode: Boolean = false,
    val ditherEnabled: Boolean = true
)

data class UsbDacInfo(
    val deviceName: String,
    val manufacturerName: String?,
    val productName: String?,
    val vendorId: Int,
    val productId: Int,
    val deviceClass: Int,
    val deviceSubclass: Int,
    val interfaceCount: Int,
    val isAudioClassCompliant: Boolean,
    val supportedSampleRates: List<Int> = emptyList(),
    val supportedBitDepths: List<Int> = emptyList()
)

data class BitPerfectEvidence(
    val description: String,
    val isSatisfied: Boolean,
    val source: EvidenceSource,
    val value: String = ""
)

data class BitPerfectVerificationResult(
    val state: BitPerfectState,
    val evidence: List<BitPerfectEvidence>,
    val confidence: Confidence,
    val failureReasons: List<String>
)

data class SignalPathStage(
    val stageName: String,
    val title: String,
    val description: String,
    val isBitPerfect: Boolean,
    val badge: String? = null
)

data class AudioRouteCapability(
    val routeType: AudioOutputRouteType,
    val deviceName: String,
    val productName: String? = null,
    val sampleRates: List<Int> = emptyList(),
    val encodings: List<Int> = emptyList(),
    val channelCounts: List<Int> = emptyList(),
    val isDirectPlaybackCapable: Boolean = false,
    val canBeExclusive: Boolean = false
)

data class CanonicalAudioRuntimeSnapshot(
    val timestamp: Long = System.currentTimeMillis(),

    val source: AudioFormatSnapshot,
    val decoder: AudioFormatSnapshot,
    val processing: AudioFormatSnapshot,

    val requestedOutput: AudioFormatSnapshot,
    val actualOutput: AudioFormatSnapshot,

    val activeRoute: AudioEvidence<AudioOutputRouteType>,
    val audioApi: AudioEvidence<AudioOutputApi>,
    val sharingMode: AudioEvidence<String>, // EXCLUSIVE / SHARED
    val performanceMode: AudioEvidence<String>,

    val directPathActive: AudioEvidence<Boolean>,
    val directPathState: AudioEvidence<DirectPathState> = AudioEvidence(
        if (directPathActive.value) DirectPathState.DIRECT_ACTIVE else DirectPathState.DIRECT_UNKNOWN,
        directPathActive.source,
        directPathActive.confidence,
        directPathActive.timestamp
    ),
    val mixerPathActive: AudioEvidence<Boolean>,
    val mixerPathState: AudioEvidence<MixerPathState> = AudioEvidence(
        if (!directPathActive.value && mixerPathActive.value) MixerPathState.MIXER_ACTIVE else if (directPathActive.value) MixerPathState.DIRECT_ACTIVE else MixerPathState.UNKNOWN,
        mixerPathActive.source,
        mixerPathActive.confidence,
        mixerPathActive.timestamp
    ),
    val resamplerState: AudioEvidence<String>, // OFF / ACTIVE / BYPASS
    val dspState: AudioEvidence<String>, // OFF / ACTIVE / BYPASS

    val dac: DacRuntimeState,

    val bitPerfect: BitPerfectRuntimeState,

    val nativeStream: NativeStreamSnapshot? = null,
    val pipeline: SignalProcessingPipelineSnapshot? = null,

    val confidence: Confidence,
    val limitations: List<String>
)

data class DacRuntimeState(
    val modelName: AudioEvidence<String>,
    val vendor: AudioEvidence<String>,
    val isActive: AudioEvidence<Boolean>,
    val maxSampleRate: AudioEvidence<Int>,
    val maxBitDepth: AudioEvidence<Int>,
    val confidence: Confidence
)

data class BitPerfectRuntimeState(
    val state: BitPerfectState,
    val eligibility: Boolean,
    val verificationResult: BitPerfectVerificationResult? = null,
    val confidence: Confidence
) {
    val evidence: String
        get() = verificationResult?.evidence?.filter { it.isSatisfied }?.joinToString("; ") { it.description }
            ?: if (state == BitPerfectState.VERIFIED) "All bit-perfect requirements verified" else "Awaiting verification"
}

data class AudioOutputState(
    val activeRoute: AudioRouteCapability? = null,
    val availableRoutes: List<AudioRouteCapability> = emptyList(),
    val connectedUsbDacs: List<UsbDacInfo> = emptyList(),
    val currentPlaybackSampleRate: Int = 0,
    val currentPlaybackBitDepth: Int = 0,
    val playbackPath: String = "Unknown",
    val bitPerfectState: BitPerfectState = BitPerfectState.UNKNOWN,
    val bitPerfectPossible: Boolean = false,
    val resamplingRequired: Boolean = false,
    val signalPathStages: List<SignalPathStage> = emptyList(),
    val deviceLimitations: List<String> = emptyList(),
    val latencyMs: Int = 0,
    val runtimeSnapshot: AudioRuntimeSnapshot? = null,
    val canonicalSnapshot: CanonicalAudioRuntimeSnapshot? = null
)

data class AudioQualityState(
    val isLossless: Boolean = false,
    val isHiResSource: Boolean = false,
    val isHiResOutput: Boolean = false,
    val bitPerfectState: BitPerfectState = BitPerfectState.UNKNOWN
) {
    companion object {
        fun evaluate(
            sourceCodec: String,
            sourceSampleRate: Int,
            sourceBitDepth: Int,
            actualOutputSampleRate: Int,
            actualOutputBitDepth: Int,
            bitPerfectState: BitPerfectState
        ): AudioQualityState {
            val cleanCodec = sourceCodec.uppercase()
            val isLossless = cleanCodec.contains("FLAC") ||
                    cleanCodec.contains("ALAC") ||
                    cleanCodec.contains("WAV") ||
                    cleanCodec.contains("AIFF") ||
                    cleanCodec.contains("APE") ||
                    cleanCodec.contains("DSF") ||
                    cleanCodec.contains("DFF") ||
                    cleanCodec.contains("DSD") ||
                    cleanCodec.contains("LOSSLESS")

            val isHiResSource = (sourceBitDepth >= 24) || (sourceSampleRate >= 88200)
            val isHiResOutput = (actualOutputSampleRate >= 88200) && (actualOutputBitDepth >= 24)

            return AudioQualityState(
                isLossless = isLossless,
                isHiResSource = isHiResSource,
                isHiResOutput = isHiResOutput,
                bitPerfectState = bitPerfectState
            )
        }
    }
}

data class AudioTrackInfo(
    val title: String = "Unknown Track",
    val artist: String = "Unknown Artist",
    val codec: String = "Unknown",
    val bitrateKbps: Int = 0,
    val bitDepth: Int = 16,
    val sampleRateHz: Int = 44100,
    val channels: Int = 2,
    val isHiResSource: Boolean = (bitDepth >= 24 || sampleRateHz >= 88200),
    val isHiRes: Boolean = (bitDepth >= 24 || sampleRateHz >= 88200),
    val quality: AudioQualityState = AudioQualityState(
        isLossless = false,
        isHiResSource = (bitDepth >= 24 || sampleRateHz >= 88200),
        isHiResOutput = false,
        bitPerfectState = BitPerfectState.UNKNOWN
    )
)

data class AudiophilePlaybackSnapshot(
    val track: AudioTrackInfo = AudioTrackInfo(),
    val output: AudioOutputState = AudioOutputState(),
    val quality: AudioQualityState = AudioQualityState()
)

internal fun AudioDeviceInfo.toRouteType(): AudioOutputRouteType = when (type) {
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AudioOutputRouteType.WIRED_HEADPHONES
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> AudioOutputRouteType.WIRED_HEADSET
    AudioDeviceInfo.TYPE_AUX_LINE -> AudioOutputRouteType.WIRED_HEADPHONES
    AudioDeviceInfo.TYPE_USB_DEVICE -> AudioOutputRouteType.USB_DEVICE
    AudioDeviceInfo.TYPE_USB_HEADSET -> AudioOutputRouteType.USB_DAC
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> AudioOutputRouteType.USB_DEVICE
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AudioOutputRouteType.BLUETOOTH_A2DP
    AudioDeviceInfo.TYPE_HDMI -> AudioOutputRouteType.HDMI
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioOutputRouteType.BUILT_IN_EARPIECE
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioOutputRouteType.SPEAKER
    AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> AudioOutputRouteType.LINE_OUT
    else -> AudioOutputRouteType.OTHER
}
