package org.futo.inputmethod.updates

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
}
