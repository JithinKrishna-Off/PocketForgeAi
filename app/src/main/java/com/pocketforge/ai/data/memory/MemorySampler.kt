package com.pocketforge.ai.data.memory

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import java.io.File

/** One BASIC-mode memory observation of the system and the own process. */
data class MemorySample(
    val meminfo: MeminfoParser.MeminfoSnapshot?,
    val amTotalMemBytes: Long,
    val amAvailMemBytes: Long,
    val amThresholdBytes: Long,
    val amLowMemory: Boolean,
    val own: OwnProcessMemory?,
    val capturedAtMillis: Long,
)

/**
 * Own-process breakdown used by the Performance > Memory panel:
 *  - "model (file-backed)"  ~ Pss_File from /proc/self/smaps_rollup
 *  - "KV cache + compute buffers (anon)" ~ Pss_Anon from /proc/self/smaps_rollup
 *  - "graphics" ~ Graphics PSS from Debug.getMemoryInfo().getMemoryStats()
 *  - "other" ~ remainder of total PSS
 */
data class OwnProcessMemory(
    val nativePssKb: Long,
    val dalvikPssKb: Long,
    val otherPssKb: Long,
    val totalPssKb: Long,
    val graphicsPssKb: Long?,
    val nativeHeapAllocatedBytes: Long,
    val nativeHeapFreeBytes: Long,
    val rssKb: Long?,
    val pssKb: Long?,
    val pssAnonKb: Long?,
    val pssFileKb: Long?,
    val swapKb: Long?,
) {
    /** Non-model, non-anon remainder: other files, graphics, stack, etc. */
    val otherDerivedKb: Long?
        get() {
            val total = pssKb ?: return null
            val accounted = (pssFileKb ?: 0L) + (pssAnonKb ?: 0L) + (graphicsPssKb ?: 0L)
            return maxOf(0L, total - accounted)
        }
}

/**
 * BASIC-mode sampler. Runs on Dispatchers.IO only - sampling reads /proc files and
 * system services and must never touch the main thread.
 */
class MemorySampler(context: Context) {

    private val appContext = context.applicationContext

    fun sample(): MemorySample {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return MemorySample(
            meminfo = readMeminfo(),
            amTotalMemBytes = mi.totalMem,
            amAvailMemBytes = mi.availMem,
            amThresholdBytes = mi.threshold,
            amLowMemory = mi.lowMemory,
            own = readOwnProcessMemory(),
            capturedAtMillis = System.currentTimeMillis(),
        )
    }

    fun readMeminfo(): MeminfoParser.MeminfoSnapshot? = runCatching {
        MeminfoParser.parse(File("/proc/meminfo").readText())
    }.getOrNull()

    /** Debug.getMemoryInfo() (native/dalvik/other PSS + native heap) + /proc/self/smaps_rollup. */
    fun readOwnProcessMemory(): OwnProcessMemory? = runCatching {
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        val graphicsKb = runCatching {
            mi.memoryStats["summary.graphics"]?.toLongOrNull()
        }.getOrNull()

        val rollup = runCatching {
            MeminfoParser.parseKv(File("/proc/self/smaps_rollup").readText())
        }.getOrNull()

        OwnProcessMemory(
            nativePssKb = mi.nativePss.toLong(),
            dalvikPssKb = mi.dalvikPss.toLong(),
            otherPssKb = mi.otherPss.toLong(),
            totalPssKb = mi.totalPss.toLong(),
            graphicsPssKb = graphicsKb,
            nativeHeapAllocatedBytes = Debug.getNativeHeapAllocatedSize(),
            nativeHeapFreeBytes = Debug.getNativeHeapFreeSize(),
            rssKb = rollup?.get("Rss"),
            pssKb = rollup?.get("Pss"),
            pssAnonKb = rollup?.get("Pss_Anon"),
            pssFileKb = rollup?.get("Pss_File"),
            swapKb = rollup?.get("Swap"),
        )
    }.getOrNull()
}
