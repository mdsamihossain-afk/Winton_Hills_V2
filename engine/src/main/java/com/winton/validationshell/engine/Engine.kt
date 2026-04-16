package com.winton.validationshell.engine

import android.content.Context
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.winton.validationshell.engine.autoeq.AutoEqMatch
import com.winton.validationshell.engine.autoeq.AutoEqProfileRepository
import com.winton.validationshell.engine.autoeq.AutoEqProfileMatcher
import com.winton.validationshell.engine.autoeq.EqProfileMapper
import com.winton.validationshell.engine.analysis.AudioCaptureThread
import com.winton.validationshell.engine.analysis.BiquadFilterChain
import com.winton.validationshell.engine.analysis.FeatureExtractor
import com.winton.validationshell.engine.hardware.HardwareProfile
import com.winton.validationshell.engine.hardware.HardwareResolver
import com.winton.validationshell.engine.hardware.ProfileDatabase
import com.winton.validationshell.engine.latency.LatencyTracker
import com.winton.validationshell.engine.policy.PolicyConfig
import com.winton.validationshell.engine.policy.PolicySelector

/**
 * Public API surface for the Winton Audio Engine.
 *
 * This class is the sole entry point for the host application.
 * It delegates to the hardware resolver, policy selector, feature extractor,
 * and (when available) the native C++ audio core via JNI.
 *
 * **Live capture mode**: When the native library is not available, the engine
 * captures real audio from the microphone via [AudioCaptureThread], computes
 * RMS / spectral centroid / classification from live PCM data, and feeds the
 * results into the policy selector. Falls back to synthetic stub data if
 * mic capture fails (e.g. permission not granted).
 */
class Engine {

    companion object {
        private const val TAG = "WintonEngine"
        private const val EXPECTED_ANALYSIS_VALUES = 8
        private var nativeAvailable: Boolean = false
        private var nativeProcessingEnabled: Boolean = false

        init {
            try {
                System.loadLibrary("engine")
                nativeAvailable = true
                Log.i(TAG, "Native engine loaded")
            } catch (e: UnsatisfiedLinkError) {
                nativeAvailable = false
                Log.w(TAG, "Native engine not available — running in stub mode")
            }
        }
    }

    // --- Sub-components ---
    private val policySelector = PolicySelector()
    private val latencyTracker = LatencyTracker()
    private var currentHardwareProfile: HardwareProfile = HardwareProfile.DEFAULT
    private var captureThread: AudioCaptureThread? = null

    // --- State ---
    private var isRunning = false
    private var autoModeEnabled = true
    private var activePolicyId: Int = PolicyConfig.PASSTHROUGH.id
    private var bypassModeEnabled: Boolean = false
    private var lastAutoEqMatch: AutoEqMatch? = null
    private var matchedAutoEqBands = emptyList<com.winton.validationshell.engine.policy.BiquadBandConfig>()

    private fun useNativePath(): Boolean = nativeAvailable && nativeProcessingEnabled

    // ========================================================================
    // PUBLIC API — these methods define the SDK surface
    // ========================================================================

    /**
     * Initialize context-dependent resources (e.g. the hardware profile database).
     * Call once, early — e.g. from Service.onCreate() or Application.onCreate().
     * Safe to call multiple times; only the first call reads from disk.
     */
    fun initWithContext(context: Context) {
        ProfileDatabase.load(context)
        AutoEqProfileRepository.load(context)
        Log.d(TAG, "Profile database loaded (${ProfileDatabase.size()} entries)")
        Log.d(TAG, "AutoEq repository loaded (${AutoEqProfileRepository.size()} entries)")
    }

    /**
     * Optional runtime control for JNI processing path.
     * Default is disabled to keep Kotlin processing as the conservative Phase-1 path.
     */
    fun setNativeProcessingEnabled(enabled: Boolean) {
        nativeProcessingEnabled = enabled && nativeAvailable
        Log.i(TAG, "nativeProcessingEnabled=$nativeProcessingEnabled, nativeAvailable=$nativeAvailable")
    }

    /**
     * Resolve the current audio output route into a [HardwareProfile].
     * Call this before [startAudioEngine] to determine conservative mode, buffer sizes, etc.
     */
    fun resolveHardware(outputDevices: Array<AudioDeviceInfo>): HardwareProfile {
        currentHardwareProfile = HardwareResolver.resolve(
            outputDevices = outputDevices,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL
        )
        Log.d(TAG, "Hardware resolved: $currentHardwareProfile")
        return currentHardwareProfile
    }

    /**
     * Start the audio engine.
     * @param conservativeMode use larger buffers and passthrough-only processing
     * @return true if started successfully
     */
    fun startAudioEngine(conservativeMode: Boolean): Boolean {
        Log.d(TAG, "startAudioEngine(conservative=$conservativeMode)")
        isRunning = true
        latencyTracker.reset()
        latencyTracker.markEngineStart()
        latencyTracker.setTheoreticalLatency(
            currentHardwareProfile.suggestedBufferFrames,
            currentHardwareProfile.suggestedSampleRate
        )

        if (conservativeMode) {
            policySelector.forcePolicy(PolicyConfig.CONSERVATIVE.id)
            activePolicyId = PolicyConfig.CONSERVATIVE.id
        }

        if (useNativePath()) {
            return nativeStartEngine(conservativeMode)
        }

        // Start real microphone capture for analysis
        stopCaptureThread() // safety: ensure no leftover thread
        val sampleRate = currentHardwareProfile.suggestedSampleRate
        val frameSize = if (conservativeMode) 2048 else 1024
        captureThread = AudioCaptureThread(sampleRate, frameSize).also {
            it.bypassMode = bypassModeEnabled

            val startupBands = if (matchedAutoEqBands.isNotEmpty() && !bypassModeEnabled) {
                matchedAutoEqBands
            } else {
                val config = PolicyConfig.fromId(activePolicyId)
                if (!config.isPassthrough && !bypassModeEnabled) config.bands else emptyList()
            }
            it.filterChain = if (startupBands.isNotEmpty()) {
                BiquadFilterChain(startupBands, sampleRate).also { chain -> chain.reset() }
            } else {
                null
            }

            try {
                it.start()
                Log.i(TAG, "AudioCaptureThread started (rate=$sampleRate, frame=$frameSize)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start capture thread — falling back to stub", e)
                captureThread = null
            }
        }

        return true
    }

    /**
     * Stop the audio engine and release resources.
     */
    fun stopAudioEngine() {
        Log.d(TAG, "stopAudioEngine()")
        isRunning = false
        stopCaptureThread()
        if (useNativePath()) {
            nativeStopEngine()
        }
    }

    /**
     * Get the latest analysis as a typed [TelemetrySnapshot].
     * Preferred over [getAnalysisData] for all new consumers.
     */
    fun getSnapshot(): TelemetrySnapshot = TelemetrySnapshot.fromFloatArray(getAnalysisData())

    /**
     * Get the latest analysis data as a raw float array.
     * Prefer [getSnapshot] — this method is kept for JNI alignment and backward compat.
     *
     * Index layout:
     *  [0] = RMS level (0.0–1.0)
     *  [1] = Spectral Centroid, normalized (0.0–1.0)
     *  [2] = Classification (0=Silence, 1=Speech, 2=Music, 3=Mixed)
     *  [3] = Confidence (0.0–1.0)
     *  [4] = Active policy ID
     *  [5] = Analysis latency estimate (ms), or -1 if not available
     *  [6] = Startup latency (ms), or -1 if not yet measured
     *  [7] = Theoretical I/O latency (ms), or -1 if not set
     */
    fun getAnalysisData(): FloatArray {
        if (useNativePath()) {
            val native = nativeGetAnalysisData()
            // Keep the public contract stable even if native and Kotlin layouts drift.
            if (native.size == EXPECTED_ANALYSIS_VALUES) return native
            return FloatArray(EXPECTED_ANALYSIS_VALUES) { index ->
                native.getOrNull(index) ?: -1f
            }
        }

        // Read from the live capture thread, or fall back to stub values
        val capture = captureThread?.latestResult
        val rms: Float
        val centroid: Float
        val classification: Int
        val confidence: Float
        val captureLatencyMs: Float

        if (capture != null && capture.captureLatencyMs >= 0f) {
            // Real microphone data (including real silence)
            rms = capture.rms
            centroid = capture.spectralCentroid
            classification = capture.classification
            confidence = capture.confidence
            captureLatencyMs = capture.captureLatencyMs
        } else {
            // Stub fallback (no mic permission, or capture not yet started)
            rms = (0.1 + Math.random() * 0.4).toFloat()
            centroid = (0.2 + Math.random() * 0.5).toFloat()
            val stub = FeatureExtractor.classify(rms, centroid)
            classification = stub.first
            confidence = stub.second
            captureLatencyMs = -1f
        }

        // Auto-mode: let the selector pick
        if (autoModeEnabled) {
            val selected = policySelector.select(
                classification = classification,
                confidence = confidence,
                hardwareProfile = currentHardwareProfile,
                currentTimeMs = SystemClock.elapsedRealtime()
            )
            activePolicyId = selected.id
        }

        // Latency tracking
        if (captureLatencyMs > 0f) {
            latencyTracker.record(captureLatencyMs)
        } else {
            // Stub latency estimate based on buffer size
            val estimatedLatencyMs = currentHardwareProfile.suggestedBufferFrames.toFloat() *
                    2f / currentHardwareProfile.suggestedSampleRate * 1000f
            latencyTracker.record(estimatedLatencyMs)
        }

        return floatArrayOf(
            rms,
            centroid,
            classification.toFloat(),
            confidence,
            activePolicyId.toFloat(),
            latencyTracker.getSmoothedLatencyMs(),
            latencyTracker.getStartupLatencyMs(),
            latencyTracker.getTheoreticalLatencyMs()
        )
    }

    /**
     * Set a specific processing policy by ID.
     * Disables auto-mode. Use [setAutoPolicy] to re-enable.
     *
     * @param policyId 0=Passthrough, 1=Speech, 2=Neutral, 3=Conservative
     */
    fun setPolicy(policyId: Int) {
        Log.d(TAG, "setPolicy($policyId)")
        latencyTracker.markPolicySwitchStart()
        autoModeEnabled = false
        activePolicyId = policyId
        policySelector.forcePolicy(policyId)

        // Manual policy change supersedes an AutoEq-applied chain.
        matchedAutoEqBands = emptyList()

        val config = PolicyConfig.fromId(policyId)
        val chainBands = if (!config.isPassthrough && !bypassModeEnabled) {
            config.bands
        } else {
            emptyList()
        }
        val sampleRate = currentHardwareProfile.suggestedSampleRate
        captureThread?.filterChain = if (chainBands.isNotEmpty()) {
            BiquadFilterChain(chainBands, sampleRate).also { it.reset() }
        } else {
            null
        }

        if (useNativePath()) {
            val mappedAutoEq = if (!config.isPassthrough) matchedAutoEqBands else emptyList()
            val payload = if (mappedAutoEq.isNotEmpty()) {
                bandsToFloatArray(mappedAutoEq)
            } else {
                policyConfigToFloatArray(config)
            }
            nativeSetPolicy(policyId, payload)
        }
        latencyTracker.markPolicySwitchEnd()
    }

    /**
     * Enable or disable automatic policy selection based on content classification.
     */
    fun setAutoPolicy(enabled: Boolean) {
        Log.d(TAG, "setAutoPolicy($enabled)")
        autoModeEnabled = enabled
        if (!enabled) {
            policySelector.forcePolicy(activePolicyId)
        }
    }

    /**
     * Set A/B output mode for Kotlin runtime DSP path.
     * true = bypass, false = processed.
     */
    fun setBypassMode(bypass: Boolean) {
        bypassModeEnabled = bypass
        captureThread?.bypassMode = bypass

        if (bypass) {
            captureThread?.filterChain = null
            return
        }

        val sampleRate = currentHardwareProfile.suggestedSampleRate
        val config = PolicyConfig.fromId(activePolicyId)
        val bands = if (matchedAutoEqBands.isNotEmpty()) {
            matchedAutoEqBands
        } else if (!config.isPassthrough) {
            config.bands
        } else {
            emptyList()
        }
        captureThread?.filterChain = if (bands.isNotEmpty()) {
            BiquadFilterChain(bands, sampleRate).also { it.reset() }
        } else {
            null
        }
    }

    /**
     * Get the current estimated stream latency in milliseconds.
     * Returns -1 if no measurement is available yet.
     */
    fun getLatencyMs(): Float {
        if (useNativePath()) {
            val nativeLatency = nativeGetLatencyMs()
            if (nativeLatency > 0f) {
                latencyTracker.record(nativeLatency)
            }
        }
        return latencyTracker.getSmoothedLatencyMs()
    }

    /**
     * Get startup latency: time from engine start to first valid analysis frame.
     * Returns -1 if not yet measured.
     */
    fun getStartupLatencyMs(): Float = latencyTracker.getStartupLatencyMs()

    /**
     * Get a full session latency summary as a map (for CSV / JSON export).
     * Includes: analysis min/max/avg, startup, theoretical I/O, policy switch.
     */
    fun getLatencySummary(): Map<String, String> = latencyTracker.getSessionSummary()

    /**
     * Record a latency sample tagged by app mode (A bypass vs B processed)
     * so session export can report added latency estimates.
     */
    fun recordModeLatencySample(latencyMs: Float, bypassMode: Boolean) {
        latencyTracker.recordModeSample(latencyMs, bypassMode)
    }

    /**
     * Quick health check. Returns a status string.
     */
    fun healthCheck(): String {
        return when {
            useNativePath() -> "Native Engine Ready"
            nativeAvailable -> "Native Engine Loaded (Kotlin Runtime Active)"
            else -> "Engine Stub Ready"
        }
    }

    /**
     * Get the currently active policy configuration.
     */
    fun getActivePolicy(): PolicyConfig = PolicyConfig.fromId(activePolicyId)

    /**
     * Whether the engine is currently running.
     */
    fun isRunning(): Boolean = isRunning

    /**
     * Whether auto-mode is enabled.
     */
    fun isAutoMode(): Boolean = autoModeEnabled

    /**
     * Loads and matches a bundled AutoEq profile by a user/device-provided headphone name.
     * Returns true on match; false means neutral fallback should be used.
     */
    fun loadProfileByName(name: String): Boolean {
        val match = AutoEqProfileMatcher.matchByName(name)
        lastAutoEqMatch = match
        val profile = match.profile
        matchedAutoEqBands = if (profile != null) EqProfileMapper.mapToBiquadBands(profile) else emptyList()

        if (profile == null) {
            Log.i(TAG, "AutoEq no match for '$name' (${match.reason}); neutral fallback")
            return false
        }

        Log.i(
            TAG,
            "AutoEq matched '${profile.id}' source=${match.matchSource} confidence=${"%.2f".format(match.confidence)} bands=${matchedAutoEqBands.size}"
        )
        return true
    }

    /**
     * Applies currently matched AutoEq bands through JNI if native backend is available.
     * Returns false when unavailable or no usable PEAK bands were mapped.
     */
    fun applyMatchedProfile(): Boolean {
        if (matchedAutoEqBands.isEmpty()) return false

        if (!bypassModeEnabled) {
            val sampleRate = currentHardwareProfile.suggestedSampleRate
            captureThread?.filterChain = BiquadFilterChain(matchedAutoEqBands, sampleRate).also { it.reset() }
        }

        if (useNativePath()) {
            nativeSetPolicy(activePolicyId, bandsToFloatArray(matchedAutoEqBands))
        }
        return true
    }

    /** Clears current AutoEq match and returns to policy-defined bands. */
    fun clearMatchedProfile() {
        lastAutoEqMatch = null
        matchedAutoEqBands = emptyList()

        val config = PolicyConfig.fromId(activePolicyId)
        val sampleRate = currentHardwareProfile.suggestedSampleRate
        val bands = if (!config.isPassthrough && !bypassModeEnabled) config.bands else emptyList()
        captureThread?.filterChain = if (bands.isNotEmpty()) {
            BiquadFilterChain(bands, sampleRate).also { it.reset() }
        } else {
            null
        }
    }

    /** Returns conservative AutoEq profile suggestions for UI typeahead. */
    fun suggestProfiles(query: String, limit: Int = 8): List<String> {
        return AutoEqProfileMatcher.suggestByName(query = query, limit = limit)
    }

    /** Lightweight telemetry hook for app/session logging. */
    fun getProfileMatchTelemetry(): Map<String, String> {
        val match = lastAutoEqMatch
        val profile = match?.profile
        return mapOf(
            "AutoEqRequestedName" to (match?.requestedName ?: ""),
            "AutoEqNormalizedName" to (match?.normalizedRequest ?: ""),
            "AutoEqMatchedProfile" to (profile?.id ?: ""),
            "AutoEqMatchedName" to (profile?.name ?: ""),
            "AutoEqMatchSource" to (match?.matchSource ?: "none"),
            "AutoEqMatchConfidence" to (match?.confidence?.let { "%.2f".format(it) } ?: "0.00"),
            "AutoEqFallbackUsed" to (match?.fallbackUsed?.toString() ?: "true"),
            // Generic aliases used by Phase-1 docs and downstream parsing.
            "requested_name" to (match?.requestedName ?: ""),
            "normalized_name" to (match?.normalizedRequest ?: ""),
            "matched_profile_name" to (profile?.name ?: ""),
            "match_type" to (match?.matchSource ?: "none"),
            "fallback_used" to (match?.fallbackUsed?.toString() ?: "true")
        )
    }

    // ========================================================================
    // NATIVE JNI DECLARATIONS — only called when useNativePath() == true
    // TODO(phase2-native): keep callback path allocation-free and free of logging.
    // TODO(phase2-native): restart streams safely on route/Bluetooth changes outside callback.
    // TODO(phase2-native): add a guarded native DSP insertion point without breaking Kotlin path.
    // ========================================================================

    private external fun nativeStartEngine(conservative: Boolean): Boolean
    private external fun nativeStopEngine()
    private external fun nativeGetAnalysisData(): FloatArray
    private external fun nativeSetPolicy(policyId: Int, bands: FloatArray)
    private external fun nativeGetLatencyMs(): Float

    // ========================================================================
    // INTERNAL HELPERS
    // ========================================================================

    /**
     * Flatten a PolicyConfig's bands into a float array for JNI:
     * [freq0, gain0, q0, freq1, gain1, q1, ...]
     */
    internal fun policyConfigToFloatArray(config: PolicyConfig): FloatArray {
        return bandsToFloatArray(config.bands)
    }

    internal fun bandsToFloatArray(
        bands: List<com.winton.validationshell.engine.policy.BiquadBandConfig>
    ): FloatArray {
        val result = FloatArray(bands.size * 3)
        bands.forEachIndexed { i, band ->
            result[i * 3] = band.frequencyHz
            result[i * 3 + 1] = band.gainDb
            result[i * 3 + 2] = band.q
        }
        return result
    }

    /** Stop and release the capture thread if it's running. */
    private fun stopCaptureThread() {
        captureThread?.let {
            it.stopAndJoin()
            Log.d(TAG, "AudioCaptureThread stopped")
        }
        captureThread = null
    }
}
