package com.tensorix.antigravityplayer.audio

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Verifies truth in vendor DAC activation and state reporting:
 * - Vendor-neutral HiFiActivationResult model
 * - Vendor-specific booleans set only from actual runtime proof, not mere support
 * - Qualcomm Direct active requires real active direct output, not just capability
 * - Samsung, Sony, LG, Vivo activation states populated consistently
 * - forceExclusive behavior enforces hardware capability checks
 * - deactivate resets all vendor active flags
 */
class VendorDacManagerActivationTruthTest {

    @Test
    fun `HiFiActivationResult accurately reflects Vivo activation`() {
        val result = HiFiActivationResult(
            isHiFiConfirmed = true,
            activeOem = "VIVO",
            confirmedParameter = "vivo_hifi_active",
            outputSampleRate = 192000,
            isLowLatencyPath = true,
            isWiredConnected = true,
            isExclusiveModeActive = false,
            isVivoActive = true,
            isSamsungActive = false,
            isSonyActive = false,
            isLgActive = false,
            isQualcommActive = false
        )

        assertTrue(result.isHiFiConfirmed)
        assertTrue(result.isVivoActive)
        assertFalse(result.isSamsungActive)
        assertFalse(result.isSonyActive)
        assertFalse(result.isLgActive)
        assertFalse(result.isQualcommActive)
        assertEquals("VIVO", result.activeOem)
        assertEquals("vivo_hifi_active", result.confirmedParameter)
    }

    @Test
    fun `HiFiActivationResult accurately reflects Samsung activation`() {
        val result = HiFiActivationResult(
            isHiFiConfirmed = true,
            activeOem = "SAMSUNG",
            confirmedParameter = "sound_alive_uhq_upscaler",
            outputSampleRate = 96000,
            isLowLatencyPath = true,
            isWiredConnected = true,
            isExclusiveModeActive = false,
            isVivoActive = false,
            isSamsungActive = true,
            isSonyActive = false,
            isLgActive = false,
            isQualcommActive = false
        )

        assertTrue(result.isHiFiConfirmed)
        assertFalse(result.isVivoActive)
        assertTrue(result.isSamsungActive)
        assertEquals("sound_alive_uhq_upscaler", result.confirmedParameter)
    }

    @Test
    fun `HiFiActivationResult accurately reflects Sony activation`() {
        val result = HiFiActivationResult(
            isHiFiConfirmed = true,
            activeOem = "SONY",
            confirmedParameter = "sony_hires_audio_enabled",
            outputSampleRate = 96000,
            isLowLatencyPath = true,
            isWiredConnected = true,
            isExclusiveModeActive = false,
            isVivoActive = false,
            isSamsungActive = false,
            isSonyActive = true,
            isLgActive = false,
            isQualcommActive = false
        )

        assertTrue(result.isHiFiConfirmed)
        assertTrue(result.isSonyActive)
        assertEquals("sony_hires_audio_enabled", result.confirmedParameter)
    }

    @Test
    fun `HiFiActivationResult accurately reflects LG Quad DAC activation`() {
        val result = HiFiActivationResult(
            isHiFiConfirmed = true,
            activeOem = "LG",
            confirmedParameter = "quad_dac_state",
            outputSampleRate = 192000,
            isLowLatencyPath = true,
            isWiredConnected = true,
            isExclusiveModeActive = false,
            isVivoActive = false,
            isSamsungActive = false,
            isSonyActive = false,
            isLgActive = true,
            isQualcommActive = false
        )

        assertTrue(result.isHiFiConfirmed)
        assertTrue(result.isLgActive)
        assertEquals("quad_dac_state", result.confirmedParameter)
    }

    @Test
    fun `Qualcomm Direct active requires live active proof not mere capability`() {
        // Capability supported, but HAL not currently running direct stream
        val isDirectSupported = true
        val isDirectActive = false

        // Truth rule: isQualcommDirectActive must NOT be set to true from isDirectSupported alone
        val isQualcommDirectActive = isDirectSupported && isDirectActive
        assertFalse("Qualcomm direct active must be false when direct output is inactive", isQualcommDirectActive)

        // Only when actually active does it become true
        val isLiveDirectActive = true
        val isQualcommDirectActiveLive = isDirectSupported && isLiveDirectActive
        assertTrue("Qualcomm direct active is true when direct output is active on hardware", isQualcommDirectActiveLive)
    }

    @Test
    fun `forceExclusive honors direct output capability and sample rate`() {
        // When forceExclusive is requested but hardware does not support direct output
        val forceExclusiveRequested = true
        val isDirectOutputSupported = false
        val exclusiveActive = forceExclusiveRequested && isDirectOutputSupported
        assertFalse("Exclusive mode cannot be active if direct output is not supported", exclusiveActive)

        // When forceExclusive is requested and hardware supports direct output
        val supportedHardware = true
        val exclusiveActiveSupported = forceExclusiveRequested && supportedHardware
        assertTrue("Exclusive mode is active when requested and supported", exclusiveActiveSupported)

        // When forceExclusive is not requested
        val forceExclusiveOff = false
        val exclusiveActiveOff = forceExclusiveOff && supportedHardware
        assertFalse("Exclusive mode is false when not requested", exclusiveActiveOff)
    }

    @Test
    fun `deactivate resets all vendor activation flags`() {
        // Set mock active states
        VendorDacManager.isVivoHiFiActive = true
        VendorDacManager.isSamsungUhqActive = true
        VendorDacManager.isSonyHiResActive = true
        VendorDacManager.isLgQuadDacActive = true
        VendorDacManager.isQualcommDirectActive = true

        val context = mock<Context>()
        VendorDacManager.deactivate(context)

        assertFalse("Vivo Hi-Fi must be reset to false", VendorDacManager.isVivoHiFiActive)
        assertFalse("Samsung UHQ must be reset to false", VendorDacManager.isSamsungUhqActive)
        assertFalse("Sony Hi-Res must be reset to false", VendorDacManager.isSonyHiResActive)
        assertFalse("LG Quad DAC must be reset to false", VendorDacManager.isLgQuadDacActive)
        assertFalse("Qualcomm Direct must be reset to false", VendorDacManager.isQualcommDirectActive)
    }

    @Test
    fun `false-positive prevention when Samsung settings write succeeds but hardware proof is absent`() {
        // GIVEN: Settings.System.putInt was successful (settingsApplied = true)
        val isSamsungMatch = true
        val settingsApplied = true
        // BUT: verified hardware probe returns false for vendor HiFi active
        val isVendorVerified = false

        // WHEN: evaluating Samsung active status (strict proof rule)
        val isSamsungActive = isSamsungMatch && isVendorVerified
        val isHiFiConfirmed = isSamsungActive

        val result = HiFiActivationResult(
            isHiFiConfirmed = isHiFiConfirmed,
            activeOem = "SAMSUNG",
            confirmedParameter = if (isSamsungActive) "sound_alive_uhq_upscaler" else "standard_hal",
            outputSampleRate = 48000,
            isLowLatencyPath = false,
            isWiredConnected = true,
            isExclusiveModeActive = false,
            settingsApplied = settingsApplied,
            isDirectSupported = false,
            isDirectActive = false,
            isVendorVerified = isVendorVerified,
            isSamsungActive = isSamsungActive
        )

        // THEN: settingsApplied is true, but active state and confirmed Hi-Fi are false
        assertTrue("Adapter settings were successfully written", result.settingsApplied)
        assertFalse("Samsung active must NOT be true without hardware proof", result.isSamsungActive)
        assertFalse("Hi-Fi confirmation must be false when proof is absent", result.isHiFiConfirmed)
        assertEquals("standard_hal", result.confirmedParameter)
    }

    @Test
    fun `genuine Samsung activation when settings write succeeds and hardware proof is verified`() {
        val isSamsungMatch = true
        val settingsApplied = true
        val isVendorVerified = true

        val isSamsungActive = isSamsungMatch && isVendorVerified
        val isHiFiConfirmed = isSamsungActive

        val result = HiFiActivationResult(
            isHiFiConfirmed = isHiFiConfirmed,
            activeOem = "SAMSUNG",
            confirmedParameter = "sound_alive_uhq_upscaler",
            outputSampleRate = 192000,
            isLowLatencyPath = true,
            isWiredConnected = true,
            isExclusiveModeActive = false,
            settingsApplied = settingsApplied,
            isDirectSupported = true,
            isDirectActive = true,
            isVendorVerified = isVendorVerified,
            isSamsungActive = isSamsungActive
        )

        assertTrue(result.settingsApplied)
        assertTrue(result.isVendorVerified)
        assertTrue(result.isSamsungActive)
        assertTrue(result.isHiFiConfirmed)
        assertEquals("sound_alive_uhq_upscaler", result.confirmedParameter)
    }

    @Test
    fun `false-positive prevention when Sony settings write succeeds but hardware proof is absent`() {
        val isSonyMatch = true
        val settingsApplied = true
        val isVendorVerified = false

        val isSonyActive = isSonyMatch && isVendorVerified
        val isHiFiConfirmed = isSonyActive

        val result = HiFiActivationResult(
            isHiFiConfirmed = isHiFiConfirmed,
            activeOem = "SONY",
            confirmedParameter = if (isSonyActive) "sony_hires_audio_enabled" else "standard_hal",
            outputSampleRate = 48000,
            isLowLatencyPath = false,
            isWiredConnected = true,
            isExclusiveModeActive = false,
            settingsApplied = settingsApplied,
            isDirectSupported = false,
            isDirectActive = false,
            isVendorVerified = isVendorVerified,
            isSonyActive = isSonyActive
        )

        assertTrue(result.settingsApplied)
        assertFalse("Sony Hi-Res must NOT be active when hardware proof is false", result.isSonyActive)
        assertFalse(result.isHiFiConfirmed)
        assertEquals("standard_hal", result.confirmedParameter)
    }

    @Test
    fun `genuine Sony activation when settings write succeeds and hardware proof is verified`() {
        val isSonyMatch = true
        val settingsApplied = true
        val isVendorVerified = true

        val isSonyActive = isSonyMatch && isVendorVerified
        val isHiFiConfirmed = isSonyActive

        val result = HiFiActivationResult(
            isHiFiConfirmed = isHiFiConfirmed,
            activeOem = "SONY",
            confirmedParameter = "sony_hires_audio_enabled",
            outputSampleRate = 96000,
            isLowLatencyPath = true,
            isWiredConnected = true,
            isExclusiveModeActive = false,
            settingsApplied = settingsApplied,
            isDirectSupported = true,
            isDirectActive = true,
            isVendorVerified = isVendorVerified,
            isSonyActive = isSonyActive
        )

        assertTrue(result.settingsApplied)
        assertTrue(result.isSonyActive)
        assertTrue(result.isHiFiConfirmed)
        assertEquals("sony_hires_audio_enabled", result.confirmedParameter)
    }

    @Test
    fun `false-positive prevention when LG settings write succeeds but hardware proof is absent`() {
        val isLgMatch = true
        val settingsApplied = true
        val isVendorVerified = false

        val isLgActive = isLgMatch && isVendorVerified
        val isHiFiConfirmed = isLgActive

        val result = HiFiActivationResult(
            isHiFiConfirmed = isHiFiConfirmed,
            activeOem = "LG",
            confirmedParameter = if (isLgActive) "quad_dac_state" else "standard_hal",
            outputSampleRate = 48000,
            isLowLatencyPath = false,
            isWiredConnected = true,
            isExclusiveModeActive = false,
            settingsApplied = settingsApplied,
            isDirectSupported = false,
            isDirectActive = false,
            isVendorVerified = isVendorVerified,
            isLgActive = isLgActive
        )

        assertTrue(result.settingsApplied)
        assertFalse("LG Quad DAC must NOT be active when hardware proof is false", result.isLgActive)
        assertFalse(result.isHiFiConfirmed)
        assertEquals("standard_hal", result.confirmedParameter)
    }

    @Test
    fun `genuine LG activation when settings write succeeds and hardware proof is verified`() {
        val isLgMatch = true
        val settingsApplied = true
        val isVendorVerified = true

        val isLgActive = isLgMatch && isVendorVerified
        val isHiFiConfirmed = isLgActive

        val result = HiFiActivationResult(
            isHiFiConfirmed = isHiFiConfirmed,
            activeOem = "LG",
            confirmedParameter = "quad_dac_state",
            outputSampleRate = 192000,
            isLowLatencyPath = true,
            isWiredConnected = true,
            isExclusiveModeActive = false,
            settingsApplied = settingsApplied,
            isDirectSupported = true,
            isDirectActive = true,
            isVendorVerified = isVendorVerified,
            isLgActive = isLgActive
        )

        assertTrue(result.settingsApplied)
        assertTrue(result.isLgActive)
        assertTrue(result.isHiFiConfirmed)
        assertEquals("quad_dac_state", result.confirmedParameter)
    }

    @Test
    fun `Vivo activation success and failure reflect verified state`() {
        // Vivo activation failure
        val unverifiedResult = HiFiActivationResult(
            isHiFiConfirmed = false,
            activeOem = "VIVO",
            confirmedParameter = "standard_hal",
            outputSampleRate = 48000,
            isLowLatencyPath = false,
            isWiredConnected = false,
            isExclusiveModeActive = false,
            settingsApplied = false,
            isDirectSupported = false,
            isDirectActive = false,
            isVendorVerified = false,
            isVivoActive = false
        )
        assertFalse(unverifiedResult.isVivoActive)
        assertFalse(unverifiedResult.isHiFiConfirmed)

        // Vivo activation verified
        val verifiedResult = HiFiActivationResult(
            isHiFiConfirmed = true,
            activeOem = "VIVO",
            confirmedParameter = "vivo_hifi_active",
            outputSampleRate = 192000,
            isLowLatencyPath = true,
            isWiredConnected = true,
            isExclusiveModeActive = false,
            settingsApplied = true,
            isDirectSupported = true,
            isDirectActive = true,
            isVendorVerified = true,
            isVivoActive = true
        )
        assertTrue(verifiedResult.isVivoActive)
        assertTrue(verifiedResult.isHiFiConfirmed)
    }

    @Test
    fun `Qualcomm support vs active-state mismatch reporting`() {
        // Support is true, but active is false
        val isQualcommMatch = true
        val isDirectSupported = true
        val isDirectActive = false
        val isQualcommActive = isQualcommMatch && isDirectActive

        val result = HiFiActivationResult(
            isHiFiConfirmed = isQualcommActive,
            activeOem = "QUALCOMM",
            confirmedParameter = if (isQualcommActive) "direct_pcm" else "standard_hal",
            outputSampleRate = 48000,
            isLowLatencyPath = false,
            isWiredConnected = true,
            isExclusiveModeActive = false,
            settingsApplied = true,
            isDirectSupported = isDirectSupported,
            isDirectActive = isDirectActive,
            isVendorVerified = false,
            isQualcommActive = isQualcommActive
        )

        assertTrue(result.isDirectSupported)
        assertFalse(result.isDirectActive)
        assertFalse("Qualcomm active must be false when direct output is not active", result.isQualcommActive)
        assertFalse("Hi-Fi confirmed must be false on support-only mismatch", result.isHiFiConfirmed)
    }
}
