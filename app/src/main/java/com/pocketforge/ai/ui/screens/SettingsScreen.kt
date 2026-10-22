package com.pocketforge.ai.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketforge.ai.PocketForgeApp
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun SettingsScreen() {
    val container = LocalContext.current.applicationContext as PocketForgeApp
    val vm: SettingsViewModel = viewModel(initializer = { SettingsViewModel(container) })
    val state by vm.state.collectAsState()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let { vm.exportDiagnostics(it) } }

    var systemPromptText by remember { mutableStateOf(state.settings.systemPrompt) }
    LaunchedEffect(state.settings.systemPrompt) {
        systemPromptText = state.settings.systemPrompt
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }

        item {
            Card { Column(Modifier.padding(14.dp)) {
                Text("Generation", style = MaterialTheme.typography.titleMedium)
                Stepper(
                    label = "Context length: ${state.settings.contextLength} tokens",
                    value = state.settings.contextLength,
                    values = listOf(2048, 4096, 8192),
                    onSelect = { vm.setContextLength(it) },
                    note = "A new context length takes effect the next time a model is loaded. 2048 is conservative and works on most devices; longer contexts need much more memory.",
                )
                Stepper(
                    label = "Max generated tokens: ${state.settings.maxTokens}",
                    value = state.settings.maxTokens,
                    values = listOf(128, 256, 512, 1024, 2048),
                    onSelect = { vm.setMaxTokens(it) },
                )
                SliderRow("Temperature: ${"%.2f".format(state.settings.temperature)}", state.settings.temperature, 0f..2f, 0.05f, vm::setTemperature)
                SliderRow("top_p: ${"%.2f".format(state.settings.topP)}", state.settings.topP, 0.05f..1f, 0.05f, vm::setTopP)
                Stepper(
                    label = "top_k: ${state.settings.topK}" + if (state.settings.topK == 0) " (off)" else "",
                    value = state.settings.topK,
                    values = listOf(0, 20, 40, 60, 80, 100),
                    onSelect = { vm.setTopK(it) },
                )
                Stepper(
                    label = "CPU threads: " + (state.settings.threadCount.takeIf { it > 0 } ?: "auto (4)"),
                    value = state.settings.threadCount,
                    values = listOf(0, 2, 4, 6, 8),
                    display = { if (it == 0) "auto (4)" else it.toString() },
                    onSelect = { vm.setThreads(it) },
                )
            } }
        }

        item {
            Card { Column(Modifier.padding(14.dp)) {
                Text("System prompt", style = MaterialTheme.typography.titleMedium)
                androidx.compose.material3.OutlinedTextField(
                    value = systemPromptText,
                    onValueChange = { systemPromptText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 6,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { vm.setSystemPrompt(systemPromptText) }) { Text("Apply") }
                }
            } }
        }

        item {
            Card { Column(Modifier.padding(14.dp)) {
                Text("Memory safety", style = MaterialTheme.typography.titleMedium)
                Stepper(
                    label = "Safety margin: ${state.settings.safetyMarginMb} MB kept free besides the estimate",
                    value = state.settings.safetyMarginMb,
                    values = listOf(512, 1024, 1536, 2048),
                    display = { "${it} MB" },
                    onSelect = { vm.setSafetyMarginMb(it) },
                    note = "Takes effect when a model is loaded. Higher margin means fewer risky loads under Android memory pressure.",
                )
            } }
        }

        item {
            Card { Column(Modifier.padding(14.dp)) {
                Text("Storage", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Models are stored in the app-private external folder (Android/data/<package>/files/models): " +
                        "no storage permission is needed. Delete models from the Models tab to free space. " +
                        "Imported files are copied into app storage.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } }
        }

        item {
            Card { Column(Modifier.padding(14.dp)) {
                Text("Privacy", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Chat is sent to the model on this device only. Nothing is uploaded. Network access is used only " +
                        "for model downloads from the configured Hugging Face repository and never carries prompts or replies. " +
                        "Conversations stay in app-private storage and can be deleted from the Chat tab.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } }
        }

        item {
            PrivacyCard(state, vm)
        }

        item {
            Card { Column(Modifier.padding(14.dp)) {
                Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Exports a JSON report (device facts, backend report, GPU probe result, engine logs) to a file of your choice.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { exportLauncher.launch("pocketforge-diagnostics.json") },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text(if (state.exportBusy) "Exporting..." else "Export diagnostics") }
            } }
        }

        item {
            Card { Column(Modifier.padding(14.dp)) {
                Text("About & licences", style = MaterialTheme.typography.titleMedium)
                Text(
                    "PocketForge AI - local inference with llama.cpp (MIT). Qwen2.5 models by Alibaba Cloud, Apache 2.0; " +
                        "GGUF quantization of the 7B model by bartowski. Detailed licences: see README.md and docs/THIRD_PARTY.md.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Chat at rest is encrypted with AES-256-GCM under an AndroidKeyStore-wrapped key. " +
                        "Third-party notices: kotlin/coroutines/compose (Apache 2.0), MoPub-free; " +
                        "the same protection class as SQLCipher would provide for a SQL store (none is used by this app).",
                    style = MaterialTheme.typography.bodySmall,
                )
                state.message?.let { msg ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = vm::dismissMessage) { Text("OK") }
                    }
                }
            } }
        }
    }

    state.message?.let { msg ->
        Card { Column(Modifier.padding(14.dp)) {
            Text(msg, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = vm::dismissMessage) { Text("OK") }
        } }
    }
}

@Composable
private fun Stepper(
    label: String,
    value: Int,
    values: List<Int>,
    display: (Int) -> String = { it.toString() },
    onSelect: (Int) -> Unit,
    note: String? = null,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            values.forEach { v ->
                TextButton(
                    onClick = { onSelect(v) },
                ) {
                    Text(
                        display(v),
                        color = if (value == v) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        note?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedRange<Float>,
    step: Float,
    onSet: (Float) -> Unit,
) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        var sliderValue by remember(value) { mutableStateOf(value) }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            valueRange = range.start..range.endInclusive,
            steps = ((range.endInclusive - range.start) / step - 1).toInt(),
            onValueChangeFinished = { onSet(sliderValue) },
        )
    }
}

@Composable
private fun PrivacyCard(state: SettingsUiState, vm: SettingsViewModel) {
    val context = LocalContext.current
    val activity = context as? androidx.activity.ComponentActivity
    Card {
        Column(Modifier.padding(14.dp)) {
            Text("Privacy & encryption", style = MaterialTheme.typography.titleMedium)
            Text(
                "Threat model: protects chat data at rest if the phone is lost, imaged, or app data is extracted " +
                    "while the app is closed. Does NOT protect against root malware while the app is running, or " +
                    "someone using the unlocked phone with the app open.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Encryption key location: " + state.keyStatus.ifBlank { "initializing" } +
                    " | chats + persona encrypted AES-256-GCM",
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.legacyPlaintextRemaining) {
                Text("Legacy plaintext chats present - new turn migration will finish on next start",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.flagSecure, onCheckedChange = vm::setFlagSecure)
                Text("Block screenshots and recents thumbnail in chat (FLAG_SECURE)")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.wipeAlsoModels, onCheckedChange = vm::setWipeAlsoModels)
                Text("Panic wipe also deletes downloaded model files")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.panicShortcut, onCheckedChange = vm::setPanicShortcut)
                Text("Add panic-wipe launcher shortcut (hold-to-confirm in Settings)")
            }

            Text(
                "Panic wipe stops generation, unloads the model, deletes the AndroidKeyStore entries " +
                    "(instant crypto-erase of all chats), then the chat files, persona, caches and chat settings, " +
                    "and restarts the app to the first-run state. There is no forensic storage overwrite; " +
                    "the crypto-erase itself is the guarantee.",
                style = MaterialTheme.typography.bodySmall,
            )
            val armed = remember { mutableStateOf(false) }
            Button(
                enabled = !state.wipeBusy,
                onClick = { },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (armed.value) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (armed.value) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier
                    .padding(top = 6.dp)
                    .pointerInput(state.wipeBusy) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            armed.value = true
                            val start = System.currentTimeMillis()
                            var completed = false
                            while (true) {
                                val event = withTimeoutOrNull(1200L - (System.currentTimeMillis() - start)) {
                                    awaitPointerEvent()
                                }
                                if (event == null) { completed = true; break }
                                if (event.changes.none { it.pressed }) break
                            }
                            armed.value = false
                            if (completed) {
                                vm.panicWipe {
                                    val i = context.packageManager.getLaunchIntentForPackage(context.packageName)
                                    i?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                    if (i != null) context.startActivity(i)
                                    activity?.finishAffinity()
                                }
                            }
                        }
                    },
            ) {
                Text(if (state.wipeBusy) "Wiping..." else if (armed.value) "Keep holding..." else "Hold to wipe chat data")
            }
            Text(
                "Notification cleanup and restart are best effort; the Keystore deletion happens before file deletion.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
