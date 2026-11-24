package org.futo.inputmethod.latin.uix.settings

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * WrenBoard 1.10 (Instinct-2 (28) review): after a successful installer launch the durable
 * marker retire is optional cleanup. An injected retire failure or cancellation must surface
 * as logged cleanup failure only - never as an attempt failure reaching the caller's
 * catch/finally, where `failed` would still delete the handed-over bytes. These tests pin the
 * exact ordering and the exact throw/cancel behavior of recordSuccessfulHandOff.
 */
class Wren110HandOffCleanupTest {

    @Test fun handOffIsRecordedBeforeCleanupRuns() = runBlocking {
        val order = mutableListOf<String>()
        recordSuccessfulHandOff(
            retireMarker = { order.add("retire") },
            onHandedOver = { order.add("handedOver") },
            onCleanupFailure = { },
        )
        assertEquals(listOf("handedOver", "retire"), order)
    }

    @Test fun retireThrowBecomesCleanupFailureNotAttemptFailure() = runBlocking {
        var handedOver = false
        var cleanupFailure: Throwable? = null
        // If this escaped, runBlocking would rethrow and the test would error - the caller's
        // outer catch/finally never sees it in production either.
        recordSuccessfulHandOff(
            retireMarker = { throw IOException("datastore write failed") },
            onHandedOver = { handedOver = true },
            onCleanupFailure = { cleanupFailure = it },
        )
        assertTrue(handedOver)
        assertTrue(cleanupFailure is IOException)
    }

    @Test fun retireCancelBecomesCleanupFailureNotAttemptFailure() = runBlocking {
        var handedOver = false
        var cleanupFailure: Throwable? = null
        recordSuccessfulHandOff(
            retireMarker = { throw CancellationException("job cancelled mid-edit") },
            onHandedOver = { handedOver = true },
            onCleanupFailure = { cleanupFailure = it },
        )
        assertTrue(handedOver)
        assertTrue(cleanupFailure is CancellationException)
    }

    @Test fun successfulRetireReportsNoCleanupFailure() = runBlocking {
        var retired = false
        var cleanupFailure: Throwable? = null
        recordSuccessfulHandOff(
            retireMarker = { retired = true },
            onHandedOver = { },
            onCleanupFailure = { cleanupFailure = it },
        )
        assertTrue(retired)
        assertEquals(null, cleanupFailure)
    }
}
