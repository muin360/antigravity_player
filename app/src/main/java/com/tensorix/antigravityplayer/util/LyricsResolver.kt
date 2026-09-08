package com.tensorix.antigravityplayer.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.tensorix.antigravityplayer.data.Song
import java.io.File

/**
 * Robust, scoped-storage-safe lyrics sidecar (.lrc) resolver.
 * Handles:
 * - Direct filesystem paths (/storage/.../song.mp3 or C:\... on tests)
 * - file:// URIs
 * - MediaStore content:// URIs with sibling .lrc resolution via _data and RELATIVE_PATH
 * - Gracefully enforces 2MB upper bound and non-empty checks to prevent OOM
 */
object LyricsResolver {
    const val MAX_LRC_SIZE_BYTES = 2L * 1024 * 1024 // 2MB

    internal var uriParser: (String) -> Uri? = {
        runCatching { Uri.parse(it) }.getOrNull()
    }

    fun resolveLrc(context: Context?, song: Song): List<LrcLine> {
        val path = song.filePath
        if (path.isBlank()) return emptyList()

        return runCatching {
            val fileCandidate = resolveAudioFile(context, path)
            if (fileCandidate != null) {
                val lrcFile = File(fileCandidate.parentFile, fileCandidate.nameWithoutExtension + ".lrc")
                if (isValidLrcFile(lrcFile)) {
                    return@runCatching LrcParser.parse(lrcFile.readText())
                }
            }

            // If not found via direct file candidate, try resolving via content URI / MediaStore
            if (path.startsWith("content://") && context != null) {
                val lrcContent = resolveLrcFromContentUri(context, path)
                if (!lrcContent.isNullOrBlank()) {
                    return@runCatching LrcParser.parse(lrcContent)
                }
            }

            emptyList()
        }.getOrDefault(emptyList())
    }

    fun isValidLrcFile(file: File?): Boolean {
        if (file == null) return false
        return file.exists() && file.isFile && file.canRead() && file.length() in 1..MAX_LRC_SIZE_BYTES
    }

    internal fun resolveAudioFile(context: Context?, path: String): File? {
        if (!path.startsWith("content://")) {
            return try {
                if (path.startsWith("file:")) File(java.net.URI(path)) else File(path)
            } catch (_: Throwable) {
                null
            }
        }

        if (path.startsWith("content://") && context != null) {
            val uri = uriParser(path)
            if (uri != null) {
                val rawPath = queryMediaStoreData(context.contentResolver, uri)
                if (!rawPath.isNullOrBlank()) {
                    val file = File(rawPath)
                    if (file.exists()) {
                        return file
                    }
                }
            }
        }

        return null
    }

    private fun queryMediaStoreData(contentResolver: ContentResolver, uri: Uri): String? {
        return runCatching {
            val projection = arrayOf(MediaStore.Audio.Media.DATA)
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        }.getOrNull()
    }

    private fun resolveLrcFromContentUri(context: Context, contentUriStr: String): String? {
        val uri = uriParser(contentUriStr) ?: return null
        // 1. Try querying _data directly
        val rawPath = queryMediaStoreData(context.contentResolver, uri)
        if (!rawPath.isNullOrBlank()) {
            val audioFile = File(rawPath)
            val lrcFile = File(audioFile.parentFile, audioFile.nameWithoutExtension + ".lrc")
            if (isValidLrcFile(lrcFile)) {
                return lrcFile.readText()
            }
        }

        // 2. On Android 10+ (API 29+), try resolving via RELATIVE_PATH + DISPLAY_NAME
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH
            )
            val metadata = runCatching {
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                        val pathIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                        val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                        val relPath = if (pathIdx >= 0) cursor.getString(pathIdx) else null
                        if (name != null && relPath != null) Pair(name, relPath) else null
                    } else null
                }
            }.getOrNull()

            if (metadata != null) {
                val (displayName, relativePath) = metadata
                val lrcName = displayName.substringBeforeLast('.') + ".lrc"
                val commonStorageRoots = listOf(
                    android.os.Environment.getExternalStorageDirectory(),
                    File("/storage/emulated/0"),
                    File("/sdcard")
                )
                for (root in commonStorageRoots) {
                    val candidate = File(File(root, relativePath), lrcName)
                    if (isValidLrcFile(candidate)) {
                        return candidate.readText()
                    }
                }
            }
        }

        return null
    }
}
