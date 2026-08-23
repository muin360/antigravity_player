#pragma once

#include <vector>
#include <cstdint>
#include <cmath>
#include <memory>
#include <atomic>

namespace antigravity {

enum class ResampleQuality {
    HERMITE_FAST = 0,
    SINC_FAST = 1,
    SINC_BEST = 2
};

class AudiophileResampler {
public:
    // Explicit data-plane contract for one process() call.
    //   outputFrames       : frames written into outBuffer
    //   inputFramesConsumed: input frames actually consumed from inData.
    // The caller MUST advance its input cursor by exactly inputFramesConsumed
    // and must NOT write any unresampled fallback data when outputFrames == 0.
    struct Result {
        int32_t outputFrames = 0;
        int32_t inputFramesConsumed = 0;
    };

    AudiophileResampler();
    ~AudiophileResampler() = default;

    // Control-thread only. Builds a complete immutable configuration and
    // publishes it atomically. Streaming state is migrated by the render
    // thread at the next process() call (never torn mid-block).
    void configure(int32_t inSampleRate, int32_t outSampleRate, int32_t channelCount,
                   ResampleQuality quality = ResampleQuality::SINC_FAST);

    // Render-thread call. Returns produced output frames and the exact number
    // of input frames consumed. All input passed in a successful call is
    // consumed (buffered internally when upsampling), so callers may treat
    // consumption as total while draining produced output asynchronously.
    Result process(const float *inData, int32_t inFrames, std::vector<float> &outBuffer);

    // Safe to call from any thread: defers the actual state clear to the
    // render thread so it can never race an in-flight process() call.
    void reset();

    bool isPassThrough() const;
    int32_t getInputSampleRate() const { return pendingInRate_; }
    int32_t getOutputSampleRate() const { return pendingOutRate_; }

private:
    struct Config {
        int32_t channelCount = 2;
        ResampleQuality quality = ResampleQuality::SINC_FAST;
        double ratio = 1.0;          // inRate / outRate
        int taps = 16;
        int halfTaps = 8;
        bool passThrough = true;
        uint64_t generation = 0;     // bumped on every configure()
        std::vector<std::vector<double>> polyphaseTable; // NUM_PHASES x taps
    };

    static constexpr int32_t NUM_PHASES = 64;
    static constexpr int32_t MAX_HISTORY_FRAMES = 128;

    static std::shared_ptr<const Config> buildConfig(
        int32_t inSampleRate, int32_t outSampleRate, int32_t channelCount,
        ResampleQuality quality, uint64_t generation);

    static double sinc(double x);
    static double blackmanNutall(double x);

    void migrateTo(const Config &cfg);

    // Immutable published configuration (atomic shared_ptr access).
    std::shared_ptr<const Config> published_;
    int32_t pendingInRate_ = 48000;
    int32_t pendingOutRate_ = 48000;

    // Render-thread-owned streaming state.
    std::shared_ptr<const Config> active_;
    uint64_t activeGeneration_ = 0;
    double timePos_ = 0.0;
    std::vector<float> historyBuffer_;
    std::vector<float> workBuffer_;

    // Deferred reset request (control thread sets, render thread executes).
    std::atomic<bool> resetRequested_{false};
};

} // namespace antigravity
