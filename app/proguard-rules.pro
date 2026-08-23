# -------------------------------------------------------------------------
# Native JNI & Oboe Bridge Protection
# -------------------------------------------------------------------------
-keep class com.tensorix.antigravityplayer.audio.OboeBridge { *; }
-keep class com.tensorix.antigravityplayer.audio.OboeBridge$* { *; }
-keepclassmembers class com.tensorix.antigravityplayer.audio.OboeBridge$NativeStreamInfo {
    <init>(...);
    *;
}

-keepclasseswithmembernames class * {
    native <methods>;
}

# -------------------------------------------------------------------------
# Audiophile Audio Models & Enums
# -------------------------------------------------------------------------
-keep class com.tensorix.antigravityplayer.audio.AudioModelsKt { *; }
-keep class com.tensorix.antigravityplayer.audio.AudioOutputRouteType { *; }
-keep class com.tensorix.antigravityplayer.audio.BitPerfectState { *; }
-keep class com.tensorix.antigravityplayer.audio.DirectPathState { *; }
-keep class com.tensorix.antigravityplayer.audio.MixerPathState { *; }
-keep class com.tensorix.antigravityplayer.audio.EvidenceSource { *; }
-keep class com.tensorix.antigravityplayer.audio.Confidence { *; }
-keep class com.tensorix.antigravityplayer.audio.ListeningMode { *; }
-keep class com.tensorix.antigravityplayer.audio.CanonicalAudioRuntimeSnapshot { *; }
-keep class com.tensorix.antigravityplayer.audio.AudioEvidence { *; }
-keep class com.tensorix.antigravityplayer.audio.AudioFormatSnapshot { *; }
-keep class com.tensorix.antigravityplayer.audio.NativeStreamSnapshot { *; }
-keep class com.tensorix.antigravityplayer.audio.SignalProcessingPipelineSnapshot { *; }
-keep class com.tensorix.antigravityplayer.audio.DacRuntimeState { *; }
-keep class com.tensorix.antigravityplayer.audio.BitPerfectRuntimeState { *; }
-keep class com.tensorix.antigravityplayer.audio.BitPerfectVerificationResult { *; }
-keep class com.tensorix.antigravityplayer.audio.BitPerfectEvidence { *; }
-keep class com.tensorix.antigravityplayer.audio.AudioRouteCapability { *; }
-keep class com.tensorix.antigravityplayer.audio.UsbDacInfo { *; }
-keep class com.tensorix.antigravityplayer.audio.AudioOutputState { *; }
-keep class com.tensorix.antigravityplayer.audio.AudioQualityState { *; }
-keep class com.tensorix.antigravityplayer.audio.AudioTrackInfo { *; }
-keep class com.tensorix.antigravityplayer.audio.AudiophilePlaybackSnapshot { *; }
-keep class com.tensorix.antigravityplayer.audio.AudioInitializationCoordinator { *; }
-keep class com.tensorix.antigravityplayer.audio.SafeAudioParameterController { *; }
-keep class com.tensorix.antigravityplayer.audio.VendorDacManager { *; }
-keep class com.tensorix.antigravityplayer.audio.VivoHiFiPermissionManager { *; }
-keep class com.tensorix.antigravityplayer.util.CrashDiagnostics { *; }

# -------------------------------------------------------------------------
# Media3 & ExoPlayer Reflection / Codec Support
# -------------------------------------------------------------------------
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# -------------------------------------------------------------------------
# Room Database Persistence
# -------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-dontwarn androidx.room.paging.**

# -------------------------------------------------------------------------
# Retrofit / OkHttp / Serialization
# -------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
