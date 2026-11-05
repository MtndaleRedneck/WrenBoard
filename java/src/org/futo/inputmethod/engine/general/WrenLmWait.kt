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
 * [COMMIT_WAIT_MS] budget and the word was committed exactly as typed ("teh", "brwon", "im",
 * "cant"). With a pause before the space the pass had already finished, so it worked.
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

    /** Move the deadline earlier (never later). A waiter wakes up at the new deadline. */
    fun shortenTo(newDeadlineMs: Long) {
        synchronized(lock) {
            if (done || newDeadlineMs >= deadline) return
            deadline = newDeadlineMs
            cutShort = true
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

        /** Longest the UI thread waits at a space for the word's suggestions (unchanged). */
        const val COMMIT_WAIT_MS = 450L

        /** The LM may use this much of the commit budget; the rest is for the dictionary pass, the merge and applying the result. */
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

        /** An LM miss counts toward switching the transformer off only when it had its full normal wait. */
        fun countsAsLmTimeout(outcome: Outcome): Boolean = outcome == Outcome.TIMED_OUT
    }
}
