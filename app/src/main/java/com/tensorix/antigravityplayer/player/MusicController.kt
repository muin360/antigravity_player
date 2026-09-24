package com.tensorix.antigravityplayer.player

import androidx.core.net.toUri
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import androidx.core.content.ContextCompat
import com.tensorix.antigravityplayer.data.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File

@androidx.media3.common.util.UnstableApi
class MusicController(private val context: Context) {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var progressJob: Job? = null

    // Seek snap-back guard: Media3 reports the OLD position for a short
    // window after seekTo; suppress tracker writes so the UI never jumps back.
    @Volatile
    private var lastSeekRequestMs: Long = 0L

    private var pendingPlayAction: (() -> Unit)? = null

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private var songMap = android.util.LruCache<String, Song>(5000)

    init {
        initController()
    }

    private var retryJob: Job? = null
    private var initRetries = 0
    private fun initController() {
        if (mediaController != null && mediaController?.isConnected == true) return
        if (controllerFuture != null) return
        retryJob?.cancel()

        // Bound the retry loop: a permanently dead session component must not
        // schedule reconnect attempts forever.
        if (initRetries >= 5) {
            Log.e("MusicController", "Session bind failed after $initRetries retries; giving up until next user action.")
            return
        }

        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                val controller = controllerFuture?.get()
                mediaController = controller
                setupPlayerListener(controller)
                syncStateFromController(controller)
                // Bug 19: Validate pendingPlayAction on mock controller
                if (controller != null && controller.isConnected) {
                    pendingPlayAction?.invoke()
                    pendingPlayAction = null
                }
                controllerFuture = null
                initRetries = 0
            } catch (e: Exception) {
                android.util.Log.w("Antigravity", "Failure in " + javaClass.simpleName, e)
                controllerFuture = null
                initRetries++
                retryJob = scope.launch {
                    delay(2000L * initRetries)
                    initController()
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun syncStateFromController(controller: MediaController?) {
        controller ?: return
        _isPlaying.value = controller.isPlaying
        _shuffleEnabled.value = controller.shuffleModeEnabled
        _repeatMode.value = controller.repeatMode
        _durationMs.value = controller.duration.coerceAtLeast(0L)
        _currentPositionMs.value = controller.currentPosition.coerceAtLeast(0L)

        controller.currentMediaItem?.let { item ->
            val songId = item.mediaId
            _currentSong.value = songMap[songId] ?: songFromMediaItem(item)
        }

        if (controller.isPlaying) {
            startProgressTracker()
        }
    }

    private fun songFromMediaItem(item: MediaItem): Song {
        val metadata = item.mediaMetadata
        return Song(
            id = item.mediaId.toLongOrNull() ?: 0L,
            title = metadata.title?.toString() ?: "Unknown Title",
            artist = metadata.artist?.toString() ?: "Unknown Artist",
            album = metadata.albumTitle?.toString() ?: "Unknown Album",
            durationMs = 0L,
            filePath = item.localConfiguration?.uri?.toString()
                ?: item.requestMetadata.mediaUri?.toString()
                ?: "",
            albumArtUri = metadata.artworkUri?.toString()
        )
    }

    private fun songToUri(song: Song): Uri {
        val path = song.filePath.trim()
        if (path.isBlank()) return Uri.EMPTY
        return runCatching {
            when {
                path.startsWith("content://") || path.startsWith("http://") || path.startsWith("https://") -> path.toUri()
                path.startsWith("file://") -> Uri.parse(path)
                else -> Uri.fromFile(java.io.File(path))
            }
        }.getOrDefault(Uri.EMPTY)
    }

    private fun setupPlayerListener(controller: MediaController?) {
        controller ?: return
        controller.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) {
                    startProgressTracker()
                } else {
                    stopProgressTracker()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.let {
                    val songId = it.mediaId
                    val song = songMap[songId] ?: songFromMediaItem(it)
                    _currentSong.value = song
                    _durationMs.value = controller.duration.coerceAtLeast(0L)

                    val calculatedBitDepth = when {
                        song.format == "DSD" || song.format == "DXD" -> 32
                        song.format == "FLAC" || song.format == "ALAC" || song.format == "WAV" || song.format == "AIFF" -> {
                            if (song.sampleRate >= 88200 || song.bitrate > 1000) 24 else 16
                        }
                        song.bitrate > 900 || song.sampleRate >= 88200 -> 24
                        else -> 16
                    }

                    // ReplayGain parsing touches disk: keep it OFF the main
                    // thread and read only a bounded header region instead of
                    // loading entire lossless files into memory.
                    scope.launch {
                        val replayGain = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            readReplayGainTags(song.filePath, song.format)
                        }
                        PlaybackService.instance?.updateCurrentTrackInfo(
                            title = song.title,
                            artist = song.artist,
                            codec = song.format ?: "Lossless PCM",
                            bitrateKbps = song.bitrate,
                            bitDepth = calculatedBitDepth,
                            sampleRateHz = if (song.sampleRate > 0) song.sampleRate else 44100,
                            trackReplayGainDb = replayGain.trackGainDb,
                            albumReplayGainDb = replayGain.albumGainDb,
                            peakAmplitude = replayGain.trackPeak,
                            useAlbumGain = false
                        )
                    }
                } ?: run {
                    _currentSong.value = null
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    _durationMs.value = controller.duration.coerceAtLeast(0L)
                }
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                _shuffleEnabled.value = shuffleModeEnabled
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                _repeatMode.value = repeatMode
            }
        })
    }

    private fun startProgressTracker() {
        stopProgressTracker()
        progressJob = scope.launch {
            while (isActive) {
                mediaController?.let { controller ->
                    val seekSettle = android.os.SystemClock.elapsedRealtime() - lastSeekRequestMs < 700L
                    if (!seekSettle) {
                        _currentPositionMs.value = controller.currentPosition.coerceAtLeast(0L)
                    }
                    _durationMs.value = controller.duration.coerceAtLeast(0L)
                }
                delay(200)
            }
        }
    }

    private fun stopProgressTracker() {
        progressJob?.cancel()
        progressJob = null
    }

    fun playPlaylist(songs: List<Song>, startIndex: Int = 0) {
        val controller = mediaController
        if (controller == null || !controller.isConnected) {
            pendingPlayAction = { playPlaylist(songs, startIndex) }
            initController()
            return
        }
        if (songs.isEmpty()) return

        // P0-7 incremental switching: if the requested queue is IDENTICAL to
        // the live Media3 timeline, never rebuild it - just select the target.
        if (isSameQueue(songs)) {
            val safeIndex = startIndex.coerceIn(0, songs.lastIndex)
            selectTimelineItem(controller, safeIndex, startAtZero = true)
            return
        }

        scope.launch {
            val mediaItems = withContext(Dispatchers.Default) {
                songs.map { song ->
                    val idStr = song.id.toString()
                    val artUri = song.albumArtUri?.takeIf { it.isNotBlank() }?.let {
                        runCatching { Uri.parse(it) }.getOrNull()
                    }

                    val metadata = MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.artist)
                        .setAlbumTitle(song.album)
                        .setArtworkUri(artUri)
                        .build()

                    val uri = if (song.filePath.isNotBlank()) songToUri(song) else Uri.EMPTY

                    MediaItem.Builder()
                        .setMediaId(idStr)
                        .setUri(uri)
                        .setMediaMetadata(metadata)
                        .build()
                }
            }
            
            songMap.evictAll()
            songs.forEach { songMap.put(it.id.toString(), it) }
            _queue.value = songs
            
            val safeIndex = startIndex.coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0))
            runCatching { Log.i("STARTUP_TIMING", "T0: User requested playback for track=${songs.getOrNull(safeIndex)?.title}") }

            controller.setMediaItems(mediaItems, safeIndex, 0L)
            controller.prepare()
            controller.play()
        }
    }

    fun playSong(song: Song, fullList: List<Song> = listOf(song), requestedIndex: Int = -1) {
        val index = if (requestedIndex >= 0 && requestedIndex < fullList.size && fullList[requestedIndex].id == song.id) requestedIndex 
                    else fullList.indexOfFirst { it.id == song.id }.let { if (it == -1) 0 else it }
        val isFullListSame = isSameQueue(fullList)

        if (isFullListSame) {
            val controller = mediaController
            if (controller != null && controller.isConnected) {
                val isCurrent = controller.currentMediaItemIndex == index
                if (isCurrent) {
                    controller.seekTo(0L)
                    controller.play()
                    _currentPositionMs.value = 0L
                    return
                }
                selectTimelineItem(controller, index, startAtZero = true)
                return
            }
        }
        playPlaylist(fullList, index)
    }

    /** True when the live timeline contains exactly these songs in order. */
    private fun isSameQueue(songs: List<Song>): Boolean {
        val currentIds = _queue.value.map { it.id }
        val requestedIds = songs.map { it.id }
        return currentIds == requestedIds && currentIds.isNotEmpty()
    }

    /**
     * Selects an item in the EXISTING timeline. Uses the mirrored song map to
     * keep _currentSong/_queue coherent; Media3 fires onMediaItemTransition so
     * all downstream state (ReplayGain, notifications) updates normally.
     */
    private fun selectTimelineItem(controller: MediaController, index: Int, startAtZero: Boolean) {
        runCatching {
            if (index in 0 until controller.mediaItemCount) {
                if (startAtZero) controller.seekToDefaultPosition(index) else controller.seekTo(index, 0L)
                controller.play()
                Log.i("TRACK_SWITCH", "incremental select index=$index total=${controller.mediaItemCount}")
            } else {
                Log.w("TRACK_SWITCH", "select index=$index outside live timeline (${controller.mediaItemCount})")
            }
        }
    }

    fun playNext(song: Song) {
        val controller = mediaController ?: return
        val currentList = _queue.value.toMutableList()
        val currentIndex = controller.currentMediaItemIndex.coerceAtLeast(0)
        val insertIndex = if (currentList.isEmpty()) 0 else (currentIndex + 1).coerceAtMost(currentList.size)

        currentList.add(insertIndex, song)
        _queue.value = currentList
        songMap.put(song.id.toString(), song)

        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setArtworkUri(song.albumArtUri?.let { Uri.parse(it) })
            .build()

        val uri = if (song.filePath.isNotBlank()) songToUri(song) else Uri.EMPTY
        val mediaItem = MediaItem.Builder()
            .setMediaId(song.id.toString())
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()

        val safeExoIndex = insertIndex.coerceIn(0, controller.mediaItemCount)
        controller.addMediaItem(safeExoIndex, mediaItem)
    }

    fun addToQueue(song: Song) {
        val controller = mediaController ?: return
        val currentList = _queue.value.toMutableList()
        currentList.add(song)
        _queue.value = currentList
        songMap.put(song.id.toString(), song)

        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setArtworkUri(song.albumArtUri?.let { Uri.parse(it) })
            .build()

        val uri = if (song.filePath.isNotBlank()) songToUri(song) else Uri.EMPTY
        val mediaItem = MediaItem.Builder()
            .setMediaId(song.id.toString())
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()

        controller.addMediaItem(mediaItem)
    }

    fun removeFromQueue(index: Int) {
        val controller = mediaController ?: return
        val currentList = _queue.value.toMutableList()
        if (index in currentList.indices) {
            currentList.removeAt(index)
            _queue.value = currentList
            if (index in 0 until controller.mediaItemCount) {
                controller.removeMediaItem(index)
            }
        }
    }

    fun togglePlayPause() {
        val controller = mediaController
        if (controller == null || !controller.isConnected) {
            initController()
            return
        }
        
        when (controller.playbackState) {
            Player.STATE_BUFFERING, Player.STATE_READY -> {
                if (controller.playWhenReady) {
                    controller.pause()
                } else {
                    controller.play()
                }
            }
            Player.STATE_ENDED -> {
                controller.seekTo(0)
                controller.play()
            }
            Player.STATE_IDLE -> {
                controller.prepare()
                controller.play()
            }
        }
    }

    fun skipToNext() {
        val controller = mediaController ?: return
        if (controller.hasNextMediaItem()) {
            controller.seekToNextMediaItem()
        } else if (controller.mediaItemCount > 0) {
            controller.seekToDefaultPosition(0)
        }
    }

    fun clearQueue() {
        val controller = mediaController ?: return
        controller.clearMediaItems()
        _queue.value = emptyList()
        songMap.evictAll()
        _currentSong.value = null
    }
    
    fun skipToPrevious() {
        val controller = mediaController ?: return
        if (controller.currentPosition > 3000L) {
            controller.seekTo(0L)
        } else if (controller.hasPreviousMediaItem()) {
            controller.seekToPreviousMediaItem()
        } else if (controller.mediaItemCount > 0) {
            controller.seekToDefaultPosition(controller.mediaItemCount - 1)
        }
    }

    fun seekTo(positionMs: Long) {
        val controller = mediaController ?: return
        runCatching { Log.i("SEEK", "User requested seekTo(positionMs=$positionMs)") }
        lastSeekRequestMs = android.os.SystemClock.elapsedRealtime()
        
        val duration = controller.duration
        val safePosition = if (duration > 0) positionMs.coerceIn(0L, maxOf(0L, duration - 100L)) else positionMs.coerceAtLeast(0L)
        
        _currentPositionMs.value = safePosition
        controller.seekTo(safePosition)
    }

    fun toggleShuffle() {
        val controller = mediaController ?: return
        controller.shuffleModeEnabled = !controller.shuffleModeEnabled
    }

    fun toggleRepeat() {
        val controller = mediaController ?: return
        val nextMode = when (controller.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        controller.repeatMode = nextMode
        _repeatMode.value = nextMode
    }

    fun release() {
        stopProgressTracker()
        scope.cancel()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }

    private data class ReplayGainTags(
        val trackGainDb: Float = 0f,
        val albumGainDb: Float = 0f,
        val trackPeak: Float = 0f
    )

    private fun readReplayGainTags(path: String, codec: String?): ReplayGainTags {
        val ext = path.substringAfterLast('.', "").lowercase()
        val isSupportedFormat = ext in setOf("mp3", "flac", "ogg", "opus") ||
            codec?.uppercase() in setOf("MP3", "FLAC", "OGG", "OPUS", "VORBIS")
        if (!isSupportedFormat) return ReplayGainTags()

        // Bounded header read: ReplayGain lives in ID3v2 (start of file) or
        // FLAC Vorbis comments (metadata blocks before audio). 1 MB covers
        // both without ever loading a full lossless track into memory.
        val bytes = runCatching {
            if (path.startsWith("content://")) {
                val uri = path.toUri()
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val maxBytes = 1024 * 1024
                    val buf = ByteArray(maxBytes)
                    var totalRead = 0
                    while (totalRead < maxBytes) {
                        val read = stream.read(buf, totalRead, maxBytes - totalRead)
                        if (read <= 0) break
                        totalRead += read
                    }
                    if (totalRead <= 0) null else buf.copyOf(totalRead)
                }
            } else {
                val file = File(path)
                if (file.exists() && file.isFile) {
                    java.io.RandomAccessFile(file, "r").use { raf ->
                        val size = minOf(raf.length(), 1024L * 1024L).toInt()
                        if (size <= 0) null
                        else {
                            val buf = ByteArray(size)
                            raf.readFully(buf)
                            buf
                        }
                    }
                } else null
            }
        }.getOrNull() ?: return ReplayGainTags()
        // Memory Bloat fix: Scan for 'REPLAYGAIN' directly in bytes to avoid allocating 1MB String
        val searchSeq = "REPLAYGAIN".toByteArray(Charsets.ISO_8859_1)
        var foundIdx = -1
        for (i in 0 until bytes.size - searchSeq.size) {
            var match = true
            for (j in searchSeq.indices) {
                if (bytes[i + j].toInt() != searchSeq[j].toInt() && 
                    bytes[i + j].toInt() != (searchSeq[j].toInt() + 32)) { // case insensitive approx
                    match = false
                    break
                }
            }
            if (match) {
                foundIdx = i
                break
            }
        }
        
        val text = if (foundIdx >= 0) {
            // Found something like ReplayGain, just decode the chunk around it (e.g. 2000 bytes)
            val start = maxOf(0, foundIdx - 1000)
            val length = minOf(bytes.size - start, 3000)
            String(bytes, start, length, Charsets.ISO_8859_1)
        } else {
            "" // No ReplayGain tags found
        }
        
        return ReplayGainTags(
            trackGainDb = extractReplayGainValue(text, listOf("REPLAYGAIN_TRACK_GAIN", "TXXX:REPLAYGAIN_TRACK_GAIN")),
            albumGainDb = extractReplayGainValue(text, listOf("REPLAYGAIN_ALBUM_GAIN", "TXXX:REPLAYGAIN_ALBUM_GAIN")),
            trackPeak = extractReplayGainValue(text, listOf("REPLAYGAIN_TRACK_PEAK", "TXXX:REPLAYGAIN_TRACK_PEAK"))
        )
    }

    private fun extractReplayGainValue(text: String, keys: List<String>): Float {
        for (key in keys) {
            val idx = text.indexOf(key, ignoreCase = true)
            if (idx >= 0) {
                val tail = text.substring(idx + key.length).take(64)
                val match = Regex("(-?\\d+(?:\\.\\d+)?)").find(tail)
                if (match != null) {
                    return match.groupValues[1].toFloatOrNull() ?: 0f
                }
            }
        }
        return 0f
    }
}
