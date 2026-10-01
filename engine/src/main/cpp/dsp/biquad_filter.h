#pragma once

namespace winton::native {

/**
 * Configuration for a single biquad band.
 */
struct BandConfig {
    float frequencyHz = 1000.0f;
    float gainDb = 0.0f;
    float q = 1.0f;
};

/**
 * Stateful second-order IIR peaking-EQ biquad.
 *
 * Coefficients follow RBJ Audio EQ Cookbook formulas.
 * State is preserved across buffers to avoid discontinuities.
 * Supports pop-free linear coefficient ramping.
 */
class BiquadFilter {
public:
    /**
     * Update filter parameters with optional ramping.
     * @param config New band configuration.
     * @param sampleRate Current audio sample rate.
     * @param rampSamples 0 for immediate jump, >0 for smooth transition over samples.
     */
    void update(const BandConfig& config, int sampleRate, int rampSamples = 0);

    /**
     * Process a single audio sample.
     */
    float process(float input);

    /**
     * Process a buffer of audio samples in-place.
     */
    void process(float* samples, int count);

    /**
     * Reset filter internal state (z-delays).
     */
    void reset();

private:
    // Current coefficients (or start of ramp)
    double b0_ = 1.0;
    double b1_ = 0.0;
    double b2_ = 0.0;
    double a1_ = 0.0;
    double a2_ = 0.0;

    // Target coefficients for ramping
    double targetB0_ = 1.0;
    double targetB1_ = 0.0;
    double targetB2_ = 0.0;
    double targetA1_ = 0.0;
    double targetA2_ = 0.0;

    // Linear step increments per sample
    double stepB0_ = 0.0;
    double stepB1_ = 0.0;
    double stepB2_ = 0.0;
    double stepA1_ = 0.0;
    double stepA2_ = 0.0;

    int rampRemaining_ = 0;

    // Delay state (DF-2T)
    double z1_ = 0.0;
    double z2_ = 0.0;
};

} // namespace winton::native
