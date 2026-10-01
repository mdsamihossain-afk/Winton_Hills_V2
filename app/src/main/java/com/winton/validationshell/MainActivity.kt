package com.winton.validationshell

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.winton.validationshell.data.ServiceAudioEngineRepository
import com.winton.validationshell.engine.CaptureSource
import com.winton.validationshell.navigation.AppNavGraph
import com.winton.validationshell.service.AudioEngineService
import com.winton.validationshell.ui.ValidationShellViewModel
import com.winton.validationshell.ui.theme.MyApplicationTheme

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {

    private val repository = ServiceAudioEngineRepository()
    private val logger = SessionLogger()
    private var sessionStartTime: Long = 0

    private val viewModel: ValidationShellViewModel by lazy {
        ViewModelProvider(this, ValidationShellViewModel.factory(repository))[ValidationShellViewModel::class.java]
    }

    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as AudioEngineService.LocalBinder).getService()
            repository.attachService(service)
            Log.d(TAG, "Service bound")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            repository.attachService(null)
            Log.d(TAG, "Service unbound")
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startEngineViaService()
        } else {
            viewModel.onError("RECORD_AUDIO permission is required to start the engine")
        }
    }

    private val createCsvLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                logger.writeCsvToUri(this, uri)
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.d(TAG, "POST_NOTIFICATIONS granted=$isGranted")
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            repository.setCaptureSource(CaptureSource.MEDIA_PROJECTION)
            val serviceIntent = Intent(this, AudioEngineService::class.java).apply {
                action = AudioEngineService.ACTION_START_PROJECTION
                putExtra(AudioEngineService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } else {
            viewModel.onError("Media projection permission denied")
            Log.w(TAG, "Media projection permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        logger.log(
            "APP_START",
            "Application Started",
            mapOf(
                "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "AndroidVersion" to Build.VERSION.RELEASE,
                "BatteryLevel" to "${getBatteryLevel()}%"
            )
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        isBound = bindService(Intent(this, AudioEngineService::class.java), serviceConnection, BIND_AUTO_CREATE)
        Log.d(TAG, "bindService returned: $isBound")

        setContent {
            MyApplicationTheme {
                AppNavGraph(
                    uiState = viewModel.uiState.collectAsState().value,
                    onToggleEngine = { turnOn ->
                        if (turnOn) {
                            if (checkPermission()) {
                                startEngineViaService()
                            } else {
                                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        } else {
                            stopEngineViaService()
                        }
                    },
                    onCaptureSourceSelected = { source ->
                        viewModel.onCaptureSourceSelected(source)
                        if (source == CaptureSource.MEDIA_PROJECTION) {
                            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            projectionLauncher.launch(manager.createScreenCaptureIntent())
                        } else {
                            repository.setCaptureSource(CaptureSource.MICROPHONE)
                        }
                    },
                    onPolicySelected = viewModel::onPolicySelected,
                    onABToggle = viewModel::onABModeChanged,
                    onExportLogs = { logger.createCsv(createCsvLauncher) },
                    onProfileInputChanged = viewModel::onProfileInputChanged,
                    onApplyProfile = viewModel::onApplyProfile,
                    onSuggestionSelected = viewModel::onSuggestionSelected,
                    onNativeProcessingToggled = viewModel::onNativeProcessingToggled,
                    onNativeCaptureToggled = viewModel::onNativeCaptureToggled
                )
            }
        }
    }

    private fun startEngineViaService() {
        logger.startSession()
        sessionStartTime = System.currentTimeMillis()

        logger.log("ENGINE_START", "Starting Audio Engine", buildMap {
            put("Route", repository.currentProfile.routeType.name)
            put("ConservativeMode", repository.currentProfile.forceConservative.toString())
            put("BatteryStart", "${getBatteryLevel()}%")
            putAll(repository.getProfileMatchTelemetry())
        })

        repository.startEngine()
    }

    private fun stopEngineViaService() {
        val duration = (System.currentTimeMillis() - sessionStartTime) / 1000.0
        val latencySummary = repository.getLatencySummary()

        logger.log("ENGINE_STOP", "Stopped Audio Engine", buildMap {
            put("SessionDurationSeconds", "%.1f".format(duration))
            put("BatteryEnd", "${getBatteryLevel()}%")
            put("LatencyMs", "%.2f".format(repository.getLatencyMs()))
            putAll(repository.getProfileMatchTelemetry())
            putAll(latencySummary)
        })

        repository.stopEngine()
    }

    private fun checkPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun getBatteryLevel(): Int {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level != -1 && scale != -1) {
            (level * 100 / scale.toFloat()).toInt()
        } else {
            -1
        }
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        repository.close()
        super.onDestroy()
    }
}
