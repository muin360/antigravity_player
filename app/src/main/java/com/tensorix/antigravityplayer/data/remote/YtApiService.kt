package com.tensorix.antigravityplayer.data.remote

import android.content.Context
import android.os.Environment
import android.util.Log
import com.tensorix.antigravityplayer.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Typed failure taxonomy for backend + download operations. */
enum class YtFailureCode {
    NETWORK_TIMEOUT,
    NETWORK_UNAVAILABLE,
    HTTP_CLIENT_ERROR,   // 4xx (bad request, not found, ...)
    HTTP_SERVER_ERROR,   // 5xx
    INVALID_RESPONSE,    // 2xx but unparseable / missing fields
    CANCELLED
}

/**
 * Result wrapper: callers can distinguish "no results" from "backend broken".
 */
sealed class YtResult<out T> {
    data class Success<T : Any>(val value: T) : YtResult<T>()
    data class Failure(val code: YtFailureCode, val userMessage: String) : YtResult<Nothing>()
}

/**
 * Native HTTP client for the YouTube extraction backend.
 *
 * Endpoint policy:
 *  - DEBUG builds default to the local development server (emulator loopback)
 *    and may use any user-configured http:// LAN URL.
 *  - RELEASE builds REQUIRE an https:// endpoint; insecure overrides are
 *    rejected outright and the production base URL from BuildConfig is used.
 */
class YtApiService(private val context: Context? = null) {

    companion object {
        private const val TAG = "YtApiService"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 15_000
        private const val DOWNLOAD_READ_TIMEOUT_MS = 60_000
        private const val MAX_QUERY_LENGTH = 200

        /** Validates a YouTube video id before it ever reaches the backend. */
        fun isValidVideoId(id: String): Boolean =
            id.matches(Regex("^[A-Za-z0-9_-]{11}$"))
    }

    // ------------------------------------------------------------------
    // Base URL resolution (dev/prod separated via BuildConfig)
    // ------------------------------------------------------------------

    private fun getBaseUrl(): String {
        val custom = context?.let { ctx ->
            runCatching {
                ctx.getSharedPreferences("yt_config", Context.MODE_PRIVATE)
                    .getString("server_url", null)
            }.getOrNull()
        }?.takeIf { it.isNotBlank() }

        if (custom != null) {
            val trimmed = custom.trim().trimEnd('/')
            val isSecure = trimmed.startsWith("https://")
            if (!isSecure && !BuildConfig.DEBUG) {
                Log.w(TAG, "Rejecting insecure custom backend URL in release build.")
                return BuildConfig.PROD_YT_BASE_URL.trimEnd('/')
            }
            return trimmed
        }
        return if (BuildConfig.DEBUG) {
            BuildConfig.DEV_YT_BASE_URL.trimEnd('/')
        } else {
            BuildConfig.PROD_YT_BASE_URL.trimEnd('/')
        }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    suspend fun searchTracks(query: String): YtResult<List<YtSearchResultItem>> =
        withContext(Dispatchers.IO) {
            val sanitized = query.trim().take(MAX_QUERY_LENGTH)
            if (sanitized.isEmpty()) {
                return@withContext YtResult.Failure(
                    YtFailureCode.INVALID_RESPONSE, "Search query is empty."
                )
            }
            val encodedQuery = java.net.URLEncoder.encode(sanitized, "UTF-8")
            val base = getBaseUrl()
            fetchJson("$base/api/search?q=$encodedQuery") { json ->
                val root = JSONObject(json)
                val array = root.optJSONArray("results")
                    ?: throw IllegalStateException("missing 'results'")
                buildList {
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        add(
                            YtSearchResultItem(
                                id = item.optString("id"),
                                title = item.optString("title", "Unknown Track"),
                                artist = item.optString("artist", "YouTube"),
                                durationSeconds = item.optLong("duration", 0L),
                                thumbnailUrl = item.optString("thumbnail", "")
                            )
                        )
                    }
                }
            }
        }

    suspend fun getStreamUrl(id: String): YtResult<YtStreamResponse> =
        withContext(Dispatchers.IO) {
            if (!isValidVideoId(id)) {
                return@withContext YtResult.Failure(
                    YtFailureCode.INVALID_RESPONSE, "Invalid video reference."
                )
            }
            val base = getBaseUrl()
            fetchJson("$base/api/stream?id=$id") { json ->
                val root = JSONObject(json)
                YtStreamResponse(
                    id = root.optString("id", id),
                    streamUrl = root.optString("streamUrl", ""),
                    title = root.optString("title", "Online Track"),
                    artist = root.optString("artist", "YouTube"),
                    durationSeconds = root.optLong("duration", 0L),
                    thumbnailUrl = root.optString("thumbnail", "")
                )
            }
        }

    /**
     * Downloads a YouTube track to device storage with scoped-storage-aware
     * directory fallbacks. Partial files are removed on failure.
     */
    suspend fun downloadTrackToDevice(
        context: Context,
        streamResponse: YtStreamResponse,
        onProgress: (Int) -> Unit = {}
    ): YtResult<String> = withContext(Dispatchers.IO) {
        var outputFile: File? = null
        var connection: java.net.HttpURLConnection? = null
        try {
            val streamUrl = streamResponse.streamUrl
            if (streamUrl.isBlank()) {
                return@withContext YtResult.Failure(
                    YtFailureCode.INVALID_RESPONSE, "No downloadable stream was provided."
                )
            }

            val targetDir = resolveTargetDir(context)
            val safeTitle = streamResponse.title
                .replace(Regex("[^\\p{L}\\p{Nd}\\s\\-_]"), "")
                .trim()
                .replace(Regex("\\s+"), "_")
                .take(60)
                .ifBlank { "Track_${streamResponse.id}" }
            outputFile = File(targetDir, "${safeTitle}_${streamResponse.id}.m4a")

            if (outputFile.exists() && outputFile.length() > 0) {
                onProgress(100)
                return@withContext YtResult.Success(outputFile.absolutePath)
            }

            val url = java.net.URL(streamUrl)
            connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = DOWNLOAD_CONNECT_TIMEOUT_MS
            connection.readTimeout = DOWNLOAD_READ_TIMEOUT_MS
            connection.requestMethod = "GET"

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                outputFile.delete()
                return@withContext classifyHttpFailure(responseCode)
            }

            val totalBytes = connection.contentLength.toLong()
            var downloadedBytes = 0L

            connection.inputStream.use { inputStream ->
                FileOutputStream(outputFile).use { fos ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        // Cooperative cancellation for long downloads.
                        kotlin.coroutines.coroutineContext.ensureActive()
                        fos.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0) {
                            onProgress(((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100))
                        }
                    }
                    fos.flush()
                }
            }

            onProgress(100)
            YtResult.Success(outputFile.absolutePath)
        } catch (e: IOException) {
            outputFile?.delete()
            mapIo(e)
        } finally {
            connection?.disconnect()
        }
    }

    private fun resolveTargetDir(context: Context): File {
        return runCatching {
            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val dir = File(musicDir, "AntigravityPlayer")
            if (!dir.exists() && !dir.mkdirs() && !dir.exists()) null else dir
        }.getOrNull()
            ?: context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: File(context.filesDir, "Music").also { if (!it.exists()) it.mkdirs() }
    }

    // ------------------------------------------------------------------
    // Transport internals
    // ------------------------------------------------------------------

    private inline fun <T : Any> fetchJson(
        urlString: String,
        parse: (String) -> T
    ): YtResult<T> {
        var connection: java.net.HttpURLConnection? = null
        return try {
            val url = java.net.URL(urlString)
            connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS

            when (connection.responseCode) {
                in 200..299 -> try {
                    YtResult.Success(parse(connection.inputStream.bufferedReader().use { it.readText() }))
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    YtResult.Failure(YtFailureCode.INVALID_RESPONSE, "The online service returned malformed data.")
                }
                else -> classifyHttpFailure(connection.responseCode)
            }
        } catch (e: Exception) {
            // Never swallow coroutine cancellation.
            if (e is CancellationException) throw e
            mapIo(e)
        } finally {
            connection?.disconnect()
        }
    }

    private fun classifyHttpFailure(code: Int): YtResult.Failure =
        if (code in 400..499) {
            YtResult.Failure(YtFailureCode.HTTP_CLIENT_ERROR, "Online service rejected the request (HTTP $code).")
        } else {
            YtResult.Failure(YtFailureCode.HTTP_SERVER_ERROR, "Online service error (HTTP $code). Try again later.")
        }

    private fun mapIo(e: Exception): YtResult.Failure = when (e) {
        is kotlinx.coroutines.CancellationException ->
            YtResult.Failure(YtFailureCode.CANCELLED, "Operation cancelled.")
        is SocketTimeoutException ->
            YtResult.Failure(YtFailureCode.NETWORK_TIMEOUT, "Connection timed out.")
        is UnknownHostException ->
            YtResult.Failure(YtFailureCode.NETWORK_UNAVAILABLE, "Cannot reach the online service.")
        is IOException ->
            YtResult.Failure(YtFailureCode.NETWORK_UNAVAILABLE, "Network error during transfer.")
        else -> {
            Log.e(TAG, "Unexpected YT operation failure: ${e.javaClass.simpleName}")
            YtResult.Failure(YtFailureCode.INVALID_RESPONSE, "Unexpected failure during the operation.")
        }
    }
}
