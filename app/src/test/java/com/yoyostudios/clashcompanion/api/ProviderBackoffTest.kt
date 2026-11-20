package com.yoyostudios.clashcompanion.api

import org.junit.Assert.*
import org.junit.Test

class ProviderBackoffTest {
    @Test fun repeatedCyclesDoNotSpendQuotaUntilDelayExpires() {
        var now = 1_000L
        val backoff = ProviderBackoff { now }
        backoff.checkAllowed()
        backoff.rateLimited(60_000)
        repeat(14) {
            now += 4_000
            assertTrue(runCatching { backoff.checkAllowed() }.exceptionOrNull() is ProviderCooldownException)
        }
        now = 61_000
        backoff.checkAllowed()
        backoff.succeeded()
        assertEquals(0L, backoff.remainingMs())
    }

    @Test fun missingRetryAfterUsesBoundedIncreasingBackoffAndSuccessResetsIt() {
        var now = 1_000L
        val backoff = ProviderBackoff { now }
        for (delay in listOf(30_000L, 60_000L, 120_000L, 120_000L)) {
            backoff.rateLimited(null)
            assertEquals(delay, backoff.remainingMs())
            now += delay
        }
        backoff.succeeded()
        backoff.rateLimited(null)
        assertEquals(30_000L, backoff.remainingMs())
    }

    @Test fun concurrentOlderSuccessDoesNotEraseQuotaFailure() {
        val backoff = ProviderBackoff { 1_000 }
        backoff.rateLimited(90_000)
        backoff.succeeded()
        assertEquals(90_000L, backoff.remainingMs())
    }

    @Test fun acceptsSecondsAndHttpDateButRejectsPrivateOrMalformedText() {
        assertEquals(60_000L, ProviderBackoff.parseRetryAfter("60"))
        assertEquals(60_000L, ProviderBackoff.parseRetryAfter("Thu, 01 Jan 1970 00:01:00 GMT", 0))
        assertNull(ProviderBackoff.parseRetryAfter("private provider diagnostic"))
        assertNull(ProviderBackoff.parseRetryAfter("-1"))
        assertNull(ProviderBackoff.parseRetryAfter("99999999999999999999999"))
        assertEquals(86_400_000L, ProviderBackoff.parseRetryAfter("999999999"))
    }
}
