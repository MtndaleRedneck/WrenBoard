package org.futo.inputmethod.updates

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WrenBoard 1.2 fix1: the update prompt's status rules (UpdateAttempts). Pure JVM, no device needed. */
class UpdateAttemptsTest {
    private val shown = mutableListOf<String>()
    private fun tracker() = UpdateAttempts(publish = { shown.add(it) }, firstToken = 1000L)

    @Test fun successIsNotCalledAnError() {
        val t = tracker()
        val a = t.start()
        assertTrue(t.installerResult(a, UpdateAttempts.STATUS_SUCCESS, "INSTALL_SUCCEEDED"))
        assertEquals("Update installed.", t.text)
        assertEquals(UpdateAttempts.Outcome.Installed, t.lastOutcome)
        assertFalse(t.text.contains("Fatal", ignoreCase = true))
        assertFalse(t.text.contains("fail", ignoreCase = true))
    }

    @Test fun cancellingIsNeutralNotAFailure() {
        val t = tracker()
        val a = t.start()
        assertTrue(t.installerResult(a, UpdateAttempts.STATUS_FAILURE_ABORTED, "INSTALL_FAILED_ABORTED: User rejected permissions"))
        assertEquals("Update cancelled.", t.text)
        assertEquals(UpdateAttempts.Outcome.Cancelled, t.lastOutcome)
        assertFalse(t.text.contains("Fatal", ignoreCase = true))
        assertFalse(t.text.contains("fail", ignoreCase = true))
    }

    @Test fun realFailuresSayFailedPlainly() {
        val codes = listOf(
            UpdateAttempts.STATUS_FAILURE, UpdateAttempts.STATUS_FAILURE_BLOCKED, UpdateAttempts.STATUS_FAILURE_INVALID,
            UpdateAttempts.STATUS_FAILURE_CONFLICT, UpdateAttempts.STATUS_FAILURE_STORAGE,
            UpdateAttempts.STATUS_FAILURE_INCOMPATIBLE, 99
        )
        for (code in codes) {
            val t = tracker()
            val a = t.start()
            assertTrue(t.installerResult(a, code, "INSTALL_FAILED_SOMETHING"))
            assertEquals(UpdateAttempts.Outcome.Failed, t.lastOutcome)
            assertTrue("code $code: ${t.text}", t.text.startsWith("Update failed"))
            assertFalse(t.text.contains("Fatal", ignoreCase = true))
        }
        assertEquals("Update failed: it does not match the installed WrenBoard.",
            UpdateAttempts.wordsFor(UpdateAttempts.STATUS_FAILURE_CONFLICT, null))
    }

    @Test fun pendingUserActionChangesNothing() {
        val t = tracker()
        val a = t.start()
        t.post(a, "Installing update")
        assertFalse(t.installerResult(a, UpdateAttempts.STATUS_PENDING_USER_ACTION, null))
        assertEquals("Installing update", t.text)
    }

    @Test fun cancelThenReopenStartsClean() {
        val t = tracker()
        t.openPrompt()
        val a = t.start()
        t.post(a, "Installing update")
        t.downloadEnded(a)
        t.installerResult(a, UpdateAttempts.STATUS_FAILURE_ABORTED, null)
        assertEquals("Update cancelled.", t.text)
        t.openPrompt()                       // the person closes and opens the prompt again
        assertEquals("", t.text)
        assertEquals(null, t.lastOutcome)
        assertEquals("", shown.last())
    }

    @Test fun failureThenRetryStartsClean() {
        val t = tracker()
        val a = t.start()
        t.fail(a, "Download failed: server answered 404")
        t.downloadEnded(a)
        val b = t.start()                    // Update tapped again on the same prompt
        assertTrue(b != a)
        assertEquals("", t.text)
        t.post(b, "Downloading... 10%")
        assertEquals("Downloading... 10%", t.text)
    }

    @Test fun lateResultFromAnOldAttemptIsIgnored() {
        val t = tracker()
        val a = t.start()
        t.post(a, "Installing update")
        t.downloadEnded(a)
        val b = t.start()                    // a new attempt began before attempt a's installer answered
        t.post(b, "Downloading... 40%")
        assertFalse(t.installerResult(a, UpdateAttempts.STATUS_FAILURE_ABORTED, null))
        assertFalse(t.installerResult(a, UpdateAttempts.STATUS_SUCCESS, null))
        assertFalse(t.post(a, "Downloading... 99%"))
        assertFalse(t.fail(a, "Update failed."))
        assertEquals("Downloading... 40%", t.text)
        assertTrue(t.installerResult(b, UpdateAttempts.STATUS_SUCCESS, null))
        assertEquals("Update installed.", t.text)
    }

    @Test fun lateResultAfterReopeningIsIgnored() {
        val t = tracker()
        val a = t.start()
        t.post(a, "Installing update")
        t.downloadEnded(a)
        t.openPrompt()                       // prompt reopened; the old installer answer arrives afterwards
        assertFalse(t.installerResult(a, UpdateAttempts.STATUS_FAILURE_ABORTED, null))
        assertEquals("", t.text)
    }

    @Test fun reopeningDuringADownloadKeepsItsProgress() {
        val t = tracker()
        val a = t.start()
        t.post(a, "Downloading... 30%")
        val tok = t.openPrompt()             // Back closed the prompt and it was opened again mid-download
        assertEquals(a, tok)
        assertEquals("Downloading... 30%", t.text)
        assertTrue(t.post(a, "Downloading... 31%"))
    }

    @Test fun resultForAPreviousAppProcessIsIgnored() {
        val t = UpdateAttempts(publish = { shown.add(it) }, firstToken = 5_000_000L)
        t.openPrompt()
        assertFalse(t.installerResult(1001L, UpdateAttempts.STATUS_SUCCESS, null))
        assertFalse(t.installerResult(-1L, UpdateAttempts.STATUS_FAILURE, null))
        assertEquals("", t.text)
    }

    // WrenBoard 1.10: no second download while Android's install confirmation is still open.
    @Test fun noSecondDownloadWhileInstallerIsWaiting() {
        val t = tracker()
        val a = t.start()
        assertTrue(t.isBusy(now = 5_000L))
        t.handOver(a, now = 5_000L)
        t.downloadEnded(a)
        assertTrue(t.isBusy(now = 5_000L + 1_000L))
        // reopening the prompt keeps the attempt, so its answer still lands
        t.openPrompt()
        assertTrue(t.installerResult(a, UpdateAttempts.STATUS_FAILURE_ABORTED, null))
        assertFalse(t.isBusy(now = 5_000L + 2_000L))
    }

    @Test fun lostInstallerAnswerDoesNotBlockUpdatesForever() {
        val t = tracker()
        val a = t.start()
        t.handOver(a, now = 0L)
        t.downloadEnded(a)
        assertTrue(t.isBusy(now = UpdateAttempts.HANDOVER_MAX_MS - 1))
        assertFalse(t.isBusy(now = UpdateAttempts.HANDOVER_MAX_MS + 1))
    }

    @Test fun returningToTheScreenFreesAHandedOverAttempt() {
        // Review M1: the installer sheet gave no answer and the person is back (a finished install would have
        // replaced the process). The busy window ends and says so, so Update can be tapped again right away.
        val t = tracker()
        val a = t.start()
        t.handOver(a, now = 0L)
        t.downloadEnded(a)
        assertTrue(t.isBusy(now = 1_000L))
        assertTrue(t.endHandOver(a, "The install did not finish. Tap Update to try again."))
        assertFalse(t.isBusy(now = 1_000L))
        assertEquals("The install did not finish. Tap Update to try again.", t.text)
        // ... well inside the old ten-minute window
        assertTrue(1_000L < UpdateAttempts.HANDOVER_MAX_MS)
    }

    @Test fun endHandOverFromAnOldAttemptIsIgnored() {
        val t = tracker()
        val a = t.start()
        t.handOver(a, now = 0L)
        t.downloadEnded(a)
        assertFalse(t.endHandOver(a + 999, "stale"))
        assertTrue(t.isBusy(now = 1_000L))
    }

    @Test fun endHandOverWithoutAHandOverIsANoOp() {
        val t = tracker()
        val a = t.start()
        assertFalse(t.endHandOver(a, "nothing handed over"))
        assertTrue(t.isBusy(now = 0L)) // the running download still counts
    }

    @Test fun recheckBlocksANewAttemptUntilResolved() {
        // I2 review of (17): while the kept APK is re-verified in the background, no new attempt may start;
        // the gate opens only when the re-check resolves (here: handed over, then the person comes back).
        val t = tracker()
        val a = t.start()
        t.downloadEnded(a)
        assertFalse(t.isBusy(now = 1_000L))
        assertTrue(t.beginRecheck(a))
        assertTrue(t.isBusy(now = 1_000L))
        assertFalse(t.beginRecheck(a)) // a second overlapping re-check is refused
        assertTrue(t.handOver(a, now = 1_000L))
        t.downloadEnded(a)
        assertTrue(t.isBusy(now = 1_000L)) // still inside the hand-over window
        assertTrue(t.endHandOver(a, "retry"))
        assertFalse(t.isBusy(now = 1_000L))
    }

    @Test fun failedRecheckOpensTheGateAfterCleanup() {
        // fail() only records the outcome; the re-check coroutine's finally block (downloadEnded) is what
        // frees the running mark. This models that exact sequence, cleanup order included.
        val t = tracker()
        val a = t.start()
        t.downloadEnded(a)
        assertTrue(t.beginRecheck(a))
        assertTrue(t.fail(a, "The downloaded file could not be checked, so it was not installed."))
        assertTrue(t.isBusy(now = 1_000L)) // still busy between fail() and the finally block
        t.downloadEnded(a) // the coroutine's finally
        assertFalse(t.isBusy(now = 1_000L))
        assertEquals(UpdateAttempts.Outcome.Failed, t.lastOutcome)
    }

    @Test fun recheckClaimedButCoroutineNeverStartsStillFreesTheGate() = runBlocking {
        // ChatGPT review of (18): a destination popped between the claim and the coroutine's first dispatch
        // cancels the job before its body runs, so the body's finally never executes. The claim must be
        // released by the job's completion cleanup (token-checked, idempotent), or updates stay busy for
        // the rest of the process lifetime. (Tracker+pattern level with real coroutines, not screen-entry.)
        val t = tracker()
        val a = t.start()
        t.downloadEnded(a)
        assertTrue(t.beginRecheck(a))
        val scope = CoroutineScope(Job())
        scope.cancel() // the destination was popped before the coroutine's first dispatch
        var bodyRan = false
        val job = scope.launch {
            bodyRan = true
            try { /* verification and resolution */ } finally { t.downloadEnded(a) }
        }
        job.invokeOnCompletion { t.downloadEnded(a) }
        job.join()
        assertFalse(bodyRan) // proves the finally-only pattern really does leave this window
        assertFalse(t.isBusy(now = 1_000L))
    }

    @Test fun handOverForAnOldAttemptIsRefused() {
        val t = tracker()
        val a = t.start()
        t.downloadEnded(a)
        assertFalse(t.handOver(a + 999, now = 0L))
        assertFalse(t.isBusy(now = 1_000L))
    }

    @Test fun failureClearsTheHandOver() {
        val t = tracker()
        val a = t.start()
        t.handOver(a, now = 0L)
        t.downloadEnded(a)
        assertTrue(t.fail(a, "Update failed."))
        assertFalse(t.isBusy(now = 1L))
    }
}
