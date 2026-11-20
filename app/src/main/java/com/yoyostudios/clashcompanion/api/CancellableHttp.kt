package com.yoyostudios.clashcompanion.api

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/** A cancelled tactical request must release its HTTP exchange and any response body. */
internal suspend fun Call.awaitResponseBody(): String = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }

        override fun onResponse(call: Call, response: Response) {
            if (!continuation.isActive) {
                response.close()
                return
            }
            try {
                val body = response.use {
                    if (!it.isSuccessful) throw GeminiHttpException(
                        it.code, ProviderBackoff.parseRetryAfter(it.header("Retry-After"))
                    )
                    it.body?.string().orEmpty()
                }
                if (continuation.isActive) continuation.resume(body)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    })
}

/** Provider response bodies can contain sensitive diagnostics; expose only the status code. */
class GeminiHttpException(val statusCode: Int, val retryAfterMs: Long? = null) : IOException("Gemini: HTTP $statusCode")
