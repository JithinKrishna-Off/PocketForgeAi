package com.pocketforge.ai.settings

import android.content.Context
import android.util.Log
import com.pocketforge.ai.crypto.ChatCrypto
import org.json.JSONObject
import java.io.File

/**
 * Persona (system prompt) storage, encrypted at rest via ChatCrypto.
 * Replaces the plaintext DataStore-backed systemPrompt. The DataStore entry is migrated
 * out once this file exists (see [migrateFromDatastore] called by PanicWipe/SettingsStore).
 */
class PersonaStore(private val context: Context) {

    private val file: File by lazy {
        File(context.noBackupFilesDir, "personas.pfenc")
    }

    fun loadDefault(): String? = try {
        if (!file.isFile) null
        else {
            val plain = ChatCrypto.decrypt(context, file.readBytes())
            JSONObject(String(plain, Charsets.UTF_8))
                .optString(KEY_DEFAULT)
                .takeIf { it.isNotBlank() }
        }
    } catch (t: Throwable) {
        Log.e(TAG, "persona load failed: $t")
        null
    }

    fun saveDefault(prompt: String) {
        try {
            val json = JSONObject().put(KEY_DEFAULT, prompt.take(4096))
            val enc = ChatCrypto.encrypt(context, json.toString().toByteArray(Charsets.UTF_8))
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeBytes(enc)
            tmp.renameTo(file)
        } catch (t: Throwable) {
            Log.e(TAG, "persona save failed: $t")
        }
    }

    /** No-op when the encrypted file already exists; else returns the prompt to seed it. */
    fun migrateFromDatastore(): Boolean {
        if (file.isFile) return false
        return true
    }

    fun exists(): Boolean = file.isFile

    private companion object {
        const val KEY_DEFAULT = "default"
        const val TAG = "PFLlamaJNI"
    }
}
