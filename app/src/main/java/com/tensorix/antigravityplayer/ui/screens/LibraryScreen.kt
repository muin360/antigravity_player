package com.tensorix.antigravityplayer.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tensorix.antigravityplayer.data.Playlist
import com.tensorix.antigravityplayer.data.Song
import com.tensorix.antigravityplayer.ui.components.SongInfoDialog
import com.tensorix.antigravityplayer.ui.components.SongListItem
import com.tensorix.antigravityplayer.ui.theme.DarkBackground
import com.tensorix.antigravityplayer.ui.theme.PrimaryCyan
import com.tensorix.antigravityplayer.ui.theme.SecondaryViolet
import com.tensorix.antigravityplayer.ui.theme.SurfaceDark
import com.tensorix.antigravityplayer.ui.theme.TextPrimary
import com.tensorix.antigravityplayer.ui.theme.TextSecondary
import com.tensorix.antigravityplayer.ui.viewmodel.SortOrder

@Composable
fun LibraryScreen(
    songs: List<Song>,
    currentSong: Song?,
    playlists: List<Playlist>,
    searchQuery: String,
    isScanning: Boolean,
    sortOrder: SortOrder,
    isSortAscending: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onSortOrderChange: (SortOrder) -> Unit,
    onScanLibrary: () -> Unit,
    onSongClick: (Song, List<Song>) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onFavoriteToggle: (Song) -> Unit,
    onPlayAllClick: (Boolean) -> Unit,
    onAddToPlaylist: (Long, Long) -> Unit
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var selectedSongForInfo by remember { mutableStateOf<Song?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(DarkBackground, SurfaceDark)
                )
            )
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Search & Refresh Top Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search songs, artists...", color = TextSecondary) },
                leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = PrimaryCyan) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear", tint = TextSecondary)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryCyan,
                    unfocusedBorderColor = SurfaceDark,
                    focusedContainerColor = SurfaceDark.copy(alpha = 0.6f),
                    unfocusedContainerColor = SurfaceDark.copy(alpha = 0.3f),
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(onClick = onScanLibrary, enabled = !isScanning) {
                if (isScanning) {
                    CircularProgressIndicator(modifier = Modifier.height(24.dp), color = PrimaryCyan, strokeWidth = 2.dp)
                } else {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = "Rescan Library", tint = PrimaryCyan)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Action & Sort Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row {
                Button(
                    onClick = { onPlayAllClick(false) },
                    enabled = songs.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryCyan),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Play All", color = Color.Black, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = { onPlayAllClick(true) },
                    enabled = songs.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageVector = Icons.Default.Shuffle, contentDescription = null, tint = SecondaryViolet)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Shuffle", color = TextPrimary)
                }
            }

            // Sort Dropdown
            Box {
                IconButton(onClick = { sortMenuExpanded = true }) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort", tint = TextPrimary)
                }

                DropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { sortMenuExpanded = false }
                ) {
                    SortOrder.values().forEach { order ->
                        DropdownMenuItem(
                            text = {
                                val indicator = if (sortOrder == order) (if (isSortAscending) " ↑" else " ↓") else ""
                                Text("${order.name.replace("_", " ")}$indicator")
                            },
                            onClick = {
                                onSortOrderChange(order)
                                sortMenuExpanded = false
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "${songs.size} TRACKS AVAILABLE",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp),
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Song List
        if (songs.isEmpty() && isScanning) {
            // Premium scanning skeleton: pulsing placeholder rows.
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(8) {
                    val pulse = remember { androidx.compose.animation.core.Animatable(0.35f) }
                    LaunchedEffect(Unit) {
                        while (true) {
                            pulse.animateTo(
                                0.7f,
                                androidx.compose.animation.core.tween(700)
                            )
                            pulse.animateTo(
                                0.35f,
                                androidx.compose.animation.core.tween(700)
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .height(64.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(SurfaceDark.copy(alpha = pulse.value))
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(TextSecondary.copy(alpha = 0.12f))
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Box(
                                    modifier = Modifier
                                        .width(160.dp)
                                        .height(13.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(TextSecondary.copy(alpha = 0.15f))
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Box(
                                    modifier = Modifier
                                        .width(96.dp)
                                        .height(10.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(TextSecondary.copy(alpha = 0.10f))
                                )
                            }
                        }
                    }
                }
            }
        } else if (songs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = PrimaryCyan.copy(alpha = 0.7f),
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Your library is empty", style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Scan your storage to find your music", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onScanLibrary, colors = ButtonDefaults.buttonColors(containerColor = PrimaryCyan)) {
                        Text("Scan Storage", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(songs, key = { index, song -> "${song.id}_${song.filePath}_$index" }) { _, song ->
                    SongListItem(
                        song = song,
                        isPlaying = currentSong?.id == song.id,
                        playlists = playlists,
                        onSongClick = { onSongClick(song, songs) },
                        onPlayNext = { onPlayNext(song) },
                        onAddToQueue = { onAddToQueue(song) },
                        onFavoriteToggle = { onFavoriteToggle(song) },
                        onAddToPlaylist = { playlistId -> onAddToPlaylist(playlistId, song.id) },
                        onShowInfo = { selectedSongForInfo = song }
                    )
                }
            }
        }
    }

    if (selectedSongForInfo != null) {
        SongInfoDialog(
            song = selectedSongForInfo,
            onDismiss = { selectedSongForInfo = null }
        )
    }
}
