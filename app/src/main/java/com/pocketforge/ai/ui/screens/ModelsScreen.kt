package com.pocketforge.ai.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketforge.ai.PocketForgeApp
import com.pocketforge.ai.download.DownloadPhase
import com.pocketforge.ai.inference.EngineState
import java.util.Locale

@Composable
fun ModelsScreen() {
    val container = LocalContext.current.applicationContext as PocketForgeApp
    val vm: ModelsViewModel = viewModel(initializer = { ModelsViewModel(container) })
    val state by vm.state.collectAsState()
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { vm.onImportResult(it) } }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) {
        vm.refresh()
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Models",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
        )
        Text(
            "Engine: ${state.engineStateLabel}" + (if (state.loadedModelId != null) " | loaded: ${state.loadedModelId}" else "") +
                (state.currentContextLength?.let { " | ctx $it" } ?: ""),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        state.message?.let { msg ->
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp, 6.dp),
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = vm::dismissMessage) { Text("OK") }
                }
            }
        }

        androidx.compose.material3.OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            singleLine = true,
            label = { Text("Search models") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        val query = searchQuery.trim()
        val visibleCards = if (query.isEmpty()) state.cards else state.cards.filter { c ->
            listOf(c.model.name, c.model.source.repository, c.model.family, c.model.precision, c.model.id)
                .any { it.contains(query, ignoreCase = true) }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(visibleCards, key = { it.model.id }) { card ->
                ModelCard(
                    card = card,
                    isLoadedHere = state.loadedModelId == card.model.id,
                    onDownload = { vm.startDownload(card.model.id) },
                    onCancel = { vm.cancelDownload(card.model.id) },
                    onClear = { vm.clearFailed(card.model.id) },
                    onLoad = { vm.prepareLoad(card.model.id) },
                    onUnload = vm::unloadModel,
                    engineBusy = state.busy,
                    onImport = {
                        importLauncher.launch(arrayOf("*/*"))
                    },
                    onDelete = { confirmDeleteId = card.model.id },
                )
            }
        }
    }

    state.loadAttempt?.let { attempt ->
        val attemptCard = state.cards.firstOrNull { it.model.id == attempt.modelId }
        LoadDialog(
            attempt = attempt,
            steeringStatus = attemptCard?.steeringStatus,
            steeringReasons = attemptCard?.steeringReasons ?: emptyList(),
            steerDefault = state.steeringEnabledDefault,
            onConfirm = vm::confirmLoad,
            onDismiss = vm::cancelLoadDialog,
        )
    }

    confirmDeleteId?.let { id ->
        val card = state.cards.firstOrNull { it.model.id == id }
        AlertDialog(
            onDismissRequest = { confirmDeleteId = null },
            title = { Text("Delete model") },
            text = {
                Text(
                    "Delete ${card?.model?.name ?: id} and its file from this device? " +
                        "You will need to download it again to use it.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteModel(id)
                    confirmDeleteId = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteId = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ModelCard(
    card: ModelCardState,
    isLoadedHere: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    engineBusy: Boolean,
    onImport: () -> Unit,
    onDelete: () -> Unit,
) {
    Card {
        Column(Modifier.padding(14.dp)) {
            Text(card.model.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "${card.model.parameterCount} params | ${card.model.precision} | " +
                    formatSize(card.model.sizeBytes) + " | GGUF",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val minRamEstimate = card.model.sizeBytes * 13 / 10 + 1073741824L
            val fitsThisDevice = card.availableRamBytes <= 0L || minRamEstimate <= card.availableRamBytes
            Text(
                (if (fitsThisDevice) "Fits this device" else "May not fit: needs ~${formatSize(minRamEstimate)} RAM") +
                    (if (card.model.notes.contains("Untested on this build")) " | Untested on this build" else ""),
                style = MaterialTheme.typography.labelSmall,
                color = if (fitsThisDevice) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                card.model.description,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "Repo: ${card.model.source.repository} @ ${card.model.source.revision.take(12)}..." +
                    " | file: ${card.model.source.file}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Licence: ${card.model.license}. ${card.model.attribution}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            card.ggufArchitecture?.let {
                Text(
                    "GGUF: arch=$it, quant=${card.ggufQuantisation}, trained ctx=${card.ggufContextLength}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (card.installed != null && card.steeringStatus != null) {
                val supported = card.steeringStatus == com.pocketforge.ai.data.steering.SteeringTechStatus.SUPPORTED
                Text(
                    when (card.steeringStatus) {
                        com.pocketforge.ai.data.steering.SteeringTechStatus.SUPPORTED ->
                            "Steering: supported (effect unverified)"
                        com.pocketforge.ai.data.steering.SteeringTechStatus.UNSUPPORTED ->
                            "Steering: unsupported - " + (card.steeringReasons.firstOrNull() ?: "unknown reason")
                        else ->
                            "Steering: unknown - " + (card.steeringReasons.firstOrNull() ?: "unknown reason")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (supported) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.padding(4.dp))
            DownloadOrActionsRow(card, isLoadedHere, onDownload, onCancel, onClear, onLoad, onUnload, onDelete)

            if (card.download != null && card.download.phase in setOf(
                    DownloadPhase.RUNNING,
                    DownloadPhase.VERIFYING,
                    DownloadPhase.PAUSED,
                    DownloadPhase.QUEUED,
                ) && card.installed == null
            ) {
                val info = card.download
                LinearProgressIndicator(
                    progress = {
                        if (info.totalBytes > 0) (info.bytesSoFar.toFloat() / info.totalBytes) else 0f
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                Text(
                    text = downloadProgressLine(info),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                info.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }

            // Import row once (on the first card)
            if (card.model.isTestModel) {
                OutlinedButton(onClick = onImport, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Import a GGUF file from device storage")
                }
            }
        }
    }
}

private fun downloadProgressLine(info: com.pocketforge.ai.download.DownloadInfo): String {
    val pct = if (info.totalBytes > 0) {
        String.format(Locale.US, "%d%%", (info.bytesSoFar * 100 / info.totalBytes))
    } else {
        "?"
    }
    val speed = if (info.bytesPerSecond > 0) String.format(Locale.US, " | %.1f MB/s", info.bytesPerSecond / 1048576.0) else ""
    return "${formatSize(info.bytesSoFar)} / ${formatSize(info.totalBytes)} ($pct)$speed | ${info.phase}"
}

@Composable
private fun DownloadOrActionsRow(
    card: ModelCardState,
    isLoadedHere: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onDelete: () -> Unit,
) {
    val phase = card.download?.phase
    val installed = card.installed != null && card.installed!!.sizeBytes > 0
    val downloading = phase in setOf(DownloadPhase.QUEUED, DownloadPhase.RUNNING, DownloadPhase.PAUSED, DownloadPhase.VERIFYING)
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            installed -> {
                if (isLoadedHere) {
                    ElevatedButton(onClick = onUnload) { Text("Unload") }
                } else {
                    ElevatedButton(onClick = onLoad) { Text("Load") }
                }
                SpacerEx()
                TextButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete model")
                    Text("Delete")
                }
            }
            downloading -> {
                OutlinedButton(onClick = onCancel) { Text("Cancel download") }
                if (phase == DownloadPhase.PAUSED) {
                    SpacerEx()
                    Button(onClick = onDownload) { Text("Resume") }
                }
            }
            phase == DownloadPhase.FAILED -> {
                Button(onClick = onDownload) { Text("Retry") }
                TextButton(onClick = onClear) { Text("Clear") }
                card.download?.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f)) }
            }
            else -> {
                Button(onClick = onDownload) { Text(if (phase == DownloadPhase.CANCELLED) "Resume download" else "Download") }
            }
        }
    }
}

@Composable
private fun SpacerEx() {
    Spacer(Modifier.padding(2.dp))
}

private fun formatSize(bytes: Long): String {
    if (bytes < 0) return "missing"
    val gb = bytes / 1073741824.0
    return if (gb >= 0.1) String.format(Locale.US, "%.2f GB", gb) else String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
}

@Composable
private fun LoadDialog(
    attempt: LoadAttempt,
    steeringStatus: com.pocketforge.ai.data.steering.SteeringTechStatus?,
    steeringReasons: List<String>,
    steerDefault: Boolean,
    onConfirm: (contextLength: Int, gpuLayers: Int, steerEnabled: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(2048) }
    var gpuOffload by remember { mutableStateOf(false) }
    var steerOn by remember(steerDefault) { mutableStateOf(steerDefault) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose context length") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected == 2048, onClick = { selected = 2048 })
                    Text(
                        "2048 tokens (recommended); " + (attempt.estimate?.explain(attempt.availableRamBytes) ?: "no estimate"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = selected == 4096,
                        onClick = { if (attempt.context4096Allowed) selected = 4096 },
                        enabled = attempt.context4096Allowed,
                    )
                    Text(
                        if (attempt.context4096Allowed) {
                            "4096 tokens; " + (attempt.estimate4096?.explain(attempt.availableRamBytes) ?: "")
                        } else {
                            "4096 tokens; blocked by the memory safety check with current RAM"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (attempt.context4096Allowed) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                val est = if (selected == 4096) attempt.estimate4096 else attempt.estimate
                est?.let {
                    Text(
                        "Weights ${formatSize(it.weightsBytes)} | KV cache ${formatSize(it.kvBytes)} | " +
                            "compute buffers ~${formatSize(it.computeBytes)} | margin ${formatSize(it.marginBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                ) {
                    Switch(
                        checked = gpuOffload,
                        onCheckedChange = { gpuOffload = it },
                    )
                    Text(
                        "GPU offload (recommended): offload all repeating layers to the engine's GPU backend when one is available.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (steeringStatus == com.pocketforge.ai.data.steering.SteeringTechStatus.SUPPORTED) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    ) {
                        Switch(
                            checked = steerOn,
                            onCheckedChange = { steerOn = it },
                        )
                        Text(
                            "Neuron steering applied at model load; requires model reload to change.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                } else if (steeringStatus != null) {
                    Text(
                        "Steering: unsupported/unknown - " +
                            (steeringReasons.firstOrNull() ?: "no details"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "These are estimates. The model file is memory-mapped; the OS, other apps and thermal " +
                        "throttling also affect real memory use.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selected, if (gpuOffload) 99 else 0, steerOn) }) { Text("Load") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
