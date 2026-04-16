package com.winton.validationshell.engine.latency

import android.os.SystemClock

/**
 * Comprehensive latency measurement for the Winton Audio Engine.
 *
 * Tracks four independent latency metrics:
 *  1. **Analysis latency** — time from `AudioRecord.read()` to classification result (per-frame)
 *  2. **Startup latency** — time from `startAudioEngine()` to first valid analysis frame
 *  3. **Theoretical I/O latency** — buffer-size-based estimate of the audio pipeline delay
 *  4. **Policy switch latency** — time from `setPolicy()` call to the new policy being active
 *
 * Each metric uses an EMA-smoothed value plus min/max/count tracking.
 * Call [getSessionSummary] to export all metrics as a map for CSV/JSON logging.
 */
class LatencyTracker {

    // ========================================================================
    // ANALYSIS LATENCY (per-frame: mic read → FFT → classify → done)
    // ========================================================================

    private var analysisSmoothed: Float = 0f
    private var analysisMin: Float = Float.MAX_VALUE
    private var analysisMax: Float = 0f
    private var analysisCount: Int = 0

    /** Exponential moving average factor (lower = smoother). */
    var alpha: Float = 0.2f

    /** Record an analysis-frame latency measurement in milliseconds. */
    fun record(latencyMs: Float) {
        analysisSmoothed = if (analysisCount == 0) {
            latencyMs
        } else {
            alpha * latencyMs + (1f - alpha) * analysisSmoothed
        }
        if (latencyMs < analysisMin) analysisMin = latencyMs
        if (latencyMs > analysisMax) analysisMax = latencyMs
        analysisCount++
        lastRecordTimeMs = SystemClock.elapsedRealtime()

        // If this is the first real frame and we're tracking startup, close it
        if (!startupRecorded && engineStartTimeMs > 0) {
            startupLatencyMs = (lastRecordTimeMs - engineStartTimeMs).toFloat()
            startupRecorded = true
        }
    }

    /** Get the smoothed analysis latency, or -1f if no samples yet. */
    fun getSmoothedLatencyMs(): Float {
        return if (analysisCount == 0) -1f else analysisSmoothed
    }

    /** Number of analysis samples recorded so far. */
    fun getSampleCount(): Int = analysisCount

    private var lastRecordTimeMs: Long = 0L

    // ========================================================================
    // MODE-SPECIFIC LATENCY (A/B)
    // ========================================================================

    private var bypassLatencySumMs: Float = 0f
    private var bypassLatencyCount: Int = 0
    private var processedLatencySumMs: Float = 0f
    private var processedLatencyCount: Int = 0

    /**
     * Record a latency sample by mode so we can estimate added processing cost.
     * bypassMode=true corresponds to UI mode A, false corresponds to mode B.
     */
    fun recordModeSample(latencyMs: Float, bypassMode: Boolean) {
        if (latencyMs < 0f) return
        if (bypassMode) {
            bypassLatencySumMs += latencyMs
            bypassLatencyCount++
        } else {
            processedLatencySumMs += latencyMs
            processedLatencyCount++
        }
    }

    // ========================================================================
    // STARTUP LATENCY (engine start → first valid analysis frame)
    // ========================================================================

    private var engineStartTimeMs: Long = 0L
    private var startupLatencyMs: Float = -1f
    private var startupRecorded: Boolean = false

    /** Call when the engine starts. Begins the startup-latency timer. */
    fun markEngineStart() {
        engineStartTimeMs = SystemClock.elapsedRealtime()
        startupRecorded = false
        startupLatencyMs = -1f
    }

    /** Get startup latency in ms, or -1 if not yet measured. */
    fun getStartupLatencyMs(): Float = startupLatencyMs

    // ========================================================================
    // THEORETICAL I/O LATENCY (buffer-based estimate)
    // ========================================================================

    private var theoreticalLatencyMs: Float = -1f

    /**
     * Calculate theoretical I/O latency from buffer size and sample rate.
     * Formula: `(bufferFrames / sampleRate) * 1000 * 2` (double-buffered round-trip estimate)
     */
    fun setTheoreticalLatency(bufferFrames: Int, sampleRate: Int) {
        theoreticalLatencyMs = if (sampleRate > 0) {
            (bufferFrames.toFloat() / sampleRate) * 1000f * 2f
        } else {
            -1f
        }
    }

    /** Get the theoretical I/O latency, or -1 if not set. */
    fun getTheoreticalLatencyMs(): Float = theoreticalLatencyMs

    // ========================================================================
    // POLICY SWITCH LATENCY
    // ========================================================================

    private var policySwitchStartMs: Long = 0L
    private var policySwitchLatencyMs: Float = -1f
    private var policySwitchCount: Int = 0

    /** Call when a policy switch is requested. */
    fun markPolicySwitchStart() {
        policySwitchStartMs = SystemClock.elapsedRealtime()
    }

    /** Call when the new policy is confirmed active. */
    fun markPolicySwitchEnd() {
        if (policySwitchStartMs > 0) {
            policySwitchLatencyMs = (SystemClock.elapsedRealtime() - policySwitchStartMs).toFloat()
            policySwitchCount++
            policySwitchStartMs = 0L
        }
    }

    /** Last measured policy-switch latency in ms, or -1 if none measured. */
    fun getPolicySwitchLatencyMs(): Float = policySwitchLatencyMs

    // ========================================================================
    // SESSION SUMMARY
    // ========================================================================

    /**
     * Get all latency metrics as a string-keyed map, suitable for CSV / JSON export.
     * Returns values rounded to 2 decimal places.
     */
    fun getSessionSummary(): Map<String, String> {
        val map = mutableMapOf<String, String>()

        // Analysis latency
        if (analysisCount > 0) {
            map["AnalysisLatencySmoothedMs"] = "%.2f".format(analysisSmoothed)
            map["AnalysisLatencyMinMs"] = "%.2f".format(analysisMin)
            map["AnalysisLatencyMaxMs"] = "%.2f".format(analysisMax)
            map["AnalysisFrameCount"] = analysisCount.toString()
        }

        // Startup latency
        if (startupRecorded) {
            map["StartupLatencyMs"] = "%.2f".format(startupLatencyMs)
        }

        // Theoretical I/O
        if (theoreticalLatencyMs >= 0f) {
            map["TheoreticalIOLatencyMs"] = "%.2f".format(theoreticalLatencyMs)
        }

        // Policy switch
        if (policySwitchCount > 0) {
            map["LastPolicySwitchLatencyMs"] = "%.2f".format(policySwitchLatencyMs)
            map["PolicySwitchCount"] = policySwitchCount.toString()
        }

        // Added latency estimate (processed vs bypass)
        if (bypassLatencyCount > 0) {
            val bypassAvg = bypassLatencySumMs / bypassLatencyCount
            map["BypassLatencyAvgMs"] = "%.2f".format(bypassAvg)
            map["BypassLatencySamples"] = bypassLatencyCount.toString()
        }
        if (processedLatencyCount > 0) {
            val processedAvg = processedLatencySumMs / processedLatencyCount
            map["ProcessedLatencyAvgMs"] = "%.2f".format(processedAvg)
            map["ProcessedLatencySamples"] = processedLatencyCount.toString()
        }
        if (bypassLatencyCount > 0 && processedLatencyCount > 0) {
            val bypassAvg = bypassLatencySumMs / bypassLatencyCount
            val processedAvg = processedLatencySumMs / processedLatencyCount
            map["AddedLatencyVsBypassMs"] = "%.2f".format(processedAvg - bypassAvg)
        }

        return map
    }

    /** Reset all tracked data. Call at engine start. */
    fun reset() {
        // Analysis
        analysisSmoothed = 0f
        analysisMin = Float.MAX_VALUE
        analysisMax = 0f
        analysisCount = 0
        lastRecordTimeMs = 0L

        // Startup
        engineStartTimeMs = 0L
        startupLatencyMs = -1f
        startupRecorded = false

        // Theoretical
        theoreticalLatencyMs = -1f

        // Policy switch
        policySwitchStartMs = 0L
        policySwitchLatencyMs = -1f
        policySwitchCount = 0

        // Mode-specific latency
        bypassLatencySumMs = 0f
        bypassLatencyCount = 0
        processedLatencySumMs = 0f
        processedLatencyCount = 0
    }
}

