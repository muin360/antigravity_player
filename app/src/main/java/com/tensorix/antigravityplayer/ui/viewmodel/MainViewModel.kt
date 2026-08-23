package com.tensorix.antigravityplayer.ui.viewmodel

import android.app.Application
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tensorix.antigravityplayer.ai.AgentAction
import com.tensorix.antigravityplayer.ai.AiKeyManager
import com.tensorix.antigravityplayer.ai.AiOutcome
import com.tensorix.antigravityplayer.ai.AiProvider
import com.tensorix.antigravityplayer.ai.MusicAiAgent
import com.tensorix.antigravityplayer.data.MusicRepository
import com.tensorix.antigravityplayer.data.Playlist
import com.tensorix.antigravityplayer.data.PlaylistWithSongs
import com.tensorix.antigravityplayer.data.Song
import com.tensorix.antigravityplayer.data.remote.YtApiService
import com.tensorix.antigravityplayer.data.remote.YtResult
import com.tensorix.antigravityplayer.data.remote.YtSearchResultItem
import com.tensorix.antigravityplayer.data.remote.YtStreamResponse
import com.tensorix.antigravityplayer.audio.AudioOutputManager
import com.tensorix.antigravityplayer.audio.AudiophilePlaybackSnapshot
import com.tensorix.antigravityplayer.audio.AudioTrackInfo
import com.tensorix.antigravityplayer.player.EqualizerEngine
import com.tensorix.antigravityplayer.player.MusicController
import com.tensorix.antigravityplayer.player.PlaybackService
import com.tensorix.antigravityplayer.ui.components.ChatMessage
import com.tensorix.antigravityplayer.util.LrcLine
import com.tensorix.antigravityplayer.util.LrcParser
import com.tensorix.antigravityplayer.voice.VoiceAssistantManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@androidx.media3.common.util.UnstableApi
enum class SortOrder { TITLE, ARTIST, DURATION, DATE_ADDED }

@androidx.media3.common.util.UnstableApi
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository(application)
    private val musicController by lazy { MusicController(application) }
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // AI & Voice Engine
    val aiKeyManager = AiKeyManager(application)
    private val musicAiAgent = MusicAiAgent(aiKeyManager)
    val voiceAssistantManager = VoiceAssistantManager(application)

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _isAiProcessing = MutableStateFlow(false)
    val isAiProcessing: StateFlow<Boolean> = _isAiProcessing.asStateFlow()

    val isListeningVoice = voiceAssistantManager.isListening
    val selectedAiProvider = aiKeyManager.selectedProvider
    val selectedAiModel = aiKeyManager.selectedModel

    // Lyrics State
    private val _lyricsLines = MutableStateFlow<List<LrcLine>>(emptyList())
    val lyricsLines: StateFlow<List<LrcLine>> = _lyricsLines.asStateFlow()

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

    // Download tracking
    private val _downloadProgress = MutableStateFlow(-1)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()

    private val _downloadingTrackId = MutableStateFlow<String?>(null)
    val downloadingTrackId: StateFlow<String?> = _downloadingTrackId.asStateFlow()

    val audioOutputManager = AudioOutputManager(application)

    private val _hiFiSupported = MutableStateFlow<Boolean>(PlaybackService.isHiFiSupported())
    val hiFiSupported: StateFlow<Boolean> = _hiFiSupported.asStateFlow()

    private val _isBitPerfectMode = MutableStateFlow(false)
    val isBitPerfectMode: StateFlow<Boolean> = _isBitPerfectMode.asStateFlow()

    private val _isSampleRateMatching = MutableStateFlow(true)
    val isSampleRateMatching: StateFlow<Boolean> = _isSampleRateMatching.asStateFlow()

    private val _audioAuxEnabled = MutableStateFlow(true)
    val audioAuxEnabled: StateFlow<Boolean> = _audioAuxEnabled.asStateFlow()

    private val _audioSnapshot = MutableStateFlow(AudiophilePlaybackSnapshot())
    val audioSnapshot: StateFlow<AudiophilePlaybackSnapshot> = _audioSnapshot.asStateFlow()

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

    val downloadedSongs: StateFlow<List<Song>> = repository.downloadedSongs.stateIn(
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
    val currentPositionMs get() = musicController.currentPositionMs

    /** Flow handle for subtree-scoped collection (Phase 22 perf fix). */
    val currentPositionState: StateFlow<Long> get() = musicController.currentPositionMs
    val durationMs get() = musicController.durationMs
    val shuffleEnabled get() = musicController.shuffleEnabled
    val repeatMode get() = musicController.repeatMode
    val queue get() = musicController.queue

    val equalizerEngine: EqualizerEngine?
        get() = PlaybackService.instance?.equalizerEngine

    private val ytApiService = YtApiService(application)
    private val _ytSearchResults = MutableStateFlow<List<YtSearchResultItem>>(emptyList())
    val ytSearchResults: StateFlow<List<YtSearchResultItem>> = _ytSearchResults.asStateFlow()

    private val _isYtSearching = MutableStateFlow(false)
    val isYtSearching: StateFlow<Boolean> = _isYtSearching.asStateFlow()

    init {
        refreshHiFiSupport()
        refreshAudioSnapshot()
        
        viewModelScope.launch {
            PlaybackService.instanceFlow.collectLatest { service ->
                if (service != null) {
                    kotlinx.coroutines.coroutineScope {
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
            audioOutputManager.outputState.collectLatest {
                refreshAudioSnapshot()
            }
        }
        runCatching {
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refreshHiFiSupport()
            refreshAudioSnapshot()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            refreshHiFiSupport()
            refreshAudioSnapshot()
        }
    }

    private fun refreshHiFiSupport() {
        _hiFiSupported.value = PlaybackService.isHiFiSupported()
    }

    fun refreshAudioSnapshot() {
        val service = PlaybackService.instance
        if (service != null) {
            _audioSnapshot.value = service.audiophileSnapshot.value
            _hiFiSupported.value = service.audiophileSnapshot.value.output.activeRoute != null
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
        _isBitPerfectMode.value = enabled
        PlaybackService.instance?.setBitPerfectMode(enabled)
        refreshAudioSnapshot()
    }

    fun setSampleRateMatching(enabled: Boolean) {
        PlaybackService.instance?.setSampleRateMatching(enabled)
        refreshAudioSnapshot()
    }

    fun setHiFiAudioSinkEnabled(enabled: Boolean) {
        PlaybackService.instance?.setHiFiEnabled(enabled)
        refreshAudioSnapshot()
    }

    fun setAudioAuxEnabled(enabled: Boolean) {
        PlaybackService.instance?.setAudioAuxEnabled(enabled)
        refreshAudioSnapshot()
    }

    fun sendAiMessage(prompt: String) {
        if (prompt.isBlank()) return
        val currentMsgs = _chatMessages.value.toMutableList()
        currentMsgs.add(ChatMessage("USER", prompt))
        _chatMessages.value = currentMsgs

        viewModelScope.launch {
            _isAiProcessing.value = true
            try {
                when (val outcome = musicAiAgent.processUserPrompt(prompt)) {
                    is AiOutcome.Success -> executeAgentAction(outcome.action)
                    is AiOutcome.Failure -> addAiReply("⚠ ${outcome.userMessage}")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                addAiReply("⚠ Unexpected error while processing your request.")
            } finally {
                _isAiProcessing.value = false
            }
        }
    }

    fun startVoiceInput() {
        voiceAssistantManager.startListening { spokenText ->
            sendAiMessage(spokenText)
        }
    }

    fun onMicPermissionDenied() {
        addAiReply("Microphone permission was not granted. Voice input requires microphone access.")
    }

    fun saveAiApiKey(provider: AiProvider, key: String) {
        aiKeyManager.setApiKey(provider, key)
    }

    fun selectAiProvider(provider: AiProvider) {
        aiKeyManager.setSelectedProvider(provider)
    }

    fun selectAiModel(provider: AiProvider, model: String) {
        aiKeyManager.setSelectedModel(provider, model)
    }

    private fun executeAgentAction(action: AgentAction) {
        when (action) {
            is AgentAction.PlaySong -> {
                val match = songs.value.find {
                    it.title.contains(action.query, ignoreCase = true) || it.artist.contains(action.query, ignoreCase = true)
                }
                if (match != null) {
                    playSong(match, songs.value)
                    addAiReply("▶ Playing '${match.title}' by ${match.artist}.")
                } else {
                    searchYtTracks(action.query)
                    addAiReply("🔍 Song not found locally. Searching YouTube for '${action.query}'...")
                }
            }
            is AgentAction.PlayMood -> {
                val moodSongs = songs.value.filter {
                    it.title.contains(action.mood, ignoreCase = true) ||
                            it.artist.contains(action.mood, ignoreCase = true) ||
                            it.album.contains(action.mood, ignoreCase = true)
                }.ifEmpty { songs.value.shuffled() }

                playAll(moodSongs, shuffle = true)
                addAiReply("🎨 Launched ${action.mood.uppercase()} mood playlist (${moodSongs.size} tracks)!")
            }
            is AgentAction.SearchYoutube -> {
                searchYtTracks(action.query)
                addAiReply("🔍 Searching YouTube for '${action.query}'...")
            }
            is AgentAction.DownloadYoutube -> {
                addAiReply("⬇ Searching & downloading '${action.query}' from YouTube...")
                viewModelScope.launch {
                    try {
                        when (val result = ytApiService.searchTracks(action.query)) {
                            is YtResult.Success -> {
                                if (result.value.isNotEmpty()) {
                                    _ytSearchResults.value = result.value
                                    downloadYtTrack(result.value.first())
                                } else {
                                    addAiReply("❌ No results found for '${action.query}' on YouTube.")
                                }
                            }
                            is YtResult.Failure -> addAiReply("⚠ ${result.userMessage}")
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    }
                }
            }
            is AgentAction.SetEqualizerPreset -> {
                equalizerEngine?.builtInPresets?.find { it.name.equals(action.presetName, ignoreCase = true) }?.let {
                    equalizerEngine?.applyPreset(it)
                    addAiReply("🎛 Applied '${it.name}' Equalizer Preset!")
                } ?: addAiReply("❓ EQ preset '${action.presetName}' not found.")
            }
            is AgentAction.SetSleepTimer -> {
                setSleepTimer(action.minutes)
                addAiReply("⏲ Sleep timer set for ${action.minutes} minutes.")
            }
            is AgentAction.PlaybackControl -> {
                when (action.command) {
                    "pause", "stop" -> if (isPlaying.value) togglePlayPause()
                    "play", "resume" -> if (!isPlaying.value) togglePlayPause()
                    "next", "skip" -> skipToNext()
                    "previous", "prev", "back" -> skipToPrevious()
                    "shuffle" -> toggleShuffle()
                }
                addAiReply("⏯ Executed: ${action.command.uppercase()}")
            }
            is AgentAction.ChatReply -> {
                addAiReply(action.message)
            }
        }
    }

    private fun addAiReply(text: String) {
        val currentMsgs = _chatMessages.value.toMutableList()
        currentMsgs.add(ChatMessage("AI", text))
        _chatMessages.value = currentMsgs
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

    fun searchYtTracks(query: String) {
        viewModelScope.launch {
            _isYtSearching.value = true
            try {
                when (val result = ytApiService.searchTracks(query)) {
                    is YtResult.Success -> _ytSearchResults.value = result.value
                    is YtResult.Failure -> addAiReply("⚠ ${result.userMessage}")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } finally {
                _isYtSearching.value = false
            }
        }
    }

    fun streamYtTrack(item: YtSearchResultItem) {
        viewModelScope.launch {
            try {
                when (val result = ytApiService.getStreamUrl(item.id)) {
                    is YtResult.Success -> {
                        val response = result.value
                        if (response.streamUrl.isBlank()) return@launch
                        val onlineSong = Song(
                            title = response.title,
                            artist = response.artist,
                            album = "YouTube Stream",
                            durationMs = response.durationSeconds * 1000,
                            filePath = response.streamUrl,
                            albumArtUri = response.thumbnailUrl,
                            source = "youtube",
                            youtubeId = response.id,
                            format = "AAC",
                            bitrate = 128
                        )
                        musicController.playSong(onlineSong, listOf(onlineSong))
                    }
                    is YtResult.Failure -> addAiReply("⚠ ${result.userMessage}")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            }
        }
    }

    fun downloadYtTrack(item: YtSearchResultItem) {
        viewModelScope.launch {
            try {
                // Check duplicate
                val existing = repository.getSongByYoutubeId(item.id)
                if (existing != null) {
                    addAiReply("✅ '${item.title}' is already downloaded!")
                    playSong(existing)
                    return@launch
                }

                _downloadingTrackId.value = item.id
                _downloadProgress.value = 0

                val streamResult = ytApiService.getStreamUrl(item.id)
                val response: YtStreamResponse
                when (streamResult) {
                    is YtResult.Success -> {
                        if (streamResult.value.streamUrl.isBlank()) {
                            addAiReply("❌ Could not get stream URL for '${item.title}'.")
                            _downloadingTrackId.value = null
                            _downloadProgress.value = -1
                            return@launch
                        }
                        response = streamResult.value
                    }
                    is YtResult.Failure -> {
                        addAiReply("⚠ ${streamResult.userMessage}")
                        _downloadingTrackId.value = null
                        _downloadProgress.value = -1
                        return@launch
                    }
                }

                when (val dl = ytApiService.downloadTrackToDevice(
                    context = getApplication(),
                    streamResponse = response,
                    onProgress = { progress ->
                        _downloadProgress.value = progress
                    }
                )) {
                    is YtResult.Success -> {
                        val localPath = dl.value
                        val downloadedSong = Song(
                            title = response.title,
                            artist = response.artist,
                            album = "YouTube Downloads",
                            durationMs = response.durationSeconds * 1000,
                            filePath = localPath,
                            albumArtUri = response.thumbnailUrl,
                            source = "youtube",
                            youtubeId = response.id,
                            isDownloaded = true,
                            format = "M4A",
                            bitrate = 128
                        )
                        repository.saveDownloadedSong(downloadedSong)
                        addAiReply("✅ Downloaded '${response.title}' successfully! Saved to Music/AntigravityPlayer/")
                        Toast.makeText(getApplication(), "Downloaded: ${response.title}", Toast.LENGTH_SHORT).show()
                    }
                    is YtResult.Failure -> {
                        addAiReply("⚠ ${dl.userMessage}")
                        _downloadProgress.value = -1
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } finally {
                _downloadingTrackId.value = null
                _downloadProgress.value = -1
            }
        }
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
        voiceAssistantManager.release()
        musicController.release()
        audioOutputManager.release()
    }
}
