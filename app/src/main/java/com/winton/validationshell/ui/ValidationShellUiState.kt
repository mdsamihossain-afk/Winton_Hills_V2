package com.winton.validationshell.ui

import com.winton.validationshell.engine.CaptureSource
import com.winton.validationshell.engine.TelemetrySnapshot

const val AUTO_POLICY_ID = -1

// Single-screen app state for the validation shell.
data class ValidationShellUiState(
    val isServiceConnected: Boolean = false,
    val isEngineOn: Boolean = false,
    val snapshot: TelemetrySnapshot? = null,
    val engineHealth: String = "Connecting to audio service…",
    val captureSource: CaptureSource = CaptureSource.MICROPHONE,
    val isNativeProcessingEnabled: Boolean = false,
    val isNativeCaptureEnabled: Boolean = false,
    val selectedPolicy: Int = AUTO_POLICY_ID,
    val isBMode: Boolean = true,
    val profileInput: String = "",
    val selectedSuggestion: String = "",
    val profileSuggestions: List<String> = emptyList(),
    val showNoSuggestionHint: Boolean = false,
    val profileStatus: String = "No manual profile applied yet",
    val errorMessage: String? = null
) {
    val isLoading: Boolean get() = !isServiceConnected
    val hasTelemetry: Boolean get() = snapshot != null
}

