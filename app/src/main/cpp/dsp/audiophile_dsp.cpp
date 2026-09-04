#include "audiophile_dsp.h"
#include <cmath>
#include <algorithm>

namespace antigravity {

static constexpr double M_PI_VAL = 3.14159265358979323846;

// Rational tanh approximation (<0.005% error on [-3, 3], no transcendentals).
static inline double fastTanh(double x) {
    if (x <= -3.0) return -1.0;
    if (x >= 3.0) return 1.0;
    const double x2 = x * x;
    return x * (27.0 + x2) / (27.0 + 9.0 * x2);
}

AudiophileDsp::AudiophileDsp() {
    // params_ starts fully neutral (see header). Build the initial filter set
    // synchronously: no render thread exists yet, so direct access is safe.
    buildFilterSet(sampleRate_);
    peqActive_.clear();
    peqFiltersL_.clear();
    peqFiltersR_.clear();
    resetRenderStateOnly();
}

// ---------------------------------------------------------------------------
// Seqlock parameter transport
// ---------------------------------------------------------------------------

void AudiophileDsp::readParams(DspParams &out) {
    // Single-reader (render thread) bounded-retry seqlock read with a
    // fallback to the last coherent snapshot.
    for (int attempt = 0; attempt < 8; ++attempt) {
        const uint32_t v1 = paramSeq_.load(std::memory_order_acquire);
        if (v1 & 1u) continue;                    // writer in progress
        out = params_;
        std::atomic_thread_fence(std::memory_order_acquire);
        if (v1 == paramSeq_.load(std::memory_order_relaxed)) {
            lastGoodParams_ = out;
            return;
        }
    }
    out = lastGoodParams_;
}

void AudiophileDsp::enqueueCommand(const Command &cmd) {
    {
        std::lock_guard<std::mutex> lk(commandMutex_);
        pendingCommands_.push_back(cmd);
    }
    coefficientSyncPending_.store(true, std::memory_order_release);
}

// ---------------------------------------------------------------------------
// Control-thread setters. Each mutates the published snapshot; anything that
// affects filter coefficients additionally queues a rebuild that the render
// thread applies at the next block boundary.
// ---------------------------------------------------------------------------

void AudiophileDsp::setEnabled(bool enabled) {
    mutateParams([&](DspParams &p) { p.enabled = enabled; });
}

void AudiophileDsp::setBitPerfectBypass(bool bypass) {
    bitPerfectBypassFlag_.store(bypass, std::memory_order_release);
    mutateParams([&](DspParams &p) { p.bitPerfectBypass = bypass; });
}

void AudiophileDsp::setPreAmpGainDb(double gainDb) {
    mutateParams([&](DspParams &p) {
        p.preAmpGainDb = gainDb;
        p.preampActive = (std::abs(gainDb) > 0.001);
    });
}

void AudiophileDsp::setBandGain(int bandIndex, double gainDb) {
    if (bandIndex < 0 || bandIndex >= static_cast<int>(kNumBands)) return;
    mutateParams([&](DspParams &p) {
        p.bandGainsDb[bandIndex] = gainDb;
        bool anyBand = false;
        for (double g : p.bandGainsDb) {
            if (std::abs(g) > 0.001) { anyBand = true; break; }
        }
        p.eqActive = anyBand;
    });
}

void AudiophileDsp::setBassBoostGainDb(double gainDb) {
    mutateParams([&](DspParams &p) {
        p.bassBoostGainDb = gainDb;
        p.bassBoostActive = (std::abs(gainDb) > 0.001);
    });
}

void AudiophileDsp::setTrebleGainDb(double gainDb) {
    mutateParams([&](DspParams &p) {
        p.trebleGainDb = gainDb;
        p.trebleActive = (std::abs(gainDb) > 0.001);
    });
}

void AudiophileDsp::setHarmonicExciterLevel(double level) {
    mutateParams([&](DspParams &p) {
        p.harmonicExciterLevel = level;
        p.harmonicExciterActive = (level > 0.001);
    });
}

void AudiophileDsp::setClarityEnhancerGain(double gainDb) {
    mutateParams([&](DspParams &p) {
        p.clarityEnhancerGainDb = gainDb;
        p.clarityActive = (std::abs(gainDb) > 0.001);
    });
}

void AudiophileDsp::setStereoExpansionMultiplier(double multiplier) {
    mutateParams([&](DspParams &p) {
        p.stereoExpansionMultiplier = multiplier;
        p.stereoExpansionActive = (std::abs(multiplier - 1.0) > 0.001);
    });
}

void AudiophileDsp::setDvcVolume(double volume) {
    mutateParams([&](DspParams &p) { p.dvcVolume = std::clamp(volume, 0.0, 2.0); });
}

void AudiophileDsp::setDitherStrength(double strength) {
    mutateParams([&](DspParams &p) {
        p.ditherStrength = strength;
        p.ditherActive = (strength > 0.0001);
    });
}

void AudiophileDsp::setOutputBitDepth(int bitDepth) {
    mutateParams([&](DspParams &p) { p.outputBitDepth = bitDepth; });
}

void AudiophileDsp::setWarmSaturationLevel(double level) {
    mutateParams([&](DspParams &p) {
        p.warmSaturationLevel = level;
        p.saturationActive = (level > 0.001 || p.triodeWarmthLevel > 0.001 || p.pentodeTapeLevel > 0.001);
    });
}

void AudiophileDsp::setTriodeWarmthLevel(double level) {
    mutateParams([&](DspParams &p) {
        p.triodeWarmthLevel = level;
        p.saturationActive = (level > 0.001 || p.warmSaturationLevel > 0.001 || p.pentodeTapeLevel > 0.001);
    });
}

void AudiophileDsp::setPentodeTapeLevel(double level) {
    mutateParams([&](DspParams &p) {
        p.pentodeTapeLevel = level;
        p.saturationActive = (level > 0.001 || p.warmSaturationLevel > 0.001 || p.triodeWarmthLevel > 0.001);
    });
}

void AudiophileDsp::setCrossfeedLevel(double level) {
    mutateParams([&](DspParams &p) {
        p.crossfeedLevel = level;
        p.crossfeedActive = (level > 0.001);
    });
}

void AudiophileDsp::setLimiterEnabled(bool enabled) {
    mutateParams([&](DspParams &p) { p.limiterActive = enabled; });
}

void AudiophileDsp::setLimiterThresholdDb(double thresholdDb) {
    mutateParams([&](DspParams &p) { p.limiterThresholdDb = thresholdDb; });
}

void AudiophileDsp::setSubBassMonoEnabled(bool enabled) {
    mutateParams([&](DspParams &p) { p.subBassMonoActive = enabled; });
}

void AudiophileDsp::setChannelBalance(double balance) {
    mutateParams([&](DspParams &p) {
        p.channelBalance = std::clamp(balance, -1.0, 1.0);
        p.balanceActive = (std::abs(p.channelBalance) > 0.001);
    });
}

void AudiophileDsp::setInvertPhase(bool invert) {
    mutateParams([&](DspParams &p) { p.invertPhase = invert; });
}

void AudiophileDsp::setAirPresenceGainDb(double gainDb) {
    mutateParams([&](DspParams &p) { p.airPresenceGainDb = gainDb; });
}

void AudiophileDsp::setHrtfSpatialEnabled(bool enabled) {
    mutateParams([&](DspParams &p) { p.spatialActive = enabled; });
}

void AudiophileDsp::setHrtfRoomSize(double roomSize) {
    mutateParams([&](DspParams &p) { p.hrtfRoomSize = std::clamp(roomSize, 0.0, 1.0); });
}

void AudiophileDsp::setDspParametersBatch(const DspParams &newParams) {
    bitPerfectBypassFlag_.store(newParams.bitPerfectBypass, std::memory_order_release);
    mutateParams([&](DspParams &p) {
        p = newParams;
    });
    Command cmd;
    cmd.kind = Command::Kind::kSyncCoefficients;
    enqueueCommand(cmd);
}

DspParams AudiophileDsp::getDspParams() {
    DspParams snap;
    readParams(snap);
    return snap;
}

void AudiophileDsp::setSampleRate(double sampleRate) {
    if (!(sampleRate > 0.0)) return;
    requestedSampleRate_.store(sampleRate, std::memory_order_release);
    Command cmd;
    cmd.kind = Command::Kind::kSyncCoefficients;
    enqueueCommand(cmd);
}

void AudiophileDsp::clearPeqBands() {
    {
        std::lock_guard<std::mutex> lk(commandMutex_);
        peqDraft_.clear();
    }
    Command cmd;
    cmd.kind = Command::Kind::kSyncCoefficients;
    enqueueCommand(cmd);
}

void AudiophileDsp::addPeqBand(FilterType type, double frequency, double q, double gainDb) {
    {
        std::lock_guard<std::mutex> lk(commandMutex_);
        PeqBandParams band;
        band.enabled = true;
        band.type = type;
        band.frequency = frequency;
        band.q = q;
        band.gainDb = gainDb;
        peqDraft_.push_back(band);
    }
    Command cmd;
    cmd.kind = Command::Kind::kSyncCoefficients;
    enqueueCommand(cmd);
}

void AudiophileDsp::updatePeqBand(size_t index, FilterType type, double frequency,
                                  double q, double gainDb) {
    {
        std::lock_guard<std::mutex> lk(commandMutex_);
        if (index >= peqDraft_.size()) return;
        auto &band = peqDraft_[index];
        band.type = type;
        band.frequency = frequency;
        band.q = q;
        band.gainDb = gainDb;
        band.enabled = true;
    }
    Command cmd;
    cmd.kind = Command::Kind::kSyncCoefficients;
    enqueueCommand(cmd);
}

// ---------------------------------------------------------------------------
// Render-side application of queued work. Runs at a block boundary.
// A failed try-lock only delays the change by one block; filtering continues
// with the existing coefficients, so no audio is ever dropped here.
// ---------------------------------------------------------------------------

void AudiophileDsp::applyCoefficientSync() {
    std::unique_lock<std::mutex> lk(commandMutex_, std::try_to_lock);
    if (!lk.owns_lock()) return;

    bool syncNeeded = false;
    bool resetNeeded = false;
    for (const auto &cmd : pendingCommands_) {
        if (cmd.kind == Command::Kind::kResetState) resetNeeded = true;
        else syncNeeded = true;
    }
    pendingCommands_.clear();

    const double requestedFs = requestedSampleRate_.load(std::memory_order_acquire);
    if (syncNeeded && requestedFs > 0.0 &&
        std::abs(requestedFs - sampleRate_) > 1e-6) {
        sampleRate_ = requestedFs;
        syncNeeded = true;
    }

    // Copy the PEQ draft while we hold the lock; rebuild outside it below.
    std::vector<PeqBandParams> specs = peqDraft_;

    lk.unlock();

    if (resetNeeded) {
        resetRenderStateOnly();
    }
    if (specs.size() != peqActive_.size() ||
        !std::equal(specs.begin(), specs.end(), peqActive_.begin(),
                    [](const PeqBandParams &a, const PeqBandParams &b) {
                        return a.enabled == b.enabled && a.type == b.type &&
                               a.frequency == b.frequency && a.q == b.q &&
                               a.gainDb == b.gainDb;
                    })) {
        rebuildPeqFilters(specs);
    }
    if (syncNeeded) {
        buildFilterSet(sampleRate_);
    }
}

// ---------------------------------------------------------------------------
// Filter construction (render-thread context only).
// ---------------------------------------------------------------------------

void AudiophileDsp::configureBiquad(BiquadFilter &f, FilterType type,
                                    double frequency, double q, double gainDb, double fs) {
    switch (type) {
        case FilterType::PEAKING_EQ: f.setPeakingEq(frequency, q, gainDb, fs); break;
        case FilterType::LOW_SHELF:  f.setLowShelf(frequency, q, gainDb, fs); break;
        case FilterType::HIGH_SHELF: f.setHighShelf(frequency, q, gainDb, fs); break;
        case FilterType::LOW_PASS:   f.setLowPass(frequency, q, fs); break;
        case FilterType::HIGH_PASS:  f.setHighPass(frequency, q, fs); break;
        case FilterType::BAND_PASS:  f.setBandPass(frequency, q, fs); break;
        case FilterType::NOTCH:      f.setNotch(frequency, q, fs); break;
        case FilterType::ALL_PASS:   f.setAllPass(frequency, q, fs); break;
    }
}

void AudiophileDsp::rebuildPeqFilters(const std::vector<PeqBandParams> &specs) {
    peqActive_ = specs;
    if (peqFiltersL_.size() != specs.size()) {
        // Allocation happens only when the user adds/removes bands (rare),
        // never during steady-state playback.
        peqFiltersL_ = std::vector<BiquadFilter>(specs.size());
        peqFiltersR_ = std::vector<BiquadFilter>(specs.size());
    }
    for (size_t i = 0; i < specs.size(); ++i) {
        configureBiquad(peqFiltersL_[i], specs[i].type, specs[i].frequency,
                        specs[i].q, specs[i].gainDb, sampleRate_);
        configureBiquad(peqFiltersR_[i], specs[i].type, specs[i].frequency,
                        specs[i].q, specs[i].gainDb, sampleRate_);
    }
}

void AudiophileDsp::buildFilterSet(double fs) {
    if (fs <= 0.0) return;
    const DspParams &p = active_;

    for (size_t i = 0; i < kNumBands; ++i) {
        biquadsL_[i].setPeakingEq(kBandCenterFreqs[i], 1.414, p.bandGainsDb[i], fs);
        biquadsR_[i].setPeakingEq(kBandCenterFreqs[i], 1.414, p.bandGainsDb[i], fs);
    }

    bassShelfL_.setLowShelf(80.0, 0.707, p.bassBoostGainDb, fs);
    bassShelfR_.setLowShelf(80.0, 0.707, p.bassBoostGainDb, fs);

    trebleShelfL_.setHighShelf(10000.0, 0.707, p.trebleGainDb, fs);
    trebleShelfR_.setHighShelf(10000.0, 0.707, p.trebleGainDb, fs);

    // The exciter operates on the interpolated (2x) signal inside the
    // waveshaping stage, so its high-pass is designed at 2*fs to preserve the
    // nominal 7.5 kHz corner.
    detailHPFL_.setHighPass(7500.0, 0.707, fs * 2.0);
    detailHPFR_.setHighPass(7500.0, 0.707, fs * 2.0);

    clarityFilterL_.setPeakingEq(3200.0, 1.0, p.clarityEnhancerGainDb, fs);
    clarityFilterR_.setPeakingEq(3200.0, 1.0, p.clarityEnhancerGainDb, fs);

    crossfeedLPFL_.setLowPass(700.0, 0.5, fs);
    crossfeedLPFR_.setLowPass(700.0, 0.5, fs);

    dcRemovalL_.setHighPass(2.0, 0.707, fs);
    dcRemovalR_.setHighPass(2.0, 0.707, fs);
    dcBlockerL_.setHighPass(1.0, 0.707, fs);
    dcBlockerR_.setHighPass(1.0, 0.707, fs);

    // Anti-aliasing guard for the saturation/exciter stages. Engaged only
    // while those stages are active so hi-res content stays untouched
    // otherwise. Corner tracks Nyquist instead of the old fixed 20 kHz.
    const double aaCorner = std::min(20000.0, fs * 0.45);
    aaFilterL_.setLowPass(aaCorner, 0.707, fs);
    aaFilterR_.setLowPass(aaCorner, 0.707, fs);

    subBassFilterL_.setLowPass(80.0, 0.707, fs);
    subBassFilterR_.setLowPass(80.0, 0.707, fs);

    airFilterL_.setHighShelf(16000.0, 0.5, p.airPresenceGainDb, fs);
    airFilterR_.setHighShelf(16000.0, 0.5, p.airPresenceGainDb, fs);

    hrtfHeadShadowL_.setLowPass(850.0, 0.55, fs);
    hrtfHeadShadowR_.setLowPass(850.0, 0.55, fs);
    hrtfPinnaNotchL_.setNotch(6200.0, 3.5, fs);
    hrtfPinnaNotchR_.setNotch(6200.0, 3.5, fs);

    rebuildPeqFilters(peqActive_);
}

void AudiophileDsp::resetRenderStateOnly() {
    for (auto &b : biquadsL_) b.reset();
    for (auto &b : biquadsR_) b.reset();
    bassShelfL_.reset(); bassShelfR_.reset();
    trebleShelfL_.reset(); trebleShelfR_.reset();
    detailHPFL_.reset(); detailHPFR_.reset();
    clarityFilterL_.reset(); clarityFilterR_.reset();
    crossfeedLPFL_.reset(); crossfeedLPFR_.reset();
    airFilterL_.reset(); airFilterR_.reset();
    dcRemovalL_.reset(); dcRemovalR_.reset();
    dcBlockerL_.reset(); dcBlockerR_.reset();
    aaFilterL_.reset(); aaFilterR_.reset();
    subBassFilterL_.reset(); subBassFilterR_.reset();
    hrtfHeadShadowL_.reset(); hrtfHeadShadowR_.reset();
    hrtfPinnaNotchL_.reset(); hrtfPinnaNotchR_.reset();
    for (auto &b : peqFiltersL_) b.reset();
    for (auto &b : peqFiltersR_) b.reset();

    osSamplesL_.fill(0.0);
    osSamplesR_.fill(0.0);
    itdBufferL_.fill(0.0);
    itdBufferR_.fill(0.0);
    itdWriteIdx_ = 0;
    ditherErrorL_ = 0.0;
    ditherErrorR_ = 0.0;
    peakL_.store(0.0, std::memory_order_relaxed);
    peakR_.store(0.0, std::memory_order_relaxed);
    phaseCorrelation_.store(1.0f, std::memory_order_relaxed);
}

void AudiophileDsp::reset() {
    Command cmd;
    cmd.kind = Command::Kind::kResetState;
    enqueueCommand(cmd);
}

double AudiophileDsp::nextRandomDouble() {
    // 64-bit xorshift*
    rngState_ ^= rngState_ >> 12;
    rngState_ ^= rngState_ << 25;
    rngState_ ^= rngState_ >> 27;
    const uint64_t v = rngState_ * 0x2545F4914F6CDD1DULL;
    return static_cast<double>(v >> 11) * (1.0 / 9007199254740992.0);
}

// ---------------------------------------------------------------------------
// Real-time processing
// ---------------------------------------------------------------------------

void AudiophileDsp::process(float *audioData, int32_t numFrames, int32_t channelCount) {
    if (!audioData || numFrames <= 0 || channelCount <= 0) return;

    // One coherent control snapshot per block.
    readParams(active_);

    // Apply queued coefficient/state work at the block boundary.
    if (coefficientSyncPending_.load(std::memory_order_acquire)) {
        applyCoefficientSync();
    }

    // Bit-perfect / disabled: strict passthrough, telemetry only.
    if (active_.bitPerfectBypass || !active_.enabled) {
        double maxL = 0.0, maxR = 0.0;
        for (int32_t frame = 0; frame < numFrames; ++frame) {
            const int32_t idx = frame * channelCount;
            const double l = std::abs(static_cast<double>(audioData[idx]));
            const double r = (channelCount > 1)
                ? std::abs(static_cast<double>(audioData[idx + 1])) : l;
            maxL = std::max(maxL, l);
            maxR = std::max(maxR, r);
        }
        peakL_.store(peakL_.load(std::memory_order_relaxed) * 0.92 + maxL * 0.08,
                     std::memory_order_relaxed);
        peakR_.store(peakR_.load(std::memory_order_relaxed) * 0.92 + maxR * 0.08,
                     std::memory_order_relaxed);
        phaseCorrelation_.store(1.0f, std::memory_order_relaxed);
        return;
    }

    const DspParams &p = active_;
    const double preAmp = std::pow(10.0, p.preAmpGainDb / 20.0);
    const double warmSat = p.warmSaturationLevel;
    const double triode = p.triodeWarmthLevel;
    const double pentode = p.pentodeTapeLevel;
    const double exciterLevel = p.harmonicExciterLevel;
    const double clarityGain = p.clarityEnhancerGainDb;
    const double stereoExp = p.stereoExpansionMultiplier;
    const double crossfeed = p.crossfeedLevel;
    const bool subMono = p.subBassMonoActive;
    const double balance = p.channelBalance;
    const bool invPhase = p.invertPhase;
    const double airGain = p.airPresenceGainDb;
    const bool hrtfOn = p.spatialActive;
    const double roomSize = p.hrtfRoomSize;
    const bool limiterOn = p.limiterActive;
    const double limThresh = std::pow(10.0, p.limiterThresholdDb / 20.0);
    const double dvc = p.dvcVolume;

    // Requantization dither is only meaningful when an integer target depth
    // is selected; the Oboe transport itself is 32-bit float end-to-end.
    const bool ditherOn =
        p.ditherStrength > 0.0 && p.outputBitDepth > 0 && p.outputBitDepth < 32;
    const double lsb =
        ditherOn ? (1.0 / std::pow(2.0, static_cast<double>(p.outputBitDepth - 1))) : 0.0;
    const double ditherStr = ditherOn ? p.ditherStrength : 0.0;

    // Nonlinear stages engaged -> their harmonics need band-limiting.
    const bool nonlinearEngaged =
        (warmSat + triode + pentode) > 0.0 || exciterLevel > 0.0;
    const bool shapingStageEngaged = nonlinearEngaged;

    double runningMaxL = 0.0, runningMaxR = 0.0;
    double sumLR = 0.0, sumL2 = 0.0, sumR2 = 0.0;

    for (int32_t frame = 0; frame < numFrames; ++frame) {
        const int32_t baseIdx = frame * channelCount;
        double sL = static_cast<double>(audioData[baseIdx]);
        double sR = (channelCount > 1) ? static_cast<double>(audioData[baseIdx + 1]) : sL;

        // 1. Pre-amp gain
        sL *= preAmp;
        sR *= preAmp;

        // 2. Interpolated waveshaping stage (saturation / harmonic exciter).
        //    Two interpolated sub-samples are shaped per input frame and
        //    averaged back; this smooths the nonlinearity but is NOT a
        //    full anti-aliased oversampler (see remediation report).
        if (shapingStageEngaged) {
            for (int ch = 0; ch < channelCount; ++ch) {
                double s = (ch == 0) ? sL : sR;
                auto &history = (ch == 0) ? osSamplesL_ : osSamplesR_;

                history[0] = history[1];
                history[1] = history[2];
                history[2] = history[3];
                history[3] = s;

                const double v0 = history[0], v1 = history[1];
                const double v2 = history[2], v3 = history[3];
                const double a = -0.5 * v0 + 1.5 * v1 - 1.5 * v2 + 0.5 * v3;
                const double b = v0 - 2.5 * v1 + 2.0 * v2 - 0.5 * v3;
                const double c = -0.5 * v0 + 0.5 * v2;
                const double subSample = a * 0.125 + b * 0.25 + c * 0.5 + v1;

                double upsampled[2] = {subSample, v2};
                for (double &smpRef : upsampled) {
                    double smp = smpRef;

                    if (warmSat > 0.0 || triode > 0.0) {
                        const double warmFactor = warmSat + triode;
                        smp = smp + (warmFactor * (smp * smp * smp - smp));
                        if (triode > 0.0) {
                            smp += triode * 0.15 * (smp * smp * (smp > 0.0 ? 1.0 : -1.0));
                        }
                    }
                    if (pentode > 0.0) {
                        smp -= pentode * 0.1 * (smp * smp * smp);
                    }
                    if (exciterLevel > 0.0) {
                        const double detail =
                            (ch == 0) ? detailHPFL_.process(smp) : detailHPFR_.process(smp);
                        const double harmonics =
                            (detail * detail * detail) * 0.5 + (detail * detail) * 0.3;
                        smp += harmonics * exciterLevel;
                    }
                    smpRef = smp;
                }

                if (ch == 0) sL = (upsampled[0] + upsampled[1]) * 0.5;
                else sR = (upsampled[0] + upsampled[1]) * 0.5;
            }
        }

        // 3. DC removal / blocking (always-on safety, transparent for music).
        sL = dcBlockerL_.process(dcRemovalL_.process(sL));
        sR = dcBlockerR_.process(dcRemovalR_.process(sR));

        // 4. Clarity presence peak
        if (clarityGain != 0.0) {
            sL = clarityFilterL_.process(sL);
            sR = clarityFilterR_.process(sR);
        }

        // 5. Bass shelf
        if (p.bassBoostGainDb != 0.0) {
            sL = bassShelfL_.process(sL);
            sR = bassShelfR_.process(sR);
        }

        // 6. 10-band graphic EQ (skip neutral bands)
        for (size_t band = 0; band < kNumBands; ++band) {
            if (p.bandGainsDb[band] != 0.0) {
                sL = biquadsL_[band].process(sL);
                sR = biquadsR_[band].process(sR);
            }
        }

        // 7. Parametric EQ bands (render-owned states: no locking, no skips)
        if (!peqFiltersL_.empty()) {
            for (size_t i = 0; i < peqFiltersL_.size(); ++i) {
                if (peqActive_[i].enabled) {
                    sL = peqFiltersL_[i].process(sL);
                    sR = peqFiltersR_[i].process(sR);
                }
            }
        }

        if (channelCount > 1) {
            // 8a. Crossfeed (Meier-style low-pass shunting)
            if (crossfeed > 0.0) {
                const double lowL = crossfeedLPFL_.process(sL);
                const double lowR = crossfeedLPFR_.process(sR);
                const double amt = crossfeed * 0.3;
                sL = sL - amt * lowL + amt * lowR;
                sR = sR - amt * lowR + amt * lowL;
            }

            // 8b. M-S stereo width
            if (stereoExp != 1.0) {
                const double mid = (sL + sR) * 0.5;
                const double side = (sL - sR) * 0.5 * stereoExp;
                sL = mid + side;
                sR = mid - side;
            }

            // 8c. HRTF-style spatialisation (ITD + head shadow + room reflection)
            if (hrtfOn) {
                itdBufferL_[itdWriteIdx_] = sL;
                itdBufferR_[itdWriteIdx_] = sR;

                const int32_t itdDelay = std::clamp(
                    static_cast<int32_t>(0.00028 * sampleRate_), 1,
                    static_cast<int32_t>(HRTF_BUFFER_SIZE - 1));
                const int32_t roomDelay = std::clamp(
                    static_cast<int32_t>((0.003 + 0.018 * roomSize) * sampleRate_), 1,
                    static_cast<int32_t>(HRTF_BUFFER_SIZE - 1));

                const size_t readIdxITD =
                    (itdWriteIdx_ + HRTF_BUFFER_SIZE - static_cast<size_t>(itdDelay)) % HRTF_BUFFER_SIZE;
                const size_t readIdxRoom =
                    (itdWriteIdx_ + HRTF_BUFFER_SIZE - static_cast<size_t>(roomDelay)) % HRTF_BUFFER_SIZE;

                const double shadowR = hrtfHeadShadowR_.process(itdBufferR_[readIdxITD]);
                const double shadowL = hrtfHeadShadowL_.process(itdBufferL_[readIdxITD]);
                const double roomReflR = itdBufferR_[readIdxRoom] * (0.15 * roomSize);
                const double roomReflL = itdBufferL_[readIdxRoom] * (0.15 * roomSize);

                sL = hrtfPinnaNotchL_.process(sL * 0.85 + shadowR * 0.35 + roomReflR);
                sR = hrtfPinnaNotchR_.process(sR * 0.85 + shadowL * 0.35 + roomReflL);

                itdWriteIdx_ = (itdWriteIdx_ + 1) % HRTF_BUFFER_SIZE;
            }

            // 8d. Sub-bass mono
            if (subMono) {
                const double subL = subBassFilterL_.process(sL);
                const double subR = subBassFilterR_.process(sR);
                const double monoSub = (subL + subR) * 0.5;
                sL = (sL - subL) + monoSub;
                sR = (sR - subR) + monoSub;
            }

            if (invPhase) sR = -sR;

            // 8e. Constant-power pan law
            if (balance != 0.0) {
                const double panAngle = (std::clamp(balance, -1.0, 1.0) + 1.0) * (M_PI_VAL / 4.0);
                sL *= std::cos(panAngle) * 1.4142135623730951;
                sR *= std::sin(panAngle) * 1.4142135623730951;
            }
        }

        // 9. Treble shelf / air presence
        if (p.trebleGainDb != 0.0) {
            sL = trebleShelfL_.process(sL);
            sR = trebleShelfR_.process(sR);
        }
        if (airGain != 0.0) {
            sL = airFilterL_.process(sL);
            sR = airFilterR_.process(sR);
        }

        // 10. Anti-aliasing guard, only while nonlinear stages are active.
        if (nonlinearEngaged) {
            sL = aaFilterL_.process(sL);
            sR = aaFilterR_.process(sR);
        }

        // 11. Optional soft-knee limiter, otherwise unity (hard clamp below).
        if (limiterOn) {
            const double absL = std::abs(sL);
            if (absL > limThresh) {
                const double overL = absL - limThresh;
                const double compL = limThresh + limThresh * fastTanh(overL / limThresh);
                sL = (sL > 0.0) ? compL : -compL;
            }
            const double absR = std::abs(sR);
            if (absR > limThresh) {
                const double overR = absR - limThresh;
                const double compR = limThresh + limThresh * fastTanh(overR / limThresh);
                sR = (sR > 0.0) ? compR : -compR;
            }
        } else {
            sL = std::clamp(sL, -1.0, 1.0);
            sR = std::clamp(sR, -1.0, 1.0);
        }

        // 12. Direct volume control
        sL *= dvc;
        sR *= dvc;

        // 13. Requantization dither: TPDF noise plus an ACTUAL quantization
        //     step onto the selected target-depth grid (models DAC input
        //     quantization). Inert unless explicitly enabled with an integer
        //     target depth below 32 bits.
        if (ditherOn) {
            const double rawL = (nextRandomDouble() - 0.5 + nextRandomDouble() - 0.5) *
                                lsb * ditherStr;
            const double shapedL = rawL - 0.5 * ditherErrorL_;
            ditherErrorL_ = rawL;
            sL = std::round((sL + shapedL) / lsb) * lsb;

            if (channelCount > 1) {
                const double rawR = (nextRandomDouble() - 0.5 + nextRandomDouble() - 0.5) *
                                    lsb * ditherStr;
                const double shapedR = rawR - 0.5 * ditherErrorR_;
                ditherErrorR_ = rawR;
                sR = std::round((sR + shapedR) / lsb) * lsb;
            }
        }

        // Telemetry
        runningMaxL = std::max(runningMaxL, std::abs(sL));
        runningMaxR = std::max(runningMaxR, std::abs(sR));
        sumLR += sL * sR;
        sumL2 += sL * sL;
        sumR2 += sR * sR;

        // Output
        audioData[baseIdx] = static_cast<float>(std::clamp(sL, -1.0, 1.0));
        if (channelCount > 1) {
            audioData[baseIdx + 1] = static_cast<float>(std::clamp(sR, -1.0, 1.0));
        }
    }

    peakL_.store(peakL_.load(std::memory_order_relaxed) * 0.92 + runningMaxL * 0.08,
                 std::memory_order_relaxed);
    peakR_.store(peakR_.load(std::memory_order_relaxed) * 0.92 + runningMaxR * 0.08,
                 std::memory_order_relaxed);

    if (channelCount > 1 && sumL2 > 1e-12 && sumR2 > 1e-12) {
        const float corr = static_cast<float>(sumLR / (std::sqrt(sumL2 * sumR2) + 1e-12));
        phaseCorrelation_.store(phaseCorrelation_.load(std::memory_order_relaxed) * 0.95f +
                                std::clamp(corr, -1.0f, 1.0f) * 0.05f,
                                std::memory_order_relaxed);
    }
}

} // namespace antigravity

