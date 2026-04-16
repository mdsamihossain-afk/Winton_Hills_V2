package com.winton.validationshell

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.winton.validationshell.service.AudioEngineService
import com.winton.validationshell.engine.TelemetrySnapshot
import com.winton.validationshell.engine.policy.PolicyConfig
import com.winton.validationshell.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TAG = "MainActivity"
private const val AUTO_POLICY_ID = -1

class MainActivity : ComponentActivity() {

    private var engineService: AudioEngineService? by mutableStateOf(null)
    private var serviceBound by mutableStateOf(false)

    // Logging
    private val logger = SessionLogger()
    private var sessionStartTime: Long = 0

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            engineService = (binder as AudioEngineService.LocalBinder).getService()
            serviceBound = true
            Log.d("MainActivity", "Service bound")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            engineService = null
            serviceBound = false
            Log.d("MainActivity", "Service unbound")
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startEngineViaService()
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

    // Android 13+ requires runtime POST_NOTIFICATIONS permission for foreground service notification
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.d(TAG, "POST_NOTIFICATIONS granted=$isGranted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        Log.d("MainActivity", "onCreate started")

        // Log Device Info on Launch
        logger.log("APP_START", "Application Started", mapOf(
            "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "AndroidVersion" to Build.VERSION.RELEASE,
            "BatteryLevel" to "${getBatteryLevel()}%"
        ))

        // Request POST_NOTIFICATIONS on Android 13+ so the foreground-service notification shows
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Bind to the engine service (it will promote to foreground when engine starts)
        val serviceIntent = Intent(this, AudioEngineService::class.java)
        val bound = bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)
        Log.d("MainActivity", "bindService returned: $bound")

        setContent {
            MyApplicationTheme {
                val service = engineService
                Log.d("MainActivity", "Recompose: serviceBound=$serviceBound, service=${service != null}")
                if (service != null && serviceBound) {
                    ServiceBoundUI(service)
                } else {
                    LoadingUI()
                }
            }
        }
    }

    @Composable
    private fun ServiceBoundUI(service: AudioEngineService) {
        val isEngineOn by service.isEngineOn.collectAsState()
        val snapshot by service.snapshot.collectAsState()
        @Suppress("ASSIGNED_VALUE_IS_NEVER_READ")
        var selectedPolicy by remember { mutableIntStateOf(AUTO_POLICY_ID) }
        @Suppress("ASSIGNED_VALUE_IS_NEVER_READ")
        var isBMode by remember { mutableStateOf(true) }
        var profileInput by remember { mutableStateOf("") }
        var selectedSuggestion by remember { mutableStateOf("") }
        var lastAutoEqStatus by remember { mutableStateOf("No manual profile applied yet") }
        var profileSuggestions by remember { mutableStateOf<List<String>>(emptyList()) }
        var showNoSuggestionHint by remember { mutableStateOf(false) }

        LaunchedEffect(profileInput) {
            val query = profileInput.trim()
            if (query.isEmpty()) {
                profileSuggestions = emptyList()
                showNoSuggestionHint = false
                return@LaunchedEffect
            }
            delay(150)
            val suggestions = withContext(Dispatchers.Default) {
                service.suggestProfiles(query, limit = 6)
            }
            profileSuggestions = suggestions
            showNoSuggestionHint = suggestions.isEmpty()
        }

        // Log analysis snapshot at ~1 Hz
        LaunchedEffect(isEngineOn) {
            if (isEngineOn) {
                while (true) {
                    snapshot?.let { snap ->
                        logger.appendAnalysisRow(
                            rms            = snap.rms,
                            centroid       = snap.spectralCentroid,
                            classification = snap.classificationId,
                            confidence     = snap.confidence,
                            policyId       = snap.activePolicyId,
                            latencyMs      = snap.analysisLatencyMs,
                            outputRoute    = service.currentProfile.routeType.name
                        )
                    }
                    delay(1000) // 1 Hz logging
                }
            }
        }

        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            ValidationShellUI(
                snapshot = snapshot,
                isEngineOn = isEngineOn,
                selectedPolicy = selectedPolicy,
                isBMode = isBMode,
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
                onPolicySelected = { policyId ->
                    selectedPolicy = policyId
                    if (isBMode) {
                        applyPolicy(policyId)
                    }
                },
                onABToggle = { bMode ->
                    isBMode = bMode
                    if (bMode) {
                        service.setBypassMode(false)
                        applyPolicy(selectedPolicy)
                        logger.log("MODE_CHANGE", "Switched to B (Processed)")
                    } else {
                        service.setBypassMode(true)
                        logger.log("MODE_CHANGE", "Switched to A (Bypass)")
                    }
                },
                onExportLogs = {
                    logger.createCsv(createCsvLauncher)
                },
                profileInput = profileInput,
                onProfileInputChanged = {
                    profileInput = it
                    if (!it.equals(selectedSuggestion, ignoreCase = true)) {
                        selectedSuggestion = ""
                    }
                },
                onApplyProfile = {
                    lastAutoEqStatus = applyManualProfileByName(
                        name = profileInput,
                        selectedSuggestion = selectedSuggestion.ifBlank { null }
                    )
                },
                profileSuggestions = profileSuggestions,
                onSuggestionSelected = { suggestion ->
                    profileInput = suggestion
                    selectedSuggestion = suggestion
                    profileSuggestions = emptyList()
                    showNoSuggestionHint = false
                },
                selectedProfileName = selectedSuggestion,
                showNoSuggestionHint = showNoSuggestionHint,
                profileStatus = lastAutoEqStatus,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }

    @Composable
    private fun LoadingUI() {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    }

    // --- Engine control helpers ---

    private fun startEngineViaService() {
        val service = engineService ?: return
        logger.startSession()
        sessionStartTime = System.currentTimeMillis()

        logger.log("ENGINE_START", "Starting Audio Engine", buildMap {
            put("Route", service.currentProfile.routeType.name)
            put("ConservativeMode", service.currentProfile.forceConservative.toString())
            put("BatteryStart", "${getBatteryLevel()}%")
            putAll(service.getProfileMatchTelemetry())
        })

        service.startEngine()
    }

    private fun stopEngineViaService() {
        val service = engineService ?: return

        val duration = (System.currentTimeMillis() - sessionStartTime) / 1000.0
        val latencySummary = service.getLatencySummary()

        logger.log("ENGINE_STOP", "Stopped Audio Engine", buildMap {
            put("SessionDurationSeconds", "%.1f".format(duration))
            put("BatteryEnd", "${getBatteryLevel()}%")
            put("LatencyMs", "%.2f".format(service.getLatencyMs()))
            putAll(service.getProfileMatchTelemetry())
            putAll(latencySummary)
        })

        service.stopEngine()
    }

    private fun applyPolicy(policyId: Int) {
        val service = engineService ?: return
        val policyName = when (policyId) {
            AUTO_POLICY_ID -> "Auto"
            PolicyConfig.PASSTHROUGH.id -> "Passthrough"
            PolicyConfig.SPEECH_INTELLIGIBILITY.id -> "Speech Intelligibility"
            PolicyConfig.NEUTRAL_CORRECTION.id -> "Neutral Correction"
            else -> "Unknown"
        }
        logger.log("POLICY_CHANGE", "Policy set to $policyName ($policyId)")

        if (policyId == AUTO_POLICY_ID) {
            service.setAutoPolicy(true)
        } else {
            service.setAutoPolicy(false)
            service.setPolicy(policyId)
        }
    }

    private fun applyManualProfileByName(name: String, selectedSuggestion: String? = null): String {
        val service = engineService ?: return "Service unavailable"
        val requested = name.trim()
        if (requested.isEmpty()) {
            logger.log("AUTOEQ_MANUAL_APPLY", "Skipped empty profile input")
            return "Enter a headphone name first"
        }

        val matched = service.loadProfileByName(requested)
        val applied = if (matched && service.isEngineOn.value) {
            service.applyMatchedProfile()
        } else {
            false
        }

        val telemetry = service.getProfileMatchTelemetry()
        val finalMatchType = telemetry["match_type"] ?: telemetry["AutoEqMatchSource"] ?: "none"
        val finalFallback = telemetry["fallback_used"] ?: telemetry["AutoEqFallbackUsed"] ?: "true"

        logger.log("AUTOEQ_MANUAL_APPLY", "Manual profile request", buildMap {
            put("RequestedProfileName", requested)
            put("SelectedSuggestion", selectedSuggestion ?: "")
            put("Matched", matched.toString())
            put("AppliedNow", applied.toString())
            put("FinalMatchType", finalMatchType)
            put("FallbackUsed", finalFallback)
            putAll(telemetry)
        })

        val matchedName = telemetry["AutoEqMatchedName"].orEmpty()
        val source = telemetry["AutoEqMatchSource"].orEmpty()
        val fallback = telemetry["AutoEqFallbackUsed"].orEmpty()

        val requestedForUi = requested.ifEmpty { "-" }
        val matchedForUi = if (matchedName.isNotEmpty()) matchedName else "-"
        return if (matched) {
            val appliedText = if (applied) "applied" else "loaded"
            "requested=$requestedForUi\nmatched=$matchedForUi\nmatch_type=$source\nfallback_used=$fallback\nstate=$appliedText"
        } else {
            "requested=$requestedForUi\nmatched=-\nmatch_type=none\nfallback_used=$fallback\nstate=neutral"
        }
    }

    private fun checkPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
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
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }
    // Note: NO onPause stop — the foreground service keeps running in background
}

// ========================================================================
// UI COMPOSABLES — kept minimal per spec
// ========================================================================

@Composable
fun ValidationShellUI(
    snapshot: TelemetrySnapshot?,
    isEngineOn: Boolean,
    selectedPolicy: Int,
    isBMode: Boolean,
    onToggleEngine: (Boolean) -> Unit,
    onPolicySelected: (Int) -> Unit,
    onABToggle: (Boolean) -> Unit,
    onExportLogs: () -> Unit,
    profileInput: String,
    onProfileInputChanged: (String) -> Unit,
    onApplyProfile: () -> Unit,
    profileSuggestions: List<String>,
    onSuggestionSelected: (String) -> Unit,
    selectedProfileName: String,
    showNoSuggestionHint: Boolean,
    profileStatus: String,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Scrollable content ──────────────────────────────────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- Header / Mode Indicator ---
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isEngineOn) MaterialTheme.colorScheme.primaryContainer
                                     else MaterialTheme.colorScheme.surfaceVariant
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
                            color = if (isBMode) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // --- Analysis Data ---
            AnalysisPanel(snapshot)

            // --- Processing Policy ---
            Text("Processing Policy", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(8.dp)) {
                    PolicyRadioRow("Auto", AUTO_POLICY_ID, selectedPolicy, onPolicySelected)
                    PolicyRadioRow("Speech Intelligibility", PolicyConfig.SPEECH_INTELLIGIBILITY.id, selectedPolicy, onPolicySelected)
                    PolicyRadioRow("Neutral Correction", PolicyConfig.NEUTRAL_CORRECTION.id, selectedPolicy, onPolicySelected)
                }
            }

            // --- A/B Toggle ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = { onABToggle(false) },
                    enabled = isEngineOn,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (!isBMode) MaterialTheme.colorScheme.error
                                         else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (!isBMode) Color.White else Color.Black
                    ),
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                ) { Text("A (Bypass)") }

                Button(
                    onClick = { onABToggle(true) },
                    enabled = isEngineOn,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isBMode) MaterialTheme.colorScheme.primary
                                         else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (isBMode) Color.White else Color.Black
                    ),
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                ) { Text("B (Process)") }
            }

            // --- Manual AutoEq profile lookup ---
            Text("Headphone Profile", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = profileInput,
                    onValueChange = onProfileInputChanged,
                    label = { Text("Headphone name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = onApplyProfile) {
                    Text("Apply")
                }
            }

            if (profileSuggestions.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        profileSuggestions.forEach { suggestion ->
                            TextButton(
                                onClick = { onSuggestionSelected(suggestion) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(text = suggestion, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }

            if (selectedProfileName.isNotBlank()) {
                Text(
                    text = "Selected: $selectedProfileName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (showNoSuggestionHint) {
                Text(
                    text = "No profile suggestions for current query",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Text(
                text = "Last match: $profileStatus",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(4.dp))
        }

        // ── Pinned bottom actions ───────────────────────────────────────────
        Button(
            onClick = onExportLogs,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Export Session Logs") }

        Button(
            onClick = { onToggleEngine(!isEngineOn) },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isEngineOn) MaterialTheme.colorScheme.error
                                 else MaterialTheme.colorScheme.primary
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
fun AnalysisPanel(snapshot: TelemetrySnapshot?) {
    val rms                = snapshot?.rms ?: 0f
    val spectralCentroid   = snapshot?.spectralCentroid ?: 0f
    val classification     = snapshot?.classificationLabel ?: "—"
    val confidence         = snapshot?.confidence ?: 0f
    val policyName         = snapshot?.activePolicyName ?: "Auto"
    val latencyMs          = snapshot?.analysisLatencyMs ?: -1f
    val startupLatencyMs   = snapshot?.startupLatencyMs ?: -1f
    val theoreticalLatencyMs = snapshot?.theoreticalIoLatencyMs ?: -1f

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Real-time Analysis", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))

            AnalysisRow("RMS Level:", "%.3f".format(rms))
            LinearProgressIndicator(
                progress = { rms.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )

            AnalysisRow("Spectral Centroid:", "%.3f".format(spectralCentroid))
            LinearProgressIndicator(
                progress = { spectralCentroid.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.secondary
            )

            Spacer(modifier = Modifier.height(8.dp))
            AnalysisRow("Classification:", classification)
            AnalysisRow("Confidence:", "${(confidence * 100).toInt()}%")
            AnalysisRow("Active Policy:", policyName)

            // --- Latency metrics ---
            Spacer(modifier = Modifier.height(8.dp))
            Text("Latency", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            if (latencyMs >= 0f) {
                AnalysisRow("Analysis:", "%.1f ms".format(latencyMs))
            }
            if (startupLatencyMs >= 0f) {
                AnalysisRow("Startup:", "%.0f ms".format(startupLatencyMs))
            }
            if (theoreticalLatencyMs >= 0f) {
                AnalysisRow("Theoretical I/O:", "%.1f ms".format(theoreticalLatencyMs))
            }
        }
    }
}

@Composable
private fun AnalysisRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Text(value, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun PolicyRadioRow(text: String, id: Int, selectedId: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = (id == selectedId), onClick = { onSelect(id) })
        Text(text = text, modifier = Modifier.padding(start = 8.dp))
    }
}
