package org.futo.inputmethod.latin.uix.settings

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

    /** ChatGPT ((30) review): cancels the coroutine's ACTUAL Job while it is suspended inside
     *  the marker retire, then runs the production finally's shape - the final reset must still
     *  execute. A manually thrown CancellationException never cancels the job, so it cannot
     *  exercise this. */
    @Test fun actualJobCancelDuringRetireStillRunsFinalReset() = runBlocking {
        val retireEntered = CountDownLatch(1)
        val resetRan = AtomicBoolean(false)
        val job = launch {
            try {
                recordSuccessfulHandOff(
                    retireMarker = {
                        retireEntered.countDown()
                        kotlinx.coroutines.delay(Long.MAX_VALUE) // parked here when cancelled
                    },
                    onHandedOver = { },
                    onCleanupFailure = { },
                )
            } finally {
                // The production finally's reset, via the same cancellation-safe helper.
                runFinalCleanupRegardless { resetRan.set(true) }
            }
        }
        org.junit.Assert.assertTrue(retireEntered.await(5, TimeUnit.SECONDS))
        job.cancel()
        job.join()
        assertTrue("final reset must run even after the job was cancelled mid-retire", resetRan.get())
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
