package com.pocketforge.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketforge.ai.PocketForgeApp
import com.pocketforge.ai.data.memory.MemoryMode
import com.pocketforge.ai.data.memory.MemorySample
import com.pocketforge.ai.data.memory.ProcessMemoryRow
import com.pocketforge.ai.data.memory.ShizukuStatus
import com.pocketforge.ai.device.HardwareCapabilityDetector
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun PerformanceScreen() {
    val container = LocalContext.current.applicationContext as PocketForgeApp
    val vm: PerformanceViewModel = viewModel(initializer = { PerformanceViewModel(container) })
    val state by vm.state.collectAsState()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text("Device", style = MaterialTheme.typography.titleMedium)
                    val d = state.device
                    Text(
                        if (d != null) {
                            "${d.manufacturer} ${d.model} | Android ${d.androidVersion} | ${d.abi} | ${d.cpuCores} cores"
                        } else {
                            "..."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (d != null) {
                        Text(
                            "RAM total ${fmt(d.totalRamBytes)}, available ${fmt(d.availableRamBytes)}; " +
                                "these numbers change constantly while the OS manages memory.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        state.thermal?.let {
                            Text(
                                "Thermal: $it",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (it.startsWith("Light") || it.startsWith("None")) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                        if (state.lowMemory) {
                            Text("Low memory pressure", color = MaterialTheme.colorScheme.error)
                        }
                        val vk = d.vulkanFeatureVersionRaw
                        Text(
                            "Platform Vulkan feature: " + (
                                vk?.let {
                                    HardwareCapabilityDetector.decodeVulkanVersion(it) + " (platform feature only; not a guarantee the engine can use this GPU)"
                                } ?: "not reported by this Android version"
                                ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(14.dp)) {
                    MemoryPanel(state = state, vm = vm)
                }
            }
        }

        if (state.memoryMode == MemoryMode.FULL && state.shizukuStatus == ShizukuStatus.GRANTED) {
            item {
                Card {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Process list (PSS)", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Sorted by PSS; protected apps have no kill button." +
                                        " Auto-refresh every 10 s while visible.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { vm.refreshProcessListOnce(force = true) }) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Refresh process list")
                            }
                        }
                        Button(
                            onClick = vm::freeMemory,
                            enabled = !state.freeMemoryBusy && state.processRows.any { !it.isProtected },
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text(if (state.freeMemoryBusy) "Freeing memory..." else "Free memory (kills top background processes)")
                        }
                        state.freedBanner?.let { freed ->
                            Text(
                                freed,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            TextButton(onClick = vm::dismissFreedBanner, modifier = Modifier.padding(top = 2.dp)) {
                                Text("Dismiss")
                            }
                        }
                        if (state.processListBusy) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                        }
                        state.processListMessage?.let { msg ->
                            Text(
                                msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            TextButton(onClick = vm::dismissProcessListMessage) { Text("OK") }
                        }
                        if (state.processRows.isNotEmpty()) {
                            Text(
                                "Kill only frees cached processes on Android; aggressive apps come back.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }

            items(state.processRows.size) { index ->
                ProcessMemoryRowItem(state.processRows[index], vm)
            }
        }

        item {
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text("Loaded model", style = MaterialTheme.typography.titleMedium)
                    val r = state.runtimeInfo
                    if (r == null) {
                        Text("No model loaded; load one from the Models tab.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        InfoRow("Description", r.desc)
                        InfoRow("File size", fmtBytes(r.sizeBytes))
                        InfoRow("Parameters", (r.parameterCount / 1_000_000_000.0).let { String.format(Locale.US, "%.2fB", it) })
                        InfoRow("Trained context", r.nCtxTrain.toString())
                        InfoRow("Active context", r.nCtx.toString())
                        InfoRow("Threads", r.nThreads.toString())
                        InfoRow("Requested GPU layers", r.requestedGpuLayers.toString())
                        state.loadDurationMs?.let { InfoRow("Measured load time", "${it} ms") }
                        state.contextStateBytes?.let { InfoRow("Context state size (measured)", fmtBytes(it)) }
                        state.ggufPreview?.let { InfoRow("GGUF preview", it) }
                    }
                    Text(
                        "Native heap allocated: ${fmtBytes(state.nativeHeapAllocated)} (whole process, includes non-model allocations)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text("Last turn (measured)", style = MaterialTheme.typography.titleMedium)
                    val t = state.lastTurn
                    if (t == null) {
                        Text("No generation yet in this session.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        InfoRow("Prompt tokens", t.promptTokens.toString())
                        InfoRow("Prompt processing", "${t.promptMillis} ms (${String.format(Locale.US, "%.1f", t.promptTokensPerSecond)} t/s)")
                        InfoRow("Generated tokens", t.generatedTokens.toString())
                        InfoRow("Generation", "${t.generationMillis} ms (${String.format(Locale.US, "%.1f", t.generationTokensPerSecond)} t/s)")
                    }
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text("GPU / backend verification", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Vulkan on this device does not automatically mean llama.cpp can run on the Mali GPU. " +
                            "The probe below executes a real compute workload through the engine and reports " +
                            "the actual result at every step.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val r = state.runtimeInfo
                    r?.backendDevices?.takeIf { it.isNotEmpty() }?.let { devices ->
                        Text(
                            "Backend devices the engine reports (after init):",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        devices.forEach {
                            Text(
                                "- ${it.type}: ${it.deviceName} (${it.description})",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    Button(
                        onClick = vm::runGpuProbe,
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text(if (state.probeBusy) "Probing..." else "Run GPU probe") }
                    state.probe?.let { probe ->
                        Text(
                            if (probe.overallOk) "GPU workload verified" else "GPU not verified",
                            color = if (probe.overallOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        probe.results.forEach {
                            Text(
                                it.dataType + ": " + if (it.ok) "passed" else "failed",
                                color = if (it.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            if (!it.ok) {
                                Text(
                                    "step=${it.failedStep}: ${it.detail}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        Text(
                            probe.summary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(14.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Engine log tail", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = vm::refreshLogs) { Text("Refresh") }
                    }
                    if (state.logs.isEmpty()) {
                        Text("Press refresh to capture recent native engine logs.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Column {
                            state.logs.forEach {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFFB0BEC5),
                                )
                            }
                        }
                    }
                }
            }
        }

        state.message?.let { msg ->
            item {
                Card {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(msg, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = vm::dismissMessage) { Text("OK") }
                    }
                }
            }
        }
    }

    state.pendingKill?.let { row ->
        KillConfirmDialog(row, vm)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------------------------------------------------------------------
// Memory panel (BASIC + FULL/Shizuku modes)
// ---------------------------------------------------------------------------

private data class MemoryBarSegment(
    val label: String,
    val bytes: Long,
    val fraction: Float,
    val color: Color,
)

@Composable
private fun MemoryPanel(state: PerfUiState, vm: PerformanceViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current

    // Sampling flows are cold: they only run while this tab is in STARTED state.
    LaunchedEffect(state.memoryMode) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { vm.refreshMemory.collect { vm.onMemorySample(it) } }
            if (state.memoryMode == MemoryMode.FULL && state.shizukuStatus == ShizukuStatus.GRANTED) {
                launch { vm.refreshProcessList.collect { vm.onProcessList(it) } }
            }
        }
    }

    MemoryModeBanner(state, vm)

    val sample = state.memory
    if (sample == null) {
        Text(
            "Sampling memory..." + if (state.memoryPaused) " (paused during generation)" else "",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    if (state.memoryPaused) {
        Text(
            "Sampling paused while the engine is generating; values will refresh afterwards.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val segments = barSegments(sample)
    if (segments != null && segments.isNotEmpty()) {
        MemoryStackedBar(segments)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            segments.forEach { seg ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .padding(end = 4.dp)
                            .height(10.dp)
                            .width(10.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(seg.color),
                    )
                    Text(
                        "${seg.label} ${(seg.fraction * 100).let { String.format(Locale.US, "%.0f", it) }}%",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        Text(
            "Stacked on total RAM. \"Available\" and \"Cached\" overlap in kernel accounting; " +
                "percentages are approximate. mmap-backed model files are reclaimable page cache.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val own = sample.own
    if (own != null) {
        Column(Modifier.padding(top = 10.dp)) {
            Text(
                "PocketForge process detail",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(bottom = 2.dp),
            )
            InfoRow("Model weights (file-backed ~ Pss_File)", own.pssFileKb?.let { fmtKb(it) } ?: "n/a")
            InfoRow("KV cache + compute buffers (anon ~ Pss_Anon)", own.pssAnonKb?.let { fmtKb(it) } ?: "n/a")
            InfoRow("Graphics (Graphics PSS)", own.graphicsPssKb?.let { fmtKb(it) } ?: "not reported")
            InfoRow("Other process memory", own.otherDerivedKb?.let { fmtKb(it) } ?: "n/a")
            InfoRow("Total PSS", own.pssKb?.let { fmtKb(it) } ?: "n/a")
            InfoRow("RSS (smaps_rollup)", own.rssKb?.let { fmtKb(it) } ?: "n/a")
            InfoRow("Swapped out", own.swapKb?.let { fmtKb(it) } ?: "n/a")
            InfoRow("Native PSS / Dalvik PSS / Other PSS", "${fmtKb(own.nativePssKb)} / ${fmtKb(own.dalvikPssKb)} / ${fmtKb(own.otherPssKb)}")
            InfoRow("Native heap allocated", fmtBytes(own.nativeHeapAllocatedBytes))
            InfoRow("Native heap free", fmtBytes(own.nativeHeapFreeBytes))
        }
    }

    Column(Modifier.padding(top = 10.dp)) {
        Text(
            "System memory (/proc/meminfo)",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        val mi = sample.meminfo
        @Composable fun row(label: String, kb: Long?) = InfoRow(label, kb?.let { fmtKb(it) } ?: "n/a")
        row("MemTotal", mi?.memTotalKb)
        row("MemAvailable", mi?.memAvailableKb)
        row("MemFree", mi?.memFreeKb)
        row("Cached", mi?.cachedKb)
        row("Buffers", mi?.buffersKb)
        row("AnonPages", mi?.anonPagesKb)
        row("Mapped", mi?.mappedKb)
        row("Shmem", mi?.shmemKb)
        row("SwapTotal", mi?.swapTotalKb)
        row("SwapFree", mi?.swapFreeKb)
        InfoRow(
            "ActivityManager.availMem / totalMem",
            "${fmtBytes(sample.amAvailMemBytes)} / ${fmtBytes(sample.amTotalMemBytes)}",
        )
        InfoRow(
            "ActivityManager threshold / lowMemory",
            "${fmtBytes(sample.amThresholdBytes)} / ${sample.amLowMemory}",
        )
    }
}

@Composable
private fun MemoryModeBanner(state: PerfUiState, vm: PerformanceViewModel) {
    val (container, label) = when (state.memoryMode) {
        MemoryMode.BASIC -> MaterialTheme.colorScheme.surfaceVariant to
            "BASIC mode - system memory and own-process detail; no special access."
        MemoryMode.FULL -> when (state.shizukuStatus) {
            ShizukuStatus.GRANTED -> MaterialTheme.colorScheme.secondaryContainer to
                "FULL mode - Shizuku access granted; can inspect and manage other apps."
            ShizukuStatus.REQUESTING -> MaterialTheme.colorScheme.tertiaryContainer to
                "FULL mode - waiting for the Shizuku permission dialog..."
            ShizukuStatus.UNSUPPORTED -> MaterialTheme.colorScheme.errorContainer to
                "FULL mode - the installed Shizuku app is too old (pre-v11; not supported)."
            ShizukuStatus.NOT_INSTALLED -> MaterialTheme.colorScheme.errorContainer to
                (state.shizukuNotice ?: "FULL mode - Shizuku is not running. Start Shizuku, then retry.")
            ShizukuStatus.NOT_GRANTED -> MaterialTheme.colorScheme.errorContainer to
                (state.shizukuNotice ?: "FULL mode - Shizuku access not granted yet.")
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(container)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
        )
        when {
            state.memoryMode == MemoryMode.BASIC -> {}
            state.shizukuStatus == ShizukuStatus.NOT_GRANTED -> {
                Button(onClick = { vm.setMemoryMode(MemoryMode.FULL) }) { Text("Request access") }
            }
            state.shizukuStatus == ShizukuStatus.NOT_INSTALLED -> {
                Button(onClick = vm::recheckShizuku) { Text("Check again") }
            }
        }
    }
}

private fun barSegments(sample: MemorySample): List<MemoryBarSegment>? {
    val snapshot = sample.meminfo ?: return null
    val totalKb = snapshot.memTotalKb?.takeIf { it > 0 } ?: return null
    val availKb = snapshot.memAvailableKb ?: ((snapshot.memFreeKb ?: 0L) + (snapshot.cachedKb ?: 0L))
    val cachedKb = snapshot.cachedKb ?: 0L
    val ownKb = sample.own?.let { it.pssKb ?: it.rssKb } ?: 0L
    val otherKb = maxOf(0L, totalKb - availKb - cachedKb - ownKb)
    return buildList {
        if (availKb > 0) add(MemoryBarSegment("Available", availKb * 1024, availKb.toFloat() / totalKb, Color(0xFF6BBE66)))
        if (cachedKb > 0) add(MemoryBarSegment("Cached", cachedKb * 1024, cachedKb.toFloat() / totalKb, Color(0xFF5B9BD5)))
        if (ownKb > 0) add(MemoryBarSegment("PocketForge", ownKb * 1024, ownKb.toFloat() / totalKb, Color(0xFF9C5BB0)))
        if (otherKb > 0) add(MemoryBarSegment("Other", otherKb * 1024, otherKb.toFloat() / totalKb, Color(0xFF9A9A9A)))
    }
}

@Composable
private fun MemoryStackedBar(segments: List<MemoryBarSegment>) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(6.dp)),
    ) {
        segments.forEach { segment ->
            Box(
                Modifier
                    .weight(segment.fraction.coerceAtLeast(0.0001f))
                    .fillMaxHeight()
                    .background(segment.color),
            ) {
                if (segment.fraction >= 0.12f) {
                    Text(
                        String.format(Locale.US, "%.0f%%", segment.fraction * 100),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProcessMemoryRowItem(row: ProcessMemoryRow, vm: PerformanceViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .alpha(if (row.isProtected) 0.55f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                row.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    row.pid?.let { "pid $it" } ?: "pid unknown",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    fmtBytes(row.pssBytes) + " PSS",
                    style = MaterialTheme.typography.labelSmall,
                )
                if (row.isForegroundService == true) {
                    Text(
                        "(services)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (row.mayRestart) {
                    Text(
                        "may restart",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (row.isProtected) {
                    Text(
                        "protected",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (!row.isProtected) {
            IconButton(onClick = { vm.requestKill(row) }) {
                Icon(Icons.Filled.Close, contentDescription = "Kill ${row.name}")
            }
        }
    }
}

@Composable
private fun KillConfirmDialog(row: ProcessMemoryRow, vm: PerformanceViewModel) {
    AlertDialog(
        onDismissRequest = vm::dismissKill,
        title = { Text("Kill ${row.name}?") },
        text = {
            Text(
                "pid ${row.pid ?: "unknown"}, ${fmtBytes(row.pssBytes)} PSS.\n\n" +
                    "\"Kill background\" removes cached state only (services keep running).\n" +
                    "\"Force stop\" kills the process outright. Either way the system may " +
                    "restart the app when it is needed again.",
            )
        },
        confirmButton = {
            Row {
                TextButton(onClick = { vm.confirmKill(forceStop = false) }) { Text("Kill background") }
                TextButton(onClick = { vm.confirmKill(forceStop = true) }) {
                    Text("Force stop", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = { TextButton(onClick = vm::dismissKill) { Text("Cancel") } },
    )
}

private fun fmtKb(kb: Long): String = fmtBytes(kb * 1024)

private fun fmt(bytes: Long): String = String.format(Locale.US, "%.2f GiB", bytes / 1073741824.0)
private fun fmtBytes(bytes: Long): String =
    if (bytes > 1073741824) String.format(Locale.US, "%.2f GiB", bytes / 1073741824.0)
    else String.format(Locale.US, "%.1f MiB", bytes / 1048576.0)
