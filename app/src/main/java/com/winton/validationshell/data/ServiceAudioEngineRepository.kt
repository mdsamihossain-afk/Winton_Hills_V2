package com.winton.validationshell.data

import android.media.projection.MediaProjection
import com.winton.validationshell.engine.CaptureSource
import com.winton.validationshell.engine.TelemetrySnapshot
import com.winton.validationshell.engine.hardware.HardwareProfile
import com.winton.validationshell.service.AudioEngineService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ServiceAudioEngineRepository : AudioEngineRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _isServiceConnected = MutableStateFlow(false)
    override val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

    private val _isEngineOn = MutableStateFlow(false)
    override val isEngineOn: StateFlow<Boolean> = _isEngineOn.asStateFlow()

    private val _snapshot = MutableStateFlow<TelemetrySnapshot?>(null)
    override val snapshot: StateFlow<TelemetrySnapshot?> = _snapshot.asStateFlow()

    private val _engineHealth = MutableStateFlow("Connecting to audio service…")
    override val engineHealth: StateFlow<String> = _engineHealth.asStateFlow()

    private val _captureSource = MutableStateFlow(CaptureSource.MICROPHONE)
    override val captureSource: StateFlow<CaptureSource> = _captureSource.asStateFlow()

    private val _isNativeProcessingEnabled = MutableStateFlow(false)
    override val isNativeProcessingEnabled: StateFlow<Boolean> = _isNativeProcessingEnabled.asStateFlow()

    private val _isNativeCaptureEnabled = MutableStateFlow(false)
    override val isNativeCaptureEnabled: StateFlow<Boolean> = _isNativeCaptureEnabled.asStateFlow()

    @Volatile
    private var service: AudioEngineService? = null

    private var mirrorJob: Job? = null

    override val currentProfile: HardwareProfile
        get() = service?.currentProfile ?: HardwareProfile.DEFAULT

    override fun attachService(service: AudioEngineService?) {
        mirrorJob?.cancel()
        mirrorJob = null
        this.service = service

        if (service == null) {
            _isServiceConnected.value = false
            _isEngineOn.value = false
            _snapshot.value = null
            _engineHealth.value = "Connecting to audio service…"
            _captureSource.value = CaptureSource.MICROPHONE
            return
        }

        _isServiceConnected.value = true
        mirrorJob = scope.launch {
            launch { service.isEngineOn.collect { _isEngineOn.value = it } }
            launch { service.snapshot.collect { _snapshot.value = it } }
            launch { service.engineHealth.collect { _engineHealth.value = it } }
            launch { service.captureSource.collect { _captureSource.value = it } }
            launch { service.isNativeProcessingEnabled.collect { _isNativeProcessingEnabled.value = it } }
            launch { service.isNativeCaptureEnabled.collect { _isNativeCaptureEnabled.value = it } }
        }
    }

    override fun startEngine() {
        service?.startEngine()
    }

    override fun stopEngine() {
        service?.stopEngine()
    }

    override fun setPolicy(policyId: Int) {
        service?.setPolicy(policyId)
    }

    override fun setAutoPolicy(enabled: Boolean) {
        service?.setAutoPolicy(enabled)
    }

    override fun setBypassMode(bypass: Boolean) {
        service?.setBypassMode(bypass)
    }

    override fun setCaptureSource(source: CaptureSource) {
        service?.setCaptureSource(source)
    }

    override fun setMediaProjection(projection: MediaProjection?) {
        service?.setMediaProjection(projection)
    }

    override fun setNativeProcessingEnabled(enabled: Boolean) {
        service?.setNativeProcessingEnabled(enabled)
    }

    override fun setNativeCaptureEnabled(enabled: Boolean) {
        service?.setNativeCaptureEnabled(enabled)
    }

    override fun loadProfileByName(name: String): Boolean = service?.loadProfileByName(name) ?: false

    override fun applyMatchedProfile(): Boolean = service?.applyMatchedProfile() ?: false

    override fun suggestProfiles(query: String, limit: Int): List<String> =
        service?.suggestProfiles(query, limit) ?: emptyList()

    override fun getLatencyMs(): Float = service?.getLatencyMs() ?: -1f

    override fun getLatencySummary(): Map<String, String> = service?.getLatencySummary() ?: emptyMap()

    override fun getProfileMatchTelemetry(): Map<String, String> =
        service?.getProfileMatchTelemetry() ?: emptyMap()

    override fun close() {
        attachService(null)
        scope.cancel()
        mirrorJob = null
    }
}


