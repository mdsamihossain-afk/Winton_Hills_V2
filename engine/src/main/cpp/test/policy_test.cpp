#include <iostream>
#include <vector>
#include <cassert>
#include <iomanip>

// Mock dsp and engine logic for standalone test
#define private public
#include "../policy/policy_selector.h"
#include "../policy/policy_selector.cpp"
#include "../dsp/biquad_filter.cpp"
#undef private

using namespace winton::native;

void testHysteresis() {
    std::cout << "\n=== Test: Policy Hysteresis (500ms) ===" << std::endl;

    PolicySelector selector;
    uint64_t time = 1000; // ms

    // 1. Initial State
    assert(selector.currentPolicyId() == PolicySelector::PolicyId::PASSTHROUGH);

    // 2. Classify as SPEECH with high confidence
    // Candidate changes to SPEECH, but remains PASSTHROUGH until 500ms pass
    auto res = selector.select(1, 0.9f, false, false, time);
    std::cout << "  Time 1000ms: Speech signal -> " << (int)res << " (Expected 0)" << std::endl;
    assert(res == PolicySelector::PolicyId::PASSTHROUGH);

    // 3. Increment 300ms (Total 1300ms)
    time += 300;
    res = selector.select(1, 0.9f, false, false, time);
    std::cout << "  Time 1300ms: Speech signal -> " << (int)res << " (Expected 0)" << std::endl;
    assert(res == PolicySelector::PolicyId::PASSTHROUGH);

    // 4. Increment 300ms (Total 1600ms) - Over 500ms threshold
    time += 300;
    res = selector.select(1, 0.9f, false, false, time);
    std::cout << "  Time 1600ms: Speech signal -> " << (int)res << " (Expected 1)" << std::endl;
    assert(res == PolicySelector::PolicyId::SPEECH);

    // 5. Rapid oscillation: Change to Music for 100ms
    time += 100;
    res = selector.select(2, 0.9f, false, false, time); // Music signal
    std::cout << "  Time 1700ms: Music signal (rapid) -> " << (int)res << " (Expected 1)" << std::endl;
    assert(res == PolicySelector::PolicyId::SPEECH);

    std::cout << "PASS: Hysteresis logic verified." << std::endl;
}

void testRouteConstraints() {
    std::cout << "\n=== Test: Route Constraints (Bluetooth) ===" << std::endl;

    PolicySelector selector;
    uint64_t time = 1000;

    // Speech policy (ID 1) does NOT allow Bluetooth in config
    // 1. On non-bluetooth, it should switch to SPEECH
    selector.select(1, 0.9f, false, false, time); // start timer
    selector.select(1, 0.9f, false, false, time + 600); // switch
    assert(selector.currentPolicyId() == PolicySelector::PolicyId::SPEECH);

    // 2. Force Bluetooth
    // Even if signal is SPEECH, it should drop to PASSTHROUGH immediately
    auto res = selector.select(1, 0.9f, false, true, time + 700);
    std::cout << "  Bluetooth active: Speech signal -> " << (int)res << " (Expected 0)" << std::endl;
    assert(res == PolicySelector::PolicyId::PASSTHROUGH);

    std::cout << "PASS: Route constraints verified." << std::endl;
}

int main() {
    testHysteresis();
    testRouteConstraints();
    return 0;
}
