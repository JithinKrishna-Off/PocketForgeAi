package com.pocketforge.ai.server

import com.pocketforge.ai.inference.ModelRuntimeInfo
import com.pocketforge.ai.inference.TokenStats
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wire-format builders for the PocketForge Phone API v1 (docs/pocketforge-api-v1.md).
 * All JSON goes through org.json per app convention.
 */
internal object PhoneServerContract {

    fun healthJson(): String = JSONObject()
        .put("ok", true)
        .put("pf_api", "v1")
        .put("app", "PocketForgeAI")
        .toString()

    fun errorJson(message: String, code: Int): String = JSONObject()
        .put("error", JSONObject().put("message", message).put("code", code))
        .toString()

    fun pairTokenJson(token: String): String = JSONObject().put("token", token).toString()

    fun statusJson(
        port: Int,
        lan: Boolean,
        stateName: String,
        busy: Boolean,
        runtime: ModelRuntimeInfo?,
        modelName: String?,
        steerActive: Boolean,
        steerSpec: String,
    ): String {
        val model = JSONObject()
            .put("loaded", runtime != null)
            .put("name", modelName.orEmpty())
            .put("desc", runtime?.desc.orEmpty())
            .put("sizeBytes", runtime?.sizeBytes ?: 0)
            .put("nCtx", runtime?.nCtx ?: 0)
            .put("nThreads", runtime?.nThreads ?: 0)
            .put("backend", runtime?.backendDevices?.firstOrNull()?.backend?.lowercase() ?: "cpu")
            .put("steering", JSONObject().put("active", steerActive).put("spec", steerSpec))
        return JSONObject()
            .put("api", "v1")
            .put("server", true)
            .put("port", port)
            .put("lan", lan)
            .put("state", stateName)
            .put("busy", busy)
            .put("model", model)
            .toString()
    }

    fun modelsJson(modelName: String?): String {
        val data = JSONArray()
        if (modelName != null) {
            data.put(
                JSONObject()
                    .put("id", modelName)
                    .put("object", "model")
                    .put("created", System.currentTimeMillis() / 1000)
                    .put("owned_by", "pocketforge"),
            )
        }
        return JSONObject().put("object", "list").put("data", data).toString()
    }

    fun completionJson(
        id: String,
        model: String,
        content: String,
        finishReason: String,
        stats: TokenStats,
        stateName: String,
    ): String = JSONObject()
        .put("id", id)
        .put("object", "chat.completion")
        .put("created", System.currentTimeMillis() / 1000)
        .put("model", model)
        .put("pf_state", stateName)
        .put(
            "choices",
            JSONArray().put(
                JSONObject()
                    .put("index", 0)
                    .put("message", JSONObject().put("role", "assistant").put("content", content))
                    .put("finish_reason", finishReason),
            ),
        )
        .put(
            "usage",
            JSONObject()
                .put("prompt_tokens", stats.promptTokens)
                .put("completion_tokens", stats.generatedTokens)
                .put("prompt_ms", stats.promptMillis)
                .put("gen_ms", stats.generationMillis),
        )
        .toString()

    fun sseDelta(id: String, text: String): String = JSONObject()
        .put("id", id)
        .put(
            "choices",
            JSONArray().put(
                JSONObject()
                    .put("index", 0)
                    .put("delta", JSONObject().put("content", text))
                    .put("finish_reason", JSONObject.NULL),
            ),
        )
        .toString()

    fun sseTerminal(id: String, finishReason: String): String = JSONObject()
        .put("id", id)
        .put(
            "choices",
            JSONArray().put(
                JSONObject()
                    .put("index", 0)
                    .put("delta", JSONObject())
                    .put("finish_reason", finishReason),
            ),
        )
        .toString()
}
