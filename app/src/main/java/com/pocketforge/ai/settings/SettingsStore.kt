package com.pocketforge.ai.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "pocketforge_settings")

/** Default assistant persona; user-visible and editable. */
const val DEFAULT_SYSTEM_PROMPT =
    "You are a helpful assistant running fully offline on the user's phone. Answer concisely."

/** User-adjustable inference + app settings with conservative defaults. */
data class Settings(
    val contextLength: Int = 2048,
    val maxTokens: Int = 512,
    val temperature: Float = 0.8f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val threadCount: Int = 0, // 0 = auto (4 on the target class of devices)
    val safetyMarginMb: Int = 1024,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val lastModelId: String = "",
    val steerEnabled: Boolean = false,
    val steerSpec: String = "14:8949:+1,13:7956:-1",
    val serverEnabled: Boolean = false,
    val serverPort: Int = 8751,
    val serverLanAccess: Boolean = false,
)

data class SettingsKeys(
    val contextLength: Preferences.Key<Int> = intPreferencesKey("context_length"),
    val maxTokens: Preferences.Key<Int> = intPreferencesKey("max_tokens"),
    val temperature: Preferences.Key<Float> = floatPreferencesKey("temperature"),
    val topP: Preferences.Key<Float> = floatPreferencesKey("top_p"),
    val topK: Preferences.Key<Int> = intPreferencesKey("top_k"),
    val threads: Preferences.Key<Int> = intPreferencesKey("threads"),
    val marginMb: Preferences.Key<Int> = intPreferencesKey("safety_margin_mb"),
    val systemPrompt: Preferences.Key<String> = stringPreferencesKey("system_prompt"),
    val lastModelId: Preferences.Key<String> = stringPreferencesKey("last_model_id"),
    val steerEnabled: Preferences.Key<Boolean> = booleanPreferencesKey("steer_enabled"),
    val steerSpec: Preferences.Key<String> = stringPreferencesKey("steer_spec"),
    val serverEnabled: Preferences.Key<Boolean> = booleanPreferencesKey("server_enabled"),
    val serverPort: Preferences.Key<Int> = intPreferencesKey("server_port"),
    val serverLanAccess: Preferences.Key<Boolean> = booleanPreferencesKey("server_lan_access"),
)

class SettingsStore(private val context: Context) {

    private val keys = SettingsKeys()
    val personaStore = PersonaStore(context)

    /** Persona lives in an encrypted file, not the plaintext DataStore. */
    private suspend fun migratePersonaOnce() {
        if (!personaStore.exists()) {
            val legacy = context.dataStore.data.first()[keys.systemPrompt]
            if (legacy != null && legacy.isNotBlank() && legacy != DEFAULT_SYSTEM_PROMPT) {
                personaStore.saveDefault(legacy)
            }
            context.dataStore.edit { it.remove(keys.systemPrompt) }
        }
    }

    val settings: Flow<Settings> = kotlinx.coroutines.flow.flow {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            migratePersonaOnce()
        }
        emitAll(context.dataStore.data.map { p ->
            Settings(
                contextLength = p[keys.contextLength] ?: 2048,
                maxTokens = p[keys.maxTokens] ?: 512,
                temperature = p[keys.temperature] ?: 0.8f,
                topP = p[keys.topP] ?: 0.95f,
                topK = p[keys.topK] ?: 40,
                threadCount = p[keys.threads] ?: 0,
                safetyMarginMb = p[keys.marginMb] ?: 1024,
                systemPrompt = personaStore.loadDefault() ?: DEFAULT_SYSTEM_PROMPT,
                lastModelId = p[keys.lastModelId] ?: "",
                steerEnabled = p[keys.steerEnabled] ?: false,
                steerSpec = p[keys.steerSpec] ?: "14:8949:+1,13:7956:-1",
                serverEnabled = p[keys.serverEnabled] ?: false,
                serverPort = p[keys.serverPort] ?: 8751,
                serverLanAccess = p[keys.serverLanAccess] ?: false,
            )
        })
    }

    suspend fun setContextLength(value: Int) = context.dataStore.edit { it[keys.contextLength] = inRange(value, 512, 131072, step = 512) }
    suspend fun setMaxTokens(value: Int) = context.dataStore.edit { it[keys.maxTokens] = inRange(value, 64, 4096, step = 64) }
    suspend fun setTemperature(value: Float) = context.dataStore.edit { it[keys.temperature] = value.coerceIn(0f, 2f) }
    suspend fun setTopP(value: Float) = context.dataStore.edit { it[keys.topP] = value.coerceIn(0.05f, 1f) }
    suspend fun setTopK(value: Int) = context.dataStore.edit { it[keys.topK] = value.coerceIn(0, 200) }
    suspend fun setThreads(value: Int) = context.dataStore.edit { it[keys.threads] = value.coerceIn(0, 16) }
    suspend fun setSafetyMarginMb(value: Int) = context.dataStore.edit { it[keys.marginMb] = value.coerceIn(256, 4096) }
    suspend fun setSystemPrompt(value: String) {
        personaStore.saveDefault(value.take(4096))
        context.dataStore.edit { it.remove(keys.systemPrompt) }
    }
    suspend fun setLastModelId(value: String) = context.dataStore.edit { it[keys.lastModelId] = value }
    suspend fun setSteerEnabled(value: Boolean) = context.dataStore.edit { it[keys.steerEnabled] = value }
    suspend fun setSteerSpec(value: String) = context.dataStore.edit { it[keys.steerSpec] = value }
    suspend fun setServerEnabled(value: Boolean) = context.dataStore.edit { it[keys.serverEnabled] = value }
    suspend fun setServerPort(value: Int) = context.dataStore.edit { it[keys.serverPort] = value.coerceIn(1024, 65535) }
    suspend fun setServerLanAccess(value: Boolean) = context.dataStore.edit { it[keys.serverLanAccess] = value }

    suspend fun clearSystemPrompt() = context.dataStore.edit {
        it.remove(keys.systemPrompt)
    }

    /** Panic wipe helper: removes chat-related (persona, last model) DataStore entries. */
    suspend fun clearChatRelated() {
        personaStore.saveDefault("")
        try { java.io.File(context.noBackupFilesDir, "personas.pfenc").delete() } catch (_: Exception) {}
        context.dataStore.edit {
            it.remove(keys.systemPrompt)
            it.remove(keys.lastModelId)
        }
    }

    private fun inRange(v: Int, min: Int, max: Int, step: Int): Int =
        ((v.coerceIn(min, max) - min) / step * step + min).coerceIn(min, max)
}
