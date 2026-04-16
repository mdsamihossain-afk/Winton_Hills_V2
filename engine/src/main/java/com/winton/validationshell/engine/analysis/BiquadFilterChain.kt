package com.winton.validationshell.engine.analysis

import com.winton.validationshell.engine.policy.BiquadBandConfig

/**
 * Ordered serial chain of biquad filters built from policy or AutoEq bands.
 */
class BiquadFilterChain(
    bands: List<BiquadBandConfig>,
    sampleRate: Int
) {

    private val filters: List<BiquadFilter> = bands
        .filter { it.frequencyHz > 0f && it.q > 0f }
        .map { BiquadFilter(it, sampleRate) }

    val bandCount: Int get() = filters.size
    val isEmpty: Boolean get() = filters.isEmpty()

    fun process(samples: FloatArray, count: Int = samples.size) {
        filters.forEach { it.process(samples, count) }
    }

    fun reset() {
        filters.forEach { it.reset() }
    }
}

