# P0 Playback Core Final Report

**Date:** 2026-08-23 · **Scope:** P0-1..P0-10 playback core only. Hi-Fi/OEM deferred.
**Verification this session:** Kotlin+KSP compile, native builds (4 ABIs), R8 release,
74/74 JVM unit tests (9 new SinkClockMath golden tests). **Real-device matrix NOT run**
(see p0-playback-core-validation.md).

## 1. Playback architecture

Media3 ExoPlayer -> custom `OboeAudioSink` -> lock-free write path -> Oboe/AAudio.
`DefaultAudioSink` fallback is pre-configured before the first buffer whenever native
open fails or the format is unsupported (`-2`/`-3` verdicts are permanent per-format).
One lifecycle owner: the sink's `lifecycleLock`. AudioEngine remains the single
RECOVERY authority only; it no longer implies a second lifecycle controller.

## 2. Clock architecture

Domains separated: SOURCE_MEDIA_TIME_US (buffer presentationTimeUs),
SINK anchor (`mediaAnchorUs` = first post-discontinuity buffer's presentation time),
NATIVE OUTPUT FRAMES (hardware played, throttled 10 ms model in oboe_bridge),
and SINK MEDIA TIME = anchor + played/rate. Monotonic clamp per anchor epoch;
anchor invalidated on flush/handleDiscontinuity; TIME_UNSET returned only before
the first post-seek frame (Media3 then uses its source clock). Pause freezes
natively; resume continues. Route reconfiguration preserves the anchor.

Pure math lives in `SinkClockMath` and is golden-tested for play/pause/resume/
seek-forward/backward/rapid-seek-wins/jitter-clamp/route-continuity/unset.

## 3. Frame-domain accounting

Renamed semantics, one ring:
- `outputFramesProduced_`: frames appended to staging or passed straight through.
- `atomicFramesWritten`: hardware-accepted frames.
- staged pending = head - tail of the fixed ring (output samples).
- hardware PLAYED = throttled position (clamped to written).
`hasPendingData()` = produced - played > 0 OR staged > 0 — computed purely in
the output domain via new JNI `getStreamFrameTelemetry`. `framesWritten` as a
mixed-domain clock has been eliminated from the sink.

## 4. Lock/lifecycle model

- `lifecycleLock`: open/close/reconfigure/recover/reset/release/configure/format-change/play/pause/flush stream calls ONLY.
- Write path: zero Kotlin monitors. Atomic snapshot `(handle, generation)` ->
  native registry validates -> stale returns `-1` -> Media3 retries / recovery.
- Lazy-open uses CAS(`opening`) instead of blocking audio behind route work.
- Native: `stagingMutex` REMOVED. Fixed preallocated ring (256 KB) + render-owned
  head/tail + atomic clear-request honoured at render boundaries. No lock during
  device I/O; no vector resize on the realtime path; overflow == stall == deactivate.

## 5. Seek model

handleDiscontinuity/flush -> invalidate anchor+clamp -> native flush drops HW queue,
requests ring discard, resets resampler state (deferred to render thread) -> next
buffer re-anchors at its presentationTimeUs -> position = target + real output
progress. No pause/start added around seeks beyond Oboe's own flush precondition.
DSP delay lines intentionally NOT reset except dither accumulators on format change.

## 6. Track-switch model

`MusicController.playPlaylist`: identical live timeline -> `seekToDefaultPosition(index)+play`
(no setMediaItems). Same-track click -> seekTo(0)+play. New playlist -> full
setMediaItems preserving index/position/playWhenReady via Media3 defaults.
`playSong` prefers incremental select when the song already lives in the timeline.
TRACK_SWITCH logs every incremental selection.

## 7. Route/device correlation

Native `NativeStreamInfo` (deviceId/rate/ch/mode/perf/state) captured at open.
New `ROUTE_PROOF` log correlates the live deviceId against
`AudioManager.getDevices` resolution. Availability never equals active; unmatched
ids report UNKNOWN. No DAC/chip claims anywhere.

## 8. Native stream lifecycle

Registry (opaque monotonic handles + generation) unchanged from prior pass; close
is idempotent; error callback keeps wrapper alive via shared_ptr. Flush now also
clears position-query caches so old hardware positions cannot leak across seeks.

## 9. Fallback behavior

Triggers: library missing, open failure, unsupported encoding (-2), bad args (-3),
recovery failure. Fallback is configured with last format + attributes + volume +
playerId BEFORE first buffer and plays immediately; BitPerfect/DSP gating mirrors
native bypass rules.

## 10. Performance

Write path: no Kotlin monitor, no staging mutex during Oboe write, preallocated
ring + scratch (allocated once at open), bounded 20 ms writes, chunked drain.
Position queries: cached native model (10 ms throttle). Logging: hot paths clean;
state-transition tags only (STREAM_OPENED/FALLBACK/SEEK/ROUTE_CHANGE/TRACK_SWITCH/RECOVERY).

## 11. Tests

74/74 green. Added `SinkClockMathTest` (9): play-increase, pause-frozen, seek
forward/backward anchors, rapid-seek final-anchor-wins, jitter monotonic clamp,
route continuity, unset sentinels, pending normal/staged/partial/drained/flushed.
Existing suites (incl. DSP transparency goldens) unaffected.

## 12. Real-device evidence

NOT COLLECTED THIS SESSION (no attached device). Required procedure and matrix are
codified in docs/p0-playback-core-validation.md; PASS claims below are therefore
code-level, not device-level.

## 13. Remaining limitations

1. Device matrix §22 + logcat acceptance §23 outstanding — final gate for GREEN.
2. DSP delay lines persist across seeks by design (documented); dither errors reset only on format change.
3. `AudioRouteCapability` still lacks an id field; correlation relies on OS enumeration lookup.
4. Duplicate AudioOutputManager instances remain (pre-existing, out of P0 scope).
5. Hi-Fi/OEM activation untouched (P0-10 DEFERRED by directive).

---

## FINAL GATE

```
P0-1 POSITION:        PASS (code+unit)   / device-unverified
P0-2 PENDING DATA:    PASS (code+unit)   / device-unverified
P0-3 WRITE LOCK:      PASS (no monitor on write path)
P0-4 STAGING LOCK:    PASS (lock-free ring; no lock during device I/O)
P0-5 LIFECYCLE:       PASS (single serialized owner; CAS lazy-open)
P0-6 SEEK:            PASS (code+unit)   / device-unverified
P0-7 TRACK SWITCH:    PASS (code)        / device-unverified
P0-8 HARDWARE ROUTE:  PASS (proof telemetry + correlation log)
P0-9 NORMAL PLAYBACK: YELLOW (architecture ready; requires §22 run)
P0-10 HI-FI:          DEFERRED
OVERALL:              YELLOW — implement-complete; GREEN requires the
                      documented real-device matrix to pass.
```
