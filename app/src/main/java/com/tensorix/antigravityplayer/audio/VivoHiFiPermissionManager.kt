package com.tensorix.antigravityplayer.audio

import androidx.core.net.toUri
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.tensorix.antigravityplayer.util.CrashDiagnostics

/**
 * Crash-safe manager for Vivo & OEM WRITE_SETTINGS permission.
 */
object VivoHiFiPermissionManager {

    fun hasWriteSettingsPermission(context: Context): Boolean {
        return try {
            Settings.System.canWrite(context)
        } catch (t: Throwable) {
            CrashDiagnostics.record("VIVO_PERMISSION", "Settings.System.canWrite", t)
            false
        }
    }

    fun requestWriteSettingsPermission(activity: Activity) {
        if (!hasWriteSettingsPermission(activity)) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = "package:${activity.packageName}".toUri()
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
            } catch (t: Throwable) {
                CrashDiagnostics.record("VIVO_PERMISSION", "requestWriteSettingsPermission", t)
            }
        }
    }

    fun isVivoDevice(): Boolean {
        val manufacturer = (Build.MANUFACTURER ?: "").lowercase()
        val brand = (Build.BRAND ?: "").lowercase()
        return manufacturer.contains("vivo") || brand.contains("vivo") || brand.contains("iqoo")
    }
}
