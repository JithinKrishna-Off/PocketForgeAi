package com.pocketforge.ai.device

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import com.pocketforge.ai.data.memory.MeminfoParser
import java.io.File

/**
 * Reports device facts relevant for inference: CPU topology, RAM accounting, Vulkan
 * feature levels (when the platform exposes them), thermal status.
 *
 * These are Android-reported facts, NOT engine capability: Vulkan presence does not
 * prove the inference engine can execute quantized kernels on that GPU. The engine
 * probe in Performance > GPU produces the trustworthy answer at runtime.
 */
data class DeviceInfo(
    val model: String,
    val manufacturer: String,
    val androidVersion: String,
    val abi: String,
    val cpuCores: Int,
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val ramThresholdBytes: Long,
    val isLowMemory: Boolean,
    val vulkanFeatureVersionRaw: Int?,
    val vulkanHardwareLevelRaw: Int?,
    val thermalStatus: String?,
)

object HardwareCapabilityDetector {

    private const val FEATURE_VULKAN_VERSION = "android.hardware.vulkan.version"
    private const val FEATURE_VULKAN_LEVEL = "android.hardware.vulkan.level"

    fun detect(context: Context): DeviceInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val pm = context.packageManager

        // Memory-fit accounting now reads the kernel's own numbers from /proc/meminfo
        // instead of ActivityManager.availMem. availableRamBytes =
        // MemAvailable + still-resident reclaimable page cache - threshold at the
        // consumption site (ModelsScreen/ModelsViewModel subtract ramThresholdBytes).
        // Rationale: a loaded GGUF is memory-mapped, so its resident pages are
        // file-backed page cache that the kernel can drop under pressure; that cache is
        // reclaimable and must not count against a new load. Only the anonymous parts
        // (KV cache + compute buffers + non-mmap allocations) are truly non-reclaimable,
        // which MemAvailable already covers. Falls back to ActivityManager when
        // /proc/meminfo cannot be read.
        val meminfo = runCatching {
            MeminfoParser.parse(File("/proc/meminfo").readText())
        }.getOrNull()

        // getSystemFeatureVersion is public from API 35; use feature presence below that.
        fun featureVersion(name: String): Int? {
            return try {
                if (!pm.hasSystemFeature(name)) {
                    null
                } else {
                    // getSystemFeatureVersion is @SystemApi Hidden in some API levels; reflect defensively
                    val v: Int = try {
                        pm.javaClass.getMethod("getSystemFeatureVersion", String::class.java)
                            .invoke(pm, name) as Int
                    } catch (_: Throwable) {
                        -1
                    }
                    if (v == -1) null else v
                }
            } catch (_: Throwable) {
                null
            }
        }

        val thermal: String? = if (Build.VERSION.SDK_INT >= 29) {
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            thermalStatusName(power.currentThermalStatus)
        } else {
            null
        }

        return DeviceInfo(
            model = Build.MODEL ?: "?",
            manufacturer = Build.MANUFACTURER ?: "?",
            androidVersion = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?",
            cpuCores = Runtime.getRuntime().availableProcessors(),
            totalRamBytes = meminfo?.memTotalBytes ?: memInfo.totalMem,
            availableRamBytes = meminfo?.availableWithReclaimableCacheBytes ?: memInfo.availMem,
            ramThresholdBytes = memInfo.threshold,
            isLowMemory = memInfo.lowMemory,
            vulkanFeatureVersionRaw = featureVersion(FEATURE_VULKAN_VERSION),
            vulkanHardwareLevelRaw = featureVersion(FEATURE_VULKAN_LEVEL),
            thermalStatus = thermal,
        )
    }

    fun thermalStatusName(status: Int): String = when (status) {
        android.os.PowerManager.THERMAL_STATUS_NONE -> "None"
        android.os.PowerManager.THERMAL_STATUS_LIGHT -> "Light; may reduce peaks"
        android.os.PowerManager.THERMAL_STATUS_MODERATE -> "Moderate; expect throttling"
        android.os.PowerManager.THERMAL_STATUS_SEVERE -> "Severe; strong throttling"
        android.os.PowerManager.THERMAL_STATUS_CRITICAL -> "Critical; near shutdown"
        android.os.PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
        android.os.PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown imminent"
        else -> "Unknown ($status)"
    }

    /** Decodes the android.hardware.vulkan.version feature encoding into "1.3.247" style. */
    fun decodeVulkanVersion(encoded: Int): String? {
        if (encoded <= 0) return null
        // Framework packs (major << 22) | (minor << 12) | patch
        val patch = encoded and 0xFFF
        val minor = (encoded shr 12) and 0x3FF
        val major = encoded shr 22
        if (major == 0) return null
        return "$major.$minor.$patch"
    }
}
