package com.pocketforge.ai.inference

/**
 * JNI boundary for the native inference layer (pf_llm.cpp).
 *
 * Resource ownership + thread safety:
 *  - All functions must be called from the engine's single-threaded dispatcher; the
 *    native process uses global state and is not thread safe except for [setStopFlag],
 *    which is a relaxed atomic write and may be called from any thread.
 *  - [loadModel] owns the model+context global; [unloadModel] frees them. Loading a
 *    second model without unloading first returns an error string.
 *  - [generateNextToken] pushes one decode step; it must only run while a turn is
 *    active (after [processUserPrompt]).
 */
object LlamaBridge {

    @JvmStatic
    external fun init(nativeLibDir: String)

    @JvmStatic
    external fun loadModel(
        modelPath: String,
        nCtx: Int,
        nThreads: Int,
        nGpuLayers: Int,
        batchSize: Int,
    ): String?

    @JvmStatic
    external fun unloadModel()

    @JvmStatic
    external fun setSampler(temperature: Float, topK: Int, topP: Float, seed: Long): String?

    @JvmStatic
    external fun resetSession()

    /**
     * Records the (possibly partial) assistant turn in the chat-template history so
     * subsequent prompts are formatted consistently. Must be called exactly once per
     * turn from Kotlin, which owns the accumulated turn text.
     */
    @JvmStatic
    external fun finishTurn(assistantText: String)

    /** Adds a message to template history only (no KV processing); used for context restore. */
    @JvmStatic
    external fun addHistoryMessage(role: String, content: String): Int

    @JvmStatic
    external fun processSystemPrompt(systemPrompt: String): Int

    @JvmStatic
    external fun processUserPrompt(userPrompt: String, nPredict: Int): Int

    /** Returns the next token piece (possibly ""), or null when the turn ended. */
    @JvmStatic
    external fun generateNextToken(): String?

    /** Signals cancellation; checked between decode steps. Thread-safe. */
    @JvmStatic
    external fun setStopFlag()

    @JvmStatic
    external fun clearStopFlag()

    /** JSON: runtime info of the loaded model. Throws/returns error JSON when nothing is loaded. */
    @JvmStatic
    external fun modelInfoJson(): String

    /** JSON: measured perf counters of the context (llama_perf_context). */
    @JvmStatic
    external fun contextStateSize(): Long

    @JvmStatic
    external fun perfJson(): String

    /** JSON array: backend devices reported by ggml after backend loading. */
    @JvmStatic
    external fun listBackendsJson(): String

    /** Executes a small real compute workload on the named backend device; JSON report. */
    @JvmStatic
    external fun probeBackendJson(deviceName: String): String

    @JvmStatic
    external fun systemInfo(): String

    /** Last N lines of native engine log (for diagnostics; excludes chat content). */
    @JvmStatic
    external fun getRecentLogs(): Array<String>

    /** JSON: device facts (HWCAP, per-core clocks, chosen CPU variant, mem) + current plan ({"facts":...,"currentPlan":...}). */
    @JvmStatic
    external fun pfPlansJson(): String

    /** Applies a plan JSON (kv types, flash-attn, batch shape, threads; bare plan or full wrapper). Returns error string, null on success. */
    @JvmStatic
    external fun pfSetPlan(plan: String): String?

    @JvmStatic
    external fun shutdown()

    // Turbo (distributed speculative decoding) primitives; null return = feature
    // unavailable or model not loaded. All returns are JSON strings.

    /** Processes this turn's prompts; returns {"ok":<bool>,"next_token":<int>,"committed":<int>,"max_new":<int>}. */
    @JvmStatic
    external fun specTurn(systemPrompt: String?, userPrompt: String, maxTokens: Int): String?

    /** Verifies K drafted token ids in one batch; returns {"accepted":<int>,"text":<str>,"next_token":<int>,"committed":<int>,"done":<bool>,"finish_reason":<str|null>}. */
    @JvmStatic
    external fun specVerify(tokens: IntArray): String?

    /** Ends the turbo turn; returns {"ok":<bool>,"prompt_tokens":<int>,"completion_tokens":<int>,"gen_ms":<long>}. */
    @JvmStatic
    external fun specFinish(text: String): String?

    /** Reports turbo support; returns {"spec_verify":<bool>,"n_vocab":<int>,"greedy_only":<bool>}. */
    @JvmStatic
    external fun specCapabilities(): String?
}
