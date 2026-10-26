package com.pocketforge.ai.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketforge.ai.PocketForgeApp
import com.pocketforge.ai.data.gguf.GgufMetadata
import com.pocketforge.ai.data.gguf.GgufParser
import com.pocketforge.ai.data.memory.DumpsysMeminfoParser
import com.pocketforge.ai.data.memory.MemoryMode
import com.pocketforge.ai.data.memory.MemoryPolicy
import com.pocketforge.ai.data.memory.MemorySample
import com.pocketforge.ai.data.memory.MemorySampler
import com.pocketforge.ai.data.memory.ProcessMemoryRow
import com.pocketforge.ai.data.memory.ShizukuMemoryClient
import com.pocketforge.ai.data.memory.ShizukuStatus
import com.pocketforge.ai.device.DeviceInfo
import com.pocketforge.ai.device.HardwareCapabilityDetector
import com.pocketforge.ai.inference.EngineState
import com.pocketforge.ai.inference.GpuProbeReport
import com.pocketforge.ai.inference.ModelRuntimeInfo
import com.pocketforge.ai.inference.SessionStats
import com.pocketforge.ai.inference.TokenStats
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PerfUiState(
    val device: DeviceInfo? = null,
    val runtimeInfo: ModelRuntimeInfo? = null,
    val loadDurationMs: Long? = null,
    val lastTurn: TokenStats? = null,
    val ggufPreview: String? = null,
    val contextStateBytes: Long? = null,
    val nativeHeapAllocated: Long = 0,
    val lowMemory: Boolean = false,
    val thermal: String? = null,
    val probeBusy: Boolean = false,
    val probe: GpuProbeReport? = null,
    val logs: List<String> = emptyList(),
    val message: String? = null,
    // Memory panel (BASIC + FULL/Shizuku)
    val memory: MemorySample? = null,
    /** True while the engine is generating; sampling pauses and the interval slows down. */
    val memoryPaused: Boolean = false,
    val memoryMode: MemoryMode = MemoryMode.BASIC,
    val shizukuStatus: ShizukuStatus = ShizukuStatus.NOT_INSTALLED,
    val shizukuNotice: String? = null,
    val processRows: List<ProcessMemoryRow> = emptyList(),
    val processListBusy: Boolean = false,
    val processListMessage: String? = null,
    val pendingKill: ProcessMemoryRow? = null,
    val freeMemoryBusy: Boolean = false,
    /** "Freed X MB" result of the last Free-memory pass, shown above the process list. */
    val freedBanner: String? = null,
)

class PerformanceViewModel(private val app: PocketForgeApp) : ViewModel() {

    private val engine = app.engine
    private val memorySampler = MemorySampler(app)
    private val shizuku = ShizukuMemoryClient(app)

    private val _state = MutableStateFlow(PerfUiState())
    val state: StateFlow<PerfUiState> = _state.asStateFlow()

    /**
     * Basic-mode memory sampling. Collected (and therefore driven) only while the tab is
     * visible via repeatOnLifecycle(STARTED). Cadence: 2 s normally, 5 s - with sampling
     * skipped - while the engine is processing a prompt or generating, so this never adds
     * pressure during inference.
     */
    val refreshMemory: Flow<MemorySample> = flow {
        while (true) {
            val generationActive = engine.state.value.let { st ->
                st is EngineState.ProcessingUserPrompt || st is EngineState.Generating
            }
            if (!generationActive) {
                emit(memorySampler.sample())
                delay(2_000)
            } else {
                delay(5_000)
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Full-mode process list. Auto refresh every 10 s while visible; a manual refresh
     * button additionally calls [refreshProcessListOnce].
     */
    val refreshProcessList: Flow<List<ProcessMemoryRow>> = flow {
        while (true) {
            emit(fetchProcessRows())
            delay(10_000)
        }
    }.flowOn(Dispatchers.IO)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                poll()
                delay(1000)
            }
        }
        viewModelScope.launch {
            SessionStats.gpuProbe.collect { report ->
                _state.update { it.copy(probe = report) }
            }
        }
        viewModelScope.launch {
            engine.state.collect { st ->
                _state.update {
                    it.copy(
                        memoryPaused = st is EngineState.ProcessingUserPrompt || st is EngineState.Generating,
                    )
                }
            }
        }
        viewModelScope.launch {
            shizuku.status.collect { status ->
                _state.update { it.copy(shizukuStatus = status) }
                if (status == ShizukuStatus.GRANTED) {
                    _state.update { it.copy(shizukuNotice = null) }
                    if (_state.value.memoryMode == MemoryMode.FULL) refreshProcessListOnce(force = true)
                }
            }
        }
        viewModelScope.launch {
            shizuku.notice.collect { notice ->
                if (notice != null) _state.update { it.copy(shizukuNotice = notice) }
            }
        }
        shizuku.start()
    }

    // Poll caches: the GGUF header parse and the native context-state query are
    // only redone when the loaded model changes, and device facts are refreshed
    // every 5th tick. Previously every 1 s tick re-parsed a GGUF header, walked
    // tokenizer arrays and blocked on the engine dispatcher lane on the main
    // thread.
    private var ggufCacheKey: String? = null
    private var ggufCached: GgufMetadata? = null
    private var ctxStateKey: ModelRuntimeInfo? = null
    private var ctxStateBytes: Long? = null
    private var deviceTick = 0
    private var deviceCached: DeviceInfo? = null

    private fun poll() {
        val device = if (deviceTick % 5 == 0 || deviceCached == null) {
            HardwareCapabilityDetector.detect(app).also { deviceCached = it }
        } else {
            deviceCached!!
        }
        deviceTick++
        val info = engine.runtimeInfo.value
        // Key the metadata cache on the LOADED model (matched by size in the
        // registry), not on registry entry 0.
        val gguf: GgufMetadata? = if (info == null) {
            ggufCacheKey = null
            ggufCached = null
            null
        } else {
            val path = app.modelRegistry.all().firstOrNull { it.sizeBytes == info.sizeBytes }?.path
            if (path != null && path == ggufCacheKey) {
                ggufCached
            } else {
                ggufCacheKey = path
                ggufCached = try {
                    path?.let { GgufParser.parse(File(it)) }
                } catch (_: Throwable) {
                    null
                }
                ggufCached
            }
        }
        val contextState: Long? = when {
            info == null -> {
                ctxStateKey = null
                ctxStateBytes = null
                null
            }
            info == ctxStateKey -> ctxStateBytes
            else -> engine.contextStateSizeBytes().also {
                ctxStateBytes = it
                ctxStateKey = info
            }
        }
        _state.update { s ->
            s.copy(
                device = device,
                runtimeInfo = info,
                loadDurationMs = SessionStats.loadDurationMs.value,
                lastTurn = SessionStats.lastTurn.value,
                ggufPreview = gguf?.let { m ->
                    "arch=${m.architecture}, quant=${m.quantName()}, tensors=${m.tensors.size}"
                },
                contextStateBytes = contextState,
                nativeHeapAllocated = android.os.Debug.getNativeHeapAllocatedSize(),
                lowMemory = device.isLowMemory,
                thermal = device.thermalStatus,
            )
        }
    }

    // ---- Memory: BASIC ----

    fun onMemorySample(sample: MemorySample) {
        _state.update { it.copy(memory = sample) }
    }

    // ---- Memory: FULL (Shizuku) ----

    fun onProcessList(rows: List<ProcessMemoryRow>) {
        _state.update { it.copy(processRows = rows) }
    }

    fun setMemoryMode(mode: MemoryMode) {
        val unchanged = _state.value.memoryMode == mode
        _state.update { it.copy(memoryMode = mode, freedBanner = null, processListMessage = null) }
        if (mode == MemoryMode.FULL) {
            // Request flow: entering FULL mode triggers the Shizuku permission request.
            if (!unchanged || _state.value.shizukuStatus != ShizukuStatus.GRANTED) {
                shizuku.enterFullMode()
            }
        }
    }

    fun recheckShizuku() {
        shizuku.recheck()
        if (_state.value.shizukuStatus == ShizukuStatus.GRANTED) {
            refreshProcessListOnce(force = true)
        }
    }

    fun refreshProcessListOnce(force: Boolean = false) {
        if (!force && _state.value.processListBusy) return
        _state.update { it.copy(processListBusy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val rows = fetchProcessRows()
            _state.update { it.copy(processRows = rows, processListBusy = false) }
        }
    }

    private suspend fun fetchProcessRows(): List<ProcessMemoryRow> {
        if (_state.value.shizukuStatus != ShizukuStatus.GRANTED) return emptyList()
        val text = shizuku.fetchDumpsysMeminfo() ?: return emptyList()
        val extras = MemoryPolicy.extraProtectedPackages(app)
        return MemoryPolicy.annotate(DumpsysMeminfoParser.parse(text), extras)
    }

    fun requestKill(row: ProcessMemoryRow) {
        _state.update { it.copy(pendingKill = row) }
    }

    fun dismissKill() {
        _state.update { it.copy(pendingKill = null) }
    }

    fun confirmKill(forceStop: Boolean) {
        val row = _state.value.pendingKill ?: return
        _state.update { it.copy(pendingKill = null, processListBusy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val error = shizuku.kill(row.name, forceStop)
            _state.update {
                it.copy(
                    processListBusy = false,
                    processListMessage = error
                        ?: "Sent ${if (forceStop) "force-stop" else "kill"} to ${row.name}.",
                )
            }
            refreshProcessListOnce(force = true)
        }
    }

    /**
     * Kills background processes by PSS (top 8, protected apps skipped), re-samples
     * /proc/meminfo and reports the MemAvailable delta in the freed banner.
     */
    fun freeMemory() {
        if (_state.value.freeMemoryBusy) return
        _state.update { it.copy(freeMemoryBusy = true, freedBanner = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val beforeKb = memorySampler.readMeminfo()?.memAvailableKb
            val targets = fetchProcessRows().filter { !it.isProtected }.take(8)
            var kills = 0
            for (target in targets) {
                shizuku.kill(target.name, forceStop = false)
                kills++
                delay(FREE_MEMORY_KILL_DELAY_MS)
            }
            val afterKb = memorySampler.readMeminfo()?.memAvailableKb
            val deltaKb = if (beforeKb != null && afterKb != null) afterKb - beforeKb else null
            _state.update {
                it.copy(
                    freeMemoryBusy = false,
                    freedBanner = when {
                        deltaKb == null -> "Free-memory pass finished, but MemAvailable could not be measured."
                        deltaKb > 0 -> buildString {
                            append("Freed ${deltaKb / 1024L} MB")
                            if (kills > 0) append(" (killed $kills background processes)")
                            append(": MemAvailable ${beforeKb!! / 1024L} -> ${afterKb!! / 1024L} MB.")
                        }
                        else -> "No measurable gain from $kills kills; MemAvailable ${beforeKb!! / 1024L} -> ${afterKb!! / 1024L} MB."
                    },
                )
            }
            refreshProcessListOnce(force = true)
        }
    }

    fun dismissFreedBanner() {
        _state.update { it.copy(freedBanner = null) }
    }

    fun dismissProcessListMessage() {
        _state.update { it.copy(processListMessage = null) }
    }

    // ---- existing diagnostics ----

    fun runGpuProbe() {
        if (_state.value.probeBusy) return
        _state.update { it.copy(probeBusy = true, message = null) }
        viewModelScope.launch {
            try {
                val report = engine.probeGpu()
                SessionStats.onProbe(report)
                _state.update {
                    it.copy(probeBusy = false, probe = report)
                }
            } catch (t: Throwable) {
                _state.update { it.copy(probeBusy = false, message = "GPU probe failed: ${t.message}") }
            }
        }
    }

    fun refreshLogs() {
        _state.update { it.copy(logs = engine.recentLogs().takeLast(80)) }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    override fun onCleared() {
        shizuku.close()
    }

    private companion object {
        const val FREE_MEMORY_KILL_DELAY_MS = 500L
    }
}
