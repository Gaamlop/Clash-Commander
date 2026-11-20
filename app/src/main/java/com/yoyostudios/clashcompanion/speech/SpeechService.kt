package com.yoyostudios.clashcompanion.speech

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import com.k2fsa.sherpa.onnx.*
import com.yoyostudios.clashcompanion.deck.DeckManager
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Offline Spanish Whisper and English Zipformer command recognition. */
class SpeechService : Service() {
    companion object {
        private const val TAG = "ClashCompanion"
        private const val CHANNEL_ID = "clash_companion_speech"
        private const val NOTIFICATION_ID = 1002
        private const val SAMPLE_RATE = 16000
        private const val SILENCE_PADDING_SAMPLES = 4800
        // A new directory avoids trusting partially extracted files from older builds.
        private const val MODEL_ROOT = "speech_models_v2"
        private const val ENGLISH_MODEL = "sherpa-onnx-zipformer-en-2023-04-01"
        private const val SPANISH_MODEL = "sherpa-onnx-whisper-tiny"

        @Volatile
        var instance: SpeechService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    // Configuration and recorder lifecycle are serialized off the Android main thread.
    private val control = Executors.newSingleThreadExecutor { task ->
        Thread(task, "STT-Control").apply { isDaemon = true }
    }
    private val nativeLock = Any()
    private var vad: Vad? = null
    private var offlineRecognizer: OfflineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var loadedLanguage: String? = null

    @Volatile private var destroyed = false
    @Volatile private var isListening = false
    @Volatile private var listeningRequested = false
    @Volatile private var isModelLoaded = false
    @Volatile private var selectedLanguage = "es"
    private val listeningSession = AtomicLong(0L)

    @Volatile
    var loadError: String? = null
        private set

    /** Only actual recognized speech is sent here; service status is never parsed as a command. */
    var onTranscript: ((text: String, latencyMs: Long) -> Unit)? = null
    var onReadyStateChanged: ((ready: Boolean, error: String?) -> Unit)? = null

    fun isReady(): Boolean = isModelLoaded && loadedLanguage == selectedLanguage && !destroyed
    fun getLanguage(): String = selectedLanguage

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        selectedLanguage = SpeechLanguage.get(this)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        enqueue { configureRecognizer() }
        return START_NOT_STICKY
    }

    /** Language changes retain the transcript callback and resume requested listening. */
    fun setLanguage(language: String) {
        val normalized = SpeechLanguage.normalize(language)
        SpeechLanguage.set(this, normalized)
        if (selectedLanguage != normalized) {
            selectedLanguage = normalized
            isModelLoaded = false
            loadError = null
            // Prevent an old-language transcript already in flight from becoming a command.
            listeningSession.incrementAndGet()
            isListening = false
            notifyReadyState()
        }
        enqueue { configureRecognizer() }
    }

    /** English hotwords belong to its transducer; Whisper uses its multilingual tokenizer. */
    fun reloadHotwords() {
        enqueue {
            if (selectedLanguage == "en") configureRecognizer(force = true)
        }
    }

    private fun enqueue(action: () -> Unit) {
        if (destroyed) return
        try {
            control.execute { if (!destroyed) action() }
        } catch (_: RejectedExecutionException) {
            // A UI callback can arrive while Android is destroying this service.
        }
    }

    private fun notifyReadyState() {
        handler.post {
            if (!destroyed) onReadyStateChanged?.invoke(isReady(), loadError)
        }
    }

    private fun configureRecognizer(force: Boolean = false) {
        if (destroyed) return
        val language = selectedLanguage
        if (!force && isModelLoaded && loadedLanguage == language) return
        stopListeningInternal()
        isModelLoaded = false
        loadError = null
        notifyReadyState()

        var replacement: OfflineRecognizer? = null
        var replacementVad: Vad? = null
        try {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val modelRoot = File(filesDir, MODEL_ROOT)
            extractAssetAtomically("silero_vad.onnx", modelRoot)
            extractAssetAtomically("hotwords.txt", modelRoot)
            val config = if (language == "es") {
                val files = listOf("tiny-encoder.int8.onnx", "tiny-decoder.int8.onnx", "tiny-tokens.txt")
                files.forEach { extractAssetAtomically("$SPANISH_MODEL/$it", modelRoot) }
                val modelDir = File(modelRoot, SPANISH_MODEL).absolutePath
                OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(
                            encoder = "$modelDir/tiny-encoder.int8.onnx",
                            decoder = "$modelDir/tiny-decoder.int8.onnx",
                            language = "es",
                            task = "transcribe",
                        ),
                        tokens = "$modelDir/tiny-tokens.txt",
                        numThreads = 2,
                        provider = "cpu",
                        debug = false,
                    ),
                    decodingMethod = "greedy_search",
                )
            } else {
                val files = listOf(
                    "encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.int8.onnx",
                    "joiner-epoch-99-avg-1.int8.onnx", "tokens.txt", "bpe.vocab",
                )
                files.forEach { extractAssetAtomically("$ENGLISH_MODEL/$it", modelRoot) }
                val modelDir = File(modelRoot, ENGLISH_MODEL).absolutePath
                val deckHotwords = File(filesDir, DeckManager.DECK_HOTWORDS_FILE)
                val hotwords = if (deckHotwords.isFile && deckHotwords.length() > 0) {
                    deckHotwords.absolutePath
                } else {
                    File(modelRoot, "hotwords.txt").absolutePath
                }
                OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = "$modelDir/encoder-epoch-99-avg-1.int8.onnx",
                            decoder = "$modelDir/decoder-epoch-99-avg-1.int8.onnx",
                            joiner = "$modelDir/joiner-epoch-99-avg-1.int8.onnx",
                        ),
                        tokens = "$modelDir/tokens.txt",
                        numThreads = 2,
                        debug = false,
                        provider = "cpu",
                        modelingUnit = "bpe",
                        bpeVocab = "$modelDir/bpe.vocab",
                    ),
                    decodingMethod = "modified_beam_search",
                    maxActivePaths = 4,
                    hotwordsFile = hotwords,
                    hotwordsScore = 2.0f,
                )
            }
            replacement = OfflineRecognizer(config = config)
            if (vad == null) {
                replacementVad = Vad(config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = File(modelRoot, "silero_vad.onnx").absolutePath,
                        minSilenceDuration = 0.25f,
                        minSpeechDuration = 0.25f,
                        threshold = 0.5f,
                        windowSize = 512,
                    ),
                    sampleRate = SAMPLE_RATE,
                ))
            }
            if (destroyed || selectedLanguage != language) return
            synchronized(nativeLock) {
                offlineRecognizer?.release()
                offlineRecognizer = replacement
                replacement = null
                replacementVad?.let { vad = it; replacementVad = null }
            }
            loadedLanguage = language
            isModelLoaded = true
            loadError = null
            Log.i(TAG, "STT: Offline $language model ready in ${android.os.SystemClock.elapsedRealtime() - startedAt}ms")
            notifyReadyState()
            if (listeningRequested) startListeningInternal()
        } catch (error: Exception) {
            reportLoadFailure(error)
        } catch (error: LinkageError) {
            reportLoadFailure(error)
        } finally {
            replacement?.release()
            replacementVad?.release()
        }
    }

    private fun reportLoadFailure(error: Throwable) {
        isModelLoaded = false
        loadError = "No se pudo iniciar la voz ${if (selectedLanguage == "es") "en español" else "en inglés"}. Reintenta Voice Engine."
        Log.e(TAG, "STT: Offline model unavailable (${error.javaClass.simpleName})")
        notifyReadyState()
    }

    /** A failed copy never leaves a destination that a subsequent attempt trusts. */
    private fun extractAssetAtomically(assetPath: String, destinationRoot: File) {
        val destination = File(destinationRoot, assetPath)
        if (destination.isFile && destination.length() > 0) return
        check(destination.parentFile?.mkdirs() == true || destination.parentFile?.isDirectory == true)
        val temporary = File.createTempFile(destination.name, ".part", destination.parentFile)
        try {
            assets.open(assetPath).use { input ->
                temporary.outputStream().use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            check(temporary.length() > 0) { "Empty speech model asset" }
            check(temporary.renameTo(destination)) { "Cannot install speech model asset" }
        } finally {
            temporary.delete()
        }
    }

    fun startListening() {
        listeningRequested = true
        enqueue { startListeningInternal() }
    }

    private fun startListeningInternal() {
        if (destroyed || !listeningRequested || isListening || !isReady()) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            loadError = "Permite el micrófono para dar órdenes de voz."
            notifyReadyState()
            return
        }
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufferSize <= 0) {
            loadError = "El micrófono no admite la captura de voz."
            notifyReadyState()
            return
        }
        try {
            if (audioRecord != null) stopListeningInternal()
            if (!listeningRequested || destroyed) return
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2,
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                throw IllegalStateException("AudioRecord not initialized")
            }
            audioRecord = recorder
            synchronized(nativeLock) { vad?.reset() }
            recorder.startRecording()
            val session = listeningSession.incrementAndGet()
            isListening = true
            loadError = null
            notifyReadyState()
            recordingThread = thread(name = "STT-Recording", isDaemon = true) {
                processAudio(recorder, session)
            }
        } catch (error: Exception) {
            audioRecord?.release()
            audioRecord = null
            isListening = false
            loadError = "No se pudo abrir el micrófono. Reintenta escuchar."
            notifyReadyState()
            Log.e(TAG, "STT: Microphone unavailable (${error.javaClass.simpleName})")
        }
    }

    fun stopListening() {
        listeningRequested = false
        isListening = false
        listeningSession.incrementAndGet()
        enqueue { stopListeningInternal() }
    }

    private fun stopListeningInternal() {
        isListening = false
        listeningSession.incrementAndGet()
        // Stop first to unblock AudioRecord.read; native objects remain alive until decode exits.
        try { audioRecord?.stop() } catch (_: Exception) {}
        val previousThread = recordingThread
        if (previousThread != null && previousThread !== Thread.currentThread()) {
            var interrupted = false
            while (previousThread.isAlive) {
                try { previousThread.join() } catch (_: InterruptedException) { interrupted = true }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
        recordingThread = null
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    private fun isCurrentSession(session: Long): Boolean =
        !destroyed && listeningRequested && isListening && listeningSession.get() == session

    private fun processAudio(recorder: AudioRecord, session: Long) {
        val buffer = ShortArray(512)
        try {
            while (isCurrentSession(session)) {
                val count = recorder.read(buffer, 0, buffer.size)
                if (count < 0) throw IllegalStateException("AudioRecord read failed")
                if (count == 0) continue
                val samples = FloatArray(count) { buffer[it] / 32768.0f }
                synchronized(nativeLock) { if (isCurrentSession(session)) vad?.acceptWaveform(samples) }
                while (isCurrentSession(session)) {
                    val segment = synchronized(nativeLock) {
                        if (vad?.empty() == false) vad?.front() else null
                    } ?: break
                    val startedAt = android.os.SystemClock.elapsedRealtime()
                    val text = synchronized(nativeLock) {
                        if (!isCurrentSession(session)) return@synchronized ""
                        val recognizer = offlineRecognizer ?: return@synchronized ""
                        val padded = FloatArray(SILENCE_PADDING_SAMPLES + segment.samples.size + SILENCE_PADDING_SAMPLES)
                        System.arraycopy(segment.samples, 0, padded, SILENCE_PADDING_SAMPLES, segment.samples.size)
                        val stream = recognizer.createStream()
                        try {
                            stream.acceptWaveform(padded, SAMPLE_RATE)
                            recognizer.decode(stream)
                            recognizer.getResult(stream).text.trim().lowercase(Locale.ROOT)
                        } finally {
                            stream.release()
                            vad?.pop()
                        }
                    }
                    if (text.isNotBlank() && isCurrentSession(session)) {
                        val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                        handler.post {
                            if (isCurrentSession(session)) onTranscript?.invoke(text, elapsed)
                        }
                    }
                }
            }
        } catch (error: Exception) {
            if (isCurrentSession(session)) {
                isListening = false
                loadError = "La escucha se interrumpió. Pulsa escuchar para reintentar."
                notifyReadyState()
                Log.e(TAG, "STT: Audio processing stopped (${error.javaClass.simpleName})")
            }
        }
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Voice Recognition", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Reconocimiento de órdenes sin conexión" }
        )
    }

    private fun buildNotification(): Notification = Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("Clash Commander")
        .setContentText("Órdenes de voz sin conexión")
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setOngoing(true)
        .build()

    override fun onDestroy() {
        destroyed = true
        isModelLoaded = false
        listeningRequested = false
        isListening = false
        listeningSession.incrementAndGet()
        instance = null
        handler.removeCallbacksAndMessages(null)
        // The control executor waits for any native decode before releasing its model.
        control.execute {
            stopListeningInternal()
            synchronized(nativeLock) {
                offlineRecognizer?.release()
                offlineRecognizer = null
                vad?.release()
                vad = null
            }
        }
        control.shutdown()
        super.onDestroy()
    }
}
