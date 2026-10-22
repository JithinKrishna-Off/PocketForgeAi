package com.pocketforge.ai.diag

import android.content.Context
import android.content.pm.PackageManager
import android.os.Debug
import android.os.StatFs
import com.pocketforge.ai.device.HardwareCapabilityDetector
import com.pocketforge.ai.inference.InferenceConfiguration
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream

/**
 * Builds a self-contained diagnostics report (privacy: contains device facts, backend
 * capabilities and engine logs; never conversation content).
 */
class Diagnostics(private val context: Context) {

    fun exportedFileName(): String = "pocketforge-diagnostics-${System.currentTimeMillis()}.json"

    fun build(
        engineRuntimeJson: String,
        backendsJson: String,
        gpuProbeJson: String,
        nativeLogs: List<String>,
        settings: Map<String, Any?>,
        config: InferenceConfiguration?,
    ): String {
        val device = HardwareCapabilityDetector.detect(context)
        val stat = StatFs(context.getExternalFilesDir(null)?.path ?: context.cacheDir.path)
        val pm = context.packageManager
        val versionName = try {
            pm.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: PackageManager.NameNotFoundException) {
            "?"
        }

        val root = JSONObject()
            .put("app", JSONObject()
                .put("name", "PocketForge AI")
                .put("version", versionName)
                .put("package", context.packageName))
            .put("device", JSONObject()
                .put("manufacturer", device.manufacturer)
                .put("model", device.model)
                .put("android", device.androidVersion)
                .put("abi", device.abi)
                .put("cpuCores", device.cpuCores)
                .put("totalRamBytes", device.totalRamBytes)
                .put("availableRamBytes", device.availableRamBytes)
                .put("ramThresholdBytes", device.ramThresholdBytes)
                .put("isLowMemory", device.isLowMemory)
                .put("vulkanFeatureVersionRaw", device.vulkanFeatureVersionRaw ?: JSONObject.NULL)
                .put("vulkanDecodedVersion",
                    device.vulkanFeatureVersionRaw
                        ?.let { HardwareCapabilityDetector.decodeVulkanVersion(it) }
                        ?: JSONObject.NULL)
                .put("vulkanHardwareLevelRaw", device.vulkanHardwareLevelRaw ?: JSONObject.NULL)
                .put("thermalStatus", device.thermalStatus ?: JSONObject.NULL)
                .put("storageFreeBytes", stat.availableBytes))
            .put("settings", JSONObject().apply { settings.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } })
            .put("inferenceConfiguration", config?.let {
                JSONObject()
                    .put("nCtx", it.nCtx)
                    .put("nThreads", it.nThreads)
                    .put("nGpuLayers", it.nGpuLayers)
                    .put("batchSize", it.batchSize)
                    .put("temperature", it.temperature.toDouble())
                    .put("topP", it.topP.toDouble())
                    .put("topK", it.topK)
            } ?: JSONObject.NULL)
            .put("modelRuntime", if (engineRuntimeJson.isBlank()) JSONObject.NULL else try { JSONObject(engineRuntimeJson) } catch (_: Exception) { JSONObject.NULL })
            .put("backendDevices", if (backendsJson.isBlank()) JSONArray() else try { JSONObject(backendsJson).optJSONArray("devices") ?: JSONArray() } catch (_: Exception) { JSONArray() })
            .put("gpuProbe", if (gpuProbeJson.isBlank()) JSONObject.NULL else try { JSONObject(gpuProbeJson) } catch (_: Exception) { JSONObject.NULL })
            .put("nativeLogs", JSONArray().apply { nativeLogs.forEach { put(it) } })
        return root.toString(2)
    }

    fun write(os: OutputStream, json: String) {
        os.use { it.write(json.toByteArray(Charsets.UTF_8)) }
    }

    fun systemMemorySummary(): JSONObject {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mem = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return JSONObject()
            .put("totalMem", mem.totalMem)
            .put("availMem", mem.availMem)
            .put("lowMemory", mem.lowMemory)
            .put("threshold", mem.threshold)
            .put("nativeHeapAllocated", Debug.getNativeHeapAllocatedSize())
            .put("nativeHeapSize", Debug.getNativeHeapSize())
    }
}
