package com.tensorix.antigravityplayer.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.tensorix.antigravityplayer.audio.VendorDacManager
import com.tensorix.antigravityplayer.audio.VivoHiFiPermissionManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.util.fastForEach
import androidx.compose.runtime.Composable
import androidx.compose.ui.util.fastForEach
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.util.fastForEach
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.util.fastForEach
import androidx.compose.runtime.getValue
import androidx.compose.ui.util.fastForEach
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.util.fastForEach
import androidx.compose.runtime.remember
import androidx.compose.ui.util.fastForEach
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.media3.common.util.UnstableApi
import com.tensorix.antigravityplayer.player.PlaybackService
import com.tensorix.antigravityplayer.ui.components.EqualizerSheet
import com.tensorix.antigravityplayer.ui.components.FullPlayerSheet
import com.tensorix.antigravityplayer.ui.components.LyricsSheet
import com.tensorix.antigravityplayer.ui.components.MiniPlayer
import com.tensorix.antigravityplayer.ui.components.QueueSheet
import com.tensorix.antigravityplayer.ui.screens.AudiophileInfoScreen
import com.tensorix.antigravityplayer.ui.screens.FavoritesScreen
import com.tensorix.antigravityplayer.ui.screens.LibraryScreen
import com.tensorix.antigravityplayer.ui.screens.PlaylistsScreen
import com.tensorix.antigravityplayer.ui.screens.SettingsScreen
import com.tensorix.antigravityplayer.ui.theme.AntigravityTheme
import com.tensorix.antigravityplayer.ui.theme.DarkBackground
import com.tensorix.antigravityplayer.ui.theme.PrimaryCyan
import com.tensorix.antigravityplayer.ui.theme.SecondaryViolet
import com.tensorix.antigravityplayer.ui.theme.SurfaceDark
import com.tensorix.antigravityplayer.ui.theme.TextPrimary
import com.tensorix.antigravityplayer.ui.theme.TextSecondary
import com.tensorix.antigravityplayer.ui.viewmodel.MainViewModel

@UnstableApi
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = if (android.os.Build.VERSION.SDK_INT >= 33) {
            permissions[Manifest.permission.READ_MEDIA_AUDIO] ?: false
        } else false
        val storageGranted = permissions[Manifest.permission.READ_EXTERNAL_STORAGE] ?: false
        val hasMediaAccess = audioGranted || storageGranted
        
        com.tensorix.antigravityplayer.audio.AudioInitializationCoordinator.onPermissionsResolved(applicationContext, hasMediaAccess)
        if (hasMediaAccess) {
            viewModel.scanLibrary()
        }
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 12+ Splash Screen API — must be called before super.onCreate()
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Premium edge-to-edge: content draws behind transparent system bars.
        enableEdgeToEdge()

        checkAndRequestPermissions()

        setContent {
            AntigravityTheme {
                var showHiFiDialog by remember { mutableStateOf(false) }
                val prefs = remember { getSharedPreferences("antigravity_prefs", Context.MODE_PRIVATE) }
                
                LaunchedEffect(Unit) {
                    if (VivoHiFiPermissionManager.isVivoDevice() && 
                        !VivoHiFiPermissionManager.hasWriteSettingsPermission(this@MainActivity) &&
                        !prefs.getBoolean("hifi_permission_requested", false)) {
                        showHiFiDialog = true
                    }
                }

                if (showHiFiDialog) {
                    AlertDialog(
                        onDismissRequest = { 
                            showHiFiDialog = false 
                            prefs.edit().putBoolean("hifi_permission_requested", true).apply()
                        },
                        title = { Text("Enable Hi-Fi Audio") },
                        text = { Text("Antigravity Player needs one-time permission to activate your device's dedicated Hi-Fi DAC chip for the best audio quality. Tap Allow to enable.") },
                        confirmButton = {
                            Button(onClick = {
                                showHiFiDialog = false
                                prefs.edit().putBoolean("hifi_permission_requested", true).apply()
                                VivoHiFiPermissionManager.requestWriteSettingsPermission(this@MainActivity)
                            }) {
                                Text("Allow")
                            }
                        },
                        dismissButton = {
                            Button(onClick = {
                                showHiFiDialog = false
                                prefs.edit().putBoolean("hifi_permission_requested", true).apply()
                            }) {
                                Text("Skip")
                            }
                        }
                    )
                }

                MainAppScreen(
                    viewModel = viewModel
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (VivoHiFiPermissionManager.isVivoDevice() && VivoHiFiPermissionManager.hasWriteSettingsPermission(this)) {
            com.tensorix.antigravityplayer.audio.AudioInitializationCoordinator.triggerOptionalVendorProbe(applicationContext)
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            com.tensorix.antigravityplayer.audio.AudioInitializationCoordinator.onPermissionsResolved(applicationContext, true)
            viewModel.scanLibrary()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun MainAppScreen(
    viewModel: MainViewModel
) {
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val currentSong by viewModel.currentSong.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    // currentPositionMs intentionally NOT collected here (Phase 22):
    // 200 ms position ticks must not recompose the whole application tree.
    val durationMs by viewModel.durationMs.collectAsStateWithLifecycle()
    val shuffleEnabled by viewModel.shuffleEnabled.collectAsStateWithLifecycle()
    val repeatMode by viewModel.repeatMode.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val playlistsWithSongs by viewModel.playlistsWithSongs.collectAsStateWithLifecycle()
    val favoriteSongs by viewModel.favoriteSongs.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val isSortAscending by viewModel.isSortAscending.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val sleepTimerRemainingMs by viewModel.sleepTimerRemainingMs.collectAsStateWithLifecycle()
    val hifiActive by viewModel.hifiActive.collectAsStateWithLifecycle()

    val lyricsLines by viewModel.lyricsLines.collectAsStateWithLifecycle()
    val audioSnapshot by viewModel.audioSnapshot.collectAsStateWithLifecycle()
    val isBitPerfectMode by viewModel.isBitPerfectMode.collectAsStateWithLifecycle()
    val isSampleRateMatching by viewModel.isSampleRateMatching.collectAsStateWithLifecycle()
    val isAudioAuxEnabled by viewModel.audioAuxEnabled.collectAsStateWithLifecycle()

    var currentTab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val hapticsTick = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove) }
    var showFullPlayer by remember { mutableStateOf(false) }
    var showEqualizerSheet by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showLyricsSheet by remember { mutableStateOf(false) }
    var showAudiophileInfoSheet by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = DarkBackground,
        bottomBar = {
            Column {
                // Mini Player floating bar
                if (currentSong != null) {
                    MiniPlayer(
                        song = currentSong,
                        isPlaying = isPlaying,
                        progressMsFlow = viewModel.currentPositionState,
                        durationMs = durationMs,
                        onMiniPlayerClick = { showFullPlayer = true },
                        onPlayPauseClick = { viewModel.togglePlayPause() },
                        onNextClick = { viewModel.skipToNext() }
                    )
                }

                // Polished OLED Black Bottom Navigation
                NavigationBar(
                    containerColor = Color(0xFF06080E),
                    modifier = Modifier.clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
                    tonalElevation = 0.dp
                ) {
                    NavigationBarItem(
                        selected = currentTab == 0,
                        onClick = { hapticsTick(); currentTab = 0 },
                        icon = { Icon(imageVector = Icons.Default.LibraryMusic, contentDescription = "Tracks", modifier = Modifier.size(22.dp)) },
                        label = { Text("Tracks", fontSize = 11.sp, fontWeight = if (currentTab == 0) FontWeight.Bold else FontWeight.Normal) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = PrimaryCyan,
                            selectedTextColor = PrimaryCyan,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = Color(0x2600E5FF)
                        )
                    )

                    NavigationBarItem(
                        selected = currentTab == 1,
                        onClick = { hapticsTick(); currentTab = 1 },
                        icon = { Icon(imageVector = Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = "Playlists", modifier = Modifier.size(22.dp)) },
                        label = { Text("Playlists", fontSize = 11.sp, fontWeight = if (currentTab == 1) FontWeight.Bold else FontWeight.Normal) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = PrimaryCyan,
                            selectedTextColor = PrimaryCyan,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = Color(0x2600E5FF)
                        )
                    )

                    NavigationBarItem(
                        selected = currentTab == 2,
                        onClick = { hapticsTick(); currentTab = 2 },
                        icon = { Icon(imageVector = Icons.Default.Favorite, contentDescription = "Favorites", modifier = Modifier.size(22.dp)) },
                        label = { Text("Favorites", fontSize = 11.sp, fontWeight = if (currentTab == 2) FontWeight.Bold else FontWeight.Normal) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = PrimaryCyan,
                            selectedTextColor = PrimaryCyan,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = Color(0x2600E5FF)
                        )
                    )

                    NavigationBarItem(
                        selected = currentTab == 3,
                        onClick = { hapticsTick(); currentTab = 3 },
                        icon = { Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings", modifier = Modifier.size(22.dp)) },
                        label = { Text("Settings", fontSize = 11.sp, fontWeight = if (currentTab == 3) FontWeight.Bold else FontWeight.Normal) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = PrimaryCyan,
                            selectedTextColor = PrimaryCyan,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = Color(0x2600E5FF)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                0 -> LibraryScreen(
                    songs = songs,
                    currentSong = currentSong,
                    playlists = playlists,
                    searchQuery = searchQuery,
                    isScanning = isScanning,
                    sortOrder = sortOrder,
                    isSortAscending = isSortAscending,
                    onSearchQueryChange = { viewModel.setSearchQuery(it) },
                    onSortOrderChange = { viewModel.setSortOrder(it) },
                    onScanLibrary = { viewModel.scanLibrary() },
                    onSongClick = { song, list -> viewModel.playSong(song, list) },
                    onPlayNext = { song -> viewModel.playNext(song) },
                    onAddToQueue = { song -> viewModel.addToQueue(song) },
                    onFavoriteToggle = { song -> viewModel.toggleFavorite(song) },
                    onPlayAllClick = { shuffle -> viewModel.playAll(songs, shuffle) },
                    onAddToPlaylist = { playlistId, songId -> viewModel.addSongToPlaylist(playlistId, songId) }
                )

                1 -> PlaylistsScreen(
                    playlistsWithSongs = playlistsWithSongs,
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                    onCreatePlaylist = { viewModel.createPlaylist(it) },
                    onDeletePlaylist = { viewModel.deletePlaylist(it) },
                    onRemoveSongFromPlaylist = { playlistId, songId -> viewModel.removeSongFromPlaylist(playlistId, songId) },
                    onPlayPlaylist = { list -> viewModel.playAll(list, false) },
                    onSongClick = { song, list -> viewModel.playSong(song, list) },
                    onFavoriteToggle = { viewModel.toggleFavorite(it) }
                )

                2 -> FavoritesScreen(
                    favoriteSongs = favoriteSongs,
                    currentSong = currentSong,
                    playlists = playlists,
                    onSongClick = { song, list -> viewModel.playSong(song, list) },
                    onFavoriteToggle = { viewModel.toggleFavorite(it) },
                    onAddToPlaylist = { playlistId, songId -> viewModel.addSongToPlaylist(playlistId, songId) }
                )

                3 -> SettingsScreen(
                    totalTracksCount = songs.size,
                    isScanning = isScanning,
                    equalizerEngine = viewModel.equalizerEngine,
                    isHiFiSupported = hifiActive,
                    isBitPerfectMode = isBitPerfectMode,
                    isSampleRateMatching = isSampleRateMatching,
                    isAudioAuxEnabled = isAudioAuxEnabled,
                    hifiProfileManager = PlaybackService.instance?.hifiProfileManager,
                    audioSnapshot = audioSnapshot,
                    onScanLibrary = { viewModel.scanLibrary() },
                    onOpenEqualizer = { showEqualizerSheet = true },
                    onHiFiToggle = { viewModel.setHiFiAudioSinkEnabled(it) },
                    onBitPerfectToggle = { viewModel.setBitPerfectMode(it) },
                    onSampleRateMatchingToggle = { viewModel.setSampleRateMatching(it) },
                    onAudioAuxToggle = { viewModel.setAudioAuxEnabled(it) },
                    onRefreshAudioSnapshot = { viewModel.refreshAudioSnapshot() },
                    onForceReload = { viewModel.forceReloadAudioPipeline() }
                )
            }
        }
    }

    // Full Player Sheet Modal
    if (showFullPlayer && currentSong != null) {
        FullPlayerSheet(
            song = currentSong,
            isPlaying = isPlaying,
            currentPositionMsFlow = viewModel.currentPositionState,
            durationMs = durationMs,
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode,
            sleepTimerRemainingMs = sleepTimerRemainingMs,
            onDismiss = { showFullPlayer = false },
            onPlayPauseClick = { viewModel.togglePlayPause() },
            onNextClick = { viewModel.skipToNext() },
            onPreviousClick = { viewModel.skipToPrevious() },
            onSeekTo = { viewModel.seekTo(it) },
            onShuffleToggle = { viewModel.toggleShuffle() },
            onRepeatToggle = { viewModel.toggleRepeat() },
            onFavoriteToggle = { viewModel.toggleFavorite(it) },
            onOpenEqualizer = { showEqualizerSheet = true },
            onOpenQueue = { showQueueSheet = true },
            onOpenSleepTimer = { showSleepTimerDialog = true },
            onOpenLyrics = { showLyricsSheet = true },
            onOpenAudiophileInfo = { showAudiophileInfoSheet = true },
            isHiFiSupported = hifiActive
        )
    }

    // Synchronized Lyrics Sheet Modal
    if (showLyricsSheet && currentSong != null) {
        LyricsSheet(
            song = currentSong,
            currentPositionMsFlow = viewModel.currentPositionState,
            lyricsLines = lyricsLines,
            onDismiss = { showLyricsSheet = false }
        )
    }

    // Equalizer Sheet Modal
    if (showEqualizerSheet) {
        EqualizerSheet(
            equalizerEngine = viewModel.equalizerEngine,
            onDismiss = { showEqualizerSheet = false }
        )
    }

    // Audiophile Signal Path & DAC Modal
    if (showAudiophileInfoSheet) {
        val audiophileSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showAudiophileInfoSheet = false },
            sheetState = audiophileSheetState,
            containerColor = DarkBackground
        ) {
            AudiophileInfoScreen(
                snapshot = audioSnapshot,
                isBitPerfectMode = isBitPerfectMode,
                isSampleRateMatching = isSampleRateMatching,
                isHiFiEnabled = hifiActive,
                onToggleBitPerfect = { viewModel.setBitPerfectMode(it) },
                onToggleSampleRateMatching = { viewModel.setSampleRateMatching(it) },
                onToggleHiFi = { viewModel.setHiFiAudioSinkEnabled(it) },
                onRefresh = { viewModel.refreshAudioSnapshot() }
            )
        }
    }

    // Queue Sheet Modal
    if (showQueueSheet) {
        QueueSheet(
            queue = queue,
            currentSong = currentSong,
            onDismiss = { showQueueSheet = false },
            onSongClick = { song, list -> viewModel.playSong(song, list) },
            onRemoveFromQueue = { index -> viewModel.removeFromQueue(index) }
        )
    }

    // Sleep Timer Dialog Modal
    if (showSleepTimerDialog) {
        SleepTimerDialog(
            remainingMs = sleepTimerRemainingMs,
            onSetTimer = { minutes ->
                viewModel.setSleepTimer(minutes)
                showSleepTimerDialog = false
            },
            onCancelTimer = {
                viewModel.setSleepTimer(0)
                showSleepTimerDialog = false
            },
            onDismiss = { showSleepTimerDialog = false }
        )
    }
}

@Composable
fun SleepTimerDialog(
    remainingMs: Long,
    onSetTimer: (Int) -> Unit,
    onCancelTimer: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkBackground,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.Timer, contentDescription = null, tint = PrimaryCyan)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sleep Timer", color = TextPrimary, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                if (remainingMs > 0) {
                    val minutes = (remainingMs / 1000) / 60
                    val seconds = (remainingMs / 1000) % 60
                    Text(
                        text = java.util.Locale.getDefault().let { 
                            String.format(it, "Active Timer: %02d:%02d remaining", minutes, seconds) 
                        },
                        color = PrimaryCyan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                } else {
                    Text("Auto-pause playback after duration:", color = TextSecondary, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                }

                listOf(15, 30, 45, 60).fastForEach { mins ->
                    Button(
                        onClick = { onSetTimer(mins) },
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("$mins Minutes", color = TextPrimary)
                    }
                }
            }
        },
        confirmButton = {
            if (remainingMs > 0) {
                Button(
                    onClick = onCancelTimer,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Turn Off Timer", color = Color.White)
                }
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark)
            ) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}
