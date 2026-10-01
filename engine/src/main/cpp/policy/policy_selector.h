#pragma once

#include <vector>
#include <cstdint>
#include <optional>
#include "../dsp/biquad_filter.h"

namespace winton::native {

/**
 * Auto-mode policy selector.
 * Uses classification results + hardware profile to pick the right policy.
 * Includes hysteresis to prevent rapid switching.
 */
class PolicySelector {
public:
    enum class PolicyId : int {
        PASSTHROUGH = 0,
        SPEECH = 1,
        NEUTRAL = 2,
        CONSERVATIVE = 3
    };

    struct PolicyConfig {
        PolicyId id;
        std::vector<BandConfig> bands;
        bool allowBluetooth;
    };

    PolicySelector();

    /**
     * Given the latest classification and constraints, return the best policy.
     * @param classification 1=Speech, 2=Music, 3=Mixed
     * @param confidence 0.0–1.0
     * @param forceConservative Hardware demanded conservative mode
     * @param isBluetooth Current route is bluetooth
     * @param currentTimeMs Monotonic time in ms
     */
    PolicyId select(
        int classification,
        float confidence,
        bool forceConservative,
        bool isBluetooth,
        uint64_t currentTimeMs
    );

    void forcePolicy(PolicyId id);
    PolicyId currentPolicyId() const { return currentPolicyId_; }

    static const PolicyConfig& getConfig(PolicyId id);

private:
    PolicyId currentPolicyId_;
    std::optional<PolicyId> pendingPolicyId_;
    uint64_t pendingStartTimeMs_;

    const uint64_t hysteresisMs_ = 500;
    const float minConfidence_ = 0.7f;

    void resetPending();
};

} // namespace winton::native
