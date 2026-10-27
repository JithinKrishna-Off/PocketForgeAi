package com.pocketforge.ai.inference

import com.pocketforge.ai.PocketForgeApp
import com.pocketforge.ai.data.model.ModelRegistry
import com.pocketforge.ai.device.HardwareCapabilityDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
 *  - Threads: 0 means "auto" - the native layer picks min(6, cores-2) for explicit
 *    thread settings (<=0 -> native auto), which adapts per device.
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
        // Measured on Dimensity 9300+/Mali-G720-Immortalis (V2503i, 2026-10-23): full
        // Vulkan offload is slower than tuned CPU for both prefill and decode
        // (1.5B: pp 13.5 vs 157 tok/s), and llama.cpp-on-Mali slowness is published
        // (arXiv:2410.03613, MediaTek Dimensity 9300). Default is therefore CPU-first;
        // a GPU offload still applies when the caller requests it explicitly.
        // Keep layer offload off by default on Vulkan for this model class: CPU was faster
        // in benchmarks; the explicit per-load toggle may still turn it on.
        val gpuLayers = 0
        if (gpu != null && gpuLayers == 0) {
            rationale.add("GPU ${gpu.description} present but CPU execution selected (measured faster per token on this GPU class); enable/offload override available")
        } else {
            rationale.add("No engine-visible GPU: running on CPU (quantized, feature-selected variant)")
        }

        val threads = if (threadCountSetting > 0) {
            threadCountSetting
        } else {
            // Mirrors the native auto-tune (min(6, cores-2) for explicit thread
            // settings, native auto for <=0) so the config validates
            // before reaching the engine; still adapts to the current device SoC.
            maxOf(1, minOf(6, cpuCores - 2))
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
        val preload = withContext(Dispatchers.IO) {
            val installed = try {
                ModelRegistry.get(app).all().firstOrNull { it.id == modelId }
            } catch (_: Exception) {
                null
            } ?: return@withContext PreloadResult.Failure("Model is not installed: $modelId")
            val file = java.io.File(installed.path)
            if (!file.isFile || file.length() <= 0) {
                return@withContext PreloadResult.Failure("Model file is missing on device storage")
            }

            val settings = app.settingsStore.settings.first()

            PreloadResult.Ready(
                settings = settings,
                file = file,
            )
        }
        val ready = when (val p = preload) {
            is PreloadResult.Failure -> return Result.Failure(p.message)
            is PreloadResult.Ready -> p
        }
        val settings = ready.settings

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

        val secondary = withContext(Dispatchers.IO) {
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
                modelPath = ready.file.path,
                nCtx = contextLength ?: rec.contextLength,
                nThreads = threads ?: rec.threads,
                nGpuLayers = gpuLayers ?: rec.gpuLayers,
                temperature = settings.temperature,
                topP = settings.topP,
                topK = settings.topK,
            )
            val errors = config.validate()
            if (errors.isNotEmpty()) {
                return@withContext SecondaryResult.Failure(errors.joinToString("; "))
            }

            SecondaryResult.Plan(rec, config)
        }
        val planData = when (secondary) {
            is SecondaryResult.Failure -> return Result.Failure(secondary.message)
            is SecondaryResult.Plan -> secondary
        }
        val rec = planData.rec
        val config = planData.config

        val start = System.currentTimeMillis()
        return try {
            app.engine.loadModel(config)
            SessionStats.onLoaded(System.currentTimeMillis() - start)
            app.settingsStore.setLastModelId(modelId)
            val planDesc = try {
                val p = withContext(Dispatchers.IO) { JSONObject(LlamaBridge.pfPlansJson()) }
                val backend = p.optJSONObject("facts")?.optString("backendName")
                val plan = p.optJSONObject("currentPlan")
                val typeK = plan?.optJSONObject("kv")?.optString("typeK")
                val typeV = plan?.optJSONObject("kv")?.optString("typeV")
                listOfNotNull(
                    backend?.takeIf { it.isNotBlank() }?.let { "backend $it" },
                    typeK?.takeIf { it.isNotBlank() }?.let {
                        "kv $it" + (typeV?.takeIf { v -> v.isNotBlank() }?.let { v -> "/$v" } ?: "")
                    },
                ).joinToString(", ")
            } catch (_: Throwable) {
                ""
            }
            val where = rec.gpuDeviceName?.takeIf { rec.gpuLayers > 0 }?.let { " (GPU: $it)" } ?: " (CPU)"
            val planNote = if (planDesc.isNotBlank()) " [plan: $planDesc]" else ""
            Result.Loaded(
                "Optimized load$where: ctx ${config.nCtx}, threads ${config.nThreads}$planNote" +
                    " in ${(System.currentTimeMillis() - start) / 1000.0}s",
            )
        } catch (t: Throwable) {
            Result.Failure("Optimized load failed: ${t.message}")
        }
    }

    /** Model registry/file/settings/spec resolution done off the main thread. */
    private sealed class PreloadResult {
        data class Failure(val message: String) : PreloadResult()
        data class Ready(
            val settings: com.pocketforge.ai.settings.Settings,
            val file: java.io.File,
        ) : PreloadResult()
    }

    /** Device detect done off the main thread. */
    private sealed class SecondaryResult {
        data class Failure(val message: String) : SecondaryResult()
        data class Plan(val rec: DeviceOptimizer.Recommendation, val config: InferenceConfiguration) : SecondaryResult()
    }

    fun installedModels(): List<Pair<String, String>> = try {
        ModelRegistry.get(app).all().map { it.id to (it.fileName + " (${(it.sizeBytes / 1048576)} MB)") }
    } catch (_: Exception) {
        emptyList()
    }
}