package com.yoyostudios.clashcompanion.api

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class ProviderCooldownException(val remainingMs: Long) : IOException("Gemini rate-limit cooldown")

/** Shared by tactical and playbook requests. Uses monotonic time, never a saved token. */
class ProviderBackoff(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var blockedUntil = 0L
    private var consecutiveLimits = 0

    @Synchronized fun remainingMs(): Long = (blockedUntil - nowMs()).coerceAtLeast(0)

    @Synchronized fun checkAllowed() {
        val remaining = remainingMs()
        if (remaining > 0) throw ProviderCooldownException(remaining)
    }

    @Synchronized fun rateLimited(retryAfterMs: Long?) {
        val fallback = 30_000L * (1L shl consecutiveLimits.coerceAtMost(2))
        consecutiveLimits = (consecutiveLimits + 1).coerceAtMost(3)
        val pause = maxOf(fallback, (retryAfterMs ?: 0).coerceIn(0, MAX_RETRY_MS))
        blockedUntil = maxOf(blockedUntil, nowMs() + pause)
    }

    @Synchronized fun succeeded() {
        // An older concurrent success cannot erase a newly received 429.
        if (remainingMs() == 0L) {
            consecutiveLimits = 0
            blockedUntil = 0
        }
    }

    companion object {
        private const val MAX_RETRY_MS = 86_400_000L

        fun parseRetryAfter(value: String?, wallTimeMs: Long = System.currentTimeMillis()): Long? {
            val header = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (header.all { it in '0'..'9' }) {
                return header.toLongOrNull()?.coerceAtMost(MAX_RETRY_MS / 1_000)?.times(1_000)
            }
            return runCatching {
                (ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli() - wallTimeMs).coerceIn(0, MAX_RETRY_MS)
            }.getOrNull()
        }
    }
}
