package com.yoyostudios.clashcompanion.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class ClashCompanionAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ClashCompanion"
        var instance: ClashCompanionAccessibilityService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isTouchActive = false
    private val releaseTouch = Runnable { isTouchActive = false }
    private val gestureToken = Any()
    private var gestureGeneration = 0L
    private var pendingResult: ((Boolean) -> Unit)? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                handler.removeCallbacks(releaseTouch)
                isTouchActive = true
            }
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> {
                handler.removeCallbacks(releaseTouch)
                handler.postDelayed(releaseTouch, 100)
            }
        }
    }

    override fun onInterrupt() {
        cancelPendingGestures()
        Log.w(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        handler.removeCallbacks(releaseTouch)
        cancelPendingGestures()
        super.onDestroy()
        instance = null
        Log.i(TAG, "AccessibilityService destroyed")
    }

    /**
     * Safe tap that avoids the Android 15/16 dispatchGesture freeze bug.
     * Won't fire if user is currently touching the screen.
     */
    fun safeTap(x: Float, y: Float, callback: GestureResultCallback? = null): Boolean {
        if (isTouchActive) {
            Log.w(TAG, "Touch active — skipping gesture (freeze prevention)")
            return false
        }
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return dispatchGesture(gesture, callback, handler)
    }

    /**
     * Safe drag from one point to another.
     */
    fun safeDrag(
        fromX: Float, fromY: Float,
        toX: Float, toY: Float,
        durationMs: Long = 200,
        callback: GestureResultCallback? = null
    ): Boolean {
        if (isTouchActive) {
            Log.w(TAG, "Touch active — skipping drag (freeze prevention)")
            return false
        }
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, callback, null)
    }

    /**
     * Play a card: tap the card slot, wait briefly, then tap the target zone.
     */
    fun cancelPendingGestures() {
        gestureGeneration++
        handler.removeCallbacksAndMessages(gestureToken)
        val result = pendingResult
        pendingResult = null
        result?.invoke(false)
    }

    // Success means both gestures completed, not that the game spent elixir.
    fun playCard(
        slotX: Float, slotY: Float, zoneX: Float, zoneY: Float,
        onResult: (Boolean) -> Unit = {}
    ) {
        cancelPendingGestures()
        val generation = gestureGeneration
        var completed = false
        fun finish(success: Boolean) {
            if (completed || generation != gestureGeneration) return
            completed = true
            pendingResult = null
            handler.removeCallbacksAndMessages(gestureToken)
            onResult(success)
        }
        pendingResult = { success ->
            if (!completed) {
                completed = true
                onResult(success)
            }
        }
        handler.postAtTime(
            { finish(false) }, gestureToken, android.os.SystemClock.uptimeMillis() + 1_500L
        )
        val tapOk = safeTap(slotX, slotY, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (completed || generation != gestureGeneration) return
                handler.postAtTime({
                    if (completed || generation != gestureGeneration) return@postAtTime
                    val accepted = safeTap(zoneX, zoneY, object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) = finish(true)
                        override fun onCancelled(gestureDescription: GestureDescription?) = finish(false)
                    })
                    if (!accepted) finish(false)
                }, gestureToken, android.os.SystemClock.uptimeMillis() + 80L)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                finish(false)
            }
        })
        if (!tapOk) {
            finish(false)
        }
    }
}
