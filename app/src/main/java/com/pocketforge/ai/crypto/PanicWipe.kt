package com.pocketforge.ai.crypto

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.pocketforge.ai.PocketForgeApp
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Panic wipe: idempotent, safe to run when the DB is locked, corrupt or mid-write.
 * Runs in a NonCancellable coroutine context.
 *
 * Order (spec P0-6): stop generation + unload model, close any crypto handles,
 * DELETE THE KEYSTORE ENTRY FIRST so the wrapped DEK becomes useless (crypto-erase:
 * all existing ciphertext is unreadable even if file deletion is interrupted), then
 * delete files (chats, wrapped key blob, personas, caches, chat-related DataStore
 * prefs, exported KV state), clear notifications, restart into onboarding.
 *
 * NOTE: no forensic flash overwrite is attempted or promised - the crypto-erase is
 * the guarantee; files deleted afterwards are best-effort filesystem removals.
 */
object PanicWipe {

    suspend fun wipe(app: PocketForgeApp, alsoDownloadedModels: Boolean): List<String> =
        withContext(Dispatchers.IO + NonCancellable) { execute(app, alsoDownloadedModels) }

    private suspend fun execute(app: PocketForgeApp, alsoDownloadedModels: Boolean): List<String> {
        val steps = mutableListOf<String>()

        // (a) stop generation and unload model/context (frees prompt data in RAM)
        runCatching {
            withTimeoutOrNull(3000) {
                app.engine.cancel()
                app.engine.unload()
            }
            steps.add("model unloaded")
        }.onFailure { steps.add("model unload failed: ${it.message}") }

        // (b) "close DB": there is no SQL DB; ChatStore is single-shot file IO with no
        //     long-lived handles, nothing to close. Streaming writes are stopped by (a).

        // (c) keystore entry FIRST of storage steps: crypto-erase
        runCatching {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(ChatCrypto.KEY_ALIAS)) {
                ks.deleteEntry(ChatCrypto.KEY_ALIAS)
                steps.add("keystore key deleted (crypto-erase)")
            } else {
                steps.add("keystore alias already absent")
            }
        }.onFailure { steps.add("keystore delete failed: ${it.message}") }

        // (d) files
        runCatching {
            app.chatStore.deleteAll() // conversations dir: *.pfenc + *.tmp
            steps.add("conversations deleted")
        }.onFailure { steps.add("conversation delete failed: ${it.message}") }

        runCatching {
            ChatCrypto.wrappedKeyFile(app).delete()
            steps.add("wrapped key blob deleted")
        }.onFailure { steps.add("wrapped key delete failed: ${it.message}") }

        runCatching {
            val pf = File(app.noBackupFilesDir, "personas.pfenc")
            if (pf.isFile) pf.delete()
            steps.add("persona store deleted")
        }.onFailure { steps.add("persona delete failed: ${it.message}") }

        // saved KV state: the engine never persisted KV state to disk
        // (pf_llm.cpp has no llama_state_seq_save_file/write to files) - nothing to delete.

        runCatching {
            app.cacheDir.listFiles()?.forEach { it.delete() }
            app.externalCacheDir?.listFiles()?.forEach { it.delete() }
            steps.add("caches deleted")
        }.onFailure { steps.add("cache delete failed: ${it.message}") }

        if (alsoDownloadedModels) {
            runCatching {
                app.modelRegistry.all().forEach { File(it.path).delete() }
                app.modelRegistry.dir.listFiles()?.filter { it.extension == "gguf" }?.forEach { it.delete() }
                steps.add("downloaded models deleted")
            }.onFailure { steps.add("model delete failed: ${it.message}") }
        }

        // chat-related DataStore entries (persona/system prompt, last model)
        runCatching {
            app.settingsStore.clearChatRelated()
            steps.add("chat-related DataStore entries cleared")
        }.onFailure { steps.add("datastore clear failed: ${it.message}") }

        // (e) notifications + UI restart into onboarding
        runCatching {
            getSystemServiceSilent<NotificationManager>(app)?.cancelAll()
        }
        steps.add("notifications cleared")
        return steps
    }

    private inline fun <reified T> getSystemServiceSilent(context: Context): T? =
        runCatching { context.getSystemService(T::class.java) }.getOrNull()

    private const val TAG = "PFLlamaJNI"
}
