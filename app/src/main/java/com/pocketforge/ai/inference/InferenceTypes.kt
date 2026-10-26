package com.pocketforge.ai.inference

import com.pocketforge.ai.data.steering.SteeringCompatibility

/** Requested sampling + context configuration for a loaded model. */
data class InferenceConfiguration(
    val modelPath: String,
    val nCtx: Int = 2048,
    val nThreads: Int = 4,
    val nGpuLayers: Int = 0,
    val batchSize: Int = 512,
    val temperature: Float = 0.8f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val seed: Long = 4242L,
    val steerSpec: String = "",
) {
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (modelPath.isBlank()) errors.add("Model path is empty")
        if (nCtx < 128) errors.add("Context length must be at least 128 tokens")
        if (nCtx > 131072) errors.add("Context length must be at most 131072 tokens")
        if (nThreads < 1) errors.add("Thread count must be at least 1")
        if (nThreads > 16) errors.add("Thread count must be at most 16")
        if (nGpuLayers < 0) errors.add("GPU layers cannot be negative")
        if (batchSize < 1 || batchSize > 4096) errors.add("Batch size must be between 1 and 4096")
        if (temperature < 0f || temperature > 4f) errors.add("Temperature must be between 0 and 4")
        if (topP !in 0f..1f) errors.add("top_p must be between 0 and 1")
        if (topK < 0) errors.add("top_k cannot be negative")
        if (steerSpec.isBlank()) {
            // blank spec = steering disabled; nothing to check
        } else {
            errors.addAll(SteeringCompatibility.validateSpec(steerSpec).map { "steer spec: $it" })
        }
        return errors
    }
}

sealed class EngineState {
    data object Uninitialized : EngineState()
    data object Initializing : EngineState()
    data object Initialized : EngineState()
    data object LoadingModel : EngineState()
    data object ModelReady : EngineState()
    data object ProcessingUserPrompt : EngineState()
    data object Generating : EngineState()
    data class Error(val error: Throwable) : EngineState()

    val isBusy: Boolean
        get() = this is LoadingModel || this is ProcessingUserPrompt || this is Generating
}

sealed class StreamEvent {
    data class Token(val text: String) : StreamEvent()
    data class Done(
        /** What ended the turn. */
        val reason: FinishReason,
        /** Raw generated text for this turn (post stop-trim, may be empty). */
        val text: String,
        val stats: TokenStats,
    ) : StreamEvent()

    data class Error(val error: Throwable) : StreamEvent()
}

enum class FinishReason { END_OF_TURN, STOP_SEQUENCE, MAX_TOKENS, CANCELLED, ERROR }

/** Measured generation timings from llama.cpp for one turn (not estimates). */
data class TokenStats(
    val promptTokens: Int,
    val promptMillis: Long,
    val generatedTokens: Int,
    val generationMillis: Long,
) {
    val promptTokensPerSecond: Double
        get() = if (promptMillis > 0) promptTokens * 1000.0 / promptMillis else 0.0

    val generationTokensPerSecond: Double
        get() = if (generationMillis > 0) generatedTokens * 1000.0 / generationMillis else 0.0
}

/** One compute backend device visible to the engine (verified post-init). */
data class BackendDeviceInfo(
    val backend: String,
    val deviceName: String,
    val description: String,
    val type: String, // "CPU" | "GPU" | "ACCEL"
    val freeMemoryBytes: Long?,
    val totalMemoryBytes: Long?,
)

enum class GpuCheckStep { ENUMERATED, INITIALIZED, COMPUTED, RESULT_MATCHED }

data class GpuProbeResult(
    val deviceName: String,
    val dataType: String, // "f32", "f16", "q4_K"
    val ok: Boolean,
    val failedStep: GpuCheckStep?,
    val detail: String,
)

data class GpuProbeReport(
    val gpuDevices: List<BackendDeviceInfo>,
    val results: List<GpuProbeResult>,
    val overallOk: Boolean,
    val summary: String,
)

/** Runtime info reported by the engine after a successful load. */
data class ModelRuntimeInfo(
    val desc: String,
    val sizeBytes: Long,
    val parameterCount: Long,
    val nCtxTrain: Long,
    val nCtx: Long,
    val nThreads: Int,
    val nativeInfo: String,
    val backendDevices: List<BackendDeviceInfo>,
    val requestedGpuLayers: Int,
)
