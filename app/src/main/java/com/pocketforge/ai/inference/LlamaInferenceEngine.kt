package com.pocketforge.ai.inference

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-model inference engine. One loaded model = one session; the engine serializes
 * all native calls on a single-threaded dispatcher and refuses to load a second model
 * while one is resident (native memory pressure guard).
 *
 * Streaming contract: [send] returns a cold Flow; collection starts a turn. The turn
 * ends via [StreamEvent.Done] (EOG / stop sequence / max tokens / cancel), or
 * [StreamEvent.Error]. Kotlin owns the accumulated turn text and records it natively
 * via [LlamaBridge.finishTurn] exactly once per turn.
 */
interface InferenceEngine {
    val state: StateFlow<EngineState>
    val runtimeInfo: StateFlow<ModelRuntimeInfo?>

    suspend fun loadModel(configuration: InferenceConfiguration)
    suspend fun setSystemPrompt(prompt: String)
    fun send(message: String, maxTokens: Int, stopSequences: List<String> = emptyList()): Flow<StreamEvent>
    fun cancel()
    suspend fun unload()
    suspend fun probeGpu(): GpuProbeReport
}

class LlamaInferenceEngine private constructor(
    context: Context,
    private val nativeLibDir: String,
) : InferenceEngine {

    companion object {
        private const val TAG = "PFLlamaEngine"
        const val NATIVE_LIB_NAME = "pocketforge-llm"

        @Volatile
        private var instance: LlamaInferenceEngine? = null

        fun get(context: Context): LlamaInferenceEngine =
            instance ?: synchronized(this) {
                instance ?: LlamaInferenceEngine(
                    context = context.applicationContext,
                    nativeLibDir = context.applicationInfo.nativeLibraryDir,
                ).also { instance = it }
            }
    }

    private val _state = MutableStateFlow<EngineState>(EngineState.Uninitialized)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _runtimeInfo = MutableStateFlow<ModelRuntimeInfo?>(null)
    override val runtimeInfo: StateFlow<ModelRuntimeInfo?> = _runtimeInfo.asStateFlow()

    private val activeModel = AtomicBoolean(false)
    private val turnFinished = AtomicBoolean(false)
    private val userCancelled = AtomicBoolean(false)
    private var loadedConfig: InferenceConfiguration? = null

    /** Set when processSystemPrompt succeeded since the last load/unload. */
    private val systemPromptApplied = AtomicBoolean(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val llamaScope = CoroutineScope(llamaDispatcher + SupervisorJob())

    /** Turn-local counters, only touched from llamaScope. */
    private var turnTokens = 0

    init {
        llamaScope.launch {
            try {
                check(_state.value is EngineState.Uninitialized) { "Engine already bootstrapping" }
                _state.value = EngineState.Initializing
                System.loadLibrary(NATIVE_LIB_NAME)
                LlamaBridge.init(nativeLibDir)
                _state.value = EngineState.Initialized
                Log.i(TAG, "Native engine initialized; system info: ${LlamaBridge.systemInfo()}")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to initialize native engine", t)
                _state.value = EngineState.Error(Exception("Native engine failed to start: ${t.message}", t))
            }
        }
    }

    override suspend fun loadModel(configuration: InferenceConfiguration) {
        val errors = configuration.validate()
        require(errors.isEmpty()) { errors.joinToString("; ") }
        val path = configuration.modelPath
        withContext(llamaDispatcher) {
            val st = _state.value
            check(st is EngineState.Initialized || st is EngineState.Error) {
                "Cannot load a model while the engine is ${st::class.simpleName}"
            }
            check(activeModel.compareAndSet(false, true)) { "A model is already resident; unload it first" }
            try {
                File(path).let { f ->
                    require(f.exists() && f.isFile && f.canRead()) { "Model file not readable: $path" }
                }
                _state.value = EngineState.LoadingModel
                _runtimeInfo.value = null
                val err = LlamaBridge.loadModel(
                    modelPath = path,
                    nCtx = configuration.nCtx,
                    nThreads = configuration.nThreads,
                    nGpuLayers = configuration.nGpuLayers,
                    batchSize = configuration.batchSize,
                )
                if (err != null) throw IllegalStateException(err)
                val samplerErr = LlamaBridge.setSampler(
                    configuration.temperature,
                    configuration.topK,
                    configuration.topP,
                    configuration.seed,
                )
                if (samplerErr != null) throw IllegalStateException(samplerErr)
                loadedConfig = configuration
                _state.value = EngineState.ModelReady
                _runtimeInfo.value = buildRuntimeInfo(configuration)
                systemPromptApplied.set(false)
                _sessionStateId.incrementAndGet()
                Log.i(TAG, "Model loaded: $path ctx=${configuration.nCtx} threads=${configuration.nThreads}")
            } catch (t: Throwable) {
                activeModel.set(false)
                _state.value = EngineState.Error(t)
                throw t
            }
        }
    }

    /**
     * Applies the system prompt exactly once per model load / before the first turn,
     * using the model's own chat template (issued through the engine's native layer).
     */
    suspend fun ensureSystemPrompt(prompt: String): Boolean = withContext(llamaDispatcher) {
        if (systemPromptApplied.get()) return@withContext true
        check(_state.value is EngineState.ModelReady) { "Model not ready" }
        if (prompt.isBlank()) {
            systemPromptApplied.set(true)
            return@withContext true
        }
        val rc = LlamaBridge.processSystemPrompt(prompt)
        if (rc == 0) {
            systemPromptApplied.set(true)
            true
        } else {
            false
        }
    }

    override suspend fun setSystemPrompt(prompt: String) = withContext(llamaDispatcher) {
        check(_state.value is EngineState.ModelReady) { "System prompt requires a loaded and idle model" }
        require(prompt.isNotBlank()) { "System prompt cannot be blank" }
        val rc = LlamaBridge.processSystemPrompt(prompt)
        if (rc != 0) {
            val ex = IllegalStateException("Failed to process system prompt (rc=$rc)")
            _state.value = EngineState.Error(ex)
            throw ex
        }
        systemPromptApplied.set(true)
    }

    override fun send(
        message: String,
        maxTokens: Int,
        stopSequences: List<String>,
    ): Flow<StreamEvent> = callbackFlow {
        require(message.isNotBlank()) { "Cannot send an empty message" }
        check(_state.value is EngineState.ModelReady) {
            "Cannot send a message while engine is ${_state.value::class.simpleName}"
        }
        _state.value = EngineState.ProcessingUserPrompt
        turnFinished.set(false)
        userCancelled.set(false)
        turnTokens = 0

        llamaScope.launch {
            val stopper = StopDetector(stopSequences)
            val sb = StringBuilder()
            var finishReason: FinishReason? = null
            try {
                LlamaBridge.clearStopFlag()
                val pfb = com.pocketforge.ai.bench.PfBenchLogger  // debug-only measurement hooks in production loop
                if (pfb.isArmed()) pfb.turnBegin()
                val rc = LlamaBridge.processUserPrompt(message, maxTokens)
                if (pfb.isArmed() && rc == 0) pfb.promptDone()
                if (rc != 0) {
                    _state.value = EngineState.ModelReady
                    trySend(StreamEvent.Error(IllegalStateException("Failed to process prompt (rc=$rc)")))
                    return@launch
                }
                _state.value = EngineState.Generating

                val text = StringBuilder()
                while (finishReason == null) {
                    if (turnFinished.get()) { finishReason = FinishReason.CANCELLED; break }
                    val pfb = com.pocketforge.ai.bench.PfBenchLogger
                    val measure = pfb.isArmed()
                    var wallNs = 0L; var cpuNs = 0L; var wallBefore = 0L; var cpuBefore = 0L
                    if (measure) {
                        cpuBefore = android.os.Debug.threadCpuTimeNanos()
                        wallBefore = System.nanoTime()
                    }
                    val piece = LlamaBridge.generateNextToken()
                    if (measure && piece != null) {
                        wallNs = System.nanoTime() - wallBefore
                        cpuNs = android.os.Debug.threadCpuTimeNanos() - cpuBefore
                        pfb.token(wallNs, cpuNs, piece.length)
                    }
                    if (piece == null) {
                        finishReason = when {
                            userCancelled.getAndSet(false) -> FinishReason.CANCELLED
                            turnTokens >= maxTokens -> FinishReason.MAX_TOKENS
                            else -> FinishReason.END_OF_TURN
                        }
                        break
                    }
                    turnTokens++
                    when (val feed = stopper.feed(piece)) {
                        is StopDetector.FeedResult.Emit -> if (feed.text.isNotEmpty()) {
                            text.append(feed.text)
                            trySend(StreamEvent.Token(feed.text))
                        }
                        is StopDetector.FeedResult.StopTriggered -> {
                            if (feed.text.isNotEmpty()) {
                                text.append(feed.text)
                                trySend(StreamEvent.Token(feed.text))
                            }
                            finishReason = FinishReason.STOP_SEQUENCE
                        }
                    }
                    if (finishReason == null && text.isNotEmpty()) {
                        turnText = text
                    }
                }

                val flushed = stopper.flush()
                if (flushed.isNotEmpty()) {
                    text.append(flushed)
                    trySend(StreamEvent.Token(flushed))
                }
                turnText = text
                val finalText = text.toString()
                finishTurnOnce(finalText)
                val stats = parseStats()
                if (com.pocketforge.ai.bench.PfBenchLogger.isArmed()) {
                    com.pocketforge.ai.bench.PfBenchLogger.done(
                        (finishReason ?: FinishReason.END_OF_TURN).name, finalText.length,
                        stats.promptTokens, stats.generatedTokens, stats.promptMillis, stats.generationMillis)
                }
                _state.value = EngineState.ModelReady
                trySend(StreamEvent.Done(finishReason ?: FinishReason.END_OF_TURN, finalText, stats))
            } catch (e: CancellationException) {
                _state.value = EngineState.ModelReady
            } catch (t: Throwable) {
                Log.e(TAG, "Generation failed", t)
                _state.value = EngineState.Error(t)
                if (com.pocketforge.ai.bench.PfBenchLogger.isArmed()) {
                    com.pocketforge.ai.bench.PfBenchLogger.aborted("throwable: ${t.message}")
                }
                trySend(StreamEvent.Error(t))
            } finally {
                close()
            }
        }
        awaitClose {
            if (_state.value is EngineState.Generating || _state.value is EngineState.ProcessingUserPrompt) {
                userCancelled.set(true)
                LlamaBridge.setStopFlag()
            }
        }
    }.flowOn(llamaDispatcher)

    override fun cancel() {
        if (_state.value is EngineState.Generating) {
            userCancelled.set(true)
            LlamaBridge.setStopFlag()
        }
    }

    /** Bumped whenever the native session state is reset (new load or explicit reset). */
    val sessionStateId: Long
        get() = _sessionStateId.get()

    private val _sessionStateId = java.util.concurrent.atomic.AtomicLong(0)

    /** Clears KV cache + template history; restoreHistory can rebuild template context. */
    suspend fun resetSession() = withContext(llamaDispatcher) {
        if (_state.value is EngineState.ModelReady) {
            LlamaBridge.resetSession()
            systemPromptApplied.set(false)
            _sessionStateId.incrementAndGet()
        }
    }

    /**
     * Rebuilds chat-template history (no KV processing) after a session reset so the
     * next formatted prompt carries the full transcript as one block.
     */
    suspend fun restoreHistory(messages: List<Pair<String, String>>): Boolean = withContext(llamaDispatcher) {
        check(_state.value is EngineState.ModelReady) { "Model not ready" }
        for ((role, content) in messages) {
            if (LlamaBridge.addHistoryMessage(role, content) != 0) return@withContext false
        }
        true
    }

    override suspend fun unload() = withContext(llamaDispatcher) {
        val st = _state.value
        when {
            st is EngineState.ModelReady -> {
                LlamaBridge.unloadModel()
                _runtimeInfo.value = null
                loadedConfig = null
                _state.value = EngineState.Initialized
            }
            st is EngineState.Error -> _state.value = EngineState.Initialized
        }
        activeModel.set(false)
    }

    override suspend fun probeGpu(): GpuProbeReport = withContext(llamaDispatcher) {
        parseProbeReport(LlamaBridge.probeBackendJson(""))
    }

    fun recentLogs(): List<String> = if (_state.value is EngineState.Uninitialized) emptyList() else try {
        LlamaBridge.getRecentLogs().toList()
    } catch (_: UnsatisfiedLinkError) {
        emptyList()
    }

    fun runtimeInfoJsonOrNull(): String? = if (_state.value is EngineState.ModelReady) {
        runCatching { LlamaBridge.modelInfoJson() }.getOrNull()
    } else {
        null
    }

    fun backendsJsonOrNull(): String? = runCatching { LlamaBridge.listBackendsJson() }.getOrNull()

    fun probeJsonOrNull(): String? = runCatching { LlamaBridge.probeBackendJson("") }.getOrNull()

    fun contextStateSizeBytes(): Long {
        if (_state.value !is EngineState.ModelReady) return 0L
        return runBlocking(llamaDispatcher) { LlamaBridge.contextStateSize() }
    }

    private var turnText = StringBuilder()

    private fun finishTurnOnce(text: String) {
        if (turnFinished.compareAndSet(false, true)) {
            try {
                LlamaBridge.finishTurn(text)
            } catch (t: Throwable) {
                Log.w(TAG, "finishTurn failed", t)
            }
        }
    }

    private fun buildRuntimeInfo(configuration: InferenceConfiguration): ModelRuntimeInfo {
        val infoJson = LlamaBridge.modelInfoJson()
        val o = JSONObject(infoJson)
        val devices = parseBackends(LlamaBridge.listBackendsJson())
        return ModelRuntimeInfo(
            desc = o.optString("desc", "unknown"),
            sizeBytes = o.optLong("sizeBytes", 0),
            parameterCount = o.optLong("nParams", 0),
            nCtxTrain = o.optLong("nCtxTrain", 0),
            nCtx = o.optLong("nCtx", configuration.nCtx.toLong()),
            nThreads = configuration.nThreads,
            nativeInfo = LlamaBridge.systemInfo(),
            backendDevices = devices,
            requestedGpuLayers = configuration.nGpuLayers,
        )
    }

    private fun parseStats(): TokenStats = try {
        val o = JSONObject(LlamaBridge.perfJson())
        TokenStats(
            promptTokens = o.optInt("nPromptTokens", 0),
            promptMillis = o.optLong("tPromptMs", 0),
            generatedTokens = o.optInt("nGenTokens", 0),
            generationMillis = o.optLong("tGenMs", 0),
        )
    } catch (_: Exception) {
        TokenStats(0, 0, turnTokens, 0)
    }
}

internal fun parseBackends(json: String): List<BackendDeviceInfo> {
    return try {
        val arr = JSONObject(json).optJSONArray("devices") ?: return emptyList()
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            BackendDeviceInfo(
                backend = o.optString("backend", "?"),
                deviceName = o.optString("name", "?"),
                description = o.optString("description", ""),
                type = o.optString("type", "?"),
                freeMemoryBytes = o.optLong("freeBytes", -1).takeIf { it >= 0 },
                totalMemoryBytes = o.optLong("totalBytes", -1).takeIf { it >= 0 },
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

internal fun parseProbeReport(json: String): GpuProbeReport {
    return try {
        val o = JSONObject(json)
        val results = o.optJSONArray("results") ?: return GpuProbeReport(
            gpuDevices = parseBackends(o.optString("devices")),
            results = emptyList(),
            overallOk = false,
            summary = o.optString("summary", "probe produced no results"),
        )
        val probes = (0 until results.length()).map { i ->
            val r = results.getJSONObject(i)
            val failedStepName = r.optString("failedStep", "NONE")
            GpuProbeResult(
                deviceName = r.optString("device", "?"),
                dataType = r.optString("dataType", "?"),
                ok = r.optBoolean("ok", false),
                failedStep = if (failedStepName == "NONE") null else GpuCheckStep.valueOf(failedStepName),
                detail = r.optString("detail", ""),
            )
        }
        GpuProbeReport(
            gpuDevices = parseBackends(o.optString("devices")),
            results = probes,
            overallOk = o.optBoolean("overallOk", false),
            summary = o.optString("summary", ""),
        )
    } catch (e: Exception) {
        GpuProbeReport(emptyList(), emptyList(), false, "Probe failed to run: ${e.message}")
    }
}
