package com.tensorix.antigravityplayer.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Premium micro-interaction kit.
 *
 * Usage:
 *   val src = rememberPressInteraction()
 *   Modifier
 *     .clickable(interactionSource = src, indication = null) { ... }
 *     .pressScale(src)
 */
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.965f,
    hapticOnPress: Boolean = true
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    if (pressed && hapticOnPress) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "pressScale"
    )
    scale(scale)
}

/** Interaction-source factory paired with [pressScale]. */
@Composable
fun rememberPressInteraction(): MutableInteractionSource =
    remember { MutableInteractionSource() }
