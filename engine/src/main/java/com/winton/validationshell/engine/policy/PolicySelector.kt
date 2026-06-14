package com.winton.validationshell.engine.policy

import com.winton.validationshell.engine.hardware.HardwareProfile

/**
 * Auto-mode policy selector.
 * Uses classification results + hardware profile + execution budget to pick the right policy.
 * Includes hysteresis to prevent rapid switching.
 */
class PolicySelector {

    private var currentPolicyId: Int = PolicyConfig.PASSTHROUGH.id
    private var pendingPolicyId: Int? = null
    private var pendingStartTimeMs: Long = 0L

    /** Hysteresis: must hold for this duration before switching. */
    var hysteresisMs: Long = 500L
    /** Minimum confidence to consider a classification valid. */
    var minConfidence: Float = 0.7f

    /**
     * Given the latest classification result and hardware profile, return the best policy.
     *
     * @param classification 1=Speech, 2=Music, 3=Mixed
     * @param confidence 0.0–1.0
     * @param hardwareProfile current route profile
     * @param currentTimeMs monotonic time in ms (e.g., SystemClock.elapsedRealtime())
     * @return the selected [PolicyConfig]
     */
    @Synchronized
    fun select(
        classification: Int,
        confidence: Float,
        hardwareProfile: HardwareProfile,
        currentTimeMs: Long
    ): PolicyConfig {
        // If hardware demands conservative mode, always use conservative passthrough
        if (hardwareProfile.forceConservative) {
            resetPending()
            currentPolicyId = PolicyConfig.CONSERVATIVE.id
            return PolicyConfig.CONSERVATIVE
        }

        // Determine the ideal policy based on classification
        val idealId = if (confidence >= minConfidence) {
            when (classification) {
                1 -> PolicyConfig.SPEECH_INTELLIGIBILITY.id
                2 -> PolicyConfig.NEUTRAL_CORRECTION.id
                3 -> PolicyConfig.NEUTRAL_CORRECTION.id  // mixed → neutral
                else -> PolicyConfig.PASSTHROUGH.id
            }
        } else {
            PolicyConfig.PASSTHROUGH.id
        }

        // Check route compatibility
        val idealPolicy = PolicyConfig.fromId(idealId)
        val finalId = if (!idealPolicy.allowBluetooth &&
            hardwareProfile.routeType == HardwareProfile.RouteType.BLUETOOTH
        ) {
            PolicyConfig.PASSTHROUGH.id
        } else {
            idealId
        }

        // Apply hysteresis
        if (finalId != currentPolicyId) {
            if (pendingPolicyId == finalId) {
                // Same pending — check if holdtime exceeded
                if (currentTimeMs - pendingStartTimeMs >= hysteresisMs) {
                    currentPolicyId = finalId
                    resetPending()
                }
            } else {
                // New candidate — start the timer
                pendingPolicyId = finalId
                pendingStartTimeMs = currentTimeMs
            }
        } else {
            resetPending()
        }

        return PolicyConfig.fromId(currentPolicyId)
    }

    /** Force a specific policy (manual mode). */
    @Synchronized
    fun forcePolicy(policyId: Int) {
        currentPolicyId = policyId
        resetPending()
    }

    @Synchronized
    fun getCurrentPolicyId(): Int = currentPolicyId

    @Synchronized
    private fun resetPending() {
        pendingPolicyId = null
        pendingStartTimeMs = 0L
    }
}

