package com.yoyostudios.clashcompanion.capture

/** Monotonic-clock freshness shared by capture and recognition. */
object CaptureFreshness {
    fun isFresh(capturedAtElapsedRealtime: Long, nowElapsedRealtime: Long, maxAgeMs: Long): Boolean =
        capturedAtElapsedRealtime >= 0L && maxAgeMs >= 0L &&
            nowElapsedRealtime >= capturedAtElapsedRealtime &&
            nowElapsedRealtime - capturedAtElapsedRealtime <= maxAgeMs
}
