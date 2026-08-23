package com.tensorix.antigravityplayer.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tensorix.antigravityplayer.data.Song
import com.tensorix.antigravityplayer.ui.theme.*
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayerSheet(
    song: Song?,
    isPlaying: Boolean,
    currentPositionMsFlow: kotlinx.coroutines.flow.StateFlow<Long>,
    durationMs: Long,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    sleepTimerRemainingMs: Long = 0L,
    onDismiss: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onShuffleToggle: () -> Unit,
    onRepeatToggle: () -> Unit,
    onFavoriteToggle: (Song) -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenLyrics: () -> Unit = {},
    onOpenAudiophileInfo: () -> Unit = {},
    isHiFiSupported: Boolean = false
) {
    if (song == null) return

    // Phase 22: position updates recompose only this sheet, not the app tree.
    val currentPositionMs by currentPositionMsFlow.collectAsState()

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkBackground,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(SurfaceDark, DarkBackground)
                    )
                )
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header / Minimize Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Minimize",
                        tint = TextPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Text(
                    text = "NOW PLAYING",
                    style = MaterialTheme.typography.labelMedium.copy(
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = TextSecondary
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isHiFiSupported) {
                        Surface(
                            color = PrimaryCyan,
                            shape = RoundedCornerShape(999.dp)
                        ) {
                            Text(
                                text = "Hi-Fi",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    IconButton(onClick = onOpenLyrics) {
                        Icon(
                            imageVector = Icons.Default.Subtitles,
                            contentDescription = "Lyrics",
                            tint = PrimaryCyan
                        )
                    }
                    IconButton(onClick = onOpenEqualizer) {
                        Icon(
                            imageVector = Icons.Default.Equalizer,
                            contentDescription = "Equalizer",
                            tint = PrimaryCyan
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Artwork Container
            val artworkScale by animateFloatAsState(targetValue = if (isPlaying) 1f else 0.9f, label = "artworkScale")
            
            Box(
                modifier = Modifier
                    .size(280.dp)
                    .scale(artworkScale)
                    .clip(RoundedCornerShape(24.dp))
                    .background(SurfaceDark),
                contentAlignment = Alignment.Center
            ) {
                if (!song.albumArtUri.isNullOrEmpty()) {
                    AsyncImage(
                        model = song.albumArtUri,
                        contentDescription = song.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = PrimaryCyan,
                        modifier = Modifier.size(96.dp)
                    )
                }

                // Universal Hi-Fi Badge
                HiFiBadge(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Audio Visualizer
            AudioVisualizer(isPlaying = isPlaying, modifier = Modifier.fillMaxWidth().height(36.dp))

            Spacer(modifier = Modifier.height(16.dp))

            // Song Info & Favorite
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // Hi-Res Audio Badge
                    if (song.format != null || song.bitrate > 0 || song.sampleRate > 0) {
                        Spacer(modifier = Modifier.height(6.dp))
                        val badgeParts = mutableListOf<String>()
                        song.format?.let { badgeParts.add(it) }
                        if (song.bitrate > 0) badgeParts.add("${song.bitrate}kbps")
                        if (song.sampleRate > 0) {
                            val srDisplay = if (song.sampleRate >= 1000) "${song.sampleRate / 1000}kHz" else "${song.sampleRate}Hz"
                            badgeParts.add(srDisplay)
                        }
                        val isHiRes = song.sampleRate >= 48000 || song.bitrate >= 900 ||
                                song.format in listOf("FLAC", "WAV", "ALAC", "DSD")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (isHiRes) Brush.horizontalGradient(listOf(Color(0xFF00E5FF), Color(0xFF7C4DFF)))
                                        else Brush.horizontalGradient(listOf(SurfaceDark, SurfaceDark))
                                    )
                                    .clickable { onOpenAudiophileInfo() }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                val hiResPrefix = when {
                                    song.sampleRate >= 352800 -> "✦ 384kHz DXD MASTER"
                                    song.sampleRate >= 176400 -> "✦ 192kHz STUDIO MASTER"
                                    song.sampleRate >= 88200 -> "✦ 96kHz HI-RES AUDIO"
                                    isHiRes -> "✦ HI-RES"
                                    else -> ""
                                }
                                Text(
                                    text = if (hiResPrefix.isNotEmpty()) "$hiResPrefix  ${badgeParts.joinToString(" · ")}" else badgeParts.joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.8.sp,
                                        fontSize = 10.sp
                                    ),
                                    color = if (isHiRes) Color.Black else TextSecondary
                                )
                            }
                        }
                    }
                }
                IconButton(onClick = { onFavoriteToggle(song) }) {
                    Icon(
                        imageVector = if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (song.isFavorite) PrimaryCyan else TextSecondary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Progress Bar (Slider)
            val maxRange = durationMs.coerceAtLeast(1L).toFloat()
            var sliderPosition by remember { mutableStateOf(currentPositionMs.toFloat().coerceIn(0f, maxRange)) }
            var isUserSeeking by remember { mutableStateOf(false) }

            androidx.compose.runtime.LaunchedEffect(currentPositionMs, isUserSeeking) {
                if (!isUserSeeking) {
                    sliderPosition = currentPositionMs.toFloat().coerceIn(0f, maxRange)
                }
            }

            Slider(
                value = sliderPosition.coerceIn(0f, maxRange),
                onValueChange = {
                    isUserSeeking = true
                    sliderPosition = it.coerceIn(0f, maxRange)
                },
                onValueChangeFinished = {
                    isUserSeeking = false
                    onSeekTo(sliderPosition.toLong().coerceIn(0L, durationMs.coerceAtLeast(1L)))
                },
                valueRange = 0f..maxRange,
                colors = SliderDefaults.colors(
                    thumbColor = PrimaryCyan,
                    activeTrackColor = PrimaryCyan,
                    inactiveTrackColor = SurfaceDark
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatDuration(if (isUserSeeking) sliderPosition.toLong() else currentPositionMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Text(
                    text = formatDuration(durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Playback Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Shuffle
                IconButton(onClick = onShuffleToggle) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (shuffleEnabled) PrimaryCyan else TextSecondary
                    )
                }

                // Previous
                IconButton(onClick = onPreviousClick) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        tint = TextPrimary,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Play / Pause Button
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(PrimaryCyan, SecondaryViolet)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(onClick = onPlayPauseClick, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.Black,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                // Next
                IconButton(onClick = onNextClick) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = TextPrimary,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Repeat
                IconButton(onClick = onRepeatToggle) {
                    val icon = if (repeatMode == 1) Icons.Default.RepeatOne else Icons.Default.Repeat
                    Icon(
                        imageVector = icon,
                        contentDescription = "Repeat Mode",
                        tint = if (repeatMode > 0) PrimaryCyan else TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Bottom Actions: Queue & Sleep Timer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onOpenQueue) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = "Now Playing Queue",
                        tint = TextPrimary
                    )
                }

                IconButton(onClick = onOpenSleepTimer) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = "Sleep Timer",
                        tint = if (sleepTimerRemainingMs > 0) PrimaryCyan else TextSecondary
                    )
                }
            }
        }
    }
}
