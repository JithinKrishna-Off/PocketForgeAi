package com.pocketforge.ai.data.memory

/**
 * Pure-JVM parser for the "Total PSS by process:" section of `dumpsys meminfo`.
 *
 * Handles both known output styles:
 *  - modern (Android 12+): "   12345 kB: com.example.app (pid 1234[, activities])",
 *    including lines with an extra "(contains: ...)" annotation
 *  - older style without pid: "   152 MB: com.android.systemui"
 *
 * Returns entries sorted by PSS descending. Never throws.
 */
object DumpsysMeminfoParser {

    data class ProcessMemory(
        val name: String,
        val pid: Int?,
        val pssBytes: Long,
        val isForegroundService: Boolean? = null,
    )

    private val rowRegex =
        Regex("^(?<value>\\d[\\d,.]*)\\s*(?<unit>[kKmMgG]B?)\\s*:\\s*(?<name>\\S+)(?:\\s+(?<annotation>\\(.*))?\\s*$")
    private val pidRegex = Regex("\\bpid\\s+(\\d+)")

    fun parse(text: String): List<ProcessMemory> {
        val entries = mutableListOf<ProcessMemory>()

        var inSection = false
        for (raw in text.split('\n')) {
            val line = raw.trim()
            if (!inSection) {
                if (line.startsWith("Total PSS by process:")) inSection = true
                continue
            }
            if (line.isEmpty()) {
                if (entries.isNotEmpty()) break
                continue
            }
            val match = rowRegex.matchEntire(line)
            if (match == null) {
                // Blank lines before other sections are handled above; some outputs also
                // omit the separator entirely, so stop at the next section header too.
                if (line.startsWith("Total PSS by")) break
                continue
            }
            val value = match.groups["value"]!!.value.replace(",", "").toLongOrNull() ?: continue
            val unit = match.groups["unit"]!!.value.lowercase()
            val name = match.groups["name"]!!.value
            val annotation = match.groups["annotation"]?.value
                ?.trim()
                ?.removePrefix("(")
                ?.removeSuffix(")")
                ?.takeIf { it.isNotBlank() }
            val bytes = toBytes(value, unit) ?: continue

            entries.add(
                ProcessMemory(
                    name = name,
                    pid = annotation?.let { pidRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() },
                    pssBytes = bytes,
                    isForegroundService = annotation
                        ?.takeIf { it.contains("service", ignoreCase = true) }
                        ?.let { true },
                ),
            )
        }

        return entries.sortedByDescending { it.pssBytes }
    }

    private fun toBytes(kb: Long, unit: String): Long? = when (unit) {
        "kB", "kb", "KB" -> if (kb > Long.MAX_VALUE / 1024) null else kb * 1024
        "mB", "mb", "MB" -> if (kb > Long.MAX_VALUE / 1048576) null else kb * 1048576
        "gB", "gb", "GB" -> if (kb > Long.MAX_VALUE / 1073741824) null else kb * 1073741824
        else -> null
    }
}
