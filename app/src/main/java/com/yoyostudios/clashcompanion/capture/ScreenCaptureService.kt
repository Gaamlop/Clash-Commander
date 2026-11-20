package com.yoyostudios.clashcompanion.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicLong

class ScreenCaptureService : Service() {
    data class CaptureMetadata(
        val frameId: Long,
        val capturedAtElapsedRealtime: Long,
        val captureSessionId: Long,
        val width: Int,
        val height: Int
    )

    /** The caller owns [bitmap] and must recycle it after use. */
    data class CaptureSnapshot(val bitmap: Bitmap, val metadata: CaptureMetadata) {
        val frameId: Long get() = metadata.frameId
        val capturedAtElapsedRealtime: Long get() = metadata.capturedAtElapsedRealtime
        val captureSessionId: Long get() = metadata.captureSessionId
    }

    companion object {
        private const val TAG = "ClashCompanion"
        private const val CHANNEL_ID = "clash_companion_capture"
        private const val NOTIFICATION_ID = 1001
        private const val MIN_FRAME_INTERVAL_MS = 100L
        const val DEFAULT_MAX_FRAME_AGE_MS = 2_000L
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        private val sessionSequence = AtomicLong()
        private val frameSequence = AtomicLong()

        @Volatile
        var instance: ScreenCaptureService? = null
            private set

        /** Copy under the same lock that protects replacement and recycling. */
        fun copyLatestSnapshot(maxAgeMs: Long = DEFAULT_MAX_FRAME_AGE_MS): CaptureSnapshot? =
            instance?.copySnapshot(maxAgeMs)

        fun getCaptureMetadata(): CaptureMetadata? = instance?.captureMetadata()
        fun currentCaptureSessionId(): Long? = instance?.captureSessionId()
    }

    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val frameLock = Any()
    private var latestFrame: CaptureSnapshot? = null
    private var activeSessionId: Long? = null
    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private fun copySnapshot(maxAgeMs: Long): CaptureSnapshot? = synchronized(frameLock) {
        val frame = latestFrame ?: return@synchronized null
        if (frame.captureSessionId != activeSessionId ||
            !CaptureFreshness.isFresh(frame.capturedAtElapsedRealtime, SystemClock.elapsedRealtime(), maxAgeMs)
        ) return@synchronized null
        val copy = frame.bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return@synchronized null
        CaptureSnapshot(copy, frame.metadata)
    }

    private fun captureMetadata(): CaptureMetadata? = synchronized(frameLock) {
        latestFrame?.takeIf { it.captureSessionId == activeSessionId }?.metadata
    }

    private fun captureSessionId(): Long? = synchronized(frameLock) { activeSessionId }

    private fun invalidateFrames() = synchronized(frameLock) {
        activeSessionId = null
        latestFrame?.bitmap?.recycle()
        latestFrame = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        captureThread = HandlerThread("Clash-Capture").apply { start() }
        captureHandler = Handler(captureThread.looper)
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
        Log.i(TAG, "ScreenCaptureService created: ${screenWidth}x${screenHeight}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            releaseCapture()
            stopSelf()
            return START_NOT_STICKY
        }

        // Renewing authorization invalidates observations and in-flight AI decisions.
        releaseCapture()
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = manager.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("MediaProjection unavailable")
            mediaProjection = projection
            val sessionId = sessionSequence.incrementAndGet()
            synchronized(frameLock) { activeSessionId = sessionId }
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    if (captureSessionId() == sessionId) {
                        invalidateFrames()
                        stopSelf()
                    }
                }
            }
            projectionCallback = callback
            projection.registerCallback(callback, Handler(Looper.getMainLooper()))
            startCapture(sessionId)
        } catch (e: Exception) {
            // Authorization intents and provider responses must not reach logs.
            Log.e(TAG, "ScreenCapture: unable to start (${e.javaClass.simpleName})")
            releaseCapture()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(sessionId: Long) {
        val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        var lastPublishedAt = -MIN_FRAME_INTERVAL_MS
        reader.setOnImageAvailableListener({ source ->
            val image = try {
                source.acquireLatestImage()
            } catch (_: IllegalStateException) {
                null // Reader may close while this callback is queued.
            } ?: return@setOnImageAvailableListener
            var pendingBitmap: Bitmap? = null
            try {
                val capturedAt = SystemClock.elapsedRealtime()
                if (capturedAt - lastPublishedAt < MIN_FRAME_INTERVAL_MS || captureSessionId() != sessionId) {
                    return@setOnImageAvailableListener
                }
                val plane = image.planes[0]
                val rowPadding = plane.rowStride - plane.pixelStride * screenWidth
                val bitmapWidth = screenWidth + rowPadding / plane.pixelStride
                val padded = Bitmap.createBitmap(bitmapWidth, screenHeight, Bitmap.Config.ARGB_8888)
                pendingBitmap = padded
                padded.copyPixelsFromBuffer(plane.buffer)
                val cropped = if (bitmapWidth != screenWidth) {
                    Bitmap.createBitmap(padded, 0, 0, screenWidth, screenHeight).also {
                        padded.recycle()
                        pendingBitmap = it
                    }
                } else padded
                synchronized(frameLock) {
                    if (activeSessionId == sessionId) {
                        val metadata = CaptureMetadata(
                            frameSequence.incrementAndGet(), capturedAt, sessionId, cropped.width, cropped.height
                        )
                        latestFrame?.bitmap?.recycle()
                        latestFrame = CaptureSnapshot(cropped, metadata)
                        pendingBitmap = null // Service now owns the published bitmap.
                        lastPublishedAt = capturedAt
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "ScreenCapture frame rejected (${e.javaClass.simpleName})")
            } finally {
                pendingBitmap?.recycle()
                image.close()
            }
        }, captureHandler)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ClashCompanionCapture", screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, captureHandler
        )
        Log.i(TAG, "ScreenCapture started: ${screenWidth}x${screenHeight}")
    }

    private fun releaseCapture() {
        invalidateFrames()
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        projectionCallback?.let { mediaProjection?.unregisterCallback(it) }
        projectionCallback = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Screen Capture", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Clash Companion screen capture for card detection"
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification = Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("Clash Companion")
        .setContentText("Screen capture active")
        .setSmallIcon(android.R.drawable.ic_menu_camera)
        .setOngoing(true)
        .build()

    override fun onDestroy() {
        releaseCapture()
        if (instance === this) instance = null
        captureThread.quitSafely()
        super.onDestroy()
        Log.i(TAG, "ScreenCaptureService destroyed")
    }
}
