# Antigravity Player

Voice-ready, Hi-Fi-focused local music player for Android.
Kotlin + Jetpack Compose · Media3/ExoPlayer · custom Oboe/AAudio native engine (C++).

## Structure

```
app/src/main/java/com/tensorix/antigravityplayer/
├── player/    ExoPlayer service, MediaController, EQ engine
├── audio/     Oboe sink, JNI bridge, DSP processors, verifiers
├── data/      Room DB, repositories, library scanner
├── ui/        Compose screens & components
├── voice/     On-device speech recognition
└── util/      LRC parser, crash diagnostics
backend/       Optional LAN YouTube-extraction service (Phase 3)
docs/          Forensic audit & validation reports
```

## Audio architecture (short version)

- **Native path:** Media3 → `OboeAudioSink` → lock-free ring → Oboe/AAudio (Float).
  64-bit double DSP with seqlock parameter transport; explicit frame-domain
  accounting for pending data and position clocks.
- **Fallback:** pre-configured `DefaultAudioSink` + JVM mirror of the DSP chain —
  engaged automatically on unsupported formats or stream failure. Normal playback
  never depends on vendor/OEM code.
- **Truth rules:** BitPerfect requires verified direct evidence; SHARED output is
  never labeled DIRECT; unmatched device IDs stay UNKNOWN. See
  `docs/p0-playback-core-final-report.md`, `docs/forensic-truth-audit.md`,
  `docs/DSPOwnership.md`.

## Build & test

```
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:assembleRelease        # R8-minified (unsigned)
./gradlew :app:testDebugUnitTest      # JVM unit tests
```

Requires Android Studio SDK 34, NDK, CMake. minSdk 27.

## Status

- Playback core P0s implemented (seek/flush legality, route truth, lock-free write).
- Real-device matrix: see `docs/p0-playback-core-validation.md`.
- Hi-Fi / OEM activation: intentionally deferred until the device matrix is green.

## Backend (optional)

`backend/` is a LAN-only dev tool (`npm start`). Production clients require an
HTTPS endpoint configured in-app; cleartext is limited to emulator/dev hosts by
the network security config.
