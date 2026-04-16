package com.winton.validationshell.engine

import com.winton.validationshell.engine.policy.PolicyConfig

/**
 * Typed snapshot of a single engine analysis frame.
 *
 * Replaces the raw [FloatArray] from [Engine.getAnalysisData] with named, type-safe fields.
 * All consumers should prefer [Engine.getSnapshot] over [Engine.getAnalysisData] to avoid
 * silent breakage from magic float-index access.
 *
 * Index reference (kept for JNI alignment only):
 *  [0] rms  [1] spectralCentroid  [2] classificationId  [3] confidence
 *  [4] activePolicyId  [5] analysisLatencyMs  [6] startupLatencyMs  [7] theoreticalIoLatencyMs
 */
data class TelemetrySnapshot(
    /** RMS level (0.0–1.0). */
    val rms: Float,
    /** Normalised spectral centroid (0.0–1.0, where 1.0 = Nyquist). */
    val spectralCentroid: Float,
    /** Raw classification integer: 0=Silence, 1=Speech, 2=Music, 3=Mixed. */
    val classificationId: Int,
    /** Human-readable classification label derived from [classificationId]. */
    val classificationLabel: String,
    /** Classifier confidence score (0.0–1.0). */
    val confidence: Float,
    /** ID of the currently active processing policy. */
    val activePolicyId: Int,
    /** Human-readable name of the active processing policy. */
    val activePolicyName: String,
    /** EMA-smoothed analysis frame latency in milliseconds, or -1 if not yet available. */
    val analysisLatencyMs: Float,
    /** Time from engine start to first valid frame in milliseconds, or -1 if not yet measured. */
    val startupLatencyMs: Float,
    /** Buffer-based theoretical I/O latency estimate in milliseconds, or -1 if not set. */
    val theoreticalIoLatencyMs: Float,
    /** Wall-clock timestamp when this snapshot was produced (System.currentTimeMillis). */
    val timestampMs: Long
) {
    companion object {
        /** Map a classification integer to its display label. */
        fun labelForClassification(id: Int): String = when (id) {
            0 -> "Silence"
            1 -> "Speech"
            2 -> "Music"
            3 -> "Mixed"
            else -> "Unknown"
        }

        /**
         * Build a [TelemetrySnapshot] from a raw analysis float array.
         * Safe for arrays shorter than 8 elements — missing fields default to 0f / -1f.
         */
        fun fromFloatArray(
            data: FloatArray,
            timestampMs: Long = System.currentTimeMillis()
        ): TelemetrySnapshot {
            val classId = data.getOrNull(2)?.toInt() ?: 0
            val policyId = data.getOrNull(4)?.toInt() ?: PolicyConfig.PASSTHROUGH.id
            return TelemetrySnapshot(
                rms                  = data.getOrNull(0) ?: 0f,
                spectralCentroid     = data.getOrNull(1) ?: 0f,
                classificationId     = classId,
                classificationLabel  = labelForClassification(classId),
                confidence           = data.getOrNull(3) ?: 0f,
                activePolicyId       = policyId,
                activePolicyName     = PolicyConfig.fromId(policyId).name,
                analysisLatencyMs    = data.getOrNull(5) ?: -1f,
                startupLatencyMs     = data.getOrNull(6) ?: -1f,
                theoreticalIoLatencyMs = data.getOrNull(7) ?: -1f,
                timestampMs          = timestampMs
            )
        }
    }
}

