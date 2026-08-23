# Final Forensic Remediation Report

**Date:** 2026-08-23 · **Base commit:** `15257cd` (local `main`, ahead of stated `9d66b0a`)
**Verification performed this session:** full Kotlin+KSP compile (debug/release), native CMake
build for all 4 ABIs (debug + RelWithDebInfo), R8 release minification, lint-vital,
65 JVM unit tests green, R8 mapping inspection for JNI keep rules.
**NOT performed:** real-device matrix (Phase 36-37) - no device/emulator attached to this
session. Everything below is code-level evidence; hardware claims remain unverified.

---

## Q. Audio-chain pass 2 (input/output/DAC/Hi-Fi/DSP deep audit)

Second forensic pass over the layers surrounding the engine. All fixes verified by the
full build + test suite including a NEW DSP transparency golden test.

| # | Problem | Root cause | File(s) | Fix |
|---|---|---|---|---|
| Q1 | Fallback-path dither injected TPDF noise into EVERY sample unconditionally, ignoring `ditherStrength=0`, with no requantization | Dither block had no gate; contradicted float transport | `Audiophile64BitDspProcessor` | Mirrored native contract: gated behind `strength>0 && depth<32`, adds an ACTUAL quantization-to-grid step; silence is now bit-exact zero (golden test) |
| Q2 | Per-sample heap allocations (`DoubleArray(channelCount)`, `doubleArrayOf(...)`) inside the render loop -> GC churn on fallback path | Allocation inside frame loop | `Audiophile64BitDspProcessor` | Reusable render-thread scratch (`frameSamples`/`upsampledPair`); hot loop allocation-free |
| Q3 | Unconditional fixed-20kHz AA low-pass band-limited legitimate hi-res content on fallback path | Copied from old native design | `Audiophile64BitDspProcessor` | Conditional on nonlinear stages engaged; corner = min(20k, fs*0.45); exciter HPF now designed at 2*fs to match its interpolated drive rate |
| Q4 | Blanket -0.11 dBFS ceiling modified signal even in neutral mode | Legacy safety ceiling | `Audiophile64BitDspProcessor` | Removed; unity + hard ±1 clamp (limiter remains opt-in safety) |
| Q5 | Disabling the EQ silently re-enabled the framework `Equalizer` with the same bands via session reattach | setEnabled(false) fell into attach branch | `EqualizerEngine` | Off means off: effects released, never reattached on disable |
| Q6 | Fresh installs auto-applied AUDIOPHILE listening signature (+3.5 dB pre-amp/clarity, warmth, air) defeating neutral defaults | Default listening mode + default pre_amp_db=3.5 | `EqualizerEngine` | Defaults now REFERENCE (transparent) and pre-amp 0 dB; signatures are explicit opt-in |
| Q7 | Band-slider updates dropped within a 16 ms throttle window (UI/DSP desync risk) | Early-return throttling | `EqualizerEngine` | Throttle removed; post-hardening setters are cheap atomic stores |
| Q8 | Two writers fought over one native DVC parameter: sink.setVolume (player volume) vs system-volume receiver -> audible level jumps | Volume ownership split across components | `OboeAudioSink`, `PlaybackService` | Single-owner model: player volume applies only to fallback AudioTrack; native DVC mirror owned exclusively by system-volume receiver; receiver disabled entirely during BitPerfect (purity) |
| Q9 | Vendor HAL `setParameters` (blocking binder) executed on MAIN thread during BitPerfect player construction -> jank/ANR risk | Sync call path | `VendorDacManager` | `prepareHardwareForDirectPlayback` now runs on a dedicated background executor |
| Q10 | Diagnostics log presented inferred values as measured facts ("AudioTrack Format Encoding", "Active AudioFlinger Thread") while the Oboe path always opens Float streams | Truthfulness | `PlaybackService` | Log relabelled as fallback-policy/inferred fields; fabricated claims removed |
| Q11 | `onReset()` left dcRemoval/dcBlocker/subBass/air/clarity filter states stale | Incomplete reset list | `Audiophile64BitDspProcessor` | Full state reset incl. dither error accumulators |

### New regression tests

- `AudiophileDspTransparencyTest`: neutral chain transparent for a 1 kHz tone (bound covers
  DC-blocker settle), **bit-perfect bypass = exact passthrough**, and **digital silence stays
  exactly zero** (sharp tripwire against any unconditional dither/noise regression).
- Suite totals: **65 tests, all passing**.

### Remaining audio-domain limitations

1. Duplicate `AudioOutputManager` instances (service + ViewModel) still exist; consolidation deferred (behaviour-risk > session scope).
2. Framework treble-strength write also writes the last EQ band when the legacy framework path is active (double application on that legacy-only path).
3. Verifier stack (`HardwareHiFiVerifier`, `UniversalHardwareDetector`) still infers path models from route metadata; labels now say "inferred" but true measurement would require loopback/audio-policy introspection.
4. Real-device validation matrix (Phase 36-37) still outstanding.

---

## A. P0 issues fixed

| # | Problem | Root cause | File(s) | Fix | Test/verification |
|---|---|---|---|---|---|
| A1 | Stale JNI handle -> UAF: 12 entry points cast raw `jlong` to `OboeStreamWrapper*` bypassing the registry; handles were pointer addresses (reusable) | Missing registry discipline | `oboe_bridge.cpp` | Monotonic opaque handle IDs; every entry validates via registry (+ generation where supplied); invalid handles are safe no-ops/errors, never dereferences | Compiles; contract documented in code; JVM-side sink updated |
| A2 | writeDirect trusted caller geometry: no direct-buffer capacity check, overflow-prone `numFrames*channels`, wrong PCM map (`case 3` = PCM_8BIT decoded as 24-bit packed) | Untrusted JNI inputs | `oboe_bridge.cpp` | Capacity validation vs `GetDirectBufferCapacity`; int64 range checks; encoding map fixed incl. proper unsigned 8-bit; unknown encodings return `-2` | Sink routes `-2` to permanent fallback |
| A3 | Resampled partial writes dropped audio and could emit unresampled data when the resampler buffered input (returned 0 frames) | Consumption estimated from output ratio; staging absent | `audiophile_resampler.*`, `oboe_bridge.cpp`, `OboeAudioSink.kt` | Explicit `Result{outputFrames, inputFramesConsumed}` contract; pending-output staging drained across calls; unresampled fallback removed | Unit-tested parser contract on Kotlin side; C++ path reviewed line-by-line |
| A4 | Render thread skipped ALL PEQ filtering whenever `try_lock(peqMutex_)` failed; `bandGainsDb_` plain doubles raced between UI and render threads | Locking design | `audiophile_dsp.*` | Seqlock parameter snapshots; coefficient changes applied by render thread at block boundaries (delay, never skip); PEQ specs owned by render thread | Full DSP rewrite compiled all ABIs; concurrency model in DSPOwnership.md |
| A5 | BYOK API keys in plaintext SharedPreferences; backup-extractable; Gemini key in URL query | Storage/auth design | `AiKeyManager.kt`, manifest, res/xml/*, `MusicAiAgent.kt` | Keystore-backed EncryptedSharedPreferences with verified one-time migration + plaintext wipe; memory-only fallback if Keystore broken; backup/dataExtraction rules exclude both prefs files; Gemini uses `x-goog-api-key` header | Build + rules wired into manifest |
| A6 | Cleartext-only YT endpoints would silently fail on API>=28 and leak capability | No NSC; hardcoded emulator URLs everywhere | `network_security_config.xml`, `YtApiService.kt`, build.gradle.kts | NSC blocks cleartext except scoped dev hosts; DEV/PROD BuildConfig URLs; release rejects insecure custom URLs | Release build compiles with new config |
| A7 | All AI/YT HTTP calls could hang indefinitely; failures collapsed to silent nulls | Missing timeouts/error taxonomy | `MusicAiAgent.kt`, `YtApiService.kt` | OkHttp call/connect/read/write timeouts (45s ceiling); structured `AiOutcome.Failure{code}` / `YtResult.Failure{code}`; coroutine cancellation aborts sockets | New unit tests cover parsing taxonomy |
| A8 | Main-thread whole-file reads on every track transition (ReplayGain tags) | Blocking IO in `onMediaItemTransition` | `MusicController.kt` | Bounded 1 MB header read on Dispatchers.IO, result posted back | Code-reviewed; jank source eliminated |

## B. P1/P2 fixed

- **DB safety:** destructive migration removed; `exportSchema=true` (app/schemas/4.json);
  kapt replaced by KSP version-matched to Kotlin 2.2.10 (fixes toolchain mismatch).
- **UI recomposition:** 200 ms position flow no longer collected at MainActivity root;
  MiniPlayer/FullPlayerSheet/LyricsSheet collect it locally (Phase 22).
- **Voice lifecycle:** recognizer timeout releases mic after 15 s; `release()` teardown
  hooked to `ViewModel.onCleared`; printStackTrace removed.
- **Dead code removed:** `AiOrchestrator.kt`, `VoiceCommandListener.kt`.
- **Truthful DSP defaults:** exciter/clarity/warmth/triode/pentode/air/dither default to
  neutral on BOTH native and Kotlin paths (fresh installs); "Flat" is now transparent.
- **Backend hardening (`server.js`):** videoId regex validation, query caps, per-IP rate
  limit, CORS allowlist, optional AUTH_TOKEN, sanitized 5xx errors, 127.0.0.1 default bind
  with documented LAN opt-in, server timeouts, nodemon devDependency added.
- **Fabricated claims removed:** ">140 dB SNR", "AK4376A air shelf", "2x oversampling AA"
  reframed honestly; DSD marked as having no decode path (see forensic-truth-audit.md).
- **Unused dependencies removed:** Retrofit, converter-gson, media3-ui, media3-exoplayer-rtsp,
  navigation-compose (OkHttp retained - now genuinely used).
- **LrcParser bug:** single-digit fractional timestamps (`[0:05.5]`) now parse as documented.
- **18 broken `\${...}` string templates** in MainViewModel chat replies fixed.

## M. Tests

- Suite at remediation end: **62 tests, all passing** (`gradlew :app:testDebugUnitTest`).
- Added: `AiAgentParsingTest` (6), `YtVideoIdValidationTest` (2), `LrcParserTest` (5).
- Real org.json added to test classpath (Android jar ships stubs only).
- Existing audio-package suites (BitPerfectVerifier, OboeAudioSink, AudioEngine, ...) still pass.
- Native golden-vector host tests: NOT built in this environment (no host C++ toolchain
  verified); the pure DSP/resampler cores are now allocation-free and side-effect isolated,
  which makes them directly host-testable in CI (Linux) as a follow-up.

## N. Build

- `assembleDebug`: OK (app-debug.apk, 21.8 MB)
- `assembleRelease`: OK with R8 minify + resource shrink + lintVital
  (app-release-unsigned.apk, 5.1 MB); mapping.txt confirms OboeBridge/JNI keeps.
- Gradle daemon Metaspace raised (R8 OOM during first attempt); jvmargs now
  `-Xmx4096m -XX:MaxMetaspaceSize=1024m`.

## O. Real-device validation

**NOT EXECUTED - environment has no attached device/emulator.**
The Phase 36 matrix (20 scenarios incl. route changes, seeks, BitPerfect toggles, USB DAC,
recovery storms) remains the gating step before any public release claim.

## P. Remaining limitations

1. Device verification outstanding (above).
2. DSD decode path does not exist; UI must not advertise it.
3. Production YT backend URL is a placeholder that must be deployed + set before shipping.
4. Model ID lists are conservative/stable but should be revalidated against provider
   catalogs periodically.
5. `PlaybackService.instance` static service locator still couples engine<->service;
   full DI refactor intentionally deferred (behaviour-risk outweighed session scope).
6. Room schema history starts at v4 export; the FIRST future bump must ship a tested
   Migration (framework now in place).
7. Native golden vectors + fuzz targets recommended as immediate CI follow-up.

---

## Honest release gate

| Area | Verdict | Basis |
|---|---|---|
| SECURITY | **GREEN (static)** / device-unverified | Keys encrypted+migrated+backup-excluded; header auth; no secrets in URLs/logs; backend hardened; cleartext scoped to dev |
| JNI | **GREEN (static)** | Registry+generation on every entry; bounds/capacity checks; exception-checked info path; idempotent close |
| NATIVE LIFETIME | **GREEN (static)** | shared_ptr registry ownership; shared_ptr error-callback registration; documented Oboe close/write interlock |
| DSP CONCURRENCY | **GREEN (static)** | Seqlock snapshots + RT-applied coefficients; no try_lock audio loss; no steady-state RT allocations (both native AND fallback JVM paths) |
| AUDIO PERFORMANCE | **YELLOW** | Hot paths allocation-free on both engines; throttled positions; vendor HAL calls off main thread; no benchmark numbers produced |
| PLAYBACK | **YELLOW** | Architecture sound & fallback pre-staged; single volume owner; needs Phase 36 run |
| SEEK | **YELLOW** | Flush/discontinuity semantics preserved + staging cleared on flush; needs device soak |
| ROUTE | **YELLOW** | Serialized reconfigure w/ lazy-open race closed; needs device soak |
| AI / NETWORK | **GREEN (bounded)** | Timeouts+cancellation+typed errors; model lists refreshed |
| DATABASE | **GREEN** | Destructive path gone; schema exported; migrations mandatory going forward |
| UI PERFORMANCE | **GREEN** | Position updates subtree-scoped; no high-frequency root recomposition |
| TESTING | **YELLOW** | 62 green JVM tests incl. new coverage; native/fuzz/device layers missing |
| RELEASE | **YELLOW** | Builds+R8+lint green; unsigned; device gate open |

**Overall: not yet releasable until the Phase 36-37 device matrix passes and a signed
release with a live production backend exists. No GREEN above claims hardware proof.**
