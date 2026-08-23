package com.tensorix.antigravityplayer.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tensorix.antigravityplayer.data.Song
import com.tensorix.antigravityplayer.ui.theme.*

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MiniPlayer(
    song: Song?,
    isPlaying: Boolean,
    progressMsFlow: kotlinx.coroutines.flow.StateFlow<Long>,
    durationMs: Long,
    onMiniPlayerClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit
) {
    if (song == null) return

    val haptics = LocalHapticFeedback.current

    // Phase 22: high-frequency position updates are collected HERE so only
    // this subtree recomposes on each tick, never the whole application.
    val progressMs by progressMsFlow.collectAsState()

    val progressFraction by animateFloatAsState(
        targetValue = if (durationMs > 0) (progressMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "progress"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    // Premium micro-interactions (press-scale + synced ripple suppression)
    val barPress = rememberPressInteraction()
    val playPress = rememberPressInteraction()
    val nextPress = rememberPressInteraction()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .shadow(14.dp, RoundedCornerShape(20.dp), spotColor = PrimaryCyan.copy(alpha = glowAlpha))
            .clip(RoundedCornerShape(20.dp))
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color(0xFF0C0F18), Color(0xFF131828))
                )
            )
            .border(1.dp, Color(0x2AFFFFFF), RoundedCornerShape(20.dp))
            .clickable(interactionSource = barPress, indication = null) { onMiniPlayerClick() }
            .pressScale(barPress, pressedScale = 0.985f, hapticOnPress = false)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Gradient progress track with rounded caps (premium replacement
            // for the stock LinearProgressIndicator).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color(0x1AFFFFFF))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progressFraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(PrimaryCyan, SecondaryViolet)
                            )
                        )
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Artwork thumbnail with subtle accent ring
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF161A28))
                        .border(1.dp, PrimaryCyan.copy(alpha = 0.25f), RoundedCornerShape(12.dp)),
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
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            ),
                            color = TextPrimary,
                            maxLines = 1,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .basicMarquee(iterations = Int.MAX_VALUE)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        HiFiBadge(modifier = Modifier.scale(0.7f))
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (song.sampleRate >= 88200 || song.bitrate > 900) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(PrimaryCyan.copy(alpha = 0.2f))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text("HI-RES", color = PrimaryCyan, fontSize = 9.sp, fontWeight = FontWeight.Black)
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = song.artist,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Play/Pause with animated icon morph + press bounce + haptics
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(PrimaryCyan, SecondaryViolet)
                            )
                        )
                        .clickable(interactionSource = playPress, indication = null) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPlayPauseClick()
                        }
                        .pressScale(playPress, pressedScale = 0.88f, hapticOnPress = false),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedContent(
                        targetState = isPlaying,
                        transitionSpec = {
                            (scaleIn(initialScale = 0.6f) + fadeIn()) togetherWith
                                (scaleOut(targetScale = 0.6f) + fadeOut())
                        },
                        label = "playPauseMorph"
                    ) { playing ->
                        Icon(
                            imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play",
                            tint = Color.Black,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Next-track button with press bounce
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .clickable(interactionSource = nextPress, indication = null) {
                            onNextClick()
                        }
                        .pressScale(nextPress, pressedScale = 0.85f, hapticOnPress = false),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next Track",
                        tint = TextPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}
