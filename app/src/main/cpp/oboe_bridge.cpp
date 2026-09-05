#include <jni.h>
#include <oboe/Oboe.h>
#include <android/log.h>
#include <vector>
#include <memory>
#include <mutex>
#include <atomic>
#include <unordered_map>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <cinttypes>
#include <time.h>
#include "dsp/audiophile_dsp.h"
#include "resampler/audiophile_resampler.h"
#include "dsd/dsd_engine.h"

#define LOG_TAG "OboeBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

// writeDirect / write return contract (mirrored by OboeAudioSink):
//   >0 : number of INPUT frames consumed from the caller buffer
//    0 : transient condition, retry the same data later
//   -1 : stream stale/closed or unrecoverable write error (trigger recovery)
//   -2 : unsupported PCM encoding (caller must fall back cleanly)
//   -3 : invalid arguments / bounds violation (never touch memory)
constexpr jint kErrStaleOrWrite = -1;
constexpr jint kErrUnsupportedEncoding = -2;
constexpr jint kErrBadArguments = -3;

constexpr int64_t kWriteTimeoutNs = 20 * 1000000LL;      // 20 ms bounded write
constexpr int32_t kMaxConsecutiveTimeouts = 50;
constexpr int32_t kMaxFramesPerCall = 1 << 20;            // sanity bound
constexpr int64_t kPositionQueryIntervalNs = 10 * 1000000LL; // 10 ms throttle

inline int64_t monotonicNowNs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

int32_t bytesPerSampleFor(jint pcmEncoding) {
    switch (pcmEncoding) {
        case 2: return 2;               // C.ENCODING_PCM_16BIT
        case 4: return 4;               // C.ENCODING_PCM_FLOAT
        case 21: return 3;              // C.ENCODING_PCM_24BIT (packed)
        case 22: return 4;              // C.ENCODING_PCM_32BIT
        case 3: return 1;               // C.ENCODING_PCM_8BIT (unsigned)
        default: return 0;              // unknown -> rejected by caller checks
    }
}

} // namespace

// ---------------------------------------------------------------------------
// Per-stream wrapper. Owned exclusively through shared_ptr inside the registry;
// JNI entry points always obtain a shared_ptr copy, so a concurrent close can
// never destroy a wrapper an in-flight call is still using.
//
// Lifetime notes:
//  * The Oboe error callback is registered via the shared_ptr overload, so the
//    stream itself keeps the wrapper alive until close() completes.
//  * oboe::AudioStream operations are safe against concurrent close() per the
//    Oboe API contract (they surface Result::ErrorClosed rather than crash).
//    The isActive flag provides a fast-path guard ahead of those calls.
// ---------------------------------------------------------------------------
class OboeStreamWrapper : public oboe::AudioStreamErrorCallback {
public:
    uint64_t generationId = 0;

    std::shared_ptr<oboe::AudioStream> stream = nullptr;
    antigravity::AudiophileDsp dsp;
    antigravity::AudiophileResampler resampler;
    antigravity::DsdEngine dsd;

    int32_t configuredSampleRate = 48000;
    int32_t configuredChannelCount = 2;
    std::atomic<int32_t> actualRate{48000};

    std::vector<float> pcmFloatScratchBuffer;

    // -------------------------------------------------------------------
    // OUTPUT STAGING RING (P0-4)
    //
    // Fixed-capacity, preallocated ring of interleaved float samples in the
    // RESAMPLED OUTPUT frame domain. The render thread is the ONLY mutator
    // of head_/tail_ (producer AND consumer); the control thread only sets
    // clearPending_, which the render thread honours at the next call
    // boundary. Therefore NO LOCK is ever held during the Oboe device write,
    // and no vector can resize underneath a live pointer.
    //
    // Frame domains (see docs/p0-playback-core-final-report.md):
    //   outputFramesProduced_ : total frames appended to ring or written
    //                           straight through (passthrough)
    //   atomicFramesWritten   : frames accepted by the hardware stream
    //   stagedPendingFrames() : frames in ring not yet handed to hardware
    // -------------------------------------------------------------------
    static constexpr size_t kRingCapacitySamples = 1u << 18;   // 1 MB floats (power of two: 262144 samples)
    static constexpr size_t kWriteScratchFrames = 4096;
    static constexpr size_t kRingMask = kRingCapacitySamples - 1;

    static constexpr size_t kMaxScratchSamples = 131072; // 131072 samples = 65536 stereo frames (512 KB floats)

    std::atomic<uint64_t> audioEpoch_{1};

    std::vector<float> ring_;                 // preallocated at open, never resized
    std::vector<float> writeScratch_;         // preallocated at open, never resized

    // Monotonic sample counters (render thread is the sole mutator;
    // atomics so control threads can read telemetry safely).
    std::atomic<uint64_t> ringHead_{0};
    std::atomic<uint64_t> ringTail_{0};
    std::atomic<bool> clearPending_{false};
    std::atomic<int64_t> outputFramesProduced_{0};

    inline uint64_t stagedSamples() const {
        return ringHead_.load(std::memory_order_relaxed) -
               ringTail_.load(std::memory_order_relaxed);
    }

    // Render thread only: honour a pending discard request from flush/reconfig.
    void applyClearIfRequested() {
        if (clearPending_.exchange(false, std::memory_order_acq_rel)) {
            ringHead_.store(0, std::memory_order_relaxed);
            ringTail_.store(0, std::memory_order_relaxed);
        }
    }

    // Render thread only: append interleaved samples to the ring.
    // Returns false when the ring would overflow (device stalled).
    bool appendToRing(const float *samples, size_t count) {
        if (!samples || count == 0) return true;
        if (stagedSamples() + count > kRingCapacitySamples) return false;
        uint64_t h = ringHead_.load(std::memory_order_relaxed);
        for (size_t i = 0; i < count; ++i) {
            ring_[static_cast<size_t>(h++) & kRingMask] = samples[i];
        }
        ringHead_.store(h, std::memory_order_release);
        return true;
    }

    // Render thread only: drain staged output to hardware WITHOUT holding any
    // lock (P0-4). Data is copied into the preallocated scratch first so no
    // live pointer ever depends on mutable container state.
    // Takes a shared_ptr snapshot so the stream object cannot be destroyed
    // Render thread only: drain staged output to hardware WITHOUT holding any
    // lock. Data is copied into the preallocated scratch first so no
    // live pointer ever depends on mutable container state.
    // Takes an oboe::AudioStream pointer guarded by caller's activeWriters_ reference.
    // Returns frames written this call; sets *stalled on fatal conditions.
    int32_t drainRingToStream(oboe::AudioStream *s, int32_t channels, bool *stalled) {
        if (!s) {
            *stalled = true;
            return 0;
        }
        applyClearIfRequested();
        int32_t writtenTotal = 0;
        while (true) {
            const uint64_t staged = stagedSamples();
            const uint64_t stagedFrames = staged / static_cast<uint64_t>(channels);
            if (stagedFrames == 0) break;
            const size_t chunkFrames =
                static_cast<size_t>(stagedFrames < kWriteScratchFrames
                                        ? stagedFrames : kWriteScratchFrames);
            const size_t samplesToCopy = chunkFrames * static_cast<size_t>(channels);
            uint64_t t = ringTail_.load(std::memory_order_relaxed);
            for (size_t i = 0; i < samplesToCopy; ++i) {
                writeScratch_[i] = ring_[static_cast<size_t>(t + i) & kRingMask];
            }

            const auto result = s->write(writeScratch_.data(),
                                         static_cast<int32_t>(chunkFrames),
                                         kWriteTimeoutNs);
            if (result.error() != oboe::Result::OK) {
                if (result.error() == oboe::Result::ErrorTimeout) {
                    const int32_t timeouts =
                        consecutiveTimeouts.fetch_add(1, std::memory_order_relaxed) + 1;
                    if (timeouts > kMaxConsecutiveTimeouts) *stalled = true;
                } else {
                    *stalled = true;   // hard write error
                }
                break;   // staged data remains; continue next call
            }
            consecutiveTimeouts.store(0, std::memory_order_relaxed);
            const int32_t written = result.value();
            ringTail_.store(t + static_cast<uint64_t>(written) * channels,
                            std::memory_order_relaxed);
            atomicFramesWritten.fetch_add(written, std::memory_order_relaxed);
            writtenTotal += written;
            if (written < chunkFrames) break;   // device backpressured
        }
        return writtenTotal;
    }

    std::mutex lifecycleMutex;
    std::atomic<oboe::AudioStream*> rawStream_{nullptr};
    std::atomic<int32_t> activeWriters_{0};

    // Formally race-safe stream pointer snapshot for control-plane callers:
    std::shared_ptr<oboe::AudioStream> getStreamSnapshot() {
        std::lock_guard<std::mutex> lock(lifecycleMutex);
        return stream;
    }

    std::atomic<bool> isActive{true};
    std::atomic<bool> isWriting_{false};
    std::atomic<int64_t> atomicFramesWritten{0};
    std::atomic<int64_t> atomicTimestampUs{0};
    std::atomic<int64_t> atomicPositionFrames{0};
    std::atomic<int32_t> consecutiveTimeouts{0};
    std::atomic<int64_t> hardwareFramesBaseline_{0};

    // Position query throttle state (guarded reads are cheap atomics).
    std::atomic<int64_t> lastQueryNs{0};
    std::atomic<int64_t> lastQueryFramePos{0};

    explicit OboeStreamWrapper(uint64_t gen) : generationId(gen) {
        pcmFloatScratchBuffer.assign(kMaxScratchSamples, 0.0f);
    }

    ~OboeStreamWrapper() override {
        closeInternal();
    }

    void closeInternal() {
        // Idempotent: a second close observes isActive == false early-outs.
        if (!isActive.exchange(false, std::memory_order_acq_rel)) {
            return;
        }
        // Quiescence wait: wait until any active audio writer has finished writing.
        // Hot-path writes are bounded by kWriteTimeoutNs (20ms), so this completes in at most a few ms.
        while (activeWriters_.load(std::memory_order_acquire) > 0) {
            struct timespec req = {0, 500000L}; // 0.5 ms
            nanosleep(&req, nullptr);
        }
        std::lock_guard<std::mutex> lock(lifecycleMutex);
        rawStream_.store(nullptr, std::memory_order_release);
        if (stream) {
            stream->stop();
            stream->close();
            stream = nullptr;
        }
        clearPending_.store(true, std::memory_order_release);
        audioEpoch_.fetch_add(1, std::memory_order_release);
        hardwareFramesBaseline_.store(0, std::memory_order_relaxed);
        atomicFramesWritten.store(0, std::memory_order_relaxed);
        outputFramesProduced_.store(0, std::memory_order_relaxed);
        atomicTimestampUs.store(0, std::memory_order_relaxed);
        atomicPositionFrames.store(0, std::memory_order_relaxed);
        consecutiveTimeouts.store(0, std::memory_order_relaxed);
        resampler.reset();
        dsp.reset();
    }

    void flush() {
        {
            std::lock_guard<std::mutex> lock(lifecycleMutex);
            if (stream && isActive.load(std::memory_order_acquire)) {
                auto state = stream->getState();
                // P0-6: Never issue flush() while state is Pausing or Started.
                // Wait/observe until state reaches a legal flush state.
                if (state == oboe::StreamState::Started || state == oboe::StreamState::Starting || state == oboe::StreamState::Pausing) {
                    stream->requestPause();
                    oboe::StreamState nextState = oboe::StreamState::Unknown;
                    stream->waitForStateChange(oboe::StreamState::Pausing, &nextState, 50 * 1000000LL);
                    state = stream->getState();
                }
                
                if (state == oboe::StreamState::Paused || state == oboe::StreamState::Open ||
                    state == oboe::StreamState::Stopped || state == oboe::StreamState::Flushed) {
                    stream->flush(50 * 1000000LL);
                } else {
                    LOGW("flush: skipped native flush due to stream state %s", oboe::convertToText(state));
                }

                // Query baseline hardware frame position at flush time (Rule 14)
                int64_t baseFrames = stream->getFramesRead();
                if (baseFrames <= 0) {
                    int64_t fp = 0, tns = 0;
                    if (stream->getTimestamp(CLOCK_MONOTONIC, &fp, &tns) == oboe::Result::OK && fp > 0) {
                        baseFrames = fp;
                    }
                }
                hardwareFramesBaseline_.store(std::max<int64_t>(0, baseFrames), std::memory_order_release);
            } else {
                hardwareFramesBaseline_.store(0, std::memory_order_release);
            }
        }
        atomicFramesWritten.store(0, std::memory_order_release);
        outputFramesProduced_.store(0, std::memory_order_release);
        atomicTimestampUs.store(0, std::memory_order_release);
        atomicPositionFrames.store(0, std::memory_order_release);
        consecutiveTimeouts.store(0, std::memory_order_release);
        lastQueryNs.store(0, std::memory_order_relaxed);
        lastQueryFramePos.store(0, std::memory_order_relaxed);
        // Render thread discards ring contents at its next call boundary
        // (P0-6.3: no pre-seek audio may survive a flush).
        clearPending_.store(true, std::memory_order_release);
        audioEpoch_.fetch_add(1, std::memory_order_release);
        resampler.reset();          // deferred internally to the render thread
        dsp.reset();                // clear biquad delay lines and ITD buffers (Rule 15)
    }

    void pause() {
        std::lock_guard<std::mutex> lock(lifecycleMutex);
        if (stream && isActive.load(std::memory_order_acquire) &&
            stream->getState() == oboe::StreamState::Started) {
            stream->requestPause();
        }
    }

    void start() {
        std::lock_guard<std::mutex> lock(lifecycleMutex);
        if (stream && isActive.load(std::memory_order_acquire)) {
            const auto state = stream->getState();
            if (state == oboe::StreamState::Paused ||
                state == oboe::StreamState::Open ||
                state == oboe::StreamState::Flushed) {
                stream->requestStart();
            }
        }
    }

    // Throttled position model: hardware timestamp query at most every
    // kPositionQueryIntervalNs, extrapolated in between. Monotonic and
    // clamped to the number of frames actually handed to the device.
    int64_t getPlaybackPositionFrames() {
        if (!isActive.load(std::memory_order_acquire)) {
            return atomicPositionFrames.load(std::memory_order_relaxed);
        }

        const int64_t nowNs = monotonicNowNs();
        const int64_t lastNs = lastQueryNs.load(std::memory_order_relaxed);
        const int64_t lastPos = lastQueryFramePos.load(std::memory_order_relaxed);

        if (lastNs != 0 && nowNs - lastNs < kPositionQueryIntervalNs) {
            const int32_t rate = actualRate.load(std::memory_order_relaxed);
            int64_t pos = lastPos;
            if (rate > 0) {
                pos += ((nowNs - lastNs) * static_cast<int64_t>(rate)) / 1000000000LL;
            }
            const int64_t ceiling = atomicFramesWritten.load(std::memory_order_relaxed);
            pos = std::max<int64_t>(0, std::min<int64_t>(pos, ceiling));
            atomicPositionFrames.store(pos, std::memory_order_relaxed);
            return pos;
        }

        std::lock_guard<std::mutex> lock(lifecycleMutex);
        oboe::AudioStream *s = stream.get();
        if (!s || !isActive.load(std::memory_order_acquire)) {
            return atomicPositionFrames.load(std::memory_order_relaxed);
        }

        int64_t framePosition = 0;
        int64_t timeNanoseconds = 0;
        const auto result = s->getTimestamp(CLOCK_MONOTONIC, &framePosition, &timeNanoseconds);
        int64_t pos = -1;
        if (result == oboe::Result::OK && timeNanoseconds > 0) {
            const int64_t deltaNs = nowNs - timeNanoseconds;
            if (deltaNs >= 0 && s->getSampleRate() > 0) {
                const int64_t extrapolated =
                    framePosition + (deltaNs * s->getSampleRate() / 1000000000LL);
                pos = extrapolated;
            } else {
                pos = framePosition;
            }
        } else {
            const auto framesRead = s->getFramesRead();
            if (framesRead > 0) pos = framesRead;
        }
        if (pos < 0) {
            const int32_t bufferSize = s->getBufferSizeInFrames();
            pos = atomicFramesWritten.load(std::memory_order_relaxed) -
                  static_cast<int64_t>(bufferSize);
        }

        const int64_t base = hardwareFramesBaseline_.load(std::memory_order_relaxed);
        if (pos >= base && base > 0) {
            pos -= base;
        }

        pos = std::max<int64_t>(0, std::min<int64_t>(
            pos, atomicFramesWritten.load(std::memory_order_relaxed)));
        atomicPositionFrames.store(pos, std::memory_order_relaxed);
        lastQueryNs.store(nowNs, std::memory_order_relaxed);
        lastQueryFramePos.store(pos, std::memory_order_relaxed);
        return pos;
    }

    int64_t getPlaybackTimestampUs() {
        if (!isActive.load(std::memory_order_acquire)) {
            return atomicTimestampUs.load(std::memory_order_relaxed);
        }
        const int64_t frames = getPlaybackPositionFrames();
        const int32_t rate = actualRate.load(std::memory_order_relaxed);
        if (rate <= 0) return atomicTimestampUs.load(std::memory_order_relaxed);
        const int64_t us = (frames * 1000000LL) / rate;
        atomicTimestampUs.store(us, std::memory_order_release);
        return us;
    }

    // Called by Oboe after it has already closed a broken stream. The builder
    // holds a shared_ptr to this wrapper, so we are guaranteed alive here.
    void onErrorAfterClose(oboe::AudioStream *audioStream, oboe::Result error) override {
        LOGW("Oboe stream disconnect/error: %s (gen=%llu)",
             oboe::convertToText(error),
             static_cast<unsigned long long>(generationId));
        isActive.store(false, std::memory_order_release);
        rawStream_.store(nullptr, std::memory_order_release);
        std::lock_guard<std::mutex> lock(lifecycleMutex);
        if (stream.get() == audioStream) {
            stream = nullptr;
        }
    }
};

// ---------------------------------------------------------------------------
// Lock-Free Generational Stream Registry (P0 & Phase 1)
//
// The realtime write path (writeDirect/write) uses an atomic generational slot
// table with atomic reader tracking (activeUsers). The audio thread NEVER
// acquires gRegistryMutex, NEVER allocates memory, and NEVER blocks.
//
// Unregister/close on the control thread removes the slot, waits for any in-flight
// reader/writer to finish (quiescence / grace period), and safely reclaims the stream.
// Handles are never reused, ensuring stale handles safely fail without UB.
// ---------------------------------------------------------------------------
namespace {
    constexpr size_t kMaxRegistrySlots = 32;

    struct StreamSlot {
        std::atomic<jlong> handle{0};
        std::atomic<uint64_t> generationId{0};
        std::atomic<OboeStreamWrapper*> wrapper{nullptr};
        std::atomic<uint32_t> activeUsers{0};
    };

    std::array<StreamSlot, kMaxRegistrySlots> gStreamSlots{};
    std::mutex gRegistryMutex;
    std::unordered_map<jlong, std::shared_ptr<OboeStreamWrapper>> gStreamRegistry;
    std::atomic<jlong> gNextHandle{1};
    std::atomic<uint64_t> gGenerationSequence{1};

    // RAII reader lease for hot-path lock-free stream access
    struct SlotLease {
        StreamSlot *slot = nullptr;
        OboeStreamWrapper *wrapper = nullptr;

        ~SlotLease() {
            if (slot) {
                slot->activeUsers.fetch_sub(1, std::memory_order_release);
            }
        }
    };

    inline OboeStreamWrapper* getStreamValidatedLockFree(jlong handle, jlong generation, SlotLease &lease) {
        if (handle <= 0) return nullptr;
        const size_t preferredSlot = static_cast<size_t>(handle) % kMaxRegistrySlots;
        for (size_t offset = 0; offset < kMaxRegistrySlots; ++offset) {
            const size_t idx = (preferredSlot + offset) % kMaxRegistrySlots;
            auto &slot = gStreamSlots[idx];
            if (slot.handle.load(std::memory_order_relaxed) == handle) {
                slot.activeUsers.fetch_add(1, std::memory_order_acquire);
                if (slot.handle.load(std::memory_order_acquire) == handle &&
                    (generation == 0 || slot.generationId.load(std::memory_order_acquire) == static_cast<uint64_t>(generation))) {
                    OboeStreamWrapper *w = slot.wrapper.load(std::memory_order_acquire);
                    if (w && w->isActive.load(std::memory_order_acquire)) {
                        lease.slot = &slot;
                        lease.wrapper = w;
                        return w;
                    }
                }
                slot.activeUsers.fetch_sub(1, std::memory_order_release);
                return nullptr;
            }
        }
        return nullptr;
    }

    std::shared_ptr<OboeStreamWrapper> getStream(jlong handle) {
        if (handle <= 0) return nullptr;
        std::lock_guard<std::mutex> lock(gRegistryMutex);
        auto it = gStreamRegistry.find(handle);
        if (it != gStreamRegistry.end()) return it->second;
        return nullptr;
    }


    jlong registerStream(const std::shared_ptr<OboeStreamWrapper> &wrapper) {
        const jlong handle = gNextHandle.fetch_add(1, std::memory_order_relaxed);
        std::lock_guard<std::mutex> lock(gRegistryMutex);
        gStreamRegistry[handle] = wrapper;

        // Publish to lock-free slot table
        const size_t preferredSlot = static_cast<size_t>(handle) % kMaxRegistrySlots;
        for (size_t offset = 0; offset < kMaxRegistrySlots; ++offset) {
            const size_t idx = (preferredSlot + offset) % kMaxRegistrySlots;
            auto &slot = gStreamSlots[idx];
            if (slot.handle.load(std::memory_order_relaxed) == 0 &&
                slot.activeUsers.load(std::memory_order_relaxed) == 0) {
                slot.generationId.store(wrapper->generationId, std::memory_order_relaxed);
                slot.wrapper.store(wrapper.get(), std::memory_order_relaxed);
                slot.handle.store(handle, std::memory_order_release);
                break;
            }
        }
        return handle;
    }

    void unregisterStream(jlong handle) {
        std::shared_ptr<OboeStreamWrapper> toClose;
        {
            std::lock_guard<std::mutex> lock(gRegistryMutex);
            // 1. Remove from lock-free slot table so no new writes can acquire lease
            for (auto &slot : gStreamSlots) {
                if (slot.handle.load(std::memory_order_relaxed) == handle) {
                    slot.handle.store(0, std::memory_order_release);
                    // Wait for active users in writeDirect to finish
                    while (slot.activeUsers.load(std::memory_order_acquire) > 0) {
                        struct timespec req = {0, 500000L}; // 0.5 ms
                        nanosleep(&req, nullptr);
                    }
                    slot.wrapper.store(nullptr, std::memory_order_release);
                    slot.generationId.store(0, std::memory_order_relaxed);
                    break;
                }
            }

            auto it = gStreamRegistry.find(handle);
            if (it != gStreamRegistry.end()) {
                toClose = it->second;
                gStreamRegistry.erase(it);
            }
        }
        // Wrapper destruction closes the stream; any in-flight writer will safely exit
        if (toClose) {
            toClose->closeInternal();
        }
    }
} // namespace

extern "C" {

// ---------------------------------------------------------------------------
// Stream lifecycle
// ---------------------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_openStream(
    JNIEnv *env, jobject thiz, jint sampleRate, jint channelCount,
    jboolean bitPerfectMode, jint deviceId) {

    // Rule 4: Step 1 - Validate input format
    if (sampleRate <= 0 || channelCount <= 0 || channelCount > 8) {
        return 0;
    }

    // Rule 4: Step 2 - Calculate maximum required memory & preallocate buffers
    const uint64_t gen = gGenerationSequence.fetch_add(1, std::memory_order_relaxed);
    auto wrapper = std::make_shared<OboeStreamWrapper>(gen);
    wrapper->configuredSampleRate = sampleRate;
    wrapper->configuredChannelCount = channelCount;

    // Fixed data-path buffers allocated ONCE before stream creation/start
    wrapper->ring_.assign(OboeStreamWrapper::kRingCapacitySamples, 0.0f);
    wrapper->writeScratch_.assign(
        OboeStreamWrapper::kWriteScratchFrames * static_cast<size_t>(std::max(1, channelCount)),
        0.0f);

    // Rule 4: Step 3 - Configure DSP & resampler initially
    wrapper->dsp.setSampleRate(static_cast<double>(sampleRate));
    wrapper->resampler.configure(sampleRate, sampleRate,
                                 channelCount, antigravity::ResampleQuality::SINC_FAST);

    // Rule 4: Step 4 - Create Oboe stream
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(bitPerfectMode ? oboe::SharingMode::Exclusive
                                           : oboe::SharingMode::Shared)
           ->setFormat(oboe::AudioFormat::Float)
           ->setSampleRate(sampleRate)
           ->setChannelCount(channelCount)
           ->setUsage(oboe::Usage::Media)
           ->setContentType(oboe::ContentType::Music);

    if (deviceId > 0) {
        builder.setDeviceId(deviceId);
    }

    // shared_ptr overload keeps callback alive for lifetime of stream
    builder.setErrorCallback(wrapper);

    oboe::Result result = builder.openStream(wrapper->stream);

    if (!bitPerfectMode && result != oboe::Result::OK) {
        LOGW("Requested open failed (%s); retrying in Shared mode.",
             oboe::convertToText(result));
        builder.setSharingMode(oboe::SharingMode::Shared);
        result = builder.openStream(wrapper->stream);
    }

    if (result != oboe::Result::OK || !wrapper->stream) {
        LOGE("Failed to open Oboe stream: %s", oboe::convertToText(result));
        return 0;
    }

    // Rule 4: Step 5 - Validate actual stream parameters & reconcile
    const int32_t actualChannels = wrapper->stream->getChannelCount();
    const int32_t actualStreamRate = wrapper->stream->getSampleRate();
    wrapper->actualRate.store(actualStreamRate, std::memory_order_release);
    wrapper->dsp.setSampleRate(static_cast<double>(actualStreamRate));

    if (actualChannels != channelCount) {
        wrapper->configuredChannelCount = actualChannels;
        wrapper->writeScratch_.assign(
            OboeStreamWrapper::kWriteScratchFrames * static_cast<size_t>(std::max(1, actualChannels)),
            0.0f);
    }
    wrapper->resampler.configure(sampleRate, actualStreamRate,
                                 actualChannels, antigravity::ResampleQuality::SINC_FAST);

    // Rule 4: Step 6 - Start stream only after render-path is 100% prepared
    result = wrapper->stream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start Oboe stream: %s", oboe::convertToText(result));
        wrapper->closeInternal();
        return 0;
    }
    wrapper->rawStream_.store(wrapper->stream.get(), std::memory_order_release);

    LOGI("Oboe stream open: api=%s sharing=%s gen=%llu dev=%d rate=%d->%d ch=%d",
         oboe::convertToText(wrapper->stream->getAudioApi()),
         (wrapper->stream->getSharingMode() == oboe::SharingMode::Exclusive)
             ? "EXCLUSIVE" : "SHARED",
         static_cast<unsigned long long>(gen),
         wrapper->stream->getDeviceId(), sampleRate,
         actualStreamRate, actualChannels);

    // Rule 4: Step 7 - Publish stream handle
    return registerStream(wrapper);
}

JNIEXPORT jlong JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_getStreamGeneration(
    JNIEnv *env, jobject thiz, jlong handle) {
    auto wrapper = getStream(handle);
    return wrapper ? static_cast<jlong>(wrapper->generationId) : 0L;
}

// ---------------------------------------------------------------------------
// Direct-buffer write path (hot path: zero allocation in steady state)
// ---------------------------------------------------------------------------

JNIEXPORT jint JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_writeDirect(
    JNIEnv *env, jobject thiz,
    jlong handle, jlong generation,
    jobject directBuffer, jint offsetBytes, jint numBytes,
    jint numFrames, jint pcmEncoding, jboolean isBitPerfect) {

    if (!directBuffer || numFrames <= 0 || numBytes <= 0 || offsetBytes < 0) {
        return kErrBadArguments;
    }
    if (numFrames > kMaxFramesPerCall) {
        return kErrBadArguments;
    }

    const int32_t bps = bytesPerSampleFor(pcmEncoding);
    if (bps == 0) {
        return kErrUnsupportedEncoding;
    }

    SlotLease lease;
    auto *wrapper = getStreamValidatedLockFree(handle, generation, lease);
    if (!wrapper || !wrapper->isActive.load(std::memory_order_acquire)) {
        return kErrStaleOrWrite;
    }

    // Rule 5: Enforce single-writer model per stream
    bool expectedWriter = false;
    if (!wrapper->isWriting_.compare_exchange_strong(expectedWriter, true, std::memory_order_acq_rel)) {
        return 0; // concurrent write detected; caller will retry
    }
    struct WriterGuard {
        std::atomic<bool> &flag;
        std::atomic<int32_t> &writers;
        ~WriterGuard() {
            flag.store(false, std::memory_order_release);
            writers.fetch_sub(1, std::memory_order_release);
        }
    } writerGuard{wrapper->isWriting_, wrapper->activeWriters_};
    wrapper->activeWriters_.fetch_add(1, std::memory_order_acquire);

    // Bounds validation against the real direct-buffer capacity.
    const jlong capacity = env->GetDirectBufferCapacity(directBuffer);
    if (capacity <= 0) {
        return kErrBadArguments;
    }
    // Overflow-safe range check.
    if (static_cast<jlong>(offsetBytes) > capacity ||
        static_cast<jlong>(numBytes) > capacity - static_cast<jlong>(offsetBytes)) {
        return kErrBadArguments;
    }

    const int32_t channelCount = wrapper->configuredChannelCount;
    if (channelCount <= 0 || channelCount > 8) {
        return kErrBadArguments;
    }
    // The sink derives numFrames from the buffer layout; require consistency
    // so we never read past the declared region nor ignore part of it.
    const int64_t expectedBytes =
        static_cast<int64_t>(numFrames) * channelCount * bps;
    if (static_cast<int64_t>(numBytes) < expectedBytes) {
        return kErrBadArguments;
    }

    const auto *rawAddress =
        static_cast<const uint8_t *>(env->GetDirectBufferAddress(directBuffer));
    if (!rawAddress) {
        return kErrBadArguments;
    }
    const uint8_t *srcBytes = rawAddress + offsetBytes;

    const uint64_t totalSamplesU =
        static_cast<uint64_t>(numFrames) * static_cast<uint64_t>(channelCount);
    if (totalSamplesU > static_cast<uint64_t>(kMaxFramesPerCall) * 8u) {
        return kErrBadArguments;
    }
    const int32_t totalSamples = static_cast<int32_t>(totalSamplesU);

    // Zero allocation in hot path (Rule 3): reject safely if exceeding preallocated capacity.
    if (static_cast<size_t>(totalSamples) > wrapper->pcmFloatScratchBuffer.size()) {
        return kErrBadArguments;
    }
    float *scratch = wrapper->pcmFloatScratchBuffer.data();

    switch (pcmEncoding) {
        case 4: {   // ENCODING_PCM_FLOAT
            std::memcpy(scratch, srcBytes, sizeof(float) * static_cast<size_t>(totalSamples));
            break;
        }
        case 2: {   // ENCODING_PCM_16BIT signed little-endian (alignment-safe)
            const uint8_t *b = srcBytes;
            for (int32_t i = 0; i < totalSamples; ++i) {
                int16_t s16;
                std::memcpy(&s16, b, sizeof(int16_t));
                b += sizeof(int16_t);
                scratch[i] = static_cast<float>(s16) * (1.0f / 32768.0f);
            }
            break;
        }
        case 3: {   // ENCODING_PCM_8BIT (unsigned, biased +128)
            for (int32_t i = 0; i < totalSamples; ++i) {
                scratch[i] = (static_cast<float>(srcBytes[i]) - 128.0f) * (1.0f / 128.0f);
            }
            break;
        }
        case 21: {  // ENCODING_PCM_24BIT packed little-endian
            const uint8_t *b = srcBytes;
            for (int32_t i = 0; i < totalSamples; ++i) {
                int32_t raw24 = static_cast<int32_t>(b[0]) |
                               (static_cast<int32_t>(b[1]) << 8) |
                               (static_cast<int32_t>(b[2]) << 16);
                b += 3;
                if (raw24 & 0x800000) raw24 |= ~0xFFFFFF;
                scratch[i] = static_cast<float>(raw24) * (1.0f / 8388608.0f);
            }
            break;
        }
        case 22: {  // ENCODING_PCM_32BIT signed little-endian (alignment-safe)
            const uint8_t *b = srcBytes;
            for (int32_t i = 0; i < totalSamples; ++i) {
                int32_t s32;
                std::memcpy(&s32, b, sizeof(int32_t));
                b += sizeof(int32_t);
                scratch[i] = static_cast<float>(
                    static_cast<double>(s32) * (1.0 / 2147483648.0));
            }
            break;
        }
        default:
            return kErrUnsupportedEncoding;
    }

    const bool bypassDsp = (isBitPerfect == JNI_TRUE);

    // Rule 15: Discard pre-seek staging before processing new audio
    wrapper->applyClearIfRequested();

    if (wrapper->resampler.isPassThrough()) {
        // ---- Pass-through path: exact partial-write semantics ----
        if (!bypassDsp) {
            wrapper->dsp.process(scratch, numFrames, channelCount);
        }

        oboe::AudioStream *activeStream = wrapper->rawStream_.load(std::memory_order_acquire);
        if (!activeStream || !wrapper->isActive.load(std::memory_order_acquire)) {
            return kErrStaleOrWrite;
        }

        const auto result =
            activeStream->write(scratch, numFrames, kWriteTimeoutNs);
        if (result.error() != oboe::Result::OK) {
            if (result.error() == oboe::Result::ErrorTimeout) {
                const int32_t timeouts =
                    wrapper->consecutiveTimeouts.fetch_add(1, std::memory_order_relaxed) + 1;
                if (timeouts > kMaxConsecutiveTimeouts) {
                    LOGW("Stream stalled (repeated timeouts); deactivating gen=%llu",
                         static_cast<unsigned long long>(wrapper->generationId));
                    wrapper->isActive.store(false, std::memory_order_release);
                    return kErrStaleOrWrite;
                }
                return 0;   // nothing consumed; caller retries same data
            }
            wrapper->isActive.store(false, std::memory_order_release);
            return kErrStaleOrWrite;
        }

        wrapper->consecutiveTimeouts.store(0, std::memory_order_relaxed);
        const int32_t written = result.value();
        if (written > 0) {
            wrapper->atomicFramesWritten.fetch_add(written, std::memory_order_relaxed);
            wrapper->outputFramesProduced_.fetch_add(written, std::memory_order_relaxed);
        }
        return std::min(written, numFrames);
    }

    // ---- Resampling path with explicit consumed-frames contract ----
    // Phase A: DSP + resampler produce into preallocated internal workspaces;
    //          output pointer r.outputData is appended to the fixed ring.
    // Phase B: drain ring -> hardware with NO LOCK held during Oboe I/O.
    if (!bypassDsp) {
        wrapper->dsp.process(scratch, numFrames, channelCount);
    }

    antigravity::AudiophileResampler::Result r =
        wrapper->resampler.process(scratch, numFrames);

    wrapper->applyClearIfRequested();
    const size_t producedSamples =
        static_cast<size_t>(r.outputFrames) * static_cast<size_t>(channelCount);
    if (producedSamples > 0 && r.outputData != nullptr) {
        if (!wrapper->appendToRing(r.outputData, producedSamples)) {
            LOGW("Resample ring overflow (device stalled); deactivating gen=%llu",
                 static_cast<unsigned long long>(wrapper->generationId));
            wrapper->isActive.store(false, std::memory_order_release);
            return kErrStaleOrWrite;
        }
        wrapper->outputFramesProduced_.fetch_add(r.outputFrames,
                                                 std::memory_order_relaxed);
    }

    oboe::AudioStream *activeStream = wrapper->rawStream_.load(std::memory_order_acquire);
    if (!activeStream || !wrapper->isActive.load(std::memory_order_acquire)) {
        return kErrStaleOrWrite;
    }

    // Phase B: device write with NO staging/lifecycle lock held.
    bool stalled = false;
    wrapper->drainRingToStream(activeStream, channelCount, &stalled);
    if (stalled && wrapper->consecutiveTimeouts.load(std::memory_order_relaxed)
            > kMaxConsecutiveTimeouts) {
        LOGW("Resampled stream stalled; deactivating gen=%llu",
             static_cast<unsigned long long>(wrapper->generationId));
        wrapper->isActive.store(false, std::memory_order_release);
        return kErrStaleOrWrite;
    }

    if (r.inputFramesConsumed <= 0) {
        // Defensive: resampler refused input; report nothing consumed.
        return 0;
    }
    return std::min(r.inputFramesConsumed, numFrames);
}

// ---------------------------------------------------------------------------
// Lifecycle controls
// ---------------------------------------------------------------------------

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_closeStream(
    JNIEnv *env, jobject thiz, jlong handle) {
    unregisterStream(handle);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_flushStream(
    JNIEnv *env, jobject thiz, jlong handle) {
    if (auto wrapper = getStream(handle)) wrapper->flush();
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_pauseStream(
    JNIEnv *env, jobject thiz, jlong handle) {
    if (auto wrapper = getStream(handle)) wrapper->pause();
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_startStream(
    JNIEnv *env, jobject thiz, jlong handle) {
    if (auto wrapper = getStream(handle)) wrapper->start();
}

JNIEXPORT jlong JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_getPlaybackPositionFrames(
    JNIEnv *env, jobject thiz, jlong handle) {
    auto wrapper = getStream(handle);
    return wrapper ? static_cast<jlong>(wrapper->getPlaybackPositionFrames()) : 0L;
}

JNIEXPORT jlong JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_getPlaybackTimestampUs(
    JNIEnv *env, jobject thiz, jlong handle) {
    auto wrapper = getStream(handle);
    return wrapper ? static_cast<jlong>(wrapper->getPlaybackTimestampUs()) : 0L;
}

JNIEXPORT jlong JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_getStreamEpoch(
    JNIEnv *env, jobject thiz, jlong handle) {
    auto wrapper = getStream(handle);
    return wrapper ? static_cast<jlong>(wrapper->audioEpoch_.load(std::memory_order_relaxed)) : 0L;
}

JNIEXPORT jint JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_getSampleRate(
    JNIEnv *env, jobject thiz, jlong handle) {
    auto wrapper = getStream(handle);
    if (!wrapper) return 0;
    auto s = wrapper->getStreamSnapshot();
    return s ? s->getSampleRate()
             : wrapper->actualRate.load(std::memory_order_relaxed);
}

JNIEXPORT jboolean JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_isExclusive(
    JNIEnv *env, jobject thiz, jlong handle) {
    auto wrapper = getStream(handle);
    if (!wrapper) return JNI_FALSE;
    auto s = wrapper->getStreamSnapshot();
    return (s && s->getSharingMode() == oboe::SharingMode::Exclusive)
               ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------------------
// DSP control surface - every entry validates the registry handle and is a
// safe no-op on stale/closed streams (never dereferences raw addresses).
// ---------------------------------------------------------------------------

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setDspEnabled(
    JNIEnv *env, jobject thiz, jlong handle, jboolean enabled) {
    if (auto w = getStream(handle)) w->dsp.setEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setBitPerfectBypass(
    JNIEnv *env, jobject thiz, jlong handle, jboolean bypass) {
    if (auto w = getStream(handle)) w->dsp.setBitPerfectBypass(bypass == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setPreAmpGainDb(
    JNIEnv *env, jobject thiz, jlong handle, jdouble gainDb) {
    if (auto w = getStream(handle)) w->dsp.setPreAmpGainDb(gainDb);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setBandGain(
    JNIEnv *env, jobject thiz, jlong handle, jint bandIndex, jdouble gainDb) {
    if (auto w = getStream(handle)) w->dsp.setBandGain(bandIndex, gainDb);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setBassBoostGainDb(
    JNIEnv *env, jobject thiz, jlong handle, jdouble gainDb) {
    if (auto w = getStream(handle)) w->dsp.setBassBoostGainDb(gainDb);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setTrebleGainDb(
    JNIEnv *env, jobject thiz, jlong handle, jdouble gainDb) {
    if (auto w = getStream(handle)) w->dsp.setTrebleGainDb(gainDb);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setHarmonicExciterLevel(
    JNIEnv *env, jobject thiz, jlong handle, jdouble level) {
    if (auto w = getStream(handle)) w->dsp.setHarmonicExciterLevel(level);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setClarityEnhancerGain(
    JNIEnv *env, jobject thiz, jlong handle, jdouble gainDb) {
    if (auto w = getStream(handle)) w->dsp.setClarityEnhancerGain(gainDb);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setStereoExpansionMultiplier(
    JNIEnv *env, jobject thiz, jlong handle, jdouble multiplier) {
    if (auto w = getStream(handle)) w->dsp.setStereoExpansionMultiplier(multiplier);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setDvcVolume(
    JNIEnv *env, jobject thiz, jlong handle, jdouble volume) {
    if (auto w = getStream(handle)) w->dsp.setDvcVolume(volume);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setDitherStrength(
    JNIEnv *env, jobject thiz, jlong handle, jdouble strength) {
    if (auto w = getStream(handle)) w->dsp.setDitherStrength(strength);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setOutputBitDepth(
    JNIEnv *env, jobject thiz, jlong handle, jint bitDepth) {
    if (auto w = getStream(handle)) w->dsp.setOutputBitDepth(bitDepth);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setWarmSaturationLevel(
    JNIEnv *env, jobject thiz, jlong handle, jdouble level) {
    if (auto w = getStream(handle)) w->dsp.setWarmSaturationLevel(level);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setTriodeWarmthLevel(
    JNIEnv *env, jobject thiz, jlong handle, jdouble level) {
    if (auto w = getStream(handle)) w->dsp.setTriodeWarmthLevel(level);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setPentodeTapeLevel(
    JNIEnv *env, jobject thiz, jlong handle, jdouble level) {
    if (auto w = getStream(handle)) w->dsp.setPentodeTapeLevel(level);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setCrossfeedLevel(
    JNIEnv *env, jobject thiz, jlong handle, jdouble level) {
    if (auto w = getStream(handle)) w->dsp.setCrossfeedLevel(level);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setLimiterEnabled(
    JNIEnv *env, jobject thiz, jlong handle, jboolean enabled) {
    if (auto w = getStream(handle)) w->dsp.setLimiterEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setLimiterThresholdDb(
    JNIEnv *env, jobject thiz, jlong handle, jdouble thresholdDb) {
    if (auto w = getStream(handle)) w->dsp.setLimiterThresholdDb(thresholdDb);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setSubBassMonoEnabled(
    JNIEnv *env, jobject thiz, jlong handle, jboolean enabled) {
    if (auto w = getStream(handle)) w->dsp.setSubBassMonoEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setChannelBalance(
    JNIEnv *env, jobject thiz, jlong handle, jdouble balance) {
    if (auto w = getStream(handle)) w->dsp.setChannelBalance(balance);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setInvertPhase(
    JNIEnv *env, jobject thiz, jlong handle, jboolean invert) {
    if (auto w = getStream(handle)) w->dsp.setInvertPhase(invert == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setAirPresenceGainDb(
    JNIEnv *env, jobject thiz, jlong handle, jdouble gainDb) {
    if (auto w = getStream(handle)) w->dsp.setAirPresenceGainDb(gainDb);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_clearPeqBands(
    JNIEnv *env, jobject thiz, jlong handle) {
    if (auto w = getStream(handle)) w->dsp.clearPeqBands();
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_addPeqBand(
    JNIEnv *env, jobject thiz, jlong handle, jint type, jdouble frequency,
    jdouble q, jdouble gainDb) {
    if (auto w = getStream(handle)) {
        w->dsp.addPeqBand(static_cast<antigravity::FilterType>(type),
                          frequency, q, gainDb);
    }
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_updatePeqBand(
    JNIEnv *env, jobject thiz, jlong handle, jint index, jint type,
    jdouble frequency, jdouble q, jdouble gainDb) {
    if (auto w = getStream(handle)) {
        if (index >= 0) {
            w->dsp.updatePeqBand(static_cast<size_t>(index),
                                 static_cast<antigravity::FilterType>(type),
                                 frequency, q, gainDb);
        }
    }
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setResamplerQuality(
    JNIEnv *env, jobject thiz, jlong handle, jint quality) {
    auto w = getStream(handle);
    if (!w) return;
    std::lock_guard<std::mutex> lock(w->lifecycleMutex);
    const int32_t outRate =
        w->stream ? w->stream->getSampleRate() : w->actualRate.load(std::memory_order_relaxed);
    w->resampler.configure(w->configuredSampleRate, outRate,
                           w->configuredChannelCount,
                           static_cast<antigravity::ResampleQuality>(quality));
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setHrtfSpatialEnabled(
    JNIEnv *env, jobject thiz, jlong handle, jboolean enabled) {
    if (auto w = getStream(handle)) w->dsp.setHrtfSpatialEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setHrtfRoomSize(
    JNIEnv *env, jobject thiz, jlong handle, jdouble roomSize) {
    if (auto w = getStream(handle)) w->dsp.setHrtfRoomSize(roomSize);
}

// Batch DSP parameters: updates all feature flags and parameters in one seqlock cycle (Rule 8)
JNIEXPORT void JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_setDspParametersBatch(
    JNIEnv *env, jobject thiz, jlong handle,
    jboolean enabled, jboolean bitPerfectBypass,
    jbooleanArray activeFlags, jdoubleArray doubleParams,
    jint outputBitDepth, jboolean invertPhase) {
    auto w = getStream(handle);
    if (!w) return;

    antigravity::DspParams p;
    p.enabled = (enabled == JNI_TRUE);
    p.bitPerfectBypass = (bitPerfectBypass == JNI_TRUE);
    p.outputBitDepth = outputBitDepth;
    p.invertPhase = (invertPhase == JNI_TRUE);

    if (activeFlags && env->GetArrayLength(activeFlags) >= 17) {
        jboolean flags[17];
        env->GetBooleanArrayRegion(activeFlags, 0, 17, flags);
        p.eqActive = (flags[0] == JNI_TRUE);
        p.autoEqActive = (flags[1] == JNI_TRUE);
        p.peqActive = (flags[2] == JNI_TRUE);
        p.limiterActive = (flags[3] == JNI_TRUE);
        p.ditherActive = (flags[4] == JNI_TRUE);
        p.replayGainActive = (flags[5] == JNI_TRUE);
        p.crossfeedActive = (flags[6] == JNI_TRUE);
        p.balanceActive = (flags[7] == JNI_TRUE);
        p.spatialActive = (flags[8] == JNI_TRUE);
        p.bassBoostActive = (flags[9] == JNI_TRUE);
        p.trebleActive = (flags[10] == JNI_TRUE);
        p.clarityActive = (flags[11] == JNI_TRUE);
        p.harmonicExciterActive = (flags[12] == JNI_TRUE);
        p.saturationActive = (flags[13] == JNI_TRUE);
        p.stereoExpansionActive = (flags[14] == JNI_TRUE);
        p.subBassMonoActive = (flags[15] == JNI_TRUE);
        p.channelTransformActive = (flags[16] == JNI_TRUE);
    }

    if (doubleParams && env->GetArrayLength(doubleParams) >= 27) {
        jdouble vals[27];
        env->GetDoubleArrayRegion(doubleParams, 0, 27, vals);
        p.preAmpGainDb = vals[0];
        p.bassBoostGainDb = vals[1];
        p.trebleGainDb = vals[2];
        p.harmonicExciterLevel = vals[3];
        p.clarityEnhancerGainDb = vals[4];
        p.stereoExpansionMultiplier = vals[5];
        p.dvcVolume = vals[6];
        p.replayGainMultiplier = vals[7];
        p.ditherStrength = vals[8];
        p.warmSaturationLevel = vals[9];
        p.triodeWarmthLevel = vals[10];
        p.pentodeTapeLevel = vals[11];
        p.crossfeedLevel = vals[12];
        p.limiterThresholdDb = vals[13];
        p.channelBalance = vals[14];
        p.airPresenceGainDb = vals[15];
        p.hrtfRoomSize = vals[16];
        for (int i = 0; i < 10; ++i) {
            p.bandGainsDb[i] = vals[17 + i];
        }
    }

    w->dsp.setDspParametersBatch(p);
}

// ---------------- Telemetry ----------------

JNIEXPORT jdouble JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_getPeakL(
    JNIEnv *env, jobject thiz, jlong handle) {
    auto w = getStream(handle);
    return w ? w->dsp.getPeakL() : 0.0;
}

JNIEXPORT jdouble JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_getPeakR(
    JNIEnv *env, jobject thiz, jlong handle) {
    auto w = getStream(handle);
    return w ? w->dsp.getPeakR() : 0.0;
}

JNIEXPORT jfloat JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_getPhaseCorrelation(
    JNIEnv *env, jobject thiz, jlong handle) {
    auto w = getStream(handle);
    return w ? w->dsp.getPhaseCorrelation() : 1.0f;
}

// Zero-allocation scalar telemetry getters for the real-time audio hot path
JNIEXPORT jlong JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_getOutputFramesProduced(
    JNIEnv *env, jobject thiz, jlong handle) {
    auto w = getStream(handle);
    return w ? static_cast<jlong>(w->outputFramesProduced_.load(std::memory_order_relaxed)) : 0L;
}

JNIEXPORT jlong JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_getHardwareFramesWritten(
    JNIEnv *env, jobject thiz, jlong handle) {
    auto w = getStream(handle);
    return w ? static_cast<jlong>(w->atomicFramesWritten.load(std::memory_order_relaxed)) : 0L;
}

JNIEXPORT jlong JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_getStagedPendingFrames(
    JNIEnv *env, jobject thiz, jlong handle) {
    auto w = getStream(handle);
    if (!w) return 0L;
    const uint64_t stagedFrames =
        w->stagedSamples() /
        static_cast<uint64_t>(std::max(1, w->configuredChannelCount));
    return static_cast<jlong>(stagedFrames);
}


JNIEXPORT jobject JNICALL
Java_com_tensorix_antigravityplayer_audio_OboeBridge_getNativeStreamInfo(
    JNIEnv *env, jobject thiz, jlong handle) {
    auto wrapper = getStream(handle);
    if (!wrapper) return nullptr;

    jclass infoClass = env->FindClass(
        "com/tensorix/antigravityplayer/audio/OboeBridge$NativeStreamInfo");
    if (env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }
    if (!infoClass) return nullptr;

    // 20-field constructor with structured IDs (Rule 11)
    jmethodID constructor = env->GetMethodID(
        infoClass, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IILjava/lang/String;"
        "IILjava/lang/String;ZJIIIIIIIIJ)V");
    if (env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }
    if (!constructor) return nullptr;

    jobject infoObject = nullptr;
    {
        std::lock_guard<std::mutex> lock(wrapper->lifecycleMutex);
        oboe::AudioStream *s = wrapper->stream.get();
        if (!s || !wrapper->isActive.load(std::memory_order_acquire)) return nullptr;

        jstring api = env->NewStringUTF(oboe::convertToText(s->getAudioApi()));
        jstring sharing = env->NewStringUTF(
            s->getSharingMode() == oboe::SharingMode::Exclusive ? "EXCLUSIVE" : "SHARED");
        jstring performance = env->NewStringUTF(oboe::convertToText(s->getPerformanceMode()));
        jstring formatStr = env->NewStringUTF(oboe::convertToText(s->getFormat()));
        jstring stateStr = env->NewStringUTF(oboe::convertToText(s->getState()));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return nullptr;
        }

        const bool started = (s->getState() == oboe::StreamState::Started);
        int32_t xruns = 0;
        if (auto xrunResult = s->getXRunCount()) xruns = xrunResult.value();

        const auto fmt = s->getFormat();
        int32_t bitDepth = 0;
        int32_t formatId = static_cast<int32_t>(fmt);
        if (fmt == oboe::AudioFormat::I16) bitDepth = 16;
        else if (fmt == oboe::AudioFormat::Float) bitDepth = 32;
        else if (fmt == oboe::AudioFormat::I24) bitDepth = 24;
        else if (fmt == oboe::AudioFormat::I32) bitDepth = 32;

        const int32_t apiId = static_cast<int32_t>(s->getAudioApi());
        const int32_t sharingModeId = (s->getSharingMode() == oboe::SharingMode::Exclusive) ? 1 : 0;
        const int32_t performanceModeId = static_cast<int32_t>(s->getPerformanceMode());
        const int32_t stateId = static_cast<int32_t>(s->getState());
        const int32_t channelMask = static_cast<int32_t>(s->getChannelMask());
        const jlong streamGen = static_cast<jlong>(wrapper->generationId);

        infoObject = env->NewObject(infoClass, constructor,
                                    api, sharing, performance,
                                    static_cast<jint>(s->getSampleRate()),
                                    static_cast<jint>(s->getChannelCount()),
                                    formatStr,
                                    static_cast<jint>(s->getBufferSizeInFrames()),
                                    static_cast<jint>(s->getDeviceId()),
                                    stateStr,
                                    started ? JNI_TRUE : JNI_FALSE,
                                    static_cast<jlong>(s->getFramesWritten()),
                                    static_cast<jint>(xruns),
                                    static_cast<jint>(apiId),
                                    static_cast<jint>(sharingModeId),
                                    static_cast<jint>(performanceModeId),
                                    static_cast<jint>(formatId),
                                    static_cast<jint>(bitDepth),
                                    static_cast<jint>(channelMask),
                                    static_cast<jint>(stateId),
                                    streamGen);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            infoObject = nullptr;
        }

        env->DeleteLocalRef(api);
        env->DeleteLocalRef(sharing);
        env->DeleteLocalRef(performance);
        env->DeleteLocalRef(formatStr);
        env->DeleteLocalRef(stateStr);
    }
    env->DeleteLocalRef(infoClass);
    return infoObject;
}

} // extern "C"
