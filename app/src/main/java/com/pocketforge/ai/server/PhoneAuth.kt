package com.pocketforge.ai.server

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/** Thrown when the pairing endpoint is hammered past the allowed failure rate. */
class PairRateLimitedException : RuntimeException("pairing rate limit")

/**
 * Pairing + bearer tokens for the phone server. The 6-digit code rotates on every
 * server start and is only shown in the settings UI. Tokens (48 hex chars) live in
 * an app-private file in noBackupFilesDir; never logged.
 */
class PhoneAuth(context: Context) {

    private val file = File(context.noBackupFilesDir, "pf_server_tokens.json")
    private val lock = Any()
    private val secureRandom = SecureRandom()
    private val failedAttempts = ArrayDeque<Long>()
    private var code: String? = null

    @Volatile
    private var cachedTokens: List<String>? = null

    /** Fresh pairing code for this server run; called on every successful bind. */
    fun newPairingCode(): String = synchronized(lock) {
        val c = "%06d".format(secureRandom.nextInt(1_000_000))
        code = c
        c
    }

    fun currentCode(): String? = synchronized(lock) { code }

    /**
     * Exchanges a pairing code for a new bearer token. Returns null on a wrong code
     * (counts against the rate limit); throws [PairRateLimitedException] when more
     * than 5 failures happened in the last minute.
     */
    fun exchangeCode(candidate: String): String? = synchronized(lock) {
        pruneFailedLocked()
        if (failedAttempts.size >= MAX_FAILED_ATTEMPTS) throw PairRateLimitedException()
        val expected = code
        val ok = expected != null &&
            candidate.length == expected.length &&
            MessageDigest.isEqual(candidate.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
        if (!ok) {
            failedAttempts.addLast(SystemClock.elapsedRealtime())
            return null
        }
        failedAttempts.clear()
        newTokenLocked().also { saveTokenLocked(it) }
    }

    /** Bearer check for versioned endpoints; constant-time compare against stored tokens. */
    fun authorize(header: String?): Boolean = synchronized(lock) {
        if (header == null) return false
        val value = header.trim()
        if (!value.startsWith("Bearer ", ignoreCase = true)) return false
        val token = value.substring(7).trim()
        if (token.length != TOKEN_CHARS) return false
        loadTokensLocked().any { MessageDigest.isEqual(token.toByteArray(Charsets.UTF_8), it.toByteArray(Charsets.UTF_8)) }
    }

    fun tokenCount(): Int = synchronized(lock) { loadTokensLocked().size }

    /** Clears all tokens (desktop clients must pair again). */
    fun revoke() = synchronized(lock) {
        file.delete()
        cachedTokens = emptyList()
    }

    private fun newTokenLocked(): String {
        val bytes = ByteArray(TOKEN_CHARS / 2)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun loadTokensLocked(): List<String> {
        cachedTokens?.let { return it }
        val list = runCatching {
            if (!file.isFile) emptyList()
            else {
                val arr = JSONArray(file.readText())
                (0 until arr.length()).mapNotNull { i ->
                    arr.optString(i).takeIf { t -> t.length == TOKEN_CHARS && t.all { c -> c.isDigit() || c in 'a'..'f' } }
                }
            }
        }.getOrDefault(emptyList())
        cachedTokens = list
        return list
    }

    private fun saveTokenLocked(token: String) {
        val list = loadTokensLocked() + token
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONArray(list).toString())
        tmp.renameTo(file)
        cachedTokens = list
    }

    private fun pruneFailedLocked() {
        val cutoff = SystemClock.elapsedRealtime() - FAILED_WINDOW_MS
        while (failedAttempts.isNotEmpty() && failedAttempts.first() < cutoff) failedAttempts.removeFirst()
    }

    private companion object {
        const val TOKEN_CHARS = 48
        const val MAX_FAILED_ATTEMPTS = 5
        const val FAILED_WINDOW_MS = 60_000L
    }
}
