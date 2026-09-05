package com.tensorix.antigravityplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tensorix.antigravityplayer.data.Song
import com.tensorix.antigravityplayer.ui.theme.DarkBackground
import com.tensorix.antigravityplayer.ui.theme.PrimaryCyan
import com.tensorix.antigravityplayer.ui.theme.TextPrimary
import com.tensorix.antigravityplayer.ui.theme.TextSecondary
import java.io.File
import android.net.Uri

@Composable
fun SongInfoDialog(
    song: Song?,
    onDismiss: () -> Unit
) {
    if (song == null) return

    val uri = runCatching { Uri.parse(song.filePath) }.getOrNull()
    val isFileUri = uri?.scheme == null || uri.scheme == "file"
    val file = if (isFileUri) File(uri?.path ?: song.filePath) else null
    val fileExtension = when {
        file?.exists() == true && file.extension.isNotBlank() -> file.extension.uppercase()
        song.format?.isNotBlank() == true -> song.format.uppercase()
        else -> "LOSSLESS PCM"
    }
    val fileSizeMb = when {
        file?.exists() == true -> String.format(java.util.Locale.US, "%.2f MB", file.length() / (1024f * 1024f))
        song.fileSize > 0L -> String.format(java.util.Locale.US, "%.2f MB", song.fileSize / (1024f * 1024f))
        else -> "N/A"
    }

    val sampleRateDisplay = if (song.sampleRate > 0) {
        if (song.sampleRate >= 1000) "${song.sampleRate / 1000.0} kHz" else "${song.sampleRate} Hz"
    } else "44.1 kHz (CD Quality)"

    val bitDepthDisplay = when {
        song.format == "DSD" -> "1-bit DSD (rendered via PCM)"
        song.format in listOf("FLAC", "WAV", "ALAC", "AIFF") -> "24-bit / 32-bit Float"
        song.sampleRate >= 88200 -> "24-bit Studio Master"
        else -> "16-bit Lossless"
    }

    val bitrateDisplay = if (song.bitrate > 0) "${song.bitrate} kbps" else "Lossless Bitrate"

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkBackground,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = PrimaryCyan)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Audio Track Details", color = TextPrimary, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                DetailRow("Title", song.title)
                DetailRow("Artist", song.artist)
                DetailRow("Album", song.album)
                DetailRow("Duration", formatDuration(song.durationMs))
                DetailRow("Format / Codec", "$fileExtension ($sampleRateDisplay)")
                DetailRow("Bit Depth & Rate", "$bitDepthDisplay • $bitrateDisplay")
                DetailRow("File Size", fileSizeMb)
                DetailRow("Storage Path", if (song.filePath.length > 50) "...${song.filePath.takeLast(47)}" else song.filePath)
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryCyan),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Close", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(text = label.uppercase(), fontSize = 10.sp, color = PrimaryCyan, fontWeight = FontWeight.Bold)
        Text(text = value, fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
    }
}
