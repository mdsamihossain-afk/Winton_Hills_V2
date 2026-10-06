package com.winton.validationshell.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winton.validationshell.engine.CaptureSource
import com.winton.validationshell.engine.TelemetrySnapshot
import com.winton.validationshell.engine.policy.PolicyConfig

@Composable
fun ValidationShellScreen(
    uiState: ValidationShellUiState,
    onToggleEngine: (Boolean) -> Unit,
    onCaptureSourceSelected: (CaptureSource) -> Unit,
    onPolicySelected: (Int) -> Unit,
    onABToggle: (Boolean) -> Unit,
    onExportLogs: () -> Unit,
    onProfileInputChanged: (String) -> Unit,
    onApplyProfile: () -> Unit,
    onSuggestionSelected: (String) -> Unit,
    onNativeProcessingToggled: (Boolean) -> Unit,
    onNativeCaptureToggled: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (uiState.errorMessage != null) {
                ErrorBanner(uiState.errorMessage)
            }

            if (uiState.isLoading) {
                LoadingBanner()
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (uiState.isEngineOn) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (uiState.isEngineOn) "ENGINE ONLINE" else "ENGINE OFFLINE",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = uiState.engineHealth,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (uiState.isEngineOn) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (uiState.isBMode) "Mode: PROCESSED (B)" else "Mode: BYPASS (A)",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (uiState.isBMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                AnalysisPanel(uiState.snapshot)

                CaptureSourceSection(
                    captureSource = uiState.captureSource,
                    onCaptureSourceSelected = onCaptureSourceSelected
                )

                PolicySection(
                    selectedPolicy = uiState.selectedPolicy,
                    onPolicySelected = onPolicySelected
                )

                NativeCoreSection(
                    processingEnabled = uiState.isNativeProcessingEnabled,
                    captureEnabled = uiState.isNativeCaptureEnabled,
                    onProcessingToggled = onNativeProcessingToggled,
                    onCaptureToggled = onNativeCaptureToggled
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(
                        onClick = { onABToggle(false) },
                        enabled = uiState.isEngineOn,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (!uiState.isBMode) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (!uiState.isBMode) Color.White else Color.Black
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    ) { Text("A (Bypass)") }

                    Button(
                        onClick = { onABToggle(true) },
                        enabled = uiState.isEngineOn,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (uiState.isBMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (uiState.isBMode) Color.White else Color.Black
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp)
                    ) { Text("B (Process)") }
                }

                Text("Headphone Profile", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = uiState.profileInput,
                        onValueChange = onProfileInputChanged,
                        label = { Text("Headphone name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = onApplyProfile) {
                        Text("Apply")
                    }
                }

                if (uiState.profileSuggestions.isNotEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            uiState.profileSuggestions.forEach { suggestion ->
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

                if (uiState.selectedSuggestion.isNotBlank()) {
                    Text(
                        text = "Selected: ${uiState.selectedSuggestion}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("selected_suggestion")
                    )
                }

                if (uiState.showNoSuggestionHint) {
                    Text(
                        text = "No profile suggestions for current query",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Text(
                    text = "Last match: ${uiState.profileStatus}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )

                if (!uiState.hasTelemetry && uiState.isServiceConnected) {
                    EmptyTelemetryBanner()
                }

                Spacer(modifier = Modifier.height(4.dp))
            }

            Button(
                onClick = onExportLogs,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Export Session Logs") }

            Button(
                onClick = { onToggleEngine(!uiState.isEngineOn) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.isEngineOn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = if (uiState.isEngineOn) "STOP ENGINE" else "START ENGINE",
                    style = MaterialTheme.typography.titleLarge
                )
            }
        }
    }
}

@Composable
private fun LoadingBanner() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .testTag("loading_banner"),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(12.dp))
                Text("Connecting to audio service…", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
private fun EmptyTelemetryBanner() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Telemetry waiting", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "The engine is connected, but no analysis frame has arrived yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CaptureSourceSection(
    captureSource: CaptureSource,
    onCaptureSourceSelected: (CaptureSource) -> Unit
) {
    Text("Capture Source", style = MaterialTheme.typography.titleMedium)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = captureSource == CaptureSource.MICROPHONE,
                    onClick = { onCaptureSourceSelected(CaptureSource.MICROPHONE) }
                )
                Text("Microphone", modifier = Modifier.padding(start = 8.dp))

                Spacer(modifier = Modifier.size(16.dp))

                RadioButton(
                    selected = captureSource == CaptureSource.MEDIA_PROJECTION,
                    onClick = { onCaptureSourceSelected(CaptureSource.MEDIA_PROJECTION) }
                )
                Text("System Audio", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun PolicySection(
    selectedPolicy: Int,
    onPolicySelected: (Int) -> Unit
) {
    Text("Processing Policy", style = MaterialTheme.typography.titleMedium)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp)) {
            PolicyRadioRow("Auto", AUTO_POLICY_ID, selectedPolicy, onPolicySelected)
            PolicyRadioRow("Speech Intelligibility", PolicyConfig.SPEECH_INTELLIGIBILITY.id, selectedPolicy, onPolicySelected)
            PolicyRadioRow("Neutral Correction", PolicyConfig.NEUTRAL_CORRECTION.id, selectedPolicy, onPolicySelected)
        }
    }
}

@Composable
fun AnalysisPanel(snapshot: TelemetrySnapshot?) {
    val rms = snapshot?.rms ?: 0f
    val spectralCentroid = snapshot?.spectralCentroid ?: 0f
    val classification = snapshot?.classificationLabel ?: "—"
    val confidence = snapshot?.confidence ?: 0f
    val policyName = snapshot?.activePolicyName ?: "Auto"
    val latencyMs = snapshot?.analysisLatencyMs ?: -1f
    val startupLatencyMs = snapshot?.startupLatencyMs ?: -1f
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

@Composable
private fun NativeCoreSection(
    processingEnabled: Boolean,
    captureEnabled: Boolean,
    onProcessingToggled: (Boolean) -> Unit,
    onCaptureToggled: (Boolean) -> Unit
) {
    Text("Native Engine Core", style = MaterialTheme.typography.titleMedium)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp)) {
            SwitchRow("Native Processing (JNI)", processingEnabled, onProcessingToggled)
            SwitchRow("Native Capture (Oboe)", captureEnabled, onCaptureToggled)
        }
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}


