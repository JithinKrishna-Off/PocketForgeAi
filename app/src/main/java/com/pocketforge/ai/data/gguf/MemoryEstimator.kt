package com.pocketforge.ai.data.gguf

/**
 * Conservative memory estimation for loading a GGUF model at a given context length.
 *
 * All numbers are ESTIMATES for a pre-load safety check; the Performance screen shows
 * measured values afterwards. Breakdown:
 *  - weights: the on-disk model file is memory-mapped by llama.cpp (resident parts count
 *    against app RSS).
 *  - kv cache: 2 tensors per layer (K, V), fp16 = 2 bytes/elem, n_embd_gqa elems per token
 *    per tensor.
 *  - compute: heuristic scratch/compute-graph allowance; llama.cpp also mmaps + allocates
 *    work buffers.
 *  - margin: user-configurable safety margin (default 1 GiB) that stays available for the
 *    OS, other apps, and transient peaks.
 */
object MemoryEstimator {

    const val DEFAULT_BATCH_SIZE = 512

    data class Breakdown(
        val contextLength: Int,
        val weightsBytes: Long,
        val kvBytes: Long,
        val computeBytes: Long,
        val marginBytes: Long,
    ) {
        val totalBytes: Long get() = weightsBytes + kvBytes + computeBytes + marginBytes

        fun fitsIn(availableBytes: Long): Boolean = totalBytes <= availableBytes

        fun explain(availableBytes: Long): String {
            val headroom = availableBytes - totalBytes
            val totalGb = totalBytes / (1024.0 * 1024 * 1024)
            val availGb = availableBytes / (1024.0 * 1024 * 1024)
            return if (headroom >= 0) {
                "Estimated %.2f GiB needed, %.2f GiB available (includes %.2f GiB safety margin), headroom %.2f GiB."
                    .format(totalGb, availGb, marginBytes / (1024.0 * 1024 * 1024), headroom / (1024.0 * 1024 * 1024))
            } else {
                "Estimated %.2f GiB needed but only %.2f GiB available. Too risky to load at this context length."
                    .format(totalGb, availGb)
            }
        }
    }

    /** Number of elements per token in one KV tensor (K or V) for this model. */
    fun nEmbdGqa(meta: GgufMetadata): Long? {
        val nEmbd = meta.embeddingLength ?: return null
        val nHeads = meta.headCount ?: return null
        val nHeadsKv = meta.headCountKv ?: nHeads
        if (nHeads <= 0 || nHeadsKv <= 0) return null
        val headDim = meta.keyLength ?: meta.valueLength
            ?: run {
                if (nHeads == 0L) return null else nEmbd / nHeads
            }
        return headDim * nHeadsKv
    }

    fun breakdown(
        meta: GgufMetadata,
        fileSizeBytes: Long,
        contextLength: Int,
        safetyMarginBytes: Long,
        batchSize: Int = DEFAULT_BATCH_SIZE,
    ): Breakdown? {
        val blockCount = meta.blockCount ?: return null
        val nEmbdGqa = nEmbdGqa(meta) ?: return null
        if (blockCount <= 0 || nEmbdGqa <= 0) return null

        // fp16 KV (no quantized KV cache, no flash-attention quantization assumed)
        val kvPerToken = blockCount * 2 * nEmbdGqa * 2 // layers * (K+V) * elems * 2 bytes
        val kvBytes = kvPerToken * contextLength

        // Heuristic compute/scratch allowance: batch activations + graph allocator overhead.
        // Grows mildly with context (attention scratch) and batch size.
        val nEmbd = meta.embeddingLength ?: contextLength.toLong() * 2
        val activations = batchSize * nEmbd * 8L * 4L
        val attentionScratch = contextLength.toLong() * nEmbd / 8L * 4L
        val computeHeuristic = (activations + attentionScratch)
            .coerceIn(192L * 1024 * 1024, 1400L * 1024 * 1024)

        return Breakdown(
            contextLength = contextLength,
            weightsBytes = fileSizeBytes,
            kvBytes = kvBytes,
            computeBytes = computeHeuristic,
            marginBytes = safetyMarginBytes,
        )
    }
}
