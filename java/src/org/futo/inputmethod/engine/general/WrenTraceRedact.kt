package org.futo.inputmethod.engine.general

/**
 * Redaction for diagnostic traces that would otherwise print raw typed text to logcat.
 * The STARTER traces exist to follow synthetic harness tokens through the learn pipeline;
 * printing real typed words (including words typed in private fields, before the learner's
 * own privacy gates run) would leak user text into diagnostic captures. Synthetic harness
 * tokens stay readable so traces stay followable; everything else collapses to a stable
 * length+hash form that still discriminates same/different and supports counting.
 */
object WrenTraceRedact {
    private val SYNTHETIC_TOKENS = setOf("zebra", "wrentest")

    @JvmStatic
    fun word(word: String?): String {
        if (word == null) return "(null)"
        if (word in SYNTHETIC_TOKENS || word.startsWith("wrentest")) return word
        return "len=${word.length};h=${word.hashCode()}"
    }

    @JvmStatic
    fun words(words: List<String>): String = words.joinToString("|") { word(it) }
}
