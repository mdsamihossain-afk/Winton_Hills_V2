package com.winton.validationshell

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.winton.validationshell.ui.theme.MyApplicationTheme
import com.winton.validationshell.engine.Engine
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val engine = Engine()
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    
    // Logging
    private val logger = SessionLogger()
    private var sessionStartTime: Long = 0
    
    // State to track if the engine is intentionally "ON"
    private var isEngineOn by mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission granted
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        // Log Device Info on Launch
        logger.log("APP_START", "Application Started", mapOf(
            "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "AndroidVersion" to Build.VERSION.RELEASE,
            "BatteryLevel" to "${getBatteryLevel()}%"
        ))
        
        // Initial setup for Engine defaults
        engine.setAutoPolicy(true) 

        setContent {
            MyApplicationTheme {
                var analysisData by remember { mutableStateOf<FloatArray?>(null) }
                // -1: Auto, 0: Passthrough, 1: Speech, 2: Neutral
                var selectedPolicy by remember { mutableIntStateOf(-1) }
                // A/B Test State
                var isBMode by remember { mutableStateOf(true) } 

                LaunchedEffect(key1 = isEngineOn) {
                    while (true) {
                        try {
                            if (isEngineOn) {
                                analysisData = engine.getAnalysisData()
                            } else {
                                analysisData = null
                            }
                        } catch (e: Exception) {
                            Log.e("ValidationShell", "Error fetching analysis data", e)
                        }
                        delay(100) 
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ValidationShellUI(
                        analysisData = analysisData,
                        isEngineOn = isEngineOn,
                        selectedPolicy = selectedPolicy,
                        isBMode = isBMode,
                        onToggleEngine = { turnOn ->
                            if (turnOn) {
                                if (checkPermission()) {
                                    startEngineSafe()
                                    isEngineOn = true
                                } else {
                                    requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            } else {
                                stopEngineAndLog()
                                isEngineOn = false
                            }
                        },
                        onPolicySelected = { policyId ->
                            selectedPolicy = policyId
                            if (isBMode) {
                                applyPolicy(policyId)
                            }
                        },
                        onABToggle = { bMode ->
                            isBMode = bMode
                            if (bMode) {
                                applyPolicy(selectedPolicy)
                                logger.log("MODE_CHANGE", "Switched to B (Processed)")
                            } else {
                                // A Mode is Bypass/Passthrough (Policy 0)
                                engine.setAutoPolicy(false)
                                engine.setPolicy(0) 
                                logger.log("MODE_CHANGE", "Switched to A (Bypass/Passthrough)")
                            }
                        },
                        onExportLogs = {
                            logger.createCsv(createCsvLauncher)
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
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
    
    private fun applyPolicy(policyId: Int) {
        val policyName = when(policyId) {
            -1 -> "Auto"
            0 -> "Passthrough"
            1 -> "Speech Intelligibility"
            2 -> "Neutral Correction"
            else -> "Unknown"
        }
        logger.log("POLICY_CHANGE", "Policy set to $policyName ($policyId)")

        if (policyId == -1) {
            engine.setAutoPolicy(true)
        } else {
            engine.setAutoPolicy(false)
            engine.setPolicy(policyId)
        }
    }

    private fun checkPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun startEngineSafe() {
        setupAudio()
        sessionStartTime = System.currentTimeMillis()
        
        val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        var outputRouteName = "Unknown"
        for (device in outputDevices) {
            if (device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) outputRouteName = "Speaker"
            else if (device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || 
                     device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET) outputRouteName = "Headphones"
            else if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || 
                     device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) outputRouteName = "Bluetooth"
        }

        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
        val problematicManufacturers = listOf("SomeBudgetBrand", "AnotherBrand") 
        val isProblematic = problematicManufacturers.any { deviceModel.contains(it, ignoreCase = true) }
        val isBluetooth = outputRouteName == "Bluetooth"
        val conservativeMode = isProblematic || isBluetooth
        
        logger.log("ENGINE_START", "Starting Audio Engine", mapOf(
            "Route" to outputRouteName,
            "ConservativeMode" to conservativeMode.toString(),
            "BatteryStart" to "${getBatteryLevel()}%"
        ))

        try {
            engine.startAudioEngine(conservativeMode)
        } catch (e: Exception) {
            logger.log("ERROR", "Failed to start engine: ${e.message}")
            isEngineOn = false 
        }
    }
    
    private fun stopEngineAndLog() {
        engine.stopAudioEngine()
        val duration = (System.currentTimeMillis() - sessionStartTime) / 1000.0
        logger.log("ENGINE_STOP", "Stopped Audio Engine", mapOf(
            "SessionDurationSeconds" to "%.1f".format(duration),
            "BatteryEnd" to "${getBatteryLevel()}%"
        ))
    }

    private fun setupAudio() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            stopEngineAndLog()
                            isEngineOn = false
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            if(isEngineOn) startEngineSafe()
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> engine.stopAudioEngine()
                    }
                }
                .build()
            audioManager.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus({ }, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    override fun onResume() {
        super.onResume()
        // If we were supposed to be on, try restart, but permission might be needed
        if (isEngineOn && checkPermission()) {
            startEngineSafe()
        }
    }

    override fun onPause() {
        super.onPause()
        // Simple stop on pause (backgrounding)
        engine.stopAudioEngine() 
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest!!)
        }
    }
}

@Composable
fun ValidationShellUI(
    analysisData: FloatArray?,
    isEngineOn: Boolean,
    selectedPolicy: Int,
    isBMode: Boolean,
    onToggleEngine: (Boolean) -> Unit,
    onPolicySelected: (Int) -> Unit,
    onABToggle: (Boolean) -> Unit,
    onExportLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- Header / Mode Indicator ---
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isEngineOn) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isEngineOn) "ENGINE ONLINE" else "ENGINE OFFLINE",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                if (isEngineOn) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isBMode) "Mode: PROCESSED (B)" else "Mode: BYPASS (A)",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isBMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        // --- Analysis Data ---
        AnalysisPanel(analysisData)

        Spacer(modifier = Modifier.weight(1f))

        // --- Controls ---
        
        Text("Processing Policy", style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(8.dp)) {
                PolicyRadioRow("Auto", -1, selectedPolicy, onPolicySelected)
                PolicyRadioRow("Speech Intelligibility", 1, selectedPolicy, onPolicySelected)
                PolicyRadioRow("Neutral Correction", 2, selectedPolicy, onPolicySelected)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = { onABToggle(false) },
                enabled = isEngineOn,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (!isBMode) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (!isBMode) Color.White else Color.Black
                ),
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            ) {
                Text("A (Bypass)")
            }
            Button(
                onClick = { onABToggle(true) },
                enabled = isEngineOn,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isBMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (isBMode) Color.White else Color.Black
                ),
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            ) {
                Text("B (Process)")
            }
        }
        
        Button(
            onClick = onExportLogs,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Export Session Logs")
        }

        Button(
            onClick = { onToggleEngine(!isEngineOn) },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isEngineOn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(
                text = if (isEngineOn) "STOP ENGINE" else "START ENGINE",
                style = MaterialTheme.typography.titleLarge
            )
        }
    }
}

@Composable
fun AnalysisPanel(analysisData: FloatArray?) {
    val rms = analysisData?.getOrNull(0) ?: 0f
    val spectralCentroid = analysisData?.getOrNull(1) ?: 0f
    val classification = when (analysisData?.getOrNull(2)?.toInt()) {
        1 -> "Speech"
        2 -> "Music"
        3 -> "Mixed"
        else -> "Unknown"
    }
    val confidence = analysisData?.getOrNull(3) ?: 0f

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Real-time Analysis", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("RMS Level:")
                Text("%.3f".format(rms), fontWeight = FontWeight.Bold)
            }
            LinearProgressIndicator(
                progress = { rms.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Spectral Centroid:")
                Text("%.3f".format(spectralCentroid), fontWeight = FontWeight.Bold)
            }
             LinearProgressIndicator(
                progress = { spectralCentroid.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.secondary
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            Text("Classification: $classification", fontWeight = FontWeight.Bold)
            Text("Confidence: ${(confidence * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun PolicyRadioRow(text: String, id: Int, selectedId: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = (id == selectedId),
            onClick = { onSelect(id) }
        )
        Text(
            text = text,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun ValidationShellPreview() {
    MyApplicationTheme {
        ValidationShellUI(
            analysisData = floatArrayOf(0.5f, 0.3f, 1f, 0.9f),
            isEngineOn = true,
            selectedPolicy = -1,
            isBMode = true,
            onToggleEngine = {},
            onPolicySelected = {},
            onABToggle = {},
            onExportLogs = {}
        )
    }
}
