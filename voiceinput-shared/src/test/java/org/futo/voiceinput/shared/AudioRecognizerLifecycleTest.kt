package org.futo.voiceinput.shared

import android.content.Context
import android.media.AudioRecord
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.futo.voiceinput.shared.types.*
import org.futo.voiceinput.shared.ui.MicrophoneDeviceState
import org.futo.voiceinput.shared.whisper.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import kotlin.coroutines.CoroutineContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
class AudioRecognizerLifecycleTest {
    private class Scope(override val coroutineContext: CoroutineContext) : CoroutineScope
    private class Recorder : CaptureRecorder {
        var starts = 0; var stops = 0; var releases = 0; var reads = 0
        var startFailure = false
        var blockAt = 0
        var errorAt = 0   // read index that reports a dead route (-1), like a dropped headset
        var loudUntilRead = 8 // reads up to this index are speech-loud; later fills are quiet
        var fillReads = 8  // blocking reads delivering audio before end-of-stream (0)
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        var routeCallback: (() -> Unit)? = null
        override fun start() { starts++; if (startFailure) error("start failure") }
        override fun stop() { stops++ }
        override fun release() { releases++ }
        override fun read(samples: ShortArray, offset: Int, count: Int, mode: Int): Int {
            // A non-blocking catch-up read on an idle fake has no buffered data.
            if (mode == AudioRecord.READ_NON_BLOCKING) return 0
            reads++
            if (reads == blockAt) {
                entered.countDown()
                check(unblock.await(5, TimeUnit.SECONDS)) { "fixture read timed out" }
                samples[0] = 2000
                return 1
            }
            if (errorAt > 0 && reads == errorAt) return -1
            if (reads > fillReads) return 0
            // Amplitude 1000 (rms ~= 0.031) is speech-like; 100 (rms ~= 0.003) stays below
            // the speech threshold so quiet reads never mark speech.
            java.util.Arrays.fill(samples, offset, offset + count,
                (if (reads <= loudUntilRead) 1000 else 100).toShort())
            return count
        }
        override fun route(): Pair<Boolean, String> = false to "fixture"
        override fun onRouteChanged(callback: () -> Unit) { routeCallback = callback }
    }
    private class Listener : AudioRecognizerListener {
        val events = mutableListOf<String>()
        var onStarted: ((MicrophoneDeviceState) -> Unit)? = null
        var onFinished: (() -> Unit)? = null
        var onPartial: (() -> Unit)? = null
        var route: MicrophoneDeviceState? = null
        override fun loading() { events += "loading" }
        override fun cancelled() { events += "cancelled" }
        override fun finished(result: String) { events += "final:$result"; onFinished?.invoke() }
        override fun languageDetected(language: Language) { events += "language" }
        override fun partialResult(result: String) { events += "partial:$result"; onPartial?.invoke() }
        override fun decodingStatus(status: InferenceState) { events += "status" }
        override fun modelLoadingFailed() { events += "modelFailed" }
        override fun needPermission(onResult: (Boolean) -> Unit) { error("unexpected permission") }
        override fun recordingStarted(device: MicrophoneDeviceState) { route = device; events += "started"; onStarted?.invoke(device) }
        override fun updateMagnitude(magnitude: Float, state: MagnitudeState) { events += "magnitude" }
        override fun processing() { events += "processing" }
    }
    private class Rig(val scheduler: TestCoroutineScheduler, workerOverride: CoroutineDispatcher? = null) {
        val main = StandardTestDispatcher(scheduler)
        val worker = workerOverride ?: StandardTestDispatcher(scheduler)
        val job = SupervisorJob()
        val failures = mutableListOf<Throwable>()
        val scope = Scope(job + main + CoroutineExceptionHandler { _, failure -> failures += failure })
        val listener = Listener()
        val recorders = mutableListOf<Recorder>()
        var factoryFailure = false
        var startFailure = false
        var blockAt = 0
        var loudUntilRead = 8
        var fillReads = 8
        var fakeDetectorSpeech: Boolean? = false // non-null: VAD seam answers this verdict
        var errorFirstRecorderAt = 0 // only the first created recorder errors at this read
        var errorAllRecordersAt = 0  // every created recorder errors at this read
        var createCount = 0
        var decodeCount = 0
        val decoded = mutableListOf<FloatArray>()
        var decodeBlock: suspend (ModelInferenceCallback) -> String = { callback ->
            callback.partialResult("first"); callback.updateStatus(InferenceState.DecodingStarted)
            callback.partialResult("second"); "done"
        }
        val dependencies = object : CaptureDependencies {
            override val main get() = this@Rig.main
            override val worker get() = this@Rig.worker
            override fun checkMain() { }
            override fun createRecorder(): CaptureRecorder {
                createCount++; if (factoryFailure) error("factory failure")
                return Recorder().also {
                    it.startFailure = startFailure; it.blockAt = blockAt; it.loudUntilRead = loudUntilRead
                    it.fillReads = fillReads
                    if (errorAllRecordersAt > 0) it.errorAt = errorAllRecordersAt
                    else if (createCount == 1 && errorFirstRecorderAt > 0) it.errorAt = errorFirstRecorderAt
                    recorders += it
                }
            }
            override fun createSpeechDetector(): GateSpeechDetector? {
                val verdict = fakeDetectorSpeech ?: return null
                return object : GateSpeechDetector {
                    override fun isSpeech(frame: ShortArray): Boolean = verdict
                    override fun close() { }
                }
            }
            override suspend fun preload() { }
            override suspend fun decode(samples: FloatArray, callback: ModelInferenceCallback): String {
                decodeCount++; decoded += samples; return decodeBlock(callback)
            }
        }
        val recognizer = AudioRecognizer(mock(Context::class.java), scope, null, listener,
            AudioRecognizerSettings(MultiModelRunConfiguration(mock(ModelLoader::class.java), emptyMap()),
                DecodingConfiguration(emptyList(), emptySet(), false),
                RecordingSettings(false, false, false, false)), dependencies)
        fun drain() = scheduler.advanceUntilIdle()
        fun close() { recognizer.reset(); job.cancel(); drain() }
    }

    @Test fun orderedPartialsThenFinalUseActualPublisher() {
        val r = Rig(TestCoroutineScheduler()); r.recognizer.start(); r.drain()
        r.recognizer.finish(); r.drain()
        assertEquals(listOf("partial:first", "status", "partial:second", "final:done"),
            r.listener.events.filter { it.startsWith("partial:") || it == "status" || it.startsWith("final:") })
        assertEquals(1, r.decodeCount); assertEquals(1, r.recorders.single().releases); r.close()
    }
    @Test fun resetSuppressesQueuedOldPartialsAndFinal() {
        val r = Rig(TestCoroutineScheduler()); r.recognizer.start(); r.drain()
        r.decodeBlock = { cb -> cb.partialResult("stale"); r.recognizer.reset(); "stale" }
        r.recognizer.finish(); r.drain()
        assertFalse(r.listener.events.any { it.contains("stale") }); r.close()
    }
    @Test fun reentrantFinishedResetCompletesWithoutHanging() {
        val r = Rig(TestCoroutineScheduler()); r.listener.onFinished = { r.recognizer.reset() }
        r.recognizer.start(); r.drain(); r.recognizer.finish(); r.drain()
        assertEquals(1, r.listener.events.count { it == "final:done" }); assertEquals(1, r.recorders.single().releases)
        assertFalse("reset left a suspended decode/publisher child", r.job.children.any { it.isActive }); r.close()
    }
    @Test fun finalListenerThrowDoesNotStrandDecode() {
        val r = Rig(TestCoroutineScheduler()); r.listener.onFinished = { error("publisher failed") }
        r.recognizer.start(); r.drain(); r.recognizer.finish(); r.drain()
        assertTrue(r.failures.isNotEmpty()); assertEquals(1, r.listener.events.count { it == "final:done" })
        assertFalse("final failure stranded a decode/publisher child", r.job.children.any { it.isActive }); r.close()
    }
    @Test fun partialPublisherThrowClosesQueuedFinalWithoutHang() {
        val r = Rig(TestCoroutineScheduler()); r.listener.onPartial = { error("partial publication failed") }
        r.recognizer.start(); r.drain(); r.recognizer.finish(); r.drain()
        assertTrue(r.failures.isNotEmpty())
        assertFalse(r.listener.events.any { it.startsWith("final:") })
        assertFalse("partial failure stranded a decode/publisher child", r.job.children.any { it.isActive }); r.close()
    }
    @Test fun cancelledBeforeLazyReaderStartReleasesOnce() {
        val r = Rig(TestCoroutineScheduler()); r.listener.onStarted = { r.job.cancel() }
        r.recognizer.start(); r.drain()
        assertEquals(1, r.recorders.single().releases); assertEquals(0, r.recorders.single().reads); r.close()
    }
    @Test fun reentrantStartedCancelNeverReadsOrResurrects() {
        val r = Rig(TestCoroutineScheduler()); r.listener.onStarted = { r.recognizer.cancel() }
        r.recognizer.start(); r.drain()
        assertEquals(1, r.recorders.single().releases); assertEquals(0, r.recorders.single().reads)
        assertEquals(1, r.listener.events.count { it == "cancelled" }); r.close()
    }
    @Test fun createFailureCancelsWithoutInventingRecorder() {
        val r = Rig(TestCoroutineScheduler()); r.factoryFailure = true
        r.recognizer.start(); r.drain()
        assertTrue(r.recorders.isEmpty()); assertTrue("cancelled" in r.listener.events); r.close()
    }
    @Test fun startFailureReleasesExactlyOnce() {
        val r = Rig(TestCoroutineScheduler()); r.startFailure = true
        r.recognizer.start(); r.drain()
        assertEquals(1, r.recorders.single().releases); assertTrue("cancelled" in r.listener.events); r.close()
    }
    @Test fun oldRouteCallbackAfterRestartIsSuppressed() {
        val r = Rig(TestCoroutineScheduler()); r.recognizer.start(); r.drain()
        val old = r.recorders.single(); r.recognizer.start(); r.drain()
        val before = r.listener.events.size; old.routeCallback!!.invoke(); r.drain()
        assertEquals(before, r.listener.events.size); assertEquals(1, old.releases); r.close()
    }
    private fun await(r: Rig, condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (!condition() && System.nanoTime() < until) { r.scheduler.runCurrent(); Thread.sleep(2) }
        r.scheduler.runCurrent()
        assertTrue("fixture condition timed out", condition())
    }
    @Test fun blockedOldReadAfterResetCannotContaminateNewCapture() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { worker ->
            val r = Rig(TestCoroutineScheduler(), worker); r.blockAt = 3
            try {
                r.recognizer.start(); r.scheduler.runCurrent()
                val old = r.recorders.single(); await(r) { old.entered.count == 0L }
                r.recognizer.reset(); r.blockAt = 0; r.recognizer.start(); r.scheduler.runCurrent()
                assertEquals(0, old.releases) // reader still owns the blocked native call
                old.unblock.countDown()
                // Wait for the new capture's stream to end so the decoded array is deterministic.
                await(r) { old.releases == 1 && r.recorders.last().reads >= 9 }
                r.recognizer.finish(); await(r) { r.decodeCount == 1 && r.listener.events.contains("final:done") }
                val decoded = r.decoded.single()
                // Only the new capture's speech-like samples; the old recorder's 2000-value
                // sample must not contaminate it.
                assertEquals(8 * 1600, decoded.size)
                assertTrue(decoded.all { it == 1000f / Short.MAX_VALUE })
                assertEquals(1, old.releases)
            } finally { r.recorders.forEach { it.unblock.countDown() }; r.close() }
        }
    }
    @Test fun cancelPendingSwitchNeverCreatesReplacementAfterOldReadReturns() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { worker ->
            val r = Rig(TestCoroutineScheduler(), worker); r.blockAt = 3
            try {
                r.recognizer.start(); r.scheduler.runCurrent(); val old = r.recorders.single()
                await(r) { old.entered.count == 0L }
                r.listener.route!!.setBluetooth(true); r.scheduler.runCurrent()
                r.recognizer.cancel(); old.unblock.countDown()
                await(r) { old.releases == 1 }; r.scheduler.runCurrent()
                assertEquals(1, r.createCount); assertEquals(0, r.decodeCount)
            } finally { r.recorders.forEach { it.unblock.countDown() }; r.close() }
        }
    }
    @Test fun stopDuringPendingSwitchPreservesPriorSamplesAndDecodesOnce() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { worker ->
            val r = Rig(TestCoroutineScheduler(), worker); r.blockAt = 3
            try {
                r.recognizer.start(); r.scheduler.runCurrent(); val old = r.recorders.single()
                await(r) { old.entered.count == 0L }
                r.listener.route!!.setBluetooth(true); r.scheduler.runCurrent()
                r.recognizer.finish(); old.unblock.countDown()
                await(r) { r.listener.events.contains("final:done") }
                assertEquals(1, r.createCount); assertEquals(1, r.decodeCount)
                val decoded = r.decoded.single()
                // 2 speech-like reads plus the blocked read's distinctive single sample,
                // in capture order.
                assertEquals(2 * 1600 + 1, decoded.size)
                assertTrue(decoded.take(2 * 1600).all { it == 1000f / Short.MAX_VALUE })
                assertEquals(2000f / Short.MAX_VALUE, decoded.last(), 0f)
                assertEquals(1, old.releases)
            } finally { r.recorders.forEach { it.unblock.countDown() }; r.close() }
        }
    }

    @Test fun noSpeechCaptureSkipsDecodeAndInsertsNothing() {
        // Sheet 18 test: silence inserts nothing. Below-threshold noise (not exact zeros,
        // so the exact-empty short circuit does not mask the gate) never marks speech.
        val r = Rig(TestCoroutineScheduler()); r.loudUntilRead = 0
        r.recognizer.start(); r.drain()
        r.recognizer.finish(); r.drain()
        assertEquals(0, r.decodeCount)
        assertTrue(r.listener.events.contains("processing"))
        assertEquals(1, r.listener.events.count { it == "final:" })
        assertFalse(r.listener.events.any { it.startsWith("partial:") })
        assertEquals(1, r.recorders.single().releases); r.close()
    }

    @Test fun quickSpeechWhollyInsideStartToneWindowStillDecodes() {
        // The decode gate must not inherit the bar's 0.6s start-tone window: a genuine
        // short word (here 0.2s of loud audio) spoken immediately after tapping the mic
        // is real speech and decodes. Note a leaking start tone (~0.27s) is
        // indistinguishable from this at capture level by design - Whisper's blank-output
        // vocabulary (bell/music/beep) is the backstop there, never a phrase blacklist.
        val r = Rig(TestCoroutineScheduler()); r.loudUntilRead = 2
        r.recognizer.start(); r.drain()
        r.recognizer.finish(); r.drain()
        assertEquals(1, r.decodeCount)
        assertTrue(r.listener.events.contains("final:done")); r.close()
    }

    @Test fun genuineSpeechMatchingPhantomPhraseIsInsertedVerbatim() {
        // Sheet 18 test: real speech of "thanks for watching" inserts it. No phrase
        // blacklist: decoded text is delivered unchanged even when it matches a known
        // hallucination phrase, because the capture tripped the speech gate.
        val r = Rig(TestCoroutineScheduler())
        r.decodeBlock = { "Thanks for watching!" }
        r.recognizer.start(); r.drain()
        r.recognizer.finish(); r.drain()
        assertEquals(1, r.decodeCount)
        assertEquals(1, r.listener.events.count { it == "final:Thanks for watching!" }); r.close()
    }

    @Test fun quickQuietSpeechInsideToneWindowStillDecodesViaVad() {
        // Explicit quiet-speech semantics: audio below the RMS threshold (rms ~= 0.003)
        // is preserved when the speech detector sees sustained speech past the start-tone
        // window. The detector always runs in production, in both auto-stop modes.
        val r = Rig(TestCoroutineScheduler())
        r.loudUntilRead = 0; r.fillReads = 2; r.fakeDetectorSpeech = true
        r.recognizer.start(); r.drain()
        r.recognizer.finish(); r.drain()
        assertEquals(1, r.decodeCount)
        assertTrue(r.listener.events.contains("final:done")); r.close()
    }

    @Test fun deadRouteReadErrorRecoversOnNewRecorderAndKeepsCapturing() {
        // Sheet 18 test: a route dying mid-session (headset disconnect errors the blocking
        // read) switches source instead of hanging; earlier samples survive the switch.
        val r = Rig(TestCoroutineScheduler()); r.errorFirstRecorderAt = 3
        r.recognizer.start(); r.drain()
        r.recognizer.finish(); r.drain()
        assertEquals(2, r.createCount)
        assertEquals(1, r.recorders[0].stops); assertEquals(1, r.recorders[0].releases)
        assertEquals(1, r.decodeCount)
        // 2 reads before the error + 8 from the replacement recorder, decoded together.
        assertEquals((2 + 8) * 1600, r.decoded.single().size)
        assertTrue(r.listener.events.contains("final:done")); r.close()
    }

    @Test fun persistentlyDeadRouteFinishesAfterBoundedRecoveryInsteadOfHanging() {
        // Every recorder errors immediately: bounded retries, then finish with the audio
        // at hand (here none) rather than restarting forever.
        val r = Rig(TestCoroutineScheduler()); r.errorAllRecordersAt = 1
        r.recognizer.start(); r.drain()
        assertEquals(1 + 2, r.createCount) // initial + MAX_ROUTE_RECOVERIES
        assertTrue(r.recorders.all { it.releases == 1 })
        assertEquals(0, r.decodeCount) // nothing captured: exact-empty short circuit
        assertTrue(r.listener.events.contains("processing"))
        assertEquals(1, r.listener.events.count { it == "final:" }); r.close()
    }

    @Test fun unavailableDetectorDoesNotSilentlyDropQuietSpeech() {
        val r = Rig(TestCoroutineScheduler())
        r.loudUntilRead = 0; r.fillReads = 2; r.fakeDetectorSpeech = null
        r.recognizer.start(); r.drain(); r.recognizer.finish(); r.drain()
        assertEquals(1, r.decodeCount)
        assertTrue(r.listener.events.contains("final:done")); r.close()
    }

}
