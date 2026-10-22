package com.pocketforge.ai.data.memory

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/** A parsed process list entry, annotated with app-level flags for the UI. */
data class ProcessMemoryRow(
    val name: String,
    val pid: Int?,
    val pssBytes: Long,
    val isForegroundService: Boolean?,
    val isProtected: Boolean,
    val mayRestart: Boolean,
)

/**
 * Kill eligibility rules for the FULL-mode process list.
 * Protected processes get no kill button; they are either the system itself
 * or things that will instantly restart (and some are safety-relevant).
 */
object MemoryPolicy {

    const val OWN_PACKAGE = "com.pocketforge.ai"

    private val ALWAYS_PROTECTED = setOf(
        OWN_PACKAGE,
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.android.dialer",
        "com.android.incallui",
    )

    /** Pure function - unit-testable without Android. */
    fun isProtected(name: String, extraProtected: Set<String> = emptySet()): Boolean {
        if (name in ALWAYS_PROTECTED) return true
        if (name in extraProtected) return true
        // The test device is a vivo phone: keep OEM housekeeping alive.
        if (name.startsWith("com.vivo.") || name.contains(".vivo.") || name.startsWith("com.bbk.")) return true
        return false
    }

    /**
     * Cheap heuristic only (the "may restart" tag is informational): Play/system
     * and vivo components typically hold foreground services and come back.
     */
    fun mayRestart(name: String): Boolean =
        name.startsWith("com.google.android.") || name.startsWith("com.vivo.") || name.startsWith("com.bbk.")

    /** Context-dependent extras: current launcher package(s) and the default IME package. */
    fun extraProtectedPackages(context: Context): Set<String> {
        val out = mutableSetOf<String>()
        runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolved = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            resolved?.activityInfo?.packageName?.let { out.add(it) }
        }
        runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
                ?.takeIf { it.isNotBlank() }
                ?.let { out.add(it) }
        }
        return out
    }

    fun annotate(entries: List<DumpsysMeminfoParser.ProcessMemory>, extraProtected: Set<String>): List<ProcessMemoryRow> =
        entries.map { entry ->
            ProcessMemoryRow(
                name = entry.name,
                pid = entry.pid,
                pssBytes = entry.pssBytes,
                isForegroundService = entry.isForegroundService,
                isProtected = isProtected(entry.name, extraProtected),
                mayRestart = mayRestart(entry.name),
            )
        }
}
