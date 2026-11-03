package org.futo.voiceinput.shared

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.SensorPrivacyManager
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MicrophoneDirection
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.LifecycleCoroutineScope
import com.konovalov.vad.Vad
import com.konovalov.vad.config.FrameSize
import com.konovalov.vad.config.Mode
import com.konovalov.vad.config.Model
import com.konovalov.vad.config.SampleRate
import com.konovalov.vad.models.VadModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.futo.voiceinput.shared.ggml.InferenceCancelledException
import org.futo.voiceinput.shared.ggml.InvalidModelException
import org.futo.voiceinput.shared.types.AudioRecognizerListener
import org.futo.voiceinput.shared.types.InferenceState
import org.futo.voiceinput.shared.types.Language
import org.futo.voiceinput.shared.types.MagnitudeState
import org.futo.voiceinput.shared.types.ModelInferenceCallback
import org.futo.voiceinput.shared.types.ModelLoader
import org.futo.voiceinput.shared.ui.MicrophoneDeviceState
import org.futo.voiceinput.shared.whisper.DecodingConfiguration
import org.futo.voiceinput.shared.whisper.ModelManager
import org.futo.voiceinput.shared.whisper.MultiModelRunConfiguration
import org.futo.voiceinput.shared.whisper.MultiModelRunner
import org.futo.voiceinput.shared.whisper.isBlankResult
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

private fun getRecordingDeviceKind(type: Int): String {
    return when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        AudioDeviceInfo.TYPE_DOCK -> "DOCK"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_FM_TUNER -> "FM_TUNER"
        AudioDeviceInfo.TYPE_TV_TUNER -> "TV_TUNER"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE_ANALOG"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "LINE_DIGITAL"
        AudioDeviceInfo.TYPE_IP -> "IP"
        AudioDeviceInfo.TYPE_BUS -> "BUS"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI_ARC"
        AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI_EARC"
        AudioDeviceInfo.TYPE_DOCK_ANALOG -> "DOCK_ANALOG"
        else -> "unknown@${type}"
    }
}

data class RecordingSettings(
    val preferBluetoothMic: Boolean,
    val requestAudioFocus: Boolean,
    val canExpandSpace: Boolean,
    val useVADAutoStop: Boolean
)

data class AudioRecognizerSettings(
    val modelRunConfiguration: MultiModelRunConfiguration,
    val decodingConfiguration: DecodingConfiguration,
    val recordingConfiguration: RecordingSettings
)

class ModelDoesNotExistException(val models: List<ModelLoader>) : Throwable()

internal interface CaptureRecorder {
    fun start()
    fun stop()
    fun release()
    fun read(samples: ShortArray, offset: Int, count: Int, mode: Int): Int
    fun route(): Pair<Boolean, String>?
    fun onRouteChanged(callback: () -> Unit)
}

// Narrow dependency boundary. Tests drive the real recognizer/session/queue, not a second model.
internal interface CaptureDependencies {
    val main: CoroutineDispatcher
    val worker: CoroutineDispatcher
    fun checkMain()
    fun createRecorder(): CaptureRecorder
    suspend fun preload()
    suspend fun decode(samples: FloatArray, callback: ModelInferenceCallback): String
}

class AudioRecognizer internal constructor(
    private val context: Context,
    private val lifecycleScope: CoroutineScope,
    modelManager: ModelManager?,
    private val listener: AudioRecognizerListener,
    private val settings: AudioRecognizerSettings,
    private val dependencies: CaptureDependencies?
) {
    constructor(context: Context, lifecycleScope: LifecycleCoroutineScope, modelManager: ModelManager,
                listener: AudioRecognizerListener, settings: AudioRecognizerSettings) :
        this(context, lifecycleScope, modelManager, listener, settings, null)
    private val main get() = dependencies?.main ?: Dispatchers.Main
    private val worker get() = dependencies?.worker ?: Dispatchers.Default

    private class CaptureSession {
        val samples = SampleBuffer()
        @Volatile var capturing = true
        @Volatile var routeRequest = 0L
        var recorder: OwnedRecorder? = null
        var reader: Job? = null
        var switchJob: Job? = null
        var preload: Job? = null
        var decode: Job? = null
        val publications = Channel<() -> Unit>(Channel.UNLIMITED)
        var publisher: Job? = null
        @Volatile var terminalWaiter: CompletableDeferred<Unit>? = null
    }
    private class OwnedRecorder(val value: CaptureRecorder) {
        private val released = AtomicBoolean(false)
        fun stop() { if (!released.get()) runCatching { value.stop() } }
        fun release() { if (released.compareAndSet(false, true)) value.release() }
    }
    private class SampleBuffer {
        var floats: FloatBuffer = FloatBuffer.allocate(16000 * 30)
    }
    @Volatile private var session: CaptureSession? = null
    private fun current(owner: CaptureSession) = session === owner
    private fun requireMain() {
        dependencies?.let { it.checkMain(); return }
        check(Looper.myLooper() == Looper.getMainLooper()) { "Audio capture controls require Main" }
    }

    private val modelRunner by lazy { MultiModelRunner(checkNotNull(modelManager)) }
    private val canExpandSpace = settings.recordingConfiguration.canExpandSpace
    private val useVAD = settings.recordingConfiguration.useVADAutoStop

    private var focusRequest: AudioFocusRequest? = null

    private var communicationDevice = "unknown"

    private fun focusAudio() {
        unfocusAudio()

        if(!settings.recordingConfiguration.requestAudioFocus) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                focusRequest =
                    AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .build()
                audioManager.requestAudioFocus(focusRequest!!)
            }
        }catch(e: Exception) {
            e.printStackTrace()
        }
    }

    private fun unfocusAudio() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (focusRequest != null) {
                    audioManager.abandonAudioFocusRequest(focusRequest!!)
                }
                focusRequest = null
            }
        }catch(e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isBluetoothAvailable(): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val devices = audioManager.availableCommunicationDevices

                return devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                } != null
            }
        } catch(_: Exception) {}

        return false
    }

    private fun setCommunicationDevice(preferBluetoothMic: Boolean): Pair<Boolean, String> {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val devices = audioManager.availableCommunicationDevices
                val tgtDevice =
                    devices.firstOrNull { preferBluetoothMic && it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } ?:
                    devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC } ?:
                    devices.firstOrNull { it.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO  } ?:
                    devices.first()

                if (!audioManager.setCommunicationDevice(tgtDevice)) {
                    audioManager.clearCommunicationDevice()
                    return Pair(false, "")
                } else {
                    return Pair(tgtDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO, tgtDevice.productName.toString())
                }
            }
        } catch(_: Exception) {}
        return Pair(false, "")
    }

    private fun clearCommunicationDevice() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
    }

    @Throws(ModelDoesNotExistException::class)
    private fun verifyModelsExist() {
        val modelsThatDoNotExist = mutableListOf<ModelLoader>()

        if (!settings.modelRunConfiguration.primaryModel.exists(context)) {
            modelsThatDoNotExist.add(settings.modelRunConfiguration.primaryModel)
        }

        for (model in settings.modelRunConfiguration.languageSpecificModels.values) {
            if (!model.exists(context)) {
                modelsThatDoNotExist.add(model)
            }
        }

        if (modelsThatDoNotExist.isNotEmpty()) {
            throw ModelDoesNotExistException(modelsThatDoNotExist)
        }
    }

    init {
        if (dependencies == null) verifyModelsExist()
    }

    fun reset() {
        requireMain()
        val old = session
        session = null // invalidate callbacks before any cancellation or cleanup
        if (old != null) {
            old.capturing = false
            old.terminalWaiter?.cancel()
            old.publications.cancel()
            old.publisher?.cancel()
            old.routeRequest++
            old.switchJob?.cancel()
            old.preload?.cancel()
            old.decode?.cancel()
            old.recorder?.stop()
            old.reader?.cancel()
            // Completion handlers run even if the parent lifecycle scope is already cancelled.
            // Each recorder is released exactly once, only after its reader stops.
            val recorder = old.recorder
            val reader = old.reader
            if (reader == null) recorder?.release()
            else reader.invokeOnCompletion { recorder?.release() }
        }
        if (dependencies == null) modelRunner.cancelAll()
        unfocusAudio()
        if (dependencies == null) clearCommunicationDevice()
    }

    fun finish() {
        requireMain()
        val owner = session ?: return
        if (!owner.capturing) return
        onFinishRecording(owner)
    }

    fun cancel() {
        requireMain()
        reset()
        listener.cancelled()
    }

    fun openPermissionSettings() {
        val packageName = context.packageName
        val micPermissionRequester = Intent()
        micPermissionRequester.setClassName(context, "org.futo.inputmethod.latin.MicPermissionActivity")
        micPermissionRequester.setFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        context.startActivity(micPermissionRequester)

        cancel()
    }

    fun start() {
        requireMain()
        reset()
        val owner = CaptureSession()
        session = owner
        owner.publisher = lifecycleScope.launch(main) {
            for (publish in owner.publications) if (current(owner)) publish()
        }.also { publisher ->
            publisher.invokeOnCompletion { failure ->
                // A dead publisher must never accept a final which can wait forever.
                owner.publications.cancel()
                owner.terminalWaiter?.let { waiter ->
                    if (failure == null) waiter.cancel()
                    else waiter.completeExceptionally(failure)
                }
            }
        }
        listener.loading()

        if (dependencies == null && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermission(owner)
        } else {
            startRecording(owner)
        }
    }

    private fun requestPermission(owner: CaptureSession) {
        listener.needPermission { wasGranted ->
            lifecycleScope.launch(main) {
                if (wasGranted && current(owner) && owner.capturing) startRecording(owner)
            }
        }
    }


    @Throws(SecurityException::class)
    private fun createAudioRecorder(): AudioRecord {
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            16000 * 2 * 5
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                recorder.setPreferredMicrophoneDirection(MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER)
            }
            return recorder
        } catch (error: Throwable) {
            recorder.release()
            throw error
        }
    }

    private suspend fun preloadModels() {
        if (dependencies != null) dependencies.preload()
        else modelRunner.preload(settings.modelRunConfiguration)
    }

    private fun recorder(): CaptureRecorder = dependencies?.createRecorder() ?: object : CaptureRecorder {
        private val audio = createAudioRecorder()
        override fun start() = audio.startRecording()
        override fun stop() = audio.stop()
        override fun release() = audio.release()
        override fun read(samples: ShortArray, offset: Int, count: Int, mode: Int) = audio.read(samples, offset, count, mode)
        override fun route(): Pair<Boolean, String>? = audio.routedDevice?.let {
            routeIsBluetooth(it) to it.productName.toString()
        }
        override fun onRouteChanged(callback: () -> Unit) = audio.addOnRoutingChangedListener({ callback() }, null)
    }

    private fun expandSpaceIfAllowed(owner: CaptureSession): Boolean {
        if(canExpandSpace) {
            // Allocate an extra 30 seconds
            val newSampleBuffer = FloatBuffer.allocate(owner.samples.floats.capacity() + 16000 * 30)
            //Log.d("AudioRecognizer", "Allocating extra space: ${owner.samples.floats.capacity() / 16000} -> ${newSampleBuffer.capacity() / 16000}")
            newSampleBuffer.put(owner.samples.floats.array(), 0, owner.samples.floats.capacity() - owner.samples.floats.remaining())
            owner.samples.floats = newSampleBuffer
            return true
        }
        return false
    }

    private suspend fun recordingJob(recorder: CaptureRecorder, vad: VadModel?, owner: CaptureSession, request: Long) {
        var hasTalked = false
        var anyNoiseAtAll = false

        val canMicBeBlocked = if (dependencies == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(SensorPrivacyManager::class.java) as SensorPrivacyManager).supportsSensorToggle(
                SensorPrivacyManager.Sensors.MICROPHONE
            )
        } else {
            false
        }
        var isMicBlocked = false

        val vadSampleBuffer = ShortBuffer.allocate(480)
        var numConsecutiveNonSpeech = 0
        var numConsecutiveSpeech = 0

        val samples = ShortArray(1600)

        while (current(owner) && owner.capturing && owner.routeRequest == request) {
            yield()
            if (!current(owner) || !owner.capturing || owner.routeRequest != request) return
            val nRead = recorder.read(samples, 0, 1600, AudioRecord.READ_BLOCKING)
            if (nRead <= 0) break
            yield()
            if (!current(owner) || !owner.capturing || owner.routeRequest != request) return

            var isRunningOutOfSpace = (owner.samples.floats.remaining() < nRead.coerceAtLeast(1600)) && !expandSpaceIfAllowed(owner)

            val hasNotTalkedRecently = hasTalked && (numConsecutiveNonSpeech > 66) && useVAD
            if (isRunningOutOfSpace || hasNotTalkedRecently) {
                yield()
                withContext(main) {
                    if (current(owner) && owner.routeRequest == request) finish()
                }
                return
            }

            // Run VAD
            if(useVAD && vad != null) {
                var remainingSamples = nRead
                var offset = 0
                while (remainingSamples > 0) {
                    if (!vadSampleBuffer.hasRemaining()) {
                        val isSpeech = vad.isSpeech(vadSampleBuffer.array())
                        vadSampleBuffer.clear()
                        vadSampleBuffer.rewind()

                        if (!isSpeech) {
                            numConsecutiveNonSpeech++
                            numConsecutiveSpeech = 0
                        } else {
                            numConsecutiveNonSpeech = 0
                            numConsecutiveSpeech++
                        }
                    }

                    val samplesToRead = min(min(remainingSamples, 480), vadSampleBuffer.remaining())
                    for (i in 0 until samplesToRead) {
                        vadSampleBuffer.put(
                            samples[offset]
                        )
                        offset += 1
                        remainingSamples -= 1
                    }
                }
            }

            owner.samples.floats.put(samples.sliceArray(0 until nRead).map { it.toFloat() / Short.MAX_VALUE.toFloat() }.toFloatArray())

            // Don't set hasTalked if the start sound may still be playing, otherwise on some
            // devices the rms just explodes and `hasTalked` is always true
            val startSoundPassed = (owner.samples.floats.position() > 16000 * 0.6)
            if (!startSoundPassed) {
                numConsecutiveSpeech = 0
                numConsecutiveNonSpeech = 0
            }

            // Only valid samples from this read; the rest of the buffer may be stale.
            val rms = validSampleRms(samples, nRead)

            if (startSoundPassed && ((rms > 0.01) || (numConsecutiveSpeech > 8))) {
                hasTalked = true
            }

            if (rms > 0.0001) {
                anyNoiseAtAll = true
                isMicBlocked = false
            }

            // Check if mic is blocked
            val blockCheckTimePassed = (owner.samples.floats.position() > 2 * 16000) // two seconds
            if (!anyNoiseAtAll && canMicBeBlocked && blockCheckTimePassed) {
                isMicBlocked = true
            }

            val magnitude = (1.0f - 0.1f.pow(24.0f * rms))

            val state = if (hasTalked) {
                MagnitudeState.TALKING
            } else if (isMicBlocked) {
                MagnitudeState.MIC_MAY_BE_BLOCKED
            } else {
                MagnitudeState.NOT_TALKED_YET
            }

            yield()
            withContext(main) {
                if (current(owner) && owner.capturing && owner.routeRequest == request)
                    listener.updateMagnitude(magnitude, state)
            }

            // Skip ahead as much as possible, in case we are behind (taking more than
            // 100ms to process 100ms)
            while (true) {
                yield()
                if (!current(owner) || !owner.capturing || owner.routeRequest != request) return
                val nRead2 = recorder.read(
                    samples, 0, 1600, AudioRecord.READ_NON_BLOCKING
                )
                if (!current(owner) || !owner.capturing || owner.routeRequest != request) return
                if (nRead2 > 0) {
                    if (owner.samples.floats.remaining() < nRead2 && !expandSpaceIfAllowed(owner)) {
                        yield()
                        withContext(main) {
                            if (current(owner) && owner.routeRequest == request) finish()
                        }
                        break
                    }
                    owner.samples.floats.put(samples.sliceArray(0 until nRead2).map { it.toFloat() / Short.MAX_VALUE.toFloat() }.toFloatArray())
                } else {
                    break
                }
            }
        }
        println("isRecording loop exited")
    }

    private fun createVad(): VadModel {
        return Vad.builder().setModel(Model.WEB_RTC_GMM).setMode(Mode.VERY_AGGRESSIVE)
            .setFrameSize(FrameSize.FRAME_SIZE_480).setSampleRate(SampleRate.SAMPLE_RATE_16K)
            .setSpeechDurationMs(150).setSilenceDurationMs(300).build()
    }

    @Throws(SecurityException::class)
    private fun routeIsBluetooth(device: AudioDeviceInfo): Boolean {
        return device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= 33 && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    private fun reportActualRoute(recorder: OwnedRecorder, state: MicrophoneDeviceState,
                                  owner: CaptureSession, request: Long) {
        // Routing notifications can arrive on a framework thread. Publish only on owned Main,
        // and recheck identity after dispatch (including a late callback from an old recorder).
        lifecycleScope.launch(main) {
            if (!current(owner) || !owner.capturing || owner.routeRequest != request || owner.recorder !== recorder) return@launch
            try {
                val routed = recorder.value.route()
                if (routed != null) listener.recordingStarted(state.copy(
                    actualRouteIsBluetooth = routed.first,
                    actualRouteName = routed.second
                ))
            } catch (_: Exception) { }
        }
    }

    private fun createRecorderAndJob(preferBluetoothMic: Boolean, owner: CaptureSession) {
        requireMain()
        if (!current(owner) || !owner.capturing) return
        val oldReader = owner.reader // capture before suspension, never adopt a newer reader
        val oldRecorder = owner.recorder
        owner.switchJob?.cancel()
        val request = ++owner.routeRequest
        oldRecorder?.stop()
        oldReader?.cancel()
        owner.switchJob = lifecycleScope.launch {
            oldReader?.join()
            if (!current(owner) || !owner.capturing || owner.routeRequest != request) return@launch
            oldRecorder?.release()
            if (owner.recorder === oldRecorder) owner.recorder = null
            var created: OwnedRecorder? = null
            try {
                val bluetoothInfo = if (dependencies == null) setCommunicationDevice(preferBluetoothMic) else (false to "test")
                val state = MicrophoneDeviceState(
                    bluetoothAvailable = bluetoothInfo.first || (dependencies == null && isBluetoothAvailable()),
                    bluetoothActive = bluetoothInfo.first,
                    deviceName = bluetoothInfo.second,
                    bluetoothPreferredByUser = settings.recordingConfiguration.preferBluetoothMic,
                    setBluetooth = { selected ->
                        lifecycleScope.launch(main) {
                            if (current(owner) && owner.capturing && owner.routeRequest == request &&
                                selected != preferBluetoothMic) createRecorderAndJob(selected, owner)
                        }
                    }
                )
                val activeRecorder = OwnedRecorder(recorder())
                created = activeRecorder
                activeRecorder.value.start()
                owner.recorder = activeRecorder
                listener.recordingStarted(state)
                // Listener may synchronously reset/restart. Never assign a reader to that old owner.
                if (!current(owner) || !owner.capturing || owner.routeRequest != request) {
                    activeRecorder.stop()
                    activeRecorder.release()
                    return@launch
                }
                reportActualRoute(activeRecorder, state, owner, request)
                activeRecorder.value.onRouteChanged {
                    reportActualRoute(activeRecorder, state, owner, request)
                }
                val reader = lifecycleScope.launch(worker, start = CoroutineStart.LAZY) {
                    if (!current(owner) || !owner.capturing || owner.routeRequest != request) return@launch
                    if (useVAD) createVad().use { recordingJob(activeRecorder.value, it, owner, request) }
                    else recordingJob(activeRecorder.value, null, owner, request)
                }
                // Handler registered before start, including already-cancelled lifecycle scope.
                reader.invokeOnCompletion { activeRecorder.release() }
                owner.reader = reader
                reader.start()
            } catch (cancelled: CancellationException) {
                created?.stop()
                created?.release()
                if (owner.recorder === created) owner.recorder = null
                throw cancelled
            } catch (e: Exception) {
                created?.stop()
                created?.release()
                if (owner.recorder === created) owner.recorder = null
                if (current(owner) && owner.capturing && owner.routeRequest == request) {
                    if (dependencies == null) clearCommunicationDevice()
                    if (e is SecurityException) requestPermission(owner)
                    else { reset(); listener.cancelled() }
                }
            }
        }
    }

    private fun startRecording(owner: CaptureSession) {
        requireMain()
        if (!current(owner) || !owner.capturing) return
        if (dependencies == null) focusAudio()
        createRecorderAndJob(settings.recordingConfiguration.preferBluetoothMic, owner)
        owner.preload = lifecycleScope.launch(worker) {
            try {
                preloadModels()
                if (!current(owner)) return@launch
            } catch (_: InvalidModelException) {
                withContext(main) {
                    if (current(owner)) { reset(); listener.modelLoadingFailed() }
                }
            }
        }
    }

    private fun enqueue(owner: CaptureSession, publication: () -> Unit) {
        if (current(owner)) owner.publications.trySend(publication)
    }
    private fun runnerCallback(owner: CaptureSession): ModelInferenceCallback = object : ModelInferenceCallback {
        override fun updateStatus(state: InferenceState) = enqueue(owner) { listener.decodingStatus(state) }
        override fun languageDetected(language: Language) = enqueue(owner) { listener.languageDetected(language) }
        override fun partialResult(string: String) {
            if (!isBlankResult(string)) enqueue(owner) { listener.partialResult(string) }
        }
    }
    private suspend fun publishFinal(owner: CaptureSession, text: String) {
        val delivered = CompletableDeferred<Unit>()
        if (!current(owner)) return
        val publisher = owner.publisher ?: return
        owner.terminalWaiter = delivered
        val completion = publisher.invokeOnCompletion { failure ->
            if (failure == null) delivered.cancel() else delivered.completeExceptionally(failure)
        }
        try {
            if (owner.publications.trySend {
                try { listener.finished(text) }
                catch (failure: Throwable) { delivered.completeExceptionally(failure); throw failure }
                finally { if (!delivered.isCompleted) delivered.complete(Unit) }
            }.isFailure) return
            delivered.await()
        } finally {
            completion.dispose()
            owner.terminalWaiter = null
            // Release retained transcript closures after final, failure or cancellation.
            owner.publications.cancel()
            owner.publisher?.cancel()
        }
    }

    private suspend fun runModel(owner: CaptureSession) {
        owner.preload?.let {
            if (it.isActive) {
                println("Model was not finished loading...")
                it.join()
            }
        }

        if (!current(owner)) return
        val floatArray = owner.samples.floats.array().sliceArray(0 until owner.samples.floats.position())
        // Exact zero only. This does not classify nonzero noise or reject quiet speech.
        if (isExactEmptyCapture(floatArray)) {
            publishFinal(owner, "")
            return
        }

        yield()
        if (!current(owner)) return
        val outputText = try {
             if (dependencies != null) dependencies.decode(floatArray, runnerCallback(owner)).trim()
             else modelRunner.run(
                floatArray,
                settings.modelRunConfiguration,
                settings.decodingConfiguration,
                runnerCallback(owner)
            ).trim()
        }catch(e: InferenceCancelledException) {
            yield()
            return
        }

        val text = when {
            isBlankResult(outputText) -> ""
            else -> outputText
        }

        yield()
        if (!current(owner)) return
        publishFinal(owner, text)
    }

    private fun onFinishRecording(owner: CaptureSession) {
        requireMain()
        // Stop also works during a pending route switch: preserve this session's collected
        // audio, cancel the pending restart, then wait for its captured reader before decode.
        owner.capturing = false
        owner.routeRequest++
        owner.switchJob?.cancel()
        val oldReader = owner.reader
        owner.recorder?.stop()
        oldReader?.cancel()
        listener.processing()
        owner.decode = lifecycleScope.launch {
            oldReader?.join()
            if (!current(owner)) return@launch
            withContext(worker) {
                if (current(owner)) runModel(owner)
            }
        }
    }
}
