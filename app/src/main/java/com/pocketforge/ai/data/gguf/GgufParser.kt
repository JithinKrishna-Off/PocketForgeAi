package com.pocketforge.ai.data.gguf

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * Minimal GGUF v2/v3 header + metadata reader.
 *
 * Reads only the header, KV metadata block, and tensor info table; tensor data is
 * skipped. Array metadata values (e.g. the tokenizer vocabulary) are walked so the
 * reader can jump to the following key without materialising their contents.
 * Used for model validation, memory estimation, and quantization display.
 * Corrupted/truncated files throw [IOException] or [GgufFormatException].
 */
object GgufParser {

    const val MAGIC = 0x46554747L // "GGUF" little endian
    private const val MAGIC_INT = 0x46554747
    private const val MAX_STRING_LEN = 64L * 1024 * 1024
    private const val MAX_ARRAY_LEN = 100L * 1024 * 1024
    private const val MAX_ARRAY_DEPTH = 16
    private const val MAX_KV_OR_TENSOR_COUNT = 10_000_000L

    fun parse(file: File): GgufMetadata {
        RandomAccessFile(file, "r").use { raf ->
            val fileSize = raf.length()
            if (fileSize < 24) throw GgufFormatException("File too small to be GGUF (${fileSize} bytes)")
            val reader = RandomAccessReader(raf, fileSize)
            val magic = reader.readU32()
            if (magic != MAGIC_INT) throw GgufFormatException("Not a GGUF file (bad magic 0x${magic.toLong().toString(16)})")
            val version = reader.readU32()
            if (version !in 2..3) throw GgufFormatException("Unsupported GGUF version $version (need 2 or 3)")
            val tensorCount = reader.readU64()
            val kvCount = reader.readU64()
            if (kvCount > MAX_KV_OR_TENSOR_COUNT) throw GgufFormatException("GGUF kv count $kvCount exceeds sanity limit")
            if (tensorCount > MAX_KV_OR_TENSOR_COUNT) throw GgufFormatException("GGUF tensor count $tensorCount exceeds sanity limit")

            val values = LinkedHashMap<String, GgufValue>()
            for (i in 0 until kvCount) {
                val key = reader.readString()
                val type = GgufDataType.fromId(reader.readU32())
                    ?: throw GgufFormatException("Unknown GGUF metadata type at key '$key' (index $i)")
                values[key] = readValue(reader, type)
            }

            val tensors = ArrayList<GgufTensorInfo>(tensorCount.coerceAtMost(100_000).toInt())
            for (i in 0 until tensorCount) {
                val name = reader.readString()
                val nDims = reader.readU32()
                if (nDims !in 1..4) throw GgufFormatException("Tensor '$name' has $nDims dimensions")
                val dims = LongArray(nDims) { reader.readU64() }
                val type = reader.readU32()
                val offset = reader.readU64()
                tensors.add(GgufTensorInfo(name, dims, type, offset))
            }

            val alignment = values["general.alignment"]?.let {
                when (it) {
                    is GgufValue.UInt32 -> it.value.toInt()
                    is GgufValue.Int32 -> it.value.toInt()
                    is GgufValue.UInt64 -> it.value.toInt()
                    is GgufValue.Int64 -> it.value.toInt()
                    else -> 32
                }
            } ?: 32
            if (alignment <= 0 || alignment and (alignment - 1) != 0) {
                throw GgufFormatException("Invalid GGUF alignment $alignment")
            }
            val dataStart = alignUp(reader.position, alignment.toLong())
            if (dataStart > fileSize) {
                throw GgufFormatException("GGUF header region (${dataStart} bytes) exceeds file size (${fileSize} bytes)")
            }

            return GgufMetadata(
                version = version,
                values = values,
                tensors = tensors,
                alignment = alignment,
                dataStartOffset = dataStart,
                fileSize = fileSize,
            )
        }
    }

    private fun alignUp(offset: Long, alignment: Long): Long =
        (offset + alignment - 1) / alignment * alignment

    private fun readValue(reader: Reader, type: GgufDataType): GgufValue = when (type) {
        GgufDataType.UINT8 -> GgufValue.UInt64(reader.readU8().toLong())
        GgufDataType.INT8 -> GgufValue.Int64(reader.readI8().toLong())
        GgufDataType.UINT16 -> GgufValue.UInt64(reader.readU16().toLong())
        GgufDataType.INT16 -> GgufValue.Int64(reader.readI16().toLong())
        GgufDataType.UINT32 -> GgufValue.UInt32(reader.readU32().toInt())
        GgufDataType.INT32 -> GgufValue.Int32(reader.readI32())
        GgufDataType.FLOAT32 -> GgufValue.Float32(reader.readF32())
        GgufDataType.BOOL -> GgufValue.Bool(reader.readU8() != 0.toShort())
        GgufDataType.STRING -> GgufValue.Str(reader.readString())
        GgufDataType.ARRAY -> {
            val elemType = reader.readU32()
            val length = reader.readU64()
            skipArrayContents(reader, elemType, length, depth = 0)
            GgufValue.Array(elemType, length)
        }
        GgufDataType.UINT64 -> GgufValue.UInt64(reader.readU64())
        GgufDataType.INT64 -> GgufValue.Int64(reader.readI64())
        GgufDataType.FLOAT64 -> GgufValue.Float64(reader.readF64())
    }

    /**
     * Advances past the byte content of an array value. Fixed-width elements are
     * skipped in one seek; strings are walked through their length headers.
     */
    private fun skipArrayContents(reader: Reader, elemTypeId: Int, length: Long, depth: Int) {
        val elemType = GgufDataType.fromId(elemTypeId)
            ?: throw GgufFormatException("Unknown GGUF array element type $elemTypeId")
        if (length > MAX_ARRAY_LEN) {
            throw GgufFormatException("Array length $length exceeds sanity limit")
        }
        if (depth > MAX_ARRAY_DEPTH) {
            throw GgufFormatException("GGUF array nesting deeper than $MAX_ARRAY_DEPTH")
        }
        when (elemType) {
            GgufDataType.STRING -> repeat(length.toInt()) { reader.skipString() }
            GgufDataType.ARRAY -> repeat(length.toInt()) {
                val nestedElemType = reader.readU32()
                skipArrayContents(reader, nestedElemType, reader.readU64(), depth + 1)
            }
            else -> reader.skip(length * elemType.sizeBytes)
        }
    }

    private class RandomAccessReader(
        private val raf: RandomAccessFile,
        private val fileSize: Long,
    ) : Reader {
        private var filePos = 0L
        private val buf = ByteArray(64 * 1024)
        private var bufStart = 0L // file offset of buf[0]
        private var bufLen = 0
        private val scratch = ByteArray(8)

        override val position: Long
            get() = filePos

        private fun ensureAvailable(n: Int) {
            if (bufStart <= filePos && filePos + n <= bufStart + bufLen) return
            if (filePos in bufStart..(bufStart + bufLen)) {
                // no bytes needed come before the buffer tail; extend from there
                val keep = (bufStart + bufLen - filePos).toInt()
                System.arraycopy(buf, (filePos - bufStart).toInt(), buf, 0, keep)
                bufStart = filePos
                val extra = raf.read(buf, keep, buf.size - keep)
                bufLen = keep + if (extra > 0) extra else 0
            } else {
                raf.seek(filePos)
                bufStart = filePos
                val nRead = raf.read(buf)
                bufLen = nRead.takeIf { it > 0 } ?: 0
            }
            if (filePos + n > bufStart + bufLen) {
                throw EOFException("Unexpected end of GGUF file")
            }
        }

        private fun take(n: Int) {
            ensureAvailable(n)
            System.arraycopy(buf, (filePos - bufStart).toInt(), scratch, 0, n)
            filePos += n
        }

        override fun readU8(): Short { take(1); return (scratch[0].toInt() and 0xFF).toShort() }
        override fun readI8(): Byte { take(1); return scratch[0] }
        override fun readU16(): Int { take(2); return (scratch[0].toInt() and 0xFF) or ((scratch[1].toInt() and 0xFF) shl 8) }
        override fun readI16(): Int { take(2); return (scratch[0].toInt() and 0xFF) or (scratch[1].toInt() shl 8) }
        override fun readU32(): Int {
            take(4)
            return (scratch[0].toInt() and 0xFF) or ((scratch[1].toInt() and 0xFF) shl 8) or
                ((scratch[2].toInt() and 0xFF) shl 16) or ((scratch[3].toInt() and 0xFF) shl 24)
        }
        override fun readI32(): Int = readU32()
        override fun readU64(): Long {
            take(8)
            var v = 0L
            for (i in 0 until 8) v = v or ((scratch[i].toLong() and 0xFF) shl (8 * i))
            return v
        }
        override fun readI64(): Long = readU64()
        override fun readF32(): Float = Float.fromBits(readU32())
        override fun readF64(): Double = Double.fromBits(readU64())

        override fun skip(n: Long) {
            if (n < 0) throw IOException("GGUF length is negative")
            if (filePos + n > fileSize) {
                throw EOFException("Unexpected end of GGUF file")
            }
            filePos += n // lazy: reads re-sync after any skip
        }

        override fun readString(): String {
            val len = readU64()
            if (len > MAX_STRING_LEN) throw GgufFormatException("String length $len exceeds sanity limit")
            if (filePos + len > fileSize) throw EOFException("Unexpected end of GGUF file in string")
            val bytes = ByteArray(len.toInt())
            var off = 0
            while (off < bytes.size) {
                ensureAvailable(1)
                val avail = (bufStart + bufLen - filePos).toInt()
                val chunk = minOf(avail, bytes.size - off)
                System.arraycopy(buf, (filePos - bufStart).toInt(), bytes, off, chunk)
                filePos += chunk
                off += chunk
            }
            return String(bytes, StandardCharsets.UTF_8)
        }
    }

    private interface Reader {
        val position: Long
        fun readU8(): Short
        fun readI8(): Byte
        fun readU16(): Int
        fun readI16(): Int
        fun readU32(): Int
        fun readI32(): Int
        fun readU64(): Long
        fun readI64(): Long
        fun readF32(): Float
        fun readF64(): Double
        fun skip(n: Long)
        fun skipString() { skip(readU64()) }
        fun readString(): String
    }
}

class GgufFormatException(message: String) : IOException(message)
