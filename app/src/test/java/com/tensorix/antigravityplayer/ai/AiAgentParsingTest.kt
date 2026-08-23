package com.tensorix.antigravityplayer.ai

import com.tensorix.antigravityplayer.data.remote.YtApiService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Pure-logic tests for the AI agent response contract and YT id validation.
 * No Android framework classes are exercised here; AiKeyManager is mocked so
 * its Keystore-backed constructor never runs.
 */
class AiAgentParsingTest {

    private val keyManager: AiKeyManager = mock()
    private val agent = MusicAiAgent(keyManager)

    @Test
    fun `parses plain JSON action`() {
        val outcome = agent.parseAgentResponse(
            """{"action":"PLAY_SONG","target":"Bohemian Rhapsody","replyMessage":"ok"}""",
            originalPrompt = "play bohemian rhapsody"
        )
        assertTrue(outcome is AiOutcome.Success)
        val action = (outcome as AiOutcome.Success).action
        assertEquals(AgentAction.PlaySong("Bohemian Rhapsody"), action)
    }

    @Test
    fun `parses json embedded in prose fences`() {
        val text = "Here you go:\n```json\n{\"action\":\"SET_TIMER\",\"target\":\"15\"}\n```"
        val outcome = agent.parseAgentResponse(text, originalPrompt = "set timer")
        assertTrue(outcome is AiOutcome.Success)
        assertEquals(AgentAction.SetSleepTimer(15), (outcome as AiOutcome.Success).action)
    }

    @Test
    fun `maps SET_EQ alias and default preset`() {
        val outcome = agent.parseAgentResponse(
            """{"action":"SET_EQUALIZER","target":""}""",
            originalPrompt = "reset equalizer"
        )
        assertEquals(AgentAction.SetEqualizerPreset("Flat"), (outcome as AiOutcome.Success).action)
    }

    @Test
    fun `maps PLAYBACK_CONTROL passthrough`() {
        val outcome = agent.parseAgentResponse(
            """{"action":"PLAYBACK_CONTROL","target":"next"}""",
            originalPrompt = "skip"
        )
        assertEquals(AgentAction.PlaybackControl("next"), (outcome as AiOutcome.Success).action)
    }

    @Test
    fun `non-json reply yields PARSE_ERROR failure not a fabricated action`() {
        val outcome = agent.parseAgentResponse("Sorry, I cannot help with that.", originalPrompt = "?")
        assertTrue(outcome is AiOutcome.Failure)
        assertEquals(AiFailureCode.PARSE_ERROR, (outcome as AiOutcome.Failure).code)
    }

    @Test
    fun `malformed json yields PARSE_ERROR failure`() {
        val outcome = agent.parseAgentResponse("{\"action\":", originalPrompt = "?")
        assertTrue(outcome is AiOutcome.Failure)
    }
}

class YtVideoIdValidationTest {

    @Test
    fun `accepts canonical 11-char youtube ids`() {
        assertTrue(YtApiService.isValidVideoId("dQw4w9WgXcQ"))
        assertTrue(YtApiService.isValidVideoId("aBcD_e-F_gH"))
    }

    @Test
    fun `rejects injection payloads and malformed ids`() {
        listOf(
            "",
            "../../etc/passwd",
            "dQw4w9WgXcQ&rm_host=evil",
            "<script>alert(1)</script>",
            "short",
            "way-too-long-video-id-value"
        ).forEach { bad ->
            assertFalse("should reject: $bad", YtApiService.isValidVideoId(bad))
        }
    }
}
