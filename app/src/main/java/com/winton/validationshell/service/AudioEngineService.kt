package com.winton.validationshell.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.winton.validationshell.MainActivity
import com.winton.validationshell.engine.CaptureSource
import com.winton.validationshell.engine.Engine
import com.winton.validationshell.engine.TelemetrySnapshot
import com.winton.validationshell.engine.hardware.HardwareProfile
import com.winton.validationshell.engine.policy.PolicyConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground service that owns the [Engine] lifecycle.
 * Handles audio focus, notification, and background persistence.
 *
 * Lifecycle:
 *   1. Activity calls bindService() — service created, binder returned immediately.
 *   2. User taps START → Activity calls startEngine() → service promotes to foreground.
 *   3. User taps STOP  → Activity calls stopEngine() → service drops foreground.
 *
 * This avoids the API 34+ crash where startForeground(mediaPlayback) requires
 * RECORD_AUDIO to already be granted.
 */
class AudioEngineService : Service() {

    companion object {
        private const val TAG = "AudioEngineService"
        private const val CHANNEL_ID = "audio_engine_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.winton.validationshell.STOP_ENGINE"
        const val ACTION_START_PROJECTION = "com.winton.validationshell.START_PROJECTION"
        const val EXTRA_RESULT_DATA = "extra_result_data"
    }

    // --- Engine ---
    private val engine = Engine()

    // --- State exposed to bound clients ---
    private val _analysisData = MutableStateFlow<FloatArray?>(null)
    val analysisData: StateFlow<FloatArray?> = _analysisData.asStateFlow()

    private val _snapshot = MutableStateFlow<TelemetrySnapshot?>(null)
    val snapshot: StateFlow<TelemetrySnapshot?> = _snapshot.asStateFlow()

    private val _isEngineOn = MutableStateFlow(false)
    val isEngineOn: StateFlow<Boolean> = _isEngineOn.asStateFlow()

    private val _engineHealth = MutableStateFlow("Initializing...")
    val engineHealth: StateFlow<String> = _engineHealth.asStateFlow()

    private val _captureSource = MutableStateFlow(CaptureSource.MICROPHONE)
    val captureSource: StateFlow<CaptureSource> = _captureSource.asStateFlow()

    private val _activePolicy = MutableStateFlow(PolicyConfig.PASSTHROUGH)

    // --- Audio focus ---
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private val serviceHandler = Handler(Looper.getMainLooper())

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.w(TAG, "Audio focus lost — stopping engine")
                stopEngine()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.i(TAG, "Audio focus lost transiently — ducking to 0.1")
                engine.setGain(0.1f)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.i(TAG, "Audio focus transient can duck — ducking to 0.2")
                engine.setGain(0.2f)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus gained — restoring gain")
                engine.setGain(1.0f)
            }
        }
    }

    // --- Route change detection ---
    private var deviceCallback: AudioDeviceCallback? = null
    private var routeRestartInProgress = false
    private var lastRouteRestartMs: Long = 0L
    private val routeRestartCooldownMs: Long = 1000L

    // --- Coroutine scope for analysis polling ---
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollingJob: Job? = null

    // --- Hardware ---
    var currentProfile: HardwareProfile = HardwareProfile.DEFAULT
        private set

    @Volatile
    private var isBypassMode: Boolean = false

    // --- Binder ---
    inner class LocalBinder : Binder() {
        fun getService(): AudioEngineService = this@AudioEngineService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "onBind")
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        engine.initWithContext(this)
        createNotificationChannel()
        registerRouteChangeCallback()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEngine()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_PROJECTION -> {
                Log.d(TAG, "ACTION_START_PROJECTION received")
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                promoteToForegroundWithProjection(resultData)
            }
        }
        // Don't call startForeground here for general starts — we do it in startEngine() 
        // or promoteToForegroundWithProjection().
        return START_STICKY
    }

    private fun promoteToForegroundWithProjection(resultData: Intent? = null) {
        try {
            val notifText = "System Audio Capture Active"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(notifText),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(notifText))
            }

            // Now that we are definitely foreground, create the projection if data was provided
            if (resultData != null) {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                val projection = mpm.getMediaProjection(android.app.Activity.RESULT_OK, resultData)
                setMediaProjection(projection)
                setCaptureSource(CaptureSource.MEDIA_PROJECTION)
                
                // If the engine should start automatically when projection is granted:
                if (!_isEngineOn.value) {
                    startEngine()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground for projection", e)
        }
    }

    override fun onDestroy() {
        stopEngine()
        unregisterRouteChangeCallback()
        serviceScope.cancel()
        Log.d(TAG, "Service destroyed")
        super.onDestroy()
    }

    // ========================================================================
    // PUBLIC CONTROL METHODS — called by Activity via binder
    // ========================================================================

    fun startEngine() {
        if (_isEngineOn.value) return

        val am = audioManager ?: return

        // Resolve hardware
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        currentProfile = engine.resolveHardware(devices)
        _captureSource.value = engine.getCaptureSource()

        // Try conservative AutoEq lookup by output-device label; no match stays neutral.
        val requestedProfileName = preferredOutputName(devices)
        if (!requestedProfileName.isNullOrBlank()) {
            engine.loadProfileByName(requestedProfileName)
            Log.d(TAG, "AutoEq telemetry: ${engine.getProfileMatchTelemetry()}")
        }

        // Request audio focus
        requestAudioFocus()

        // Default engine start in processed mode; UI can switch to bypass via setBypassMode(true).
        isBypassMode = false

        // Start engine
        try {
            val success = engine.startAudioEngine(currentProfile.forceConservative)
            if (success) {
                _isEngineOn.value = true
                startPolling()

                // Promote to foreground: must startService first, then startForeground
                try {
                    val fgIntent = Intent(this, AudioEngineService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(fgIntent)
                    } else {
                        startService(fgIntent)
                    }
                    val notifText = "Engine Running · ${currentProfile.routeType.name}"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        // FIX: Include MEDIA_PROJECTION type if active to prevent Android 14 crash
                        var fgTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                                     ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        
                        if (_captureSource.value == CaptureSource.MEDIA_PROJECTION) {
                            fgTypes = fgTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        }

                        startForeground(
                            NOTIFICATION_ID,
                            buildNotification(notifText),
                            fgTypes
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, buildNotification(notifText))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not go foreground (notification permission?)", e)
                }

                Log.i(TAG, "Engine started, profile=$currentProfile")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start engine", e)
        }
    }

    fun stopEngine() {
        if (!_isEngineOn.value) return

        pollingJob?.cancel()
        pollingJob = null
        engine.stopAudioEngine()
        abandonAudioFocus()
        _isEngineOn.value = false
        _analysisData.value = null
        _snapshot.value = null

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground failed", e)
        }

        Log.i(TAG, "Engine stopped")
    }

    fun setPolicy(policyId: Int) {
        engine.setPolicy(policyId)
        isBypassMode = (policyId == PolicyConfig.PASSTHROUGH.id)
        _activePolicy.value = engine.getActivePolicy()
    }

    fun setAutoPolicy(enabled: Boolean) {
        engine.setAutoPolicy(enabled)
    }

    fun setBypassMode(bypass: Boolean) {
        isBypassMode = bypass
        engine.setBypassMode(bypass)
        if (bypass) {
            engine.setAutoPolicy(false)
            engine.setPolicy(PolicyConfig.PASSTHROUGH.id)
        }
    }

    fun getLatencyMs(): Float = engine.getLatencyMs()

    fun getLatencySummary(): Map<String, String> = engine.getLatencySummary()

    fun getProfileMatchTelemetry(): Map<String, String> = engine.getProfileMatchTelemetry()

    fun loadProfileByName(name: String): Boolean = engine.loadProfileByName(name)

    fun applyMatchedProfile(): Boolean = engine.applyMatchedProfile()

    fun suggestProfiles(query: String, limit: Int = 8): List<String> = engine.suggestProfiles(query, limit)

    fun setCaptureSource(source: CaptureSource) {
        engine.setCaptureSource(source)
        _captureSource.value = source
    }

    fun setMediaProjection(projection: MediaProjection?) {
        engine.setMediaProjection(projection)
    }

    fun getCaptureSource(): CaptureSource = engine.getCaptureSource()

    // ========================================================================
    // ANALYSIS POLLING
    // ========================================================================

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = serviceScope.launch {
            while (isActive) {
                try {
                    // Update health status every poll cycle
                    _engineHealth.value = engine.healthCheck(this@AudioEngineService)

                    if (_isEngineOn.value) {
                        val data = engine.getAnalysisData()
                        val snap = TelemetrySnapshot.fromFloatArray(data)
                        if (snap.analysisLatencyMs >= 0f) {
                            engine.recordModeLatencySample(snap.analysisLatencyMs, isBypassMode)
                        }
                        _analysisData.value = data   // backward compat
                        _snapshot.value = snap
                        _activePolicy.value = engine.getActivePolicy()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Analysis polling error", e)
                }
                delay(100) // 10 Hz polling
            }
        }
    }

    // ========================================================================
    // AUDIO FOCUS
    // ========================================================================

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(focusChangeListener, serviceHandler)
                .build()
            am.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(focusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            audioManager?.abandonAudioFocusRequest(focusRequest!!)
            focusRequest = null
        }
    }

    // ========================================================================
    // ROUTE CHANGE DETECTION
    // ========================================================================

    private fun registerRouteChangeCallback() {
        val am = audioManager ?: return
        deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                onRouteChanged("Device added")
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                onRouteChanged("Device removed")
            }
        }
        am.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        Log.d(TAG, "Registered audio device callback")
    }

    private fun unregisterRouteChangeCallback() {
        val am = audioManager ?: return
        if (deviceCallback != null) {
            am.unregisterAudioDeviceCallback(deviceCallback)
            deviceCallback = null
        }
    }

    private fun onRouteChanged(reason: String) {
        val am = audioManager ?: return
        val now = SystemClock.elapsedRealtime()
        if (routeRestartInProgress) {
            Log.d(TAG, "Route change ignored (restart in progress)")
            return
        }
        if (now - lastRouteRestartMs < routeRestartCooldownMs) {
            Log.d(TAG, "Route change ignored (cooldown active)")
            return
        }

        try {
            val oldRoute = currentProfile.routeType
            val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            currentProfile = engine.resolveHardware(devices)

            val requestedProfileName = preferredOutputName(devices)
            if (!requestedProfileName.isNullOrBlank()) {
                engine.loadProfileByName(requestedProfileName)
                Log.d(TAG, "AutoEq telemetry: ${engine.getProfileMatchTelemetry()}")
            }

            val newRoute = currentProfile.routeType

            if (oldRoute != newRoute) {
                Log.i(TAG, "Route changed ($reason): $oldRoute → $newRoute")

                if (_isEngineOn.value) {
                    // If new route demands conservative mode, apply it
                    if (currentProfile.forceConservative) {
                        engine.setPolicy(PolicyConfig.CONSERVATIVE.id)
                        _activePolicy.value = PolicyConfig.CONSERVATIVE
                        Log.i(TAG, "Forced conservative mode for $newRoute")
                    }

                    // Restart the engine internals to pick up new sample rate / buffer size
                    routeRestartInProgress = true
                    try {
                        engine.stopAudioEngine()
                        engine.startAudioEngine(currentProfile.forceConservative)
                        lastRouteRestartMs = SystemClock.elapsedRealtime()
                        Log.i(TAG, "Restarted engine for new route $newRoute")
                    } finally {
                        routeRestartInProgress = false
                    }

                    // Update notification to show current route
                    updateNotification("Engine Running · ${newRoute.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Critical failure in onRouteChanged (System service crash?)", e)
            _engineHealth.value = "System Error: ${e.message}"
        }
    }

    private fun preferredOutputName(devices: Array<AudioDeviceInfo>): String? {
        val product = devices.firstOrNull { it.isSink }?.productName?.toString()?.trim()
        if (!product.isNullOrEmpty()) return product
        return "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    }

    /** Update the ongoing notification text without recreating the channel. */
    private fun updateNotification(statusText: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(statusText))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification", e)
        }
    }

    // ========================================================================
    // NOTIFICATIONS
    // ========================================================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Engine",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Winton Hills audio engine processing"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, AudioEngineService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Winton Audio Engine")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
