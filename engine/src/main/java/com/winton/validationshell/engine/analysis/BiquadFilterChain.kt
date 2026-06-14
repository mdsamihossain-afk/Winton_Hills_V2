package com.winton.validationshell.engine.analysis

import com.winton.validationshell.engine.policy.BiquadBandConfig

/**
 * Ordered serial chain of biquad filters built from policy or AutoEq bands.
 * Supports smooth coefficient ramping to prevent pops during policy switches.
 */
class BiquadFilterChain(
    initialBands: List<BiquadBandConfig>,
    private val sampleRate: Int
) {
    private val filters = mutableListOf<BiquadFilter>()

    init {
        initialBands.forEach { 
            filters.add(BiquadFilter(it, sampleRate))
        }
    }

    val bandCount: Int get() = filters.size
    val isEmpty: Boolean get() = filters.isEmpty()

    /**
     * Updates the filter chain with new bands, ramping coefficients to avoid pops.
     * If newBands is shorter than current filters, extra filters are ramped to neutral.
     * If newBands is longer, new filters are added (starting at neutral and ramping to target).
     */
    @Synchronized
    fun updateBands(newBands: List<BiquadBandConfig>, rampDurationMs: Int = 50) {
        val maxLen = maxOf(filters.size, newBands.size)
        
        for (i in 0 until maxLen) {
            val targetConfig = newBands.getOrNull(i)
            
            if (i < filters.size) {
                // Update existing filter
                val config = targetConfig ?: BiquadBandConfig(1000f, 0f, 1f) // Ramp to neutral
                filters[i].updateCoefficients(config, rampDurationMs)
            } else if (targetConfig != null) {
                // Add new filter, starting from neutral
                val neutral = BiquadBandConfig(targetConfig.frequencyHz, 0f, targetConfig.q)
                val filter = BiquadFilter(neutral, sampleRate)
                filter.updateCoefficients(targetConfig, rampDurationMs)
                filters.add(filter)
            }
        }
    }

    @Synchronized
    fun process(samples: FloatArray, count: Int = samples.size) {
        filters.forEach { it.process(samples, count) }
    }

    @Synchronized
    fun reset() {
        filters.forEach { it.reset() }
    }
}
