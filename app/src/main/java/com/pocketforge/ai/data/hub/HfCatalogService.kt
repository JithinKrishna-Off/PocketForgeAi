package com.pocketforge.ai.data.hub

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/**
 * Read-only Hugging Face Hub catalog access over HttpURLConnection (no HTTP client deps).
 *
 * Endpoints used (verified against the live API):
 * - search: /api/models?search=<terms>+gguf&limit=N (the offset parameter is IGNORED by the
 *   server, so callers paginate by growing the limit and de-duplicating by id)
 * - detail: /api/models/<id>?full=true (adds cardData.license and gguf.architecture)
 * - tree:   /api/models/<id>/tree/<sha>?recursive=true (lfs.oid is the content SHA-256)
 */

sealed interface HfResult<out T> {
    data class Success<T>(val value: T) : HfResult<T>
    data class Failure(val message: String) : HfResult<Nothing>
}

data class HfModelSummary(
    val id: String,
    val author: String,
    val likes: Long,
    val downloads: Long,
    val lastModified: String,
    val sha: String?,
    val tags: List<String>,
    val gated: Boolean,
    val private: Boolean,
    val pipelineTag: String?,
)

data class HfGgufFile(
    val path: String,
    val sizeBytes: Long,
    val sha256: String?,
)

data class HfModelDetail(
    val id: String,
    val author: String,
    val license: String?,
    val architecture: String?,
    val contextLength: Long?,
    val totalGgufBytes: Long?,
    val lastModified: String,
    val sha: String?,
    val tags: List<String>,
    val gated: Boolean,
    val files: List<HfGgufFile>,
    val likes: Long,
    val downloads: Long,
)

object HfCatalogService {

    private const val HOST = "https://huggingface.co"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 5
    private const val USER_AGENT = "PocketForgeAI-hub"
    private const val MAX_SEARCH_LIMIT = 100

    private val repoIdPattern = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
    private val revisionPattern = Regex("^[A-Za-z0-9_.-]+$")
    private val shardPattern = Regex("-000\\d+-of-")

    fun isRepoIdAllowed(repoId: String): Boolean = repoId.matches(repoIdPattern)

    /** URL for a direct file download; null when the repo id, revision or path is not safe. */
    fun resolveUrl(repoId: String, revision: String, filePath: String): String? {
        if (!isRepoIdAllowed(repoId)) return null
        if (!revision.matches(revisionPattern)) return null
        if (filePath.isBlank() || filePath.startsWith("/") || filePath.endsWith("/") ||
            filePath.contains('\\') || filePath.contains("..") || filePath.contains("//")
        ) {
            return null
        }
        if (!filePath.substringAfterLast('/').endsWith(".gguf", ignoreCase = true)) return null
        val segments = filePath.split('/').joinToString("/") { segment ->
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
        return "$HOST/$repoId/resolve/$revision/$segments"
    }

    fun search(query: String, limit: Int): HfResult<List<HfModelSummary>> {
        val terms = query.trim()
        if (terms.isEmpty()) return HfResult.Success(emptyList())
        val lim = limit.coerceIn(1, MAX_SEARCH_LIMIT)
        return guarded {
            val enc = URLEncoder.encode(terms, "UTF-8")
            val body = httpGetString("$HOST/api/models?search=$enc+gguf&limit=$lim")
            HfResult.Success(parseSearch(JSONArray(body)))
        }
    }

    fun detail(repoId: String): HfResult<HfModelDetail> {
        if (!isRepoIdAllowed(repoId)) return HfResult.Failure("Invalid repository id: $repoId")
        return guarded {
            val body = httpGetString("$HOST/api/models/$repoId?full=true")
            val o = JSONObject(body)
            if (o.optBoolean("private", false) || isGatedValue(o.opt("gated"))) {
                throw IOException("Repository is gated or private: sign-in required")
            }
            val sha = o.optString("sha", "").takeIf { it.isNotEmpty() }
            val license = o.optJSONObject("cardData")?.optString("license", "")?.takeIf { it.isNotEmpty() }
            val gguf = o.optJSONObject("gguf")
            val architecture = gguf?.optString("architecture", "")?.takeIf { it.isNotEmpty() }
            val contextLength = gguf?.optLong("context_length", 0L)?.takeIf { it > 0 }
            val totalReported = gguf?.optLong("total", 0L)?.takeIf { it > 0 }
            val files = parseTree(JSONArray(httpGetString("$HOST/api/models/$repoId/tree/${sha ?: "main"}?recursive=true")))
            HfResult.Success(
                HfModelDetail(
                    id = repoId,
                    author = repoId.substringBefore('/'),
                    license = license,
                    architecture = architecture,
                    contextLength = contextLength,
                    totalGgufBytes = totalReported ?: files.sumOf { it.sizeBytes }.takeIf { it > 0 },
                    lastModified = o.optString("lastModified", ""),
                    sha = sha,
                    tags = o.optJSONArray("tags").stringList(),
                    gated = isGatedValue(o.opt("gated")),
                    files = files,
                    likes = o.optLong("likes", 0),
                    downloads = o.optLong("downloads", 0),
                ),
            )
        }
    }

    private fun parseSearch(arr: JSONArray): List<HfModelSummary> {
        val out = ArrayList<HfModelSummary>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isBlank()) continue
            val isPrivate = o.optBoolean("private", false)
            val gated = isGatedValue(o.opt("gated"))
            if (isPrivate || gated) continue
            val tags = o.optJSONArray("tags").stringList()
            val tagsHaveGguf = tags.any { it.equals("gguf", ignoreCase = true) }
            val siblings = o.optJSONArray("siblings")
            var sibHasGguf = false
            if (!tagsHaveGguf && siblings != null) {
                for (s in 0 until siblings.length()) {
                    val sib = siblings.optJSONObject(s) ?: continue
                    if (sib.optString("rfilename", "").endsWith(".gguf", ignoreCase = true)) {
                        sibHasGguf = true
                        break
                    }
                }
            }
            if (!tagsHaveGguf && !sibHasGguf) continue
            out.add(
                HfModelSummary(
                    id = id,
                    author = id.substringBefore('/'),
                    likes = o.optLong("likes", 0),
                    downloads = o.optLong("downloads", 0),
                    lastModified = o.optString("lastModified", ""),
                    sha = o.optString("sha", "").takeIf { it.isNotEmpty() },
                    tags = tags,
                    gated = gated,
                    private = isPrivate,
                    pipelineTag = o.optString("pipeline_tag", "").takeIf { it.isNotEmpty() },
                ),
            )
        }
        return out
    }

    /** Files only: keep .git-unsharded non-shard .gguf entries, smallest first. */
    private fun parseTree(arr: JSONArray): List<HfGgufFile> {
        val files = ArrayList<HfGgufFile>(arr.length())
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            if (!f.optString("type", "").equals("file", ignoreCase = true)) continue
            val path = f.optString("path", "")
            if (!path.endsWith(".gguf", ignoreCase = true)) continue
            if (shardPattern.containsMatchIn(path)) continue
            val lfs = f.optJSONObject("lfs")
            val size = f.optLong("size", 0L).takeIf { it > 0 } ?: lfs?.optLong("size", 0L) ?: 0L
            files.add(
                HfGgufFile(
                    path = path,
                    sizeBytes = size,
                    sha256 = lfs?.optString("oid", "")?.takeIf { it.isNotEmpty() },
                ),
            )
        }
        files.sortBy { it.sizeBytes }
        return files
    }

    private fun isGatedValue(v: Any?): Boolean = when (v) {
        null, JSONObject.NULL, false -> false
        is Boolean -> v
        "auto", "manual" -> true
        else -> v.toString().toBooleanStrictOrNull() ?: true
    }

    private inline fun <T> guarded(block: () -> HfResult<T>): HfResult<T> = try {
        block()
    } catch (e: Exception) {
        HfResult.Failure(e.message ?: e.javaClass.simpleName)
    }

    /** GET with manual redirects (same host allowlist as DownloadWorker). */
    private fun httpGetString(urlStr: String): String {
        var redirects = 0
        var url = URL(urlStr)
        if (!isAllowedHost(url.host)) {
            throw IOException("Unexpected host ${url.host}: only huggingface.co is allowed")
        }
        while (true) {
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept-Encoding", "identity")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            val code = conn.responseCode
            when {
                code in 300..399 -> {
                    val location = conn.getHeaderField("Location")
                        ?: run { conn.disconnect(); throw IOException("Redirect without Location header (HTTP $code)") }
                    conn.disconnect()
                    if (++redirects > MAX_REDIRECTS) throw IOException("Too many redirects")
                    url = URL(url, location)
                    if (url.protocol != "https") throw IOException("Redirect to non-HTTPS host is not allowed")
                    if (!isAllowedHost(url.host)) {
                        throw IOException("Redirect to unexpected host ${url.host} is not allowed")
                    }
                }
                else -> {
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    conn.disconnect()
                    if (code !in 200..299) throw IOException(httpErrorText(code))
                    return body
                }
            }
        }
    }

    private fun isAllowedHost(host: String): Boolean =
        host.equals("huggingface.co", ignoreCase = true) ||
            host.endsWith(".huggingface.co", ignoreCase = true) ||
            host.endsWith(".hf.co", ignoreCase = true)

    private fun httpErrorText(code: Int): String = when (code) {
        404 -> "Not found on the Hugging Face Hub: check the repository id"
        401, 403 -> "The Hugging Face Hub refused access (HTTP $code); the repository may be gated or need sign-in"
        429 -> "The Hugging Face Hub is rate limiting requests (HTTP 429); try again later"
        else -> "Unexpected response from the Hugging Face Hub (HTTP $code)"
    }
}

private fun JSONArray?.stringList(): List<String> {
    if (this == null) return emptyList()
    val out = ArrayList<String>(length())
    for (i in 0 until length()) {
        val s = optString(i, "")
        if (s.isNotEmpty()) out.add(s)
    }
    return out
}
