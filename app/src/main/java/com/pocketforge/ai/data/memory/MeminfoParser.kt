package com.pocketforge.ai.data.memory

/**
 * Pure-JVM parser for /proc/meminfo (values are kB). Also reused for
 * /proc/self/smaps_rollup, which uses the same "Key:  value kB" layout.
 *
 * The parser must never throw: missing lines become null fields, unknown lines are ignored.
 */
object MeminfoParser {

    data class MeminfoSnapshot(
        val memTotalKb: Long?,
        val memAvailableKb: Long?,
        val memFreeKb: Long?,
        val cachedKb: Long?,
        val buffersKb: Long?,
        val anonPagesKb: Long?,
        val mappedKb: Long?,
        val shmemKb: Long?,
        val swapTotalKb: Long?,
        val swapFreeKb: Long?,
    ) {
        /** Cached minus Shmem: the part of the page cache that can be dropped to reclaim RAM. */
        val cachedReclaimableKb: Long?
            get() = cachedKb?.let { cached -> maxOf(0L, cached - (shmemKb ?: 0L)) }

        val memTotalBytes: Long? get() = memTotalKb?.safeBytes()
        val memAvailableBytes: Long? get() = memAvailableKb?.safeBytes()

        /** MemAvailable plus still-resident reclaimable page cache, in bytes.
         *  mmap-backed model files are file-backed page cache which the kernel can drop
         *  under pressure, so that cache counts towards what a new load can consume. */
        val availableWithReclaimableCacheBytes: Long?
            get() = memAvailableKb?.let { it.safeBytes() + (cachedReclaimableKb ?: 0L).safeBytes() }

        private fun Long.safeBytes(): Long = if (this > Long.MAX_VALUE / 1024) Long.MAX_VALUE else this * 1024
    }

    /** Parses "MemTotal:  123 kB"-style text. Only known keys are kept; junk lines ignored. */
    fun parse(text: String): MeminfoSnapshot {
        val kv = parseKv(text)
        return MeminfoSnapshot(
            memTotalKb = kv["MemTotal"],
            memAvailableKb = kv["MemAvailable"],
            memFreeKb = kv["MemFree"],
            cachedKb = kv["Cached"],
            buffersKb = kv["Buffers"],
            anonPagesKb = kv["AnonPages"],
            mappedKb = kv["Mapped"],
            shmemKb = kv["Shmem"],
            swapTotalKb = kv["SwapTotal"],
            swapFreeKb = kv["SwapFree"],
        )
    }

    /** Extracts every "Key:  123 kB" (or bare integer) pair into a map. Never throws. */
    fun parseKv(text: String): Map<String, Long> {
        val out = mutableMapOf<String, Long>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            val colon = line.indexOf(':')
            if (colon <= 0) return@forEach
            val key = line.substring(0, colon).trim()
            if (key.isEmpty() || key.containsKeySpace()) return@forEach
            val valuePart = line.substring(colon + 1).trim()
            val number = valuePart.substringBefore(' ').replace(",", "")
            val kb = number.toLongOrNull() ?: return@forEach
            out[key] = kb
        }
        return out
    }

    private fun String.containsKeySpace(): Boolean = any { it.isWhitespace() }
}
