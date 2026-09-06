#include "audiophile_resampler.h"
#include <algorithm>
#include <cmath>

namespace antigravity {

static constexpr double M_PI_VAL = 3.14159265358979323846;

AudiophileResampler::AudiophileResampler() {
    historyBuffer_.assign(static_cast<size_t>(MAX_HISTORY_FRAMES) * MAX_CHANNELS, 0.0f);
    workBuffer_.assign(static_cast<size_t>(MAX_HISTORY_FRAMES + MAX_INPUT_FRAMES) * MAX_CHANNELS, 0.0f);
    outputBuffer_.assign(static_cast<size_t>(MAX_OUTPUT_FRAMES) * MAX_CHANNELS, 0.0f);

    for (auto &cfg : configPool_) {
        populateConfig(cfg, 48000, 48000, 2, ResampleQuality::SINC_FAST, 1);
    }
    activeGeneration_ = configPool_[renderSlot_].generation;
}

void AudiophileResampler::populateConfig(
    Config &cfg,
    int32_t inSampleRate, int32_t outSampleRate, int32_t channelCount,
    ResampleQuality quality, uint64_t generation) {

    cfg.channelCount = std::clamp(channelCount, 1, MAX_CHANNELS);
    cfg.quality = quality;
    const double inRate = std::max(1.0, static_cast<double>(inSampleRate));
    const double outRate = std::max(1.0, static_cast<double>(outSampleRate));
    cfg.ratio = inRate / outRate;
    cfg.passThrough =
        inSampleRate <= 0 || outSampleRate <= 0 || inSampleRate == outSampleRate;
    cfg.taps = (quality == ResampleQuality::SINC_BEST) ? 64
              : ((quality == ResampleQuality::SINC_FAST) ? 16 : 4);
    cfg.halfTaps = cfg.taps / 2;
    cfg.generation = generation;
    cfg.polyphaseTable.fill(0.0);

    if (!cfg.passThrough) {
        // Windowed-sinc polyphase bank. Stopband behaviour depends on tap
        // count and the Blackman-Nutall window.
        const double cutoff = (cfg.ratio > 1.0) ? (0.95 / cfg.ratio) : 0.95;

        for (int phase = 0; phase < NUM_PHASES; ++phase) {
            const double phaseFrac = static_cast<double>(phase) / NUM_PHASES;
            double sumWeights = 0.0;
            const size_t phaseOffset = static_cast<size_t>(phase) * MAX_TAPS;
            for (int t = 0; t < cfg.taps; ++t) {
                const double delta = (t - cfg.halfTaps) - phaseFrac;
                const double weight =
                    blackmanNutall(delta / cfg.halfTaps) * sinc(delta * cutoff) * cutoff;
                cfg.polyphaseTable[phaseOffset + t] = weight;
                sumWeights += weight;
            }
            if (std::abs(sumWeights) > 1.0e-9) {
                for (int t = 0; t < cfg.taps; ++t) {
                    cfg.polyphaseTable[phaseOffset + t] /= sumWeights;
                }
            }
        }
    }
}

double AudiophileResampler::sinc(double x) {
    if (std::abs(x) < 1.0e-9) return 1.0;
    const double px = M_PI_VAL * x;
    return std::sin(px) / px;
}

double AudiophileResampler::blackmanNutall(double x) {
    // x normalized from -1.0 to 1.0
    const double nx = (x + 1.0) * 0.5;
    if (nx < 0.0 || nx > 1.0) return 0.0;

    static constexpr double a0 = 0.3635819;
    static constexpr double a1 = 0.4891775;
    static constexpr double a2 = 0.1365995;
    static constexpr double a3 = 0.0106411;

    const double p = 2.0 * M_PI_VAL * nx;
    return a0 - a1 * std::cos(p) + a2 * std::cos(2.0 * p) - a3 * std::cos(3.0 * p);
}

void AudiophileResampler::configure(int32_t inSampleRate, int32_t outSampleRate,
                                    int32_t channelCount, ResampleQuality quality) {
    std::lock_guard<std::mutex> lock(configMutex_);
    pendingInRate_.store(inSampleRate, std::memory_order_relaxed);
    pendingOutRate_.store(outSampleRate, std::memory_order_relaxed);
    static std::atomic<uint64_t> gGenerationSequence{1};
    const uint64_t gen = gGenerationSequence.fetch_add(1, std::memory_order_relaxed);

    // Populate control-thread-owned writeSlot_
    Config &targetCfg = configPool_[writeSlot_];
    populateConfig(targetCfg, inSampleRate, outSampleRate, channelCount, quality, gen);

    isPassThrough_.store(targetCfg.passThrough, std::memory_order_release);

    // Atomic triple-buffer swap: exchange writeSlot_ with cleanSlot_
    writeSlot_ = cleanSlot_.exchange(writeSlot_, std::memory_order_acq_rel);

    // Publish new generation
    publishedGen_.store(gen, std::memory_order_release);
}

bool AudiophileResampler::isPassThrough() const {
    return isPassThrough_.load(std::memory_order_acquire);
}

void AudiophileResampler::reset() {
    // Defer to render thread; never mutate streaming state concurrently.
    resetRequested_.store(true, std::memory_order_release);
}

void AudiophileResampler::migrateTo(const Config &cfg) {
    timePos_ = 0.0;
    // Zero-allocation: preallocated for MAX_CHANNELS in constructor, zero active slice only
    const size_t ch = static_cast<size_t>(std::clamp(cfg.channelCount, 1, MAX_CHANNELS));
    const size_t activeHistorySamples = static_cast<size_t>(MAX_HISTORY_FRAMES) * ch;
    std::fill(historyBuffer_.begin(), historyBuffer_.begin() + activeHistorySamples, 0.0f);
}

AudiophileResampler::Result AudiophileResampler::process(
    const float *inData, int32_t inFrames) {

    Result result;
    if (!inData || inFrames <= 0) return result;

    const uint64_t pubGen = publishedGen_.load(std::memory_order_acquire);
    if (pubGen != activeGeneration_) {
        // Exchange renderSlot_ with cleanSlot_ to acquire the latest published config
        renderSlot_ = cleanSlot_.exchange(renderSlot_, std::memory_order_acq_rel);
        const Config &newCfg = configPool_[renderSlot_];
        activeGeneration_ = newCfg.generation;
        migrateTo(newCfg);
    }

    const Config &cfg = configPool_[renderSlot_];
    const int32_t ch = std::clamp(cfg.channelCount, 1, MAX_CHANNELS);

    if (resetRequested_.exchange(false, std::memory_order_acq_rel)) {
        timePos_ = 0.0;
        const size_t activeHistorySamples = static_cast<size_t>(MAX_HISTORY_FRAMES) * static_cast<size_t>(ch);
        std::fill(historyBuffer_.begin(), historyBuffer_.begin() + activeHistorySamples, 0.0f);
    }

    if (cfg.passThrough) {
        // Direct zero-copy pass-through: caller receives the exact input pointer
        result.outputFrames = inFrames;
        result.inputFramesConsumed = inFrames;
        result.outputData = inData;
        return result;
    }

    // Safety clamp to guaranteed preallocated capacity: NEVER reallocate on audio thread
    const int32_t clampedInFrames = std::min(inFrames, MAX_INPUT_FRAMES);

    const double ratio = cfg.ratio;
    const int taps = cfg.taps;
    const int halfTaps = cfg.halfTaps;

    const int32_t historyFrames = MAX_HISTORY_FRAMES;
    const size_t historySamples = static_cast<size_t>(historyFrames) * static_cast<size_t>(ch);
    const int32_t totalWorkFrames = historyFrames + clampedInFrames;

    std::copy(historyBuffer_.begin(), historyBuffer_.begin() + historySamples, workBuffer_.begin());
    std::copy(inData, inData + static_cast<size_t>(clampedInFrames) * ch,
              workBuffer_.begin() + historySamples);

    int32_t outFrameCount = 0;
    const int32_t maxOutFrames = MAX_OUTPUT_FRAMES;
    float *outBuffer = outputBuffer_.data();

    while (timePos_ + halfTaps < clampedInFrames && outFrameCount < maxOutFrames) {
        const double currentInTime = historyFrames + timePos_;
        const int32_t baseInFrame = static_cast<int32_t>(std::floor(currentInTime));
        const double frac = currentInTime - baseInFrame;

        if (cfg.quality == ResampleQuality::HERMITE_FAST) {
            // 4-point Hermite cubic interpolation
            for (int32_t c = 0; c < ch; ++c) {
                const int32_t f0 = std::clamp(baseInFrame - 1, 0, totalWorkFrames - 1);
                const int32_t f1 = std::clamp(baseInFrame, 0, totalWorkFrames - 1);
                const int32_t f2 = std::clamp(baseInFrame + 1, 0, totalWorkFrames - 1);
                const int32_t f3 = std::clamp(baseInFrame + 2, 0, totalWorkFrames - 1);

                const double v0 = workBuffer_[static_cast<size_t>(f0) * ch + c];
                const double v1 = workBuffer_[static_cast<size_t>(f1) * ch + c];
                const double v2 = workBuffer_[static_cast<size_t>(f2) * ch + c];
                const double v3 = workBuffer_[static_cast<size_t>(f3) * ch + c];

                const double a = -0.5 * v0 + 1.5 * v1 - 1.5 * v2 + 0.5 * v3;
                const double b = v0 - 2.5 * v1 + 2.0 * v2 - 0.5 * v3;
                const double cc = -0.5 * v0 + 0.5 * v2;
                const double sample =
                    a * frac * frac * frac + b * frac * frac + cc * frac + v1;
                outBuffer[static_cast<size_t>(outFrameCount) * ch + c] =
                    static_cast<float>(std::clamp(sample, -1.0, 1.0));
            }
        } else {
            // Windowed-sinc polyphase FIR interpolation with linear inter-phase interpolation.
            const double phaseRaw = frac * NUM_PHASES;
            const int phase1 = std::clamp(static_cast<int>(std::floor(phaseRaw)), 0, static_cast<int>(NUM_PHASES) - 1);
            const int phase2 = (phase1 + 1 < static_cast<int>(NUM_PHASES)) ? (phase1 + 1) : phase1;
            const double phaseFrac = phaseRaw - std::floor(phaseRaw);
            
            const size_t offset1 = static_cast<size_t>(phase1) * MAX_TAPS;
            const size_t offset2 = static_cast<size_t>(phase2) * MAX_TAPS;

            for (int32_t c = 0; c < ch; ++c) {
                double sample1 = 0.0;
                double sample2 = 0.0;
                
                #pragma clang loop vectorize(enable)
                #pragma omp simd
                for (int t = 0; t < taps; ++t) {
                    const int32_t srcFrame =
                        std::clamp(baseInFrame - halfTaps + t, 0, totalWorkFrames - 1);
                    const double srcSample = workBuffer_[static_cast<size_t>(srcFrame) * ch + c];
                    sample1 += srcSample * cfg.polyphaseTable[offset1 + t];
                    sample2 += srcSample * cfg.polyphaseTable[offset2 + t];
                }
                
                const double sample = sample1 + (sample2 - sample1) * phaseFrac;
                outBuffer[static_cast<size_t>(outFrameCount) * ch + c] =
                    static_cast<float>(std::clamp(sample, -1.0, 1.0));
            }
        }

        ++outFrameCount;
        timePos_ += ratio;
    }

    timePos_ -= clampedInFrames;

    // Save tail to history buffer for the next block.
    const int32_t copyStartFrame = std::max(0, totalWorkFrames - historyFrames);
    for (int32_t f = 0; f < historyFrames; ++f) {
        for (int32_t c = 0; c < ch; ++c) {
            const size_t srcIdx = (static_cast<size_t>(copyStartFrame + f)) * ch + c;
            historyBuffer_[static_cast<size_t>(f) * ch + c] =
                (srcIdx < workBuffer_.size()) ? workBuffer_[srcIdx] : 0.0f;
        }
    }

    result.outputFrames = outFrameCount;
    result.inputFramesConsumed = clampedInFrames;
    result.outputData = outBuffer;
    return result;
}

} // namespace antigravity
