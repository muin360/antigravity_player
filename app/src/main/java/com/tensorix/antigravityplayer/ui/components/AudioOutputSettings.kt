package com.tensorix.antigravityplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.util.fastForEach
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.tensorix.antigravityplayer.audio.*
import com.tensorix.antigravityplayer.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun AudioOutputSettings(
    audioSnapshot: AudiophilePlaybackSnapshot,
    onClose: () -> Unit,
    onConfigChange: () -> Unit
) {
    val context = LocalContext.current
    val configManager = remember { AudioOutputConfigManager.getInstance(context) }

    // We categorize settings by route types
    val categories = listOf(
        AudioOutputRouteType.USB_DAC,
        AudioOutputRouteType.WIRED_HEADPHONES,
        AudioOutputRouteType.BLUETOOTH_A2DP,
        AudioOutputRouteType.SPEAKER
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkBackground)
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "HI-RES OUTPUT CONFIG",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp),
                color = PrimaryCyan
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        categories.fastForEach { routeType ->
            OutputCategoryCard(
                routeType = routeType,
                audioSnapshot = audioSnapshot
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        Spacer(modifier = Modifier.height(40.dp))
    }
}

@Composable
fun OutputCategoryCard(
    routeType: AudioOutputRouteType,
    audioSnapshot: AudiophilePlaybackSnapshot
) {
    val isActive = audioSnapshot.output.activeRoute?.routeType == routeType
    var expanded by remember { mutableStateOf(isActive) }

    Card(
        colors = CardDefaults.cardColors(containerColor = if (isActive) SurfaceDark else CardBackground.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when (routeType) {
                            AudioOutputRouteType.USB_DAC -> Icons.Default.Usb
                            AudioOutputRouteType.WIRED_HEADPHONES -> Icons.Default.Headset
                            AudioOutputRouteType.BLUETOOTH_A2DP -> Icons.Default.Bluetooth
                            else -> Icons.Default.Speaker
                        },
                        contentDescription = null,
                        tint = if (isActive) PrimaryCyan else TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(routeType.displayName, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        val statusText = if (isActive) {
                            "ACTIVE • ${audioSnapshot.output.currentPlaybackBitDepth}-bit / ${audioSnapshot.output.currentPlaybackSampleRate / 1000.0} kHz"
                        } else "AVAILABLE"
                        Text(statusText, color = if (isActive) PrimaryCyan else TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Black)
                    }
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                }
            }

            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color.White.copy(alpha = 0.1f))

                // TRUTH SCOPE (audit §0-e): these engine presets affect the
                // SYSTEM-FALLBACK output engine. The active low-latency Oboe
                // path negotiates rate/format directly with the device, so
                // per-route API/bit-depth/exclusive controls would be dead
                // knobs and are intentionally not shown.
            }
        }
    }
}
