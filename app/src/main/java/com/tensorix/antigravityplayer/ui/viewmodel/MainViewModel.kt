package com.tensorix.antigravityplayer.ui.viewmodel

import android.app.Application
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tensorix.antigravityplayer.audio.AudioOutputManager
import com.tensorix.antigravityplayer.audio.AudiophilePlaybackSnapshot
import com.tensorix.antigravityplayer.audio.AudioTrackInfo
import com.tensorix.antigravityplayer.data.MusicRepository
import com.tensorix.antigravityplayer.data.Playlist
import com.tensorix.antigravityplayer.data.PlaylistWithSongs
import com.tensorix.antigravityplayer.data.Song
import com.tensorix.antigravityplayer.player.EqualizerEngine
import com.tensorix.antigravityplayer.player.MusicController
import com.tensorix.antigravityplayer.player.PlaybackService
import com.tensorix.antigravityplayer.util.LrcLine
import com.tensorix.antigravityplayer.util.LrcParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@androidx.media3.common.util.UnstableApi
enum class SortOrder { TITLE, ARTIST, DURATION, DATE_ADDED }

@androidx.media3.common.util.UnstableApi
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository(application)
    private val musicController by lazy { MusicController(application) }
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Lyrics State
    private val _lyricsLines = MutableStateFlow<List<LrcLine>>(emptyList())
    val lyricsLines: StateFlow<List<LrcLine>> = _lyricsLines.asStateFlow()

    /** Sibling .lrc lookup: supports both direct paths and MediaStore content:// URIs */
    private fun loadLrcFor(song: Song): List<LrcLine> {
        return com.tensorix.antigravityplayer.util.LyricsResolver.resolveLrc(getApplication(), song)
    }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sortOrder = MutableStateFlow(SortOrder.TITLE)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    private val _isSortAscending = MutableStateFlow(true)
    val isSortAscending: StateFlow<Boolean> = _isSortAscending.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // Sleep Timer
    private var sleepTimerJob: Job? = null
    private val _sleepTimerRemainingMs = MutableStateFlow(0L)
    val sleepTimerRemainingMs: StateFlow<Long> = _sleepTimerRemainingMs.asStateFlow()

    /**
     * The SERVICE owns the listening AudioOutputManager.
     * The UI falls back to a poll-only instance (no system listeners) before the
     * service exists, so route events can never trigger double reconfigurations.
     */
    private val fallbackOutputManager by lazy {
        AudioOutputManager(application, registerSystemListeners = false)
    }
    val audioOutputManager: AudioOutputManager
        get() = PlaybackService.instance?.audioOutputManager ?: fallbackOutputManager

    private val _hiFiSupported = MutableStateFlow<Boolean>(PlaybackService.isHiFiSupported())
    val hiFiSupported: StateFlow<Boolean> = _hiFiSupported.asStateFlow()

    private val _hiFiEnabled = MutableStateFlow(
        PlaybackService.instance?.hiFiEnabled?.value
            ?: application.getSharedPreferences("antigravity_audio_prefs", Context.MODE_PRIVATE).getBoolean("hi_fi_enabled", true)
    )
    val hiFiEnabled: StateFlow<Boolean> = _hiFiEnabled.asStateFlow()

    private val _isBitPerfectMode = MutableStateFlow(false)
    val isBitPerfectMode: StateFlow<Boolean> = _isBitPerfectMode.asStateFlow()

    private val _isSampleRateMatching = MutableStateFlow(true)
    val isSampleRateMatching: StateFlow<Boolean> = _isSampleRateMatching.asStateFlow()

    private val _audioAuxEnabled = MutableStateFlow(true)
    val audioAuxEnabled: StateFlow<Boolean> = _audioAuxEnabled.asStateFlow()

    private val _audioSnapshot = MutableStateFlow(AudiophilePlaybackSnapshot())
    val audioSnapshot: StateFlow<AudiophilePlaybackSnapshot> = _audioSnapshot.asStateFlow()

    val isRouteAvailable: StateFlow<Boolean> = _audioSnapshot.map {
        it.output.activeRoute != null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isDirectPathActive: StateFlow<Boolean> = _audioSnapshot.map {
        it.output.canonicalSnapshot?.directPathActive?.value == true
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isDirectOutputVerified: StateFlow<Boolean> = _audioSnapshot.map {
        it.output.bitPerfectState == com.tensorix.antigravityplayer.audio.BitPerfectState.VERIFIED
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val hifiActive: StateFlow<Boolean> = _audioSnapshot.map { snapshot ->
        val route = snapshot.output.activeRoute
        val wiredHeadsetConnected = route?.routeType == com.tensorix.antigravityplayer.audio.AudioOutputRouteType.WIRED_HEADPHONES || 
                                    route?.routeType == com.tensorix.antigravityplayer.audio.AudioOutputRouteType.WIRED_HEADSET ||
                                    route?.routeType == com.tensorix.antigravityplayer.audio.AudioOutputRouteType.USB_DAC
        val detectedDac = route?.deviceName != null
        val outputSampleRate = snapshot.output.currentPlaybackSampleRate
        detectedDac && wiredHeadsetConnected && outputSampleRate >= 48000
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val playlists: StateFlow<List<Playlist>> = repository.allPlaylists.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val playlistsWithSongs: StateFlow<List<PlaylistWithSongs>> = repository.playlistsWithSongs.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val favoriteSongs: StateFlow<List<Song>> = repository.favoriteSongs.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val songs: StateFlow<List<Song>> = combine(
        _searchQuery.flatMapLatest { query ->
            if (query.isBlank()) repository.allSongs else repository.searchSongs(query)
        },
        _sortOrder,
        _isSortAscending
    ) { songList, sort, asc ->
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val sorted = when (sort) {
                SortOrder.TITLE -> songList.sortedBy { it.title.lowercase() }
                SortOrder.ARTIST -> songList.sortedBy { it.artist.lowercase() }
                SortOrder.DURATION -> songList.sortedByDescending { it.durationMs }
                SortOrder.DATE_ADDED -> songList.sortedByDescending { it.dateAdded }
            }
            if (asc) sorted else sorted.reversed()
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val currentSong get() = musicController.currentSong
    val isPlaying get() = musicController.isPlaying
    val currentPositionState: StateFlow<Long> get() = musicController.currentPositionMs
    val durationMs get() = musicController.durationMs
    val shuffleEnabled get() = musicController.shuffleEnabled
    val repeatMode get() = musicController.repeatMode
    val queue get() = musicController.queue

    val equalizerEngine: EqualizerEngine?
        get() = PlaybackService.instance?.equalizerEngine

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshAudioSnapshot()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshAudioSnapshot()
        }
    }

    init {
        refreshHiFiSupport()
        refreshAudioSnapshot()
        
        viewModelScope.launch {
            PlaybackService.instanceFlow.collectLatest { service ->
                if (service != null) {
                    kotlinx.coroutines.coroutineScope {
                        launch { service.hiFiEnabled.collect { _hiFiEnabled.value = it } }
                        launch { service.bitPerfectMode.collect { _isBitPerfectMode.value = it } }
                        launch { service.sampleRateMatching.collect { _isSampleRateMatching.value = it } }
                        launch { service.audioAuxEnabled.collect { _audioAuxEnabled.value = it } }
                        launch { service.audiophileSnapshot.collect { _audioSnapshot.value = it } }
                    }
                }
            }
        }

        viewModelScope.launch {
            currentSong.collectLatest {
                refreshAudioSnapshot()
            }
        }
        viewModelScope.launch {
            currentSong.collectLatest { song ->
                val lines = if (song == null || song.filePath.isBlank()) emptyList()
                else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    loadLrcFor(song)
                }
                _lyricsLines.value = lines
            }
        }

        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
    }

    fun refreshHiFiSupport() {
        _hiFiSupported.value = PlaybackService.isHiFiSupported()
    }

    fun refreshAudioSnapshot() {
        val service = PlaybackService.instance
        if (service != null) {
            _audioSnapshot.value = service.audiophileSnapshot.value
            _hiFiSupported.value = PlaybackService.isHiFiSupported()
            _hiFiEnabled.value = service.hiFiEnabled.value
            return
        }

        val track = currentSong.value?.let {
            val sRate = if (it.sampleRate > 0) it.sampleRate else 44100
            val bDepth = when {
                it.format.equals("FLAC", true) || it.format.equals("WAV", true) || it.format.equals("ALAC", true) -> 24
                it.sampleRate >= 176400 -> 24
                it.sampleRate > 0 -> 16
                else -> 16
            }
            val isHiRes = (bDepth >= 24) || (sRate >= 88200)
            AudioTrackInfo(
                title = it.title,
                artist = it.artist,
                codec = it.format ?: "Lossless PCM",
                bitrateKbps = it.bitrate,
                bitDepth = bDepth,
                sampleRateHz = sRate,
                isHiResSource = isHiRes,
                isHiRes = isHiRes
            )
        } ?: AudioTrackInfo()
        
        val dspEnabled = PlaybackService.instance?.equalizerEngine?.isEnabled?.value ?: false
        val isBitPerfect = PlaybackService.instance?.bitPerfectMode?.value ?: false
        val isDsp = !isBitPerfect && dspEnabled
        _audioSnapshot.value = audioOutputManager.currentSnapshot(track, isDsp)
    }

    fun forceReloadAudioPipeline() {
        PlaybackService.instance?.reloadAudioPipeline()
        refreshAudioSnapshot()
    }

    fun setBitPerfectMode(enabled: Boolean) {
        PlaybackService.instance?.setBitPerfectMode(enabled)
        refreshAudioSnapshot()
    }

    fun setSampleRateMatching(enabled: Boolean) {
        PlaybackService.instance?.setSampleRateMatching(enabled)
        refreshAudioSnapshot()
    }

    fun setHiFiAudioSinkEnabled(enabled: Boolean) {
        _hiFiEnabled.value = enabled
        PlaybackService.instance?.setHiFiEnabled(enabled)
        refreshAudioSnapshot()
    }

    fun setAudioAuxEnabled(enabled: Boolean) {
        PlaybackService.instance?.setAudioAuxEnabled(enabled)
        refreshAudioSnapshot()
    }

    fun scanLibrary() {
        if (_isScanning.value) return
        viewModelScope.launch {
            _isScanning.value = true
            try {
                repository.scanLocalLibrary()
            } catch (e: Exception) {
                android.util.Log.w("Antigravity", "Failure in " + javaClass.simpleName, e)
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSortOrder(order: SortOrder) {
        if (_sortOrder.value == order) {
            _isSortAscending.value = !_isSortAscending.value
        } else {
            _sortOrder.value = order
            _isSortAscending.value = true
        }
    }

    fun toggleFavorite(song: Song) {
        viewModelScope.launch {
            repository.toggleFavorite(song.id, !song.isFavorite)
        }
    }

    fun playSong(song: Song, fullList: List<Song> = emptyList()) {
        val listToPlay = if (fullList.isNotEmpty()) fullList else songs.value
        musicController.playSong(song, listToPlay)
    }

    fun playNext(song: Song) = musicController.playNext(song)
    fun addToQueue(song: Song) = musicController.addToQueue(song)
    fun removeFromQueue(index: Int) = musicController.removeFromQueue(index)

    fun playAll(songsToPlay: List<Song> = songs.value, shuffle: Boolean = false) {
        if (songsToPlay.isEmpty()) return
        val list = if (shuffle) songsToPlay.shuffled() else songsToPlay
        musicController.playPlaylist(list, 0)
    }

    fun togglePlayPause() = musicController.togglePlayPause()
    fun skipToNext() = musicController.skipToNext()
    fun skipToPrevious() = musicController.skipToPrevious()
    fun seekTo(positionMs: Long) = musicController.seekTo(positionMs)
    fun toggleShuffle() = musicController.toggleShuffle()
    fun toggleRepeat() = musicController.toggleRepeat()

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            _sleepTimerRemainingMs.value = 0L
            return
        }
        val totalMs = minutes * 60 * 1000L
        _sleepTimerRemainingMs.value = totalMs

        sleepTimerJob = viewModelScope.launch {
            var remaining = totalMs
            while (remaining > 0) {
                delay(1000)
                remaining -= 1000
                _sleepTimerRemainingMs.value = remaining
            }
            if (isPlaying.value) {
                togglePlayPause()
            }
            _sleepTimerRemainingMs.value = 0L
        }
    }

    fun refreshAudioRouteSnapshot() {
        audioOutputManager.refresh()
        refreshAudioSnapshot()
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            if (name.isNotBlank()) {
                repository.createPlaylist(name.trim())
            }
        }
    }

    fun addSongToPlaylist(playlistId: Long, songId: Long) {
        viewModelScope.launch {
            repository.addSongToPlaylist(playlistId, songId)
        }
    }

    fun removeSongFromPlaylist(playlistId: Long, songId: Long) {
        viewModelScope.launch {
            repository.removeSongFromPlaylist(playlistId, songId)
        }
    }

    fun deletePlaylist(playlist: Playlist) {
        viewModelScope.launch {
            repository.deletePlaylist(playlist)
        }
    }

    override fun onCleared() {
        super.onCleared()
        sleepTimerJob?.cancel()
        runCatching {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        }
        musicController.release()
        // Only release UI's own fallback output manager; never release PlaybackService's active manager!
        fallbackOutputManager.release()
    }
}
