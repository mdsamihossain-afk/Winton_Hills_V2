#include <iostream>
#include <vector>
#include <cmath>
#include <cassert>
#include <iomanip>

// Hack to access private members
#define private public
#include "../native_processing_engine.h"
#include "../native_processing_engine.cpp"
#include "../dsp/fft.h"
#include "../dsp/fft.cpp"
#include "../dsp/analysis.h"
#include "../dsp/analysis.cpp"
#include "../policy/policy_selector.h"
#include "../policy/policy_selector.cpp"
#include "../dsp/biquad_filter.h"
#include "../dsp/biquad_filter.cpp"
#include "../dsp/biquad_cascade.h"
#include "../dsp/biquad_cascade.cpp"
#undef private

using namespace winton::native;

void testZcrRobustness() {
    std::cout << "\n=== Test: ZCR Robustness ===" << std::endl;

    struct TestCase {
        std::string name;
        std::vector<float> samples;
        float expectedCrossings;
    };

    std::vector<TestCase> cases = {
        {"Simple crossing", {1.0f, -1.0f}, 1.0f},
        {"Into zero", {1.0f, 0.0f, -1.0f}, 1.0f},
        {"Out of zero", {0.0f, 1.0f, 0.0f}, 1.0f},
        {"Zero jump (pos-zero-pos)", {1.0f, 0.0f, 1.0f}, 1.0f},
        {"Exact zero edge", {1.0f, 0.0f, 1.0f, -1.0f, 0.0f, -1.0f}, 3.0f},
        {"Noise near zero (below threshold)", {1e-6f, -1e-6f, 1e-6f}, 0.0f},
        {"Noise above threshold", {2e-5f, -2e-5f}, 1.0f},
        {"Repeated zeros", {1.0f, 0.0f, 0.0f, -1.0f}, 1.0f}
    };

    for (const auto& tc : cases) {
        float zcr = Analysis::computeZcr(tc.samples.data(), tc.samples.size());
        float actualCrossings = zcr * (tc.samples.size() - 1);

        bool pass = std::abs(actualCrossings - tc.expectedCrossings) < 1e-5f;
        std::cout << "  " << (pass ? "PASS" : "FAIL") << ": " << tc.name
                  << " -> " << actualCrossings << " (expected " << tc.expectedCrossings << ")" << std::endl;
    }
}

int main() {
    testZcrRobustness();
    return 0;
}
