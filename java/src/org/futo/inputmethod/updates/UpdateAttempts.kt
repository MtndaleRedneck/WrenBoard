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
    var text: String = ""
        private set
    var lastOutcome: Outcome? = null
        private set

    val currentToken: Long get() = synchronized(lock) { token }
    val isRunning: Boolean get() = synchronized(lock) { running }

    /** The prompt was opened. With no attempt running, the previous attempt's words go and its token is retired. */
    fun openPrompt(): Long = synchronized(lock) {
        if (!running) {
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
        lastOutcome = null
        set("")
        token
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
        lastOutcome = Outcome.Failed
        set(words)
        true
    }

    /** The Android installer's answer for [attempt]; ignored when it is not the current attempt. */
    fun installerResult(attempt: Long, status: Int, systemMessage: String?): Boolean = synchronized(lock) {
        if (attempt != token) return false
        val outcome = outcomeFor(status)
        if (outcome == Outcome.Waiting) return false
        lastOutcome = outcome
        set(wordsFor(status, systemMessage))
        true
    }

    private fun set(words: String) {
        text = words
        publish(words)
    }

    companion object {
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
