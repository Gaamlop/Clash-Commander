package com.yoyostudios.clashcompanion.detection

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.yoyostudios.clashcompanion.api.RoboflowClient
import com.yoyostudios.clashcompanion.capture.CaptureFreshness
import com.yoyostudios.clashcompanion.capture.ScreenCaptureService
import com.yoyostudios.clashcompanion.util.Coordinates
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

/** Optional hosted arena detection. Cached results expire even when polling fails. */
object ArenaDetector {
    private const val TAG = "ClashCompanion"
    private const val POLL_INTERVAL_MS = 800L
    private const val DETECTION_MAX_AGE_MS = 3_000L
    private const val JPEG_QUALITY = 70

    data class Detection(
        val className: String,
        val confidence: Float,
        val centerX: Int,
        val centerY: Int,
        val width: Int,
        val height: Int,
        val timestamp: Long = System.currentTimeMillis(),
        val capturedAtElapsedRealtime: Long = SystemClock.elapsedRealtime()
    )

    @Volatile private var cachedDetections: List<Detection> = emptyList()
    @Volatile private var detectionsSessionId: Long? = null
    val currentDetections: List<Detection>
        get() {
            if (detectionsSessionId != ScreenCaptureService.currentCaptureSessionId()) return emptyList()
            val now = SystemClock.elapsedRealtime()
            return cachedDetections.filter {
                CaptureFreshness.isFresh(it.capturedAtElapsedRealtime, now, DETECTION_MAX_AGE_MS)
            }
        }

    val isPolling: Boolean get() = pollJob?.isActive == true
    private var pollJob: Job? = null
    private val generation = AtomicLong()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun startPolling(onDetectionsChanged: ((List<Detection>) -> Unit)? = null) {
        if (pollJob?.isActive == true) return
        val pollingGeneration = generation.incrementAndGet()
        pollJob = scope.launch {
            var previousFrameId = -1L
            var consecutiveErrors = 0
            while (isActive && pollingGeneration == generation.get()) {
                val snapshot = ScreenCaptureService.copyLatestSnapshot()
                if (snapshot == null) {
                    onDetectionsChanged?.invoke(currentDetections)
                    delay(POLL_INTERVAL_MS)
                    continue
                }
                val frame = snapshot.bitmap
                val roi = Coordinates.getArenaROI(frame.width, frame.height)
                val base64 = try {
                    if (snapshot.frameId == previousFrameId || roi == null) null
                    else {
                        previousFrameId = snapshot.frameId
                        val crop = Bitmap.createBitmap(frame, roi.x, roi.y, roi.w, roi.h)
                        try { bitmapToBase64(crop) } finally { if (crop !== frame) crop.recycle() }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "ARENA: Frame rejected (${e.javaClass.simpleName})")
                    null
                } finally {
                    frame.recycle()
                }
                if (base64 != null && roi != null) {
                    try {
                        val response = RoboflowClient.detect(base64)
                        ensureActive()
                        if (pollingGeneration != generation.get() ||
                            ScreenCaptureService.currentCaptureSessionId() != snapshot.captureSessionId
                        ) continue
                        val now = SystemClock.elapsedRealtime()
                        if (!CaptureFreshness.isFresh(snapshot.capturedAtElapsedRealtime, now, DETECTION_MAX_AGE_MS)) {
                            onDetectionsChanged?.invoke(currentDetections)
                            delay(POLL_INTERVAL_MS)
                            continue
                        }
                        val incoming = response.map { detection ->
                            detection.copy(
                                centerX = detection.centerX + roi.x,
                                centerY = detection.centerY + roi.y,
                                capturedAtElapsedRealtime = snapshot.capturedAtElapsedRealtime
                            )
                        }
                        val freshOld = currentDetections.filter { old ->
                            incoming.none {
                                it.className == old.className &&
                                    kotlin.math.abs(it.centerX - old.centerX) < 100 &&
                                    kotlin.math.abs(it.centerY - old.centerY) < 100
                            }
                        }
                        cachedDetections = incoming + freshOld
                        detectionsSessionId = snapshot.captureSessionId
                        consecutiveErrors = 0
                        onDetectionsChanged?.invoke(currentDetections)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        consecutiveErrors++
                        Log.w(TAG, "ARENA: Poll rejected (${e.javaClass.simpleName})")
                        onDetectionsChanged?.invoke(currentDetections)
                        if (consecutiveErrors > 5) {
                            delay(10_000L)
                            consecutiveErrors = 0
                        }
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopPolling() {
        generation.incrementAndGet()
        pollJob?.cancel()
        pollJob = null
        cachedDetections = emptyList()
        detectionsSessionId = null
    }

    fun destroy() {
        stopPolling()
        scope.cancel()
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream))
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }
}
