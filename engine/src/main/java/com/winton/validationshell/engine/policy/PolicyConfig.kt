package com.winton.validationshell.engine.policy

/**
 * A single biquad EQ band configuration.
 * Coefficients are computed at apply-time, not stored here.
 */
data class BiquadBandConfig(
    val frequencyHz: Float,
    val gainDb: Float,
    val q: Float
)

/**
 * A processing policy is a named configuration — NOT a pipeline.
 * Each policy declares its EQ bands, cost hint, and route constraints.
 */
data class PolicyConfig(
    val id: Int,
    val name: String,
    val bands: List<BiquadBandConfig>,
    val isPassthrough: Boolean,
    val expectedCostPercent: Float,
    val allowBluetooth: Boolean
) {
    companion object {
        val PASSTHROUGH = PolicyConfig(
            id = 0,
            name = "Passthrough",
            bands = emptyList(),
            isPassthrough = true,
            expectedCostPercent = 0f,
            allowBluetooth = true
        )

        val SPEECH_INTELLIGIBILITY = PolicyConfig(
            id = 1,
            name = "Speech Intelligibility",
            bands = listOf(
                BiquadBandConfig(frequencyHz = 250f, gainDb = -2f, q = 0.8f),
                BiquadBandConfig(frequencyHz = 1000f, gainDb = 3f, q = 1.0f),
                BiquadBandConfig(frequencyHz = 3000f, gainDb = 4f, q = 1.2f),
                BiquadBandConfig(frequencyHz = 6000f, gainDb = 2f, q = 0.9f)
            ),
            isPassthrough = false,
            expectedCostPercent = 2f,
            allowBluetooth = false
        )

        val NEUTRAL_CORRECTION = PolicyConfig(
            id = 2,
            name = "Neutral Correction",
            bands = listOf(
                BiquadBandConfig(frequencyHz = 200f, gainDb = -1f, q = 0.7f),
                BiquadBandConfig(frequencyHz = 800f, gainDb = 1f, q = 0.9f),
                BiquadBandConfig(frequencyHz = 4000f, gainDb = 1.5f, q = 1.0f),
                BiquadBandConfig(frequencyHz = 8000f, gainDb = -0.5f, q = 0.8f)
            ),
            isPassthrough = false,
            expectedCostPercent = 2f,
            allowBluetooth = true
        )

        val CONSERVATIVE = PolicyConfig(
            id = 3,
            name = "Conservative Passthrough",
            bands = emptyList(),
            isPassthrough = true,
            expectedCostPercent = 0f,
            allowBluetooth = true
        )

        /** Look up a policy by its integer ID. */
        fun fromId(id: Int): PolicyConfig = when (id) {
            0 -> PASSTHROUGH
            1 -> SPEECH_INTELLIGIBILITY
            2 -> NEUTRAL_CORRECTION
            3 -> CONSERVATIVE
            else -> PASSTHROUGH
        }

        fun all(): List<PolicyConfig> = listOf(PASSTHROUGH, SPEECH_INTELLIGIBILITY, NEUTRAL_CORRECTION, CONSERVATIVE)
    }
}

