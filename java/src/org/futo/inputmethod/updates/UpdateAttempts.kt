package org.futo.inputmethod.updates

/**
 * WrenBoard 1.2: what the update prompt says about the current attempt, and which attempt a late result belongs to.
 *
 * Plain Kotlin with no Android types, so the rules are unit tested (src/test/.../UpdateAttemptsTest.kt).
 *
 * - Every tap on Update starts a new attempt with its own token; the token rides the installer's result intent.
 * - Opening the prompt again while nothing is running clears the old attempt's words and retires its token, so a
 *   cancelled or failed attempt never shows on the next prompt, and its late result cannot overwrite anything.
 * - A result or progress line carrying any token other than the current one is ignored.
 * - The installer's answers are told apart: success, the person cancelling, and a real failure.
 */
class UpdateAttempts(
    /** Called with the words to show under the prompt ("" = nothing). */
    private val publish: (String) -> Unit,
    /** First token; a process-unique start so a result meant for a previous app process never matches. */
    firstToken: Long = System.currentTimeMillis()
) {
    enum class Outcome { Installed, Cancelled, Failed, Waiting }

    private val lock = Any()
    private var token: Long = firstToken
    private var running = false
    // WrenBoard 1.10: the APK is committed to Android's installer and its answer has not come back yet.
    private var handedOver = false
    private var handedOverAt = 0L
    var text: String = ""
        private set
    var lastOutcome: Outcome? = null
        private set

    val currentToken: Long get() = synchronized(lock) { token }
    val isRunning: Boolean get() = synchronized(lock) { running }

    /** The prompt was opened. With no attempt running, the previous attempt's words go and its token is retired. */
    fun openPrompt(): Long = synchronized(lock) {
        if (!running && !handedOver) {
            token++
            lastOutcome = null
            set("")
        }
        token
    }

    /** Update was tapped: a fresh attempt with a fresh token and no leftover words. */
    fun start(): Long = synchronized(lock) {
        token++
        running = true
        handedOver = false
        lastOutcome = null
        set("")
        token
    }

    /**
     * WrenBoard 1.10: true while a download is running, or the finished download is with Android's installer and
     * no final answer has arrived (waiting for the person to confirm). Update must not start a second download then.
     * A hand-over older than [HANDOVER_MAX_MS] no longer counts, so a lost answer cannot block updates forever.
     */
    fun isBusy(now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        running || (handedOver && now - handedOverAt < HANDOVER_MAX_MS)
    }

    /**
     * The finished download of [attempt] was committed to the installer, whose answer is still to come.
     * Returns true when the hand-over was recorded. Callers must act (launch the installer) only on true:
     * a false means this attempt is no longer current and its work has been superseded.
     */
    fun handOver(attempt: Long, now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        if (attempt != token) return false
        handedOver = true; handedOverAt = now
        true
    }

    /**
     * WrenBoard 1.10 (review M1, I2): the update screen is re-checking the kept APK before handing it over again.
     * Marks the attempt running so no new attempt can start while the re-check's background work (verify, then
     * cleanup or installer launch) is still in flight. Returns false when the attempt is already busy, so a
     * second resume cannot start overlapping re-check work for the same file.
     */
    fun beginRecheck(attempt: Long): Boolean = synchronized(lock) {
        if (attempt != token || running || handedOver) return false
        running = true
        true
    }

    /**
     * WrenBoard 1.10 (review M1): the update screen is showing again while this attempt's hand-over had no answer.
     * A finished install replaces the app process, so reaching the screen means the install did not happen: free the
     * busy window and say so, so the person can retry now instead of waiting out [HANDOVER_MAX_MS]. The timeout
     * stays the recovery for never coming back. Returns false when this attempt has no active hand-over.
     */
    /** True while [attempt] is current and its hand-over has no answer yet. Snapshot for the
     *  resume-return path, which retires the durable marker BEFORE freeing the tracker: while
     *  this holds, beginRecheck refuses every claim, so no resume can claim the old selector
     *  in the retire-then-free window. */
    fun isHandedOver(attempt: Long): Boolean = synchronized(lock) { attempt == token && handedOver }

    fun endHandOver(attempt: Long, words: String): Boolean = synchronized(lock) {
        if (attempt != token || !handedOver) return false
        handedOver = false
        set(words)
        true
    }

    /** The download/hand-over part of [attempt] ended (the installer may still answer later). */
    fun downloadEnded(attempt: Long) = synchronized(lock) {
        if (attempt == token) running = false
    }

    /** A progress or status line from [attempt]; ignored when that attempt is no longer current. */
    fun post(attempt: Long, words: String): Boolean = synchronized(lock) {
        if (attempt != token) return false
        set(words)
        true
    }

    /** A download or hand-over failure in [attempt]. */
    fun fail(attempt: Long, words: String): Boolean = synchronized(lock) {
        if (attempt != token) return false
        handedOver = false
        lastOutcome = Outcome.Failed
        set(words)
        true
    }

    /** The Android installer's answer for [attempt]; ignored when it is not the current attempt. */
    fun installerResult(attempt: Long, status: Int, systemMessage: String?): Boolean = synchronized(lock) {
        if (attempt != token) return false
        val outcome = outcomeFor(status)
        if (outcome == Outcome.Waiting) return false
        handedOver = false
        lastOutcome = outcome
        set(wordsFor(status, systemMessage))
        true
    }

    private fun set(words: String) {
        text = words
        publish(words)
    }

    companion object {
        const val HANDOVER_MAX_MS = 10 * 60 * 1000L
        // The values of android.content.pm.PackageInstaller.STATUS_* (fixed by the Android API).
        const val STATUS_PENDING_USER_ACTION = -1
        const val STATUS_SUCCESS = 0
        const val STATUS_FAILURE = 1
        const val STATUS_FAILURE_BLOCKED = 2
        const val STATUS_FAILURE_ABORTED = 3
        const val STATUS_FAILURE_INVALID = 4
        const val STATUS_FAILURE_CONFLICT = 5
        const val STATUS_FAILURE_STORAGE = 6
        const val STATUS_FAILURE_INCOMPATIBLE = 7

        const val TEXT_INSTALLED = "Update installed."
        const val TEXT_CANCELLED = "Update cancelled."

        fun outcomeFor(status: Int): Outcome = when (status) {
            STATUS_PENDING_USER_ACTION -> Outcome.Waiting
            STATUS_SUCCESS -> Outcome.Installed
            STATUS_FAILURE_ABORTED -> Outcome.Cancelled
            else -> Outcome.Failed
        }

        /** Short plain words for an installer answer. */
        fun wordsFor(status: Int, systemMessage: String?): String = when (status) {
            STATUS_SUCCESS -> TEXT_INSTALLED
            STATUS_FAILURE_ABORTED -> TEXT_CANCELLED
            STATUS_FAILURE_BLOCKED -> "Update failed: the phone blocked the install."
            STATUS_FAILURE_INVALID -> "Update failed: the downloaded file is not a valid app."
            STATUS_FAILURE_CONFLICT -> "Update failed: it does not match the installed WrenBoard."
            STATUS_FAILURE_STORAGE -> "Update failed: not enough storage."
            STATUS_FAILURE_INCOMPATIBLE -> "Update failed: not compatible with this phone."
            else -> "Update failed."
        }
    }
}
