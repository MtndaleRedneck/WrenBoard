package org.futo.inputmethod.engine.general

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WrenWordSplitTest {
    private val words = setOf("going", "blank", "point", "a", "go", "in", "ing", "hello")
    private val isWord = { w: String -> w in words }

    @Test fun splitsRunTogether() = assertEquals("going blank", WrenWordSplit.best("goingblank", isWord))
    @Test fun keepsRealWord() = assertNull(WrenWordSplit.best("hello", isWord))
    @Test fun noSplitForGibberish() = assertNull(WrenWordSplit.best("xqzvbn", isWord))
    @Test fun ignoresDigitsAndShort() { assertNull(WrenWordSplit.best("go1ing", isWord)); assertNull(WrenWordSplit.best("goi", isWord)) }
}
