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
        OboeAudioSink.activeStreamSnapshot = ActiveStreamSnapshot(handle = 12345L, generation = 1L, epoch = 1L)
    }

    @org.junit.After
    fun tearDown() {
        OboeAudioSink.activeStreamSnapshot = null
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
        OboeAudioSink.activeStreamSnapshot = null

        val snapshot = createBaseSnapshot().copy(nativeStream = null)
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), isHrtfEnabled = false, isBitPerfectRequested = true)

        assertEquals(BitPerfectState.REQUESTED, result.state)
        assertTrue(result.failureReasons.any { it.contains("stream handle is null or closed") || it.contains("telemetry is null") })
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
        assertTrue(result.failureReasons.any { it.contains("Native DSD bitstream is unsupported; decoded to PCM") || it.contains("DSD") })
    }

    @Test
    fun `REGRESSION TEST - SHARED + DIRECT_ACTIVE yields UNAVAILABLE and never VERIFIED (Rule 13)`() {
        val snapshot = createBaseSnapshot().copy(
            sharingMode = AudioEvidence("SHARED", EvidenceSource.OBOE_STREAM, Confidence.VERIFIED),
            directPathActive = AudioEvidence(true, EvidenceSource.OBOE_STREAM, Confidence.VERIFIED),
            mixerPathActive = AudioEvidence(false, EvidenceSource.HAL_PARAMETER, Confidence.VERIFIED)
        )
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), isHrtfEnabled = false, isBitPerfectRequested = true)

        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
        assertFalse("SHARED mode must never yield VERIFIED", result.state == BitPerfectState.VERIFIED)
        assertTrue(result.failureReasons.any { it.contains("shared mixer mode") })
    }

    @Test
    fun `REGRESSION TEST - EXCLUSIVE + DIRECT_FALSE yields UNAVAILABLE and never VERIFIED (Rule 13)`() {
        val snapshot = createBaseSnapshot().copy(
            sharingMode = AudioEvidence("EXCLUSIVE", EvidenceSource.OBOE_STREAM, Confidence.VERIFIED),
            directPathActive = AudioEvidence(false, EvidenceSource.OBOE_STREAM, Confidence.VERIFIED),
            mixerPathActive = AudioEvidence(false, EvidenceSource.HAL_PARAMETER, Confidence.VERIFIED)
        )
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), isHrtfEnabled = false, isBitPerfectRequested = true)

        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
        assertFalse("DIRECT_FALSE must never yield VERIFIED", result.state == BitPerfectState.VERIFIED)
        assertTrue(result.failureReasons.any { it.contains("Direct PCM HAL path is not verified") || it.contains("Direct PCM HAL path") })
    }

    @Test
    fun `REGRESSION TEST - HIGH_CONFIDENCE on direct path yields ACTIVE_UNVERIFIED and never VERIFIED (Rule 14)`() {
        val snapshot = createBaseSnapshot().copy(
            sharingMode = AudioEvidence("EXCLUSIVE", EvidenceSource.OBOE_STREAM, Confidence.VERIFIED),
            directPathActive = AudioEvidence(true, EvidenceSource.HAL_PARAMETER, Confidence.HIGH_CONFIDENCE),
            mixerPathActive = AudioEvidence(false, EvidenceSource.HAL_PARAMETER, Confidence.VERIFIED)
        )
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), isHrtfEnabled = false, isBitPerfectRequested = true)

        assertEquals(BitPerfectState.ACTIVE_UNVERIFIED, result.state)
        assertFalse("HIGH_CONFIDENCE must never yield VERIFIED", result.state == BitPerfectState.VERIFIED)
    }

    @Test
    fun `REGRESSION TEST - HIGH_CONFIDENCE on output rate yields ACTIVE_UNVERIFIED and never VERIFIED (Rule 14)`() {
        val snapshot = createBaseSnapshot().copy(
            actualOutput = createFormat(44100, 16, 2, "PCM", Confidence.HIGH_CONFIDENCE)
        )
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), isHrtfEnabled = false, isBitPerfectRequested = true)

        assertEquals(BitPerfectState.ACTIVE_UNVERIFIED, result.state)
        assertFalse("Inferred/High-Confidence rate must never yield VERIFIED", result.state == BitPerfectState.VERIFIED)
    }

    // ========================================================================
    // NEGATIVE DOMINANCE SUITE (User Requirement 46)
    // Every single mandatory criterion independently fails -> NOT VERIFIED
    // ========================================================================

    @Test
    fun `NEGATIVE DOMINANCE - Wrong route (Speaker) yields UNAVAILABLE`() {
        val snapshot = createBaseSnapshot().copy(
            activeRoute = AudioEvidence(AudioOutputRouteType.SPEAKER, EvidenceSource.ANDROID_AUDIO_DEVICE, Confidence.VERIFIED)
        )
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), isHrtfEnabled = false, isBitPerfectRequested = true)
        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
        assertTrue(result.failureReasons.any { it.contains("does not support bit-perfect") })
    }

    @Test
    fun `NEGATIVE DOMINANCE - Unverified route yields UNAVAILABLE`() {
        val snapshot = createBaseSnapshot().copy(
            activeRoute = AudioEvidence(AudioOutputRouteType.USB_DAC, EvidenceSource.ANDROID_AUDIO_DEVICE, Confidence.INFERRED)
        )
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), isHrtfEnabled = false, isBitPerfectRequested = true)
        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
        assertTrue(result.failureReasons.any { it.contains("route is not verified") })
    }

    @Test
    fun `NEGATIVE DOMINANCE - EQ active yields UNAVAILABLE`() {
        val snapshot = createBaseSnapshot()
        val dsp = createBaseDsp(isEqActive = true)
        val result = BitPerfectVerifier.verify(snapshot, dsp, isHrtfEnabled = false, isBitPerfectRequested = true)
        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
        assertTrue(result.failureReasons.any { it.contains("Equalizer filters are active") })
    }

    @Test
    fun `NEGATIVE DOMINANCE - Bass boost active yields UNAVAILABLE`() {
        val snapshot = createBaseSnapshot()
        val dsp = createBaseDsp(isBassBoostActive = true)
        val result = BitPerfectVerifier.verify(snapshot, dsp, isHrtfEnabled = false, isBitPerfectRequested = true)
        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
        assertTrue(result.failureReasons.any { it.contains("Hardware tone shaping") })
    }

    @Test
    fun `NEGATIVE DOMINANCE - Limiter active yields UNAVAILABLE`() {
        val snapshot = createBaseSnapshot()
        val dsp = createBaseDsp(isLimiterActive = true)
        val result = BitPerfectVerifier.verify(snapshot, dsp, isHrtfEnabled = false, isBitPerfectRequested = true)
        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
        assertTrue(result.failureReasons.any { it.contains("True-peak limiter is active") })
    }

    @Test
    fun `NEGATIVE DOMINANCE - Dither active yields UNAVAILABLE`() {
        val snapshot = createBaseSnapshot()
        val dsp = createBaseDsp(isDitherActive = true, dither = 0.5)
        val result = BitPerfectVerifier.verify(snapshot, dsp, isHrtfEnabled = false, isBitPerfectRequested = true)
        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
        assertTrue(result.failureReasons.any { it.contains("TPDF dither modification is active") })
    }

    @Test
    fun `NEGATIVE DOMINANCE - Volume not unity yields UNAVAILABLE`() {
        val snapshot = createBaseSnapshot()
        val dsp = createBaseDsp(volume = 0.8)
        val result = BitPerfectVerifier.verify(snapshot, dsp, isHrtfEnabled = false, isBitPerfectRequested = true)
        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
        assertTrue(result.failureReasons.any { it.contains("Software digital volume attenuation is active") })
    }

    @Test
    fun `NEGATIVE DOMINANCE - Preamp not unity yields UNAVAILABLE`() {
        val snapshot = createBaseSnapshot()
        val dsp = createBaseDsp(preamp = 3.0)
        val result = BitPerfectVerifier.verify(snapshot, dsp, isHrtfEnabled = false, isBitPerfectRequested = true)
        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
        assertTrue(result.failureReasons.any { it.contains("Preamp gain is active") })
    }

    @Test
    fun `NEGATIVE DOMINANCE - ReplayGain not unity yields UNAVAILABLE`() {
        val snapshot = createBaseSnapshot()
        val dsp = createBaseDsp(replayGain = 0.9)
        val result = BitPerfectVerifier.verify(snapshot, dsp, isHrtfEnabled = false, isBitPerfectRequested = true)
        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
        assertTrue(result.failureReasons.any { it.contains("ReplayGain modification is active") })
    }

    @Test
    fun `NEGATIVE DOMINANCE - Saturation active yields UNAVAILABLE`() {
        val snapshot = createBaseSnapshot()
        val dsp = createBaseDsp(isSaturationActive = true)
        val result = BitPerfectVerifier.verify(snapshot, dsp, isHrtfEnabled = false, isBitPerfectRequested = true)
        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
        assertTrue(result.failureReasons.any { it.contains("Tube/Tape saturation simulation is active") })
    }

    @Test
    fun `NEGATIVE DOMINANCE - Spatial HRTF active yields UNAVAILABLE`() {
        val snapshot = createBaseSnapshot()
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), isHrtfEnabled = true, isBitPerfectRequested = true)
        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
        assertTrue(result.failureReasons.any { it.contains("HRTF Spatial Audio processing is active") })
    }

    @Test
    fun `NEGATIVE DOMINANCE - Crossfeed active yields UNAVAILABLE`() {
        val snapshot = createBaseSnapshot()
        val dsp = createBaseDsp(isCrossfeedActive = true)
        val result = BitPerfectVerifier.verify(snapshot, dsp, isHrtfEnabled = false, isBitPerfectRequested = true)
        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
        assertTrue(result.failureReasons.any { it.contains("Meier crossfeed is active") })
    }

    @Test
    fun `NEGATIVE DOMINANCE - Balance not unity yields UNAVAILABLE`() {
        val snapshot = createBaseSnapshot()
        val dsp = createBaseDsp(isChannelBalanceActive = true)
        val result = BitPerfectVerifier.verify(snapshot, dsp, isHrtfEnabled = false, isBitPerfectRequested = true)
        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
        assertTrue(result.failureReasons.any { it.contains("Channel balance attenuation is active") })
    }

    @Test
    fun `NEGATIVE DOMINANCE - Channel mismatch yields UNAVAILABLE`() {
        val snapshot = createBaseSnapshot().copy(
            actualOutput = createFormat(44100, 16, 6, "PCM")
        )
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), isHrtfEnabled = false, isBitPerfectRequested = true)
        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
        assertTrue(result.failureReasons.any { it.contains("Channel count mismatch") })
    }

    @Test
    fun `NEGATIVE DOMINANCE - Sample rate mismatch yields UNAVAILABLE`() {
        val snapshot = createBaseSnapshot().copy(
            actualOutput = createFormat(48000, 16, 2, "PCM")
        )
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), isHrtfEnabled = false, isBitPerfectRequested = true)
        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
        assertTrue(result.failureReasons.any { it.contains("Sample rate mismatch") })
    }

    @Test
    fun `NEGATIVE DOMINANCE - Stale generation yields REQUESTED`() {
        val snapshot = createBaseSnapshot().copy(
            nativeStream = createBaseSnapshot().nativeStream?.copy(streamGeneration = 999L)
        )
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), isHrtfEnabled = false, isBitPerfectRequested = true)
        assertEquals(BitPerfectState.REQUESTED, result.state)
        assertTrue(result.failureReasons.any { it.contains("generation mismatch") })
    }

    @Test
    fun `NEGATIVE DOMINANCE - Fallback AudioTrack active yields UNAVAILABLE`() {
        val snapshot = createBaseSnapshot().copy(
            audioApi = AudioEvidence(AudioOutputApi.AUDIOTRACK, EvidenceSource.AUDIO_TRACK, Confidence.VERIFIED)
        )
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), isHrtfEnabled = false, isBitPerfectRequested = true)
        assertEquals(BitPerfectState.UNAVAILABLE, result.state)
        assertTrue(result.failureReasons.any { it.contains("Fallback AudioTrack sink is active") })
    }

    @Test
    fun `SEMANTICS - USB DAC verified yields END_TO_END_BITPERFECT tier`() {
        val snapshot = createBaseSnapshot()
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), isHrtfEnabled = false, isBitPerfectRequested = true)
        assertEquals(BitPerfectState.VERIFIED, result.state)
        assertEquals(BitPerfectTier.END_TO_END_BITPERFECT, result.tier)
    }

    @Test
    fun `SEMANTICS - Wired Headphones verified yields DIRECT_PATH_VERIFIED tier`() {
        val snapshot = createBaseSnapshot().copy(
            activeRoute = AudioEvidence(AudioOutputRouteType.WIRED_HEADPHONES, EvidenceSource.ANDROID_AUDIO_DEVICE, Confidence.VERIFIED)
        )
        val result = BitPerfectVerifier.verify(snapshot, createBaseDsp(), isHrtfEnabled = false, isBitPerfectRequested = true)
        assertEquals(BitPerfectState.VERIFIED, result.state)
        assertEquals(BitPerfectTier.DIRECT_PATH_VERIFIED, result.tier)
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
                confidence = Confidence.VERIFIED,
                streamGeneration = 1L
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
        dither: Double = 0.0,
        replayGain: Double = 1.0,
        isEqActive: Boolean = false,
        isBassBoostActive: Boolean = false,
        isTrebleActive: Boolean = false,
        isClarityActive: Boolean = false,
        isAirPresenceActive: Boolean = false,
        isSaturationActive: Boolean = false,
        isCrossfeedActive: Boolean = false,
        isChannelBalanceActive: Boolean = false,
        isStereoExpansionActive: Boolean = false,
        isSubBassMonoActive: Boolean = false,
        isInvertPhaseActive: Boolean = false,
        isLimiterActive: Boolean = false,
        isDitherActive: Boolean = false
    ): Audiophile64BitDspProcessor {
        val dsp = mock<Audiophile64BitDspProcessor>()
        whenever(dsp.isEnabled).thenReturn(isEnabled)
        whenever(dsp.isBitPerfectBypass).thenReturn(isBitPerfectBypass)
        whenever(dsp.dvcVolume).thenReturn(volume)
        whenever(dsp.preAmpGainDb).thenReturn(preamp)
        whenever(dsp.ditherStrength).thenReturn(dither)
        whenever(dsp.limiterEnabled).thenReturn(false)
        whenever(dsp.crossfeedLevel).thenReturn(0.0)
        whenever(dsp.replayGainMultiplier).thenReturn(replayGain)
        whenever(dsp.channelBalance).thenReturn(0.0)
        whenever(dsp.isEqActive).thenReturn(isEqActive)
        whenever(dsp.isBassBoostActive).thenReturn(isBassBoostActive)
        whenever(dsp.isTrebleActive).thenReturn(isTrebleActive)
        whenever(dsp.isClarityActive).thenReturn(isClarityActive)
        whenever(dsp.isAirPresenceActive).thenReturn(isAirPresenceActive)
        whenever(dsp.isSaturationActive).thenReturn(isSaturationActive)
        whenever(dsp.isCrossfeedActive).thenReturn(isCrossfeedActive)
        whenever(dsp.isChannelBalanceActive).thenReturn(isChannelBalanceActive)
        whenever(dsp.isStereoExpansionActive).thenReturn(isStereoExpansionActive)
        whenever(dsp.isSubBassMonoActive).thenReturn(isSubBassMonoActive)
        whenever(dsp.isInvertPhaseActive).thenReturn(isInvertPhaseActive)
        whenever(dsp.isLimiterActive).thenReturn(isLimiterActive)
        whenever(dsp.isDitherActive).thenReturn(isDitherActive)
        return dsp
    }
}

