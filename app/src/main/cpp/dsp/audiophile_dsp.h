#pragma once

#include "biquad_filter.h"
#include <vector>
#include <array>
#include <atomic>
#include <mutex>
#include <cstdint>

namespace antigravity {

// ---------------------------------------------------------------------------
// Concurrency model (see docs/final-forensic-remediation-report.md):
//
//  * Scalar parameters live in one POD snapshot (DspParams) exchanged through
//    a seqlock: the control thread publishes complete updates, the render
//    thread takes one coherent snapshot per block. No locks on the read path.
//
//  * Filter COEFFICIENT changes are queued as commands. The render thread
//    drains the queue at a block boundary and rebuilds coefficients itself.
//    A failed try-lock only DELAYS coefficient application - filtering never
//    stops, so no audio can be dropped while the UI mutates parameters.
//
//  * The render thread is the only writer of all filter state (z1/z2),
//    telemetry accumulators and dither state.
// ---------------------------------------------------------------------------

struct PeqBandParams {
    bool enabled = true;
    FilterType type = FilterType::PEAKING_EQ;
    double frequency = 1000.0;
    double q = 1.414;
    double gainDb = 0.0;
};

struct DspParams {
    bool enabled = true;
    bool bitPerfectBypass = false;

    // Independent feature active flags (Rule 8)
    bool eqActive = false;
    bool autoEqActive = false;
    bool peqActive = false;
    bool limiterActive = false;
    bool ditherActive = false;
    bool replayGainActive = false;
    bool crossfeedActive = false;
    bool balanceActive = false;
    bool spatialActive = false;
    bool bassBoostActive = false;
    bool trebleActive = false;
    bool clarityActive = false;
    bool harmonicExciterActive = false;
    bool saturationActive = false;
    bool stereoExpansionActive = false;
    bool subBassMonoActive = false;
    bool channelTransformActive = false;
    bool preampActive = false;

    // Neutral-by-default signal chain: with no user adjustments
    // the DSP is bit-transparent apart from the final safety clamp.
    double preAmpGainDb = 0.0;
    double bassBoostGainDb = 0.0;
    double trebleGainDb = 0.0;
    double harmonicExciterLevel = 0.0;
    double clarityEnhancerGainDb = 0.0;
    double stereoExpansionMultiplier = 1.0;
    double dvcVolume = 1.0;
    double replayGainMultiplier = 1.0;
    double ditherStrength = 0.0;
    int32_t outputBitDepth = 24;
    double warmSaturationLevel = 0.0;
    double triodeWarmthLevel = 0.0;
    double pentodeTapeLevel = 0.0;
    double crossfeedLevel = 0.0;
    double limiterThresholdDb = 0.0;
    double channelBalance = 0.0;
    bool invertPhase = false;
    double airPresenceGainDb = 0.0;
    double hrtfRoomSize = 0.5;
    double bandGainsDb[10] = {0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0};
};

class AudiophileDsp {
public:
    AudiophileDsp();
    ~AudiophileDsp() = default;

    void setSampleRate(double sampleRate);
    void process(float *audioData, int32_t numFrames, int32_t channelCount);

    // Live parameter mutators (control thread).
    void setEnabled(bool enabled);
    void setBitPerfectBypass(bool bypass);
    void setPreAmpGainDb(double gainDb);
    void setBandGain(int bandIndex, double gainDb);
    void setBassBoostGainDb(double gainDb);
    void setTrebleGainDb(double gainDb);
    void setHarmonicExciterLevel(double level);
    void setClarityEnhancerGain(double gainDb);
    void setStereoExpansionMultiplier(double multiplier);
    void setDvcVolume(double volume);
    void setDitherStrength(double strength);
    void setOutputBitDepth(int bitDepth);
    void setWarmSaturationLevel(double level);
    void setTriodeWarmthLevel(double level);
    void setPentodeTapeLevel(double level);
    void setCrossfeedLevel(double level);
    void setLimiterEnabled(bool enabled);
    void setLimiterThresholdDb(double thresholdDb);
    void setSubBassMonoEnabled(bool enabled);
    void setChannelBalance(double balance);
    void setInvertPhase(bool invert);
    void setAirPresenceGainDb(double gainDb);
    void setHrtfSpatialEnabled(bool enabled);
    void setHrtfRoomSize(double roomSize);

    // Batch parameter mutator (atomic update for all parameters in one seqlock cycle)
    void setDspParametersBatch(const DspParams &newParams);
    DspParams getDspParams();

    // Parametric EQ (PEQ)
    void clearPeqBands();
    void addPeqBand(FilterType type, double frequency, double q, double gainDb);
    void updatePeqBand(size_t index, FilterType type, double frequency, double q, double gainDb);

    // Telemetry (atomic, safe from any thread)
    double getPeakL() const { return peakL_.load(std::memory_order_relaxed); }
    double getPeakR() const { return peakR_.load(std::memory_order_relaxed); }
    float getPhaseCorrelation() const { return phaseCorrelation_.load(std::memory_order_relaxed); }
    bool isBitPerfectBypass() const { return bitPerfectBypassFlag_.load(std::memory_order_acquire); }

    void reset();

private:
    struct Command {
        enum class Kind : uint8_t {
            kSyncCoefficients,   // rebuild every filter from current params
            kResetState          // clear all filter/delay state
        };
        Kind kind = Kind::kSyncCoefficients;
    };

    static constexpr size_t kNumBands = 10;
    static constexpr size_t HRTF_BUFFER_SIZE = 2048;

    // ---- Seqlock parameter publisher -------------------------------------
    template <typename F>
    void mutateParams(F &&mutator) {
        std::lock_guard<std::mutex> lk(paramWriteMutex_);
        const uint32_t s = paramSeq_.load(std::memory_order_relaxed);
        paramSeq_.store(s + 1u, std::memory_order_release);      // odd: writing
        mutator(params_);
        std::atomic_thread_fence(std::memory_order_release);
        paramSeq_.store(s + 2u, std::memory_order_release);      // even: stable
        coefficientSyncPending_.store(true, std::memory_order_release);
    }

    void readParams(DspParams &out);       // render thread
    static constexpr size_t kMaxPeqBands = 32;

    void enqueueCommand(const Command &cmd);
    void applyCoefficientSync();           // render thread, block boundary
    void buildFilterSet(double fs);        // render thread, helper of the above
    void rebuildPeqFilters(const std::array<PeqBandParams, kMaxPeqBands> &specs, size_t count); // render thread
    void resetRenderStateOnly();           // render thread (or pre-render ctor)
    double nextRandomDouble();             // render thread
    static void configureBiquad(BiquadFilter &f, FilterType type,
                                double frequency, double q, double gainDb, double fs);

    static constexpr std::array<double, 10> kBandCenterFreqs = {
        31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0
    };

    // ---- Control-side storage --------------------------------------------
    std::mutex paramWriteMutex_;
    std::atomic<uint32_t> paramSeq_{0};
    DspParams params_{};
    DspParams lastGoodParams_{};           // reader fallback (render thread only)

    std::mutex commandMutex_;
    std::vector<Command> pendingCommands_;
    std::vector<PeqBandParams> peqDraft_;  // guarded by commandMutex_

    std::atomic<bool> bitPerfectBypassFlag_{false};

    // ---- Render-thread-owned state ---------------------------------------
    double sampleRate_ = 48000.0;
    std::atomic<double> requestedSampleRate_{48000.0};
    DspParams active_{};                   // last coherent snapshot used

    std::array<BiquadFilter, kNumBands> biquadsL_;
    std::array<BiquadFilter, kNumBands> biquadsR_;
    BiquadFilter bassShelfL_, bassShelfR_;
    BiquadFilter trebleShelfL_, trebleShelfR_;
    BiquadFilter detailHPFL_, detailHPFR_;
    BiquadFilter clarityFilterL_, clarityFilterR_;
    BiquadFilter crossfeedLPFL_, crossfeedLPFR_;
    BiquadFilter airFilterL_, airFilterR_;
    BiquadFilter dcRemovalL_, dcRemovalR_;
    BiquadFilter dcBlockerL_, dcBlockerR_;
    BiquadFilter aaFilterL_, aaFilterR_;
    BiquadFilter subBassFilterL_, subBassFilterR_;
    BiquadFilter hrtfHeadShadowL_, hrtfHeadShadowR_;
    BiquadFilter hrtfPinnaNotchL_, hrtfPinnaNotchR_;

    size_t peqActiveCount_ = 0;
    std::array<PeqBandParams, kMaxPeqBands> peqActive_{};     // fixed preallocated spec set
    std::array<BiquadFilter, kMaxPeqBands> peqFiltersL_{};    // fixed parallel runtime states
    std::array<BiquadFilter, kMaxPeqBands> peqFiltersR_{};

    // Oversampling history (interpolated waveshaping stage)
    std::array<double, 4> osSamplesL_{};
    std::array<double, 4> osSamplesR_{};

    // HRTF ITD circular buffers
    std::array<double, HRTF_BUFFER_SIZE> itdBufferL_{};
    std::array<double, HRTF_BUFFER_SIZE> itdBufferR_{};
    size_t itdWriteIdx_ = 0;

    // Requantization-dither state (render thread only)
    double ditherErrorL_ = 0.0;
    double ditherErrorR_ = 0.0;
    uint64_t rngState_ = 0x853c49e6748fea9bULL;

    // Telemetry
    std::atomic<double> peakL_{0.0};
    std::atomic<double> peakR_{0.0};
    std::atomic<float> phaseCorrelation_{1.0f};

    std::atomic<bool> coefficientSyncPending_{false};
};

} // namespace antigravity
