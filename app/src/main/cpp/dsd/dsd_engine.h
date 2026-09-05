#pragma once

#include <vector>
#include <cstdint>
#include <cstddef>

namespace antigravity {

enum class DsdMode {
    NATIVE_DSD = 0,
    DSD_OVER_PCM = 1, // DoP
    DSD_TO_PCM = 2    // High-Res Decimation
};

class DsdEngine {
public:
    DsdEngine();
    ~DsdEngine() = default;

    void configure(DsdMode mode, int32_t dsdRate = 2822400); // 2.8224 MHz (DSD64)

    // Converts raw DSD 1-bit stream bytes into DoP 24/32-bit PCM frames
    int32_t convertDsdToDoP(const uint8_t *dsdBytesL, const uint8_t *dsdBytesR, int32_t numBytes, std::vector<int32_t> &dopOutFrames);

    // Decimates raw DSD 1-bit stream bytes to 32-bit Float PCM (352.8 kHz for DSD64, 8:1 decimation)
    int32_t decimateDsdToPcm(const uint8_t *dsdBytesL, const uint8_t *dsdBytesR, int32_t numBytes, std::vector<float> &pcmOut);

    int32_t getDecimatedSampleRate() const;

    void reset();

private:
    DsdMode mode_ = DsdMode::DSD_TO_PCM;
    int32_t dsdRate_ = 2822400;
    uint8_t dopMarker_ = 0x05;

    // 64-tap Multistage decimation FIR state (sliding history of 1-bit polar samples)
    static constexpr size_t kFirTaps = 64;
    std::vector<float> firHistoryL_;
    std::vector<float> firHistoryR_;
    size_t historyIndex_ = 0;
};

} // namespace antigravity
