package org.futo.inputmethod.latin.uix.settings

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Instinct-2 ((28) review): hand-off ownership and file retention are recorded BEFORE any
 *  suspending durable cleanup, so a marker-retire failure or cancellation can never route the
 *  already-handed-over file into the caller's failed-download deletion or a false failure
 *  report. The retire is optional cleanup: its failure leaves only a stale marker, which the
 *  next attempt supersedes and the cold-start recovery tolerates (file + digest re-verified
 *  before any reuse). Kept in its own file (like SuggestionStripGuard) so the JVM unit tests
 *  load no Android/Compose types. */
internal suspend fun recordSuccessfulHandOff(
    retireMarker: suspend () -> Unit,
    onHandedOver: () -> Unit,
    onCleanupFailure: (Throwable) -> Unit,
) {
    onHandedOver()
    try {
        retireMarker()
    } catch (t: Throwable) {
        onCleanupFailure(t)
    }
}

/** ChatGPT ((30) review): the final busy-state reset must survive cancellation of the
 *  attempt's job. A cancel delivered during the marker cleanup leaves the job cancelled, and
 *  a plain withContext(Dispatchers.Main) then throws before the reset runs, latching
 *  isDownloading true forever. Running the reset under NonCancellable keeps the successful
 *  hand-off / file-retention state intact and always clears the flag. */
internal suspend fun runFinalCleanupRegardless(reset: suspend () -> Unit) =
    reset() // MUTATION UNDER TEST (never merged): wrapper removed; the (33) job-cancel test must go RED
