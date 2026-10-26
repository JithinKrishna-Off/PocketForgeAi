package com.pocketforge.ai.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketforge.ai.PocketForgeApp
import com.pocketforge.ai.crypto.ChatCrypto
import com.pocketforge.ai.crypto.PanicWipe
import com.pocketforge.ai.inference.InferenceConfiguration
import com.pocketforge.ai.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val settings: Settings = Settings(),
    val exportBusy: Boolean = false,
    val message: String? = null,
    // privacy/encryption panel state
    val keyStatus: String = "",
    val keyInSecureHardware: Boolean = false,
    val legacyPlaintextRemaining: Boolean = false,
    val wipeQuiet: Boolean = false,
    val wipeAlsoModels: Boolean = false,
    val wipeBusy: Boolean = false,
    val flagSecure: Boolean = true,
    val panicShortcut: Boolean = false,
)

class SettingsViewModel(private val app: PocketForgeApp) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            app.settingsStore.settings.collect { s ->
                _state.update { it.copy(settings = s) }
            }
        }
        refreshPrivacyStatus()
    }

    fun refreshPrivacyStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val info = ChatCrypto.securityLevelInfo(app)
            val legacy = runCatching { app.chatStore.hasLegacyPlaintext() }.getOrDefault(false)
            _state.update {
                it.copy(
                    keyStatus = info.label + (if (legacy) " | legacy plaintext present" else ""),
                    keyInSecureHardware = info.insideSecureHardware,
                    legacyPlaintextRemaining = legacy,
                )
            }
        }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    fun setContextLength(v: Int) = viewModelScope.launch { app.settingsStore.setContextLength(v) }
    fun setMaxTokens(v: Int) = viewModelScope.launch { app.settingsStore.setMaxTokens(v) }
    fun setTemperature(v: Float) = viewModelScope.launch { app.settingsStore.setTemperature(v) }
    fun setTopP(v: Float) = viewModelScope.launch { app.settingsStore.setTopP(v) }
    fun setTopK(v: Int) = viewModelScope.launch { app.settingsStore.setTopK(v) }
    fun setThreads(v: Int) = viewModelScope.launch { app.settingsStore.setThreads(v) }
    fun setSafetyMarginMb(v: Int) = viewModelScope.launch { app.settingsStore.setSafetyMarginMb(v) }
    fun setSystemPrompt(v: String) = viewModelScope.launch { app.settingsStore.setSystemPrompt(v) }
    fun setSteeringEnabled(v: Boolean) = viewModelScope.launch { app.settingsStore.setSteerEnabled(v) }
    fun setSteerSpec(v: String) = viewModelScope.launch { app.settingsStore.setSteerSpec(v) }

    // ---- privacy panel ----
    fun setWipeAlsoModels(v: Boolean) = _state.update { it.copy(wipeAlsoModels = v) }
    fun setFlagSecure(v: Boolean) = _state.update { it.copy(flagSecure = v) }
    fun setPanicShortcut(v: Boolean) = _state.update { it.copy(panicShortcut = v) }

    /**
     * Panic wipe. Runs in NonCancellable; stops generation, unloads the model, deletes
     * the Keystore entry first (crypto-erase), then files/caches/DataStore entries.
     * Caller (UI) restarts the activity afterwards to land on onboarding state.
     */
    fun panicWipe(onDone: (List<String>) -> Unit) {
        if (_state.value.wipeBusy) return
        _state.update { it.copy(wipeBusy = true) }
        viewModelScope.launch {
            val steps = PanicWipe.wipe(app, _state.value.wipeAlsoModels)
            withContext(Dispatchers.Main) {
                _state.update { it.copy(wipeBusy = false, message = "Panic wipe complete: " + steps.size + " steps") }
                onDone(steps)
            }
        }
    }

    /** Exports diagnostics JSON through SAF CreateDocument (no network, device-local). */
    fun exportDiagnostics(uri: android.net.Uri) {
        _state.update { it.copy(exportBusy = true, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val engine = app.engine
                val runtimeJson = runCatching { engine.runtimeInfoJsonOrNull() }.getOrNull().orEmpty()
                val backendsJson = runCatching { engine.backendsJsonOrNull() }.getOrNull().orEmpty()
                val probeJson = runCatching { engine.probeJsonOrNull() }.getOrNull().orEmpty()
                val settings = mapOf(
                    "contextLength" to _state.value.settings.contextLength,
                    "maxTokens" to _state.value.settings.maxTokens,
                    "temperature" to _state.value.settings.temperature,
                    "topP" to _state.value.settings.topP,
                    "topK" to _state.value.settings.topK,
                    "threads" to _state.value.settings.threadCount,
                    "safetyMarginMb" to _state.value.settings.safetyMarginMb,
                )
                val json = app.diagnostics.build(
                    engineRuntimeJson = runtimeJson,
                    backendsJson = backendsJson,
                    gpuProbeJson = probeJson,
                    nativeLogs = runCatching { engine.recentLogs() }.getOrDefault(emptyList()),
                    settings = settings,
                    config = InferenceConfiguration(
                        modelPath = "",
                        nCtx = _state.value.settings.contextLength,
                        // native auto rule: min(6, hw-2) for explicit values; <=0 -> native auto
                        nThreads = _state.value.settings.threadCount.takeIf { it > 0 }
                            ?: maxOf(1, minOf(6, Runtime.getRuntime().availableProcessors() - 2)),
                    ),
                )
                app.contentResolver.openOutputStream(uri)?.use { os ->
                    app.diagnostics.write(os, json)
                } ?: throw java.io.IOException("Cannot write to the selected location")
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(exportBusy = false, message = "Diagnostics exported.") }
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(exportBusy = false, message = "Export failed: ${t.message}") }
                }
            }
        }
    }
}
