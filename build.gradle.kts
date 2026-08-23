// Top-level build file
plugins {
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    // kapt plugin removed: Room now uses KSP (version-matched to Kotlin,
    // eliminating the previous 2.0.21/2.2.10 toolchain mismatch).
    id("com.google.devtools.ksp") version "2.2.10-2.0.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
