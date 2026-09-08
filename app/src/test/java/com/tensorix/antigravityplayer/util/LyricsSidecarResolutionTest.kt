package com.tensorix.antigravityplayer.util

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import com.tensorix.antigravityplayer.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Verifies lyrics sidecar resolution:
 * - Direct filesystem paths (/path/to/song.mp3 -> /path/to/song.lrc)
 * - file:// URI paths
 * - MediaStore content:// URIs resolving sibling .lrc through _data query
 * - Missing lyrics sidecar handling
 * - Oversized (>2MB) and empty (0 bytes) file protection
 * - Malformed syntax safety
 */
class LyricsSidecarResolutionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `resolves sibling lrc for local filesystem path`() {
        val audioFile = tempFolder.newFile("test_track.flac")
        val lrcFile = tempFolder.newFile("test_track.lrc")
        lrcFile.writeText("[00:05.00]First line\n[00:15.00]Second line")

        val song = Song(
            id = 1L,
            title = "Test Track",
            artist = "Artist",
            album = "Album",
            durationMs = 30000L,
            filePath = audioFile.absolutePath
        )

        val lines = LyricsResolver.resolveLrc(null, song)
        assertEquals(2, lines.size)
        assertEquals(5000L, lines[0].timeMs)
        assertEquals("First line", lines[0].text)
        assertEquals(15000L, lines[1].timeMs)
        assertEquals("Second line", lines[1].text)
    }

    @Test
    fun `resolves sibling lrc for file URI`() {
        val audioFile = tempFolder.newFile("uri_track.mp3")
        val lrcFile = tempFolder.newFile("uri_track.lrc")
        lrcFile.writeText("[00:02.50]Hello from URI")

        val song = Song(
            id = 2L,
            title = "URI Track",
            artist = "Artist",
            album = "Album",
            durationMs = 20000L,
            filePath = audioFile.toURI().toString()
        )

        val lines = LyricsResolver.resolveLrc(null, song)
        assertEquals(1, lines.size)
        assertEquals(2500L, lines[0].timeMs)
        assertEquals("Hello from URI", lines[0].text)
    }

    @Test
    fun `resolves sibling lrc for MediaStore content URI using data column`() {
        val audioFile = tempFolder.newFile("mediastore_song.m4a")
        val lrcFile = tempFolder.newFile("mediastore_song.lrc")
        lrcFile.writeText("[00:10.00]Content URI Lyrics")

        val contentUriStr = "content://media/external/audio/media/999"
        val mockUri = mock<Uri>()
        LyricsResolver.uriParser = { mockUri }

        val song = Song(
            id = 999L,
            title = "Content Song",
            artist = "Artist",
            album = "Album",
            durationMs = 60000L,
            filePath = contentUriStr
        )

        val context = mock<Context>()
        val contentResolver = mock<ContentResolver>()
        val cursor = mock<Cursor>()

        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(contentResolver.query(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(cursor)
        whenever(cursor.moveToFirst()).thenReturn(true)
        whenever(cursor.getColumnIndex(MediaStore.Audio.Media.DATA)).thenReturn(0)
        whenever(cursor.getString(0)).thenReturn(audioFile.absolutePath)

        val lines = LyricsResolver.resolveLrc(context, song)
        assertEquals(1, lines.size)
        assertEquals(10000L, lines[0].timeMs)
        assertEquals("Content URI Lyrics", lines[0].text)
    }

    @org.junit.After
    fun tearDown() {
        LyricsResolver.uriParser = { runCatching { Uri.parse(it) }.getOrNull() }
    }

    @Test
    fun `returns empty list when sibling lrc is missing`() {
        val audioFile = tempFolder.newFile("solo_audio.wav")
        val song = Song(
            id = 3L,
            title = "Solo Audio",
            artist = "Artist",
            album = "Album",
            durationMs = 15000L,
            filePath = audioFile.absolutePath
        )

        val lines = LyricsResolver.resolveLrc(null, song)
        assertTrue("Missing sidecar must yield empty list without throwing", lines.isEmpty())
    }

    @Test
    fun `rejects oversized lrc files larger than 2MB`() {
        val oversizedFile = mock<File>()
        whenever(oversizedFile.exists()).thenReturn(true)
        whenever(oversizedFile.isFile).thenReturn(true)
        whenever(oversizedFile.canRead()).thenReturn(true)
        whenever(oversizedFile.length()).thenReturn(3L * 1024 * 1024) // 3MB

        assertFalse("LRC larger than 2MB must be rejected", LyricsResolver.isValidLrcFile(oversizedFile))
    }

    @Test
    fun `rejects empty 0-byte lrc files`() {
        val emptyFile = tempFolder.newFile("empty.lrc")
        assertFalse("0-byte file must be rejected", LyricsResolver.isValidLrcFile(emptyFile))

        val audioFile = tempFolder.newFile("empty_track.mp3")
        val song = Song(id = 4L, title = "Empty", artist = "A", album = "B", durationMs = 1000L, filePath = audioFile.absolutePath)
        val lines = LyricsResolver.resolveLrc(null, song)
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `handles malformed lrc content gracefully`() {
        val audioFile = tempFolder.newFile("corrupt_track.mp3")
        val lrcFile = tempFolder.newFile("corrupt_track.lrc")
        lrcFile.writeText("THIS IS NOT A VALID LRC FILE\nNO TIMESTAMPS AT ALL\n[broken:tag]")

        val song = Song(id = 5L, title = "Corrupt", artist = "A", album = "B", durationMs = 1000L, filePath = audioFile.absolutePath)
        val lines = LyricsResolver.resolveLrc(null, song)
        assertTrue("Corrupt LRC without timestamps must gracefully yield empty list", lines.isEmpty())
    }
}
