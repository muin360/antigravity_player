#include "audiophile_dsp.h"
#include <algorithm>
#include <cmath>
#include <cstring>

namespace antigravity {

static constexpr double M_PI_VAL = 3.14159265358979323846;

// 5th-order Padé rational approximation of tanh(x).
// Accelerates the true-peak limiter and saturation stages.
static inline double fastTanh(double x) {
    if (x <= -3.0) return -1.0;
    if (x >= 3.0) return 1.0;
    const double x2 = x * x;
    return x * (27.0 + x2) / (27.0 + 9.0 * x2);
}

AudiophileDsp::AudiophileDsp() {
    controlSampleRate_ = 48000.0;
    peqDraft_.clear();

    computeCoefficients(controlParams_, peqDraft_, controlSampleRate_, snapshotPool_[0].coeffs);
    snapshotPool_[0].params = controlParams_;
    snapshotPool_[0].generation = 1;
    publishedGeneration_ = 1;

    applySnapshot(snapshotPool_[0]);
    active_ = controlParams_;
    appliedGeneration_ = 1;

    cleanSlot_.store(0, std::memory_order_relaxed);
    readSlot_ = 1;
    writeSlot_ = 2;

    resetRenderStateOnly();
}

void AudiophileDsp::publishSnapshotUnderLock() {
    DspSnapshot &slot = snapshotPool_[writeSlot_];
    slot.params = controlParams_;
    computeCoefficients(controlParams_, peqDraft_, controlSampleRate_, slot.coeffs);
    slot.generation = ++publishedGeneration_;

    writeSlot_ = cleanSlot_.exchange(writeSlot_, std::memory_order_release);
}

void AudiophileDsp::computeCoefficients(const DspParams &p,
                                        const std::vector<PeqBandParams> &peqBands,
                                        double fs,
                                        FilterCoefficients &outCoeffs) {
    if (fs <= 0.0) return;

    for (size_t i = 0; i < kNumBands; ++i) {
        outCoeffs.biquadsL[i] = BiquadFilter::computePeakingEq(kBandCenterFreqs[i], 1.414, p.bandGainsDb[i], fs);
        outCoeffs.biquadsR[i] = outCoeffs.biquadsL[i];
    }

    outCoeffs.bassShelfL = BiquadFilter::computeLowShelf(80.0, 0.707, p.bassBoostGainDb, fs);
    outCoeffs.bassShelfR = outCoeffs.bassShelfL;

    outCoeffs.trebleShelfL = BiquadFilter::computeHighShelf(10000.0, 0.707, p.trebleGainDb, fs);
    outCoeffs.trebleShelfR = outCoeffs.trebleShelfL;

    outCoeffs.detailHPFL = BiquadFilter::computeHighPass(7500.0, 0.707, fs * 2.0);
    outCoeffs.detailHPFR = outCoeffs.detailHPFL;

    outCoeffs.clarityFilterL = BiquadFilter::computePeakingEq(3200.0, 1.0, p.clarityEnhancerGainDb, fs);
    outCoeffs.clarityFilterR = outCoeffs.clarityFilterL;

    outCoeffs.crossfeedLPFL = BiquadFilter::computeLowPass(700.0, 0.5, fs);
    outCoeffs.crossfeedLPFR = outCoeffs.crossfeedLPFL;

    outCoeffs.dcRemovalL = BiquadFilter::computeHighPass(2.0, 0.707, fs);
    outCoeffs.dcRemovalR = outCoeffs.dcRemovalL;
    outCoeffs.dcBlockerL = BiquadFilter::computeHighPass(1.0, 0.707, fs);
    outCoeffs.dcBlockerR = outCoeffs.dcBlockerL;

    const double aaCorner = std::min(20000.0, fs * 0.45);
    outCoeffs.aaFilterL = BiquadFilter::computeLowPass(aaCorner, 0.707, fs);
    outCoeffs.aaFilterR = outCoeffs.aaFilterL;

    outCoeffs.subBassFilterL = BiquadFilter::computeLowPass(80.0, 0.707, fs);
    outCoeffs.subBassFilterR = outCoeffs.subBassFilterL;

    outCoeffs.airFilterL = BiquadFilter::computeHighShelf(16000.0, 0.5, p.airPresenceGainDb, fs);
    outCoeffs.airFilterR = outCoeffs.airFilterL;

    outCoeffs.hrtfHeadShadowL = BiquadFilter::computeLowPass(850.0, 0.55, fs);
    outCoeffs.hrtfHeadShadowR = outCoeffs.hrtfHeadShadowL;
    outCoeffs.hrtfPinnaNotchL = BiquadFilter::computeNotch(6200.0, 3.5, fs);
    outCoeffs.hrtfPinnaNotchR = outCoeffs.hrtfPinnaNotchL;

    outCoeffs.peqCount = std::min(peqBands.size(), kMaxPeqBands);
    for (size_t i = 0; i < outCoeffs.peqCount; ++i) {
        outCoeffs.peqActive[i] = peqBands[i];
        BiquadCoefficients bc{1.0, 0.0, 0.0, 0.0, 0.0};
        if (peqBands[i].enabled) {
            switch (peqBands[i].type) {
                case FilterType::PEAKING_EQ: bc = BiquadFilter::computePeakingEq(peqBands[i].frequency, peqBands[i].q, peqBands[i].gainDb, fs); break;
                case FilterType::LOW_SHELF:  bc = BiquadFilter::computeLowShelf(peqBands[i].frequency, peqBands[i].q, peqBands[i].gainDb, fs); break;
                case FilterType::HIGH_SHELF: bc = BiquadFilter::computeHighShelf(peqBands[i].frequency, peqBands[i].q, peqBands[i].gainDb, fs); break;
                case FilterType::LOW_PASS:   bc = BiquadFilter::computeLowPass(peqBands[i].frequency, peqBands[i].q, fs); break;
                case FilterType::HIGH_PASS:  bc = BiquadFilter::computeHighPass(peqBands[i].frequency, peqBands[i].q, fs); break;
                case FilterType::BAND_PASS:  bc = BiquadFilter::computeBandPass(peqBands[i].frequency, peqBands[i].q, fs); break;
                case FilterType::NOTCH:      bc = BiquadFilter::computeNotch(peqBands[i].frequency, peqBands[i].q, fs); break;
                case FilterType::ALL_PASS:   bc = BiquadFilter::computeAllPass(peqBands[i].frequency, peqBands[i].q, fs); break;
            }
        }
        outCoeffs.peqL[i] = bc;
        outCoeffs.peqR[i] = bc;
    }
}

void AudiophileDsp::applySnapshot(const DspSnapshot &snap) {
    const auto &c = snap.coeffs;
    for (size_t i = 0; i < kNumBands; ++i) {
        biquadsL_[i].setCoefficients(c.biquadsL[i]);
        biquadsR_[i].setCoefficients(c.biquadsR[i]);
    }
    bassShelfL_.setCoefficients(c.bassShelfL);
    bassShelfR_.setCoefficients(c.bassShelfR);
    trebleShelfL_.setCoefficients(c.trebleShelfL);
    trebleShelfR_.setCoefficients(c.trebleShelfR);
    detailHPFL_.setCoefficients(c.detailHPFL);
    detailHPFR_.setCoefficients(c.detailHPFR);
    clarityFilterL_.setCoefficients(c.clarityFilterL);
    clarityFilterR_.setCoefficients(c.clarityFilterR);
    crossfeedLPFL_.setCoefficients(c.crossfeedLPFL);
    crossfeedLPFR_.setCoefficients(c.crossfeedLPFR);
    dcRemovalL_.setCoefficients(c.dcRemovalL);
    dcRemovalR_.setCoefficients(c.dcRemovalR);
    dcBlockerL_.setCoefficients(c.dcBlockerL);
    dcBlockerR_.setCoefficients(c.dcBlockerR);
    aaFilterL_.setCoefficients(c.aaFilterL);
    aaFilterR_.setCoefficients(c.aaFilterR);
    subBassFilterL_.setCoefficients(c.subBassFilterL);
    subBassFilterR_.setCoefficients(c.subBassFilterR);
    airFilterL_.setCoefficients(c.airFilterL);
    airFilterR_.setCoefficients(c.airFilterR);
    hrtfHeadShadowL_.setCoefficients(c.hrtfHeadShadowL);
    hrtfHeadShadowR_.setCoefficients(c.hrtfHeadShadowR);
    hrtfPinnaNotchL_.setCoefficients(c.hrtfPinnaNotchL);
    hrtfPinnaNotchR_.setCoefficients(c.hrtfPinnaNotchR);

    peqActiveCount_ = std::min(c.peqCount, kMaxPeqBands);
    for (size_t i = 0; i < peqActiveCount_; ++i) {
        peqActive_[i] = c.peqActive[i];
        peqFiltersL_[i].setCoefficients(c.peqL[i]);
        peqFiltersR_[i].setCoefficients(c.peqR[i]);
    }
}

void AudiophileDsp::setSampleRate(double sampleRate) {
    if (sampleRate <= 0.0) return;
    std::lock_guard<std::mutex> lk(paramWriteMutex_);
    if (std::abs(controlSampleRate_ - sampleRate) > 1e-6) {
        controlSampleRate_ = sampleRate;
        publishSnapshotUnderLock();
    }
}

void AudiophileDsp::setEnabled(bool enabled) {
    mutateParams([enabled](DspParams &p) { p.enabled = enabled; });
}

void AudiophileDsp::setBitPerfectBypass(bool bypass) {
    bitPerfectBypassFlag_.store(bypass, std::memory_order_release);
    mutateParams([bypass](DspParams &p) { p.bitPerfectBypass = bypass; });
}

void AudiophileDsp::setPreAmpGainDb(double gainDb) {
    mutateParams([gainDb](DspParams &p) {
        p.preAmpGainDb = gainDb;
        p.preampActive = (gainDb < -0.01 || gainDb > 0.01);
    });
}

void AudiophileDsp::setBandGain(int bandIndex, double gainDb) {
    if (bandIndex < 0 || bandIndex >= static_cast<int>(kNumBands)) return;
    mutateParams([bandIndex, gainDb](DspParams &p) {
        p.bandGainsDb[bandIndex] = gainDb;
        bool any = false;
        for (double g : p.bandGainsDb) {
            if (g < -0.01 || g > 0.01) { any = true; break; }
        }
        p.eqActive = any;
    });
}

void AudiophileDsp::setBassBoostGainDb(double gainDb) {
    mutateParams([gainDb](DspParams &p) {
        p.bassBoostGainDb = gainDb;
        p.bassBoostActive = (gainDb > 0.01);
    });
}

void AudiophileDsp::setTrebleGainDb(double gainDb) {
    mutateParams([gainDb](DspParams &p) {
        p.trebleGainDb = gainDb;
        p.trebleActive = (gainDb > 0.01);
    });
}

void AudiophileDsp::setHarmonicExciterLevel(double level) {
    mutateParams([level](DspParams &p) {
        p.harmonicExciterLevel = level;
        p.harmonicExciterActive = (level > 0.001);
    });
}

void AudiophileDsp::setClarityEnhancerGain(double gainDb) {
    mutateParams([gainDb](DspParams &p) {
        p.clarityEnhancerGainDb = gainDb;
        p.clarityActive = (gainDb > 0.01);
    });
}

void AudiophileDsp::setStereoExpansionMultiplier(double multiplier) {
    mutateParams([multiplier](DspParams &p) {
        p.stereoExpansionMultiplier = multiplier;
        p.stereoExpansionActive = (multiplier < 0.99 || multiplier > 1.01);
    });
}

void AudiophileDsp::setDvcVolume(double volume) {
    mutateParams([volume](DspParams &p) {
        p.dvcVolume = volume;
    });
}

void AudiophileDsp::setDitherStrength(double strength) {
    mutateParams([strength](DspParams &p) {
        p.ditherStrength = strength;
        p.ditherActive = (strength > 0.0001);
    });
}

void AudiophileDsp::setOutputBitDepth(int bitDepth) {
    mutateParams([bitDepth](DspParams &p) {
        p.outputBitDepth = bitDepth;
    });
}

void AudiophileDsp::setWarmSaturationLevel(double level) {
    mutateParams([level](DspParams &p) {
        p.warmSaturationLevel = level;
        p.saturationActive = (level > 0.001 || p.triodeWarmthLevel > 0.001 || p.pentodeTapeLevel > 0.001);
    });
}

void AudiophileDsp::setTriodeWarmthLevel(double level) {
    mutateParams([level](DspParams &p) {
        p.triodeWarmthLevel = level;
        p.saturationActive = (level > 0.001 || p.warmSaturationLevel > 0.001 || p.pentodeTapeLevel > 0.001);
    });
}

void AudiophileDsp::setPentodeTapeLevel(double level) {
    mutateParams([level](DspParams &p) {
        p.pentodeTapeLevel = level;
        p.saturationActive = (level > 0.001 || p.warmSaturationLevel > 0.001 || p.triodeWarmthLevel > 0.001);
    });
}

void AudiophileDsp::setCrossfeedLevel(double level) {
    mutateParams([level](DspParams &p) {
        p.crossfeedLevel = level;
        p.crossfeedActive = (level > 0.001);
    });
}

void AudiophileDsp::setLimiterEnabled(bool enabled) {
    mutateParams([enabled](DspParams &p) {
        p.limiterActive = enabled;
    });
}

void AudiophileDsp::setLimiterThresholdDb(double thresholdDb) {
    mutateParams([thresholdDb](DspParams &p) {
        p.limiterThresholdDb = thresholdDb;
    });
}

void AudiophileDsp::setSubBassMonoEnabled(bool enabled) {
    mutateParams([enabled](DspParams &p) {
        p.subBassMonoActive = enabled;
    });
}

void AudiophileDsp::setChannelBalance(double balance) {
    mutateParams([balance](DspParams &p) {
        p.channelBalance = balance;
        p.balanceActive = (balance < -0.01 || balance > 0.01);
    });
}

void AudiophileDsp::setInvertPhase(bool invert) {
    mutateParams([invert](DspParams &p) {
        p.invertPhase = invert;
    });
}

void AudiophileDsp::setAirPresenceGainDb(double gainDb) {
    mutateParams([gainDb](DspParams &p) {
        p.airPresenceGainDb = gainDb;
        p.airPresenceGainDb = gainDb;
    });
}

void AudiophileDsp::setHrtfSpatialEnabled(bool enabled) {
    mutateParams([enabled](DspParams &p) {
        p.spatialActive = enabled;
    });
}

void AudiophileDsp::setHrtfRoomSize(double roomSize) {
    mutateParams([roomSize](DspParams &p) {
        p.hrtfRoomSize = roomSize;
    });
}

void AudiophileDsp::setDspParametersBatch(const DspParams &newParams) {
    std::lock_guard<std::mutex> lk(paramWriteMutex_);
    controlParams_ = newParams;
    bitPerfectBypassFlag_.store(newParams.bitPerfectBypass, std::memory_order_release);
    publishSnapshotUnderLock();
}

DspParams AudiophileDsp::getDspParams() {
    std::lock_guard<std::mutex> lk(paramWriteMutex_);
    return controlParams_;
}

void AudiophileDsp::clearPeqBands() {
    std::lock_guard<std::mutex> lk(paramWriteMutex_);
    peqDraft_.clear();
    controlParams_.peqActive = false;
    publishSnapshotUnderLock();
}

void AudiophileDsp::addPeqBand(FilterType type, double frequency, double q, double gainDb) {
    std::lock_guard<std::mutex> lk(paramWriteMutex_);
    if (peqDraft_.size() < kMaxPeqBands) {
        PeqBandParams band;
        band.type = type;
        band.frequency = frequency;
        band.q = q;
        band.gainDb = gainDb;
        band.enabled = true;
        peqDraft_.push_back(band);
        controlParams_.peqActive = true;
        publishSnapshotUnderLock();
    }
}

void AudiophileDsp::updatePeqBand(size_t index, FilterType type, double frequency, double q, double gainDb) {
    std::lock_guard<std::mutex> lk(paramWriteMutex_);
    if (index < peqDraft_.size()) {
        auto &band = peqDraft_[index];
        band.type = type;
        band.frequency = frequency;
        band.q = q;
        band.gainDb = gainDb;
        band.enabled = true;
        publishSnapshotUnderLock();
    }
}

void AudiophileDsp::reset() {
    resetEpoch_.fetch_add(1, std::memory_order_release);
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
}

double AudiophileDsp::nextRandomDouble() {
    rngState_ = rngState_ * 6364136223846793005ULL + 1ULL;
    return static_cast<double>(rngState_ >> 11) * (1.0 / 9007199254740992.0);
}

void AudiophileDsp::process(float *audioData, int32_t numFrames, int32_t channelCount) {
    if (!audioData || numFrames <= 0 || channelCount <= 0) return;

    // 1. Check for state reset request
    const uint64_t reqReset = resetEpoch_.load(std::memory_order_acquire);
    if (reqReset != appliedResetEpoch_) {
        resetRenderStateOnly();
        appliedResetEpoch_ = reqReset;
    }

    // 2. Lock-free parameter & coefficient update check
    const int clean = cleanSlot_.load(std::memory_order_acquire);
    if (snapshotPool_[clean].generation != appliedGeneration_) {
        readSlot_ = cleanSlot_.exchange(readSlot_, std::memory_order_acq_rel);
        const DspSnapshot &snap = snapshotPool_[readSlot_];
        active_ = snap.params;
        appliedGeneration_ = snap.generation;
        applySnapshot(snap);
    }

    // 3. Bit-perfect / disabled: strict passthrough, telemetry only.
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
    const double replayGain = (p.replayGainActive && p.replayGainMultiplier > 0.0)
        ? p.replayGainMultiplier
        : (p.replayGainMultiplier > 0.0 ? p.replayGainMultiplier : 1.0);
    const double totalPreGain = preAmp * replayGain;
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

    const bool ditherOn =
        p.ditherStrength > 0.0 && p.outputBitDepth > 0 && p.outputBitDepth < 32;
    const double lsb =
        ditherOn ? (1.0 / std::pow(2.0, static_cast<double>(p.outputBitDepth - 1))) : 0.0;
    const double ditherStr = ditherOn ? p.ditherStrength : 0.0;

    const bool nonlinearEngaged =
        (warmSat + triode + pentode) > 0.0 || exciterLevel > 0.0;
    const bool shapingStageEngaged = nonlinearEngaged;

    bool hasEqGain = false;
    for (size_t b = 0; b < kNumBands; ++b) {
        if (p.bandGainsDb[b] < -0.01 || p.bandGainsDb[b] > 0.01) { hasEqGain = true; break; }
    }
    const bool replayGainActive = p.replayGainActive && (replayGain < 0.9999 || replayGain > 1.0001);
    const bool hasActiveProcessing = shapingStageEngaged || (clarityGain > 0.01) ||
        (p.bassBoostGainDb > 0.01) || (p.trebleGainDb > 0.01) || (p.preAmpGainDb < -0.01 || p.preAmpGainDb > 0.01) ||
        (peqActiveCount_ > 0) || (crossfeed > 0.001) || subMono ||
        (airGain > 0.01) || hrtfOn || hasEqGain || (stereoExp < 0.99 || stereoExp > 1.01) ||
        (balance < -0.01 || balance > 0.01) || invPhase || replayGainActive;

    // Mathematically exact identity when all features are neutral and volume is unity
    if (!hasActiveProcessing && !limiterOn && !ditherOn && (dvc >= 0.99999 && dvc <= 1.00001)) {
        double maxL = 0.0, maxR = 0.0;
        double sumLR = 0.0, sumL2 = 0.0, sumR2 = 0.0;
        for (int32_t frame = 0; frame < numFrames; ++frame) {
            const int32_t idx = frame * channelCount;
            const double l = std::abs(static_cast<double>(audioData[idx]));
            const double r = (channelCount > 1)
                ? std::abs(static_cast<double>(audioData[idx + 1])) : l;
            maxL = std::max(maxL, l);
            maxR = std::max(maxR, r);
            sumLR += l * r;
            sumL2 += l * l;
            sumR2 += r * r;
        }
        peakL_.store(peakL_.load(std::memory_order_relaxed) * 0.92 + maxL * 0.08,
                     std::memory_order_relaxed);
        peakR_.store(peakR_.load(std::memory_order_relaxed) * 0.92 + maxR * 0.08,
                     std::memory_order_relaxed);
        if (channelCount > 1 && sumL2 > 1e-12 && sumR2 > 1e-12) {
            const float corr = static_cast<float>(sumLR / (std::sqrt(sumL2 * sumR2) + 1e-12));
            phaseCorrelation_.store(phaseCorrelation_.load(std::memory_order_relaxed) * 0.95f +
                                    std::clamp(corr, -1.0f, 1.0f) * 0.05f,
                                    std::memory_order_relaxed);
        }
        return;
    }

    double runningMaxL = 0.0, runningMaxR = 0.0;
    double sumLR = 0.0, sumL2 = 0.0, sumR2 = 0.0;

    for (int32_t frame = 0; frame < numFrames; ++frame) {
        const int32_t baseIdx = frame * channelCount;
        double sL = static_cast<double>(audioData[baseIdx]);
        double sR = (channelCount > 1) ? static_cast<double>(audioData[baseIdx + 1]) : sL;

        // 1. Pre-amp and ReplayGain gain
        sL *= totalPreGain;
        sR *= totalPreGain;

        // 2. Interpolated waveshaping stage
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

                    // Symmetric 3rd-harmonic tape saturation
                    if (warmSat > 0.0) {
                        smp = smp + (warmSat * (smp * smp * smp - smp));
                    }

                    // Dedicated asymmetric 2nd-harmonic triode vacuum tube warmth
                    if (triode > 0.0) {
                        smp = smp + triode * 0.25 * (smp * smp * (smp >= 0.0 ? 1.0 : -0.5) - 0.1 * smp);
                    }

                    // Pentode soft tape compression
                    if (pentode > 0.0) {
                        smp = fastTanh(smp * (1.0 + pentode * 0.6)) / (1.0 + pentode * 0.3);
                    }

                    smpRef = smp;
                }

                double shaped = (upsampled[0] + upsampled[1]) * 0.5;

                if (exciterLevel > 0.0) {
                    auto &detailHPF = (ch == 0) ? detailHPFL_ : detailHPFR_;
                    const double highs0 = detailHPF.process(upsampled[0]);
                    const double highs1 = detailHPF.process(upsampled[1]);
                    const double harmonics = ((highs0 * highs0) + (highs1 * highs1)) * 0.5;
                    shaped += harmonics * exciterLevel * 0.6;
                }

                if (ch == 0) sL = shaped; else sR = shaped;
            }
        }

        // 3. DC removal filter
        if (hasActiveProcessing) {
            sL = dcRemovalL_.process(sL);
            sR = dcRemovalR_.process(sR);
        }

        // 4. Clarity enhancer
        if (clarityGain > 0.0) {
            sL = clarityFilterL_.process(sL);
            sR = clarityFilterR_.process(sR);
        }

        // 5. Bass shelf
        if (p.bassBoostGainDb > 0.0) {
            sL = bassShelfL_.process(sL);
            sR = bassShelfR_.process(sR);
        }

        // 6. 10-Band Graphic Equalizer
        if (hasEqGain) {
            for (size_t b = 0; b < kNumBands; ++b) {
                if (p.bandGainsDb[b] < -0.01 || p.bandGainsDb[b] > 0.01) {
                    sL = biquadsL_[b].process(sL);
                    sR = biquadsR_[b].process(sR);
                }
            }
        }

        // 7. Parametric Equalizer (PEQ)
        for (size_t i = 0; i < peqActiveCount_; ++i) {
            if (peqActive_[i].enabled) {
                sL = peqFiltersL_[i].process(sL);
                sR = peqFiltersR_[i].process(sR);
            }
        }

        // 8. Stereo processing
        if (channelCount > 1) {
            if (crossfeed > 0.0) {
                const double lowL = crossfeedLPFL_.process(sL) * crossfeed * 0.35;
                const double lowR = crossfeedLPFR_.process(sR) * crossfeed * 0.35;
                sL = sL * (1.0 - crossfeed * 0.15) + lowR;
                sR = sR * (1.0 - crossfeed * 0.15) + lowL;
            }

            if (stereoExp < 0.99 || stereoExp > 1.01) {
                const double mid = (sL + sR) * 0.5;
                const double side = (sL - sR) * 0.5 * stereoExp;
                sL = mid + side;
                sR = mid - side;
            }

            if (hrtfOn) {
                itdBufferL_[itdWriteIdx_] = sL;
                itdBufferR_[itdWriteIdx_] = sR;

                constexpr size_t delayITD = 14;
                constexpr size_t delayRoom = 240;
                const size_t readIdxITD = (itdWriteIdx_ + HRTF_BUFFER_SIZE - delayITD) % HRTF_BUFFER_SIZE;
                const size_t readIdxRoom = (itdWriteIdx_ + HRTF_BUFFER_SIZE - delayRoom) % HRTF_BUFFER_SIZE;

                const double shadowR = hrtfHeadShadowR_.process(itdBufferR_[readIdxITD]);
                const double shadowL = hrtfHeadShadowL_.process(itdBufferL_[readIdxITD]);
                const double roomReflR = itdBufferR_[readIdxRoom] * (0.15 * roomSize);
                const double roomReflL = itdBufferL_[readIdxRoom] * (0.15 * roomSize);

                sL = hrtfPinnaNotchL_.process(sL * 0.85 + shadowR * 0.35 + roomReflR);
                sR = hrtfPinnaNotchR_.process(sR * 0.85 + shadowL * 0.35 + roomReflL);

                itdWriteIdx_ = (itdWriteIdx_ + 1) % HRTF_BUFFER_SIZE;
            }

            if (subMono) {
                const double subL = subBassFilterL_.process(sL);
                const double subR = subBassFilterR_.process(sR);
                const double monoSub = (subL + subR) * 0.5;
                sL = (sL - subL) + monoSub;
                sR = (sR - subR) + monoSub;
            }

            if (invPhase) sR = -sR;

            if (balance < -0.01 || balance > 0.01) {
                const double panAngle = (std::clamp(balance, -1.0, 1.0) + 1.0) * (M_PI_VAL / 4.0);
                sL *= std::cos(panAngle) * 1.4142135623730951;
                sR *= std::sin(panAngle) * 1.4142135623730951;
            }
        }

        // 9. Treble shelf & air presence
        if (p.trebleGainDb > 0.01) {
            sL = trebleShelfL_.process(sL);
            sR = trebleShelfR_.process(sR);
        }
        if (airGain > 0.01) {
            sL = airFilterL_.process(sL);
            sR = airFilterR_.process(sR);
        }

        // 10. Anti-aliasing guard
        if (nonlinearEngaged) {
            sL = aaFilterL_.process(sL);
            sR = aaFilterR_.process(sR);
        }

        // 11. Limiter
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

        // 12. Volume
        sL *= dvc;
        sR *= dvc;

        // 13. Requantization dither
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

        runningMaxL = std::max(runningMaxL, std::abs(sL));
        runningMaxR = std::max(runningMaxR, std::abs(sR));
        sumLR += sL * sR;
        sumL2 += sL * sL;
        sumR2 += sR * sR;

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
