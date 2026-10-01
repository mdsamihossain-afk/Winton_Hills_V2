#include "policy_selector.h"
#include <algorithm>

namespace winton::native {

namespace {
    const PolicySelector::PolicyConfig kPassthroughConfig = {
        PolicySelector::PolicyId::PASSTHROUGH,
        {},
        true
    };

    const PolicySelector::PolicyConfig kSpeechConfig = {
        PolicySelector::PolicyId::SPEECH,
        {
            {250.0f, -2.0f, 0.8f},
            {1000.0f, 3.0f, 1.0f},
            {3000.0f, 4.0f, 1.2f},
            {6000.0f, 2.0f, 0.9f}
        },
        false
    };

    const PolicySelector::PolicyConfig kNeutralConfig = {
        PolicySelector::PolicyId::NEUTRAL,
        {
            {200.0f, -1.0f, 0.7f},
            {800.0f, 1.0f, 0.9f},
            {4000.0f, 1.5f, 1.0f},
            {8000.0f, -0.5f, 0.8f}
        },
        true
    };

    const PolicySelector::PolicyConfig kConservativeConfig = {
        PolicySelector::PolicyId::CONSERVATIVE,
        {},
        true
    };
}

PolicySelector::PolicySelector()
    : currentPolicyId_(PolicyId::PASSTHROUGH), pendingStartTimeMs_(0) {}

const PolicySelector::PolicyConfig& PolicySelector::getConfig(PolicyId id) {
    switch (id) {
        case PolicyId::SPEECH: return kSpeechConfig;
        case PolicyId::NEUTRAL: return kNeutralConfig;
        case PolicyId::CONSERVATIVE: return kConservativeConfig;
        default: return kPassthroughConfig;
    }
}

PolicySelector::PolicyId PolicySelector::select(
    int classification,
    float confidence,
    bool forceConservative,
    bool isBluetooth,
    uint64_t currentTimeMs
) {
    if (forceConservative) {
        resetPending();
        currentPolicyId_ = PolicyId::CONSERVATIVE;
        return currentPolicyId_;
    }

    // Determine ideal policy
    PolicyId idealId = PolicyId::PASSTHROUGH;
    if (confidence >= minConfidence_) {
        if (classification == 1) idealId = PolicyId::SPEECH;
        else if (classification == 2 || classification == 3) idealId = PolicyId::NEUTRAL;
    }

    // Route constraints
    const auto& idealConfig = getConfig(idealId);
    PolicyId finalId = idealId;
    if (!idealConfig.allowBluetooth && isBluetooth) {
        finalId = PolicyId::PASSTHROUGH;
    }

    // Apply immediate switch if current policy becomes invalid due to route change
    // This overrides hysteresis for safety.
    const auto& currentConfig = getConfig(currentPolicyId_);
    if (!currentConfig.allowBluetooth && isBluetooth) {
        currentPolicyId_ = PolicyId::PASSTHROUGH;
        resetPending();
        return currentPolicyId_;
    }

    // Hysteresis
    if (finalId != currentPolicyId_) {
        if (pendingPolicyId_ == finalId) {
            if (currentTimeMs - pendingStartTimeMs_ >= hysteresisMs_) {
                currentPolicyId_ = finalId;
                resetPending();
            }
        } else {
            pendingPolicyId_ = finalId;
            pendingStartTimeMs_ = currentTimeMs;
        }
    } else {
        resetPending();
    }

    return currentPolicyId_;
}

void PolicySelector::forcePolicy(PolicyId id) {
    currentPolicyId_ = id;
    resetPending();
}

void PolicySelector::resetPending() {
    pendingPolicyId_.reset();
    pendingStartTimeMs_ = 0;
}

} // namespace winton::native
