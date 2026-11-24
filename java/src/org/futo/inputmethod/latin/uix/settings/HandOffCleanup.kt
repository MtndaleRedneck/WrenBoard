package org.futo.inputmethod.latin.uix.settings

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
