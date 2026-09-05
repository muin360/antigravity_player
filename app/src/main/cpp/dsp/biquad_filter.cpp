#include "biquad_filter.h"
#include <algorithm>
#include <cmath>

namespace antigravity {

static constexpr double M_PI_VAL = 3.14159265358979323846;

static inline BiquadCoefficients safeNormalize(double b0, double b1, double b2, double a0, double a1, double a2) {
    if (!std::isfinite(a0) || std::abs(a0) < 1.0e-15) {
        return {1.0, 0.0, 0.0, 0.0, 0.0};
    }
    const double invA0 = 1.0 / a0;
    const double nb0 = b0 * invA0;
    const double nb1 = b1 * invA0;
    const double nb2 = b2 * invA0;
    const double na1 = a1 * invA0;
    const double na2 = a2 * invA0;

    if (!std::isfinite(nb0) || !std::isfinite(nb1) || !std::isfinite(nb2) ||
        !std::isfinite(na1) || !std::isfinite(na2)) {
        return {1.0, 0.0, 0.0, 0.0, 0.0};
    }
    return {nb0, nb1, nb2, na1, na2};
}

BiquadFilter::BiquadFilter() {
    reset();
}

void BiquadFilter::reset() {
    z1_ = 0.0;
    z2_ = 0.0;
    b0_ = 1.0;
    b1_ = 0.0;
    b2_ = 0.0;
    a1_ = 0.0;
    a2_ = 0.0;
}

BiquadCoefficients BiquadFilter::computePeakingEq(double frequency, double q, double gainDb, double sampleRate) {
    if (!std::isfinite(sampleRate) || sampleRate <= 0.0) return {1.0, 0.0, 0.0, 0.0, 0.0};
    frequency = std::clamp(frequency, 1.0, sampleRate * 0.499);
    q = std::clamp(q, 0.01, 100.0);
    gainDb = std::clamp(gainDb, -60.0, 60.0);

    const double A = std::pow(10.0, gainDb / 40.0);
    const double w0 = 2.0 * M_PI_VAL * frequency / sampleRate;
    const double alpha = std::sin(w0) / (2.0 * q);
    const double cosW0 = std::cos(w0);

    const double b0 = 1.0 + alpha * A;
    const double b1 = -2.0 * cosW0;
    const double b2 = 1.0 - alpha * A;
    const double a0 = 1.0 + alpha / A;
    const double a1 = -2.0 * cosW0;
    const double a2 = 1.0 - alpha / A;

    return safeNormalize(b0, b1, b2, a0, a1, a2);
}

BiquadCoefficients BiquadFilter::computeLowShelf(double frequency, double q, double gainDb, double sampleRate) {
    if (!std::isfinite(sampleRate) || sampleRate <= 0.0) return {1.0, 0.0, 0.0, 0.0, 0.0};
    frequency = std::clamp(frequency, 1.0, sampleRate * 0.499);
    q = std::clamp(q, 0.01, 100.0);
    gainDb = std::clamp(gainDb, -60.0, 60.0);

    const double A = std::pow(10.0, gainDb / 40.0);
    const double w0 = 2.0 * M_PI_VAL * frequency / sampleRate;
    const double cosW0 = std::cos(w0);
    const double sinW0 = std::sin(w0);
    const double alpha = sinW0 / (2.0 * q);
    const double sqrtA = std::sqrt(A);

    const double b0 = A * ((A + 1.0) - (A - 1.0) * cosW0 + 2.0 * sqrtA * alpha);
    const double b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cosW0);
    const double b2 = A * ((A + 1.0) - (A - 1.0) * cosW0 - 2.0 * sqrtA * alpha);
    const double a0 = (A + 1.0) + (A - 1.0) * cosW0 + 2.0 * sqrtA * alpha;
    const double a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cosW0);
    const double a2 = (A + 1.0) + (A - 1.0) * cosW0 - 2.0 * sqrtA * alpha;

    return safeNormalize(b0, b1, b2, a0, a1, a2);
}

BiquadCoefficients BiquadFilter::computeHighShelf(double frequency, double q, double gainDb, double sampleRate) {
    if (!std::isfinite(sampleRate) || sampleRate <= 0.0) return {1.0, 0.0, 0.0, 0.0, 0.0};
    frequency = std::clamp(frequency, 1.0, sampleRate * 0.499);
    q = std::clamp(q, 0.01, 100.0);
    gainDb = std::clamp(gainDb, -60.0, 60.0);

    const double A = std::pow(10.0, gainDb / 40.0);
    const double w0 = 2.0 * M_PI_VAL * frequency / sampleRate;
    const double cosW0 = std::cos(w0);
    const double sinW0 = std::sin(w0);
    const double alpha = sinW0 / (2.0 * q);
    const double sqrtA = std::sqrt(A);

    const double b0 = A * ((A + 1.0) + (A - 1.0) * cosW0 + 2.0 * sqrtA * alpha);
    const double b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cosW0);
    const double b2 = A * ((A + 1.0) + (A - 1.0) * cosW0 - 2.0 * sqrtA * alpha);
    const double a0 = (A + 1.0) - (A - 1.0) * cosW0 + 2.0 * sqrtA * alpha;
    const double a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cosW0);
    const double a2 = (A + 1.0) - (A - 1.0) * cosW0 - 2.0 * sqrtA * alpha;

    return safeNormalize(b0, b1, b2, a0, a1, a2);
}

BiquadCoefficients BiquadFilter::computeLowPass(double frequency, double q, double sampleRate) {
    if (!std::isfinite(sampleRate) || sampleRate <= 0.0) return {1.0, 0.0, 0.0, 0.0, 0.0};
    frequency = std::clamp(frequency, 1.0, sampleRate * 0.499);
    q = std::clamp(q, 0.01, 100.0);

    const double w0 = 2.0 * M_PI_VAL * frequency / sampleRate;
    const double cosW0 = std::cos(w0);
    const double alpha = std::sin(w0) / (2.0 * q);

    const double b0 = (1.0 - cosW0) * 0.5;
    const double b1 = 1.0 - cosW0;
    const double b2 = (1.0 - cosW0) * 0.5;
    const double a0 = 1.0 + alpha;
    const double a1 = -2.0 * cosW0;
    const double a2 = 1.0 - alpha;

    return safeNormalize(b0, b1, b2, a0, a1, a2);
}

BiquadCoefficients BiquadFilter::computeHighPass(double frequency, double q, double sampleRate) {
    if (!std::isfinite(sampleRate) || sampleRate <= 0.0) return {1.0, 0.0, 0.0, 0.0, 0.0};
    frequency = std::clamp(frequency, 1.0, sampleRate * 0.499);
    q = std::clamp(q, 0.01, 100.0);

    const double w0 = 2.0 * M_PI_VAL * frequency / sampleRate;
    const double cosW0 = std::cos(w0);
    const double alpha = std::sin(w0) / (2.0 * q);

    const double b0 = (1.0 + cosW0) * 0.5;
    const double b1 = -(1.0 + cosW0);
    const double b2 = (1.0 + cosW0) * 0.5;
    const double a0 = 1.0 + alpha;
    const double a1 = -2.0 * cosW0;
    const double a2 = 1.0 - alpha;

    return safeNormalize(b0, b1, b2, a0, a1, a2);
}

BiquadCoefficients BiquadFilter::computeBandPass(double frequency, double q, double sampleRate) {
    if (!std::isfinite(sampleRate) || sampleRate <= 0.0) return {1.0, 0.0, 0.0, 0.0, 0.0};
    frequency = std::clamp(frequency, 1.0, sampleRate * 0.499);
    q = std::clamp(q, 0.01, 100.0);

    const double w0 = 2.0 * M_PI_VAL * frequency / sampleRate;
    const double cosW0 = std::cos(w0);
    const double alpha = std::sin(w0) / (2.0 * q);

    const double b0 = alpha;
    const double b1 = 0.0;
    const double b2 = -alpha;
    const double a0 = 1.0 + alpha;
    const double a1 = -2.0 * cosW0;
    const double a2 = 1.0 - alpha;

    return safeNormalize(b0, b1, b2, a0, a1, a2);
}

BiquadCoefficients BiquadFilter::computeNotch(double frequency, double q, double sampleRate) {
    if (!std::isfinite(sampleRate) || sampleRate <= 0.0) return {1.0, 0.0, 0.0, 0.0, 0.0};
    frequency = std::clamp(frequency, 1.0, sampleRate * 0.499);
    q = std::clamp(q, 0.01, 100.0);

    const double w0 = 2.0 * M_PI_VAL * frequency / sampleRate;
    const double cosW0 = std::cos(w0);
    const double alpha = std::sin(w0) / (2.0 * q);

    const double b0 = 1.0;
    const double b1 = -2.0 * cosW0;
    const double b2 = 1.0;
    const double a0 = 1.0 + alpha;
    const double a1 = -2.0 * cosW0;
    const double a2 = 1.0 - alpha;

    return safeNormalize(b0, b1, b2, a0, a1, a2);
}

BiquadCoefficients BiquadFilter::computeAllPass(double frequency, double q, double sampleRate) {
    if (!std::isfinite(sampleRate) || sampleRate <= 0.0) return {1.0, 0.0, 0.0, 0.0, 0.0};
    frequency = std::clamp(frequency, 1.0, sampleRate * 0.499);
    q = std::clamp(q, 0.01, 100.0);

    const double w0 = 2.0 * M_PI_VAL * frequency / sampleRate;
    const double cosW0 = std::cos(w0);
    const double alpha = std::sin(w0) / (2.0 * q);

    const double b0 = 1.0 - alpha;
    const double b1 = -2.0 * cosW0;
    const double b2 = 1.0 + alpha;
    const double a0 = 1.0 + alpha;
    const double a1 = -2.0 * cosW0;
    const double a2 = 1.0 - alpha;

    return safeNormalize(b0, b1, b2, a0, a1, a2);
}

void BiquadFilter::setPeakingEq(double frequency, double q, double gainDb, double sampleRate) {
    setCoefficients(computePeakingEq(frequency, q, gainDb, sampleRate));
}

void BiquadFilter::setLowShelf(double frequency, double q, double gainDb, double sampleRate) {
    setCoefficients(computeLowShelf(frequency, q, gainDb, sampleRate));
}

void BiquadFilter::setHighShelf(double frequency, double q, double gainDb, double sampleRate) {
    setCoefficients(computeHighShelf(frequency, q, gainDb, sampleRate));
}

void BiquadFilter::setLowPass(double frequency, double q, double sampleRate) {
    setCoefficients(computeLowPass(frequency, q, sampleRate));
}

void BiquadFilter::setHighPass(double frequency, double q, double sampleRate) {
    setCoefficients(computeHighPass(frequency, q, sampleRate));
}

void BiquadFilter::setBandPass(double frequency, double q, double sampleRate) {
    setCoefficients(computeBandPass(frequency, q, sampleRate));
}

void BiquadFilter::setNotch(double frequency, double q, double sampleRate) {
    setCoefficients(computeNotch(frequency, q, sampleRate));
}

void BiquadFilter::setAllPass(double frequency, double q, double sampleRate) {
    setCoefficients(computeAllPass(frequency, q, sampleRate));
}

} // namespace antigravity
