package com.tensorix.antigravityplayer.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Hi-Res device storage scanner using MediaStore API + MediaMetadataRetriever.
 * Bulletproofed for all Android API versions (SDK 26-34+) & custom OEM ROMs (MIUI, Samsung, OneUI).
 * Extracts bitrate, sample rate, format, and file size for audiophile metadata display.
 */
class LibraryScanner(private val context: Context, private val songDao: SongDao) {

    suspend fun scanLocalLibrary(): List<Song> = withContext(Dispatchers.IO) {
        val scanStartTimestamp = System.currentTimeMillis()
        val scannedSongs = mutableListOf<Song>()

        val existingSongs = songDao.getAllLocalSongsList()
        val existingByPath = existingSongs.associateBy { it.filePath }
        val existingByMediaId = mutableMapOf<Long, Song>()
        val existingByFileName = mutableMapOf<String, Song>()
        val existingByCanonical = mutableMapOf<String, Song>()
        val claimedSongIds = mutableSetOf<Long>()

        for (song in existingSongs) {
            if (song.filePath.startsWith("content://")) {
                val extractedId = song.filePath.substringAfterLast('/').toLongOrNull()
                if (extractedId != null) {
                    existingByMediaId[extractedId] = song
                }
            }
            val fileName = song.filePath.substringAfterLast('/')
            if (fileName.isNotBlank()) {
                existingByFileName[fileName.lowercase()] = song
            }
            val canonicalKey = "${song.title.trim().lowercase()}|${song.artist.trim().lowercase()}|${song.durationMs / 1000}"
            existingByCanonical[canonicalKey] = song
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATE_ADDED
        )

        val selection = "(${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.MIME_TYPE} LIKE 'audio/%') AND ${MediaStore.Audio.Media.DURATION} >= 3000 AND (${MediaStore.Audio.Media.IS_RINGTONE} = 0 AND ${MediaStore.Audio.Media.IS_NOTIFICATION} = 0 AND ${MediaStore.Audio.Media.IS_ALARM} = 0)"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        var querySucceeded = false
        val cursor = try {
            val c = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder
            )
            querySucceeded = (c != null)
            c
        } catch (e: Exception) {
            android.util.Log.w("Antigravity", "Failure in " + javaClass.simpleName, e)
            null
        }

        val artworkBaseUri = Uri.parse("content://media/external/audio/albumart")

        cursor?.use { c ->
            val idCol = c.getColumnIndex(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndex(MediaStore.Audio.Media.ALBUM)
            val durationCol = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
            val dataCol = c.getColumnIndex(MediaStore.Audio.Media.DATA)
            val albumIdCol = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
            val sizeCol = c.getColumnIndex(MediaStore.Audio.Media.SIZE)
            val mimeCol = c.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
            val dateAddedCol = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)

            while (c.moveToNext()) {
                val mediaId = if (idCol >= 0) c.getLong(idCol) else System.currentTimeMillis()
                val title = if (titleCol >= 0) c.getString(titleCol) ?: "Unknown Title" else "Unknown Title"
                val artist = if (artistCol >= 0) c.getString(artistCol) ?: "<Unknown>" else "<Unknown>"
                val album = if (albumCol >= 0) c.getString(albumCol) ?: "Unknown Album" else "Unknown Album"
                val duration = if (durationCol >= 0) c.getLong(durationCol) else 0L
                val rawFilePath = if (dataCol >= 0) c.getString(dataCol) ?: "" else ""
                val albumId = if (albumIdCol >= 0) c.getLong(albumIdCol) else -1L
                val fileSize = if (sizeCol >= 0) c.getLong(sizeCol) else 0L
                val mimeType = if (mimeCol >= 0) c.getString(mimeCol) ?: "" else ""
                // DATE_ADDED is in SECONDS; Song expects millis. Fixes the
                // previously-meaningless DATE_ADDED sort.
                val dateAddedMs = if (dateAddedCol >= 0) c.getLong(dateAddedCol) * 1000L else 0L

                val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId).toString()
                val playUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentUri
                } else {
                    if (rawFilePath.isNotBlank()) rawFilePath else contentUri
                }

                if (playUri.isBlank()) continue

                val albumArtUri = if (albumId >= 0) {
                    ContentUris.withAppendedId(artworkBaseUri, albumId).toString()
                } else null

                var bitrate = 0
                var sampleRate = 0
                
                val format = detectAudioFormat(playUri, rawFilePath, mimeType)

                var existing: Song? = existingByPath[playUri] ?: if (rawFilePath.isNotBlank()) existingByPath[rawFilePath] else null
                if (existing == null && mediaId > 0) {
                    existing = existingByMediaId[mediaId]
                }
                if (existing == null && rawFilePath.isNotBlank()) {
                    val fileName = rawFilePath.substringAfterLast('/').lowercase()
                    val candidate = existingByFileName[fileName]
                    if (candidate != null && Math.abs(candidate.durationMs - duration) <= 2000L) {
                        existing = candidate
                    }
                }
                if (existing == null) {
                    val canonicalKey = "${title.trim().lowercase()}|${artist.trim().lowercase()}|${duration / 1000}"
                    existing = existingByCanonical[canonicalKey]
                }
                if (existing != null && claimedSongIds.contains(existing.id)) {
                    existing = null
                }
                if (existing != null) {
                    claimedSongIds.add(existing.id)
                }

                val isFav = existing?.isFavorite ?: false
                
                if (existing != null && existing.bitrate > 0 && existing.sampleRate > 0) {
                    bitrate = existing.bitrate
                    sampleRate = existing.sampleRate
                } else {
                    val retriever = MediaMetadataRetriever()
                    try {
                        if (playUri.startsWith("content://")) {
                            retriever.setDataSource(context, Uri.parse(playUri))
                        } else {
                            retriever.setDataSource(playUri)
                        }
                        bitrate = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0) / 1000

                        // METADATA_KEY_SAMPLERATE requires API 31; the old
                        // fallback constant 24 was CAPTURE_FRAMERATE (wrong key!).
                        val sampleRateKey = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                            MediaMetadataRetriever.METADATA_KEY_SAMPLERATE else -1

                        sampleRate = if (sampleRateKey >= 0) retriever.extractMetadata(sampleRateKey)?.toIntOrNull() ?: 0 else 0
                    } catch (e: Throwable) {
                        // Silently continue if retriever unsupported or throws native Error for this item
                    } finally {
                        runCatching { retriever.release() }
                    }
                }

                // format, existing and isFav already initialized above

                val resolvedFileSize = when {
                    fileSize > 0 -> fileSize
                    rawFilePath.isNotBlank() -> runCatching { File(rawFilePath).length() }.getOrDefault(0L)
                    else -> 0L
                }

                // Fallback computation for lossless/high-res tracks when MediaMetadataRetriever returns 0
                val resolvedBitrate = when {
                    bitrate > 0 -> bitrate
                    duration > 0 && resolvedFileSize > 0 -> {
                        val durationSec = duration / 1000L
                        if (durationSec > 0) ((resolvedFileSize * 8L) / durationSec / 1000L).toInt().coerceIn(128, 9216)
                        else 1411
                    }
                    format == "FLAC" || format == "WAV" || format == "ALAC" -> 1411
                    else -> 320
                }

                val resolvedSampleRate = if (sampleRate > 0) sampleRate else 44100

                val song = Song(
                    id = existing?.id ?: 0,
                    title = title,
                    artist = if (artist.equals("<unknown>", ignoreCase = true)) "Unknown Artist" else artist,
                    album = album,
                    durationMs = duration,
                    filePath = playUri,
                    albumArtUri = albumArtUri,
                    isFavorite = isFav,
                    dateAdded = if (dateAddedMs > 0) dateAddedMs else existing?.dateAdded ?: System.currentTimeMillis(),
                    lastScanned = scanStartTimestamp,
                    bitrate = resolvedBitrate,
                    sampleRate = resolvedSampleRate,
                    format = format,
                    fileSize = resolvedFileSize
                )
                scannedSongs.add(song)
            }
        }

        if (scannedSongs.isNotEmpty()) {
            val toUpdate = scannedSongs.filter { it.id > 0L }
            val toInsert = scannedSongs.filter { it.id == 0L }

            if (toUpdate.isNotEmpty()) {
                songDao.updateSongs(toUpdate)
            }
            if (toInsert.isNotEmpty()) {
                songDao.insertSongsIgnore(toInsert)
            }
            songDao.deleteStaleLocalSongs(scanStartTimestamp)
        } else if (querySucceeded && existingSongs.isEmpty()) {
            // Legitimate empty library
            songDao.deleteStaleLocalSongs(scanStartTimestamp)
        } else {
            android.util.Log.w("LibraryScanner", "Storage scan yielded 0 songs while existing library has ${existingSongs.size} tracks; preserving existing library.")
        }

        return@withContext scannedSongs
    }

    private fun detectAudioFormat(path: String, rawFilePath: String, mimeType: String): String {
        val ext = (if (rawFilePath.isNotBlank()) rawFilePath else path).substringAfterLast('.', "").lowercase()
        val mime = mimeType.lowercase()
        return when {
            ext == "flac" || mime.contains("flac") -> "FLAC"
            ext == "wav" || mime.contains("wav") || mime.contains("x-wav") -> "WAV"
            ext == "alac" || mime.contains("alac") -> "ALAC"
            ext == "aac" || mime.contains("aac") -> "AAC"
            ext == "ogg" || ext == "opus" || mime.contains("ogg") || mime.contains("opus") -> "OGG"
            ext == "mp3" || mime.contains("mpeg") || mime.contains("mp3") -> "MP3"
            ext == "m4a" || mime.contains("mp4a") || mime.contains("m4a") -> "M4A"
            ext == "wma" || mime.contains("wma") -> "WMA"
            ext == "dsf" || ext == "dff" || mime.contains("dsd") -> "DSD"
            ext == "aiff" || ext == "aif" || mime.contains("aiff") -> "AIFF"
            ext == "webm" || mime.contains("webm") -> "WEBM"
            ext.length in 2..4 && ext.all { it.isLetterOrDigit() } -> ext.uppercase()
            else -> "AUDIO"
        }
    }
}
