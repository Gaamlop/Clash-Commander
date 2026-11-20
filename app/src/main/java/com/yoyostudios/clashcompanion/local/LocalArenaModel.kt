package com.yoyostudios.clashcompanion.local

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.google.gson.Gson
import java.nio.FloatBuffer
import kotlinx.coroutines.*

/** Bundled neural perception. No HTTP client, API key or runtime model download. */
object LocalArenaModel {
    @Volatile var enabled = false
    @Volatile var status: String = "Motor local preparado para iniciar"; private set
    @Volatile var selfTestPassed = false; private set
    @Volatile var executionProvider = "Preparando"; private set
    private var appContext: Context? = null
    private var session: OrtSession? = null
    private var labels: List<String> = emptyList()
    private val env by lazy { OrtEnvironment.getEnvironment() }
    private val warmScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var warmJob: Job? = null

    fun initialize(context: Context) { appContext = context.applicationContext }

    @Synchronized fun warmUp() {
        if (session != null || warmJob?.isActive == true) return
        warmJob = warmScope.launch { runCatching { prepare() } }
    }

    fun tryObserve(frame: Bitmap): LocalBoard? = try { observe(frame) } catch (error: Exception) {
        status = "Detector local: ${error.javaClass.simpleName}; mano sigue disponible"
        null
    }

    @Synchronized private fun prepare(accelerated: Boolean = true) {
        if (session != null) return
        val context = checkNotNull(appContext) { "Local model context unavailable" }
        status = "Cargando detector local..."
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val created = try {
            val bytes = context.assets.open("local_hypno/arena.onnx").use { it.readBytes() }
            val contract = context.assets.open("local_hypno/model_contract.json").bufferedReader().use {
                com.google.gson.JsonParser.parseReader(it).asJsonObject
            }
            val checksum = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(checksum == contract.get("output_sha256").asString) { "Local model integrity mismatch" }
            options.use {
                if (accelerated) it.addNnapi(java.util.EnumSet.of(
                    ai.onnxruntime.providers.NNAPIFlags.USE_FP16,
                    ai.onnxruntime.providers.NNAPIFlags.CPU_DISABLED))
                env.createSession(bytes, it)
            }
        } catch (error: Exception) {
            if (accelerated) { prepare(false); return }
            status = "No se pudo cargar el detector local (${error.javaClass.simpleName})"
            throw error
        }
        try {
            labels = context.assets.open("local_hypno/labels.json").bufferedReader().use {
                Gson().fromJson(it, Array<String>::class.java).toList()
            }
            require(labels.size == 393 && created.inputNames == setOf("images"))
            session = created
            // Exercise the native runtime, decoder and class mapping on a real public frame
            // before the first live gesture. This is a compatibility check, not match validation.
            val fixture = context.assets.open("local_hypno/selftest.jpg").use { BitmapFactory.decodeStream(it) }
            requireNotNull(fixture)
            val checked = try { observe(fixture) } finally { fixture.recycle() }
            check(checked.sceneVerified && checked.units.any { !it.enemy && it.base == "cannon" }) {
                "Local detector self-test failed"
            }
            selfTestPassed = true
            executionProvider = if (accelerated) "NNAPI" else "CPU"
            status = "Autoprueba local correcta"
        } catch (error: Exception) {
            session = null; created.close()
            if (accelerated) { prepare(false); return }
            throw error
        }
    }

    /** Called only by the scanner: hand, bar and arena all come from the same owned frame. */
    @Synchronized fun observe(frame: Bitmap): LocalBoard {
        prepare()
        val start = SystemClock.elapsedRealtime()
        val geo = ArenaLetterbox(frame.width, frame.height)
        val pixels = IntArray(geo.cropWidth * geo.cropHeight)
        frame.getPixels(pixels, 0, geo.cropWidth, geo.cropX, geo.cropY, geo.cropWidth, geo.cropHeight)
        val rgb = ArenaPreprocessor.encodeCrop(pixels, geo)
        val units = OnnxTensor.createTensor(env, FloatBuffer.wrap(rgb), longArrayOf(1, 3, 640, 640)).use { input ->
            checkNotNull(session).run(mapOf("images" to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val batch = result[0].value as Array<Array<FloatArray>>
                require(batch.size == 1)
                YoloArenaDecoder.decode(batch[0], labels, geo)
            }
        }
        val elapsed = SystemClock.elapsedRealtime() - start
        status = "Local: ${units.count { it.enemy && !it.tower }} rivales | ${elapsed}ms"
        return LocalBoard(units, elapsed, HeroButtonDetector.read(frame))
    }
}
