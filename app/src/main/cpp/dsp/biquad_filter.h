#pragma once

#include <cmath>
#include <cstdint>

namespace antigravity {

enum class FilterType {
    PEAKING_EQ = 0,
    LOW_SHELF = 1,
    HIGH_SHELF = 2,
    LOW_PASS = 3,
    HIGH_PASS = 4,
    BAND_PASS = 5,
    NOTCH = 6,
    ALL_PASS = 7
};

struct BiquadCoefficients {
    double b0 = 1.0;
    double b1 = 0.0;
    double b2 = 0.0;
    double a1 = 0.0;
    double a2 = 0.0;
};

/**
 * 64-bit Double Precision Direct Form II Transposed Biquad Filter
 * Mathematically stable, denormal-flushed, low phase noise filter
 */
class BiquadFilter {
public:
    BiquadFilter();
    ~BiquadFilter() = default;

    static BiquadCoefficients computePeakingEq(double frequency, double q, double gainDb, double sampleRate);
    static BiquadCoefficients computeLowShelf(double frequency, double q, double gainDb, double sampleRate);
    static BiquadCoefficients computeHighShelf(double frequency, double q, double gainDb, double sampleRate);
    static BiquadCoefficients computeLowPass(double frequency, double q, double sampleRate);
    static BiquadCoefficients computeHighPass(double frequency, double q, double sampleRate);
    static BiquadCoefficients computeBandPass(double frequency, double q, double sampleRate);
    static BiquadCoefficients computeNotch(double frequency, double q, double sampleRate);
    static BiquadCoefficients computeAllPass(double frequency, double q, double sampleRate);

    void setCoefficients(const BiquadCoefficients &c) {
        b0_ = c.b0; b1_ = c.b1; b2_ = c.b2;
        a1_ = c.a1; a2_ = c.a2;
    }

    BiquadCoefficients getCoefficients() const {
        return {b0_, b1_, b2_, a1_, a2_};
    }

    void setPeakingEq(double frequency, double q, double gainDb, double sampleRate);
    void setLowShelf(double frequency, double q, double gainDb, double sampleRate);
    void setHighShelf(double frequency, double q, double gainDb, double sampleRate);
    void setLowPass(double frequency, double q, double sampleRate);
    void setHighPass(double frequency, double q, double sampleRate);
    void setBandPass(double frequency, double q, double sampleRate);
    void setNotch(double frequency, double q, double sampleRate);
    void setAllPass(double frequency, double q, double sampleRate);

    inline double process(double in) {
        if (!std::isfinite(in)) {
            z1_ = 0.0;
            z2_ = 0.0;
            return 0.0;
        }
        // Direct Form II Transposed:
        // y[n] = b0*x[n] + z1[n-1]
        // z1[n] = b1*x[n] - a1*y[n] + z2[n-1]
        // z2[n] = b2*x[n] - a2*y[n]
        double out = b0_ * in + z1_;
        z1_ = b1_ * in - a1_ * out + z2_;
        z2_ = b2_ * in - a2_ * out;

        // Subnormal / denormal float and non-finite flushing to prevent CPU stalls and NaN contamination
        if (std::abs(z1_) < 1.0e-20 || !std::isfinite(z1_)) z1_ = 0.0;
        if (std::abs(z2_) < 1.0e-20 || !std::isfinite(z2_)) z2_ = 0.0;
        if (!std::isfinite(out)) out = 0.0;

        return out;
    }

    void reset();

private:
    double b0_ = 1.0;
    double b1_ = 0.0;
    double b2_ = 0.0;
    double a1_ = 0.0;
    double a2_ = 0.0;

    double z1_ = 0.0;
    double z2_ = 0.0;
};

} // namespace antigravity
