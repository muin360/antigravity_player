package com.tensorix.antigravityplayer.audio

import android.content.Context
import android.util.Log
import com.tensorix.antigravityplayer.util.CrashDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * State machine for lifecycle-safe, idempotent application & audio engine initialization.
 */
enum class AppInitializationState {
    STARTING,
    CHECKING_PERMISSIONS,
    WAITING_FOR_PERMISSION,
    LIBRARY_READY,
    AUDIO_READY,
    READY,
    ERROR
}

object AudioInitializationCoordinator {

    private const val TAG = "AudioInitCoord"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow(AppInitializationState.STARTING)
    val state: StateFlow<AppInitializationState> = _state.asStateFlow()

    private val isInitialized = AtomicBoolean(false)
    private val isVendorProbing = AtomicBoolean(false)

    fun onPermissionsResolved(context: Context, hasMediaPermission: Boolean) {
        if (!hasMediaPermission) {
            _state.value = AppInitializationState.WAITING_FOR_PERMISSION
            return
        }

        if (_state.value == AppInitializationState.READY) {
            return
        }

        _state.value = AppInitializationState.LIBRARY_READY
        initializeAudioEngine(context)
    }

    fun initializeAudioEngine(context: Context) {
        if (isInitialized.compareAndSet(false, true)) {
            scope.launch {
                try {
                    _state.value = AppInitializationState.AUDIO_READY
                    Log.i(TAG, "Initializing canonical AudioEngine...")
                    AudioEngine.invalidate()
                    
                    _state.value = AppInitializationState.READY
                    Log.i(TAG, "Audio engine and coordinator initialized successfully.")
                } catch (t: Throwable) {
                    _state.value = AppInitializationState.ERROR
                    CrashDiagnostics.record(
                        subsystem = "AUDIO_INIT_COORDINATOR",
                        stage = "initializeAudioEngine",
                        throwable = t
                    )
                }
            }
        }
    }

    fun triggerOptionalVendorProbe(context: Context) {
        if (isVendorProbing.compareAndSet(false, true)) {
            scope.launch(Dispatchers.IO) {
                try {
                    Log.i(TAG, "Running background optional vendor DAC probe...")
                    VendorDacManager.activateHardwareDac(context)
                    AudioEngine.invalidate()
                } catch (t: Throwable) {
                    CrashDiagnostics.record(
                        subsystem = "AUDIO_INIT_COORDINATOR",
                        stage = "triggerOptionalVendorProbe",
                        throwable = t
                    )
                } finally {
                    isVendorProbing.set(false)
                }
            }
        }
    }

    fun shutdown() {
        isInitialized.set(false)
        _state.value = AppInitializationState.STARTING
    }
}
