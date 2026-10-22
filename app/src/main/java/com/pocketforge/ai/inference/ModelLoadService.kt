package com.pocketforge.ai.inference

import com.pocketforge.ai.PocketForgeApp
import com.pocketforge.ai.data.model.ModelRegistry
import com.pocketforge.ai.device.HardwareCapabilityDetector
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * Device-adaptive inference preset selection ("optimization engine").
 *
 * The engine reacts to hardware differences at load time (backend variants are
 * rejected per CPU feature support, the Vulkan backend loads per device), and this
 * object chooses the sensible knobs for the CURRENT device:
 *
 *  - GPU offload: all repeating layers when an engine-visible GPU/IGPU device exists
 *    (llama.cpp clamps an oversized layer count to the model's layer count).
 *  - Threads: 0 means "auto" — the native layer picks min(4, cores-2), which is
 *    correct for the common 4+4 big.LITTLE phone layout and adapts per device.
 *  - Context length: sized by currently available RAM, not by the biggest model.
 */
object DeviceOptimizer {

    data class Recommendation(
        val gpuLayers: Int,
        val gpuDeviceName: String?,
        val threads: Int,
        val contextLength: Int,
        val rationale: List<String>,
    )

    fun recommend(
        devices: List<BackendDeviceInfo>,
        threadCountSetting: Int,
        availableRamBytes: Long?,
        cpuCores: Int = 8,
        userContextSetting: Int? = null,
    ): Recommendation {
        val rationale = mutableListOf<String>()
        val gpu = devices.firstOrNull { it.type == "GPU" }
        val gpuLayers = if (gpu != null) 99 else 0
        if (gpu != null) {
            rationale.add("GPU ${gpu.description}: offloading all repeating layers to ${gpu.deviceName}")
        } else {
            rationale.add("No engine-visible GPU: running on CPU (quantized, feature-selected variant)")
        }

        val threads = if (threadCountSetting > 0) {
            threadCountSetting
        } else {
            // Mirrors the native auto-tune (min(4, cores-2)) so the config validates
            // before reaching the engine; still adapts to the current device SoC.
            maxOf(1, minOf(4, cpuCores - 2))
        }
        rationale.add("Threads: $threads (from $cpuCores cores)")

        val ctx = userContextSetting
            ?: if (availableRamBytes != null && availableRamBytes >= 5500L * 1024 * 1024) 4096 else 2048
        rationale.add("Context: $ctx (based on available RAM)")
        return Recommendation(gpuLayers, gpu?.deviceName, threads, ctx, rationale)
    }

    /** Parses the engine backend list JSON produced by the native layer. */
    fun parseDevices(json: String): List<BackendDeviceInfo> = try {
        val arr = JSONObject(json).optJSONArray("devices")
        buildList {
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        BackendDeviceInfo(
                            backend = o.optString("backend", "?"),
                            deviceName = o.optString("name", "?"),
                            description = o.optString("description", ""),
                            type = o.optString("type", "CPU"),
                            freeMemoryBytes = if (o.has("freeBytes")) o.getLong("freeBytes") else null,
                            totalMemoryBytes = if (o.has("totalBytes")) o.getLong("totalBytes") else null,
                        ),
                    )
                }
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * One convenience entry point used by both the Models screen and the Chat quick
 * switcher: loads an installed model with device-optimised defaults and records
 * the activation in SessionStats / settings.
 */
class ModelLoadService(private val app: PocketForgeApp) {

    sealed class Result {
        data class Loaded(val summary: String) : Result()
        data class Failure(val message: String) : Result()
    }

    suspend fun loadInstalled(
        modelId: String,
        contextLength: Int? = null,
        gpuLayers: Int? = null,
        threads: Int? = null,
    ): Result {
        val installed = try {
            ModelRegistry.get(app).all().firstOrNull { it.id == modelId }
        } catch (_: Exception) {
            null
        } ?: return Result.Failure("Model is not installed: $modelId")
        val file = java.io.File(installed.path)
        if (!file.isFile || file.length() <= 0) {
            return Result.Failure("Model file is missing on device storage")
        }

        val settings = app.settingsStore.settings.first()

        // Wait until the native engine bootstrap finished (backends get loaded then).
        try {
            withTimeout(45_000L) {
                app.engine.state.first { it !is EngineState.Uninitialized && it !is EngineState.Initializing }
            }
        } catch (_: TimeoutCancellationException) {
            return Result.Failure("Engine took too long to start; retry")
        }
        val st = app.engine.state.value
        if (st is EngineState.Error) {
            return Result.Failure("Engine failed to start: ${st.error.message}")
        }
        val busyState = app.engine.state.value
        if (busyState.isBusy) {
            return Result.Failure("Engine is busy; wait for the current turn or load to finish")
        }
        if (busyState is EngineState.ModelReady) {
            app.engine.unload()
        }

        val device = HardwareCapabilityDetector.detect(app)
        val devices = DeviceOptimizer.parseDevices(
            LlamaBridge.listBackendsJson(),
        )
        val rec = DeviceOptimizer.recommend(
            devices = devices,
            threadCountSetting = threads ?: settings.threadCount,
            availableRamBytes = device.availableRamBytes,
            cpuCores = device.cpuCores,
        )

        val config = InferenceConfiguration(
            modelPath = installed.path,
            nCtx = contextLength ?: rec.contextLength,
            nThreads = threads ?: rec.threads,
            nGpuLayers = gpuLayers ?: rec.gpuLayers,
            temperature = settings.temperature,
            topP = settings.topP,
            topK = settings.topK,
        )
        val errors = config.validate()
        if (errors.isNotEmpty()) return Result.Failure(errors.joinToString("; "))

        val start = System.currentTimeMillis()
        return try {
            app.engine.loadModel(config)
            SessionStats.onLoaded(System.currentTimeMillis() - start)
            app.settingsStore.setLastModelId(modelId)
            val where = rec.gpuDeviceName?.let { " (GPU: $it)" } ?: " (CPU)"
            Result.Loaded("Optimized load${where}: ctx ${config.nCtx}, threads ${config.nThreads} in ${(System.currentTimeMillis() - start) / 1000.0}s")
        } catch (t: Throwable) {
            Result.Failure("Optimized load failed: ${t.message}")
        }
    }

    fun installedModels(): List<Pair<String, String>> = try {
        ModelRegistry.get(app).all().map { it.id to (it.fileName + " (${(it.sizeBytes / 1048576)} MB)") }
    } catch (_: Exception) {
        emptyList()
    }
}