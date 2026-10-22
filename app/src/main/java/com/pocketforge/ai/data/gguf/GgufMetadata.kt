package com.pocketforge.ai.data.gguf

/**
 * Parsed GGUF header/metadata. Values are typed per the GGUF spec (v2/v3).
 */
sealed class GgufValue {
    data class UInt64(val value: Long) : GgufValue()
    data class Int64(val value: Long) : GgufValue()
    data class UInt32(val value: Int) : GgufValue()
    data class Int32(val value: Int) : GgufValue()
    data class Float64(val value: Double) : GgufValue()
    data class Float32(val value: Float) : GgufValue()
    data class Bool(val value: Boolean) : GgufValue()
    data class Str(val value: String) : GgufValue()
    data class Array(val elementType: Int, val length: Long) : GgufValue()
}

enum class GgufDataType(val id: Int, val sizeBytes: Int) {
    UINT8(0, 1),
    INT8(1, 1),
    UINT16(2, 2),
    INT16(3, 2),
    UINT32(4, 4),
    INT32(5, 4),
    FLOAT32(6, 4),
    BOOL(7, 1),
    STRING(8, 0),
    ARRAY(9, 0),
    UINT64(10, 8),
    INT64(11, 8),
    FLOAT64(12, 8);

    companion object {
        fun fromId(id: Int): GgufDataType? = entries.firstOrNull { it.id == id }
    }
}

data class GgufTensorInfo(
    val name: String,
    val dims: LongArray,
    val type: Int,
    val offset: Long,
)

data class GgufMetadata(
    val version: Int,
    val values: Map<String, GgufValue>,
    val tensors: List<GgufTensorInfo>,
    val alignment: Int,
    val dataStartOffset: Long,
    val fileSize: Long,
) {
    fun str(key: String): String? = (values[key] as? GgufValue.Str)?.value

    /** Numeric metadata as Long (accepts u64/i64/u32/i32). */
    fun num(key: String): Long? = when (val v = values[key]) {
        is GgufValue.UInt64 -> v.value
        is GgufValue.Int64 -> v.value
        is GgufValue.UInt32 -> v.value.toLong()
        is GgufValue.Int32 -> v.value.toLong()
        else -> null
    }

    val architecture: String get() = str("general.architecture") ?: ""
    val modelName: String get() = str("general.name") ?: ""
    val fileType: Long? get() = values["general.file_type"]?.let {
        when (it) {
            is GgufValue.UInt32 -> it.value.toLong()
            is GgufValue.Int32 -> it.value.toLong()
            is GgufValue.UInt64 -> it.value
            is GgufValue.Int64 -> it.value
            else -> null
        }
    }
    val contextLength: Long? get() = num("$architecture.context_length")
    val blockCount: Long? get() = num("$architecture.block_count")
    val embeddingLength: Long? get() = num("$architecture.embedding_length")
    val headCount: Long? get() = num("$architecture.attention.head_count")
    val headCountKv: Long? get() = num("$architecture.attention.head_count_kv")
    val keyLength: Long? get() = num("$architecture.attention.key_length")
    val valueLength: Long? get() = num("$architecture.attention.value_length")

    val tensorDataBytes: Long get() = (fileSize - dataStartOffset).coerceAtLeast(0L)

    /** builder style accessors applied to a metadata instance, with arch-specific keys falling back to generic names. */
    fun contextLengthOr(default: Long): Long =
        contextLength ?: num("context_length") ?: default

    fun quantName(): String {
        val t = fileType ?: return "unknown"
        return FileTypeMapping.of(t)
    }
}

/** Mapping of llama.cpp LLAMA_FTYPE values to short quant names (subset, unknown values reported raw). */
object FileTypeMapping {
    private val names = mapOf(
        0L to "F32",
        1L to "F16",
        2L to "Q4_0",
        3L to "Q4_1",
        7L to "Q8_0",
        8L to "Q5_0",
        9L to "Q5_1",
        10L to "Q2_K",
        11L to "Q3_K_S",
        12L to "Q3_K_M",
        13L to "Q3_K_L",
        14L to "Q4_K_S",
        15L to "Q4_K_M",
        16L to "Q5_K_S",
        17L to "Q5_K_M",
        18L to "Q6_K",
        19L to "IQ2_XXS",
        20L to "IQ2_XS",
        21L to "Q2_K_S",
        22L to "IQ3_XS",
        23L to "IQ3_XXS",
        24L to "IQ1_S",
        25L to "IQ4_NL",
        26L to "IQ3_S",
        27L to "IQ3_M",
        28L to "IQ2_S",
        29L to "IQ2_M",
        30L to "IQ4_XS",
        31L to "IQ1_M",
        36L to "TQ1_0",
        37L to "TQ2_0",
        38L to "MXFP4",
        39L to "NXFP4",
    )

    fun of(ftype: Long): String = names[ftype] ?: "ftype_$ftype"
}
