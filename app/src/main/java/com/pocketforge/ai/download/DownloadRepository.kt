package com.pocketforge.ai.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File

/**
 * Download state machine observed by the UI.
 *
 * NOT_STARTED      no work done for this id yet
 * QUEUED           work scheduled but worker not started
 * RUNNING          transferring bytes
 * PAUSED           partial file kept, work stopped (system stop or process death)
 * VERIFYING        SHA-256 check in progress
 * DONE             validated and installed into the models directory
 * FAILED           terminal failure; error explains why (space, checksum, network...)
 * CANCELLED        user cancelled; partial file kept for resume
 */
enum class DownloadPhase { NOT_STARTED, QUEUED, RUNNING, PAUSED, VERIFYING, DONE, FAILED, CANCELLED }

data class DownloadInfo(
    val modelId: String,
    val phase: DownloadPhase,
    val bytesSoFar: Long = 0,
    val totalBytes: Long = 0,
    val bytesPerSecond: Long = 0,
    val error: String? = null,
)

object DownloadRegistry {
    private val states = MutableStateFlow<Map<String, DownloadInfo>>(emptyMap())
    val userCancelled = java.util.Collections.synchronizedSet(HashSet<String>())

    fun observe(): StateFlow<Map<String, DownloadInfo>> = states

    fun get(id: String): DownloadInfo? = states.value[id]

    fun update(
        id: String,
        phase: DownloadPhase? = null,
        bytesSoFar: Long? = null,
        bytesPerSecond: Long? = null,
        totalBytes: Long? = null,
        error: String? = null,
    ) {
        states.update { map ->
            val cur = map[id] ?: DownloadInfo(id, DownloadPhase.NOT_STARTED)
            val next = cur.copy(
                phase = phase ?: cur.phase,
                bytesSoFar = bytesSoFar ?: cur.bytesSoFar,
                bytesPerSecond = bytesPerSecond ?: cur.bytesPerSecond,
                totalBytes = totalBytes ?: cur.totalBytes,
                error = error ?: cur.error,
            )
            map + (id to next)
        }
    }

    fun remove(id: String) {
        states.update { map -> map - id }
    }
}

/** Metadata for a resumable download, persisted next to the partial file.
 *  Hub downloads add provenance (repo/revision/displayName/author); catalogue downloads leave them empty. */
data class DownloadMarker(
    val modelId: String,
    val url: String,
    val fileName: String,
    val totalBytes: Long,
    val sha256: String? = null,
    val supportsRange: Boolean? = null,
    val repo: String = "",
    val revision: String = "",
    val displayName: String = "",
    val author: String = "",
) {
    companion object {
        fun file(downloadsDir: File, modelId: String) = File(downloadsDir, "$modelId.download.json")
        fun part(downloadsDir: File, modelId: String) = File(downloadsDir, "$modelId.part")

        fun write(dir: File, marker: DownloadMarker) {
            file(dir, marker.modelId).writeText(
                JSONObject()
                    .put("modelId", marker.modelId)
                    .put("url", marker.url)
                    .put("fileName", marker.fileName)
                    .put("totalBytes", marker.totalBytes)
                    .put("sha256", marker.sha256 ?: JSONObject.NULL)
                    .put("supportsRange", JSONObject.NULL.takeIf { marker.supportsRange == null } ?: marker.supportsRange)
                    .put("repo", marker.repo)
                    .put("revision", marker.revision)
                    .put("displayName", marker.displayName)
                    .put("author", marker.author)
                    .toString(2)
            )
        }

        fun load(dir: File, modelId: String): DownloadMarker? {
            val f = file(dir, modelId)
            if (!f.isFile) return null
            return try {
                val o = JSONObject(f.readText())
                val range = o.opt("supportsRange")
                DownloadMarker(
                    modelId = o.getString("modelId"),
                    url = o.getString("url"),
                    fileName = o.getString("fileName"),
                    totalBytes = o.getLong("totalBytes"),
                    sha256 = o.optString("sha256", "").takeIf { it.isNotEmpty() },
                    supportsRange = when (range) {
                        JSONObject.NULL, null -> null
                        is Boolean -> range
                        else -> range.toString().toBooleanStrictOrNull()
                    },
                    repo = o.optString("repo", ""),
                    revision = o.optString("revision", ""),
                    displayName = o.optString("displayName", ""),
                    author = o.optString("author", ""),
                )
            } catch (_: Exception) {
                null
            }
        }

        fun remove(dir: File, modelId: String) {
            file(dir, modelId).delete()
            part(dir, modelId).delete()
            File(dir, "$modelId.importing.json").delete()
        }
    }
}

/** Make a file name safe: basename only, no traversal, sane length. */
fun sanitizeFileName(name: String): String {
    val base = name.substringAfterLast('/').substringAfterLast('\\')
    val cleaned = base.replace(Regex("[^A-Za-z0-9._\\- ()+]"), "_").trim()
    if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return "model.gguf"
    return cleaned.take(160)
}
