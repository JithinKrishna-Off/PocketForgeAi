package com.pocketforge.ai.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pocketforge.ai.PocketForgeApp
import com.pocketforge.ai.data.model.ModelRegistry
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Durable model downloader.
 *
 * - HTTPS only; redirects are followed manually with a host allowlist.
 * - Resume via HTTP Range when the server supports it (marker + partial file kept).
 * - SHA-256 is verified against the catalogue value before a model is marked installed.
 * - Progress goes through [DownloadRegistry] plus a foreground notification.
 */
class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_MODEL_ID = "modelId"
        const val MAX_REDIRECTS = 5
        const val PROGRESS_UPDATE_BYTES = 1024L * 1024
    }

    private val downloadsDir get() = File(applicationContext.getExternalFilesDir(null), "downloads")
        .also { it.mkdirs() }
    private val modelsDir get() = File(applicationContext.getExternalFilesDir(null), "models")
        .also { it.mkdirs() }

    override suspend fun doWork(): Result {
        val modelId = inputData.getString(KEY_MODEL_ID)
            ?: return Result.failure()
        val marker = DownloadMarker.load(downloadsDir, modelId)
            ?: let { DownloadRegistry.remove(modelId); return Result.failure() }

        startForegroundNotification(marker)
        DownloadRegistry.update(
            id = modelId,
            phase = DownloadPhase.RUNNING,
            bytesSoFar = if (DownloadMarker.part(downloadsDir, modelId).isFile)
                DownloadMarker.part(downloadsDir, modelId).length() else 0,
            totalBytes = marker.totalBytes,
            error = null,
        )

        val partFile = DownloadMarker.part(downloadsDir, modelId)
        try {
            download(marker, partFile)
        } catch (e: CancelledByUser) {
            DownloadRegistry.update(modelId, phase = DownloadPhase.CANCELLED)
            persistPartial(modelId, partFile)
            return Result.failure()
        } catch (e: IOException) {
            DownloadRegistry.update(modelId, phase = DownloadPhase.FAILED, error = e.message ?: "Download failed")
            persistPartial(modelId, partFile)
            return Result.failure()
        }

        return try {
            verifyAndInstall(modelId, marker, partFile)
            Result.success()
        } catch (e: IOException) {
            DownloadRegistry.update(modelId, phase = DownloadPhase.FAILED, error = e.message ?: "Validation failed")
            if (e is CancelledByUser) Result.failure() else Result.failure()
        }
    }

    private suspend fun startForegroundNotification(marker: DownloadMarker) {
        val notification = NotificationCompat.Builder(applicationContext, PocketForgeApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading model")
            .setContentText(marker.fileName)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            setForeground(
                ForegroundInfo(
                    marker.modelId.hashCode(),
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                ),
            )
        } else {
            setForeground(ForegroundInfo(marker.modelId.hashCode(), notification))
        }
        NotificationManagerCompat.from(applicationContext).notify(marker.modelId.hashCode(), notification)
    }

    private suspend fun download(marker: DownloadMarker, partFile: File) {
        val initial = URL(marker.url)
        require(initial.protocol == "https") { "Model download URL must use HTTPS" }
        require(
            initial.host.endsWith(".huggingface.co") ||
                initial.host.endsWith(".hf.co") ||
                initial.host.equals("huggingface.co", ignoreCase = true),
        ) { "Model download URL points outside the allowed model sources (${initial.host})" }
        var resumeFrom = if (partFile.isFile) partFile.length() else 0L
        var url = URL(marker.url)
        var redirects = 0
        var conn: HttpURLConnection? = null
        while (true) {
            if (isStopped) throw CancelledByUser()
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            c.requestMethod = "GET"
            c.setRequestProperty("Accept-Encoding", "identity")
            if (resumeFrom > 0) c.setRequestProperty("Range", "bytes=$resumeFrom-")
            val code = c.responseCode
            when {
                code in 300..399 -> {
                    val location = c.getHeaderField("Location")
                        ?: throw IOException("Redirect without Location header (HTTP $code)")
                    c.disconnect()
                    if (++redirects > MAX_REDIRECTS) throw IOException("Too many redirects")
                    val next = URL(url, location)
                    if (next.protocol != "https") throw IOException("Redirect to non-HTTPS host is not allowed")
                    if (!next.host.endsWith(".huggingface.co") && !next.host.endsWith(".hf.co")) {
                        throw IOException("Redirect to unexpected host ${next.host} is not allowed")
                    }
                    url = next
                }
                code == 200 || code == 206 -> { conn = c; break }
                code == 416 -> {
                    c.disconnect()
                    resumeFrom = 0
                    partFile.delete()
                }
                else -> {
                    c.disconnect()
                    throw IOException("Server returned HTTP $code for model download")
                }
            }
        }
        val connection = conn!!

        val append = connection.responseCode == 206
        if (!append) {
            resumeFrom = 0
            if (partFile.length() > 0) partFile.delete()
        } else if (partFile.length() == 0L) {
            throw IOException("Range response but no partial data present")
        }
        val totalExpected = marker.totalBytes
        val contentLength = connection.contentLengthLong
        if (contentLength > 0 && totalExpected > 0) {
            val expectedThisRequest = if (append) totalExpected - resumeFrom else totalExpected
            if (contentLength != expectedThisRequest) {
                connection.disconnect()
                throw IOException(
                    "Server reports $contentLength bytes for this request but expected ${expectedThisRequest}",
                )
            }
        }
        DownloadRegistry.update(id = marker.modelId, phase = DownloadPhase.RUNNING, bytesSoFar = resumeFrom)

        try {
            connection.inputStream.use { input ->
                FileOutputStream(partFile, append).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var written = resumeFrom
                    var sinceUpdate = 0L
                    var updateStart = System.currentTimeMillis()
                    var lastBytes = written
                    while (true) {
                        if (isStopped) throw CancelledByUser()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        sinceUpdate += n
                        if (sinceUpdate >= PROGRESS_UPDATE_BYTES) {
                            val now = System.currentTimeMillis()
                            val dt = (now - updateStart).coerceAtLeast(1)
                            DownloadRegistry.update(
                                marker.modelId,
                                bytesSoFar = written,
                                bytesPerSecond = (written - lastBytes) * 1000 / dt,
                            )
                            setProgress(workDataOf(KEY_MODEL_ID to marker.modelId, "bytes" to written))
                            sinceUpdate = 0
                            lastBytes = written
                            updateStart = now
                        }
                    }
                    out.flush()
                }
            }
        } finally {
            connection.disconnect()
        }

        if (totalExpected > 0 && partFile.length() != totalExpected) {
            throw IOException("Connection closed early: got ${partFile.length()} of $totalExpected bytes")
        }
    }

    private suspend fun verifyAndInstall(modelId: String, marker: DownloadMarker, partFile: File) {
        DownloadRegistry.update(modelId, phase = DownloadPhase.VERIFYING)
        if (marker.totalBytes > 0 && partFile.length() != marker.totalBytes) {
            partFile.delete()
            throw IOException("Downloaded ${partFile.length()} bytes does not match expected ${marker.totalBytes}")
        }
        val sha = sha256(modelId, partFile)
        val expected = marker.sha256
        if (expected != null && !sha.equals(expected, ignoreCase = true)) {
            partFile.delete()
            throw IOException("SHA-256 checksum mismatch: file does not match the trusted checksum, download deleted")
        }
        val finalFile = File(modelsDir, marker.fileName)
        partFile.copyTo(finalFile, overwrite = true)
        partFile.delete()
        DownloadMarker.remove(downloadsDir, modelId)
        ModelRegistry.get(applicationContext).register(
            modelId = modelId,
            fileName = finalFile.name,
            sizeBytes = finalFile.length(),
            shaVerified = expected != null,
        )
        DownloadRegistry.update(modelId, phase = DownloadPhase.DONE, bytesSoFar = finalFile.length())
        NotificationManagerCompat.from(applicationContext).notify(
            modelId.hashCode(),
            NotificationCompat.Builder(applicationContext, PocketForgeApp.CHANNEL_DOWNLOADS)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Model installed")
                .setContentText(marker.fileName)
                .setAutoCancel(true)
                .build(),
        )
    }

    private suspend fun persistPartial(modelId: String, partFile: File) {
        DownloadRegistry.update(modelId, bytesSoFar = partFile.length())
    }

    private suspend fun sha256(modelId: String, file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1024 * 1024)
            var n: Int
            var last = System.currentTimeMillis()
            var count = 0L
            while (input.read(buf).also { n = it } > 0) {
                md.update(buf, 0, n)
                count += n
                if (System.currentTimeMillis() - last > 1500) {
                    DownloadRegistry.update(modelId, bytesSoFar = count)
                    last = System.currentTimeMillis()
                }
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

class CancelledByUser : IOException("download cancelled by user")
