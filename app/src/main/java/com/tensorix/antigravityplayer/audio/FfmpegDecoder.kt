package com.tensorix.antigravityplayer.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Audio decoder using Android's built-in MediaExtractor + MediaCodec APIs.
 * Decodes any supported audio file to a lossless 16-bit PCM WAV file.
 * Hardened against buffer underflow, infinite loops, and resource leaks.
 */
object FfmpegDecoder {

    private const val TIMEOUT_US = 10_000L // 10ms codec timeout
    private const val MAX_EOS_RETRIES = 50 // Avoid infinite loop on EOS

    /**
     * Decodes the input audio [uri] to a temporary WAV file and returns the [Uri] of the decoded file.
     * Returns null if decoding fails.
     */
    fun decodeToWav(context: Context, uri: Uri): Uri? {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var dstFile: File? = null

        return try {
            extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)

            // Find the first audio track
            val audioTrackIndex = findAudioTrack(extractor) ?: return null
            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)

            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100

            val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 2

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null

            // Create decoder
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            dstFile = File(context.cacheDir, "decoded_${System.currentTimeMillis()}.wav")
            val pcmData = decodePcm(extractor, codec)

            if (pcmData.isEmpty()) {
                dstFile.delete()
                return null
            }

            // Write WAV file
            writeWav(dstFile, pcmData, sampleRate, channelCount, 16)

            if (dstFile.exists() && dstFile.length() > 44) {
                Uri.fromFile(dstFile)
            } else {
                dstFile.delete()
                null
            }
        } catch (e: Exception) {
            android.util.Log.w("Antigravity", "Failure in " + javaClass.simpleName, e)
            dstFile?.let { if (it.exists()) it.delete() }
            null
        } finally {
            runCatching {
                codec?.stop()
                codec?.release()
            }
            runCatching {
                extractor?.release()
            }
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    private fun decodePcm(extractor: MediaExtractor, codec: MediaCodec): ByteArray {
        val outputChunks = mutableListOf<ByteArray>()
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var eosRetries = 0

        while (!outputDone && eosRetries < MAX_EOS_RETRIES) {
            // Feed input
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    if (inputBuffer != null) {
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, pts, 0)
                            extractor.advance()
                        }
                    }
                }
            }

            // Drain output
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (outputIndex >= 0) {
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                }
                val outputBuffer = codec.getOutputBuffer(outputIndex)
                if (outputBuffer != null && bufferInfo.size > 0) {
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    val chunk = ByteArray(bufferInfo.size)
                    outputBuffer.get(chunk, 0, bufferInfo.size)
                    outputChunks.add(chunk)
                }
                codec.releaseOutputBuffer(outputIndex, false)
            } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (inputDone) {
                    eosRetries++
                    try {
                        Thread.sleep(2)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }
        }

        // Combine all chunks
        val totalSize = outputChunks.sumOf { it.size }
        val result = ByteArray(totalSize)
        var offset = 0
        for (chunk in outputChunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.size)
            offset += chunk.size
        }
        return result
    }

    private fun writeWav(file: File, pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val totalSize = 36 + dataSize

        FileOutputStream(file).use { fos ->
            val header = ByteBuffer.allocate(44).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                // RIFF header
                put("RIFF".toByteArray())
                putInt(totalSize)
                put("WAVE".toByteArray())
                // fmt sub-chunk
                put("fmt ".toByteArray())
                putInt(16) // sub-chunk size
                putShort(1) // PCM format
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(byteRate)
                putShort(blockAlign.toShort())
                putShort(bitsPerSample.toShort())
                // data sub-chunk
                put("data".toByteArray())
                putInt(dataSize)
            }
            fos.write(header.array())
            fos.write(pcmData)
        }
    }
}
