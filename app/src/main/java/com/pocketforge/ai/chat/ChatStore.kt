package com.pocketforge.ai.chat

import android.content.Context
import android.util.Log
import com.pocketforge.ai.crypto.ChatCrypto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Local-only conversation store. Message text is AES-256-GCM encrypted at rest
 * (ChatCrypto): each conversation is stored as its own encrypted document
 * (<id>.pfenc) in the app-private conversations directory.
 *
 * Legacy plaintext *.json files from earlier builds are migrated once to the encrypted
 * form; the plaintext file is deleted only after the encrypted copy verifies
 * (row count + message-text checksum). The migration is resumable and idempotent:
 * it simply reprocesses whatever ~/.json files remain.
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
)

data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val messages: List<ChatMessage>,
)

class ChatStore(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "conversations").apply { mkdirs() }
    private var contextRef: Context = context.applicationContext

    private val _unreadable = MutableStateFlow(false)
    /** True when ciphertext exists but cannot be decrypted (wrong/corrupted key). */
    val unreadable: StateFlow<Boolean> = _unreadable.asStateFlow()

    data class MigrationResult(val migrated: Int, val failed: Int, val failedIds: List<String>)

    private fun jsonOf(c: Conversation): ByteArray {
        val arr = JSONArray()
        c.messages.forEach { m ->
            arr.put(JSONObject().put("role", m.role).put("content", m.content).put("timestamp", m.timestamp))
        }
        return JSONObject()
            .put("id", c.id)
            .put("title", c.title)
            .put("createdAt", c.createdAt)
            .put("messages", arr)
            .toString(2)
            .toByteArray(Charsets.UTF_8)
    }

    fun list(): List<Conversation> = dir.listFiles { f -> f.isFile && f.extension == "pfenc" }
        ?.mapNotNull { read(it) }
        ?.sortedByDescending { it.createdAt }
        ?: emptyList()

    fun get(id: String): Conversation? = read(File(dir, "$id.pfenc"))

    fun create(id: String = "conv-${System.currentTimeMillis()}", title: String = "New chat"): Conversation {
        val conv = Conversation(id = id, title = title, createdAt = System.currentTimeMillis(), messages = emptyList())
        write(conv)
        return conv
    }

    fun save(conversation: Conversation) {
        write(conversation)
    }

    /**
     * Streaming checkpoint: append-or-replace the trailing assistant message so the
     * on-disk conversation reflects partial generation every ~2 s.
     */
    fun upsertAssistantPartial(id: String, partialText: String) {
        val existing = read(File(dir, "$id.pfenc")) ?: return
        val msgs = existing.messages.toMutableList()
        if (msgs.isNotEmpty() && msgs.last().role == "assistant") {
            msgs[msgs.size - 1] = msgs.last().copy(content = partialText, timestamp = System.currentTimeMillis())
        } else {
            msgs.add(ChatMessage("assistant", partialText))
        }
        write(existing.copy(messages = msgs))
    }

    fun delete(id: String) {
        File(dir, "$id.pfenc").delete()
        File(dir, "$id.pfenc.tmp").delete()
    }

    fun deleteAll() {
        // include tmp files; conversation dir only (models ets live elsewhere)
        dir.listFiles()?.forEach { it.delete() }
        _unreadable.value = false
    }

    /** Chats-only reset used by the recovery screen; does not touch the Keystore key. */
    fun resetBecauseUnreadable(): Boolean {
        return try {
            deleteAll()
            _unreadable.value = false
            true
        } catch (t: Throwable) {
            Log.e(TAG, "chat reset failed: $t")
            false
        }
    }

    fun newTitleFor(messages: List<ChatMessage>, existingTitle: String): String =
        if (existingTitle != "New chat") existingTitle
        else messages.firstOrNull { it.role == "user" }?.content?.take(40)?.let { if (it.length == 40) "$it..." else it }
            ?: existingTitle

    private fun read(f: File): Conversation? {
        if (!f.isFile) return null
        return try {
            val plain = ChatCrypto.decrypt(contextRef, f.readBytes())
            parse(String(plain, Charsets.UTF_8))
        } catch (rec: ChatCrypto.RecoveryException) {
            _unreadable.value = true
            null
        } catch (aeae: javax.crypto.AEADBadTagException) {
            Log.e(TAG, "chat decrypt auth failure (wrong key or corrupted): ${f.name}")
            _unreadable.value = true
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun parse(text: String): Conversation? = try {
        val o = JSONObject(text)
        val arr = o.getJSONArray("messages")
        Conversation(
            id = o.getString("id"),
            title = o.getString("title"),
            createdAt = o.getLong("createdAt"),
            messages = (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                ChatMessage(m.getString("role"), m.getString("content"), m.getLong("timestamp"))
            },
        )
    } catch (_: Exception) {
        null
    }

    private fun write(c: Conversation) {
        try {
            val enc = ChatCrypto.encrypt(contextRef, jsonOf(c))
            val dest = File(dir, "${c.id}.pfenc")
            val tmp = File(dir, "${c.id}.pfenc.tmp")
            tmp.writeBytes(enc)
            if (!tmp.renameTo(dest)) {
                tmp.delete(); dest.delete(); tmp.writeBytes(enc)
                tmp.renameTo(dest)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "chat write failed: $t")
        }
    }

    // -------- Migration from the legacy plaintext store --------

    /**
     * One atomic pass per legacy file: decrypt target chain is: read plaintext,
     * marshal JSON, encrypt, write .pfenc.tmp, rename to .pfenc, verify
     * (re-decrypt + compare message count + text checksum), then delete the
     * plaintext file and its .tmp. Failures keep the plaintext file untouched.
     */
    fun migrateLegacyPlaintext(): MigrationResult {
        var migrated = 0
        val failed = mutableListOf<String>()
        val legacy = dir.listFiles { f -> f.isFile && f.extension == "json" } ?: emptyArray()
        for (src in legacy) {
            val id = src.name.removeSuffix(".json")
            if (id.endsWith(".tmp")) { src.delete(); continue }
            try {
                val plaintext = src.readText()
                val conv = parse(plaintext) ?: throw IllegalArgumentException("unparseable legacy conversation")
                val enc = ChatCrypto.encrypt(contextRef, jsonOf(conv))
                val tmp = File(dir, "${conv.id}.pfenc.tmp")
                tmp.writeBytes(enc)
                val dest = File(dir, "${conv.id}.pfenc")
                if (!tmp.renameTo(dest)) throw IllegalStateException("rename failed")
                // verify
                val d = ChatCrypto.decrypt(contextRef, dest.readBytes())
                val verified = parse(String(d, Charsets.UTF_8))
                val ok = verified != null &&
                    verified.messages.size == conv.messages.size &&
                    verified.textChecksum() == conv.textChecksum()
                if (!ok) {
                    throw IllegalStateException("post-encryption verification mismatch for $id")
                }
                src.delete()
                File(dir, "$id.json.tmp").delete()
                migrated++
            } catch (t: Throwable) {
                Log.e(TAG, "migration of $id failed: $t")
                failed.add(id)
            }
        }
        return MigrationResult(migrated, failed.size, failed)
    }

    private fun Conversation.textChecksum(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        messages.forEach { digest.update(it.content.toByteArray(Charsets.UTF_8)); digest.update(0.toByte()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Returns true if any legacy plaintext JSON still exists (for status UI/tests). */
    fun hasLegacyPlaintext(): Boolean =
        dir.listFiles { f -> f.isFile && (f.extension == "json" || f.name.endsWith(".json.tmp")) }
            ?.isNotEmpty() == true

    private companion object {
        const val TAG = "PFLlamaJNI"
    }
}
