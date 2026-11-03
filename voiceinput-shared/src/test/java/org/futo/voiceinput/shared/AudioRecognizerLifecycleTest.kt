package org.futo.voiceinput.shared

import android.content.Context
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
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        var routeCallback: (() -> Unit)? = null
        override fun start() { starts++; if (startFailure) error("start failure") }
        override fun stop() { stops++ }
        override fun release() { releases++ }
        override fun read(samples: ShortArray, offset: Int, count: Int, mode: Int): Int {
            reads++
            if (reads == blockAt) {
                entered.countDown()
                check(unblock.await(5, TimeUnit.SECONDS)) { "fixture read timed out" }
                samples[0] = 2000
                return 1
            }
            if (reads == 2 && blockAt > 0) return 0
            if (reads != 1) return -1
            samples[0] = 1000; return 1
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
                return Recorder().also { it.startFailure = startFailure; it.blockAt = blockAt; recorders += it }
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
        assertEquals(1, r.listener.events.count { it == "final:done" }); assertEquals(1, r.recorders.single().releases); r.close()
    }
    @Test fun finalListenerThrowDoesNotStrandDecode() {
        val r = Rig(TestCoroutineScheduler()); r.listener.onFinished = { error("publisher failed") }
        r.recognizer.start(); r.drain(); r.recognizer.finish(); r.drain()
        assertTrue(r.failures.isNotEmpty()); assertEquals(1, r.listener.events.count { it == "final:done" }); r.close()
    }
    @Test fun partialPublisherThrowClosesQueuedFinalWithoutHang() {
        val r = Rig(TestCoroutineScheduler()); r.listener.onPartial = { error("partial publication failed") }
        r.recognizer.start(); r.drain(); r.recognizer.finish(); r.drain()
        assertTrue(r.failures.isNotEmpty())
        assertFalse(r.listener.events.any { it.startsWith("final:") }); r.close()
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
                await(r) { old.releases == 1 && r.recorders.last().reads >= 2 }
                r.recognizer.finish(); await(r) { r.decodeCount == 1 && r.listener.events.contains("final:done") }
                assertArrayEquals(floatArrayOf(1000f / Short.MAX_VALUE), r.decoded.single(), 0f)
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
                assertArrayEquals(floatArrayOf(1000f / Short.MAX_VALUE), r.decoded.single(), 0f)
                assertEquals(1, old.releases)
            } finally { r.recorders.forEach { it.unblock.countDown() }; r.close() }
        }
    }

}
