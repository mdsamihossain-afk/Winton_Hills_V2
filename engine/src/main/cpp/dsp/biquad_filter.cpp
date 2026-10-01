#include "biquad_filter.h"
#include "dsp_constants.h"

#include <algorithm>
#include <cmath>

namespace winton::native {

namespace {
float clampFrequency(float frequencyHz, int sampleRate) {
    const float nyquist = std::max(1.0f, static_cast<float>(sampleRate) / 2.0f - 1.0f);
    return std::clamp(frequencyHz, 20.0f, nyquist);
}

float dbToLinear(float gainDb) {
    return std::pow(10.0f, gainDb / 40.0f);
}
} // namespace

void BiquadFilter::update(const BandConfig& config, int sampleRate, int rampSamples) {
    const float f = clampFrequency(config.frequencyHz, sampleRate);
    const float q = std::max(0.1f, config.q);
    const float a = dbToLinear(config.gainDb);
    const float w0 = 2.0f * static_cast<float>(kPi) * f / static_cast<float>(sampleRate);
    const float alpha = std::sin(w0) / (2.0f * q);
    const float cosW0 = std::cos(w0);

    const double a0 = 1.0 + alpha / a;
    const double nextB0 = (1.0 + alpha * a) / a0;
    const double nextB1 = (-2.0 * cosW0) / a0;
    const double nextB2 = (1.0 - alpha * a) / a0;
    const double nextA1 = (-2.0 * cosW0) / a0;
    const double nextA2 = (1.0 - alpha / a) / a0;

    if (rampSamples <= 0) {
        b0_ = nextB0;
        b1_ = nextB1;
        b2_ = nextB2;
        a1_ = nextA1;
        a2_ = nextA2;
        rampRemaining_ = 0;
        return;
    }

    targetB0_ = nextB0;
    targetB1_ = nextB1;
    targetB2_ = nextB2;
    targetA1_ = nextA1;
    targetA2_ = nextA2;

    rampRemaining_ = std::max(1, rampSamples);
    stepB0_ = (targetB0_ - b0_) / rampRemaining_;
    stepB1_ = (targetB1_ - b1_) / rampRemaining_;
    stepB2_ = (targetB2_ - b2_) / rampRemaining_;
    stepA1_ = (targetA1_ - a1_) / rampRemaining_;
    stepA2_ = (targetA2_ - a2_) / rampRemaining_;
}

float BiquadFilter::process(float input) {
    if (rampRemaining_ > 0) {
        b0_ += stepB0_;
        b1_ += stepB1_;
        b2_ += stepB2_;
        a1_ += stepA1_;
        a2_ += stepA2_;
        --rampRemaining_;
        if (rampRemaining_ == 0) {
            b0_ = targetB0_;
            b1_ = targetB1_;
            b2_ = targetB2_;
            a1_ = targetA1_;
            a2_ = targetA2_;
        }
    }

    const double x = input;
    const double y = b0_ * x + z1_;
    z1_ = b1_ * x - a1_ * y + z2_;
    z2_ = b2_ * x - a2_ * y;
    return static_cast<float>(y);
}

void BiquadFilter::process(float* samples, int count) {
    if (samples == nullptr || count <= 0) {
        return;
    }
    for (int i = 0; i < count; ++i) {
        samples[i] = process(samples[i]);
    }
}

void BiquadFilter::reset() {
    z1_ = 0.0;
    z2_ = 0.0;
}

} // namespace winton::native
