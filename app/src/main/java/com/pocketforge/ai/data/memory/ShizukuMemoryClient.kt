package com.pocketforge.ai.data.memory

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.sui.Sui
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class ShizukuStatus {
    /** Shizuku/Sui is not reachable (not running, or not installed). */
    NOT_INSTALLED,

    /** Binder reachable, but the user has not granted the permission yet. */
    NOT_GRANTED,

    /** Permission dialog is pending in the Shizuku app. */
    REQUESTING,

    /** Permission granted; user service can be bound. */
    GRANTED,

    /** Connected but with an unsupported pre-v11 Shizuku server. */
    UNSUPPORTED,
}

enum class MemoryMode { BASIC, FULL }

/**
 * App-process side of the Shizuku integration: binder acquisition, permission request
 * flow and user-service binding for [MemoryUserService].
 */
class ShizukuMemoryClient(private val appContext: Context) {

    private val _status = MutableStateFlow(ShizukuStatus.NOT_INSTALLED)
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val bindMutex = Mutex()

    @Volatile
    private var proxy: MemoryUserService.ClientProxy? = null

    /** ShizukuProvider-based binder delivery only works once, so register just one receiver. */
    private var nonProviderBinderRequested = false

    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(appContext.packageName, MemoryUserService.serviceClassName()),
        )
            .tag(MemoryUserService.SERVICE_TAG)
            .version(MemoryUserService.SERVICE_VERSION)
            .daemon(false)
            .debuggable(false)
            .processNameSuffix("memory")
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service != null && service.pingBinder()) {
                proxy = MemoryUserService.ClientProxy(service)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            proxy = null
        }
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code != REQUEST_CODE) return@OnRequestPermissionResultListener
        if (result == PackageManager.PERMISSION_GRANTED) {
            _status.update { ShizukuStatus.GRANTED }
            _notice.update { null }
        } else {
            _status.update { ShizukuStatus.NOT_GRANTED }
            _notice.update { "Shizuku access was not granted; FULL mode stays disabled." }
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { evaluateStatus() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        proxy = null
        _status.update { ShizukuStatus.NOT_INSTALLED }
    }

    /** Registers listeners and evaluates the current state. Call once from the VM init. */
    fun start() {
        registerListeners()
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        evaluateStatus()
    }

    fun close() {
        runCatching { Shizuku.removeBinderReceivedListener(binderReceivedListener) }
        runCatching { Shizuku.removeBinderDeadListener(binderDeadListener) }
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        runCatching {
            if (Shizuku.pingBinder()) {
                Shizuku.unbindUserService(serviceArgs, serviceConnection, true)
            }
        }
        proxy = null
    }

    private fun registerListeners() {
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
    }

    /** Sui provides the binder without the manifest provider path when it is installed. */
    private fun trySuiInit() {
        runCatching {
            Sui.init(appContext.packageName)
        }
        if (!nonProviderBinderRequested) {
            nonProviderBinderRequested = true
            runCatching {
                // Official no-manifest fallback; stays inert when no provider is declared.
                ShizukuProvider.requestBinderForNonProviderProcess(appContext)
            }
        }
    }

    private fun evaluateStatus() {
        proxy = null
        if (!Shizuku.pingBinder()) {
            _status.update { ShizukuStatus.NOT_INSTALLED }
            return
        }
        if (Shizuku.isPreV11()) {
            _status.update { ShizukuStatus.UNSUPPORTED }
            return
        }
        val granted = runCatching { Shizuku.checkSelfPermission() }
            .getOrDefault(PackageManager.PERMISSION_DENIED) == PackageManager.PERMISSION_GRANTED
        _status.update { if (granted) ShizukuStatus.GRANTED else ShizukuStatus.NOT_GRANTED }
    }

    /**
     * Entry point for "enter FULL mode": request permission when needed and surface
     * an explanation when Shizuku itself is not available.
     */
    fun enterFullMode() {
        trySuiInit()
        evaluateStatus()
        when (_status.value) {
            ShizukuStatus.GRANTED -> Unit
            ShizukuStatus.REQUESTING -> Unit
            ShizukuStatus.NOT_INSTALLED -> _notice.update {
                "Shizuku is not running. Start Shizuku (wireless debugging or root) and try again."
            }
            ShizukuStatus.UNSUPPORTED -> _notice.update {
                "The installed Shizuku version is too old. Update the Shizuku app and retry."
            }
            ShizukuStatus.NOT_GRANTED -> {
                if (runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)) {
                    _notice.update {
                        "Shizuku access was previously denied. Grant it from the Shizuku app, then retry."
                    }
                } else {
                    runCatching { Shizuku.requestPermission(REQUEST_CODE) }
                        .onFailure { err -> _notice.update { "Could not open Shizuku permission dialog: ${err.message}" } }
                        .onSuccess { _status.update { ShizukuStatus.REQUESTING } }
                }
            }
        }
    }

    fun recheck() {
        trySuiInit()
        evaluateStatus()
    }

    /** Raw `dumpsys meminfo` output through the user service, or null when unavailable. */
    suspend fun fetchDumpsysMeminfo(): String? = withContext(Dispatchers.IO) {
        serviceCall { it.dumpsysMeminfo() }
    }

    suspend fun whoAmI(): String? = withContext(Dispatchers.IO) {
        serviceCall { it.whoAmI() }
    }

    /** @return null on success, otherwise a human-readable error. */
    suspend fun kill(packageName: String, forceStop: Boolean): String? = withContext(Dispatchers.IO) {
        if (!isSafeShellToken(packageName)) return@withContext "invalid package name"
        val exit = serviceCall {
            if (forceStop) it.amForceStop(packageName) else it.amKill(packageName)
        } ?: return@withContext "Shizuku service unavailable"
        if (exit >= 0) null else "command failed (exit $exit)"
    }

    private suspend fun <T> serviceCall(block: (MemoryUserService.ClientProxy) -> T): T? {
        val p = ensureBound() ?: return null
        return runCatching { block(p) }
            .onFailure { proxy = null }
            .getOrNull()
    }

    private suspend fun ensureBound(): MemoryUserService.ClientProxy? {
        proxy?.let { if (it.isAlive()) return it }
        if (_status.value != ShizukuStatus.GRANTED) return null
        if (!Shizuku.pingBinder()) return null
        return bindMutex.withLock {
            proxy?.let { if (it.isAlive()) return@withLock it }
            runCatching { Shizuku.bindUserService(serviceArgs, serviceConnection) }
                .onFailure { return null }
            withTimeoutOrNull(4_000) {
                while (proxy == null) delay(100)
            }
            proxy
        }
    }

    private companion object {
        const val REQUEST_CODE = 47_101
    }
}
