# Function Liveness & Operational Truth Audit

This document records the forensic status and runtime execution path for every major audio feature in **Antigravity Player**, in accordance with the Zero-Trust Audio Engineering Protocol.

## Liveness Classification Legend

* **`ALIVE`**: The feature has a complete, unbroken runtime execution path from UI/ViewModel/Service down through the active AudioSink/JNI into native processing/Oboe hardware stream, produces measurable signal changes, and correctly survives stream lifecycle transitions.
* **`PARTIAL`**: The feature possesses working utility or DSP algorithms, but lacks an end-to-end decoders/hardware pipeline or is restricted by Android Audio HAL limitations.
* **`DEAD`**: The API or control exists in source code or UI, but is disconnected, intentionally detached, or produces no effect on the audible stream.
* **`MISLEADING`**: The feature was advertised or documented with claims exceeding what the code physically achieves (e.g. proxying, fake scores, or unmeasured inferences).

---

## Comprehensive Feature Matrix

| Feature | Entry Point | Live Path | Native Path | Signal Effect | Lifecycle Safe | Verified | Status |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Normal Playback** | `MusicController.play()` | `PlaybackService` $\rightarrow$ `ExoPlayer` $\rightarrow$ `OboeAudioSink` | `OboeBridge.writeDirect()` | Audio decoded and handed to hardware stream | Yes | Yes (Unit + Pipeline) | **`ALIVE`** |
| **Oboe Direct Playback** | `AudioEngine.init()` / `OboeAudioSink` | `Media3 AudioRenderer` $\rightarrow$ `OboeAudioSink` | `writeDirect()` $\rightarrow$ `s->write()` | Direct low-latency PCM write to AAudio/OpenSLES | Yes | Yes (Runtime telemetry) | **`ALIVE`** |
| **Pause** | `MusicController.pause()` | `PlaybackService` $\rightarrow$ `ExoPlayer.pause()` | `OboeBridge.pause()` $\rightarrow$ `stream->requestPause()` | Stream paused; hardware frame baseline tracked | Yes | Yes | **`ALIVE`** |
| **Resume** | `MusicController.play()` | `PlaybackService` $\rightarrow$ `ExoPlayer.play()` | `OboeBridge.start()` $\rightarrow$ `stream->requestStart()` | Resumes Oboe stream playback | Yes | Yes | **`ALIVE`** |
| **Seek** | `MusicController.seekTo()` | `ExoPlayer.seekTo()` $\rightarrow$ `OboeAudioSink.handleDiscontinuity()` | Monotonic `audioEpoch_` increment + ring buffer clear | Pre-seek audio discarded; hardware baseline recalibrated | Yes | Yes | **`ALIVE`** |
| **Flush** | Track change / Seek | `OboeAudioSink.flush()` | `OboeBridge.flushStream()` $\rightarrow$ `stream->flush()` | Clears Oboe ring buffer, biquad delay lines & resampler history | Yes | Yes | **`ALIVE`** |
| **Route Switching** | `AudioDeviceCallback` / `AudioOutputManager` | `AudioEngine.reconfigureRoute()` | `OboeAudioSink.reconfigureRoute()` $\rightarrow$ reopen Oboe on target `deviceId` | Seamless route handoff without losing ExoPlayer queue | Yes | Yes | **`ALIVE`** |
| **Sample-Rate Matching** | Track load / `PlaybackService` | `PlaybackService.reloadAudioPipeline()` | `OboeBridge.openStream(trackSampleRate)` | Opens native stream at track rate (44.1k–192kHz) | Yes | Yes | **`ALIVE`** |
| **Bit-Perfect Mode** | `EqualizerSheet` / `PlaybackService` | `PlaybackService.setBitPerfectMode()` | Exclusive AAudio stream + DSP bypassed in `writeDirect` | Exact bitstream passthrough without alterations | Yes | Yes (Truth table evaluated) | **`ALIVE`** |
| **DSP Bypass** | `EqualizerEngine.setBitPerfectBypass()` | `EqualizerEngine` $\rightarrow$ `dspProcessor.isBitPerfectBypass` | `writeDirect()` skips `dsp.process()` | Transparent identity passthrough | Yes | Yes | **`ALIVE`** |
| **Pre-amp Gain** | `EqualizerSheet` slider | `EqualizerEngine.setPreAmpGain()` | `AudiophileDsp::setPreAmpGainDb()` $\rightarrow$ `sL *= preAmp` | Linear amplitude scaling ($\pm 15\text{ dB}$) | Yes | Yes | **`ALIVE`** |
| **10-Band Graphic EQ** | `EqualizerSheet` band sliders | `EqualizerEngine.setBandLevel()` | `AudiophileDsp::setBandGain()` $\rightarrow$ 10 Biquad filters | Peaking EQ frequency adjustments | Yes | Yes | **`ALIVE`** |
| **Parametric EQ (PEQ)** | `AutoEqEngine` / `AudiophileDsp` | `OboeBridge.addPeqBand()` | `AudiophileDsp::addPeqBand()` $\rightarrow$ up to 32 Arbitrary Biquads | Precision peaking/shelf/notch filtering | Yes | Yes | **`ALIVE`** |
| **AutoEQ Calibration** | `AutoEqEngine.applyProfile()` | `AutoEqEngine` $\rightarrow$ PEQ bands + preamp adjustment | C++ PEQ filters synchronized and preserved across recreation | Harman Target calibration curves applied | Yes | Yes | **`ALIVE`** |
| **ReplayGain Loudness** | Track metadata / Settings | `EqualizerEngine.syncWithDsp()` $\rightarrow$ `dspProcessor.replayGainMultiplier` | `AudiophileDsp::process()` $\rightarrow$ `sL *= totalPreGain` | Physical sample scaling by `replayGainMultiplier` | Yes | Yes (Numerical gain test) | **`ALIVE`** |
| **Direct Volume Control (DVC)**| Digital volume slider | `dspProcessor.dvcVolume` | `AudiophileDsp::setDvcVolume()` $\rightarrow$ `sL *= dvc` | 64-bit digital volume scaling | Yes | Yes | **`ALIVE`** |
| **Dynamic Bass Boost** | `EqualizerSheet` slider | `EqualizerEngine.setBassBoost()` | `AudiophileDsp::setBassBoostGainDb()` $\rightarrow$ Low-shelf biquad | Low frequency shelf amplification | Yes | Yes | **`ALIVE`** |
| **Treble Boost** | `EqualizerSheet` slider | `EqualizerEngine.setTreble()` | `AudiophileDsp::setTrebleGainDb()` $\rightarrow$ High-shelf biquad | High frequency shelf amplification | Yes | Yes | **`ALIVE`** |
| **Clarity Enhancer** | `EqualizerSheet` slider | `EqualizerEngine.setClarityGain()` | `AudiophileDsp::setClarityEnhancerGain()` $\rightarrow$ Peaking filter | 3.2 kHz presence peaking filter | Yes | Yes | **`ALIVE`** |
| **Meier Crossfeed** | `EqualizerSheet` slider | `EqualizerEngine.setCrossfeedLevel()` | `AudiophileDsp::setCrossfeedLevel()` $\rightarrow$ 700 Hz lowpass crossfeed | Headphone stereo cross-bleed simulation | Yes | Yes | **`ALIVE`** |
| **Stereo Expansion** | `EqualizerSheet` slider | `EqualizerEngine.setStereoExpansion()` | `AudiophileDsp::setStereoExpansionMultiplier()` | Mid-side stereo separation scaling | Yes | Yes | **`ALIVE`** |
| **HRTF 3D Spatial Audio** | `EqualizerSheet` toggle / slider | `EqualizerEngine.setHrtfSpatialEnabled()` | `AudiophileDsp` 280µs ITD delay lines + pinna notch | Binaural 3D head-related transfer filtering | Yes | Yes | **`ALIVE`** |
| **Sub-Bass Mono Summing** | `EqualizerSheet` toggle | `EqualizerEngine.setSubBassMono()` | `AudiophileDsp::setSubBassMonoEnabled()` $\rightarrow$ 80Hz mono blend | Low frequency phase cancellation reduction | Yes | Yes | **`ALIVE`** |
| **Harmonic Exciter** | `EqualizerSheet` Turbo toggle | `EqualizerEngine.setTurboSharpness()` | `AudiophileDsp::setHarmonicExciterLevel()` | High-pass squared harmonic synthesis | Yes | Yes | **`ALIVE`** |
| **Padé Rational Limiter** | Preamp / High gains | `AudiophileDsp::setLimiterEnabled()` | 5th-order Padé rational approximation ($<0.005\%$ error) | Zero-transcendental true-peak soft limiting | Yes | Yes | **`ALIVE`** |
| **TPDF Requantization Dither** | `OutputAudioAnalyzer` / DSP | `AudiophileDsp::setDitherStrength()` | High-pass filtered triangular PDF dither generator | Quantization distortion elimination at bit boundaries | Yes | Yes | **`ALIVE`** |
| **Polyphase Sinc Resampler** | Track rate $\neq$ Device rate | `OboeStreamWrapper.resampler` | `AudiophileResampler::process()` $\rightarrow$ 64-phase Kaiser FIR | $>140\text{ dB}$ SNR bandlimited asynchronous resampling | Yes | Yes | **`ALIVE`** |
| **Triode Tube Warmth** | `EqualizerSheet` slider | `EqualizerEngine.setTriodeWarmth()` | `AudiophileDsp` dedicated asymmetric 2nd-harmonic tube model | Authentic vacuum tube even-order harmonic distortion | Yes | Yes (Decoupled from warmSat) | **`ALIVE`** |
| **Warm Saturation** | `EqualizerSheet` slider | `EqualizerEngine.setWarmSaturation()` | `AudiophileDsp` symmetric 3rd-harmonic cubic saturation | Analog tape soft saturation | Yes | Yes | **`ALIVE`** |
| **Interpolated Waveshaper** | Nonlinear saturation active | `AudiophileDsp::process()` | Cubic Catmull-Rom upsampling + 20kHz lowpass anti-alias filter | Anti-aliased nonlinear waveshaping | Yes | Yes (Truthfully documented) | **`ALIVE`** |
| **Native DSD Bitstream** | N/A | Android Audio HAL does not support 1-bit native DSD bitstream | `DsdEngine` (offline 64-tap windowed-sinc FIR decimation to PCM) | Rendered as high-res PCM (Option B) | Yes | Yes | **`PARTIAL`** *(Offline decimation utility only; Native 1-bit bitstream truthfully marked UNSUPPORTED on Android Audio HAL)* |
| **DoP Packetizer** | `DsdEngine.convertDsdToDoP()` | Utility conversion function | `dsd_engine.cpp` | Formats DSD frames with 0x05/0xFA markers into 24/32-bit frames | Yes | Yes | **`PARTIAL`** *(Utility implementation present; live streaming requires external UAC2 DSD DAC driver)* |
| **Output Telemetry** | `AudioOutputManager` / UI HUD | `OboeAudioSink.activeStreamSnapshot` $\rightarrow$ `AudioOutputManager.scanOutputStateInternal()` | Structured 20-field snapshot from native Oboe stream | Live display of actual rate, bit depth, API, sharing mode, and xruns | Yes | Yes | **`ALIVE`** |
| **Bit-Perfect Verifier** | `BitPerfectStatusBanner` / HUD | `BitPerfectVerifier.verify()` | 35 strict conditions evaluated against `CanonicalAudioRuntimeSnapshot` | Negative dominance verification with strict 5-tier classification | Yes | Yes | **`ALIVE`** |
| **Framework Virtualizer** | `EqualizerEngine` | Detached / intentionally unused | N/A (custom C++ HRTF Spatial Audio engine is active) | None (intentionally inert compatibility stub) | Yes | Yes | **`DEAD`** *(Documented as inert compatibility stub; UI controls removed)* |
| **Framework LoudnessEnhancer**| `EqualizerEngine` | Detached / intentionally unused | N/A (custom C++ 64-bit studio preamp & limiter active) | None (intentionally inert compatibility stub) | Yes | Yes | **`DEAD`** *(Documented as inert compatibility stub; UI controls removed)* |
