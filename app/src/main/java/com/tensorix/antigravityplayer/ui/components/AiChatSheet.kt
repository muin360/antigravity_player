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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tensorix.antigravityplayer.ai.AiProvider
import com.tensorix.antigravityplayer.ui.theme.CardBackground
import com.tensorix.antigravityplayer.ui.theme.DarkBackground
import com.tensorix.antigravityplayer.ui.theme.PrimaryCyan
import com.tensorix.antigravityplayer.ui.theme.SecondaryViolet
import com.tensorix.antigravityplayer.ui.theme.SurfaceDark
import com.tensorix.antigravityplayer.ui.theme.TextPrimary
import com.tensorix.antigravityplayer.ui.theme.TextSecondary

data class ChatMessage(
    val sender: String, // "USER" or "AI"
    val text: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatSheet(
    messages: List<ChatMessage>,
    isProcessing: Boolean,
    isListening: Boolean,
    selectedProvider: AiProvider,
    selectedModel: String = "",
    availableModelsMap: Map<AiProvider, List<String>> = emptyMap(),
    onSendMessage: (String) -> Unit,
    onStartVoiceInput: () -> Unit,
    onSaveApiKey: (AiProvider, String) -> Unit,
    onSelectProvider: (AiProvider) -> Unit,
    onSelectModel: (AiProvider, String) -> Unit = { _, _ -> },
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var inputPrompt by remember { mutableStateOf("") }
    var showSettingsDialog by remember { mutableStateOf(false) }

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
                .padding(20.dp)
        ) {
            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(PrimaryCyan, SecondaryViolet)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, tint = Color.Black)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "ANTIGRAVITY AI AGENT",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Text(
                            text = "${selectedProvider.name} • ${if (selectedModel.isNotBlank()) selectedModel else "Auto"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = PrimaryCyan,
                            fontSize = 11.sp
                        )
                    }
                }

                Row {
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = "AI Settings", tint = TextPrimary)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Quick suggestion chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("Play workout music", "Bass boost EQ", "Set 30m timer").forEach { suggestion ->
                    Button(
                        onClick = { onSendMessage(suggestion) },
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(suggestion, fontSize = 10.sp, color = TextPrimary, maxLines = 1)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Chat Messages List
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            androidx.compose.runtime.LaunchedEffect(messages.size) {
                if (messages.isNotEmpty()) {
                    listState.animateScrollToItem(messages.size - 1)
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                if (messages.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Ask AI to play music, switch EQ presets, or search YouTube tracks!",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(messages) { msg ->
                            val isUser = msg.sender == "USER"
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(if (isUser) PrimaryCyan.copy(alpha = 0.2f) else CardBackground)
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        text = msg.text,
                                        color = if (isUser) PrimaryCyan else TextPrimary,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Bottom Input Row (Voice + Text)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Voice Mic Button
                IconButton(
                    onClick = onStartVoiceInput,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (isListening) SecondaryViolet else SurfaceDark)
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Voice Input",
                        tint = if (isListening) Color.White else PrimaryCyan
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                OutlinedTextField(
                    value = inputPrompt,
                    onValueChange = { inputPrompt = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask AI Assistant...", color = TextSecondary, fontSize = 13.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
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

                IconButton(
                    onClick = {
                        if (inputPrompt.isNotBlank()) {
                            val textToSend = inputPrompt
                            inputPrompt = ""
                            onSendMessage(textToSend)
                        }
                    },
                    enabled = !isProcessing && inputPrompt.isNotBlank()
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = PrimaryCyan, strokeWidth = 2.dp)
                    } else {
                        Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = PrimaryCyan)
                    }
                }
            }
        }
    }

    if (showSettingsDialog) {
        AiSettingsDialog(
            selectedProvider = selectedProvider,
            selectedModel = selectedModel,
            availableModelsMap = availableModelsMap,
            onSaveKey = { prov, key ->
                onSaveApiKey(prov, key)
                showSettingsDialog = false
            },
            onSelectProvider = {
                onSelectProvider(it)
            },
            onSelectModel = { prov, model ->
                onSelectModel(prov, model)
            },
            onDismiss = { showSettingsDialog = false }
        )
    }
}

@Composable
fun AiSettingsDialog(
    selectedProvider: AiProvider,
    selectedModel: String,
    availableModelsMap: Map<AiProvider, List<String>>,
    onSaveKey: (AiProvider, String) -> Unit,
    onSelectProvider: (AiProvider) -> Unit,
    onSelectModel: (AiProvider, String) -> Unit,
    onDismiss: () -> Unit
) {
    var keyInput by remember { mutableStateOf("") }
    val modelsForProvider = availableModelsMap[selectedProvider] ?: listOf(
        when (selectedProvider) {
            AiProvider.GEMINI -> "gemini-2.0-flash"
            AiProvider.OPENAI -> "gpt-4o-mini"
            AiProvider.CLAUDE -> "claude-3-7-sonnet-latest"
            AiProvider.GROQ -> "llama-3.3-70b-versatile"
        }
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkBackground,
        title = {
            Text("BYOK AI Config & ChatModel", color = TextPrimary, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Select AI Provider:", color = TextSecondary, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AiProvider.values().forEach { provider ->
                        Button(
                            onClick = { onSelectProvider(provider) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selectedProvider == provider) PrimaryCyan else SurfaceDark
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(provider.name, fontSize = 9.sp, color = if (selectedProvider == provider) Color.Black else TextPrimary)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text("Select ChatModel:", color = TextSecondary, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(6.dp))

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    modelsForProvider.forEach { modelName ->
                        val isModelSelected = selectedModel == modelName || (selectedModel.isBlank() && modelName == modelsForProvider.first())
                        Button(
                            onClick = { onSelectModel(selectedProvider, modelName) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isModelSelected) SecondaryViolet else SurfaceDark.copy(alpha = 0.6f)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(modelName, fontSize = 11.sp, color = TextPrimary)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    placeholder = { Text("Paste ${selectedProvider.name} API Key here...", fontSize = 12.sp) },
                    singleLine = true,
                    // Secret input: masked echo, password keyboard, and the
                    // value lives only in transient composition state (plain
                    // remember, NOT rememberSaveable), never in backups.
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryCyan),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSaveKey(selectedProvider, keyInput) },
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryCyan)
            ) {
                Text("Save Config", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}
