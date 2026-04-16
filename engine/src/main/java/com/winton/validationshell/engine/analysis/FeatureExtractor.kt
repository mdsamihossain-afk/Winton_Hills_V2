package com.winton.validationshell.engine.analysis

import kotlin.math.sqrt

/**
 * Lightweight audio feature extraction.
 * All methods are pure, stateless, and designed to run on an analysis thread (not audio callback).
 */
object FeatureExtractor {

    /**
     * Compute the Root Mean Square (RMS) level of a buffer.
     * @param buffer audio samples (float, -1.0 to 1.0 expected)
     * @param size number of samples to process
     * @return RMS value (0.0 to ~1.0)
     */
    fun computeRms(buffer: FloatArray, size: Int = buffer.size): Float {
        if (size <= 0) return 0f
        var sum = 0.0
        for (i in 0 until size.coerceAtMost(buffer.size)) {
            sum += buffer[i] * buffer[i]
        }
        return sqrt(sum / size).toFloat()
    }

    /**
     * Compute the Spectral Centroid from a magnitude spectrum.
     * Uses the naive weighted-average formula:
     *   `centroid = sum(f_i * |X_i|) / sum(|X_i|)`
     *
     * @param magnitudes magnitude spectrum (e.g., from FFT)
     * @param sampleRate audio sample rate in Hz
     * @return normalized centroid (0.0–1.0, where 1.0 = Nyquist)
     */
    fun computeSpectralCentroid(magnitudes: FloatArray, sampleRate: Int): Float {
        if (magnitudes.isEmpty()) return 0f
        val nyquist = sampleRate / 2.0
        var weightedSum = 0.0
        var magnitudeSum = 0.0
        for (i in magnitudes.indices) {
            val freq = (i.toDouble() / magnitudes.size) * nyquist
            weightedSum += freq * magnitudes[i]
            magnitudeSum += magnitudes[i]
        }
        if (magnitudeSum < 1e-10) return 0f
        val centroidHz = weightedSum / magnitudeSum
        return (centroidHz / nyquist).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Apply a Hann window in-place.
     * @param buffer audio samples to window
     */
    fun applyHannWindow(buffer: FloatArray) {
        val n = buffer.size
        if (n <= 1) return
        for (i in buffer.indices) {
            val w = 0.5 * (1.0 - kotlin.math.cos(2.0 * Math.PI * i / (n - 1)))
            buffer[i] = (buffer[i] * w).toFloat()
        }
    }

    /**
     * Classify audio content based on simple heuristics.
     *
     * @param rms RMS level
     * @param spectralCentroid normalized spectral centroid (0–1)
     * @return Pair(classification, confidence) where classification: 1=Speech, 2=Music, 3=Mixed
     */
    fun classify(rms: Float, spectralCentroid: Float): Pair<Int, Float> {
        // Simple heuristic thresholds (will be tuned with real data)
        return when {
            // Speech: moderate RMS, mid-range centroid
            rms in 0.02f..0.3f && spectralCentroid in 0.05f..0.35f -> {
                val confidence = 0.6f + 0.3f * (1f - (spectralCentroid - 0.15f).coerceIn(0f, 0.2f) / 0.2f)
                Pair(1, confidence.coerceIn(0f, 1f))
            }
            // Music: higher centroid or higher RMS
            spectralCentroid > 0.35f || rms > 0.3f -> {
                val confidence = 0.5f + 0.4f * spectralCentroid.coerceIn(0f, 1f)
                Pair(2, confidence.coerceIn(0f, 1f))
            }
            // Silence / very quiet
            rms < 0.02f -> Pair(0, 0.95f)
            // Mixed / uncertain
            else -> Pair(3, 0.4f)
        }
    }
}

