package com.yoyostudios.clashcompanion.local

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Opted-in Auto diagnostics: game-only, no top names/notifications, ten private bounded samples. */
object LocalCaptureEvidence {
    private var directory: File? = null
    private var lastAt = Long.MIN_VALUE
    private var sequence = 0
    private var writing: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Any()
    fun initialize(context: Context) { directory = File(context.filesDir, "local-hypno-evidence") }

    /** Caller must verify the foreground package AND neural game scene before passing a frame. */
    fun sample(frame: Bitmap, id: Long, captureSession: Long, capturedAtMs: Long, hand: Map<Int,String>, elixir: Int?) {
        val folder = directory ?: return
        val now = SystemClock.elapsedRealtime()
        if (writing?.isActive == true || (lastAt != Long.MIN_VALUE && now - lastAt < 5_000)) return
        lastAt = now
        val top = (frame.height * .18f).toInt()
        val crop = Bitmap.createBitmap(frame, 0, top, frame.width, frame.height - top)
        val width = frame.width.coerceAtMost(720)
        val scaled = Bitmap.createScaledBitmap(crop, width, (crop.height.toLong()*width/frame.width).toInt(), true)
        if (crop !== scaled && crop !== frame) crop.recycle()
        val index = sequence++ % 10
        val metadata = Gson().toJson(mapOf("frame" to id, "capture_session" to captureSession,
            "captured_at_ms" to capturedAtMs,
            "source_width" to frame.width, "source_height" to frame.height, "crop_top" to top,
            "saved_width" to scaled.width, "saved_height" to scaled.height,
            "predicted_hand" to hand, "elixir_lower_bound" to elixir, "labels_are_predictions" to true))
        writing = scope.launch {
            try {
                val bytes = ByteArrayOutputStream().use { out ->
                    check(scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)); out.toByteArray()
                }
                if (bytes.size <= 200_000) synchronized(lock) {
                    folder.mkdirs()
                    File(folder, "frame-$index.jpg").writeBytes(bytes)
                    File(folder, "frame-$index.json").writeText(metadata)
                }
            } catch (_: Exception) {
                // Diagnostics cannot stop a match or expose platform exception text.
            } finally { if (scaled !== frame) scaled.recycle() }
        }
    }

    fun appendTo(zip: ZipOutputStream) { synchronized(lock) {
        directory?.listFiles()?.filter { it.name.matches(Regex("frame-[0-9]\\.(jpg|json)")) }
            ?.sortedBy { it.name }?.forEach { file ->
                zip.putNextEntry(ZipEntry("evidence/" + file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
