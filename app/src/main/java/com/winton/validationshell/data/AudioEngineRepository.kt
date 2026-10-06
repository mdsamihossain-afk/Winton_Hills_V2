package com.winton.validationshell.data

import android.media.projection.MediaProjection
import com.winton.validationshell.service.AudioEngineService
import com.winton.validationshell.engine.CaptureSource
import com.winton.validationshell.engine.TelemetrySnapshot
import com.winton.validationshell.engine.hardware.HardwareProfile
import kotlinx.coroutines.flow.StateFlow

interface AudioEngineRepository : AutoCloseable {
    val isServiceConnected: StateFlow<Boolean>
    val isEngineOn: StateFlow<Boolean>
    val snapshot: StateFlow<TelemetrySnapshot?>
    val engineHealth: StateFlow<String>
    val captureSource: StateFlow<CaptureSource>
    val isNativeProcessingEnabled: StateFlow<Boolean>
    val isNativeCaptureEnabled: StateFlow<Boolean>

    val currentProfile: HardwareProfile

    fun attachService(service: AudioEngineService?)

    fun startEngine()
    fun stopEngine()

    fun setPolicy(policyId: Int)
    fun setAutoPolicy(enabled: Boolean)
    fun setBypassMode(bypass: Boolean)
    fun setCaptureSource(source: CaptureSource)
    fun setMediaProjection(projection: MediaProjection?)
    fun setNativeProcessingEnabled(enabled: Boolean)
    fun setNativeCaptureEnabled(enabled: Boolean)

    fun loadProfileByName(name: String): Boolean
    fun applyMatchedProfile(): Boolean
    fun suggestProfiles(query: String, limit: Int = 8): List<String>

    fun getLatencyMs(): Float
    fun getLatencySummary(): Map<String, String>
    fun getProfileMatchTelemetry(): Map<String, String>
}

