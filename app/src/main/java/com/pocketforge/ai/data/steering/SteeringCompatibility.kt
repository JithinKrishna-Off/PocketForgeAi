package com.pocketforge.ai.data.steering

import com.pocketforge.ai.data.gguf.GgufParser
import java.io.File

/**
 * Shared checks for the qwen35 neuron-steering patch (ffn_down neuron overrides).
 *
 * A steering spec is a comma-separated list of entries "L:N:dir[:dose]":
 *  - L = 0-based trunk layer index, N = neuron index into the FIRST dim of the
 *    GGUF tensor "blk.<L>.ffn_down.weight"
 *  - dir = "+1" or "-1" (direction; also called factor 1/3 in the paper terms)
 *  - dose = optional finite amp factor >= 0 (applied multiplicatively)
 * A blank spec means steering is disabled (valid, no-op).
 */
enum class SteeringTechStatus { SUPPORTED, UNSUPPORTED, UNKNOWN }

data class SteeringTarget(val layer: Int, val neuron: Int, val dir: Int, val dose: Double?)

data class SteeringAssessment(
    val techStatus: SteeringTechStatus,
    val reasons: List<String>,
    val behaviouralVerified: Boolean = false,
)

object SteeringCompatibility {

    const val REQUIRED_ARCH: String = "qwen35"

    /**
     * Parses a steering spec into targets. Duplicates are allowed (additive).
     * Throws IllegalArgumentException on a malformed entry.
     */
    fun parseSpec(spec: String): List<SteeringTarget> {
        if (spec.isBlank()) return emptyList()
        return spec.split(',').map { raw ->
            val entry = raw.trim()
            val parts = entry.split(':')
            if (parts.size < 3 || parts.size > 4) {
                throw IllegalArgumentException("invalid steering entry '$entry' (want L:N:dir[:dose])")
            }
            val layer = parseNonNegInt(parts[0], entry)
            val neuron = parseNonNegInt(parts[1], entry)
            val dir = when (parts[2]) {
                "+1" -> 1
                "-1" -> -1
                else -> throw IllegalArgumentException("invalid steering entry '$entry' (dir must be +1 or -1)")
            }
            val dose = if (parts.size == 4) {
                val d = parts[3].toDoubleOrNull()
                    ?: throw IllegalArgumentException("invalid steering entry '$entry' (dose not a number)")
                if (!d.isFinite() || d < 0.0) {
                    throw IllegalArgumentException("invalid steering entry '$entry' (dose must be finite >= 0)")
                }
                d
            } else {
                null
            }
            SteeringTarget(layer, neuron, dir, dose)
        }
    }

    /**
     * Grammar check for a spec. Empty list = valid (blank spec = disabled, valid).
     */
    fun validateSpec(spec: String): List<String> {
        if (spec.isBlank()) return emptyList()
        val notes = mutableListOf<String>()
        spec.split(',').forEachIndexed { i, raw ->
            val entry = raw.trim()
            if (entry.isEmpty()) {
                notes.add("stopping entry ${i + 1} is empty")
                return@forEachIndexed
            }
            try {
                val t = parseSpec(entry)
                val target = t.singleOrNull()
                if (target == null) {
                    notes.add("stopping entry '$entry' is not a single L:N:dir[:dose] term")
                }
            } catch (e: IllegalArgumentException) {
                notes.add(e.message ?: "invalid steering entry '$entry'")
            }
        }
        if (notes.isEmpty()) {
            val targets = runCatching { parseSpec(spec) }.getOrDefault(emptyList())
            val dup = targets.groupingBy { it.toString() }.eachCount().any { it.value > 1 }
            if (dup) {
                notes.add("duplicate targets are additive (same layer/neuron appears more than once)")
            }
        }
        return notes
    }

    /**
     * Static compat check against either recorded metadata or a probed tensor dim.
     * Pure function, safe on the main thread.
     */
    fun evaluate(
        arch: String?,
        blockCount: Int?,
        ffnDownFirstDim: Int?,
        maxTargetLayer: Int,
        maxTargetNeuron: Int,
    ): SteeringAssessment {
        val reasons = mutableListOf<String>()
        if (arch.isNullOrBlank()) {
            reasons.add("insufficient metadata")
            return SteeringAssessment(SteeringTechStatus.UNKNOWN, reasons)
        }
        if (arch != REQUIRED_ARCH) {
            reasons.add("architecture $arch is not implemented in the steering patch")
            return SteeringAssessment(SteeringTechStatus.UNSUPPORTED, reasons)
        }
        if (blockCount == null) {
            reasons.add("layer count not reported")
            return SteeringAssessment(SteeringTechStatus.UNKNOWN, reasons)
        }
        if (maxTargetLayer >= blockCount) {
            reasons.add("model has $blockCount layers; spec targets layer $maxTargetLayer")
            return SteeringAssessment(SteeringTechStatus.UNSUPPORTED, reasons)
        }
        if (ffnDownFirstDim == null) {
            reasons.add("tensor dimensions not inspected; validated from the GGUF header at load")
            return SteeringAssessment(SteeringTechStatus.UNKNOWN, reasons)
        }
        if (maxTargetNeuron >= ffnDownFirstDim) {
            reasons.add("ffn_down dimension $ffnDownFirstDim; spec targets neuron $maxTargetNeuron")
            return SteeringAssessment(SteeringTechStatus.UNSUPPORTED, reasons)
        }
        reasons.add("architecture $REQUIRED_ARCH, layer $maxTargetLayer < $blockCount, neuron $maxTargetNeuron < ffn_down dim $ffnDownFirstDim")
        reasons.add("not yet verified behaviourally on this build (experimental)")
        return SteeringAssessment(SteeringTechStatus.SUPPORTED, reasons)
    }

    /**
     * Reads the GGUF header of the file and validates the spec against the real
     * tensor table. Does file IO - call off the main thread.
     */
    fun assessGgufFile(file: File, spec: String): SteeringAssessment {
        val meta = try {
            GgufParser.parse(file)
        } catch (e: Exception) {
            return SteeringAssessment(SteeringTechStatus.UNKNOWN, listOf("GGUF header not readable: ${e.message}"))
        }
        if (spec.isBlank()) {
            return SteeringAssessment(SteeringTechStatus.SUPPORTED, listOf("steering disabled (blank spec)"))
        }
        val notes = validateSpec(spec)
        if (notes.isNotEmpty()) {
            return SteeringAssessment(SteeringTechStatus.UNSUPPORTED, notes)
        }
        val targets = try {
            parseSpec(spec)
        } catch (e: IllegalArgumentException) {
            return SteeringAssessment(SteeringTechStatus.UNSUPPORTED, listOf(e.message ?: "invalid steering spec"))
        }
        val arch = meta.architecture.ifBlank { null }
        val reasons = mutableListOf<String>()
        if (arch == null) {
            reasons.add("insufficient metadata")
            return SteeringAssessment(SteeringTechStatus.UNKNOWN, reasons)
        }
        if (arch != REQUIRED_ARCH) {
            reasons.add("architecture $arch is not implemented in the steering patch")
            return SteeringAssessment(SteeringTechStatus.UNSUPPORTED, reasons)
        }
        val blockCount = meta.blockCount?.toInt()
        if (blockCount == null) {
            reasons.add("layer count not reported")
            return SteeringAssessment(SteeringTechStatus.UNKNOWN, reasons)
        }
        val maxLayer = targets.maxOf { it.layer }
        val maxNeuron = targets.maxOf { it.neuron }
        if (maxLayer >= blockCount) {
            reasons.add("model has $blockCount layers; spec targets layer $maxLayer")
            return SteeringAssessment(SteeringTechStatus.UNSUPPORTED, reasons)
        }
        return perLayerCheck(meta, targets, maxLayer, maxNeuron)
    }

    private fun perLayerCheck(
        meta: com.pocketforge.ai.data.gguf.GgufMetadata,
        targets: List<SteeringTarget>,
        maxLayer: Int,
        maxNeuron: Int,
    ): SteeringAssessment {
        val reasons = mutableListOf<String>()
        val byLayer = targets.groupBy { it.layer }
        for ((layer, group) in byLayer) {
            val tensor = meta.tensors.firstOrNull { it.name == "blk.$layer.ffn_down.weight" }
            if (tensor == null) {
                reasons.add("layer $layer has no ffn_down tensor")
                return SteeringAssessment(SteeringTechStatus.UNSUPPORTED, reasons)
            }
            if (tensor.dims.isEmpty()) {
                reasons.add("layer $layer has an empty ffn_down tensor shape")
                return SteeringAssessment(SteeringTechStatus.UNSUPPORTED, reasons)
            }
            val firstDim = tensor.dims[0]
            val neuron = group.maxOf { it.neuron }
            if (neuron >= firstDim) {
                reasons.add("ffn_down dimension $firstDim; spec targets neuron $neuron")
                return SteeringAssessment(SteeringTechStatus.UNSUPPORTED, reasons)
            }
        }
        reasons.add("architecture $REQUIRED_ARCH, layer $maxLayer < ${meta.blockCount}, neuron $maxNeuron within ffn_down dims")
        reasons.add("not yet verified behaviourally on this build (experimental)")
        return SteeringAssessment(SteeringTechStatus.SUPPORTED, reasons)
    }

    private fun parseNonNegInt(text: String, entry: String): Int {
        val v = text.toIntOrNull()
        if (v == null || v < 0) {
            throw IllegalArgumentException("invalid steering entry '$entry' (non-negative integer expected: '$text')")
        }
        return v
    }
}
