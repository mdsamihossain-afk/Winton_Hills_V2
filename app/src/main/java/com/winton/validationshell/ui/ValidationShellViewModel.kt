package com.winton.validationshell.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.winton.validationshell.data.AudioEngineRepository
import com.winton.validationshell.engine.CaptureSource
import com.winton.validationshell.engine.TelemetrySnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ValidationShellViewModel(
    private val repository: AudioEngineRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ValidationShellUiState())
    val uiState: StateFlow<ValidationShellUiState> = _uiState.asStateFlow()

    private var suggestionJob: Job? = null

    init {
        viewModelScope.launch {
            val combinedFlow = combine(
                repository.isServiceConnected,
                repository.isEngineOn,
                repository.snapshot,
                repository.engineHealth,
                repository.captureSource,
                repository.isNativeProcessingEnabled,
                repository.isNativeCaptureEnabled
            ) { args ->
                val connected = args[0] as Boolean
                val engineState = Triple(args[1] as Boolean, args[2] as TelemetrySnapshot?, args[3] as String)
                val config = Triple(args[4] as CaptureSource, args[5] as Boolean, args[6] as Boolean)
                Triple(connected, engineState, config)
            }
            
            combinedFlow.collect { (connected, engineState, config) ->
                val (engineOn, snapshot, health) = engineState
                val (source, nativeProc, nativeCapt) = config
                _uiState.update {
                    it.copy(
                        isServiceConnected = connected,
                        isEngineOn = engineOn,
                        snapshot = snapshot,
                        engineHealth = health,
                        captureSource = source,
                        isNativeProcessingEnabled = nativeProc,
                        isNativeCaptureEnabled = nativeCapt,
                        errorMessage = if (connected) null else it.errorMessage
                    )
                }
            }
        }
    }

    fun onPolicySelected(policyId: Int) {
        _uiState.update { it.copy(selectedPolicy = policyId) }
        if (_uiState.value.isBMode) {
            applyPolicy(policyId)
        }
    }

    fun onABModeChanged(isBMode: Boolean) {
        _uiState.update { it.copy(isBMode = isBMode) }
        if (isBMode) {
            repository.setBypassMode(false)
            applyPolicy(_uiState.value.selectedPolicy)
        } else {
            repository.setBypassMode(true)
        }
    }

    fun onCaptureSourceSelected(source: CaptureSource) {
        _uiState.update { it.copy(captureSource = source, errorMessage = null) }
        if (source == CaptureSource.MICROPHONE) {
            repository.setCaptureSource(source)
        }
    }

    fun onNativeProcessingToggled(enabled: Boolean) {
        repository.setNativeProcessingEnabled(enabled)
    }

    fun onNativeCaptureToggled(enabled: Boolean) {
        repository.setNativeCaptureEnabled(enabled)
    }

    fun onProfileInputChanged(text: String) {
        _uiState.update {
            val suggestionSelected = text.equals(it.selectedSuggestion, ignoreCase = true)
            it.copy(
                profileInput = text,
                selectedSuggestion = if (suggestionSelected) it.selectedSuggestion else "",
                profileSuggestions = if (text.isBlank()) emptyList() else it.profileSuggestions,
                showNoSuggestionHint = false,
                errorMessage = null
            )
        }

        suggestionJob?.cancel()
        val query = text.trim()
        if (query.isBlank()) {
            _uiState.update {
                it.copy(
                    profileSuggestions = emptyList(),
                    showNoSuggestionHint = false
                )
            }
            return
        }

        suggestionJob = viewModelScope.launch {
            delay(150L)
            val suggestions = repository.suggestProfiles(query, limit = 6)
            if (_uiState.value.profileInput.trim() == query) {
                _uiState.update {
                    it.copy(
                        profileSuggestions = suggestions,
                        showNoSuggestionHint = suggestions.isEmpty()
                    )
                }
            }
        }
    }

    fun onSuggestionSelected(suggestion: String) {
        _uiState.update {
            it.copy(
                profileInput = suggestion,
                selectedSuggestion = suggestion,
                profileSuggestions = emptyList(),
                showNoSuggestionHint = false,
                errorMessage = null
            )
        }
    }

    fun onApplyProfile() {
        val state = _uiState.value
        val requested = state.profileInput.trim()
        if (requested.isEmpty()) {
            _uiState.update {
                it.copy(
                    profileStatus = "Enter a headphone name first",
                    errorMessage = "Enter a headphone name before applying a profile"
                )
            }
            return
        }

        val matched = repository.loadProfileByName(requested)
        val applied = if (matched && repository.isEngineOn.value) {
            repository.applyMatchedProfile()
        } else {
            false
        }

        val telemetry = repository.getProfileMatchTelemetry()
        val source = telemetry["AutoEqMatchSource"].orEmpty()
        val fallback = telemetry["AutoEqFallbackUsed"].orEmpty()
        val matchedName = telemetry["AutoEqMatchedName"].orEmpty()
        val requestedForUi = requested.ifEmpty { "-" }
        val matchedForUi = if (matchedName.isNotEmpty()) matchedName else "-"
        val profileStatus = if (matched) {
            val appliedText = if (applied) "applied" else "loaded"
            "requested=$requestedForUi\nmatched=$matchedForUi\nmatch_type=$source\nfallback_used=$fallback\nstate=$appliedText"
        } else {
            "requested=$requestedForUi\nmatched=-\nmatch_type=none\nfallback_used=$fallback\nstate=neutral"
        }

        _uiState.update {
            it.copy(
                selectedSuggestion = if (matchedName.isNotBlank()) matchedName else it.selectedSuggestion,
                profileStatus = profileStatus,
                profileSuggestions = emptyList(),
                showNoSuggestionHint = false,
                errorMessage = if (matched) null else "No safe AutoEq match was found"
            )
        }
    }

    fun onError(message: String?) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    private fun applyPolicy(policyId: Int) {
        if (policyId == AUTO_POLICY_ID) {
            repository.setAutoPolicy(true)
        } else {
            repository.setAutoPolicy(false)
            repository.setPolicy(policyId)
        }
    }

    override fun onCleared() {
        suggestionJob?.cancel()
        super.onCleared()
    }

    companion object {
        fun factory(repository: AudioEngineRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(ValidationShellViewModel::class.java)) {
                        return ValidationShellViewModel(repository) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }
}


