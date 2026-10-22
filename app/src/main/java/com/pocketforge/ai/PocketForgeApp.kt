package com.pocketforge.ai

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.pocketforge.ai.chat.ChatStore
import com.pocketforge.ai.data.catalogue.CatalogRepository
import com.pocketforge.ai.data.model.ModelRegistry
import com.pocketforge.ai.diag.Diagnostics
import com.pocketforge.ai.download.ModelDownloadManager
import com.pocketforge.ai.inference.LlamaInferenceEngine
import com.pocketforge.ai.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Single-process container; app has no DI framework by design. */
class PocketForgeApp : Application() {

    lateinit var catalog: CatalogRepository
        private set
    lateinit var modelRegistry: ModelRegistry
        private set
    lateinit var downloadManager: ModelDownloadManager
        private set
    lateinit var engine: LlamaInferenceEngine
        private set
    lateinit var chatStore: ChatStore
        private set
    lateinit var settingsStore: SettingsStore
        private set
    lateinit var diagnostics: Diagnostics
        private set

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        catalog = CatalogRepository(this)
        modelRegistry = ModelRegistry.get(this)
        downloadManager = ModelDownloadManager.get(this)
        engine = LlamaInferenceEngine.get(this)
        // Chat-at-rest crypto: key material + migration must exist before first ChatStore IO.
        chatStore = runBlocking {
            withContext(Dispatchers.IO) {
                val status = com.pocketforge.ai.crypto.ChatCrypto.ensure(this@PocketForgeApp)
                val store = ChatStore(this@PocketForgeApp)
                val m = store.migrateLegacyPlaintext()
                if (m.failed > 0) Log.w("PFLlamaJNI", "chat migration incomplete: $m")
                Log.i("PFLlamaJNI", "chat crypto: $status, migrated ${m.migrated} legacy files")
                store
            }
        }
        settingsStore = SettingsStore(this)
        diagnostics = Diagnostics(this)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_DOWNLOADS,
                "Model downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progress of model downloads"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_DOWNLOADS = "com.pocketforge.ai.downloads"

        fun container(context: Context): PocketForgeApp = context.applicationContext as PocketForgeApp
    }
}
