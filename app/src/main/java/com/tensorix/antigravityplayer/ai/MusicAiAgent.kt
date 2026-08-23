package com.tensorix.antigravityplayer.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

sealed class AgentAction {
    data class PlaySong(val query: String) : AgentAction()
    data class PlayMood(val mood: String) : AgentAction()
    data class SearchYoutube(val query: String) : AgentAction()
    data class DownloadYoutube(val query: String) : AgentAction()
    data class SetEqualizerPreset(val presetName: String) : AgentAction()
    data class SetSleepTimer(val minutes: Int) : AgentAction()
    data class PlaybackControl(val command: String) : AgentAction() // "pause","next","previous","shuffle"
    data class ChatReply(val message: String) : AgentAction()
}

/** Structured failure taxonomy surfaced to the UI (Phase 17.2). */
enum class AiFailureCode {
    AUTH_FAILED,
    RATE_LIMITED,
    SERVER_ERROR,
    NETWORK_TIMEOUT,
    NETWORK_UNAVAILABLE,
    INVALID_RESPONSE,
    PARSE_ERROR
}

/**
 * Result of one assistant turn: either an executable action or a typed,
 * user-safe failure. Network failures are NEVER collapsed into null.
 */
sealed class AiOutcome {
    data class Success(val action: AgentAction) : AiOutcome()
    data class Failure(val code: AiFailureCode, val userMessage: String) : AiOutcome()
}

/**
 * Universal agentic engine supporting Gemini, OpenAI, Claude and Groq.
 *
 * Security & reliability contract:
 *  - Credentials are sent in provider-specific auth HEADERS, never URLs.
 *  - Every request carries connect/read/write/call timeouts.
 *  - Cancelling the calling scope aborts the in-flight HTTP call.
 *  - Errors surface as [AiOutcome.Failure] with human-safe messages;
 *    raw responses and keys are never logged.
 */
class MusicAiAgent(private val keyManager: AiKeyManager) {

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    private val httpClient: OkHttpClient by lazy { defaultHttpClient() }

    // ------------------------------------------------------------------
    // Public entry point
    // ------------------------------------------------------------------

    suspend fun processUserPrompt(prompt: String): AiOutcome = withContext(Dispatchers.IO) {
        val provider = keyManager.selectedProvider.value
        val apiKey = keyManager.getApiKey(provider)
        val model = keyManager.selectedModel.value

        val lowerPrompt = prompt.lowercase().trim()

        // Rule-based instant pattern matching for offline control.
        run {
            when {
                lowerPrompt == "pause" || lowerPrompt == "stop" || lowerPrompt == "play" ||
                    lowerPrompt == "resume" ->
                    return@withContext AiOutcome.Success(AgentAction.PlaybackControl(lowerPrompt))
                lowerPrompt == "next" || lowerPrompt == "skip" ->
                    return@withContext AiOutcome.Success(AgentAction.PlaybackControl("next"))
                lowerPrompt == "previous" || lowerPrompt == "prev" || lowerPrompt == "back" ->
                    return@withContext AiOutcome.Success(AgentAction.PlaybackControl("previous"))
                lowerPrompt.startsWith("shuffle") ->
                    return@withContext AiOutcome.Success(AgentAction.PlaybackControl("shuffle"))
                lowerPrompt.contains("sleep timer") ||
                    lowerPrompt.matches(Regex(".*timer\\s*\\d+.*")) -> {
                    val digits = Regex("\\d+").find(lowerPrompt)?.value?.toIntOrNull() ?: 30
                    return@withContext AiOutcome.Success(AgentAction.SetSleepTimer(digits))
                }
                lowerPrompt.startsWith("download ") ||
                    (lowerPrompt.startsWith("save ") && lowerPrompt.contains("youtube")) -> {
                    val query = lowerPrompt
                        .removePrefix("download ")
                        .removePrefix("save ")
                        .replace("from youtube", "")
                        .replace("youtube", "")
                        .trim()
                    return@withContext AiOutcome.Success(AgentAction.DownloadYoutube(query))
                }
                lowerPrompt.startsWith("play rock") || lowerPrompt.contains("rock music") ->
                    return@withContext AiOutcome.Success(AgentAction.PlayMood("rock"))
                lowerPrompt.startsWith("play sad") || lowerPrompt.contains("sad songs") ->
                    return@withContext AiOutcome.Success(AgentAction.PlayMood("sad"))
                lowerPrompt.startsWith("play chill") || lowerPrompt.contains("chill music") ->
                    return@withContext AiOutcome.Success(AgentAction.PlayMood("chill"))
                lowerPrompt.startsWith("play workout") || lowerPrompt.contains("energetic") ->
                    return@withContext AiOutcome.Success(AgentAction.PlayMood("workout"))
                lowerPrompt.startsWith("bass boost") || lowerPrompt.contains("boost bass") ->
                    return@withContext AiOutcome.Success(AgentAction.SetEqualizerPreset("Bass Boost"))
                lowerPrompt.contains("flat eq") || lowerPrompt.contains("reset equalizer") ->
                    return@withContext AiOutcome.Success(AgentAction.SetEqualizerPreset("Flat"))
            }
        }

        if (apiKey.isBlank()) {
            // No credential configured: deterministic local fallbacks still work.
            return@withContext fallbackWithoutLlm(lowerPrompt, provider)
        }

        val llmResult = when (provider) {
            AiProvider.GEMINI -> callGeminiApi(prompt, apiKey, model)
            AiProvider.OPENAI -> callOpenAiCompatible(
                url = "https://api.openai.com/v1/chat/completions",
                providerLabel = "OpenAI",
                apiKey = apiKey,
                model = model,
                prompt = prompt
            )
            AiProvider.GROQ -> callOpenAiCompatible(
                url = "https://api.groq.com/openai/v1/chat/completions",
                providerLabel = "Groq",
                apiKey = apiKey,
                model = model,
                prompt = prompt
            )
            AiProvider.CLAUDE -> callClaudeApi(prompt, apiKey, model)
        }

        when (llmResult) {
            is LlmResult.Ok ->
                parseAgentResponse(llmResult.body, prompt)
            is LlmResult.Failed ->
                AiOutcome.Failure(llmResult.code, llmResult.userMessage)
        }
    }

    private fun fallbackWithoutLlm(lowerPrompt: String, provider: AiProvider): AiOutcome {
        return when {
            lowerPrompt.startsWith("play ") ->
                AiOutcome.Success(AgentAction.PlaySong(lowerPrompt.removePrefix("play ").trim()))
            lowerPrompt.startsWith("search ") ->
                AiOutcome.Success(AgentAction.SearchYoutube(lowerPrompt.removePrefix("search ").trim()))
            lowerPrompt.startsWith("download ") ->
                AiOutcome.Success(AgentAction.DownloadYoutube(lowerPrompt.removePrefix("download ").trim()))
            else -> AiOutcome.Success(
                AgentAction.ChatReply(
                    "Offline mode: basic commands work right now. Add your ${provider.name} API key in Settings to unlock full natural-language control."
                )
            )
        }
    }

    // ------------------------------------------------------------------
    // Provider transports (all header-authenticated + timeout-bounded)
    // ------------------------------------------------------------------

    private sealed class LlmResult {
        data class Ok(val body: String) : LlmResult()
        data class Failed(val code: AiFailureCode, val userMessage: String) : LlmResult()
    }

    private val systemPrompt = """
        You are Antigravity Music AI Assistant. Respond ONLY in JSON format with fields:
        action: one of 'PLAY_SONG', 'PLAY_MOOD', 'SEARCH_YOUTUBE', 'DOWNLOAD_YOUTUBE', 'SET_EQ', 'SET_TIMER', 'PLAYBACK_CONTROL', 'CHAT'
        target: the song name/query/preset name/minutes/control command
        replyMessage: short conversational text for the user
    """.trimIndent()

    /** Blocking POST executed on IO; cancelling the caller cancels the call. */
    private suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        body: JSONObject,
        providerLabel: String
    ): LlmResult = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
        headers.forEach { (name, value) -> requestBuilder.header(name, value) }
        val call = httpClient.newCall(requestBuilder.build())

        // Abort the socket when this coroutine's job completes/cancels.
        coroutineContext[Job]?.invokeOnCompletion { call.cancel() }

        try {
            call.execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val text = response.body?.string()
                        if (text.isNullOrBlank()) {
                            LlmResult.Failed(AiFailureCode.INVALID_RESPONSE, "$providerLabel returned an empty response.")
                        } else {
                            LlmResult.Ok(text)
                        }
                    }
                    response.code == 401 || response.code == 403 ->
                        LlmResult.Failed(AiFailureCode.AUTH_FAILED, "$providerLabel rejected the API key. Check it in Settings.")
                    response.code == 429 ->
                        LlmResult.Failed(AiFailureCode.RATE_LIMITED, "$providerLabel rate limit reached. Try again shortly.")
                    response.code >= 500 ->
                        LlmResult.Failed(AiFailureCode.SERVER_ERROR, "$providerLabel server error (HTTP ${response.code}).")
                    else ->
                        LlmResult.Failed(AiFailureCode.INVALID_RESPONSE, "Unexpected HTTP ${response.code} from $providerLabel.")
                }
            }
        } catch (e: IOException) {
            // Preserve structured-concurrency semantics on cancellation.
            coroutineContext.ensureActive()
            when {
                e is SocketTimeoutException ->
                    LlmResult.Failed(AiFailureCode.NETWORK_TIMEOUT, "Connection to $providerLabel timed out.")
                e is UnknownHostException ->
                    LlmResult.Failed(AiFailureCode.NETWORK_UNAVAILABLE, "Cannot reach $providerLabel. Check network connectivity.")
                else ->
                    LlmResult.Failed(AiFailureCode.NETWORK_UNAVAILABLE, "Network error while contacting $providerLabel.")
            }
        }
    }

    private suspend fun callGeminiApi(prompt: String, apiKey: String, model: String) =
        postJson(
            url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
            headers = mapOf("x-goog-api-key" to apiKey),   // secret stays OUT of the URL
            body = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("text", "$systemPrompt\n\nUser query: $prompt")
                    }))
                }))
            },
            providerLabel = "Gemini"
        )

    private suspend fun callClaudeApi(prompt: String, apiKey: String, model: String) =
        postJson(
            url = "https://api.anthropic.com/v1/messages",
            headers = mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01"
            ),
            body = JSONObject().apply {
                put("model", model)
                put("max_tokens", 300)
                put("system", systemPrompt)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                }))
            },
            providerLabel = "Claude"
        )

    private suspend fun callOpenAiCompatible(
        url: String,
        providerLabel: String,
        apiKey: String,
        model: String,
        prompt: String
    ) = postJson(
        url = url,
        headers = mapOf("Authorization" to "Bearer $apiKey"),
        body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 300)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        },
        providerLabel = providerLabel
    )

    // ------------------------------------------------------------------
    // Response parsing (pure function; unit-testable)
    // ------------------------------------------------------------------

    internal fun parseAgentResponse(jsonText: String, originalPrompt: String): AiOutcome {
        return try {
            val firstBrace = jsonText.indexOf('{')
            val lastBrace = jsonText.lastIndexOf('}')
            if (firstBrace != -1 && lastBrace > firstBrace) {
                val cleanJson = jsonText.substring(firstBrace, lastBrace + 1)
                val root = JSONObject(cleanJson)
                val action = root.optString("action", "CHAT").uppercase()
                val target = root.optString("target", "")
                val replyMessage = root.optString("replyMessage", "Action executed.")

                val agentAction: AgentAction = when (action) {
                    "PLAY_SONG" -> AgentAction.PlaySong(target.ifBlank { originalPrompt })
                    "PLAY_MOOD" -> AgentAction.PlayMood(target.ifBlank { "chill" })
                    "SEARCH_YOUTUBE" -> AgentAction.SearchYoutube(target.ifBlank { originalPrompt })
                    "DOWNLOAD_YOUTUBE" -> AgentAction.DownloadYoutube(target.ifBlank { originalPrompt })
                    "SET_EQ", "SET_EQUALIZER" -> AgentAction.SetEqualizerPreset(target.ifBlank { "Flat" })
                    "SET_TIMER", "SET_SLEEP_TIMER" ->
                        AgentAction.SetSleepTimer(target.toIntOrNull() ?: 30)
                    "PLAYBACK_CONTROL" -> AgentAction.PlaybackControl(target.ifBlank { "play" })
                    else -> AgentAction.ChatReply(replyMessage)
                }
                AiOutcome.Success(agentAction)
            } else {
                AiOutcome.Failure(
                    AiFailureCode.PARSE_ERROR,
                    "The assistant replied in an unexpected format."
                )
            }
        } catch (e: Exception) {
            AiOutcome.Failure(AiFailureCode.PARSE_ERROR, "Could not interpret the assistant reply.")
        }
    }
}
