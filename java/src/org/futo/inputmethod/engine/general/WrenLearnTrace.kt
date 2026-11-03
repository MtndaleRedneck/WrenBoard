package org.futo.inputmethod.engine.general

/**
 * Shared correlation id for one commit's learn chain across the synchronous hops
 * (InputLogic -> GeneralIME -> DictionaryFacilitatorImpl) and the executor handoff
 * (ExpandableBinaryDictionary captures it when the write task is posted). DEBUG
 * diagnostics only: carries a sequence number, never content. 0 = outside a learn chain.
 */
object WrenLearnTrace {
    private val current = ThreadLocal<Int?>()
    private var next = 0

    @JvmStatic @Synchronized fun begin(): Int {
        val id = ++next
        current.set(id)
        return id
    }

    @JvmStatic fun id(): Int = current.get() ?: 0

    @JvmStatic fun end() {
        current.remove()
    }
}
