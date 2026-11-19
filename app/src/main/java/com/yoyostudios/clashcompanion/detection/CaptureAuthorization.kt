package com.yoyostudios.clashcompanion.detection

/** Fail-closed authorization for a live capture, independent of Android. */
internal class CaptureAuthorization(private val freshnessMs: Long = 2_000L) {
    @Volatile private var lastValidFrameMs: Long = 0L
    fun authorizeFrame(nowMs: Long) { lastValidFrameMs = nowMs }
    fun revoke() { lastValidFrameMs = 0L }
    fun isFresh(nowMs: Long): Boolean {
        val last = lastValidFrameMs
        return last > 0L && nowMs >= last && nowMs - last < freshnessMs
    }
}
