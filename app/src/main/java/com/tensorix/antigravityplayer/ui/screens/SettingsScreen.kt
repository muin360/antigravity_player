package com.tensorix.antigravityplayer.ui.screens

import android.content.Context
import android.os.Build
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.tensorix.antigravityplayer.audio.AudiophilePlaybackSnapshot
import com.tensorix.antigravityplayer.audio.BitPerfectState
import com.tensorix.antigravityplayer.audio.HiFiProfileManager
import com.tensorix.antigravityplayer.player.EqualizerEngine
import com.tensorix.antigravityplayer.player.PlaybackService
import com.tensorix.antigravityplayer.ui.components.AudioOutputSettings
import com.tensorix.antigravityplayer.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun SettingsScreen(
    totalTracksCount: Int,
    isScanning: Boolean,
    equalizerEngine: EqualizerEngine?,
    isHiFiSupported: Boolean,
    isBitPerfectMode: Boolean,
    isSampleRateMatching: Boolean,
    isAudioAuxEnabled: Boolean,
    hifiProfileManager: HiFiProfileManager?,
    audioSnapshot: AudiophilePlaybackSnapshot,
    onScanLibrary: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onHiFiToggle: (Boolean) -> Unit,
    onBitPerfectToggle: (Boolean) -> Unit = {},
    onSampleRateMatchingToggle: (Boolean) -> Unit = {},
    onAudioAuxToggle: (Boolean) -> Unit = {},
    onRefreshAudioSnapshot: () -> Unit,
    onForceReload: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val hiFiEnabled by com.tensorix.antigravityplayer.ui.components.stableCollect(PlaybackService.instance?.hiFiEnabled, true)
    val isTurboMode by com.tensorix.antigravityplayer.ui.components.stableCollect(PlaybackService.instance?.sampleRateMatching, true)
    val autoProfileSwitch by com.tensorix.antigravityplayer.ui.components.stableCollect(PlaybackService.instance?.autoProfileSwitch, true)

    var showDiagnostics by remember { mutableStateOf(false) }
    var showOutputConfig by remember { mutableStateOf(false) }
    var showProfilePicker by remember { mutableStateOf(false) }
    
    val activeProfile by if (hifiProfileManager != null) {
        hifiProfileManager.activeProfile.collectAsState()
    } else {
        remember { mutableStateOf(null) }
    }
    
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (showOutputConfig) {
        ModalBottomSheet(
            onDismissRequest = { showOutputConfig = false },
            sheetState = sheetState,
            containerColor = DarkBackground,
            dragHandle = null
        ) {
            AudioOutputSettings(
                audioSnapshot = audioSnapshot,
                onClose = { showOutputConfig = false },
                onConfigChange = { 
                    onRefreshAudioSnapshot()
                    onForceReload()
                }
            )
        }
    }

    if (showDiagnostics) {
        ModalBottomSheet(
            onDismissRequest = { showDiagnostics = false },
            sheetState = sheetState,
            containerColor = DarkBackground,
            dragHandle = null
        ) {
            AudiophileInfoScreen(
                snapshot = audioSnapshot,
                isBitPerfectMode = isBitPerfectMode,
                isSampleRateMatching = isSampleRateMatching,
                isHiFiEnabled = hiFiEnabled,
                onToggleBitPerfect = onBitPerfectToggle,
                onToggleSampleRateMatching = onSampleRateMatchingToggle,
                onToggleHiFi = onHiFiToggle,
                onRefresh = onRefreshAudioSnapshot,
                scrollable = true
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkBackground)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                tint = PrimaryCyan,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "SETTINGS & PREFERENCES",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary
                )
                Text(
                    text = "Audiophile Audio Engine & Local Library Settings",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // SECTION 1: AUDIO & HI-FI ENGINE
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "AUDIO & HI-FI ENGINE",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold),
                color = PrimaryCyan
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = CardBackground.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Hi-Res Float Output Status
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.GraphicEq, contentDescription = null, tint = PrimaryCyan)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Audiophile Hi-Fi Sink", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("32-bit Float + 64-bit Double DSP", color = TextSecondary, fontSize = 11.sp)
                            }
                        }
                        Switch(
                            checked = hiFiEnabled,
                            onCheckedChange = { onHiFiToggle(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = PrimaryCyan,
                                uncheckedTrackColor = SurfaceDark
                            )
                        )
                    }
                    
                    AnimatedVisibility(visible = hiFiEnabled) {
                        Column {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Sample Rate Matching", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text("Open the device stream at the track's native rate; resampler owns conversion when off", color = TextSecondary, fontSize = 10.sp)
                                }
                                Switch(
                                    checked = isTurboMode,
                                    onCheckedChange = { onSampleRateMatchingToggle(it) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = PrimaryCyan)
                                )
                            }
                        }
                    }
                }
                
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Note: 32-bit Float sink requires Android 8.0+",
                        color = SecondaryViolet,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Quick Status Badges
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val routeType = audioSnapshot.output.activeRoute?.routeType?.displayName ?: "Internal"
                    StatusBadge(text = routeType, color = PrimaryCyan)
                    StatusBadge(text = "${audioSnapshot.output.currentPlaybackBitDepth}-BIT", color = SecondaryViolet)
                    if (audioSnapshot.output.bitPerfectState == BitPerfectState.VERIFIED || audioSnapshot.output.bitPerfectState == BitPerfectState.ACTIVE_UNVERIFIED) {
                        StatusBadge(text = "DIRECT HI-RES", color = Color(0xFF00E676))
                    } else {
                        StatusBadge(text = "64-BIT DSP", color = PrimaryCyan.copy(alpha = 0.8f))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Advanced Config Row
        Card(
            onClick = { showOutputConfig = true },
            colors = CardDefaults.cardColors(containerColor = CardBackground.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Tune, contentDescription = null, tint = PrimaryCyan)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("Advanced Output Configuration", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        val apiLabel = audioSnapshot.output.playbackPath.split("\u2794").firstOrNull()?.trim() ?: "Direct HD Output"
                        Text(apiLabel, color = TextSecondary, fontSize = 11.sp)
                    }
                }
                Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // SECTION 1B: HARDWARE MASTER SWITCHES
        Text(
            text = "HARDWARE MASTER SWITCHES",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold),
            color = PrimaryCyan
        )
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = CardBackground.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Bit-Perfect Mode
                HardwareToggleRow(
                    title = "Bit-Perfect Mode",
                    subtitle = "Bypass all DSP for 100% bitstream purity",
                    icon = Icons.Default.Tune,
                    checked = isBitPerfectMode,
                    onCheckedChange = onBitPerfectToggle
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Sample Rate Matching
                HardwareToggleRow(
                    title = "Sample Rate Matching",
                    subtitle = "Force hardware clock to match source rate",
                    icon = Icons.Default.Refresh,
                    checked = isSampleRateMatching,
                    onCheckedChange = onSampleRateMatchingToggle
                )

                Spacer(modifier = Modifier.height(12.dp))

                // AudioAux Precision
                HardwareToggleRow(
                    title = "AudioAux Precision Mode",
                    subtitle = "Low-latency buffer optimization for Wired Aux",
                    icon = Icons.Default.Headset,
                    checked = isAudioAuxEnabled,
                    onCheckedChange = onAudioAuxToggle,
                    color = SecondaryViolet
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // MASTER AUDIO PROFILE
        Text(
            text = "INTELLIGENT AUDIO PROFILES",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold),
            color = PrimaryCyan
        )
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = CardBackground.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Equalizer, contentDescription = null, tint = SecondaryViolet)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Master Audio Profile", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(activeProfile?.name ?: "Audiophile Master", color = TextSecondary, fontSize = 11.sp)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (autoProfileSwitch) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(PrimaryCyan.copy(alpha = 0.15f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("AUTO", color = PrimaryCyan, fontSize = 8.sp, fontWeight = FontWeight.Black)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Button(
                                onClick = { showProfilePicker = true },
                                colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("Switch", fontSize = 11.sp, color = TextPrimary)
                            }
                        }
                    }
            }
        }

        if (showProfilePicker && hifiProfileManager != null) {
            val profiles by hifiProfileManager.allProfiles.collectAsState()
            AlertDialog(
                onDismissRequest = { showProfilePicker = false },
                title = { Text("Select Master Profile", color = PrimaryCyan) },
                containerColor = DarkBackground,
                text = {
                    Column {
                        profiles.forEach { profile ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (activeProfile?.id == profile.id) SurfaceDark else Color.Transparent)
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(profile.name, color = TextPrimary, fontWeight = FontWeight.Bold)
                                    Text(profile.description, color = TextSecondary, fontSize = 11.sp)
                                }
                                RadioButton(
                                    selected = activeProfile?.id == profile.id,
                                    onClick = { 
                                        hifiProfileManager.selectProfile(profile.id)
                                        equalizerEngine?.applyHiFiProfile(profile)
                                        showProfilePicker = false 
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = PrimaryCyan)
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showProfilePicker = false }) {
                        Text("CLOSE", color = PrimaryCyan)
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // SECTION 1C: SIGNAL PATH & DIAGNOSTICS
        Text(
            text = "AUDIOPHILE DIAGNOSTICS",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold),
            color = PrimaryCyan
        )
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            onClick = { showDiagnostics = true },
            colors = CardDefaults.cardColors(containerColor = CardBackground.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Analytics, contentDescription = null, tint = PrimaryCyan)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("Real-time Signal Path", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Inspect hardware buffers & bit-depth integrity", color = TextSecondary, fontSize = 11.sp)
                    }
                }
                Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary)
            }
        }

        Spacer(modifier = Modifier.height(30.dp))

        // SECTION 2: LIBRARY MANAGEMENT
        Text(
            text = "LIBRARY MANAGEMENT",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold),
            color = PrimaryCyan
        )
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = CardBackground.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Scan Library
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null, tint = PrimaryCyan)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Refresh Local Library", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("$totalTracksCount tracks found in storage", color = TextSecondary, fontSize = 11.sp)
                        }
                    }
                    IconButton(onClick = onScanLibrary, enabled = !isScanning) {
                        if (isScanning) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = PrimaryCyan)
                        else Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = PrimaryCyan)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(60.dp))
    }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text = text, color = color, fontWeight = FontWeight.Black, fontSize = 9.sp)
    }
}

@Composable
private fun HardwareToggleRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    color: Color = PrimaryCyan
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = icon, contentDescription = null, tint = color)
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(subtitle, color = TextSecondary, fontSize = 11.sp)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = color,
                uncheckedTrackColor = SurfaceDark
            )
        )
    }
}
