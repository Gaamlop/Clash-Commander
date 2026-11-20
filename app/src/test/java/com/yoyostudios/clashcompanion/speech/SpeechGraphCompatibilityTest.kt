package com.yoyostudios.clashcompanion.speech

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Graph/operator compatibility on Linux; Android JNI linking is checked on the APK separately. */
class SpeechGraphCompatibilityTest {
    @Test fun spanishEnglishAndVadGraphsLoadWithTheSharedRuntime() {
        val assets = File(checkNotNull(System.getProperty("clash.assets")))
        val paths = listOf(
            "sherpa-onnx-whisper-tiny/tiny-encoder.int8.onnx",
            "sherpa-onnx-whisper-tiny/tiny-decoder.int8.onnx",
            "sherpa-onnx-zipformer-en-2023-04-01/encoder-epoch-99-avg-1.int8.onnx",
            "sherpa-onnx-zipformer-en-2023-04-01/decoder-epoch-99-avg-1.int8.onnx",
            "sherpa-onnx-zipformer-en-2023-04-01/joiner-epoch-99-avg-1.int8.onnx",
            "silero_vad.onnx",
        )
        val env = OrtEnvironment.getEnvironment()
        for (path in paths) {
            val model = assets.resolve(path)
            assertTrue("Missing speech graph: $path", model.isFile && model.length() > 0)
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2)
                options.setInterOpNumThreads(1)
                env.createSession(model.absolutePath, options).use { session ->
                    assertTrue("No inputs: $path", session.inputNames.isNotEmpty())
                    assertTrue("No outputs: $path", session.outputNames.isNotEmpty())
                }
            }
        }
    }
}
