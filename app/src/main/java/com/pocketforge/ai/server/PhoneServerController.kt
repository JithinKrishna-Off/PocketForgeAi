package com.pocketforge.ai.server

import android.content.Context
import com.pocketforge.ai.PocketForgeApp
import com.pocketforge.ai.data.model.ModelRegistry
import com.pocketforge.ai.inference.EngineState
import com.pocketforge.ai.inference.FinishReason
import com.pocketforge.ai.inference.LlamaBridge
import com.pocketforge.ai.inference.LlamaInferenceEngine
import com.pocketforge.ai.inference.ModelRuntimeInfo
import com.pocketforge.ai.inference.StreamEvent
import com.pocketforge.ai.inference.TokenStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Observable server state for the settings UI. */
data class ServerStatus(
    val running: Boolean = false,
    val port: Int = 0,
    val boundAddress: String = "",
    val pairingCode: String = "",
    val activeConnections: Int = 0,
    val pairedClients: Int = 0,
    val busy: Boolean = false,
    val lastError: String? = null,
)

/**
 * Process-wide owner of the phone HTTP server lifecycle. Binds 127.0.0.1 by
 * default, 0.0.0.0 only when the user opts into LAN access. NOT the engine
 * owner: it uses the app-wide engine singleton so in-app chat and desktop
 * requests share one model and one generation at a time.
 */
class PhoneServerController private constructor(private val app: PocketForgeApp) {

    companion object {
        private const val DEFAULT_MAX_TOKENS = 512
        private const val WATCHDOG_POLL_MS = 5000L
        private const val SSE_WRITE_TIMEOUT_NANOS = 30_000_000_000L

        @Volatile
        private var instance: PhoneServerController? = null

        fun get(context: Context): PhoneServerController =
            instance ?: synchronized(this) {
                instance ?: PhoneServerController(context.applicationContext as PocketForgeApp).also { instance = it }
            }
    }

    val auth = PhoneAuth(app)

    private val _status = MutableStateFlow(ServerStatus())
    val status: StateFlow<ServerStatus> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startLock = Any()
    private var server: PhoneHttpServer? = null

    /** True between a service-initiated start() and the matching stop(); used to
     *  distinguish "starting" from "stale toggle after process death". */
    @Volatile
    private var startRequested = false

    /** Server-side guard so only one desktop turn runs at a time. */
    private val generationLock = AtomicBoolean(false)
    private val connCount = AtomicInteger(0)

    fun isStartPending(): Boolean = startRequested

    fun start(port: Int, lanAccess: Boolean): Boolean {
        startRequested = true
        synchronized(startLock) {
            server?.let { existing ->
                if (existing.boundPort == port && existing.lanAccess == lanAccess) {
                    _status.update { it.copy(running = true, lastError = null) }
                    return true
                }
                existing.stop()
                server = null
            }
            val address = if (lanAccess) InetAddress.getByName("0.0.0.0") else InetAddress.getByName("127.0.0.1")
            val listener = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(address, port))
                }
            } catch (t: Throwable) {
                _status.update { it.copy(running = false, lastError = "cannot bind port $port: ${t.message}") }
                return false
            }
            val pairingCode = auth.newPairingCode()
            val bound = if (lanAccess) lanAddress() else "127.0.0.1"
            val srv = PhoneHttpServer(scope, listener, lanAccess) { req, sock -> handleConnection(req, sock) }
            server = srv
            srv.start()
            _status.value = ServerStatus(
                running = true,
                port = listener.localPort,
                boundAddress = bound,
                pairingCode = pairingCode,
                pairedClients = auth.tokenCount(),
            )
            return true
        }
    }

    fun stop() {
        startRequested = false
        synchronized(startLock) {
            server?.stop()
            server = null
            _status.value = ServerStatus(pairedClients = auth.tokenCount())
        }
    }

    fun revokeTokens() {
        auth.revoke()
        _status.update { it.copy(pairedClients = 0) }
    }

    private fun lanAddress(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull() ?: "0.0.0.0"

    private suspend fun handleConnection(req: HttpRequest, sock: Socket) {
        val n = connCount.incrementAndGet()
        _status.update { it.copy(activeConnections = n) }
        try {
            val out = sock.getOutputStream()
            when {
                req.method == "GET" && req.path == "/health" ->
                    PhoneHttpServer.writeJsonResponse(out, 200, PhoneServerContract.healthJson())
                req.method == "POST" && req.path == "/pair" -> handlePair(req, out)
                req.path.startsWith("/v1/") -> handleApi(req, out)
                else -> PhoneHttpServer.writeJsonResponse(out, 404, PhoneServerContract.errorJson("not found", 404))
            }
        } finally {
            val m = connCount.decrementAndGet()
            _status.update { it.copy(activeConnections = m.coerceAtLeast(0)) }
        }
    }

    private suspend fun handleApi(req: HttpRequest, out: OutputStream) {
        if (!auth.authorize(req.header("Authorization"))) {
            PhoneHttpServer.writeJsonResponse(out, 401, PhoneServerContract.errorJson("unauthorized", 401))
            return
        }
        when {
            req.method == "GET" && req.path == "/v1/status" -> handleStatus(out)
            req.method == "GET" && req.path == "/v1/models" -> handleModels(out)
            req.method == "POST" && req.path == "/v1/chat/completions" -> handleChatCompletions(req, out)
            req.method == "POST" && req.path == "/v1/cancel" -> handleCancel(out)
            else -> PhoneHttpServer.writeJsonResponse(out, 404, PhoneServerContract.errorJson("not found", 404))
        }
    }

    private suspend fun handlePair(req: HttpRequest, out: OutputStream) {
        val body = runCatching { JSONObject(String(req.body, Charsets.UTF_8)) }.getOrNull()
        if (body == null) {
            PhoneHttpServer.writeJsonResponse(out, 400, PhoneServerContract.errorJson("malformed JSON body", 400))
            return
        }
        val token = try {
            auth.exchangeCode(body.optString("code"))
        } catch (_: PairRateLimitedException) {
            PhoneHttpServer.writeJsonResponse(
                out, 429,
                PhoneServerContract.errorJson("too many pairing attempts; wait a minute", 429),
            )
            return
        }
        if (token == null) {
            PhoneHttpServer.writeJsonResponse(out, 401, PhoneServerContract.errorJson("invalid pairing code", 401))
            return
        }
        _status.update { it.copy(pairedClients = auth.tokenCount()) }
        PhoneHttpServer.writeJsonResponse(out, 200, PhoneServerContract.pairTokenJson(token))
    }

    private suspend fun handleStatus(out: OutputStream) {
        val engine = app.engine
        val st = engine.state.value
        val runtime: ModelRuntimeInfo? = if (st is EngineState.ModelReady) engine.runtimeInfo.value else null
        val steer = runCatching {
            val s = JSONObject(LlamaBridge.pfPlansJson()).optJSONObject("steer")
            (s?.optBoolean("active", false) ?: false) to (s?.optString("appliedSpec") ?: "")
        }.getOrDefault(false to "")
        PhoneHttpServer.writeJsonResponse(
            out, 200,
            PhoneServerContract.statusJson(
                port = _status.value.port,
                lan = server?.lanAccess == true,
                stateName = st::class.simpleName ?: "Unknown",
                busy = st.isBusy,
                runtime = runtime,
                modelName = loadedModelName(),
                steerActive = steer.first,
                steerSpec = steer.second,
            ),
        )
    }

    private suspend fun handleModels(out: OutputStream) {
        PhoneHttpServer.writeJsonResponse(out, 200, PhoneServerContract.modelsJson(loadedModelName()))
    }

    private suspend fun handleCancel(out: OutputStream) {
        app.engine.cancel()
        PhoneHttpServer.writeNoContent(out)
    }

    private suspend fun loadedModelName(): String? {
        if (app.engine.state.value !is EngineState.ModelReady) return null
        return runCatching {
            val id = app.settingsStore.settings.first().lastModelId
            ModelRegistry.get(app).byId(id)?.fileName
        }.getOrNull()
    }

    private suspend fun handleChatCompletions(req: HttpRequest, out: OutputStream) {
        val body = runCatching { JSONObject(String(req.body, Charsets.UTF_8)) }.getOrNull()
        if (body == null) {
            PhoneHttpServer.writeJsonResponse(out, 400, PhoneServerContract.errorJson("malformed JSON body", 400))
            return
        }
        val messages = body.optJSONArray("messages")
        if (messages == null || messages.length() == 0) {
            PhoneHttpServer.writeJsonResponse(out, 400, PhoneServerContract.errorJson("messages array is required", 400))
            return
        }
        var system: String? = null
        var lastUser: String? = null
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i)
            if (m == null) {
                PhoneHttpServer.writeJsonResponse(out, 400, PhoneServerContract.errorJson("malformed message entry", 400))
                return
            }
            when (m.optString("role")) {
                "system" -> {
                    if (i != 0 || system != null) {
                        PhoneHttpServer.writeJsonResponse(
                            out, 400,
                            PhoneServerContract.errorJson("system message must be first and appear once", 400),
                        )
                        return
                    }
                    system = m.optString("content")
                }
                "user" -> lastUser = m.optString("content")
            }
        }
        val user = lastUser
        if (user.isNullOrBlank()) {
            PhoneHttpServer.writeJsonResponse(out, 400, PhoneServerContract.errorJson("no user message", 400))
            return
        }
        val stream = body.optBoolean("stream", false)
        val maxTokens = body.optInt("max_tokens", DEFAULT_MAX_TOKENS).coerceIn(1, 32768)
        val stop = body.optJSONArray("stop")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
        } ?: emptyList()
        val resetSession = body.optBoolean("reset_session", false)

        if (!generationLock.compareAndSet(false, true)) {
            PhoneHttpServer.writeJsonResponse(out, 409, PhoneServerContract.errorJson("a generation is already running", 409))
            return
        }
        _status.update { it.copy(busy = true) }
        try {
            val engine = app.engine
            val st = engine.state.value
            if (st.isBusy) {
                PhoneHttpServer.writeJsonResponse(out, 409, PhoneServerContract.errorJson("a generation is already running", 409))
                return
            }
            if (st !is EngineState.ModelReady) {
                PhoneHttpServer.writeJsonResponse(out, 503, PhoneServerContract.errorJson("model not loaded", 503))
                return
            }
            if (resetSession) runCatching { engine.resetSession() }
            val sys = system
            if (sys != null) {
                val applied = runCatching { engine.ensureSystemPrompt(sys) }.getOrNull()
                if (applied != true) {
                    val s2 = engine.state.value
                    if (s2.isBusy) {
                        PhoneHttpServer.writeJsonResponse(out, 409, PhoneServerContract.errorJson("a generation is already running", 409))
                    } else {
                        PhoneHttpServer.writeJsonResponse(out, 503, PhoneServerContract.errorJson("model not ready", 503))
                    }
                    return
                }
            }
            runTurn(engine, user, maxTokens, stop, stream, out)
        } finally {
            generationLock.set(false)
            _status.update { it.copy(busy = false) }
        }
    }

    private suspend fun runTurn(
        engine: LlamaInferenceEngine,
        user: String,
        maxTokens: Int,
        stop: List<String>,
        stream: Boolean,
        out: OutputStream,
    ) {
        val id = "pf-" + UUID.randomUUID().toString().replace("-", "").take(16)
        if (!stream) {
            var finish = FinishReason.END_OF_TURN
            var stats = TokenStats(0, 0, 0, 0)
            var content: String? = null
            try {
                engine.send(user, maxTokens, stop).collect { event ->
                    when (event) {
                        is StreamEvent.Token -> {}
                        is StreamEvent.Done -> {
                            finish = event.reason
                            stats = event.stats
                            content = event.text
                        }
                        is StreamEvent.Error -> throw event.error
                    }
                }
            } catch (e: IllegalStateException) {
                // lost the race at send(): the engine is no longer idle
                PhoneHttpServer.writeJsonResponse(out, 409, PhoneServerContract.errorJson("a generation is already running", 409))
                return
            } catch (e: IOException) {
                engine.cancel()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                PhoneHttpServer.writeJsonResponse(
                    out, 500,
                    PhoneServerContract.errorJson(t.message ?: "generation failed", 500),
                )
                return
            }
            PhoneHttpServer.writeJsonResponse(
                out, 200,
                PhoneServerContract.completionJson(
                    id = id,
                    model = loadedModelName() ?: "",
                    content = content ?: "",
                    finishReason = finishWireName(finish),
                    stats = stats,
                    stateName = engine.state.value::class.simpleName ?: "Unknown",
                ),
            )
            return
        }

        var sawDone = false
        try {
            PhoneHttpServer.startSse(out)
            val lastWrite = AtomicLong(System.nanoTime())
            coroutineScope {
                val watchdog = launch { writeWatchdog(out, lastWrite) }
                try {
                    engine.send(user, maxTokens, stop).collect { event ->
                        when (event) {
                            is StreamEvent.Token -> {
                                PhoneHttpServer.writeSseData(out, PhoneServerContract.sseDelta(id, event.text))
                                lastWrite.set(System.nanoTime())
                            }
                            is StreamEvent.Done -> {
                                sawDone = true
                                PhoneHttpServer.writeSseData(
                                    out, PhoneServerContract.sseTerminal(id, finishWireName(event.reason)),
                                )
                                lastWrite.set(System.nanoTime())
                            }
                            is StreamEvent.Error -> throw event.error
                        }
                    }
                } finally {
                    watchdog.cancel()
                }
            }
            if (sawDone) PhoneHttpServer.writeSseData(out, "[DONE]")
        } catch (e: IllegalStateException) {
            PhoneHttpServer.writeSseError(out, "a generation is already running")
        } catch (e: IOException) {
            // client went away mid-stream: stop the turn
            engine.cancel()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            PhoneHttpServer.writeSseError(out, t.message ?: "generation failed")
        }
    }

    /** Unblocks a stalled SSE frame write; the handler turns the failure into engine.cancel(). */
    private suspend fun writeWatchdog(out: OutputStream, lastWrite: AtomicLong) {
        while (true) {
            delay(WATCHDOG_POLL_MS)
            if (System.nanoTime() - lastWrite.get() > SSE_WRITE_TIMEOUT_NANOS) {
                runCatching { out.close() }
                return
            }
        }
    }

    private fun finishWireName(reason: FinishReason): String = when (reason) {
        FinishReason.END_OF_TURN, FinishReason.STOP_SEQUENCE -> "stop"
        FinishReason.MAX_TOKENS -> "length"
        FinishReason.CANCELLED -> "cancelled"
        FinishReason.ERROR -> "error"
    }
}
