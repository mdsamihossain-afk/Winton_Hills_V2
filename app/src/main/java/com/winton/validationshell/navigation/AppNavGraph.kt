package com.winton.validationshell.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.winton.validationshell.engine.CaptureSource
import com.winton.validationshell.ui.ValidationShellScreen
import com.winton.validationshell.ui.ValidationShellUiState

@Composable
fun AppNavGraph(
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
    ValidationShellScreen(
        uiState = uiState,
        onToggleEngine = onToggleEngine,
        onCaptureSourceSelected = onCaptureSourceSelected,
        onPolicySelected = onPolicySelected,
        onABToggle = onABToggle,
        onExportLogs = onExportLogs,
        onProfileInputChanged = onProfileInputChanged,
        onApplyProfile = onApplyProfile,
        onSuggestionSelected = onSuggestionSelected,
        onNativeProcessingToggled = onNativeProcessingToggled,
        onNativeCaptureToggled = onNativeCaptureToggled,
        modifier = modifier
    )
}

