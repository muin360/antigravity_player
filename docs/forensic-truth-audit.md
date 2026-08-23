# Forensic Truth Audit

**Date:** 2026-08-23 · **Base HEAD at audit start:** `15257cd` (one commit ahead of the stated `9d66b0a`; that commit is its parent)
**Method:** code-as-source-of-truth. Class existence is NOT evidence. Status reflects what the
code demonstrably does, verified by reading every relevant call path and by the test suite
(62 JVM unit tests passing at the time of writing).

Legend: `IMPLEMENTED` / `PARTIALLY_IMPLEMENTED` / `BROKEN` / `UNVERIFIED` /
`FABRICATED` / `DEAD`

| Feature | Status | Evidence & notes |
|---|---|---|
| Normal playback (local files) | **IMPLEMENTED** (device-UNVERIFIED this pass) | Media3 ExoPlayer -> custom Oboe sink or tuned DefaultAudioSink fallback. Fallback pre-configured before first buffer. |
| Custom Oboe output | **IMPLEMENTED** (code-verified, device-UNVERIFIED) | Registry-guarded JNI, Oboe 1.9.3 Float streams, bounded writes, resample staging. Real-device behaviour pending Phase 36 matrix. |
| BitPerfect mode | **IMPLEMENTED state machine; VERDICTS stay UNVERIFIED by design** | Strict bypass path exists; verifier requires actual stream+route+format+DSP-off evidence and refuses to promote inferred data to VERIFIED. Exclusive-mode acquisition itself can fail on many devices -> falls back honestly. |
| Hi-Fi badge/state | **PARTIALLY_IMPLEMENTED** | State plumbing exists end-to-end, but "Hi-Fi active" remains a label derived from route+capability inference, not a measurement of the DAC signal path. UI copy must not imply measured proof. |
| DAC detection | **IMPLEMENTED (detection only)** | Route/USB enumeration via AudioManager/MediaDevice descriptors. Model/chip identification beyond OS metadata was removed as fabrication territory. |
| USB DAC | **PARTIALLY_IMPLEMENTED** | Device-id-targeted stream opens are wired; Class 1/2 direct access beyond AAudio routing is NOT implemented. |
| DSD playback | **BROKEN as a user-facing claim** | `dsd_engine.cpp` exists with DoP/decimation converters, but NO decode path ever feeds DSD bitstreams into it: no DSF/DFF demuxer, no decoder integration. `setDsdMode` configures an engine nothing uses. Treat any "Native DSD" UI claim as FABRICATED until a real decode path exists (Phase B choice below). |
| Resampler | **IMPLEMENTED (honest specs)** | Polyphase windowed-sinc + Hermite modes; explicit input-consumed contract; atomic config swap. The previous ">140 dB SNR" comment was fabricated and has been removed; no SNR number is claimed without measurement. |
| DSP engine (native) | **IMPLEMENTED** | Seqlock parameter snapshots + render-thread coefficient application; neutral-by-default chain; honest requantization dither; conditional anti-alias guard. |
| EQ (10-band graphic) | **IMPLEMENTED** | Native biquad chain driven from EqualizerEngine flows on both sink paths. Framework `Equalizer` effect also exists for the legacy path - see DSPOwnership.md. |
| AutoEQ (PEQ import/profiles) | **PARTIALLY_IMPLEMENTED** | Profile application into native PEQ bands works via sync path; profile *acquisition* depends on import sources not audited here. |
| Lyrics (LRC) | **IMPLEMENTED** | LrcParser wired ViewModel->LyricsSheet with scroll sync; parser now supports all documented timestamp shapes (single-digit fractions fixed by tests). |
| YouTube search/stream | **PARTIALLY_IMPLEMENTED** | Client+backend hardened; production endpoint placeholder (`https://yt-backend.tensorix.com`) must be deployed before release builds have working YT features. Dev loopback works. |
| YouTube downloads | **PARTIALLY_IMPLEMENTED** | Download pipeline works against backend-provided URLs with scoped-storage fallbacks; same deployment dependency as above. |
| AI chat (LLM BYOK) | **IMPLEMENTED** | 4 providers, header auth, timeouts, cancellation, structured errors. Model lists updated to stable IDs; runtime model validity still provider-dependent. |
| AI tool execution | **IMPLEMENTED** | Rule-based fast path + JSON-action parsing now unit-tested (AiAgentParsingTest). Broken `\${...}` templates fixed (18 occurrences). |
| Voice commands | **PARTIALLY_IMPLEMENTED** | On-device STT works with lifecycle hardening (timeout mic release, full teardown). Complex intent parsing relies on configured LLM; offline coverage is the rule-based subset only. |
| Library scanner | **IMPLEMENTED** (incremental-safe) | Preserves favorites/metadata; empty scans do NOT purge the DB; heavy work on IO dispatcher. |
| Database migration | **IMPLEMENTED framework; history EMPTY** | Destructive fallback removed, schema export enabled (app/schemas/4.json). No version bump shipped yet, so no migration exists to test - first schema change MUST add one. |
| Notifications/media session | **IMPLEMENTED** | Standard Media3 MediaSessionService foreground wiring (unchanged this pass). |

## Removed fabrications

- ">140 dB SNR" comment on the polyphase resampler (unmeasured).
- "+2 dB air presence for AK4376A DAC" default/comment (specific chip claim with no runtime basis).
- "2x Oversampling Anti-Aliasing" framing: the stage is an interpolated waveshaper,
  not an oversampler; comments and docs now say so.
- "Native DSD" capability claims: see DSD row.

## Chosen remediation for DSD

Option B (truthful removal of claims) was selected for this pass: implementing real DSD
decoding (DSF/DFF demux + sigma-delta decimation pipeline) is a feature project, not a
remediation item. `dsd_engine.cpp` is retained but documented as unused-by-playback.
