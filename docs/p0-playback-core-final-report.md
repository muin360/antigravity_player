# P0 Playback Core Final Report

**Date:** 2026-08-23 Ã‚Â· **Scope:** P0-1..P0-10 playback core only. Hi-Fi/OEM deferred.
**Verification this session:** Kotlin+KSP compile, native builds (4 ABIs), R8 release,
**80/80 JVM unit tests** (12 suites; incl. seek-state machine + SinkClockMath goldens).
**Real-device matrix NOT run** (see p0-playback-core-validation.md).

## 0. Second-agent work audit (this pass)

An independent pass landed uncommitted changes targeting P0-6 flush legality,
vendor decoupling and route telemetry. Audited line-by-line against the prompt:

| Their change | Verdict | Action taken |
|---|---|---|
| `shared_ptr<oboe::AudioStream>` migration (Phase 19) | Ã¢Å“â€¦ correct lifetime model, error-callback compare via `.get()` | kept |
| Flush state-gate: requestPause Ã¢â€ â€™ `waitForStateChange(PausingÃ¢â€ â€™next,50ms)` Ã¢â€ â€™ flush only in Paused/Open/Stopped/Flushed Ã¢â€ â€™ else LOGW skip | Ã¢Å“â€¦ eliminates the AAudio "PAUSINGÃ¢â€ â€™flush" illegal-state error | kept |
| **Flush no longer resumes the stream** | Ã¢ÂÅ’ **CRITICAL regression**: after an in-playback seek Media3 does not re-issue play(); stream stayed PAUSED Ã¢â€ â€™ audio disappears (the exact reported symptom) | **FIXED**: Kotlin sink issues `startStream` after successful native flush when `isPlaying`, in both `handleDiscontinuity()` and `flush()` |
| Vendor probe removed from route-change + init coordinator (Phase 11) | Ã¢Å“â€¦ | kept; `prepareHardwareForDirectPlayback` call at player creation also removed Ã¢â‚¬â€ playback construction is now vendor-free end to end |
| VivoAdapter: IllegalArgumentException caught, CrashDiagnostics spam removed (Phase 12) | Ã¢Å“â€¦ single debug log instead | kept |
| SeekState machine IDLEÃ¢â€ â€™REQUESTEDÃ¢â€ â€™FLUSHINGÃ¢â€ â€™REANCHOREDÃ¢â€ â€™WRITINGÃ¢â€ â€™STABLE | Ã¢Å“â€¦ matches Ã‚Â§1.3 | kept; added SEEK_START/SEEK_END duration metrics |
| ROUTE_TELEMETRY log on open | Ã¢Å“â€¦ contract fields present | extended with Phase-3 rate domains: sourceSampleRate / nativeSampleRate / resampler=ACTIVEÃ‚Â·PASSTHROUGH |
| AudioOutputManager: opaque deviceId Ã¢â€ â€™ hardcoded type-bitmask map Ã¢â€ â€™ "single non-speaker" guess | Ã¢ÂÅ’ fabrication risk (principles 13/16): AAudio ids are opaque handles; guessing a lone wired device as active is exactly the forbidden inference | **FIXED**: strict id match only; anything else Ã¢â€ â€™ UNKNOWN propagates |

Rate-reconciliation note now encoded in code + telemetry: when SOURCE==NATIVE but
AAudio logs a flowgraph conversion (44.1Ã¢â€ â€™48k), the conversion happens inside the
HAL mixer Ã¢â‚¬â€ our resampler is PASSTHROUGH and BitPerfect stays UNAVAILABLE because
the endpoint differs from source (Ã‚Â§3.2/3.3).

## 0-b. Strict full-project audit (production pass, AI/YT excluded)

| # | Finding | Severity | Fix |
|---|---|---|---|
| R1 | Duplicate `AudioOutputManager` (service + ViewModel) each registered device callbacks & route broadcasts; both fired `AudioEngine.reconfigureRoute` on route events; `ACTION_AUDIO_BECOMING_NOISY` also triggered full reconfig during unplug-pause | **High** | Service owns the ONLY listener-registered instance (`registerSystemListeners` flag); ViewModel resolves service-first with a poll-only fallback and re-subscribes via `instanceFlow`; BECOMING_NOISY removed from route manager |
| R2 | Canonical snapshot rebuilt (vendor probes + verification pipeline) on EVERY playback state transition | **High** | Memo-cache keyed on track/route/dsp/stream identity in `scanOutputStateInternal` |
| R3 | Auto-profile engine rewrote EQ prefs (10 separate disk writes) + re-drove DSP sync on every state change, not just real route changes | **High** | Route-change dedupe in PlaybackService + single-transaction prefs batch + skip-if-unchanged in `applyHiFiProfile` |
| R4 | `FfmpegDecoder.kt`: dead code, misleading name, `Thread.sleep(2)` polling | Medium | Deleted (zero references) |
| R5 | Chip-name fabrication strings ("AK4376A/ESS Sabre", "S-Master HX", "Aqstic") returned as DAC identity from runtime probes | Medium | All vendor returns now "OEM Ã¢â‚¬Â¦ (chip unverified) / runtime-probed"; sysfs probing retained as legitimate evidence |
| R6 | DynamicProfileEngine policy strings claimed "Bit-Perfect Passthrough"/"Clock Synced" from route alone | Medium | Reworded to capability-neutral text |
| R7 | `MusicController.initController` retried session bind forever | Low | Bounded at 5 attempts with backoff |
| R8 | Garbled emoji startup logs in `AntigravityApp` | Low | Clean ASCII rewrite (behavior unchanged) |
| R9 | Stale `-dontwarn retrofit2.**` after dependency removal | Low | Removed |

Post-fix verification: 80/80 unit tests, debug build, R8 release build.

## 0-c. Strict sweep #2 - UI/data-plane defect batch (24 real fixes)

Honest count: **24 distinct defects fixed** (no padding). Suite after: 12 files, 80 tests, 0 failures.

| # | Defect | Severity | Fix |
|---|---|---|---|
| C1 | **Lyrics feature dead-wired**: nothing ever populated lyricsLines; LrcParser had no caller | High | ViewModel loads sibling track.lrc (<=2MB) on IO per track change and publishes parsed lines |
| C2-C9 | **8 flow-per-recomposition subscription bugs**: inline `?: MutableStateFlow(x)` fallback recreated the flow every recomposition, tearing down the collector each frame (AudiophileInfoScreen x3, EqualizerSheet x2, SettingsScreen x3) | Med | New stableCollect() helper hoists resolution into remember(source); all 8 sites migrated |
| C10 | currentTab nav state lost on rotation (remember) | Med | rememberSaveable |
| C11 | SmartCollectionManager.kt dead code | Low | Deleted |
| C12 | Chat LazyColumn missing item keys -> state churn on append | Low | key = hashCode |
| C13 | EQ brand list missing keys | Low | added |
| C14 | EQ profile list missing keys | Low | key = id |
| C15-C20 | OboeAudioSink: six fallbackSink!! force-unwraps (NPE-on-race class) | Low | null-safe locals / safe-call returns |
| C21 | MusicController.release() cancelled its own scope from inside a coroutine on that scope -> future leak when already cancelled | Med | direct scope.cancel() + import fix |
| C22 | Duplicate notifications: hand-rolled ongoing notify posted alongside MediaSessionService automatic media notification | Med | custom posting removed; Media3 default is single source |
| C23 | Orphaned channel/ID constants after C22 | Low | removed |
| C24 | Unused Random import in AudioVisualizer | Trivial | removed |


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
`hasPendingData()` = produced - played > 0 OR staged > 0 Ã¢â‚¬â€ computed purely in
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

1. Device matrix Ã‚Â§22 + logcat acceptance Ã‚Â§23 outstanding Ã¢â‚¬â€ final gate for GREEN.
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
P0-9 NORMAL PLAYBACK: YELLOW (architecture ready; requires Ã‚Â§22 run)
P0-10 HI-FI:          DEFERRED
OVERALL:              YELLOW Ã¢â‚¬â€ implement-complete; GREEN requires the
                      documented real-device matrix to pass.
```