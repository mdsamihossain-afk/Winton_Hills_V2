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
class BiquadFilter(config: BiquadBandConfig, private val sampleRate: Int) {

    private var b0 = 0.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    // Ramping state
    private var targetB0 = 0.0
    private var targetB1 = 0.0
    private var targetB2 = 0.0
    private var targetA1 = 0.0
    private var targetA2 = 0.0

    private var stepB0 = 0.0
    private var stepB1 = 0.0
    private var stepB2 = 0.0
    private var stepA1 = 0.0
    private var stepA2 = 0.0
    private var rampRemaining = 0

    private var z1 = 0.0
    private var z2 = 0.0

    init {
        updateCoefficients(config, rampDurationMs = 0)
    }

    /**
     * Update filter parameters with optional ramping.
     * @param rampDurationMs 0 for immediate jump, >0 for smooth transition.
     */
    fun updateCoefficients(config: BiquadBandConfig, rampDurationMs: Int = 0) {
        val f = config.frequencyHz.toDouble().coerceIn(20.0, sampleRate / 2.0 - 1.0)
        val gainDb = config.gainDb.toDouble()
        val q = config.q.toDouble().coerceAtLeast(0.1)

        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * Math.PI * f / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)

        val a0 = 1.0 + alpha / a
        val nextB0 = (1.0 + alpha * a) / a0
        val nextB1 = (-2.0 * cosW0) / a0
        val nextB2 = (1.0 - alpha * a) / a0
        val nextA1 = (-2.0 * cosW0) / a0
        val nextA2 = (1.0 - alpha / a) / a0

        if (rampDurationMs <= 0) {
            b0 = nextB0; b1 = nextB1; b2 = nextB2; a1 = nextA1; a2 = nextA2
            rampRemaining = 0
        } else {
            targetB0 = nextB0; targetB1 = nextB1; targetB2 = nextB2
            targetA1 = nextA1; targetA2 = nextA2
            
            rampRemaining = (rampDurationMs * sampleRate / 1000).coerceAtLeast(1)
            stepB0 = (targetB0 - b0) / rampRemaining
            stepB1 = (targetB1 - b1) / rampRemaining
            stepB2 = (targetB2 - b2) / rampRemaining
            stepA1 = (targetA1 - a1) / rampRemaining
            stepA2 = (targetA2 - a2) / rampRemaining
        }
    }

    fun process(samples: FloatArray, count: Int = samples.size) {
        val n = minOf(count, samples.size)
        for (i in 0 until n) {
            if (rampRemaining > 0) {
                b0 += stepB0; b1 += stepB1; b2 += stepB2
                a1 += stepA1; a2 += stepA2
                rampRemaining--
                if (rampRemaining == 0) {
                    b0 = targetB0; b1 = targetB1; b2 = targetB2
                    a1 = targetA1; a2 = targetA2
                }
            }

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

