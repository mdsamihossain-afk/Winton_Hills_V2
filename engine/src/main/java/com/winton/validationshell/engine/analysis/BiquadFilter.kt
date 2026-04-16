package com.winton.validationshell.engine.analysis

import com.winton.validationshell.engine.policy.BiquadBandConfig
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Stateful second-order IIR peaking-EQ biquad.
 *
 * Coefficients follow RBJ Audio EQ Cookbook formulas.
 * State is preserved across buffers to avoid discontinuities.
 */
class BiquadFilter(config: BiquadBandConfig, sampleRate: Int) {

    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double

    private var z1 = 0.0
    private var z2 = 0.0

    init {
        val f = config.frequencyHz.toDouble().coerceIn(20.0, sampleRate / 2.0 - 1.0)
        val gainDb = config.gainDb.toDouble()
        val q = config.q.toDouble().coerceAtLeast(0.1)

        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * Math.PI * f / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)

        val rawB0 = 1.0 + alpha * a
        val rawB1 = -2.0 * cosW0
        val rawB2 = 1.0 - alpha * a
        val a0 = 1.0 + alpha / a
        val rawA1 = -2.0 * cosW0
        val rawA2 = 1.0 - alpha / a

        b0 = rawB0 / a0
        b1 = rawB1 / a0
        b2 = rawB2 / a0
        a1 = rawA1 / a0
        a2 = rawA2 / a0
    }

    fun process(samples: FloatArray, count: Int = samples.size) {
        val n = minOf(count, samples.size)
        for (i in 0 until n) {
            val x = samples[i].toDouble()
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            samples[i] = y.toFloat()
        }
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }
}

