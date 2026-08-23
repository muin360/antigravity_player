#include "audiophile_resampler.h"
#include <algorithm>
#include <cmath>

namespace antigravity {

static constexpr double M_PI_VAL = 3.14159265358979323846;

AudiophileResampler::AudiophileResampler() {
    historyBuffer_.assign(MAX_HISTORY_FRAMES * 2, 0.0f);
}

std::shared_ptr<const AudiophileResampler::Config> AudiophileResampler::buildConfig(
    int32_t inSampleRate, int32_t outSampleRate, int32_t channelCount,
    ResampleQuality quality, uint64_t generation) {

    auto cfg = std::make_shared<Config>();
    cfg->channelCount = std::max(1, channelCount);
    cfg->quality = quality;
    const double inRate = std::max(1.0, static_cast<double>(inSampleRate));
    const double outRate = std::max(1.0, static_cast<double>(outSampleRate));
    cfg->ratio = inRate / outRate;
    cfg->passThrough =
        inSampleRate <= 0 || outSampleRate <= 0 || inSampleRate == outSampleRate;
    cfg->taps = (quality == ResampleQuality::SINC_BEST) ? 64
              : ((quality == ResampleQuality::SINC_FAST) ? 16 : 4);
    cfg->halfTaps = cfg->taps / 2;
    cfg->generation = generation;

    if (!cfg->passThrough) {
        // Windowed-sinc polyphase bank. Stopband behaviour depends on tap
        // count and the Blackman-Nutall window; no specific SNR figure is
        // claimed without measurement (see docs/final-forensic-remediation-report.md).
        const double cutoff = (cfg->ratio > 1.0) ? (0.95 / cfg->ratio) : 0.95;
        cfg->polyphaseTable.assign(NUM_PHASES, std::vector<double>(cfg->taps, 0.0));

        for (int phase = 0; phase < NUM_PHASES; ++phase) {
            const double phaseFrac = static_cast<double>(phase) / NUM_PHASES;
            double sumWeights = 0.0;
            for (int t = 0; t < cfg->taps; ++t) {
                const double delta = (t - cfg->halfTaps) - phaseFrac;
                const double weight =
                    blackmanNutall(delta / cfg->halfTaps) * sinc(delta * cutoff) * cutoff;
                cfg->polyphaseTable[phase][t] = weight;
                sumWeights += weight;
            }
            if (std::abs(sumWeights) > 1.0e-9) {
                for (int t = 0; t < cfg->taps; ++t) cfg->polyphaseTable[phase][t] /= sumWeights;
            }
        }
    }
    return cfg;
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
    pendingInRate_ = inSampleRate;
    pendingOutRate_ = outSampleRate;
    static std::atomic<uint64_t> gGenerationSequence{1};
    const uint64_t gen = gGenerationSequence.fetch_add(1, std::memory_order_relaxed);
    auto cfg = buildConfig(inSampleRate, outSampleRate, channelCount, quality, gen);
    // Atomic publish: any concurrent process() keeps using the previous
    // complete configuration until it loads the new pointer.
    std::atomic_store_explicit(&published_, std::shared_ptr<const Config>(cfg),
                               std::memory_order_release);
}

bool AudiophileResampler::isPassThrough() const {
    if (auto cfg = std::atomic_load_explicit(&published_, std::memory_order_acquire)) {
        return cfg->passThrough;
    }
    return true;
}

void AudiophileResampler::reset() {
    // Defer to render thread; never mutate streaming state concurrently.
    resetRequested_.store(true, std::memory_order_release);
}

void AudiophileResampler::migrateTo(const Config &cfg) {
    timePos_ = 0.0;
    historyBuffer_.assign(static_cast<size_t>(MAX_HISTORY_FRAMES) * cfg.channelCount, 0.0f);
    workBuffer_.clear();
}

AudiophileResampler::Result AudiophileResampler::process(
    const float *inData, int32_t inFrames, std::vector<float> &outBuffer) {

    Result result;
    if (!inData || inFrames <= 0) return result;

    auto cfg = std::atomic_load_explicit(&published_, std::memory_order_acquire);
    if (!cfg) { result.inputFramesConsumed = 0; return result; }

    if (cfg != active_) {
        migrateTo(*cfg);   // one-time state migration on the render thread
        active_ = cfg;
        activeGeneration_ = cfg->generation;
    }

    if (resetRequested_.exchange(false, std::memory_order_acq_rel)) {
        timePos_ = 0.0;
        std::fill(historyBuffer_.begin(), historyBuffer_.end(), 0.0f);
    }

    const int32_t ch = cfg->channelCount;

    if (cfg->passThrough) {
        const size_t totalSamples = static_cast<size_t>(inFrames) * ch;
        if (outBuffer.size() < totalSamples) outBuffer.resize(totalSamples);
        std::copy(inData, inData + totalSamples, outBuffer.begin());
        result.outputFrames = inFrames;
        result.inputFramesConsumed = inFrames;
        return result;
    }

    const double ratio = cfg->ratio;
    const int taps = cfg->taps;
    const int halfTaps = cfg->halfTaps;
    const int32_t estimatedOutFrames =
        static_cast<int32_t>(std::ceil(inFrames / ratio)) + 4;
    const size_t neededOut = static_cast<size_t>(estimatedOutFrames) * ch;
    if (outBuffer.size() < neededOut) outBuffer.resize(neededOut);

    const int32_t historyFrames = MAX_HISTORY_FRAMES;
    const int32_t totalWorkFrames = historyFrames + inFrames;
    const size_t totalWorkSamples = static_cast<size_t>(totalWorkFrames) * ch;
    if (workBuffer_.size() < totalWorkSamples) workBuffer_.resize(totalWorkSamples * 2);

    std::copy(historyBuffer_.begin(), historyBuffer_.end(), workBuffer_.begin());
    std::copy(inData, inData + static_cast<size_t>(inFrames) * ch,
              workBuffer_.begin() + historyBuffer_.size());

    int32_t outFrameCount = 0;

    while (timePos_ + halfTaps < inFrames) {
        const double currentInTime = historyFrames + timePos_;
        const int32_t baseInFrame = static_cast<int32_t>(std::floor(currentInTime));
        const double frac = currentInTime - baseInFrame;

        if (cfg->quality == ResampleQuality::HERMITE_FAST) {
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
            // Windowed-sinc polyphase FIR interpolation.
            const int phase =
                std::clamp(static_cast<int>(frac * NUM_PHASES), 0, static_cast<int>(NUM_PHASES) - 1);
            const auto &phaseWeights = cfg->polyphaseTable[phase];

            for (int32_t c = 0; c < ch; ++c) {
                double sample = 0.0;
                for (int t = 0; t < taps; ++t) {
                    const int32_t srcFrame =
                        std::clamp(baseInFrame - halfTaps + t, 0, totalWorkFrames - 1);
                    sample += workBuffer_[static_cast<size_t>(srcFrame) * ch + c] *
                              phaseWeights[t];
                }
                outBuffer[static_cast<size_t>(outFrameCount) * ch + c] =
                    static_cast<float>(std::clamp(sample, -1.0, 1.0));
            }
        }

        ++outFrameCount;
        timePos_ += ratio;
    }

    timePos_ -= inFrames;

    // Save tail to history buffer for the next block.
    const int32_t copyStartFrame = std::max(0, totalWorkFrames - historyFrames);
    for (int32_t f = 0; f < historyFrames; ++f) {
        for (int32_t c = 0; c < ch; ++c) {
            const size_t srcIdx = (static_cast<size_t>(copyStartFrame + f)) * ch + c;
            historyBuffer_[static_cast<size_t>(f) * ch + c] =
                (srcIdx < workBuffer_.size()) ? workBuffer_[srcIdx] : 0.0f;
        }
    }

    if (outBuffer.size() > static_cast<size_t>(outFrameCount) * ch) {
        outBuffer.resize(static_cast<size_t>(outFrameCount) * ch);
    }
    result.outputFrames = outFrameCount;
    // All input was either emitted or folded into the history/timePos state:
    // nothing is dropped or duplicated across calls.
    result.inputFramesConsumed = inFrames;
    return result;
}

} // namespace antigravity
