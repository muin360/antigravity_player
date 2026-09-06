#pragma once

#include "biquad_filter.h"
#include <vector>
#include <array>
#include <atomic>
#include <mutex>
#include <cstdint>

namespace antigravity {

// ---------------------------------------------------------------------------
// Lock-Free Triple-Buffer Parameter & Coefficient Concurrency Model:
//
//  * Control thread computes complete parameters and filter coefficients
//    under paramWriteMutex_ into an isolated write slot of a 3-slot pool.
//
//  * Once completely built, the write slot is atomically exchanged with
//    cleanSlot_ (release semantics).
//
//  * The render thread checks cleanSlot_ at the start of each block. If the
//    generation differs from appliedGeneration_, it exchanges its read slot
//    with cleanSlot_ (acquire semantics) and applies the immutable coefficients.
//
//  * Mathematical proof of lock-freedom:
//    {readSlot_, writeSlot_, cleanSlot_} are always a permutation of {0, 1, 2}.
//    The render thread NEVER reads a slot currently being written by the control
//    thread, NEVER acquires a mutex, and NEVER allocates heap memory.
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

    // Independent feature active flags
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
    // the DSP is bit-transparent.
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

struct FilterCoefficients {
    std::array<BiquadCoefficients, 10> biquadsL{};
    std::array<BiquadCoefficients, 10> biquadsR{};
    BiquadCoefficients bassShelfL{}, bassShelfR{};
    BiquadCoefficients trebleShelfL{}, trebleShelfR{};
    BiquadCoefficients detailHPFL{}, detailHPFR{};
    BiquadCoefficients clarityFilterL{}, clarityFilterR{};
    BiquadCoefficients crossfeedLPFL{}, crossfeedLPFR{};
    BiquadCoefficients airFilterL{}, airFilterR{};
    BiquadCoefficients dcRemovalL{}, dcRemovalR{};
    BiquadCoefficients dcBlockerL{}, dcBlockerR{};
    BiquadCoefficients aaFilterL{}, aaFilterR{};
    BiquadCoefficients subBassFilterL{}, subBassFilterR{};
    BiquadCoefficients hrtfHeadShadowL{}, hrtfHeadShadowR{};
    BiquadCoefficients hrtfPinnaNotchL{}, hrtfPinnaNotchR{};
    size_t peqCount = 0;
    std::array<PeqBandParams, 32> peqActive{};
    std::array<BiquadCoefficients, 32> peqL{};
    std::array<BiquadCoefficients, 32> peqR{};
};

struct DspSnapshot {
    uint64_t generation = 0;
    DspParams params{};
    FilterCoefficients coeffs{};
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

    // Unified Atomic DSP Publication (Base parameters + PEQ bands in ONE transaction)
    void setDspUnifiedConfig(const DspParams &newParams, const std::vector<PeqBandParams> &bands);

    // Batch parameter mutator
    void setDspParametersBatch(const DspParams &newParams);
    DspParams getDspParams();

    // Parametric EQ (PEQ)
    void clearPeqBands();
    void addPeqBand(FilterType type, double frequency, double q, double gainDb);
    void updatePeqBand(size_t index, FilterType type, double frequency, double q, double gainDb);
    void setPeqBandsBatch(const std::vector<PeqBandParams> &bands);

    // Telemetry (atomic, safe from any thread)
    double getPeakL() const { return peakL_.load(std::memory_order_relaxed); }
    double getPeakR() const { return peakR_.load(std::memory_order_relaxed); }
    float getPhaseCorrelation() const { return phaseCorrelation_.load(std::memory_order_relaxed); }
    bool isBitPerfectBypass() const { return bitPerfectBypassFlag_.load(std::memory_order_acquire); }

    void reset();

private:
    static constexpr size_t kNumBands = 10;
    static constexpr size_t kMaxPeqBands = 32;
    static constexpr size_t HRTF_BUFFER_SIZE = 2048;

    template <typename F>
    void mutateParams(F &&mutator) {
        std::lock_guard<std::mutex> lk(paramWriteMutex_);
        mutator(controlParams_);
        publishSnapshotUnderLock();
    }

    void publishSnapshotUnderLock();
    static void computeCoefficients(const DspParams &p,
                                    const std::vector<PeqBandParams> &peqBands,
                                    double fs,
                                    FilterCoefficients &outCoeffs);

    void applySnapshot(const DspSnapshot &snap);
    void resetRenderStateOnly();
    double nextRandomDouble();

    static constexpr std::array<double, 10> kBandCenterFreqs = {
        31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0
    };

    // ---- Control-side state (guarded by paramWriteMutex_) -----------------
    std::mutex paramWriteMutex_;
    DspParams controlParams_{};
    std::vector<PeqBandParams> peqDraft_;
    double controlSampleRate_ = 48000.0;
    uint64_t publishedGeneration_ = 0;

    // ---- Lock-Free Triple Buffer Exchange ---------------------------------
    std::array<DspSnapshot, 3> snapshotPool_{};
    std::atomic<int> cleanSlot_{0};
    std::atomic<uint64_t> publishedGen_{1};
    int writeSlot_ = 2;

    std::atomic<bool> bitPerfectBypassFlag_{false};
    std::atomic<uint64_t> resetEpoch_{0};

    // ---- Render-thread-owned state (ONLY accessed by process()) -----------
    int readSlot_ = 1;
    uint64_t appliedGeneration_ = 0;
    uint64_t appliedResetEpoch_ = 0;
    DspParams active_{};

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
    std::array<PeqBandParams, kMaxPeqBands> peqActive_{};
    std::array<BiquadFilter, kMaxPeqBands> peqFiltersL_{};
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
};

} // namespace antigravity
