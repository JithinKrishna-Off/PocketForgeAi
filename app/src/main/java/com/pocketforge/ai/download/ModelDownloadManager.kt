package com.pocketforge.ai.download

import android.content.Context
import android.os.StatFs
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.pocketforge.ai.data.catalogue.CatalogueModel
import com.pocketforge.ai.data.model.ModelRegistry
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Preferred download only over an unmetered connection? Test models are small; allow all networks. */
class ModelDownloadManager private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: ModelDownloadManager? = null

        fun get(context: Context): ModelDownloadManager =
            instance ?: synchronized(this) {
                instance ?: ModelDownloadManager(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    val modelsDir = File(appContext.getExternalFilesDir(null), "models").apply { mkdirs() }
    val downloadsDir = File(appContext.getExternalFilesDir(null), "downloads").apply { mkdirs() }
    private val registry = ModelRegistry.get(appContext)

    fun storageCheck(sizeBytes: Long): String? {
        val stat = StatFs(modelsDir.absolutePath)
        val free = stat.availableBytes
        val needed = sizeBytes + (sizeBytes / 20) + (256L * 1024 * 1024)
        return if (free < needed) {
            String.format(
                "Not enough storage: %.2f GiB free, need about %.2f GiB.",
                free / 1073741824.0, needed / 1073741824.0,
            )
        } else {
            null
        }
    }

    fun startOrResume(model: CatalogueModel): String? {
        registry.byId(model.id)?.let { installed ->
            val f = File(installed.path)
            if (f.isFile) return "Model already installed"
            registry.unregister(model.id)
        }
        storageCheck(model.sizeBytes)?.let { return it }
        DownloadRegistry.update(
            model.id,
            phase = DownloadPhase.QUEUED,
            bytesSoFar = DownloadMarker.part(downloadsDir, model.id).length(),
            totalBytes = model.sizeBytes,
            error = null,
        )
        DownloadMarker.write(
            downloadsDir,
            DownloadMarker(
                modelId = model.id,
                url = model.source.url,
                fileName = model.source.file,
                totalBytes = model.sizeBytes,
                sha256 = model.sha256,
                supportsRange = null,
            ),
        )
        DownloadRegistry.userCancelled.remove(model.id)
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_MODEL_ID to model.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(appContext)
            .enqueueUniqueWork("download_${model.id}", ExistingWorkPolicy.KEEP, request)
        return null
    }

    fun cancel(modelId: String) {
        DownloadRegistry.userCancelled.add(modelId)
        WorkManager.getInstance(appContext).cancelUniqueWork("download_$modelId")
    }

    fun clearFailed(modelId: String) {
        DownloadMarker.remove(downloadsDir, modelId)
        DownloadRegistry.remove(modelId)
    }

    fun byPhase(modelId: String): DownloadPhase? = DownloadRegistry.get(modelId)?.phase

    fun deleteInstalled(modelId: String): String? {
        val installed = registry.byId(modelId) ?: return "Not installed"
        val f = File(installed.path)
        if (f.isFile && !f.delete()) return "Could not delete model file"
        registry.unregister(modelId)
        return null
    }

    /**
     * Imports a GGUF file chosen through the Storage Access Framework.
     * Streams to a temporary file while hashing; validates the GGUF header before
     * marking the model installed. Arbitrary imports cannot be checksum-verified
     * against a trusted source unless the caller provides a known digest.
     */
    suspend fun importGguf(
        displayName: String,
        openStream: () -> InputStream,
        onProgress: (bytesRead: Long) -> Unit,
    ): Result<InstalledImportResult> {
        val safeName = sanitizeFileName(displayName)
        if (!safeName.endsWith(".gguf", ignoreCase = true)) {
            return Result.failure(java.io.IOException("The selected file is not a .gguf file"))
        }
        val part = File(downloadsDir, "import_${safeName}")
        val md = MessageDigest.getInstance("SHA-256")
        try {
            openStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    var n: Int
                    var count = 0L
                    var lastProgress = 0L
                    while (input.read(buf).also { n = it } > 0) {
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        count += n
                        if (count - lastProgress > 1024L * 1024 * 8) {
                            onProgress(count)
                            lastProgress = count
                            val stat = StatFs(downloadsDir.absolutePath)
                            if (stat.availableBytes < count + 256L * 1024 * 1024) {
                                throw java.io.IOException("Ran out of storage while importing")
                            }
                        }
                    }
                    out.flush()
                }
            }
            val meta = com.pocketforge.ai.data.gguf.GgufParser.parse(part)

            val sha = md.digest().joinToString("") { "%02x".format(it) }
            val finalName = if (File(modelsDir, safeName).isFile) {
                val dot = safeName.lastIndexOf('.')
                safeName.substring(0, dot) + "-1" + safeName.substring(dot)
            } else {
                safeName
            }
            val finalFile = File(modelsDir, finalName)
            part.copyTo(finalFile, overwrite = true)
            part.delete()
            val importId = "import-${finalFile.hashCode()}-${System.currentTimeMillis() / 1000}"
            val installed = registry.register(
                modelId = importId,
                fileName = finalName,
                sizeBytes = finalFile.length(),
                source = "import",
                shaVerified = false,
            )
            return Result.success(
                InstalledImportResult(
                    installed = installed,
                    sha256 = sha,
                    architecture = meta.architecture,
                    quantization = meta.quantName(),
                    contextLength = meta.contextLengthOr(0),
                ),
            )
        } catch (e: Exception) {
            part.delete()
            return Result.failure(e)
        }
    }
}

data class InstalledImportResult(
    val installed: com.pocketforge.ai.data.model.InstalledModel,
    val sha256: String,
    val architecture: String,
    val quantization: String,
    val contextLength: Long,
)
