package com.tensorix.antigravityplayer.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.tensorix.antigravityplayer.ui.theme.PrimaryCyan
import com.tensorix.antigravityplayer.ui.theme.SecondaryViolet

@Composable
fun AudioVisualizer(
    isPlaying: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth().height(48.dp),
    barCount: Int = 32 // More bars for "Poweramp" feel
) {
    val phase = remember { Animatable(0f) }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            phase.animateTo(
                targetValue = 100f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 2000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                )
            )
        } else {
            phase.snapTo(0f)
        }
    }

    val gradientBrush = remember {
        Brush.verticalGradient(
            colors = listOf(PrimaryCyan, SecondaryViolet)
        )
    }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val barWidth = (width / (barCount * 1.5f)).coerceAtLeast(2f)
        val spacing = ((width - (barCount * barWidth)) / (barCount + 1)).coerceAtLeast(1f)
        val currentPhase = phase.value

        for (i in 0 until barCount) {
            val x = spacing + i * (barWidth + spacing)

            val heightFraction = if (isPlaying) {
                // More complex wave for "detailed" feel
                val wave1 = kotlin.math.sin((i * 0.4f + currentPhase * 0.15f).toDouble()).toFloat() * 0.4f + 0.5f
                val wave2 = kotlin.math.cos((i * 0.8f - currentPhase * 0.1f).toDouble()).toFloat() * 0.3f + 0.5f
                val wave3 = kotlin.math.sin((i * 1.2f + currentPhase * 0.3f).toDouble()).toFloat() * 0.2f + 0.5f
                (0.15f + 0.85f * (wave1 * 0.5f + wave2 * 0.3f + wave3 * 0.2f)).coerceIn(0.1f, 1f)
            } else {
                0.08f
            }

            val barHeight = (height * heightFraction).coerceIn(2f, height)
            val y = height - barHeight

            drawRoundRect(
                brush = gradientBrush,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 3, barWidth / 3)
            )
        }
    }
}
