package com.pocketforge.ai.data.model

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Registry of installed models, persisted as JSON in the app-private models directory.
 */
data class InstalledModel(
    val id: String,
    val fileName: String,
    val path: String,
    val sizeBytes: Long,
    val source: String, // "download" | "import"
    val shaVerified: Boolean,
    val installedAt: Long,
)

class ModelRegistry private constructor(context: Context) {

    private val modelsDir = File(context.getExternalFilesDir(null), "models").apply { mkdirs() }
    private val registryFile = File(modelsDir, "installed.json")
    private val lock = Any()

    @Volatile
    private var cache: List<InstalledModel> = load()

    companion object {
        @Volatile
        private var instance: ModelRegistry? = null

        fun get(context: Context): ModelRegistry =
            instance ?: synchronized(this) {
                instance ?: ModelRegistry(context.applicationContext).also { instance = it }
            }
    }

    val dir: File get() = modelsDir

    fun all(): List<InstalledModel> = synchronized(lock) { cache }

    fun byId(id: String): InstalledModel? = synchronized(lock) { cache.firstOrNull { it.id == id } }

    fun byFile(path: String): InstalledModel? = synchronized(lock) { cache.firstOrNull { it.path == path } }

    fun register(
        modelId: String,
        fileName: String,
        sizeBytes: Long,
        source: String = "download",
        shaVerified: Boolean,
    ): InstalledModel {
        val model = InstalledModel(
            id = modelId,
            fileName = fileName,
            path = File(modelsDir, fileName).absolutePath,
            sizeBytes = sizeBytes,
            source = source,
            shaVerified = shaVerified,
            installedAt = System.currentTimeMillis(),
        )
        synchronized(lock) {
            cache = cache.filter { it.id != modelId } + model
            persist()
        }
        return model
    }

    fun unregister(modelId: String) {
        synchronized(lock) {
            cache = cache.filter { it.id != modelId }
            persist()
        }
    }

    private fun persist() {
        val arr = JSONArray()
        cache.forEach { m ->
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("fileName", m.fileName)
                    .put("path", m.path)
                    .put("sizeBytes", m.sizeBytes)
                    .put("source", m.source)
                    .put("shaVerified", m.shaVerified)
                    .put("installedAt", m.installedAt),
            )
        }
        val tmp = File(modelsDir, "installed.json.tmp")
        tmp.writeText(JSONObject().put("models", arr).toString(2), Charsets.UTF_8)
        tmp.renameTo(registryFile)
    }

    private fun load(): List<InstalledModel> {
        Log.d("PFLlamaJNI", "registry load: modelsDir=${modelsDir.absolutePath} isFile=${registryFile.isFile} len=${registryFile.length()}")
        if (!registryFile.isFile) return emptyList()
        return try {
            val text = registryFile.readText().trimStart('\uFEFF')
            val arr = JSONObject(text).getJSONArray("models")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                // drop entries whose backing file vanished silently? no: surface through UI checks.
                InstalledModel(
                    id = o.getString("id"),
                    fileName = o.getString("fileName"),
                    path = o.getString("path"),
                    sizeBytes = o.getLong("sizeBytes"),
                    source = o.optString("source", "download"),
                    shaVerified = o.optBoolean("shaVerified", false),
                    installedAt = o.optLong("installedAt", 0),
                )
            }.also { Log.d("PFLlamaJNI", "registry load ok: ${it.size} entries: ${it.joinToString { m -> m.id + "@" + m.path }}") }
        } catch (e: Exception) {
            Log.e("PFLlamaJNI", "installed.json load failed: dir=${registryFile.parent} isFile=${registryFile.isFile} err=$e")
            emptyList()
        }
    }
}
