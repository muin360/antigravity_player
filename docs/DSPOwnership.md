# DSP Ownership Map

**Rule: audio must be processed at most once per stage, by exactly one authoritative layer.**

## Authoritative layer per sink mode

| Sink mode | Processing authority | Framework AudioEffects | JVM `Audiophile64BitDspProcessor` | Native C++ DSP |
|---|---|---|---|---|
| Native Oboe active, BitPerfect OFF | **Native C++ DSP** (`audiophile_dsp.cpp`) | Detached (BitPerfect bypass detaches; EQ effects detached when native path owns EQ) | Idle (not inserted into pipeline) | ACTIVE |
| Native Oboe active, BitPerfect ON | **None (bit-transparent)** | Detached | Idle | Bypassed in-process (`dsp.process` skipped) + DVC forced 1.0 |
| DefaultAudioSink fallback, BitPerfect OFF | **JVM processor** | Per EqualizerEngine wiring | ACTIVE (BaseAudioProcessor) | N/A (no stream) |

## Invariants enforced after remediation

1. **Single volume owner per path.**
   - System volume: always the final attenuator (hardware).
   - Software gain: exactly one of {native DVC param, JVM dvcVolume} - never both,
     because only one processing layer is ever live.
   - BitPerfect: software PCM gain disabled on both paths.

2. **EQ ownership follows the sink.**
   - Native path: 10-band + PEQ coefficients are pushed to C++ and framework
     `Equalizer`/`BassBoost`/... effects are bypassed for those bands.
   - Fallback path: same user settings drive the JVM biquad implementation.
   - The framework effect stack remains ONLY for legacy/shared-output profiles
     where neither custom layer is engaged; `EqualizerEngine.bitPerfectBypass`
     detaches them to preserve transparency.

3. **Parameter transport.**
   - Control thread -> render thread via seqlock snapshots (scalars) and a
     command queue (coefficients). The render thread applies coefficient
     changes at block boundaries; a contended queue delays application by one
     block but NEVER drops or skips filtering.

4. **Resampler reconfiguration** builds a complete immutable config and swaps
   it atomically; stream-state migration happens on the render thread at the
   next block. Tables/buffers are never reallocated mid-block.

5. **No double processing:** `OboeAudioSink` inserts `Audiophile64BitDspProcessor`
   into the fallback builder only for the fallback path
   (`getOrCreateFallbackSink`), so the JVM DSP can never run while the native
   DSP is also in the signal path.
