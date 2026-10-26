package com.pocketforge.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.pocketforge.ai.data.hub.HfGgufFile
import com.pocketforge.ai.data.steering.SteeringAssessment
import com.pocketforge.ai.data.steering.SteeringTechStatus
import com.pocketforge.ai.download.DownloadInfo
import com.pocketforge.ai.download.DownloadPhase
import java.util.Locale

@Composable
fun HubScreen() {
    val container = LocalContext.current.applicationContext as PocketForgeApp
    val vm: HubViewModel = viewModel(initializer = { HubViewModel(container) })
    val state by vm.state.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Text(
            "Hub",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
        )
        Text(
            "Search the Hugging Face Hub and download community GGUF files over HTTPS; " +
                "files are checksum-verified before install.",
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
        OutlinedTextField(
            value = state.query,
            onValueChange = vm::onQueryChange,
            singleLine = true,
            label = { Text("Search Hugging Face (GGUF)") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        if (state.loading) {
            Row(
                Modifier.padding(16.dp, 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 10.dp))
                Text("Searching the Hub...", style = MaterialTheme.typography.bodySmall)
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.results, key = { it.id }) { hub ->
                HubCard(
                    hub = hub,
                    state = state,
                    onOpenDetail = { vm.openDetail(hub.id) },
                    onDownload = { file -> vm.startDownload(hub.id, file) },
                    onCancel = vm::cancelDownload,
                    onRetry = vm::retryDownload,
                    onClear = vm::clearFailed,
                )
            }
            if (state.loadingMore) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 10.dp))
                        Text("Loading more results...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (state.canLoadMore && !state.loadingMore && state.results.isNotEmpty()) {
                item {
                    TextButton(onClick = vm::loadMore, modifier = Modifier.fillMaxWidth()) {
                        Text("Load more results")
                    }
                }
            }
            if (!state.loading && state.results.isEmpty()) {
                item {
                    Text(
                        "No results yet. Try terms like \"qwen\", \"tiny llama\" or \"gpt-oss\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HubCard(
    hub: HubModelUi,
    state: HubUiState,
    onOpenDetail: () -> Unit,
    onDownload: (HfGgufFile?) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onClear: (String) -> Unit,
) {
    Card {
        Column(Modifier.padding(14.dp)) {
            Text(hub.displayName, style = MaterialTheme.typography.titleMedium)
            Text(hub.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "likes: ${hub.likes} | downloads: ${hub.downloads}" +
                    (if (hub.lastModified.isNotEmpty()) " | updated: ${hub.lastModified.substringBefore('T')}" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (hub.detailLoaded) {
                Text(
                    "arch: ${hub.architecture ?: "?"} | ctx: ${hub.contextLength ?: "?"}" +
                        " | licence: ${hub.license ?: "unlisted"} | GGUF files: ${hub.fileCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                hub.totalGgufBytes?.let {
                    Text(
                        "repo GGUF total: ${formatSize(it)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                hub.steerStatus?.let { steering ->
                    Text(
                        steerLine(steering),
                        style = MaterialTheme.typography.labelSmall,
                        color = steerColor(steering),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            } else {
                Text(
                    "Expand the file list to load size, architecture and steering compatibility.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            state.cardErrors[hub.id]?.let { err ->
                Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            }
            HubActionsRow(hub, state, onDownload, onCancel, onClear)
            var filesOpen by remember { mutableStateOf(false) }
            TextButton(
                onClick = {
                    filesOpen = !filesOpen
                    if (filesOpen && !hub.detailLoaded) onOpenDetail()
                },
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Text(if (filesOpen) "Hide files" else if (hub.detailLoaded) "View ${hub.fileCount} GGUF files" else "View file list")
            }
            if (filesOpen && !hub.detailLoaded) {
                Row(Modifier.padding(8.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 10.dp))
                    Text("Fetching file list...", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (filesOpen && hub.detailLoaded) {
                hub.files.forEach { file ->
                    HubFileRow(
                        file = file,
                        info = state.downloadByTarget["${hub.id}|${file.path}"],
                        onDownload = { onDownload(file) },
                        onCancel = onCancel,
                        onRetry = onRetry,
                        onClear = onClear,
                    )
                }
                Text(
                    "https://huggingface.co/${hub.id}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    "Licence: ${hub.license ?: "unlisted"}. Downloads are verified against the source repository.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun HubActionsRow(
    hub: HubModelUi,
    state: HubUiState,
    onDownload: (HfGgufFile?) -> Unit,
    onCancel: (String) -> Unit,
    onClear: (String) -> Unit,
) {
    val info = hub.bestFile?.let { state.downloadByTarget["${hub.id}|${it.path}"] }
    val installedCount = state.installedByRepo[hub.id]?.size ?: 0
    if (installedCount > 0) {
        Text(
            "In models ($installedCount)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        return
    }
    if (info == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onDownload(null) }) { Text("Download") }
            hub.bestFile?.let { f ->
                Text(
                    "Will fetch ${f.path} (${formatSize(f.sizeBytes)})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    } else if (info.phase == DownloadPhase.DONE) {
        Text(
            "In models",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    } else if (info.phase == DownloadPhase.FAILED) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onDownload(null) }) { Text("Retry") }
            Spacer(Modifier.padding(4.dp))
            TextButton(onClick = { onClear(info.modelId) }) { Text("Clear") }
            Text(
                info.error ?: "Download failed",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onCancel(info.modelId) }) { Text("Cancel download") }
            if (info.phase == DownloadPhase.PAUSED || info.phase == DownloadPhase.CANCELLED) {
                Spacer(Modifier.padding(4.dp))
                Button(onClick = { onDownload(null) }) { Text("Resume") }
            }
        }
        if (info.phase != DownloadPhase.CANCELLED) {
            LinearProgressIndicator(
                progress = {
                    if (info.totalBytes > 0) (info.bytesSoFar.toFloat() / info.totalBytes) else 0f
                },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Text(
                downloadProgressLine(info),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        } else {
            Text(
                downloadProgressLine(info),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        info.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun HubFileRow(
    file: HfGgufFile,
    info: DownloadInfo?,
    onDownload: () -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onClear: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    ) {
        Text(file.path, style = MaterialTheme.typography.labelMedium)
        Text(
            "size: ${formatSize(file.sizeBytes)} | sha256: ${shortSha(file.sha256)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            info == null -> {
                OutlinedButton(onClick = onDownload) { Text("Download") }
            }
            info.phase == DownloadPhase.DONE -> {
                Text("In models", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            info.phase == DownloadPhase.FAILED -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { onRetry(info.modelId) }) { Text("Retry") }
                    Spacer(Modifier.padding(4.dp))
                    TextButton(onClick = { onClear(info.modelId) }) { Text("Clear") }
                    Text(
                        info.error ?: "Download failed",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            else -> {
                // QUEUED, RUNNING, VERIFYING, PAUSED or CANCELLED
                if (info.phase != DownloadPhase.CANCELLED) {
                    LinearProgressIndicator(
                        progress = {
                            if (info.totalBytes > 0) (info.bytesSoFar.toFloat() / info.totalBytes) else 0f
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    Text(
                        downloadProgressLine(info),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (info.phase != DownloadPhase.CANCELLED) {
                        OutlinedButton(onClick = { onCancel(info.modelId) }) { Text("Cancel") }
                        Spacer(Modifier.padding(4.dp))
                    }
                    Button(onClick = onDownload) { Text("Resume") }
                }
            }
        }
    }
}

private fun steerLine(a: SteeringAssessment): String = when (a.techStatus) {
    SteeringTechStatus.SUPPORTED -> "Steering: supported (dims verified after download, effect unverified)"
    SteeringTechStatus.UNSUPPORTED -> "Steering: unsupported - " + (a.reasons.firstOrNull() ?: "no reason given")
    SteeringTechStatus.UNKNOWN -> "Steering: unknown - " + (a.reasons.firstOrNull() ?: "no reason given")
}

@Composable
private fun steerColor(a: SteeringAssessment) = when (a.techStatus) {
    SteeringTechStatus.SUPPORTED -> MaterialTheme.colorScheme.primary
    SteeringTechStatus.UNSUPPORTED -> MaterialTheme.colorScheme.error
    SteeringTechStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun shortSha(sha: String?): String =
    if (sha.isNullOrEmpty()) "not available" else sha.take(12)

private fun downloadProgressLine(info: DownloadInfo): String {
    val pct = if (info.totalBytes > 0) {
        String.format(Locale.US, "%d%%", (info.bytesSoFar * 100 / info.totalBytes))
    } else {
        "?"
    }
    val speed = if (info.bytesPerSecond > 0) {
        String.format(Locale.US, " | %.1f MB/s", info.bytesPerSecond / 1048576.0)
    } else {
        ""
    }
    return "${formatSize(info.bytesSoFar)} / ${formatSize(info.totalBytes)} ($pct)$speed | ${info.phase}"
}

private fun formatSize(bytes: Long): String {
    if (bytes < 0) return "missing"
    val gb = bytes / 1073741824.0
    return if (gb >= 0.1) String.format(Locale.US, "%.2f GB", gb) else String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
}
