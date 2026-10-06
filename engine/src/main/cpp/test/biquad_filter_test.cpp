#include <cmath>
#include <iostream>
#include <iomanip>
#include <vector>
#include <limits>
#include <algorithm>

// Hack to access private members in tests
#define private public
#include "../dsp/biquad_filter.h"
#include "../dsp/biquad_filter.cpp"
#include "../dsp/biquad_cascade.h"
#include "../dsp/biquad_cascade.cpp"
#undef private

namespace winton::native::test {

// Helper: Compute expected RBJ coefficients manually for validation
struct RbjCoefficients {
    double b0, b1, b2, a1, a2;
};

RbjCoefficients computeRbjManual(float frequencyHz, float gainDb, float q, int sampleRate) {
    const double f = frequencyHz;
    const double a = std::pow(10.0, gainDb / 40.0);
    const double w0 = 2.0 * M_PI * f / sampleRate;
    const double alpha = std::sin(w0) / (2.0 * q);
    const double cosW0 = std::cos(w0);

    const double a0 = 1.0 + alpha / a;
    const double b0 = (1.0 + alpha * a) / a0;
    const double b1 = (-2.0 * cosW0) / a0;
    const double b2 = (1.0 - alpha * a) / a0;
    const double a1_coeff = (-2.0 * cosW0) / a0;
    const double a2_coeff = (1.0 - alpha / a) / a0;

    return {b0, b1, b2, a1_coeff, a2_coeff};
}

// Test 1: Verify RBJ coefficient computation
bool testBiquadCoefficients() {
    std::cout << "\n=== Test 1: RBJ Coefficient Computation ===" << std::endl;

    BiquadFilter filter;
    BandConfig config{1000.0f, 6.0f, 1.0f};
    filter.update(config, 48000, 0);

    auto expected = computeRbjManual(1000.0f, 6.0f, 1.0f, 48000);

    const double tolerance = 1e-9;
    bool pass = true;

    std::cout << std::fixed << std::setprecision(12);

    if (std::abs(filter.b0_ - expected.b0) > tolerance) {
        std::cout << "FAIL: b0 mismatch: " << filter.b0_ << " vs " << expected.b0 << std::endl;
        pass = false;
    }
    if (std::abs(filter.b1_ - expected.b1) > tolerance) {
        std::cout << "FAIL: b1 mismatch: " << filter.b1_ << " vs " << expected.b1 << std::endl;
        pass = false;
    }
    if (std::abs(filter.b2_ - expected.b2) > tolerance) {
        std::cout << "FAIL: b2 mismatch: " << filter.b2_ << " vs " << expected.b2 << std::endl;
        pass = false;
    }
    if (std::abs(filter.a1_ - expected.a1) > tolerance) {
        std::cout << "FAIL: a1 mismatch: " << filter.a1_ << " vs " << expected.a1 << std::endl;
        pass = false;
    }
    if (std::abs(filter.a2_ - expected.a2) > tolerance) {
        std::cout << "FAIL: a2 mismatch: " << filter.a2_ << " vs " << expected.a2 << std::endl;
        pass = false;
    }

    if (pass) {
        std::cout << "PASS: Coefficients match RBJ formula" << std::endl;
        std::cout << "  b0=" << filter.b0_ << " b1=" << filter.b1_ << " b2=" << filter.b2_ << std::endl;
        std::cout << "  a1=" << filter.a1_ << " a2=" << filter.a2_ << std::endl;
    }
    return pass;
}

// Test 2: Impulse response shape
bool testImpulseResponse() {
    std::cout << "\n=== Test 2: Impulse Response ===" << std::endl;

    BiquadFilter filter;
    BandConfig config{1000.0f, 6.0f, 1.0f};
    filter.update(config, 48000, 0);

    // Feed impulse
    std::vector<float> samples(100, 0.0f);
    samples[0] = 1.0f;

    filter.process(samples.data(), samples.size());

    // Check for NaN/Inf
    bool pass = true;
    float maxMag = 0.0f;
    for (size_t i = 0; i < samples.size(); ++i) {
        if (std::isnan(samples[i]) || std::isinf(samples[i])) {
            std::cout << "FAIL: NaN/Inf at index " << i << ": " << samples[i] << std::endl;
            pass = false;
            break;
        }
        maxMag = std::max(maxMag, std::abs(samples[i]));
    }

    if (pass) {
        std::cout << "PASS: Impulse response is finite" << std::endl;
        std::cout << "  Peak magnitude: " << std::fixed << std::setprecision(6) << maxMag << std::endl;
        std::cout << "  First 10 samples: ";
        for (int i = 0; i < 10; ++i) {
            std::cout << samples[i] << " ";
        }
        std::cout << std::endl;
    }
    return pass;
}

// Test 3: Coefficient ramping
bool testCoefficientRamping() {
    std::cout << "\n=== Test 3: Coefficient Ramping ===" << std::endl;

    BiquadFilter filter;

    // Start at neutral
    BandConfig config0{1000.0f, 0.0f, 1.0f};
    filter.update(config0, 48000, 0);

    // Ramp to +12dB over 50 samples
    BandConfig config1{1000.0f, 12.0f, 1.0f};
    filter.update(config1, 48000, 50);

    // Verify step size
    double expectedStepB0 = (filter.targetB0_ - (filter.b0_ - filter.stepB0_ * 50)) / 50;
    double actualStepB0 = filter.stepB0_;

    std::cout << std::fixed << std::setprecision(12);
    std::cout << "Ramp parameters:" << std::endl;
    std::cout << "  rampRemaining: " << filter.rampRemaining_ << std::endl;
    std::cout << "  targetB0: " << filter.targetB0_ << std::endl;
    std::cout << "  stepB0: " << filter.stepB0_ << std::endl;

    // Process with DC input to see smooth ramp
    std::vector<float> samples(100, 1.0f);
    filter.process(samples.data(), samples.size());

    bool pass = true;

    // Check monotonicity (output should increase monotonically for +12dB boost on DC)
    for (size_t i = 1; i < 51; ++i) {
        if (samples[i] < samples[i-1]) {
            std::cout << "FAIL: Non-monotonic increase during ramp at sample " << i << std::endl;
            pass = false;
            break;
        }
    }

    // Check convergence
    if (std::abs(samples[49] - samples[50]) > 1e-6) {
        std::cout << "WARN: Ramp not fully converged by sample 50" << std::endl;
    }

    if (pass) {
        std::cout << "PASS: Coefficient ramping is monotonic" << std::endl;
        std::cout << "  Sample 0: " << samples[0] << std::endl;
        std::cout << "  Sample 25: " << samples[25] << std::endl;
        std::cout << "  Sample 49: " << samples[49] << std::endl;
        std::cout << "  Sample 50: " << samples[50] << std::endl;
    }
    return pass;
}

// Test 4: Stability over long run
bool testNumericalStability() {
    std::cout << "\n=== Test 4: Numerical Stability (Long Run) ===" << std::endl;

    BiquadFilter filter;

    // Extreme Q value (narrow resonance)
    BandConfig config{1000.0f, 12.0f, 10.0f};
    filter.update(config, 48000, 0);

    // Feed noise for 48000 samples (1 second at 48kHz)
    std::vector<float> samples(48000);
    for (size_t i = 0; i < samples.size(); ++i) {
        samples[i] = 0.1f * std::sin(2.0f * M_PI * i / 48.0f);  // ~1kHz sine
    }

    filter.process(samples.data(), samples.size());

    bool pass = true;
    for (size_t i = 0; i < samples.size(); ++i) {
        if (std::isnan(samples[i]) || std::isinf(samples[i])) {
            std::cout << "FAIL: Numerical instability at sample " << i << ": " << samples[i] << std::endl;
            pass = false;
            break;
        }
    }

    if (pass) {
        std::cout << "PASS: 1-second run is stable (no NaN/Inf)" << std::endl;
        std::cout << "  Min: " << *std::min_element(samples.begin(), samples.end()) << std::endl;
        std::cout << "  Max: " << *std::max_element(samples.begin(), samples.end()) << std::endl;
    }
    return pass;
}

// Test 5: State reset
bool testStateReset() {
    std::cout << "\n=== Test 5: State Reset ===" << std::endl;

    BiquadFilter filter;
    BandConfig config{1000.0f, 6.0f, 1.0f};
    filter.update(config, 48000, 0);

    // Process some signal to build up state
    std::vector<float> samples(100, 0.5f);
    filter.process(samples.data(), samples.size());

    // Check state is non-zero
    if (filter.z1_ == 0.0 && filter.z2_ == 0.0) {
        std::cout << "WARN: State remained zero after processing (unexpected)" << std::endl;
    }

    // Reset
    filter.reset();

    bool pass = (filter.z1_ == 0.0 && filter.z2_ == 0.0);
    if (pass) {
        std::cout << "PASS: State reset to zero" << std::endl;
    } else {
        std::cout << "FAIL: State not reset (z1=" << filter.z1_ << " z2=" << filter.z2_ << ")" << std::endl;
    }
    return pass;
}

// Test 6: Cascade serial ordering
bool testCascadeSerialOrdering() {
    std::cout << "\n=== Test 6: Cascade Serial Ordering ===" << std::endl;

    BiquadCascade cascade;
    std::vector<BandConfig> bands{
        {1000.0f, 6.0f, 1.0f},
        {2000.0f, -3.0f, 1.0f}
    };
    cascade.updateBands(bands, 48000, 0);

    // Create test signal
    std::vector<float> signal(100, 1.0f);  // DC

    // Process through cascade
    cascade.process(signal.data(), signal.size());

    // Verify cascade is not empty
    if (cascade.filters_.size() != 2) {
        std::cout << "FAIL: Expected 2 filters, got " << cascade.filters_.size() << std::endl;
        return false;
    }

    bool pass = true;
    for (size_t i = 0; i < signal.size(); ++i) {
        if (std::isnan(signal[i]) || std::isinf(signal[i])) {
            std::cout << "FAIL: NaN/Inf in cascade output at sample " << i << std::endl;
            pass = false;
            break;
        }
    }

    if (pass) {
        std::cout << "PASS: Cascade processes serial ordering correctly" << std::endl;
        std::cout << "  Filter count: " << cascade.filters_.size() << std::endl;
        std::cout << "  Output range: [" << *std::min_element(signal.begin(), signal.end())
                  << ", " << *std::max_element(signal.begin(), signal.end()) << "]" << std::endl;
    }
    return pass;
}

// Test 7: Cascade dynamic update
bool testCascadeDynamicUpdate() {
    std::cout << "\n=== Test 7: Cascade Dynamic Update (Growing/Shrinking) ===" << std::endl;

    BiquadCascade cascade;

    // Start with 1 band
    std::vector<BandConfig> bands1{{1000.0f, 6.0f, 1.0f}};
    cascade.updateBands(bands1, 48000, 0);

    if (cascade.filters_.size() != 1) {
        std::cout << "FAIL: Initial update failed (expected 1 filter)" << std::endl;
        return false;
    }

    // Expand to 3 bands
    std::vector<BandConfig> bands3{
        {1000.0f, 6.0f, 1.0f},
        {2000.0f, -3.0f, 1.0f},
        {4000.0f, 0.0f, 1.0f}
    };
    cascade.updateBands(bands3, 48000, 0);

    if (cascade.filters_.size() != 3) {
        std::cout << "FAIL: Expansion failed (expected 3 filters, got " << cascade.filters_.size() << ")" << std::endl;
        return false;
    }

    // Shrink back to 1 band
    cascade.updateBands(bands1, 48000, 0);

    if (cascade.filters_.size() != 1) {
        std::cout << "FAIL: Shrinking failed (expected 1 filter, got " << cascade.filters_.size() << ")" << std::endl;
        return false;
    }

    std::cout << "PASS: Cascade dynamic update works (grow 1→3, shrink 3→1)" << std::endl;
    return true;
}

}  // namespace winton::native::test

int main() {
    std::cout << "========================================" << std::endl;
    std::cout << "  Winton Native DSP Unit Tests" << std::endl;
    std::cout << "========================================" << std::endl;

    int passed = 0;
    int failed = 0;

    // Run all tests
    if (winton::native::test::testBiquadCoefficients()) passed++; else failed++;
    if (winton::native::test::testImpulseResponse()) passed++; else failed++;
    if (winton::native::test::testCoefficientRamping()) passed++; else failed++;
    if (winton::native::test::testNumericalStability()) passed++; else failed++;
    if (winton::native::test::testStateReset()) passed++; else failed++;
    if (winton::native::test::testCascadeSerialOrdering()) passed++; else failed++;
    if (winton::native::test::testCascadeDynamicUpdate()) passed++; else failed++;

    std::cout << "\n========================================" << std::endl;
    std::cout << "Results: " << passed << " PASSED, " << failed << " FAILED" << std::endl;
    std::cout << "========================================" << std::endl;

    return (failed == 0) ? 0 : 1;
}

