#include "dsd_engine.h"
#include <algorithm>
#include <cmath>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

namespace antigravity {

static const std::vector<float>& getFirCoeffs() {
    static const std::vector<float> coeffs = []() {
        std::vector<float> c(64);
        double sum = 0.0;
        const double wc = M_PI / 16.0;
        for (int i = 0; i < 64; ++i) {
            double n = i - 31.5;
            double sinc = std::sin(wc * n) / (M_PI * n);
            double window = 0.54 - 0.46 * std::cos(2.0 * M_PI * i / 63.0);
            c[i] = static_cast<float>(sinc * window);
            sum += c[i];
        }
        for (int i = 0; i < 64; ++i) {
            c[i] /= static_cast<float>(sum);
        }
        return c;
    }();
    return coeffs;
}

DsdEngine::DsdEngine() {
    firHistoryL_.resize(kFirTaps, 0.0f);
    firHistoryR_.resize(kFirTaps, 0.0f);
    reset();
}

void DsdEngine::reset() {
    dopMarker_ = 0x05;
    std::fill(firHistoryL_.begin(), firHistoryL_.end(), 0.0f);
    std::fill(firHistoryR_.begin(), firHistoryR_.end(), 0.0f);
    historyIndex_ = 0;
}

void DsdEngine::configure(DsdMode mode, int32_t dsdRate) {
    mode_ = mode;
    dsdRate_ = dsdRate > 0 ? dsdRate : 2822400;
    reset();
}

int32_t DsdEngine::getDecimatedSampleRate() const {
    // 8:1 decimation of DSD64 (2,822,400 Hz) produces 352,800 Hz PCM.
    // Higher DSD rates scale proportionally.
    return (dsdRate_ > 0) ? (dsdRate_ / 8) : 352800;
}

int32_t DsdEngine::convertDsdToDoP(
    const uint8_t *dsdBytesL,
    const uint8_t *dsdBytesR,
    int32_t numBytes,
    std::vector<int32_t> &dopOutFrames) {
    if (!dsdBytesL || !dsdBytesR || numBytes < 2) return 0;

    // DoP Standard: 16-bit DSD payload wrapped in 24-bit PCM word (shifted to MSB in 32-bit int)
    // Frame format: [Marker (8-bit) | DSD payload (16-bit) | 0x00 (8-bit)]
    // Marker alternates between 0x05 and 0xFA for each sample frame (both channels share frame marker)
    // Handle odd numBytes defensively by dropping the trailing byte
    if (numBytes % 2 != 0) {
        numBytes -= 1;
    }

    int32_t words16 = numBytes / 2;
    dopOutFrames.clear();
    dopOutFrames.reserve(static_cast<size_t>(words16 * 2)); // Stereo

    for (int32_t i = 0; i < words16; ++i) {
        uint16_t sampleL = (static_cast<uint16_t>(dsdBytesL[i * 2]) << 8) | dsdBytesL[i * 2 + 1];
        uint16_t sampleR = (static_cast<uint16_t>(dsdBytesR[i * 2]) << 8) | dsdBytesR[i * 2 + 1];

        uint8_t marker = dopMarker_;
        int32_t dopWordL = (static_cast<int32_t>(marker) << 24) | (static_cast<int32_t>(sampleL) << 8);
        int32_t dopWordR = (static_cast<int32_t>(marker) << 24) | (static_cast<int32_t>(sampleR) << 8);

        dopOutFrames.push_back(dopWordL);
        dopOutFrames.push_back(dopWordR);

        // Toggle marker once per stereo frame (after writing both L and R)
        dopMarker_ = (dopMarker_ == 0x05) ? 0xFA : 0x05;
    }

    return words16; // Number of stereo frames produced
}

int32_t DsdEngine::decimateDsdToPcm(
    const uint8_t *dsdBytesL,
    const uint8_t *dsdBytesR,
    int32_t numBytes,
    std::vector<float> &pcmOut) {
    if (!dsdBytesL || !dsdBytesR || numBytes <= 0) return 0;

    const auto &coeffs = getFirCoeffs();
    pcmOut.resize(numBytes * 2); // Stereo

    for (int32_t i = 0; i < numBytes; ++i) {
        uint8_t byteL = dsdBytesL[i];
        uint8_t byteR = dsdBytesR[i];

        // Shift 8 bits (MSB-first) into history buffers
        for (int bit = 7; bit >= 0; --bit) {
            float bitValL = ((byteL >> bit) & 1) ? 1.0f : -1.0f;
            float bitValR = ((byteR >> bit) & 1) ? 1.0f : -1.0f;

            firHistoryL_[historyIndex_] = bitValL;
            firHistoryR_[historyIndex_] = bitValR;
            historyIndex_ = (historyIndex_ + 1) % kFirTaps;
        }

        // Convolve 64 taps with history buffer
        float accL = 0.0f;
        float accR = 0.0f;
        size_t idx = historyIndex_;
        for (size_t k = 0; k < kFirTaps; ++k) {
            accL += firHistoryL_[idx] * coeffs[k];
            accR += firHistoryR_[idx] * coeffs[k];
            idx = (idx + 1) % kFirTaps;
        }

        pcmOut[i * 2] = std::clamp(accL, -1.0f, 1.0f);
        pcmOut[i * 2 + 1] = std::clamp(accR, -1.0f, 1.0f);
    }

    return numBytes;
}

} // namespace antigravity
