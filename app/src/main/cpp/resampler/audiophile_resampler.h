#pragma once

#include <vector>
#include <array>
#include <cstdint>
#include <cmath>
#include <atomic>

namespace antigravity {

enum class ResampleQuality {
    HERMITE_FAST = 0,
    SINC_FAST = 1,
    SINC_BEST = 2
};

class AudiophileResampler {
public:
    static constexpr int32_t MAX_CHANNELS = 8;
    static constexpr int32_t MAX_INPUT_FRAMES = 8192;
    static constexpr int32_t MAX_HISTORY_FRAMES = 128;
    // Support upsampling up to 384 kHz (8.7x). Sized for up to 8 channels.
    static constexpr int32_t MAX_OUTPUT_FRAMES = 73728;
    static constexpr int32_t NUM_PHASES = 64;
    static constexpr int32_t MAX_TAPS = 64;

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

    // Non-copyable, non-movable to guarantee stability of internal fixed workspaces.
    AudiophileResampler(const AudiophileResampler &) = delete;
    AudiophileResampler &operator=(const AudiophileResampler &) = delete;

    // Control-thread only. Builds a complete immutable configuration and
    // publishes it atomically via triple-buffer exchange.
    // Streaming state is migrated by the render thread at the next process() call (never torn mid-block).
    void configure(int32_t inSampleRate, int32_t outSampleRate, int32_t channelCount,
                   ResampleQuality quality = ResampleQuality::SINC_FAST);

    // Render-thread call. Truly allocation-free and lock-free: uses preallocated workspaces.
    Result process(const float *inData, int32_t inFrames);

    // Safe to call from any thread: defers the actual state clear to the
    // render thread so it can never race an in-flight process() call.
    void reset();

    bool isPassThrough() const;
    int32_t getInputSampleRate() const { return pendingInRate_.load(std::memory_order_relaxed); }
    int32_t getOutputSampleRate() const { return pendingOutRate_.load(std::memory_order_relaxed); }

private:
    struct Config {
        int32_t channelCount = 2;
        ResampleQuality quality = ResampleQuality::SINC_FAST;
        double ratio = 1.0;          // inRate / outRate
        int taps = 16;
        int halfTaps = 8;
        bool passThrough = true;
        uint64_t generation = 0;     // bumped on every configure()
        // Flat polyphase table: NUM_PHASES x MAX_TAPS, zero dynamic allocations
        std::array<double, NUM_PHASES * MAX_TAPS> polyphaseTable{};
    };

    static void populateConfig(Config &cfg,
        int32_t inSampleRate, int32_t outSampleRate, int32_t channelCount,
        ResampleQuality quality, uint64_t generation);

    static double sinc(double x);
    static double blackmanNutall(double x);

    void migrateTo(const Config &cfg);

    // Preallocated triple-buffered pool for Config (zero shared_ptr, zero malloc/free on render thread).
    std::array<Config, 3> configPool_{};
    std::atomic<int> cleanSlot_{0};
    int writeSlot_{2};
    int renderSlot_{1};
    std::atomic<uint64_t> publishedGen_{1};
    uint64_t activeGeneration_{0};

    std::atomic<bool> isPassThrough_{true};
    std::atomic<int32_t> pendingInRate_{48000};
    std::atomic<int32_t> pendingOutRate_{48000};

    // Render-thread-owned streaming state (preallocated, never resized on audio thread).
    double timePos_ = 0.0;
    std::vector<float> historyBuffer_;
    std::vector<float> workBuffer_;
    std::vector<float> outputBuffer_;

    // Deferred reset request (control thread sets, render thread executes).
    std::atomic<bool> resetRequested_{false};
};

} // namespace antigravity


