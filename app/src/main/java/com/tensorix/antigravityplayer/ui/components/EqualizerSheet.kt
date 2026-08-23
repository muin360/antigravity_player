package com.tensorix.antigravityplayer.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SpatialAudio
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import com.tensorix.antigravityplayer.audio.AutoEqDatabase
import com.tensorix.antigravityplayer.audio.AutoEqProfile
import com.tensorix.antigravityplayer.player.PlaybackService
import com.tensorix.antigravityplayer.player.EqualizerEngine
import com.tensorix.antigravityplayer.ui.theme.DarkBackground
import com.tensorix.antigravityplayer.ui.theme.PrimaryCyan
import com.tensorix.antigravityplayer.ui.theme.SecondaryViolet
import com.tensorix.antigravityplayer.ui.theme.SurfaceDark
import com.tensorix.antigravityplayer.ui.theme.TextPrimary
import com.tensorix.antigravityplayer.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun EqualizerSheet(
    equalizerEngine: EqualizerEngine?,
    onDismiss: () -> Unit
) {
    if (equalizerEngine == null) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val isEnabled by equalizerEngine.isEnabled.collectAsState()
    val bandCount by equalizerEngine.bandCount.collectAsState()
    val bandFrequencies by equalizerEngine.bandFrequencies.collectAsState()
    val bandLevels by equalizerEngine.bandLevels.collectAsState()
    val minLevel by equalizerEngine.minBandLevel.collectAsState()
    val maxLevel by equalizerEngine.maxBandLevel.collectAsState()

    val bassBoost by equalizerEngine.bassBoostStrength.collectAsState()
            val preAmpGain by equalizerEngine.preAmpGainDb.collectAsState()
    val clarityGain by equalizerEngine.clarityGain.collectAsState()
    val airPresence by equalizerEngine.airPresence.collectAsState()
    val isTurboSharpness by equalizerEngine.isTurboSharpness.collectAsState()
    val warmSaturation by equalizerEngine.warmSaturation.collectAsState()
    val crossfeedLevel by equalizerEngine.crossfeedLevel.collectAsState()
    val channelBalance by equalizerEngine.channelBalance.collectAsState()
    val invertPhase by equalizerEngine.invertPhase.collectAsState()
    val stereoExpansion by equalizerEngine.stereoExpansion.collectAsState()
    val limiterThreshold by equalizerEngine.limiterThreshold.collectAsState()
    val currentPresetName by equalizerEngine.currentPresetName.collectAsState()

    val hrtfSpatialEnabled by equalizerEngine.hrtfSpatialEnabled.collectAsState()
    val hrtfRoomSize by equalizerEngine.hrtfRoomSize.collectAsState()

    val autoEqEngine = PlaybackService.instance?.autoEqEngine
    val activeAutoEqProfile by com.tensorix.antigravityplayer.ui.components.stableCollect(autoEqEngine?.activeProfile, null)
    val isAutoEqEnabled by com.tensorix.antigravityplayer.ui.components.stableCollect(autoEqEngine?.isAutoEqEnabled, false)
    var autoEqDialogExpanded by remember { mutableStateOf(false) }

    var presetMenuExpanded by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkBackground,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(SurfaceDark, DarkBackground)
                    )
                )
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            // Top Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Equalizer, contentDescription = null, tint = PrimaryCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "PARAMETRIC EQ",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        ),
                        color = TextPrimary
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isEnabled) {
                        val infiniteTransition = rememberInfiniteTransition(label = "dsp")
                        val glowAlpha by infiniteTransition.animateFloat(
                            initialValue = 0.4f,
                            targetValue = 0.8f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1500, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "glow"
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(PrimaryCyan.copy(alpha = 0.15f))
                                .border(1.dp, PrimaryCyan.copy(alpha = glowAlpha), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("64-BIT DSP ACTIVE", color = PrimaryCyan, fontSize = 9.sp, fontWeight = FontWeight.Black)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { equalizerEngine.setEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = PrimaryCyan
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // STUDIO LISTENING MODES PILLS
            val currentListeningMode by equalizerEngine.listeningMode.collectAsState()
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.tensorix.antigravityplayer.audio.ListeningMode.values().forEach { mode ->
                    val isSelected = (mode == currentListeningMode)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) PrimaryCyan.copy(alpha = 0.25f) else SurfaceDark)
                            .border(1.dp, if (isSelected) PrimaryCyan else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable { equalizerEngine.setListeningMode(mode) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(mode.displayName.substringBefore(" Mode"), color = if (isSelected) PrimaryCyan else TextPrimary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            Text(mode.badge, color = if (isSelected) Color.White else TextSecondary, fontSize = 9.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // AUTOEQ HEADPHONE CALIBRATION CARD
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isAutoEqEnabled && activeAutoEqProfile != null) PrimaryCyan.copy(alpha = 0.15f) else SurfaceDark)
                    .border(1.dp, if (isAutoEqEnabled && activeAutoEqProfile != null) PrimaryCyan else Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                    .clickable { autoEqDialogExpanded = true }
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Headphones,
                            contentDescription = null,
                            tint = if (isAutoEqEnabled && activeAutoEqProfile != null) PrimaryCyan else TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "AutoEQ™ CALIBRATION",
                                    color = if (isAutoEqEnabled && activeAutoEqProfile != null) PrimaryCyan else TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    letterSpacing = 1.sp
                                )
                                if (isAutoEqEnabled && activeAutoEqProfile != null) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(PrimaryCyan)
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                    ) {
                                        Text("HARMAN", color = Color.Black, fontSize = 8.sp, fontWeight = FontWeight.Black)
                                    }
                                }
                            }
                            Text(
                                text = if (isAutoEqEnabled && activeAutoEqProfile != null) activeAutoEqProfile!!.displayName else "Tap to Calibrate Audiophile Headphones",
                                color = if (isAutoEqEnabled && activeAutoEqProfile != null) TextPrimary else TextSecondary,
                                fontSize = 13.sp,
                                fontWeight = if (isAutoEqEnabled && activeAutoEqProfile != null) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }

                    Button(
                        onClick = { autoEqDialogExpanded = true },
                        colors = ButtonDefaults.buttonColors(containerColor = if (isAutoEqEnabled) PrimaryCyan.copy(alpha = 0.25f) else SurfaceDark),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(if (isAutoEqEnabled) "CHANGE" else "SELECT", color = PrimaryCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Presets row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Preset: $currentPresetName", color = TextPrimary, fontWeight = FontWeight.SemiBold)

                Box {
                    Button(
                        onClick = { presetMenuExpanded = true },
                        enabled = isEnabled,
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(currentPresetName, color = PrimaryCyan, fontSize = 13.sp)
                    }

                    DropdownMenu(
                        expanded = presetMenuExpanded,
                        onDismissRequest = { presetMenuExpanded = false }
                    ) {
                        equalizerEngine.builtInPresets.forEach { preset ->
                            DropdownMenuItem(
                                text = { Text(preset.name) },
                                onClick = {
                                    equalizerEngine.applyPreset(preset)
                                    presetMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Frequency Band Sliders
            Text(text = "FREQUENCY BANDS (dB)", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceDark.copy(alpha = 0.5f))
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                for (i in 0 until bandCount) {
                    val freqHz = if (i < bandFrequencies.size) bandFrequencies[i] else 0
                    val level = if (i < bandLevels.size) bandLevels[i] else 0

                    val freqLabel = if (freqHz >= 1000) "${freqHz / 1000}k" else "${freqHz}Hz"
                    val dbValue = (level / 100f)

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(48.dp)
                    ) {
                        Text(
                            text = String.format(java.util.Locale.US, "%.1f", dbValue),
                            fontSize = 11.sp,
                            color = if (isEnabled) PrimaryCyan else TextSecondary,
                            fontWeight = FontWeight.Bold
                        )

                        Slider(
                            value = level.toFloat(),
                            onValueChange = { equalizerEngine.setBandLevel(i.toShort(), it.toInt().toShort()) },
                            valueRange = minLevel.toFloat()..maxLevel.toFloat(),
                            enabled = isEnabled,
                            colors = SliderDefaults.colors(
                                thumbColor = PrimaryCyan,
                                activeTrackColor = PrimaryCyan,
                                inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                            ),
                            modifier = Modifier.height(160.dp)
                        )

                        Text(
                            text = freqLabel,
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // DSP Controls (all controls below are LIVE on the active audio path)
            Text(text = "DSP & AUDIO EFFECTS", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Spacer(modifier = Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceDark.copy(alpha = 0.5f))
                    .padding(16.dp)
            ) {
                // Pre-Amp Gain
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Digital Pre-Amp", color = TextPrimary, fontSize = 14.sp)
                    Text(String.format(java.util.Locale.US, "%.1f dB", preAmpGain), color = PrimaryCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = preAmpGain,
                    onValueChange = { equalizerEngine.setPreAmpGain(it) },
                    valueRange = -15f..15f,
                    enabled = isEnabled,
                    colors = SliderDefaults.colors(thumbColor = PrimaryCyan, activeTrackColor = PrimaryCyan)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Bass Boost
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Bass Boost", color = TextPrimary, fontSize = 14.sp)
                    Text("${(bassBoost / 10)}%", color = PrimaryCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = bassBoost.toFloat(),
                    onValueChange = { equalizerEngine.setBassBoost(it.toInt().toShort()) },
                    valueRange = 0f..1000f,
                    enabled = isEnabled,
                    colors = SliderDefaults.colors(thumbColor = PrimaryCyan, activeTrackColor = PrimaryCyan)
                )

                // NOTE: the old "3D Surround Virtualizer" and "Loudness"
                // sliders were REMOVED (truth audit §0-d): they only drove
                // framework AudioEffect objects that are permanently detached
                // while the 64-bit DSP owns the chain, so they had zero effect
                // on the actual audio path. Dead controls are worse than no
                // controls.

                Spacer(modifier = Modifier.height(12.dp))

                // Clarity Presence Enhancer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Clarity / Presence", color = TextPrimary, fontSize = 14.sp)
                    Text(String.format(java.util.Locale.US, "%.1f dB", clarityGain), color = PrimaryCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = clarityGain,
                    onValueChange = { equalizerEngine.setClarityGain(it) },
                    valueRange = 0f..10f,
                    enabled = isEnabled,
                    colors = SliderDefaults.colors(thumbColor = PrimaryCyan, activeTrackColor = PrimaryCyan)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Air Presence
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Ultimate Air Presence", color = TextPrimary, fontSize = 14.sp)
                    Text("${String.format(java.util.Locale.US, "%.1f", airPresence)} dB", color = PrimaryCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = airPresence,
                    onValueChange = { equalizerEngine.setAirPresence(it) },
                    valueRange = 0f..12f,
                    enabled = isEnabled,
                    colors = SliderDefaults.colors(thumbColor = SecondaryViolet, activeTrackColor = SecondaryViolet)
                )

                Spacer(modifier = Modifier.height(12.dp))
                
                // Warm Saturation (Poweramp Valve)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Valve Warmth", color = TextPrimary, fontSize = 14.sp)
                    Text("${(warmSaturation * 100).toInt()}%", color = SecondaryViolet, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = warmSaturation,
                    onValueChange = { equalizerEngine.setWarmSaturation(it) },
                    valueRange = 0f..0.5f,
                    enabled = isEnabled,
                    colors = SliderDefaults.colors(thumbColor = SecondaryViolet, activeTrackColor = SecondaryViolet)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Crossfeed (Meier)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Meier Crossfeed", color = TextPrimary, fontSize = 14.sp)
                    Text("${(crossfeedLevel * 100).toInt()}%", color = PrimaryCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = crossfeedLevel,
                    onValueChange = { equalizerEngine.setCrossfeedLevel(it) },
                    valueRange = 0f..1.0f,
                    enabled = isEnabled,
                    colors = SliderDefaults.colors(thumbColor = PrimaryCyan, activeTrackColor = PrimaryCyan)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Stereo Expansion
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Stereo Width", color = TextPrimary, fontSize = 14.sp)
                    Text("${(stereoExpansion * 100).toInt()}%", color = PrimaryCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = stereoExpansion,
                    onValueChange = { equalizerEngine.setStereoExpansion(it) },
                    valueRange = 1.0f..2.0f,
                    enabled = isEnabled,
                    colors = SliderDefaults.colors(thumbColor = PrimaryCyan, activeTrackColor = PrimaryCyan)
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Channel Balance
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Channel Balance", color = TextPrimary, fontSize = 14.sp)
                    Text(if (channelBalance < 0) "L ${(channelBalance * -100).toInt()}%" else if (channelBalance > 0) "R ${(channelBalance * 100).toInt()}%" else "Center", color = PrimaryCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = channelBalance,
                    onValueChange = { equalizerEngine.setChannelBalance(it) },
                    valueRange = -1.0f..1.0f,
                    enabled = isEnabled,
                    colors = SliderDefaults.colors(thumbColor = SecondaryViolet, activeTrackColor = SecondaryViolet)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Phase Inversion
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Invert Phase (L/R)", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("Fixes polarity issues in some IEMs/Cables", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = invertPhase,
                        onCheckedChange = { equalizerEngine.setInvertPhase(it) },
                        enabled = isEnabled,
                        colors = SwitchDefaults.colors(checkedThumbColor = PrimaryCyan)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Sub-Bass Mono (<80Hz)
                val subBassMono by equalizerEngine.subBassMono.collectAsState()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Sub-Bass Mono (<80Hz)", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("Centers deep sub-bass for punchier headphone response", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = subBassMono,
                        onCheckedChange = { equalizerEngine.setSubBassMono(it) },
                        enabled = isEnabled,
                        colors = SwitchDefaults.colors(checkedThumbColor = PrimaryCyan)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Limiter Threshold
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Safety Limiter (dB)", color = TextPrimary, fontSize = 14.sp)
                    Text(String.format(java.util.Locale.US, "%.1f dB", limiterThreshold), color = SecondaryViolet, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = limiterThreshold,
                    onValueChange = { equalizerEngine.setLimiterThreshold(it) },
                    valueRange = -6.0f..0.0f,
                    enabled = isEnabled,
                    colors = SliderDefaults.colors(thumbColor = SecondaryViolet, activeTrackColor = SecondaryViolet)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // HRTF 3D Spatial Audio (Studio Room Monitor Emulation)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.SpatialAudio, contentDescription = null, tint = SecondaryViolet, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("3D Studio Room (HRTF)", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("Bauer/Linkwitz head-shadow & 280µs ITD delay", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = hrtfSpatialEnabled,
                        onCheckedChange = { equalizerEngine.setHrtfSpatialEnabled(it) },
                        enabled = isEnabled,
                        colors = SwitchDefaults.colors(checkedThumbColor = SecondaryViolet)
                    )
                }

                if (hrtfSpatialEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Room Size / Diffusion", color = TextPrimary, fontSize = 13.sp)
                        Text("${(hrtfRoomSize * 100).toInt()}%", color = SecondaryViolet, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = hrtfRoomSize,
                        onValueChange = { equalizerEngine.setHrtfRoomSize(it) },
                        valueRange = 0.0f..1.0f,
                        enabled = isEnabled,
                        colors = SliderDefaults.colors(thumbColor = SecondaryViolet, activeTrackColor = SecondaryViolet)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Turbo Sharpness (Harmonic Exciter)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("64-bit Turbo Path", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("Harmonic richness + safety limiter", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = isTurboSharpness,
                        onCheckedChange = { equalizerEngine.setTurboSharpness(it) },
                        enabled = isEnabled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = PrimaryCyan,
                            uncheckedTrackColor = SurfaceDark
                        )
                    )
                }
            }
        }
    }

    if (autoEqDialogExpanded) {
        AutoEqSelectionDialog(
            activeProfile = activeAutoEqProfile,
            onSelectProfile = { profile ->
                autoEqEngine?.applyProfile(profile, equalizerEngine)
                autoEqDialogExpanded = false
            },
            onDisableAutoEq = {
                autoEqEngine?.disableAutoEq(equalizerEngine)
                autoEqDialogExpanded = false
            },
            onDismiss = { autoEqDialogExpanded = false }
        )
    }
}

@Composable
fun AutoEqSelectionDialog(
    activeProfile: AutoEqProfile?,
    onSelectProfile: (AutoEqProfile) -> Unit,
    onDisableAutoEq: () -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedBrand by remember { mutableStateOf<String?>(null) }

    val allProfiles = remember { AutoEqDatabase.profiles }
    val brands = remember { listOf("ALL") + AutoEqDatabase.allBrands }

    val filteredProfiles = remember(searchQuery, selectedBrand) {
        val searched = if (searchQuery.isNotBlank()) AutoEqDatabase.search(searchQuery) else allProfiles
        if (selectedBrand == null || selectedBrand == "ALL") searched
        else searched.filter { it.brand.equals(selectedBrand, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkBackground,
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Headphones, contentDescription = null, tint = PrimaryCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "AutoEQ™ HEADPHONE CALIBRATION",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        letterSpacing = 1.sp
                    )
                }
                Text(
                    text = "Harman Target 2018/2019 Calibrated Profiles",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().height(420.dp)) {
                // Search Input
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search 20+ Models (Sennheiser, Sony...)", fontSize = 12.sp, color = TextSecondary) },
                    leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = PrimaryCyan) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryCyan,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Brand Filter Chips
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(brands, key = { it }) { brand ->
                        val isSelected = (selectedBrand == brand) || (selectedBrand == null && brand == "ALL")
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) PrimaryCyan.copy(alpha = 0.25f) else SurfaceDark)
                                .border(1.dp, if (isSelected) PrimaryCyan else Color.Transparent, RoundedCornerShape(8.dp))
                                .clickable { selectedBrand = if (brand == "ALL") null else brand }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = brand,
                                color = if (isSelected) PrimaryCyan else TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Headphone List
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredProfiles, key = { it.id }) { profile ->
                        val isActive = (profile.id == activeProfile?.id)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isActive) PrimaryCyan.copy(alpha = 0.15f) else SurfaceDark)
                                .border(1.dp, if (isActive) PrimaryCyan else Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                                .clickable { onSelectProfile(profile) }
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = profile.displayName,
                                        color = if (isActive) PrimaryCyan else TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(profile.targetCurve, color = SecondaryViolet, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                        Text(" • ${profile.type.displayName} • ${profile.bands.size} PEQ Bands", color = TextSecondary, fontSize = 10.sp)
                                    }
                                }

                                if (isActive) {
                                    Icon(imageVector = Icons.Default.Check, contentDescription = "Active", tint = PrimaryCyan, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (activeProfile != null) {
                Button(
                    onClick = onDisableAutoEq,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Disable Calibration", color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Close", color = TextPrimary, fontSize = 12.sp)
            }
        }
    )
}
