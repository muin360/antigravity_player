# AntigravityPlayer — Full Project Review

**Date:** 2026-08-22
**Scope:** Build config, manifest, backend, native C++/JNI engine, playback service, data/UI/AI layers, tests, repo hygiene
**Project:** Android Hi-Fi music player (Kotlin/Compose + Media3 + Oboe/C++ DSP + Node backend), minSdk 27 / targetSdk 34, ~113 tracked files

---

## Executive Summary

Strong architectural intent — clean layering, Media3 session service, a real Oboe/native DSP path with mathematically correct biquads, disciplined coroutine usage. **But this is not shippable**: plaintext BYOK key storage, a YouTube backend that cannot execute a single successful request under targetSdk 34, half the JNI surface exposed to use-after-free, systemic control-plane races in the native DSP, and several headline features that are dead or fabricated (DSD, lyrics, "140 dB SNR" resampling). Test coverage is cosmetic. The docs/commit history claims "production GREEN" while these issues exist — the honesty gap is itself a project risk.

| Area | Grade | Headline |
|---|---|---|
| Native DSP math | B− | Biquads correct; sync, resampler & DSD claims not |
| JNI bridge | **D+** | Half the surface is raw-pointer UAF territory |
| PlaybackService | B | Solid lifecycle; volume path breaks bit-perfect |
| Data layer | C | Good schema; destructive migration + REPLACE rescan |
| Security | **F** | Keys in plaintext prefs; backup enabled; dep unused |
| UI/Compose | C+ | Whole tree recomposes every 200 ms |
| AI/Voice | C− | Every reply template literally broken; no HTTP timeouts |
| Backend | C− | Works for LAN dev; blocked by NSC on device; no hardening |
| Tests | F | Tautological; zero DSP/JNI/concurrency coverage |

---

## P0 — Ship blockers

1. **Plaintext API key storage** — `AiKeyManager.kt:27-31,50-61` writes BYOK keys to plain SharedPreferences. `androidx.security:security-crypto` is declared (`app/build.gradle.kts:113`) but never imported. `allowBackup="true"` with no exclusion rules (`AndroidManifest.xml:36`) ships keys to cloud backup. Key input field is unmasked (`AiChatSheet.kt:369-376`).
2. **YouTube backend is dead on device** — default base `http://10.0.2.2:3000` (`YtApiService.kt:28`) with cleartext fallbacks to `localhost:3000` (:164-176). No `networkSecurityConfig`; targetSdk 34 blocks cleartext by default → every call fails, errors swallowed to `null` (`:178-196`, `MainViewModel.kt:500-511`). Search/stream/download/AI-download silently do nothing. Also tries `localhost` even when a remote host is configured.
3. **JNI use-after-free surface** — ~15 entry points bypass the registry and cast the raw handle (`oboe_bridge.cpp:667-759`: PEQ, HRTF, DSD, telemetry, stream info). Handles are raw pointers (`:179`); after `closeStream` any stale Kotlin call is UAF, and a recycled address can mutate the *wrong* stream. Only `writeDirect` (:292) validates the generation token. Plus unchecked direct-buffer bounds (:297-300) and `ENCODING_PCM_8BIT == 3` misread as 24-bit (:326-339) → heap overread.
4. **Control-vs-render data races (crash class)** — biquad coefficients, band gains, `sampleRate_`, ITD/oversampling history mutated non-atomically while the render thread processes them (`audiophile_dsp.cpp` setters vs `process()` :504-513); `resampler.configure()` reallocates `polyphaseTable_` mid-render (`oboe_bridge.cpp:694-704`). PEQ uses `try_to_lock` so bands **silently drop out of the audio path during edits** (:432,:516-523).

---

## P1 — Correctness / functional bugs

- **All AI chat replies render broken** — 18 escaped `\${…}` occurrences print literally (e.g., `▶ Playing '${match.title}'…`). `MainViewModel.kt:312,349,352,363,367,370,378,381,388-389,393,403,542,552,581,584,588`.
- **Partial-write duplication** — when resampling, `writeDirect` reports estimated consumed frames (`oboe_bridge.cpp:409-412`) while the resampler already consumed all input → ExoPlayer resubmits remainder → glitches under load. Sink-side `framesWritten` mixes input/output frame units (`OboeAudioSink.kt:376-381`).
- **Volume path breaks bit-perfect** — `VOLUME_CHANGED_ACTION` receiver writes DVC gain even when bit-perfect forced 1.0 (`PlaybackService.kt:204-229` vs `:596`); three competing writers including `OboeAudioSink.setVolume` (:434-440) → double attenuation.
- **EQ presets never reach the native DSP** — `applyPreset` updates only dspProcessor/audiofx, no `syncWithNativeDsp()` (`EqualizerEngine.kt:514-533`) → presets silently ignored while Oboe sink active. Slider throttle also drops the final resting value (:341-344).
- **ANR risk** — ReplayGain tag scan reads entire FLAC into memory on main thread (`MusicController.kt:420` from `onMediaItemTransition` :153).
- **No LLM HTTP timeouts** (`MusicAiAgent.kt:110-277`) → infinite processing spinner; failures return null silently. Gemini key embedded in URL query string (:112) — leaks via intermediaries.
- **Destructive DB migration**, `exportSchema=false` (`AppDatabase.kt:14-16,33`) → next bump wipes playlists/favorites.
- **Rescan strategy** — full-table REPLACE per scan, serial `MediaMetadataRetriever`, stale-purge skipped when scan returns empty (`LibraryScanner.kt:102-120,165-168`) → zombie rows on storage unmount; `dateAdded` = scan time, breaking "Recently Added".
- **Lyrics feature dead** — `LrcParser` solid but `_lyricsLines` never populated (`MainViewModel.kt:71-72`).
- **Downloads land wrong** — public-storage raw File path ignored ≥API 29 (`YtApiService.kt:98-116`); success toast lies (`MainViewModel.kt:581`).
- **Whole-app recomposition every 200 ms** — position flow collected at root (`MainActivity.kt:233,246` from `MusicController.kt:199-206`); plus 60 fps polling loop in `AudiophileInfoScreen.kt:100-117`.

---

## Fabricated capability claims (integrity)

These matter because the project's identity is "truthful audiophile telemetry":

- **DSD is vaporware** — `dsd_engine.cpp` has zero JNI bindings/callers; its "decimator" averages bits within each byte with no cross-byte state; UI reports DSD as 32-bit (`MusicController.kt:156`).
- **Resampler ">140 dB SNR polyphase sinc"** — actually 64 quantized phases with no inter-phase interpolation → realistic ceiling ≈ −40…−50 dB near Nyquist (`audiophile_resampler.cpp:133-134`).
- **"2× oversampling anti-aliasing"** interpolates one midpoint, waveshapes both, then *averages* — no decimation filter, alias rejection ≈ none (`audiophile_dsp.cpp:443-491`).
- **Dither theater** — output stays float32; nothing requantized to `outputBitDepth_`; "noise shaping" is an HPF on the dither itself (:625-643).
- **"Flat" isn't flat** — defaults bake in +3.5 dB presence, saturation, exciter, air shelf, unconditional 20 kHz LPF (`audiophile_dsp.h:78-93`).
- **`-ffast-math` globally** (`CMakeLists.txt:8-14`) undermines the bit-perfect/64-bit precision story.
- **Tests protect against none of this** — JVM tests run without the native lib, exercise fallback branches, assert tautologies, and the ratio test re-implements production math locally (`OboeAudioSinkTest.kt:82,121-164,218`).

---

## P2/P3 highlights

- Duplicate bare notification alongside media3's (`PlaybackService.kt:250,493-504`)
- Leaked Oboe stream on error-after-close (`oboe_bridge.cpp:150-157`)
- Inverted Exclusive→Shared fallback logic (`oboe_bridge.cpp:232-236`)
- Missing `ExceptionClear` after failed FindClass/GetMethodID (`oboe_bridge.cpp:755-759`)
- Static `PlaybackService.instance` read across UI (`SettingsScreen.kt:74-76`, `EqualizerSheet.kt:113-114`, `AudiophileInfoScreen.kt:119`)
- Conditional `collectAsState` allocating fresh flows per recomposition
- Mic session never destroyed after results (`VoiceAssistantManager.kt:61-69`)
- `remember` instead of `rememberSaveable` for nav state (`MainActivity.kt:265-272`)
- Zero string resources (i18n impossible); dark-only hardcoded theme
- kapt + Room alpha (`2.7.0-alpha13`); Retrofit/OkHttp declared but unused while hand-rolled `HttpURLConnection` used (two networking stacks)
- Triplicated DSP stack (C++ ~670 lines / Kotlin clone ~555 lines / audiofx)
- Dead code: entire `dsd_engine.cpp`, `SmartCollectionManager`, `AiOrchestrator`, `VoiceCommandListener`, `OboeBridge.write`, `presetReverb`
- `e.printStackTrace()` culture (~dozens of sites)
- Backend (`server.js`): open CORS, no rate limiting/timeouts/auth, error details echoed to clients — acceptable for LAN dev only

---

## What's genuinely good

- Correct RBJ biquads with DF2T, denormal flush, input clamps
- Registry + generation token on the hot write path; zero-copy direct ByteBuffers; bounded 20 ms writes with stall detection
- Proper FGS type/focus/wake/becoming-noisy/task-removal wiring
- Session-ID tracking with audiofx attach/detach ordering to avoid effect conflicts
- Room indices incl. junction FKs + Flow DAOs + off-main sorting
- Lazy voice permission lifecycle; denial surfaced in chat
- Memory-only crash diagnostics with correct handler chaining
- No `GlobalScope`/`runBlocking`/context leaks
- 16 KB page alignment done; 4-ABI consistency between Gradle and CMake
- `.gitignore` correctly excludes the log/hprof clutter sitting in the worktree

---

## Remediation roadmap

### A. Ship blockers (days)
- Migrate keys to EncryptedSharedPreferences + backup exclusion rules + masked input
- Add `networkSecurityConfig` (or HTTPS) and surface YT errors in UI
- Fix `\${}` templates
- Route every JNI entry through registry lookup with generation validation + buffer bounds checks

### B. Correctness (1–2 wks)
- Double-buffered atomic parameter swap for DSP/resampler
- Accurate partial-write protocol
- Single volume owner honoring bit-perfect
- `applyPreset` sync
- Move file scans off main thread
- Real Room migrations + schema export
- Incremental scanner upserts
- LLM timeouts + error surfacing
- Wire lyrics parser
- Move position/timer collection down-tree

### C. Integrity
- Delete or truly implement DSD
- Make telemetry claims true or remove them
- Drop/scope `-ffast-math`
- Collapse to one DSP stack
- Delete dead code
- Rebuild tests (DSP golden vectors, JNI contract, concurrency)

### D. Polish
- i18n, light theme, touch-target/accessibility pass, backend hardening if ever exposed beyond LAN

---

## Verdict

**Not production-ready — C− as a release candidate, B potential.** The happy path plays and sounds reasonable, and the fundamentals underneath are often right. But the P0 list is disqualifying, the marquee features (YT, AI chat, lyrics, DSD) are broken or simulated, and the audit docs claiming "GREEN" contradict the code. Fix Phase A before touching anything else.
