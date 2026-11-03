package org.futo.inputmethod.engine.general

import org.futo.inputmethod.latin.BuildConfig

/**
 * Shared correlation id for one learning attempt's hops across the synchronous chain
 * (InputLogic -> GeneralIME -> DictionaryFacilitatorImpl) and the executor handoff
 * (ExpandableBinaryDictionary captures it when the write task is posted). Carries a
 * sequence number, never content. 0 = outside a learn chain.
 *
 * DEBUG builds only: every entry point is a no-op in release (0 / nothing), and the
 * call sites are BuildConfig.DEBUG-guarded, so release behaviour is unchanged.
 *
 * Scope of the claim: the id correlates the hops of ONE performAdditionToUserHistory-
 * Dictionary call, including the executor handoff. It is not a durable global
 * exactly-once proof. Exactly-once per commit is asserted by counting the commit-side
 * learnEnter markers, one per commit. begin/restore are nest-safe: restore puts back
 * the id that was current before the matching begin, so a nested chain cannot clobber
 * an outer one.
 */
object WrenLearnTrace {
    private val current = ThreadLocal<Int?>()
    private var next = 0

    @JvmStatic fun begin(): Int {
        if (!BuildConfig.DEBUG) return 0
        val id: Int
        synchronized(this) { id = ++next }
        current.set(id)
        return id
    }

    @JvmStatic fun id(): Int = if (BuildConfig.DEBUG) current.get() ?: 0 else 0

    /** Restores the id that was current before the matching begin (0 clears). */
    @JvmStatic fun restore(previous: Int) {
        if (!BuildConfig.DEBUG) return
        if (previous == 0) current.remove() else current.set(previous)
    }
}
