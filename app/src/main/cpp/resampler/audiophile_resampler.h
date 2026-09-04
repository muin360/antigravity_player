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
    static constexpr int32_t MAX_INPUT_FRAMES = 8192;
    static constexpr int32_t MAX_HISTORY_FRAMES = 128;
    // Support upsampling up to 384 kHz (8.7x). Sized for up to 8 channels.
    static constexpr int32_t MAX_OUTPUT_FRAMES = 73728;

    // Explicit data-plane contract for one process() call.
    //   outputFrames       : frames produced and available at outputData
    //   inputFramesConsumed: input frames actually consumed from inData
    //   outputData         : pointer to contiguous interleaved float samples
    //                        (in pass-through mode, points directly to inData;
    //                         in resample mode, points to internal preallocated buffer)
    struct Result {
        int32_t outputFrames = 0;
        int32_t inputFramesConsumed = 0;
        const float *outputData = nullptr;
    };

    AudiophileResampler();
    ~AudiophileResampler() = default;

    // Control-thread only. Builds a complete immutable configuration and
    // publishes it atomically. Streaming state is migrated by the render
    // thread at the next process() call (never torn mid-block).
    void configure(int32_t inSampleRate, int32_t outSampleRate, int32_t channelCount,
                   ResampleQuality quality = ResampleQuality::SINC_FAST);

    // Render-thread call. Truly allocation-free: uses preallocated workspaces.
    Result process(const float *inData, int32_t inFrames);

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

    // Render-thread-owned streaming state (preallocated, never resized on audio thread).
    std::shared_ptr<const Config> active_;
    uint64_t activeGeneration_ = 0;
    double timePos_ = 0.0;
    std::vector<float> historyBuffer_;
    std::vector<float> workBuffer_;
    std::vector<float> outputBuffer_;

    // Deferred reset request (control thread sets, render thread executes).
    std::atomic<bool> resetRequested_{false};
};

} // namespace antigravity

