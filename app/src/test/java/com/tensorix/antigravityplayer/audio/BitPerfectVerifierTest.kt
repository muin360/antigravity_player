package com.tensorix.antigravityplayer.audio

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@UnstableApi
class BitPerfectVerifierTest {

    @Before
    fun setUp() {
        OboeAudioSink.currentActiveHandle = 12345L
    }

    @org.junit.After
    fun tearDown() {
        OboeAudioSink.currentActiveHandle = 0L
        OboeAudioSink.currentStreamInfo = null
    }

    // ========================================================================
    // MANDATORY REAL-DEVICE STATE SCENARIO TESTS (Section 12)
    // ========================================================================

    @Test
    fun `TEST 1 - BitPerfect OFF, DSP ON, Shared output yields DISABLED`() {
        val snapshot = createBaseSnapshot().copy(
            sharingMode = AudioEvidence("SHARED", EvidenceSource.OBOE_STREAM, Confidence.VERIFIED),
            directPathActive = AudioEvidence(false, EvidenceSource.HAL_PARAMETER, Confidence.VERIFIED),
            mixerPathActive = AudioEvidence(true, EvidenceSource.HAL_PARAMETER, Confidence.VERIFIED),
            dspState = AudioEvidence("ACTIVE", EvidenceSource.OBOE_STREAM, Confidence.VERIFIED)
        )
        val dsp = createBaseDsp(isEnabled = true, isBitPerfectBypass = false)

        val result = BitPerfectVerifier.verify(snapshot, dsp, isHrtfEnabled = false, isBitPerfectRequested = false)

        assertEquals(BitPerfectState.DISABLED, result.state)
        assertEquals(Confidence.VERIFIED, result.confidence)
        assertEquals(0, result.failureReasons.size)
    }

    @Test
    fun `TEST 2 - BitPerfect OFF, DSP OFF, Shared output yields DISABLED`() {
        val snapshot = createBaseSnapshot().copy(
            sharingMode = AudioEvidence("SHARED", EvidenceSource.OBOE_STREAM, Confidence.VERIFIED),
            directPathActive = AudioEvidence(false, EvidenceSource.HAL_PARAMETER, Confidence.VERIFIED),
            mixerPathActive = AudioEvidence(true, EvidenceSource.HAL_PARAMETER, Confidence.VERIFIED),
            dspState = AudioEvidence("OFF", EvidenceSource.OBOE_STREAM, Confidence.VERIFIED)
        )
        val dsp = createBaseDsp(isEnabled = false, isBitPerfectBypass = true)

        val result = BitPerfectVerifier.verify(snapshot, dsp, isHrtfEnabled = false, isBitPerfectRequested = false)

        assertEquals(BitPerfectState.DISABLED, result.state)
        assertEquals(Confidence.VERIFIED, result.confidence)
        assertEquals(0, result.failureReasons.size)
    }

    @Test
    fun `TEST 3 - BitPerfect OFF, Direct output yields DISABLED`() {
        val snapshot = createBaseSnapshot().copy(
            sharingMode = AudioEvidence("EXCLUSIVE", EvidenceSource.OBOE_STREAM, Confidence.VERIFIED),
            directPathActive = AudioEvidence(true, EvidenceSource.OBOE_STREAM, Confidence.VERIFIED),
            mixerPathActive = AudioEvidence(false, EvidenceSource.HAL_PARAMETER, Confidence.VERIFIED)
        )
        val dsp = createBaseDsp(isEnabled = false, isBitPerfectBypass = true)

        val result = BitPerfectVerifier.verify(snapshot, dsp, isHrtfEnabled = false, isBitPerfectRequested = false)

        assertEquals(BitPerfectState.DISABLED, result.state)
        assertEquals(Confidence.VERIFIED, result.confidence)
        assertEquals(0, result.failureReasons.size)
    }

    @Test
    fun `TEST 4 - BitPerfect ON, DSP ON yields UNAVAILABLE with DSP reason`() {
        val snapshot = createBaseSnapshot().copy(
            dspState = AudioEvidence("ACTIVE", EvidenceSource.OBOE_STREAM, Confidence.VERIFIED)
        )
        val dsp = createBaseDsp(isEnabled = true, isBitPerfectBypass = false)

        val result = BitPerfectVerifier.verify(snapshot, dsp, isHrtfEnabled = false, isBitPerfectRequested = true)

        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
        assertTrue(result.failureReasons.any { it.contains("DSP Engine is active") })
    }

    @Test
    fun `TEST 5 - BitPerfect ON, DSP OFF, Shared output yields UNAVAILABLE with mixer reason, not FAILED`() {
        val snapshot = createBaseSnapshot().copy(
            sharingMode = AudioEvidence("SHARED", EvidenceSource.OBOE_STREAM, Confidence.VERIFIED),
            directPathActive = AudioEvidence(false, EvidenceSource.HAL_PARAMETER, Confidence.VERIFIED),
            mixerPathActive = AudioEvidence(true, EvidenceSource.HAL_PARAMETER, Confidence.VERIFIED)
        )
        val dsp = createBaseDsp(isEnabled = false, isBitPerfectBypass = true)

        val result = BitPerfectVerifier.verify(snapshot, dsp, isHrtfEnabled = false, isBitPerfectRequested = true)

        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
        assertFalse(result.state == BitPerfectState.FAILED)
        assertTrue(result.failureReasons.any { it.contains("shared mixer mode") || it.contains("mixer path is active") })
    }

    @Test
    fun `TEST 6 - BitPerfect ON, DSP OFF, Exclusive direct path, all evidence valid yields VERIFIED`() {
        val snapshot = createBaseSnapshot()
        val dsp = createBaseDsp(isEnabled = false, isBitPerfectBypass = true)

        val result = BitPerfectVerifier.verify(snapshot, dsp, isHrtfEnabled = false, isBitPerfectRequested = true)

        assertEquals(BitPerfectState.VERIFIED, result.state)
        assertEquals(Confidence.VERIFIED, result.confidence)
        assertEquals(0, result.failureReasons.size)
    }

    // ========================================================================
    // GRANULAR VERIFICATION CONDITIONS
    // ========================================================================

    @Test
    fun `NEGATIVE TEST - Software volume non-unity`() {
        val dsp = createBaseDsp(volume = 0.8)
        val result = BitPerfectVerifier.verify(createBaseSnapshot(), dsp, isHrtfEnabled = false, isBitPerfectRequested = true)

        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
        assertTrue(result.failureReasons.any { it.contains("Software digital volume attenuation is active") })
    }

    @Test
    fun `NEGATIVE TEST - Preamp gain non-unity`() {
        val dsp = createBaseDsp(preamp = 2.5)
        val result = BitPerfectVerifier.verify(createBaseSnapshot(), dsp, isHrtfEnabled = false, isBitPerfectRequested = true)

        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
        assertTrue(result.failureReasons.any { it.contains("Preamp gain is active") })
    }

    @Test
    fun `NEGATIVE TEST - ReplayGain non-unity`() {
        val dsp = createBaseDsp()
        whenever(dsp.replayGainMultiplier).thenReturn(0.7)
        val result = BitPerfectVerifier.verify(createBaseSnapshot(), dsp, isHrtfEnabled = false, isBitPerfectRequested = true)

        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
        assertTrue(result.failureReasons.any { it.contains("ReplayGain modification is active") })
    }

    @Test
    fun `NEGATIVE TEST - Dither active`() {
        val dsp = createBaseDsp(dither = 1.0)
        val result = BitPerfectVerifier.verify(createBaseSnapshot(), dsp, isHrtfEnabled = false, isBitPerfectRequested = true)

        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
        assertTrue(result.failureReasons.any { it.contains("TPDF dither modification is active") })
    }

    @Test
    fun `NEGATIVE TEST - Limiter enabled`() {
        val dsp = createBaseDsp()
        whenever(dsp.limiterEnabled).thenReturn(true)
        val result = BitPerfectVerifier.verify(createBaseSnapshot(), dsp, isHrtfEnabled = false, isBitPerfectRequested = true)

        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
        assertTrue(result.failureReasons.any { it.contains("True-peak limiter is active") })
    }

    @Test
    fun `NEGATIVE TEST - Crossfeed active`() {
        val dsp = createBaseDsp()
        whenever(dsp.crossfeedLevel).thenReturn(0.5)
        val result = BitPerfectVerifier.verify(createBaseSnapshot(), dsp, isHrtfEnabled = false, isBitPerfectRequested = true)

        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
        assertTrue(result.failureReasons.any { it.contains("Meier crossfeed is active") })
    }

    @Test
    fun `NEGATIVE TEST - Channel balance non-zero`() {
        val dsp = createBaseDsp()
        whenever(dsp.channelBalance).thenReturn(-0.5)
        val result = BitPerfectVerifier.verify(createBaseSnapshot(), dsp, isHrtfEnabled = false, isBitPerfectRequested = true)

        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
        assertTrue(result.failureReasons.any { it.contains("Channel balance attenuation is active") })
    }

    @Test
    fun `NEGATIVE TEST - HRTF Spatial Audio enabled`() {
        val result = BitPerfectVerifier.verify(createBaseSnapshot(), createBaseDsp(), isHrtfEnabled = true, isBitPerfectRequested = true)

        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
        assertTrue(result.failureReasons.any { it.contains("Spatial Audio processing is active") })
    }

    @Test
    fun `NEGATIVE TEST - Sample rate mismatch`() {
        val snapshot = createBaseSnapshot().copy(
            actualOutput = createFormat(48000, 24, 2, "PCM")
        )
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), isHrtfEnabled = false, isBitPerfectRequested = true)

        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
        assertTrue(result.failureReasons.any { it.contains("Sample rate mismatch") })
    }

    @Test
    fun `NEGATIVE TEST - Channel count mismatch`() {
        val snapshot = createBaseSnapshot().copy(
            actualOutput = createFormat(44100, 16, 1, "PCM")
        )
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), isHrtfEnabled = false, isBitPerfectRequested = true)

        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
        assertTrue(result.failureReasons.any { it.contains("Channel count mismatch") })
    }

    @Test
    fun `NEGATIVE TEST - Route UNKNOWN yields UNAVAILABLE`() {
        val snapshot = createBaseSnapshot().copy(
            activeRoute = AudioEvidence(AudioOutputRouteType.OTHER, EvidenceSource.UNKNOWN, Confidence.UNKNOWN)
        )
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), isHrtfEnabled = false, isBitPerfectRequested = true)

        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
    }

    @Test
    fun `NEGATIVE TEST - Route Bluetooth A2DP yields UNAVAILABLE`() {
        val snapshot = createBaseSnapshot().copy(
            activeRoute = AudioEvidence(AudioOutputRouteType.BLUETOOTH_A2DP, EvidenceSource.ANDROID_AUDIO_DEVICE, Confidence.VERIFIED)
        )
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), isHrtfEnabled = false, isBitPerfectRequested = true)

        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
    }

    @Test
    fun `NEGATIVE TEST - Route Built-in Speaker yields UNAVAILABLE`() {
        val snapshot = createBaseSnapshot().copy(
            activeRoute = AudioEvidence(AudioOutputRouteType.SPEAKER, EvidenceSource.ANDROID_AUDIO_DEVICE, Confidence.VERIFIED)
        )
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), isHrtfEnabled = false, isBitPerfectRequested = true)

        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
    }

    @Test
    fun `NEGATIVE TEST - Resampler active yields UNAVAILABLE`() {
        val snapshot = createBaseSnapshot().copy(
            resamplerState = AudioEvidence("ACTIVE", EvidenceSource.HAL_PARAMETER, Confidence.VERIFIED)
        )
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), isHrtfEnabled = false, isBitPerfectRequested = true)

        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
        assertTrue(result.failureReasons.any { it.contains("Resampler is actively altering audio clock") })
    }

    @Test
    fun `NEGATIVE TEST - Stream handle is null or closed yields REQUESTED`() {
        OboeAudioSink.currentActiveHandle = 0L

        val snapshot = createBaseSnapshot().copy(nativeStream = null)
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), isHrtfEnabled = false, isBitPerfectRequested = true)

        assertEquals(BitPerfectState.REQUESTED, result.state)
        assertTrue(result.failureReasons.any { it.contains("stream handle is null or closed") })
    }

    @Test
    fun `NEGATIVE TEST - Critical telemetry is UNKNOWN while direct path is active yields ACTIVE_UNVERIFIED`() {
        val snapshot = createBaseSnapshot().copy(
            actualOutput = createFormat(0, 0, 0, "Unknown", Confidence.UNKNOWN)
        )
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), isHrtfEnabled = false, isBitPerfectRequested = true)

        assertEquals(BitPerfectState.ACTIVE_UNVERIFIED, result.state)
        assertTrue(result.failureReasons.any { it.contains("unknown or unverified") })
    }

    @Test
    fun `NEGATIVE TEST - 32-bit integer PCM routed to 32-bit Float output yields UNAVAILABLE (Rule 10)`() {
        val snapshot = createBaseSnapshot().copy(
            source = createFormat(44100, 32, 2, "WAV_PCM32"),
            actualOutput = createFormat(44100, 32, 2, "Float")
        )
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), isHrtfEnabled = false, isBitPerfectRequested = true)

        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
        assertTrue(result.failureReasons.any { it.contains("PCM bit-depth truncation or lossy downconversion detected") })
    }

    @Test
    fun `POSITIVE TEST - 24-bit PCM routed to 32-bit Float output preserves bit depth (Rule 10)`() {
        val snapshot = createBaseSnapshot().copy(
            source = createFormat(96000, 24, 2, "FLAC"),
            actualOutput = createFormat(96000, 32, 2, "Float")
        )
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), isHrtfEnabled = false, isBitPerfectRequested = true)

        assertEquals(BitPerfectState.VERIFIED, result.state)
        assertTrue(result.evidence.first { it.description == "No Lossy PCM" }.isSatisfied)
    }

    @Test
    fun `NEGATIVE TEST - DSD source decimated to PCM yields UNAVAILABLE (Rule 40)`() {
        val snapshot = createBaseSnapshot().copy(
            source = createFormat(2822400, 1, 2, "DSD_DFF"),
            actualOutput = createFormat(88200, 24, 2, "PCM")
        )
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), isHrtfEnabled = false, isBitPerfectRequested = true)

        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
        assertTrue(result.failureReasons.any { it.contains("DSD 1-bit bitstream is decimated to PCM") })
    }

    private fun createBaseSnapshot(): CanonicalAudioRuntimeSnapshot {
        return CanonicalAudioRuntimeSnapshot(
            source = createFormat(44100, 16, 2, "FLAC"),
            decoder = createFormat(44100, 32, 2, "PCM_FLOAT"),
            processing = createFormat(44100, 64, 2, "FLOAT64"),
            requestedOutput = createFormat(44100, 16, 2, "PCM"),
            actualOutput = createFormat(44100, 16, 2, "PCM"),
            activeRoute = AudioEvidence(AudioOutputRouteType.USB_DAC, EvidenceSource.ANDROID_AUDIO_DEVICE, Confidence.VERIFIED),
            audioApi = AudioEvidence(AudioOutputApi.AAUDIO, EvidenceSource.OBOE_STREAM, Confidence.VERIFIED),
            sharingMode = AudioEvidence("EXCLUSIVE", EvidenceSource.OBOE_STREAM, Confidence.VERIFIED),
            performanceMode = AudioEvidence("LOW_LATENCY", EvidenceSource.OBOE_STREAM, Confidence.VERIFIED),
            directPathActive = AudioEvidence(true, EvidenceSource.OBOE_STREAM, Confidence.VERIFIED),
            mixerPathActive = AudioEvidence(false, EvidenceSource.HAL_PARAMETER, Confidence.VERIFIED),
            resamplerState = AudioEvidence("OFF", EvidenceSource.HAL_PARAMETER, Confidence.VERIFIED),
            dspState = AudioEvidence("OFF", EvidenceSource.OBOE_STREAM, Confidence.VERIFIED),
            dac = DacRuntimeState(
                modelName = AudioEvidence("Test DAC", EvidenceSource.HEURISTIC, Confidence.HIGH_CONFIDENCE),
                vendor = AudioEvidence("Test Vendor", EvidenceSource.HEURISTIC, Confidence.HIGH_CONFIDENCE),
                isActive = AudioEvidence(true, EvidenceSource.HAL_PARAMETER, Confidence.VERIFIED),
                maxSampleRate = AudioEvidence(192000, EvidenceSource.HEURISTIC, Confidence.HIGH_CONFIDENCE),
                maxBitDepth = AudioEvidence(32, EvidenceSource.HEURISTIC, Confidence.HIGH_CONFIDENCE),
                confidence = Confidence.HIGH_CONFIDENCE
            ),
            bitPerfect = BitPerfectRuntimeState(BitPerfectState.DISABLED, true, null, Confidence.VERIFIED),
            nativeStream = NativeStreamSnapshot(
                handle = 12345L,
                state = "Started",
                isStarted = true,
                sampleRate = 44100,
                channelCount = 2,
                nativeFormat = "Float",
                sharingMode = "EXCLUSIVE",
                performanceMode = "LOW_LATENCY",
                audioApi = "AAudio",
                deviceId = 1,
                framesWritten = 1000L,
                underrunCount = 0,
                bufferSizeInFrames = 192,
                confidence = Confidence.VERIFIED
            ),
            confidence = Confidence.UNKNOWN,
            limitations = emptyList()
        )
    }

    private fun createFormat(rate: Int, depth: Int, channels: Int, enc: String, conf: Confidence = Confidence.VERIFIED): AudioFormatSnapshot {
        return AudioFormatSnapshot(
            sampleRate = AudioEvidence(rate, EvidenceSource.SOURCE_METADATA, conf),
            bitDepth = AudioEvidence(depth, EvidenceSource.SOURCE_METADATA, conf),
            channels = AudioEvidence(channels, EvidenceSource.SOURCE_METADATA, conf),
            encoding = AudioEvidence(enc, EvidenceSource.SOURCE_METADATA, conf)
        )
    }

    private fun createBaseDsp(
        isEnabled: Boolean = false,
        isBitPerfectBypass: Boolean = true,
        volume: Double = 1.0,
        preamp: Double = 0.0,
        dither: Double = 0.0
    ): Audiophile64BitDspProcessor {
        val dsp = mock<Audiophile64BitDspProcessor>()
        whenever(dsp.isEnabled).thenReturn(isEnabled)
        whenever(dsp.isBitPerfectBypass).thenReturn(isBitPerfectBypass)
        whenever(dsp.dvcVolume).thenReturn(volume)
        whenever(dsp.preAmpGainDb).thenReturn(preamp)
        whenever(dsp.ditherStrength).thenReturn(dither)
        whenever(dsp.limiterEnabled).thenReturn(false)
        whenever(dsp.crossfeedLevel).thenReturn(0.0)
        whenever(dsp.replayGainMultiplier).thenReturn(1.0)
        whenever(dsp.channelBalance).thenReturn(0.0)
        return dsp
    }
}
