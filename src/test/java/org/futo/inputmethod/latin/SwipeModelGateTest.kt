package org.futo.inputmethod.latin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream

class SwipeModelGateTest {
    private fun opener(files: Map<String, ByteArray>): (String) -> InputStream = { name ->
        files[name]?.let { ByteArrayInputStream(it) } ?: throw FileNotFoundException(name)
    }

    private val model = "futo-swipe/honorable_sturgeon/model_fp32.pte"
    private val real = ByteArray(2_649_856) { (it % 251).toByte() }
    private val lfsPointer = ("version https://git-lfs.github.com/spec/v1\n" +
            "oid sha256:725242bab5d14345e96ff214e8de2bfbc1f962c232d320df9c24cb82ffd1fbaf\n" +
            "size 2649856\n").toByteArray()

    @Test fun missingModelIsNotUsable() {
        assertFalse(SwipeModelGate.isUsable(opener(emptyMap()), model, SwipeModelGate.MIN_MODEL_BYTES))
    }

    @Test fun lfsPointerInsteadOfWeightsIsNotUsable() {
        assertFalse(SwipeModelGate.isUsable(opener(mapOf(model to lfsPointer)), model, SwipeModelGate.MIN_MODEL_BYTES))
    }

    @Test fun truncatedModelIsNotUsable() {
        assertFalse(SwipeModelGate.isUsable(opener(mapOf(model to ByteArray(1000))), model, SwipeModelGate.MIN_MODEL_BYTES))
    }

    @Test fun emptyNameIsNotUsable() {
        assertFalse(SwipeModelGate.isUsable(opener(mapOf(model to real)), "", 1))
    }

    @Test fun openerThatThrowsIsNotUsable() {
        assertFalse(SwipeModelGate.isUsable({ throw RuntimeException("asset manager closed") }, model, 1))
    }

    @Test fun realSizedModelIsUsable() {
        assertTrue(SwipeModelGate.isUsable(opener(mapOf(model to real)), model, SwipeModelGate.MIN_MODEL_BYTES))
    }

    @Test fun allUsableNeedsEveryAsset() {
        val meta = "futo-swipe/honorable_sturgeon/metadata.json"
        val files = mapOf(model to real, meta to "{\"codename\":\"honorable_sturgeon\"}".toByteArray())
        assertTrue(SwipeModelGate.allUsable(opener(files), listOf(model to SwipeModelGate.MIN_MODEL_BYTES, meta to 2)))
        assertFalse(SwipeModelGate.allUsable(opener(mapOf(model to real)), listOf(model to SwipeModelGate.MIN_MODEL_BYTES, meta to 2)))
    }
}
