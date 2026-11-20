package com.yoyostudios.clashcompanion.overlay

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Environment
import android.os.Build
import kotlin.concurrent.thread
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.yoyostudios.clashcompanion.BuildConfig
import com.yoyostudios.clashcompanion.accessibility.ClashCompanionAccessibilityService
import com.yoyostudios.clashcompanion.capture.ScreenCaptureService
import com.yoyostudios.clashcompanion.command.CommandRouter
import com.yoyostudios.clashcompanion.detection.ArenaDetector
import com.yoyostudios.clashcompanion.detection.HandDetector
import com.yoyostudios.clashcompanion.speech.SpeechService
import com.yoyostudios.clashcompanion.util.Coordinates

class OverlayManager(private val context: Context) {

    companion object {
        private const val TAG = "ClashCompanion"

        // Tier colors for status display
        private const val COLOR_FAST = 0xFF4CAF50.toInt()      // Green
        private const val COLOR_QUEUE = 0xFF2196F3.toInt()     // Blue
        private const val COLOR_SMART = 0xFFAB47BC.toInt()     // Purple
        private const val COLOR_TARGET = 0xFFFFEB3B.toInt()    // Yellow
        private const val COLOR_RULE = 0xFFFF9800.toInt()      // Orange
        private const val COLOR_AUTOPILOT = 0xFFF5A623.toInt() // Gold
        private const val COLOR_ERROR = 0xFFEF5350.toInt()     // Red
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var statusText: TextView? = null
    private var handText: TextView? = null
    private var autopilotText: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())

    // Minimize/maximize state
    private var minimizedView: View? = null
    private var minimizedParams: WindowManager.LayoutParams? = null
    private var isMinimized = false

    // Periodic queue check — ensures retries happen even when hand state is stable
    private val queueCheckRunnable = object : Runnable {
        override fun run() {
            CommandRouter.checkQueue()
            updateHandDisplay(HandDetector.currentHand)
            handler.postDelayed(this, 2000)
        }
    }

    /**
     * Make overlay transparent to ALL touches (including dispatchGesture).
     * Call before injecting taps so the overlay doesn't intercept them.
     */
    fun setPassthrough(enabled: Boolean) {
        val params = layoutParams ?: return
        val view = overlayView ?: return
        if (enabled) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update overlay passthrough: ${e.message}")
        }
    }

    fun show() {
        if (overlayView != null) return

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(210, 20, 20, 20))
            setPadding(20, 12, 20, 12)
        }

        // ── Autopilot indicator (hidden by default) ──
        val autopilotLabel = TextView(context).apply {
            text = "AI AUTOPILOT ACTIVE"
            setTextColor(COLOR_AUTOPILOT)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            visibility = View.GONE
            setPadding(0, 0, 0, 4)
        }
        autopilotText = autopilotLabel
        layout.addView(autopilotLabel)

        // ── Status text (tier-colored command feedback) ──
        val status = TextView(context).apply {
            text = "Clash Companion Ready"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 3
        }
        statusText = status
        layout.addView(status)

        // ── Hand state display (green text showing detected cards) ──
        val handDisplay = TextView(context).apply {
            text = ""
            setTextColor(Color.argb(255, 100, 255, 100))
            textSize = 11f
            maxLines = 7
        }
        handText = handDisplay
        layout.addView(handDisplay)

        // ── Button row: Listen + Minimize + Close ──
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6, 0, 0)
        }

        // Start/Stop Listening toggle
        var isListening = false
        val btnListen = Button(context).apply {
            text = "Listen"
            textSize = 11f
            minWidth = 0; minimumWidth = 0
            minHeight = 0; minimumHeight = 0
            setPadding(16, 6, 16, 6)
            setOnClickListener {
                val svc = SpeechService.instance
                if (svc == null) {
                    updateStatus("ERROR: Start Speech Service first")
                    return@setOnClickListener
                }
                if (!isListening) {
                    svc.onTranscript = { text, latencyMs ->
                        CommandRouter.handleTranscript(text, latencyMs)
                    }
                    svc.startListening()
                    text = "Stop"
                    isListening = true
                    updateStatus("Listening...")
                } else {
                    svc.stopListening()
                    text = "Listen"
                    isListening = false
                    updateStatus("Stopped listening")
                }
            }
        }
        buttonRow.addView(btnListen)

        // Minimize button
        val btnMinimize = Button(context).apply {
            text = "Min"
            textSize = 11f
            minWidth = 0; minimumWidth = 0
            minHeight = 0; minimumHeight = 0
            setPadding(16, 6, 16, 6)
            setOnClickListener { minimize() }
        }
        buttonRow.addView(btnMinimize)

        // Explicit, user-initiated capture diagnostic. No screenshots are
        // uploaded automatically or stored in logs.
        val btnImage = Button(context).apply {
            text = "Img"
            textSize = 11f
            minWidth = 0; minimumWidth = 0
            minHeight = 0; minimumHeight = 0
            setPadding(12, 6, 12, 6)
            setOnClickListener { saveDebugFrame() }
        }
        buttonRow.addView(btnImage)
        buttonRow.addView(Button(context).apply {
            text = "Info"; textSize = 11f
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setPadding(10, 6, 10, 6)
            setOnClickListener { saveLocalReport() }
        })

        // Close overlay button
        val btnClose = Button(context).apply {
            text = "Close"
            textSize = 11f
            minWidth = 0; minimumWidth = 0
            minHeight = 0; minimumHeight = 0
            setPadding(16, 6, 16, 6)
            setOnClickListener { hide() }
        }
        buttonRow.addView(btnClose)

        layout.addView(buttonRow)

        // The autonomous mode can be controlled without starting the microphone.
        val tacticalRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "Defender" to { CommandRouter.handleTranscript("defend", 0) },
            "Atacar I" to { CommandRouter.handleTranscript("push left", 0) },
            "Atacar D" to { CommandRouter.handleTranscript("push right", 0) },
            "Auto" to { CommandRouter.handleTranscript("autopilot", 0) },
            "Parar" to { CommandRouter.stopAutopilot() }
        ).forEach { (label, action) ->
            tacticalRow.addView(Button(context).apply {
                text = label
                textSize = 10f
                minWidth = 0; minimumWidth = 0
                minHeight = 0; minimumHeight = 0
                setPadding(8, 6, 8, 6)
                setOnClickListener { action() }
            })
        }
        layout.addView(tacticalRow)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 200
        }

        layoutParams = params
        overlayView = layout
        windowManager.addView(layout, params)
        CommandRouter.overlay = this
        Log.i(TAG, "Overlay shown")
        updateHandDisplay(HandDetector.currentHand)

        // Auto-start hand scanning with ML classifier
        if (!HandDetector.isScanning && ScreenCaptureService.instance != null) {
            HandDetector.startScanning(CommandRouter.deckCards, context) { hand ->
                handler.post {
                    updateHandDisplay(hand)
                    CommandRouter.checkQueue()
                }
            }
            updateStatus("Scanning hand (ML classifier)")
        }

        // Start arena detection if Roboflow API key is configured
        if (BuildConfig.ROBOFLOW_API_KEY.isNotBlank() && !ArenaDetector.isPolling) {
            ArenaDetector.startPolling { detections ->
                handler.post { CommandRouter.checkRules(detections) }
            }
            Log.i(TAG, "ARENA: Started Roboflow polling")
        }

        // Start periodic queue check (every 2s) for elixir retry
        handler.removeCallbacks(queueCheckRunnable)
        handler.postDelayed(queueCheckRunnable, 2000)
    }

    /**
     * Minimize: hide the full overlay, show a small floating button.
     * Hand scanning, listening, and arena detection KEEP RUNNING.
     */
    private fun minimize() {
        if (isMinimized) return

        overlayView?.visibility = View.GONE

        val restoreBtn = Button(context).apply {
            text = "CC"
            textSize = 11f
            setBackgroundColor(Color.argb(180, 30, 30, 30))
            setTextColor(COLOR_AUTOPILOT)
            setPadding(16, 8, 16, 8)
            minWidth = 0; minimumWidth = 0
            minHeight = 0; minimumHeight = 0
            setOnClickListener { maximize() }
        }

        val miniParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 100
        }

        minimizedView = restoreBtn
        minimizedParams = miniParams
        windowManager.addView(restoreBtn, miniParams)
        isMinimized = true
        Log.i(TAG, "Overlay minimized")
    }

    private fun maximize() {
        if (!isMinimized) return

        minimizedView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        minimizedView = null
        minimizedParams = null

        overlayView?.visibility = View.VISIBLE
        isMinimized = false
        Log.i(TAG, "Overlay maximized")
    }

    fun hide() {
        handler.removeCallbacks(queueCheckRunnable)
        HandDetector.stopScanning()
        ArenaDetector.stopPolling()
        CommandRouter.clearQueue()
        CommandRouter.clearRules()
        CommandRouter.stopAutopilot()
        SpeechService.instance?.apply {
            stopListening()
            onTranscript = null
        }
        // Remove minimized button if present
        minimizedView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            minimizedView = null
            minimizedParams = null
        }
        isMinimized = false
        overlayView?.let {
            windowManager.removeView(it)
            overlayView = null
            statusText = null
            handText = null
            autopilotText = null
            layoutParams = null
            Log.i(TAG, "Overlay hidden")
        }
    }

    fun updateStatus(message: String) {
        // Apply tier color based on message prefix
        val color = when {
            message.startsWith("FAST:") -> COLOR_FAST
            message.startsWith("QUEUE") || message.startsWith("THEN:") -> COLOR_QUEUE
            message.startsWith("SMART:") || message.startsWith("AUTO:") -> COLOR_SMART
            message.startsWith("TARGET:") -> COLOR_TARGET
            message.startsWith("RULE") -> COLOR_RULE
            message.startsWith("AUTOPILOT") -> COLOR_AUTOPILOT
            message.startsWith("ERROR") || message.startsWith("Not in hand") -> COLOR_ERROR
            else -> Color.WHITE
        }
        statusText?.setTextColor(color)
        statusText?.text = message
        Log.i(TAG, "Status: $message")
    }

    /**
     * Show/hide the autopilot active indicator.
     */
    fun setAutopilotActive(active: Boolean) {
        autopilotText?.visibility = if (active) View.VISIBLE else View.GONE
    }

    fun updateHandDisplay(hand: Map<Int, String>) {
        val slots = (0..3).map { hand[it] ?: "?" }
        val next = hand[4] ?: "?"
        val queueInfo = CommandRouter.getQueueDisplay()
        val rulesInfo = CommandRouter.getRulesDisplay()
        val sb = StringBuilder("Hand: ${slots.joinToString(" | ")} | Next: $next")
        sb.append("\n").append(HandDetector.diagnosticStatus)
        if (queueInfo.isNotEmpty()) sb.append("\nQueue:\n$queueInfo")
        if (rulesInfo.isNotEmpty()) sb.append("\nRules:\n$rulesInfo")
        if (ArenaDetector.isPolling) {
            val detCount = ArenaDetector.currentDetections.size
            if (detCount > 0) sb.append("\nArena: $detCount detected")
        }
        handText?.text = sb.toString()
    }
    /**
     * Save precisely what Android's MediaProjection gives the classifier.
     * Helps distinguish stale/app-only capture from poor model recognition.
     * This runs only when the user taps the Img button.
     */
    private fun saveDebugFrame() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            updateStatus("IMG: Disponible desde Android 10")
            return
        }
        val snapshot = try {
            ScreenCaptureService.copyLatestSnapshot()?.bitmap
        } catch (e: Exception) {
            Log.w(TAG, "IMG: Failed copying screen frame: ${e.message}")
            null
        }
        if (snapshot == null) {
            updateStatus("IMG: No hay captura. Reinicia Screen Capture")
            return
        }

        updateStatus("IMG: Guardando captura...")
        thread(name = "Clash-DebugCapture") {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME,
                        "ClashCommander-debug-${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/ClashCommander")
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                ) ?: throw IllegalStateException("No se pudo crear la imagen")
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    check(snapshot.compress(Bitmap.CompressFormat.JPEG, 85, stream)) {
                        "No se pudo guardar JPG"
                    }
                } ?: throw IllegalStateException("No hay acceso al archivo")
                handler.post {
                    updateStatus("IMG: Guardada en Fotos/Pictures/ClashCommander")
                }
            } catch (e: Exception) {
                Log.w(TAG, "IMG: Capture export failed: ${e.message}")
                handler.post {
                    updateStatus("IMG: Error al guardar ${e.message?.take(60)}")
                }
            } finally {
                snapshot.recycle()
            }
        }
    }

    private fun saveLocalReport() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        thread(name = "Clash-LocalReport") {
            try {
                val report = com.yoyostudios.clashcompanion.local.LocalMatchTrace.snapshot()
                check(report.isNotBlank())
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "ClashCommander-informe-${System.currentTimeMillis()}.zip")
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ClashCommander")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("No report destination")
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    java.util.zip.ZipOutputStream(output).use { zip ->
                        zip.putNextEntry(java.util.zip.ZipEntry("observations.jsonl"))
                        zip.write(report.toByteArray())
                        zip.closeEntry()
                        com.yoyostudios.clashcompanion.local.LocalCaptureEvidence.appendTo(zip)
                    }
                }
                    ?: error("No report stream")
                handler.post { updateStatus("INFO: Informe guardado en Descargas/ClashCommander") }
            } catch (error: Exception) {
                handler.post { updateStatus("INFO: Aun no hay informe local o no se pudo guardar") }
            }
        }
    }

}
