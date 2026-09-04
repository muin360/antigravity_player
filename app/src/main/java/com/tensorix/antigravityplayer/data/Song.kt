package com.tensorix.antigravityplayer.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Core Song entity for Room DB — Hi-Fi/Hi-Res offline audio metadata
 */
@Entity(
    tableName = "songs",
    indices = [
        Index(value = ["filePath"], unique = true),
        Index(value = ["title"]),
        Index(value = ["artist"]),
        Index(value = ["album"]),
        Index(value = ["isFavorite"]),
        Index(value = ["lastScanned"])
    ]
)
data class Song(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val filePath: String,
    val albumArtUri: String? = null,
    val dateAdded: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    val lastScanned: Long = System.currentTimeMillis(),
    // Hi-Res audio metadata
    val bitrate: Int = 0,           // kbps (e.g. 320, 1411)
    val sampleRate: Int = 0,        // Hz (e.g. 44100, 96000, 192000)
    val format: String? = null,     // "FLAC", "MP3", "AAC", "WAV", "ALAC", "OGG"
    val fileSize: Long = 0L         // bytes
)
