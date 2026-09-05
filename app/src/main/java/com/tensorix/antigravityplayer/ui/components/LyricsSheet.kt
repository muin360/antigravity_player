package com.tensorix.antigravityplayer.ui.components

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tensorix.antigravityplayer.data.Song
import com.tensorix.antigravityplayer.ui.theme.DarkBackground
import com.tensorix.antigravityplayer.ui.theme.PrimaryCyan
import com.tensorix.antigravityplayer.ui.theme.SurfaceDark
import com.tensorix.antigravityplayer.ui.theme.TextPrimary
import com.tensorix.antigravityplayer.ui.theme.TextSecondary
import com.tensorix.antigravityplayer.util.LrcLine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsSheet(
    song: Song?,
    currentPositionMsFlow: kotlinx.coroutines.flow.StateFlow<Long>,
    lyricsLines: List<LrcLine>,
    onDismiss: () -> Unit
) {
    if (song == null) return

    // Phase 22: lyric-sync position updates stay scoped to this sheet.
    val currentPositionMs by currentPositionMsFlow.collectAsStateWithLifecycle()

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()

    // Find active line index based on current position (-1 if before first line)
    val activeIndex = lyricsLines.indexOfLast { it.timeMs <= currentPositionMs }

    LaunchedEffect(activeIndex) {
        if (lyricsLines.isNotEmpty() && activeIndex >= 0 && activeIndex in lyricsLines.indices) {
            listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
        }
    }

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
                .padding(24.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Subtitles, contentDescription = null, tint = PrimaryCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "SYNCHRONIZED LYRICS",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp
                        ),
                        color = TextPrimary
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (lyricsLines.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No synchronized lyrics found for this track.", color = TextSecondary)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Enjoy the music!", color = PrimaryCyan, fontSize = 12.sp)
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    itemsIndexed(lyricsLines, key = { i, _ -> i }) { index, line ->
                        val isActive = index == activeIndex
                        // Premium active-line emphasis: animated color + scale
                        val lineColor by androidx.compose.animation.animateColorAsState(
                            targetValue = if (isActive) PrimaryCyan else TextSecondary.copy(alpha = 0.5f),
                            animationSpec = androidx.compose.animation.core.tween(300),
                            label = "lyricColor"
                        )
                        val lineScale by androidx.compose.animation.core.animateFloatAsState(
                            targetValue = if (isActive) 1f else 0.88f,
                            animationSpec = androidx.compose.animation.core.spring(
                                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                                stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                            ),
                            label = "lyricScale"
                        )
                        val displayText = if (line.text.isEmpty()) if (isActive) "♪ ♪ ♪" else "♪" else line.text
                        Text(
                            text = displayText,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                fontSize = if (isActive) 22.sp else 16.sp,
                                textAlign = TextAlign.Center
                            ),
                            color = lineColor,
                            modifier = Modifier
                                .fillMaxWidth()
                                .scale(lineScale)
                                .padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

