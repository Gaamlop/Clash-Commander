package com.yoyostudios.clashcompanion.local

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.FloatBuffer

class LocalNativePipelineTest {
    @Test fun realFrameRunsThroughSharedPreprocessingNativeOnnxDecodeAndHypnoDecision() {
        val assets = File(checkNotNull(System.getProperty("clash.assets")))
        // Android's compile stubs omit java.desktop; the host JDK provides ImageIO at runtime.
        val frame = Class.forName("javax.imageio.ImageIO").getMethod("read", File::class.java)
            .invoke(null, assets.resolve("local_hypno/selftest.jpg"))
        val width = frame.javaClass.getMethod("getWidth").invoke(frame) as Int
        val height = frame.javaClass.getMethod("getHeight").invoke(frame) as Int
        val geo = ArenaLetterbox(width, height)
        val integer = Int::class.javaPrimitiveType
        val pixels = frame.javaClass.getMethod("getRGB", integer, integer, integer, integer,
            IntArray::class.java, integer, integer).invoke(frame,
            geo.cropX, geo.cropY, geo.cropWidth, geo.cropHeight, null, 0, geo.cropWidth) as IntArray
        val rgb = ArenaPreprocessor.encodeCrop(pixels, geo)
        assertEquals(3*640*640, rgb.size)
        assertTrue(rgb.all { it.isFinite() && it in 0f..1f })
        assertEquals(114f/255f, rgb[0], .00001f)
        val labels = Gson().fromJson(assets.resolve("local_hypno/labels.json").readText(), Array<String>::class.java).toList()
        val env = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            env.createSession(assets.resolve("local_hypno/arena.onnx").absolutePath, options).use { session ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(rgb), longArrayOf(1,3,640,640)).use { input ->
                    session.run(mapOf("images" to input)).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val output = result[0].value as Array<Array<FloatArray>>
                        val units = YoloArenaDecoder.decode(output[0], labels, geo)
                        assertTrue("Own king missing: $units", units.any { !it.enemy && it.base == "king-tower" })
                        assertTrue("Own cannon missing: $units", units.any { !it.enemy && it.base == "cannon" })
                        assertFalse("Allied cannon became enemy", units.any { it.enemy && it.base == "cannon" })
                        val b = LocalBattle(1,1,1,1_000,mapOf(0 to "Goblin Barrel",1 to "Wall Breakers",2 to "Cannon",3 to "Dart Goblin"),8,
                            LocalBoard(units,0),width,height)
                        val action = HypnoPolicy.decide(b,1_100) as LocalAction.Play
                        assertEquals("Goblin Barrel",action.card)
                        assertTrue(HypnoPolicy.validates(action,b))
                    }
                }
            }
        }
    }
}
