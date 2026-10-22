package com.pocketforge.ai.data.gguf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

class GgufParserTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // GGUF integers and lengths are little endian; DataOutputStream writes big
    // endian, so every numeric datum goes through a reverseBytes helper.
    private fun DataOutputStream.l32(v: Int) = writeInt(java.lang.Integer.reverseBytes(v))
    private fun DataOutputStream.l64(v: Long) = writeLong(java.lang.Long.reverseBytes(v))
    private fun DataOutputStream.str(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        l64(b.size.toLong())
        write(b)
    }

    private fun syntheticFile(f: File) {
        DataOutputStream(FileOutputStream(f).buffered()).use { d ->
            d.writeBytes("GGUF")
            d.l32(3) // version
            d.l64(1) // tensor count
            d.l64(7) // kv count

            // key order: the first array must desync any parser that forgets to skip it
            d.str("general.architecture"); d.l32(8); d.str("qwen2") // STRING
            d.str("general.file_type"); d.l32(5); d.l32(15) // INT32
            d.str("tokenizer.ggml.tokens"); d.l32(9) // ARRAY
            d.l32(8); d.l64(4) // of STRING
            d.str("hello"); d.str("chat"); d.str("world"); d.str("模型")
            d.str("tokenizer.ggml.token_type"); d.l32(9) // ARRAY
            d.l32(5); d.l64(3) // of INT32
            d.l32(2); d.l32(-3); d.l32(2147483647)
            d.str("tokenizer.ggml.merges"); d.l32(9) // ARRAY of STRING
            d.l32(8); d.l64(2)
            d.str("a b"); d.str("c d")
            d.str("qwen2.context_length"); d.l32(10); d.l64(4096) // UINT64
            d.str("chat_template"); d.l32(8); d.str("{% set ns = namespace(x=1) %}")

            // tensor table
            d.str("blk.0.attn_q.weight")
            d.l32(2) // nDims
            d.l64(128); d.l64(64)
            d.l32(10) // tensor type id (not interpreted here)
            d.l64(0) // data offset

            repeat(512) { d.writeByte(0) }
        }
    }

    @Test
    fun parsesMetadataAndSkipsArrays() {
        val f = tmp.newFile("model.gguf")
        syntheticFile(f)
        val m = GgufParser.parse(f)
        assertEquals("qwen2", m.architecture)
        assertEquals(4L, (m.values["tokenizer.ggml.tokens"] as GgufValue.Array).length)
        assertEquals(3L, (m.values["tokenizer.ggml.token_type"] as GgufValue.Array).length)
        assertEquals(2L, (m.values["tokenizer.ggml.merges"] as GgufValue.Array).length)
        assertEquals(4096L, m.contextLength)
        assertEquals(1, m.tensors.size)
        assertEquals(listOf(128L, 64L), m.tensors[0].dims.toList())
        assertTrue(m.dataStartOffset <= m.fileSize)
        assertEquals("{% set ns = namespace(x=1) %}", m.str("chat_template"))
        assertEquals("Q4_K_M", m.quantName())
    }

    @Test
    fun rejectsBadMagic() {
        val f = tmp.newFile("bad.gguf")
        FileOutputStream(f).use { it.write(ByteArray(64)) }
        try {
            GgufParser.parse(f)
            throw AssertionError("expected GgufFormatException")
        } catch (e: GgufFormatException) {
            assertTrue(e.message!!.contains("Not a GGUF file"))
        }
    }

    /**
     * End-to-end check against a real model file (the 0.5B test model suffices;
     * it carries the same tokenizer-array metadata family as the 7B model).
     * Enable by passing -D_pf_test_gguf=<path> to the test JVM.
     */
    @Test
    fun parsesRealModelFileWhenProvided() {
        val path = System.getProperty("pf.test.gguf") ?: return
        val f = File(path)
        assumeTrue("no model file at $path", f.isFile)
        val m = GgufParser.parse(f)
        println(
            "arch=${m.architecture} version=${m.version} kv=${m.values.size} tensors=${m.tensors.size} " +
                "quant=${m.quantName()} ctx=${m.contextLengthOr(0)} dataStart=${m.dataStartOffset} size=${m.fileSize}",
        )
        assertTrue(m.tensors.size > 100)
        assertTrue(m.architecture.isNotEmpty())
    }
}
