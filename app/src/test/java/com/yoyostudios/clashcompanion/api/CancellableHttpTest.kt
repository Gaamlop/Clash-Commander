package com.yoyostudios.clashcompanion.api

import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

class CancellableHttpTest {
    private class FakeCall : Call {
        lateinit var callback: Callback
        var cancelled = false
        private var executed = false
        override fun request() = Request.Builder().url("https://test.invalid/v1/gemini").build()
        override fun execute(): Response = error("Only asynchronous calls are supported")
        override fun enqueue(responseCallback: Callback) { callback = responseCallback; executed = true }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = executed
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout()
        override fun clone(): Call = FakeCall()
        fun respond(code: Int, body: String, retryAfter: String? = null) = callback.onResponse(this,
            Response.Builder().request(request()).protocol(Protocol.HTTP_1_1)
                .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
                .code(code).message("test").body(body.toResponseBody()).build())
    }

    @Test fun cancellingDecisionAbortsHttpExchange() = runBlocking {
        val call = FakeCall()
        val request = launch(start = CoroutineStart.UNDISPATCHED) { call.awaitResponseBody() }
        assertTrue(call.isExecuted())
        request.cancelAndJoin()
        assertTrue(call.cancelled)
        // A late network failure must not revive the cancelled decision.
        call.callback.onFailure(call, IOException("cancelled"))
        assertTrue(request.isCancelled)
    }

    @Test fun returnsSuccessfulBody() = runBlocking {
        val call = FakeCall()
        val result = async(start = CoroutineStart.UNDISPATCHED) { call.awaitResponseBody() }
        call.respond(200, "response")
        assertEquals("response", result.await())
    }

    @Test fun exposesHttpStatusWithoutProviderBody() = runBlocking {
        val call = FakeCall()
        val result = async(start = CoroutineStart.UNDISPATCHED) { runCatching { call.awaitResponseBody() } }
        call.respond(401, "sensitive-test-diagnostic")
        val error = result.await().exceptionOrNull()
        assertTrue(error is GeminiHttpException)
        assertEquals(401, (error as GeminiHttpException).statusCode)
        assertFalse(error.message.orEmpty().contains("sensitive-test-diagnostic"))
    }

    @Test fun rateLimitResponseCarriesSafeDelayIntoSharedBackoff() = runBlocking {
        var now = 1_000L
        val backoff = ProviderBackoff { now }
        val call = FakeCall()
        val result = async(start = CoroutineStart.UNDISPATCHED) { runCatching { call.awaitResponseBody() } }
        call.respond(429, "private provider detail", "75")
        val error = result.await().exceptionOrNull() as GeminiHttpException
        assertEquals(429, error.statusCode)
        backoff.rateLimited(error.retryAfterMs)
        assertTrue(runCatching { backoff.checkAllowed() }.exceptionOrNull() is ProviderCooldownException)
        now += 75_000
        backoff.checkAllowed()
        assertFalse(error.toString().contains("private provider detail"))
    }
}
