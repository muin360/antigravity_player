# P0 Playback Core Validation

**Status: REAL-DEVICE VALIDATION NOT YET EXECUTED.**
This file is the evidence record required before P1 (Hi-Fi/OEM) may begin.
Code-level verification completed this pass: Kotlin+KSP compile, native build on
arm64-v8a / armeabi-v7a / x86 / x86_64 (debug), R8 release build, 74/74 JVM unit
tests including the SinkClockMath position/pending golden suite.

## Device matrix (fill in during the §22 run)

| # | Scenario | Device / Android | Route | Native device ID | Result |
|---|---|---|---|---|---|
| 1 | IEM connected -> launch -> play | _NOT RUN_ | | | |
| 2 | Speaker -> plug IEM | _NOT RUN_ | | | |
| 3 | IEM -> unplug | _NOT RUN_ | | | |
| 4 | IEM -> speaker -> IEM | _NOT RUN_ | | | |
| 5 | Seek forward | _NOT RUN_ | | | |
| 6 | Seek backward | _NOT RUN_ | | | |
| 7 | Rapid seek 10/20/5/60/15 | _NOT RUN_ | | | |
| 8 | Pause -> resume | _NOT RUN_ | | | |
| 9 | Next -> previous | _NOT RUN_ | | | |
| 10 | A->B->C->A incremental switch | _NOT RUN_ | | | |
| 11 | Route change during seek | _NOT RUN_ | | | |
| 12 | Route change during pause | _NOT RUN_ | | | |
| 13 | Native failure -> fallback | _NOT RUN_ | | | |
| 14 | Long playback 20+ min | _NOT RUN_ | | | |
| 15 | IEM plug/unplug x20 | _NOT RUN_ | | | |

## Logcat acceptance (§23)

Capture tags: AndroidRuntime, ActivityManager, AudioFlinger, AudioTrack,
OboeBridge, OboeAudioSink, Choreographer, GC.
Reject on: FATAL EXCEPTION / ANR / buffer timeout / underrun loop /
ErrorDisconnected loop / recovery loop / repeated stream restart / multi-second
UI stalls.

## Evidence to attach per row

- `adb logcat -s OboeAudioSink OboeBridge ROUTE_CHANGE ROUTE_PROOF SEEK TRACK_SWITCH RECOVERY AudioTrack`
- STREAM_OPENED line: handle/gen/exclusive/rate/deviceId
- ROUTE_PROOF line: nativeDev found/type/rate/mode
