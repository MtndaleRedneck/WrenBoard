package org.futo.inputmethod.engine.general

/**
 * WrenBoard 1.6 (review finding 2, autocorrect at normal typing speed).
 *
 * One suggestion pass's wait for the transformer (LM) result. The wait has a deadline that can
 * only be moved EARLIER while the pass is waiting. GeneralIME uses that when a space or
 * punctuation commits the word: the word's autocorrection must be decided within the commit's
 * time budget, so a pass that is still waiting on a late LM result is cut short and the
 * dictionary's decision (already computed by then) is used instead of throwing the whole pass
 * away.
 *
 * Why: suggestion passes run one at a time on a single thread. Typed continuously, the space
 * arrives while the last letter's pass is still waiting up to [NORMAL_LM_WAIT_MS] for the LM;
 * the commit pass then queued behind it and waited for the LM again, so the commit missed its
 * 450 ms budget and the word was committed exactly as typed ("teh", "brwon", "im", "cant").
 * With a pause before the space the pass had already finished, so it worked. A word committed
 * that way was also learned as typed, so the same typo then stayed uncorrected for good.
 *
 * Plain Kotlin (no Android) so it is unit tested on the JVM.
 */
class WrenLmWait<T>(
    val sequenceNumber: Int,
    deadlineMs: Long,
    private val clock: () -> Long,
) {
    enum class Outcome { RESULT, TIMED_OUT, CUT_SHORT }

    private val lock = Object()
    private var deadline = deadlineMs
    private var cutShort = false
    private var done = false
    private var value: T? = null

    /** The LM result arrived (null is a valid "no suggestions" result). First call wins. */
    fun set(result: T?) {
        synchronized(lock) {
            if (done) return
            value = result
            done = true
            lock.notifyAll()
        }
    }

    /**
     * Move the deadline earlier (never later); a waiter wakes up at the new deadline.
     * [superseded] = the pass's result is no longer wanted (a newer word is being committed):
     * its miss is then [Outcome.CUT_SHORT], not the LM's fault. Otherwise (the commit's own
     * budget for this word) a miss stays [Outcome.TIMED_OUT] and counts, as FUTO's slow-device
     * fallback (switch the LM off after repeated misses) expects.
     */
    fun shortenTo(newDeadlineMs: Long, superseded: Boolean) {
        synchronized(lock) {
            if (done || newDeadlineMs >= deadline) return
            deadline = newDeadlineMs
            if (superseded) cutShort = true
            lock.notifyAll()
        }
    }

    /** Blocks until the result arrives or the (possibly shortened) deadline passes. */
    fun await(): Pair<Outcome, T?> {
        synchronized(lock) {
            while (!done) {
                val left = deadline - clock()
                if (left <= 0) break
                lock.wait(left)
            }
            return when {
                done -> Outcome.RESULT to value
                cutShort -> Outcome.CUT_SHORT to null
                else -> Outcome.TIMED_OUT to null
            }
        }
    }

    companion object {
        /** Normal wait for the LM in a suggestion pass (unchanged FUTO value). */
        const val NORMAL_LM_WAIT_MS = 350L

        /**
         * Longest the UI thread waits at a space for the word's dictionary result. Normally the
         * commit returns within [COMMIT_LM_BUDGET_MS] plus the dictionary time (tens of ms on a
         * phone); the longer cap only matters when the device is briefly overloaded (seen on the
         * emulator while the LM loads at the start of a field: the dictionary took 0.7-1.2 s), so
         * the word still gets its correction instead of being committed as typed. Far below
         * Android's 5 s input timeout; past it the typed word is committed as before.
         */
        const val COMMIT_MAX_WAIT_MS = 1500L

        /** From the moment a space arrives, the LM gets at most this long; then the dictionary's result decides. */
        const val COMMIT_LM_BUDGET_MS = 250L

        /** The LM deadline for a pass: the normal one, or the commit's when that is earlier. */
        fun deadlineFor(passStartMs: Long, commitDeadlineMs: Long?): Long {
            val normal = passStartMs + NORMAL_LM_WAIT_MS
            return if (commitDeadlineMs != null && commitDeadlineMs < normal) commitDeadlineMs else normal
        }

        /**
         * What a commit does to a pass that is waiting on the LM: the word being committed keeps
         * waiting until the commit's LM deadline; an older (superseded) pass stops waiting now,
         * because its result would be dropped anyway.
         */
        fun commitDeadlineForWaitingPass(passSequence: Int, commitSequence: Int, nowMs: Long, commitLmDeadlineMs: Long): Long =
            if (passSequence >= commitSequence) commitLmDeadlineMs else nowMs

        /** An LM miss counts toward FUTO's "switch the transformer off" rule unless the pass was superseded. */
        fun countsAsLmTimeout(outcome: Outcome): Boolean = outcome == Outcome.TIMED_OUT
    }
}
