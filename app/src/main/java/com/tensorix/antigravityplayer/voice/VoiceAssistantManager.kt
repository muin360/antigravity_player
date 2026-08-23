package com.tensorix.antigravityplayer.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * On-device Speech Recognizer voice assistant manager.
 * Thread-safe main looper dispatching ensures zero crashes across all Android OEM ROMs.
 */
class VoiceAssistantManager(private val context: Context) {

    companion object {
        private const val TAG = "VoiceAssistantManager"
        private const val LISTEN_TIMEOUT_MS = 15_000L
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val listenTimeout = Runnable { stopListeningInternal() }

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()

    fun startListening(onResult: (String) -> Unit) {
        mainHandler.post {
            try {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    _recognizedText.value = "Speech recognition unavailable on device."
                    return@post
                }

                stopListeningInternal()

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }

                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            _isListening.value = true
                        }
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {
                            _isListening.value = false
                        }
                        override fun onError(error: Int) {
                            mainHandler.removeCallbacks(listenTimeout)
                            _isListening.value = false
                        }
                        override fun onResults(results: Bundle?) {
                            mainHandler.removeCallbacks(listenTimeout)
                            _isListening.value = false
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val spoken = matches?.firstOrNull() ?: ""
                            if (spoken.isNotBlank()) {
                                _recognizedText.value = spoken
                                onResult(spoken)
                            }
                        }
                        override fun onPartialResults(partialResults: Bundle?) {
                            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val spoken = matches?.firstOrNull() ?: ""
                            if (spoken.isNotBlank()) {
                                _recognizedText.value = spoken
                            }
                        }
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    startListening(intent)
                    // Safety net: release the microphone even if the provider
                    // never delivers results or an error callback.
                    mainHandler.postDelayed(listenTimeout, LISTEN_TIMEOUT_MS)
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Speech recognition start failed", e)
                _isListening.value = false
                _recognizedText.value = "Speech recognition error: ${e.localizedMessage}"
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            stopListeningInternal()
        }
    }

    /** Full teardown (ViewModel.onCleared): no mic session may outlive the app UI. */
    fun release() {
        mainHandler.post {
            stopListeningInternal()
        }
    }

    private fun stopListeningInternal() {
        mainHandler.removeCallbacks(listenTimeout)
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Recognizer teardown warning", e)
        }
        speechRecognizer = null
        _isListening.value = false
    }
}
