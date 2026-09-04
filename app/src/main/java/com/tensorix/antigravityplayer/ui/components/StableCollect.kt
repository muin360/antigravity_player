package com.tensorix.antigravityplayer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * BUG-FIX helper: `nullableFlow?.collectAsStateWithLifecycle()` with an inline
 * `?: MutableStateFlow(default)` fallback recreated the fallback flow on EVERY
 * recomposition, tearing down and re-subscribing the collector each frame.
 *
 * This hoists the resolved flow into composition state keyed on the source, so
 * the subscription is stable. When [source] instance changes (e.g. service
 * recreated), the collector re-subscribes correctly.
 */
@Composable
fun <T> stableCollect(
    source: StateFlow<T>?,
    default: T
): State<T> {
    val resolved = remember(source) { source ?: MutableStateFlow(default) }
    return resolved.collectAsStateWithLifecycle()
}
