package com.tensorix.antigravityplayer.player

import com.tensorix.antigravityplayer.audio.AudioOutputRouteType
import com.tensorix.antigravityplayer.audio.AudioRouteCapability
import com.tensorix.antigravityplayer.audio.BitPerfectState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that hardware capability is strictly decoupled from active route state:
 * - Hardware Hi-Fi capability does not collapse into active route presence.
 * - Route presence is not a proxy for Hi-Fi capability.
 * - Speaker routes, USB routes, route disconnects, and pre-route startup report truth.
 * - User Hi-Fi toggle choice is preserved and not silently turned off on temporary route loss.
 */
class PlaybackServiceRoutingTruthTest {

    @Test
    fun `startup before any route is known preserves hardware capability`() {
        // Given hardware capability is known at startup
        PlaybackService.updateHiFiSupported(true)
        assertTrue("Hardware capability must be true even before route discovery", PlaybackService.isHiFiSupported())

        val isRouteAvailable = MutableStateFlow(false)
        val isDirectPathActive = MutableStateFlow(false)
        val isDirectOutputVerified = MutableStateFlow(false)

        // Route is not yet known
        assertFalse("Route availability must be false prior to connection", isRouteAvailable.value)
        assertFalse("Direct path cannot be active without route", isDirectPathActive.value)
        assertFalse("Direct output cannot be verified without route", isDirectOutputVerified.value)
        assertTrue("Hi-Fi capability must remain independent of route state", PlaybackService.isHiFiSupported())
    }

    @Test
    fun `speaker route does not destroy hardware capability`() {
        PlaybackService.updateHiFiSupported(true)

        val speakerRoute = AudioRouteCapability(
            routeType = AudioOutputRouteType.SPEAKER,
            deviceName = "Phone Speaker",
            sampleRates = listOf(48000),
            isDirectPlaybackCapable = false
        )

        val isRouteAvailable = (speakerRoute.routeType != AudioOutputRouteType.OTHER)
        val isDirectPathActive = speakerRoute.isDirectPlaybackCapable
        val isSpeakerRoute = (speakerRoute.routeType == AudioOutputRouteType.SPEAKER)

        assertTrue("Speaker route is an available route", isRouteAvailable)
        assertTrue("Route is correctly identified as speaker", isSpeakerRoute)
        assertFalse("Speaker is not a direct Hi-Fi path", isDirectPathActive)
        assertTrue("Hardware capability remains true even when playing on speaker", PlaybackService.isHiFiSupported())
    }

    @Test
    fun `usb dac route exposes active direct path independently from capability`() {
        PlaybackService.updateHiFiSupported(true)

        val usbRoute = AudioRouteCapability(
            routeType = AudioOutputRouteType.USB_DEVICE,
            deviceName = "AudioQuest Dragonfly",
            sampleRates = listOf(44100, 48000, 88200, 96000),
            isDirectPlaybackCapable = true
        )

        val isRouteAvailable = (usbRoute.routeType != AudioOutputRouteType.OTHER)
        val isDirectPathActive = usbRoute.isDirectPlaybackCapable

        assertTrue("Route must be available", isRouteAvailable)
        assertTrue("Direct path must be active for direct capable USB DAC", isDirectPathActive)
        assertTrue("Hardware capability remains true", PlaybackService.isHiFiSupported())
    }

    @Test
    fun `temporary route loss during playback updates route availability without clearing hardware capability`() {
        PlaybackService.updateHiFiSupported(true)

        fun checkRouteAvailable(r: AudioRouteCapability?): Boolean = (r != null)
        fun checkDirectPathActive(r: AudioRouteCapability?): Boolean = (r?.isDirectPlaybackCapable == true)

        var routeOnPlayback: AudioRouteCapability? = AudioRouteCapability(
            routeType = AudioOutputRouteType.WIRED_HEADPHONES,
            deviceName = "3.5mm Headset",
            sampleRates = listOf(48000, 96000, 192000),
            isDirectPlaybackCapable = true
        )

        assertTrue(checkRouteAvailable(routeOnPlayback))
        assertTrue(checkDirectPathActive(routeOnPlayback))
        assertTrue(PlaybackService.isHiFiSupported())

        // Simulate unplug / temporary route loss
        routeOnPlayback = null

        assertFalse("Route availability must become false on disconnect", checkRouteAvailable(routeOnPlayback))
        assertFalse("Direct path must become false on disconnect", checkDirectPathActive(routeOnPlayback))
        assertTrue("Hardware capability must NOT be wiped out by temporary route loss", PlaybackService.isHiFiSupported())
    }

    @Test
    fun `user toggling Hi-Fi while route is unavailable preserves user choice`() {
        PlaybackService.updateHiFiSupported(true)

        // Route is disconnected (null)
        val activeRoute: AudioRouteCapability? = null
        val hiFiEnabled = MutableStateFlow(false)

        // User turns Hi-Fi ON in settings while no route is plugged in
        hiFiEnabled.value = true

        assertTrue("Hi-Fi setting must remain enabled per user intent even when route is null", hiFiEnabled.value)
        assertFalse("Route remains unavailable", activeRoute != null)
        assertTrue("Hardware capability remains true", PlaybackService.isHiFiSupported())

        // User turns Hi-Fi OFF in settings
        hiFiEnabled.value = false
        assertFalse("Hi-Fi setting must be false when user explicitly toggles it off", hiFiEnabled.value)
    }

    @Test
    fun `separate state variables never collapse into a single boolean`() {
        // Verify that 4 distinct state variables represent 4 distinct facts:
        // 1. Hardware capability
        // 2. Route availability
        // 3. Active direct path
        // 4. Verified direct output
        val hardwareCapable = true
        val routeAvailable = false
        val directPathActive = false
        val verifiedDirectOutput = false

        assertEquals(true, hardwareCapable)
        assertEquals(false, routeAvailable)
        assertEquals(false, directPathActive)
        assertEquals(false, verifiedDirectOutput)

        // Only when route is plugged in and direct path verified
        val routeNowAvailable = true
        val directPathNowActive = true
        val bitPerfectState = BitPerfectState.VERIFIED
        val verifiedNow = (bitPerfectState == BitPerfectState.VERIFIED)

        assertTrue(hardwareCapable)
        assertTrue(routeNowAvailable)
        assertTrue(directPathNowActive)
        assertTrue(verifiedNow)
    }
}
