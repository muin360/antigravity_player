package com.tensorix.antigravityplayer

import android.app.Application
import android.util.Log
import com.tensorix.antigravityplayer.util.CrashDiagnostics

/**
 * Antigravity Application Root.
 * Installs structured crash diagnostics; performs no main-thread work.
 */
class AntigravityApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i("AntigravityPlayer", "[STARTUP] Antigravity core initializing")

        // Structured crash & anomaly handler (delegates to the platform
        // handler after recording — never swallows crashes).
        CrashDiagnostics.installGlobalHandler(this)

        Log.i("AntigravityPlayer", "[STARTUP] Application initialized without blocking main thread")
    }
}
